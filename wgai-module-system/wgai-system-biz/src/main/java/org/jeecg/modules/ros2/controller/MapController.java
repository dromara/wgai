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
import org.springframework.beans.factory.annotation.Autowired;
import com.google.gson.JsonObject;
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

    /** 位姿持久化文件后缀(每张地图独立) */
    private static final String POSE_FILE_SUFFIX = "_last_pose.json";

    /** launch 脚本启动时优先读取的软链接文件名 */
    private static final String LAST_USED_LINK = "last_used.yaml";

    /** fast_lio 里程计父帧 / 机器人帧 / 话题 */
    private static final String ODOM_FRAME   = "camera_init";
    private static final String BASE_FRAME   = "body";
    private static final String ODOM_TOPIC   = "/Odometry";
    private static final String CLOUD_TOPIC  = "/cloud_registered";

    /** 点云投影高度范围 */
    private static final double SCAN_MIN_H   = 0.1;
    private static final double SCAN_MAX_H   = 1.5;
    /** 机器人半径 / 膨胀半径(米) */
    private static final double ROBOT_RADIUS = 0.3;
    private static final double SELF_FILTER_RADIUS = 0.40;   // ① 机身自过滤距离(m)
    private static final double INFLATE            = 0.20;   // ③ 膨胀半径(m),原 INFLATE_R=0.3

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

        // 8s 足够;正常 rosbridge 长连接毫秒级返回。注意 load_map 响应会带回整张地图,
        // 已在 ROS2WebSocketHandler 里做了分片拼接,这里直接拿 values 即可。
        JsonObject values = ros2BridgeService.callService(
                "/map_server/load_map", "nav2_msgs/srv/LoadMap", args, 8000);
        if (values == null) {
            log.error("[load] load_map 超时/无响应");
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
    private void updateLastUsedSymlink(String mapName) {
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
            log.info("[isNav2Active] rosbridge 已连接但 AMCL 心跳超时(可能刚启动),暂认为 Nav2 活跃");
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

                boolean allOk = fastLioTfOk && scanOk && amclOk && tfOk;
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
            log.info("[位姿恢复] x={}, y={}, theta={}", x, y, theta);
            return new double[]{x, y, theta};
        } catch (Exception e) {
            log.warn("[位姿恢复] 读取失败,使用默认 (0,0,0): {}", e.getMessage());
            return new double[]{0.0, 0.0, 0.0};
        }
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
        poseAutoSaveScheduler.scheduleAtFixedRate(() -> {
            try {
                String cmd = "source " + ROS_BASH + " && source " + SETUP_BASH
                        + " && timeout 3 ros2 topic echo /amcl_pose"
                        + " --field pose.pose --once 2>&1";
                String out = runCommand(cmd, 4);
                if (out.contains("position")) {
                    double x     = parseRosField(out, "x");
                    double y     = parseRosField(out, "y");
                    double theta = parseYawFromQuaternion(out);
                    savePose(finalMapName, x, y, theta);
                }
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
     * Unitree L1 专项修复 (v2):
     *   ① obstacle_min_range / raytrace_min_range = 0.40m
     *      L1 机身宽约 0.35m,腿部扫描点距雷达中心 ≤ 0.4m。
     *      不设此值时,腿的点云被 obstacle_layer 实时标为障碍,
     *      导致机器人永远处于"四周被包围"状态,规划/恢复行为全部失败。
     *
     *   ② min_obstacle_height = 0.05m
     *      过滤地面近距离反射(脚垫/地面纹理),避免地面噪点进入代价地图。
     *
     *   ③ inflation_radius: 0.20m(原 0.3)
     *      降低膨胀半径,防止走廊/房间角落因膨胀叠加而完全封堵。
     *      配合 ① 的 self-filter 后,0.2m 已足够保护机身。
     */
    private void writeNav2Params(String filePath, double initX, double initY,
                                 double initTheta, String mapYamlPath) throws IOException {

        // Unitree L1 专用参数


        String content = "# Nav2 参数 - 由 Java MapController 生成,适配 fast_lio + Unitree L1\n"
                + "# 生成时间: " + new java.util.Date() + "\n"
                + "# 注意:修改后需重启 Nav2 才生效(systemctl restart robot-nav 或 重启 launch)\n"
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
                + "    max_beams: 360\n"
                + "    max_particles: 2000\n"
                + "    min_particles: 500\n"
                + "    robot_model_type: nav2_amcl::DifferentialMotionModel\n"
                + "    transform_tolerance: 1.0\n"
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
                + "          data_type: LaserScan\n"
                + "          min_obstacle_height: 0.05\n"         // ② 过滤地面反射
                + "          max_obstacle_height: 2.0\n"
                // ① 核心修复：过滤宇树L1腿部点云（腿展开最宽约0.4m）
                + "          obstacle_min_range: " + String.format("%.2f", SELF_FILTER_RADIUS) + "\n"
                + "          obstacle_max_range: 5.5\n"
                + "          raytrace_min_range: " + String.format("%.2f", SELF_FILTER_RADIUS) + "\n"
                + "          raytrace_max_range: 8.0\n"
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
                + "          data_type: LaserScan\n"
                + "          min_obstacle_height: 0.05\n"         // ② 过滤地面反射
                + "          max_obstacle_height: 2.0\n"
                // ① 核心修复：同局部代价地图，全局也要过滤腿部
                + "          obstacle_min_range: " + String.format("%.2f", SELF_FILTER_RADIUS) + "\n"
                + "          obstacle_max_range: 5.5\n"
                + "          raytrace_min_range: " + String.format("%.2f", SELF_FILTER_RADIUS) + "\n"
                + "          raytrace_max_range: 8.0\n"
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
                + "    transform_tolerance: 0.5\n"
                + "    simulate_ahead_time: 2.0\n"
                + "    max_rotational_vel: 1.0\n"
                + "    min_rotational_vel: 0.4\n"
                + "    rotational_acc_lim: 3.2\n"
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

    private double parseRosField(String out, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*([\\-0-9.eE]+)").matcher(out);
        double val = 0;
        while (m.find()) val = Double.parseDouble(m.group(1));
        return val;
    }

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
            for (String ext : new String[]{".yaml", ".pgm", ".png", POSE_FILE_SUFFIX}) {
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