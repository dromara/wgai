package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.model.NavigationGoalDTO;
import org.jeecg.modules.ros2.service.NavigationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 导航控制器
 *
 * 位姿说明：
 *   正常情况下无需手动设置初始位置。
 *   MapController 在 Nav2 运行期间每 5 秒自动保存 AMCL 位姿，
 *   下次 loadMap 时自动恢复，首次使用默认 (0, 0, 0)。
 *
 *   仅在以下情况需要手动干预：
 *     - 机器人被人为搬动（断电后位置发生变化）
 *     - 定位长时间漂移无法自动收敛
 *   此时可调用 /global-localization 触发全局重定位，
 *   或调用 /initial-pose 直接指定当前位置。
 */
@Slf4j
@RestController
@RequestMapping("/api/navigation")
@Api(tags = "导航控制")
public class NavigationController {

    private static final String SETUP_BASH = "/home/lio_ws/install/setup.bash";
    private static final String ROS_BASH   = "/opt/ros/humble/setup.bash";

    @Autowired
    private NavigationService navigationService;

    // ===================== 发送导航目标 =====================

    @PostMapping("/goal")
    @ApiOperation("设置导航目标")
    public Result<Void> setGoal(@Validated @RequestBody NavigationGoalDTO goal) {
        navigationService.sendNavigationGoal(goal.getX(), goal.getY(), goal.getTheta());
        return Result.OK("导航目标已设置");
    }

    // ===================== 取消导航 =====================

    @PostMapping("/cancel")
    @ApiOperation("取消导航")
    public Result<Void> cancel() {
        navigationService.cancelNavigation();
        return Result.OK("导航已取消");
    }

    // ===================== advertise =====================

    @PostMapping("/advertise")
    @ApiOperation("预先advertise导航话题（避免Cannot infer topic type错误）")
    public Result<Void> advertise() {
        navigationService.advertiseTopics();
        return Result.OK("话题已advertise");
    }

    // ===================== 全局重定位（机器人被搬动时使用） =====================

    /**
     * 触发 AMCL 全局重定位
     *
     * 使用场景：
     *   - 机器人断电后被人为搬动，重启后位置与历史记录不符
     *   - 定位持续漂移，AMCL 粒子发散无法收敛
     *
     * 原理：
     *   调用 /reinitialize_global_localization 服务
     *   → AMCL 把粒子撒满整张地图
     *   → 让机器人缓慢移动或旋转 10~30 秒
     *   → AMCL 根据激光匹配度自动收敛到真实位置
     *
     * 注意：
     *   - 调用后约 10~30 秒内 map TF 会不稳定，请勿在此期间发送导航目标
     *   - 收敛后位姿会自动保存，下次重启无需再次调用
     */
    @PostMapping("/global-localization")
    @ApiOperation("触发AMCL全局重定位（机器人被搬动时使用）")
    public Result<Void> triggerGlobalLocalization() {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 service call /reinitialize_global_localization"
                    + " std_srvs/srv/Empty '{}'";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return Result.error("全局定位服务调用超时，请确认 Nav2 正在运行");
            }
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                return Result.error("全局定位服务调用失败（退出码:" + exitCode + "），请确认 Nav2 已启动");
            }
            log.info("✅ AMCL 全局定位已触发，粒子撒满地图，等待激光收敛...");
            return Result.OK("全局重定位已触发。请让机器人缓慢移动或旋转 10~30 秒以帮助 AMCL 收敛");
        } catch (Exception e) {
            log.error("触发全局定位失败", e);
            return Result.error("调用失败: " + e.getMessage());
        }
    }

    // ===================== 手动校正初始位姿（紧急使用） =====================

    /**
     * 手动设置 AMCL 初始位姿（紧急校正用）
     *
     * 正常情况下无需调用此接口，系统会自动恢复上次位姿。
     *
     * 使用场景：
     *   - 全局重定位收敛缓慢，已知机器人在地图中的精确位置
     *   - 需要快速指定初始位置跳过收敛过程
     *
     * 参数（地图坐标系，单位：米/弧度）：
     *   x     - X 坐标
     *   y     - Y 坐标
     *   theta - 朝向角（弧度，0 = 正东方向）
     */
    @PostMapping("/initial-pose")
    @ApiOperation("手动设置AMCL初始位姿（紧急校正用，正常无需调用）")
    public Result<Void> setInitialPose(@RequestBody Map<String, Double> body) {
        double x     = body.getOrDefault("x",     0.0);
        double y     = body.getOrDefault("y",     0.0);
        double theta = body.getOrDefault("theta", 0.0);
        navigationService.sendInitialPose(x, y, theta);
        log.info("手动设置初始位姿: x={}, y={}, theta={}", x, y, theta);
        return Result.OK("初始位姿已发送（x=" + x + ", y=" + y + ", theta=" + theta + "），AMCL 开始定位...");
    }
}