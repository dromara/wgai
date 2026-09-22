package org.jeecg.modules.ros2.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.ros2.controller.MappingController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * fast_lio 掉线看门狗。
 *
 * <h3>解决什么问题</h3>
 * 现场症状："脚本跑久了建图就没点云了，Nav2 一直刷
 * {@code Invalid frame ID "camera_init" ... frame does not exist}，只能重启脚本。"
 * <p>
 * 这行报错不是 Nav2 的问题：{@code camera_init→body} 这条 TF 和 {@code /cloud_registered}
 * 都是 fast_lio 出的，它一没，TF 树里这个 frame 就随 buffer 过期消失，建图同时没点云。
 * 在此之前 Java 对这件事**毫无感知**——照样推位姿、照样攒栅格、急停也不会触发，
 * 全靠人盯着终端刷屏才发现。
 *
 * <h3>策略：只做 fail-safe，不闷头自愈</h3>
 * <ul>
 *   <li><b>进程真没了</b>（pgrep 查不到 fastlio_mapping）→ 判定 DEAD：
 *       立刻急停并把车锁住、停止栅格累积、前端红色告警。</li>
 *   <li><b>进程还在、只是 Java 收不到 /Odometry</b> → 判定 DEGRADED：只告警不急停。
 *       这一路多半是 rosbridge 订阅丢了（{@code ROS2BridgeService.checkOdomAlive} 会自愈），
 *       车其实跑得好好的，急停属于误伤；真出事也有 PLC 侧 {@code plc.watchdog.ms}
 *       这道 cmd_vel 断流刹车兜底。</li>
 *   <li><b>整栈重启不自动做。</b>导航途中被悄悄重启一次栈，定位和目标全废，比掉线本身更危险。
 *       留 {@code POST /ros2/mapping/recover-stack} 手动入口，由现场人看着点。
 *       {@code ros.fastlio-watchdog.auto-restart=true} 可以打开自动重启，但默认关。</li>
 * </ul>
 *
 * <h3>为什么急停要上锁</h3>
 * {@link RobotHardwareService#emergencyStop()} 会置 Java 侧急停锁，必须人工
 * {@code clearEmergencyStopLock()} 才能再接 cmd_vel。这是故意的：
 * fast_lio 重启后（launch 里的 respawn 会把它拉起来）{@code camera_init} 原点会跳到车的**当前**位置，
 * AMCL 定位随之作废。此时如果车还能动，它会朝着一个错误的坐标系往前开。
 * 锁住 = 强制人工重设初始位姿后才放行。
 *
 * <h3>为什么栅格累积必须停</h3>
 * 同理，fast_lio 重启后新点落在**新的** camera_init 系里，继续往同一张栅格图上叠 =
 * 图静默错位，而且事后看不出来。所以一掉线就 {@code setEnabled(false)}，
 * 已经攒到的数据保留着（可以先存图），要继续扫必须走「开始建图」重新来。
 *
 * @see MappingController#isStackBusy() pcd 落盘/转换/重启整栈期间不判定，那是 Java 自己在杀进程
 */
@Slf4j
@Service
public class FastLioWatchdogService {

    /** 健康：最近 odomTimeoutMs 内收到过 /Odometry */
    private static final int STATE_OK       = 0;
    /** 降级：odom 断流但 fastlio_mapping 进程还在 */
    private static final int STATE_DEGRADED = 1;
    /** 掉线：odom 断流且 pgrep 查不到 fastlio_mapping */
    private static final int STATE_DEAD     = 2;

    @Value("${ros.fastlio-watchdog.enabled:true}")
    private boolean enabled;

    /** 多久没收到 /Odometry 才开始怀疑。fast_lio 正常 50Hz，4s 已经非常宽松 */
    @Value("${ros.fastlio-watchdog.odom-timeout-ms:4000}")
    private long odomTimeoutMs;

    @Value("${ros.fastlio-watchdog.check-interval-ms:1000}")
    private long checkIntervalMs;

    /**
     * 掉线后是否自动重启整栈。
     * ⚠ 默认 false —— 导航途中自动重启会让定位和当前目标一起作废，
     * 比"停在原地等人处理"危险得多。开之前想清楚。
     */
    @Value("${ros.fastlio-watchdog.auto-restart:false}")
    private boolean autoRestart;

    /** 自动重启的最小间隔，防止 fast_lio 起不来时无限重启风暴 */
    @Value("${ros.fastlio-watchdog.restart-cooldown-ms:300000}")
    private long restartCooldownMs;

    /** 同一状态下重复告警的间隔，免得刷屏 */
    @Value("${ros.fastlio-watchdog.alarm-interval-ms:15000}")
    private long alarmIntervalMs;

    /**
     * fast_lio 常驻内存(RSS)超过这个值就告警，单位 MB。0 = 不查。
     *
     * ⭐ 这是"跑久了就挂"的**提前量**：真凶是 fast_lio 的 PCD 累积缓冲(pcl_wait_save)，
     *   `pcd_save_en:true` + `pcd_save_interval:-1` 时它把每帧稠密点堆在内存里只增不减，
     *   mid360 20万点/s × 48B ≈ 3.4GB/小时，堆到 OOM killer 出手为止。
     *   ⚠ 而且这个累积**跟建图开没开无关**：Java 的 /start /cancel 只管栅格累积，
     *     碰都不碰 fast_lio 进程，它从 launch 那一刻就开始堆。
     *   正解是把 config 里的 pcd_save_en 改 false(save-mode=grid 下那份 PCD 根本没人读)，
     *   这里只负责在真的又涨起来时提前喊一嗓子，而不是等它被内核杀掉。
     *   关掉 pcd_save_en 后 fast_lio 内存只剩 ikd-tree 局部地图，是有界的，
     *   稳定值一般几百 MB，所以 3GB 门限不会误报。
     */
    @Value("${ros.fastlio-watchdog.rss-warn-mb:3000}")
    private long rssWarnMb;

    @Autowired
    private ROS2BridgeService ros2BridgeService;

    @Autowired
    private RobotHardwareService robotHardwareService;

    @Autowired
    private MappingGridService mappingGridService;

    @Autowired
    private WebSocketPushService pushService;

    @Autowired
    private MappingController mappingController;

    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fastlio-watchdog");
                t.setDaemon(true);
                return t;
            });

    private volatile int  state          = STATE_OK;
    /** 曾经收到过 /Odometry。没有过就不判定——Java 先于 ROS 栈启动是常态，不能开机就急停 */
    private volatile boolean hasBeenHealthy = false;
    private volatile long lastOdomOkMs   = 0L;
    private volatile long lastAlarmMs    = 0L;
    private volatile long lastRestartMs  = 0L;
    /** 掉线时栅格是不是开着的，用来在告警里告诉前端"建图已经中断" */
    private volatile boolean wasMapping  = false;
    private volatile String  lastReason  = "";
    /** fast_lio 常驻内存 MB，-1 = 没读到 */
    private volatile long    rssMb       = -1;
    private volatile long    lastRssWarnMs = 0L;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.warn("[fast_lio看门狗] 已禁用 (ros.fastlio-watchdog.enabled=false)");
            return;
        }
        timer.scheduleWithFixedDelay(this::tick, 10, Math.max(500, checkIntervalMs),
                TimeUnit.MILLISECONDS);
        log.info("[fast_lio看门狗] 已启动 | odom超时={}ms 自动重启整栈={}", odomTimeoutMs, autoRestart);
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
    }

    private void tick() {
        try {
            long now = System.currentTimeMillis();

            // Java 自己在动进程（pcd 落盘 SIGINT / 转 pgm / 重启整栈）：这期间 fast_lio 本来就该没有，
            // 不能判定成掉线。顺带把计时基准往前推，免得流程结束那一刻立刻误报。
            if (mappingController.isStackBusy()) {
                lastOdomOkMs = now;
                return;
            }

            if (ros2BridgeService.getOdomPose(odomTimeoutMs) != null) {
                onHealthy(now);
                return;
            }

            // 从来没健康过 = 栈还没起来 / Java 先启动，不是"掉线"
            if (!hasBeenHealthy) return;
            if (now - lastOdomOkMs < odomTimeoutMs) return;

            // odom 断流了，用 pgrep 复核进程死没死。两种情况的处置完全不同，不能混。
            boolean alive = mappingController.isFastLioRunning();
            if (alive) {
                onDegraded(now);
            } else {
                onDead(now);
            }
        } catch (Exception e) {
            log.warn("[fast_lio看门狗] 巡检异常: {}", e.getMessage());
        }
    }

    private void onHealthy(long now) {
        lastOdomOkMs = now;
        checkRss(now);
        boolean first = !hasBeenHealthy;
        hasBeenHealthy = true;
        if (state == STATE_OK) return;

        int prev = state;
        state = STATE_OK;
        log.warn("[fast_lio看门狗] ✅ /Odometry 恢复（此前状态={}）", prev == STATE_DEAD ? "掉线" : "降级");
        if (prev == STATE_DEAD) {
            // ⚠ 恢复 ≠ 可以继续跑。fast_lio 是新进程，camera_init 原点已经变了。
            push("fastlio_recovered", "fast_lio 已恢复，但坐标原点已改变",
                    "必须重新设置初始位姿并解除急停后才能继续导航；"
                            + (wasMapping ? "建图已中断，之前扫到的数据仍可保存，继续扫请点「开始建图」重新开始。" : ""));
        }
        if (first) log.info("[fast_lio看门狗] 首次收到 /Odometry，开始监控");
    }

    private void onDegraded(long now) {
        if (state != STATE_DEGRADED) {
            state = STATE_DEGRADED;
            lastReason = "fastlio_mapping 进程还在，但 Java 已 " + (now - lastOdomOkMs) + "ms 没收到 /Odometry";
            log.warn("[fast_lio看门狗] ⚠ {}。多半是 rosbridge 订阅丢了（checkOdomAlive 会自愈），"
                    + "不急停。若持续不恢复，在机器人上跑 ros2 topic hz /Odometry 分清是订阅问题还是雷达/驱动问题", lastReason);
        }
        if (now - lastAlarmMs >= alarmIntervalMs) {
            lastAlarmMs = now;
            push("fastlio_degraded", "里程计数据中断", lastReason);
        }
    }

    private void onDead(long now) {
        if (state == STATE_DEAD) {
            if (now - lastAlarmMs >= alarmIntervalMs) {
                lastAlarmMs = now;
                push("fastlio_lost", "fast_lio 未运行", lastReason);
            }
            return;
        }

        state = STATE_DEAD;
        lastAlarmMs = now;
        lastReason = "pgrep 查不到 fastlio_mapping 进程，已 " + (now - lastOdomOkMs) + "ms 没有 /Odometry";
        log.error("[fast_lio看门狗] ❌ fast_lio 掉线！{}", lastReason);
        log.error("[fast_lio看门狗]   → camera_init 这条 TF 已经没人发，Nav2 会持续刷 "
                + "'Invalid frame ID \"camera_init\"'，建图也收不到点云。"
                + "先在机器人上查: dmesg -T | grep -i 'killed process' / df -h /home");

        // ① 停车。fail-safe 优先，PLC 通不通都先喊一嗓子，emergencyStop 内部自己兜异常。
        try {
            robotHardwareService.emergencyStop();
            log.error("[fast_lio看门狗]   → 已触发急停并上锁，需人工解除");
        } catch (Exception e) {
            log.error("[fast_lio看门狗]   → 急停调用异常，请立即现场手动停车! {}", e.getMessage());
        }

        // ② 停止栅格累积。fast_lio 重起后 camera_init 原点会变，继续叠图 = 静默错位。
        //    只关开关、不清数据：已经扫到的还能存出来。
        wasMapping = mappingGridService.isEnabled();
        if (wasMapping) {
            mappingGridService.setEnabled(false);
            log.error("[fast_lio看门狗]   → 已停止栅格累积（已扫数据保留，可先保存；继续扫必须点「开始建图」重开）");
        }

        // ③ 前端告警
        push("fastlio_lost", "fast_lio 掉线，已紧急停车", lastReason);

        // ④ 自动重启整栈（默认关）
        if (!autoRestart) {
            log.error("[fast_lio看门狗]   → 未自动重启整栈（ros.fastlio-watchdog.auto-restart=false）。"
                    + "确认现场安全后，在导航页点「重启导航栈」或调 POST /ros2/mapping/recover-stack");
            return;
        }
        if (now - lastRestartMs < restartCooldownMs) {
            log.error("[fast_lio看门狗]   → 距上次自动重启不足 {}ms，本次不再重启，等人工处理", restartCooldownMs);
            return;
        }
        lastRestartMs = now;
        log.error("[fast_lio看门狗]   → auto-restart=true，后台重启整栈中...");
        new Thread(() -> {
            try {
                mappingController.restartRobotFull();
                push("fastlio_recovered", "导航栈已自动重启",
                        "必须重新设置初始位姿并解除急停后才能继续导航。");
            } catch (Exception e) {
                log.error("[fast_lio看门狗] 自动重启整栈失败: {}", e.getMessage());
                push("fastlio_lost", "导航栈自动重启失败", String.valueOf(e.getMessage()));
            }
        }, "fastlio-watchdog-restart").start();
    }

    /**
     * 读 fast_lio 的 RSS。10s 一次，fork 一个 ps 的开销可以忽略。
     * 多实例时 ps 会出多行 —— 那本身就是事故(两个 laserMapping 抢写 TF)，取最大值即可。
     */
    private void checkRss(long now) {
        if (rssWarnMb <= 0 || now - lastRssProbeMs < 10_000) return;
        lastRssProbeMs = now;
        try {
            Process p = new ProcessBuilder("bash", "-c",
                    "ps -o rss= -C fastlio_mapping | sort -n | tail -1").start();
            String out;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return; }
            if (out == null || out.trim().isEmpty()) return;
            rssMb = Long.parseLong(out.trim()) / 1024L;   // ps 的 rss 单位是 KB
        } catch (Exception e) {
            log.debug("[fast_lio看门狗] 读 RSS 失败: {}", e.getMessage());
            return;
        }

        if (rssMb >= rssWarnMb && now - lastRssWarnMs >= 60_000) {
            lastRssWarnMs = now;
            String msg = "fast_lio 常驻内存已达 " + rssMb + " MB（门限 " + rssWarnMb + " MB）。"
                    + "多半是 config 里 pcd_save_en 还开着（save-mode=grid 下那份 PCD 没人读，"
                    + "却按 ~3.4GB/小时 只增不减地堆），再涨下去会被 OOM killer 杀掉、"
                    + "Nav2 就开始刷 camera_init frame does not exist。"
                    + "处理：把 fast_lio config 的 pcd_save_en 改成 false 后重启栈";
            log.error("[fast_lio看门狗] ⚠ {}", msg);
            push("fastlio_mem_warn", "fast_lio 内存偏高", msg);
        }
    }

    private volatile long lastRssProbeMs = 0L;

    private void push(String type, String title, String detail) {
        try {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("title",  title);
            d.put("detail", detail);
            d.put("state",  state);
            d.put("wasMapping", wasMapping);
            d.put("autoRestart", autoRestart);
            pushService.pushToAllReliable(type, d);
        } catch (Exception e) {
            log.debug("[fast_lio看门狗] 推送告警失败: {}", e.getMessage());
        }
    }

    /** 供 /ros2/mapping/status 等接口展示 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",      enabled);
        m.put("state",        state == STATE_OK ? "ok" : state == STATE_DEGRADED ? "degraded" : "dead");
        m.put("hasBeenHealthy", hasBeenHealthy);
        m.put("lastOdomOkMs", lastOdomOkMs);
        m.put("odomAgeMs",    hasBeenHealthy ? System.currentTimeMillis() - lastOdomOkMs : -1);
        m.put("autoRestart",  autoRestart);
        m.put("rssMb",        rssMb);
        m.put("rssWarnMb",    rssWarnMb);
        m.put("reason",       lastReason);
        return m;
    }

    /** 人工处理完（重启栈 + 重设初始位姿）之后，把看门狗状态归位 */
    public void acknowledge() {
        state = STATE_OK;
        lastOdomOkMs = System.currentTimeMillis();
        lastReason = "";
        log.info("[fast_lio看门狗] 状态已人工确认复位");
    }
}
