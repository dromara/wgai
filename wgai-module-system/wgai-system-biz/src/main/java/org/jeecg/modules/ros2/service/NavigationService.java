package org.jeecg.modules.ros2.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 导航服务 - 通过 rosbridge 发送 ROS 消息
 *
 * 新架构说明:
 *   - Nav2 由 systemd 托管,启动后常驻
 *   - 本服务只负责往 rosbridge 发消息(advertise / publish)
 *   - service 调用(load_map / global_localization)由 Controller 用 subprocess 完成
 *
 * 关键时间戳修复(直接影响"换点导航失败"问题):
 *   旧版 nanosec 始终为 0,在同一秒内多次发 goal_pose,
 *   Nav2 BT navigator 会认为是重复 goal 而忽略后续。
 *   新版用毫秒级 nanosec 保证每次时间戳唯一。
 */
@Slf4j
@Service
public class NavigationService {

    @Autowired
    private ROS2BridgeService ros2Bridge;

    /** 是否已经完成 advertise(每次 WebSocket 连接只需 advertise 一次) */
    private volatile boolean advertised = false;

    /**
     * advertise 所有需要发布的话题
     * publish 之前必须先 advertise,否则 rosbridge 报 "Cannot infer topic type"
     */
    public void advertiseTopics() {
        ros2Bridge.advertise("/goal_pose",   "geometry_msgs/PoseStamped");
        ros2Bridge.advertise("/initialpose", "geometry_msgs/PoseWithCovarianceStamped");
        ros2Bridge.advertise("/cmd_vel",     "geometry_msgs/Twist");
        advertised = true;
        log.info("✅ 话题已 advertise: /goal_pose /initialpose /cmd_vel");
    }

    /**
     * WebSocket 重连后重置 advertise 状态
     */
    public void resetAdvertised() {
        advertised = false;
    }

    // ===================== 发送 AMCL 初始位姿 =====================

    /**
     * 发送 AMCL 初始位姿
     *
     * AMCL 收到后会:
     *   1. 在初始位姿附近撒粒子
     *   2. 订阅 /scan 做粒子滤波
     *   3. 开始发布 map → camera_init TF
     *
     * @param x     地图 X 坐标(米)
     * @param y     地图 Y 坐标(米)
     * @param theta 朝向(弧度)
     */
    public void sendInitialPose(double x, double y, double theta) {
        if (!advertised) advertiseTopics();

        JsonObject msg = new JsonObject();
        msg.add("header", buildHeader("map"));

        JsonObject poseWithCov = new JsonObject();
        poseWithCov.add("pose",       buildPose(x, y, theta));
        poseWithCov.add("covariance", buildCovariance());
        msg.add("pose", poseWithCov);

        ros2Bridge.publish("/initialpose",
                "geometry_msgs/msg/PoseWithCovarianceStamped", msg);
        log.info("✅ 初始位姿已发送: x={}, y={}, theta={} 消息内容={}°",
                x, y, Math.toDegrees(theta),msg);
    }

    // ===================== 发送导航目标 =====================

    /**
     * 发送导航目标到 Nav2(发布到 /goal_pose,触发 NavigateToPose action)
     *
     * 前提: AMCL 已收敛,map → camera_init TF 已建立。
     *      若 TF 无效,Nav2 会立即返回 NO_PATH。
     */
    public void sendNavigationGoal(Double x, Double y, Double theta) {
        if (!advertised) advertiseTopics();

        JsonObject msg = new JsonObject();
        msg.add("header", buildHeader("map"));
        msg.add("pose",   buildPose(x, y, theta));

        ros2Bridge.publish("/goal_pose", "geometry_msgs/PoseStamped", msg);
        log.info("导航目标已发送: x={}, y={}, theta={}°",
                x, y, Math.toDegrees(theta));
    }

    // ===================== 取消导航 =====================

    /**
     * 取消当前导航
     *
     * 实现方式:发零速度到 /cmd_vel(velocity_smoother 接收后让控制器减速到 0)
     * 注意:此方法不会取消 NavigateToPose action,Nav2 内部仍然认为在导航中。
     *      Nav2 的 controller_server 会发现机器人没在跟轨迹后,触发 progress_checker
     *      失败回调,最终上报 status=6(失败),客户端就能感知到导航结束。
     *
     * 更可靠的取消方式是通过 ROS action 客户端发 cancel_goal,
     * 但 rosbridge 对 action 支持复杂,后续可优化。
     */
    public void cancelNavigation() {
        if (!advertised) advertiseTopics();

        JsonObject zero = new JsonObject();
        zero.addProperty("x", 0.0);
        zero.addProperty("y", 0.0);
        zero.addProperty("z", 0.0);

        JsonObject twist = new JsonObject();
        twist.add("linear",  zero);
        twist.add("angular", zero);

        // 连续发 5 次,确保 controller 一定能收到
        for (int i = 0; i < 5; i++) {
            ros2Bridge.publish("/cmd_vel", "geometry_msgs/Twist", twist);
        }
        log.info("导航已取消(发送零速度)");
    }

    // ===================== 内部工具 =====================

    /**
     * 构造 std_msgs/Header
     * 时间戳用毫秒级精度,避免同秒内重复时间戳被 Nav2 去重
     */
    private JsonObject buildHeader(String frameId) {
        JsonObject header = new JsonObject();
        header.addProperty("frame_id", frameId);

        long nowMs = System.currentTimeMillis();
        JsonObject stamp = new JsonObject();
        stamp.addProperty("sec",     (int)  (nowMs / 1000));
        stamp.addProperty("nanosec", (int) ((nowMs % 1000) * 1_000_000));
        header.add("stamp", stamp);

        return header;
    }

    /**
     * 构造 geometry_msgs/Pose(2D 平面,只有 yaw)
     */
    private JsonObject buildPose(double x, double y, double theta) {
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
        return pose;
    }

    /**
     * AMCL 协方差矩阵(6x6,行优先 36 元素)
     * 对角线: [x_var, y_var, z_var, roll_var, pitch_var, yaw_var]
     */
    private JsonArray buildCovariance() {
        double[] cov = {
                0.25, 0,    0, 0, 0, 0,
                0,    0.25, 0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0,
                0,    0,    0, 0, 0, 0.068
        };
        JsonArray arr = new JsonArray();
        for (double v : cov) arr.add(v);
        return arr;
    }
}