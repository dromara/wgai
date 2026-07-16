package org.jeecg.modules.ros2.service;

import com.github.s7connector.api.DaveArea;
import com.github.s7connector.api.S7Connector;
import com.github.s7connector.api.factory.S7ConnectorFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AGV 底盘硬件服务 — Siemens Smart 200 PLC 接口 (v3)
 *
 * ─── PLC 地址规范 ─────────────────────────────────────────────────────────
 *
 *  ◆ 方向控制 M 区 (置1启动, 置0停止):
 *    M1.0  前进
 *    M1.1  后退
 *    M1.2  左转  (单独=原地左转; + M1.0=左前转)
 *    M1.3  右转  (单独=原地右转; + M1.0=右前转)
 *    M1.4  直行模式  (先置1切到直行, 再配合 M1.0/M1.1)
 *    M1.5  平移模式  (先置1切到平移, 再配合 M1.0/M1.1)
 *
 *  ◆ 速度/角度 V 区 Word (2字节有符号整数, 大端):
 *    VW500  目标速度  0 ~ 3000 RPM
 *    VW550  转弯角度  ±45°(直行模式) / ±12°(平移模式)
 *
 *  ◆ 状态反馈 V 区 (只读位):
 *    V1.2  前进运行中
 *    V1.3  后退运行中
 *    V1.4  左转运行中
 *    V1.5  右转运行中
 *    V1.6  直行运行中
 *    V1.7  平移运行中
 *
 * ─── S7-200 Smart V 区访问说明 ────────────────────────────────────────────
 *  在 Dave/libnodave 协议中, S7-200 的 V 内存以 DB1 形式访问:
 *    DaveArea.DB, areaNumber=1, byteOffset = VW地址
 *  若 s7connector 版本不支持, 可将 VW500/VW550 改映射到 MW500/MW550
 *  (DaveArea.FLAGS), 并同步修改 PLC 程序中的地址。
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class RobotHardwareService {

    // ======================== 配置项 ========================

    @Value("${plc.host:192.168.0.242}")
    private String plcHost;

    @Value("${plc.rack:0}")
    private int plcRack;

    @Value("${plc.slot:1}")
    private int plcSlot;

    @Value("${plc.enabled:true}")
    private boolean plcEnabled;

    /** 速度地址: 模拟器默认 VW500, 真实 PLC 可配置为 VD500 */
    @Value("${plc.speed.address:500}")
    private int speedAddress;

    /** 转弯角度地址: 模拟器默认 VW550, 真实 PLC 可配置为 VD200 */
    @Value("${plc.angle.address:550}")
    private int angleAddress;

    /** 数值写入宽度: WORD=16bit(VW), DWORD=32bit(VD) */
    @Value("${plc.value.type:WORD}")
    private String valueType;

    /** 真实车要求按住 M3.3/M3.4 才允许 AGV 动作 */
    @Value("${plc.hold.start.program:true}")
    private boolean holdStartProgram;

    @Value("${plc.hold.start.device:true}")
    private boolean holdStartDevice;

    /** 原地旋转使用 M3.5, 方向由角度正负决定 */
    @Value("${plc.hold.rotate.bit:true}")
    private boolean holdRotateBit;

    /** 看门狗超时(ms): 超过此时间无新指令则自动停车 */
    @Value("${plc.watchdog.ms:500}")
    private long watchdogMs;

    /** PLC 控制日志输出间隔(ms), 避免 /cmd_vel 高频刷屏 */
    @Value("${plc.control.log.interval.ms:500}")
    private long controlLogIntervalMs;

    /** PLC 急停通知 M 位脉冲保持时间 */
    @Value("${plc.emergency.stop.pulse.ms:100}")
    private long emergencyStopPulseMs;

    /** 最大线速度(m/s), 对应 3000 RPM */
    @Value("${plc.max.linear.vel:0.8}")
    private double maxLinearVel;

    /** 角速度(rad/s) → 转向角(°) 缩放系数, 根据实际底盘调整 */
    @Value("${plc.angular.scale:30.0}")
    private double angularToDegreesScale;

    /** 被认为是"直行"的角度阈值(°), 小于此值走直行模式 M1.4 */
    @Value("${plc.straight.threshold.deg:5}")
    private int straightThresholdDeg;

    /** 障碍物紧急停车距离(m), 0 = 禁用 */
    @Value("${plc.obstacle.stop.distance:0.35}")
    private double obstacleStopDistance;

    // ======================== M 区位地址 (编码: 高字节=字节号, 低字节=位号) ========================

    private static final int M_FORWARD    = 0x10;  // M1.0 前进
    private static final int M_BACKWARD   = 0x11;  // M1.1 后退
    private static final int M_TURN_LEFT  = 0x12;  // M1.2 左转
    private static final int M_TURN_RIGHT = 0x13;  // M1.3 右转
    private static final int M_STRAIGHT   = 0x14;  // M1.4 直行模式
    private static final int M_LATERAL    = 0x15;  // M1.5 平移模式

    private static final int M_START_PROGRAM = 0x33;  // M3.3 AGV 小车启动程序
    private static final int M_START_DEVICE  = 0x34;  // M3.4 AGV 小车启动设备
    private static final int M_ROTATE        = 0x35;  // M3.5 AGV 小车旋转
    private static final int M_EMERGENCY_STOP = 0x60; // M6.0 PLC 急停通知

    // ======================== V 区字地址 (VW = 2字节有符号整数) ========================

    private static final int DEFAULT_SPEED_ADDRESS = 500;   // simulator: VW500
    private static final int DEFAULT_ANGLE_ADDRESS = 550;   // simulator: VW550

    // ======================== V 区状态反馈位地址 ========================

    private static final int V_FORWARD_RUN  = 0x12;  // V1.2
    private static final int V_BACKWARD_RUN = 0x13;  // V1.3
    private static final int V_LEFT_RUN     = 0x14;  // V1.4
    private static final int V_RIGHT_RUN    = 0x15;  // V1.5
    private static final int V_STRAIGHT_RUN = 0x16;  // V1.6
    private static final int V_LATERAL_RUN  = 0x17;  // V1.7

    // ======================== Ramp 限制 (每 20ms tick 最大变化量) ========================

    private static final int RPM_RAMP   = 100;  // RPM/tick
    private static final int ANGLE_RAMP = 3;    // °/tick

    // ======================== 运行模式枚举 ========================

    public enum DriveMode {
        STOP,
        STRAIGHT_FORWARD,   // M1.4 + M1.0
        STRAIGHT_BACKWARD,  // M1.4 + M1.1
        LEFT_FORWARD,       // M1.2 + M1.0
        RIGHT_FORWARD,      // M1.3 + M1.0
        LEFT_BACKWARD,      // M1.2 + M1.1
        RIGHT_BACKWARD,     // M1.3 + M1.1
        ROTATE_LEFT,        // M1.2 单独 (原地左转)
        ROTATE_RIGHT        // M1.3 单独 (原地右转)
    }

    // ======================== 内部指令封装 ========================

    private static class PlcCommand {
        final DriveMode mode;
        final int rpm;
        final int angleDeg;
        final double linear;
        final double angular;

        PlcCommand(DriveMode mode, int rpm, int angleDeg, double linear, double angular) {
            this.mode     = mode;
            this.rpm      = rpm;
            this.angleDeg = angleDeg;
            this.linear   = linear;
            this.angular  = angular;
        }

        @Override
        public String toString() {
            return "PlcCommand{mode=" + mode + ", rpm=" + rpm + ", angle=" + angleDeg + "°}";
        }
    }

    // ======================== PLC 状态 DTO ========================

    @Data
    public static class PlcStatus {
        private boolean connected;
        private String  mode;
        private int     rpm;
        private int     angleDeg;
        private double  obstacleDistance;   // -1 表示无数据
        private boolean obstacleOverride;   // true 表示因障碍物触发了停车
        // V 区反馈位
        private boolean forwardRunning;
        private boolean backwardRunning;
        private boolean leftRunning;
        private boolean rightRunning;
        private boolean straightRunning;
        private boolean lateralRunning;
        private String  valueType;
        private int     speedAddress;
        private int     angleAddress;
        private boolean holdStartProgram;
        private boolean holdStartDevice;
        private boolean holdRotateBit;
        private boolean emergencyStopActive;
    }

    // ======================== 运行状态 ========================

    private S7Connector s7Connector;
    private final AtomicBoolean connected    = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean emergencyStopActive = new AtomicBoolean(false);

    /** 待下发指令缓冲 */
    private volatile double  pendingLinear  = 0;
    private volatile double  pendingAngular = 0;
    private final AtomicBoolean hasPending  = new AtomicBoolean(false);
    private final AtomicLong lastCmdTimeMs  = new AtomicLong(0);

    /** 上次实际下发值 (用于 Ramp 平滑) */
    private volatile int       lastSentRpm   = 0;
    private volatile int       lastSentAngle = 0;
    private volatile DriveMode currentMode   = DriveMode.STOP;

    private volatile DriveMode lastLoggedMode = null;
    private volatile int lastLoggedTargetRpm = Integer.MIN_VALUE;
    private volatile int lastLoggedTargetAngle = Integer.MIN_VALUE;
    private volatile long lastControlLogMs = 0L;

    /** 障碍物距离监控 */
    private volatile double  minObstacleDistance = Double.MAX_VALUE;
    private volatile boolean obstacleOverride    = false;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "plc-worker");
        t.setDaemon(true);
        return t;
    });

    // ======================== 生命周期 ========================

    @PostConstruct
    public void init() {
        connectPlc();
        scheduler.scheduleAtFixedRate(this::sendTick,      100, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::statusReadTick,  1,  1, TimeUnit.SECONDS);
        log.info("AGV PLC bridge started | host={} | enabled={} | watchdog={}ms",
                plcHost, plcEnabled, watchdogMs);
        log.info("[PLC] valueType={} speedAddr={} angleAddr={} maxLinearVel={} angularScale={} startProgram={} startDevice={} rotateBit={}",
                getValueType(), speedAddress, angleAddress, maxLinearVel, angularToDegreesScale,
                holdStartProgram, holdStartDevice, holdRotateBit);
    }

    @PreDestroy
    public void destroy() {
        shuttingDown.set(true);
        log.info("⏹ 底盘服务关闭中...");
        try { forceStop(); } catch (Exception ignored) {}
        scheduler.shutdownNow();
        disconnectPlc();
    }

    // ======================== 对外接口 ========================

    /**
     * 接收来自 Nav2 /cmd_vel 或手动遥控的速度指令。
     *
     * @param linear  线速度 m/s  (正=前进, 负=后退)
     * @param angular 角速度 rad/s (正=左转, 负=右转)
     */
    public void sendVelocity(double linear, double angular) {
        if (shuttingDown.get()) return;
        if (emergencyStopActive.get()) {
            hasPending.set(false);
            pendingLinear = 0;
            pendingAngular = 0;
            return;
        }
        pendingLinear  = linear;
        pendingAngular = angular;
        hasPending.set(true);
        lastCmdTimeMs.set(System.currentTimeMillis());
    }

    /** 硬件级紧急停车: 直接清除所有 M 位 + 速度归零, 不经过任何缓冲 */
    public void emergencyStop() {
        log.warn("🛑 紧急停车触发!");
        try {
            emergencyStopActive.set(true);
            hasPending.set(false);
            pendingLinear = 0;
            pendingAngular = 0;
            lastCmdTimeMs.set(0);
            forceStop();
            pulseEmergencyStopBit();
            logControlCommand(new PlcCommand(DriveMode.STOP, 0, 0, 0, 0), 0, 0);
        } catch (Exception e) {
            log.error("急停失败, 请立即手动断电! {}", e.getMessage());
        }
    }

    /** 解除 Java 侧急停锁。注意: PLC 侧如有报警锁存, 仍需按现场复位流程处理。 */
    public void clearEmergencyStopLock() {
        emergencyStopActive.set(false);
        lastCmdTimeMs.set(0);
        hasPending.set(false);
        log.warn("[PLC急停] Java 侧急停锁已解除, 可重新接收 cmd_vel");
    }



    /**
     * 注入最新激光/点云最近障碍物距离(m)。
     * 由 ROS2WebSocketHandler 收到 /scan 后调用。
     * 内部判断是否需要触发紧急停车。
     */
    public void updateObstacleDistance(double minDistM) {
        this.minObstacleDistance = minDistM;
        if (obstacleStopDistance > 0 && minDistM < obstacleStopDistance) {
            if (!obstacleOverride) {
                obstacleOverride = true;
                log.warn("障碍物 {}m < 阈值 {}m, 触发停车",
                        String.format("%.2f", minDistM), String.format("%.2f", obstacleStopDistance));
                try { forceStop(); } catch (Exception ignored) {}
            }
        } else {
            obstacleOverride = false;
        }
    }

    /** 动态设置障碍物停车阈值 */
    public void setObstacleStopDistance(double distM) {
        this.obstacleStopDistance = distM;
        log.info("[PLC] 障碍物停车阈值更新: {}m", distM);
    }

    /** 读取 PLC 综合状态 (含 V 区反馈位), 供 REST API 返回给前端 */
    public PlcStatus readStatus() {
        PlcStatus st = new PlcStatus();
        st.setConnected(connected.get());
        st.setMode(currentMode.name());
        st.setRpm(lastSentRpm);
        st.setAngleDeg(lastSentAngle);
        st.setObstacleDistance(minObstacleDistance > 1e6 ? -1 : minObstacleDistance);
        st.setObstacleOverride(obstacleOverride);
        st.setValueType(getValueType().name());
        st.setSpeedAddress(speedAddressOrDefault());
        st.setAngleAddress(angleAddressOrDefault());
        st.setHoldStartProgram(holdStartProgram);
        st.setHoldStartDevice(holdStartDevice);
        st.setHoldRotateBit(holdRotateBit);
        st.setEmergencyStopActive(emergencyStopActive.get());

        if (plcEnabled && connected.get()) {
            try {
                st.setForwardRunning (readVBit(V_FORWARD_RUN));
                st.setBackwardRunning(readVBit(V_BACKWARD_RUN));
                st.setLeftRunning    (readVBit(V_LEFT_RUN));
                st.setRightRunning   (readVBit(V_RIGHT_RUN));
                st.setStraightRunning(readVBit(V_STRAIGHT_RUN));
                st.setLateralRunning (readVBit(V_LATERAL_RUN));
            } catch (Exception e) {
                log.trace("读状态位失败: {}", e.getMessage());
            }
        }
        return st;
    }

    public boolean isConnected()          { return connected.get(); }
    public DriveMode getCurrentMode()     { return currentMode; }
    public int  getLastRpm()              { return lastSentRpm; }
    public int  getLastAngle()            { return lastSentAngle; }
    public double getMinObstacleDistance(){ return minObstacleDistance; }
    public boolean isObstacleOverride()   { return obstacleOverride; }
    public boolean isEmergencyStopActive(){ return emergencyStopActive.get(); }

    // ======================== 周期发送 tick ========================

    private void sendTick() {
        try {
            if (emergencyStopActive.get()) {
                hasPending.set(false);
                return;
            }
            if (obstacleOverride) return;

            long last = lastCmdTimeMs.get();
            boolean stale = last > 0 && (System.currentTimeMillis() - last) > watchdogMs;

            // 看门狗:只在“指令过期”时负责停车,不再顺手 return 把新指令也跳过
            if (stale) {
                if (currentMode != DriveMode.STOP) {
                    log.warn("⚠ 看门狗触发 ({}ms 无新指令), 停车", watchdogMs);
                    forceStop();
                }
                return;   // 过期了本来也没有新鲜指令可发,return 合理
            }

            // 未过期:有新指令就发
            if (!hasPending.get()) return;
            hasPending.set(false);
            PlcCommand cmd = twistToPlcCommand(pendingLinear, pendingAngular);
            executeCommand(cmd);

        } catch (Exception e) {
            log.warn("sendTick 异常: {}", e.getMessage(), e);   // 带上堆栈
            tryReconnect();
        }
    }

    // ======================== Twist → PlcCommand 转换 ========================

    /**
     * 将 ROS Twist (linear.x / angular.z) 转换为 PLC 指令。
     *
     * 转换规则:
     *   RPM   = |linear| / maxLinearVel × 3000   ∈ [0, 3000]
     *   angle = angular × angularToDegreesScale  ∈ [-45, 45]
     *   mode  由 linear/angular 的符号与幅值决定
     */
    private PlcCommand twistToPlcCommand(double linear, double angular) {
        final double LIN_DEAD = 0.01;   // 线速度死区 m/s
        final double ANG_DEAD = 0.02;   // 角速度死区 rad/s

        boolean moving  = Math.abs(linear)  > LIN_DEAD;
        boolean turning = Math.abs(angular) > ANG_DEAD;

        // 全停
        if (!moving && !turning) {
            return new PlcCommand(DriveMode.STOP, 0, 0, linear, angular);
        }

        // RPM: 线速度 → 转速
        int rpm = moving
                ? (int)(Math.abs(linear) / maxLinearVel * 3000)
                : 150; // 原地旋转给最小驱动 RPM
        rpm = Math.max(0, Math.min(3000, rpm));

        // 角度: angular.z > 0 左转, angular.z < 0 右转, 写入 PLC 时保留正负号。
        int angleDeg;
        if (!moving && turning) {
            angleDeg = angular > 0 ? 45 : -45;
        } else {
            angleDeg = (int)(angular * angularToDegreesScale);
            angleDeg = Math.max(-45, Math.min(45, angleDeg));
        }

        // 模式决策
        boolean goForward  = linear  >  LIN_DEAD;
        boolean goBackward = linear  < -LIN_DEAD;
        boolean turnLeft   = angular >  ANG_DEAD;
        boolean turnRight  = angular < -ANG_DEAD;
        boolean isStraight = Math.abs(angleDeg) <= straightThresholdDeg;

        DriveMode mode;
        if (!moving) {
            mode = turnLeft ? DriveMode.ROTATE_LEFT : DriveMode.ROTATE_RIGHT;
        } else if (goForward) {
            if      (isStraight) mode = DriveMode.STRAIGHT_FORWARD;
            else if (turnLeft)   mode = DriveMode.LEFT_FORWARD;
            else                 mode = DriveMode.RIGHT_FORWARD;
        } else { // goBackward
            if      (isStraight) mode = DriveMode.STRAIGHT_BACKWARD;
            else if (turnLeft)   mode = DriveMode.LEFT_BACKWARD;
            else                 mode = DriveMode.RIGHT_BACKWARD;
        }

        return new PlcCommand(mode, rpm, angleDeg, linear, angular);
    }

    // ======================== 执行指令 ========================

    /**
     * 将 PlcCommand 写入 PLC。
     *
     * 模式切换流程:
     *   1. 速度/角度清零
     *   2. 关旧模式 M 位
     *   3. 等待 50ms (PLC 响应)
     *   4. 开新模式 M 位
     *   5. Ramp 平滑写入速度/角度
     */
    private synchronized void executeCommand(PlcCommand cmd) throws Exception {
        // ─── 模式切换 ────────────────────────────────────────────
        if (cmd.mode != currentMode) {
            log.info("[PLC] 模式切换: {} → {}", currentMode, cmd.mode);

            writeVNumber(speedAddressOrDefault(), 0);
            writeVNumber(angleAddressOrDefault(), 0);
            lastSentRpm   = 0;
            lastSentAngle = 0;

            clearAllDirectionBits();
            Thread.sleep(50);

            applyModeBits(cmd.mode);
            currentMode = cmd.mode;

            if (cmd.mode == DriveMode.STOP) {
                logControlCommand(cmd, 0, 0);
                return;
            }
            Thread.sleep(30);
        }

        if (currentMode == DriveMode.STOP) {
            logControlCommand(cmd, 0, 0);
            return;
        }

        // ─── Ramp 平滑 ────────────────────────────────────────────
        int rampedRpm   = rampStep(lastSentRpm,   cmd.rpm,      RPM_RAMP);
        int rampedAngle = rampStep(lastSentAngle, cmd.angleDeg, ANGLE_RAMP);

        writeVNumber(speedAddressOrDefault(), rampedRpm);
        writeVNumber(angleAddressOrDefault(), rampedAngle);

        lastSentRpm   = rampedRpm;
        lastSentAngle = rampedAngle;

        log.debug("[PLC] {} | rpm={} | angle={}°", currentMode, rampedRpm, rampedAngle);
        logControlCommand(cmd, rampedRpm, rampedAngle);
    }

    private void logControlCommand(PlcCommand cmd, int actualRpm, int actualAngle) {
        long now = System.currentTimeMillis();
        boolean changed = cmd.mode != lastLoggedMode
                || Math.abs(cmd.rpm - lastLoggedTargetRpm) >= 50
                || Math.abs(cmd.angleDeg - lastLoggedTargetAngle) >= 2;
        boolean intervalReached = now - lastControlLogMs >= Math.max(100, controlLogIntervalMs);
        if (!changed && !intervalReached) return;

        log.info("[PLC控制] 动作={} | ROS(linear.x={}, angular.z={}) | 目标RPM={} 实发RPM={} | 目标角度={}° 实发角度={}° | M点位={} | 写入={}{}速度, {}{}角度",
                driveModeLabel(cmd.mode),
                String.format("%.3f", cmd.linear),
                String.format("%.3f", cmd.angular),
                cmd.rpm,
                actualRpm,
                cmd.angleDeg,
                actualAngle,
                mBitsLabel(cmd.mode),
                getValueType().addressPrefix(),
                speedAddressOrDefault(),
                getValueType().addressPrefix(),
                angleAddressOrDefault());

        lastLoggedMode = cmd.mode;
        lastLoggedTargetRpm = cmd.rpm;
        lastLoggedTargetAngle = cmd.angleDeg;
        lastControlLogMs = now;
    }

    private String driveModeLabel(DriveMode mode) {
        switch (mode) {
            case STRAIGHT_FORWARD:  return "前进";
            case STRAIGHT_BACKWARD: return "后退";
            case LEFT_FORWARD:      return "前进左转";
            case RIGHT_FORWARD:     return "前进右转";
            case LEFT_BACKWARD:     return "后退左转";
            case RIGHT_BACKWARD:    return "后退右转";
            case ROTATE_LEFT:       return "原地左旋";
            case ROTATE_RIGHT:      return "原地右旋";
            case STOP:
            default:                return "停止";
        }
    }

    private String mBitsLabel(DriveMode mode) {
        StringBuilder sb = new StringBuilder();
        if (holdStartProgram && mode != DriveMode.STOP) appendBit(sb, "M3.3启动程序");
        if (holdStartDevice && mode != DriveMode.STOP) appendBit(sb, "M3.4启动设备");

        switch (mode) {
            case STRAIGHT_FORWARD:
                appendBit(sb, "M1.4直行");
                appendBit(sb, "M1.0前进");
                break;
            case STRAIGHT_BACKWARD:
                appendBit(sb, "M1.4直行");
                appendBit(sb, "M1.1后退");
                break;
            case LEFT_FORWARD:
                appendBit(sb, "M1.2左前转");
                appendBit(sb, "M1.0前进");
                break;
            case RIGHT_FORWARD:
                appendBit(sb, "M1.3右前转");
                appendBit(sb, "M1.0前进");
                break;
            case LEFT_BACKWARD:
                appendBit(sb, "M1.2左转");
                appendBit(sb, "M1.1后退");
                break;
            case RIGHT_BACKWARD:
                appendBit(sb, "M1.3右转");
                appendBit(sb, "M1.1后退");
                break;
            case ROTATE_LEFT:
            case ROTATE_RIGHT:
                if (holdRotateBit) {
                    appendBit(sb, "M3.5旋转");
                } else {
                    appendBit(sb, mode == DriveMode.ROTATE_LEFT ? "M1.2左转" : "M1.3右转");
                }
                break;
            case STOP:
            default:
                appendBit(sb, "全部释放");
                break;
        }
        return sb.toString();
    }

    private void appendBit(StringBuilder sb, String text) {
        if (sb.length() > 0) sb.append("+");
        sb.append(text);
    }

    /** 打开新模式的 M 位 */
    private void applyModeBits(DriveMode mode) throws Exception {
        applyRunEnableBits(mode != DriveMode.STOP);
        writeMBit(M_ROTATE, holdRotateBit && (mode == DriveMode.ROTATE_LEFT || mode == DriveMode.ROTATE_RIGHT));

        switch (mode) {
            case STRAIGHT_FORWARD:
                writeMBit(M_STRAIGHT, true);
                writeMBit(M_FORWARD,  true);
                break;
            case STRAIGHT_BACKWARD:
                writeMBit(M_STRAIGHT,  true);
                writeMBit(M_BACKWARD,  true);
                break;
            case LEFT_FORWARD:
                writeMBit(M_TURN_LEFT, true);
                writeMBit(M_FORWARD,   true);
                break;
            case RIGHT_FORWARD:
                writeMBit(M_TURN_RIGHT, true);
                writeMBit(M_FORWARD,    true);
                break;
            case LEFT_BACKWARD:
                writeMBit(M_TURN_LEFT, true);
                writeMBit(M_BACKWARD,  true);
                break;
            case RIGHT_BACKWARD:
                writeMBit(M_TURN_RIGHT, true);
                writeMBit(M_BACKWARD,   true);
                break;
            case ROTATE_LEFT:
            case ROTATE_RIGHT:
                if (!holdRotateBit) {
                    writeMBit(mode == DriveMode.ROTATE_LEFT ? M_TURN_LEFT : M_TURN_RIGHT, true);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 一次性清除 M1.0 ~ M1.5 (保留 M1.6 M1.7)。
     * 使用读-改-写减少通信次数。
     */
    private synchronized void clearAllDirectionBits() throws Exception {
        if (!plcEnabled) {
            currentMode = DriveMode.STOP;
            return;
        }
        if (connected.get() && s7Connector != null) {
            byte[] buf = s7Connector.read(DaveArea.FLAGS, 0, 1, 1);
            buf[0] &= (byte) 0b11000000; // 清 bit0~bit5, 保留 bit6 bit7
            s7Connector.write(DaveArea.FLAGS, 0, 1, buf);
            writeMBit(M_ROTATE, false);
            applyRunEnableBits(false);
        }
        currentMode   = DriveMode.STOP;
        lastSentRpm   = 0;
        lastSentAngle = 0;
    }

    /** 强制停车: 速度清零 + 所有方向 M 位关闭 */
    private synchronized void forceStop() throws Exception {
        writeVNumber(speedAddressOrDefault(), 0);
        writeVNumber(angleAddressOrDefault(), 0);
        clearAllDirectionBits();
    }

    private synchronized void pulseEmergencyStopBit() throws Exception {
        log.warn("[PLC急停] 通知 PLC: M6.0=1 -> {}ms -> M6.0=0", emergencyStopPulseMs);
        writeMBit(M_EMERGENCY_STOP, true);
        try {
            Thread.sleep(Math.max(20, emergencyStopPulseMs));
        } finally {
            writeMBit(M_EMERGENCY_STOP, false);
        }
    }

    private void applyRunEnableBits(boolean running) throws Exception {
        if (holdStartProgram) {
            writeMBit(M_START_PROGRAM, running);
        }
        if (holdStartDevice) {
            writeMBit(M_START_DEVICE, running);
        }
    }

    private int rampStep(int current, int target, int maxStep) {
        int diff = target - current;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Integer.signum(diff) * maxStep;
    }

    /** 定时读取 V1 字节状态反馈, 用于日志与前端轮询 */
    private void statusReadTick() {
        if (!plcEnabled || !connected.get() || s7Connector == null) return;
        try {
            byte[] v = s7Connector.read(DaveArea.DB, 1, 1, 1); // V1 字节
            if (log.isDebugEnabled()) {
                log.debug("[PLC反馈] 前={} 后={} 左={} 右={} 直行={} 平移={}",
                        (v[0] & 0x04) != 0, (v[0] & 0x08) != 0,
                        (v[0] & 0x10) != 0, (v[0] & 0x20) != 0,
                        (v[0] & 0x40) != 0, (v[0] & 0x80) != 0);
            }
        } catch (Exception e) {
            log.trace("状态读取失败: {}", e.getMessage());
        }
    }

    // ======================== PLC 连接管理 ========================

    private synchronized void connectPlc() {
        if (!plcEnabled) {
            log.info("[PLC] 调试模式 (plc.enabled=false), 不实际连接");
            return;
        }
        try {
            s7Connector = S7ConnectorFactory
                    .buildTCPConnector()
                    .withHost(plcHost)
                    .withRack(plcRack)
                    .withSlot(plcSlot)
                    .withTimeout(2000)
                    .build();
            connected.set(true);
            log.info("✅ [PLC] 连接成功: {} rack={} slot={}", plcHost, plcRack, plcSlot);
        } catch (Exception e) {
            connected.set(false);
            log.error("❌ [PLC] 连接失败: {}", e.getMessage());
        }
    }

    private synchronized void disconnectPlc() {
        if (s7Connector == null) return;
        try { s7Connector.close(); } catch (Exception ignored) {}
        s7Connector = null;
        connected.set(false);
    }

    private void tryReconnect() {
        if (!plcEnabled || shuttingDown.get()) return;
        log.info("[PLC] 尝试重连...");
        disconnectPlc();
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        connectPlc();
    }

    // ======================== S7 底层读写 ========================

    /**
     * 写 VW (V 区 2字节有符号整数, 大端) 到指定字节地址。
     * S7-200 Smart V 内存在 Dave 协议中映射为 DB1。
     *
     * @param byteAddr VW 字节地址, 如 VW500 → 500
     * @param value    有符号整数 [-32768, 32767]
     */
    private synchronized void writeVNumber(int byteAddr, int value) throws Exception {
        if (!plcEnabled) {
            log.trace("[PLC调试] V{}{} = {}", getValueType().addressPrefix(), byteAddr, value);
            return;
        }
        if (!connected.get() || s7Connector == null) {
            log.warn("[PLC] 未连接, 跳过 V{}{} 写入", getValueType().addressPrefix(), byteAddr);
            return;
        }

        byte[] buf;
        if (getValueType() == PlcValueType.DWORD) {
            buf = new byte[4];
            buf[0] = (byte)((value >> 24) & 0xFF);
            buf[1] = (byte)((value >> 16) & 0xFF);
            buf[2] = (byte)((value >> 8) & 0xFF);
            buf[3] = (byte)(value & 0xFF);
        } else {
            if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
            if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
            buf = new byte[2];
            buf[0] = (byte)((value >> 8) & 0xFF);
            buf[1] = (byte)(value & 0xFF);
        }

        // V 内存在 S7-200 Smart 中以 DB1 方式访问
        s7Connector.write(DaveArea.DB, 1, byteAddr, buf);
    }

    /**
     * 写 M 位 (Merker/Flags 区, 读-改-写)。
     * address 编码: 高字节=字节地址, 低字节=位地址
     * 示例: M1.0=0x10, M1.4=0x14
     */
    private synchronized void writeMBit(int address, boolean value) throws Exception {
        if (!plcEnabled) {
            log.trace("[PLC调试] M{}.{} = {}", address >> 4, address & 0x0F, value);
            return;
        }
        if (!connected.get() || s7Connector == null) return;

        int byteAddr = address >> 4;
        int bitAddr  = address & 0x0F;

        byte[] cur = s7Connector.read(DaveArea.FLAGS, 0, 1, byteAddr);
        if (value) cur[0] |=  (byte)(1 << bitAddr);
        else       cur[0] &= (byte)~(1 << bitAddr);
        s7Connector.write(DaveArea.FLAGS, 0, byteAddr, cur);
    }

    /**
     * 读 V 位 (V 区单个位)。
     * address 编码: 高字节=字节地址, 低字节=位地址
     * 示例: V1.2=0x12
     */
    private synchronized boolean readVBit(int address) throws Exception {
        if (!plcEnabled || !connected.get() || s7Connector == null) return false;
        int byteAddr = address >> 4;
        int bitAddr  = address & 0x0F;
        byte[] buf = s7Connector.read(DaveArea.DB, 1, 1, byteAddr);
        return (buf[0] & (1 << bitAddr)) != 0;
    }

    private int speedAddressOrDefault() {
        return speedAddress > 0 ? speedAddress : DEFAULT_SPEED_ADDRESS;
    }

    private int angleAddressOrDefault() {
        return angleAddress > 0 ? angleAddress : DEFAULT_ANGLE_ADDRESS;
    }

    private PlcValueType getValueType() {
        return "DWORD".equalsIgnoreCase(valueType) || "VD".equalsIgnoreCase(valueType)
                ? PlcValueType.DWORD
                : PlcValueType.WORD;
    }

    private enum PlcValueType {
        WORD("W"),
        DWORD("D");

        private final String addressPrefix;

        PlcValueType(String addressPrefix) {
            this.addressPrefix = addressPrefix;
        }

        String addressPrefix() {
            return addressPrefix;
        }
    }
}
