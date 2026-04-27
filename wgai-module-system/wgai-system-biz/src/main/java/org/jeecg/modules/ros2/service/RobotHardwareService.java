package org.jeecg.modules.ros2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机器人底盘硬件控制服务 —— 西门子 PLC 版
 *
 * 通信协议：S7 Protocol（Siemens 原生协议，TCP 端口 102）
 * Java 库：Moka7（纯 Java 实现，无需安装 snap7 本地库）
 *
 * Maven 依赖（pom.xml 添加）：
 * <dependency>
 *     <groupId>com.github.s7connector</groupId>
 *     <artifactId>s7connector</artifactId>
 *     <version>2.1</version>
 * </dependency>
 *
 * 或者使用 Moka7：
 * <dependency>
 *     <groupId>org.moka7</groupId>
 *     <artifactId>moka7</artifactId>
 *     <version>1.0.2</version>
 * </dependency>
 *
 * PLC 侧需要在 DB 块中定义以下变量：
 * +------+--------+---------+----------------------------+
 * | 偏移 | 类型   | 变量名  | 说明                       |
 * +------+--------+---------+----------------------------+
 * | 0.0  | REAL   | LinearV | 线速度 m/s（前进+/后退-）  |
 * | 4.0  | REAL   | AngularW| 角速度 rad/s（左转+/右转-）|
 * | 8.0  | BOOL   | Enable  | 使能位（true=允许运动）    |
 * | 10.0 | INT    | HeartBeat| 心跳计数（Java每秒+1）   |
 * +------+--------+---------+----------------------------+
 *
 * application.properties 配置：
 *   plc.host=192.168.0.1       PLC IP 地址
 *   plc.rack=0                 机架号（通常为0）
 *   plc.slot=1                 槽号（S7-300通常为2，S7-1200/1500通常为1）
 *   plc.db=100                 速度控制 DB 块号（DB100）
 *   plc.enabled=true           false=调试模式（只打日志不写PLC）
 *   plc.watchdog.ms=500        看门狗超时（ms），超时自动停车
 */
@Slf4j
@Service
public class RobotHardwareService {

    // ===================== 可修改的配置 =====================

    @Value("${plc.host:192.168.0.1}")
    private String plcHost;

    @Value("${plc.rack:0}")
    private int plcRack;

    @Value("${plc.slot:1}")
    private int plcSlot;

    /** DB 块编号，例如 100 代表 DB100 */
    @Value("${plc.db:100}")
    private int dbNumber;

    /** false=调试模式，只打印日志，不实际写PLC，上线前改为true */
    @Value("${plc.enabled:false}")
    private boolean plcEnabled;

    /** 看门狗超时时间(ms)，超过此时间没收到新指令就发停车 */
    @Value("${plc.watchdog.ms:500}")
    private long watchdogMs;

    // ─── DB 内变量偏移量（按你实际 DB 定义修改）───────────────────────
    private static final int OFFSET_LINEAR   = 0;   // REAL, 4字节，线速度
    private static final int OFFSET_ANGULAR  = 4;   // REAL, 4字节，角速度
    private static final int OFFSET_ENABLE   = 8;   // BOOL, 1字节，使能位
    private static final int OFFSET_HEARTBEAT= 10;  // INT,  2字节，心跳

    // ===================== PLC 连接 =====================

    /** Moka7 S7Client 实例（通过反射调用避免编译期强依赖） */
    private Object s7Client = null;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    // ===================== 速度限流 & 看门狗 =====================

    private volatile double pendingLinear  = 0;
    private volatile double pendingAngular = 0;
    private final AtomicBoolean hasPending = new AtomicBoolean(false);
    private volatile long lastCmdTimeMs    = 0;
    private volatile int heartbeatCount    = 0;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // ===================== 初始化 =====================

   // @PostConstruct
    public void init() {
        // ① 连接 PLC
        connectPlc();

        // ② 50Hz 定时发送速度（限流，合并高频指令）
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                // 看门狗：超时停车
                if (lastCmdTimeMs > 0
                        && System.currentTimeMillis() - lastCmdTimeMs > watchdogMs) {
                    writeToPLC(0, 0);
                    lastCmdTimeMs = 0;
                    log.debug("[看门狗] 超过{}ms无指令，已停车", watchdogMs);
                    return;
                }
                if (hasPending.getAndSet(false)) {
                    writeToPLC(pendingLinear, pendingAngular);
                }
            }
        }, 0, 20, TimeUnit.MILLISECONDS);

        // ③ 1秒更新一次心跳（PLC侧可据此判断 Java 是否在线）
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                writeHeartbeat();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        log.info("[PLC] 关闭连接，停车");
        writeToPLC(0, 0);
        scheduler.shutdownNow();
        disconnectPlc();
    }

    // ===================== 核心接口 =====================

    /**
     * 发送速度指令（由 ROS2WebSocketHandler 和 RobotController 调用）
     *
     * @param linear  线速度 m/s（正=前进，负=后退）
     * @param angular 角速度 rad/s（正=左转，负=右转）
     */
    public void sendVelocity(double linear, double angular) {
        // 安全限速（按 PLC 允许范围修改）
        linear  = clamp(linear,  -1.0, 1.0);
        angular = clamp(angular, -2.0, 2.0);

        pendingLinear  = linear;
        pendingAngular = angular;
        hasPending.set(true);
        lastCmdTimeMs = System.currentTimeMillis();
    }

    public boolean isConnected() {
        return connected.get();
    }

    // ===================== PLC 连接管理 =====================

    private void connectPlc() {
        if (!plcEnabled) {
            log.info("[PLC] 调试模式，不实际连接 PLC（plc.enabled=false）");
            return;
        }
        try {
            // 使用 Moka7 的 S7Client（需在 pom.xml 中引入 moka7 依赖）
            Class<?> clientClass = Class.forName("org.moka7.S7Client");
            s7Client = clientClass.getDeclaredConstructor().newInstance();

            // ConnectTo(ip, rack, slot) → 0 = 成功
            java.lang.reflect.Method connectMethod = clientClass
                    .getMethod("ConnectTo", String.class, int.class, int.class);
            int result = (int) connectMethod.invoke(s7Client, plcHost, plcRack, plcSlot);

            if (result == 0) {
                connected.set(true);
                log.info("✅ [PLC] 连接成功: {} rack={} slot={} DB={}", plcHost, plcRack, plcSlot, dbNumber);
                // 上电时写使能位
                writeEnable(true);
            } else {
                log.error("❌ [PLC] 连接失败，错误码: {} (host={})", result, plcHost);
            }
        } catch (ClassNotFoundException e) {
            log.error("[PLC] Moka7 库未找到，请在 pom.xml 添加依赖:\n"
                    + "  <dependency>\n"
                    + "    <groupId>org.moka7</groupId>\n"
                    + "    <artifactId>moka7</artifactId>\n"
                    + "    <version>1.0.2</version>\n"
                    + "  </dependency>");
        } catch (Exception e) {
            log.error("[PLC] 连接异常: {}", e.getMessage());
        }
    }

    private void disconnectPlc() {
        try {
            if (s7Client != null) {
                writeEnable(false); // 关闭使能
                s7Client.getClass().getMethod("Disconnect").invoke(s7Client);
                connected.set(false);
                log.info("[PLC] 已断开连接");
            }
        } catch (Exception e) {
            log.warn("[PLC] 断开异常: {}", e.getMessage());
        }
    }

    // ===================== 写入 PLC =====================

    /**
     * 将线速度和角速度写入 PLC DB 块
     *
     * DB 数据布局（按实际修改 OFFSET_* 常量）：
     *   DB100.DBD0  = LinearV  (REAL, 4字节)
     *   DB100.DBD4  = AngularW (REAL, 4字节)
     *   DB100.DBX8.0= Enable   (BOOL, 1字节)
     *   DB100.DBW10 = HeartBeat(INT,  2字节)
     */
    private synchronized void writeToPLC(double linear, double angular) {
        if (!plcEnabled) {
            if (Math.abs(linear) > 0.001 || Math.abs(angular) > 0.001) {
                log.info("[PLC调试] linear={} m/s  angular={} rad/s  →（调试模式，未写入）",
                        String.format("%.3f", linear), String.format("%.3f", angular));
            }
            return;
        }

        if (!connected.get()) {
            log.warn("[PLC] 未连接，尝试重连...");
            connectPlc();
            if (!connected.get()) return;
        }

        try {
            // 构造写入缓冲区（8字节：2个 REAL）
            byte[] buffer = new byte[8];
            floatToBytes((float) linear,  buffer, 0); // DBD0
            floatToBytes((float) angular, buffer, 4); // DBD4

            // DBWrite(dbNumber, start, size, buffer) → 0 = 成功
            java.lang.reflect.Method writeMethod = s7Client.getClass()
                    .getMethod("DBWrite", int.class, int.class, int.class, byte[].class);
            int result = (int) writeMethod.invoke(s7Client, dbNumber, OFFSET_LINEAR, 8, buffer);

            if (result != 0) {
                log.warn("[PLC] 写入失败，错误码: {}，尝试重连", result);
                connected.set(false);
                connectPlc();
            } else {
                log.debug("[PLC] 写入成功 DB{}.DBD{}: linear={} angular={}",
                        dbNumber, OFFSET_LINEAR,
                        String.format("%.3f", linear), String.format("%.3f", angular));
            }
        } catch (Exception e) {
            log.error("[PLC] 写入异常: {}", e.getMessage());
            connected.set(false);
        }
    }

    /** 写使能位（DB100.DBX8.0） */
    private void writeEnable(boolean enable) {
        if (!plcEnabled || !connected.get()) return;
        try {
            byte[] buf = new byte[]{ (byte)(enable ? 1 : 0) };
            java.lang.reflect.Method m = s7Client.getClass()
                    .getMethod("DBWrite", int.class, int.class, int.class, byte[].class);
            m.invoke(s7Client, dbNumber, OFFSET_ENABLE, 1, buf);
            log.info("[PLC] 使能位 = {}", enable);
        } catch (Exception e) {
            log.warn("[PLC] 写使能位失败: {}", e.getMessage());
        }
    }

    /** 写心跳计数（DB100.DBW10），PLC侧可据此判断 Java 是否在线 */
    private void writeHeartbeat() {
        if (!plcEnabled || !connected.get()) return;
        try {
            heartbeatCount = (heartbeatCount + 1) % 32767;
            byte[] buf = shortToBytes((short) heartbeatCount);
            java.lang.reflect.Method m = s7Client.getClass()
                    .getMethod("DBWrite", int.class, int.class, int.class, byte[].class);
            m.invoke(s7Client, dbNumber, OFFSET_HEARTBEAT, 2, buf);
        } catch (Exception e) {
            log.debug("[PLC] 写心跳失败: {}", e.getMessage());
        }
    }

    // ===================== 字节转换工具 =====================

    /**
     * float → 大端字节序（Siemens PLC 使用大端序）
     */
    private void floatToBytes(float value, byte[] buf, int offset) {
        int bits = Float.floatToIntBits(value);
        buf[offset]     = (byte)((bits >> 24) & 0xFF); // 大端
        buf[offset + 1] = (byte)((bits >> 16) & 0xFF);
        buf[offset + 2] = (byte)((bits >>  8) & 0xFF);
        buf[offset + 3] = (byte)( bits        & 0xFF);
    }

    /**
     * short → 大端字节序
     */
    private byte[] shortToBytes(short value) {
        return new byte[]{ (byte)((value >> 8) & 0xFF), (byte)(value & 0xFF) };
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}