package org.jeecg.modules.ros2.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.ros2.config.ROS2Config;
import org.jeecg.modules.ros2.config.ROS2WebSocketHandler;
import org.jeecg.modules.ros2.service.RobotHardwareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ROS2BridgeService {

    @Autowired
    private ROS2Config ros2Config;

    @Autowired
    private WebSocketClient webSocketClient;

    @Autowired
    private VelocityMonitorService velocityService;

    @Autowired
    private WebSocketPushService pushService;

    @Autowired
    private RobotHardwareService hardwareService; // 底盘硬件控制（手动遥控时直发）

    private WebSocketSession session;
    private final Gson gson = new Gson();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    /** 等待 service_response 的请求，按 rosbridge id 配对 */
    private final Map<String, CompletableFuture<JsonObject>> pendingServiceCalls = new ConcurrentHashMap<>();
    private final AtomicLong serviceCallSeq = new AtomicLong();
    private ROS2WebSocketHandler webSocketHandler;
    // ===================== /amcl_pose 订阅缓存 =====================
    /** 最新 AMCL 位姿: [x, y, theta]，null 表示尚未收到 */
    private volatile double[] lastAmclPose = null;
    /** 最新 AMCL 位姿收到的时间戳(毫秒) */
    private volatile long lastAmclPoseMs = 0L;

    public void setWebSocketHandler(ROS2WebSocketHandler handler) {
        this.webSocketHandler = handler;
    }

    // === 加个对外的开关方法 ===
    public void setNavMode(boolean nav) {
        if (webSocketHandler != null) {
            webSocketHandler.setNavMode(nav);
        } else {
            log.warn("setNavMode 调用时 handler 还未注入,跳过");
        }
    }
    @PostConstruct
    public void init() {
        if (ros2Config.isAutoConnect()) {
            connect();
        }
    }

    /**
     * 返回最新 AMCL 位姿缓存 [x, y, theta]。
     * 如果从未收到过数据，返回 null。
     * MapController 的位姿自动保存定时任务直接调此方法，无需 fork 任何进程。
     */
    public double[] getLastAmclPose() {
        return lastAmclPose;
    }

    /**
     * 判断 AMCL 是否"活跃"：在 maxAgeMs 毫秒内收到过 /amcl_pose 消息。
     * 用于 isNav2Active() 判断，替代 ros2 service list shell 命令。
     *
     * @param maxAgeMs 心跳有效期，建议 60000（60秒）
     */
    public boolean isAmclAlive(long maxAgeMs) {
        return lastAmclPose != null
                && (System.currentTimeMillis() - lastAmclPoseMs) < maxAgeMs;
    }

    /**
     * 由 ROS2WebSocketHandler 在每次收到 /amcl_pose 消息时回调，更新本地缓存。
     * msg 是 rosbridge publish 帧里的 "msg" 字段（geometry_msgs/PoseWithCovarianceStamped）。
     */
    public void updateAmclPose(JsonObject msg) {
        try {
            JsonObject pose        = msg.getAsJsonObject("pose").getAsJsonObject("pose");
            JsonObject position    = pose.getAsJsonObject("position");
            JsonObject orientation = pose.getAsJsonObject("orientation");

            double x     = position.get("x").getAsDouble();
            double y     = position.get("y").getAsDouble();
            double qz    = orientation.get("z").getAsDouble();
            double qw    = orientation.get("w").getAsDouble();
            double theta = 2.0 * Math.atan2(qz, qw);

            lastAmclPose   = new double[]{x, y, theta};
            lastAmclPoseMs = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("[amcl_pose缓存] 解析失败: {}", e.getMessage());
        }
    }


    public void connect() {
        try {
            log.info("开始连接 ROS2 Bridge: {}", ros2Config.getBridgeUrl());

            ROS2WebSocketHandler handler = new ROS2WebSocketHandler(
                    velocityService,
                    pushService,
                    hardwareService,
                    this::onConnected,
                    this::attemptReconnect,
                    this::onServiceResponse,
                    this::updateAmclPose      // ← 新增：/amcl_pose 收到时更新本地缓存
            );

            URI uri = new URI(ros2Config.getBridgeUrl());
            session = webSocketClient.doHandshake(handler, new WebSocketHttpHeaders(), uri).get();

            log.info("✅ ROS2 Bridge 连接成功，Session: {}", session.getId());
            reconnectAttempts.set(0);

        } catch (Exception e) {
            log.error("连接 ROS2 Bridge 失败: {}", e.getMessage());
            attemptReconnect();
        }
    }

    private void onConnected(WebSocketSession connectedSession) {
        if (this.session == null) this.session = connectedSession;
        subscribeToTopics();
    }

    /**
     * ✅ 关键修复：订阅 fast_lio 真实发布的 topic
     *
     * fast_lio 发布：
     *   /cloud_registered  → sensor_msgs/PointCloud2  实时建图点云
     *   /Odometry          → nav_msgs/Odometry         机器人位姿
     *   /path              → nav_msgs/Path             轨迹（如果开启）
     *
     * ❌ fast_lio 不发布：/map (那是 SLAM Toolbox 的格式)
     */
    private void subscribeToTopics() {
        log.info("开始订阅话题...");

        // ================================================================
        // 订阅说明：
        //
        // 【订阅】= 接收数据（只读），用于前端显示
        // 【发布】= 发送指令（写），用于控制机器人
        //
        // ┌─────────────────────────────────────────────────────────────┐
        // │  话题           方向    作用                                  │
        // │  /cloud_registered 订阅  fast_lio点云 → 前端建图显示           │
        // │  /Odometry         订阅  fast_lio位姿 → 前端机器人图标位置      │
        // │  /amcl_pose        订阅  导航精确定位 → 前端机器人图标位置      │
        // │  /plan             订阅  Nav2规划路径 → 前端画绿色路径线        │
        // │  /cmd_vel          订阅  监控当前速度（只看，不控制）            │
        // │                                                               │
        // │  /cmd_vel          发布  ← 这才是控制车子移动的！               │
        // │                         由 publish("/cmd_vel",...) 发送        │
        // │                         D-PAD/键盘 → RobotController          │
        // │                         → publish("/cmd_vel") → 车子运动       │
        // │  /goal_pose        发布  ← 设置 Nav2 导航目标点                 │
        // │                         由 NavigationService.sendGoal() 发送   │
        // └─────────────────────────────────────────────────────────────┘
        // ================================================================

        // ① fast_lio 点云（建图实时显示）
        subscribe("/cloud_registered", "sensor_msgs/PointCloud2", 1000);

        // ② fast_lio 里程计（机器人实时位姿）
        subscribe("/Odometry", "nav_msgs/Odometry", 100);

        // ③ AMCL 定位（导航时比里程计更准）
        subscribe("/amcl_pose", "geometry_msgs/PoseWithCovarianceStamped", 200);

        // ④ Nav2 规划路径（路径不显示的原因就是没订阅这个！）
        //    Nav2 把规划路径发布到 /plan，前端需要这个来画路径线
        //    注意：/path 是 fast_lio 轨迹，/plan 才是 Nav2 导航路径
        subscribe("/plan", "nav_msgs/Path", 500);

        // ⑤ 速度监控（只读，用于前端显示当前速度，不用于控制）
        subscribe("/cmd_vel", "geometry_msgs/Twist", 200);
        subscribe("/cmd_vel_smoothed", "geometry_msgs/Twist", 200);

        // ⑥ ✅ 【新增】导航动作状态（到达/失败/取消 → 前端弹提示+清路径线）
        //    Nav2 在以下情况推送：
        //      status=4 到达目标 → 前端弹"导航完成"、清除路径线
        //      status=6 导航失败 → 前端弹"导航失败"警告
        subscribe("/navigate_to_pose/_action/status", "action_msgs/GoalStatusArray", 500);

        log.info("✅ 话题订阅完成: /cloud_registered /Odometry /amcl_pose /plan /cmd_vel /navigate_to_pose/_action/status");
    }

    private void subscribe(String topic, String type, int throttleRate) {
        JsonObject json = new JsonObject();
        json.addProperty("op", "subscribe");
        json.addProperty("topic", topic);
        json.addProperty("type", type);
        if (throttleRate > 0) {
            json.addProperty("throttle_rate", throttleRate);
        }
        send(json.toString());
        log.info("  已订阅: {} ({}), 节流: {}ms", topic, type, throttleRate);
    }

    private void subscribe(String topic, String type) {
        subscribe(topic, type, 0);
    }

    public void send(String message) {
        try {
            if (session == null || !session.isOpen()) {
                log.warn("ROS2 Bridge 未连接");
                return;
            }
            session.sendMessage(new TextMessage(message));
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }
    /**
     * 重新订阅 Nav2 相关 topic
     * 必须在 Nav2 完全启动后调用,否则订阅会绑到不存在的 publisher 上
     */
    public void resubscribeNav2Topics() {
        if (!isConnected()) {
            log.warn("rosbridge 未连接,无法重新订阅");
            return;
        }
        log.info("🔄 重新订阅 Nav2 相关 topic(确保绑到新启动的 publisher)...");

        // 先 unsubscribe(rosbridge 协议支持)
        for (String topic : new String[]{
                "/plan", "/amcl_pose", "/navigate_to_pose/_action/status"
        }) {
            JsonObject json = new JsonObject();
            json.addProperty("op", "unsubscribe");
            json.addProperty("topic", topic);
            send(json.toString());
        }

        // 等 50ms 让 rosbridge 处理 unsubscribe
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        // 再 subscribe(此时 Nav2 已起来,DDS discovery 能正确握手)
        subscribe("/plan",        "nav_msgs/Path", 500);
        subscribe("/amcl_pose",   "geometry_msgs/PoseWithCovarianceStamped", 200);
        subscribe("/navigate_to_pose/_action/status",
                "action_msgs/GoalStatusArray", 500);
        log.info("✅ Nav2 topic 已重新订阅");
    }
    /**
     * 预先声明话题类型
     * rosbridge 在第一次 publish 时如果话题还没被其他节点广播，
     * 则不知道消息类型，报 "Cannot infer topic type" 错误。
     * 解决方法：publish 前先 advertise 一次告知类型。
     */
    public void advertise(String topic, String type) {
        JsonObject json = new JsonObject();
        json.addProperty("op",    "advertise");
        json.addProperty("topic", topic);
        json.addProperty("type",  type);
        send(json.toString());
        log.info("[RosBridge] advertise: {} ({})", topic, type);
    }

    public void publish(String topic, String type, JsonObject message) {
        JsonObject json = new JsonObject();
        json.addProperty("op", "publish");
        json.addProperty("topic", topic);
        json.addProperty("type", type);
        json.add("msg", message);
        send(json.toString());
    }

    /**
     * 通过 rosbridge 同步调用一个 ROS2 service。
     * 复用现有长连接，不再 spawn ros2 CLI 子进程，杜绝孤儿进程与 SHM 残留。
     *
     * @return service_response 里的 values 对象；未连接/超时返回 null。
     */
    public JsonObject callService(String service, String type, JsonObject args, long timeoutMs) {
        if (!isConnected()) {
            log.warn("[callService] rosbridge 未连接，无法调用 {}", service);
            return null;
        }
        String id = "svc_" + serviceCallSeq.incrementAndGet() + "_" + System.currentTimeMillis();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingServiceCalls.put(id, future);

        JsonObject json = new JsonObject();
        json.addProperty("op", "call_service");
        json.addProperty("id", id);
        json.addProperty("service", service);
        if (type != null && !type.isEmpty()) json.addProperty("type", type);
        if (args != null) json.add("args", args);

        try {
            send(json.toString());
            log.info("[callService] -> {} id={} args={}", service, id, args);
            JsonObject values = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            log.info("[callService] <- {} id={} ok", service, id);
            return values;
        } catch (TimeoutException te) {
            log.error("[callService] ★超时 {}ms 未收到响应: {} id={}", timeoutMs, service, id);
            return null;
        } catch (Exception e) {
            log.error("[callService] 调用异常 {} id={}: {}", service, id, e.getMessage());
            return null;
        } finally {
            pendingServiceCalls.remove(id);   // 防止泄漏
        }
    }

    /** 由 ROS2WebSocketHandler 在收到 op=="service_response" 时回调 */
    public void onServiceResponse(JsonObject msg) {
        try {
            if (msg == null || !msg.has("id")) return;
            String id = msg.get("id").getAsString();
            CompletableFuture<JsonObject> future = pendingServiceCalls.get(id);
            if (future == null) {                  // 多半是已超时被移除的迟到响应
                log.warn("[callService] service_response 未匹配到 id={}", id);
                return;
            }
            // rosbridge 层 result=true 才算调用成功；真正的 srv 字段在 values 里
            boolean bridgeOk = !msg.has("result") || msg.get("result").getAsBoolean();
            if (!bridgeOk) log.warn("[callService] rosbridge result=false id={} msg={}", id, msg);
            JsonObject values = (msg.has("values") && msg.get("values").isJsonObject())
                    ? msg.getAsJsonObject("values") : new JsonObject();
            future.complete(values);
        } catch (Exception e) {
            log.error("[callService] 处理 service_response 失败: {}", e.getMessage());
        }
    }

    private void attemptReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        if (ros2Config.getMaxReconnectAttempts() > 0
                && attempts > ros2Config.getMaxReconnectAttempts()) {
            log.error("达到最大重连次数，停止重连");
            return;
        }
        log.info("尝试重连 ({}/{})", attempts,
                ros2Config.getMaxReconnectAttempts() > 0 ? ros2Config.getMaxReconnectAttempts() : "∞");
        try {
            Thread.sleep(ros2Config.getReconnectInterval());
            connect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 手动遥控发送速度指令
     * 同时做两件事：
     *   1. 发给底盘硬件（直接生效，不经过Nav2）
     *   2. 发布 /cmd_vel 到 ROS2（供 Nav2 监控，避免状态不一致）
     *
     * 由 RobotController（D-PAD接口）调用
     */
    public void sendCmdVel(double linear, double angular) {
        // ① 直接发底盘（实时性最好，不经过 rosbridge 延迟）
        hardwareService.sendVelocity(linear, angular);

        // ② 同步发到 ROS2 /cmd_vel（让 Nav2 感知当前速度，可选）
//        JsonObject linearVec  = new JsonObject();
//        linearVec.addProperty("x", linear);
//        linearVec.addProperty("y", 0.0);
//        linearVec.addProperty("z", 0.0);
//        JsonObject angularVec = new JsonObject();
//        angularVec.addProperty("x", 0.0);
//        angularVec.addProperty("y", 0.0);
//        angularVec.addProperty("z", angular);
//        JsonObject twist = new JsonObject();
//        twist.add("linear",  linearVec);
//        twist.add("angular", angularVec);
//        publish("/cmd_vel", "geometry_msgs/Twist", twist);
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (session != null && session.isOpen()) session.close();
        } catch (Exception e) {
            log.error("关闭连接失败", e);
        }
    }
}