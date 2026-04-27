package org.jeecg.modules.ros2.config;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.jeecg.modules.ros2.service.RobotHardwareService;
import org.jeecg.modules.ros2.service.VelocityMonitorService;
import org.jeecg.modules.ros2.service.WebSocketPushService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * ROS2 WebSocket 消息处理器
 *
 * 核心职责：rosbridge → Java → 翻译 → 底盘硬件
 *
 * /cmd_vel 处理路径：
 *   Nav2 发布 /cmd_vel
 *        ↓ (rosbridge 转发)
 *   handleCmdVel()  ← 这里解析 linear.x / angular.z
 *        ↓
 *   RobotHardwareService.sendVelocity()  ← 翻译为底盘协议
 *        ↓
 *   UDP / TCP / 串口 → 底盘硬件
 */
@Slf4j
public class ROS2WebSocketHandler extends TextWebSocketHandler {

    private final VelocityMonitorService velocityService;
    private final WebSocketPushService   pushService;
    private final RobotHardwareService   hardwareService; // ← 底盘硬件控制
    private final Gson gson = new Gson();
    private final Consumer<WebSocketSession> onConnectCallback;
    private final Runnable onDisconnectCallback;

    // 点云下采样：最多推送给前端的点数，避免数据量过大
    private static final int MAX_POINTS_TO_PUSH = 3000;

    public ROS2WebSocketHandler(
            VelocityMonitorService velocityService,
            WebSocketPushService pushService,
            RobotHardwareService hardwareService,
            Consumer<WebSocketSession> onConnectCallback,
            Runnable onDisconnectCallback) {
        this.velocityService      = velocityService;
        this.pushService          = pushService;
        this.hardwareService      = hardwareService;
        this.onConnectCallback    = onConnectCallback;
        this.onDisconnectCallback = onDisconnectCallback;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("✅ ROS2 Bridge 连接建立: {}", session.getId());
        session.setTextMessageSizeLimit(20 * 1024 * 1024);   // 20MB（点云数据量大）
        session.setBinaryMessageSizeLimit(20 * 1024 * 1024);
        if (onConnectCallback != null) onConnectCallback.accept(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        handleRosMessage(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("ROS2 Bridge 断开: code={}, reason={}", status.getCode(), status.getReason());
        if (onDisconnectCallback != null) onDisconnectCallback.run();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("ROS2 Bridge 传输错误: {}", ex.getMessage());
    }

    // ======================== 消息分发 ========================

    private void handleRosMessage(String raw) {
        try {
            JsonObject json = gson.fromJson(raw, JsonObject.class);
            if (!json.has("topic") || !json.has("msg")) return;

            String topic = json.get("topic").getAsString();
            JsonObject msg = json.getAsJsonObject("msg");

            // ================================================================
            // 消息分发说明：
            //
            // 这里处理的全部是【订阅】方向的消息（ROS2 → Java → 前端显示）
            //
            // 【控制车子移动】不在这里！控制是【发布】方向：
            //   前端 D-PAD → HTTP POST /ros2/robot/cmd-vel
            //   → ROS2BridgeService.publish("/cmd_vel", twist)
            //   → rosbridge → ROS2 /cmd_vel → 底盘驱动 → 电机
            // ================================================================

            switch (topic) {
                // fast_lio 点云 → 建图页面实时显示
                case "/cloud_registered":
                    handlePointCloud(msg);
                    break;

                // fast_lio 里程计 → 机器人位姿（建图时用）
                case "/Odometry":
                    handleOdometry(msg);
                    break;

                // AMCL 定位 → 机器人位姿（导航时更精确，覆盖里程计）
                case "/amcl_pose":
                    handleRobotPose(msg);
                    break;

                // ✅ Nav2 规划路径 → 前端画绿色路径线（这是路径不显示的关键！）
                //    /plan = Nav2 的路径，/path = fast_lio 的轨迹，两者不同！
                case "/plan":
                    handlePath(msg);
                    break;

                // ✅ /cmd_vel：Nav2自主导航 或 手动遥控 都走这里
                //    解析速度 → 转发给底盘硬件（核心桥接逻辑）
                case "/cmd_vel":
                    handleCmdVel(msg);
                    break;

                // 兼容旧的 SLAM Toolbox 格式
                case "/map":
                    handleMapUpdate(msg);
                    break;

                // fast_lio 轨迹（与 /plan 不同，是历史轨迹）
                case "/path":
                    handlePath(msg);
                    break;

                default:
                    log.debug("未处理的话题: {}", topic);
            }
        } catch (Exception e) {
            log.warn("处理消息失败: {}", e.getMessage());
        }
    }

    // ======================== ✅ 新增：处理 PointCloud2 ========================

    /**
     * 解析 sensor_msgs/PointCloud2 并推送2D点云给前端
     *
     * rosbridge 传来的 PointCloud2 结构：
     * {
     *   "header": {...},
     *   "height": 1,
     *   "width": 点数,
     *   "fields": [{"name":"x","offset":0,"datatype":7},{"name":"y","offset":4},...],
     *   "is_bigendian": false,
     *   "point_step": 16,   // 每个点占字节数（x+y+z+intensity = 4*4=16）
     *   "row_step": ...,
     *   "data": "base64编码的二进制点云数据",
     *   "is_dense": true
     * }
     */
    private void handlePointCloud(JsonObject msg) {
        try {
            // 读取基础参数
            int pointStep = msg.has("point_step") ? msg.get("point_step").getAsInt() : 16;
            int width     = msg.has("width")      ? msg.get("width").getAsInt()      : 0;

            if (width == 0 || !msg.has("data")) {
                log.debug("点云数据为空，跳过");
                return;
            }

            // ✅ 解码 base64 二进制数据
            String dataBase64 = msg.get("data").getAsString();
            byte[] rawData    = Base64.getDecoder().decode(dataBase64);

            int totalPoints = rawData.length / pointStep;
            // 下采样：如果点太多，每隔 N 个取一个
            int step = Math.max(1, totalPoints / MAX_POINTS_TO_PUSH);

            // ✅ 动态读取字段偏移量（从 fields 获取 x/y 的偏移）
            int xOffset = 0, yOffset = 4, zOffset = 8; // 默认 fast_lio 格式
            if (msg.has("fields")) {
                for (var fieldEl : msg.getAsJsonArray("fields")) {
                    JsonObject field = fieldEl.getAsJsonObject();
                    String name = field.get("name").getAsString();
                    int offset  = field.get("offset").getAsInt();
                    switch (name) {
                        case "x": xOffset = offset; break;
                        case "y": yOffset = offset; break;
                        case "z": zOffset = offset; break;
                    }
                }
            }

            // 提取 X、Y 坐标（Z 用于高度过滤）
            JSONArray points = new JSONArray();
            for (int i = 0; i < totalPoints; i += step) {
                int base = i * pointStep;
                if (base + zOffset + 4 > rawData.length) break;

                // 小端字节序读取 float32
                float x = readFloat(rawData, base + xOffset);
                float y = readFloat(rawData, base + yOffset);
                float z = readFloat(rawData, base + zOffset);

                // 过滤无效点和 NaN
                if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y))
                    continue;

                // ✅ 高度过滤：只保留 -0.5m ~ 2.0m 高度的点（去除地面和天花板噪点）
                if (z < -0.5f || z > 2.0f) continue;

                JSONObject pt = new JSONObject();
                pt.put("x", Math.round(x * 1000.0) / 1000.0); // 保留3位小数，减少数据量
                pt.put("y", Math.round(y * 1000.0) / 1000.0);
                pt.put("z", Math.round(z * 1000.0) / 1000.0);  // ← 加这行
                points.add(pt);
            }

            if (points.isEmpty()) {
                log.debug("过滤后点云为空");
                return;
            }

            // 推送给前端
            JSONObject cloudData = new JSONObject();
            cloudData.put("points",      points);
            cloudData.put("totalRaw",    totalPoints);  // 原始点数（调试用）
            cloudData.put("pushCount",   points.size()); // 实际推送点数

            pushService.pushToAll("cloud_update", cloudData);
            log.info("✅ 点云推送: 原始{}点 → 推送{}点", totalPoints, points.size());

        } catch (Exception e) {
            log.error("处理点云失败: {}", e.getMessage());
        }
    }

    /** 从字节数组读取小端 float32 */
    private float readFloat(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    // ======================== ✅ 新增：处理 Odometry ========================

    /**
     * 解析 nav_msgs/Odometry（fast_lio 发布的里程计）
     * 用于显示机器人实时位置
     */
    private void handleOdometry(JsonObject msg) {
        try {
            JsonObject poseWithCov = msg.getAsJsonObject("pose");
            if (poseWithCov == null) return;

            JsonObject pose        = poseWithCov.getAsJsonObject("pose");
            JsonObject position    = pose.getAsJsonObject("position");
            JsonObject orientation = pose.getAsJsonObject("orientation");

            double posX = position.get("x").getAsDouble();
            double posY = position.get("y").getAsDouble();

            // 四元数 → 偏航角 theta
            double qx = orientation.get("x").getAsDouble();
            double qy = orientation.get("y").getAsDouble();
            double qz = orientation.get("z").getAsDouble();
            double qw = orientation.get("w").getAsDouble();
            double theta = Math.atan2(2.0 * (qw * qz + qx * qy),
                    1.0 - 2.0 * (qy * qy + qz * qz));

            // 速度（可选）
            double linearVel  = 0, angularVel = 0;
            JsonObject twist = msg.getAsJsonObject("twist");
            if (twist != null && twist.has("twist")) {
                JsonObject twistInner = twist.getAsJsonObject("twist");
                linearVel  = twistInner.getAsJsonObject("linear").get("x").getAsDouble();
                angularVel = twistInner.getAsJsonObject("angular").get("z").getAsDouble();
            }

            JSONObject poseData = new JSONObject();
            poseData.put("x",          posX);
            poseData.put("y",          posY);
            poseData.put("theta",      theta);
            poseData.put("linearVel",  linearVel);
            poseData.put("angularVel", angularVel);

            pushService.pushToAll("robot_pose", poseData);
            log.debug("位姿推送: x={:.2f}, y={:.2f}, θ={:.1f}°",
                    posX, posY, Math.toDegrees(theta));

        } catch (Exception e) {
            log.error("处理 Odometry 失败: {}", e.getMessage());
        }
    }

    // ======================== 保留原有：/map 和 /amcl_pose ========================

    /** 兼容 SLAM Toolbox 的 OccupancyGrid */
    private void handleMapUpdate(JsonObject msg) {
        try {
            JsonObject info = msg.getAsJsonObject("info");
            if (info == null) return;

            int width     = info.get("width").getAsInt();
            int height    = info.get("height").getAsInt();
            double resolution = info.get("resolution").getAsDouble();

            JsonArray dataArray = msg.getAsJsonArray("data");
            if (dataArray == null) return;

            JsonObject origin   = info.getAsJsonObject("origin");
            JsonObject position = origin.getAsJsonObject("position");

            JSONArray dataArr = new JSONArray();
            for (int i = 0; i < dataArray.size(); i++) {
                dataArr.add(dataArray.get(i).getAsInt());
            }

            JSONObject mapData = new JSONObject();
            mapData.put("width",      width);
            mapData.put("height",     height);
            mapData.put("resolution", resolution);
            mapData.put("data",       dataArr);

            JSONObject originObj = new JSONObject();
            originObj.put("x", position.get("x").getAsDouble());
            originObj.put("y", position.get("y").getAsDouble());
            mapData.put("origin", originObj);

            pushService.pushToAll("map_update", mapData);
            log.info("地图更新: {}x{}", width, height);

        } catch (Exception e) {
            log.error("处理 /map 失败", e);
        }
    }

    /** amcl_pose（导航定位用） */
    private void handleRobotPose(JsonObject msg) {
        try {
            JsonObject pose     = msg.getAsJsonObject("pose").getAsJsonObject("pose");
            JsonObject position = pose.getAsJsonObject("position");
            JsonObject ori      = pose.getAsJsonObject("orientation");

            double theta = Math.atan2(
                    2 * (ori.get("w").getAsDouble() * ori.get("z").getAsDouble()
                            + ori.get("x").getAsDouble() * ori.get("y").getAsDouble()),
                    1 - 2 * (ori.get("y").getAsDouble() * ori.get("y").getAsDouble()
                            + ori.get("z").getAsDouble() * ori.get("z").getAsDouble())
            );

            JSONObject poseData = new JSONObject();
            poseData.put("x",     position.get("x").getAsDouble());
            poseData.put("y",     position.get("y").getAsDouble());
            poseData.put("theta", theta);

            pushService.pushToAll("robot_pose", poseData);
        } catch (Exception e) {
            log.error("处理 amcl_pose 失败", e);
        }
    }

    /**
     * 处理 /cmd_vel 速度指令
     *
     * 来源有两种：
     *   1. Nav2 自主导航时，controller_server 实时计算并发布（自动驾驶）
     *   2. 手动遥控时，前端 D-PAD → RobotController → publish /cmd_vel
     *
     * geometry_msgs/Twist 结构：
     *   linear:  { x: 线速度m/s, y: 0, z: 0 }
     *   angular: { x: 0,        y: 0, z: 角速度rad/s }
     */
    private void handleCmdVel(JsonObject msg) {
        try {
            JsonObject linear  = msg.getAsJsonObject("linear");
            JsonObject angular = msg.getAsJsonObject("angular");
            if (linear == null || angular == null) return;

            double linearX  = linear.get("x").getAsDouble();  // 线速度 m/s
            double angularZ = angular.get("z").getAsDouble(); // 角速度 rad/s

            // ① 推送前端（速度仪表盘显示）
            JSONObject velData = new JSONObject();
            velData.put("linear",  linearX);
            velData.put("angular", angularZ);
            pushService.pushToAll("cmd_vel_update", velData);

            // ② VelocityMonitorService 监控记录
            velocityService.handleVelocityMessage(msg);

            // ③ ✅ 核心：发送到底盘硬件（Java 作为协议翻译层）
            hardwareService.sendVelocity(linearX, angularZ);

        } catch (Exception e) {
            log.error("处理 /cmd_vel 失败: {}", e.getMessage());
        }
    }

    /** 路径规划 */
    private void handlePath(JsonObject msg) {
        try {
            JsonArray poses = msg.getAsJsonArray("poses");
            if (poses == null) return;

            JSONArray posesArray = new JSONArray();
            for (int i = 0; i < poses.size(); i++) {
                JsonObject position = poses.get(i).getAsJsonObject()
                        .getAsJsonObject("pose").getAsJsonObject("position");
                JSONObject pt = new JSONObject();
                pt.put("x", position.get("x").getAsDouble());
                pt.put("y", position.get("y").getAsDouble());
                posesArray.add(pt);
            }

            JSONObject pathData = new JSONObject();
            pathData.put("poses", posesArray);
            pushService.pushToAll("path_update", pathData);
        } catch (Exception e) {
            log.error("处理路径失败", e);
        }
    }

    @Override
    public boolean supportsPartialMessages() { return true; }
}