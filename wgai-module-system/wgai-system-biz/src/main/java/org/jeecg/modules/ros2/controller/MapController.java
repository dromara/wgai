package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.gson.JsonObject;
import org.jeecg.modules.ros2.service.ObstacleGuardService;
import org.jeecg.modules.ros2.service.ROS2BridgeService;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地图管理控制器 - 新架构(systemd 托管 ROS 栈,或直接 ros2 launch 启动)
 *
 * 架构说明:
 *   ROS 栈(rosbridge + 雷达 + fast_lio + pc2scan + Nav2)由 launch 脚本启动,
 *   可由 systemd 托管(robot-nav.service)或直接 ros2 launch 运行。
 *   Java 只负责:
 *     1. 读写地图文件(yaml/pgm)
 *     2. 通过 /map_server/load_map service 热切换地图(无需重启 Nav2)
 *     3. 维护 last_used.yaml 软链接(launch 脚本启动时自动恢复上次地图)
 *     4. 自动持久化 AMCL 位姿
 *     5. 维护 nav2_params_fastlio.yaml(供 launch 脚本下次启动读取)
 *     6. 健康检查
 *
 * 接口:
 *   POST   /api/map/load          切换到指定地图(service 调用,瞬时)
 *   GET    /api/map/nav2-status   查询 Nav2 运行状态
 *   GET    /api/map/nav2-health   查询 TF/scan/AMCL 健康状态
 *   GET    /api/map/list          地图列表
 *   GET    /api/map/image/{name}  pgm→png 图像
 *   GET    /api/map/meta/{name}   分辨率/原点/尺寸
 *   DELETE /api/map/{name}        删除地图
 *
 * 位姿自动持久化机制:
 *   - 加载地图后每 5 秒自动读取 AMCL 当前位姿并保存到文件
 *   - 下次切换同一张地图或系统重启时,自动从文件恢复上次位姿
 *   - 位姿文件路径: {MAP_DIR}/{mapName}_last_pose.json
 *
 * TF 链(由 launch 脚本启动的 fast_lio + AMCL 提供):
 *   fast_lio:  camera_init → body
 *   AMCL:      map → camera_init
 *   完整链:    map → camera_init → body  ✓
 */
@Slf4j
@RestController
@RequestMapping("/api/map")
@Api(tags = "地图管理")
public class MapController {

    // ===================== 配置常量 =====================

    private static final String MAP_DIR     = "/home/ros/maps/";
    private static final String SETUP_BASH  = "/home/lio_ws/install/setup.bash";
    private static final String ROS_BASH    = "/opt/ros/humble/setup.bash";

    /** systemd 服务名(与 /etc/systemd/system/robot-nav.service 一致) */
    private static final String SYSTEMD_SERVICE = "robot-nav";

    /** nav2 params 文件路径(与 robot_full.launch.py 中的路径一致) */
    private static final String NAV2_PARAMS_PATH = "/home/ros/nav2_params_fastlio.yaml";

    /** 默认不在 Java 启动阶段检查/生成 Nav2 参数文件，按需由地图功能生成。 */
    @Value("${ros.map.startup-init.enabled:false}")
    private boolean mapStartupInitEnabled;

    /**
     * 自定义行为树目录 —— 车辆物理上无法原地旋转,behavior_server 不注册 spin。
     * Nav2 官方默认 BT(navigate_to/through_poses_w_replanning_and_recovery.xml)的
     * 恢复子树里硬编码了 <Spin/> 节点,bt_navigator 激活时会等 spin action server,
     * 等不到就直接 "Failed to bring up all requested nodes"。
     * 这里生成两份去掉 Spin 节点的官方默认树副本,通过 default_nav_to_pose_bt_xml /
     * default_nav_through_poses_bt_xml 覆盖掉,其余逻辑(重规划、清代价地图、倒车、等待)不变。
     */
    private static final String BT_XML_DIR = "/home/ros/behavior_trees/";
    private static final String BT_XML_NAV_TO_POSE = BT_XML_DIR + "navigate_to_pose_no_spin.xml";
    private static final String BT_XML_NAV_THROUGH_POSES = BT_XML_DIR + "navigate_through_poses_no_spin.xml";

    /** 位姿持久化文件后缀(每张地图独立) */
    private static final String POSE_FILE_SUFFIX = "_last_pose.json";

    /** launch 脚本启动时优先读取的软链接文件名 */
    private static final String LAST_USED_LINK = "last_used.yaml";

    /** fast_lio 里程计父帧 / 机器人帧 / 话题 */
    private static final String ODOM_FRAME   = "camera_init";
    /** fast_lio / AMCL 用的车体帧(原点 = 雷达) */
    private static final String BASE_FRAME   = "body";
    /**
     * Nav2(bt/controller/planner/costmap/behavior)用的车体帧，原点 = 转向中心。
     * Smac Hybrid-A* 和 RPP 都假设"帧原点沿车头方向做圆弧运动"，这只对转弯圆心正横方向那个点成立；
     * 拿车头雷达当原点，规划器会以为车绕车头转，车尾 5m 的甩尾全算错。
     * body→base_link 静态 TF 由 robot_full.launch.py 读本 yaml 的 agv_base_link 段发布。
     */
    private static final String NAV_BASE_FRAME = "base_link";
    private static final String ODOM_TOPIC   = "/Odometry";
    private static final String CLOUD_TOPIC  = "/cloud_registered";

    /**
     * 代价地图的障碍数据源 = robot_full.launch.py 里第二个 pointcloud_to_laserscan(/scan_obstacles)，
     * 高度层离地 0.15~2.3m，能看见矮料堆。
     * ⚠ AMCL 仍用 /scan(离地 0.6~2.3m)：定位那一层必须落在地图保存的高度范围内，
     *   否则拿地图里根本没有的低处点去匹配，会收敛到错误位姿(2026-09-16 现场朝向被转了 90°)。
     */

    /** 点云投影高度范围 */
    private static final double SCAN_MIN_H   = 0.1;
    private static final double SCAN_MAX_H   = 1.5;

    /**
     * 车体 footprint(扒粮机 AGV,长5.5m×宽2.1m,长宽比大,不能用圆形 robot_radius 近似)。
     * 坐标以雷达安装点(= body 坐标系原点,fast_lio 惯例)为原点,x 轴指向前进方向(扒粮机构一端):
     *   前端(扒粮机构): +0.5m   后端(履带底盘尾部): -5.0m
     * 横向雷达**不在车身中线上**(现场实测): 距左侧边缘 +1.2m,距右侧边缘 -0.9m。
     * 因此这是一个前后、左右都不对称的矩形,四个方向的数值都不能互相推导。
     * 该数值必须与机器人端 livox_self_filter.py 的过滤箱体保持一致,否则
     * "SLAM 输入过滤掉的车体范围"和"costmap 认为的车体范围"对不上。
     *
     * 车体自身点云(输送管/底盘)会落在这个多边形内,由 obstacle_layer 的
     * footprint_clearing_enabled 自动清除为可通行区域,不再需要旧版 SELF_FILTER_RADIUS
     * 那种"以雷达为圆心、不分方向"的全向 min-range 过滤(那是给宇树L1腿部360°自遮挡设计的,
     * 对前后极不对称的这台车不适用)。
     */
    private static final double FP_FRONT = 0.5, FP_BACK = -5.0, FP_LEFT = 1.2, FP_RIGHT = -0.9;

    /** 以转向中心(base_link)为原点的 footprint 字符串，(cx,cy) 是转向中心在 body 系的坐标 */
    private static String navFootprint(double cx, double cy) {
        double f = FP_FRONT - cx, b = FP_BACK - cx, l = FP_LEFT - cy, r = FP_RIGHT - cy;
        return String.format("[[%.3f, %.3f], [%.3f, %.3f], [%.3f, %.3f], [%.3f, %.3f]]", f, r, f, l, b, l, b, r);
    }

    /** 舵角上限(°)，与 RobotHardwareService.STRAIGHT_MAX_ANGLE_DEG 一致 */
    private static final double MAX_STEER_DEG = 45.0;

    /**
     * Smac 最小转弯半径 = 等效轴距/tan(45°) × 本系数。
     * 按车的物理极限去规划，RPP 跟踪时就没有任何舵角余量纠偏，弯道一偏就压线；留 20% 给控制器。
     */
    @Value("${ros.nav2.turn-radius-factor:1.2}")
    private double turnRadiusFactor;

    /** 转向几何(等效轴距、转向中心)的唯一来源，热改参数也落在它身上 */
    @Autowired
    private ObstacleGuardService obstacleGuardService;

    /** 传感器近距离噪声过滤(m)。自身遮挡已交给 footprint 自动清除,这里只过滤贴着镜头的噪点 */
    private static final double SENSOR_MIN_RANGE = 0.05;

    /** 膨胀半径(m),footprint 之外再留的安全缓冲 */
    private static final double INFLATE = 0.30;

    /**
     * 全局代价地图的 footprint 外扩(m)，只给全局规划器用，局部代价地图不加。
     *
     * SmacPlannerHybrid 判碰撞只看 footprint **轮廓线**有没有压到 LETHAL(254) 格子，
     * 膨胀层给的 253 及以下代价一律不算碰撞 —— INFLATE 只让它"尽量离远点"(cost_penalty)，
     * 挡不住它把车身贴着墙规划。要硬性留出车身余量只能把 footprint 本身撑大。
     * ⚠ 等效车宽 = 2.1 + 2×padding。0.30(2.7m)时现场持续规划失败(2026-09-14，和参考点/转弯半径问题叠加，未单独验证)，先降到 0.15。
     * ⚠ 不要加到 local_costmap：那边 footprint_clearing 会把车身外 padding 范围内的真障碍一起清掉。
     */
    @Value("${ros.nav2.footprint-padding:0.15}")
    private double globalFootprintPadding;

    /** Smac 倒车代价倍数，1.0 = 倒车和前进一样 */
    @Value("${ros.nav2.reverse-penalty:1.5}")
    private double reversePenalty;

    /** Smac 左右换向代价，0 = 不罚 */
    @Value("${ros.nav2.change-penalty:1.0}")
    private double changePenalty;

    /** Smac 走弯路代价倍数(≥1)，越大越偏好直线 */
    @Value("${ros.nav2.non-straight-penalty:1.3}")
    private double nonStraightPenalty;

    /** Nav2 到达判定：位置容差(m) */
    @Value("${ros.nav2.xy-goal-tolerance:0.30}")
    private double xyGoalTolerance;

    /** Nav2 到达判定：朝向容差(rad)，> π 即不检查朝向 */
    @Value("${ros.nav2.yaw-goal-tolerance:3.15}")
    private double yawGoalTolerance;

    /** ManeuverService 发目标前的车身预检查要用同一个外扩，口径不一致就会出现"预检查说放得下、Smac 说不行" */
    public double getGlobalFootprintPadding() { return globalFootprintPadding; }

    // ===================== 运行状态 =====================

    private volatile String  loadedMapName  = null;
    private volatile Map<String, Object> lastHealthCheck = null;

    /** 位姿自动保存定时任务 */
    private volatile ScheduledExecutorService poseAutoSaveScheduler = null;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** rosbridge 长连接（用于通过 WebSocket 调 /map_server/load_map，替代 ros2 CLI） */
    @Autowired
    private ROS2BridgeService ros2BridgeService;

    /** 切图防重入：前端连点/重试时只放一个进去，杜绝请求堆积 */
    private final java.util.concurrent.atomic.AtomicBoolean mapLoading =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ===================== 启动初始化 =====================

    /**
     * Java 启动时:
     *   1. 如果 nav2_params_fastlio.yaml 不存在,写一份默认配置
     *      (这样 launch 脚本启动 Nav2 时不会因为缺文件失败)
     *   2. 不主动启动任何 ROS 进程,launch 脚本/systemd 自管
     */
    @PostConstruct
    public void init() {
        if (!mapStartupInitEnabled) {
            log.info("[地图初始化] 已通过 ros.map.startup-init.enabled=false 关闭，跳过 Nav2 参数文件检查");
            return;
        }
        try {
            File f = new File(NAV2_PARAMS_PATH);
            if (!f.exists()) {
                log.info("[初始化] {} 不存在,写入默认配置...", NAV2_PARAMS_PATH);
                writeNav2Params(NAV2_PARAMS_PATH, 0.0, 0.0, 0.0, "");
                log.info("[初始化] ✅ 默认 nav2_params 已生成");
            } else {
                log.info("[初始化] {} 已存在,跳过", NAV2_PARAMS_PATH);
            }
        } catch (Exception e) {
            log.warn("[初始化] 写入默认 nav2_params 失败(可手动创建): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        stopPoseAutoSave();
        executor.shutdown();
    }

    // ===================== 切换地图(核心) =====================

    @PostMapping("/load")
    @ApiOperation("切换到指定地图(通过 Nav2 service 热切换,无需重启)")
    public Result<Map<String, Object>> loadMap(@RequestBody Map<String, Object> params) {
        long t0 = System.currentTimeMillis();
        log.info("====== [load] 进入 loadMap, params={} ======", params);
        if (!mapLoading.compareAndSet(false, true)) {
            log.warn("[load] 已有地图切换在进行中，拒绝并发请求");
            return Result.error("已有地图切换正在进行，请稍候再试");
        }
        try {
            String mapName = (String) params.get("mapName");
            if (mapName == null || mapName.trim().isEmpty()) {
                log.warn("[load] 地图名称为空,直接返回");
                return Result.error("地图名称不能为空");
            }
            mapName = mapName.trim();

            String yamlPath = MAP_DIR + mapName + ".yaml";
            log.info("[load] step0 校验文件: {}", yamlPath);
            if (!new File(yamlPath).exists()) {
                log.warn("[load] 文件不存在: {}", yamlPath);
                return Result.error("地图文件不存在: " + yamlPath);
            }
            log.info("[load] step0 文件存在 ✓ (+{}ms)", System.currentTimeMillis() - t0);

            // 1. 检查 Nav2 是否可达(兼容 systemd 和直接 ros2 launch 两种启动方式)
            log.info("[load] step1 调用 isNav2Active() ...");
            long ts = System.currentTimeMillis();
            boolean nav2Active = isNav2Active();
            log.info("[load] step1 isNav2Active()={} 耗时={}ms (总+{}ms)",
                    nav2Active, System.currentTimeMillis() - ts, System.currentTimeMillis() - t0);
            if (!nav2Active) {
                return Result.error(
                        "Nav2 服务未运行。请确认已启动:\n"
                                + "  方式 A: ros2 launch /home/ros/robot_full.launch.py\n"
                                + "  方式 B: sudo systemctl start " + SYSTEMD_SERVICE
                );
            }

            // 2. 读上次保存的位姿(用于 params 文件 & 提示)
            log.info("[load] step2 读取历史位姿 ...");
            double[] saved = loadSavedPose(mapName);
            boolean hasSavedPose =
                    (saved[0] != 0.0 || saved[1] != 0.0 || saved[2] != 0.0);
            log.info("[load] step2 完成 hasSavedPose={} (总+{}ms)",
                    hasSavedPose, System.currentTimeMillis() - t0);

            // 3. 关键:通过 rosbridge 长连接调 /map_server/load_map 热切换(无需重启 Nav2)
            //    不再 spawn ros2 CLI 子进程,从根上消除孤儿进程 / SHM 残留 / 12s 超时卡死。
            log.info("[load] step3 通过 rosbridge 调 /map_server/load_map ...");
            ts = System.currentTimeMillis();
            boolean ok = callMapLoadServiceViaBridge(yamlPath);
            log.info("[load] step3 返回 ok={} 耗时={}ms (总+{}ms)",
                    ok, System.currentTimeMillis() - ts, System.currentTimeMillis() - t0);
            if (!ok) {
                log.error("[load] step3 地图切换失败(rosbridge)");
                return Result.error("地图切换失败:/map_server/load_map 调用未成功(rosbridge)");
            }
            log.info("[load] step3 service 切换成功 ✓");

            // 4. ✅ 更新 last_used.yaml 软链接 → launch 脚本重启时自动恢复此图
            log.info("[load] step4 更新 last_used 软链接 ...");
            updateLastUsedSymlink(mapName);

            // 5. 重新生成 params 文件(供下次启动 Nav2 时使用最新配置)
            log.info("[load] step5 写 nav2_params ...");
            writeNav2Params(NAV2_PARAMS_PATH, saved[0], saved[1], saved[2], yamlPath);

            // 6. 启动位姿自动保存
            log.info("[load] step6 启动位姿自动保存 ...");
            startPoseAutoSave(mapName);

            // 7. 异步触发健康检查(供前端轮询查询)
            log.info("[load] step7 异步触发健康检查 ...");
            scheduleHealthCheck(mapName);

            loadedMapName = mapName;
            log.info("[load] step8 组装响应并返回 (总+{}ms)", System.currentTimeMillis() - t0);

            // 8. 提示用户:如果机器人位置不准,需调用全局重定位
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mapName",       mapName);
            res.put("yamlPath",      yamlPath);
            res.put("imageUrl",      "/api/map/image/" + mapName);
            res.put("hasSavedPose",  hasSavedPose);
            res.put("savedPose",     buildPoseInfo(saved[0], saved[1], saved[2], hasSavedPose));
            res.put("nextStep", hasSavedPose
                    ? "地图已切换,使用上次保存的位姿。如机器人位置偏差,请调用 /api/navigation/global-localization"
                    : "地图已切换,首次使用默认(0,0,0)。请调用 /api/navigation/global-localization 触发全局重定位,然后遥控机器人慢速转一圈"
            );

            log.info("✅ 地图切换成功: {} (位姿:{})", mapName,
                    hasSavedPose ? "从文件恢复" : "默认(0,0,0)");

            return Result.OK(res);

        } catch (Exception e) {
            log.error("[load] 切换地图失败 (总+{}ms)", System.currentTimeMillis() - t0, e);
            return Result.error("切换失败: " + e.getMessage());
        } finally {
            mapLoading.set(false);
        }
    }

    /**
     * 调用 /map_server/load_map service 热切换地图
     * 等价于命令: ros2 service call /map_server/load_map nav2_msgs/srv/LoadMap
     *           '{map_url: "/path/to/map.yaml"}'
     */
    /**
     * 通过 rosbridge 调 /map_server/load_map 热切换地图。
     * 等价于: ros2 service call /map_server/load_map nav2_msgs/srv/LoadMap '{map_url: "..."}'
     * 但走的是已建立的 WebSocket 长连接,毫秒级返回,零子进程。
     *
     * LoadMap.Response.result 取值:
     *   0=SUCCESS  1=MAP_DOES_NOT_EXIST  2=INVALID_MAP_DATA
     *   3=INVALID_MAP_METADATA  255=UNDEFINED_FAILURE
     */
    private boolean callMapLoadServiceViaBridge(String yamlPath) {
        if (!ros2BridgeService.isConnected()) {
            log.error("[load] rosbridge 未连接,无法切图(请确认 ROS2BridgeService 已连上 9090)");
            return false;
        }
        JsonObject args = new JsonObject();
        args.addProperty("map_url", yamlPath);

        // load_map 的响应会把整张 OccupancyGrid 带回来(rosbridge 转成 JSON 数组，每格约 3 字节文本)，
        // 所以耗时跟地图格子数成正比。以前写死 15s：小图够，扫大了之后必然超时 → 切图失败 →
        // Nav2 还是旧图、前端显示新图、位姿文件也不会生成(2026-09-14 现场"大地图打不开")。
        long cells = 0;
        try {
            int[] wh = readPgmSize(new File(yamlPath.replaceAll("\\.yaml$", ".pgm")));
            cells = (long) wh[0] * wh[1];
        } catch (Exception ignored) { }
        long timeoutMs = Math.min(180_000L, 15_000L + cells / 1_000_000L * 15_000L);
        log.info("[load] 地图 {} 万格, load_map 等待上限 {}s", cells / 10_000, timeoutMs / 1000);
        JsonObject values = ros2BridgeService.callService(
                "/map_server/load_map", "nav2_msgs/srv/LoadMap", args, timeoutMs);
        if (values == null) {
            log.error("[load] load_map {}s 超时/无响应(地图 {} 万格)。若 Java 日志同时出现 'ROS2 Bridge 断开'，"
                    + "是整张地图的响应把 WebSocket 撑断了", timeoutMs / 1000, cells / 10_000);
            return false;
        }
        int result = values.has("result") ? values.get("result").getAsInt() : -1;
        log.info("[load] load_map result={} ({})", result, loadMapResultText(result));
        return result == 0;   // RESULT_SUCCESS
    }

    private String loadMapResultText(int code) {
        switch (code) {
            case 0:   return "SUCCESS";
            case 1:   return "MAP_DOES_NOT_EXIST";    // yaml/pgm 路径不对
            case 2:   return "INVALID_MAP_DATA";      // pgm 损坏
            case 3:   return "INVALID_MAP_METADATA";  // yaml 字段问题
            case 255: return "UNDEFINED_FAILURE";
            default:  return "UNKNOWN(" + code + ")";
        }
    }

    /**
     * 更新 /home/ros/maps/last_used.yaml 软链接,指向当前加载的地图。
     * launch 脚本重启时优先读取这个软链接,实现"上次用什么图,启动后自动恢复"。
     *
     * 用相对路径建链接,这样整个 maps 目录搬到别处也不会失效。
     * 部分文件系统(如某些 NTFS/FAT)不支持软链接,会回退到文件拷贝模式。
     */
    /** public：MappingController 保存完新地图后也要更新它，好让重启的 robot_full 直接加载新图 */
    public void updateLastUsedSymlink(String mapName) {
        Path lastUsed = Paths.get(MAP_DIR, LAST_USED_LINK);
        Path target   = Paths.get(mapName + ".yaml");      // 相对路径

        try {
            Files.deleteIfExists(lastUsed);
            Files.createSymbolicLink(lastUsed, target);
            log.info("✅ last_used 软链接已更新 → {}.yaml", mapName);
        } catch (UnsupportedOperationException e) {
            // 文件系统不支持软链接,回退到拷贝
            try {
                Path srcAbs = Paths.get(MAP_DIR, mapName + ".yaml");
                Files.copy(srcAbs, lastUsed, StandardCopyOption.REPLACE_EXISTING);
                log.info("✅ last_used.yaml 已更新(拷贝模式) → {}.yaml", mapName);
            } catch (Exception ex) {
                log.warn("更新 last_used 失败(不影响本次切图): {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("更新 last_used 软链接失败(不影响本次切图): {}", e.getMessage());
        }
    }

    private Map<String, Object> buildPoseInfo(double x, double y, double theta, boolean fromFile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x",      x);
        m.put("y",      y);
        m.put("theta",  theta);
        m.put("source", fromFile ? "pose_file" : "default(0,0,0)");
        return m;
    }

    // ===================== Nav2 服务状态 =====================

    @GetMapping("/nav2-status")
    @ApiOperation("查询 Nav2 运行状态(兼容 launch 脚本和 systemd 两种启动方式)")
    public Result<Map<String, Object>> getNav2Status() {
        Map<String, Object> s = new LinkedHashMap<>();
        boolean active = isNav2Active();
        s.put("nav2Running",  active);
        s.put("loadedMap",    loadedMapName);
        s.put("poseSaving",   poseAutoSaveScheduler != null
                && !poseAutoSaveScheduler.isShutdown());
        s.put("startupHint",  active
                ? null
                : "Nav2 未运行,请使用以下方式之一启动:\n"
                + "  ros2 launch /home/ros/robot_full.launch.py\n"
                + "  sudo systemctl start " + SYSTEMD_SERVICE);
        return Result.OK(s);
    }

    /**
     * 检查 Nav2 是否在运行,兼容两种启动方式:
     *   方式 A: systemd 托管 → systemctl is-active robot-nav
     *   方式 B: 直接 ros2 launch → rosbridge 已连接 且 近期收到过 /amcl_pose 数据
     *
     * 方式 B 原来用 ros2 service list shell 命令,source setup.bash 本身就要 3-5s,
     * 经常把整个 4s 超时耗光导致误判。改为查 rosbridge 连接状态+AMCL心跳,毫秒级返回。
     */
    private boolean isNav2Active() {
        // 方式 A: systemd
        try {
            String state = runCommand(
                    "systemctl is-active " + SYSTEMD_SERVICE + " 2>&1", 3
            ).trim();
            if ("active".equals(state)) return true;
        } catch (Exception ignored) {}

        // 方式 B: rosbridge 已连接 且 AMCL 心跳正常(60s 内有 /amcl_pose 数据)
        // 不再 fork ros2 CLI 子进程,毫秒级返回,彻底消除 source 超时问题。
        if (ros2BridgeService.isConnected() && ros2BridgeService.isAmclAlive(60_000)) {
            return true;
        }

        // 方式 B 降级: rosbridge 已连 但 AMCL 心跳超时(可能刚启动还没收到第一条 pose)
        // 此时只要 rosbridge 连接正常就认为 Nav2 在跑(load_map 调用会进一步验证)
        if (ros2BridgeService.isConnected()) {
            // ⚠ 这条不代表定位坏了：AMCL 只在车移动超过 update_min_d/a 才发 /amcl_pose，车停着超过 60s 必然"超时"
            log.info("[isNav2Active] rosbridge 已连接, 60s 内没有新的 /amcl_pose(车静止时正常), 按 Nav2 活跃处理");
            return true;
        }

        return false;
    }


    // ===================== Nav2 健康自检 =====================

    @GetMapping("/nav2-health")
    @ApiOperation("查询 Nav2 健康状态(TF/scan/AMCL)")
    public Result<Map<String, Object>> getNav2Health() {
        if (lastHealthCheck == null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("status",  "pending");
            pending.put("message", "尚未执行检测,请先调用 /api/map/load 后等待几秒");
            return Result.OK(pending);
        }
        return Result.OK(lastHealthCheck);
    }

    private void scheduleHealthCheck(String mapName) {
        final String mName = mapName;
        executor.submit(() -> {
            try {
                // 不像旧版要等 20s 启动,新架构 Nav2 已经在跑了,5s 给 service 切换稳定
                Thread.sleep(5000);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("mapName",   mName);
                result.put("checkTime", new java.util.Date().toString());

                // ① fast_lio TF
                boolean fastLioTfOk = checkTfAvailable(ODOM_FRAME, BASE_FRAME, 5);
                result.put("fastLioTfAvailable", fastLioTfOk);
                if (!fastLioTfOk) {
                    result.put("fastLioWarn", "⚠ " + ODOM_FRAME + "→" + BASE_FRAME
                            + " TF 不可用,fast_lio 可能未运行。检查启动日志:"
                            + " ros2 launch 终端输出 或 journalctl -u " + SYSTEMD_SERVICE);
                }

                // ①' body→base_link 静态 TF。缺了 Nav2 所有 costmap 都拿不到车位姿，一条路径都不会出
                boolean baseLinkTfOk = checkTfAvailable(BASE_FRAME, NAV_BASE_FRAME, 5);
                result.put("baseLinkTfAvailable", baseLinkTfOk);
                if (!baseLinkTfOk) {
                    result.put("baseLinkWarn", "⚠ " + BASE_FRAME + "→" + NAV_BASE_FRAME
                            + " TF 不可用: robot_full.launch.py 还没加读取 agv_base_link 段发布静态 TF 的那段");
                }

                // ② /scan
                boolean scanOk = checkTopicActive("/scan", 5);
                result.put("scanActive", scanOk);
                result.put("scanTopic",  CLOUD_TOPIC + " → /scan");

                // ③ AMCL 节点
                boolean amclOk = checkNodeExists("amcl", 5);
                result.put("amclRunning", amclOk);

                // ④ map → odom TF(关键)
                boolean tfOk = checkTfAvailable("map", ODOM_FRAME, 8);
                result.put("mapTfAvailable", tfOk);
                if (!tfOk) {
                    result.put("tfWarn", "⚠ map→" + ODOM_FRAME
                            + " TF 不可用,可调用 POST /api/navigation/global-localization "
                            + "触发全局重定位");
                }

                // ⑤ 位姿文件
                File poseFile = new File(MAP_DIR + mName + POSE_FILE_SUFFIX);
                result.put("poseFileSaved", poseFile.exists());

                boolean allOk = fastLioTfOk && baseLinkTfOk && scanOk && amclOk && tfOk;
                result.put("status", allOk ? "healthy" : (amclOk ? "degraded" : "error"));
                result.put("message", allOk
                        ? "✅ Nav2 完全就绪,可以设置导航目标"
                        : (!fastLioTfOk ? "❌ fast_lio 未运行"
                        : (!tfOk        ? "❌ map TF 未就绪,需全局重定位"
                        : "⚠ 部分服务异常但导航 TF 已就绪")));

                lastHealthCheck = result;
                log.info("[健康检查] fastLioTf={}, scan={}, amcl={}, mapTf={}",
                        fastLioTfOk, scanOk, amclOk, tfOk);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("[健康检查] 异常", e);
            }
        });
    }

    // ===================== 位姿持久化 =====================

    private double[] loadSavedPose(String mapName) {
        File f = new File(MAP_DIR + mapName + POSE_FILE_SUFFIX);
        if (!f.exists()) {
            log.info("[位姿恢复] 无历史记录,使用默认 (0, 0, 0)");
            return new double[]{0.0, 0.0, 0.0};
        }
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            double x     = parseJsonDouble(json, "x");
            double y     = parseJsonDouble(json, "y");
            double theta = parseJsonDouble(json, "theta");
            // ⚠ 必须校验在地图范围内。AMCL 发散过一次，自动保存就会把 (566,1014) 这种离谱位姿
            //   存下来，切地图时写进 nav2 yaml 的 initial_pose → 重启 ROS 后 AMCL 直接出生在图外，
            //   planner 报 "Robot is out of bounds of the costmap!"，一条路径都规划不出来(2026-09-14 现场)。
            double[] b = mapBounds(mapName);
            if (b != null && !insideBounds(b, x, y)) {
                log.warn("[位姿恢复] 历史位姿 ({}, {}) 不在地图范围 ({},{})~({},{}) 内, 视为 AMCL 发散时存下的脏数据, "
                        + "改用 (0,0,0)，请在前端手动设置初始位姿", x, y, b[0], b[1], b[2], b[3]);
                return new double[]{0.0, 0.0, 0.0};
            }
            log.info("[位姿恢复] x={}, y={}, theta={}", x, y, theta);
            return new double[]{x, y, theta};
        } catch (Exception e) {
            log.warn("[位姿恢复] 读取失败,使用默认 (0,0,0): {}", e.getMessage());
            return new double[]{0.0, 0.0, 0.0};
        }
    }

    /** 地图世界坐标范围 {minX, minY, maxX, maxY}；yaml/pgm 读不出来返回 null(不做校验) */
    private double[] mapBounds(String mapName) {
        try {
            Map<String, Object> m = getMapMeta(mapName).getResult();
            if (m == null) return null;
            int w = ((Number) m.get("width")).intValue();
            int h = ((Number) m.get("height")).intValue();
            if (w <= 0 || h <= 0) return null;
            double res = ((Number) m.get("resolution")).doubleValue();
            double ox  = ((Number) m.get("originX")).doubleValue();
            double oy  = ((Number) m.get("originY")).doubleValue();
            return new double[]{ox, oy, ox + w * res, oy + h * res};
        } catch (Exception e) {
            log.warn("[位姿] 读取地图 {} 范围失败, 跳过位姿范围校验: {}", mapName, e.getMessage());
            return null;
        }
    }

    private static boolean insideBounds(double[] b, double x, double y) {
        return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3];
    }

    private void savePose(String mapName, double x, double y, double theta) {
        try {
            String json = String.format(
                    "{\"x\":%.4f,\"y\":%.4f,\"theta\":%.4f,\"savedAt\":\"%s\"}",
                    x, y, theta, new java.util.Date()
            );
            Files.write(
                    new File(MAP_DIR + mapName + POSE_FILE_SUFFIX).toPath(),
                    json.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.warn("[位姿保存] 写入失败: {}", e.getMessage());
        }
    }

    private void startPoseAutoSave(String mapName) {
        if (poseAutoSaveScheduler != null && !poseAutoSaveScheduler.isShutdown()) {
            poseAutoSaveScheduler.shutdownNow();
        }
        poseAutoSaveScheduler = Executors.newSingleThreadScheduledExecutor();
        final String finalMapName = mapName;
        final double[] bounds = mapBounds(mapName);      // 地图不会在保存期间变，算一次就够
        final boolean[] outWarned = {false};             // 只在"进入图外"那一刻打一条，别每 5s 刷屏
        // ⚠ 这里绝对不能 fork `ros2 topic echo /amcl_pose`：
        //   每 5s 起一个 bash + source 两个 setup.bash + 拉起一个完整的 rclpy 节点做 DDS 发现，
        //   光发现阶段就常常超过 timeout，实测每次固定 4.5s 超时、输出 0 字节 —— 一次都没存成过，
        //   却持续占着 CPU 和 DDS 发现流量，反过来加剧点云/TF 的延迟尖峰。
        //   /amcl_pose 已经由 rosbridge 常态订阅并缓存在 ROS2BridgeService，直接读即可。
        poseAutoSaveScheduler.scheduleAtFixedRate(() -> {
            try {
                double[] p = ros2BridgeService.getLastAmclPose();
                if (p == null) return;
                // 图外位姿 = AMCL 发散，存了就会污染下次启动的 initial_pose，宁可保留上一次的好值
                if (bounds != null && !insideBounds(bounds, p[0], p[1])) {
                    if (!outWarned[0]) {
                        log.warn("[位姿自动保存] AMCL 位姿 ({}, {}) 在地图范围外, 暂停保存(保留上次有效位姿)。"
                                + "定位已发散，请在前端手动设置初始位姿", p[0], p[1]);
                        outWarned[0] = true;
                    }
                    return;
                }
                outWarned[0] = false;
                savePose(finalMapName, p[0], p[1], p[2]);
            } catch (Exception e) {
                log.warn("[位姿自动保存] 跳过: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        log.info("✅ 位姿自动保存已启动 → {}{}{}", MAP_DIR, mapName, POSE_FILE_SUFFIX);
    }

    private void stopPoseAutoSave() {
        if (poseAutoSaveScheduler != null && !poseAutoSaveScheduler.isShutdown()) {
            poseAutoSaveScheduler.shutdownNow();
            poseAutoSaveScheduler = null;
            log.info("⏹ 位姿自动保存已停止");
        }
    }

    // ===================== 生成 nav2_params.yaml =====================

    /**
     * 生成 nav2_params_fastlio.yaml
     *
     * 扒粮机 AGV 适配说明(前轮转向+后轮辅助转向,车长5.5m。底盘能原地旋转(VW1002=1)，但 Nav2 这边
     * 没接 —— RobotHardwareService#twistToPlcCommand 线速度接近0的纯旋转指令直接停车):
     *
     *   ① footprint(非圆形): 用 FP_* 精确描述车体轮廓(按 base_link 平移后写入),代替旧的
     *      圆形 robot_radius。车体自身反射点自动落在 footprint 内,由
     *      footprint_clearing_enabled 清除,不再需要按半径盲目过滤一整圈。
     *
     *   ② min/max_obstacle_height = -1.0 ~ 1.0
     *      数据源是 LaserScan：costmap 把扫描点投到 global_frame 时 z = 雷达在 camera_init 里的高度 ≈ 0
     *      (camera_init 原点就是雷达开机位置)，随 fast_lio 的 z 漂移上下几厘米。曾配 min 0.05 → 点时过时不过，
     *      障碍"时有时无"、清障也时灵时不灵(2026-09-15)。离地高度的筛选在 pointcloud_to_laserscan 那一步做
     *      (robot_full.launch.py: min_height -0.7 / max_height 1.3 = 离地 0.3m ~ 车高 2.3m)，这里不再切。
     *
     *   ③ inflation_radius: 见 {@link #INFLATE}。footprint 已经精确表达车身,
     *      这里只是额外安全缓冲,不需要像圆形近似那样靠减小它来防止走廊被堵死。
     *
     *   ④ use_rotate_to_heading: false,且 behavior_server 不注册 spin 恢复行为 ——
     *      纯旋转指令目前在 Java 侧直接停车;另外 RPP 不允许 use_rotate_to_heading 和 allow_reversing 同时开。
     *
     *   ⑤ local_costmap 从 3m×3m 放大到能完整容纳 5.5m 车身的窗口,否则局部规划器
     *      连自己的车头车尾都看不全。
     */
    /**
     * 生成两份去掉 Spin 节点的 Nav2 官方默认行为树(每次写 nav2 参数时都重写,幂等)。
     * 内容即官方 navigate_to/through_poses_w_replanning_and_recovery.xml,
     * 仅从 RecoveryActions 的 RoundRobin 里删掉 <Spin spin_dist="1.57"/> 一行。
     */
    private void ensureBtXmlFiles() throws IOException {
        File dir = new File(BT_XML_DIR);
        if (!dir.exists()) dir.mkdirs();

        String navToPose = "<root main_tree_to_execute=\"MainTree\">\n"
                + "  <BehaviorTree ID=\"MainTree\">\n"
                + "    <RecoveryNode number_of_retries=\"6\" name=\"NavigateRecovery\">\n"
                + "      <PipelineSequence name=\"NavigateWithReplanning\">\n"
                + "        <RateController hz=\"1.0\">\n"
                + "          <RecoveryNode number_of_retries=\"1\" name=\"ComputePathToPose\">\n"
                + "            <ComputePathToPose goal=\"{goal}\" path=\"{path}\" planner_id=\"GridBased\"/>\n"
                + "            <ReactiveFallback name=\"ComputePathToPoseRecoveryFallback\">\n"
                + "              <GoalUpdated/>\n"
                + "              <ClearEntireCostmap name=\"ClearGlobalCostmap-Context\" service_name=\"global_costmap/clear_entirely_global_costmap\"/>\n"
                + "            </ReactiveFallback>\n"
                + "          </RecoveryNode>\n"
                + "        </RateController>\n"
                + "        <RecoveryNode number_of_retries=\"1\" name=\"FollowPath\">\n"
                + "          <FollowPath path=\"{path}\" controller_id=\"FollowPath\"/>\n"
                + "          <ReactiveFallback name=\"FollowPathRecoveryFallback\">\n"
                + "            <GoalUpdated/>\n"
                + "            <ClearEntireCostmap name=\"ClearLocalCostmap-Context\" service_name=\"local_costmap/clear_entirely_local_costmap\"/>\n"
                + "          </ReactiveFallback>\n"
                + "        </RecoveryNode>\n"
                + "      </PipelineSequence>\n"
                + "      <ReactiveFallback name=\"RecoveryFallback\">\n"
                + "        <GoalUpdated/>\n"
                + "        <RoundRobin name=\"RecoveryActions\">\n"
                + "          <Sequence name=\"ClearingActions\">\n"
                + "            <ClearEntireCostmap name=\"ClearLocalCostmap-Subtree\" service_name=\"local_costmap/clear_entirely_local_costmap\"/>\n"
                + "            <ClearEntireCostmap name=\"ClearGlobalCostmap-Subtree\" service_name=\"global_costmap/clear_entirely_global_costmap\"/>\n"
                + "          </Sequence>\n"
                // ⚠ 去掉了官方树里的 <BackUp/>：雷达在车头、车尾是盲区，规划失败就闭眼倒 0.3m 不可接受
                //   (现场日志一直刷 linearX=-0.05 "后退")。规划失败改由 ManeuverService 诊断后侧移重试
                + "          <Wait wait_duration=\"5\"/>\n"
                + "        </RoundRobin>\n"
                + "      </ReactiveFallback>\n"
                + "    </RecoveryNode>\n"
                + "  </BehaviorTree>\n"
                + "</root>\n";

        String navThroughPoses = "<root main_tree_to_execute=\"MainTree\">\n"
                + "  <BehaviorTree ID=\"MainTree\">\n"
                + "    <RecoveryNode number_of_retries=\"6\" name=\"NavigateRecovery\">\n"
                + "      <PipelineSequence name=\"NavigateWithReplanning\">\n"
                + "        <RateController hz=\"1.0\">\n"
                + "          <RecoveryNode number_of_retries=\"1\" name=\"ComputePathThroughPoses\">\n"
                + "            <ComputePathThroughPoses goals=\"{goals}\" path=\"{path}\" planner_id=\"GridBased\"/>\n"
                + "            <ReactiveFallback name=\"ComputePathThroughPosesRecoveryFallback\">\n"
                + "              <GoalUpdated/>\n"
                + "              <ClearEntireCostmap name=\"ClearGlobalCostmap-Context\" service_name=\"global_costmap/clear_entirely_global_costmap\"/>\n"
                + "            </ReactiveFallback>\n"
                + "          </RecoveryNode>\n"
                + "        </RateController>\n"
                + "        <RecoveryNode number_of_retries=\"1\" name=\"FollowPath\">\n"
                + "          <FollowPath path=\"{path}\" controller_id=\"FollowPath\"/>\n"
                + "          <ReactiveFallback name=\"FollowPathRecoveryFallback\">\n"
                + "            <GoalUpdated/>\n"
                + "            <ClearEntireCostmap name=\"ClearLocalCostmap-Context\" service_name=\"local_costmap/clear_entirely_local_costmap\"/>\n"
                + "          </ReactiveFallback>\n"
                + "        </RecoveryNode>\n"
                + "      </PipelineSequence>\n"
                + "      <ReactiveFallback name=\"RecoveryFallback\">\n"
                + "        <GoalUpdated/>\n"
                + "        <RoundRobin name=\"RecoveryActions\">\n"
                + "          <Sequence name=\"ClearingActions\">\n"
                + "            <ClearEntireCostmap name=\"ClearLocalCostmap-Subtree\" service_name=\"local_costmap/clear_entirely_local_costmap\"/>\n"
                + "            <ClearEntireCostmap name=\"ClearGlobalCostmap-Subtree\" service_name=\"global_costmap/clear_entirely_global_costmap\"/>\n"
                + "          </Sequence>\n"
                // ⚠ 去掉了官方树里的 <BackUp/>：雷达在车头、车尾是盲区，规划失败就闭眼倒 0.3m 不可接受
                //   (现场日志一直刷 linearX=-0.05 "后退")。规划失败改由 ManeuverService 诊断后侧移重试
                + "          <Wait wait_duration=\"5\"/>\n"
                + "        </RoundRobin>\n"
                + "      </ReactiveFallback>\n"
                + "    </RecoveryNode>\n"
                + "  </BehaviorTree>\n"
                + "</root>\n";

        Files.write(new File(BT_XML_NAV_TO_POSE).toPath(), navToPose.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(BT_XML_NAV_THROUGH_POSES).toPath(), navThroughPoses.getBytes(StandardCharsets.UTF_8));
    }

    private void writeNav2Params(String filePath, double initX, double initY,
                                 double initTheta, String mapYamlPath) throws IOException {

        ensureBtXmlFiles();

        // 转向几何：一处配置(plc.wheel.*)，这里、舵角换算、避障走廊、导航目标换算全用它
        double steerCx = obstacleGuardService.getSteerCenterX();
        double steerCy = obstacleGuardService.getSteerCenterY();
        String footprint = navFootprint(steerCx, steerCy);
        double minTurnRadius = obstacleGuardService.getWheelbaseM()
                / Math.tan(Math.toRadians(MAX_STEER_DEG)) * turnRadiusFactor;
        log.info("[nav2参数] base_link = body + ({}, {}), footprint={}, Smac 最小转弯半径={}m",
                String.format("%.2f", steerCx), String.format("%.2f", steerCy), footprint,
                String.format("%.2f", minTurnRadius));

        String content = "# Nav2 参数 - 由 Java MapController 生成,适配 fast_lio(雷达无关,支持 Unitree L1 / Livox Mid360 等)\n"
                + "# 生成时间: " + new java.util.Date() + "\n"
                + "# 注意:修改后需重启 Nav2 才生效(systemctl restart robot-nav 或 重启 launch)\n"
                + "\n"
                // 不是 ROS 节点，是给 robot_full.launch.py 读的：按这里发布 body→base_link 静态 TF。
                // 放在同一个文件里，保证 TF 偏移和下面按 base_link 写的 footprint 永远出自同一次生成。
                + "agv_base_link:\n"
                + "  ros__parameters:\n"
                + "    parent_frame: " + BASE_FRAME + "\n"
                + "    child_frame: " + NAV_BASE_FRAME + "\n"
                + "    x: " + String.format("%.4f", steerCx) + "\n"
                + "    y: " + String.format("%.4f", steerCy) + "\n"
                + "\n"
                + "amcl:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    alpha1: 0.1\n"
                + "    alpha2: 0.1\n"
                + "    alpha3: 0.1\n"
                + "    alpha4: 0.1\n"
                + "    alpha5: 0.1\n"
                + "    base_frame_id: \"" + BASE_FRAME + "\"\n"
                + "    global_frame_id: \"map\"\n"
                + "    odom_frame_id: \"" + ODOM_FRAME + "\"\n"
                + "    scan_topic: /scan\n"
                + "    laser_model_type: likelihood_field\n"
                + "    laser_max_range: 30.0\n"
                + "    laser_min_range: -1.0\n"
                + "    resample_interval: 3\n"
                // ⚠ 不要往上调。likelihood_field 的计算量正比于 max_beams × 粒子数,
                //   曾配 360 → 360×2000 = 72 万次查表/帧,AMCL 跟不上 10Hz 的 /scan,
                //   它的 scan 消息过滤器队列(深度 10)一路攒满,实测处理的是 1.5 秒前的那帧。
                //   后果不是"定位慢一点"这么简单,见下面 transform_tolerance 的注释。
                //   60 是 nav2 默认值,对 360 线扫描已经足够。
                + "    max_beams: 60\n"
                + "    max_particles: 2000\n"
                + "    min_particles: 500\n"
                + "    robot_model_type: nav2_amcl::DifferentialMotionModel\n"
                // AMCL 发的 map→camera_init TF,时间戳 = 它处理的那帧 scan 的时间戳 + 本值。
                // 值太小 → TF 早于当前时刻过期 → controller_server 查 map→camera_init 报
                //   [tf_help] Transform data too old when converting from map to camera_init
                // ⚠ 而 nav2 的 ControllerServer::isGoalReached() **不检查这次转换的返回值**:
                //     transformPose(tf, "camera_init", end_pose_, transformed_end_pose, tol);
                //     return goal_checker_->isGoalReached(pose, transformed_end_pose.pose, vel);
                //   转换失败时 transformed_end_pose 保持默认的 (0,0,0),而 camera_init 原点
                //   就是 fast_lio 启动时车所在位置 —— 车还没走远就落在 xy_goal_tolerance(0.25m)
                //   以内 → 直接判定"Reached the goal!"。
                //   2026-08 现场就是这样:路径正常算出、车动了 1.3s、TF 一超时立刻报导航完成。
                // 所以这个值必须覆盖住 AMCL 的实际处理滞后,2.0 是留了余量的兜底。
                + "    transform_tolerance: 2.0\n"
                + "    update_min_a: 0.2\n"
                + "    update_min_d: 0.25\n"
                + "    tf_broadcast: true\n"
                + "    set_initial_pose: true\n"
                + "    initial_pose:\n"
                + "      x: " + String.format("%.4f", initX) + "\n"
                + "      y: " + String.format("%.4f", initY) + "\n"
                + "      z: 0.0\n"
                + "      yaw: " + String.format("%.4f", initTheta) + "\n"
                + "\n"
                + "bt_navigator:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    global_frame: map\n"
                + "    robot_base_frame: " + NAV_BASE_FRAME + "\n"
                + "    odom_topic: " + ODOM_TOPIC + "\n"
                + "    bt_loop_duration: 10\n"
                + "    default_server_timeout: 20\n"
                // 车辆无法原地旋转,官方默认树里的 Spin 恢复节点会导致 bt_navigator
                // 等不到 spin action server 而激活失败,这里换成去掉 Spin 的自定义树
                + "    default_nav_to_pose_bt_xml: \"" + BT_XML_NAV_TO_POSE + "\"\n"
                + "    default_nav_through_poses_bt_xml: \"" + BT_XML_NAV_THROUGH_POSES + "\"\n"
                + "    navigators: ['navigate_to_pose', 'navigate_through_poses']\n"
                + "    navigate_to_pose:\n"
                + "      plugin: nav2_bt_navigator/NavigateToPoseNavigator\n"
                + "    navigate_through_poses:\n"
                + "      plugin: nav2_bt_navigator/NavigateThroughPosesNavigator\n"
                + "\n"
                + "controller_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    controller_frequency: 10.0\n"
                + "    min_x_velocity_threshold: 0.001\n"
                + "    min_y_velocity_threshold: 0.5\n"
                + "    min_theta_velocity_threshold: 0.001\n"
                + "    failure_tolerance: 0.3\n"
                + "    progress_checker_plugin: progress_checker\n"
                + "    goal_checker_plugins: [general_goal_checker]\n"
                + "    controller_plugins: [FollowPath]\n"
                + "    progress_checker:\n"
                + "      plugin: nav2_controller::SimpleProgressChecker\n"
                + "      required_movement_radius: 0.5\n"
                + "      movement_time_allowance: 10.0\n"
                + "    general_goal_checker:\n"
                + "      stateful: True\n"
                + "      plugin: nav2_controller::SimpleGoalChecker\n"
                // ⚠ 朝向默认不检查(3.15 rad > π)。这台车不能靠控制器原地转来对朝向，RPP 为了凑 0.25rad
                //   只能在终点前后来回倒着磨，现场表现为"到了目标一直左右转调整身位"(2026-09-14)。
                //   需要精确停车的目标由 ManeuverService 到达后原地转 + 平移对位
                + "      xy_goal_tolerance: " + String.format("%.2f", xyGoalTolerance) + "\n"
                + "      yaw_goal_tolerance: " + String.format("%.2f", yawGoalTolerance) + "\n"
                + "    FollowPath:\n"
                + "      plugin: nav2_regulated_pure_pursuit_controller::RegulatedPurePursuitController\n"
                + "      desired_linear_vel: 0.3\n"
                // 车长5.5m、舵轮最大打角45°，0.6/0.3/0.9m 的原前视距离对这台车曲率需求过大，
                // 转向能力覆盖不了；以下为起始值，需现场实测调整
                + "      lookahead_dist: 2.5\n"
                + "      min_lookahead_dist: 1.5\n"
                + "      max_lookahead_dist: 4.0\n"
                // tf_help(map→camera_init 桥接节点)实测延迟可达0.6s+,
                // 0.1s 过紧会导致 controller_server 拿姿态失败/误判"到达目标"; 放宽到 0.5s
                + "      transform_tolerance: 0.5\n"
                + "      use_velocity_scaled_lookahead_dist: false\n"
                + "      use_regulated_linear_velocity_scaling: true\n"
                + "      use_cost_regulated_linear_velocity_scaling: false\n"
                // 纯旋转指令 Java 侧直接停车（见 RobotHardwareService#twistToPlcCommand），关闭原地转向；
                // 允许倒车，配合 Smac REEDS_SHEPP 做多点掉头。⚠ RPP 规定这两个开关不能同时为 true
                + "      use_rotate_to_heading: false\n"
                + "      allow_reversing: true\n"
                + "      max_angular_accel: 1.0\n"     // 起始值，需现场实测调整
                + "\n"
                // ─────────────────────────────────────────────────────────────
                // 局部代价地图
                // ─────────────────────────────────────────────────────────────
                + "local_costmap:\n"
                + "  local_costmap:\n"
                + "    ros__parameters:\n"
                + "      use_sim_time: false\n"
                + "      update_frequency: 5.0\n"
                + "      publish_frequency: 2.0\n"
                + "      global_frame: " + ODOM_FRAME + "\n"
                + "      robot_base_frame: " + NAV_BASE_FRAME + "\n"
                + "      transform_tolerance: 0.5\n"
                + "      rolling_window: true\n"
                + "      width: 16\n"
                + "      height: 16\n"
                + "      resolution: 0.05\n"
                + "      footprint: \"" + footprint + "\"\n"
                + "      plugins: [obstacle_layer, inflation_layer]\n"
                + "      obstacle_layer:\n"
                + "        plugin: nav2_costmap_2d::ObstacleLayer\n"
                + "        enabled: True\n"
                + "        footprint_clearing_enabled: True\n"    // ① 车体自身反射点落在 footprint 内自动清除
                + "        observation_sources: scan\n"
                + "        scan:\n"
                + "          topic: /scan_obstacles\n"
                + "          data_type: LaserScan\n"
                + "          min_obstacle_height: -1.0\n"         // ② 见方法注释：LaserScan 点的 z≈0，不能在这里再按高度切
                + "          max_obstacle_height: 1.0\n"
                + "          obstacle_min_range: " + String.format("%.2f", SENSOR_MIN_RANGE) + "\n"
                + "          obstacle_max_range: 5.5\n"
                + "          raytrace_min_range: " + String.format("%.2f", SENSOR_MIN_RANGE) + "\n"
                + "          raytrace_max_range: 8.0\n"
                // ④ inf_is_valid 必须开。pc2scan 配的是 use_inf:True,没打到东西的角度输出 inf;
                //    而 nav2 默认 inf_is_valid:False 会把 inf 射线整条丢弃,不参与清障。
                //    mid360 是非重复扫描,单帧点本就散,再被 self_filter 丢掉近三成、
                //    被 min_height 0.1~max_height 1.5 卡掉大部分,720 个 bin 里绝大多数是 inf
                //    → 人走过时恰好被点中标成障碍,人走了以后那个角度几乎永远是 inf,清不掉,
                //    表现就是"人经过后障碍残留很久"。开了之后 inf 按 raytrace_max_range 清障。
                + "          inf_is_valid: True\n"
                + "          clearing: True\n"
                + "          marking: True\n"
                + "      inflation_layer:\n"
                + "        plugin: nav2_costmap_2d::InflationLayer\n"
                + "        cost_scaling_factor: 3.0\n"
                + "        inflation_radius: " + String.format("%.2f", INFLATE) + "\n"  // ③
                + "      always_send_full_costmap: True\n"
                + "\n"
                // ─────────────────────────────────────────────────────────────
                // 全局代价地图
                // ─────────────────────────────────────────────────────────────
                + "global_costmap:\n"
                + "  global_costmap:\n"
                + "    ros__parameters:\n"
                + "      use_sim_time: false\n"
                + "      update_frequency: 1.0\n"
                + "      publish_frequency: 1.0\n"
                + "      global_frame: map\n"
                + "      robot_base_frame: " + NAV_BASE_FRAME + "\n"
                + "      transform_tolerance: 0.5\n"
                + "      footprint: \"" + footprint + "\"\n"
                + "      footprint_padding: " + String.format("%.2f", globalFootprintPadding) + "\n"  // 见 globalFootprintPadding
                + "      resolution: 0.05\n"
                + "      track_unknown_space: true\n"
                + "      plugins: [static_layer, obstacle_layer, inflation_layer]\n"
                + "      static_layer:\n"
                + "        plugin: nav2_costmap_2d::StaticLayer\n"
                + "        map_subscribe_transient_local: True\n"
                + "      obstacle_layer:\n"
                + "        plugin: nav2_costmap_2d::ObstacleLayer\n"
                + "        enabled: True\n"
                + "        footprint_clearing_enabled: True\n"    // ① 同局部代价地图
                + "        observation_sources: scan\n"
                + "        scan:\n"
                + "          topic: /scan_obstacles\n"
                + "          data_type: LaserScan\n"
                + "          min_obstacle_height: -1.0\n"         // ② 见方法注释：LaserScan 点的 z≈0，不能在这里再按高度切
                + "          max_obstacle_height: 1.0\n"
                + "          obstacle_min_range: " + String.format("%.2f", SENSOR_MIN_RANGE) + "\n"
                + "          obstacle_max_range: 5.5\n"
                + "          raytrace_min_range: " + String.format("%.2f", SENSOR_MIN_RANGE) + "\n"
                + "          raytrace_max_range: 8.0\n"
                // ④ inf_is_valid 必须开。pc2scan 配的是 use_inf:True,没打到东西的角度输出 inf;
                //    而 nav2 默认 inf_is_valid:False 会把 inf 射线整条丢弃,不参与清障。
                //    mid360 是非重复扫描,单帧点本就散,再被 self_filter 丢掉近三成、
                //    被 min_height 0.1~max_height 1.5 卡掉大部分,720 个 bin 里绝大多数是 inf
                //    → 人走过时恰好被点中标成障碍,人走了以后那个角度几乎永远是 inf,清不掉,
                //    表现就是"人经过后障碍残留很久"。开了之后 inf 按 raytrace_max_range 清障。
                + "          inf_is_valid: True\n"
                + "          clearing: True\n"
                + "          marking: True\n"
                + "      inflation_layer:\n"
                + "        plugin: nav2_costmap_2d::InflationLayer\n"
                + "        cost_scaling_factor: 3.0\n"
                + "        inflation_radius: " + String.format("%.2f", INFLATE) + "\n"  // ③
                + "      always_send_full_costmap: True\n"
                + "\n"
                + "map_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    yaml_filename: \"" + (mapYamlPath == null ? "" : mapYamlPath) + "\"\n"
                + "\n"
                // ─────────────────────────────────────────────────────────────
                // 全局规划器
                //
                // ⚠ 这里**不能**用 nav2_navfn_planner/NavfnPlanner。NavFn 根本不读 footprint,
                //   它把车当成一个点在膨胀后的栅格上做 Dijkstra。对这台 5.5m×2.1m 的车,
                //   算出来的路径会贴着墙走、钻进车根本进不去的缝里(2026-09 现场实测)。
                //   footprint 配了也没用 —— 那只有 costmap 清障和 controller 在读。
                //
                // SmacPlannerHybrid 是 Hybrid-A*: 在 SE2(x,y,θ) 上搜索,每个节点都用
                // costmap 的 footprint 做整车碰撞检测,并且受 minimum_turning_radius 约束,
                // 所以规划出来的路径 ①车宽过得去 ②弯车打得过来。
                // ─────────────────────────────────────────────────────────────
                + "planner_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    planner_plugins: [GridBased]\n"
                + "    GridBased:\n"
                + "      plugin: nav2_smac_planner/SmacPlannerHybrid\n"
                + "      tolerance: 0.5\n"
                + "      allow_unknown: true\n"
                // ⚠ 2026-09-14 现场：0.05m 原分辨率下每次都卡满 max_planning_time(5s) 超时，
                //   Humble 超时和真无路打的是同一句 "no valid path found"。
                //   6m×2.4m 的车身每扩展一个节点都要沿整圈轮廓查格子(车太长，膨胀层给不出
                //   "离障碍够远就跳过轮廓检查"的加速条件)，格子数减到 1/4 规划快约 4 倍。
                //   降采样取的是 2×2 里的最大代价，偏保守，不会把障碍降没。
                + "      downsample_costmap: true\n"
                + "      downsampling_factor: 2\n"
                + "      max_iterations: 1000000\n"
                + "      max_on_approach_iterations: 1000\n"
                + "      max_planning_time: 10.0\n"
                // = 等效轴距(plc.wheel.wheelbase-m)/tan45° × ros.nav2.turn-radius-factor，参考点是 base_link(转向中心)。
                // 现场若发现规划出的弯车实际打不过来, 调 factor 或重新标定等效轴距, 不要去动 footprint。
                + "      minimum_turning_radius: " + String.format("%.2f", minTurnRadius) + "\n"
                // REEDS_SHEPP 允许倒车, 和 RPP 的 allow_reversing:true 对齐 ——
                // 这台车不能原地掉头, 不许倒车的话死胡同里只能规划失败
                + "      motion_model_for_search: REEDS_SHEPP\n"
                + "      angle_quantization_bins: 72\n"
                + "      analytic_expansion_ratio: 3.5\n"
                + "      analytic_expansion_max_length: 6.0\n"
                // 倒车代价倍数。2.0 时点车正后方几米的目标，Smac 宁可往前开出去掉头绕一圈也不倒(2026-09-14 现场)。
                // ⚠ 调小让它愿意倒车后，还受 Java 侧 plc.reverse.max-distance-m(单段倒车上限)约束
                + "      reverse_penalty: " + String.format("%.2f", reversePenalty) + "\n"
                // 左右换向(左打舵↔右打舵)代价。0 时规划器随意左右交替，关了平滑之后路径就是一截截小弯(2026-09-15)
                + "      change_penalty: " + String.format("%.2f", changePenalty) + "\n"
                + "      non_straight_penalty: " + String.format("%.2f", nonStraightPenalty) + "\n"
                + "      cost_penalty: 2.0\n"
                + "      retrospective_penalty: 0.015\n"
                + "      lookup_table_size: 20.0\n"
                // 目标不变时复用障碍启发表。BT 每秒重规划一次，不缓存就每次从头算一遍整图
                + "      cache_obstacle_heuristic: true\n"
                // ⚠ 关掉平滑。Humble 的 Smac 平滑器挪动路径点时只查"路径点本身"那一格的代价，
                //   不做 footprint 碰撞检测 —— Hybrid-A* 按整车轮廓搜出来的安全路径，
                //   被它往弯道内侧一拉，车身就压墙了。Hybrid-A* 的运动基元本身就是满足
                //   最小转弯半径的圆弧，不平滑也能跑，RPP 2.5m 前视对这点折线不敏感。
                //   下面 smoother 参数块留着，smooth_path 为 false 时不生效。
                + "      smooth_path: false\n"
                + "      smoother:\n"
                + "        max_iterations: 1000\n"
                + "        w_smooth: 0.3\n"
                + "        w_data: 0.2\n"
                + "        tolerance: 1.0e-10\n"
                + "        do_refinement: true\n"
                + "        refinement_num: 2\n"
                + "\n"
                + "behavior_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    costmap_topic: local_costmap/costmap_raw\n"
                + "    footprint_topic: local_costmap/published_footprint\n"
                + "    cycle_frequency: 10.0\n"
                // 车辆无法原地旋转，不注册 spin 恢复行为（若 BT 配置里仍引用 Spin 节点，
                // 需一并改成 backup/drive_on_heading，否则该恢复步骤会直接失败）
                + "    behavior_plugins: [backup, drive_on_heading, wait]\n"
                + "    backup:\n"
                + "      plugin: nav2_behaviors/BackUp\n"
                + "    drive_on_heading:\n"
                + "      plugin: nav2_behaviors/DriveOnHeading\n"
                + "    wait:\n"
                + "      plugin: nav2_behaviors/Wait\n"
                + "    global_frame: " + ODOM_FRAME + "\n"
                + "    robot_base_frame: " + NAV_BASE_FRAME + "\n"
                + "    transform_tolerance: 0.5\n"
                + "    simulate_ahead_time: 2.0\n"
                + "\n"
                + "waypoint_follower:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    loop_rate: 20\n"
                + "    stop_on_failure: false\n"
                + "    waypoint_task_executor_plugin: wait_at_waypoint\n"
                + "    wait_at_waypoint:\n"
                + "      plugin: nav2_waypoint_follower::WaitAtWaypoint\n"
                + "      enabled: True\n"
                + "      waypoint_pause_duration: 200\n"
                + "\n"
                + "velocity_smoother:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    smoothing_frequency: 20.0\n"
                + "    scale_velocities: false\n"
                + "    feedback: OPEN_LOOP\n"
                + "    max_velocity: [0.5, 0.0, 2.0]\n"
                + "    min_velocity: [-0.5, 0.0, -2.0]\n"
                + "    deadband_velocity: [0.0, 0.0, 0.0]\n"
                + "    velocity_timeout: 1.0\n"
                + "    max_accel: [2.5, 0.0, 3.2]\n"
                + "    max_decel: [-2.5, 0.0, -3.2]\n"
                + "    odom_topic: " + ODOM_TOPIC + "\n"
                + "    odom_duration: 0.1\n"
                + "\n"
                + "lifecycle_manager_localization:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    autostart: true\n"
                + "    node_names: [\"map_server\", \"amcl\"]\n"
                + "    bond_timeout: 0.0\n"
                + "    attempt_respawn_reconnection: false\n"
                + "\n"
                + "lifecycle_manager_navigation:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    autostart: true\n"
                + "    node_names: [\"controller_server\", \"smoother_server\", \"planner_server\","
                + " \"behavior_server\", \"bt_navigator\", \"waypoint_follower\", \"velocity_smoother\"]\n"
                + "    bond_timeout: 0.0\n"
                + "    attempt_respawn_reconnection: false\n";

        File f = new File(filePath);
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    // ===================== ROS2 检测工具 =====================

    private boolean checkTopicActive(String topic, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec + " ros2 topic hz " + topic
                    + " --window 3 2>&1 | head -5";
            String output = runCommand(cmd, timeoutSec + 2);
            return output.contains("average rate") || output.contains("hz");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkNodeExists(String nodeName, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec + " ros2 node list 2>&1";
            String output = runCommand(cmd, timeoutSec);
            return output.contains(nodeName);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkTfAvailable(String parentFrame, String childFrame, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec
                    + " ros2 run tf2_ros tf2_echo " + parentFrame + " " + childFrame
                    + " 2>&1 | head -10";
            String output = runCommand(cmd, timeoutSec + 2);
            return output.contains("Translation") || output.contains("Rotation");
        } catch (Exception e) {
            return false;
        }
    }

    private String runCommand(String cmd, int timeoutSec) throws Exception {
        long t0 = System.currentTimeMillis();
        // 日志里只打印命令尾部,避免 source 那串噪音刷屏
        String shortCmd = cmd.length() > 120 ? "..." + cmd.substring(cmd.length() - 120) : cmd;
        log.warn("[runCommand] 开始(超时{}s): {}", timeoutSec, shortCmd);
        log.warn("[runCommand] 执行的内容：{}", cmd);
        // 用 setsid 让 bash 自成新会话/进程组,这样命令里的 `timeout -s KILL N`
        // 杀进程时能干净地带走 ros2/python 子孙,不会残留孤儿占着流。
        ProcessBuilder pb = new ProcessBuilder("setsid", "bash", "-c", cmd);
        cleanCondaEnv(pb.environment());
        pb.environment().put("QT_QPA_PLATFORM", "offscreen");
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // ★ 关键修复:在后台线程里读 stdout。
        //   原来的写法是在主线程 while(readLine()) 里读流,readLine 会一直阻塞到
        //   子进程退出为止;一旦 ros2 永不退出,后面的 waitFor(timeout) 根本执行不到,
        //   所谓的超时完全失效。把读流丢到后台线程,主线程才能真正用 waitFor 控制超时。
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) { sb.append(line).append("\n"); }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流会抛异常,正常现象
            }
        }, "runCommand-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!finished) {
            // 超时:先 destroyForcibly,再兜底用 kill 把整个进程组干掉
            //   (bash 的子孙进程 ros2/python 可能不随 bash 一起死,会变孤儿继续占着流)
            log.warn("[runCommand] ★超时 {}s 未结束,强制终止! cmd尾={}", timeoutSec, shortCmd);
            killProcessTree(p);
        }
        // 给读线程一点时间把已有输出收完
        reader.join(500);

        String out;
        synchronized (sb) { out = sb.toString(); }
        log.warn("[runCommand] 结束 finished={} 耗时={}ms 输出{}字节",
                finished, System.currentTimeMillis() - t0, out.length());
        return out;
    }

    /**
     * 强制终止子进程。JDK 1.8 没有 Process.pid(),无法按进程组 kill,
     * 所以这里只杀 bash 本体;真正可能 hang 的 ros2/python 子孙,
     * 由命令里包的 `timeout -s KILL N` + setsid 进程组负责清理。
     * → 因此务必保证每条可能阻塞的命令都带了 `timeout`。
     */
    private void killProcessTree(Process p) {
        if (p.isAlive()) p.destroyForcibly();
    }

    /** 清理 conda 污染的环境变量 */
    private static void cleanCondaEnv(Map<String, String> env) {
        env.put("QT_QPA_PLATFORM", "offscreen");
        env.remove("PYTHONPATH");
        env.remove("PYTHONHOME");
        env.remove("CONDA_PREFIX");
        env.remove("CONDA_DEFAULT_ENV");
        env.remove("CONDA_PYTHON_EXE");
        env.remove("CONDA_SHLVL");
        env.remove("CONDA_PROMPT_MODIFIER");
        env.remove("_CE_CONDA");
        env.remove("_CE_M");

        String path = env.getOrDefault("PATH", "");
        if (!path.isEmpty()) {
            StringBuilder cleanPath = new StringBuilder();
            for (String p : path.split(":")) {
                if (!p.contains("conda") && !p.contains("anaconda")) {
                    if (cleanPath.length() > 0) cleanPath.append(":");
                    cleanPath.append(p);
                }
            }
            env.put("PATH", cleanPath.toString());
        }

        String ld = env.get("LD_LIBRARY_PATH");
        if (ld != null && !ld.isEmpty()) {
            StringBuilder cleanLd = new StringBuilder();
            for (String p : ld.split(":")) {
                if (!p.contains("conda") && !p.contains("anaconda")) {
                    if (cleanLd.length() > 0) cleanLd.append(":");
                    cleanLd.append(p);
                }
            }
            env.put("LD_LIBRARY_PATH", cleanLd.toString());
        }
    }

    // ===================== 解析工具 =====================

    private double parseJsonDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\-0-9.eE]+)").matcher(json);
        if (m.find()) return Double.parseDouble(m.group(1));
        throw new IllegalArgumentException("JSON key not found: " + key);
    }

    // ===================== 地图列表 =====================

    @GetMapping("/list")
    @ApiOperation("获取已保存地图列表")
    public Result<List<Map<String, Object>>> getMapList() {
        try {
            File dir = new File(MAP_DIR);
            if (!dir.exists()) return Result.OK(new ArrayList<>());
            File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml"));
            if (files == null) return Result.OK(new ArrayList<>());
            List<Map<String, Object>> list = new ArrayList<>();
            for (File f : files) {
                if (f.getName().startsWith("nav2_params")) continue;
                // 跳过 last_used.yaml 软链接,它只是指向真实地图的别名
                if (f.getName().equals(LAST_USED_LINK)) continue;
                String name = f.getName().replace(".yaml", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",          name);
                m.put("createTime",    f.lastModified());
                m.put("hasImage",      new File(MAP_DIR + name + ".pgm").exists());
                m.put("loaded",        name.equals(loadedMapName));
                m.put("hasSavedPose",  new File(MAP_DIR + name + POSE_FILE_SUFFIX).exists());
                list.add(m);
            }
            list.sort((a, b) -> Long.compare(
                    (Long) b.get("createTime"),
                    (Long) a.get("createTime")
            ));
            return Result.OK(list);
        } catch (Exception e) {
            log.error("获取地图列表失败", e);
            return Result.error("获取列表失败: " + e.getMessage());
        }
    }

    // ===================== 地图图像 =====================

    @GetMapping("/image/{mapName}")
    @ApiOperation("获取地图图像(PNG)")
    public ResponseEntity<byte[]> getMapImage(@PathVariable String mapName) {
        try {
            File pngFile = new File(MAP_DIR + mapName + ".png");
            if (pngFile.exists()) {
                return new ResponseEntity<>(
                        Files.readAllBytes(pngFile.toPath()), pngHeaders(), HttpStatus.OK);
            }
            File pgmFile = new File(MAP_DIR + mapName + ".pgm");
            if (!pgmFile.exists()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

            BufferedImage img = readPgm(pgmFile);
            byte[] bytes = toPng(img);
            try { Files.write(pngFile.toPath(), bytes); } catch (Exception ignored) {}
            return new ResponseEntity<>(bytes, pngHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            log.error("获取地图图像失败: {}", mapName, e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===================== 地图元数据 =====================

    @GetMapping("/meta/{mapName}")
    @ApiOperation("获取地图元数据")
    public Result<Map<String, Object>> getMapMeta(@PathVariable String mapName) {
        try {
            File yamlFile = new File(MAP_DIR + mapName + ".yaml");
            if (!yamlFile.exists()) return Result.error("地图 yaml 不存在: " + mapName);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mapName",  mapName);
            meta.put("imageUrl", "/api/map/image/" + mapName);

            try (BufferedReader br = new BufferedReader(new FileReader(yamlFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("resolution:")) {
                        meta.put("resolution",
                                Double.parseDouble(line.substring("resolution:".length()).trim()));
                    } else if (line.startsWith("origin:")) {
                        String arr = line.replaceAll(".*\\[(.*)\\].*", "$1");
                        String[] parts = arr.split(",");
                        if (parts.length >= 2) {
                            meta.put("originX", Double.parseDouble(parts[0].trim()));
                            meta.put("originY", Double.parseDouble(parts[1].trim()));
                        }
                    }
                }
            }

            File pgmFile = new File(MAP_DIR + mapName + ".pgm");
            if (pgmFile.exists()) {
                int[] size = readPgmSize(pgmFile);
                meta.put("width",  size[0]);
                meta.put("height", size[1]);
            }

            meta.putIfAbsent("resolution", 0.05);
            meta.putIfAbsent("originX",    0.0);
            meta.putIfAbsent("originY",    0.0);
            meta.putIfAbsent("width",      0);
            meta.putIfAbsent("height",     0);

            File poseFile = new File(MAP_DIR + mapName + POSE_FILE_SUFFIX);
            if (poseFile.exists()) {
                try {
                    String json = new String(
                            Files.readAllBytes(poseFile.toPath()), StandardCharsets.UTF_8);
                    meta.put("lastPose", json);
                } catch (Exception ignored) {}
            }

            return Result.OK(meta);
        } catch (Exception e) {
            log.error("读取地图元数据失败: {}", mapName, e);
            return Result.error("读取元数据失败: " + e.getMessage());
        }
    }

    // ===================== 删除地图 =====================

    @DeleteMapping("/{mapName}")
    @ApiOperation("删除地图(yaml + pgm + png + 位姿文件)")
    public Result<Void> deleteMap(@PathVariable String mapName) {
        try {
            // 不允许删除当前正在使用的地图
            if (mapName.equals(loadedMapName)) {
                return Result.error("不能删除当前正在使用的地图,请先切换到其他地图");
            }
            // 不允许通过此接口删除 last_used 软链接
            if (LAST_USED_LINK.equals(mapName + ".yaml")) {
                return Result.error("last_used 是系统维护的软链接,不能直接删除");
            }
            boolean deleted = false;
            for (String ext : new String[]{".yaml", ".pgm", ".png", POSE_FILE_SUFFIX, ERASE_ORIG_SUFFIX, ERASE_LOG_SUFFIX}) {
                File f = new File(MAP_DIR + mapName + ext);
                if (f.exists()) deleted |= f.delete();
            }
            // 如果删除的恰好是 last_used 当前指向的地图,把链接也清掉,避免悬空
            try {
                Path link = Paths.get(MAP_DIR, LAST_USED_LINK);
                if (Files.isSymbolicLink(link)) {
                    Path target = Files.readSymbolicLink(link);
                    if (target.getFileName().toString().equals(mapName + ".yaml")) {
                        Files.deleteIfExists(link);
                        log.info("已清理悬空的 last_used 软链接(原指向已删除的 {})", mapName);
                    }
                }
            } catch (Exception ignored) {}
            return deleted ? Result.OK("地图已删除")
                    : Result.error("地图文件不存在: " + mapName);
        } catch (Exception e) {
            log.error("删除地图失败", e);
            return Result.error("删除失败: " + e.getMessage());
        }
    }

    // ===================== 已保存地图擦除(导航页框选) =====================
    //
    // 建图页的「擦除」只作用于建图时内存里的栅格，存成 pgm 之后就改不了了。建图时被扫进去、后来挪走的
    // 料堆/车/人会一直留在 static_layer 里，任何 costmap 参数都清不掉。这里直接改 pgm：
    //   · 第一次擦时把原图备份成 {map}.pgm.orig，之后每次都从原图 + 擦除记录({map}_erase.json)整张重算，
    //     所以撤销任意一步、恢复原图都是精确的，不会越擦越花
    //   · 改完调 /map_server/load_map 热加载，不重启 ROS
    //   · ⚠ AMCL 收到新地图会重置粒子，定位会丢 —— 加载完立刻按擦除前的位姿重发一次初始位姿

    private static final String ERASE_ORIG_SUFFIX = ".pgm.orig";
    private static final String ERASE_LOG_SUFFIX  = "_erase.json";

    @Autowired
    private org.jeecg.modules.ros2.service.RobotHardwareService hardwareService;

    @Autowired
    private org.jeecg.modules.ros2.service.NavigationService navigationService;

    @GetMapping("/erase")
    @ApiOperation("当前地图已擦除的区域")
    public Result<Map<String, Object>> eraseList() {
        try {
            String name = currentMapName();
            if (name == null) return Result.error("当前没有加载地图");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("mapName", name);
            r.put("rects", readEraseLog(name));
            return Result.OK(r);
        } catch (Exception e) {
            return Result.error("读取擦除记录失败: " + e.getMessage());
        }
    }

    @PostMapping("/erase")
    @ApiOperation("擦除当前地图的一块矩形区域(涂成空闲)并热加载")
    public synchronized Result<Map<String, Object>> eraseRect(@RequestBody Map<String, Double> body) {
        try {
            String name = currentMapName();
            if (name == null) return Result.error("当前没有加载地图");
            String busy = eraseBusyReason();
            if (busy != null) return Result.error(busy);
            Double x1 = body.get("x1"), y1 = body.get("y1"), x2 = body.get("x2"), y2 = body.get("y2");
            if (x1 == null || y1 == null || x2 == null || y2 == null) return Result.error("缺少 x1/y1/x2/y2");
            List<double[]> rects = readEraseLog(name);
            rects.add(new double[]{Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)});
            return Result.OK("已擦除并重新加载地图", applyErase(name, rects));
        } catch (Exception e) {
            log.error("[地图擦除] 失败", e);
            return Result.error("擦除失败: " + e.getMessage());
        }
    }

    @PostMapping("/erase/undo")
    @ApiOperation("撤销最后一块擦除")
    public synchronized Result<Map<String, Object>> eraseUndo() {
        try {
            String name = currentMapName();
            if (name == null) return Result.error("当前没有加载地图");
            String busy = eraseBusyReason();
            if (busy != null) return Result.error(busy);
            List<double[]> rects = readEraseLog(name);
            if (rects.isEmpty()) return Result.error("没有可撤销的擦除");
            rects.remove(rects.size() - 1);
            return Result.OK("已撤销并重新加载地图", applyErase(name, rects));
        } catch (Exception e) {
            log.error("[地图擦除] 撤销失败", e);
            return Result.error("撤销失败: " + e.getMessage());
        }
    }

    @PostMapping("/erase/restore")
    @ApiOperation("恢复原图(丢弃全部擦除)")
    public synchronized Result<Map<String, Object>> eraseRestore() {
        try {
            String name = currentMapName();
            if (name == null) return Result.error("当前没有加载地图");
            String busy = eraseBusyReason();
            if (busy != null) return Result.error(busy);
            if (!new File(MAP_DIR + name + ERASE_ORIG_SUFFIX).exists()) return Result.error("这张地图没有擦除过");
            return Result.OK("已恢复原图并重新加载", applyErase(name, new ArrayList<>()));
        } catch (Exception e) {
            log.error("[地图擦除] 恢复失败", e);
            return Result.error("恢复失败: " + e.getMessage());
        }
    }

    /** 车在动时不许换地图：AMCL 重置粒子那一下定位会跳 */
    private String eraseBusyReason() {
        if (hardwareService.isManeuverActive()
                || hardwareService.getCurrentMode() != org.jeecg.modules.ros2.service.RobotHardwareService.DriveMode.STOP) {
            return "车正在行驶/机动中，停车(取消导航)后再擦除地图";
        }
        return null;
    }

    private List<double[]> readEraseLog(String name) throws IOException {
        File f = new File(MAP_DIR + name + ERASE_LOG_SUFFIX);
        List<double[]> out = new ArrayList<>();
        if (!f.exists()) return out;
        double[][] arr = new com.google.gson.Gson().fromJson(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), double[][].class);
        if (arr != null) out.addAll(Arrays.asList(arr));
        return out;
    }

    /** 从原图 + 擦除记录整张重算 pgm，写回、清缓存、热加载、恢复定位 */
    private Map<String, Object> applyErase(String name, List<double[]> rects) throws Exception {
        File pgm  = new File(MAP_DIR + name + ".pgm");
        File yaml = new File(MAP_DIR + name + ".yaml");
        File orig = new File(MAP_DIR + name + ERASE_ORIG_SUFFIX);
        File logF = new File(MAP_DIR + name + ERASE_LOG_SUFFIX);
        if (!pgm.exists() || !yaml.exists()) throw new IllegalStateException("地图文件不存在: " + name);
        if (!orig.exists()) {
            Files.copy(pgm.toPath(), orig.toPath());
            log.info("[地图擦除] 首次擦除，原图已备份为 {}", orig.getName());
        }

        int[] wh = readPgmSize(orig);
        int w = wh[0], h = wh[1];
        byte[] all = Files.readAllBytes(orig.toPath());
        int dataOff = all.length - w * h;   // 8 位 pgm：像素区就是最后 w*h 个字节，头部多长都不影响
        if (dataOff <= 0) throw new IllegalStateException("pgm 不是 8 位灰度图，无法擦除");

        double res = 0.05, ox = 0, oy = 0;
        boolean negate = false;
        for (String line : Files.readAllLines(yaml.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("resolution:"))  res = Double.parseDouble(line.substring(11).trim());
            else if (line.startsWith("negate:")) negate = "1".equals(line.substring(7).trim());
            else if (line.startsWith("origin:")) {
                String[] p = line.replaceAll(".*\\[(.*)\\].*", "$1").split(",");
                ox = Double.parseDouble(p[0].trim());
                oy = Double.parseDouble(p[1].trim());
            }
        }
        byte free = negate ? 0 : (byte) 254;
        int cells = 0;
        for (double[] rc : rects) {
            int c0 = Math.max(0, (int) Math.floor((rc[0] - ox) / res)), c1 = Math.min(w - 1, (int) Math.floor((rc[2] - ox) / res));
            int r0 = Math.max(0, (int) Math.floor((rc[1] - oy) / res)), r1 = Math.min(h - 1, (int) Math.floor((rc[3] - oy) / res));
            for (int r = r0; r <= r1; r++) {
                int row = h - 1 - r;                 // pgm 第 0 行是 y 最大处
                for (int c = c0; c <= c1; c++) {
                    all[dataOff + row * w + c] = free;
                    cells++;
                }
            }
        }

        // 先写临时文件再原子替换：map_server 读到写了一半的 pgm 会直接加载失败
        Path tmp = Paths.get(MAP_DIR, name + ".pgm.tmp");
        Files.write(tmp, all);
        Files.move(tmp, pgm.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(Paths.get(MAP_DIR, name + ".png"));   // /image 接口的 PNG 缓存，不删前端看到的还是旧图

        if (rects.isEmpty()) {
            Files.deleteIfExists(logF.toPath());
            Files.deleteIfExists(orig.toPath());
        } else {
            Files.write(logF.toPath(), new com.google.gson.Gson().toJson(rects).getBytes(StandardCharsets.UTF_8));
        }
        log.info("[地图擦除] {} 共 {} 块擦除区, 涂白 {} 格", name, rects.size(), cells);

        double[] pose = ros2BridgeService.getLastAmclPose();
        boolean reloaded = callMapLoadServiceViaBridge(yaml.getAbsolutePath());
        boolean relocalized = false;
        if (reloaded && pose != null) {
            navigationService.sendInitialPose(pose[0], pose[1], pose[2]);
            relocalized = true;
        }
        if (!reloaded) log.warn("[地图擦除] pgm 已改好，但 load_map 失败；前端切一次地图或重启 ROS 栈后生效");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mapName", name);
        r.put("rects", rects);
        r.put("cells", cells);
        r.put("reloaded", reloaded);
        r.put("relocalized", relocalized);
        return r;
    }

    // ===================== 静态地图占据查询(机动安全校验用) =====================
    //
    // 雷达在车头，车身后段两侧和车尾大半被车体自己挡住，点云证明不了"那里没东西"。
    // 原地旋转/平移前再拿静态地图查一遍扫掠区域：墙、料堆这类固定物至少不会漏。
    // 判定按 map_server trinary 规则；未知格一律当不可通行(fail-safe)。

    private static final class StaticGrid {
        String name; long mtime; int w, h; double res, ox, oy; boolean[] free, occupied;
    }
    private volatile StaticGrid staticGrid;

    /** 当前地图名。loadedMapName 为空(Java 重启后还没切过图)时按 last_used.yaml 软链接找；都没有返回 null */
    private String currentMapName() throws IOException {
        String name = loadedMapName;
        if (name == null) {
            Path link = Paths.get(MAP_DIR, LAST_USED_LINK);
            if (Files.isSymbolicLink(link)) {
                name = Files.readSymbolicLink(link).getFileName().toString().replaceAll("\\.yaml$", "");
            }
        }
        return name;
    }

    /** 当前地图的栅格；没有可用地图返回 null */
    private StaticGrid currentStaticGrid() throws IOException {
        String name = currentMapName();
        if (name == null) return null;
        File yaml = new File(MAP_DIR + name + ".yaml"), pgm = new File(MAP_DIR + name + ".pgm");
        if (!yaml.exists() || !pgm.exists()) return null;

        StaticGrid g = staticGrid;
        if (g != null && g.name.equals(name) && g.mtime == pgm.lastModified()) return g;

        double res = 0.05, ox = 0, oy = 0, freeTh = 0.196, occTh = 0.65;
        boolean negate = false;
        for (String line : Files.readAllLines(yaml.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("resolution:"))           res = Double.parseDouble(line.substring(11).trim());
            else if (line.startsWith("free_thresh:"))     freeTh = Double.parseDouble(line.substring(12).trim());
            else if (line.startsWith("occupied_thresh:")) occTh = Double.parseDouble(line.substring(16).trim());
            else if (line.startsWith("negate:"))          negate = "1".equals(line.substring(7).trim());
            else if (line.startsWith("origin:")) {
                String[] p = line.replaceAll(".*\\[(.*)\\].*", "$1").split(",");
                ox = Double.parseDouble(p[0].trim());
                oy = Double.parseDouble(p[1].trim());
            }
        }
        BufferedImage img = readPgm(pgm);
        g = new StaticGrid();
        g.name = name; g.mtime = pgm.lastModified();
        g.w = img.getWidth(); g.h = img.getHeight(); g.res = res; g.ox = ox; g.oy = oy;
        g.free = new boolean[g.w * g.h];
        g.occupied = new boolean[g.w * g.h];
        for (int r = 0; r < g.h; r++) {
            for (int c = 0; c < g.w; c++) {
                int v = img.getRGB(c, r) & 0xFF;
                double p = negate ? v / 255.0 : (255 - v) / 255.0;
                // pgm 第 0 行是地图最上面(y 最大)，翻成 row 0 = y 最小
                int i = (g.h - 1 - r) * g.w + c;
                g.free[i]     = p < freeTh;
                g.occupied[i] = p > occTh;
            }
        }
        staticGrid = g;
        log.info("[静态地图校验] 已载入 {} ({}x{} @ {}m)", name, g.w, g.h, res);
        return g;
    }

    /**
     * 世界系(map)凸多边形覆盖的格子是否全部是已知空闲。
     * @return null = 全部空闲；否则是拦下的原因(含地图不可用)
     */
    public String staticMapBlockedPolygon(double[][] poly) {
        try {
            StaticGrid g = currentStaticGrid();
            if (g == null) return "当前没有可用的静态地图，无法校验";
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : poly) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            for (double y = minY; y <= maxY; y += g.res) {
                for (double x = minX; x <= maxX; x += g.res) {
                    if (!insidePolygon(poly, x, y)) continue;
                    String why = cellBlocked(g, x, y);
                    if (why != null) return why;
                }
            }
            return null;
        } catch (Exception e) {
            return "读取静态地图失败: " + e.getMessage();
        }
    }

    /**
     * 按 Smac 的口径查多边形压没压到**障碍格**(未知格不算，planner 配的 allow_unknown: true；出图也算障碍)。
     * 用来在发目标前就告诉现场"这个位姿车身放不下"，不用等 Nav2 搜满 100 万次再报一句没头没尾的失败。
     * @return null = 没压到；否则是命中数量和前几个坐标；地图不可用返回 null(不拦，交给 Nav2)
     */
    public String staticMapOccupiedPolygon(double[][] poly) {
        List<double[]> pts = staticMapOccupiedPoints(poly, Integer.MAX_VALUE);
        if (pts.isEmpty()) return null;
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < Math.min(3, pts.size()); i++) {
            sample.append(String.format(" (%.2f,%.2f)", pts.get(i)[0], pts.get(i)[1]));
        }
        return pts.size() + " 个障碍格，如" + sample;
    }

    /** 多边形内压到的障碍格(含图外)中心坐标，最多 max 个；地图不可用返回空表(不拦) */
    public List<double[]> staticMapOccupiedPoints(double[][] poly, int max) {
        List<double[]> out = new ArrayList<>();
        scanOccupied(poly, max, out);
        return out;
    }

    /** 多边形内是否压到任何障碍格。只要是非/是，命中第一个就返回，找可行位姿时要跑几百次 */
    public boolean staticMapPolygonHasOccupied(double[][] poly) {
        return scanOccupied(poly, 1, null) > 0;
    }

    private int scanOccupied(double[][] poly, int max, List<double[]> out) {
        try {
            StaticGrid g = currentStaticGrid();
            if (g == null) return 0;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : poly) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            int hits = 0;
            for (double y = minY; y <= maxY; y += g.res) {
                for (double x = minX; x <= maxX; x += g.res) {
                    if (!insidePolygon(poly, x, y)) continue;
                    int c = (int) Math.floor((x - g.ox) / g.res), r = (int) Math.floor((y - g.oy) / g.res);
                    if (c < 0 || r < 0 || c >= g.w || r >= g.h || g.occupied[r * g.w + c]) {
                        if (out != null) out.add(new double[]{x, y});
                        if (++hits >= max) return hits;
                    }
                }
            }
            return hits;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 世界系圆内格子是否全部已知空闲。null = 空闲 */
    public String staticMapBlockedCircle(double cx, double cy, double r) {
        try {
            StaticGrid g = currentStaticGrid();
            if (g == null) return "当前没有可用的静态地图，无法校验";
            for (double y = cy - r; y <= cy + r; y += g.res) {
                for (double x = cx - r; x <= cx + r; x += g.res) {
                    if (Math.hypot(x - cx, y - cy) > r) continue;
                    String why = cellBlocked(g, x, y);
                    if (why != null) return why;
                }
            }
            return null;
        } catch (Exception e) {
            return "读取静态地图失败: " + e.getMessage();
        }
    }

    /**
     * 从 (x,y) 沿单位方向 (ux,uy) 往外走，第一个非空闲格的距离；maxM 内都空闲返回 maxM。地图不可用返回 0(当贴墙处理)。
     */
    public double staticMapFreeDistance(double x, double y, double ux, double uy, double maxM) {
        try {
            StaticGrid g = currentStaticGrid();
            if (g == null) return 0;
            for (double d = 0; d < maxM; d += g.res) {
                if (cellBlocked(g, x + ux * d, y + uy * d) != null) return d;
            }
            return maxM;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String cellBlocked(StaticGrid g, double x, double y) {
        int c = (int) Math.floor((x - g.ox) / g.res), r = (int) Math.floor((y - g.oy) / g.res);
        if (c < 0 || r < 0 || c >= g.w || r >= g.h) {
            return String.format("(%.2f, %.2f) 在地图范围外", x, y);
        }
        return g.free[r * g.w + c] ? null : String.format("静态地图 (%.2f, %.2f) 是障碍或未知区域", x, y);
    }

    private static boolean insidePolygon(double[][] poly, double x, double y) {
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            if ((poly[i][1] > y) != (poly[j][1] > y)
                    && x < (poly[j][0] - poly[i][0]) * (y - poly[i][1]) / (poly[j][1] - poly[i][1]) + poly[i][0]) {
                in = !in;
            }
        }
        return in;
    }

    // ===================== PGM/PNG 工具 =====================

    private HttpHeaders pngHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setCacheControl("max-age=60, must-revalidate");
        return h;
    }

    private BufferedImage readPgm(File file) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            readAsciiLine(bis);
            String line = readAsciiLine(bis);
            while (line.startsWith("#")) line = readAsciiLine(bis);
            String[] dims = line.trim().split("\\s+");
            int w = Integer.parseInt(dims[0]), h = Integer.parseInt(dims[1]);
            readAsciiLine(bis);
            byte[] data = new byte[w * h];
            int offset = 0;
            while (offset < data.length) {
                int n = bis.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int v = data[y * w + x] & 0xFF;
                    img.setRGB(x, y, (v << 16) | (v << 8) | v);
                }
            return img;
        }
    }

    private int[] readPgmSize(File file) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            readAsciiLine(bis);
            String line = readAsciiLine(bis);
            while (line.startsWith("#")) line = readAsciiLine(bis);
            String[] d = line.trim().split("\\s+");
            return new int[]{Integer.parseInt(d[0]), Integer.parseInt(d[1])};
        }
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    private String readAsciiLine(BufferedInputStream bis) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = bis.read()) != -1 && b != '\n') {
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString().trim();
    }
}
