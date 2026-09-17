package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.service.ObstacleGuardService;
import org.jeecg.modules.ros2.service.RobotHardwareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PLC 手动连接 / 参数配置 / 点位测试。
 *
 * 拆出来单独一个控制器，不塞进 NavigationController：这些接口是「开自动之前」用的，
 * 和导航目标那套是两个阶段。现场流程是
 *   连接 PLC → 点位测试逐条点通(尤其确认 V1201.7 上位控制模式=true) → 调好停车距离 → 再发导航目标。
 *
 * ⚠ 这里的接口会真的往底盘写寄存器(plc.enabled=true 时)，测试动作全部限时点动、到点自动停。
 */
@Slf4j
@RestController
@RequestMapping("/api/plc")
@Api(tags = "PLC 连接与点位测试")
public class PlcController {

    @Autowired
    private RobotHardwareService hardwareService;

    @Autowired
    private ObstacleGuardService obstacleGuardService;

    // ======================== 连接 ========================

    @ApiOperation("手动连接 PLC(默认配置不连)")
    @PostMapping("/connect")
    public Result<Map<String, Object>> connect() {
        Map<String, Object> info = hardwareService.enablePlc();
        boolean ok = Boolean.TRUE.equals(info.get("connected"));
        return ok ? Result.OK(info)
                  : Result.error("PLC 连接失败: " + info.get("host") + ":" + info.get("port")
                                 + "，请确认底盘已上电、网线通、IP 一致");
    }

    @ApiOperation("手动断开 PLC(先停车再断)")
    @PostMapping("/disconnect")
    public Result<Map<String, Object>> disconnect() {
        return Result.OK(hardwareService.disablePlc());
    }

    // ======================== 配置 / 状态 ========================

    @ApiOperation("PLC 连接与运行参数(前端配置面板回显)")
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plc",      hardwareService.plcInfo());
        m.put("obstacle", obstacleGuardService.info());
        return Result.OK(m);
    }

    @ApiOperation("PLC 实时状态(反馈寄存器缓存)")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plc",      hardwareService.plcInfo());
        m.put("feedback", hardwareService.readStatus());
        m.put("obstacle", obstacleGuardService.info());
        m.put("mode",     hardwareService.getCurrentMode());
        m.put("lastRpm",      hardwareService.getLastRpm());
        m.put("lastAngleDeg", hardwareService.getLastAngleDeg());
        m.put("obstacleOverride", hardwareService.isObstacleOverride());
        return Result.OK(m);
    }

    /**
     * 自动导航阶段「前面多少米有东西就停」。
     * 单位米，量的是**车头前方**净空，不是离雷达的距离。0 = 关闭该保护。
     */
    @ApiOperation("设置自动导航障碍物停车距离(m, 离车头)")
    @PostMapping("/obstacle-threshold")
    public Result<Map<String, Object>> setObstacleThreshold(@RequestParam("distanceMeters") double distanceMeters) {
        if (distanceMeters < 0 || distanceMeters > 10) {
            return Result.error("停车距离需在 0~10m 之间(0=关闭)");
        }
        hardwareService.setObstacleStopDistance(distanceMeters);
        return Result.OK(hardwareService.plcInfo());
    }

    @ApiOperation("设置自动导航限速上限(r/min)")
    @PostMapping("/auto-nav-rpm")
    public Result<Map<String, Object>> setAutoNavRpm(@RequestParam("rpm") int rpm) {
        hardwareService.setAutoNavMaxRpm(rpm);
        return Result.OK(hardwareService.plcInfo());
    }

    // ======================== 点位测试 ========================

    /**
     * 动作清单 + 分组。故意由后端给出而不是前端写死 —— 点位表改了前端跟着变，
     * 不会出现"界面上有个按钮但后台早就不用这个点位了"。
     */
    @ApiOperation("测试窗支持的动作清单(由后端点位表生成)")
    @GetMapping("/test/actions")
    public Result<Map<String, Object>> testActions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groups",  hardwareService.testGroups());
        m.put("actions", hardwareService.testActions());
        return Result.OK(m);
    }

    /**
     * 执行一条点位测试。运动类动作会在 holdMs 后自动停车并把模式字复位成直行。
     *
     * @param code     动作 code，见 /test/actions
     * @param speed    VW1004 转速 r/min，钳到 plc.test.max-rpm
     * @param angleDeg VW1006 角度(°)，负=左 正=右，钳到 ±45
     * @param holdMs   保持时间，钳到 200 ~ plc.test.max-hold-ms
     * @param linear   仅 auto-sim：ROS linear.x (m/s)
     * @param angular  仅 auto-sim：ROS angular.z (rad/s)
     */
    @ApiOperation("执行一条 PLC 点位测试(限时点动)")
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestParam("code") String code,
                                            @RequestParam(value = "speed",    required = false) Integer speed,
                                            @RequestParam(value = "angleDeg", required = false) Double angleDeg,
                                            @RequestParam(value = "holdMs",   required = false) Integer holdMs,
                                            @RequestParam(value = "linear",   required = false) Double linear,
                                            @RequestParam(value = "angular",  required = false) Double angular) {
        try {
            return Result.OK(hardwareService.runTest(code, speed, angleDeg, holdMs, linear, angular));
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[PLC测试] 拒绝执行 {}: {}", code, e.getMessage());
            return Result.error(e.getMessage());
        }
    }
}
