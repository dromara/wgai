package org.jeecg.modules.ros2.controller;


import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.service.ROS2BridgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 机器人手动遥控接口
 *
 * 控制数据流：
 *   前端 D-PAD / 键盘
 *        ↓ POST /ros2/robot/cmd-vel { linear, angular }
 *   ROS2BridgeService.sendCmdVel(linear, angular)
 *        ├─ RobotHardwareService.sendVelocity()  → 底盘硬件（直发，最低延迟）
 *        └─ publish("/cmd_vel") to rosbridge     → ROS2（让导航感知）
 */
@Slf4j
@RestController
@RequestMapping("/ros2/robot")
@Api(tags = "机器人手动控制")
public class RobotController {

    @Autowired
    private ROS2BridgeService ros2Bridge;

    /**
     * 发送速度指令（D-PAD / 键盘 调用）
     * Body: { "linear": 0.3, "angular": 0.5 }
     *
     * D-PAD 对应值：
     *   前进：linear=+speed, angular=0
     *   后退：linear=-speed, angular=0
     *   左转：linear=0,      angular=+speed
     *   右转：linear=0,      angular=-speed
     *   停止：linear=0,      angular=0
     */
    @PostMapping("/cmd-vel")
    @ApiOperation("手动控制速度")
    public Result<Void> sendCmdVel(@RequestBody Map<String, Double> body) {
        double linear  = body.getOrDefault("linear",  0.0);
        double angular = body.getOrDefault("angular", 0.0);
        ros2Bridge.sendCmdVel(linear, angular);
        return Result.OK();
    }

    /**
     * 紧急停车
     */
    @PostMapping("/stop")
    @ApiOperation("紧急停车")
    public Result<Void> stop() {
        ros2Bridge.sendCmdVel(0, 0);
        log.info("[紧急停车]");
        return Result.OK("已停车");
    }

    /**
     * 查询 rosbridge 连接状态
     */
    @GetMapping("/status")
    @ApiOperation("查询连接状态")
    public Result<Boolean> status() {
        return Result.OK(ros2Bridge.isConnected());
    }
}
