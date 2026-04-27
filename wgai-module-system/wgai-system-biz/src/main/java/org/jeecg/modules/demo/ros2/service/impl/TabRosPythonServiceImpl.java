package org.jeecg.modules.demo.ros2.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.ros2.entity.TabRosPython;
import org.jeecg.modules.demo.ros2.mapper.TabRosPythonMapper;
import org.jeecg.modules.demo.ros2.service.ITabRosPythonService;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Description: ROS脚本
 * @Author: wggg
 * @Date:   2026-04-21
 * @Version: V1.0
 */

@Slf4j
@Service
public class TabRosPythonServiceImpl extends ServiceImpl<TabRosPythonMapper, TabRosPython> implements ITabRosPythonService {

    public Result<String> startPy(TabRosPython rosPython) {
        log.info("开始执行脚本{},{}", rosPython.getBeforePy(), rosPython.getEndPy());

        try {
            // ✅ 修复1：拼接命令时加上 launch_rviz:=false
            String rawCmd = rosPython.getBeforePy() + rosPython.getEndPy();
            String finalCmd = appendLaunchRvizFalse(rawCmd);
            log.info("最终执行命令: {}", finalCmd);

            ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-c", finalCmd);
            processBuilder.redirectErrorStream(true);

            // ✅ 修复2：设置环境变量，避免 Qt/DISPLAY 报错
            Map<String, String> env = processBuilder.environment();
            env.put("DISPLAY", ":0");                    // 有本地显示器时用 :0
            env.put("QT_QPA_PLATFORM", "offscreen");     // 无显示器备用方案

            Process process = processBuilder.start();
            log.info("进程已启动 PID: {}");

            // ✅ 修复3：异步读取输出，不阻塞 HTTP 线程
            // 不再调用 process.waitFor()，让进程在后台持续运行
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[fast-lio] {}", line);
                    }
                } catch (IOException e) {
                    log.info("进程输出流结束");
                }
            });
            outputThread.setDaemon(true);  // 守护线程，JVM退出时自动结束
            outputThread.start();

            // 存储进程引用，供后续停止使用
            processMap.put(rosPython.getId(), process);

        } catch (IOException e) {
            log.error("启动失败", e);
            return Result.error("启动失败: " + e.getMessage());
        }

        return Result.OK("脚本已在后台启动");  // ✅ 立即返回，不等待
    }

    /**
     * 如果是 ros2 launch fast_lio 命令，自动追加 launch_rviz:=false
     */
    private String appendLaunchRvizFalse(String cmd) {
        if (cmd.contains("fast_lio") && cmd.contains("launch")
                && !cmd.contains("launch_rviz")) {
            return cmd.trim() + " launch_rviz:=false";
        }
        return cmd;
    }

    // 用 Map 保存进程，支持后续停止
    private final Map<String, Process> processMap = new ConcurrentHashMap<>();

    /**
     * 停止指定脚本进程
     */
    public Result<String> stopPy(String id) {
        Process process = processMap.get(id);
        if (process != null && process.isAlive()) {
            process.destroy();
            processMap.remove(id);
            log.info("进程 {} 已停止", id);
            return Result.OK("已停止");
        }
        return Result.error("进程不存在或已停止");
    }
}
