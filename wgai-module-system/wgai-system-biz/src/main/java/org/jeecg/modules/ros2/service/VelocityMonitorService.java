package org.jeecg.modules.ros2.service;

import com.google.gson.JsonObject;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.ros2.config.ROS2Config;
import org.jeecg.modules.ros2.model.VelocityCommand;
import org.jeecg.modules.ros2.service.WebSocketPushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 速度监控服务
 * 处理速度指令的业务逻辑
 */
@Slf4j
@Service
public class VelocityMonitorService {

    @Autowired
    private ROS2Config ros2Config;

    @Autowired
    private WebSocketPushService pushService;

    // 使用线程安全的列表
    private final List<VelocityCommand> velocityHistory = new CopyOnWriteArrayList<>();
    private volatile VelocityCommand currentVelocity = new VelocityCommand();

    /**
     * 处理速度消息
     */
    public void handleVelocityMessage(JsonObject twistMsg) {
        try {

            VelocityCommand velocity = parseVelocityCommand(twistMsg);

            // 更新当前速度
            this.currentVelocity = velocity;

            // 添加到历史记录
            addToHistory(velocity);

            // 推送给前端
            pushService.pushVelocity(velocity);
            log.info("处理速度消息{}",velocity);
            // 记录日志
            logVelocity(velocity);

        } catch (Exception e) {
            log.error("处理速度消息失败", e);
        }
    }

    /**
     * 解析速度指令
     */
    private VelocityCommand parseVelocityCommand(JsonObject twistMsg) {
        VelocityCommand velocity = new VelocityCommand();

        // 解析 linear
        JsonObject linear = twistMsg.getAsJsonObject("linear");
        velocity.setLinearX(linear.get("x").getAsDouble());
        velocity.setLinearY(linear.get("y").getAsDouble());
        velocity.setLinearZ(linear.get("z").getAsDouble());

        // 解析 angular
        JsonObject angular = twistMsg.getAsJsonObject("angular");
        velocity.setAngularX(angular.get("x").getAsDouble());
        velocity.setAngularY(angular.get("y").getAsDouble());
        velocity.setAngularZ(angular.get("z").getAsDouble());

        // 设置时间戳
        velocity.setTimestamp(System.currentTimeMillis());

        // 判断运动类型
        velocity.setMotionType(determineMotionType(velocity.getLinearX(), velocity.getAngularZ()));

        return velocity;
    }

    /**
     * 判断运动类型
     */
    private String determineMotionType(Double linear, Double angular) {
        double threshold = 0.01;

        if (Math.abs(linear) < threshold && Math.abs(angular) < threshold) {
            return "停止";
        } else if (Math.abs(linear) > threshold && Math.abs(angular) < threshold) {
            return linear > 0 ? "前进" : "后退";
        } else if (Math.abs(linear) < threshold && Math.abs(angular) > threshold) {
            return angular > 0 ? "原地左转" : "原地右转";
        } else {
            if (linear > 0 && angular > 0) return "前进左转";
            else if (linear > 0 && angular < 0) return "前进右转";
            else if (linear < 0 && angular > 0) return "后退左转";
            else return "后退右转";
        }
    }

    /**
     * 添加到历史记录
     */
    private void addToHistory(VelocityCommand velocity) {
        velocityHistory.add(velocity);

        // 限制历史记录大小
        int maxSize = ros2Config.getVelocityHistoryMaxSize();
        while (velocityHistory.size() > maxSize) {
            velocityHistory.remove(0);
        }
    }

    /**
     * 记录日志
     */
    private void logVelocity(VelocityCommand velocity) {
        log.debug("速度指令 - 类型:{}, 线速度:{} m/s, 角速度:{} rad/s",
                velocity.getMotionType(),
                String.format("%.3f", velocity.getLinearX()),
                String.format("%.3f", velocity.getAngularZ())
        );
    }

    /**
     * 获取当前速度
     */
    public VelocityCommand getCurrentVelocity() {
        return currentVelocity;
    }

    /**
     * 获取历史记录
     */
    public List<VelocityCommand> getVelocityHistory() {
        return new ArrayList<>(velocityHistory);
    }

    /**
     * 清空历史记录
     */
    public void clearHistory() {
        velocityHistory.clear();
        log.info("已清空速度历史记录");
    }
}