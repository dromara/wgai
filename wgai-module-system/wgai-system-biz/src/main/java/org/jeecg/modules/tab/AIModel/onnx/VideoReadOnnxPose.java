package org.jeecg.modules.tab.AIModel.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.track.ByteTracker;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.utils.Converters;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jeecg.modules.demo.audio.util.video.videoIdentifyTypeNewOnnx.isPointInArea;
import static org.jeecg.modules.demo.video.util.identifyTypeNewOnnx.letterboxResize;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColorsVue;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * @author wggg
 * @date 2025/10/31 15:16
 *
 * v3 重构说明：
 *
 * 根本原因分析（来自日志 trackId 41-47 全是同一人）：
 *   旧设计依赖 ByteTracker.TrackResult 的坐标存快照，但 ByteTracker 的 Kalman 滤波器
 *   在 300ms 大帧间隔下预测严重漂移，快照位置与下帧实际检测位置偏差被放大，
 *   导致 findLostMatch 在 MAX_MATCH_DIST=150px 附近频繁 miss（实际移动 136-180px）。
 *
 * v3 解法：
 *   1. PermanentIdMapper 完全不再依赖 ByteTracker 的坐标，改为直接使用检测框坐标
 *   2. 新方法 assignPermIds(List<DetCache>) 用贪心最近邻（Greedy-NN）一次完成全帧分配：
 *      - 计算所有 (检测框, 历史快照) 对的欧式距离
 *      - 按距离升序排序，优先匹配最近的对（近似 Hungarian）
 *      - 已认领的检测框/快照跳过（防止多对多争抢）
 *   3. MAX_MATCH_DIST 提升到 300px，dynThresh = 0.5×max(w,h)（对 500px 高的人≈250px）
 *   4. 快照存储真实检测坐标，而非 Kalman 预测坐标
 *   5. ByteTracker 仍运行（保留以备扩展），但不参与 permId 分配
 */
@Slf4j
public class VideoReadOnnxPose implements Runnable {

    private RedisTemplate redisTemplate;
    public Integer TARGET_FRAME_INTERVAL = 300;
    public String videoUrl;
    public String userId;
    private volatile long lastFrameTime = 0;
    public String namesUrl;
    public String cfgUrl;
    public String weightUrl;
    public WebSocket webSocket;
    public TabAiModelBund tabAiModelBund;
    public TabAudioDevice tabAudioDevice;
    NetPush netpush;
    String uuid;

    private final Java2DFrameConverter converter = new Java2DFrameConverter();

    public VideoReadOnnxPose(NetPush netpush, TabAudioDevice tabAudioDevice,
                             TabAiModelBund tabAiModelBund, String videoUrl,
                             RedisTemplate redisTemplate, String userId,
                             String namesUrl, WebSocket webSocket) {
        this.videoUrl = videoUrl;
        this.redisTemplate = redisTemplate;
        this.userId = userId;
        this.namesUrl = namesUrl;
        this.webSocket = webSocket;
        this.netpush = netpush;
        this.tabAiModelBund = tabAiModelBund;
        this.tabAudioDevice = tabAudioDevice;
    }

    // =========================================================================
    // PermanentIdMapper v3
    //
    // 设计原则：
    //   - 完全使用检测框坐标（非 Kalman 预测坐标）存快照和做匹配
    //   - 贪心最近邻分配，O(n² + n²log n)，n 一般 < 50，完全可接受
    //   - MAX_MATCH_DIST=300px，动态阈值 0.5×max(w,h) ≤ 300px
    //     对 500px 高人体 → 250px；对 200px 高小人 → 100px
    // =========================================================================
    private static class PermanentIdMapper {

        private final AtomicInteger permIdCounter = new AtomicInteger(1);

        /** permId → snapshot，LinkedHashMap 保持插入顺序（遍历稳定）*/
        private final Map<Integer, LostSnapshot> recentLost = new LinkedHashMap<>();

        /** 快照保留帧数：20 × 300ms = 6s */
        private static final int   LOST_KEEP_FRAMES = 20;
        /** 动态阈值基础值 */
        private static final float BASE_MATCH_DIST  = 80f;
        /** 动态阈值系数：max(w,h) × DYNAMIC_FACTOR，适应近大远小 */
        private static final float DYNAMIC_FACTOR   = 0.5f;
        /** 动态阈值硬上限，防止超远程误匹配 */
        private static final float MAX_MATCH_DIST   = 300f;

        static class LostSnapshot {
            int   permId;
            float cx, cy, w, h;
            int   framesLost;
        }

        /**
         * 核心方法：一次完成整帧的 permId 分配。
         *
         * 算法：
         *   1. 老化快照
         *   2. 构建 (检测索引, 快照permId, 距离) 候选对，按距离排序
         *   3. 贪心分配：距离最小的对优先认领，已认领的跳过
         *   4. 未匹配的检测 → 新 permId
         *   5. 用检测框坐标更新快照
         *
         * @param cache NMS 后的检测框列表
         * @return 与 cache 同索引的 permId 列表
         */
        List<Integer> assignPermIds(List<DetCache> cache) {
            // ── 1. 老化所有快照 ────────────────────────────────────────────
            recentLost.values().removeIf(s -> ++s.framesLost > LOST_KEEP_FRAMES);

            int n = cache.size();
            int[] result = new int[n];
            Arrays.fill(result, -1);

            // ── 2. 构建候选对（检测 i ↔ 快照 permId，距离 < 动态阈值）──────
            if (!recentLost.isEmpty()) {
                // [detIdx, permId, dist*1000（整数，用于排序）]
                List<int[]> candidates = new ArrayList<>();

                for (int i = 0; i < n; i++) {
                    DetCache c   = cache.get(i);
                    float    cx  = (float)(c.box.x + c.box.width  / 2);
                    float    cy  = (float)(c.box.y + c.box.height / 2);
                    float    w   = (float) c.box.width;
                    float    h   = (float) c.box.height;
                    // 动态阈值：目标越大允许偏移越多；硬上限 300px
                    float thresh = Math.min(MAX_MATCH_DIST,
                            Math.max(BASE_MATCH_DIST, Math.max(w, h) * DYNAMIC_FACTOR));

                    for (LostSnapshot s : recentLost.values()) {
                        float dist = (float) Math.hypot(cx - s.cx, cy - s.cy);
                        if (dist < thresh) {
                            candidates.add(new int[]{i, s.permId, (int)(dist * 1000)});
                        }
                    }
                }

                // ── 3. 按距离升序排序，贪心认领 ───────────────────────────
                candidates.sort((a, b) -> a[2] - b[2]);

                Set<Integer> claimedDets  = new HashSet<>();
                Set<Integer> claimedPerms = new HashSet<>();

                for (int[] cand : candidates) {
                    int di     = cand[0];
                    int permId = cand[1];
                    if (claimedDets.contains(di) || claimedPerms.contains(permId)) continue;
                    result[di] = permId;
                    claimedDets.add(di);
                    claimedPerms.add(permId);
                    log.debug("[重用ID] det[{}] cx={} → permId={} dist={}px",
                            di,
                            (int)(cache.get(di).box.x + cache.get(di).box.width / 2),
                            permId, cand[2] / 1000);
                }
            }

            // ── 4. 未匹配的检测 → 新 permId ───────────────────────────────
            for (int i = 0; i < n; i++) {
                if (result[i] == -1) {
                    result[i] = permIdCounter.getAndIncrement();
                    log.debug("[新目标] det[{}] → permId={}", i, result[i]);
                }
            }

            // ── 5. 用检测坐标更新快照（framesLost=0，位置=检测框中心）─────
            //      注意：这里存的是真实检测坐标，不是 Kalman 预测坐标
            for (int i = 0; i < n; i++) {
                DetCache     c     = cache.get(i);
                int          permId = result[i];
                LostSnapshot s     = recentLost.computeIfAbsent(permId, k -> new LostSnapshot());
                s.permId     = permId;
                s.cx         = (float)(c.box.x + c.box.width  / 2);
                s.cy         = (float)(c.box.y + c.box.height / 2);
                s.w          = (float) c.box.width;
                s.h          = (float) c.box.height;
                s.framesLost = 0;
            }

            // 把 int[] 转成 List<Integer> 返回
            List<Integer> permIds = new ArrayList<>(n);
            for (int v : result) permIds.add(v);
            return permIds;
        }

        /** 无检测帧：仅老化快照，避免空场景后旧快照无限留存 */
        void tickEmpty() {
            recentLost.values().removeIf(s -> ++s.framesLost > LOST_KEEP_FRAMES);
        }
    }

    private final Map<String, PermanentIdMapper> idMapperMap = new ConcurrentHashMap<>();
    private PermanentIdMapper getIdMapper(NetPush netPush) {
        return idMapperMap.computeIfAbsent(netPush.getId().toString(),
                k -> new PermanentIdMapper());
    }

    // =========================================================================
    // ByteTracker（保留运行，不再用于 permId 分配）
    // =========================================================================
    private final Map<String, ByteTracker> trackerMap = new ConcurrentHashMap<>();
    private ByteTracker getTracker(NetPush netPush) {
        return trackerMap.computeIfAbsent(netPush.getId().toString(), k -> new ByteTracker());
    }

    // =========================================================================
    // 主循环
    // =========================================================================
    @Override
    public void run() {
        FFmpegFrameGrabber grabber = null;
        int consecutiveNullFrames  = 0;
        long streamOpenStartMs = System.currentTimeMillis();
        boolean firstImageLogged = false;
        try {
            grabber = createOptimizedGrabber();
            log.info("[视频流打开完成] 耗时={}ms, 地址={}", System.currentTimeMillis() - streamOpenStartMs, videoUrl);
            while (true) {
                if (!isStreamActive()) { log.warn("[主动停止推送]{}", uuid); break; }

                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (++consecutiveNullFrames > 10) {
                        log.info("[连续空帧过多，重启视频流]");
                        streamOpenStartMs = System.currentTimeMillis();
                        grabber = restartGrabber(grabber);
                        log.info("[视频流重连完成] 耗时={}ms, 地址={}", System.currentTimeMillis() - streamOpenStartMs, videoUrl);
                        firstImageLogged = false;
                        consecutiveNullFrames = 0;
                    }
                    Thread.sleep(100);
                    continue;
                }
                consecutiveNullFrames = 0;
                if (!firstImageLogged) {
                    long firstFrameCostMs = System.currentTimeMillis() - streamOpenStartMs;
                    log.info("[首帧图像已读取] 耗时={}ms, 流时间戳={}ms", firstFrameCostMs, grabber.getTimestamp() / 1000L);
                    if (firstFrameCostMs > 3000) {
                        log.warn("[首帧图像读取较慢] 耗时={}ms, 通常是在等待RTSP关键帧/IDR，请检查摄像头GOP/I帧间隔", firstFrameCostMs);
                    }
                    firstImageLogged = true;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < TARGET_FRAME_INTERVAL) {
                    frame.close();
                    continue;
                }
                lastFrameTime = currentTime;

                long grabTimestamp = System.currentTimeMillis();
                long streamPts     = grabber.getTimestamp() / 1000L;
                processFrame(frame, grabTimestamp, streamPts);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processFrame(Frame frame, long grabTimestamp, long streamPts) {
        Frame frameClone = null;
        Mat   matInfo    = null;
        try {
            frameClone = frame.clone();
            frame.close();

            BufferedImage image = converter.getBufferedImage(frameClone);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;

            matInfo = bufferedImageToMat(image);
            if (matInfo == null || matInfo.empty()) return;

            detectObjectsDifyOnnxPose(matInfo, netpush, redisTemplate, null, grabTimestamp, streamPts);

        } catch (Exception e) {
            log.error("[processFrame 异常]", e);
        } finally {
            if (frameClone != null) try { frameClone.close(); } catch (Exception ignored) {}
            if (matInfo   != null) matInfo.release();
        }
    }

    public boolean detectObjectsDifyOnnxPose(Mat image, NetPush netPush,
                                             RedisTemplate redisTemplate,
                                             List<retureBoxInfo> retureBoxInfos,
                                             long grabTimestamp, long streamPts) {
        final String PERSON_CLASS = "person";
        long startTime = System.currentTimeMillis();

        Mat processedImage = null;
        try {
            processedImage = letterboxResize(image, 640, 640);
            float[] inputData = preprocessImage(processedImage);
            processedImage.release();
            processedImage = null;

            OrtSession     session = netPush.getSession();
            OrtEnvironment env     = netPush.getEnv();

            DetectionResult detectionResult;
            try {
                detectionResult = runOnnxInferencePose(session, env, inputData);
            } catch (Exception ex) {
                log.error("姿态模型推理失败", ex);
                return false;
            }

            int detectionCount = detectionResult.confidences.size();
            if (detectionCount <= 0) {
                // 无检测：仍老化快照，防止空场景后旧快照无限留存
                getIdMapper(netPush).tickEmpty();
                JSONObject bja = new JSONObject();
                bja.put("cmd",       "video");
                bja.put("number",    grabTimestamp);
                bja.put("streamPts", streamPts);
                bja.put("list",      new ArrayList<>());
                webSocket.sendMessage(bja.toJSONString());
                return true;
            }
            if (detectionCount > 200) { log.warn("检测数量异常: {}", detectionCount); return false; }

            int[] nmsIndices = performNMS(detectionResult, 0.25f, 0.25f);
            if (nmsIndices.length > 100) { log.warn("NMS后人员数量过多: {}", nmsIndices.length); return false; }

            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx    = (640 - image.cols() * scale) / 2;
            double dy    = (640 - image.rows() * scale) / 2;

            // ── 构建检测缓存（原始图像坐标）────────────────────────────────
            List<ByteTracker.Detection> detections = new ArrayList<>();
            List<DetCache> cache = new ArrayList<>();
            for (int idx : nmsIndices) {
                Rect2d box        = detectionResult.boxes2d.get(idx);
                float  confidence = detectionResult.confidences.get(idx);



                if (netPush.getIsBy() == 0) {
                    TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                    boolean inArea;
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    if (!isBoundingBoxInShapeData(box.x,box.y,box.width,box.height, videoUtil.getShapeData(),false)) { // false=有交集就算, true=完全在区域内
                        log.warn("检测框不在指定区域内，跳过");
                        continue;
                    }
                }

                BoundingBox ob    = restoreCoordinates(box, scale, dx, dy, image);
                cache.add(new DetCache(ob, 0, confidence));
                detections.add(new ByteTracker.Detection(
                        (float) ob.x, (float) ob.y,
                        (float)(ob.x + ob.width), (float)(ob.y + ob.height),
                        confidence, 0));

            }

            // ── ByteTracker 仍运行（不用于 permId 分配）────────────────────
            ByteTracker tracker = getTracker(netPush);
            List<ByteTracker.TrackResult> trackResults = tracker.update(detections);

            // ── v3 核心：直接用检测坐标做 permId 分配，不依赖 ByteTracker ID ──
            PermanentIdMapper idMapper = getIdMapper(netPush);
            List<Integer> permIds = idMapper.assignPermIds(cache);

            // ── 构建输出 ────────────────────────────────────────────────────
            TabAiBase aiBase  = getAiBaseConfig(PERSON_CLASS);
            List<JSONObject> jsonlist = new ArrayList<>();
            for (int i = 0; i < cache.size(); i++) {
                DetCache c      = cache.get(i);
                int      permId = permIds.get(i);

                JSONObject bj = new JSONObject();
                bj.put("x",         c.box.x);
                bj.put("y",         c.box.y);
                bj.put("width",     c.box.width);
                bj.put("height",    c.box.height);
                bj.put("url",       videoUrl);
                bj.put("name",      aiBase.getChainName() + permId);
                bj.put("color",     CommonColorsVue(0));
                bj.put("number",    grabTimestamp);
                bj.put("streamPts", streamPts);
                bj.put("trackId",   permId);
                bj.put("conf",      String.format("%.2f", c.conf));
                jsonlist.add(bj);
            }

            JSONObject bja = new JSONObject();
            bja.put("cmd",       "video");
            bja.put("number",    grabTimestamp);
            bja.put("streamPts", streamPts);
            bja.put("list",      jsonlist);

            long inferMs = System.currentTimeMillis() - startTime;
            log.info("姿态识别耗时: {}ms, 检测: {}, 追踪: {}", inferMs, cache.size(), trackResults.size());

            webSocket.sendMessage(bja.toJSONString());
            return true;

        } finally {
            if (processedImage != null) processedImage.release();
        }
    }


    /**
     * 判断物体框是否在自定义区域内
     *
     * @param x640 物体框左上角X坐标（640×640坐标系）
     * @param y640 物体框左上角Y坐标（640×640坐标系）
     * @param width640 物体框宽度（640×640坐标系）
     * @param height640 物体框高度（640×640坐标系）
     * @param shapeDataJson 前端保存的区域配置JSON
     * @param strictMode true=完全在区域内才算, false=有交集就算（推荐）
     */
    public static boolean isBoundingBoxInShapeData(double x640, double y640, double width640, double height640,
                                                   String shapeDataJson, boolean strictMode) {
        try {
            JSONObject shapeData = JSON.parseObject(shapeDataJson);

            // 获取原图尺寸
            int imageWidth = shapeData.getIntValue("imageWidth");
            int imageHeight = shapeData.getIntValue("imageHeight");

            // ✅ 计算物体框的四个边界点（640坐标系）
            double x1_640 = x640;
            double y1_640 = y640;
            double x2_640 = x640 + width640;
            double y2_640 = y640 + height640;

            // ✅ 将640×640的边界框转换到原图坐标系
            double x1_original = x1_640;
            double y1_original = y1_640;
            double x2_original = x2_640;
            double y2_original = y2_640;

            log.info("物体框坐标转换: 640系[x:{}, y:{}, w:{}, h:{}] → 640边界[{}, {}, {}, {}] → 原图边界[{}, {}, {}, {}] [原图: {}×{}]",
                    x640, y640, width640, height640,
                    x1_640, y1_640, x2_640, y2_640,
                    x1_original, y1_original, x2_original, y2_original,
                    imageWidth, imageHeight);

            // 获取所有形状
            JSONArray shapes = shapeData.getJSONArray("shapes");
            if (shapes == null || shapes.isEmpty()) {
                log.warn("shapeData 中没有定义任何形状");
                return false;
            }

            // 遍历所有形状，只要满足条件就返回true
            for (int i = 0; i < shapes.size(); i++) {
                JSONObject shape = shapes.getJSONObject(i);
                String type = shape.getString("type");
                JSONObject coordinates = shape.getJSONObject("coordinates");

                boolean matchThisShape = false;

                if ("rect".equals(type)) {
                    // ✅ 矩形判断 - 修复了min/max问题
                    double startX = coordinates.getDoubleValue("startX");
                    double startY = coordinates.getDoubleValue("startY");
                    double endX = coordinates.getDoubleValue("endX");
                    double endY = coordinates.getDoubleValue("endY");

                    // 计算矩形的实际边界（无论从哪个方向绘制）
                    double areaMinX = Math.min(startX, endX);
                    double areaMaxX = Math.max(startX, endX);
                    double areaMinY = Math.min(startY, endY);
                    double areaMaxY = Math.max(startY, endY);

                    if (strictMode) {
                        // 严格模式：物体框完全在区域内
                        matchThisShape = x1_original >= areaMinX && x2_original <= areaMaxX
                                && y1_original >= areaMinY && y2_original <= areaMaxY;
                    } else {
                        // 宽松模式：物体框与区域有交集（推荐）
                        matchThisShape = !(x2_original < areaMinX || x1_original > areaMaxX
                                || y2_original < areaMinY || y1_original > areaMaxY);
                    }

                    log.info("矩形{} 判断: 物体框[{}, {}, {}, {}] vs 区域[{}, {}, {}, {}] {} = {}",
                            i + 1,
                            x1_original, y1_original, x2_original, y2_original,
                            areaMinX, areaMinY, areaMaxX, areaMaxY,
                            strictMode ? "完全包含" : "有交集",
                            matchThisShape);

                } else if ("polygon".equals(type)) {
                    // ✅ 多边形判断
                    JSONArray points = coordinates.getJSONArray("points");

                    if (strictMode) {
                        // 严格模式：物体框的四个角点都在多边形内
                        matchThisShape = isPointInPolygon(x1_original, y1_original, points)
                                && isPointInPolygon(x2_original, y1_original, points)
                                && isPointInPolygon(x1_original, y2_original, points)
                                && isPointInPolygon(x2_original, y2_original, points);
                    } else {
                        // 宽松模式：物体框至少有一个角点在多边形内，或中心点在多边形内
                        double centerX = (x1_original + x2_original) / 2;
                        double centerY = (y1_original + y2_original) / 2;

                        matchThisShape = isPointInPolygon(x1_original, y1_original, points)
                                || isPointInPolygon(x2_original, y1_original, points)
                                || isPointInPolygon(x1_original, y2_original, points)
                                || isPointInPolygon(x2_original, y2_original, points)
                                || isPointInPolygon(centerX, centerY, points);
                    }

                    log.info("多边形{} 判断: 物体框[{}, {}, {}, {}] 顶点数={} {} = {}",
                            i + 1,
                            x1_original, y1_original, x2_original, y2_original,
                            points.size(),
                            strictMode ? "完全包含" : "有交集",
                            matchThisShape);
                }

                if (matchThisShape) {
                    log.info("✅ 物体框在区域内 - {}{}内 (模式: {})",
                            type.equals("rect") ? "矩形" : "多边形",
                            i + 1,
                            strictMode ? "严格" : "宽松");
                    return true;
                }
            }

            log.info("❌ 物体框不在任何定义的区域内");
            return false;

        } catch (Exception e) {
            log.error("解析 shapeData 失败", e);
            return false;
        }
    }
    /**
     * 矩形相交判断
     */
    private static boolean isRectIntersect(double l1, double t1, double r1, double b1,
                                           double l2, double t2, double r2, double b2) {
        return !(r1 < l2 || l1 > r2 || b1 < t2 || t1 > b2);
    }

    /**
     * 检测框是否与多边形相交
     */
    private static boolean isBoxIntersectPolygon(double boxLeft, double boxTop,
                                                 double boxRight, double boxBottom,
                                                 JSONArray points) {

        // 1. 多边形外包矩形快速排除
        double polyMinX = Double.MAX_VALUE;
        double polyMinY = Double.MAX_VALUE;
        double polyMaxX = -Double.MAX_VALUE;
        double polyMaxY = -Double.MAX_VALUE;

        for (int i = 0; i < points.size(); i++) {
            JSONObject p = points.getJSONObject(i);
            double x = p.getDoubleValue("x");
            double y = p.getDoubleValue("y");

            if (x < polyMinX) polyMinX = x;
            if (y < polyMinY) polyMinY = y;
            if (x > polyMaxX) polyMaxX = x;
            if (y > polyMaxY) polyMaxY = y;
        }

        if (!isRectIntersect(boxLeft, boxTop, boxRight, boxBottom, polyMinX, polyMinY, polyMaxX, polyMaxY)) {
            return false;
        }

        // 2. 检测框四个角有任意一点在多边形内
        if (isPointInPolygon(boxLeft, boxTop, points)
                || isPointInPolygon(boxRight, boxTop, points)
                || isPointInPolygon(boxRight, boxBottom, points)
                || isPointInPolygon(boxLeft, boxBottom, points)) {
            return true;
        }

        // 3. 多边形任意顶点在检测框内
        for (int i = 0; i < points.size(); i++) {
            JSONObject p = points.getJSONObject(i);
            double x = p.getDoubleValue("x");
            double y = p.getDoubleValue("y");

            if (x >= boxLeft && x <= boxRight && y >= boxTop && y <= boxBottom) {
                return true;
            }
        }

        // 4. 多边形边与检测框四条边是否相交
        for (int i = 0, j = points.size() - 1; i < points.size(); j = i++) {
            JSONObject pi = points.getJSONObject(i);
            JSONObject pj = points.getJSONObject(j);

            double x1 = pj.getDoubleValue("x");
            double y1 = pj.getDoubleValue("y");
            double x2 = pi.getDoubleValue("x");
            double y2 = pi.getDoubleValue("y");

            // 上边
            if (isLineIntersect(x1, y1, x2, y2, boxLeft, boxTop, boxRight, boxTop)) return true;
            // 右边
            if (isLineIntersect(x1, y1, x2, y2, boxRight, boxTop, boxRight, boxBottom)) return true;
            // 下边
            if (isLineIntersect(x1, y1, x2, y2, boxRight, boxBottom, boxLeft, boxBottom)) return true;
            // 左边
            if (isLineIntersect(x1, y1, x2, y2, boxLeft, boxBottom, boxLeft, boxTop)) return true;
        }

        return false;
    }

    /**
     * 点在多边形内：射线法
     */
    private static boolean isPointInPolygon(double px, double py, JSONArray points) {
        int n = points.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            JSONObject pi = points.getJSONObject(i);
            JSONObject pj = points.getJSONObject(j);

            double xi = pi.getDoubleValue("x");
            double yi = pi.getDoubleValue("y");
            double xj = pj.getDoubleValue("x");
            double yj = pj.getDoubleValue("y");

            if (((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (((yj - yi) == 0) ? 1e-10 : (yj - yi)) + xi)) {
                inside = !inside;
            }
        }

        return inside;
    }

    /**
     * 两线段是否相交
     */
    private static boolean isLineIntersect(double x1, double y1, double x2, double y2,
                                           double x3, double y3, double x4, double y4) {
        double d1 = cross(x3, y3, x4, y4, x1, y1);
        double d2 = cross(x3, y3, x4, y4, x2, y2);
        double d3 = cross(x1, y1, x2, y2, x3, y3);
        double d4 = cross(x1, y1, x2, y2, x4, y4);

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
            return true;
        }

        if (d1 == 0 && onSegment(x3, y3, x4, y4, x1, y1)) return true;
        if (d2 == 0 && onSegment(x3, y3, x4, y4, x2, y2)) return true;
        if (d3 == 0 && onSegment(x1, y1, x2, y2, x3, y3)) return true;
        if (d4 == 0 && onSegment(x1, y1, x2, y2, x4, y4)) return true;

        return false;
    }

    private static double cross(double x1, double y1, double x2, double y2, double px, double py) {
        return (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
    }

    private static boolean onSegment(double x1, double y1, double x2, double y2, double px, double py) {
        return px >= Math.min(x1, x2) && px <= Math.max(x1, x2)
                && py >= Math.min(y1, y2) && py <= Math.max(y1, y2);
    }
    // =========================================================================
    // 推理 / NMS / 预处理
    // =========================================================================
    private DetectionResult runOnnxInferencePose(OrtSession session, OrtEnvironment env,
                                                 float[] inputData) throws Exception {
        long[] shape = {1, 3, 640, 640};
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(session.getInputNames().iterator().next(), inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] output = (float[][][]) result.get(0).getValue();
                int numBoxes = output[0][0].length;
                DetectionResult dr = new DetectionResult();
                for (int i = 0; i < numBoxes; i++) {
                    float cx   = output[0][0][i], cy = output[0][1][i];
                    float w    = output[0][2][i], h  = output[0][3][i];
                    float conf = output[0][4][i];
                    if (conf < 0.25f) continue;
                    dr.boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                    dr.confidences.add(conf);
                    dr.classIds.add(0);
                }
                return dr;
            }
        }
    }

    private int[] performNMS(DetectionResult dr, float confThr, float nmsThr) {
        if (dr.boxes2d.isEmpty()) return new int[0];
        MatOfRect2d boxesMat = new MatOfRect2d();
        MatOfFloat  confMat  = new MatOfFloat();
        MatOfInt    indices  = new MatOfInt();
        try {
            boxesMat.fromList(dr.boxes2d);
            confMat = new MatOfFloat(Converters.vector_float_to_Mat(dr.confidences));
            Dnn.NMSBoxes(boxesMat, confMat, confThr, nmsThr, indices);
            if (indices.empty() || indices.rows() == 0) {
                log.warn("NMS未返回索引，置信度阈值{}可能过高", confThr);
                return new int[0];
            }
            return indices.toArray();
        } finally {
            boxesMat.release(); confMat.release(); indices.release();
        }
    }

    private float[] preprocessImage(Mat processedImage) {
        Mat blob = new Mat();
        List<Mat> channels = new ArrayList<>();
        try {
            processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
            Core.split(blob, channels);
            float[] inputData = new float[3 * 640 * 640];
            for (int c = 0; c < 3; c++) {
                float[] data = new float[640 * 640];
                channels.get(c).get(0, 0, data);
                System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
            }
            return inputData;
        } finally {
            blob.release();
            for (Mat ch : channels) ch.release();
        }
    }

    // =========================================================================
    // 辅助类
    // =========================================================================
    static class DetCache {
        final BoundingBox box; final int classId; final float conf;
        DetCache(BoundingBox b, int c, float f) { box = b; classId = c; conf = f; }
    }
    static class BoundingBox {
        double x, y, width, height;
        BoundingBox(double x, double y, double w, double h) {
            this.x = x; this.y = y; width = w; height = h;
        }
    }
    static class DetectionStats {
        String audioText = ""; Integer warnNumber = 0; String warnText = "", warnName = "";
        void accumulate(TabAiBase a) {
            audioText  += a.getRemark() + a.getSpaceOne();
            warnNumber += a.getSpaceTwo() == null ? 1 : a.getSpaceTwo();
        }
    }
    private static class DetectionResult {
        List<Rect2d>  boxes2d     = new ArrayList<>();
        List<Float>   confidences = new ArrayList<>();
        List<Integer> classIds    = new ArrayList<>();
        void addDetection(double x, double y, double w, double h, float conf, int cid) {
            boxes2d.add(new Rect2d(x, y, w, h)); confidences.add(conf); classIds.add(cid);
        }
    }
    private TabAiBase getAiBaseConfig(String className) {
        TabAiBase a = VideoSendReadCfg.map.get(className);
        if (a == null) { a = new TabAiBase(); a.setChainName(className); }
        return a;
    }
    private BoundingBox restoreCoordinates(Rect2d box, double scale,
                                           double dx, double dy, Mat image) {
        double x = Math.max(0, Math.min((box.x - dx) / scale, image.cols() - 1));
        double y = Math.max(0, Math.min((box.y - dy) / scale, image.rows() - 1));
        double w = Math.min(box.width  / scale, image.cols() - x);
        double h = Math.min(box.height / scale, image.rows() - y);
        return new BoundingBox(x, y, w, h);
    }
    private void parseOnnxOutput(Object raw, long[] shape, Integer expClass,
                                 float confThr, DetectionResult result) {
        if (raw instanceof float[][][]) {
            float[][][] batch = (float[][][]) raw;
            boolean needTranspose = shape.length == 3 && shape[1] < shape[2]
                    && shape[1] <= (expClass + 5);
            for (float[][] d : batch) {
                if (needTranspose) parseTransposedDetections(d, shape, expClass, confThr, result);
                else               parseStandardDetections(d, expClass, confThr, result);
            }
        } else if (raw instanceof float[][]) {
            parseStandardDetections((float[][]) raw, expClass, confThr, result);
        }
    }
    private void parseTransposedDetections(float[][] det, long[] shape, Integer expClass,
                                           float confThr, DetectionResult result) {
        int nc = (int) shape[1] - 4, nd = (int) shape[2];
        for (int i = 0; i < nd; i++) {
            float cx = det[0][i], cy = det[1][i], w = det[2][i], h = det[3][i];
            float max = 0; int cid = 0;
            for (int c = 0; c < nc; c++) if (det[4 + c][i] > max) { max = det[4 + c][i]; cid = c; }
            if (max > confThr && cid < expClass)
                result.addDetection(cx - w / 2, cy - h / 2, w, h, max, cid);
        }
    }
    private void parseStandardDetections(float[][] det, Integer expClass,
                                         float confThr, DetectionResult result) {
        for (float[] d : det) {
            boolean hasObj = d.length > 5; int si = hasObj ? 5 : 4;
            float max = 0; int cid = 0;
            for (int i = si; i < d.length; i++) if (d[i] > max) { max = d[i]; cid = i - si; }
            float conf = hasObj ? d[4] * max : max;
            if (conf > confThr && cid < expClass)
                result.addDetection(d[0] - d[2] / 2, d[1] - d[3] / 2, d[2], d[3], conf, cid);
        }
    }
    private DetectionResult runOnnxInference(OrtSession session, OrtEnvironment env,
                                             float[] inputData, Integer expClass) throws Exception {
        long[] shape = {1, 3, 640, 640};
        DetectionResult result = new DetectionResult();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)) {
            try (OrtSession.Result results = session.run(
                    Collections.singletonMap(session.getInputNames().iterator().next(), t))) {
                for (Map.Entry<String, OnnxValue> e : results) {
                    if (!(e.getValue() instanceof OnnxTensor)) continue;
                    OnnxTensor tensor = (OnnxTensor) e.getValue();
                    parseOnnxOutput(tensor.getValue(), tensor.getInfo().getShape(),
                            expClass, 0.45f, result);
                }
            }
        }
        return result;
    }
    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        if (grabber != null) { grabber.stop(); grabber.release(); }
        return createOptimizedGrabber();
    }
    private boolean isStreamActive() {
        try { return RedisCacheHolder.get(tabAiModelBund.getId() + "videoRead"); }
        catch (Exception e) { log.warn("[检查流状态异常]", e); return false; }
    }
    public FFmpegFrameGrabber createOptimizedGrabber() throws Exception {
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoUrl);
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("stimeout", "5000000");
        probe.start();
        String codecName = probe.getVideoCodecName();
        int    codecId   = probe.getVideoCodec();
        probe.stop(); probe.close(); probe.release();
        log.info("检测到视频编码: {} (ID={})", codecName, codecId);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
        grabber.setOption("hwaccel", "qsv");
        if ("h264".equalsIgnoreCase(codecName))
            grabber.setVideoCodecName("h264_qsv");
        else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName))
            grabber.setVideoCodecName("hevc_qsv");
        log.info("[使用Intel加速解码]");
        grabber.setOption("loglevel",       "-8");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags",     "prefer_tcp");
        grabber.setOption("stimeout",       "3000000");
        grabber.setOption("rw_timeout",     "3000000");
        grabber.setOption("allowed_media_types", "video");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        grabber.setOption("avioflags",      "direct");
        grabber.setOption("flags",          "low_delay");
        grabber.setOption("max_delay",      "0");
        grabber.setOption("buffer_size",    "512000");
        grabber.setOption("reorder_queue_size", "0");
        grabber.setOption("use_wallclock_as_timestamps", "1");
        grabber.setOption("fflags",         "nobuffer+flush_packets+discardcorrupt");
        grabber.setOption("flags2",         "fast");
        grabber.setOption("err_detect",     "compliant");
        grabber.setOption("framedrop",      "1");
        grabber.start();
        return grabber;
    }
}
