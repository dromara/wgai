package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.ros2.model.VelocityCommand;
import org.jeecg.modules.ros2.model.VelocityVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * WebSocket 推送服务
 *
 * 关键修复：ROS2 数据全部绕过 Redis，直接调用 WebSocket.pushMessage() 系列
 *
 * 旧版问题：所有方法都调 webSocket.sendMessage() → Redis pub/sub →
 *           SocketHandler → webSocket.pushMessage()
 *   - robot_pose 以 50Hz 推送，每秒 50 条消息进入 Redis 队列
 *   - Redis 消费速度跟不上生产速度，消息在队列中堆积
 *   - SocketHandler 高频回调 webSocket.pushMessage()（旧版用 getAsyncRemote()）
 *   - 触发 TEXT_FULL_WRITING 异常
 *
 * 新版策略：
 *   高频实时数据（robot_pose / cmd_vel）
 *     → webSocket.pushMessage()         [tryLock 丢帧，不堵调用线程]
 *
 *   低频关键数据（path_update / nav_status / localization_status）
 *     → webSocket.pushMessageReliable() [tryLock(1s) 等待送达，不丢失]
 *
 *   普通业务通知（非 ROS2 场景）
 *     → webSocket.sendMessage()         [走 Redis，保持原有集群广播能力]
 */
@Slf4j
@Service
public class WebSocketPushService {

    @Autowired
    private WebSocket webSocket;

    // ─────────────────── 高频实时数据（直连 WebSocket，tryLock 丢帧） ───────────────────

    /**
     * 推送速度数据（cmd_vel，~20Hz）
     * 直接调用 pushMessage，Session 忙则丢帧，绝不堵塞 ROS2 消息处理线程。
     */
    public void pushVelocity(VelocityCommand velocity) {
        try {
            VelocityVO vo = VelocityVO.fromEntity(velocity);
            JSONObject message = new JSONObject();
            message.put("type",      "cmd_vel_update");
            message.put("data",      vo);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送速度数据失败", e);
        }
    }

    /**
     * 推送机器人位姿（robot_pose，~50Hz）
     * 直接调用 pushMessage，Session 忙则丢帧。
     */
    public void pushRobotPose(Object poseData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "robot_pose");
            message.put("data",      poseData);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送机器人位姿失败", e);
        }
    }

    /**
     * 推送位置数据（通用，高频）
     */
    public void pushPosition(Object position) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "position");
            message.put("data",      position);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessage(message.toJSONString());   // ① 高频广播
        } catch (Exception e) {
            log.error("推送位置数据失败", e);
        }
    }

    // ─────────────────── 低频关键数据（直连 WebSocket，tryLock(1s) 确保送达） ───────────────────

    /**
     * 推送导航路径（path_update）
     * 路径规划一次触发一次，必须送达，使用可靠广播。
     */
    public void pushPath(Object pathData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "path_update");
            message.put("data",      pathData);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送路径数据失败", e);
        }
    }

    /**
     * 推送导航状态（nav_status：成功/失败/取消）
     * 状态变更事件，必须送达。
     */
    public void pushNavStatus(Object statusData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "nav_status");
            message.put("data",      statusData);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送导航状态失败", e);
        }
    }

    /**
     * 推送地图数据（map，低频）
     */
    public void pushMap(Object mapData) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      "map");
            message.put("data",      mapData);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("推送地图数据失败", e);
        }
    }

    /**
     * 推送定位状态（localization_status：globalizing / converged）
     * 全局重定位触发/收敛通知，必须送达。
     *
     * @param status "globalizing" | "converged" | "failed"
     */
    public void pushLocalizationStatus(String status) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type",      "localization_status");
            payload.put("status",    status);
            payload.put("waitMs",    15000);               // 前端倒计时参考值
            payload.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessageReliable(payload.toJSONString());  // ② 可靠广播
            log.info("📡 推送 localization_status: {}", status);
        } catch (Exception e) {
            log.warn("推送 localization_status 失败: {}", e.getMessage());
        }
    }

    // ─────────────────── 通用推送接口 ───────────────────

    /**
     * 推送给指定用户（单点，走 Redis 保留集群广播能力）
     */
    public void pushToUser(String userId, String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.sendMessage(userId, message.toJSONString());
        } catch (Exception e) {
            log.error("推送消息到用户失败: userId={}", userId, e);
        }
    }

    /**
     * 高频广播（外部调用入口，对应 pushMessage tryLock 丢帧）
     * 适合 ROS2 实时传感器数据。
     */
    public void pushToAll(String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessage(message.toJSONString());          // ① 高频广播
        } catch (Exception e) {
            log.error("高频广播失败: type={}", type, e);
        }
    }

    /**
     * 可靠广播（外部调用入口，对应 pushMessageReliable tryLock(1s)）
     * 适合 path_update / nav_status 等关键消息。
     */
    public void pushToAllReliable(String type, Object data) {
        try {
            JSONObject message = new JSONObject();
            message.put("type",      type);
            message.put("data",      data);
            message.put("timestamp", System.currentTimeMillis());
            webSocket.pushMessageReliable(message.toJSONString());  // ② 可靠广播
        } catch (Exception e) {
            log.error("可靠广播失败: type={}", type, e);
        }
    }
}