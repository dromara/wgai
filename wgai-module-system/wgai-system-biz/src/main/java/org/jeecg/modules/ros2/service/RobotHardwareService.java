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
import java.util.Map;
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
 *   VW1004 速度写入值 = |linear| × 60 / (π × 轮径) × plc.wheel.rpm-scale
 *                       ← 不乘减速比! 但必须乘现场标定的 rpm-scale，纯几何值慢一个数量级
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

    /**
     * 默认不连接真实 PLC。
     * ⚠ 这是**运行时可变**的：导航页的「连接 PLC」按钮会调 {@link #enablePlc()} 把它翻成 true。
     *   YAML 里的值只是开机默认值，不是最终状态；排查问题时要以 {@link #plcInfo()} 为准。
     */
    @Value("${plc.enabled:false}")
    private volatile boolean plcEnabled;

    /**
     * PLC 桥接启动预热开关。false 时不启动 PLC 定时任务，也不会创建 PLC 连接；
     * 需要 PLC 功能时，此项和 plc.enabled 都必须为 true。
     */
    @Value("${plc.bridge.enabled:false}")
    private boolean plcBridgeEnabled;

    // 下面到 autoNavMaxRpm 为止的这批 @Value 字段全部 volatile：AgvParamService 会在运行时
    // (HTTP 线程)按 tab_ros_python.agv_param 的内容反射改写它们，而读取发生在 20ms 的 sendTick
    // 线程里。不加 volatile 就可能出现"页面显示已生效、底盘用的还是旧值"。加参数时记得一并加。
    // ⚠ 上面的 host/port/rack/slot 故意不在此列 —— 那几个改了必须重连 PLC，不是改个数就完事。

    /** 看门狗超时(ms): 超过此时间无新指令则自动停车 */
    @Value("${plc.watchdog.ms:500}")
    private volatile long watchdogMs;

    /** PLC 控制日志输出间隔(ms), 避免 /cmd_vel 高频刷屏 */
    @Value("${plc.control.log.interval.ms:500}")
    private volatile long controlLogIntervalMs;

    /**
     * 障碍物紧急停车距离(m) —— 车头前方走廊内出现障碍物时停车的门限, 0 = 禁用。
     * 距离由 ObstacleGuardService 逐帧算出, 含义是"离**车头**多远"(不是离雷达)。
     * 只对自动导航(/cmd_vel)生效, 手动遥控和测试点动不受它拦。前端可运行时改。
     */
    @Value("${plc.obstacle.stop.distance:0.35}")
    private volatile double obstacleStopDistance;

    /**
     * 障碍物距离数据的最长容忍空窗(ms)。超过此时间没有新的判定结果就当"看不见前面",
     * 自动导航一律拦停 —— 数据不足 = 禁止动作, 绝不默认放行。
     * ⚠ 只在**曾经**收到过判定结果之后才生效, 否则点云链路没接的环境会一上来就被锁死。
     */
    @Value("${plc.obstacle.stale-ms:2000}")
    private volatile long obstacleStaleMs;

    /** 测试点动的转速上限(r/min)。测试窗是用来验证点位通不通的, 不该让车窜出去 */
    @Value("${plc.test.max-rpm:100}")
    private volatile int testMaxRpm;

    /** 测试点动的单次最长保持时间(ms), 到点自动停 */
    @Value("${plc.test.max-hold-ms:3000}")
    private volatile long testMaxHoldMs;

    // ======================== 运动学配置 ========================

    /** 轮径(米), 现场确认 0.452m */
    @Value("${plc.wheel.diameter-m:0.452}")
    private volatile double wheelDiameterM;

    /**
     * VW1004 速度标定系数。几何换算 |v|×60/(π×轮径) 得到的是**轮子** r/min，
     * 但 VW1004 吃的显然不是这个刻度 —— 现场实测 0.3m/s 算出 12 时车几乎不走，×10 才对得上。
     * 现场对着实际车速校准这个数即可，不要去改换算公式。
     */
    @Value("${plc.wheel.rpm-scale:1.0}")
    private volatile double rpmScale;

    /** 轮速比(减速比): 电机转 gearRatio 圈, 轮子实际转 1 圈; 仅用于 VD1212 里程计换算 */
    @Value("${plc.wheel.gear-ratio:45}")
    private volatile double gearRatio;

    /** VD1212 编码器脉冲分辨率(每转脉冲数), 用于位置反馈换算成米, 现场未确认前仅供参考 */
    @Value("${plc.encoder.counts-per-rev:10000}")
    private volatile double encoderCountsPerRev;

    /** ROS angular.z(rad/s) → PLC 转弯角度(°) 缩放系数。自动导航只在 steerMode=0 时用；手动遥控一直用它 */
    @Value("${plc.angular.scale:30.0}")
    private volatile double angularToDegreesScale;

    /**
     * 自动导航舵角换算方式。
     *   1 = 运动学(默认)：舵角 = atan(等效轴距 × ω / v)，转弯半径跟 Nav2 要的一致
     *   0 = 旧的比例系数：舵角 = -ω × angularScale，**与车速无关** —— 0.3m/s 下 Nav2 要 R=2.1m
     *       只给 4.3°，实际 R≈28m，弯道严重外甩。仅留作现场回退
     */
    @Value("${plc.steer.mode:1}")
    private volatile int steerMode;

    /** 起步打舵：转角反馈与目标差在这个范围内才置方向位起步(°) */
    @Value("${plc.steer.ready-tol-deg:1.0}")
    private volatile double steerReadyTolDeg;

    /** 起步打舵：超过这么久还没到位就报警，继续不起步(ms) */
    @Value("${plc.steer.ready-timeout-ms:3000}")
    private volatile long steerReadyTimeoutMs;

    /** 行驶中转角反馈与目标差超过它就限速(°) */
    @Value("${plc.steer.slow-error-deg:5.0}")
    private volatile double steerSlowErrorDeg;

    /** 行驶中舵角没跟上时的限速(VW1004) */
    @Value("${plc.steer.slow-rpm:50}")
    private volatile int steerSlowRpm;

    /** 转角反馈读取间隔(ms)。只在自动导航下发时读 */
    @Value("${plc.steer.feedback-interval-ms:200}")
    private volatile long steerFeedbackIntervalMs;

    /** 等效轴距(m)，与 ObstacleGuardService / MapController 同一个配置项，含义见 plc.wheel.wheelbase-m */
    @Value("${plc.wheel.wheelbase-m:2.1}")
    private volatile double wheelbaseM;

    /** 自动导航(Nav2 /cmd_vel)限速上限(VW1004, r/min), 只限制 autoNav 来源的指令,
     *  手动遥控不受影响; Nav2 请求更低速度(减速/避障)时仍可以更慢, 只是不会超过此值 */
    @Value("${plc.auto-nav.max-rpm:100}")
    private volatile int autoNavMaxRpm;

    /**
     * 自动导航倒车限速(VW1004)。雷达在车头，车尾后面基本是盲区 —— 前向避障走廊管不到倒车，
     * 倒车只能靠慢 + 短来控风险。
     */
    @Value("${plc.reverse.max-rpm:100}")
    private volatile int reverseMaxRpm;

    /**
     * 自动导航单段连续倒车最长距离(m，按 /Odometry 实测位移)。超过就不再执行倒车指令，
     * Nav2 会因为走不动报 progress 失败。出现一次前进指令才重新计数。0 = 禁止自动倒车。
     */
    @Value("${plc.reverse.max-distance-m:2.0}")
    private volatile double reverseMaxDistanceM;

    /** 机动(原地旋转/平移)切模式后，等 PLC 模式反馈位点亮的最长时间。舵轮要转到位，给足 */
    @Value("${plc.maneuver.mode-switch-timeout-ms:8000}")
    private volatile long modeSwitchTimeoutMs;

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

    // ── 反馈区连续块 ──────────────────────────────────────────────────────
    // 上面所有反馈点位都落在 V1200~V1215 这 16 个字节里(状态位 V1200~V1203 +
    // 反馈字 V1204/1206/1208/1210 + 里程 DWORD V1212)，所以一次连续区读就能全部拿回。
    // ⚠ 不要退回逐点读：S7 是请求-应答一次一条，库里 PLCNetwork.readFromServer 还
    //   synchronized 在同一把 socket 锁上。逐点读是 ~30 次往返/秒，急停的写要和它们
    //   抢同一把锁 —— 工业网一次往返 5~30ms，最坏近 1s 才轮到急停的第一个字节。
    private static final int    FB_BLOCK_BASE = 1200;
    private static final String ADDR_FB_BLOCK = "V" + FB_BLOCK_BASE;
    private static final int    FB_BLOCK_LEN  = 16;

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
    /** 最近一次收到障碍物判定结果的时刻, 0 = 从未收到(此时不做空窗拦停) */
    private volatile long    obstacleUpdateMs    = 0L;

    /** 定时任务是否已经起过。手动连接 PLC 时要补起来, 但不能重复 schedule */
    private final AtomicBoolean ticksStarted = new AtomicBoolean(false);

    /**
     * 测试点动的独占窗口截止时刻。窗口内 /cmd_vel 一律丢弃 ——
     * 否则人在测试窗点"前进", Nav2 同时还在发速度, 两边抢着写同一组寄存器,
     * 测出来的现象没有任何参考价值。
     */
    private final AtomicLong testHoldUntilMs = new AtomicLong(0);

    /**
     * 「模拟自动导航」点动的 Twist。非 null 时 sendTick 会拿它当成一条 Nav2 /cmd_vel
     * **走完全相同的那条链**(twistToPlcCommand 换算 → executeCommand 模式切换 → ramp 斜坡)，
     * 只是速度来源换成了人填的数、并且有到点必停的窗口兜底。
     *
     * 单点位测试只能证明"这个寄存器写得进去"，证明不了换算系数对不对、ramp 会不会太肉、
     * 模式切换那几个 sleep 够不够 —— 那些只有跑完整条链才看得出来，所以必须有这一项。
     * 下标: [0]=linear(m/s)  [1]=angular(rad/s)
     */
    private volatile double[] testSimTwist = null;

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
            log.info("[PLC] 桥接启动预热已关闭 (plc.bridge.enabled=false)，"
                    + "不在启动阶段起定时任务/建连接。导航页点「连接 PLC」可以随时手动拉起");
            return;
        }
        if (plcEnabled) {
            connectPlc();
        } else {
            log.info("[PLC] 启动连接已关闭 (plc.enabled=false)，跳过 PLC 加载和网络连接");
        }
        ensureTicksStarted();
        log.info("AGV PLC bridge started | host={}:{} | plc.enabled={} | watchdog={}ms",
                plcHost, plcPort, plcEnabled, watchdogMs);
        log.info("[PLC] 轮径={}m 速度标定系数={} 减速比={} 编码器分辨率={} 角度缩放={} 自动导航限速={}r/min",
                wheelDiameterM, rpmScale, gearRatio, encoderCountsPerRev, angularToDegreesScale, autoNavMaxRpm);
    }

    /** 起 20ms 下发 tick 和 1s 状态读取 tick。手动连接 PLC 时也要走这里补起来，但只起一次 */
    private void ensureTicksStarted() {
        if (!ticksStarted.compareAndSet(false, true)) return;
        scheduler.scheduleAtFixedRate(this::sendTick,      100, 20, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::statusReadTick,  1,  1, TimeUnit.SECONDS);
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

    /** 硬件级紧急停车: 先脉冲 PLC 侧急停位, 再清除方向控制位 + 速度归零, 不经过任何缓冲 */
    public void emergencyStop() {
        log.warn("🛑 紧急停车触发!");
        // 标志先置位, 不放进 try: sendTick 第一行就检查它, 置位后下一拍(≤20ms)周期下发就停了,
        // 完全不依赖下面这几次 S7 写能不能成功
        emergencyStopActive.set(true);
        hasPending.set(false);
        pendingLinear = 0;
        pendingAngular = 0;
        lastCmdTimeMs.set(0);
        try {
            // ⚠ 顺序不能倒过来。旧代码是先 forceStop() 那 4 次写、最后才脉冲 V1001.4,
            //   真正的硬急停排在队尾, S7 通道一忙就是几百 ms 起步。
            //   现在 V1001.4 占掉第一个往返, 脉冲的 100ms 保持期拿来做 forceStop, 不白等。
            writeBit(ADDR_SYS_STOP, true);   // ① PLC 侧"系统停止(急停)"
            forceStop();                     // ② 速度/角度归零 + 方向位清零
            Thread.sleep(100);               // ③ 脉冲保持
            writeBit(ADDR_SYS_STOP, false);
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
     * 注入车头前方最近障碍物距离(m)。由 ROS2WebSocketHandler 每帧点云调用
     * (数据源 ObstacleGuardService, 逐帧算的前向走廊净空)。
     *
     * @param minDistM 离**车头**的净空距离; Double.MAX_VALUE = 走廊内没有障碍物
     */
    public void updateObstacleDistance(double minDistM) {
        this.minObstacleDistance = minDistM;
        this.obstacleUpdateMs    = System.currentTimeMillis();

        boolean blocked = obstacleStopDistance > 0 && minDistM < obstacleStopDistance;
        if (blocked) {
            if (!obstacleOverride) {
                obstacleOverride = true;
                log.warn("[PLC避障] 车头前方 {}m < 阈值 {}m, 拦停自动导航",
                        String.format("%.2f", minDistM), String.format("%.2f", obstacleStopDistance));
                // 只在自动导航正在驱动底盘时才主动刹停。手动遥控/测试点动是人在盯着的,
                // 让它们被点云判定抢走控制权只会让现场没法调试。
                if (pendingAutoNav && currentMode != DriveMode.STOP) {
                    try { forceStop(); } catch (Exception ignored) {}
                }
            }
        } else if (obstacleOverride) {
            obstacleOverride = false;
            log.info("[PLC避障] 前方净空恢复 ({}m ≥ 阈值 {}m), 解除拦停",
                    minDistM == Double.MAX_VALUE ? "∞" : String.format("%.2f", minDistM),
                    String.format("%.2f", obstacleStopDistance));
        }
    }

    /**
     * 自动导航是否应当被障碍物判定拦下。
     * 两种情况都拦：① 前方确实有东西；② 判定数据断流(看不见前面 ≠ 前面没东西)。
     */
    private boolean obstacleBlocksAutoNav() {
        if (obstacleStopDistance <= 0) return false;   // 阈值 0 = 整个功能关闭
        if (obstacleOverride) return true;
        // 从没收到过判定结果 → 说明这套链路在当前环境根本没接, 不拦(否则一装就动不了)
        if (obstacleUpdateMs == 0L) return false;
        return System.currentTimeMillis() - obstacleUpdateMs > obstacleStaleMs;
    }

    /** 动态设置障碍物停车阈值(m, 离车头)。0 = 关闭该功能 */
    public void setObstacleStopDistance(double distM) {
        this.obstacleStopDistance = Math.max(0, distM);
        log.info("[PLC] 障碍物停车阈值更新: {}m (0=关闭)", this.obstacleStopDistance);
    }

    public double getObstacleStopDistance() { return obstacleStopDistance; }

    // ======================== 手动连接 / 断开 ========================

    /**
     * 手动连上 PLC。默认配置是不连的(plc.enabled=false)，现场确认可以通电动车之后
     * 才在导航页点这个按钮，避免 Java 一启动就往底盘写寄存器。
     *
     * plc.bridge.enabled=false 的环境下定时任务还没起，这里会一并补起来。
     */
    public synchronized Map<String, Object> enablePlc() {
        plcEnabled = true;
        ensureTicksStarted();
        connectPlc();
        if (!connected.get()) {
            // 连不上就退回未启用，免得后面每条指令都刷"未连接, 跳过写入"
            plcEnabled = false;
            disconnectPlc();
        }
        return plcInfo();
    }

    /** 手动断开 PLC。先把车停住再断，不能留着方向位是 1 的状态走人 */
    public synchronized Map<String, Object> disablePlc() {
        try { forceStop(); } catch (Exception e) {
            log.warn("[PLC] 断开前停车失败: {}", e.getMessage());
        }
        disconnectPlc();
        plcEnabled = false;
        log.warn("[PLC] 已手动断开，后续 writeInt16/writeBit 全部空转(仅演算)");
        return plcInfo();
    }

    /** 连接与运行参数汇总，前端配置面板直接回显这一份 */
    public Map<String, Object> plcInfo() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled",              plcEnabled);
        m.put("connected",            connected.get());
        m.put("bridgePreheat",        plcBridgeEnabled);
        m.put("host",                 plcHost);
        m.put("port",                 plcPort);
        m.put("rack",                 plcRack);
        m.put("slot",                 plcSlot);
        m.put("obstacleStopDistance", obstacleStopDistance);
        m.put("obstacleStaleMs",      obstacleStaleMs);
        m.put("autoNavMaxRpm",        autoNavMaxRpm);
        m.put("rpmScale",             rpmScale);
        m.put("testMaxRpm",           testMaxRpm);
        m.put("testMaxHoldMs",        testMaxHoldMs);
        m.put("watchdogMs",           watchdogMs);
        m.put("emergencyStopActive",  emergencyStopActive.get());
        m.put("faultActive",          plcFaultActive.get());
        m.put("testActive",           isTestActive());
        return m;
    }

    /** 自动导航限速上限(VW1004 r/min)，现场调完速度需要看当前值 */
    public void setAutoNavMaxRpm(int rpm) {
        this.autoNavMaxRpm = Math.max(1, Math.min(3000, rpm));
        log.info("[PLC] 自动导航限速上限更新: {} r/min", this.autoNavMaxRpm);
    }

    // ======================== 倒车距离限制 ========================

    private volatile double odomX, odomY;
    private volatile long   odomMs = 0L;
    /** 本段连续倒车起点(camera_init 系)，null = 当前不在倒车段里 */
    private volatile double[] reverseStartXY = null;
    private volatile boolean reverseLimitLogged = false;

    /** ROS2WebSocketHandler 每帧 /Odometry 回调 */
    public void updateOdomPose(double x, double y, long ms) {
        odomX = x; odomY = y; odomMs = ms;
    }

    /** 新目标开始时清零，上一趟被截停的倒车段不能拖到下一趟 */
    public void resetReverseTracker() {
        reverseStartXY = null;
        reverseLimitLogged = false;
    }

    /** 自动导航倒车指令是否该拦下。fail-safe：没有新鲜里程计就不许倒 */
    private boolean reverseBlocked() {
        if (reverseMaxDistanceM <= 0) return logReverseBlock("plc.reverse.max-distance-m=0，禁止自动倒车");
        if (odomMs == 0L || System.currentTimeMillis() - odomMs > 1000) {
            return logReverseBlock("/Odometry 超过 1s 没更新，量不了倒车距离");
        }
        double[] s = reverseStartXY;
        if (s == null) {
            reverseStartXY = new double[]{odomX, odomY};
            return false;
        }
        double d = Math.hypot(odomX - s[0], odomY - s[1]);
        if (d > reverseMaxDistanceM) {
            return logReverseBlock(String.format("本段已连续倒车 %.2fm > 上限 %.2fm(车尾是雷达盲区)", d, reverseMaxDistanceM));
        }
        return false;
    }

    private boolean logReverseBlock(String why) {
        if (!reverseLimitLogged) {
            log.warn("[倒车限制] 拦下自动导航倒车指令: {}。出现前进指令或发新目标后解除", why);
            reverseLimitLogged = true;
        }
        return true;
    }

    // ======================== 机动：原地旋转 / 平移 ========================
    //
    // Nav2 只管直行模式(弧线+倒车)。原地旋转(VW1002=1)、平移(VW1002=2)由 ManeuverService 在
    // 起点/终点闭环执行，这里只提供"独占底盘 + 切模式 + 给方向和速度"的原子操作。
    //
    // 独占期间 /cmd_vel 一律丢弃；ManeuverService 必须每拍调 maneuverDrive 续命，
    // 超过 watchdogMs 没续命(线程挂了/卡死)sendTick 会自己停车并退出机动 —— 和 /cmd_vel 看门狗同一个道理。

    public enum ManeuverKind { ROTATE, LATERAL }

    private final AtomicBoolean maneuverActive = new AtomicBoolean(false);
    private final AtomicLong    maneuverKeepaliveMs = new AtomicLong(0);
    /** PLC 当前已切到的机动模式，null = 还没切 */
    private volatile ManeuverKind maneuverKind = null;
    /** 当前置位的方向：+1 = V1001.5，-1 = V1001.6，0 = 都没置 */
    private volatile int maneuverDir = 0;
    private volatile long lastManeuverLogMs = 0L;

    public boolean isManeuverActive() { return maneuverActive.get(); }

    /**
     * 机动续命超时(ms)。不能沿用 /cmd_vel 的 500ms 看门狗：机动循环每拍要读里程计 + 写 S7，
     * PLC 忙时一拍就可能几百毫秒，500ms 会把正常平移半路掐断(2026-09-15 现场"侧移出错: 不在机动独占中")
     */
    @Value("${plc.maneuver.watchdog-ms:2000}")
    private volatile long maneuverWatchdogMs;

    /** 机动被非调用方结束的原因(看门狗)，maneuverDrive 报错时带出来 */
    private volatile String maneuverEndReason = null;

    /** 机动循环每拍开头调一次续命，别等到 maneuverDrive 才续(它前面还有读位姿等耗时步骤) */
    public void maneuverKeepalive() {
        if (maneuverActive.get()) maneuverKeepaliveMs.set(System.currentTimeMillis());
    }

    /** 指令真的能写进 PLC(启用且已连接)。否则机动只会空转到超时，调用方应直接放弃 */
    public boolean isPlcWritable() { return plcEnabled && connected.get(); }

    /** 进入机动独占。急停锁/故障/点位测试中直接拒绝 */
    public void beginManeuver() throws Exception {
        if (emergencyStopActive.get()) throw new IllegalStateException("急停锁未解除");
        if (plcFaultActive.get())      throw new IllegalStateException("PLC 故障位置 1 中");
        if (isTestActive())            throw new IllegalStateException("点位测试窗口占用底盘中");
        maneuverEndReason = null;
        maneuverActive.set(true);
        maneuverKeepaliveMs.set(System.currentTimeMillis());
        hasPending.set(false);
        forceStop();
        maneuverKind = null;
        maneuverDir  = 0;
        log.info("[PLC机动] 进入独占，/cmd_vel 暂停转发");
    }

    /**
     * 机动下发一拍。
     *
     * @param kind ROTATE：dir=+1 左向旋转(逆时针，V1001.5)，-1 右向旋转(V1001.6)
     *             LATERAL：dir=+1 向左平移(V1001.5，"前进"=向左)，-1 向右平移(V1001.6)  ← 2026-09-15 现场确认
     * @param rpm  VW1004，0 = 模式保持但不走
     */
    public void maneuverDrive(ManeuverKind kind, int dir, int rpm) throws Exception {
        if (!maneuverActive.get()) {
            throw new IllegalStateException(maneuverEndReason != null ? maneuverEndReason : "不在机动独占中");
        }
        if (emergencyStopActive.get()) throw new IllegalStateException("急停触发");
        if (plcFaultActive.get())      throw new IllegalStateException("PLC 故障位置 1");
        maneuverKeepaliveMs.set(System.currentTimeMillis());

        if (kind != maneuverKind) {
            writeInt16(ADDR_SPEED, 0);
            writeBit(ADDR_FORWARD, false);
            writeBit(ADDR_BACKWARD, false);
            lastSentRpm = 0;
            maneuverDir = 0;
            writeInt16(ADDR_MODE, kind == ManeuverKind.ROTATE ? 1 : 2);
            log.info("[PLC机动] 模式字 {}={} ({})，等待模式反馈 ...", ADDR_MODE,
                    kind == ManeuverKind.ROTATE ? 1 : 2, kind == ManeuverKind.ROTATE ? "原地旋转" : "平移");
            waitModeFeedback(kind == ManeuverKind.ROTATE ? ADDR_MODE_ROTATE_FB : ADDR_MODE_LATERAL_FB);
            maneuverKind = kind;
        }
        if (dir != maneuverDir) {
            writeInt16(ADDR_SPEED, 0);
            lastSentRpm = 0;
            writeBit(ADDR_FORWARD,  dir > 0);
            writeBit(ADDR_BACKWARD, dir < 0);
            maneuverDir = dir;
        }
        int r = rampStep(lastSentRpm, Math.max(0, Math.min(3000, rpm)), RPM_RAMP);
        writeInt16Periodic(ADDR_SPEED, r);
        lastSentRpm = r;

        long now = System.currentTimeMillis();
        if (now - lastManeuverLogMs >= Math.max(100, controlLogIntervalMs)) {
            lastManeuverLogMs = now;
            String dirTxt = kind == ManeuverKind.ROTATE ? (dir > 0 ? "左旋" : dir < 0 ? "右旋" : "-")
                                                        : (dir > 0 ? "左移" : dir < 0 ? "右移" : "-");
            log.info("[PLC机动·寄存器] {}(模式)={} | {}(速度)={} | {}={} | {}={} | {} | {}",
                    ADDR_MODE, kind == ManeuverKind.ROTATE ? 1 : 2, ADDR_SPEED, r,
                    ADDR_FORWARD, dir > 0, ADDR_BACKWARD, dir < 0, dirTxt,
                    !plcEnabled ? "未写PLC(plc.enabled=false，仅演算)" : (connected.get() ? "已写PLC" : "未写PLC(PLC未连接)"));
        }
    }

    /** 退出机动：停车 + 模式字复位直行并等反馈，不能把 PLC 留在旋转/平移模式交给 Nav2 */
    public void endManeuver() {
        try {
            forceStop();
            writeInt16(ADDR_MODE, 0);
            if (maneuverKind != null) waitModeFeedback(ADDR_MODE_STRAIGHT_FB);
        } catch (Exception e) {
            log.error("[PLC机动] 退出时复位直行失败，请现场确认模式: {}", e.getMessage());
        } finally {
            maneuverKind = null;
            maneuverDir  = 0;
            maneuverActive.set(false);
            log.info("[PLC机动] 退出独占，恢复 /cmd_vel 转发");
        }
    }

    /**
     * 等模式反馈位点亮。**不能持有对象锁等** —— emergencyStop 的 writeBit 要抢同一把锁，
     * 在这里 synchronized 睡几秒等于把急停也卡住几秒。
     * PLC 没启用/没连上时只演算，直接放过(车本来也不会动，闭环那边会因为位姿不变而超时)。
     */
    private void waitModeFeedback(String fbAddr) throws Exception {
        if (!plcEnabled || !connected.get() || s7PLC == null) return;
        long deadline = System.currentTimeMillis() + modeSwitchTimeoutMs;
        while (true) {
            // 舵轮转到位要好几秒，期间也得续命，否则 sendTick 的机动看门狗会把这次切换当成线程卡死
            maneuverKeepaliveMs.set(System.currentTimeMillis());
            if (emergencyStopActive.get()) throw new IllegalStateException("等待模式反馈时急停触发");
            byte[] fb = s7PLC.readByte(ADDR_FB_BLOCK, FB_BLOCK_LEN);
            if (blkBit(fb, fbAddr)) return;
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(fbAddr + " 模式反馈 " + modeSwitchTimeoutMs + "ms 未点亮，舵轮可能没转到位");
            }
            Thread.sleep(100);
        }
    }

    // ======================== 点位测试(限时点动) ========================
    //
    // 开自动之前得先知道"这些点位到底通不通"。以前只能靠发导航目标看车动不动，
    // 一旦不动，模式字/方向位/速度字/PLC 连接/Nav2 输出这五层分不清是哪层坏的。
    // 这里把后台实际会写的每一条指令单独拆出来，一条一条点、一条一条看寄存器。
    //
    // 安全约束(现场确认的口子)：
    //   ① 转速上限 plc.test.max-rpm，测试窗不该让车窜出去
    //   ② 单次最长 plc.test.max-hold-ms，到点自动 forceStop，不存在"忘了点停"
    //   ③ 点动期间独占底盘，/cmd_vel 一律丢弃(见 sendTick)
    //   ④ 急停锁/故障位未清时直接拒绝

    /** 测试点动窗口是否还开着 */
    public boolean isTestActive() { return System.currentTimeMillis() < testHoldUntilMs.get(); }

    /**
     * 测试窗支持的动作清单。故意由后端给出而不是前端写死 —— 点位表改了，
     * 前端按钮跟着变，不会出现"界面上有个按钮但后台早就不用这个点位了"。
     */
    public java.util.List<Map<String, Object>> testActions() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();

        // ── ① 前置检查：不写任何字节，先确认 PLC 肯不肯听 ──────────────────
        list.add(action("precheck", "check", "自动导航前置条件自检", false, false, false,
                "读 V1201.7/V1201.3/V1201.5/V1202.6/V1203.* 逐条判定",
                "不写任何寄存器。先跑这条 —— 任何一条 NG，下面全部白测"));
        list.add(action("read", "check", "读取全部反馈寄存器", false, false, false,
                "VW1204/1206/1208/1210 + VD1212 + V1200~V1203 全部状态位",
                "自动运行时 statusReadTick 每秒读的就是这一组，前端状态面板全靠它"));

        // ── ② 使能位：自动链路上只有急停/复位会脉冲这两个 ──────────────────
        list.add(action("sys-start", "enable", "系统启动 (急停复位)", false, false, false,
                ADDR_SYS_START + " 脉冲 1→0",
                "等同于前端「解除急停」走的那条路径 clearEmergencyStopLock()"));
        list.add(action("sys-stop", "enable", "系统停止 (急停)", false, false, false,
                ADDR_SYS_STOP + " 脉冲 1→0",
                "等同于工具栏「⚡急停」走的那条路径 emergencyStop()，会先 forceStop 再脉冲"));

        // ── ③ 单寄存器：一次只动一个点位，坏在哪一位一目了然。都不会让车走 ──
        list.add(action("mode-straight", "reg", "写模式字 = 0 直行", false, false, false,
                ADDR_MODE + "=0",
                "自动导航全程只用这个模式。写完看 V1200.1 直行模式反馈是否点亮"));
        list.add(action("mode-rotate", "reg", "写模式字 = 1 原地旋转", false, false, false,
                ADDR_MODE + "=1",
                "自动导航不用。写完看 V1200.2 旋转模式反馈是否点亮"));
        list.add(action("mode-lateral", "reg", "写模式字 = 2 平移", false, false, false,
                ADDR_MODE + "=2",
                "自动导航不用。写完看 V1200.3 平移模式反馈是否点亮"));
        list.add(action("angle-only", "reg", "只打舵不走 (验转向电机)", false, true, false,
                ADDR_MODE + "=0, " + ADDR_ANGLE + "=角度×10, 方向位保持 0",
                "验 VW1006 通不通 + VW1208 转角反馈能否跟到设定值。负=左 正=右"));
        list.add(action("speed-only", "reg", "只给速度不给方向位", true, false, false,
                ADDR_MODE + "=0, " + ADDR_SPEED + "=速度, " + ADDR_FORWARD + "/" + ADDR_BACKWARD + "=0",
                "⚠ 安全前提验证：车**应该不动**。若这样车就走了，说明方向位不是启动条件，"
                        + "自动导航的停车逻辑(forceStop 只清速度+方向位)全部不成立，必须停下来查 PLC 程序"));
        list.add(action("stop", "reg", "停止 (清速度+角度+方向位)", false, false, false,
                ADDR_SPEED + "=0, " + ADDR_ANGLE + "=0, " + ADDR_FORWARD + "/" + ADDR_BACKWARD + "=0, " + ADDR_MODE + "=0",
                "自动链路上的 forceStop()：看门狗超时、急停、障碍停车、点动到点，走的都是它"));

        // ── ④ 整机动作：会让车走。前四条是自动导航真正会出现的形态 ─────────
        list.add(action("forward", "motion", "直行前进 (自动导航形态)", true, true, true,
                ADDR_MODE + "=0, " + ADDR_SPEED + "=速度, " + ADDR_ANGLE + "=角度×10, " + ADDR_FORWARD + "=1",
                "角度填 0 就是纯直行。这就是 Nav2 让车往前走时写的那组寄存器"));
        list.add(action("backward", "motion", "直行后退 (自动导航形态)", true, true, true,
                ADDR_MODE + "=0, " + ADDR_SPEED + "=速度, " + ADDR_ANGLE + "=角度×10, " + ADDR_BACKWARD + "=1",
                "Nav2 倒车时写的那组寄存器"));
        list.add(action("forward-left", "motion", "前进 + 左打舵", true, true, true,
                ADDR_MODE + "=0, " + ADDR_SPEED + "=速度, " + ADDR_ANGLE + "=-|角度|×10, " + ADDR_FORWARD + "=1",
                "自动导航**左转就是这个形态**，不是原地旋转。角度取输入值的绝对值再置负"));
        list.add(action("forward-right", "motion", "前进 + 右打舵", true, true, true,
                ADDR_MODE + "=0, " + ADDR_SPEED + "=速度, " + ADDR_ANGLE + "=+|角度|×10, " + ADDR_FORWARD + "=1",
                "自动导航右转的形态。角度取输入值的绝对值"));
        list.add(action("rotate-left", "motion", "原地左转 (自动不用)", true, false, true,
                ADDR_MODE + "=1, " + ADDR_SPEED + "=速度, " + ADDR_FORWARD + "=1 (旋转模式下=左向)",
                "⚠ 转一圈扫出的是直径约 6.9m 的圆，远大于车身。先用工具栏「旋转判定」确认净空"));
        list.add(action("rotate-right", "motion", "原地右转 (自动不用)", true, false, true,
                ADDR_MODE + "=1, " + ADDR_SPEED + "=速度, " + ADDR_BACKWARD + "=1 (旋转模式下=右向)",
                "同上，先做净空判定"));

        // ── ⑤ 整链路：单点位测不出换算系数和 ramp，只有跑完整条链才看得出来 ──
        list.add(action("auto-sim", "chain", "★ 模拟一条 Nav2 速度指令", false, false, true,
                "linear/angular → twistToPlcCommand → executeCommand (含 ramp 斜坡 + 模式切换)",
                "填 ROS 的 linear.x(m/s) 和 angular.z(rad/s)，走的是和自动导航**完全相同**的代码路径。"
                        + "这条通了，自动导航的下发环节就通了。rpm=|v|×60/(π×" + wheelDiameterM + ")×" + rpmScale + "，"
                        + "角度=-angular×" + angularToDegreesScale + "(ROS左转为正，PLC右转为正，要取反)"));
        return list;
    }

    private Map<String, Object> action(String code, String group, String label,
                                       boolean needSpeed, boolean needAngle, boolean motion,
                                       String regs, String hint) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("code", code);
        m.put("group", group);     // check / enable / reg / motion / chain
        m.put("label", label);
        m.put("regs", regs);
        m.put("hint", hint);
        m.put("needSpeed", needSpeed);
        m.put("needAngle", needAngle);
        // 整链路验证填的是 ROS 的 linear/angular，不是 rpm/角度 —— 换算正是它要验的东西
        m.put("needTwist", "chain".equals(group));
        m.put("motion", motion);   // true = 会让车动，前端要二次确认 + 限时点动
        return m;
    }

    /** 分组标题，前端按这个分段渲染 */
    public java.util.List<Map<String, Object>> testGroups() {
        java.util.List<Map<String, Object>> gs = new java.util.ArrayList<>();
        gs.add(group("check",  "① 前置检查",   "不写任何寄存器。这一组全过了再往下走"));
        gs.add(group("enable", "② 使能位",     "自动链路上只有急停/复位会脉冲这两个位"));
        gs.add(group("reg",    "③ 单寄存器",   "一次只动一个点位，坏在哪一位一目了然。都不会让车走"));
        gs.add(group("motion", "④ 整机动作",   "会让车走。前四条就是自动导航真正会出现的形态"));
        gs.add(group("chain",  "⑤ 整链路验证", "单点位证明不了换算系数和 ramp 对不对，只有跑完整条链才看得出来"));
        return gs;
    }

    private Map<String, Object> group(String key, String title, String desc) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("desc", desc);
        return m;
    }

    /**
     * 执行一条测试指令。
     *
     * @param code     testActions() 里的 code
     * @param speed    VW1004 转速，null 用 20；一律钳到 plc.test.max-rpm 以内
     * @param angleDeg VW1006 角度(°)，负=左 正=右，null 用 0；钳到 ±45
     * @param holdMs   运动类动作的保持时间，null 用 1000；钳到 200 ~ plc.test.max-hold-ms
     * @return 实际写了哪些寄存器、写没写进 PLC
     */
    public synchronized Map<String, Object> runTest(String code, Integer speed, Double angleDeg, Integer holdMs,
                                                    Double linear, Double angular) {
        if (emergencyStopActive.get()) {
            throw new IllegalStateException("Java 侧急停锁未解除，请先点「解除急停」再测试");
        }
        if (plcFaultActive.get()) {
            throw new IllegalStateException("PLC 故障位置 1 中，请到现场确认并复位后再测试");
        }

        int rpm = clamp(speed == null ? 20 : speed, 0, testMaxRpm);
        int angleTenths = (int) Math.round(clampD(angleDeg == null ? 0 : angleDeg,
                -STRAIGHT_MAX_ANGLE_DEG, STRAIGHT_MAX_ANGLE_DEG) * 10);
        long hold = clampL(holdMs == null ? 1000 : holdMs, 200, testMaxHoldMs);

        java.util.List<Map<String, Object>> regs = new java.util.ArrayList<>();
        String message;

        try {
            switch (code == null ? "" : code) {
                // ── ① 前置检查 ─────────────────────────────────────────
                case "precheck": {
                    statusReadTick();
                    PlcStatus st = lastStatus;
                    boolean linkOk = plcEnabled && connected.get();

                    // 这一串就是"PLC 到底肯不肯听 Java"的全部条件。
                    // 任何一条 NG，后面所有测试和自动导航都没有意义。
                    regs.add(chk("V1201.7", "上位控制模式", st.isUpperComputerMode(), true,
                            "false 时 PLC 根本不理会 S7 写进来的指令 —— 必须先在屏上切到上位控制"));
                    regs.add(chk("V1201.3", "系统已启动",   st.isSystemStarted(), true,
                            "false 时先点上面的「系统启动(急停复位)」"));
                    regs.add(chk("V1201.2", "找零完成",     st.isHomed(), true,
                            "舵轮没找零，转角指令不会被执行"));
                    regs.add(chk("V1201.5", "故障中",       st.isFault(), false,
                            "true 时必须到现场排故并复位"));
                    regs.add(chk("V1202.6", "外部停止中",   st.isExternalStop(), false,
                            "true 时有外部信号把车锁住了"));
                    regs.add(chk("V1202.1", "屏手动中",     st.isScreenManualActive(), false,
                            "true 时屏在抢控制权"));
                    regs.add(chk("V1202.0", "舵轮未找零",   st.isSteerNotHomed(), false,
                            "true 时转角指令不会被执行"));
                    regs.add(chk("V1202.5", "电量低",       st.isLowBattery(), false,
                            "电量低时不要开自动"));
                    regs.add(chk("V1203.0", "急停故障",     st.isFaultEmergencyStop(), false, "现场急停被按下"));
                    regs.add(chk("V1203.2", "防撞触边",     st.isFaultBumper(), false, "触边被压住"));
                    regs.add(chk("V1203.3", "转向找零超时", st.isFaultHomingTimeout(), false, ""));
                    regs.add(chk("V1203.4", "行走电机故障", st.isFaultDriveMotor(), false, ""));
                    regs.add(chk("V1203.5", "转向电机故障", st.isFaultSteerMotor(), false, ""));
                    regs.add(chk("V1203.6", "驱动器CAN异常", st.isFaultCan(), false, ""));

                    int ng = 0;
                    for (Map<String, Object> r : regs) {
                        if (!Boolean.TRUE.equals(r.get("ok"))) ng++;
                    }
                    if (!linkOk) {
                        message = "⚠ PLC 未连接，上面读到的全是 0，这份自检结果不作数。请先点「连接」";
                    } else if (ng == 0) {
                        message = "✅ 全部通过，PLC 侧已具备接受上位指令的条件。可以往下逐条测点位了";
                    } else {
                        message = "❌ " + ng + " 项未通过(红色行)。这些没解决之前，"
                                + "下面的点位测试和自动导航都不会有正常表现";
                    }
                    break;
                }

                case "sys-start":
                    pulseBit(ADDR_SYS_START, 100);
                    regs.add(reg(ADDR_SYS_START, "系统启动", "脉冲 1 → 0 (保持100ms)"));
                    message = "已脉冲系统启动位。正常应看到 V1201.3 系统启动状态变为 true";
                    break;

                case "sys-stop":
                    pulseBit(ADDR_SYS_STOP, 100);
                    regs.add(reg(ADDR_SYS_STOP, "系统停止", "脉冲 1 → 0 (保持100ms)"));
                    message = "已脉冲系统停止位。正常应看到 V1203.0 急停故障或 V1201.3 掉下来";
                    break;

                case "stop":
                    stopTest();
                    regs.add(reg(ADDR_SPEED, "速度", "0"));
                    regs.add(reg(ADDR_ANGLE, "角度", "0"));
                    regs.add(reg(ADDR_FORWARD,  "前进", "false"));
                    regs.add(reg(ADDR_BACKWARD, "后退", "false"));
                    regs.add(reg(ADDR_MODE, "模式", "0 (直行, 复位)"));
                    message = "已清零速度/角度并断开方向位";
                    break;

                // ── ③ 单寄存器：一次只动一个点位 ───────────────────────
                case "mode-straight":
                case "mode-rotate":
                case "mode-lateral": {
                    int w = "mode-straight".equals(code) ? 0 : ("mode-rotate".equals(code) ? 1 : 2);
                    String[] name = { "直行", "原地旋转", "平移" };
                    String[] fb   = { ADDR_MODE_STRAIGHT_FB, ADDR_MODE_ROTATE_FB, ADDR_MODE_LATERAL_FB };
                    // 只写模式字，方向位和速度保持 0 —— 换模式本身不该让车动
                    beginTest(hold);
                    writeInt16(ADDR_SPEED, 0);
                    writeBit(ADDR_FORWARD,  false);
                    writeBit(ADDR_BACKWARD, false);
                    writeInt16(ADDR_MODE, w);
                    regs.add(reg(ADDR_MODE,  "模式", w + " (" + name[w] + ")"));
                    regs.add(reg(ADDR_SPEED, "速度", "0 (只换模式,不给速度)"));
                    message = "车应该不动，只有舵轮姿态可能变化。看 " + fb[w] + " " + name[w]
                            + "模式反馈是否点亮；" + hold + "ms 后自动复位成直行";
                    break;
                }

                case "angle-only":
                    beginTest(hold);
                    writeInt16(ADDR_MODE, 0);
                    writeInt16(ADDR_SPEED, 0);
                    writeInt16(ADDR_ANGLE, angleTenths);
                    regs.add(reg(ADDR_MODE,  "模式", "0 (直行)"));
                    regs.add(reg(ADDR_SPEED, "速度", "0 (不走)"));
                    regs.add(reg(ADDR_ANGLE, "角度", angleTenths + " → " + fmtAngle(angleTenths)));
                    message = "只打舵不给速度。看 VW1208 转角反馈能否跟到 " + fmtAngle(angleTenths)
                            + "，" + hold + "ms 后自动回正";
                    break;

                case "speed-only":
                    beginTest(hold);
                    writeInt16(ADDR_MODE, 0);
                    writeInt16(ADDR_ANGLE, 0);
                    writeBit(ADDR_FORWARD,  false);
                    writeBit(ADDR_BACKWARD, false);
                    writeInt16(ADDR_SPEED, rpm);
                    regs.add(reg(ADDR_MODE,     "模式", "0 (直行)"));
                    regs.add(reg(ADDR_SPEED,    "速度", rpm + " r/min"));
                    regs.add(reg(ADDR_FORWARD,  "前进", "false"));
                    regs.add(reg(ADDR_BACKWARD, "后退", "false"));
                    message = "⚠ 车**应该纹丝不动**。如果车走了，说明方向位不是启动条件，"
                            + "自动导航靠 forceStop() 清速度+方向位来停车的做法就不成立，"
                            + "必须先查 PLC 程序再谈开自动。" + hold + "ms 后速度自动清零";
                    break;

                // ── ④ 整机动作 ────────────────────────────────────────
                case "forward":
                case "backward":
                case "forward-left":
                case "forward-right":
                case "rotate-left":
                case "rotate-right": {
                    boolean rotate  = code.startsWith("rotate");
                    boolean steer   = code.startsWith("forward-");
                    // 旋转模式下 V1001.5 是"左向旋转"、V1001.6 是"右向旋转"，不再是前进/后退
                    boolean useFwdBit = !"backward".equals(code) && !"rotate-right".equals(code);
                    int modeWord = rotate ? 1 : 0;
                    // 打舵动作强制用输入角度的绝对值定符号，免得"点左转却填了正角度"测出个右转
                    int angleW = rotate ? 0
                            : steer ? (("forward-left".equals(code) ? -1 : 1) * Math.abs(angleTenths))
                            : angleTenths;

                    beginTest(hold);
                    // 先归零再切模式，和 executeCommand 的模式切换流程保持一致
                    writeInt16(ADDR_SPEED, 0);
                    writeInt16(ADDR_ANGLE, 0);
                    writeBit(ADDR_FORWARD,  false);
                    writeBit(ADDR_BACKWARD, false);
                    Thread.sleep(50);
                    writeInt16(ADDR_MODE, modeWord);
                    Thread.sleep(30);
                    writeInt16(ADDR_ANGLE, angleW);
                    writeInt16(ADDR_SPEED, rpm);
                    writeBit(useFwdBit ? ADDR_FORWARD : ADDR_BACKWARD, true);

                    regs.add(reg(ADDR_MODE,  "模式", modeWord + (rotate ? " (原地旋转)" : " (直行)")));
                    regs.add(reg(ADDR_SPEED, "速度", rpm + " r/min"));
                    regs.add(reg(ADDR_ANGLE, "角度", angleW + " → " + fmtAngle(angleW)));
                    regs.add(reg(useFwdBit ? ADDR_FORWARD : ADDR_BACKWARD,
                            rotate ? (useFwdBit ? "左向旋转" : "右向旋转") : (useFwdBit ? "前进" : "后退"),
                            "true"));
                    message = hold + "ms 后自动停车并复位模式字。看 V1201.4 行走中是否点亮、VW1204 速度反馈有没有跟上"
                            + (steer ? "，以及车头是否朝 " + (angleW < 0 ? "左" : "右") + " 偏" : "");
                    break;
                }

                // ── ⑤ 整链路：和自动导航走完全相同的代码路径 ─────────────
                case "auto-sim": {
                    double lin = clampD(linear  == null ? 0.2 : linear,  -1.5, 1.5);
                    double ang = clampD(angular == null ? 0.0 : angular, -1.5, 1.5);

                    // 先把换算结果算出来给人看，再交给 sendTick 按 20ms 一拍持续跑完窗口。
                    // 不在这里直接 executeCommand —— 那样只跑一拍，ramp 永远到不了目标值，
                    // 测不出"斜坡是不是太肉、几秒才能到速"这类真正会影响自动导航的问题。
                    PlcCommand preview = twistToPlcCommand(lin, ang, true);
                    int prevAngleTenths = (int) Math.round(preview.angleDeg * 10);

                    beginTest(hold);
                    testSimTwist = new double[]{ lin, ang };
                    lastCmdTimeMs.set(System.currentTimeMillis());

                    regs.add(reg("linear.x",  "ROS线速度", String.format("%.3f", lin) + " m/s"));
                    regs.add(reg("angular.z", "ROS角速度", String.format("%.3f", ang) + " rad/s"));
                    regs.add(reg(ADDR_MODE,  "模式", "0 (直行, 自动导航只用这个)"));
                    regs.add(reg(ADDR_SPEED, "速度", preview.rpm + " r/min (已按 auto-nav.max-rpm="
                            + autoNavMaxRpm + " 限速)"));
                    regs.add(reg(ADDR_ANGLE, "角度", prevAngleTenths + " → " + fmtAngle(prevAngleTenths)));
                    regs.add(reg(preview.mode == DriveMode.STRAIGHT_BACKWARD ? ADDR_BACKWARD : ADDR_FORWARD,
                            preview.mode == DriveMode.STRAIGHT_BACKWARD ? "后退" : "前进",
                            preview.mode == DriveMode.STOP ? "false (线速度在死区内, 判定为停车)" : "true"));

                    message = "走的是和自动导航完全相同的代码路径(twistToPlcCommand → executeCommand)，"
                            + "含 ramp 斜坡(" + RPM_RAMP + " r/min·拍, " + (ANGLE_TENTHS_RAMP / 10.0) + "°·拍, 20ms 一拍)。"
                            + hold + "ms 后自动停。左侧「控制历史」会像真自动导航一样逐条刷 [PLC控制] 记录 —— "
                            + "对着看 VW1204 速度反馈能不能跟上目标 " + preview.rpm + " r/min";
                    break;
                }

                case "read": {
                    statusReadTick();
                    PlcStatus st = lastStatus;
                    regs.add(reg(ADDR_FB_SPEED,      "当前速度",   st.getFeedbackSpeed() + " r/min"));
                    regs.add(reg(ADDR_FB_MOTOR_RPM,  "电机转速",   st.getFeedbackMotorRpm() + " r/min"));
                    regs.add(reg(ADDR_FB_ANGLE,      "转角反馈",   st.getFeedbackAngleDeg() + "°"));
                    regs.add(reg(ADDR_FB_BATTERY,    "电量",       String.valueOf(st.getBattery())));
                    regs.add(reg(ADDR_FB_REAR_L_POS, "后左轮位置", st.getRearLeftWheelRaw() + " → "
                            + String.format("%.2f", st.getRearLeftWheelDistM()) + "m"));
                    regs.add(reg(ADDR_MODE_STRAIGHT_FB, "直行模式", String.valueOf(st.isStraightModeFb())));
                    regs.add(reg(ADDR_MODE_ROTATE_FB,   "旋转模式", String.valueOf(st.isRotateModeFb())));
                    regs.add(reg(ADDR_SYS_STARTED,      "系统启动", String.valueOf(st.isSystemStarted())));
                    regs.add(reg(ADDR_WALKING,          "行走中",   String.valueOf(st.isWalking())));
                    regs.add(reg(ADDR_HOMED,            "找零完成", String.valueOf(st.isHomed())));
                    regs.add(reg(ADDR_UPPER_MODE,       "上位控制", String.valueOf(st.isUpperComputerMode())));
                    regs.add(reg(ADDR_FAULT,            "故障中",   String.valueOf(st.isFault())));
                    message = plcEnabled && connected.get()
                            ? "已实时读取。⚠ V1201.7 上位控制模式必须是 true，否则 PLC 根本不理会 S7 写进来的指令"
                            : "PLC 未连接，读到的全是 0，不代表现场状态";
                    break;
                }

                default:
                    throw new IllegalArgumentException("未知测试动作: " + code);
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            testHoldUntilMs.set(0);
            throw new IllegalStateException("测试指令执行失败: " + e.getMessage(), e);
        }

        String wire = !plcEnabled ? "未写PLC(plc.enabled=false，仅演算)"
                : (connected.get() ? "已写PLC" : "未写PLC(PLC未连接)");

        log.info("[PLC测试] 动作={} | 速度={} | 角度={} | 保持={}ms | {}",
                code, rpm, fmtAngle(angleTenths), hold, wire);
        for (Map<String, Object> r : regs) {
            Object ok = r.get("ok");
            log.info("[PLC测试·寄存器] {} {} = {}{}", r.get("addr"), r.get("name"), r.get("value"),
                    ok == null ? "" : (Boolean.TRUE.equals(ok) ? "  [OK]" : "  [NG] " + r.get("why")));
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("code",    code);
        out.put("regs",    regs);
        out.put("wire",    wire);
        out.put("message", message);
        out.put("holdMs",  hold);
        out.put("plcEnabled", plcEnabled);
        out.put("connected",  connected.get());
        return out;
    }

    /**
     * 自检行。和 reg() 的区别是多一个 ok 判定 —— 前端据此标红/标绿，
     * 不用人对着一堆 true/false 自己回忆"哪些位是 true 才对、哪些是 false 才对"。
     *
     * @param expect 这一位取什么值才算正常
     */
    private Map<String, Object> chk(String addr, String name, boolean actual, boolean expect, String why) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("addr",  addr);
        m.put("name",  name);
        m.put("value", actual + (actual == expect ? "" : "  (应为 " + expect + ")"));
        m.put("ok",    actual == expect);
        m.put("why",   actual == expect ? "" : why);
        return m;
    }

    /** 开一个点动窗口：抢占底盘 + 挂一个到点必停的定时器 */
    private void beginTest(long holdMs) {
        testHoldUntilMs.set(System.currentTimeMillis() + holdMs);
        scheduler.schedule(() -> {
            try {
                stopTest();
                log.info("[PLC测试] {}ms 点动窗口结束，已自动停车", holdMs);
            } catch (Exception e) {
                log.error("[PLC测试] 自动停车失败！请立即按现场急停: {}", e.getMessage());
            }
        }, holdMs, TimeUnit.MILLISECONDS);
    }

    /** 点动收尾：停车 + 把模式字复位成直行，别把 PLC 留在旋转模式下给下一次自动导航 */
    private synchronized void stopTest() throws Exception {
        testSimTwist = null;   // 必须先清，否则窗口刚关 sendTick 可能再补一拍把速度写回去
        forceStop();
        writeInt16(ADDR_MODE, 0);
        testHoldUntilMs.set(0);
    }

    private static String fmtAngle(int tenths) {
        return String.format("%.1f", tenths / 10.0) + "°"
                + (tenths == 0 ? "" : tenths < 0 ? "(左)" : "(右)");
    }

    private static int clamp(int v, int lo, int hi)          { return Math.max(lo, Math.min(hi, v)); }
    private static long clampL(long v, long lo, long hi)     { return Math.max(lo, Math.min(hi, v)); }
    private static double clampD(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 读取 PLC 综合状态, 供 REST API 返回给前端(直接返回 statusReadTick 缓存, 避免阻塞请求线程) */
    public PlcStatus readStatus() {
        return lastStatus;
    }

    public boolean isConnected()          { return connected.get(); }
    public DriveMode getCurrentMode()     { return currentMode; }
    public int  getLastRpm()              { return lastSentRpm; }
    public double getLastAngleDeg()       { return lastSentAngleTenths / 10.0; }

    /**
     * 前向避障画弯走廊用的舵角：有新鲜转角反馈(300ms 内)就用反馈，否则退回实发值。
     * 舵轮在转的途中，车实际按反馈角度走，按实发值画走廊会弯错方向。
     */
    public double getSteerAngleForGuardDeg() {
        double fb = steerFbDeg;
        return (!Double.isNaN(fb) && System.currentTimeMillis() - steerFbMs < 300) ? fb : getLastAngleDeg();
    }

    // ── 转角反馈(VW1208) ─────────────────────────────────────────────────
    private volatile boolean preSteering = false;
    private volatile long    preSteerSinceMs = 0L;
    private volatile boolean preSteerTimeoutLogged = false;
    private volatile double  steerFbDeg = Double.NaN;
    private volatile long    steerFbMs  = 0L;

    /**
     * 读转角反馈(°)，按 steerFeedbackIntervalMs 节流(反馈区一次连续读，和 statusReadTick 同一块)。
     * PLC 没启用/没连上返回 NaN —— 调用方按"无反馈、不卡"处理，只演算。
     * ⚠ 别把节流调得太小：S7 一次一条，读得越勤急停的写越要排队(见 FB_BLOCK 处说明)。
     */
    private double readSteerFeedbackDeg() {
        if (!plcEnabled || !connected.get() || s7PLC == null) return Double.NaN;
        long now = System.currentTimeMillis();
        if (now - steerFbMs >= Math.max(20, steerFeedbackIntervalMs)) {
            try {
                byte[] fb = s7PLC.readByte(ADDR_FB_BLOCK, FB_BLOCK_LEN);
                steerFbDeg = blkWord(fb, ADDR_FB_ANGLE) / 10.0;
                steerFbMs  = now;
            } catch (Exception e) {
                log.debug("[转角反馈] 读取失败: {}", e.getMessage());
                return Double.NaN;
            }
        }
        return steerFbDeg;
    }
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
            if (plcFaultActive.get()) return;
            // 机动(原地旋转/平移)独占：寄存器由 ManeuverService 直接写，这里只丢指令 + 看门狗
            if (maneuverActive.get()) {
                hasPending.set(false);
                long silent = System.currentTimeMillis() - maneuverKeepaliveMs.get();
                if (silent > maneuverWatchdogMs) {
                    maneuverEndReason = "机动看门狗: " + silent + "ms 没有续命(上限 " + maneuverWatchdogMs + "ms)，已强制停车";
                    log.error("[PLC机动] {}", maneuverEndReason);
                    endManeuver();
                }
                return;
            }
            // 测试点动期间独占底盘：把这段时间里到达的 /cmd_vel 直接丢掉，
            // 不能让 Nav2 和测试窗抢着写同一组寄存器
            if (isTestActive()) {
                if (pendingAutoNav) hasPending.set(false);
                // 「模拟自动导航」项：这里**不**走捷径直接写寄存器，而是原样跑
                // twistToPlcCommand + executeCommand，这样 ramp 斜坡、模式切换时序、
                // 日志与 plc_control 推送全都和真自动导航一模一样，测出来才算数
                double[] sim = testSimTwist;
                if (sim != null) {
                    executeCommand(twistToPlcCommand(sim[0], sim[1], true));
                }
                return;
            }
            // 障碍物只拦自动导航。手动遥控/测试点动是人在盯着看的，不该被点云判定夺权
            if (pendingAutoNav && obstacleBlocksAutoNav()) {
                hasPending.set(false);
                return;
            }

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
            if (pendingAutoNav) {
                if (cmd.mode == DriveMode.STRAIGHT_BACKWARD && reverseBlocked()) {
                    if (currentMode != DriveMode.STOP) forceStop();
                    return;
                }
                if (cmd.mode == DriveMode.STRAIGHT_FORWARD) resetReverseTracker();
            }
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
     *   rpm   = |v| × 60 / (π × 轮径) × rpmScale   ← 不乘减速比, 乘的是现场标定系数
     *   angle = -atan(等效轴距 × ω / v)   自动导航且 steerMode=1(运动学)
     *         = -angular × angularScale    手动遥控, 或 steerMode=0
     *   (ROS 左转为正, PLC 负=左, 取反)
     *
     * 运动学公式里 v 带符号：倒车时同样的 ω 要反打舵(前进左打舵车头左转，倒车左打舵车头右转)。
     * Nav2(RPP) 输出的 ω = v × 曲率，所以 ω/v 就是它要的曲率，低速时也不会发散。
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

        // ⚠ VW1004 的单位不是"轮子 r/min"。纯几何换算出来的值现场实测慢了约一个数量级
        // (0.3m/s 只给出 12，车几乎不走)，所以要乘一个现场标定的 rpmScale。
        // 具体是电机端转速还是 PLC 内部另一套刻度不重要 —— 系数进 yml，现场对着车速计校准即可。
        double rpm = Math.abs(linear) * 60.0 / circumference * rpmScale;
        rpm = Math.max(0, Math.min(3000, rpm));
        if (autoNav) rpm = Math.min(rpm, autoNavMaxRpm);
        if (autoNav && linear < 0) rpm = Math.min(rpm, reverseMaxRpm);

        double angleDeg = (autoNav && steerMode == 1)
                ? -Math.toDegrees(Math.atan(wheelbaseM * angular / linear))
                : -angular * angularToDegreesScale;
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
     *   4. 【起步打舵】只写目标角度、速度保持 0、方向位保持 0，每拍读 VW1208 转角反馈，
     *      进入 ±steerReadyTolDeg 才置方向位 —— 舵轮转到位要时间，以前角度和前进一起下发，
     *      车轮还没转过去车就走了，起步第一段必然跑偏(2026-09-15 现场)
     *   5. Ramp 平滑写入速度/角度；行驶中转角反馈与目标差 > steerSlowErrorDeg 时限速到 steerSlowRpm
     *
     * 起步打舵不在锁里 sleep 等：每拍(20ms)进来查一次，没到位就 return，急停照样能插进来。
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

            // 方向位先不置，进入起步打舵
            currentMode      = cmd.mode;
            preSteering      = true;
            preSteerSinceMs  = System.currentTimeMillis();
            preSteerTimeoutLogged = false;
        }

        if (currentMode == DriveMode.STOP) {
            logControlCommand(cmd, 0, 0);
            return;
        }

        if (preSteering) {
            // 目标角度直接给(不走 ramp)，让舵轮尽快转；速度 0、方向位 0，车不会动
            writeInt16Periodic(ADDR_ANGLE, angleTenths);
            writeInt16Periodic(ADDR_SPEED, 0);
            lastSentAngleTenths = angleTenths;
            lastSentRpm = 0;
            double fb = readSteerFeedbackDeg();
            long waited = System.currentTimeMillis() - preSteerSinceMs;
            // 没接 PLC(读不到反馈)时只演算，不卡住
            if (!Double.isNaN(fb) && Math.abs(fb - cmd.angleDeg) > steerReadyTolDeg) {
                if (waited > steerReadyTimeoutMs && !preSteerTimeoutLogged) {
                    log.error("[起步打舵] {}ms 转角仍未到位: 目标 {}° 反馈 {}° (容差 ±{}°)，不起步。查转向电机/找零/容差是否过严",
                            steerReadyTimeoutMs, String.format("%.1f", cmd.angleDeg), String.format("%.1f", fb), steerReadyTolDeg);
                    preSteerTimeoutLogged = true;
                }
                logControlCommand(cmd, 0, angleTenths);
                return;
            }
            writeBit(cmd.mode == DriveMode.STRAIGHT_BACKWARD ? ADDR_BACKWARD : ADDR_FORWARD, true);
            preSteering = false;
            log.info("[起步打舵] 转角到位({}° / 目标 {}°, 等了 {}ms)，{}", Double.isNaN(fb) ? "无反馈" : String.format("%.1f", fb),
                    String.format("%.1f", cmd.angleDeg), waited, driveModeLabel(cmd.mode));
            Thread.sleep(20);
        }

        // 行驶中舵角没跟上：限速，别按错的角度多走一大段
        int targetRpm = cmd.rpm;
        double fbNow = readSteerFeedbackDeg();
        if (!Double.isNaN(fbNow) && Math.abs(fbNow - cmd.angleDeg) > steerSlowErrorDeg) {
            targetRpm = Math.min(targetRpm, steerSlowRpm);
        }

        int rampedRpm         = rampStep(lastSentRpm, targetRpm, RPM_RAMP);
        int rampedAngleTenths = rampStep(lastSentAngleTenths, angleTenths, ANGLE_TENTHS_RAMP);

        writeInt16Periodic(ADDR_SPEED, rampedRpm);
        writeInt16Periodic(ADDR_ANGLE, rampedAngleTenths);

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
        // 起步打舵期间方向位实际是 0，日志必须照实写，别让人以为"方向位给了车却不走"
        boolean fwd = cmd.mode == DriveMode.STRAIGHT_FORWARD && !preSteering;
        boolean bwd = cmd.mode == DriveMode.STRAIGHT_BACKWARD && !preSteering;
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
        preSteering = false;
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
            // 全部反馈点位一次连续区读拿回来(见 ADDR_FB_BLOCK 处的说明)。
            // 读失败时整拍放弃, lastStatus 保留上一次的值 —— 比逐点读那样个别点位
            // 静默回 0/false、前端看到一堆假零要好
            byte[] fb = s7PLC.readByte(ADDR_FB_BLOCK, FB_BLOCK_LEN);

            PlcStatus st = new PlcStatus();
            st.setConnected(connected.get());
            st.setMode(currentMode.name());
            st.setRpm(lastSentRpm);
            st.setAngleDeg(lastSentAngleTenths / 10.0);

            st.setFeedbackSpeed(blkWord(fb, ADDR_FB_SPEED));
            st.setFeedbackMotorRpm(blkWord(fb, ADDR_FB_MOTOR_RPM));
            st.setFeedbackAngleDeg(blkWord(fb, ADDR_FB_ANGLE) / 10.0);
            steerFbDeg = blkWord(fb, ADDR_FB_ANGLE) / 10.0;
            steerFbMs  = System.currentTimeMillis();
            st.setBattery(blkWord(fb, ADDR_FB_BATTERY));

            long rawPos = blkDword(fb, ADDR_FB_REAR_L_POS);
            st.setRearLeftWheelRaw(rawPos);
            st.setRearLeftWheelDistM(rawPos / encoderCountsPerRev / gearRatio * Math.PI * wheelDiameterM);

            st.setScreenManualMode(blkBit(fb, ADDR_MODE_MANUAL_FB));
            st.setStraightModeFb(blkBit(fb, ADDR_MODE_STRAIGHT_FB));
            st.setRotateModeFb(blkBit(fb, ADDR_MODE_ROTATE_FB));
            st.setLateralModeFb(blkBit(fb, ADDR_MODE_LATERAL_FB));

            st.setHoming(blkBit(fb, ADDR_HOMING));
            st.setHomed(blkBit(fb, ADDR_HOMED));
            st.setSystemStarted(blkBit(fb, ADDR_SYS_STARTED));
            st.setWalking(blkBit(fb, ADDR_WALKING));
            st.setFault(blkBit(fb, ADDR_FAULT));
            st.setRemoteMode(blkBit(fb, ADDR_REMOTE_MODE));
            st.setUpperComputerMode(blkBit(fb, ADDR_UPPER_MODE));

            st.setSteerNotHomed(blkBit(fb, ADDR_STEER_NOT_HOMED));
            st.setScreenManualActive(blkBit(fb, ADDR_SCREEN_MANUAL));
            st.setSteerHoming(blkBit(fb, ADDR_STEER_HOMING));
            st.setLowBattery(blkBit(fb, ADDR_LOW_BATTERY));
            st.setExternalStop(blkBit(fb, ADDR_EXT_STOP));

            boolean faultEstop  = blkBit(fb, ADDR_FAULT_ESTOP);
            boolean faultBumper = blkBit(fb, ADDR_FAULT_BUMPER);
            boolean faultHoming = blkBit(fb, ADDR_FAULT_HOMING_TIMEOUT);
            boolean faultDrive  = blkBit(fb, ADDR_FAULT_DRIVE_MOTOR);
            boolean faultSteer  = blkBit(fb, ADDR_FAULT_STEER_MOTOR);
            boolean faultCan    = blkBit(fb, ADDR_FAULT_CAN);
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
            // ⚠ iot-communication 的 S7PLC 是**懒连接**：构造函数只记下参数，TCP 握手要等第一次读写
            // 才发生。所以构造完立刻 checkConnected() 必然是 false —— PLC 明明是通的也会报连接失败。
            // 这里改用一次真实读当探针：读得到才算连上，顺带把 rack/slot 配错、地址无权限这类问题
            // 一起在"连接"这一步暴露掉，而不是留到第一次下发指令时才炸。
            s7PLC.readInt16(ADDR_FB_SPEED);
            connected.set(true);
            log.info("✅ [PLC] 连接成功: {}:{} rack={} slot={} (探针读 {} 通过)",
                    plcHost, plcPort, plcRack, plcSlot, ADDR_FB_SPEED);
        } catch (Exception e) {
            connected.set(false);
            if (s7PLC != null) {
                try { s7PLC.close(); } catch (Exception ignored) {}
                s7PLC = null;
            }
            log.error("❌ [PLC] 连接失败 {}:{} rack={} slot={} : {}",
                    plcHost, plcPort, plcRack, plcSlot, e.getMessage());
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
        lastWrittenValue.put(address, value);
        lastWrittenMs.put(address, System.currentTimeMillis());
    }

    // ── 周期写入去重 ──────────────────────────────────────────────────────
    // PLC 走的是 PAD 同一个 WiFi 网关(2026-09-17 现场确认)。自动导航 20ms 一拍把速度、角度各写一遍，
    // 即使数值没变也写 → 每秒 100 次 S7 往返全在无线上跑，WiFi 一忙控制和急停都跟着排队。
    // 周期性下发(ramp、起步打舵、机动续速)改走 writeInt16Periodic：值变了立刻写，没变就每 refresh-ms 保底刷新一次。
    // 急停/停车/模式切换这类一次性写入仍直接调 writeInt16，并且会刷新这里的记录，保证下一拍比较的是真实值。
    private final Map<String, Integer> lastWrittenValue = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long>    lastWrittenMs    = new java.util.concurrent.ConcurrentHashMap<>();

    /** 数值没变时的保底刷新间隔(ms)。0 = 不去重，每拍都写(回退用) */
    @Value("${plc.write.refresh-ms:200}")
    private volatile long writeRefreshMs;

    private synchronized void writeInt16Periodic(String address, int value) throws Exception {
        Integer last = lastWrittenValue.get(address);
        Long lastMs  = lastWrittenMs.get(address);
        if (writeRefreshMs > 0 && last != null && last == value && lastMs != null
                && System.currentTimeMillis() - lastMs < writeRefreshMs) {
            return;
        }
        writeInt16(address, value);
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

    // ── 反馈块解码 ────────────────────────────────────────────────────────
    // 下标一律从点位表里的地址串现算，不写死魔数：点位表改了这里自动跟着变，
    // 不存在"常量改了、偏移量忘了改"这种错位。S7 是大端。

    /** 地址串(如 "V1201.5" / "V1204")在反馈块里的字节下标 */
    private static int blkOffset(String address) {
        int dot = address.indexOf('.');
        String bytePart = dot < 0 ? address.substring(1) : address.substring(1, dot);
        return Integer.parseInt(bytePart) - FB_BLOCK_BASE;
    }

    private static boolean blkBit(byte[] fb, String address) {
        int bit = Integer.parseInt(address.substring(address.indexOf('.') + 1));
        return ((fb[blkOffset(address)] & 0xFF) & (1 << bit)) != 0;
    }

    private static int blkWord(byte[] fb, String address) {
        int o = blkOffset(address);
        return (short) (((fb[o] & 0xFF) << 8) | (fb[o + 1] & 0xFF));
    }

    private static long blkDword(byte[] fb, String address) {
        int o = blkOffset(address);
        return ((fb[o] & 0xFF) << 24) | ((fb[o + 1] & 0xFF) << 16)
                | ((fb[o + 2] & 0xFF) << 8) | (fb[o + 3] & 0xFF);
    }
}
