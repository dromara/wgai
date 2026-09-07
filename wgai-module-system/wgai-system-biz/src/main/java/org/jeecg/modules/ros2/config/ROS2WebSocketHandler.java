package org.jeecg.modules.ros2.config;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import lombok.var;
import org.jeecg.modules.ros2.service.MappingGridService;
import org.jeecg.modules.ros2.service.RobotHardwareService;
import org.jeecg.modules.ros2.service.RotationSafetyService;
import org.jeecg.modules.ros2.service.VelocityMonitorService;
import org.jeecg.modules.ros2.service.WebSocketPushService;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
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
    /** 原地旋转安全判定:每帧点云在抽稀前先喂给它做净空累积 */
    private final RotationSafetyService  rotationSafetyService;
    /** 建图占据栅格:同样吃抽稀前的全量点,让保存地图不必再杀 fast_lio 取 PCD */
    private final MappingGridService     mappingGridService;
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

    // 点云接收日志节流:每隔多久打印一次"收到点云"心跳日志,避免刷屏
    private static final long POINT_CLOUD_LOG_INTERVAL_MS = 5000;
    private final AtomicLong lastPointCloudLogMs = new AtomicLong(0);

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
    /** 最后一次收到**任何** /cmd_vel 的时刻。导航失败时用它区分"Nav2 没输出速度"和"底盘没执行" */
    private volatile long lastAnyCmdVelMs = 0L;

    // ====================== 导航状态去重 ======================

    /** 上一次已推送过的 (目标 uuid, status)，避免终态被反复重播时前端每 500ms 弹一次提示 */
    private String lastNavGoalUuid = "";
    private int lastNavStatus = -1;

    // ====================== 里程计位姿缓存 ======================

    /**
     * 最近一次 /Odometry 的位姿(camera_init 系)。
     * /cloud_registered 的点也在 camera_init 系,旋转安全判定要靠这个位姿把点反变换回车体系,
     * 所以必须在 navMode 提前 return 之前就缓存下来(导航模式恰恰是最需要旋转判定的时候)。
     */
    private volatile double cachedOdomX     = 0.0;
    private volatile double cachedOdomY     = 0.0;
    private volatile double cachedOdomTheta = 0.0;
    private volatile boolean hasOdomPose    = false;

    public ROS2WebSocketHandler(
            VelocityMonitorService velocityService,
            WebSocketPushService pushService,
            RobotHardwareService hardwareService,
            RotationSafetyService rotationSafetyService,
            MappingGridService mappingGridService,
            Consumer<WebSocketSession> onConnectCallback,
            Runnable onDisconnectCallback,
            Consumer<JsonObject> onServiceResponseCallback,
            Consumer<JsonObject> onAmclPoseCallback) {
        this.velocityService           = velocityService;
        this.pushService               = pushService;
        this.hardwareService           = hardwareService;
        this.rotationSafetyService     = rotationSafetyService;
        this.mappingGridService        = mappingGridService;
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

            // ★ 原地旋转安全判定:必须用**全量**点(在下面按 step 抽稀之前)。
            //   抽稀是为了前端渲染,一根立柱/一个人完全可能整个落在采样间隔里被漏掉,
            //   拿抽稀后的点做安全判定等于漏检。
            if (rotationSafetyService != null && hasOdomPose) {
                rotationSafetyService.updateFromCloud(
                        rawData, pointStep, totalPoints,
                        xOffset, yOffset, zOffset,
                        cachedOdomX, cachedOdomY, cachedOdomTheta);
            }

            // ★ 建图栅格累积:同样要用全量点。开关在 service 自己身上(由 /start、/cancel 控制),
            //   这里无条件喂,它内部判 enabled。
            //   ⚠ 别改成判 navMode:那是本类的实例字段,而 ROS2BridgeService.connect() 每次重连
            //     都 new 一个新 handler,navMode 被重置回 true,累积就静默停了。
            if (mappingGridService != null) {
                mappingGridService.updateFromCloud(
                        rawData, pointStep, totalPoints, xOffset, yOffset, zOffset);
            }

            // 推给前端的高度范围必须和栅格累积范围**完全一致**,否则前端就有盲区。
            //
            // ⚠ 这里曾写死 -1f ~ 10f,而栅格收的是 mapping.grid.z-min/z-max(默认 -5 ~ 10),
            //   于是 z ∈ [-5, -1) 的点前端一个都看不到、却全都进了栅格。
            //   camera_init 的 z 原点是雷达开机位置(离地约 1m),**地面正好在 z ≈ -1.0**,
            //   地面不平/车身俯仰/下坡时地面点就落到 -1 以下 → 前端丢掉、栅格照收 →
            //   保存时不开高度过滤(-99~99)就全画进 pgm。现象正是
            //   "预览干净、存出来地上莫名多一簇密集点"(近场地面回波最密,呈放射状一小片)。
            //   这类不对称过滤是最难查的:两边看的是同一帧,却只有一边显示。
            float pushZMin = mappingGridService != null ? (float) mappingGridService.getAcceptZMin() : -5f;
            float pushZMax = mappingGridService != null ? (float) mappingGridService.getAcceptZMax() : 10f;

            JSONArray points = new JSONArray();
            for (int i = 0; i < totalPoints; i += step) {
                int base = i * pointStep;
                if (base + zOffset + 4 > rawData.length) break;

                float x = readFloat(rawData, base + xOffset);
                float y = readFloat(rawData, base + yOffset);
                float z = readFloat(rawData, base + zOffset);

                if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)
                        || Float.isNaN(z) || Float.isInfinite(z))
                    continue;
                if (z < pushZMin || z > pushZMax) continue;

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

            // 心跳日志:每隔 POINT_CLOUD_LOG_INTERVAL_MS 打一行,确认点云在正常传输,不刷屏
            long now = System.currentTimeMillis();
            long last = lastPointCloudLogMs.get();
            if (now - last >= POINT_CLOUD_LOG_INTERVAL_MS && lastPointCloudLogMs.compareAndSet(last, now)) {
                // 带上栅格状态:光看"点云正常接收"会误以为建图数据也在攒,
                // 实际累积开关没开时一个格都不会进,保存时才发现是空图
                log.info("📡 点云正常接收: 原始{}点 → 推送{}点 | 建图栅格: {}",
                        totalPoints, points.size(),
                        mappingGridService == null ? "未接入"
                                : (mappingGridService.isEnabled()
                                        ? "累积中 " + mappingGridService.cellCount() + " 格"
                                        : "未开始(点「开始建图」)"));
            }

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

            // 0) 始终缓存位姿(旋转安全判定要用,必须在下面 navMode return 之前)
            cachedOdomX     = posX;
            cachedOdomY     = posY;
            cachedOdomTheta = theta;
            hasOdomPose     = true;

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

            // ⚠ status_list 里同时存着"当前目标"和"已结束的历史目标"——rclcpp_action 在
            //   result_timeout 内不会丢弃 goal handle，而遍历顺序来自 unordered_map，
            //   最新的目标**不保证**在数组末尾。
            //   原来直接取 size()-1，会把上一个目标遗留的 status=4 当成本次导航的结果推给前端
            //   → 表现为"刚点新目标就弹 🎉导航完成"、"点了没反应"，且完全随机复现。
            //   正确做法：按 goal_info.stamp 取时间戳最大的那条。
            JsonObject latestGoal = null;
            long latestStamp = Long.MIN_VALUE;
            for (int i = 0; i < statusList.size(); i++) {
                JsonObject item = statusList.get(i).getAsJsonObject();
                long stampNs = extractGoalStampNs(item);
                if (stampNs >= latestStamp) {
                    latestStamp = stampNs;
                    latestGoal = item;
                }
            }
            if (latestGoal == null) return;

            int status = latestGoal.get("status").getAsInt();
            String uuid = extractGoalUuid(latestGoal);

            // 终态(4/5/6)会被持续重播，同一目标的同一状态只推一次
            if (status == lastNavStatus && uuid.equals(lastNavGoalUuid)) return;
            lastNavStatus = status;
            lastNavGoalUuid = uuid;

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
                // 光一句"导航失败"没法往下查。最有区分度的一条信息是:这次导航期间到底有没有
                // 收到过 /cmd_vel —— 一次都没有 = Nav2 的 controller 根本没跑起来(定位/TF 的问题),
                // 而不是底盘或 PLC 的问题,不用去查 plc.enabled、点位表那一摊。
                long sinceCmd = lastAnyCmdVelMs == 0 ? -1 : System.currentTimeMillis() - lastAnyCmdVelMs;
                if (sinceCmd < 0) {
                    log.warn("❌ 导航失败! 状态: {} | 本次全程未收到任何 /cmd_vel —— "
                            + "Nav2 的 controller 一次都没输出速度，问题在定位/TF(map→camera_init)，"
                            + "不在底盘。建图中导航必然如此: 此时 AMCL 没在跑，没人发布 map→camera_init", statusText);
                } else {
                    log.warn("❌ 导航失败! 状态: {} | 最后一次 /cmd_vel 在 {}ms 前(linear={}, angular={})",
                            statusText, sinceCmd,
                            String.format("%.3f", cachedLinearVel), String.format("%.3f", cachedAngularVel));
                }
            }

        } catch (Exception e) {
            log.warn("处理导航状态失败: {}", e.getMessage());
        }
    }

    /** 取 action_msgs/GoalStatus 的 goal_info.stamp，转成纳秒；取不到返回 0 */
    private static long extractGoalStampNs(JsonObject statusItem) {
        JsonObject info = statusItem.getAsJsonObject("goal_info");
        if (info == null) return 0L;
        JsonObject stamp = info.getAsJsonObject("stamp");
        if (stamp == null) return 0L;
        return stamp.get("sec").getAsLong() * 1_000_000_000L + stamp.get("nanosec").getAsLong();
    }

    /**
     * 取目标 UUID 的字符串形式。
     * rosbridge 对 uint8[16] 可能编码成 base64 字符串，也可能是数字数组，两种都要认。
     */
    private static String extractGoalUuid(JsonObject statusItem) {
        JsonObject info = statusItem.getAsJsonObject("goal_info");
        if (info == null) return "";
        JsonObject goalId = info.getAsJsonObject("goal_id");
        if (goalId == null || !goalId.has("uuid")) return "";
        JsonElement uuid = goalId.get("uuid");
        if (uuid.isJsonArray()) {
            JsonArray arr = uuid.getAsJsonArray();
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < arr.size(); i++) {
                sb.append(String.format("%02x", arr.get(i).getAsInt() & 0xFF));
            }
            return sb.toString();
        }
        return uuid.getAsString();
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
            lastAnyCmdVelMs = now;
            if ("/cmd_vel_smoothed".equals(topic)) {
                lastSmoothedCmdVelMs = now;
                hardwareService.sendVelocity(linearX, angularZ, true);
            } else if (now - lastSmoothedCmdVelMs > 500) {
                hardwareService.sendVelocity(linearX, angularZ, true);
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
