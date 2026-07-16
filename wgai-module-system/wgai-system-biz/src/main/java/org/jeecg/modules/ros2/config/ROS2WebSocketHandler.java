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
 * 核心职责：rosbridge → Java 翻译 → 前端显示 + 底盘硬件
 *
 * 消息流向：
 *   【订阅方向】ROS2 → rosbridge → 这里 → 前端WebSocket推送
 *     /amcl_pose                       → robot_pose      → 导航模式机器人位置
 *     /Odometry                        → robot_pose      → 建图模式机器人位置(navMode=false才推)
 *     /plan                            → path_update     → 前端绿色路径线
 *     /cmd_vel                         → cmd_vel_update  → 前端速度仪表盘
 *     /navigate_to_pose/_action/status → nav_status      → 导航完成/失败通知
 *
 * 【模式切换核心】navMode 标志:
 *   true  = 导航模式: 只用 /amcl_pose 推送位姿,/Odometry 仅缓存速度(默认)
 *   false = 建图模式: 用 /Odometry 推送位姿(此时 AMCL 不在跑,/amcl_pose 不会进来)
 *
 *   切换入口:
 *     - MapController.loadMap()      成功 → setNavMode(true)
 *     - NavigationController.advertise()  → setNavMode(true)
 *     - 建图启动接口(自行实现)            → setNavMode(false)
 *
 * 为什么必须切换? /Odometry 是 odom 坐标系,/amcl_pose 是 map 坐标系。
 * 两者频率不同(50Hz vs 2Hz),如果都推 robot_pose,前端就会被 odom 帧覆盖,
 * 表现为 "AMCL 初始位姿设置无效 / 雷达位置和地图不匹配 / 位置不停跳变"。
 */
@Slf4j
public class ROS2WebSocketHandler extends TextWebSocketHandler {

    private final VelocityMonitorService velocityService;
    private final WebSocketPushService   pushService;
    private final RobotHardwareService   hardwareService;
    private final Gson gson = new Gson();
    private final Consumer<WebSocketSession> onConnectCallback;
    private final Runnable onDisconnectCallback;
    private final Consumer<JsonObject> onServiceResponseCallback;
    /** 每次收到 /amcl_pose 时回调，用于更新 ROS2BridgeService 的位姿缓存 */
    private final Consumer<JsonObject> onAmclPoseCallback;
    /** supportsPartialMessages()=true,大消息会分片到达,拼到 isLast 再解析 */
    private final StringBuilder partialBuf = new StringBuilder();

    // 点云下采样:最多推送给前端的点数
    private static final int MAX_POINTS_TO_PUSH = 3000;

    // ====================== 模式标志 ======================

    /**
     * 模式标志(关键!)
     *   true  = 导航模式 → 只推 /amcl_pose,/Odometry 仅缓存速度
     *   false = 建图模式 → 推 /Odometry
     *
     * 默认 true: 系统主要用于导航,建图属于偶发操作。
     */
    private volatile boolean navMode = true;

    public void setNavMode(boolean navMode) {
        boolean changed = (this.navMode != navMode);
        this.navMode = navMode;
        if (changed) {
            log.info("🔄 [模式切换] → {}",
                    navMode ? "导航模式 (位姿来源: /amcl_pose)"
                            : "建图模式 (位姿来源: /Odometry)");
        }
    }

    public boolean isNavMode() { return navMode; }

    // ====================== 速度缓存 ======================

    // amcl_pose 只有位姿没有速度;速度从 /Odometry 或 /cmd_vel 缓存而来
    private volatile double cachedLinearVel  = 0.0;
    private volatile double cachedAngularVel = 0.0;
    private volatile long lastSmoothedCmdVelMs = 0L;

    public ROS2WebSocketHandler(
            VelocityMonitorService velocityService,
            WebSocketPushService pushService,
            RobotHardwareService hardwareService,
            Consumer<WebSocketSession> onConnectCallback,
            Runnable onDisconnectCallback,
            Consumer<JsonObject> onServiceResponseCallback,
            Consumer<JsonObject> onAmclPoseCallback) {
        this.velocityService           = velocityService;
        this.pushService               = pushService;
        this.hardwareService           = hardwareService;
        this.onConnectCallback         = onConnectCallback;
        this.onDisconnectCallback      = onDisconnectCallback;
        this.onServiceResponseCallback = onServiceResponseCallback;
        this.onAmclPoseCallback        = onAmclPoseCallback;
    }

    // ======================== 生命周期 ========================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("✅ ROS2 Bridge 连接建立: {}", session.getId());
        session.setTextMessageSizeLimit(20 * 1024 * 1024);
        session.setBinaryMessageSizeLimit(20 * 1024 * 1024);
        if (onConnectCallback != null) onConnectCallback.accept(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // supportsPartialMessages()=true,大消息(如 load_map 回传的整张地图)会分片到达,
        // 必须拼接到 isLast 再解析;单 session 串行回调,StringBuilder 无需加锁。
        partialBuf.append(message.getPayload());
        if (!message.isLast()) return;
        String full = partialBuf.toString();
        partialBuf.setLength(0);
        handleRosMessage(full);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("ROS2 Bridge 断开: code={}, reason={}", status.getCode(), status.getReason());
        if (onDisconnectCallback != null) onDisconnectCallback.run();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.warn("ROS2 Bridge 传输错误: {}", ex.getMessage());
    }

    // ======================== 消息分发 ========================

    private void handleRosMessage(String raw) {
        try {
            JsonObject json = gson.fromJson(raw, JsonObject.class);

            // ★ service 调用响应:没有 topic/msg,必须在下面的判空 return 之前拦截,
            //   否则会被当成无效消息直接丢弃,导致 ROS2BridgeService 那边永远等不到响应。
            if (json.has("op") && "service_response".equals(json.get("op").getAsString())) {
                if (onServiceResponseCallback != null) onServiceResponseCallback.accept(json);
                return;
            }

            if (!json.has("topic") || !json.has("msg")) return;

            String topic = json.get("topic").getAsString();
            JsonObject msg = json.getAsJsonObject("msg");

            switch (topic) {
                case "/cloud_registered": //点云数据
                    handlePointCloud(msg);
                    break;
                case "/Odometry": //OD坐标
                    handleOdometry(msg);
                    break;
                case "/amcl_pose":// 初始化位姿
                    handleAmclPose(msg);

                    break;
                case "/plan": //规划路线
                    handleNavPlan(msg);
                    break;
                case "/path": //规划路径
                    handlePath(msg);
                    break;
                case "/navigate_to_pose/_action/status": //导航状态位姿
                    handleNavStatus(msg);
                    break;
                case "/cmd_vel": //速度命令
                case "/cmd_vel_smoothed":
                    handleCmdVel(topic, msg);
                    break;
                case "/map": //地图数据
                    handleMapUpdate(msg);
                    break;
                default:
                    log.warn("未处理的话题: {}", topic);
            }
        } catch (Exception e) {
            log.warn("处理消息失败: {}", e.getMessage());
        }
    }

    // ======================== 点云 ========================

    private void handlePointCloud(JsonObject msg) {
        try {
            int pointStep = msg.has("point_step") ? msg.get("point_step").getAsInt() : 16;
            int width     = msg.has("width")      ? msg.get("width").getAsInt()      : 0;

            if (width == 0 || !msg.has("data")) {
                log.warn("点云数据为空,跳过");
                return;
            }

            String dataBase64 = msg.get("data").getAsString();
            byte[] rawData    = Base64.getDecoder().decode(dataBase64);

            int totalPoints = rawData.length / pointStep;
            int step = Math.max(1, totalPoints / MAX_POINTS_TO_PUSH);

            int xOffset = 0, yOffset = 4, zOffset = 8;
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

            JSONArray points = new JSONArray();
            for (int i = 0; i < totalPoints; i += step) {
                int base = i * pointStep;
                if (base + zOffset + 4 > rawData.length) break;

                float x = readFloat(rawData, base + xOffset);
                float y = readFloat(rawData, base + yOffset);
                float z = readFloat(rawData, base + zOffset);

                if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y))
                    continue;
                if (z < -0.5f || z > 2.0f) continue;

                JSONObject pt = new JSONObject();
                pt.put("x", Math.round(x * 1000.0) / 1000.0);
                pt.put("y", Math.round(y * 1000.0) / 1000.0);
                pt.put("z", Math.round(z * 1000.0) / 1000.0);
                points.add(pt);
            }

            if (points.isEmpty()) {
                log.warn("过滤后点云为空");
                return;
            }

            JSONObject cloudData = new JSONObject();
            cloudData.put("points",    points);
            cloudData.put("totalRaw",  totalPoints);
            cloudData.put("pushCount", points.size());

            pushService.pushToAll("cloud_update", cloudData);

        } catch (Exception e) {
            log.warn("处理点云失败: {}", e.getMessage());
        }
    }

    private float readFloat(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    // ======================== 里程计(建图时用) ========================

    /**
     * 解析 nav_msgs/Odometry(fast_lio 发布,odom 坐标系)
     *
     * 行为:
     *   - 始终缓存速度(供 amcl_pose 推送时附带)
     *   - navMode=true(导航模式):  到此为止,不推 robot_pose
     *   - navMode=false(建图模式): 推 robot_pose 用于建图实时显示
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
            double theta = quaternionToYaw(orientation);

            // 1) 始终缓存速度
            double linearVel  = 0;
            double angularVel = 0;
            JsonObject twist = msg.getAsJsonObject("twist");
            if (twist != null && twist.has("twist")) {
                JsonObject twistInner = twist.getAsJsonObject("twist");
                linearVel  = twistInner.getAsJsonObject("linear").get("x").getAsDouble();
                angularVel = twistInner.getAsJsonObject("angular").get("z").getAsDouble();
                cachedLinearVel  = linearVel;
                cachedAngularVel = angularVel;
            }

            // 2) ⭐ 导航模式: 不推位姿,让 /amcl_pose 独家推送 robot_pose
            if (navMode) return;

            // 3) 建图模式: 推送里程计位姿
            JSONObject poseData = new JSONObject();
            poseData.put("x",          posX);
            poseData.put("y",          posY);
            poseData.put("theta",      theta);
            poseData.put("linearVel",  linearVel);
            poseData.put("angularVel", angularVel);

            pushService.pushToAll("robot_pose", poseData);

        } catch (Exception e) {
            log.warn("处理 Odometry 失败: {}", e.getMessage());
        }
    }

    // ======================== AMCL 精确位姿(导航时用) ========================

    /**
     * 解析 geometry_msgs/PoseWithCovarianceStamped(AMCL 输出,map 坐标系)
     *
     * AMCL 不发速度,这里附带 cachedLinearVel/cachedAngularVel
     * (来自最近一次的 /Odometry 或 /cmd_vel)。
     *
     * 注意:此方法不受 navMode 限制 —— 建图时 AMCL 不在运行,本来就收不到 /amcl_pose;
     *      多一层 if 反而是冗余,直接信任源头即可。
     */
    private void handleAmclPose(JsonObject msg) {
        try {
            JsonObject pose     = msg.getAsJsonObject("pose").getAsJsonObject("pose");
            JsonObject position = pose.getAsJsonObject("position");
            JsonObject ori      = pose.getAsJsonObject("orientation");

            double posX  = position.get("x").getAsDouble();
            double posY  = position.get("y").getAsDouble();
            double theta = quaternionToYaw(ori);

            JSONObject poseData = new JSONObject();
            poseData.put("x",          posX);
            poseData.put("y",          posY);
            poseData.put("theta",      theta);
            poseData.put("linearVel",  cachedLinearVel);
            poseData.put("angularVel", cachedAngularVel);

            pushService.pushToAll("robot_pose", poseData);

            // ★ 更新 ROS2BridgeService 位姿缓存，供 MapController 位姿自动保存使用
            //   （替代原来每5s fork ros2 topic echo 的 shell 命令）
            if (onAmclPoseCallback != null) onAmclPoseCallback.accept(msg);

            log.warn("AMCL位姿: x={}, y={}, θ={}°", posX, posY, (int) Math.toDegrees(theta));

        } catch (Exception e) {
            log.warn("处理 amcl_pose 失败: {}", e.getMessage());
        }
    }

    // ======================== Nav2 路径 ========================

    private void handleNavPlan(JsonObject msg) {
        try {
            JsonArray poses = msg.getAsJsonArray("poses");
            if (poses == null || poses.size() == 0) {
                log.warn("/plan 路径为空,跳过");
                return;
            }

            int total = poses.size();
            int step  = Math.max(1, total / 200);

            JSONArray posesArray = new JSONArray();
            for (int i = 0; i < total; i += step) {
                JsonObject position = poses.get(i).getAsJsonObject()
                        .getAsJsonObject("pose").getAsJsonObject("position");
                JSONObject pt = new JSONObject();
                pt.put("x", position.get("x").getAsDouble());
                pt.put("y", position.get("y").getAsDouble());
                posesArray.add(pt);
            }

            // 确保终点被包含
            if (total > 1) {
                JsonObject lastPos = poses.get(total - 1).getAsJsonObject()
                        .getAsJsonObject("pose").getAsJsonObject("position");
                JSONObject lastPt = new JSONObject();
                lastPt.put("x", lastPos.get("x").getAsDouble());
                lastPt.put("y", lastPos.get("y").getAsDouble());
                posesArray.add(lastPt);
            }

            JSONObject pathData = new JSONObject();
            pathData.put("poses", posesArray);
            pushService.pushToAll("path_update", pathData);

            log.info("✅ 导航路径推送: 原始{}点 → 下采样{}点", total, posesArray.size());

        } catch (Exception e) {
            log.warn("处理 /plan 路径失败: {}", e.getMessage());
        }
    }

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
            log.warn("处理 /path 轨迹失败: {}", e.getMessage());
        }
    }

    // ======================== 导航动作状态 ========================

    private void handleNavStatus(JsonObject msg) {
        try {
            JsonArray statusList = msg.getAsJsonArray("status_list");
            if (statusList == null || statusList.size() == 0) return;

            JsonObject latestGoal = statusList.get(statusList.size() - 1).getAsJsonObject();
            int status = latestGoal.get("status").getAsInt();

            String statusText;
            boolean navigating;
            boolean reached = false;
            boolean failed  = false;

            switch (status) {
                case 1: statusText = "已接受";   navigating = true;  break;
                case 2: statusText = "导航中";   navigating = true;  break;
                case 3: statusText = "取消中";   navigating = false; break;
                case 4: statusText = "到达目标"; navigating = false; reached = true;  break;
                case 5: statusText = "已取消";   navigating = false; break;
                case 6: statusText = "导航失败"; navigating = false; failed  = true;  break;
                default: statusText = "未知";    navigating = false; break;
            }

            JSONObject statusData = new JSONObject();
            statusData.put("status",    status);
            statusData.put("statusText",statusText);
            statusData.put("navigating",navigating);
            statusData.put("reached",   reached);
            statusData.put("failed",    failed);
            pushService.pushToAll("nav_status", statusData);

            if (reached) {
                log.info("🎉 导航完成!机器人已到达目标点");
                cachedLinearVel  = 0.0;
                cachedAngularVel = 0.0;
            } else if (failed) {
                log.warn("❌ 导航失败! 状态: {}", statusText);
            }

        } catch (Exception e) {
            log.warn("处理导航状态失败: {}", e.getMessage());
        }
    }

    // ======================== cmd_vel ========================

    private void handleCmdVel(String topic, JsonObject msg) {
        try {
            JsonObject linear  = msg.getAsJsonObject("linear");
            JsonObject angular = msg.getAsJsonObject("angular");
            if (linear == null || angular == null) return;

            double linearX  = linear.get("x").getAsDouble();
            double angularZ = angular.get("z").getAsDouble();

            cachedLinearVel  = linearX;
            cachedAngularVel = angularZ;

            JSONObject velData = new JSONObject();
            velData.put("linear",  linearX);
            velData.put("angular", angularZ);
            pushService.pushToAll("cmd_vel_update", velData);

            velocityService.handleVelocityMessage(msg);

            long now = System.currentTimeMillis();
            if ("/cmd_vel_smoothed".equals(topic)) {
                lastSmoothedCmdVelMs = now;
                hardwareService.sendVelocity(linearX, angularZ);
            } else if (now - lastSmoothedCmdVelMs > 500) {
                hardwareService.sendVelocity(linearX, angularZ);
            }

        } catch (Exception e) {
            log.warn("处理 /cmd_vel 失败: {}", e.getMessage());
        }
    }

    // ======================== 地图(SLAM Toolbox 兼容) ========================

    private void handleMapUpdate(JsonObject msg) {
        try {
            JsonObject info = msg.getAsJsonObject("info");
            if (info == null) return;

            int width         = info.get("width").getAsInt();
            int height        = info.get("height").getAsInt();
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
            log.warn("处理 /map 失败", e);
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 四元数 → 偏航角(yaw)
     */
    private double quaternionToYaw(JsonObject ori) {
        double qx = ori.get("x").getAsDouble();
        double qy = ori.get("y").getAsDouble();
        double qz = ori.get("z").getAsDouble();
        double qw = ori.get("w").getAsDouble();
        return Math.atan2(
                2.0 * (qw * qz + qx * qy),
                1.0 - 2.0 * (qy * qy + qz * qz)
        );
    }

    @Override
    public boolean supportsPartialMessages() { return true; }
}
