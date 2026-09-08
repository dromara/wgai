package org.jeecg.modules.szr.websocket;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数字人播报事件推送。
 *
 * <p>数字人驱动占用 GPU 资源，同一时刻只允许一位用户连接。客户端传入的
 * {@code userId} 可以是浏览器生成的随机连接标识，管理端据此识别和移除当前连接。</p>
 */
@Slf4j
@Component
@ServerEndpoint("/WebSocketSzr/{userId}")
public class WebSocketSzr {

    private static final String OCCUPIED_MESSAGE = "数字人当前已有用户连接，同一时间只允许一人使用";
    private static final String REMOVED_MESSAGE = "管理员已移除当前数字人连接";

    /**
     * GPU 只有一个可用名额，因此不能用 userId -> Session 的 Map；AtomicReference 才能保证
     * 两个不同用户同时握手时也只会有一个成功占用名额。
     */
    private static final AtomicReference<ConnectionInfo> ACTIVE_CONNECTION = new AtomicReference<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            sendAndClose(session, "connection_rejected", "未获取到连接标识，无法连接数字人");
            return;
        }

        ConnectionInfo candidate = new ConnectionInfo(userId, session, System.currentTimeMillis());
        while (true) {
            ConnectionInfo active = ACTIVE_CONNECTION.get();
            if (active == null) {
                if (ACTIVE_CONNECTION.compareAndSet(null, candidate)) {
                    log.info("[WebSocketSzr] 连接建立 userId={}, 在线={}", userId, onlineCount());
                    return;
                }
                continue;
            }
            // 容器偶发未回调 OnClose 时，不让已失效的 Session 永远占着 GPU 名额。
            if (!active.session.isOpen()) {
                ACTIVE_CONNECTION.compareAndSet(active, null);
                continue;
            }

            log.info("[WebSocketSzr] 拒绝连接 userId={}，当前占用 userId={}", userId, active.userId);
            sendAndClose(session, "connection_rejected", OCCUPIED_MESSAGE);
            return;
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("userId") String userId) {
        ConnectionInfo active = ACTIVE_CONNECTION.get();
        // 被拒绝的第二个连接也会触发 OnClose；必须按 Session 比较，不能误删占用者。
        if (active != null && active.session == session && ACTIVE_CONNECTION.compareAndSet(active, null)) {
            log.info("[WebSocketSzr] 连接关闭 userId={}, 在线=0", userId);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        ConnectionInfo active = ACTIVE_CONNECTION.get();
        if (active != null && active.session == session) {
            ACTIVE_CONNECTION.compareAndSet(active, null);
        }
        log.warn("[WebSocketSzr] 连接异常: {}", error.getMessage());
    }

    @OnMessage
    public void onMessage(String message, @PathParam("userId") String userId) {
        // 前端目前只订阅不上行，收到就当心跳
        log.debug("[WebSocketSzr] 收到 userId={} message={}", userId, message);
    }

    /** 广播给当前唯一在线前端。 */
    public static void broadcast(String json) {
        ConnectionInfo active = activeConnection();
        if (active != null) {
            sendTo(active, json);
        }
    }

    /** 定向推送；只有当前占用用户才会收到消息。 */
    public static void sendTo(String userId, String json) {
        ConnectionInfo active = activeConnection();
        if (active != null && active.userId.equals(userId)) {
            sendTo(active, json);
        }
    }

    /** 当前连接清单，最多一条，供管理页面展示。 */
    public static List<ConnectionInfo> onlineConnections() {
        ConnectionInfo active = activeConnection();
        return active == null ? Collections.emptyList() : Collections.singletonList(active);
    }

    /** 管理员移除指定用户的连接。 */
    public static boolean disconnect(String userId) {
        ConnectionInfo active = activeConnection();
        if (active == null || !active.userId.equals(userId) || !ACTIVE_CONNECTION.compareAndSet(active, null)) {
            return false;
        }
        sendAndClose(active.session, "connection_removed", REMOVED_MESSAGE);
        log.info("[WebSocketSzr] 管理员移除连接 userId={}", userId);
        return true;
    }

    public static int onlineCount() {
        return activeConnection() == null ? 0 : 1;
    }

    private static ConnectionInfo activeConnection() {
        ConnectionInfo active = ACTIVE_CONNECTION.get();
        if (active != null && !active.session.isOpen()) {
            ACTIVE_CONNECTION.compareAndSet(active, null);
            return null;
        }
        return active;
    }

    private static void sendTo(ConnectionInfo connection, String json) {
        Session session = connection.session;
        if (!session.isOpen()) {
            ACTIVE_CONNECTION.compareAndSet(connection, null);
            return;
        }
        try {
            // 同一个 Session 并发 sendText 会抛 IllegalStateException，必须串行化。
            synchronized (session) {
                session.getBasicRemote().sendText(json);
            }
        } catch (IOException | IllegalStateException e) {
            log.warn("[WebSocketSzr] 推送失败 userId={}: {}", connection.userId, e.getMessage());
            ACTIVE_CONNECTION.compareAndSet(connection, null);
        }
    }

    private static void sendAndClose(Session session, String event, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", event);
            payload.put("message", message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(payload.toJSONString());
                    session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, message));
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("[WebSocketSzr] 控制消息发送/关闭失败: {}", e.getMessage());
        }
    }

    /** 公开给 JSON 序列化的连接状态，不暴露 Session 实例。 */
    public static final class ConnectionInfo {
        private final String userId;
        private final String sessionId;
        private final long connectedAt;
        private final Session session;

        private ConnectionInfo(String userId, Session session, long connectedAt) {
            this.userId = userId;
            this.sessionId = session.getId();
            this.connectedAt = connectedAt;
            this.session = session;
        }

        public String getUserId() {
            return userId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getConnectedAt() {
            return connectedAt;
        }
    }
}
