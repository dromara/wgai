package org.jeecg.modules.message.websocket;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Resource;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.common.base.BaseMap;
import org.jeecg.common.constant.WebsocketConst;
import org.jeecg.common.modules.redis.client.JeecgRedisClient;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.modules.demo.tab.service.ITabAiHistoryService;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 服务端
 *
 * 推送策略说明（三档）:
 *
 *   ① pushMessage(String message)            高频广播，丢帧不丢最新
 *      适合: robot_pose(50Hz) / cmd_vel(20Hz)
 *      策略: tryLock() 抢不到直接跳过，绝不堵塞调用线程
 *
 *   ② pushMessageReliable(String message)     低频可靠广播，等待送达
 *      适合: path_update / nav_status / localization_status
 *      策略: tryLock(1s timeout)，超时才放弃，确保关键消息送达
 *
 *   ③ pushMessage(String userId, String msg)  单点可靠推送
 *      适合: 心跳响应 / 用户专属通知
 *      策略: lock() 无限等待，一定送达
 *
 * 注意: ROS2 高频数据请直接调用 pushMessage() / pushMessageReliable()，
 *       不要走 sendMessage()→Redis→SocketHandler 这条路，
 *       Redis 队列会在 50Hz 下迅速堆积导致 TEXT_FULL_WRITING。
 */
@Component
@Slf4j
@ServerEndpoint("/websocket/{userId}")
public class WebSocket {

    /** Session 池: userId → Session */
    private static final ConcurrentHashMap<String, Session> sessionPool = new ConcurrentHashMap<>();

    /**
     * 每个 Session 独立写锁。
     * 使用 ReentrantLock 而非 synchronized(session)：
     *   - tryLock() / tryLock(timeout) 支持非阻塞/有超时的获取
     *   - 避免 synchronized 与 Tomcat 内部状态机交互时的潜在死锁
     */
    private static final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public static final String REDIS_TOPIC_NAME = "socketHandler";

    @Resource
    private JeecgRedisClient jeecgRedisClient;

    // ─────────────────────────── 生命周期 ───────────────────────────

    @OnOpen
    public void onOpen(Session session, @PathParam(value = "userId") String userId) {
        try {
            sessionPool.put(userId, session);
            sessionLocks.put(userId, new ReentrantLock());
            log.info("【WS】连接建立 userId={}, 当前连接数={}", userId, sessionPool.size());

            if (userId.contains("rtsp")) {
                ITabAiHistoryService svc = (ITabAiHistoryService)
                        SpringContextUtils.getBean("tabAiHistoryServiceImpl");
                svc.sendUrlFLV();
            }
        } catch (Exception e) {
            log.error("【WS】onOpen 异常", e);
        }
    }

    @OnClose
    public void onClose(@PathParam("userId") String userId) {
        try {
            sessionPool.remove(userId);
            sessionLocks.remove(userId);
            log.info("【WS】连接关闭 userId={}, 当前连接数={}", userId, sessionPool.size());
        } catch (Exception e) {
            log.error("【WS】onClose 异常", e);
        }
    }

    @OnMessage
    public void onMessage(String message, @PathParam(value = "userId") String userId) {
        if (userId.contains("audio")) {
            log.info("【WS】收到 audio 消息");
            return;
        }
        if (!"ping".equals(message) && !WebsocketConst.CMD_CHECK.equals(message)) {
            log.info("【WS】收到客户端消息 userId={}: {}", userId, message);
        } else {
            log.debug("【WS】心跳 userId={}", userId);
        }
        JSONObject obj = new JSONObject();
        obj.put(WebsocketConst.MSG_CMD, WebsocketConst.CMD_CHECK);
        obj.put(WebsocketConst.MSG_TXT, "心跳响应");
        pushMessage(userId, obj.toJSONString());
    }

    @OnError
    public void onError(Session session, Throwable t) {
        log.warn("【WS】连接发生错误: {}", t.getMessage());
    }

    // ─────────────────────────── 推送方法 ───────────────────────────

    /**
     * ① 高频广播 —— tryLock，Session 忙则丢帧（robot_pose / cmd_vel）
     */
    public void pushMessage(String message) {
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            Session session = entry.getValue();
            ReentrantLock lock = sessionLocks.get(entry.getKey());
            if (lock == null || session == null || !session.isOpen()) continue;

            if (!lock.tryLock()) {
                log.debug("【WS】高频广播丢帧(Session忙) userId={}", entry.getKey());
                continue;
            }
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                log.error("【WS】高频广播失败 userId={}: {}", entry.getKey(), e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * ② 可靠广播 —— tryLock(1s)，超时才放弃（path_update / nav_status / localization_status）
     *
     * 发送前最多等待 1 秒让 Session 腾出写通道。
     * 1 秒内仍未获得锁才跳过并打 warn，正常网络下几乎不会触发。
     */
    public void pushMessageReliable(String message) {
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            Session session = entry.getValue();
            ReentrantLock lock = sessionLocks.get(entry.getKey());
            if (lock == null || session == null || !session.isOpen()) continue;

            boolean acquired = false;
            try {
                acquired = lock.tryLock(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("【WS】可靠广播被中断 userId={}", entry.getKey());
                continue;
            }

            if (!acquired) {
                log.warn("【WS】可靠广播等待 1s 超时，跳过 userId={}，消息可能丢失", entry.getKey());
                continue;
            }
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                log.error("【WS】可靠广播发送失败 userId={}: {}", entry.getKey(), e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * ③ 单点可靠推送 —— lock() 无超时阻塞（心跳响应 / 用户专属通知）
     */
    public void pushMessage(String userId, String message) {
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            if (!entry.getKey().contains(userId)) continue;

            Session session = entry.getValue();
            ReentrantLock lock = sessionLocks.get(entry.getKey());
            if (lock == null || session == null || !session.isOpen()) continue;

            lock.lock();
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                log.error("【WS】单点推送失败 userId={}: {}", userId, e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 二进制广播（视频帧）—— tryLock 丢帧
     */
    public void pushMessageByte(byte[] encodedData) {
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            Session session = entry.getValue();
            ReentrantLock lock = sessionLocks.get(entry.getKey());
            if (lock == null || session == null || !session.isOpen()) continue;

            if (!lock.tryLock()) continue;
            try {
                session.getBasicRemote().sendBinary(ByteBuffer.wrap(encodedData));
            } catch (Exception e) {
                log.error("【WS】二进制广播失败: {}", e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * ByteBuffer 广播（视频帧）—— tryLock 丢帧，每 Session 独立 rewind
     */
    public void broadcastFrame(ByteBuffer buffer) {
        for (Map.Entry<String, Session> entry : sessionPool.entrySet()) {
            Session session = entry.getValue();
            ReentrantLock lock = sessionLocks.get(entry.getKey());
            if (lock == null || session == null || !session.isOpen()) continue;

            if (!lock.tryLock()) continue;
            try {
                buffer.rewind();
                session.getBasicRemote().sendBinary(buffer);
            } catch (Exception e) {
                log.error("【WS】broadcastFrame 失败: {}", e.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    // ─────────────────────── Redis 发布订阅（保留原有逻辑）───────────────────────
    // 注意：ROS2 高频数据不要走这条路，直接调用上面的 pushMessage() 系列方法

    public void sendMessage(String message) {
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", "");
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    public void sendMessage(String userId, String message) {
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", userId);
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    public void sendMessage(String[] userIds, String message) {
        for (String userId : userIds) {
            sendMessage(userId, message);
        }
    }
}