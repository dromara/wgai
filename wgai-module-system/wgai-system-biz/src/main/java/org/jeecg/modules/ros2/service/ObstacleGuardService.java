package org.jeecg.modules.ros2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前向走廊避障判定 —— 自动导航时「前面多少米有东西就停」的数据来源。
 *
 * ─── 为什么单独写一个，不复用 RotationSafetyService ─────────────────────────
 * 旋转判定那套是**按方位角分扇区的滚动窗口**，而且「车一动就 clearBins()」——
 * 因为累积出来的扇区数据在车体系下会随车移动而失效。可是自动导航时车一直在动，
 * 那套窗口在行进途中基本永远是空的，拿它做行进避障等于没有。
 *
 * 这里反过来：**逐帧算、不累积**。mid360 单帧在正前方的点是足够密的(前向不是盲区，
 * 稀疏的是侧后方)，一帧就能判出"前面这条走廊里最近的东西有多远"。
 *
 * ─── 判定几何 ─────────────────────────────────────────────────────────────
 * 点云是 camera_init 世界系，先用 /Odometry 位姿反变换回车体系(body 原点=雷达安装点)。
 * 然后分两种情况：
 *
 * ① 直行(舵角≈0)：矩形走廊
 *   纵向  bx ∈ [车头 0.5m, 车头 + range]
 *   横向  by ∈ [-(右 0.9 + 余量), +(左 1.2 + 余量)]   ← 雷达不在车身中线上，左右不对称
 *
 * ② 打舵：沿转弯圆弧的**扫掠区**
 *   ⚠ 2026-09 现场事故：右打舵时右侧蹭上才停。原因就是走廊永远是笔直朝前的矩形，
 *     右前方的障碍物落在矩形外不触发，等它转到正前方时车侧面已经贴上了。
 *   转弯圆心 C 在后轴延长线上、距车体中线 R = 轴距/tan(|舵角|) 处(左舵在左、右舵在右)。
 *   车体绕 C 转，车身扫出的是一个**圆环带**：点到 C 的距离落在 [r_in, r_out] 之间就会被扫到。
 *   再按"车还要往前开多少弧长才碰到它"算净空 —— 即该点绕 C 的方位角与
 *   车体前缘方位角之差 × R。
 *
 * 两种情况返回的都是**离车头的可行驶距离**(直行=直线距离，打舵=弧长)，不是离雷达的距离，
 * 这样阈值填 1.0m 就真的是"还能再往前开 1.0m"，现场好理解。
 *
 * 高度带两种情况通用：离地 [groundClearance, vehicleHeight]  ← z 离地高度 = z + 雷达安装高度
 *
 * ─── 为什么要 minPoints ───────────────────────────────────────────────────
 * 单个飞点/雨雾回波就能把最近距离拉到 0.2m，自动导航会被一路刹停。
 * 要求走廊内落在"最近那一档"的点数达到 minPoints 才算数，单点噪声直接忽略。
 *
 * ⚠ 只判定、只上报，不下发任何 PLC 指令。真正的停车在 RobotHardwareService 里做。
 */
@Slf4j
@Service
public class ObstacleGuardService {

    /** 车体前端距雷达(body 原点)，与 MapController.FP_* / RotationSafetyService 一致 */
    static final double FOOTPRINT_FRONT_M = 0.5;
    /** 车体左侧距雷达 */
    static final double FOOTPRINT_LEFT_M  = 1.2;
    /** 车体右侧距雷达 */
    static final double FOOTPRINT_RIGHT_M = 0.9;
    /** 车体后端距雷达(负值)，打舵扫掠判定要算整车，不能只算车头 */
    static final double FOOTPRINT_BACK_M  = -5.0;

    // 下面这批 @Value 字段全部 volatile：AgvParamService 会在运行时(HTTP 线程)按
    // tab_ros_python.agv_param 的内容反射改写它们，而读取发生在点云回调线程。不加 volatile
    // 就可能出现"页面显示已生效、判定用的还是旧值"。加参数时记得一并加。

    /**
     * 等效轴距(m)。转弯半径 R = 等效轴距 / tan(前轮舵角)。
     * 车是**前轮转向 + 后轮辅助转向**：后轮反向打 k 倍前轮角时 R = 轴距/((1+k)·tanδ)，
     * 所以这里填的不是机械轴距，而是 轴距/(1+k)。现场标定：舵打满 45° 慢速走一圈，
     * 转弯圆心到车体中线的垂直距离就是它(tan45°=1)。
     * 同一个数还喂给 RobotHardwareService 的舵角换算、MapController 写进 Smac 的最小转弯半径。
     */
    @Value("${plc.wheel.wheelbase-m:2.1}")
    private volatile double wheelbaseM;

    /**
     * 转向中心线在车体系的 x 坐标(负值=在雷达后方)，转弯圆心就落在这条横线上。
     * 只有前轮转向时就是后轴；后轮辅助转向会让它往底盘中心(-1.75)挪。
     * 这个点同时是 Nav2 的 base_link 原点(MapController 写进 yaml，launch 发静态 TF)。
     */
    @Value("${plc.wheel.rear-axle-x-m:-2.80}")
    private volatile double rearAxleXM;

    public double getWheelbaseM() { return wheelbaseM; }

    /** 转向中心(= Nav2 base_link 原点)在 body 系的 x */
    public double getSteerCenterX() { return rearAxleXM; }

    /** 转向中心在 body 系的 y：车身横向中线。雷达偏右装，中线在雷达左侧 (1.2-0.9)/2 = 0.15m */
    public double getSteerCenterY() { return (FOOTPRINT_LEFT_M - FOOTPRINT_RIGHT_M) / 2.0; }

    /** 舵角小于该值按直行处理(矩形走廊)。太小的舵角转弯半径大到没意义，还会放大数值误差 */
    @Value("${plc.obstacle.straight-angle-deg:1.0}")
    private volatile double straightAngleDeg;

    /**
     * 保险杠兜底距离(m)。打舵时车头正前方这一段仍然按**直线矩形**判，和圆弧扫掠取最小值。
     * 圆弧几何依赖轴距/后轴位置这两个估算值，估偏了走廊会弯过头把正前方的东西漏掉 —— 这一段是
     * 不管舵角多大都不会漏的底线。设 0 关闭(不建议)。
     */
    @Value("${plc.obstacle.bumper-guard-m:2.0}")
    private volatile double bumperGuardM;

    /** 走廊左右各再放宽多少米，覆盖定位误差和车体摆动 */
    @Value("${plc.obstacle.corridor-margin-m:0.3}")
    private volatile double corridorMarginM;

    /** 走廊往前看多远。超过这个距离的东西不参与判定(也不必要，Nav2 自己会绕) */
    @Value("${plc.obstacle.corridor-range-m:8.0}")
    private volatile double corridorRangeM;

    /** 要有多少个点落在同一距离档位才算真障碍，用来挡掉单点飞点 */
    @Value("${plc.obstacle.min-points:3}")
    private volatile int minPoints;

    /** 距离分档粒度(米)，minPoints 是在同一档里计数的 */
    @Value("${plc.obstacle.bucket-m:0.10}")
    private volatile double bucketM;

    /** 雷达安装高度(离地)，把点云 z 换算成离地高度。和 plc.rotate.lidar-height-m 是同一个物理量 */
    @Value("${plc.rotate.lidar-height-m:1.0}")
    private volatile double lidarHeightM;

    /** 车体总高，高于此高度的点撞不到车 */
    @Value("${plc.rotate.vehicle-height-m:2.3}")
    private volatile double vehicleHeightM;

    /** 地面滤除高度，低于此离地高度视为地面反射 */
    @Value("${plc.rotate.ground-clearance-m:0.10}")
    private volatile double groundClearanceM;

    /** 侧向净空只看车身两侧这么远，平移/侧移单次也不会超过这个量级 */
    private static final double SIDE_RANGE_M = 3.0;
    private volatile double lastLeftClearM  = Double.MAX_VALUE;
    private volatile double lastRightClearM = Double.MAX_VALUE;

    /** 最近一次判定结果：车头前方净空(m)，Double.MAX_VALUE = 走廊内没东西 */
    private volatile double lastClearanceM = Double.MAX_VALUE;
    /** 最近一次判定时刻，用于上游判断数据是否已经过期 */
    private volatile long   lastUpdateMs   = 0L;
    /** 最近一次判定时走廊内命中的点数，排查"到底是没东西还是没数据"用 */
    private volatile int    lastHitPoints  = 0;
    /** 最近一次判定用的舵角，排查"为什么走廊是弯的/是直的" */
    private volatile double lastSteerAngleDeg = 0;
    /** 最近一次判定的转弯半径，Double.MAX_VALUE = 按直行算的 */
    private volatile double lastTurnRadiusM   = Double.MAX_VALUE;

    /**
     * 用一帧**全量**点云(抽稀前)刷新前向净空。
     *
     * @param raw         PointCloud2 原始字节
     * @param pointStep   每点字节数
     * @param totalPoints 点数
     * @param xOff/yOff/zOff 各字段字节偏移
     * @param poseX/poseY/poseTheta 车体在 camera_init 系的位姿
     * @param steerAngleDeg 当前舵角(VW1006 实际下发值，负=左 正=右)。0 按直行走矩形走廊，
     *                      否则按转弯圆弧扫掠判定
     * @return 车头前方可行驶距离(m)，打舵时是弧长；无障碍时返回 Double.MAX_VALUE
     */
    public double updateFromCloud(byte[] raw, int pointStep, int totalPoints,
                                  int xOff, int yOff, int zOff,
                                  double poseX, double poseY, double poseTheta,
                                  double steerAngleDeg) {
        // 点云 z 在 camera_init 系(原点=雷达开机位置)，离地高度 = z + 雷达安装高度
        double zMin = -lidarHeightM + groundClearanceM;
        double zMax = -lidarHeightM + vehicleHeightM;

        double cos = Math.cos(poseTheta);
        double sin = Math.sin(poseTheta);

        boolean arc = Math.abs(steerAngleDeg) >= straightAngleDeg;
        // 右舵(角度为正)时把整个世界沿 y 镜像，下面就只需要写"左转"一套几何
        boolean mirror = steerAngleDeg > 0;

        // 车体轮廓(含左右安全余量)。镜像后左右要互换
        double rectYL, rectYR;
        if (mirror) {
            rectYL =  FOOTPRINT_RIGHT_M + corridorMarginM;
            rectYR = -(FOOTPRINT_LEFT_M + corridorMarginM);
        } else {
            rectYL =  FOOTPRINT_LEFT_M  + corridorMarginM;
            rectYR = -(FOOTPRINT_RIGHT_M + corridorMarginM);
        }

        // 转弯圆心 C：在后轴延长线上，左转时在车体左侧 +R 处
        double turnR = arc ? wheelbaseM / Math.tan(Math.toRadians(Math.abs(steerAngleDeg))) : 0;
        double cx = rearAxleXM;
        double cy = turnR;

        // 圆环带半径范围：内径 = 圆心到车体最近边(后轴 x 落在车身范围内，所以就是侧边)
        // 外径 = 圆心到四个角的最大距离
        double rIn = 0, rOut = 0;
        if (arc) {
            rIn  = turnR - rectYL;
            rOut = 0;
            for (double px2 : new double[]{FOOTPRINT_FRONT_M, FOOTPRINT_BACK_M}) {
                for (double py2 : new double[]{rectYL, rectYR}) {
                    rOut = Math.max(rOut, Math.hypot(px2 - cx, py2 - cy));
                }
            }
            if (rIn < 0) rIn = 0;   // 圆心落进车体里(舵角极大)，内径按 0
        }

        double yLeft  = rectYL;
        double yRight = rectYR;
        double xFar   = FOOTPRINT_FRONT_M + corridorRangeM;

        // 按距离分档计数：档号 = floor(净空 / bucketM)。只需要最近的那几档，用稀疏计数即可
        final int buckets = (int) Math.ceil(corridorRangeM / Math.max(0.01, bucketM)) + 1;
        int[] hist = new int[buckets];

        // 左右侧向净空(平移对位/窄通道侧移用)：车身纵向范围内，离左/右侧边多远有东西。同样分档 + minPoints 去飞点
        final int sideBuckets = (int) Math.ceil(SIDE_RANGE_M / Math.max(0.01, bucketM)) + 1;
        int[] leftHist  = new int[sideBuckets];
        int[] rightHist = new int[sideBuckets];

        for (int i = 0; i < totalPoints; i++) {
            int base = i * pointStep;
            if (base + pointStep > raw.length) break;

            // 先判高度，band 外的点直接跳过，省两次 float 解析
            float pz = readFloat(raw, base + zOff);
            if (Float.isNaN(pz) || Float.isInfinite(pz)) continue;
            if (pz < zMin || pz > zMax) continue;

            float px = readFloat(raw, base + xOff);
            float py = readFloat(raw, base + yOff);
            if (Float.isNaN(px) || Float.isInfinite(px)
                    || Float.isNaN(py) || Float.isInfinite(py)) continue;

            // camera_init 世界系 → 车体系(逆旋转)
            double dx = px - poseX;
            double dy = py - poseY;
            double bx =  dx * cos + dy * sin;
            double by = -dx * sin + dy * cos;

            if (bx >= FOOTPRINT_BACK_M && bx <= FOOTPRINT_FRONT_M) {
                double side = by > FOOTPRINT_LEFT_M ? by - FOOTPRINT_LEFT_M
                        : (by < -FOOTPRINT_RIGHT_M ? -FOOTPRINT_RIGHT_M - by : -1);
                if (side >= 0 && side <= SIDE_RANGE_M) {
                    int sb = (int) (side / bucketM);
                    if (sb < sideBuckets) {
                        if (by > 0) leftHist[sb]++; else rightHist[sb]++;
                    }
                }
            }

            if (mirror) by = -by;

            double travel;
            if (arc) {
                travel = arcTravelToHit(bx, by, cx, cy, turnR, rIn, rOut, yLeft, yRight);
                // ★ 保险杠兜底：圆弧扫掠依赖轴距/后轴位置这两个**估算值**，估偏了走廊会弯过头，
                //   把真正挡在正前方的东西漏掉(fail-open)。所以贴着车头这一小段无论舵角多大
                //   都按直线判，保证"车头正前方近处有东西一定停"。
                if (bx >= FOOTPRINT_FRONT_M && bx <= FOOTPRINT_FRONT_M + bumperGuardM
                        && by >= yRight && by <= yLeft) {
                    double straight = bx - FOOTPRINT_FRONT_M;
                    if (travel < 0 || straight < travel) travel = straight;
                }
            } else {
                if (bx < FOOTPRINT_FRONT_M || bx > xFar) continue;
                if (by < yRight || by > yLeft) continue;
                travel = bx - FOOTPRINT_FRONT_M;
            }
            if (travel < 0 || travel > corridorRangeM) continue;

            int b = (int) (travel / bucketM);
            if (b >= 0 && b < buckets) hist[b]++;
        }

        double clearance = Double.MAX_VALUE;
        int hitPoints = 0;
        for (int b = 0; b < buckets; b++) {
            if (hist[b] >= minPoints) {
                clearance = b * bucketM;
                hitPoints = hist[b];
                break;
            }
        }

        lastLeftClearM  = firstHit(leftHist);
        lastRightClearM = firstHit(rightHist);

        lastClearanceM   = clearance;
        lastHitPoints    = hitPoints;
        lastSteerAngleDeg = steerAngleDeg;
        lastTurnRadiusM  = arc ? turnR : Double.MAX_VALUE;
        lastUpdateMs     = System.currentTimeMillis();
        return clearance;
    }

    /**
     * 车体绕圆心 C 前进多少**弧长**后，这个点会被车身扫到。
     *
     * 思路：车体绕 C 做定轴转动，所以点到 C 的距离 ρ 在整个过程中不变。
     *   ① ρ 不在车身扫出的圆环带 [rIn, rOut] 内 → 这个点永远撞不上，直接排除
     *   ② 否则求"车体前缘在半径 ρ 上的方位角" θLead，点的方位角 φ 减去它就是还要转过的角度
     *   ③ 弧长 = Δφ × R  (R 是车体参考点的转弯半径，即"车还能再往前开多少米")
     *
     * 已镜像成左转，车绕 C 逆时针转(方位角递增)。
     *
     * @return 还能行驶的弧长(m)；永不相撞或已在身后时返回 -1
     */
    private double arcTravelToHit(double bx, double by, double cx, double cy, double turnR,
                                  double rIn, double rOut, double yLeft, double yRight) {
        double rho = Math.hypot(bx - cx, by - cy);
        if (rho < rIn || rho > rOut) return -1;

        double thetaLead = leadingAngle(rho, cx, cy, yLeft, yRight);
        if (Double.isNaN(thetaLead)) return -1;

        double phi = Math.atan2(by - cy, bx - cx);
        double d = phi - thetaLead;
        while (d < 0)             d += 2 * Math.PI;
        while (d >= 2 * Math.PI)  d -= 2 * Math.PI;
        // 超过半圈说明点在车身后方(转一大圈才轮到它)，不是本次前进要撞的东西
        if (d > Math.PI) return -1;

        return d * turnR;
    }

    /**
     * 半径 ρ 的圆(圆心 C)与车体矩形相交处、**方位角最大**的那个点 —— 即绕 C 逆时针转时
     * 最先扫过去的车体前缘。车体矩形 x∈[BACK, FRONT]、y∈[yRight, yLeft]，C 在矩形上方(cy>yLeft)，
     * 所以交点的方位角都在 (-π, 0)，取最大 = 最靠近 +x 方向的那个。
     *
     * @return 方位角(rad)；该半径与车体无交集时返回 NaN
     */
    private double leadingAngle(double rho, double cx, double cy, double yLeft, double yRight) {
        double best = Double.NaN;

        // 候选①：车体前缘 x = FRONT 这条竖边
        double a = FOOTPRINT_FRONT_M - cx;          // >0
        if (rho >= Math.abs(a)) {
            double dyy = Math.sqrt(rho * rho - a * a);
            double y = cy - dyy;                    // 取 y<cy 的那个交点
            if (y >= yRight && y <= yLeft) best = Math.atan2(-dyy, a);
        }
        // 候选②③：车体左右两条横边 y = yLeft / yRight
        for (double edgeY : new double[]{yLeft, yRight}) {
            double b = cy - edgeY;                  // >0
            if (rho < b) continue;
            double dxx = Math.sqrt(rho * rho - b * b);
            double x = cx + dxx;                    // 取 x>cx 的那个交点(方位角更大)
            if (x < FOOTPRINT_BACK_M || x > FOOTPRINT_FRONT_M) continue;
            double t = Math.atan2(-b, dxx);
            if (Double.isNaN(best) || t > best) best = t;
        }
        return best;
    }

    private double firstHit(int[] h) {
        for (int b = 0; b < h.length; b++) {
            if (h[b] >= minPoints) return b * bucketM;
        }
        return Double.MAX_VALUE;
    }

    /** 车头前方最近障碍距离(m)。Double.MAX_VALUE = 走廊内无障碍 */
    public double getClearanceM() { return lastClearanceM; }

    /**
     * 车身左侧边往外最近障碍(m)，Double.MAX_VALUE = SIDE_RANGE_M 内没看到。
     * ⚠ "没看到"≠"没有"：雷达在车头，车身后段两侧大半被车体自己挡住，这个数只能证明**看得见的那段**是空的。
     *   平移前必须再叠加静态地图校验(ManeuverService)。
     */
    public double getLeftClearanceM()  { return lastLeftClearM; }
    public double getRightClearanceM() { return lastRightClearM; }

    /** 距上次判定的毫秒数；-1 表示从未判定过 */
    public long getAgeMs() {
        return lastUpdateMs == 0L ? -1L : System.currentTimeMillis() - lastUpdateMs;
    }

    /** 判定参数 + 最近一次结果，供前端配置面板回显 */
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        double c = lastClearanceM;
        m.put("clearanceM",      c == Double.MAX_VALUE ? null : Math.round(c * 100.0) / 100.0);
        m.put("hitPoints",       lastHitPoints);
        m.put("ageMs",           getAgeMs());
        m.put("corridorMarginM", corridorMarginM);
        m.put("corridorRangeM",  corridorRangeM);
        m.put("corridorWidthM",  FOOTPRINT_LEFT_M + FOOTPRINT_RIGHT_M + corridorMarginM * 2);
        m.put("steerAngleDeg",   lastSteerAngleDeg);
        m.put("turnRadiusM",     lastTurnRadiusM == Double.MAX_VALUE ? null
                                 : Math.round(lastTurnRadiusM * 100.0) / 100.0);
        m.put("mode",            lastTurnRadiusM == Double.MAX_VALUE ? "直行(矩形走廊)" : "打舵(圆弧扫掠)");
        m.put("wheelbaseM",      wheelbaseM);
        m.put("minPoints",       minPoints);
        m.put("bucketM",         bucketM);
        m.put("heightBandM",     new double[]{groundClearanceM, vehicleHeightM});
        return m;
    }

    private static float readFloat(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }
}
