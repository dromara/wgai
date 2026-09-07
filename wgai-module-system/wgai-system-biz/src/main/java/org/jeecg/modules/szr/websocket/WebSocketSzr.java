package org.jeecg.modules.szr.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字人播报事件推送。
 *
 * <p>前端订阅后会收到这类消息（JSON）：
 * <pre>
 * {"event":"sentence_start","tag":"task-17..-3","index":3,"total":6,"text":"请注意避让"}
 * {"event":"sentence_end",  "tag":"task-17..-3","index":3,"total":6,"text":"请注意避让"}
 * {"event":"task_end",      "tag":"task-17..",  "text":""}
 * </pre>
 *
 * <p>事件源头是 Python 驱动服务的 scheduler —— 它是唯一知道某一帧真正被推进
 * ffmpeg 的地方。不要在 Java 侧按音频时长估算播放进度，那必然漂移。
 *
 * @author wggg
 */
@Slf4j
@Component
@ServerEndpoint("/WebSocketSzr/{userId}")
public class WebSocketSzr {

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        SESSIONS.put(userId, session);
        log.info("[WebSocketSzr] 连接建立 userId={}, 在线={}", userId, SESSIONS.size());
    }

    @OnClose
    public void onClose(@PathParam("userId") String userId) {
        SESSIONS.remove(userId);
        log.info("[WebSocketSzr] 连接关闭 userId={}, 在线={}", userId, SESSIONS.size());
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.warn("[WebSocketSzr] 连接异常: {}", error.getMessage());
    }

    @OnMessage
    public void onMessage(String message, @PathParam("userId") String userId) {
        // 前端目前只订阅不上行，收到就当心跳
        log.debug("[WebSocketSzr] 收到 userId={} message={}", userId, message);
    }

    /** 广播给所有在线前端。 */
    public static void broadcast(String json) {
        SESSIONS.forEach((userId, session) -> sendTo(userId, session, json));
    }

    /** 定向推送。 */
    public static void sendTo(String userId, String json) {
        Session session = SESSIONS.get(userId);
        if (session != null) {
            sendTo(userId, session, json);
        }
    }

    private static void sendTo(String userId, Session session, String json) {
        if (session == null || !session.isOpen()) {
            SESSIONS.remove(userId);
            return;
        }
        try {
            // 同一个 Session 并发 sendText 会抛 IllegalStateException，必须串行化
            synchronized (session) {
                session.getBasicRemote().sendText(json);
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("[WebSocketSzr] 推送失败 userId={}: {}", userId, e.getMessage());
            SESSIONS.remove(userId);
        }
    }

    public static int onlineCount() {
        return SESSIONS.size();
    }
}
