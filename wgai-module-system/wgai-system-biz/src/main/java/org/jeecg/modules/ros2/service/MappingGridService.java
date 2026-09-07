package org.jeecg.modules.ros2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 建图占据栅格累积器 —— 让「保存地图」不再需要杀 fast_lio。
 *
 * ─── 要解决的问题 ─────────────────────────────────────────────────────────
 * 老流程是一条死锁链：
 *     PCD 只有 SIGINT 才落盘 → 必须杀 fast_lio → camera_init 消失
 *         → Nav2 全废 → 只能整栈重启，保存完还得等 20 多秒才能导航
 * 只要保存还依赖「杀进程落 PCD」，这个等待就省不掉。
 *
 * ─── 为什么可以绕开 PCD ───────────────────────────────────────────────────
 * Java 本来就在实时收 /cloud_registered 的**全量**点(见 ROS2WebSocketHandler
 * #handlePointCloud，抽稀只发生在推前端那一步之后)，而且这些点就在 camera_init 系，
 * 和 fast_lio 写进 PCD 的坐标系完全一致。也就是说 PCD 里有的信息这里都有，
 * 边收边往栅格上打即可，保存时直接把栅格写成 pgm，全程不碰 fast_lio 进程。
 *
 * ─── 已知取舍 ─────────────────────────────────────────────────────────────
 * Java 断连或重启期间的点收不到，这段会缺；fast_lio 自己内存里的 PCD 则始终完整。
 * 所以保留了 PCD 回退路径(application.yml: mapping.save-mode=pcd)，
 * 现场发现栅格图不对可以立刻切回去。
 *
 * ─── 分辨率 ───────────────────────────────────────────────────────────────
 * 栅格在累积时就定死了分辨率，保存时无法再变细。/save 传的 resolution 和它不一致时
 * 只记 warn 并按累积值输出，不做插值(插出来的精度是假的)。
 */
@Slf4j
@Service
public class MappingGridService {

    /** 栅格分辨率(米/格)。累积时定死，保存时不可变更 */
    @Value("${mapping.grid.resolution:0.05}")
    private double resolution;

    /**
     * 累积时的高度门限(camera_init 系的 z，原点是雷达开机位置)。
     * 放得比导航用的高度带宽，是为了保存时还能按 zMin/zMax 再筛一次。
     */
    @Value("${mapping.grid.z-min:-5.0}")
    private double acceptZMin;

    @Value("${mapping.grid.z-max:10.0}")
    private double acceptZMax;

    /**
     * 一个格子至少要被多少**帧**观测到才算真障碍，用来滤掉建图时走过的人。
     *
     * 订阅端节流 200ms(5Hz)，人以 1m/s 走，每帧跨 0.2m = 4 个格子，
     * 所以人在任一格子上基本只留下 1 帧；墙面/立柱这类静态结构会被连续几十上百帧看到。
     * 3 已经足够把行人切干净，同时不至于误伤"车路过时只瞥了两眼"的远处真实障碍。
     */
    @Value("${mapping.grid.min-hits:3}")
    private int minHits;

    /**
     * z 分层位图：起点 -5.0m，层厚 0.05m，320 层(5 个 long)覆盖 -5.0 ~ +11.0m。
     *
     * ⚠ 层厚必须细到和栅格分辨率同量级(0.05m)，绝不能图省事用一个 long 凑 64 层。
     *   早期是 `Z_BASE=-8.0, Z_BUCKET=0.25`(单 long)，后果是 zMask() 把用户填的
     *   [zMin,zMax] **向外取整到整层边界**：填 zMax=2.0 → 落在层 [2.00,2.25) → 实际按 2.25 筛；
     *   填 zMin=-0.9 → 落在层 [-1.00,-0.75) → 实际按 -1.0 筛，地面照样漏进来。
     *   而前端点云预览是逐点精确比较 `z <= zMax`，于是两边最多差一整层 0.25m。
     *   现场表现极其明确：**zMax 填 2.0 出图凭空多一大片、填 1.9 就和预览一致**
     *   —— 那片东西的 z 就在 [2.00,2.25) 这一层里。
     *   改成 0.05m 后取整误差和栅格分辨率同量级，等于精确。
     *
     * 内存代价：每格 7 个 long = 56B。5 万格才 2.8MB，百万格也只有 56MB，不必省。
     */
    private static final double Z_BASE   = -5.0;
    private static final double Z_BUCKET = 0.05;
    /** 位图占的 long 个数；层数 = Z_WORDS * 64 */
    private static final int    Z_WORDS  = 5;
    private static final int    Z_LEVELS = Z_WORDS * 64;   // 320 层 × 0.05m = 16m

    /** cells 的 value 里，帧数和末次帧号的下标(紧跟在 Z_WORDS 个位图之后) */
    private static final int    IDX_FRAMES = Z_WORDS;
    private static final int    IDX_LASTFS = Z_WORDS + 1;
    private static final int    CELL_LEN   = Z_WORDS + 2;

    /**
     * key   = 栅格坐标打包 (ix << 32) | (iy & 0xFFFFFFFFL)
     * value = [z 分层位图 ×Z_WORDS, 观测到的帧数, 最后一次计数的帧号]
     *
     * ⚠ 前 Z_WORDS 位必须是**分层位图**，不能退回成 [最低z, 最高z] 区间。
     *   区间的判据是「格子 z 区间与 [zMin,zMax] 有交集」，而前端预览和 PCD 那版都是
     *   「存在某个点落在区间内」——两者对地面格子的结果正好相反：
     *   地面格子本来是 [-1.05,-0.95]，只要**曾经**被一个高处的点打中过(车经过时扫到
     *   货架上沿、一个噪声点)，区间就被撑成 [-1.05,+1.4]，于是不论 zMin 设多高都判交集成立
     *   → 整片地面被画成障碍。稀疏点云里这种污染非常普遍，不是小概率。
     *   位图逐层记录"这一层到底有没有点"，才是真正的逐点语义。
     *
     * ⚠ IDX_FRAMES 必须是**帧**数不是**点**数：人身上一帧就有十几个点落在同一格，
     *   按点计数 hits 会虚高到过不掉 minHits，行人过滤直接失效。
     *   靠 IDX_LASTFS 记的帧号做到"同一帧内同一格只计一次"。
     */
    private final ConcurrentHashMap<Long, long[]> cells = new ConcurrentHashMap<>();

    /** 帧序号，每收到一帧点云 +1。只用于判"是不是同一帧"，溢出无所谓 */
    private final java.util.concurrent.atomic.AtomicInteger frameSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 是否正在累积。由 /start 打开、/cancel 关闭。
     *
     * ⚠ 绝对不要改回去挂 ROS2WebSocketHandler 的 navMode：那是 handler 的实例字段，
     *   而 ROS2BridgeService.connect() 每次重连都会 new 一个新 handler，navMode 直接被
     *   重置回默认的 true，累积就此静默停掉 —— 现象是点云日志一切正常("原始1249点 →
     *   推送1055点")，保存时却报"尚未累积到任何点云"。这个 service 是单例，不受重连影响。
     */
    private volatile boolean enabled = false;

    private volatile long totalPointsSeen = 0L;
    private volatile long lastUpdateMs    = 0L;

    /**
     * 人工擦除的矩形区域（世界坐标，米），元素为 {minX, minY, maxX, maxY}。
     *
     * 建图时躲不掉的东西（走过的人、临时堆的料、正在开的门）会实打实地留在栅格里，
     * minHits 只能滤掉"路过一两帧"的，站着不动看了半分钟的人是滤不掉的 —— 只能人工圈掉。
     *
     * ⚠ 故意**不**直接 cells.remove()，而是在 render() 里跳过：
     *   ① 可撤销。误擦一块墙不至于要整张图重扫。
     *   ② 擦完还在继续扫，如果只是删格子，下一帧同一个地方又被打上点，擦了等于没擦
     *      （现象是"擦掉了、过两秒又冒出来"）。矩形是持久的，扫多久都不会复活。
     * 代价：这块区域后来即使有真障碍也画不出来。所以擦之前要确认是空地，
     *   前端擦除框是红色高亮 + 有撤销/清空，就是为了让人能反悔。
     */
    private final java.util.List<double[]> erasedRects =
            java.util.Collections.synchronizedList(new java.util.ArrayList<double[]>());

    // ======================== 累积 ========================

    public void setEnabled(boolean on) {
        if (this.enabled != on) {
            log.info("[建图栅格] 累积开关 → {}（当前 {} 个占据格）", on ? "开" : "关", cells.size());
        }
        this.enabled = on;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 喂一帧点云。必须传**抽稀之前**的全量点。
     *
     * @param raw         PointCloud2 的 data 字段(已 base64 解码)
     * @param pointStep   每点字节数
     * @param totalPoints 点数
     * @param xOff/yOff/zOff 各字段在点内的字节偏移
     */
    public void updateFromCloud(byte[] raw, int pointStep, int totalPoints,
                                int xOff, int yOff, int zOff) {
        if (!enabled) return;
        if (raw == null || pointStep <= 0 || totalPoints <= 0) return;

        double invRes = 1.0 / resolution;
        int accepted = 0;
        // 每帧一个序号，供下面"同一帧内同一格只计一次"用
        final int fs = frameSeq.incrementAndGet();

        for (int i = 0; i < totalPoints; i++) {
            int base = i * pointStep;
            if (base + pointStep > raw.length) break;

            float z = readFloat(raw, base + zOff);
            // z 的上下界顺带把 ±Infinity 挡掉了；x/y 没有界，必须显式判
            if (Float.isNaN(z) || z < acceptZMin || z > acceptZMax) continue;

            float x = readFloat(raw, base + xOff);
            float y = readFloat(raw, base + yOff);
            // Infinite 的 x/y 会算出极端 ix/iy，把出图边界整个撑开(表现为地图尺寸异常)
            if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) continue;

            int ix = (int) Math.floor(x * invRes);
            int iy = (int) Math.floor(y * invRes);
            long key = pack(ix, iy);
            final int b = zBucket(z);

            // compute 而不是 get+put：多个 rosbridge 消息可能并发进来
            cells.compute(key, (k, v) -> {
                if (v == null) {
                    v = new long[CELL_LEN];
                    v[IDX_FRAMES] = 1;
                    v[IDX_LASTFS] = fs;
                    v[b >> 6] = 1L << (b & 63);
                    return v;
                }
                v[b >> 6] |= 1L << (b & 63);
                // 同一帧里这个格子已经计过了就不再累加，保证 IDX_FRAMES 是帧数而不是点数
                if (v[IDX_LASTFS] != fs) { v[IDX_FRAMES]++; v[IDX_LASTFS] = fs; }
                return v;
            });
            accepted++;
        }

        totalPointsSeen += accepted;
        lastUpdateMs = System.currentTimeMillis();
    }

    /** 丢弃已累积的一切，重新开始。不影响 fast_lio，纯内存操作，瞬时 */
    public void clear() {
        int n = cells.size();
        cells.clear();
        // 擦除区是针对上一轮那张图圈的，重扫后世界坐标对应的东西已经不一样了，必须一起丢
        erasedRects.clear();
        totalPointsSeen = 0L;
        log.info("[建图栅格] 已清空，丢弃 {} 个占据格", n);
    }

    public int cellCount() { return cells.size(); }

    // ======================== 人工擦除 ========================

    /**
     * 圈掉一块区域，出图时当它不存在。传世界坐标(米)，两个角点顺序随意。
     *
     * @return 这一刀实际盖住了多少个已累积的占据格，前端拿它提示"擦掉了 N 格"。
     *         为 0 说明框歪了或者框在空地上，比静默成功有用得多。
     */
    public int addErase(double x1, double y1, double x2, double y2) {
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        erasedRects.add(new double[]{minX, minY, maxX, maxY});

        int[] r = toGridRect(new double[]{minX, minY, maxX, maxY});
        int n = 0;
        for (Long k : cells.keySet()) {
            int ix = unpackX(k), iy = unpackY(k);
            if (ix >= r[0] && ix <= r[2] && iy >= r[1] && iy <= r[3]) n++;
        }
        log.info("[建图栅格] 擦除区域 [{}, {}] ~ [{}, {}]m，盖住 {} 个占据格（共 {} 块擦除区）",
                String.format("%.2f", minX), String.format("%.2f", minY),
                String.format("%.2f", maxX), String.format("%.2f", maxY), n, erasedRects.size());
        return n;
    }

    /** 撤销最后一次擦除 */
    public boolean undoErase() {
        synchronized (erasedRects) {
            if (erasedRects.isEmpty()) return false;
            erasedRects.remove(erasedRects.size() - 1);
        }
        log.info("[建图栅格] 撤销一块擦除区，剩 {} 块", erasedRects.size());
        return true;
    }

    /** 清空所有擦除区，恢复成原始扫描结果 */
    public int clearErase() {
        int n = erasedRects.size();
        erasedRects.clear();
        log.info("[建图栅格] 已清空全部 {} 块擦除区", n);
        return n;
    }

    /** 擦除区列表(世界坐标)，供前端把红框画回画布上 */
    public java.util.List<double[]> getErasedRects() {
        synchronized (erasedRects) { return new java.util.ArrayList<>(erasedRects); }
    }

    /** 世界坐标矩形 → 栅格下标范围 {ix0, iy0, ix1, iy1}(闭区间) */
    private int[] toGridRect(double[] w) {
        double invRes = 1.0 / resolution;
        return new int[]{
                (int) Math.floor(w[0] * invRes), (int) Math.floor(w[1] * invRes),
                (int) Math.floor(w[2] * invRes), (int) Math.floor(w[3] * invRes)
        };
    }

    private static boolean inAnyRect(int ix, int iy, int[][] rects) {
        for (int[] r : rects) {
            if (ix >= r[0] && ix <= r[2] && iy >= r[1] && iy <= r[3]) return true;
        }
        return false;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",         enabled);
        m.put("cells",           cells.size());
        m.put("pointsAccepted",  totalPointsSeen);
        m.put("resolution",      resolution);
        m.put("minHits",         minHits);
        m.put("erasedRects",     erasedRects.size());
        // 保存前就能看到该往 zMin/zMax 里填什么，不用等出图后再猜
        long[] gMask = new long[Z_WORDS];
        for (long[] c : cells.values()) orInto(gMask, c);
        boolean none = isEmptyMask(gMask);
        m.put("actualZMin",      none ? null : maskLowZ(gMask));
        m.put("actualZMax",      none ? null : maskHighZ(gMask));
        m.put("lastUpdateMs",    lastUpdateMs);
        m.put("estimatedAreaM2", cells.size() * resolution * resolution);
        return m;
    }

    // ======================== 导出 ========================

    /**
     * 一次出图的结果：像素数组 + 地理信息 + 三类去向的统计。
     *
     * ⚠ 保存(saveAsPgm)和预览(renderPreview)必须共用它，绝不能各写一份过滤逻辑。
     *   以前前端预览画的是自己攒的点云(还带体素粗化)，后端出图是全量点+minHits+zMask，
     *   两套数据两套规则，"预览干净、存出来一堆黑块"就是这么来的。
     */
    private static final class Rendered {
        byte[] img;              // 255=空闲, 0=占据，行优先，已按 pgm 的上下翻转存好
        int w, h;
        double originX, originY; // 左下角(minIx,minIy)对应的世界坐标，米
        int hit, dropped, zDropped, erased;
        double gZLo, gZHi;
        int hitsUsed;
    }

    /** 出图用的帧数门限：调用方传 &lt;=0 就退回 application.yml 里的 mapping.grid.min-hits */
    private int effectiveHits(int hitsThreshold) {
        return hitsThreshold > 0 ? hitsThreshold : minHits;
    }

    /**
     * 把当前栅格按 [zMin,zMax] 渲染成像素。纯内存操作，不碰任何进程、不写文件。
     * 保存和预览都走这里，保证所见即所得。
     */
    private Rendered render(double zMin, double zMax, int hitsThreshold) {
        if (cells.isEmpty()) {
            throw new IllegalStateException("尚未累积到任何点云（累积开关=" + enabled + "）。"
                    + "请先点「开始建图」再扫一段时间；可用 GET /ros2/mapping/status 看 grid.cells 是否在涨");
        }

        // [zMin,zMax] → 分层位图的层号区间。层厚 0.05m，取整误差已小到可以忽略
        int loB = zBucket(zMin), hiB = zBucket(zMax);
        if (hiB < loB) { int t = loB; loB = hiB; hiB = t; }

        // 先扫一遍定边界，避免为整张地图预分配一个过大的数组
        int minIx = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE;
        int minIy = Integer.MAX_VALUE, maxIy = Integer.MIN_VALUE;
        int hit = 0, transient_ = 0, zDropped = 0, erased = 0;
        // 人工擦除区先换算成栅格下标，避免在百万级循环里反复做浮点除法
        int[][] rects;
        synchronized (erasedRects) {
            rects = new int[erasedRects.size()][];
            for (int i = 0; i < rects.length; i++) rects[i] = toGridRect(erasedRects.get(i));
        }

        // 顺带统计全局 z 分布，好在日志里直接告诉用户 zMin/zMax 该怎么填
        long[] gMask = new long[Z_WORDS];
        for (Map.Entry<Long, long[]> e : cells.entrySet()) {
            long[] c = e.getValue();
            orInto(gMask, c);
            int ix = unpackX(e.getKey());
            int iy = unpackY(e.getKey());
            // 擦除要放在最前面：被人工圈掉的格子不该再参与边界计算，
            // 否则擦掉角落那一簇之后地图尺寸还是老样子，白留一大片空白
            if (inAnyRect(ix, iy, rects)) { erased++; continue; }
            if (!hasPointIn(c, loB, hiB)) { zDropped++; continue; }        // 这段高度里一个点都没有
            if (c[IDX_FRAMES] < hitsThreshold) { transient_++; continue; } // 观测帧数不够，当行人/动态物滤掉
            if (ix < minIx) minIx = ix;
            if (ix > maxIx) maxIx = ix;
            if (iy < minIy) minIy = iy;
            if (iy > maxIy) maxIy = iy;
            hit++;
        }
        double gZLo = maskLowZ(gMask), gZHi = maskHighZ(gMask);

        // 三类去向全部打出来。曾经 z 过滤是静默的，出图只剩 12% 却查不出砍在哪一步
        log.info("[建图栅格] 出图统计: 累积 {} 格 → 人工擦除 {} 块砍掉 {} 格 → z过滤[{}, {}]m 砍掉 {} 格 "
                        + "→ 帧数<{} 砍掉 {} 格 → 实际出图 {} 格。"
                        + "点云实际 z 范围 [{}, {}]m（原点=雷达开机位置，离地约 1m，所以地面在 z≈-1）",
                cells.size(), rects.length, erased, zMin, zMax, zDropped, hitsThreshold, transient_, hit,
                String.format("%.2f", gZLo), String.format("%.2f", gZHi));
        if (hit > 0 && zDropped > hit) {
            log.warn("[建图栅格] ⚠ z 过滤砍掉了 {}%，地图会明显偏稀疏。"
                            + "保存对话框里的 Z 范围要按上面那个实际 z 范围来填，别按离地高度填",
                    zDropped * 100 / cells.size());
        }
        if (hit == 0) {
            throw new IllegalStateException("过滤后没有剩下任何格子。点云实际 z 范围是 ["
                    + String.format("%.2f", gZLo) + ", " + String.format("%.2f", gZHi)
                    + "]m，而你填的是 [" + zMin + ", " + zMax + "]m（z 原点是雷达开机位置不是地面）。"
                    + "按前者调整；若是刚开始扫就保存，则调小「动态物过滤」帧数(当前 " + hitsThreshold + ")");
        }

        // 四周留 1m 空白，和原 PCD 转换脚本的 MARGIN 保持一致
        int margin = (int) Math.ceil(1.0 / resolution);
        minIx -= margin; minIy -= margin;
        maxIx += margin; maxIy += margin;

        int w = maxIx - minIx + 1;
        int h = maxIy - minIy + 1;
        if ((long) w * h > 200_000_000L) {
            throw new IllegalStateException("地图尺寸异常: " + w + "x" + h
                    + " px，多半是有离群点把边界撑开了，请检查 zMin/zMax");
        }

        // 255=空闲, 0=占据。和原脚本一致：没有点的格子一律算空闲，不区分 unknown
        byte[] img = new byte[w * h];
        java.util.Arrays.fill(img, (byte) 255);
        for (Map.Entry<Long, long[]> e : cells.entrySet()) {
            long[] c = e.getValue();
            int cx = unpackX(e.getKey()), cy = unpackY(e.getKey());
            // 三个判据必须和上面那趟边界统计**完全一致**，否则会出现"统计说出图 N 格、
            // 画出来却不是那 N 格"，又是一轮预览/保存对不上
            if (inAnyRect(cx, cy, rects)) continue;
            if (!hasPointIn(c, loB, hiB)) continue;
            if (c[IDX_FRAMES] < hitsThreshold) continue;
            int px = cx - minIx;
            // pgm 是从上往下存的，y 轴要翻过来
            int py = h - 1 - (cy - minIy);
            if (px >= 0 && px < w && py >= 0 && py < h) {
                img[py * w + px] = 0;
            }
        }

        Rendered out = new Rendered();
        out.img      = img;
        out.w        = w;
        out.h        = h;
        out.originX  = minIx * resolution;
        out.originY  = minIy * resolution;
        out.hit      = hit;
        out.dropped  = transient_;
        out.zDropped = zDropped;
        out.erased   = erased;
        out.gZLo     = gZLo;
        out.gZHi     = gZHi;
        out.hitsUsed = hitsThreshold;
        return out;
    }

    /** 把 Rendered 的统计塞进返回体，保存和预览的字段保持一致 */
    private Map<String, Object> statsOf(Rendered g) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("widthPx",        g.w);
        r.put("heightPx",       g.h);
        r.put("occupiedCells",  g.hit);
        r.put("filteredCells",  g.dropped);
        r.put("zDroppedCells",  g.zDropped);
        r.put("erasedCells",    g.erased);
        r.put("erasedRects",    getErasedRects());
        r.put("totalCells",     cells.size());
        r.put("actualZMin",     g.gZLo);
        r.put("actualZMax",     g.gZHi);
        r.put("minHits",        g.hitsUsed);
        r.put("resolution",     resolution);
        r.put("originX",        g.originX);
        r.put("originY",        g.originY);
        return r;
    }

    /**
     * 把当前栅格写成 Nav2 能直接加载的 pgm + yaml。纯内存 → 文件，秒级，不碰任何进程。
     *
     * @param outPath 不含扩展名的输出路径，如 /home/ros/maps/map_1
     * @param zMin/zMax 高度筛选(camera_init 系的 z)
     * @param hitsThreshold 动态物过滤的帧数门限，&lt;=0 用配置默认值
     * @return 结果概要(尺寸、原点、占据格数)
     */
    public Map<String, Object> saveAsPgm(String outPath, double zMin, double zMax, int hitsThreshold)
            throws IOException {
        Rendered g = render(zMin, zMax, effectiveHits(hitsThreshold));

        Files.createDirectories(Paths.get(outPath).getParent());
        String pgmPath  = outPath + ".pgm";
        String yamlPath = outPath + ".yaml";

        // P5 二进制 pgm。不用 ImageIO：它不支持 pgm 写出
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(pgmPath))) {
            os.write(("P5\n" + g.w + " " + g.h + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            os.write(g.img);
        }

        String yaml = "image: " + pgmPath + "\n"
                + "resolution: " + resolution + "\n"
                + String.format("origin: [%.4f, %.4f, 0.0]%n", g.originX, g.originY)
                + "negate: 0\n"
                + "occupied_thresh: 0.65\n"
                + "free_thresh: 0.196\n";
        Files.write(Paths.get(yamlPath), yaml.getBytes(StandardCharsets.UTF_8));

        log.info("[建图栅格] ✅ 已导出 {}x{} px, 占据格 {}, 原点({}, {}), 分辨率 {}",
                g.w, g.h, g.hit, String.format("%.2f", g.originX), String.format("%.2f", g.originY), resolution);

        Map<String, Object> r = statsOf(g);
        r.put("pgm",  pgmPath);
        r.put("yaml", yamlPath);
        return r;
    }

    /**
     * 出图预览：和 saveAsPgm 走同一个 render()，所以画出来的和保存下来的 pgm 逐像素一致。
     *
     * 返回 base64 PNG 而不是直接返回图片流，是为了把 origin/resolution 一起带给前端 ——
     * 前端要按世界坐标把它叠在点云上，没有这几个数就对不齐。
     * 占据格画成不透明白色、空闲格**透明**，方便叠在深色画布和点云之上直接对比。
     */
    public Map<String, Object> renderPreview(double zMin, double zMax, int hitsThreshold) throws IOException {
        Rendered g = render(zMin, zMax, effectiveHits(hitsThreshold));

        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(g.w, g.h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[g.w * g.h];
        for (int i = 0; i < px.length; i++) {
            px[i] = (g.img[i] == 0) ? 0xFFFFFFFF : 0x00000000;
        }
        bi.setRGB(0, 0, g.w, g.h, px, 0, g.w);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", bos);

        Map<String, Object> r = statsOf(g);
        r.put("png", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bos.toByteArray()));
        return r;
    }

    public double getResolution() { return resolution; }

    /**
     * 累积时的高度门限。ROS2WebSocketHandler 推点云给前端时必须用同一对值 ——
     * 前端能看到的范围一旦比这里窄，那段高度就成了盲区：预览里干干净净，
     * 保存出来却凭空多一片(地面点就是这么漏进去的)。
     */
    public double getAcceptZMin() { return acceptZMin; }

    public double getAcceptZMax() { return acceptZMax; }

    // ======================== 工具 ========================

    /** z(米) → 分层位图的层号 0..Z_LEVELS-1，超出范围的钳到两端 */
    private static int zBucket(double z) {
        int b = (int) Math.floor((z - Z_BASE) / Z_BUCKET);
        return b < 0 ? 0 : (b >= Z_LEVELS ? Z_LEVELS - 1 : b);
    }

    /** 这个格子在 [loB,hiB] 这段层里到底有没有点。位图跨多个 long，只能逐 word 判 */
    private static boolean hasPointIn(long[] cell, int loB, int hiB) {
        int loW = loB >> 6, hiW = hiB >> 6;
        for (int w = loW; w <= hiW; w++) {
            long m = -1L;
            // 首尾两个 word 只取区间内的那几位，中间的整个 word 都算
            if (w == loW) m &= -1L << (loB & 63);
            if (w == hiW) {
                int t = hiB & 63;
                m &= (t == 63) ? -1L : ((1L << (t + 1)) - 1);
            }
            if ((cell[w] & m) != 0) return true;
        }
        return false;
    }

    /** 把 cell 的位图或进 dst，用来统计全局 z 分布 */
    private static void orInto(long[] dst, long[] cell) {
        for (int w = 0; w < Z_WORDS; w++) dst[w] |= cell[w];
    }

    private static boolean isEmptyMask(long[] mask) {
        for (int w = 0; w < Z_WORDS; w++) if (mask[w] != 0) return false;
        return true;
    }

    /** 位图里最低那层的下沿高度(米) */
    private static double maskLowZ(long[] mask) {
        for (int w = 0; w < Z_WORDS; w++) {
            if (mask[w] != 0) return Z_BASE + ((w << 6) + Long.numberOfTrailingZeros(mask[w])) * Z_BUCKET;
        }
        return Double.NaN;
    }

    /** 位图里最高那层的上沿高度(米) */
    private static double maskHighZ(long[] mask) {
        for (int w = Z_WORDS - 1; w >= 0; w--) {
            if (mask[w] != 0) return Z_BASE + ((w << 6) + 64 - Long.numberOfLeadingZeros(mask[w])) * Z_BUCKET;
        }
        return Double.NaN;
    }

    private static long pack(int ix, int iy) {
        return ((long) ix << 32) | (iy & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) { return (int) (key >> 32); }

    private static int unpackY(long key) { return (int) key; }

    private static float readFloat(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }
}
