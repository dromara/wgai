package org.jeecg.modules.ros2.config;

/**
 * @author wggg
 * @date 2026/1/23 9:50
 */

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ros2")
public class ROS2Config {

    /**
     * rosbridge WebSocket 地址
     */
    private String bridgeUrl = "ws://127.0.0.1:9090";

    /**
     * 是否自动连接
     */
    private boolean autoConnect = false;

    /**
     * 重连间隔（毫秒）
     */
    private long reconnectInterval = 5000;

    /**
     * 最大重连次数
     */
    private int maxReconnectAttempts = 10;

    /**
     * 速度历史记录最大条数
     */
    private int velocityHistoryMaxSize = 100;

    /**
     * 轨迹记录最大点数
     */
    private int trajectoryMaxSize = 1000;
}
