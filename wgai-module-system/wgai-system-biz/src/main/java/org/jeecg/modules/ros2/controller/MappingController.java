package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.service.MappingGridService;
import org.jeecg.modules.ros2.service.ROS2BridgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 建图控制器（Java 8 兼容版）
 *
 * ─── 架构：建图复用常驻栈的 fast_lio，本类不再自己启动它 ────────────────────
 * robot_full.launch.py 已经 include 了 fast_lio 的 mapping.launch.py。本类以前
 * 还会再 `ros2 launch fast_lio mapping.launch.py` 起第二个，后果是：
 *   · 两个同名 laser_mapping 节点同时存在（ros2 node list 会报 share an exact name）
 *   · 两个 livox_self_filter 同时往 /livox/lidar_filtered 发 → 每帧点云发两遍
 *   · 两个 fast_lio 同时往 TF 树写 camera_init→body → TF 反复横跳，
 *     tf2_echo 会一会儿报 "frame does not exist" 一会儿又能输出
 * 表现就是建图重影、定位漂移、Nav2 刷 costmap transform timeout。
 * 所以 /start 现在只做「确认常驻 fast_lio 活着 + 重新绑订阅」，不再拉起进程。
 * 建图和导航共用一份 config（mid360_mapping.yaml，dense_publish_en:true）。
 *
 * ─── 出图方式：默认已不再依赖 PCD ─────────────────────────────────────────
 * mapping.save-mode=grid（默认）走 MappingGridService：Java 本来就在实时收
 * /cloud_registered 的全量点，且它们就在 camera_init 系——和 fast_lio 写进 PCD 的
 * 坐标系完全一致。边收边打栅格，保存时直接导出 pgm，全程不碰 fast_lio 进程。
 * 于是 /save 和 /restart 都不再重启整栈，保存完可以马上继续导航。
 *
 * ─── 老的 pcd 模式为什么非重启不可（保留作回退，mapping.save-mode=pcd） ──
 *   fast_lio 只有收到 SIGINT（Ctrl+C）才会触发 PCD 保存
 *   process.destroy()             → SIGTERM → 直接退出，不保存 PCD ❌
 *   pkill -INT -f fastlio_mapping → SIGINT  → 保存 PCD 再退出       ✅
 * 既然建图用的就是常驻那个 fast_lio，「保存地图」= SIGINT 它 = 常驻栈的
 * fast_lio 死掉，Nav2 立刻失去 camera_init。所以 pcd 模式下 /save 转换完 pgm 之后
 * 必须自动重启整个 robot_full（restartRobotFull()），顺带让新地图生效。
 * 同理 pcd 模式的 /restart（清空重扫）也只能靠重启整栈来清空 fast_lio 的地图。
 *
 * 进程存活判定一律用 pgrep（isFastLioRunning()），不能用 Process.isAlive()——
 * 常驻栈可能是 systemd 或人工启的，Java 这边根本没有 Process 句柄。
 *
 * 不使用 process.pid()（Java 9+），改用 pkill 按进程名发信号，兼容 Java 8
 */
@Slf4j
@RestController
@RequestMapping("/ros2/mapping")
@Api(tags = "建图控制")
public class MappingController {

    private static final String PCD_PATH   = "/home/lio_ws/src/FAST_LIO/PCD/scans.pcd";
    private static final String MAPS_DIR   = "/home/ros/maps";
    private static final String SETUP_BASH = "/home/lio_ws/install/setup.bash";

    // fast_lio 可执行文件名（pkill 用）
    private static final String FASTLIO_PROCESS_NAME = "fastlio_mapping";
    // 自身遮挡过滤脚本进程名（pkill 用）
    private static final String SELF_FILTER_PROCESS_NAME = "livox_self_filter.py";
    // ⚠️ mapping.launch.py 里除了 fastlio_mapping 还有 self_filter_node(livox_self_filter.py)
    //   两个长驻子进程，必须一起处理：
    //   · 发 SIGINT 时只发给 fastlio_mapping 的话，self_filter 还活着会让 `ros2 launch`
    //     父进程一直不退出，doStopAndWait() 就一直等到 10 分钟安全阀。
    //   · self_filter 残留还会和重启后新起的那个同时往 /livox/lidar_filtered 发，
    //     每帧点云发两遍，直接把 fast_lio 的配准搞乱。
    private static final String KILL_PATTERN = FASTLIO_PROCESS_NAME + "|" + SELF_FILTER_PROCESS_NAME;

    /**
     * 重启 robot_full 前要清干净的**整栈**进程（robot_full.launch.py 会拉起这些）。
     *
     * ⚠️ 只杀 fast_lio 系列是不够的：rosbridge_websocket 活着就占着 9090，
     *    新栈起来会一直刷 "Unable to start server: [Errno 98] Address already in use"，
     *    Java 也就永远连不上新的 rosbridge。
     *
     * ⚠️ 每个名字首字母都用方括号包起来（[r]osbridge_websocket）。
     *    pkill -f 匹配的是整条命令行，而承载 pkill 的那个 `bash -c "pkill -f '...'"`
     *    命令行里就含着这些模式串，不加方括号会把自己的父 bash 一起杀掉
     *    （尤其 robot_full.launch.py，Java 启动它的 bash 命令行里正好有这个串）。
     *    方括号是正则字符类，只匹配到实际进程名，匹配不到含 "[r]" 字面量的自身命令行。
     */
    private static final String STACK_KILL_PATTERN =
              "[f]astlio_mapping"
            + "|[l]ivox_self_filter"
            + "|[r]osbridge_websocket"
            + "|[l]ivox_ros_driver2_node"
            + "|[p]ointcloud_to_laserscan"
            // Nav2 改用 use_composition:=False 后，下面这些不再藏在 nav2_container 里，
            // 而是各自独立进程，必须逐个点名，只杀 nav2_container 会留一堆孤儿节点，
            // 新栈起来就会撞上 "nodes in the graph that share an exact name"
            + "|[n]av2_container"
            + "|[c]ontroller_server"
            + "|[p]lanner_server"
            + "|[b]t_navigator"
            + "|[b]ehavior_server"
            + "|[s]moother_server"
            + "|[w]aypoint_follower"
            + "|[v]elocity_smoother"
            + "|[m]ap_server"
            + "|[a]mcl"
            + "|[l]ifecycle_manager"
            + "|[r]obot_full.launch.py";

    /** 常驻导航栈 launch 文件；建图和导航共用它拉起的那一个 fast_lio */
    @Value("${ros.robot-full.launch-file:/home/ros/robot_full.launch.py}")
    private String robotFullLaunchFile;

    /** ROS 2 环境 setup.bash（Java 起的子进程不继承人工终端的 source） */
    @Value("${ros.robot-full.ros-setup:/opt/ros/humble/setup.bash}")
    private String rosSetupBash;

    /** robot_full 重启后等它把 Nav2 拉起来的时间；launch 里 T+12s 才启 Nav2，要留够 */
    @Value("${ros.robot-full.ready-wait-ms:20000}")
    private long robotFullReadyWaitMs;

    /**
     * 车体自身遮挡过滤开关，透传给 robot_full.launch.py 的 SELF_FILTER 环境变量。
     * 必须和人工启动栈时用的值保持一致，否则保存地图触发自动重启后，
     * 过滤会悄悄退回默认的关闭状态，下一张图又出车体重影。
     */
    @Value("${ros.robot-full.self-filter:true}")
    private boolean robotFullSelfFilter;

    /**
     * 保存地图的取图方式：
     *   grid(默认) = 用 Java 实时累积的占据栅格出图。不发 SIGINT、不重启整栈，秒级，
     *                保存完 Nav2 一直活着，可以马上继续导航。
     *   pcd        = 老流程。SIGINT 杀 fast_lio 逼它落盘 PCD → 转 pgm → 整栈重启(~20s+)。
     *                只在怀疑栅格图不对时临时切回来用。
     */
    @Value("${mapping.save-mode:grid}")
    private String saveMode;

    /** Java 重启 robot_full 时持有的句柄；常驻栈由 systemd/人工启动时为 null（此时靠 pgrep 判活） */
    private volatile Process robotFullProcess = null;

    /** 最近一次启动用的雷达类型，供 /restart（清空点云→重新扫描）复用，不用前端每次都传 */
    private volatile String lastLidarType = "unitree_l1";

    /** true = SIGINT 已发送，PCD 正在后台写盘（进程可能还存活，也可能已退出但文件系统 flush 未完成） */
    private final AtomicBoolean pcdSaving = new AtomicBoolean(false);

    /** true = /save 触发的"停止落盘+转换pgm"整条流程正在后台跑 */
    private final AtomicBoolean converting = new AtomicBoolean(false);

    /** /save 最近一次的异步结果：{success:true,...} 或 {success:false,message:...}；null=尚未有结果（进行中或从未保存过） */
    private volatile Map<String, Object> lastSaveResult = null;

    /** PCD 写盘监控安全阀：正常情况下文件大小会持续增长直到进程退出；
     *  超过这个时间还没退出才强制 kill（大点云 ASCII 落盘可能要几分钟，不能再用 15 秒） */
    private static final long SAVE_SAFETY_TIMEOUT_MS = 10 * 60 * 1000L; // 10 分钟

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Autowired
    ROS2BridgeService ros2BridgeService;

    /** 复用它的 updateLastUsedSymlink：新地图要挂上软链接，重启的 robot_full 才会加载它 */
    @Autowired
    MapController mapController;

    /** 实时累积的占据栅格；栅格模式下 /save 和 /restart 都只跟它打交道，不碰进程 */
    @Autowired
    MappingGridService mappingGridService;

    // ==================== 启动建图 ====================
    @PostMapping("/connection")
    @ApiOperation("启动 fast_lio 建图")
    public Result<Map<String, Object>> connection(){
        if(ros2BridgeService.isConnected()==false){
                ros2BridgeService.connect();
        }
        return Result.OK("开始连接");
    }
    @PostMapping("/start")
    @ApiOperation("启动 fast_lio 建图")
    public Result<Map<String, Object>> startMapping(
            @RequestBody(required = false) Map<String, String> body) {

        if(ros2BridgeService.isConnected()==false){
            ros2BridgeService.connect();
        }

        if (pcdSaving.get() || converting.get()) {
            return Result.error("上一次地图正在保存/转换中，请等它完成后再开始建图");
        }

        if (body == null) body = new HashMap<>();
        // lidarType 只用于重启 robot_full 时传 LIDAR_TYPE 环境变量，不再决定 fast_lio config
        lastLidarType = body.getOrDefault("lidarType", lastLidarType);

        // 建图复用常驻栈的 fast_lio。它没起来说明 robot_full 没跑，这里不能替它拉起——
        // 单独起一个 fast_lio 会和常驻栈形成双实例，TF 树被两个 publisher 抢写。
        if (!isFastLioRunning()) {
            return Result.error("fast_lio 未在运行。建图复用常驻导航栈的 fast_lio，"
                    + "请先启动 " + robotFullLaunchFile + "（或 robot-nav.service）");
        }

        // resume=true 表示"暂停后恢复"，栅格要接着上次继续攒；
        // 默认(false)是开新一轮建图，必须先清掉上一张图的栅格，否则两张图会叠在一起。
        boolean resume = "true".equalsIgnoreCase(body.getOrDefault("resume", "false"));
        if (!"pcd".equalsIgnoreCase(saveMode)) {
            if (!resume) {
                mappingGridService.clear();
            }
            mappingGridService.setEnabled(true);
        }

        // ⭐ 进入建图模式,前端 robot_pose 改用 /Odometry 来源
        ros2BridgeService.setNavMode(false);

        // ⭐ rosbridge 的订阅会绑死在当时的 publisher 上。上一轮保存地图会重启整个 robot_full，
        //   fast_lio 是全新进程、publisher 也是新的，旧订阅不会自动重绑，前端就再也收不到点云。
        //   这里主动 unsubscribe+resubscribe 一次。
        executor.submit(new Runnable() {
            public void run() {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
                ros2BridgeService.resubscribeFastLioTopics();
            }
        });

        log.info("▶ 开始建图（复用常驻 fast_lio，未新起进程）");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("reusedExistingFastLio", true);
        res.put("lidarType", lastLidarType);
        res.put("pcdPath",   PCD_PATH);
        return Result.OK(res);
    }

    /**
     * fast_lio 是否在运行。必须用 pgrep 而不是 Process.isAlive()：
     * 常驻栈由 systemd 或人工 `ros2 launch robot_full.launch.py` 启动，Java 没有 Process 句柄。
     *
     * pattern 写成 '[f]astlio_mapping' 是为了让 pgrep 不匹配到承载这条命令的 bash 自身
     * （bash -c 的命令行里含有这个字符串，直接写 fastlio_mapping 会永远返回"在运行"）。
     */
    private boolean isFastLioRunning() {
        try {
            Process p = new ProcessBuilder("bash", "-c",
                    "pgrep -f '[f]astlio_mapping' > /dev/null").start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            log.warn("pgrep 检测 fast_lio 失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 停止建图（SIGINT → 触发 PCD 保存） ====================

    /**
     * 真正的"停止 fast_lio 并等它把 PCD 落盘完成"，阻塞方法，只能在后台线程里调用（不能直接挂 HTTP 线程上）。
     * 供 /stop 和 /save 共用：/save 点击后会自动先调这个来触发落盘，再转换 pgm。
     * fast_lio 已经不在运行时直接返回（幂等）。
     *
     * ⚠️ 这里 SIGINT 掉的是常驻栈的 fast_lio，Nav2 会立刻失去 camera_init。
     *    调用方有义务在落盘结束后调 restartRobotFull() 把整栈拉回来。
     */
    private void doStopAndWait() throws Exception {
        if (!isFastLioRunning()) {
            return;
        }
        if (!pcdSaving.compareAndSet(false, true)) {
            // 已经有别的调用在等落盘了（理论上不会同时触发），跟着等它做完即可
            while (pcdSaving.get()) Thread.sleep(1000);
            return;
        }

        log.info("⏹ 发送 SIGINT 到 fast_lio（等价于 Ctrl+C），大点云落盘可能需要几分钟...");
        log.info("   ⚠ 这个 fast_lio 是常驻栈的，SIGINT 之后 Nav2 会失去 camera_init，"
                + "转换完成后由 /save 自动重启 robot_full 恢复");
        try {
            sendSigInt();
        } catch (Exception e) {
            pcdSaving.set(false);
            throw e;
        }

        File pcdFile = new File(PCD_PATH);
        long lastSize = -1;
        long start = System.currentTimeMillis();
        try {
            while (true) {
                // 进程不是 Java 起的，只能靠 pgrep 判断它退没退
                boolean alive = isFastLioRunning();
                long curSize = pcdFile.exists() ? pcdFile.length() : 0;
                long elapsed = System.currentTimeMillis() - start;

                if (curSize != lastSize) {
                    log.info("[PCD保存中] 大小: {} MB, 已用时: {}s, 进程存活: {}",
                            curSize / 1024 / 1024, elapsed / 1000, alive);
                    lastSize = curSize;
                }

                if (!alive) {
                    log.info("✅ fast_lio 已退出，PCD 最终大小: {} MB，用时 {}s，保存到: {}",
                            curSize / 1024 / 1024, elapsed / 1000, PCD_PATH);
                    break;
                }

                // 安全阀：进程存活但超过安全超时仍未退出才强杀（比如卡死），
                // 正常大点云落盘期间文件在持续增长，不会走到这里
                if (elapsed > SAVE_SAFETY_TIMEOUT_MS) {
                    log.warn("fast_lio 超过 {} 分钟仍未退出，强制终止（PCD可能未完整保存），最终大小: {} MB",
                            SAVE_SAFETY_TIMEOUT_MS / 60000, curSize / 1024 / 1024);
                    new ProcessBuilder("bash", "-c", "pkill -9 -f '" + KILL_PATTERN + "'")
                            .start().waitFor(3, TimeUnit.SECONDS);
                    break;
                }

                Thread.sleep(2000);
            }
        } finally {
            pcdSaving.set(false);
        }
    }

    @PostMapping("/stop")
    @ApiOperation("手动停止建图触发PCD落盘（不转换地图，仅用于管理场景；正常保存流程走 /save 即可），接口立即返回")
    public Result<Map<String, Object>> stopMapping() {
        if (!isFastLioRunning()) {
            return Result.error("fast_lio 未在运行");
        }
        if (pcdSaving.get()) {
            return Result.error("上一次 PCD 正在保存中，请稍后再试（轮询 /pcd-status 查看进度）");
        }
        executor.submit(new Runnable() {
            public void run() {
                try {
                    doStopAndWait();
                } catch (Exception e) {
                    log.error("停止 fast_lio 失败", e);
                }
            }
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("stopping", true);
        res.put("pcdPath",  PCD_PATH);
        res.put("message",  "已发送停止信号，PCD 正在后台保存，请轮询 /ros2/mapping/pcd-status（saving=false 才代表保存完成）");
        return Result.OK(res);
    }

    // ==================== 取消建图（不碰任何进程） ====================

    /**
     * 退出建图模式，保留 fast_lio 已经累积的地图。
     *
     * 和 /restart 的区别是"要不要丢掉后台数据"：
     *   · /cancel  = 我不扫了，但已经扫的留着 → 只切回导航模式，不碰进程，瞬时返回
     *   · /restart = 这张图不要了，重头扫    → fast_lio 的地图在进程内存里没有 reset 接口，
     *                                          只能杀进程，而它是常驻栈的一环 → 整栈重启 ~20s
     *
     * 前端"取消建图"按的是前者，以前却接到了 /restart 上，扫到一半点一下整栈就没了。
     */
    @PostMapping("/cancel")
    @ApiOperation("取消建图，保留已扫描数据，不重启任何进程，立即返回")
    public Result<Map<String, Object>> cancelMapping() {
        if (pcdSaving.get() || converting.get()) {
            return Result.error("正在保存/转换地图，请等它完成");
        }
        // 建图模式下前端 robot_pose 取自 /Odometry，退出建图要切回 AMCL
        ros2BridgeService.setNavMode(true);
        // 停止往栅格里攒新点，但已攒的保留 —— 取消之后仍然可以直接点保存
        mappingGridService.setEnabled(false);
        log.info("⏸ 取消建图（fast_lio 继续运行，已扫描数据保留，未重启任何进程）");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("cancelled",      true);
        res.put("fastLioRunning", isFastLioRunning());
        res.put("gridCells",      mappingGridService.cellCount());
        res.put("message", "已退出建图模式，已扫描的数据保留，随时可再次开始建图或直接保存地图");
        return Result.OK(res);
    }

    // ==================== 清空重扫（放弃当前数据，强制重启） ====================

    @PostMapping("/restart")
    @ApiOperation("放弃当前建图数据并重新开始扫描（配合前端\"清空点云\"，保证前端预览和后台数据一致），接口立即返回")
    public Result<Map<String, Object>> restartMapping() {
        if (pcdSaving.get() || converting.get()) {
            return Result.error("正在保存/转换地图，暂不能清空重扫，请等保存完成后再试");
        }

        // 栅格模式下出图只认 Java 这边累积的栅格，清掉它就等于重头扫了，
        // fast_lio 进程内存里那份 PCD 已经没人用，没必要为它重启整栈。
        if (!"pcd".equalsIgnoreCase(saveMode)) {
            mappingGridService.clear();
            mappingGridService.setEnabled(true);
            ros2BridgeService.setNavMode(false);
            log.info("🗑 已清空建图栅格，立即重新开始累积（未重启任何进程）");
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("restarting", false);
            res.put("message", "已清空并重新开始扫描（未重启任何进程）");
            return Result.OK(res);
        }

        // 以下是 pcd 模式的老路径：fast_lio 的地图累积在进程内存里，没有"清空"接口，
        // 只能靠重启进程丢掉。而它是常驻栈的一部分，单独重启它会让 Nav2 失去 camera_init，
        // 所以整栈一起重启。这里不走 SIGINT：反正数据要丢弃，没必要等它把没用的 PCD 写完。
        executor.submit(new Runnable() {
            public void run() {
                try {
                    log.info("🗑 丢弃当前建图数据，重启 robot_full 重新开始扫描...");
                    restartRobotFull();
                    ros2BridgeService.setNavMode(false);
                    ros2BridgeService.resubscribeFastLioTopics();
                    log.info("✅ 已重新开始扫描");
                } catch (Exception e) {
                    log.error("清空重扫失败", e);
                }
            }
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("restarting", true);
        res.put("message", "正在重启导航栈以清空建图数据，约 "
                + (robotFullReadyWaitMs / 1000) + " 秒后可重新开始扫描");
        return Result.OK(res);
    }

    /**
     * 重启整个 robot_full 导航栈（rosbridge + 雷达驱动 + fast_lio + pc2scan + Nav2）。
     *
     * 用在两个地方：
     *   · /save 转换完地图之后（SIGINT 已经把常驻 fast_lio 打死，必须拉回来，顺带让新地图生效）
     *   · /restart 清空重扫（fast_lio 的地图只能靠重启进程丢掉）
     *
     * 先 pkill 再启，是因为残余进程和新拉起的会形成双实例——两个同名 laser_mapping
     * 同时往 TF 树写 camera_init→body，正是之前建图重影的根因。
     *
     * 阻塞方法，只能在后台线程里调用。
     */
    private void restartRobotFull() throws Exception {
        log.info("[robot_full] 清理残余进程...");
        new ProcessBuilder("bash", "-c",
                "pkill -9 -f '" + STACK_KILL_PATTERN + "'")
                .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
        Thread.sleep(3000);
        // rosbridge 没退干净的话，新栈会一直刷 Errno 98 并且永远连不上，
        // 与其等 20s 超时后报"fast_lio 没起来"误导排查，不如在这里就说清楚。
        waitRosbridgePortFree(10000);

        String cmd = "source " + rosSetupBash
                + " && source " + SETUP_BASH
                + " && LIDAR_TYPE=" + lastLidarType
                + " SELF_FILTER=" + robotFullSelfFilter
                + " ros2 launch " + robotFullLaunchFile;
        log.info("[robot_full] 启动: {}", cmd);

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.directory(new File(System.getProperty("user.home")));
        pb.redirectErrorStream(true);
        pb.environment().put("QT_QPA_PLATFORM", "offscreen");
        robotFullProcess = pb.start();

        final Process proc = robotFullProcess;
        executor.submit(new Runnable() {
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.info("[robot_full] {}", line);
                    }
                } catch (IOException ignored) {}
            }
        });

        // launch 里 Nav2 在 T+12s 才起，等够时间再让上层去重连 rosbridge
        log.info("[robot_full] 等待导航栈就绪 {}ms...", robotFullReadyWaitMs);
        Thread.sleep(robotFullReadyWaitMs);

        if (!isFastLioRunning()) {
            throw new RuntimeException("robot_full 重启后 fast_lio 仍未运行，请查看上面 [robot_full] 日志");
        }
        // rosbridge 也是新进程，Java 这边的 WebSocket 连接已经断了，必须重连
        ros2BridgeService.connect();
        Thread.sleep(2000);
        log.info("[robot_full] ✅ 导航栈已就绪");
    }

    /**
     * 等 rosbridge 的 9090 端口释放。
     * 端口没放开就启新栈，rosbridge 会一路 "Unable to start server: [Errno 98]" 重试到天荒地老。
     */
    private void waitRosbridgePortFree(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Process p = new ProcessBuilder("bash", "-c",
                    "ss -ltnH 2>/dev/null | grep -q ':9090 '")
                    .redirectErrorStream(true).start();
            // grep 无匹配(退出码非 0) = 端口已空闲
            if (p.waitFor() != 0) {
                return;
            }
            Thread.sleep(500);
        }
        log.warn("[robot_full] ⚠ 9090 端口仍被占用，新 rosbridge 大概率起不来。"
                + "手工排查: ss -ltnp | grep 9090");
    }

    /**
     * 发送 SIGINT 到 fast_lio（唯一能触发 PCD 落盘的信号）。
     * 进程是常驻栈起的，Java 没有句柄，只能 pkill 按进程名发信号。
     */
    private void sendSigInt() throws Exception {
        // fastlio_mapping 和 livox_self_filter.py 一起发：只发前者的话 self_filter 还活着，
        // 承载它俩的 `ros2 launch` 父进程就不退出，doStopAndWait() 会一直等到 10 分钟安全阀。
        Process pkill = new ProcessBuilder("bash", "-c",
                "pkill -INT -f '" + KILL_PATTERN + "'")
                .redirectErrorStream(true)
                .start();
        pkill.waitFor(3, TimeUnit.SECONDS);
        // 退出码 1 = 没有进程匹配（fast_lio 本来就没在跑），不是错误
        log.info("pkill -INT -f '{}' 执行完成，退出码: {}", KILL_PATTERN, pkill.exitValue());
    }

    // ==================== 轮询 PCD 状态 ====================

    @GetMapping("/pcd-status")
    @ApiOperation("查询PCD文件是否已生成（saving=true 表示仍在后台写盘，不要在这时候调用 /save）")
    public Result<Map<String, Object>> getPcdStatus() {
        File pcdFile  = new File(PCD_PATH);
        boolean exists = pcdFile.exists() && pcdFile.length() > 0;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("exists",   exists);
        status.put("saving",   pcdSaving.get());
        status.put("path",     PCD_PATH);
        status.put("sizeKB",   exists ? pcdFile.length() / 1024 : 0);
        status.put("modified", exists ? new Date(pcdFile.lastModified()).toString() : null);
        return Result.OK(status);
    }

    // ==================== 运行状态 ====================

    @GetMapping("/status")
    @ApiOperation("查询运行状态")
    public Result<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running",   isFastLioRunning());
        status.put("saving",    pcdSaving.get());
        status.put("pcdExists", new File(PCD_PATH).exists());
        status.put("pcdPath",   PCD_PATH);
        status.put("saveMode",  saveMode);
        // 栅格模式下地图数据全在这里，现场排查"保存出来是空图"先看它涨不涨
        status.put("grid",      mappingGridService.status());
        return Result.OK(status);
    }

    // ==================== 保存导航地图 ====================

    /**
     * 保存导航地图。两条路径，由 application.yml 的 mapping.save-mode 决定：
     *
     * grid(默认) —— saveFromGrid()：把 Java 实时累积的占据栅格直接写成 pgm/yaml。
     *   不发 SIGINT、不碰 fast_lio、不重启整栈，秒级完成，保存完 Nav2 一直活着，
     *   可以马上继续导航。这是为了解掉下面那条死锁链才做的。
     *
     * pcd —— 老流程，保留作回退：
     *   1) fast_lio 还在运行就先发 SIGINT 触发完整 PCD 落盘（全量数据只有这一步才写盘）
     *   2) 把 PCD 转换成 pgm/yaml
     *   3) SIGINT 已经把常驻 fast_lio 打死、Nav2 失去 camera_init，必须重启整栈
     *   死锁链：PCD 只有 SIGINT 才落盘 → 必须杀 fast_lio → camera_init 消失 → Nav2 全废
     *          → 整栈重启，保存完还得等 20 多秒才能导航。
     *
     * 两种模式都丢到后台线程做，接口立即返回，前端轮询 /save-status。
     */
    @PostMapping("/save")
    @ApiOperation("保存为导航地图pgm+yaml（Nav2可直接加载）；默认 grid 模式不中断 fast_lio、不重启导航栈。接口立即返回，轮询 /save-status 查看结果")
    public Result<Map<String, Object>> saveMap(@RequestBody Map<String, String> body) {
        if (converting.get()) {
            return Result.error("上一次保存/转换仍在进行中，请轮询 /ros2/mapping/save-status");
        }
        if (pcdSaving.get()) {
            return Result.error("PCD 仍在后台写盘中，请稍后再试");
        }
        if (!converting.compareAndSet(false, true)) {
            return Result.error("上一次保存/转换仍在进行中");
        }

        lastSaveResult = null;
        final Map<String, String> params = (body != null) ? body : new HashMap<>();

        executor.submit(new Runnable() {
            public void run() {
                Map<String, Object> result = new LinkedHashMap<>();
                boolean fastLioKilled = false;
                try {
                    if (!"pcd".equalsIgnoreCase(saveMode)) {
                        saveFromGrid(params, result);
                        return;
                    }

                    log.info("[保存地图] save-mode=pcd，走老流程：SIGINT 落盘 + 转换 + 整栈重启");
                    if (isFastLioRunning()) {
                        log.info("[保存地图] fast_lio 仍在运行，先停止以触发完整 PCD 落盘...");
                        doStopAndWait();
                        fastLioKilled = true;
                    }

                    File pcdFile = new File(PCD_PATH);
                    if (!pcdFile.exists() || pcdFile.length() == 0) {
                        throw new RuntimeException("PCD 文件不存在: " + PCD_PATH + "，请先完成建图");
                    }

                    String filename   = params.getOrDefault("filename", "map_" + System.currentTimeMillis());
                    filename          = filename.replaceAll("[^a-zA-Z0-9_\\-]", "_");
                    double resolution = Double.parseDouble(params.getOrDefault("resolution", "0.05"));
                    double zMin       = Double.parseDouble(params.getOrDefault("zMin", "-1"));
                    double zMax       = Double.parseDouble(params.getOrDefault("zMax", "10"));

                    Files.createDirectories(Paths.get(MAPS_DIR));
                    String outPath = MAPS_DIR + "/" + filename;

                    log.info("[保存地图] 转换: {} → {}.pgm/.yaml (res={}, z={}~{})",
                            PCD_PATH, outPath, resolution, zMin, zMax);

                    long totalPoints = runConvert(pcdFile.getAbsolutePath(), outPath, resolution, zMin, zMax);

                    if (!new File(outPath + ".pgm").exists() || !new File(outPath + ".yaml").exists()) {
                        throw new RuntimeException("转换完成但文件未找到，请检查 Python 依赖：pip3 install Pillow numpy");
                    }

                    // 挂上 last_used 软链接，下面重启 robot_full 时 resolve_initial_map()
                    // 的第一层 fallback 就会直接加载这张刚存的新图
                    mapController.updateLastUsedSymlink(filename);

                    result.put("success",     true);
                    result.put("filename",    filename);
                    result.put("pgm",         outPath + ".pgm");
                    result.put("yaml",        outPath + ".yaml");
                    result.put("totalPoints", totalPoints); // PCD 文件里的真实总点数(去重后的最终建图结果)
                    log.info("[保存地图] ✅ 完成，共 {} 点", totalPoints);

                } catch (Exception e) {
                    log.error("[保存地图] 失败", e);
                    result.put("success", false);
                    result.put("message", e.getMessage());
                } finally {
                    // 落盘用的 SIGINT 把常驻 fast_lio 打死了，Nav2 此刻正在刷
                    // "camera_init frame does not exist"。不管转换成没成都得把整栈拉回来。
                    if (fastLioKilled) {
                        try {
                            restartRobotFull();
                            ros2BridgeService.setNavMode(true);
                            ros2BridgeService.resubscribeFastLioTopics();
                            result.put("robotFullRestarted", true);
                        } catch (Exception re) {
                            log.error("[保存地图] robot_full 重启失败，导航栈需要人工恢复："
                                    + "ros2 launch {}", robotFullLaunchFile, re);
                            result.put("robotFullRestarted", false);
                            result.put("restartError", re.getMessage());
                        }
                    }
                    lastSaveResult = result;
                    converting.set(false);
                }
            }
        });

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("processing", true);
        res.put("message", "已开始保存地图（停止建图落盘 + 转换pgm），请轮询 /ros2/mapping/save-status 查看结果");
        return Result.OK(res);
    }

    /**
     * 栅格模式保存：纯内存 → 文件，不发 SIGINT、不碰 fast_lio、不重启整栈，秒级完成。
     * 保存完 Nav2 还在正常跑，随时可以继续导航。
     *
     * 只更新 last_used 软链接，不自动热切换到新图 —— 切图会连带重置 AMCL 初始位姿，
     * 该由用户在地图管理里显式操作(/api/map/load 走的是 load_map service，同样无需重启)。
     */
    private void saveFromGrid(Map<String, String> params, Map<String, Object> result) throws Exception {
        String filename = params.getOrDefault("filename", "map_" + System.currentTimeMillis());
        filename        = filename.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        double zMin     = Double.parseDouble(params.getOrDefault("zMin", "-1"));
        double zMax     = Double.parseDouble(params.getOrDefault("zMax", "10"));
        double reqRes   = Double.parseDouble(params.getOrDefault("resolution", "0.05"));
        // 动态物过滤帧数；0 = 用 application.yml 的 mapping.grid.min-hits
        int minHits     = Integer.parseInt(params.getOrDefault("minHits", "0"));

        double gridRes = mappingGridService.getResolution();
        if (Math.abs(reqRes - gridRes) > 1e-9) {
            // 栅格在累积时就按 gridRes 打好了，保存时插值出来的精度是假的，直接按累积值出图
            log.warn("[保存地图] 请求分辨率 {} 与栅格累积分辨率 {} 不一致，按后者输出。"
                    + "要改请调 application.yml 的 mapping.grid.resolution 后重新建图", reqRes, gridRes);
        }

        log.info("[保存地图] 栅格模式：已累积 {} 个占据格，导出中(不停 fast_lio)...",
                mappingGridService.cellCount());

        Files.createDirectories(Paths.get(MAPS_DIR));
        Map<String, Object> g = mappingGridService.saveAsPgm(MAPS_DIR + "/" + filename, zMin, zMax, minHits);

        // 挂软链接，下次 robot_full 启动时自动加载这张新图
        mapController.updateLastUsedSymlink(filename);

        result.put("success",  true);
        result.put("mode",     "grid");
        result.put("filename", filename);
        result.putAll(g);
        result.put("totalPoints", g.get("occupiedCells"));
        result.put("robotFullRestarted", false);
        result.put("message", "地图已保存，fast_lio 未中断、导航栈无需重启，可直接继续导航。"
                + "要让 Nav2 用这张新图，去地图管理里切换一次(热切换，同样不重启)");
        log.info("[保存地图] ✅ 栅格模式完成，未重启任何进程");
    }

    // ==================== 出图预览 ====================

    /**
     * 把当前累积的栅格按给定 z 范围渲染成 PNG 返回，让前端能在保存**之前**就看到出图效果。
     *
     * 走的是 MappingGridService.render()，和 /save 完全同一段代码、同一份数据、同一套过滤
     * (zMask + minHits)，所以这里看到的就是保存下来的 pgm，逐像素一致。
     *
     * 存在的意义：前端画布上那份点云是它自己攒的另一套数据 —— 每帧最多推 3000 点、
     * 还带 0.08m 体素去重和"超 10 万点自动 ×1.5 粗化"，而栅格吃的是全量点 + 5cm 整格涂黑。
     * 两条链路天生对不上，靠调参数是调不齐的，只能让预览直接来自栅格本身。
     */
    @GetMapping("/grid-preview")
    @ApiOperation("预览当前栅格的出图效果(与保存的 pgm 逐像素一致)，返回 base64 PNG + 原点/分辨率")
    public Result<Map<String, Object>> gridPreview(
            @RequestParam(defaultValue = "-99") double zMin,
            @RequestParam(defaultValue = "99")  double zMax,
            @RequestParam(defaultValue = "0")   int minHits) {
        if ("pcd".equalsIgnoreCase(saveMode)) {
            return Result.error("当前 mapping.save-mode=pcd，出图走 fast_lio 的 PCD，Java 这边没有栅格可预览");
        }
        try {
            return Result.OK(mappingGridService.renderPreview(zMin, zMax, minHits));
        } catch (Exception e) {
            // 没累积到点/过滤后全空，都是 IllegalStateException，消息里已经写明该怎么调
            return Result.error(e.getMessage());
        }
    }

    /**
     * 人工擦掉一块区域(世界坐标，米)。建图时躲不掉的人/临时料堆/开着的门就靠它抹掉。
     *
     * 记的是矩形不是删格子 —— 边扫边擦时，删掉的格子下一帧就会被重新打上点
     * (现象是"擦掉了、过两秒又冒出来")，而矩形是持久的。代价是这块区域后来
     * 有真障碍也画不出来，所以给了 undo/clear。
     */
    @PostMapping("/grid-erase")
    @ApiOperation("擦除出图中的一块矩形区域（世界坐标 x1,y1,x2,y2，单位米），预览和保存同步生效")
    public Result<Map<String, Object>> gridErase(@RequestBody Map<String, String> body) {
        try {
            double x1 = Double.parseDouble(body.get("x1"));
            double y1 = Double.parseDouble(body.get("y1"));
            double x2 = Double.parseDouble(body.get("x2"));
            double y2 = Double.parseDouble(body.get("y2"));
            int n = mappingGridService.addErase(x1, y1, x2, y2);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("erasedCells", n);
            r.put("rects",       mappingGridService.getErasedRects().size());
            return Result.OK(r);
        } catch (Exception e) {
            return Result.error("擦除失败: " + e.getMessage());
        }
    }

    @PostMapping("/grid-erase/undo")
    @ApiOperation("撤销最后一次擦除")
    public Result<Map<String, Object>> gridEraseUndo() {
        boolean ok = mappingGridService.undoErase();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("undone", ok);
        r.put("rects",  mappingGridService.getErasedRects().size());
        return ok ? Result.OK(r) : Result.error("没有可撤销的擦除区");
    }

    @PostMapping("/grid-erase/clear")
    @ApiOperation("清空所有擦除区，恢复成原始扫描结果")
    public Result<Map<String, Object>> gridEraseClear() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cleared", mappingGridService.clearErase());
        return Result.OK(r);
    }

    @GetMapping("/save-status")
    @ApiOperation("查询 /save 异步保存转换的进度和结果")
    public Result<Map<String, Object>> getSaveStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("converting", converting.get());
        status.put("pcdSaving",  pcdSaving.get());
        status.put("result",     lastSaveResult); // null=进行中或从未保存过；否则含 success/message/totalPoints等
        return Result.OK(status);
    }

    // ==================== 地图列表 ====================

    @GetMapping("/list")
    @ApiOperation("获取已保存导航地图列表")
    public Result<List<Map<String, Object>>> getMapList() {
        try {
            File dir = new File(MAPS_DIR);
            if (!dir.exists()) return Result.OK(new ArrayList<>());
            File[] files = dir.listFiles(new FilenameFilter() {
                public boolean accept(File d, String n) { return n.endsWith(".yaml"); }
            });
            if (files == null) return Result.OK(new ArrayList<>());
            List<Map<String, Object>> list = new ArrayList<>();
            for (File f : files) {
                String name = f.getName().replace(".yaml", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",       name);
                m.put("createTime", f.lastModified());
                m.put("hasImage",   new File(MAPS_DIR + "/" + name + ".pgm").exists());
                list.add(m);
            }
            return Result.OK(list);
        } catch (Exception e) {
            return Result.error("获取列表失败: " + e.getMessage());
        }
    }

    // ==================== Python 转换工具 ====================

    /**
     * @return PCD 文件里的真实总点数(从 Python 脚本 "总点数: N" 那行日志里解析出来，解析失败返回 -1)
     */
    private long runConvert(String pcdPath, String outPath,
                            double res, double zMin, double zMax) throws Exception {
        String script = buildScript(pcdPath, outPath, res, zMin, zMax);
        File   tmp    = File.createTempFile("pcd2pgm_", ".py");
        Files.write(tmp.toPath(), script.getBytes("UTF-8"));
        long totalPoints = -1;
        try {
            String cmd = "source " + SETUP_BASH + " && python3 " + tmp.getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[pcd2pgm] {}", line);
                    if (line.contains("总点数:")) {
                        try {
                            totalPoints = Long.parseLong(line.replaceAll("[^0-9]", ""));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new RuntimeException("转换超时(>120s)"); }
            if (p.exitValue() != 0) throw new RuntimeException("Python 脚本执行失败，请检查依赖: pip3 install Pillow numpy");
        } finally {
            tmp.delete();
        }
        return totalPoints;
    }

    /**
     * 构建 Python 脚本内容
     *
     * 修复点：
     *   1. 不再使用 open3d（与 NumPy 2.x 不兼容）
     *   2. 原生支持 binary / binary_compressed / ascii 三种 PCD 格式
     *   3. 以 'rb' 模式读文件，彻底避免 UnicodeDecodeError
     */
    private String buildScript(String pcdPath, String outPath,
                               double res, double zMin, double zMax) {
        // 把 outPath 拆成目录和文件名（Python 端需要分别用）
        String outDir  = outPath.contains("/")
                ? outPath.substring(0, outPath.lastIndexOf('/'))
                : ".";
        String outName = outPath.contains("/")
                ? outPath.substring(outPath.lastIndexOf('/') + 1)
                : outPath;

        return "#!/usr/bin/env python3\n"
                + "import numpy as np\n"
                + "import struct, os, sys\n"
                + "from PIL import Image\n"
                + "\n"
                + "# ===== Java 传入的参数 =====\n"
                + "PCD_PATH   = '" + pcdPath  + "'\n"
                + "OUTPUT_DIR = '" + outDir   + "'\n"
                + "OUT_NAME   = '" + outName  + "'\n"
                + "RESOLUTION = " + res       + "\n"
                + "Z_MIN      = " + zMin      + "\n"
                + "Z_MAX      = " + zMax      + "\n"
                + "MARGIN     = 1.0\n"
                + "\n"
                + "def read_pcd(filepath):\n"
                + "    \"\"\"纯Python读取PCD，支持ascii/binary，无需open3d\"\"\"\n"
                + "    with open(filepath, 'rb') as f:\n"
                + "        headers = {}\n"
                + "        while True:\n"
                + "            line = f.readline().decode('utf-8', errors='ignore').strip()\n"
                + "            if line.upper().startswith('DATA'):\n"
                + "                data_type = line.split()[1].lower()\n"
                + "                break\n"
                + "            if line:\n"
                + "                parts = line.split()\n"
                + "                if len(parts) >= 2:\n"
                + "                    headers[parts[0].upper()] = parts[1:]\n"
                + "\n"
                + "        fields  = headers.get('FIELDS', [])\n"
                + "        sizes   = [int(s) for s in headers.get('SIZE',  [])]\n"
                + "        types   = headers.get('TYPE',  [])\n"
                + "        counts  = [int(c) for c in headers.get('COUNT', [])]\n"
                + "        num_pts = int(headers.get('POINTS', ['0'])[0])\n"
                + "        print(f'  格式: {data_type}, 点数: {num_pts}, 字段: {fields}')\n"
                + "\n"
                + "        try:\n"
                + "            xi = fields.index('x')\n"
                + "            yi = fields.index('y')\n"
                + "            zi = fields.index('z')\n"
                + "        except ValueError:\n"
                + "            print('ERROR: PCD 文件中没有 x/y/z 字段'); sys.exit(1)\n"
                + "\n"
                // ⚠ 这里全部走 numpy 向量化，绝对不能退回逐点 for + struct.unpack_from。
                //   fast_lio 一次建图动辄几百万点，逐点解析要几分钟，直接撞上 runConvert 的
                //   120s 超时 → 报"转换超时"。np.frombuffer 一次读完是亚秒级。
                + "        tmap = {('F',4):'f4', ('F',8):'f8',\n"
                + "                ('I',1):'i1', ('I',2):'i2', ('I',4):'i4', ('I',8):'i8',\n"
                + "                ('U',1):'u1', ('U',2):'u2', ('U',4):'u4', ('U',8):'u8'}\n"
                + "        names, formats = [], []\n"
                + "        for i in range(len(fields)):\n"
                + "            # PCD 用 '_' 表示对齐填充字段，同一文件里会出现多次；numpy 不允许重名\n"
                + "            names.append(fields[i] if fields[i] != '_' else '_pad%d' % i)\n"
                + "            ft = tmap.get((types[i], sizes[i]), 'V%d' % sizes[i])\n"
                + "            formats.append(ft if counts[i] == 1 else (ft, counts[i]))\n"
                + "        # PCD binary 是紧密打包的(point_step = sum(size*count))，不做结构体对齐，\n"
                + "        # 所以这里必须用默认的 packed dtype，加 align=True 会整体错位\n"
                + "        dt = np.dtype({'names': names, 'formats': formats})\n"
                + "\n"
                + "        if data_type == 'ascii':\n"
                + "            a = np.loadtxt(f, dtype=np.float32, usecols=(xi, yi, zi), ndmin=2)\n"
                + "            return np.ascontiguousarray(a, dtype=np.float32)\n"
                + "\n"
                + "        elif data_type == 'binary':\n"
                + "            a = np.frombuffer(f.read(dt.itemsize * num_pts), dtype=dt, count=num_pts)\n"
                + "            return np.stack([a['x'], a['y'], a['z']], axis=1).astype(np.float32)\n"
                + "\n"
                + "        elif data_type == 'binary_compressed':\n"
                + "            try:\n"
                + "                import lzf\n"
                + "            except ImportError:\n"
                + "                print('binary_compressed 格式需要 lzf: pip3 install lzf'); sys.exit(1)\n"
                + "            csize = struct.unpack('I', f.read(4))[0]\n"
                + "            dsize = struct.unpack('I', f.read(4))[0]\n"
                + "            raw   = lzf.decompress(f.read(csize), dsize)\n"
                + "            # ⚠ binary_compressed 是 field-major(每个字段的全部取值连续存放)，\n"
                + "            #   跟 binary 的 point-major 布局完全不同，不能套上面的 dt\n"
                + "            cols, off = {}, 0\n"
                + "            for i in range(len(fields)):\n"
                + "                if i in (xi, yi, zi):\n"
                + "                    cols[i] = np.frombuffer(raw, dtype=formats[i], count=num_pts, offset=off)\n"
                + "                off += sizes[i] * counts[i] * num_pts\n"
                + "            return np.stack([cols[xi], cols[yi], cols[zi]], axis=1).astype(np.float32)\n"
                + "        else:\n"
                + "            print(f'ERROR: 不支持的格式: {data_type}'); sys.exit(1)\n"
                + "\n"
                + "# ===== 主流程 =====\n"
                + "os.makedirs(OUTPUT_DIR, exist_ok=True)\n"
                + "print(f'读取PCD: {PCD_PATH}')\n"
                + "if not os.path.exists(PCD_PATH):\n"
                + "    print('ERROR: 文件不存在'); sys.exit(1)\n"
                + "\n"
                + "pts = read_pcd(PCD_PATH)\n"
                + "print(f'总点数: {len(pts)}')\n"
                + "print(f'  Z: {pts[:,2].min():.2f} ~ {pts[:,2].max():.2f} m')\n"
                + "print(f'  X: {pts[:,0].min():.2f} ~ {pts[:,0].max():.2f} m')\n"
                + "print(f'  Y: {pts[:,1].min():.2f} ~ {pts[:,1].max():.2f} m')\n"
                + "\n"
                + "mask  = (pts[:,2] > Z_MIN) & (pts[:,2] < Z_MAX)\n"
                + "pts2d = pts[mask][:, :2]\n"
                + "print(f'高度过滤 [{Z_MIN}, {Z_MAX}]m 后: {len(pts2d)} 点')\n"
                + "if len(pts2d) == 0:\n"
                + "    print(f'ERROR: 过滤后无点，请调整 Z_MIN/Z_MAX（当前范围见上方Z轴信息）'); sys.exit(1)\n"
                + "\n"
                + "x0 = pts2d[:,0].min() - MARGIN\n"
                + "y0 = pts2d[:,1].min() - MARGIN\n"
                + "x1 = pts2d[:,0].max() + MARGIN\n"
                + "y1 = pts2d[:,1].max() + MARGIN\n"
                + "W  = int((x1 - x0) / RESOLUTION) + 1\n"
                + "H  = int((y1 - y0) / RESOLUTION) + 1\n"
                + "print(f'地图尺寸: {W} x {H} px')\n"
                + "\n"
                + "grid = np.full((H, W), 255, dtype=np.uint8)\n"
                + "ix   = ((pts2d[:,0] - x0) / RESOLUTION).astype(int)\n"
                + "iy   = ((pts2d[:,1] - y0) / RESOLUTION).astype(int)\n"
                + "ok   = (ix >= 0) & (ix < W) & (iy >= 0) & (iy < H)\n"
                + "grid[H - 1 - iy[ok], ix[ok]] = 0\n"
                + "\n"
                + "pgm_path  = os.path.join(OUTPUT_DIR, OUT_NAME + '.pgm')\n"
                + "yaml_path = os.path.join(OUTPUT_DIR, OUT_NAME + '.yaml')\n"
                + "\n"
                + "Image.fromarray(grid).save(pgm_path)\n"
                + "print(f'PGM 已保存: {pgm_path}')\n"
                + "\n"
                + "with open(yaml_path, 'w') as f:\n"
                + "    f.write(f'image: {pgm_path}\\n')\n"
                + "    f.write(f'resolution: {RESOLUTION}\\n')\n"
                + "    f.write(f'origin: [{x0:.4f}, {y0:.4f}, 0.0]\\n')\n"
                + "    f.write('negate: 0\\n')\n"
                + "    f.write('occupied_thresh: 0.65\\n')\n"
                + "    f.write('free_thresh: 0.196\\n')\n"
                + "print(f'YAML 已保存: {yaml_path}')\n"
                + "print('=== 转换完成，可用于 Nav2 导航 ===')\n";
    }
}