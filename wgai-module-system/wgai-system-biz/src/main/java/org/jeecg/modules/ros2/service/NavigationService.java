package org.jeecg.modules.ros2.service;

import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 导航服务
 *
 * 关键修复：
 *   1. publish /goal_pose 前必须先 advertise，否则 rosbridge 报 "Cannot infer topic type"
 *   2. AMCL 启动后必须先发 /initialpose，否则 map frame 不存在
 *      流程：publish /initialpose → AMCL 收到 → 开始定位 → 发布 map→camera_init TF
 *           → 之后才能设导航目标
 */
@Slf4j
@Service
public class NavigationService {

    @Autowired
    private ROS2BridgeService ros2Bridge;

    // 是否已经完成过 advertise（每次连接只需 advertise 一次）
    private volatile boolean advertised = false;

    /**
     * 在 Nav2 启动后调用一次，advertise 所有需要发布的话题
     * 必须在 publish 之前调用，否则 rosbridge 不知道消息类型
     */
    public void advertiseTopics() {
        // /goal_pose：Nav2 导航目标
        ros2Bridge.advertise("/goal_pose", "geometry_msgs/PoseStamped");
        // /initialpose：AMCL 初始位姿
        ros2Bridge.advertise("/initialpose", "geometry_msgs/PoseWithCovarianceStamped");
        // /cmd_vel：速度（手动停车用）
        ros2Bridge.advertise("/cmd_vel", "geometry_msgs/Twist");
        advertised = true;
        log.info("✅ 话题已 advertise: /goal_pose /initialpose /cmd_vel");
    }

    /**
     * 发送 AMCL 初始位姿
     *
     * AMCL 启动后默认不知道机器人在哪，需要发一次初始位姿才能开始定位。
     * 发送后 AMCL 会：
     *   1. 在初始位姿附近撒粒子
     *   2. 订阅 /scan 做粒子滤波
     *   3. 开始发布 map→camera_init TF  ← 这才是 map frame 出现的时机
     *
     * @param x     机器人在地图中的 X 坐标（米）
     * @param y     机器人在地图中的 Y 坐标（米）
     * @param theta 机器人朝向（弧度）
     *
     * 如果不知道初始位置，用 (0, 0, 0) 作为默认值（大部分建图从原点开始）
     */
    public void sendInitialPose(double x, double y, double theta) {
        if (!advertised) advertiseTopics();

        JsonObject msg = new JsonObject();

        // Header
        JsonObject header = new JsonObject();
        header.addProperty("frame_id", "map");
        JsonObject stamp = new JsonObject();
        stamp.addProperty("sec",     (int)(System.currentTimeMillis() / 1000));
        stamp.addProperty("nanosec", 0);
        header.add("stamp", stamp);
        msg.add("header", header);

        // Pose with covariance
        JsonObject poseWithCov = new JsonObject();

        JsonObject pose = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", 0.0);

        double halfTheta = theta / 2.0;
        JsonObject orientation = new JsonObject();
        orientation.addProperty("x", 0.0);
        orientation.addProperty("y", 0.0);
        orientation.addProperty("z", Math.sin(halfTheta));
        orientation.addProperty("w", Math.cos(halfTheta));
        pose.add("position", position);
        pose.add("orientation", orientation);
        poseWithCov.add("pose", pose);

        // 协方差矩阵（6x6，较大值表示不确定性高，AMCL 会在附近广撒粒子）
        // 对角线：[x_var, y_var, z_var, roll_var, pitch_var, yaw_var]
        // 通常 x/y 给 0.25（0.5m 不确定），yaw 给 0.068（约15度不确定）
        com.google.gson.JsonArray cov = new com.google.gson.JsonArray();
        double[] covValues = {
                0.25, 0,    0, 0, 0, 0,
                0,    0.25, 0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0.068
        };
        for (double v : covValues) cov.add(v);
        poseWithCov.add("covariance", cov);
        msg.add("pose", poseWithCov);

        ros2Bridge.publish("/initialpose", "geometry_msgs/PoseWithCovarianceStamped", msg);
        log.info("✅ 初始位姿已发送: x={}, y={}, theta={}°", x, y, Math.toDegrees(theta));
    }

    /**
     * 发送导航目标到 Nav2
     *
     * ⚠️ 前提：必须先调用 sendInitialPose()，确保 map TF 已存在
     */
    public void sendNavigationGoal(Double x, Double y, Double theta) {
        // 确保已 advertise
        if (!advertised) advertiseTopics();

        JsonObject msg = new JsonObject();

        JsonObject header = new JsonObject();
        header.addProperty("frame_id", "map");
        JsonObject stamp = new JsonObject();
        stamp.addProperty("sec",     (int)(System.currentTimeMillis() / 1000));
        stamp.addProperty("nanosec", 0);
        header.add("stamp", stamp);
        msg.add("header", header);

        JsonObject pose = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", x);
        position.addProperty("y", y);
        position.addProperty("z", 0.0);

        double halfTheta = theta / 2.0;
        JsonObject orientation = new JsonObject();
        orientation.addProperty("x", 0.0);
        orientation.addProperty("y", 0.0);
        orientation.addProperty("z", Math.sin(halfTheta));
        orientation.addProperty("w", Math.cos(halfTheta));
        pose.add("position",    position);
        pose.add("orientation", orientation);
        msg.add("pose", pose);

        ros2Bridge.publish("/goal_pose", "geometry_msgs/PoseStamped", msg);
        log.info("导航目标已发送: x={}, y={}, theta={}°", x, y, Math.toDegrees(theta));
    }

    /**
     * 取消当前导航（发零速度停车）
     */
    public void cancelNavigation() {
        if (!advertised) advertiseTopics();
        JsonObject zero  = new JsonObject();
        zero.addProperty("x", 0.0); zero.addProperty("y", 0.0); zero.addProperty("z", 0.0);
        JsonObject twist = new JsonObject();
        twist.add("linear", zero); twist.add("angular", zero);
        ros2Bridge.publish("/cmd_vel", "geometry_msgs/Twist", twist);
        log.info("导航已取消（发送零速度）");
    }
}