package org.jeecg.modules.szr.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 反向订阅 Python 驱动服务的播放事件（SSE）。
 *
 * <p>相比让 Python 主动 POST 回调 Java，订阅方式<b>只需要开放 Python 一个端口</b>，
 * Java 侧不用对外暴露，跨网段/端口映射的面小很多。
 *
 * <p><b>按需启动</b>：不在应用启动时连接 —— 数字人多数时候用不到，
 * 常驻重连只会刷日志。第一次提交播报时才拉起，空闲 {@link #IDLE_STOP_MS}
 * 之后自动停掉。也可以通过 {@code /szr/speak/subscriber/start|stop} 手动控制。
 *
 * <p>断线会自动重连，并带上最后收到的 {@code seq}，让驱动服务把断开期间漏掉的
 * 事件补发回来 —— 否则一次网络抖动就会丢掉几句话的播报回调。
 *
 * @author wggg
 */
@Slf4j
@Component
public class SzrEventSubscriber {

    /** 空闲多久没收到任何字节就认为连接已死。驱动服务每 15 秒发一次心跳。 */
    private static final int READ_TIMEOUT_MS = 40_000;

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** 重连间隔，从 1 秒退避到 30 秒 */
    private static final long RETRY_MIN_MS = 1_000L;
    private static final long RETRY_MAX_MS = 30_000L;

    /** 最后一次播报活动之后，空闲这么久就自动断开，不再占着连接刷日志 */
    private static final long IDLE_STOP_MS = 10 * 60_000L;

    @Autowired
    private SzrDriverConfig driverConfig;

    @Autowired
    private SzrPlayEventService playEventService;

    private volatile boolean running = false;
    private volatile boolean manual = false;
    private volatile long lastSeq = 0L;
    private volatile boolean connected = false;
    private volatile long lastActiveAt = 0L;
    /** 连续失败次数，用来把重复的重连日志降级，避免刷屏 */
    private volatile int failStreak = 0;
    private Thread worker;

    /**
     * 有播报活动时调用：没起就起，起了就续命。
     * 由 {@code SzrSpeakController} 在提交播报时触发。
     */
    public synchronized void touch() {
        lastActiveAt = System.currentTimeMillis();
        if (!running) {
            start(false);
        }
    }

    /** 手动启动。manual=true 时不受空闲自动停约束。 */
    public synchronized void start(boolean manualStart) {
        if (manualStart) {
            manual = true;
        }
        lastActiveAt = System.currentTimeMillis();
        if (running) {
            return;
        }
        running = true;
        failStreak = 0;
        worker = new Thread(this::loop, "szr-event-subscriber");
        worker.setDaemon(true);
        worker.start();
        log.info("[szr-sse] 订阅线程启动（{}）", manualStart ? "手动" : "按需");
    }

    public synchronized void stop() {
        manual = false;
        if (!running) {
            return;
        }
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("[szr-sse] 订阅线程停止");
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return connected;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    private void loop() {
        long retry = RETRY_MIN_MS;
        while (running) {
            if (shouldIdleStop()) {
                log.info("[szr-sse] 空闲超过 {} 分钟，自动断开订阅", IDLE_STOP_MS / 60_000);
                running = false;
                break;
            }
            try {
                consume();
                retry = RETRY_MIN_MS;
                failStreak = 0;
            } catch (Exception e) {
                connected = false;
                failStreak++;
                // 只有第一次失败打 warn，后续重试降到 debug，否则驱动服务没开时会一直刷屏
                if (failStreak == 1) {
                    log.warn("[szr-sse] 订阅中断（{}），将自动重连: {}",
                            driverConfig.baseUrl(), e.getMessage());
                } else {
                    log.debug("[szr-sse] 第 {} 次重连失败: {}", failStreak, e.getMessage());
                }
            }
            if (!running) {
                break;
            }
            try {
                Thread.sleep(retry);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            retry = Math.min(retry * 2, RETRY_MAX_MS);
        }
        connected = false;
        running = false;
        log.info("[szr-sse] 订阅线程退出");
    }

    private boolean shouldIdleStop() {
        return !manual && lastActiveAt > 0
                && System.currentTimeMillis() - lastActiveAt > IDLE_STOP_MS;
    }

    private void consume() throws Exception {
        String url = driverConfig.baseUrl() + "/events";
        if (lastSeq > 0) {
            // 带上断点，让驱动服务补发断开期间的事件
            url += "?since=" + lastSeq;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        connected = true;
        log.info("[szr-sse] 已连接 {}", url);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (shouldIdleStop()) {
                    break;
                }
                // SSE 格式：注释行以 : 开头（心跳），数据行以 data: 开头，空行分隔事件
                if (line.isEmpty() || line.startsWith(":") || line.startsWith("id:")) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (!payload.isEmpty()) {
                    dispatch(payload);
                }
            }
        } finally {
            connected = false;
            conn.disconnect();
        }
    }

    private void dispatch(String payload) {
        try {
            JSONObject obj = JSONObject.parseObject(payload);
            Long seq = obj.getLong("seq");
            if (seq != null) {
                lastSeq = seq;
            }
            // 有事件说明还在播，续命，避免长文本播到一半被空闲逻辑掐掉
            lastActiveAt = System.currentTimeMillis();
            playEventService.handle(obj.getString("event"), obj.getString("tag"), seq, "SSE");
        } catch (Exception e) {
            log.warn("[szr-sse] 事件解析失败: {} -> {}", payload, e.getMessage());
        }
    }
}
