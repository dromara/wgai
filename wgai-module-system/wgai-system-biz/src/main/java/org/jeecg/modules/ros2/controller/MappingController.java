package org.jeecg.modules.ros2.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.ros2.service.ROS2BridgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 建图控制器（Java 8 兼容版）
 *
 * 关键原理：
 *   fast_lio 只有收到 SIGINT（Ctrl+C）才会触发 PCD 保存
 *   process.destroy()         → SIGTERM → fast_lio 直接退出，不保存 PCD ❌
 *   pkill -INT -f fastlio_mapping → SIGINT  → fast_lio 保存 PCD 再退出  ✅
 *
 * 不使用 process.pid()（Java 9+），改用 pkill 按进程名发信号，兼容 Java 8
 */
@Slf4j
@RestController
@RequestMapping("/ros2/mapping")
@Api(tags = "建图控制")
public class MappingController {

    private static final String PCD_PATH   = "/home/lio_ws/src/FAST_LIO/PCD/scans.pcd";
    private static final String MAPS_DIR   = "/home/ros/maps";
    private static final String SETUP_BASH = "/home/lio_ws/install/setup.bash";

    // fast_lio 可执行文件名（pkill 用）
    private static final String FASTLIO_PROCESS_NAME = "fastlio_mapping";

    private volatile Process fastlioProcess = null;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Autowired
    ROS2BridgeService ros2BridgeService;

    // ==================== 启动建图 ====================
    @PostMapping("/connection")
    @ApiOperation("启动 fast_lio 建图")
    public Result<Map<String, Object>> connection(){
        if(ros2BridgeService.isConnected()==false){
                ros2BridgeService.connect();
        }
        return Result.OK("开始连接");
    }
    @PostMapping("/start")
    @ApiOperation("启动 fast_lio 建图")
    public Result<Map<String, Object>> startMapping(
            @RequestBody(required = false) Map<String, String> body) {

        if(ros2BridgeService.isConnected()==false){
            ros2BridgeService.connect();
        }

        if (fastlioProcess != null && fastlioProcess.isAlive()) {
            return Result.error("fast_lio 已在运行中");
        }

        if (body == null) body = new HashMap<>();
        String configFile = body.getOrDefault("configFile", "unitree_l1.yaml");

        String cmd = "source " + SETUP_BASH
                + " && ros2 launch fast_lio mapping.launch.py"
                + " config_file:=" + configFile
                + " launch_rviz:=false";

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.directory(new File(System.getProperty("user.home")));
            pb.redirectErrorStream(true);
            pb.environment().put("QT_QPA_PLATFORM", "offscreen");

            fastlioProcess = pb.start();
            // ⭐ 进入建图模式,前端 robot_pose 改用 /Odometry 来源
            ros2BridgeService.setNavMode(false);

            // ✅ Java 8 兼容：用反射获取 PID（仅用于日志，不做关键逻辑）
            long pid = getProcessPid(fastlioProcess);
            log.info("✅ fast_lio 已启动，PID: {}", pid > 0 ? pid : "未知");

            // 异步读取日志（不阻塞HTTP线程）
            final Process proc = fastlioProcess;
            executor.submit(new Runnable() {
                public void run() {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[fast_lio] {}", line);
                        }
                    } catch (IOException ignored) {}
                }
            });

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("pid",        pid > 0 ? pid : "N/A");
            res.put("configFile", configFile);
            res.put("pcdPath",    PCD_PATH);
            return Result.OK(res);

        } catch (IOException e) {
            log.error("启动 fast_lio 失败", e);
            return Result.error("启动失败: " + e.getMessage());
        }
    }

    // ==================== 停止建图（SIGINT → 触发 PCD 保存） ====================

    @PostMapping("/stop")
    @ApiOperation("停止建图（发SIGINT触发PCD保存，Java8兼容）")
    public Result<Map<String, Object>> stopMapping() {
        if (fastlioProcess == null || !fastlioProcess.isAlive()) {
            return Result.error("fast_lio 未在运行");
        }

        try {
            log.info("⏹ 发送 SIGINT 到 fast_lio（等价于 Ctrl+C）...");

            // ✅ 方案：pkill -INT -f fastlio_mapping
            //    按进程名发 SIGINT，不需要 PID，Java 8 完全兼容
            //    pkill -INT：发送 SIGINT 信号
            //    -f：按完整命令行匹配，不只是进程名
            sendSigInt();

            // 等待 fast_lio 保存 PCD 并退出（最多等 15 秒）
            boolean exited = fastlioProcess.waitFor(15, TimeUnit.SECONDS);

            if (!exited) {
                log.warn("fast_lio 15秒未退出，强制终止（PCD可能未完整保存）");
                fastlioProcess.destroyForcibly();
                fastlioProcess.waitFor(3, TimeUnit.SECONDS);
            } else {
                log.info("✅ fast_lio 正常退出，PCD 已保存到: {}", PCD_PATH);
            }

            fastlioProcess = null;

            // 检查 PCD 文件
            File pcdFile   = new File(PCD_PATH);
            boolean exists = pcdFile.exists() && pcdFile.length() > 0;
            long    sizeKB = exists ? pcdFile.length() / 1024 : 0;

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("stopped",   true);
            res.put("pcdExists", exists);
            res.put("pcdPath",   PCD_PATH);
            res.put("pcdSizeKB", sizeKB);
            res.put("message",   exists
                    ? "PCD 已保存（" + sizeKB + " KB），可以转换为导航地图"
                    : "进程已停止，PCD 可能仍在写入，请轮询 /pcd-status");
            return Result.OK(res);

        } catch (Exception e) {
            log.error("停止 fast_lio 失败", e);
            if (fastlioProcess != null) {
                fastlioProcess.destroyForcibly();
                fastlioProcess = null;
            }
            return Result.error("停止失败: " + e.getMessage());
        }
    }

    /**
     * 发送 SIGINT 到 fast_lio 进程
     * 优先用 pkill（按名称），备用反射获取PID再 kill -2
     */
    private void sendSigInt() throws Exception {
        // 方法一：pkill -INT（推荐，最简单）
        Process pkill = new ProcessBuilder("bash", "-c",
                "pkill -INT -f " + FASTLIO_PROCESS_NAME)
                .redirectErrorStream(true)
                .start();
        pkill.waitFor(3, TimeUnit.SECONDS);
        log.info("pkill -INT -f {} 执行完成，退出码: {}", FASTLIO_PROCESS_NAME, pkill.exitValue());

        // 备用方法：通过反射获取 PID 再 kill -2
        if (pkill.exitValue() != 0) {
            long pid = getProcessPid(fastlioProcess);
            if (pid > 0) {
                log.info("pkill 失败，改用 kill -2 {}", pid);
                new ProcessBuilder("bash", "-c", "kill -2 " + pid)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * ✅ Java 8 兼容：通过反射获取 Process 的 PID
     * - Unix/Linux：UnixProcess 有 int pid 字段
     * - Java 9+：优先调用 process.pid()（通过反射避免编译报错）
     */
    private long getProcessPid(Process process) {
        if (process == null) return -1;

        // 方法1：Java 9+ 的 process.pid()，通过反射调用避免编译错误
        try {
            java.lang.reflect.Method pidMethod = process.getClass().getMethod("pid");
            Object result = pidMethod.invoke(process);
            if (result instanceof Long) return (Long) result;
        } catch (NoSuchMethodException e) {
            // Java 8，正常走下面的路径
        } catch (Exception e) {
            log.debug("process.pid() 反射调用失败: {}", e.getMessage());
        }

        // 方法2：Java 8 在 Linux 上，UNIXProcess 有 pid 字段
        try {
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            Object pidValue = pidField.get(process);
            if (pidValue instanceof Integer) return ((Integer) pidValue).longValue();
            if (pidValue instanceof Long)    return (Long) pidValue;
        } catch (Exception e) {
            log.debug("反射获取 pid 字段失败: {}", e.getMessage());
        }

        // 方法3：通过 /proc/self 查找子进程（兜底）
        try {
            String output = runCommand("ps -o pid,ppid --no-headers -e");
            long selfPid  = getSelfPid();
            for (String line : output.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        long childPid  = Long.parseLong(parts[0]);
                        long parentPid = Long.parseLong(parts[1]);
                        if (parentPid == selfPid) return childPid;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            log.debug("ps 方式获取PID失败: {}", e.getMessage());
        }

        return -1;
    }

    private long getSelfPid() {
        // Java 8：从 /proc/self 读取
        try {
            Path self = Paths.get("/proc/self");
            String resolved = Files.readSymbolicLink(self).toString();
            return Long.parseLong(resolved);
        } catch (Exception e) {
            return -1;
        }
    }

    private String runCommand(String cmd) throws Exception {
        Process p = new ProcessBuilder("bash", "-c", cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        p.waitFor(3, TimeUnit.SECONDS);
        return sb.toString();
    }

    // ==================== 轮询 PCD 状态 ====================

    @GetMapping("/pcd-status")
    @ApiOperation("查询PCD文件是否已生成")
    public Result<Map<String, Object>> getPcdStatus() {
        File pcdFile  = new File(PCD_PATH);
        boolean exists = pcdFile.exists() && pcdFile.length() > 0;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("exists",   exists);
        status.put("path",     PCD_PATH);
        status.put("sizeKB",   exists ? pcdFile.length() / 1024 : 0);
        status.put("modified", exists ? new Date(pcdFile.lastModified()).toString() : null);
        return Result.OK(status);
    }

    // ==================== 运行状态 ====================

    @GetMapping("/status")
    @ApiOperation("查询运行状态")
    public Result<Map<String, Object>> getStatus() {
        boolean running = fastlioProcess != null && fastlioProcess.isAlive();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running",   running);
        status.put("pcdExists", new File(PCD_PATH).exists());
        status.put("pcdPath",   PCD_PATH);
        return Result.OK(status);
    }

    // ==================== 保存导航地图 ====================

    @PostMapping("/save")
    @ApiOperation("将PCD转换为导航地图pgm+yaml（Nav2可直接加载）")
    public Result<Map<String, Object>> saveMap(@RequestBody Map<String, String> body) {
        try {
            File pcdFile = new File(PCD_PATH);
            if (!pcdFile.exists() || pcdFile.length() == 0) {
                return Result.error("PCD 文件不存在: " + PCD_PATH
                        + "\n请先完成建图并停止，等待 PCD 保存完毕");
            }

            String filename   = body.getOrDefault("filename", "map_" + System.currentTimeMillis());
            filename          = filename.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            double resolution = Double.parseDouble(body.getOrDefault("resolution", "0.05"));
            double zMin       = Double.parseDouble(body.getOrDefault("zMin", "0.1"));
            double zMax       = Double.parseDouble(body.getOrDefault("zMax", "2.0"));

            Files.createDirectories(Paths.get(MAPS_DIR));
            String outPath = MAPS_DIR + "/" + filename;

            log.info("转换: {} → {}.pgm/.yaml (res={}, z={}~{})",
                    PCD_PATH, outPath, resolution, zMin, zMax);

            runConvert(pcdFile.getAbsolutePath(), outPath, resolution, zMin, zMax);

            if (!new File(outPath + ".pgm").exists() || !new File(outPath + ".yaml").exists()) {
                return Result.error("转换完成但文件未找到，请检查 Python 依赖：pip3 install Pillow numpy");
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("filename", filename);
            res.put("pgm",      outPath + ".pgm");
            res.put("yaml",     outPath + ".yaml");
            res.put("nav2Cmd",  "ros2 launch nav2_bringup navigation_launch.py map:=" + outPath + ".yaml");
            return Result.OK(res);

        } catch (Exception e) {
            log.error("保存地图失败", e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }

    // ==================== 地图列表 ====================

    @GetMapping("/list")
    @ApiOperation("获取已保存导航地图列表")
    public Result<List<Map<String, Object>>> getMapList() {
        try {
            File dir = new File(MAPS_DIR);
            if (!dir.exists()) return Result.OK(new ArrayList<>());
            File[] files = dir.listFiles(new FilenameFilter() {
                public boolean accept(File d, String n) { return n.endsWith(".yaml"); }
            });
            if (files == null) return Result.OK(new ArrayList<>());
            List<Map<String, Object>> list = new ArrayList<>();
            for (File f : files) {
                String name = f.getName().replace(".yaml", "");
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",       name);
                m.put("createTime", f.lastModified());
                m.put("hasImage",   new File(MAPS_DIR + "/" + name + ".pgm").exists());
                list.add(m);
            }
            return Result.OK(list);
        } catch (Exception e) {
            return Result.error("获取列表失败: " + e.getMessage());
        }
    }

    // ==================== Python 转换工具 ====================

    private void runConvert(String pcdPath, String outPath,
                            double res, double zMin, double zMax) throws Exception {
        String script = buildScript(pcdPath, outPath, res, zMin, zMax);
        File   tmp    = File.createTempFile("pcd2pgm_", ".py");
        Files.write(tmp.toPath(), script.getBytes("UTF-8"));
        try {
            String cmd = "source " + SETUP_BASH + " && python3 " + tmp.getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) log.info("[pcd2pgm] {}", line);
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new RuntimeException("转换超时(>120s)"); }
            if (p.exitValue() != 0) throw new RuntimeException("Python 脚本执行失败，请检查依赖: pip3 install Pillow numpy");
        } finally {
            tmp.delete();
        }
    }

    /**
     * 构建 Python 脚本内容
     *
     * 修复点：
     *   1. 不再使用 open3d（与 NumPy 2.x 不兼容）
     *   2. 原生支持 binary / binary_compressed / ascii 三种 PCD 格式
     *   3. 以 'rb' 模式读文件，彻底避免 UnicodeDecodeError
     */
    private String buildScript(String pcdPath, String outPath,
                               double res, double zMin, double zMax) {
        // 把 outPath 拆成目录和文件名（Python 端需要分别用）
        String outDir  = outPath.contains("/")
                ? outPath.substring(0, outPath.lastIndexOf('/'))
                : ".";
        String outName = outPath.contains("/")
                ? outPath.substring(outPath.lastIndexOf('/') + 1)
                : outPath;

        return "#!/usr/bin/env python3\n"
                + "import numpy as np\n"
                + "import struct, os, sys\n"
                + "from PIL import Image\n"
                + "\n"
                + "# ===== Java 传入的参数 =====\n"
                + "PCD_PATH   = '" + pcdPath  + "'\n"
                + "OUTPUT_DIR = '" + outDir   + "'\n"
                + "OUT_NAME   = '" + outName  + "'\n"
                + "RESOLUTION = " + res       + "\n"
                + "Z_MIN      = " + zMin      + "\n"
                + "Z_MAX      = " + zMax      + "\n"
                + "MARGIN     = 1.0\n"
                + "\n"
                + "def read_pcd(filepath):\n"
                + "    \"\"\"纯Python读取PCD，支持ascii/binary，无需open3d\"\"\"\n"
                + "    with open(filepath, 'rb') as f:\n"
                + "        headers = {}\n"
                + "        while True:\n"
                + "            line = f.readline().decode('utf-8', errors='ignore').strip()\n"
                + "            if line.upper().startswith('DATA'):\n"
                + "                data_type = line.split()[1].lower()\n"
                + "                break\n"
                + "            if line:\n"
                + "                parts = line.split()\n"
                + "                if len(parts) >= 2:\n"
                + "                    headers[parts[0].upper()] = parts[1:]\n"
                + "\n"
                + "        fields  = headers.get('FIELDS', [])\n"
                + "        sizes   = [int(s) for s in headers.get('SIZE',  [])]\n"
                + "        types   = headers.get('TYPE',  [])\n"
                + "        counts  = [int(c) for c in headers.get('COUNT', [])]\n"
                + "        num_pts = int(headers.get('POINTS', ['0'])[0])\n"
                + "        print(f'  格式: {data_type}, 点数: {num_pts}, 字段: {fields}')\n"
                + "\n"
                + "        try:\n"
                + "            xi = fields.index('x')\n"
                + "            yi = fields.index('y')\n"
                + "            zi = fields.index('z')\n"
                + "        except ValueError:\n"
                + "            print('ERROR: PCD 文件中没有 x/y/z 字段'); sys.exit(1)\n"
                + "\n"
                + "        if data_type == 'ascii':\n"
                + "            rows = []\n"
                + "            for _ in range(num_pts):\n"
                + "                row = f.readline().decode('utf-8', errors='ignore').strip().split()\n"
                + "                if row:\n"
                + "                    try: rows.append([float(row[xi]), float(row[yi]), float(row[zi])])\n"
                + "                    except: pass\n"
                + "            return np.array(rows, dtype=np.float32)\n"
                + "\n"
                + "        elif data_type == 'binary':\n"
                + "            point_step = sum(s * c for s, c in zip(sizes, counts))\n"
                + "            raw = f.read(point_step * num_pts)\n"
                + "            offsets = []\n"
                + "            off = 0\n"
                + "            for s, c in zip(sizes, counts):\n"
                + "                offsets.append(off); off += s * c\n"
                + "            type_map = {'F': {4:'f', 8:'d'}, 'I': {4:'i'}, 'U': {4:'I', 1:'B'}}\n"
                + "            pts = []\n"
                + "            for i in range(num_pts):\n"
                + "                base = i * point_step\n"
                + "                def rv(idx, base=base):\n"
                + "                    fmt = type_map.get(types[idx], {}).get(sizes[idx], 'f')\n"
                + "                    return struct.unpack_from(fmt, raw, base + offsets[idx])[0]\n"
                + "                pts.append([rv(xi), rv(yi), rv(zi)])\n"
                + "            return np.array(pts, dtype=np.float32)\n"
                + "\n"
                + "        elif data_type == 'binary_compressed':\n"
                + "            # binary_compressed 需要 lzf 解压，先尝试导入\n"
                + "            try:\n"
                + "                import lzf\n"
                + "                csize   = struct.unpack('I', f.read(4))[0]\n"
                + "                dsize   = struct.unpack('I', f.read(4))[0]\n"
                + "                raw     = lzf.decompress(f.read(csize), dsize)\n"
                + "            except ImportError:\n"
                + "                print('binary_compressed 格式需要 lzf: pip3 install lzf'); sys.exit(1)\n"
                + "            point_step = sum(s * c for s, c in zip(sizes, counts))\n"
                + "            offsets = []\n"
                + "            off = 0\n"
                + "            for s, c in zip(sizes, counts):\n"
                + "                offsets.append(off); off += s * c\n"
                + "            type_map = {'F': {4:'f', 8:'d'}, 'I': {4:'i'}, 'U': {4:'I', 1:'B'}}\n"
                + "            pts = []\n"
                + "            for i in range(num_pts):\n"
                + "                base = i * point_step\n"
                + "                def rv2(idx, base=base):\n"
                + "                    fmt = type_map.get(types[idx], {}).get(sizes[idx], 'f')\n"
                + "                    return struct.unpack_from(fmt, raw, base + offsets[idx])[0]\n"
                + "                pts.append([rv2(xi), rv2(yi), rv2(zi)])\n"
                + "            return np.array(pts, dtype=np.float32)\n"
                + "        else:\n"
                + "            print(f'ERROR: 不支持的格式: {data_type}'); sys.exit(1)\n"
                + "\n"
                + "# ===== 主流程 =====\n"
                + "os.makedirs(OUTPUT_DIR, exist_ok=True)\n"
                + "print(f'读取PCD: {PCD_PATH}')\n"
                + "if not os.path.exists(PCD_PATH):\n"
                + "    print('ERROR: 文件不存在'); sys.exit(1)\n"
                + "\n"
                + "pts = read_pcd(PCD_PATH)\n"
                + "print(f'总点数: {len(pts)}')\n"
                + "print(f'  Z: {pts[:,2].min():.2f} ~ {pts[:,2].max():.2f} m')\n"
                + "print(f'  X: {pts[:,0].min():.2f} ~ {pts[:,0].max():.2f} m')\n"
                + "print(f'  Y: {pts[:,1].min():.2f} ~ {pts[:,1].max():.2f} m')\n"
                + "\n"
                + "mask  = (pts[:,2] > Z_MIN) & (pts[:,2] < Z_MAX)\n"
                + "pts2d = pts[mask][:, :2]\n"
                + "print(f'高度过滤 [{Z_MIN}, {Z_MAX}]m 后: {len(pts2d)} 点')\n"
                + "if len(pts2d) == 0:\n"
                + "    print(f'ERROR: 过滤后无点，请调整 Z_MIN/Z_MAX（当前范围见上方Z轴信息）'); sys.exit(1)\n"
                + "\n"
                + "x0 = pts2d[:,0].min() - MARGIN\n"
                + "y0 = pts2d[:,1].min() - MARGIN\n"
                + "x1 = pts2d[:,0].max() + MARGIN\n"
                + "y1 = pts2d[:,1].max() + MARGIN\n"
                + "W  = int((x1 - x0) / RESOLUTION) + 1\n"
                + "H  = int((y1 - y0) / RESOLUTION) + 1\n"
                + "print(f'地图尺寸: {W} x {H} px')\n"
                + "\n"
                + "grid = np.full((H, W), 255, dtype=np.uint8)\n"
                + "ix   = ((pts2d[:,0] - x0) / RESOLUTION).astype(int)\n"
                + "iy   = ((pts2d[:,1] - y0) / RESOLUTION).astype(int)\n"
                + "ok   = (ix >= 0) & (ix < W) & (iy >= 0) & (iy < H)\n"
                + "grid[H - 1 - iy[ok], ix[ok]] = 0\n"
                + "\n"
                + "pgm_path  = os.path.join(OUTPUT_DIR, OUT_NAME + '.pgm')\n"
                + "yaml_path = os.path.join(OUTPUT_DIR, OUT_NAME + '.yaml')\n"
                + "\n"
                + "Image.fromarray(grid).save(pgm_path)\n"
                + "print(f'PGM 已保存: {pgm_path}')\n"
                + "\n"
                + "with open(yaml_path, 'w') as f:\n"
                + "    f.write(f'image: {pgm_path}\\n')\n"
                + "    f.write(f'resolution: {RESOLUTION}\\n')\n"
                + "    f.write(f'origin: [{x0:.4f}, {y0:.4f}, 0.0]\\n')\n"
                + "    f.write('negate: 0\\n')\n"
                + "    f.write('occupied_thresh: 0.65\\n')\n"
                + "    f.write('free_thresh: 0.196\\n')\n"
                + "print(f'YAML 已保存: {yaml_path}')\n"
                + "print('=== 转换完成，可用于 Nav2 导航 ===')\n";
    }
}