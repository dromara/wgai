package org.jeecg.modules.ros2.service;

import com.github.xingshuangs.iot.protocol.s7.enums.EPlcType;
import com.github.xingshuangs.iot.protocol.s7.service.S7PLC;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * AGV 底盘硬件服务 — Siemens Smart 200 PLC 接口 (v4, 真实点位表)
 *
 * 使用 com.github.xingshuangs:iot-communication 的 S7PLC 客户端(用法参考
 * WgSocket.java: new S7PLC(EPlcType.S200_SMART, ip, port, rack, slot),
 * 布尔量用 "V字节.位"(如 "V1001.5"),字/双字量用 "V字节地址"(如 "V1004"/"V1212"),
 * 数据类型由调用的方法决定(writeInt16/writeInt32/writeBoolean)。
 *
 * ─── 真实 PLC 点位表 ───────────────────────────────────────────────────────
 *  ◆ 控制点位(写):
 *    VW1002  模式设置  0=直行 1=原地旋转 2=平移
 *    VW1004  速度设置  0~3000 r/min
 *    VW1006  转弯角度  精度0.1°(写入值=角度×10),负=左转 正=右转;
 *            直行模式最大 ±45°,平移模式最大 ±12°
 *    V1001.3 系统启动(急停复位)  脉冲: 置1置0
 *    V1001.4 系统停止(急停)      脉冲: 置1置0
 *    V1001.5 前进控制   (旋转模式下语义变为: 左向旋转)
 *    V1001.6 后退控制   (旋转模式下语义变为: 右向旋转)
 *
 *  ◆ 反馈点位(读):
 *    VW1204  当前速度         VW1206  电机转速        VW1208  转弯角度反馈(×10)
 *    VW1210  电量             VD1212  后左轮实际位置(编码器脉冲, DWORD)
 *    V1200.0 屏手动模式  V1200.1 直行模式  V1200.2 旋转模式  V1200.3 平移模式
 *    V1201.1 找零状态    V1201.2 找零完成  V1201.3 系统启动  V1201.4 行走中
 *    V1201.5 故障中      V1201.6 遥控模式  V1201.7 上位控制模式
 *    V1202.0 舵轮未找零  V1202.1 屏手动中  V1202.2 找零中    V1202.5 电量低
 *    V1202.6 外部停止中
 *    V1203.0 急停故障  V1203.2 防撞触边  V1203.3 转向找零超时
 *    V1203.4 行走电机故障  V1203.5 转向电机故障  V1203.6 驱动器CAN通信异常
 *
 * ─── 自动导航控制策略(现场工程师确认) ─────────────────────────────────────
 *   自动驾驶(Nav2 /cmd_vel)只用直行模式(VW1002=0):正常前进/后退 + 转弯角度打舵,
 *   左转给负角度、右转给正角度即可覆盖所有转弯需求。
 *   旋转模式(VW1002=1)和平移模式(轮子转90°变横移)目前不用于自动控制,
 *   仅保留点位定义供以后手动/特殊场景使用。
 *
 *   ⚠ 旋转模式的实际行为(现场工程师确认, 修正此前"固定原地转360°"的错误描述):
 *   写 VW1002=1 进入原地旋转后, V1001.5=左向旋转、V1001.6=右向旋转(不再是前进/后退),
 *   VW1004 仍是速度; 方向位置1启动、置0停止 —— 是**持续旋转、随时可停**,
 *   不是只能转整圈, 因此旋转角度完全可控, 后续接 Nav2 的 Spin 行为或"转到指定角度"都可行。
 *
 *   ⚠ 原地旋转前必须先做净空判定: 车体 5.5m×2.1m, 原地转一圈扫出的是一个圆,
 *   半径 = 旋转中心到车体最远角点的距离(底盘中心为旋转中心时约 2.94m, 即需要近 6m 直径净空),
 *   远大于车身本身。判定逻辑见 RotationSafetyService, 查询接口 GET /api/navigation/rotate-check。
 *
 *   ⚠ 机械结构说明(现场工程师确认): VW1006 写入的是整车转弯角度(左轮角度),
 *   精度0.1°(如写350即35.0°),负数=左转弯、正数=右转弯; 右轮角度由 PLC/驱动器根据
 *   轮距自动计算, 不需要 Java 侧关心。直行模式最大 ±45°,平移模式最大 ±12°。
 *   不存在"前后轮同转、效果翻倍"的情况,写入角度就是实际转弯角度, 不用做任何倍数换算。
 *
 * ─── 运动学换算(现场工程师确认,系数集中在配置项,实车测试如有偏差改
 *     application.yml 即可,不用改代码) ──────────────────────────────────
 *   VW1004 速度写入值 = |linear| × 60 / (π × 轮径)   ← 不乘减速比!
 *     (VW1004 是"车速设置",不是电机转速,减速比只用于下面的里程计换算; 轮径确认为 0.452m)
 *   VW1006 角度: ROS cmd_vel.angular.z 正值=左转(逆时针),PLC 角度正值=右转,
 *     因此写入 PLC 前需要取反: angleDeg = -angular × angularScale
 *     (angularScale 就是单纯的 rad/s → 度 换算系数, 不涉及任何倍数修正, 现场标定时直接调整即可)
 *   VD1212 电机转圈累计(类似里程计,前进为正/后退为负) → 实际轮子走过的距离(m):
 *     distance = (VD1212原始值 / 10000 / 轮速比45) × π×轮径0.452
 *     (电机转45圈,轮子实际转1圈,所以要除以轮速比才是轮子真实转数)
 * ─────────────────────────────────────────────────────────────────────────
 */
@Slf4j
@Service
public class RobotHardwareService {

    // ======================== 连接配置 ========================

    @Value("${plc.host:192.168.0.242}")
    private String plcHost;

    @Value("${plc.port:102}")
    private int plcPort;

    @Value("${plc.rack:0}")
    private int plcRack;

    @Value("${plc.slot:1}")
    private int plcSlot;

    /** 默认不连接真实 PLC，必须在 YAML 中显式设为 true 才会启用。 */
    @Value("${plc.enabled:false}")
    private boolean plcEnabled;

    /**
     * PLC 桥接启动预热开关。false 时不启动 PLC 定时任务，也不会创建 PLC 连接；
     * 需要 PLC 功能时，此项和 plc.enabled 都必须为 true。
     */
    @Value("${plc.bridge.enabled:false}")
    private boolean plcBridgeEnabled;

    /** 看门狗超时(ms): 超过此时间无新指令则自动停车 */
    @Value("${plc.watchdog.ms:500}")
    private long watchdogMs;

    /** PLC 控制日志输出间隔(ms), 避免 /cmd_vel 高频刷屏 */
    @Value("${plc.control.log.interval.ms:500}")
    private long controlLogIntervalMs;

    /** 障碍物紧急停车距离(m), 0 = 禁用 */
    @Value("${plc.obstacle.stop.distance:0.35}")
    private double obstacleStopDistance;

    // ======================== 运动学配置 ========================

    /** 轮径(米), 现场确认 0.452m */
    @Value("${plc.wheel.diameter-m:0.452}")
    private double wheelDiameterM;

    /** 轮速比(减速比): 电机转 gearRatio 圈, 轮子实际转 1 圈; 仅用于 VD1212 里程计换算 */
    @Value("${plc.wheel.gear-ratio:45}")
    private double gearRatio;

    /** VD1212 编码器脉冲分辨率(每转脉冲数), 用于位置反馈换算成米, 现场未确认前仅供参考 */
    @Value("${plc.encoder.counts-per-rev:10000}")
    private double encoderCountsPerRev;

    /** ROS angular.z(rad/s) → PLC 转弯角度(°) 缩放系数 */
    @Value("${plc.angular.scale:30.0}")
    private double angularToDegreesScale;

    /** 自动导航(Nav2 /cmd_vel)限速上限(VW1004, r/min), 只限制 autoNav 来源的指令,
     *  手动遥控不受影响; Nav2 请求更低速度(减速/避障)时仍可以更慢, 只是不会超过此值 */
    @Value("${plc.auto-nav.max-rpm:100}")
    private int autoNavMaxRpm;

    // ======================== 控制点位地址(写) ========================

    /** VW1002 模式设置(WORD): 0=直行  1=原地旋转(持续转,方向位置0即停)  2=平移(轮子转90°横移); 自动导航固定写 0 */
    private static final String ADDR_MODE     = "V1002";
    /** VW1004 速度设置(WORD): 0~3000 r/min, "车速设置"而非电机转速, 不乘减速比 */
    private static final String ADDR_SPEED    = "V1004";
    /** VW1006 转弯角度设置(WORD): 精度0.1°(写入值=角度×10), 负=左转 正=右转;
     *  直行模式限幅 ±45°, 平移模式限幅 ±12° */
    private static final String ADDR_ANGLE    = "V1006";
    /** V1001.5 前进控制位(BOOL): 移动时置1, 停止时置0 */
    private static final String ADDR_FORWARD  = "V1001.5";
    /** V1001.6 后退控制位(BOOL): 移动时置1, 停止时置0 */
    private static final String ADDR_BACKWARD = "V1001.6";
    /** V1001.3 系统启动(急停复位)控制位(BOOL): 脉冲触发, 置1再置0 */
    private static final String ADDR_SYS_START = "V1001.3";
    /** V1001.4 系统停止(急停)控制位(BOOL): 脉冲触发, 置1再置0 */
    private static final String ADDR_SYS_STOP  = "V1001.4";

    // ======================== 反馈点位地址(读) ========================

    /** VW1204 当前速度反馈(WORD, r/min) */
    private static final String ADDR_FB_SPEED      = "V1204";
    /** VW1206 电机转速反馈(WORD, r/min) */
    private static final String ADDR_FB_MOTOR_RPM  = "V1206";
    /** VW1208 转弯角度反馈(WORD): 精度0.1°(读到的值需 ÷10 才是实际角度) */
    private static final String ADDR_FB_ANGLE      = "V1208";
    /** VW1210 电量反馈(WORD) */
    private static final String ADDR_FB_BATTERY    = "V1210";
    /** VD1212 后左轮实际位置(DWORD): 电机转圈累计脉冲, 类似里程计, 前进为正/后退为负;
     *  换算实际里程需 ÷编码器分辨率(10000) ÷轮速比(45) ×π×轮径(0.452m) */
    private static final String ADDR_FB_REAR_L_POS = "V1212";

    /** V1200.0 AGV屏手动控制模式(BOOL) */
    private static final String ADDR_MODE_MANUAL_FB    = "V1200.0";
    /** V1200.1 直行模式反馈(BOOL) */
    private static final String ADDR_MODE_STRAIGHT_FB   = "V1200.1";
    /** V1200.2 旋转模式反馈(BOOL) */
    private static final String ADDR_MODE_ROTATE_FB     = "V1200.2";
    /** V1200.3 平移模式反馈(BOOL) */
    private static final String ADDR_MODE_LATERAL_FB    = "V1200.3";

    /** V1201.1 找零状态(BOOL): 舵轮正在寻找零位 */
    private static final String ADDR_HOMING       = "V1201.1";
    /** V1201.2 AGV找零完成(BOOL) */
    private static final String ADDR_HOMED        = "V1201.2";
    /** V1201.3 系统启动状态(BOOL) */
    private static final String ADDR_SYS_STARTED  = "V1201.3";
    /** V1201.4 AGV行走中(BOOL) */
    private static final String ADDR_WALKING      = "V1201.4";
    /** V1201.5 故障中(BOOL): 任意故障位置1时该位也置1 */
    private static final String ADDR_FAULT        = "V1201.5";
    /** V1201.6 AGV遥控控制模式(BOOL) */
    private static final String ADDR_REMOTE_MODE  = "V1201.6";
    /** V1201.7 AGV上位控制模式(BOOL): 本服务通过 S7 写入指令时应处于该模式 */
    private static final String ADDR_UPPER_MODE   = "V1201.7";

    /** V1202.0 舵轮未找零(BOOL) */
    private static final String ADDR_STEER_NOT_HOMED = "V1202.0";
    /** V1202.1 屏手动控制中(BOOL) */
    private static final String ADDR_SCREEN_MANUAL   = "V1202.1";
    /** V1202.2 找零中(BOOL) */
    private static final String ADDR_STEER_HOMING    = "V1202.2";
    /** V1202.5 电量低(BOOL) */
    private static final String ADDR_LOW_BATTERY     = "V1202.5";
    /** V1202.6 外部停止中(BOOL): 外部急停/开关触发的停止状态 */
    private static final String ADDR_EXT_STOP        = "V1202.6";

    /** V1203.0 急停故障(BOOL) */
    private static final String ADDR_FAULT_ESTOP         = "V1203.0";
    /** V1203.2 防撞条触边故障(BOOL) */
    private static final String ADDR_FAULT_BUMPER        = "V1203.2";
    /** V1203.3 转向找零超时故障(BOOL) */
    private static final String ADDR_FAULT_HOMING_TIMEOUT= "V1203.3";
    /** V1203.4 行走电机故障(BOOL) */
    private static final String ADDR_FAULT_DRIVE_MOTOR   = "V1203.4";
    /** V1203.5 转向电机故障(BOOL) */
    private static final String ADDR_FAULT_STEER_MOTOR   = "V1203.5";
    /** V1203.6 驱动器CAN通信异常故障(BOOL) */
    private static final String ADDR_FAULT_CAN           = "V1203.6";

    // ======================== Ramp 限制 (每 20ms tick 最大变化量) ========================

    private static final int RPM_RAMP          = 100; // r/min / tick
    private static final int ANGLE_TENTHS_RAMP  = 30;  // 0.1° / tick (=3°/tick)
    private static final int STRAIGHT_MAX_ANGLE_DEG = 45;

    // ======================== 运行模式枚举 ========================

    public enum DriveMode {
        STOP,
        STRAIGHT_FORWARD,
        STRAIGHT_BACKWARD
    }

    // ======================== 内部指令封装 ========================

    private static class PlcCommand {
        final DriveMode mode;
        final int    rpm;       // 写入 VW1004 的转速(减速比之前)
        final double angleDeg;  // 写入 VW1006 的角度(°), 会 ×10 后写入
        final double linear;
        final double angular;

        PlcCommand(DriveMode mode, int rpm, double angleDeg, double linear, double angular) {
            this.mode     = mode;
            this.rpm      = rpm;
            this.angleDeg = angleDeg;
            this.linear   = linear;
            this.angular  = angular;
        }
    }

    // ======================== PLC 状态 DTO ========================

    @Data
    public static class PlcStatus {
        private boolean connected;
        private String  mode;          // Java 侧当前下发的 DriveMode
        private int     rpm;           // 实发转速(VW1004)
        private double  angleDeg;      // 实发角度(VW1006, 单位°)

        // 反馈寄存器
        private int    feedbackSpeed;      // VW1204 当前速度
        private int    feedbackMotorRpm;   // VW1206 电机转速
        private double feedbackAngleDeg;   // VW1208 转弯角度反馈(已÷10)
        private int    battery;            // VW1210 电量
        private long   rearLeftWheelRaw;   // VD1212 原始脉冲
        private double rearLeftWheelDistM; // VD1212 换算距离(m, 按配置系数估算)

        // 模式反馈位
        private boolean screenManualMode;
        private boolean straightModeFb;
        private boolean rotateModeFb;
        private boolean lateralModeFb;

        // 状态位
        private boolean homing;
        private boolean homed;
        private boolean systemStarted;
        private boolean walking;
        private boolean fault;
        private boolean remoteMode;
        private boolean upperComputerMode;
        private boolean steerNotHomed;
        private boolean screenManualActive;
        private boolean steerHoming;
        private boolean lowBattery;
        private boolean externalStop;

        // 故障位
        private boolean faultEmergencyStop;
        private boolean faultBumper;
        private boolean faultHomingTimeout;
        private boolean faultDriveMotor;
        private boolean faultSteerMotor;
        private boolean faultCan;

        private double  obstacleDistance; // -1 表示无数据
        private boolean obstacleOverride;
        private boolean emergencyStopActive;
    }

    // ======================== 运行状态 ========================

    private S7PLC s7PLC;
    private final AtomicBoolean connected    = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean emergencyStopActive = new AtomicBoolean(false);
    private final AtomicBoolean plcFaultActive = new AtomicBoolean(false);

    /** 待下发指令缓冲 */
    private volatile double  pendingLinear  = 0;
    private volatile double  pendingAngular = 0;
    private volatile boolean pendingAutoNav = false;
    private final AtomicBoolean hasPending  = new AtomicBoolean(false);
    private final AtomicLong lastCmdTimeMs  = new AtomicLong(0);

    /** 上次实际下发值 (用于 Ramp 平滑) */
    private volatile int       lastSentRpm         = 0;
    private volatile int       lastSentAngleTenths = 0;
    private volatile DriveMode currentMode         = DriveMode.STOP;

    /**
     * 控制记录推送。没接 PLC 时前端「控制历史」是唯一能看到"到底会怎么控制"的地方，
     * 所以和日志同一处、同一份数据推出去，两边永远对得上。
     */
    @Autowired
    private WebSocketPushService pushService;

    private volatile DriveMode lastLoggedMode = null;
    private volatile int lastLoggedTargetRpm = Integer.MIN_VALUE;
    private volatile int lastLoggedTargetAngleTenths = Integer.MIN_VALUE;
    private volatile long lastControlLogMs = 0L;

    /** 障碍物距离监控 */
    private volatile double  minObstacleDistance = Double.MAX_VALUE;
    private volatile boolean obstacleOverride    = false;

    /** 最近一次读到的完整状态(供 readStatus 直接返回, statusReadTick 周期刷新) */
    private volatile PlcStatus lastStatus = new PlcStatus();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "plc-worker");
        t.setDaemon(true);
        return t;
    });

    // ======================== 生命周期 ========================

    @PostConstruct
    public void init() {
        if (!plcBridgeEnabled) {
            log.info("[PLC] 桥接启动预热已关闭 (plc.bridge.enabled=false)，跳过 PLC 定时任务和连接初始化");
            return;
        }
        if (plcEnabled) {
            connectPlc();
        } else {
            log.info("[PLC] 启动连接已关闭 (plc.enabled=false)，跳过 PLC 加载和网络连接");
        }
        scheduler.scheduleAtFixedRate(this::sendTick,      100, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::statusReadTick,  1,  1, TimeUnit.SECONDS);
        log.info("AGV PLC bridge started | host={}:{} | plc.enabled={} | watchdog={}ms",
                plcHost, plcPort, plcEnabled, watchdogMs);
        log.info("[PLC] 轮径={}m 减速比={} 编码器分辨率={} 角度缩放={}",
                wheelDiameterM, gearRatio, encoderCountsPerRev, angularToDegreesScale);
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
     * @param angular 角速度 rad/s (正=左转, 负=右转, 遵循 ROS 约定)
     */
    public void sendVelocity(double linear, double angular) {
        sendVelocity(linear, angular, false);
    }

    /**
     * @param linear  线速度 m/s  (正=前进, 负=后退)
     * @param angular 角速度 rad/s (正=左转, 负=右转, 遵循 ROS 约定)
     * @param autoNav 是否来自 Nav2 自动导航(/cmd_vel); true 时 rpm 会被限制在 autoNavMaxRpm 以内,
     *                手动遥控(D-PAD)传 false, 不受此限速影响
     */
    public void sendVelocity(double linear, double angular, boolean autoNav) {
        if (shuttingDown.get()) return;
        if (emergencyStopActive.get() || plcFaultActive.get()) {
            hasPending.set(false);
            pendingLinear = 0;
            pendingAngular = 0;
            return;
        }
        pendingLinear  = linear;
        pendingAngular = angular;
        pendingAutoNav = autoNav;
        hasPending.set(true);
        lastCmdTimeMs.set(System.currentTimeMillis());
    }

    /** 硬件级紧急停车: 直接清除方向控制位 + 速度归零, 不经过任何缓冲 */
    public void emergencyStop() {
        log.warn("🛑 紧急停车触发!");
        try {
            emergencyStopActive.set(true);
            hasPending.set(false);
            pendingLinear = 0;
            pendingAngular = 0;
            lastCmdTimeMs.set(0);
            forceStop();
            pulseBit(ADDR_SYS_STOP, 100); // 触发 PLC 侧"系统停止(急停)"
        } catch (Exception e) {
            log.error("急停失败, 请立即手动断电! {}", e.getMessage());
        }
    }

    /** 解除 Java 侧急停锁, 并脉冲 PLC 侧"系统启动(急停复位)"位。 */
    public void clearEmergencyStopLock() {
        emergencyStopActive.set(false);
        lastCmdTimeMs.set(0);
        hasPending.set(false);
        try {
            pulseBit(ADDR_SYS_START, 100); // 触发 PLC 侧"系统启动(急停复位)"
        } catch (Exception e) {
            log.error("[PLC急停] PLC 侧复位失败, 请到现场手动复位! {}", e.getMessage());
        }
        log.warn("[PLC急停] Java 侧急停锁已解除, 可重新接收 cmd_vel");
    }

    /**
     * 注入最新激光/点云最近障碍物距离(m)。
     * 由 ROS2WebSocketHandler 收到 /scan 后调用。
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

    /** 读取 PLC 综合状态, 供 REST API 返回给前端(直接返回 statusReadTick 缓存, 避免阻塞请求线程) */
    public PlcStatus readStatus() {
        return lastStatus;
    }

    public boolean isConnected()          { return connected.get(); }
    public DriveMode getCurrentMode()     { return currentMode; }
    public int  getLastRpm()              { return lastSentRpm; }
    public double getLastAngleDeg()       { return lastSentAngleTenths / 10.0; }
    public double getMinObstacleDistance(){ return minObstacleDistance; }
    public boolean isObstacleOverride()   { return obstacleOverride; }
    public boolean isEmergencyStopActive(){ return emergencyStopActive.get(); }
    public boolean isPlcFaultActive()     { return plcFaultActive.get(); }

    // ======================== 周期发送 tick ========================

    private void sendTick() {
        try {
            if (emergencyStopActive.get()) {
                hasPending.set(false);
                return;
            }
            if (obstacleOverride || plcFaultActive.get()) return;

            long last = lastCmdTimeMs.get();
            boolean stale = last > 0 && (System.currentTimeMillis() - last) > watchdogMs;

            if (stale) {
                if (currentMode != DriveMode.STOP) {
                    log.warn("⚠ 看门狗触发 ({}ms 无新指令), 停车", watchdogMs);
                    forceStop();
                }
                return;
            }

            if (!hasPending.get()) return;
            hasPending.set(false);
            PlcCommand cmd = twistToPlcCommand(pendingLinear, pendingAngular, pendingAutoNav);
            executeCommand(cmd);

        } catch (Exception e) {
            log.warn("sendTick 异常: {}", e.getMessage(), e);
            tryReconnect();
        }
    }

    // ======================== Twist → PlcCommand 转换 ========================

    /**
     * 将 ROS Twist (linear.x / angular.z) 转换为 PLC 指令。只用直行模式:
     *
     *   rpm   = |v| × 60 / (π × 轮径)   ← 不乘减速比 (VW1004 是车速设置, 非电机转速)
     *   angle = -angular × angularScale (ROS 左转为正, PLC 右转为正, 取反)
     *
     * linear 近 0 (纯旋转指令) 时直行模式无法处理, 直接停车。
     *
     * autoNav=true(来自 Nav2 /cmd_vel) 时, rpm 会被限制在 autoNavMaxRpm 以内(限速上限,
     * 不是固定值), Nav2 请求更低速度(接近目标点减速/避障)时仍然可以更慢; 手动遥控不受影响。
     */
    private PlcCommand twistToPlcCommand(double linear, double angular, boolean autoNav) {
        final double LIN_DEAD = 0.01;   // 线速度死区 m/s

        boolean moving = Math.abs(linear) > LIN_DEAD;
        if (!moving) {
            return new PlcCommand(DriveMode.STOP, 0, 0, linear, angular);
        }

        double circumference = Math.PI * wheelDiameterM;

        double rpm = Math.abs(linear) * 60.0 / circumference;
        rpm = Math.max(0, Math.min(3000, rpm));
        if (autoNav) rpm = Math.min(rpm, autoNavMaxRpm);

        double angleDeg = -angular * angularToDegreesScale;
        angleDeg = Math.max(-STRAIGHT_MAX_ANGLE_DEG, Math.min(STRAIGHT_MAX_ANGLE_DEG, angleDeg));

        DriveMode mode = linear >= 0 ? DriveMode.STRAIGHT_FORWARD : DriveMode.STRAIGHT_BACKWARD;
        return new PlcCommand(mode, (int) rpm, angleDeg, linear, angular);
    }

    // ======================== 执行指令 ========================

    /**
     * 将 PlcCommand 写入 PLC。
     *
     * 模式切换流程:
     *   1. 速度/角度清零, 方向位清零
     *   2. STOP 直接返回(不写模式字, 因为 0/1/2 只对应直行/旋转/平移, 没有"停止"这个模式值)
     *   3. 写入新模式字(VW1002), 等待 50ms
     *   4. 置位对应方向控制位, 等待 30ms
     *   5. Ramp 平滑写入速度/角度
     */
    private synchronized void executeCommand(PlcCommand cmd) throws Exception {
        int angleTenths = (int) Math.round(cmd.angleDeg * 10);

        if (cmd.mode != currentMode) {
            log.info("[PLC] 模式切换: {} → {}", currentMode, cmd.mode);

            writeInt16(ADDR_SPEED, 0);
            writeInt16(ADDR_ANGLE, 0);
            writeBit(ADDR_FORWARD, false);
            writeBit(ADDR_BACKWARD, false);
            lastSentRpm         = 0;
            lastSentAngleTenths = 0;

            if (cmd.mode == DriveMode.STOP) {
                currentMode = DriveMode.STOP;
                logControlCommand(cmd, 0, 0);
                return;
            }

            Thread.sleep(50);

            writeInt16(ADDR_MODE, 0); // 自动导航只用直行模式

            Thread.sleep(30);

            switch (cmd.mode) {
                case STRAIGHT_FORWARD:
                    writeBit(ADDR_FORWARD, true);
                    break;
                case STRAIGHT_BACKWARD:
                    writeBit(ADDR_BACKWARD, true);
                    break;
                default:
                    break;
            }
            currentMode = cmd.mode;
            Thread.sleep(20);
        }

        if (currentMode == DriveMode.STOP) {
            logControlCommand(cmd, 0, 0);
            return;
        }

        int rampedRpm         = rampStep(lastSentRpm, cmd.rpm, RPM_RAMP);
        int rampedAngleTenths = rampStep(lastSentAngleTenths, angleTenths, ANGLE_TENTHS_RAMP);

        writeInt16(ADDR_SPEED, rampedRpm);
        writeInt16(ADDR_ANGLE, rampedAngleTenths);

        lastSentRpm         = rampedRpm;
        lastSentAngleTenths = rampedAngleTenths;

        log.debug("[PLC] {} | rpm={} | angle={}°", currentMode, rampedRpm, rampedAngleTenths / 10.0);
        logControlCommand(cmd, rampedRpm, rampedAngleTenths);
    }

    private void logControlCommand(PlcCommand cmd, int actualRpm, int actualAngleTenths) {
        long now = System.currentTimeMillis();
        boolean changed = cmd.mode != lastLoggedMode
                || Math.abs(cmd.rpm - lastLoggedTargetRpm) >= 50
                || Math.abs((int) Math.round(cmd.angleDeg * 10) - lastLoggedTargetAngleTenths) >= 5;
        boolean intervalReached = now - lastControlLogMs >= Math.max(100, controlLogIntervalMs);
        if (!changed && !intervalReached) return;

        // ⚠ 这行**不能**当成"指令真的出了 Java 进程"的证据：plc.enabled=false 时
        //   writeInt16/writeBit 早就 return 了，一个字节都没写 PLC，这行照打。
        //   所以把 enabled/connected 的实际状态直接写进日志，别再让人对着"实发rpm=12"
        //   一路去查 Nav2/TF/点云（2026-08 为此绕了好几天）。
        String wire = !plcEnabled ? "未写PLC(plc.enabled=false，仅演算)"
                : (connected.get() ? "已写PLC" : "未写PLC(PLC未连接)");

        log.info("[PLC控制] 动作={} | ROS(linear.x={}, angular.z={}) | 目标rpm={} 实发rpm={} | 目标角度={}° 实发角度={}° | {}",
                driveModeLabel(cmd.mode),
                String.format("%.3f", cmd.linear),
                String.format("%.3f", cmd.angular),
                cmd.rpm, actualRpm,
                String.format("%.1f", cmd.angleDeg),
                String.format("%.1f", actualAngleTenths / 10.0),
                wire);

        // 寄存器级明细：没接 PLC 时这是唯一能看到"到底会怎么控制"的地方。
        // 直接按点位表把地址、值、含义都写全，免得还要翻文件头的注释对照。
        boolean fwd = cmd.mode == DriveMode.STRAIGHT_FORWARD;
        boolean bwd = cmd.mode == DriveMode.STRAIGHT_BACKWARD;
        log.info("[PLC控制·寄存器] {}(模式)=0 直行 | {}(速度)={} r/min | {}(角度×10)={} → {}° {} | {}(前进)={} | {}(后退)={}",
                ADDR_MODE,
                ADDR_SPEED, actualRpm,
                ADDR_ANGLE, actualAngleTenths,
                String.format("%.1f", actualAngleTenths / 10.0),
                actualAngleTenths == 0 ? "" : (actualAngleTenths < 0 ? "(左)" : "(右)"),
                ADDR_FORWARD, fwd,
                ADDR_BACKWARD, bwd);

        pushControlRecord(cmd, actualRpm, actualAngleTenths, fwd, bwd, wire);

        lastLoggedMode = cmd.mode;
        lastLoggedTargetRpm = cmd.rpm;
        lastLoggedTargetAngleTenths = (int) Math.round(cmd.angleDeg * 10);
        lastControlLogMs = now;
    }

    /** 把刚打进日志的那条控制指令原样推给前端「控制历史」，节流沿用 logControlCommand 的判定 */
    private void pushControlRecord(PlcCommand cmd, int actualRpm, int actualAngleTenths,
                                   boolean fwd, boolean bwd, String wire) {
        if (pushService == null) return;
        try {
            java.util.Map<String, Object> d = new java.util.HashMap<>();
            d.put("mode",        cmd.mode.name());
            d.put("modeLabel",   driveModeLabel(cmd.mode));
            d.put("linear",      cmd.linear);
            d.put("angular",     cmd.angular);
            d.put("targetRpm",   cmd.rpm);
            d.put("rpm",         actualRpm);
            d.put("targetAngle", cmd.angleDeg);
            d.put("angleDeg",    actualAngleTenths / 10.0);
            d.put("wire",        wire);
            d.put("plcEnabled",  plcEnabled);
            d.put("connected",   connected.get());
            // 寄存器明细：前端直接展示地址+值，和 [PLC控制·寄存器] 日志一一对应
            d.put("regs", java.util.Arrays.asList(
                    reg(ADDR_MODE,     "模式", "0 (直行)"),
                    reg(ADDR_SPEED,    "速度", actualRpm + " r/min"),
                    reg(ADDR_ANGLE,    "角度", actualAngleTenths + " (=" + String.format("%.1f", actualAngleTenths / 10.0) + "°"
                            + (actualAngleTenths == 0 ? "" : actualAngleTenths < 0 ? " 左" : " 右") + ")"),
                    reg(ADDR_FORWARD,  "前进", String.valueOf(fwd)),
                    reg(ADDR_BACKWARD, "后退", String.valueOf(bwd))
            ));
            pushService.pushToAll("plc_control", d);
        } catch (Exception e) {
            log.debug("推送 PLC 控制记录失败", e);
        }
    }

    private java.util.Map<String, Object> reg(String addr, String name, String value) {
        java.util.Map<String, Object> m = new java.util.HashMap<>(4);
        m.put("addr", addr);
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private String driveModeLabel(DriveMode mode) {
        switch (mode) {
            case STRAIGHT_FORWARD:  return "前进";
            case STRAIGHT_BACKWARD: return "后退";
            case STOP:
            default:                return "停止";
        }
    }

    /** 强制停车: 速度清零 + 方向控制位关闭(不改变模式字) */
    private synchronized void forceStop() throws Exception {
        writeInt16(ADDR_SPEED, 0);
        writeInt16(ADDR_ANGLE, 0);
        writeBit(ADDR_FORWARD, false);
        writeBit(ADDR_BACKWARD, false);
        currentMode         = DriveMode.STOP;
        lastSentRpm         = 0;
        lastSentAngleTenths = 0;
    }

    private int rampStep(int current, int target, int maxStep) {
        int diff = target - current;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Integer.signum(diff) * maxStep;
    }

    // ======================== 定时读取状态反馈 ========================

    private void statusReadTick() {
        if (!plcEnabled || !connected.get() || s7PLC == null) return;
        try {
            PlcStatus st = new PlcStatus();
            st.setConnected(connected.get());
            st.setMode(currentMode.name());
            st.setRpm(lastSentRpm);
            st.setAngleDeg(lastSentAngleTenths / 10.0);

            st.setFeedbackSpeed(readInt16(ADDR_FB_SPEED));
            st.setFeedbackMotorRpm(readInt16(ADDR_FB_MOTOR_RPM));
            st.setFeedbackAngleDeg(readInt16(ADDR_FB_ANGLE) / 10.0);
            st.setBattery(readInt16(ADDR_FB_BATTERY));

            long rawPos = readInt32(ADDR_FB_REAR_L_POS);
            st.setRearLeftWheelRaw(rawPos);
            st.setRearLeftWheelDistM(rawPos / encoderCountsPerRev / gearRatio * Math.PI * wheelDiameterM);

            st.setScreenManualMode(readBit(ADDR_MODE_MANUAL_FB));
            st.setStraightModeFb(readBit(ADDR_MODE_STRAIGHT_FB));
            st.setRotateModeFb(readBit(ADDR_MODE_ROTATE_FB));
            st.setLateralModeFb(readBit(ADDR_MODE_LATERAL_FB));

            st.setHoming(readBit(ADDR_HOMING));
            st.setHomed(readBit(ADDR_HOMED));
            st.setSystemStarted(readBit(ADDR_SYS_STARTED));
            st.setWalking(readBit(ADDR_WALKING));
            st.setFault(readBit(ADDR_FAULT));
            st.setRemoteMode(readBit(ADDR_REMOTE_MODE));
            st.setUpperComputerMode(readBit(ADDR_UPPER_MODE));

            st.setSteerNotHomed(readBit(ADDR_STEER_NOT_HOMED));
            st.setScreenManualActive(readBit(ADDR_SCREEN_MANUAL));
            st.setSteerHoming(readBit(ADDR_STEER_HOMING));
            st.setLowBattery(readBit(ADDR_LOW_BATTERY));
            st.setExternalStop(readBit(ADDR_EXT_STOP));

            boolean faultEstop  = readBit(ADDR_FAULT_ESTOP);
            boolean faultBumper = readBit(ADDR_FAULT_BUMPER);
            boolean faultHoming = readBit(ADDR_FAULT_HOMING_TIMEOUT);
            boolean faultDrive  = readBit(ADDR_FAULT_DRIVE_MOTOR);
            boolean faultSteer  = readBit(ADDR_FAULT_STEER_MOTOR);
            boolean faultCan    = readBit(ADDR_FAULT_CAN);
            st.setFaultEmergencyStop(faultEstop);
            st.setFaultBumper(faultBumper);
            st.setFaultHomingTimeout(faultHoming);
            st.setFaultDriveMotor(faultDrive);
            st.setFaultSteerMotor(faultSteer);
            st.setFaultCan(faultCan);

            st.setObstacleDistance(minObstacleDistance > 1e6 ? -1 : minObstacleDistance);
            st.setObstacleOverride(obstacleOverride);
            st.setEmergencyStopActive(emergencyStopActive.get());

            boolean anyFault = st.isFault() || faultEstop || faultBumper || faultHoming
                    || faultDrive || faultSteer || faultCan;
            if (anyFault != plcFaultActive.get()) {
                plcFaultActive.set(anyFault);
                if (anyFault) {
                    log.warn("⚠ [PLC故障] 检测到故障位, 停止下发 cmd_vel, 请到现场确认并复位");
                    try { forceStop(); } catch (Exception ignored) {}
                } else {
                    log.info("✅ [PLC故障] 故障位已清除, 恢复 cmd_vel 转发");
                }
            }

            lastStatus = st;
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
            s7PLC = new S7PLC(EPlcType.S200_SMART, plcHost, plcPort, plcRack, plcSlot);
            connected.set(s7PLC.checkConnected());
            if (connected.get()) {
                log.info("✅ [PLC] 连接成功: {}:{} rack={} slot={}", plcHost, plcPort, plcRack, plcSlot);
            } else {
                log.error("❌ [PLC] 连接失败: checkConnected()=false");
            }
        } catch (Exception e) {
            connected.set(false);
            log.error("❌ [PLC] 连接失败: {}", e.getMessage());
        }
    }

    private synchronized void disconnectPlc() {
        if (s7PLC == null) return;
        try { s7PLC.close(); } catch (Exception ignored) {}
        s7PLC = null;
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

    // ======================== S7 底层读写(基于 S7PLC) ========================

    private synchronized void writeInt16(String address, int value) throws Exception {
        if (!plcEnabled) {
            log.trace("[PLC调试] {} = {}", address, value);
            return;
        }
        if (!connected.get() || s7PLC == null) {
            log.warn("[PLC] 未连接, 跳过 {} 写入", address);
            return;
        }
        if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
        if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
        s7PLC.writeInt16(address, (short) value);
    }

    private synchronized void writeBit(String address, boolean value) throws Exception {
        if (!plcEnabled) {
            log.trace("[PLC调试] {} = {}", address, value);
            return;
        }
        if (!connected.get() || s7PLC == null) return;
        s7PLC.writeBoolean(address, value);
    }

    /** 脉冲写入: 置1保持 holdMs 后置0, 用于系统启动/系统停止这类脉冲触发点位 */
    private void pulseBit(String address, long holdMs) throws Exception {
        writeBit(address, true);
        Thread.sleep(holdMs);
        writeBit(address, false);
    }

    private int readInt16(String address) {
        try {
            if (!plcEnabled || !connected.get() || s7PLC == null) return 0;
            return s7PLC.readInt16(address);
        } catch (Exception e) {
            log.trace("[PLC] 读取 {} 失败: {}", address, e.getMessage());
            return 0;
        }
    }

    private long readInt32(String address) {
        try {
            if (!plcEnabled || !connected.get() || s7PLC == null) return 0;
            return s7PLC.readInt32(address);
        } catch (Exception e) {
            log.trace("[PLC] 读取 {} 失败: {}", address, e.getMessage());
            return 0;
        }
    }

    private boolean readBit(String address) {
        try {
            if (!plcEnabled || !connected.get() || s7PLC == null) return false;
            return s7PLC.readBoolean(address);
        } catch (Exception e) {
            log.trace("[PLC] 读取 {} 失败: {}", address, e.getMessage());
            return false;
        }
    }
}
