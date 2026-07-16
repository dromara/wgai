package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.model.NavigationGoalDTO;
import org.jeecg.modules.ros2.service.NavigationService;
import org.jeecg.modules.ros2.service.ROS2BridgeService;
import org.jeecg.modules.ros2.service.RobotHardwareService;
import org.jeecg.modules.ros2.service.WebSocketPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 导航控制器
 *
 * Bug fixes (v2):
 *   1. cancel() — 新增 cancelNav2Action(): 通过 subprocess 调用
 *      /navigate_to_pose/_action/cancel_goal 真正终止 Nav2 action,
 *      原来只发零速度无法阻止 controller_server 继续发速度指令。
 *
 *   2. triggerGlobalLocalization() — 触发成功后向前端推送 WebSocket 通知
 *      (localizationType=global_localization),前端不再"无反应";
 *      同时更新 lastGlobalLocalizationTime,供 setGoal 做收敛等待保护。
 *
 *   3. setGoal() — 全局重定位触发后 MIN_CONVERGENCE_MS (默认 15s) 内拒绝接受
 *      导航目标:此窗口内 AMCL 粒子尚未收敛,map→camera_init TF 不稳定,
 *      costmap 消息过滤器会丢弃 scan(TF cache 比 scan 时间戳新),
 *      导致 GridBased planner 找不到路径。
 */
@Slf4j
@RestController
@RequestMapping("/api/navigation")
@Api(tags = "导航控制")
public class NavigationController {

    private static final String SETUP_BASH = "/home/lio_ws/install/setup.bash";
    private static final String ROS_BASH   = "/opt/ros/humble/setup.bash";

    /**
     * 全局重定位后,拒绝接受导航目标的最短等待时间(毫秒)。
     * AMCL 需要机器人移动 + 粒子滤波迭代才能收敛到正确位姿,
     * 过早发目标会导致 TF 不稳定 → costmap 空 → 规划失败。
     * 15 秒是保守下限;如果场景较大或初始位置偏差大,可以改为 20~30s。
     */
    private static final long MIN_CONVERGENCE_MS = 15_000L;

    /** 最近一次触发全局重定位的时间戳,0 表示未触发过 */
    private final AtomicLong lastGlobalLocalizationTime = new AtomicLong(0);

    @Autowired
    private NavigationService navigationService;

    @Autowired
    private ROS2BridgeService bridgeService;

    @Autowired
    private WebSocketPushService pushService;  // 注入 WebSocket 推送服务

    // ===================== 发送导航目标 =====================

    /**
     * 设置导航目标。
     *
     * Fix (Bug 2): 全局重定位触发后 MIN_CONVERGENCE_MS 内拒绝接受目标。
     * 原因: reinitialize_global_localization 之后 AMCL 粒子撒满全图,
     *       map → camera_init TF 短暂无效或抖动,fast_lio 的 TF buffer 也会重置,
     *       costmap message_filter 会丢弃 scan(错误:"frame 'body' at time T
     *       is earlier than all data in transform cache"),导致 GridBased
     *       planner 报 "failed to create plan with tolerance 0.50"。
     *       等待 AMCL 收敛(遥控机器人转圈)再发目标可彻底解决。
     */
    @PostMapping("/goal")
    @ApiOperation("设置导航目标")
    public Result<Void> setGoal(@Validated @RequestBody NavigationGoalDTO goal) {
        // ⭐ Bug 2 fix: 全局重定位收敛保护
        long elapsed = System.currentTimeMillis() - lastGlobalLocalizationTime.get();
        if (lastGlobalLocalizationTime.get() > 0 && elapsed < MIN_CONVERGENCE_MS) {
            long remaining = (MIN_CONVERGENCE_MS - elapsed) / 1000;
            return Result.error(
                    "全局重定位刚触发,AMCL 正在收敛(TF 不稳定)。" +
                            "请先遥控机器人慢速旋转,等待约 " + remaining + " 秒后再发导航目标。" +
                            "或调用 /api/navigation/localization-ready 确认收敛状态后再试。"
            );
        }

        log.info("[导航目标] 收到目标点: x={}, y={}, theta={}°",
                goal.getX(), goal.getY(), Math.toDegrees(goal.getTheta()));
        if (hardwareService.isEmergencyStopActive()) {
            log.warn("[导航目标] 检测到 Java 侧急停锁, 设置新目标前自动解除, 恢复 cmd_vel -> PLC 转发");
            hardwareService.clearEmergencyStopLock();
        }
        navigationService.sendNavigationGoal(goal.getX(), goal.getY(), goal.getTheta());
        return Result.OK("导航目标已设置");
    }

    // ===================== 取消导航 =====================

    /**
     * 取消导航。
     *
     * Fix (Bug 3): 原来只发 5 次零速度,但 Nav2 的 controller_server 仍在运行
     * NavigateToPose action,每 100 ms 会覆盖一次 /cmd_vel,零速度无效。
     * 正确做法:先通过 subprocess 调用 /navigate_to_pose/_action/cancel_goal
     * service 终止 action,再发零速度作为兜底保障。
     */
    @PostMapping("/cancel")
    @ApiOperation("取消导航")
    public Result<Void> cancel() {
        // ⭐ Bug 3 fix: 真正取消 Nav2 action
        boolean actionCancelled = cancelNav2Action();
        // 零速度兜底(action cancel 需要一个控制周期才生效)
        navigationService.cancelNavigation();

        if (actionCancelled) {
            return Result.OK("导航已取消(Nav2 action 已终止)");
        } else {
            return Result.OK("导航取消指令已发送(零速度)。Nav2 action cancel 调用失败,请确认 Nav2 正在运行");
        }
    }

    /**
     * 通过 subprocess 调用 /navigate_to_pose/_action/cancel_goal ROS2 service。
     *
     * CancelGoal 请求格式:
     *   goal_id.uuid = [0]*16  → 表示"取消所有目标"(不指定特定 goal UUID)
     *   stamp = {sec:0, nanosec:0} → 表示"取消所有时间戳 ≤ 0 的目标",
     *           配合全零 uuid,等效于 cancel all
     *
     * 参考: action_msgs/srv/CancelGoal.srv 规范
     *
     * @return true 表示 service call 成功(exit code 0)
     */
    private boolean cancelNav2Action() {
        try {
            // 构造全零 uuid 列表(16 字节)
            String uuid = "[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]";
            String goalInfo = "{goal_id: {uuid: " + uuid + "}, stamp: {sec: 0, nanosec: 0}}";
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 service call /navigate_to_pose/_action/cancel_goal"
                    + " action_msgs/srv/CancelGoal"
                    + " \"{goal_info: " + goalInfo + "}\"";

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 读取并丢弃输出,防止缓冲区阻塞
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }

            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("⏱️ cancel_goal 调用超时(5s),Nav2 可能未运行");
                return false;
            }

            int exitCode = p.exitValue();
            if (exitCode == 0) {
                log.info("✅ Nav2 NavigateToPose action 已取消");
                return true;
            } else {
                log.warn("⚠️ cancel_goal service 返回非零退出码: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.warn("取消 Nav2 action 失败: {}", e.getMessage());
            return false;
        }
    }

    // ===================== advertise =====================

    @PostMapping("/advertise")
    @ApiOperation("advertise 导航话题(WebSocket 重连后调用)")
    public Result<Void> advertise() {
        if (!bridgeService.isConnected()) {
            return Result.OK("未连接 ROS WebSocket,请先连接");
        }
        bridgeService.resubscribeNav2Topics();
        navigationService.advertiseTopics();
        bridgeService.setNavMode(true);
        return Result.OK("话题已 advertise (导航模式)");
    }

    // ===================== 全局重定位 =====================

    /**
     * 触发 AMCL 全局重定位。
     *
     * Fix (Bug 1): 原来只返回 HTTP 响应,前端没有 WebSocket 通知 → "没反应"。
     * 新增:
     *   a) 记录 lastGlobalLocalizationTime 供 setGoal() 做收敛等待保护。
     *   b) 调用 pushService 推送 WebSocket 事件,前端可据此显示"收敛中"状态。
     *      事件格式由 WebSocketPushService.pushLocalizationStatus() 定义,
     *      前端监听 "localization_status" 事件,status="globalizing" 时
     *      显示旋转图标或提示文案,直到收到 status="converged"(由前端订阅
     *      /amcl_pose 后判断协方差是否缩小来触发)。
     *
     * 注意: reinitialize_global_localization 之后约 10~30 秒内:
     *   - map → camera_init TF 不稳定
     *   - costmap 消息过滤器会丢弃 scan
     *   - 此时不应发导航目标(setGoal 会拒绝)
     *   - 需遥控机器人慢速移动或旋转帮助 AMCL 收敛
     */
    @PostMapping("/global-localization")
    @ApiOperation("触发 AMCL 全局重定位")
    public Result<Void> triggerGlobalLocalization() {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 service call /reinitialize_global_localization"
                    + " std_srvs/srv/Empty '{}'";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return Result.error("全局定位服务调用超时,请确认 Nav2 正在运行 "
                        + "(ros2 launch robot_full.launch.py 或 systemctl status robot-nav)");
            }
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                return Result.error("调用失败(退出码:" + exitCode
                        + "),请确认 Nav2 已启动");
            }

            bridgeService.setNavMode(true);

            // ⭐ Bug 1 fix (a): 记录触发时间,setGoal 据此拒绝过早的导航目标
            lastGlobalLocalizationTime.set(System.currentTimeMillis());

            // ⭐ Bug 1 fix (b): 推送 WebSocket 通知,前端可以显示"收敛中"状态
            // 前端应监听此事件,显示提示:"请遥控机器人慢速转动,等待定位收敛"
            // WebSocketPushService 需要提供 pushLocalizationStatus(String status) 方法:
            //   status = "globalizing" | "converged" | "failed"
            try {
                pushService.pushLocalizationStatus("globalizing");
            } catch (Exception pushEx) {
                // push 失败不影响主流程,仅记录警告
                log.warn("推送 localization_status 失败(前端可能不会显示收敛提示): {}",
                        pushEx.getMessage());
            }

            log.info("✅ AMCL 全局定位已触发,粒子撒满地图");
            return Result.OK("全局重定位已触发,请遥控机器人慢速移动或旋转 15~30 秒以帮助 AMCL 收敛。" +
                    "收敛期间(约 " + MIN_CONVERGENCE_MS / 1000 + "s) 不能发送导航目标。");
        } catch (Exception e) {
            log.error("触发全局定位失败", e);
            return Result.error("调用失败: " + e.getMessage());
        }
    }

    // ===================== 手动校正初始位姿 =====================

    /**
     * 手动设置 AMCL 初始位姿(精准校正)。
     * 与全局重定位不同,此接口提供精确的初始位姿,粒子集中撒在指定位置附近,
     * 收敛速度更快(通常 3~5 秒),但仍需短暂等待。
     */
    @PostMapping("/initial-pose")
    @ApiOperation("手动设置 AMCL 初始位姿")
    public Result<Void> setInitialPose(@RequestBody Map<String, Double> body) {
        double x     = body.getOrDefault("x",     0.0);
        double y     = body.getOrDefault("y",     0.0);
        double theta = body.getOrDefault("theta", 0.0);

        bridgeService.setNavMode(true);
        navigationService.sendInitialPose(x, y, theta);

        // 初始位姿也会触发粒子重置,记录时间;因为是精确位置,等待时间设短一些
        // 用 MIN_CONVERGENCE_MS / 3 近似(约 5 秒),通过更新 lastGlobalLocalizationTime
        // 触发 setGoal 的保护逻辑(等待约 5 秒)
        lastGlobalLocalizationTime.set(System.currentTimeMillis() - MIN_CONVERGENCE_MS * 2 / 3);

        log.info("手动设置初始位姿: x={}, y={}, theta={}", x, y, theta);
        return Result.OK("初始位姿已发送(x=" + x + ", y=" + y + ", theta=" + theta + ")");
    }

    // ===================== 收敛状态查询(前端轮询用) =====================

    /**
     * 查询 AMCL 是否已度过收敛等待期(供前端轮询)。
     *
     * 用法: 前端在全局重定位后每秒轮询此接口,
     * 当 ready=true 时再显示"发送导航目标"按钮。
     *
     * 注意: ready=true 仅表示已过等待时间窗口,不代表 AMCL 100% 收敛。
     * 如需更精确判断,前端应同时订阅 /amcl_pose 并检查协方差(covariance[0] < 0.05)。
     */
    @GetMapping("/localization-ready")
    @ApiOperation("查询 AMCL 收敛等待是否完成")
    public Result<Map<String, Object>> isLocalizationReady() {
        long now     = System.currentTimeMillis();
        long last    = lastGlobalLocalizationTime.get();
        long elapsed = now - last;
        boolean ready = (last == 0) || (elapsed >= MIN_CONVERGENCE_MS);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("ready",       ready);
        result.put("elapsed",     elapsed);
        result.put("waitMs",      MIN_CONVERGENCE_MS);
        result.put("remainingMs", ready ? 0 : MIN_CONVERGENCE_MS - elapsed);
        result.put("message",     ready
                ? "可以发送导航目标"
                : "AMCL 收敛等待中,剩余约 " + (MIN_CONVERGENCE_MS - elapsed) / 1000 + " 秒");
        return Result.OK(result);
    }

    // ===================== 手动模式切换 =====================

    @PostMapping("/mode/{mode}")
    @ApiOperation("手动切换 navMode (nav / mapping)")
    public Result<Void> switchMode(@PathVariable String mode) {
        if ("nav".equalsIgnoreCase(mode)) {
            bridgeService.setNavMode(true);
            return Result.OK("已切到导航模式 (位姿来源: /amcl_pose)");
        } else if ("mapping".equalsIgnoreCase(mode)) {
            bridgeService.setNavMode(false);
            return Result.OK("已切到建图模式 (位姿来源: /Odometry)");
        } else {
            return Result.error("无效模式,可选: nav | mapping");
        }
    }


    @Autowired
    private RobotHardwareService hardwareService;

// ─── 新接口 1: 紧急停车 ───────────────────────────────────────────────────

    /**
     * 硬件级紧急停车。
     * 立即清除 PLC 全部方向 M 位 + 速度归零。
     * 比 cancel() 更彻底: cancel() 依赖 Nav2 Action; 此接口直达 PLC。
     */
    @PostMapping("/emergency-stop")
    @ApiOperation("硬件紧急停车 (直达 PLC)")
    public Result<Void> emergencyStop() {
        hardwareService.emergencyStop();
        navigationService.cancelNavigation();  // 同步停 Nav2
        return Result.OK("🛑 紧急停车已执行");
    }

    /**
     * 解除 Java 侧急停锁。
     * 只恢复 cmd_vel 转发能力, 不替代 PLC/现场报警复位。
     */
    @PostMapping("/emergency-stop/clear")
    @ApiOperation("解除 Java 侧急停锁")
    public Result<Void> clearEmergencyStop() {
        hardwareService.clearEmergencyStopLock();
        return Result.OK("Java 侧急停锁已解除");
    }

// ─── 新接口 2: PLC 实时状态查询 (前端每秒轮询) ───────────────────────────

    /**
     * 查询底盘 PLC 实时状态。
     * 前端用于在导航监控面板显示:
     *   - 当前驱动模式 (STRAIGHT_FORWARD / ROTATE_LEFT 等)
     *   - 实际 RPM 和转向角
     *   - V 区反馈位 (真实运行状态)
     *   - 障碍物最近距离 + 是否触发停车
     */
    @GetMapping("/plc-status")
    @ApiOperation("查询底盘 PLC 实时状态")
    public Result<?> getPlcStatus() {
        return Result.OK(hardwareService.readStatus());
    }

// ─── 新接口 3: 设置障碍物停车阈值 ─────────────────────────────────────────

    @PostMapping("/obstacle-threshold")
    @ApiOperation("设置障碍物紧急停车距离阈值(m)")
    public Result<Void> setObstacleThreshold(@RequestParam double distanceMeters) {
        // 动态调整 (需在 RobotHardwareService 增加 setter)
        hardwareService.setObstacleStopDistance(distanceMeters);
        return Result.OK("障碍物停车阈值已设为 " + distanceMeters + "m");
    }
}
