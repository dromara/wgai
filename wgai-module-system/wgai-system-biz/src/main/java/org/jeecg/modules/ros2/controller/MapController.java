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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 地图管理控制器 - 适配 fast_lio + Unitree L1
 *
 * 接口：
 *   POST   /api/map/load          加载地图 + 自动写 nav2_params + 启动全部进程
 *   POST   /api/map/stop-nav2     停止全部导航进程
 *   GET    /api/map/nav2-status   查询运行状态
 *   GET    /api/map/list          地图列表
 *   GET    /api/map/image/{name}  pgm→png 图像
 *   GET    /api/map/meta/{name}   分辨率/原点/尺寸
 *   DELETE /api/map/{name}        删除地图
 *
 * loadMap 启动顺序：
 *   1. 写 nav2_params_fastlio.yaml（Java 直接生成，无需手动维护文件）
 *      ★ yaml 内直接写 fast_lio 真实帧名，Nav2 自动适配，不伪造 TF
 *   2. 点云转激光: /cloud_registered → /scan
 *   3. nav2_bringup（bringup_launch.py）— 包含 AMCL 定位 + 完整导航栈
 *      ★ 必须用 bringup_launch.py，navigation_launch.py 不含 AMCL，map 帧不存在
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

    /** nav2 params 文件生成路径（Java 自动写入，直接改代码内容即可） */
    private static final String NAV2_PARAMS_PATH = "/home/ros/nav2_params_fastlio.yaml";

    /** fast_lio 里程计父帧（camera_init 或 world） */
    private static final String ODOM_FRAME   = "camera_init";
    /** fast_lio 机器人帧（body 或 base_link） */
    private static final String BASE_FRAME   = "body";
    /** fast_lio 里程计话题 */
    private static final String ODOM_TOPIC   = "/Odometry";
    /** fast_lio 点云话题（转为 /scan） */
    private static final String CLOUD_TOPIC  = "/cloud_registered";
    /** 点云投影高度范围（去掉地面和天花板，只留障碍物） */
    private static final double SCAN_MIN_H   = 0.1;
    private static final double SCAN_MAX_H   = 1.5;
    /** 机器人半径（米） */
    private static final double ROBOT_RADIUS = 0.3;
    /** 膨胀半径（米） */
    private static final double INFLATE_R    = 0.55;

    // ===================== 进程管理 =====================

    private volatile Process pc2scanProcess   = null;  // 点云→激光扫描
    private volatile Process nav2Process      = null;  // nav2_bringup
    private volatile String  loadedMapName    = null;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ===================== 加载地图（核心） =====================

    @PostMapping("/load")
    @ApiOperation("加载地图并启动Nav2完整导航栈（适配fast_lio）")
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

            // 停止旧进程
            stopNav2Internal();
            Thread.sleep(500);

            // ─────────────────────────────────────────────────────────────
            // Step 1: 自动生成 nav2_params_fastlio.yaml
            //         直接在 Java 代码里维护内容，改代码即改配置，无需上机手动编辑
            // ─────────────────────────────────────────────────────────────
            writeNav2Params(NAV2_PARAMS_PATH);
            log.info("✅ nav2 params 已写入: {}", NAV2_PARAMS_PATH);

            // ─────────────────────────────────────────────────────────────
            // ✅ TF 说明（无需任何桥接）：
            //   nav2_params.yaml 已配置 odom_frame=camera_init, base_frame=body
            //   fast_lio 运行时自动发布 camera_init→body 动态 TF
            //   AMCL 定位后发布 map→camera_init
            //   完整 TF 链: map → camera_init → body  (全部由 ROS 节点自动维护)
            // ─────────────────────────────────────────────────────────────

            // ─────────────────────────────────────────────────────────────
            // Step 2: 点云 → 激光扫描 转换
            //   fast_lio 发布 PointCloud2，AMCL 和 costmap 需要 LaserScan
            //   安装: apt install ros-humble-pointcloud-to-laserscan
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
                    + " -p transform_tolerance:=0.01"
                    + " -p use_inf:=true";
            pc2scanProcess = startProcess("pc2scan", pc2scanCmd);
            Thread.sleep(1000);
            log.info("✅ 点云→激光: {} → /scan", CLOUD_TOPIC);

            // ─────────────────────────────────────────────────────────────
            // Step 3: 启动 Nav2 bringup（AMCL定位 + 完整导航栈）
            //         bringup_launch.py 内部自己会启动 map_server，无需单独启动
            //
            // ❌ navigation_launch.py — 只启动规划/控制，不包含 AMCL
            //                           map 帧无人发布 → "map does not exist"
            //
            // ✅ bringup_launch.py    — 同时启动 AMCL + 导航栈
            //                           AMCL 订阅 /scan 做粒子滤波定位
            //                           AMCL 发布 map → camera_init TF
            //                           完整 TF 链: map → camera_init → body ✓
            // ─────────────────────────────────────────────────────────────
            String nav2Cmd = "source " + ROS_BASH
                    + " && source " + SETUP_BASH
                    + " && ros2 launch nav2_bringup bringup_launch.py"
                    + " map:=" + yamlPath
                    + " use_sim_time:=false"
                    + " params_file:=" + NAV2_PARAMS_PATH;
            nav2Process   = startProcess("nav2", nav2Cmd);
            loadedMapName = mapName;

            log.info("✅ 全部启动完成 | 地图:{} | odomFrame:{} | baseFrame:{} | 激光:{}", mapName, ODOM_FRAME, BASE_FRAME, CLOUD_TOPIC);

            // ─────────────────────────────────────────────────────────────
            // Step 4: 异步自检（nav2 启动需要约 15~30 秒才完全就绪）
            //   检测项：/scan 话题 | amcl 节点 | map→camera_init TF
            //   结果写入 lastHealthCheck，可通过 /api/map/nav2-health 查询
            // ─────────────────────────────────────────────────────────────
            scheduleHealthCheck(mapName);
            executor.submit(() -> {
                try {
                    Thread.sleep(22000); // 等 Nav2 完全就绪
                    triggerGlobalLocalization();
                } catch (InterruptedException ignored) {}
            });
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("mapName",     mapName);
            res.put("yamlPath",    yamlPath);
            res.put("imageUrl",    "/api/map/image/" + mapName);
            res.put("nav2Started", true);
            res.put("paramsFile",  NAV2_PARAMS_PATH);
            res.put("scanConvert", CLOUD_TOPIC + " → /scan");
            res.put("odomFrame",   ODOM_FRAME);
            res.put("baseFrame",   BASE_FRAME);
            res.put("healthCheck", "异步检测中，约20秒后可查询 /api/map/nav2-health");
            return Result.OK(res);

        } catch (Exception e) {
            log.error("加载地图失败", e);
            return Result.error("加载失败: " + e.getMessage());
        }
    }

    /**
     * 触发 AMCL 全局自动定位
     * 原理：把粒子撒满整张地图 → AMCL 订阅 /scan → 粒子逐步收敛到真实位置
     * 机器人只要稍微移动或旋转，收敛速度会显著加快
     */
    private void triggerGlobalLocalization() {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 service call /reinitialize_global_localization"
                    + " std_srvs/srv/Empty '{}'";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 读完输出避免阻塞
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            p.waitFor(10, TimeUnit.SECONDS);
            log.info("✅ AMCL 全局定位已触发，粒子撒满地图，等待激光收敛...");
        } catch (Exception e) {
            log.warn("⚠️ 触发全局定位失败: {}", e.getMessage());
        }
    }

    // ===================== 生成 nav2_params.yaml =====================

    /**
     * 将 nav2 参数以 Java 字符串形式写入文件。
     * 所有 frame_id 已适配 fast_lio：
     *   odom_frame   = camera_init
     *   base_frame   = body
     *   odom_topic   = /Odometry
     * 需要调参时直接修改此方法即可，无需 SSH 到机器人手动编辑。
     */
    private void writeNav2Params(String filePath) throws IOException {
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
                + "    set_initial_pose: false\n"
//                + "    initial_pose:\n"
//                + "      x: 0.0\n"
//                + "      y: 0.0\n"
//                + "      z: 0.0\n"
//                + "      yaw: 0.0\n"
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

        // 确保目录存在
        File f = new File(filePath);
        if (!f.getParentFile().exists()) {
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
        // 按反序停止，先停高层再停底层
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
        s.put("nav2Running",    nav2Process    != null && nav2Process.isAlive());
        s.put("pc2scanRunning",   pc2scanProcess   != null && pc2scanProcess.isAlive());
        s.put("loadedMap",        loadedMapName);
        return Result.OK(s);
    }

    // ===================== Nav2 健康自检 =====================

    /** 最近一次健康检查结果（异步写入） */
    private volatile Map<String, Object> lastHealthCheck = null;

    /**
     * 查询 Nav2 启动后的健康状态
     * 在 loadMap 后约 20 秒调用此接口，确认 AMCL 是否真正定位成功
     */
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

    /**
     * 在 nav2 启动后异步延迟检测，写入 lastHealthCheck
     * 检测内容：
     *   1. /scan 话题是否在发布
     *   2. AMCL 节点是否存在
     *   3. map → ODOM_FRAME TF 是否可用（最关键）
     */
    private void scheduleHealthCheck(String mapName) {
        final String mName = mapName;
        executor.submit(new Runnable() {
            public void run() {
                try {
                    log.info("[健康检查] 等待 Nav2 完全就绪（20秒）...");
                    Thread.sleep(20000);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("mapName",    mName);
                    result.put("checkTime",  new java.util.Date().toString());

                    // ① 检测 /scan 话题是否在发布
                    boolean scanOk = checkTopicActive("/scan", 5);
                    result.put("scanActive", scanOk);
                    result.put("scanTopic",  CLOUD_TOPIC + " → /scan");
                    if (!scanOk) {
                        result.put("scanWarn", "⚠ /scan 未检测到数据，请确认 pointcloud_to_laserscan 已安装: apt install ros-humble-pointcloud-to-laserscan");
                    }

                    // ② 检测 AMCL 节点是否存在
                    boolean amclOk = checkNodeExists("amcl", 5);
                    result.put("amclRunning", amclOk);
                    if (!amclOk) {
                        result.put("amclWarn", "⚠ AMCL 节点未找到，bringup_launch.py 可能未正常加载 AMCL");
                    }

                    // ③ 检测 map → ODOM_FRAME TF 是否可用（最关键）
                    boolean tfOk = checkTfAvailable("map", ODOM_FRAME, 8);
                    result.put("mapTfAvailable", tfOk);
                    result.put("mapTfPath",      "map → " + ODOM_FRAME + " → " + BASE_FRAME);
                    if (!tfOk) {
                        result.put("tfWarn",
                                "⚠ map→" + ODOM_FRAME + " TF 不可用。可能原因： "
                                        + "  1. AMCL 未收到 /scan 数据（检查 scanActive）                               "
                                        + "  2. 机器人真实位置与地图偏差过大，AMCL 粒子发散                                "
                                        + "  3. 需要手动设置初始位置（在 RViz2 用 2D Pose Estimate）                                "
                                        + "  建议：移动机器人帮助 AMCL 收敛，或在 nav2_params 中调整 initial_pose"
                        );
                    }

                    // 综合判断
                    boolean allOk = scanOk && amclOk && tfOk;
                    result.put("status",  allOk ? "healthy" : (amclOk ? "degraded" : "error"));
                    result.put("message", allOk
                            ? "✅ Nav2 完全就绪，可以设置导航目标"
                            : (tfOk ? "⚠ 部分服务异常，但导航 TF 已就绪"
                            : "❌ map TF 未就绪，导航可能失败。请查看各警告项"));

                    lastHealthCheck = result;
                    log.info("[健康检查] 完成: scan={}, amcl={}, mapTf={}", scanOk, amclOk, tfOk);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("[健康检查] 异常", e);
                }
            }
        });
    }

    /** 检测 ROS2 话题是否活跃（能收到消息） */
    private boolean checkTopicActive(String topic, int timeoutSec) {
        try {
            // ros2 topic hz 输出中有 "average rate" 表示有数据
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec + " ros2 topic hz " + topic
                    + " --window 3 2>&1 | head -5";
            String output = runCommand(cmd, timeoutSec + 2);
            boolean ok = output.contains("average rate") || output.contains("hz");
            log.info("[健康检查] {} 话题: {}", topic, ok ? "✅ 活跃" : "❌ 无数据 | " + output.trim());
            return ok;
        } catch (Exception e) {
            log.warn("[健康检查] 检测 {} 失败: {}", topic, e.getMessage());
            return false;
        }
    }

    /** 检测 ROS2 节点是否存在 */
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

    /**
     * 检测两个坐标帧之间的 TF 变换是否可用
     * 用 tf2_echo 尝试获取一次变换，有输出则说明 TF 链通
     */
    private boolean checkTfAvailable(String parentFrame, String childFrame, int timeoutSec) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && timeout " + timeoutSec
                    + " ros2 run tf2_ros tf2_echo " + parentFrame + " " + childFrame
                    + " 2>&1 | head -10";
            String output = runCommand(cmd, timeoutSec + 2);
            // tf2_echo 成功时输出含 "Translation" 或 "Rotation"
            boolean ok = output.contains("Translation") || output.contains("Rotation");
            log.info("[健康检查] TF {} → {}: {}", parentFrame, childFrame,
                    ok ? "✅ 可用" : "❌ 不可用 | " + output.replace("\n", " | ").trim());
            return ok;
        } catch (Exception e) {
            log.warn("[健康检查] 检测 TF {}→{} 失败: {}", parentFrame, childFrame, e.getMessage());
            return false;
        }
    }

    /** 执行 shell 命令并返回输出字符串 */
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
                // 过滤掉 nav2_params 生成文件
                if (f.getName().startsWith("nav2_params")) continue;
                String name = f.getName().replace(".yaml", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",       name);
                m.put("createTime", f.lastModified());
                m.put("hasImage",   new File(MAP_DIR + name + ".pgm").exists());
                m.put("loaded",     name.equals(loadedMapName));
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
            BufferedImage img = readPgm(pgmFile);
            byte[] bytes = toPng(img);
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
            return Result.OK(meta);

        } catch (Exception e) {
            log.error("读取地图元数据失败: {}", mapName, e);
            return Result.error("读取元数据失败: " + e.getMessage());
        }
    }

    // ===================== 删除地图 =====================

    @DeleteMapping("/{mapName}")
    @ApiOperation("删除地图（yaml + pgm + png）")
    public Result<Void> deleteMap(@PathVariable String mapName) {
        try {
            boolean deleted = false;
            for (String ext : new String[]{".yaml", ".pgm", ".png"}) {
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

    private void activateLifecycle(String nodeName) {
        try {
            String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                    + " && ros2 lifecycle set /" + nodeName + " configure"
                    + " && sleep 1 && ros2 lifecycle set /" + nodeName + " activate";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            p.waitFor(10, TimeUnit.SECONDS);
            log.info("lifecycle 激活: {}", nodeName);
        } catch (Exception e) {
            log.warn("lifecycle 激活失败: {}", e.getMessage());
        }
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
                    int v = data[y*w+x] & 0xFF;
                    img.setRGB(x, y, (v<<16)|(v<<8)|v);
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
            return new int[]{ Integer.parseInt(d[0]), Integer.parseInt(d[1]) };
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
        while ((b = bis.read()) != -1 && b != '\n') { if (b != '\r') sb.append((char)b); }
        return sb.toString().trim();
    }
}