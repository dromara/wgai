package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 地图管理控制器 - 适配 fast_lio + Unitree L1
 *
 * 接口：
 *   POST   /api/map/load          加载地图 + 自动写 nav2_params + 启动全部进程
 *   POST   /api/map/stop-nav2     停止全部导航进程
 *   GET    /api/map/nav2-status   查询运行状态
 *   GET    /api/map/nav2-health   查询健康状态（启动约20秒后调用）
 *   GET    /api/map/list          地图列表
 *   GET    /api/map/image/{name}  pgm→png 图像
 *   GET    /api/map/meta/{name}   分辨率/原点/尺寸
 *   DELETE /api/map/{name}        删除地图
 *
 * 位姿自动持久化机制（无需前端手动设置初始位置）：
 *   - Nav2 启动后每 5 秒自动读取 AMCL 当前位姿并保存到文件
 *   - 下次加载同一张地图时，自动从文件恢复上次位姿作为 AMCL 初始位置
 *   - 若位姿文件不存在（首次使用），默认初始化为 (0, 0, 0)
 *   - 位姿文件路径: {MAP_DIR}/{mapName}_last_pose.json
 *
 * TF 链（fast_lio 自己提供，无需额外发布）：
 *   fast_lio → camera_init → body     (实时里程计)
 *   AMCL    → map → camera_init       (定位结果)
 *   完整链:    map → camera_init → body  ✓
 */
@Slf4j
@RestController
@RequestMapping("/api/map")
@Api(tags = "地图管理")
public class MapController {

    // ===================== 可修改的配置常量 =====================

    private static final String MAP_DIR     = "/home/ros/maps/";
    private static final String SETUP_BASH  = "/home/lio_ws/install/setup.bash";
    private static final String ROS_BASH    = "/opt/ros/humble/setup.bash";

    /** nav2 params 文件生成路径 */
    private static final String NAV2_PARAMS_PATH = "/home/ros/nav2_params_fastlio.yaml";

    /** 位姿持久化文件后缀（每张地图独立） */
    private static final String POSE_FILE_SUFFIX = "_last_pose.json";

    /** fast_lio 里程计父帧 */
    private static final String ODOM_FRAME   = "camera_init";
    /** fast_lio 机器人帧 */
    private static final String BASE_FRAME   = "body";
    /** fast_lio 里程计话题 */
    private static final String ODOM_TOPIC   = "/Odometry";
    /** fast_lio 点云话题 */
    private static final String CLOUD_TOPIC  = "/cloud_registered";
    /** 点云投影高度范围 */
    private static final double SCAN_MIN_H   = 0.1;
    private static final double SCAN_MAX_H   = 1.5;
    /** 机器人半径（米） */
    private static final double ROBOT_RADIUS = 0.3;
    /** 膨胀半径（米） */
    private static final double INFLATE_R    = 0.55;

    // ===================== 进程管理 =====================

    private volatile Process pc2scanProcess = null;
    private volatile Process nav2Process    = null;
    private volatile String  loadedMapName  = null;

    /** 位姿自动保存定时任务 */
    private volatile ScheduledExecutorService poseAutoSaveScheduler = null;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ===================== 加载地图（核心） =====================

    @PostMapping("/load")
    @ApiOperation("加载地图并启动Nav2完整导航栈（自动恢复上次位姿，无需手动设置）")
    public Result<Map<String, Object>> loadMap(@RequestBody Map<String, Object> params) {
        try {
            String mapName = (String) params.get("mapName");
            if (mapName == null || mapName.trim().isEmpty()) {
                return Result.error("地图名称不能为空");
            }
            mapName = mapName.trim();

            String yamlPath = MAP_DIR + mapName + ".yaml";
            if (!new File(yamlPath).exists()) {
                return Result.error("地图文件不存在: " + yamlPath);
            }

            // ─────────────────────────────────────────────────────────────
            // 读取上次保存的位姿，若无则默认 (0, 0, 0)
            // 无需前端传入初始位置，自动恢复上次位置
            // ─────────────────────────────────────────────────────────────
            double[] savedPose = loadSavedPose(mapName);
            double initX     = savedPose[0];
            double initY     = savedPose[1];
            double initTheta = savedPose[2];
            boolean hasSavedPose = (savedPose[0] != 0.0 || savedPose[1] != 0.0 || savedPose[2] != 0.0);
            log.info("初始位姿: x={}, y={}, theta={} ({})",
                    initX, initY, initTheta, hasSavedPose ? "从文件恢复" : "默认(0,0,0)/首次使用");

            // 停止旧进程
            stopNav2Internal();
            Thread.sleep(500);

            // ─────────────────────────────────────────────────────────────
            // Step 1: 自动生成 nav2_params_fastlio.yaml（含初始位姿）
            // ─────────────────────────────────────────────────────────────
            writeNav2Params(NAV2_PARAMS_PATH, initX, initY, initTheta);
            log.info("✅ nav2 params 已写入: {}", NAV2_PARAMS_PATH);

            // ─────────────────────────────────────────────────────────────
            // Step 2: 点云 → 激光扫描 转换
            // ─────────────────────────────────────────────────────────────
            String pc2scanCmd = "source " + ROS_BASH
                    + " && source " + SETUP_BASH
                    + " && ros2 run pointcloud_to_laserscan pointcloud_to_laserscan_node"
                    + " --ros-args"
                    + " -r cloud_in:=" + CLOUD_TOPIC
                    + " -r scan:=/scan"
                    + " -p min_height:=" + SCAN_MIN_H
                    + " -p max_height:=" + SCAN_MAX_H
                    + " -p angle_min:=-3.14159"
                    + " -p angle_max:=3.14159"
                    + " -p angle_increment:=0.0087"
                    + " -p range_min:=0.1"
                    + " -p range_max:=30.0"
                    + " -p target_frame:=" + BASE_FRAME
                    + " -p transform_tolerance:=0.1"
                    + " -p use_inf:=true";
            pc2scanProcess = startProcess("pc2scan", pc2scanCmd);
            Thread.sleep(1000);
            log.info("✅ 点云→激光: {} → /scan", CLOUD_TOPIC);

            // ─────────────────────────────────────────────────────────────
            // Step 3: 启动 Nav2 bringup（AMCL 定位 + 完整导航栈）
            //   bringup_launch.py 内含 AMCL，会在初始位姿附近撒粒子
            //   AMCL 收到 /scan 后开始定位，发布 map→camera_init TF
            // ─────────────────────────────────────────────────────────────
            String nav2Cmd = "source " + ROS_BASH
                    + " && source " + SETUP_BASH
                    + " && ros2 launch nav2_bringup bringup_launch.py"
                    + " map:=" + yamlPath
                    + " use_sim_time:=false"
                    + " params_file:=" + NAV2_PARAMS_PATH;
            nav2Process   = startProcess("nav2", nav2Cmd);
            loadedMapName = mapName;

            log.info("✅ 全部启动完成 | 地图:{} | odomFrame:{} | baseFrame:{} | 激光:{}",
                    mapName, ODOM_FRAME, BASE_FRAME, CLOUD_TOPIC);

            // ─────────────────────────────────────────────────────────────
            // Step 4: 异步健康检查 + 启动位姿自动保存
            // ─────────────────────────────────────────────────────────────
            scheduleHealthCheck(mapName);
            final String finalMapName = mapName;
            executor.submit(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(30000); // 等 Nav2 完全就绪后再开始保存
                        startPoseAutoSave(finalMapName);
                    } catch (InterruptedException ignored) {}
                }
            });

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mapName",      mapName);
            res.put("yamlPath",     yamlPath);
            res.put("imageUrl",     "/api/map/image/" + mapName);
            res.put("nav2Started",  true);
            res.put("paramsFile",   NAV2_PARAMS_PATH);
            res.put("scanConvert",  CLOUD_TOPIC + " → /scan");
            res.put("odomFrame",    ODOM_FRAME);
            res.put("baseFrame",    BASE_FRAME);
            res.put("initialPose",  buildInitialPoseInfo(initX, initY, initTheta, hasSavedPose));
            res.put("healthCheck",  "异步检测中，约20秒后可查询 /api/map/nav2-health");
            return Result.OK(res);

        } catch (Exception e) {
            log.error("加载地图失败", e);
            return Result.error("加载失败: " + e.getMessage());
        }
    }

    private Map<String, Object> buildInitialPoseInfo(double x, double y, double theta, boolean fromFile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x",        x);
        m.put("y",        y);
        m.put("theta",    theta);
        m.put("source",   fromFile ? "pose_file" : "default(0,0,0)");
        return m;
    }

    // ===================== 位姿持久化 =====================

    /**
     * 读取上次保存的位姿文件
     * 返回 [x, y, theta]，若文件不存在或读取失败则返回 [0, 0, 0]
     */
    private double[] loadSavedPose(String mapName) {
        File f = new File(MAP_DIR + mapName + POSE_FILE_SUFFIX);
        if (!f.exists()) {
            log.info("[位姿恢复] 无历史记录，使用默认位姿 (0, 0, 0)");
            return new double[]{0.0, 0.0, 0.0};
        }
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            double x     = parseJsonDouble(json, "x");
            double y     = parseJsonDouble(json, "y");
            double theta = parseJsonDouble(json, "theta");
            log.info("[位姿恢复] 读取成功: x={}, y={}, theta={}", x, y, theta);
            return new double[]{x, y, theta};
        } catch (Exception e) {
            log.warn("[位姿恢复] 读取失败，使用默认位姿 (0, 0, 0): {}", e.getMessage());
            return new double[]{0.0, 0.0, 0.0};
        }
    }

    /**
     * 将当前位姿保存到文件
     */
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

    /**
     * 启动后台定时任务：每 5 秒读取 AMCL 位姿并持久化
     * 机器人运行中自动保存，断电/重启后自动恢复，无需手动设置初始位置
     */
    private void startPoseAutoSave(String mapName) {
        if (poseAutoSaveScheduler != null && !poseAutoSaveScheduler.isShutdown()) {
            poseAutoSaveScheduler.shutdownNow();
        }
        poseAutoSaveScheduler = Executors.newSingleThreadScheduledExecutor();
        final String finalMapName = mapName;
        poseAutoSaveScheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try {
                    // 读取 AMCL 当前位姿（取一帧）
                    String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                            + " && timeout 3 ros2 topic echo /amcl_pose"
                            + " --field pose.pose --once 2>&1";
                    String out = runCommand(cmd, 4);
                    if (out.contains("position")) {
                        double x     = parseRosField(out, "x");
                        double y     = parseRosField(out, "y");
                        double theta = parseYawFromQuaternion(out);
                        savePose(finalMapName, x, y, theta);
                        log.debug("[位姿自动保存] x={:.4f}, y={:.4f}, theta={:.4f}", x, y, theta);
                    }
                } catch (Exception e) {
                    log.debug("[位姿自动保存] 跳过: {}", e.getMessage());
                }
            }
        }, 0, 5, TimeUnit.SECONDS);

        log.info("✅ 位姿自动保存已启动，每5秒保存到: {}{}{}",
                MAP_DIR, mapName, POSE_FILE_SUFFIX);
    }

    /**
     * 停止位姿自动保存（stopNav2 时调用）
     */
    private void stopPoseAutoSave() {
        if (poseAutoSaveScheduler != null && !poseAutoSaveScheduler.isShutdown()) {
            poseAutoSaveScheduler.shutdownNow();
            poseAutoSaveScheduler = null;
            log.info("⏹ 位姿自动保存已停止");
        }
    }

    // ===================== 生成 nav2_params.yaml =====================

    /**
     * 将 nav2 参数写入文件
     * initX/Y/Theta：AMCL 初始位姿（从历史文件读取，首次默认 0,0,0）
     * set_initial_pose 始终为 true，AMCL 激活后立即在此位置附近撒粒子并发布 map TF
     */
    private void writeNav2Params(String filePath,
                                 double initX, double initY, double initTheta) throws IOException {
        String content = "# Nav2 参数 - 由 Java 自动生成，适配 fast_lio\n"
                + "# 生成时间: " + new java.util.Date() + "\n"
                + "# 修改方式: 修改 MapController.java 中的 writeNav2Params() 方法\n"
                + "\n"
                + "amcl:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    alpha1: 0.2\n"
                + "    alpha2: 0.2\n"
                + "    alpha3: 0.2\n"
                + "    alpha4: 0.2\n"
                + "    alpha5: 0.2\n"
                + "    base_frame_id: \"" + BASE_FRAME + "\"\n"
                + "    global_frame_id: \"map\"\n"
                + "    odom_frame_id: \"" + ODOM_FRAME + "\"\n"
                + "    scan_topic: /scan\n"
                + "    laser_model_type: likelihood_field\n"
                + "    laser_max_range: 30.0\n"
                + "    laser_min_range: -1.0\n"
                + "    resample_interval: 1\n"
                + "    max_beams: 360\n"
                + "    max_particles: 5000\n"
                + "    min_particles: 1000\n"
                + "    robot_model_type: nav2_amcl::DifferentialMotionModel\n"
                + "    transform_tolerance: 1.0\n"
                + "    update_min_a: 0.0\n"
                + "    update_min_d: 0.0\n"
                + "    tf_broadcast: true\n"
                // set_initial_pose 始终 true：
                //   AMCL 激活后立即在 initial_pose 附近撒粒子，无需等待 /initialpose 消息
                //   map→camera_init TF 在收到第一帧 /scan 后立刻可用
                //   首次使用 (0,0,0) 时粒子在地图原点附近，机器人稍微移动即可收敛
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
                + "    robot_base_frame: " + BASE_FRAME + "\n"
                + "    odom_topic: " + ODOM_TOPIC + "\n"
                + "    bt_loop_duration: 10\n"
                + "    default_server_timeout: 20\n"
                + "    navigators: ['navigate_to_pose', 'navigate_through_poses']\n"
                + "    navigate_to_pose:\n"
                + "      plugin: nav2_bt_navigator/NavigateToPoseNavigator\n"
                + "    navigate_through_poses:\n"
                + "      plugin: nav2_bt_navigator/NavigateThroughPosesNavigator\n"
                + "\n"
                + "controller_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    controller_frequency: 20.0\n"
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
                + "      xy_goal_tolerance: 0.25\n"
                + "      yaw_goal_tolerance: 0.25\n"
                + "    FollowPath:\n"
                + "      plugin: nav2_regulated_pure_pursuit_controller::RegulatedPurePursuitController\n"
                + "      desired_linear_vel: 0.3\n"
                + "      lookahead_dist: 0.6\n"
                + "      min_lookahead_dist: 0.3\n"
                + "      max_lookahead_dist: 0.9\n"
                + "      rotate_to_heading_angular_vel: 1.8\n"
                + "      transform_tolerance: 0.1\n"
                + "      use_velocity_scaled_lookahead_dist: false\n"
                + "      use_regulated_linear_velocity_scaling: true\n"
                + "      use_cost_regulated_linear_velocity_scaling: false\n"
                + "      use_rotate_to_heading: true\n"
                + "      allow_reversing: false\n"
                + "      rotate_to_heading_min_angle: 0.785\n"
                + "      max_angular_accel: 3.2\n"
                + "\n"
                + "local_costmap:\n"
                + "  local_costmap:\n"
                + "    ros__parameters:\n"
                + "      use_sim_time: false\n"
                + "      update_frequency: 5.0\n"
                + "      publish_frequency: 2.0\n"
                + "      global_frame: " + ODOM_FRAME + "\n"
                + "      robot_base_frame: " + BASE_FRAME + "\n"
                + "      rolling_window: true\n"
                + "      width: 3\n"
                + "      height: 3\n"
                + "      resolution: 0.05\n"
                + "      robot_radius: " + ROBOT_RADIUS + "\n"
                + "      plugins: [obstacle_layer, inflation_layer]\n"
                + "      obstacle_layer:\n"
                + "        plugin: nav2_costmap_2d::ObstacleLayer\n"
                + "        enabled: True\n"
                + "        observation_sources: scan\n"
                + "        scan:\n"
                + "          topic: /scan\n"
                + "          max_obstacle_height: 2.0\n"
                + "          clearing: True\n"
                + "          marking: True\n"
                + "          data_type: LaserScan\n"
                + "          raytrace_max_range: 8.0\n"
                + "          obstacle_max_range: 5.5\n"
                + "      inflation_layer:\n"
                + "        plugin: nav2_costmap_2d::InflationLayer\n"
                + "        cost_scaling_factor: 3.0\n"
                + "        inflation_radius: " + INFLATE_R + "\n"
                + "      always_send_full_costmap: True\n"
                + "\n"
                + "global_costmap:\n"
                + "  global_costmap:\n"
                + "    ros__parameters:\n"
                + "      use_sim_time: false\n"
                + "      update_frequency: 1.0\n"
                + "      publish_frequency: 1.0\n"
                + "      global_frame: map\n"
                + "      robot_base_frame: " + BASE_FRAME + "\n"
                + "      robot_radius: " + ROBOT_RADIUS + "\n"
                + "      resolution: 0.05\n"
                + "      track_unknown_space: true\n"
                + "      plugins: [static_layer, obstacle_layer, inflation_layer]\n"
                + "      static_layer:\n"
                + "        plugin: nav2_costmap_2d::StaticLayer\n"
                + "        map_subscribe_transient_local: True\n"
                + "      obstacle_layer:\n"
                + "        plugin: nav2_costmap_2d::ObstacleLayer\n"
                + "        enabled: True\n"
                + "        observation_sources: scan\n"
                + "        scan:\n"
                + "          topic: /scan\n"
                + "          max_obstacle_height: 2.0\n"
                + "          clearing: True\n"
                + "          marking: True\n"
                + "          data_type: LaserScan\n"
                + "          raytrace_max_range: 8.0\n"
                + "          obstacle_max_range: 5.5\n"
                + "      inflation_layer:\n"
                + "        plugin: nav2_costmap_2d::InflationLayer\n"
                + "        cost_scaling_factor: 3.0\n"
                + "        inflation_radius: " + INFLATE_R + "\n"
                + "      always_send_full_costmap: True\n"
                + "\n"
                + "map_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    yaml_filename: \"\"\n"
                + "\n"
                + "planner_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    planner_plugins: [GridBased]\n"
                + "    GridBased:\n"
                + "      plugin: nav2_navfn_planner/NavfnPlanner\n"
                + "      tolerance: 0.5\n"
                + "      use_astar: false\n"
                + "      allow_unknown: true\n"
                + "\n"
                + "behavior_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    costmap_topic: local_costmap/costmap_raw\n"
                + "    footprint_topic: local_costmap/published_footprint\n"
                + "    cycle_frequency: 10.0\n"
                + "    behavior_plugins: [spin, backup, drive_on_heading, wait]\n"
                + "    spin:\n"
                + "      plugin: nav2_behaviors/Spin\n"
                + "    backup:\n"
                + "      plugin: nav2_behaviors/BackUp\n"
                + "    drive_on_heading:\n"
                + "      plugin: nav2_behaviors/DriveOnHeading\n"
                + "    wait:\n"
                + "      plugin: nav2_behaviors/Wait\n"
                + "    global_frame: " + ODOM_FRAME + "\n"
                + "    robot_base_frame: " + BASE_FRAME + "\n"
                + "    transform_tolerance: 0.1\n"
                + "    simulate_ahead_time: 2.0\n"
                + "    max_rotational_vel: 1.0\n"
                + "    min_rotational_vel: 0.4\n"
                + "    rotational_acc_lim: 3.2\n"
                + "\n"
                + "velocity_smoother:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    smoothing_frequency: 20.0\n"
                + "    scale_velocities: False\n"
                + "    feedback: OPEN_LOOP\n"
                + "    max_velocity: [0.5, 0.0, 2.0]\n"
                + "    min_velocity: [-0.5, 0.0, -2.0]\n"
                + "    max_accel: [2.5, 0.0, 3.2]\n"
                + "    max_decel: [-2.5, 0.0, -3.2]\n"
                + "    odom_topic: \"" + ODOM_TOPIC + "\"\n"
                + "    odom_duration: 0.1\n"
                + "    deadband_velocity: [0.0, 0.0, 0.0]\n"
                + "    velocity_timeout: 1.0\n"
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
                + "smoother_server:\n"
                + "  ros__parameters:\n"
                + "    use_sim_time: false\n"
                + "    smoother_plugins: [simple_smoother]\n"
                + "    simple_smoother:\n"
                + "      plugin: nav2_smoother::SimpleSmoother\n"
                + "      tolerance: 1.0e-10\n"
                + "      max_its: 1000\n"
                + "      do_refinement: True\n";

        File f = new File(filePath);
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    // ===================== 停止 Nav2 =====================

    @PostMapping("/stop-nav2")
    @ApiOperation("停止全部导航进程")
    public Result<Void> stopNav2() {
        stopNav2Internal();
        loadedMapName = null;
        return Result.OK("Nav2已停止");
    }

    private void stopNav2Internal() {
        stopPoseAutoSave();                          // 先停位姿保存
        killProcess(nav2Process,    "nav2");
        killProcess(pc2scanProcess, "pc2scan");
        nav2Process    = null;
        pc2scanProcess = null;
    }

    private void killProcess(Process p, String name) {
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try { p.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (p.isAlive()) p.destroyForcibly();
        log.info("⏹ [{}] 已停止", name);
    }

    // ===================== Nav2 状态 =====================

    @GetMapping("/nav2-status")
    @ApiOperation("查询Nav2运行状态")
    public Result<Map<String, Object>> getNav2Status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nav2Running",      nav2Process    != null && nav2Process.isAlive());
        s.put("pc2scanRunning",   pc2scanProcess != null && pc2scanProcess.isAlive());
        s.put("loadedMap",        loadedMapName);
        s.put("poseSaving",       poseAutoSaveScheduler != null && !poseAutoSaveScheduler.isShutdown());
        return Result.OK(s);
    }

    // ===================== Nav2 健康自检 =====================

    private volatile Map<String, Object> lastHealthCheck = null;

    @GetMapping("/nav2-health")
    @ApiOperation("查询Nav2健康状态（AMCL定位/TF/scan）")
    public Result<Map<String, Object>> getNav2Health() {
        if (lastHealthCheck == null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("status",  "pending");
            pending.put("message", "尚未执行检测，请先调用 /api/map/load");
            return Result.OK(pending);
        }
        return Result.OK(lastHealthCheck);
    }

    private void scheduleHealthCheck(String mapName) {
        final String mName = mapName;
        executor.submit(new Runnable() {
            public void run() {
                try {
                    log.info("[健康检查] 等待 Nav2 完全就绪（20秒）...");
                    Thread.sleep(20000);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("mapName",   mName);
                    result.put("checkTime", new java.util.Date().toString());

                    // ① fast_lio TF：camera_init → body（最基础依赖）
                    boolean fastLioTfOk = checkTfAvailable(ODOM_FRAME, BASE_FRAME, 5);
                    result.put("fastLioTfAvailable", fastLioTfOk);
                    if (!fastLioTfOk) {
                        result.put("fastLioWarn",
                                "⚠ " + ODOM_FRAME + "→" + BASE_FRAME + " TF 不可用！"
                                        + "fast_lio 可能未运行，这是导航失败的根本原因："
                                        + "无 fast_lio → 无 /scan → AMCL 无法定位 → map 帧不存在");
                    }

                    // ② /scan 话题是否在发布
                    boolean scanOk = checkTopicActive("/scan", 5);
                    result.put("scanActive", scanOk);
                    result.put("scanTopic",  CLOUD_TOPIC + " → /scan");
                    if (!scanOk) {
                        result.put("scanWarn",
                                "⚠ /scan 无数据。若 fast_lio TF 正常，请确认已安装: "
                                        + "apt install ros-humble-pointcloud-to-laserscan");
                    }

                    // ③ AMCL 节点是否存在
                    boolean amclOk = checkNodeExists("amcl", 5);
                    result.put("amclRunning", amclOk);
                    if (!amclOk) {
                        result.put("amclWarn", "⚠ AMCL 节点未找到，bringup_launch.py 可能未正常加载 AMCL");
                    }

                    // ④ map → ODOM_FRAME TF（最关键，AMCL 定位结果）
                    boolean tfOk = checkTfAvailable("map", ODOM_FRAME, 8);
                    result.put("mapTfAvailable", tfOk);
                    result.put("mapTfPath",      "map → " + ODOM_FRAME + " → " + BASE_FRAME);
                    if (!tfOk) {
                        result.put("tfWarn",
                                "⚠ map→" + ODOM_FRAME + " TF 不可用。"
                                        + "AMCL 未收到 /scan 数据，或初始位置偏差过大。"
                                        + "可调用 POST /api/navigation/global-localization 触发全局重定位");
                    }

                    // ⑤ 当前位姿文件
                    File poseFile = new File(MAP_DIR + mName + POSE_FILE_SUFFIX);
                    result.put("poseFileSaved", poseFile.exists());
                    if (poseFile.exists()) {
                        result.put("poseFile", MAP_DIR + mName + POSE_FILE_SUFFIX);
                    }

                    boolean allOk = fastLioTfOk && scanOk && amclOk && tfOk;
                    result.put("status",  allOk ? "healthy" : (amclOk ? "degraded" : "error"));
                    result.put("message", allOk
                            ? "✅ Nav2 完全就绪，可以设置导航目标"
                            : (!fastLioTfOk ? "❌ fast_lio 未运行，请先启动建图节点"
                            : (!tfOk ? "❌ map TF 未就绪，请查看各警告项"
                            : "⚠ 部分服务异常，但导航 TF 已就绪")));

                    lastHealthCheck = result;
                    log.info("[健康检查] 完成: fastLioTf={}, scan={}, amcl={}, mapTf={}",
                            fastLioTfOk, scanOk, amclOk, tfOk);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("[健康检查] 异常", e);
                }
            }
        });
    }

    // ===================== ROS2 检测工具 =====================

    private boolean checkTopicActive(String topic, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec + " ros2 topic hz " + topic
                    + " --window 3 2>&1 | head -5";
            String output = runCommand(cmd, timeoutSec + 2);
            boolean ok = output.contains("average rate") || output.contains("hz");
            log.info("[健康检查] {} 话题: {}", topic, ok ? "✅ 活跃" : "❌ 无数据");
            return ok;
        } catch (Exception e) {
            log.warn("[健康检查] 检测 {} 失败: {}", topic, e.getMessage());
            return false;
        }
    }

    private boolean checkNodeExists(String nodeName, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 node list 2>&1";
            String output = runCommand(cmd, timeoutSec);
            boolean ok = output.contains(nodeName);
            log.info("[健康检查] {} 节点: {}", nodeName, ok ? "✅ 存在" : "❌ 未找到");
            return ok;
        } catch (Exception e) {
            log.warn("[健康检查] 检测节点 {} 失败: {}", nodeName, e.getMessage());
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
            boolean ok = output.contains("Translation") || output.contains("Rotation");
            log.info("[健康检查] TF {} → {}: {}", parentFrame, childFrame,
                    ok ? "✅ 可用" : "❌ 不可用 | " + output.replace("\n", " | ").trim());
            return ok;
        } catch (Exception e) {
            log.warn("[健康检查] 检测 TF {}→{} 失败: {}", parentFrame, childFrame, e.getMessage());
            return false;
        }
    }

    private String runCommand(String cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.environment().put("QT_QPA_PLATFORM", "offscreen");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (p.isAlive()) p.destroyForcibly();
        return sb.toString();
    }

    // ===================== 解析工具方法 =====================

    /** 从 JSON 字符串中解析指定 key 的 double 值 */
    private double parseJsonDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*([\\-0-9.eE]+)").matcher(json);
        if (m.find()) return Double.parseDouble(m.group(1));
        throw new IllegalArgumentException("JSON key not found: " + key);
    }

    /**
     * 从 ros2 topic echo 输出中解析指定字段值
     * 取最后一个匹配，避免嵌套结构取错层
     */
    private double parseRosField(String out, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*([\\-0-9.eE]+)").matcher(out);
        double val = 0;
        while (m.find()) val = Double.parseDouble(m.group(1));
        return val;
    }

    /**
     * 从四元数 z/w 分量解析 yaw（仅 2D 平面导航，roll=pitch=0）
     * yaw = 2 * atan2(qz, qw)
     */
    private double parseYawFromQuaternion(String out) {
        try {
            Matcher m = Pattern.compile(
                    "orientation[\\s\\S]*?z:\\s*([\\-0-9.eE]+)[\\s\\S]*?w:\\s*([\\-0-9.eE]+)"
            ).matcher(out);
            if (m.find()) {
                double qz = Double.parseDouble(m.group(1));
                double qw = Double.parseDouble(m.group(2));
                return 2.0 * Math.atan2(qz, qw);
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    // ===================== 地图列表 =====================

    @GetMapping("/list")
    @ApiOperation("获取已保存地图列表")
    public Result<List<Map<String, Object>>> getMapList() {
        try {
            File dir = new File(MAP_DIR);
            if (!dir.exists()) return Result.OK(new ArrayList<Map<String, Object>>());
            File[] files = dir.listFiles(new FilenameFilter() {
                public boolean accept(File d, String n) { return n.endsWith(".yaml"); }
            });
            if (files == null) return Result.OK(new ArrayList<Map<String, Object>>());
            List<Map<String, Object>> list = new ArrayList<>();
            for (File f : files) {
                if (f.getName().startsWith("nav2_params")) continue;
                String name = f.getName().replace(".yaml", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",          name);
                m.put("createTime",    f.lastModified());
                m.put("hasImage",      new File(MAP_DIR + name + ".pgm").exists());
                m.put("loaded",        name.equals(loadedMapName));
                m.put("hasSavedPose",  new File(MAP_DIR + name + POSE_FILE_SUFFIX).exists());
                list.add(m);
            }
            return Result.OK(list);
        } catch (Exception e) {
            log.error("获取地图列表失败", e);
            return Result.error("获取列表失败: " + e.getMessage());
        }
    }

    // ===================== 地图图像（pgm → png） =====================

    @GetMapping("/image/{mapName}")
    @ApiOperation("获取地图图像（PNG格式）")
    public ResponseEntity<byte[]> getMapImage(@PathVariable String mapName) {
        try {
            File pngFile = new File(MAP_DIR + mapName + ".png");
            if (pngFile.exists()) {
                return new ResponseEntity<>(Files.readAllBytes(pngFile.toPath()), pngHeaders(), HttpStatus.OK);
            }
            File pgmFile = new File(MAP_DIR + mapName + ".pgm");
            if (!pgmFile.exists()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            BufferedImage img   = readPgm(pgmFile);
            byte[]        bytes = toPng(img);
            try { Files.write(pngFile.toPath(), bytes); } catch (Exception ignored) {}
            log.info("地图图像已转换: {} ({}x{})", mapName, img.getWidth(), img.getHeight());
            return new ResponseEntity<>(bytes, pngHeaders(), HttpStatus.OK);
        } catch (Exception e) {
            log.error("获取地图图像失败: {}", mapName, e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===================== 地图元数据 =====================

    @GetMapping("/meta/{mapName}")
    @ApiOperation("获取地图元数据（分辨率+原点+尺寸）")
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
                        meta.put("resolution", Double.parseDouble(line.substring("resolution:".length()).trim()));
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

            // 附带最近一次保存的位姿信息
            File poseFile = new File(MAP_DIR + mapName + POSE_FILE_SUFFIX);
            if (poseFile.exists()) {
                try {
                    String json = new String(Files.readAllBytes(poseFile.toPath()), StandardCharsets.UTF_8);
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
    @ApiOperation("删除地图（yaml + pgm + png + 位姿文件）")
    public Result<Void> deleteMap(@PathVariable String mapName) {
        try {
            boolean deleted = false;
            for (String ext : new String[]{".yaml", ".pgm", ".png", POSE_FILE_SUFFIX}) {
                File f = new File(MAP_DIR + mapName + ext);
                if (f.exists()) deleted |= f.delete();
            }
            if (deleted) {
                if (mapName.equals(loadedMapName)) { stopNav2Internal(); loadedMapName = null; }
                return Result.OK("地图已删除");
            }
            return Result.error("地图文件不存在: " + mapName);
        } catch (Exception e) {
            log.error("删除地图失败", e);
            return Result.error("删除失败: " + e.getMessage());
        }
    }

    // ===================== 工具方法 =====================

    private Process startProcess(String name, String cmd) throws IOException {
        log.info("▶ 启动 [{}]", name);
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
        pb.directory(new File(System.getProperty("user.home")));
        pb.environment().put("QT_QPA_PLATFORM", "offscreen");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        final String pName = name;
        executor.submit(new Runnable() {
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) log.info("[{}] {}", pName, line);
                } catch (IOException ignored) {}
            }
        });
        log.info("✅ [{}] 已启动", name);
        return p;
    }

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
            log.info("PGM 读取完成: {}x{}", w, h);
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
        while ((b = bis.read()) != -1 && b != '\n') { if (b != '\r') sb.append((char) b); }
        return sb.toString().trim();
    }
}