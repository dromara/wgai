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
import java.util.List;
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

    @Autowired
    private RotationSafetyService rotationSafetyService; // 原地旋转净空判定（点云在抽稀前喂给它）

    @Autowired
    private MappingGridService mappingGridService; // 建图占据栅格（同样吃抽稀前的全量点）

    @Autowired
    private ObstacleGuardService obstacleGuardService; // 前向走廊避障（自动导航"前面有东西就停"）

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
    /** 最新 AMCL 位姿协方差: x方差(covariance[0]) / y方差(covariance[7]), 用于判断是否真正收敛 */
    private volatile double lastAmclCovXX = Double.MAX_VALUE;
    private volatile double lastAmclCovYY = Double.MAX_VALUE;

    /** connect() 每次重连都 new 一个 handler，这里始终指向最新那个(odom 位姿缓存在它身上) */
    private volatile ROS2WebSocketHandler activeHandler;

    private final List<java.util.function.BiConsumer<Integer, Long>> navTerminalListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 订阅导航终态 (status 4/5/6, goal stamp 纳秒)。handler 重连换了也不用重新注册 */
    public void addNavTerminalListener(java.util.function.BiConsumer<Integer, Long> l) {
        navTerminalListeners.add(l);
    }

    private final List<Runnable> planListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 订阅"收到一条 /plan" */
    public void addPlanListener(Runnable l) {
        planListeners.add(l);
    }

    /** 取消 Nav2 当前全部导航目标(走 rosbridge，毫秒级；不 fork ros2 CLI)。成功返回 true */
    public boolean cancelAllNavGoals() {
        com.google.gson.JsonArray uuid = new com.google.gson.JsonArray();
        for (int i = 0; i < 16; i++) uuid.add(0);
        JsonObject goalId = new JsonObject();
        goalId.add("uuid", uuid);
        JsonObject stamp = new JsonObject();
        stamp.addProperty("sec", 0);
        stamp.addProperty("nanosec", 0);
        JsonObject info = new JsonObject();
        info.add("goal_id", goalId);
        info.add("stamp", stamp);
        JsonObject args = new JsonObject();
        args.add("goal_info", info);   // 全零 uuid + 零时间戳 = 取消所有目标
        return callService("/navigate_to_pose/_action/cancel_goal", "action_msgs/srv/CancelGoal", args, 3000) != null;
    }

    private void fireNavTerminal(Integer status, Long stampNs) {
        for (java.util.function.BiConsumer<Integer, Long> l : navTerminalListeners) {
            l.accept(status, stampNs);
        }
    }

    /**
     * 最近一次 /Odometry 位姿 [x, y, theta](camera_init 系)，超过 maxAgeMs 没更新返回 null。
     * 50Hz 连续不跳变，适合做机动闭环；AMCL 只在车动过阈值才更新，原地小角度转根本不出新值。
     */
    public double[] getOdomPose(long maxAgeMs) {
        ROS2WebSocketHandler h = activeHandler;
        double[] p = h == null ? null : h.getOdomPose();
        if (p == null || System.currentTimeMillis() - (long) p[3] > maxAgeMs) return null;
        return new double[]{p[0], p[1], p[2]};
    }

    public void setWebSocketHandler(ROS2WebSocketHandler handler) {
        this.webSocketHandler = handler;
    }

    /**
     * 期望的位姿来源模式，connect() 里要重新喂给新 handler。
     * ⚠ handler 是每次(重)连都 new 的实例，它自己的 navMode 会重置回 true；
     *   而且 setWebSocketHandler 从来没人调过 → setNavMode 一直只打印"handler 还未注入,跳过"，
     *   建图时位姿源切不到 /Odometry，图标用的还是 AMCL 的 map 系坐标，
     *   和 camera_init 系的点云差多少就偏多少(2026-09-16 现场"建图时圆点位置不对")
     */
    private volatile boolean desiredNavMode = true;

    // === 加个对外的开关方法 ===
    public void setNavMode(boolean nav) {
        desiredNavMode = nav;
        ROS2WebSocketHandler h = activeHandler != null ? activeHandler : webSocketHandler;
        if (h != null) {
            h.setNavMode(nav);
        } else {
            log.warn("setNavMode({}) 时还没有连接上 rosbridge，已记下，连上后自动应用", nav);
        }
    }

    public boolean isNavMode() {
        return desiredNavMode;
    }
    @PostConstruct
    public void init() {
        if (ros2Config.isAutoConnect()) {
            connect();
        }
        odomWatchdog.scheduleWithFixedDelay(this::checkOdomAlive, 5, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 返回最新 AMCL 位姿缓存 [x, y, theta]。
     * 如果从未收到过数据，返回 null。
     * MapController 的位姿自动保存定时任务直接调此方法，无需 fork 任何进程。
     */
    public double[] getLastAmclPose() {
        return lastAmclPose;
    }

    /** 收到最近一次 /amcl_pose 时的里程计位姿 [x,y,theta]，null = 当时没有新鲜里程计 */
    private volatile double[] amclOdomRef = null;

    /**
     * 当前车在 map 系的估计位姿 = 最近一次 AMCL 位姿 ⊕ 此后 fast_lio 里程计的相对位移。
     * AMCL 车静止时不发新位姿、request_nomotion_update 有时也催不出来(现场"AMCL 5s 内没有新位姿")，
     * 但 fast_lio 一直在跑，拿它把上次 AMCL 位姿推到现在，精度≈AMCL 本身 + 里程计短时漂移(厘米级)。
     * @return null = 没有 AMCL 位姿，或里程计(当时/现在)不新鲜
     */
    public double[] getEstimatedMapPose() {
        double[] a = lastAmclPose, ref = amclOdomRef, now = getOdomPose(1500);
        if (a == null || ref == null || now == null) return null;
        double dx = now[0] - ref[0], dy = now[1] - ref[1];
        double c0 = Math.cos(ref[2]), s0 = Math.sin(ref[2]);
        double lx = c0 * dx + s0 * dy, ly = -s0 * dx + c0 * dy;          // 位移换到当时的车体系
        double ca = Math.cos(a[2]), sa = Math.sin(a[2]);
        double th = a[2] + (now[2] - ref[2]);
        return new double[]{a[0] + ca * lx - sa * ly, a[1] + sa * lx + ca * ly, Math.atan2(Math.sin(th), Math.cos(th))};
    }

    /** 最近一次收到 /amcl_pose 的时刻(ms)，0 = 没收到过 */
    public long getLastAmclPoseMs() {
        return lastAmclPoseMs;
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
     * 判断 AMCL 是否已真正收敛(而不是只看是否过了固定等待时间)。
     * PoseWithCovarianceStamped.pose.covariance 是 6x6 行优先展开的 36 元素数组,
     * covariance[0]=x方差, covariance[7]=y方差(行1列1, index=row*6+col=1*6+1=7)。
     *
     * @param threshold 方差阈值(单位 m²), 越小要求越严格; 现场建议 0.05
     */
    public boolean isAmclConverged(double threshold) {
        return lastAmclPose != null
                && lastAmclCovXX < threshold
                && lastAmclCovYY < threshold;
    }

    public double getLastAmclCovXX() { return lastAmclCovXX; }
    public double getLastAmclCovYY() { return lastAmclCovYY; }

    /**
     * 由 ROS2WebSocketHandler 在每次收到 /amcl_pose 消息时回调，更新本地缓存。
     * msg 是 rosbridge publish 帧里的 "msg" 字段（geometry_msgs/PoseWithCovarianceStamped）。
     */
    public void updateAmclPose(JsonObject msg) {
        try {
            JsonObject poseWithCov = msg.getAsJsonObject("pose");
            JsonObject pose        = poseWithCov.getAsJsonObject("pose");
            JsonObject position    = pose.getAsJsonObject("position");
            JsonObject orientation = pose.getAsJsonObject("orientation");

            double x     = position.get("x").getAsDouble();
            double y     = position.get("y").getAsDouble();
            double qz    = orientation.get("z").getAsDouble();
            double qw    = orientation.get("w").getAsDouble();
            double theta = 2.0 * Math.atan2(qz, qw);

            lastAmclPose   = new double[]{x, y, theta};
            lastAmclPoseMs = System.currentTimeMillis();
            amclOdomRef    = getOdomPose(300);   // 同一时刻的里程计，用来往后推算(见 getEstimatedMapPose)

            if (poseWithCov.has("covariance")) {
                com.google.gson.JsonArray cov = poseWithCov.getAsJsonArray("covariance");
                if (cov.size() >= 8) {
                    lastAmclCovXX = cov.get(0).getAsDouble();
                    lastAmclCovYY = cov.get(7).getAsDouble();
                }
            }
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
                    rotationSafetyService,
                    mappingGridService,
                    obstacleGuardService,
                    this::onConnected,
                    this::attemptReconnect,
                    this::onServiceResponse,
                    this::updateAmclPose      // ← 新增：/amcl_pose 收到时更新本地缓存
            );
            handler.setNavTerminalListener(this::fireNavTerminal);
            handler.setPlanListener(() -> planListeners.forEach(Runnable::run));
            handler.setNavMode(desiredNavMode);   // 重连后把位姿来源模式重新喂给新 handler
            activeHandler = handler;
            webSocketHandler = handler;

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
        // ⚠ 必须无条件换成新连接。原来写的是 if (session == null)：Java 刚启动时 session 为空没问题，
        //   但 rosbridge 断开重连时 session 还是旧的已关闭连接 → 下面的订阅全发到死连接上丢了，
        //   等 connect() 里 doHandshake().get() 返回才换成新 session，订阅早错过了 →
        //   /Odometry /amcl_pose /plan 点云全收不到、isConnected() 却是 true。
        //   现场表现"重启 ROS 栈、切菜单都没用，只有重启 Java 才恢复"(2026-09-15)
        this.session = connectedSession;
        connectedAtMs = System.currentTimeMillis();
        subscribeToTopics();
        for (Runnable l : connectListeners) {
            try { l.run(); } catch (Exception e) { log.warn("重连回调异常: {}", e.getMessage()); }
        }
    }

    private volatile long connectedAtMs = 0L;
    private final List<Runnable> connectListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 每次(重新)连上 rosbridge 后回调，用来重置 advertise 之类只对单个连接有效的状态 */
    public void addConnectListener(Runnable l) {
        connectListeners.add(l);
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
        subscribe("/cloud_registered", "sensor_msgs/PointCloud2", 200);

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

        // 等 rosbridge 处理完 unsubscribe 再订(50ms 在点云繁忙时不够，见 resubscribeFastLioTopics)
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // 再 subscribe(此时 Nav2 已起来,DDS discovery 能正确握手)
        subscribe("/plan",        "nav_msgs/Path", 500);
        subscribe("/amcl_pose",   "geometry_msgs/PoseWithCovarianceStamped", 200);
        subscribe("/navigate_to_pose/_action/status",
                "action_msgs/GoalStatusArray", 500);
        log.info("✅ Nav2 topic 已重新订阅");
    }

    /**
     * 重新订阅 fast_lio 相关 topic（/cloud_registered、/Odometry）
     * 原理同 resubscribeNav2Topics：rosbridge 的订阅会绑死在当时的 publisher 上，
     * fast_lio 进程重启（stop 再 start）后是全新的 publisher，旧订阅收不到新数据，
     * 表现为"停止建图再开始建图后前端收不到点云，只有重启 Java 才恢复"。
     * MappingController 每次成功(重新)启动 fast_lio 后都要调用一次。
     */
    public void resubscribeFastLioTopics() {
        if (!isConnected()) {
            log.warn("rosbridge 未连接,无法重新订阅");
            return;
        }
        log.info("🔄 重新订阅 fast_lio 相关 topic(确保绑到新启动的 publisher)...");

        for (String topic : new String[]{"/cloud_registered", "/Odometry"}) {
            JsonObject json = new JsonObject();
            json.addProperty("op", "unsubscribe");
            json.addProperty("topic", topic);
            send(json.toString());
        }

        // ⚠ 50ms 不够：WebSocket 上正挤着大点云，rosbridge 可能在新 subscribe 之后才处理到 unsubscribe，
        //   结果把刚订上的又退掉 → Java 永久收不到 /Odometry(2026-09-15 现场"切个菜单回来里程计就没了")。
        //   另有 startOdomWatchdog() 兜底自愈
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        subscribe("/cloud_registered", "sensor_msgs/PointCloud2", 200);
        subscribe("/Odometry", "nav_msgs/Odometry", 100);
        log.info("✅ fast_lio topic 已重新订阅");
    }

    private final java.util.concurrent.ScheduledExecutorService odomWatchdog =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "odom-watchdog");
                t.setDaemon(true);
                return t;
            });
    private volatile long lastOdomResubMs = 0L;

    /**
     * /Odometry 断流自愈：曾经收到过、现在超过 3s 没来、rosbridge 还连着 → 只补发 subscribe(不先 unsubscribe，
     * 避免再撞上面那个乱序)。rosbridge 对同一客户端同一 topic 重复 subscribe 不会重复推送。
     * 10s 内最多补一次，免得 fast_lio 真挂了时刷屏。
     */
    private void checkOdomAlive() {
        try {
            ROS2WebSocketHandler h = activeHandler;
            if (h == null || !isConnected()) return;
            double[] p = h.getOdomPose();
            long now = System.currentTimeMillis();
            if (p == null) {
                // 这个连接上一条都没收到过(重连后订阅没发出去的老症状)：连上 8s 还没有就把全部话题重订一遍
                if (now - connectedAtMs > 8000 && now - lastOdomResubMs > 10_000) {
                    lastOdomResubMs = now;
                    log.warn("[里程计看门狗] 连上 rosbridge {}ms 仍未收到任何 /Odometry，重新订阅全部话题", now - connectedAtMs);
                    subscribeToTopics();
                }
                return;
            }
            if (now - (long) p[3] > 3000 && now - lastOdomResubMs > 10_000) {
                lastOdomResubMs = now;
                log.warn("[里程计看门狗] {}ms 没收到 /Odometry，补发订阅 /Odometry /cloud_registered。"
                        + "若持续出现，在机器人上 ros2 topic hz /Odometry 确认 fast_lio 是否在跑", now - (long) p[3]);
                subscribe("/Odometry", "nav_msgs/Odometry", 100);
                subscribe("/cloud_registered", "sensor_msgs/PointCloud2", 200);
            }
        } catch (Exception e) {
            log.debug("[里程计看门狗] 异常: {}", e.getMessage());
        }
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

    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 断线重连。
     * ⚠ 以前超过 maxReconnectAttempts(10 次×5s=50s)就永久放弃，ROS 栈重启慢一点 Java 就再也连不回去，
     *   只能重启 Java。现在超过次数后不放弃，改成每 30s 试一次。
     *   也不再在回调线程里 sleep + 递归 connect()(断得久了栈会越压越深)，改为调度执行，同一时刻只排一个。
     */
    private void attemptReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        int attempts = reconnectAttempts.incrementAndGet();
        int max = ros2Config.getMaxReconnectAttempts();
        long delay = (max > 0 && attempts > max) ? 30_000L : ros2Config.getReconnectInterval();
        log.info("{}ms 后尝试重连 (第 {} 次{})", delay, attempts, (max > 0 && attempts > max) ? "，已超过快速重连次数，改为每 30s 一次" : "");
        odomWatchdog.schedule(() -> {
            reconnectScheduled.set(false);
            if (isConnected()) return;
            connect();
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
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