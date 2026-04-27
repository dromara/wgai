package org.jeecg.modules.message.websocket;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import org.jeecg.modules.demo.tab.service.impl.TabAiHistoryServiceImpl;
import org.jeecg.modules.system.mapper.SysCategoryMapper;
import org.jeecg.modules.system.service.ISysDepartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * @Author scott
 * @Date 2019/11/29 9:41
 * @Description: 此注解相当于设置访问URL
 */
@Component
@Slf4j
@ServerEndpoint("/websocket/{userId}")
public class WebSocket {
    
    /**线程安全Map*/
    private static ConcurrentHashMap<String, Session> sessionPool = new ConcurrentHashMap<>();

    /**
     * Redis触发监听名字SpringContextUtils
     */
    public static final String REDIS_TOPIC_NAME = "socketHandler";
    @Resource
    private JeecgRedisClient jeecgRedisClient;




    //==========【websocket接受、推送消息等方法 —— 具体服务节点推送ws消息】========================================================================================
    @OnOpen
    public void onOpen(Session session, @PathParam(value = "userId") String userId) {
        try {
            sessionPool.put(userId, session);
            log.info("连接人：{}",userId);
            if(userId.indexOf("rtsp")>-1){
                System.out.println("连接开始了");

                ITabAiHistoryService baseMapper = (ITabAiHistoryService) SpringContextUtils.getBean("tabAiHistoryServiceImpl");
                baseMapper.sendUrlFLV();//sendUrl();
            }

            log.info("【系统 WebSocket】有新的连接，总数为:" + sessionPool.size());
        } catch (Exception e) {
        }
    }

    @OnClose
    public void onClose(@PathParam("userId") String userId) {
        try {
            sessionPool.remove(userId);
            log.info("【系统 WebSocket】连接断开，总数为:" + sessionPool.size());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * ws推送消息
     *
     * @param userId
     * @param message
     */
    public void pushMessage(String userId, String message) {
        for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
            //userId key值= {用户id + "_"+ 登录token的md5串}
            //TODO vue2未改key新规则，暂时不影响逻辑
            if (item.getKey().contains(userId)) {
                Session session = item.getValue();
                try {
                    //update-begin-author:taoyan date:20211012 for: websocket报错 https://gitee.com/jeecg/jeecg-boot/issues/I4C0MU
                    synchronized (session){
                        log.info("【系统 WebSocket】推送单人消息:" + message);
                        session.getBasicRemote().sendText(message);
                    }
                    //update-end-author:taoyan date:20211012 for: websocket报错 https://gitee.com/jeecg/jeecg-boot/issues/I4C0MU
                } catch (Exception e) {
                    log.error(e.getMessage(),e);
                }
            }
        }
    }

    /**
     * ws遍历群发消息
     */
    public void pushMessage(String message) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                Session session = item.getValue();
                try {
                    if (session != null && session.isOpen()) {
                        synchronized (session) {
                            session.getAsyncRemote().sendText(message);
                        }
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
         //   log.info("【系统 WebSocket】群发消息:" + message.substring(0,30));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * ws遍历群发消息
     */
    public void pushMessageByte( byte[] encodedData) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                Session session = item.getValue();
                try {
                    synchronized(session){
                        session.getBasicRemote().sendBinary(ByteBuffer.wrap(encodedData));
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("【系统 WebSocket】群发消息:" + encodedData);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void broadcastFrame( ByteBuffer buffer) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                Session session = item.getValue();
                try {
                    synchronized(session){
                        session.getBasicRemote().sendBinary(buffer);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("【系统 WebSocket】群发消息:" + buffer);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
    /**
     * ws接受客户端消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam(value = "userId") String userId) {
        log.info("1【系统 WebSocket】收到客户端消息:" + message,userId);
        if(userId.indexOf("audio")>-1){
            log.info("【系统 WebSocket】收到客户端audio消息");
        }else {


            if (!"ping".equals(message) && !WebsocketConst.CMD_CHECK.equals(message)) {
                log.info("【系统 WebSocket】收到客户端消息:" + message);
            } else {
                log.debug("【系统 WebSocket】收到客户端消息:" + message);
            }

            //------------------------------------------------------------------------------
            JSONObject obj = new JSONObject();
            //业务类型
            obj.put(WebsocketConst.MSG_CMD, WebsocketConst.CMD_CHECK);
            //消息内容
            obj.put(WebsocketConst.MSG_TXT, "心跳响应");
            this.pushMessage(userId, obj.toJSONString());
        }
        //------------------------------------------------------------------------------
    }

    /**
     * 配置错误信息处理
     *
     * @param session
     * @param t
     */
    @OnError
    public void onError(Session session, Throwable t) {
        log.warn("【系统 WebSocket】消息出现错误");
        t.printStackTrace();
    }
    //==========【系统 WebSocket接受、推送消息等方法 —— 具体服务节点推送ws消息】========================================================================================
    

    //==========【采用redis发布订阅模式——推送消息】========================================================================================
    /**
     * 后台发送消息到redis
     *
     * @param message
     */
    public void sendMessage(String message) {
        //log.info("【系统 WebSocket】广播消息:" + message);
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", "");
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    /**
     * 此为单点消息 redis
     *
     * @param userId
     * @param message
     */
    public void sendMessage(String userId, String message) {
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", userId);
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    /**
     * 此为单点消息(多人) redis
     *
     * @param userIds
     * @param message
     */
    public void sendMessage(String[] userIds, String message) {
        for (String userId : userIds) {
            sendMessage(userId, message);
        }
    }
    //=======【采用redis发布订阅模式——推送消息】==========================================================================================
    
}