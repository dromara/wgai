package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.ros2.entity.TabRosPython;
import org.jeecg.modules.demo.ros2.service.ITabRosPythonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGV 运行参数热加载。
 *
 * 现场调 plc.* 参数以前只能改 application.yml 再重启 Java，一次重启就是几分钟，
 * 而这批系数(车速标定、避障距离、扫转半径)恰恰是要一边看车一边试出来的。
 *
 * 这里把参数存进已有的 tab_ros_python 表的 agv_param 列(一列 JSON)，认准 ros_name =
 * PARAM_RECORD_NAME 的**那一条固定记录**，启动时读出来覆盖各 Service 上 @Value 注入的字段，
 * 页面保存后就地重新下发，不用重启。
 *
 * 几个刻意的设计：
 *   · 这条记录不存在时，启动会拿 yml 的现值自动播种出来。所以流程是
 *     「改 yml → 重启一次 → 以后全在页面上调」，现场不用再碰配置文件。
 *   · 只认一条记录，不做多条合并 —— 合并规则在现场没人说得清到底哪条在起作用。
 *   · yml 仍是默认值兜底 —— JSON 里没写的 key、或起来时连不上库，全部维持 yml 的值，
 *     绝不会因为数据库挂了就开不了机。
 *   · 参数的名称/分组/单位/取值范围/说明统一在下面 PARAMS 里定义，前端从接口拿着渲染，
 *     加参数只改这一处，不用 ALTER 表、不用改实体、不用改页面。
 *   · 写入一律按 min/max 钳位。现场手滑把停车距离填成 100 不该让车直接躺平。
 *   · 只收纯热改参数。plc.enabled / host / port / rack / slot 这类改了要重连 PLC 的
 *     故意不放进来，写进 JSON 也不会生效。
 */
@Slf4j
@Service
public class AgvParamService {

    /** 参数定义。targets 是 "beanKey#字段名"，一个参数可以同时喂给多个 Service */
    public static class ParamDef {
        public final String key, name, group, unit, type, remark;
        public final double min, max;
        public final String[] targets;
        /** yml 里的原始值，启动时在覆盖之前抓一份，页面上的「恢复默认」用它 */
        public String ymlValue;

        ParamDef(String key, String name, String group, String unit, String type,
                 double min, double max, String remark, String... targets) {
            this.key = key; this.name = name; this.group = group; this.unit = unit;
            this.type = type; this.min = min; this.max = max;
            this.remark = remark; this.targets = targets;
        }
    }

    private static final String HW = "hw", OBS = "obstacle", ROT = "rotate", MAN = "maneuver";

    private static final List<ParamDef> PARAMS = new ArrayList<>();

    static {
        // ══ 行驶控制 (drive) ═══════════════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.auto-nav.max-rpm", "自动导航限速上限", "drive", "r/min", "int", 1, 3000,
                "写入 VW1004 的上限，只钳制 Nav2 自动导航来源的指令，手动遥控和点位测试不受影响。" +
                "Nav2 要求更低速度(接近目标点减速/避障)时仍可以更慢，这里只是封顶。",
                HW + "#autoNavMaxRpm"));

        PARAMS.add(new ParamDef("plc.wheel.rpm-scale", "车速标定系数", "drive", "", "double", 0.1, 100,
                "VW1004 吃的不是\"轮子 r/min\"这个刻度。纯几何换算 |v|×60/(π×轮径) 现场实测慢一个数量级" +
                "(linear=0.3m/s 算出 12，车几乎不走，×10 才对)。调车速改这个数，不要去改换算公式，" +
                "也不要用减速比 45(会算出 570，对不上)。",
                HW + "#rpmScale"));

        PARAMS.add(new ParamDef("plc.steer.mode", "自动导航舵角换算", "drive", "", "int", 0, 1,
                "1 = 运动学(默认)：舵角 = atan(等效轴距 × ω / v)，车实际转弯半径和 Nav2 规划的一致。" +
                "0 = 旧比例系数：舵角 = -ω × 角速度转舵角系数，与车速无关，0.3m/s 下弯道严重外甩，仅作现场回退。" +
                "只影响自动导航和点位测试，手动遥控始终用比例系数。",
                HW + "#steerMode"));

        PARAMS.add(new ParamDef("plc.steer.ready-tol-deg", "起步打舵到位容差", "drive", "°", "double", 0.2, 10,
                "起步/前进后退换向时先打舵、速度保持 0，VW1208 转角反馈与目标差进入 ±此值才给方向位起步。" +
                "舵机停稳后有余差、老是等到超时才走，就调大；起步第一段还跑偏就调小。",
                HW + "#steerReadyTolDeg"));

        PARAMS.add(new ParamDef("plc.steer.ready-timeout-ms", "起步打舵超时", "drive", "ms", "long", 500, 20000,
                "超过这么久转角还没到位就报警，**继续不起步**(fail-safe)。一般是转向电机故障/未找零/容差太严。",
                HW + "#steerReadyTimeoutMs"));

        PARAMS.add(new ParamDef("plc.steer.slow-error-deg", "行驶中舵角偏差限速阈值", "drive", "°", "double", 1, 45,
                "行驶中转角反馈与目标差超过此角度，速度压到「舵角没跟上时限速」，等舵轮追上再恢复。",
                HW + "#steerSlowErrorDeg"));

        PARAMS.add(new ParamDef("plc.steer.slow-rpm", "舵角没跟上时限速", "drive", "r/min", "int", 0, 3000,
                "行驶中舵角偏差超过阈值时 VW1004 的上限。0 = 直接停下等舵。", HW + "#steerSlowRpm"));

        PARAMS.add(new ParamDef("plc.steer.feedback-interval-ms", "转角反馈读取间隔", "drive", "ms", "long", 20, 2000,
                "自动导航下发时读 VW1208 的间隔。⚠ 调太小会让急停的写入排队变慢(S7 一次一条)，别低于 50。",
                HW + "#steerFeedbackIntervalMs"));

        PARAMS.add(new ParamDef("plc.angular.scale", "角速度转舵角系数", "drive", "", "double", 1, 200,
                "ROS angular.z(rad/s) → PLC 转弯角度 VW1006 的缩放系数。结果会被限幅到 ±45°。" +
                "车打舵太猛调小，转不过弯调大。⚠ 自动导航只在「自动导航舵角换算」=0 时用它。",
                HW + "#angularToDegreesScale"));

        PARAMS.add(new ParamDef("plc.watchdog.ms", "指令看门狗超时", "drive", "ms", "long", 100, 5000,
                "超过该时间没收到新的 /cmd_vel 就自动停车。调大了 Nav2 一卡顿车会继续往前冲，" +
                "调太小正常的指令间隔都会被误判成掉线。",
                HW + "#watchdogMs"));

        PARAMS.add(new ParamDef("plc.control.log.interval.ms", "控制日志输出间隔", "drive", "ms", "long", 100, 10000,
                "[PLC控制] / [PLC控制·寄存器] 两行日志的节流间隔。动作或目标值有明显变化时不受此间隔限制，一定会打。",
                HW + "#controlLogIntervalMs"));

        // ══ 前向避障 (obstacle) ════════════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.obstacle.stop.distance", "障碍停车距离", "obstacle", "m", "double", 0, 10,
                "车头前方净空小于该值就拦停自动导航。量的是**还能再往前开多少米**(打舵时是弧长)，" +
                "不是离雷达的直线距离。0 = 关闭该保护。0.35/0.5 现场都不够(车还没刹住)，实测 1.0 才留得出余量。" +
                "⚠ 只拦自动导航，手动遥控和点位测试不受它管。",
                HW + "#obstacleStopDistance"));

        PARAMS.add(new ParamDef("plc.obstacle.corridor-range-m", "走廊前视距离", "obstacle", "m", "double", 1, 30,
                "走廊往前看多远。再远的东西交给 Nav2 自己绕，不参与急停判定。调太大会把远处正常的墙也算进来。",
                OBS + "#corridorRangeM"));

        PARAMS.add(new ParamDef("plc.obstacle.corridor-margin-m", "走廊左右余量", "obstacle", "m", "double", 0, 2,
                "走廊在车宽(雷达左 1.2m + 右 0.9m)基础上左右各再放宽多少米，覆盖定位误差和车体摆动。",
                OBS + "#corridorMarginM"));

        PARAMS.add(new ParamDef("plc.obstacle.bumper-guard-m", "保险杠兜底距离", "obstacle", "m", "double", 0, 10,
                "打舵时车头正前方这一段仍然按**直线矩形**判，和圆弧扫掠结果取最小值。圆弧几何依赖轴距/后轴位置" +
                "这两个估算值，估偏了走廊会弯过头、把正前方的东西漏掉 —— 这一段是不管舵角多大都不会漏的底线。" +
                "0 = 关闭(不建议)。",
                OBS + "#bumperGuardM"));

        PARAMS.add(new ParamDef("plc.obstacle.straight-angle-deg", "直行判定舵角阈值", "obstacle", "°", "double", 0, 10,
                "舵角小于该值按直行处理(矩形走廊)，否则走圆弧扫掠判定。太小的舵角转弯半径大到没意义，还会放大数值误差。",
                OBS + "#straightAngleDeg"));

        PARAMS.add(new ParamDef("plc.obstacle.min-points", "成障最少点数", "obstacle", "个", "int", 1, 50,
                "同一距离档位里至少要有几个点才算真障碍。1~2 个飞点/雨雾回波就能把净空拉到 0.2m，" +
                "不设门限自动导航会被一路刹停。",
                OBS + "#minPoints"));

        PARAMS.add(new ParamDef("plc.obstacle.bucket-m", "距离分档粒度", "obstacle", "m", "double", 0.01, 1,
                "按这个粒度把走廊内的点分档，min-points 是在同一档内计数的。",
                OBS + "#bucketM"));

        PARAMS.add(new ParamDef("plc.obstacle.stale-ms", "避障数据有效期", "obstacle", "ms", "long", 200, 20000,
                "判定结果超过这么久没刷新就视为失效并拦停自动导航。fail-safe：点云链路断了 = 看不见前面 ≠ 前面没东西。" +
                "只在\"曾经出过结果\"之后才生效，免得没接雷达的环境被永久锁死。",
                HW + "#obstacleStaleMs"));

        // ══ 原地旋转安全判定 (rotate) ═══════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.rotate.center-offset-m", "旋转中心距雷达", "rotate", "m", "double", 0, 6,
                "旋转中心(底盘 2.1×2.1 的中心)在雷达后方多少米。扫转半径 R = max(√((0.5+d)²+1.2²), √((5.0-d)²+1.2²))：" +
                "d=1.75 → R=3.46m(底盘在车身正中，理论最小值)；d=0 → R=5.14m(按雷达原点算，最保守)。" +
                "⚠ 现场实测确认，改错会导致净空判定偏乐观 → 直接撞车。",
                ROT + "#centerOffsetM"));

        PARAMS.add(new ParamDef("plc.rotate.safety-margin-m", "扫转圆安全余量", "rotate", "m", "double", 0, 3,
                "扫转圆外再留多少米才允许转。",
                ROT + "#safetyMarginM"));

        PARAMS.add(new ParamDef("plc.rotate.lidar-height-m", "雷达安装高度", "rotate", "m", "double", 0.1, 5,
                "雷达离地高度，点云 z 靠它换算成离地高度。旋转判定和前向避障共用这一个值。" +
                "⚠ 曾误配 2.0，会把离地 1~2m 的障碍物(人/料堆)算成负高度、被 ground-clearance 当地面滤掉 → 漏检。" +
                "改前必须现场实测。",
                ROT + "#lidarHeightM", OBS + "#lidarHeightM"));

        PARAMS.add(new ParamDef("plc.rotate.vehicle-height-m", "车体总高", "rotate", "m", "double", 0.5, 6,
                "高于此离地高度的点撞不到车，忽略。",
                ROT + "#vehicleHeightM", OBS + "#vehicleHeightM"));

        PARAMS.add(new ParamDef("plc.rotate.ground-clearance-m", "地面滤除高度", "rotate", "m", "double", 0, 1,
                "低于此离地高度的点视为地面反射并丢弃。调大了会连矮障碍一起滤掉。",
                ROT + "#groundClearanceM", OBS + "#groundClearanceM"));

        PARAMS.add(new ParamDef("plc.rotate.cloud-window-ms", "扇区滚动窗口", "rotate", "ms", "long", 100, 10000,
                "mid360 是非重复扫描，单帧 360° 覆盖不全，需要累积多帧。这是累积数据的有效期。" +
                "⚠ 只作用于旋转判定；前向避障是逐帧算的，不走这个窗口。",
                ROT + "#cloudWindowMs"));

        PARAMS.add(new ParamDef("plc.rotate.min-coverage", "最低方位角覆盖率", "rotate", "", "double", 0, 1,
                "累积到的方位角覆盖率低于此值就判为\"数据不足\"并禁止旋转 —— 无数据 ≠ 安全。",
                ROT + "#minCoverage"));

        PARAMS.add(new ParamDef("plc.rotate.stationary-tolerance-m", "静止判定位移阈值", "rotate", "m", "double", 0.01, 2,
                "位移超过该值就清空已累积的扇区数据(车一动，车体系下的累积数据就失效了)。",
                ROT + "#stationaryToleranceM"));

        PARAMS.add(new ParamDef("plc.rotate.stationary-tolerance-rad", "静止判定转角阈值", "rotate", "rad", "double", 0.01, 1,
                "朝向变化超过该值就清空已累积的扇区数据。",
                ROT + "#stationaryToleranceRad"));

        // ══ 车体标定 (chassis) ═════════════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.wheel.diameter-m", "轮径", "chassis", "m", "double", 0.1, 2,
                "现场确认 0.452m。用于 VW1004 车速换算和 VD1212 里程计换算。",
                HW + "#wheelDiameterM"));

        PARAMS.add(new ParamDef("plc.wheel.gear-ratio", "减速比", "chassis", "", "double", 1, 200,
                "电机转 45 圈轮子实际转 1 圈。⚠ **只**用于 VD1212 里程计换算(需要除)；" +
                "VW1004 车速走 plc.wheel.rpm-scale，不乘这个系数。",
                HW + "#gearRatio"));

        PARAMS.add(new ParamDef("plc.encoder.counts-per-rev", "编码器分辨率", "chassis", "脉冲/转", "double", 1, 1000000,
                "VD1212 每转脉冲数，位置反馈换算成米时用。现场未确认前仅供参考。",
                HW + "#encoderCountsPerRev"));

        PARAMS.add(new ParamDef("plc.wheel.wheelbase-m", "等效轴距", "chassis", "m", "double", 0.5, 10,
                "转弯半径 R = 等效轴距/tan(前轮舵角)。车是前轮转向+后轮辅助转向，这里不是机械轴距而是 轴距/(1+后轮反打比例)。" +
                "标定：舵打满 45° 慢速走一圈，量转弯圆心到车身中线的垂直距离，就是这个数。" +
                "同时用于：自动导航舵角换算、打舵避障圆弧走廊、Smac 最小转弯半径。" +
                "⚠ 前两者立即生效；Smac 那份要「重启 Java → 前端切一次地图 → 重启 ROS 栈」才生效。",
                OBS + "#wheelbaseM", HW + "#wheelbaseM"));

        PARAMS.add(new ParamDef("plc.wheel.rear-axle-x-m", "转向中心线X坐标", "chassis", "m", "double", -10, 0,
                "转弯圆心所在横线在车体系的 x(负 = 雷达后方)。只有前轮转向时是后轴(-2.80)，后轮辅助转向会让它往底盘中心(-1.75)挪。" +
                "标定：舵打满走圈时，从转弯圆心向车身中线作垂线，垂足到雷达的纵向距离取负。" +
                "同时是 Nav2 base_link 原点(导航目标点也按它换算)。" +
                "⚠ 避障立即生效；Nav2 那份要「重启 Java → 前端切一次地图 → 重启 ROS 栈」才生效，两边不一致期间目标点会偏。",
                OBS + "#rearAxleXM"));

        // ══ 机动 / 倒车 (maneuver) ═════════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.reverse.max-rpm", "自动倒车限速", "maneuver", "r/min", "int", 1, 3000,
                "自动导航倒车时 VW1004 上限，和自动导航限速取小。雷达在车头，车尾后面基本是盲区，倒车只能靠慢。",
                HW + "#reverseMaxRpm"));
        PARAMS.add(new ParamDef("plc.reverse.max-distance-m", "单段倒车最长距离", "maneuver", "m", "double", 0, 20,
                "按 /Odometry 实测的连续倒车距离，超过就不再执行倒车指令(Nav2 会报走不动)。出现前进指令或发新目标后解除。0 = 禁止自动倒车。",
                HW + "#reverseMaxDistanceM"));
        PARAMS.add(new ParamDef("plc.maneuver.mode-switch-timeout-ms", "切模式等待反馈", "maneuver", "ms", "long", 500, 30000,
                "写模式字后等 V1200.2(旋转)/V1200.3(平移)/V1200.1(直行) 点亮的最长时间，舵轮转到位要时间。超时即中止机动。",
                HW + "#modeSwitchTimeoutMs"));
        PARAMS.add(new ParamDef("plc.maneuver.pre-rotate-min-deg", "起点预旋转角度门限", "maneuver", "°", "double", 30, 180,
                "目标方位与车头相差超过它(目标在身后)才原地转向目标再出发，否则交给 Nav2 走弧线/倒车。180 = 永不预旋转。",
                MAN + "#preRotateMinDeg"));
        PARAMS.add(new ParamDef("plc.maneuver.rotate-rpm", "原地旋转速度", "maneuver", "r/min", "int", 1, 3000,
                "旋转模式 VW1004。⚠ 旋转模式下这个数对应多快未标定，先低速试。",
                MAN + "#rotateRpm"));
        PARAMS.add(new ParamDef("plc.maneuver.rotate-slow-rpm", "原地旋转慢速", "maneuver", "r/min", "int", 1, 3000,
                "离目标角度小于「慢速区」时用这个速度，防止过冲。", MAN + "#rotateSlowRpm"));
        PARAMS.add(new ParamDef("plc.maneuver.yaw-tol-deg", "朝向对位容差", "maneuver", "°", "double", 0.5, 20,
                "原地旋转停止的角度容差。", MAN + "#yawTolDeg"));
        PARAMS.add(new ParamDef("plc.maneuver.lateral-rpm", "平移速度", "maneuver", "r/min", "int", 1, 3000,
                "平移模式 VW1004。⚠ 未标定，先低速试。", MAN + "#lateralRpm"));
        PARAMS.add(new ParamDef("plc.maneuver.lateral-slow-rpm", "平移慢速", "maneuver", "r/min", "int", 1, 3000,
                "离目标横向距离小于 0.2m 时用这个速度。", MAN + "#lateralSlowRpm"));
        PARAMS.add(new ParamDef("plc.maneuver.lateral-tol-m", "横向对位容差", "maneuver", "m", "double", 0.01, 0.5,
                "横向偏差小于它就不平移。", MAN + "#lateralTolM"));
        PARAMS.add(new ParamDef("plc.maneuver.lateral-max-m", "单次平移上限", "maneuver", "m", "double", 0.1, 3,
                "侧向对位/窄通道侧移单次最多横移多少。车身后段两侧雷达被车体挡住看不全，不要调大。",
                MAN + "#lateralMaxM"));
        PARAMS.add(new ParamDef("plc.maneuver.lateral-margin-m", "平移侧边余量", "maneuver", "m", "double", 0.05, 2,
                "平移后车身侧边离障碍至少留这么多，平移过程中实时净空小于它也会立即停车。贴料堆要更近就调小，但不建议低于 0.15。",
                MAN + "#lateralMarginM"));
        PARAMS.add(new ParamDef("plc.maneuver.corridor-tight-m", "窄通道判定净空", "maneuver", "m", "double", 0.05, 3,
                "Nav2 失败后，较近一侧净空小于它才认为是侧向太挤、尝试平移摆中后重发一次目标。",
                MAN + "#corridorTightM"));
        PARAMS.add(new ParamDef("plc.maneuver.corridor-shift-max-m", "窄通道侧移上限", "maneuver", "m", "double", 0.05, 2,
                "窄通道侧移单次最多移多少(同时受单次平移上限约束)。", MAN + "#corridorShiftMaxM"));

        // ══ 点位测试 (test) ════════════════════════════════════════════════════
        PARAMS.add(new ParamDef("plc.test.max-rpm", "测试转速上限", "test", "r/min", "int", 1, 3000,
                "点位测试窗的转速钳位。测试窗不该让车窜出去。",
                HW + "#testMaxRpm"));

        PARAMS.add(new ParamDef("plc.test.max-hold-ms", "测试单次最长保持", "test", "ms", "long", 200, 30000,
                "单次点动最长保持时间，到点自动 forceStop 并把模式字复位成直行。不存在\"忘了点停车\"这种情况。",
                HW + "#testMaxHoldMs"));
    }

    // ⚠ 这三个不能用 @Lazy 注入：@Lazy 会包一层代理，反射 set 到的是代理对象上那份没人读的
    //   字段副本，参数看着"改成功了"但底盘行为纹丝不动。必须拿到真身。
    @Autowired private RobotHardwareService hardwareService;
    @Autowired private ObstacleGuardService obstacleGuardService;
    @Autowired private RotationSafetyService rotationSafetyService;
    @Autowired private ManeuverService maneuverService;
    @Autowired private ITabRosPythonService tabRosPythonService;

    private final Map<String, ParamDef> defByKey = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        for (ParamDef d : PARAMS) {
            defByKey.put(d.key, d);
        }
        // 先把 yml 注入进来的原值抓一份当默认值，再拿库里的覆盖。顺序反了默认值就被污染了。
        for (ParamDef d : PARAMS) {
            d.ymlValue = readCurrent(d);
        }
        try {
            int n = applyFromDb();
            log.info("[AGV参数] 启动加载完成, 定义 {} 项, 库里覆盖 {} 项", PARAMS.size(), n);
        } catch (Exception e) {
            // 连不上库不能让服务起不来，全部退回 yml 默认值即可
            log.warn("[AGV参数] 启动加载失败, 全部使用 application.yml 默认值: {}", e.getMessage());
        }
    }

    /** 参数定义 + 当前生效值 + yml 默认值，前端参数面板直接渲染这一份 */
    public List<Map<String, Object>> listParams() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ParamDef d : PARAMS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key);
            m.put("name", d.name);
            m.put("group", d.group);
            m.put("unit", d.unit);
            m.put("type", d.type);
            m.put("min", d.min);
            m.put("max", d.max);
            m.put("remark", d.remark);
            m.put("value", readCurrent(d));
            m.put("ymlValue", d.ymlValue);
            out.add(m);
        }
        return out;
    }

    /**
     * 参数只认 ros_name = 这个值的那一条记录，别的脚本记录上的 agv_param 一概不看。
     * 早先"所有记录合并"的规则在现场没法用：谁都说不清到底哪条在起作用。
     */
    public static final String PARAM_RECORD_NAME = "AGV运行参数";

    /** 取参数载体记录，没有就按 yml 现值建一条。库不可用时直接抛，由调用方决定是兜底还是报错 */
    private TabRosPython paramRecord() {
        List<TabRosPython> hit = tabRosPythonService.list(
                new LambdaQueryWrapper<TabRosPython>().eq(TabRosPython::getRosName, PARAM_RECORD_NAME));
        if (!hit.isEmpty()) {
            if (hit.size() > 1) {
                log.warn("[AGV参数] 叫\"{}\"的记录有 {} 条, 只用第一条, 建议删掉多余的",
                        PARAM_RECORD_NAME, hit.size());
            }
            return hit.get(0);
        }
        // 头一回启动：拿 yml 当前值播种出一条，之后现场就只在页面上调，不用再碰配置文件
        TabRosPython seed = new TabRosPython();
        seed.setRosName(PARAM_RECORD_NAME);
        seed.setRemake("AGV运行参数载体，不是脚本，请勿删除，也不要点单步运行");
        seed.setSort("9999");
        JSONObject json = new JSONObject(true);
        for (ParamDef d : PARAMS) {
            json.put(d.key, d.ymlValue);
        }
        seed.setAgvParam(json.toJSONString());
        tabRosPythonService.save(seed);
        log.info("[AGV参数] 未找到\"{}\"记录, 已按 application.yml 现值自动建了一条, 共 {} 项",
                PARAM_RECORD_NAME, PARAMS.size());
        return seed;
    }

    /** 参数载体记录，前端要拿它的 id 和当前已存的值 */
    public TabRosPython getParamRecord() {
        return paramRecord();
    }

    /**
     * 保存参数并立即生效。
     * 只接受 PARAMS 里定义过的 key，其余静默忽略 —— 免得页面上残留的旧 key 越攒越多。
     */
    public synchronized int saveAndApply(Map<String, String> values) {
        TabRosPython record = paramRecord();
        JSONObject json = new JSONObject(true);
        for (ParamDef d : PARAMS) {
            String v = values.get(d.key);
            if (v != null && !v.trim().isEmpty()) {
                json.put(d.key, format(d, clamp(d, v.trim())));
            }
        }
        record.setAgvParam(json.isEmpty() ? null : json.toJSONString());
        tabRosPythonService.updateById(record);
        return applyFromDb();
    }

    /**
     * 从库里重新读一遍并下发。
     * 只读 ros_name = PARAM_RECORD_NAME 那一条；JSON 里没有的 key 复位成 yml 默认值 ——
     * 否则页面上删掉一个 key，内存里那份改过的值会一直赖着不走。
     */
    public synchronized int applyFromDb() {
        Map<String, String> merged = new LinkedHashMap<>();
        String raw = paramRecord().getAgvParam();
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject o = JSON.parseObject(raw);
                for (String k : o.keySet()) {
                    merged.put(k, o.getString(k));
                }
            } catch (Exception e) {
                log.warn("[AGV参数] \"{}\" 的 agv_param 不是合法 JSON, 全部退回 yml 默认值: {}",
                        PARAM_RECORD_NAME, e.getMessage());
            }
        }

        int applied = 0;
        for (ParamDef d : PARAMS) {
            String v = merged.get(d.key);
            boolean fromDb = v != null && !v.trim().isEmpty();
            String target = fromDb ? format(d, clamp(d, v.trim())) : d.ymlValue;
            String before = readCurrent(d);
            if (!writeField(d, target)) continue;
            if (fromDb) applied++;
            if (!target.equals(before)) {
                log.info("[AGV参数] {} ({}) {} → {}{}", d.key, d.name, before, target,
                        fromDb ? "" : " (复位为yml默认值)");
            }
        }
        for (String k : merged.keySet()) {
            if (!defByKey.containsKey(k)) {
                log.warn("[AGV参数] 未知参数 {} 已忽略(不在定义表里，写了也不会生效)", k);
            }
        }
        return applied;
    }

    // ======================== 反射读写 ========================

    private Object beanOf(String target) {
        String beanKey = target.substring(0, target.indexOf('#'));
        switch (beanKey) {
            case HW:  return hardwareService;
            case OBS: return obstacleGuardService;
            case ROT: return rotationSafetyService;
            case MAN: return maneuverService;
            default:  throw new IllegalStateException("未知的参数宿主: " + beanKey);
        }
    }

    private Field fieldOf(Object bean, String target) throws NoSuchFieldException {
        Field f = bean.getClass().getDeclaredField(target.substring(target.indexOf('#') + 1));
        f.setAccessible(true);
        return f;
    }

    private String readCurrent(ParamDef d) {
        try {
            Object bean = beanOf(d.targets[0]);
            return String.valueOf(fieldOf(bean, d.targets[0]).get(bean));
        } catch (Exception e) {
            log.warn("[AGV参数] 读取 {} 失败: {}", d.key, e.getMessage());
            return "";
        }
    }

    private boolean writeField(ParamDef d, String value) {
        boolean ok = true;
        for (String t : d.targets) {
            try {
                Object bean = beanOf(t);
                Field f = fieldOf(bean, t);
                f.set(bean, convert(f.getType(), value));
            } catch (Exception e) {
                log.error("[AGV参数] 下发 {} → {} 失败: {}", d.key, t, e.getMessage());
                ok = false;
            }
        }
        return ok;
    }

    private Object convert(Class<?> type, String value) {
        double d = Double.parseDouble(value);
        if (type == int.class    || type == Integer.class) return (int) Math.round(d);
        if (type == long.class   || type == Long.class)    return Math.round(d);
        if (type == float.class  || type == Float.class)   return (float) d;
        return d;
    }

    /** 按类型格式化，免得整数参数被存成 "500.0" 在页面上晃眼、还让变更比对每次都不相等 */
    private String format(ParamDef d, double v) {
        if ("int".equals(d.type) || "long".equals(d.type)) {
            return String.valueOf(Math.round(v));
        }
        return String.valueOf(v);
    }

    /** 一律钳位。现场手滑把停车距离填成 100，不该让车直接躺平 */
    private double clamp(ParamDef d, String raw) {
        double v;
        try {
            v = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(d.name + "(" + d.key + ") 不是数字: " + raw);
        }
        double c = Math.max(d.min, Math.min(d.max, v));
        if (c != v) {
            log.warn("[AGV参数] {} 填的是 {}, 超出 [{}, {}], 已钳位到 {}", d.key, v, d.min, d.max, c);
        }
        return c;
    }
}
