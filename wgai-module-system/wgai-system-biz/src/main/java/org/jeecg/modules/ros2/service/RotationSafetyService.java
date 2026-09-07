package org.jeecg.modules.ros2.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原地旋转安全判定。
 *
 * ─── 为什么需要它 ─────────────────────────────────────────────────────────
 * 扒粮机支持原地旋转(VW1002=1, V1001.5=左转 / V1001.6=右转)，但车体是
 * 5.5m × 2.1m 的大家伙，原地转一圈扫出来的是一个**圆**，这个圆比车身本身大得多。
 * 转之前必须确认这个圆内没有障碍物，否则就是直接撞上去。
 *
 * ─── 扫转半径怎么算 ───────────────────────────────────────────────────────
 * footprint 以雷达安装点(= body 系原点, fast_lio 惯例)为原点:
 *   前端 +0.5m / 后端 -5.0m / 左 +1.2m / 右 -0.9m  (与 MapController.ROBOT_FOOTPRINT 一致)
 * 雷达横向不在车身中线上，左右不对称；扫转半径取到最远角点，所以横向按较大的一侧(1.2m)算。
 * 旋转中心在雷达后方 centerOffsetM 处(底盘 2.1×2.1 的中心)，于是:
 *
 *   R = max( √((前端+offset)² + 1.2²),  √((后端-offset)² + 1.2²) )
 *
 * offset=1.75m(底盘在车身正中)时 R = 3.46m，即需要约 6.9m 直径的净空圆。
 * 注意 offset 偏离车身中点越远，R 越大 —— 该值必须现场实测后写进 application.yml。
 *
 * ─── 数据来源与关键约束 ───────────────────────────────────────────────────
 * 用 /cloud_registered(fast_lio 配准点云，坐标在 camera_init 世界系)，配合
 * /Odometry 的当前位姿反变换回车体系。有三点必须注意:
 *
 *  1) **必须用全量点，不能用抽稀后的点**。ROS2WebSocketHandler 里推给前端的点云
 *     是按 MAX_POINTS_TO_PUSH 抽稀过的，一根立柱/一个人完全可能整个落在采样间隔里
 *     被漏掉。安全判定在抽稀之前对全量点跑。
 *
 *  2) **mid360 单帧覆盖不全**。非重复扫描的雷达单帧在 360° 上是稀疏的，要累积若干帧
 *     才铺得满。所以这里维护一个按方位角分扇区(1°/扇区)的滚动窗口，取每个扇区在窗口
 *     期内的最近距离。
 *
 *  3) **车一动，之前累积的扇区数据在车体系下就失效了**，必须清空重新累积。
 *
 * ─── 失效安全(fail-safe) ─────────────────────────────────────────────────
 * 扇区没有数据 = "不知道那边有没有东西"，绝不等于"那边是安全的"。
 * 因此覆盖率不足时一律返回不允许旋转，而不是默认放行。
 */
@Slf4j
@Service
public class RotationSafetyService {

    // ======================== 车体 footprint(与 MapController.ROBOT_FOOTPRINT 保持一致) ========================

    /** 车体前端距雷达(body 原点) */
    private static final double FOOTPRINT_FRONT_M = 0.5;
    /** 车体后端距雷达(body 原点)，取正值参与计算 */
    private static final double FOOTPRINT_BACK_M  = 5.0;
    /**
     * 车体横向距雷达的**较大**一侧(左 1.2m，右 0.9m —— 雷达不在车身中线上)。
     * 扫转半径取最远角点，所以只需要较大的那一侧。
     */
    private static final double FOOTPRINT_HALF_W_M = 1.2;

    /** 方位角扇区数，1°/扇区 */
    private static final int BIN_COUNT = 360;

    // ======================== 配置项 ========================

    /**
     * 旋转中心(底盘 2.1×2.1 的中心)在雷达**后方**多少米。
     * 默认 0 = 按雷达原点算(R=5.14m)，是最保守的取值:
     * 只会"该转的时候不让转"，不会"不该转的时候放行"。现场实测后必须改成真实值。
     */
    @Value("${plc.rotate.center-offset-m:0.0}")
    private double centerOffsetM;

    /** 扫转圆外再留的安全余量 */
    @Value("${plc.rotate.safety-margin-m:0.5}")
    private double safetyMarginM;

    /** 雷达安装高度(离地)。用于把点云 z 换算成"离地高度"，判断障碍物是否在车身高度带内 */
    @Value("${plc.rotate.lidar-height-m:2.0}")
    private double lidarHeightM;

    /** 车体总高。高于此高度的点撞不到车，忽略 */
    @Value("${plc.rotate.vehicle-height-m:2.3}")
    private double vehicleHeightM;

    /** 地面滤除高度。低于此离地高度的点视为地面反射，忽略 */
    @Value("${plc.rotate.ground-clearance-m:0.10}")
    private double groundClearanceM;

    /** 扇区数据有效期(滚动窗口)，超期视为无数据 */
    @Value("${plc.rotate.cloud-window-ms:1000}")
    private long cloudWindowMs;

    /** 最低方位角覆盖率，低于此值判定为"数据不足"，禁止旋转 */
    @Value("${plc.rotate.min-coverage:0.80}")
    private double minCoverage;

    /** 判定车体是否静止的位移阈值，超过则清空已累积扇区 */
    @Value("${plc.rotate.stationary-tolerance-m:0.20}")
    private double stationaryToleranceM;

    /** 判定车体是否静止的转角阈值(rad)，超过则清空已累积扇区 */
    @Value("${plc.rotate.stationary-tolerance-rad:0.10}")
    private double stationaryToleranceRad;

    // ======================== 扇区滚动窗口 ========================

    /** 每个方位角扇区在窗口期内观测到的最近障碍物距离(到旋转中心) */
    private final double[] binMinDist = new double[BIN_COUNT];
    /** 每个扇区最后一次更新时间，用于超期判定 */
    private final long[]   binStamp   = new long[BIN_COUNT];

    private double  lastPoseX;
    private double  lastPoseY;
    private double  lastPoseTheta;
    private boolean hasLastPose = false;

    /** 最近一次收到点云的时间，用于判断数据链路是否还活着 */
    private volatile long lastCloudMs = 0L;

    // ======================== 几何 ========================

    /** 原地旋转扫出的圆半径(车体最远角点到旋转中心的距离) */
    public double getSweptRadiusM() {
        double front = Math.hypot(FOOTPRINT_FRONT_M + centerOffsetM, FOOTPRINT_HALF_W_M);
        double rear  = Math.hypot(FOOTPRINT_BACK_M  - centerOffsetM, FOOTPRINT_HALF_W_M);
        return Math.max(front, rear);
    }

    /** 允许旋转所需的净空半径 = 扫转半径 + 安全余量 */
    public double getRequiredRadiusM() {
        return getSweptRadiusM() + safetyMarginM;
    }

    // ======================== 点云接入 ========================

    /**
     * 用一帧原始点云更新扇区窗口。必须传**全量**点(抽稀前)。
     *
     * @param raw         PointCloud2 原始字节
     * @param pointStep   每点字节数
     * @param totalPoints 点数
     * @param xOff        x 字段字节偏移
     * @param yOff        y 字段字节偏移
     * @param zOff        z 字段字节偏移
     * @param poseX       车体在 camera_init 系的 x
     * @param poseY       车体在 camera_init 系的 y
     * @param poseTheta   车体在 camera_init 系的偏航角
     */
    public void updateFromCloud(byte[] raw, int pointStep, int totalPoints,
                                int xOff, int yOff, int zOff,
                                double poseX, double poseY, double poseTheta) {
        long now = System.currentTimeMillis();

        // 点云 z 在 camera_init 系，原点是雷达开机位置。假定地面平整(车体无明显俯仰/侧倾)，
        // 则 z 就是相对雷达安装高度的高度差，离地高度 = z + lidarHeightM。
        double zMin = -lidarHeightM + groundClearanceM;   // 地面以上一点点
        double zMax = -lidarHeightM + vehicleHeightM;     // 车顶

        double cos = Math.cos(poseTheta);
        double sin = Math.sin(poseTheta);

        synchronized (binMinDist) {
            // 车动过 → 之前在车体系下累积的扇区全部作废
            if (hasLastPose
                    && (Math.hypot(poseX - lastPoseX, poseY - lastPoseY) > stationaryToleranceM
                     || Math.abs(normalizeAngle(poseTheta - lastPoseTheta)) > stationaryToleranceRad)) {
                clearBins();
            }
            lastPoseX = poseX;
            lastPoseY = poseY;
            lastPoseTheta = poseTheta;
            hasLastPose = true;

            for (int i = 0; i < totalPoints; i++) {
                int base = i * pointStep;
                if (base + zOff + 4 > raw.length || base + xOff + 4 > raw.length
                        || base + yOff + 4 > raw.length) {
                    break;
                }

                // 先看高度，band 外的点直接跳过，省掉两次 float 解析
                float pz = readFloat(raw, base + zOff);
                if (Float.isNaN(pz) || Float.isInfinite(pz)) continue;
                if (pz < zMin || pz > zMax) continue;

                float px = readFloat(raw, base + xOff);
                float py = readFloat(raw, base + yOff);
                if (Float.isNaN(px) || Float.isInfinite(px)
                        || Float.isNaN(py) || Float.isInfinite(py)) {
                    continue;
                }

                // camera_init 世界系 → 车体系(逆旋转)
                double dx = px - poseX;
                double dy = py - poseY;
                double bx =  dx * cos + dy * sin;
                double by = -dx * sin + dy * cos;

                // 车体系 → 以旋转中心为原点(旋转中心在雷达后方 centerOffsetM 处)
                double rx = bx + centerOffsetM;
                double ry = by;
                double dist = Math.sqrt(rx * rx + ry * ry);

                int bin = (int) Math.floor(Math.toDegrees(Math.atan2(ry, rx)));
                bin = ((bin % BIN_COUNT) + BIN_COUNT) % BIN_COUNT;

                if (now - binStamp[bin] > cloudWindowMs) {
                    // 该扇区上一次数据已超期，直接用本点重置
                    binMinDist[bin] = dist;
                } else if (dist < binMinDist[bin]) {
                    binMinDist[bin] = dist;
                }
                binStamp[bin] = now;
            }
        }

        lastCloudMs = now;
    }

    private void clearBins() {
        for (int i = 0; i < BIN_COUNT; i++) {
            binMinDist[i] = 0.0;
            binStamp[i]   = 0L;
        }
    }

    // ======================== 判定 ========================

    /**
     * 判定当前是否满足原地旋转条件。
     *
     * 返回字段:
     *   canRotate            是否允许旋转
     *   sweptRadiusM         扫转圆半径
     *   requiredRadiusM      所需净空半径(= 扫转半径 + 安全余量)
     *   minClearanceM        窗口期内观测到的最近障碍物距离(到旋转中心)
     *   nearestAngleDeg      最近障碍物方位角(车体系, 0°=正前, 逆时针为正)
     *   coverage             方位角覆盖率(有新鲜数据的扇区占比)
     *   cloudAgeMs           距最近一帧点云的时间
     */
    public Map<String, Object> check() {
        long now = System.currentTimeMillis();

        int    freshBins = 0;
        double minDist   = Double.MAX_VALUE;
        int    minBin    = -1;

        synchronized (binMinDist) {
            for (int i = 0; i < BIN_COUNT; i++) {
                if (binStamp[i] == 0L || now - binStamp[i] > cloudWindowMs) continue;
                freshBins++;
                if (binMinDist[i] < minDist) {
                    minDist = binMinDist[i];
                    minBin  = i;
                }
            }
        }

        double coverage  = freshBins / (double) BIN_COUNT;
        double required  = getRequiredRadiusM();
        long   cloudAge  = lastCloudMs == 0L ? -1L : now - lastCloudMs;

        boolean dataOk    = freshBins > 0 && coverage >= minCoverage;
        boolean clearance = dataOk && minDist > required;

        String message;
        if (lastCloudMs == 0L) {
            message = "从未收到点云，无法判定。请确认 rosbridge 已连接且 /cloud_registered 正在发布。";
        } else if (cloudAge > cloudWindowMs * 3) {
            message = "点云已中断 " + cloudAge + "ms，无法判定。";
        } else if (!dataOk) {
            message = String.format(
                    "点云方位覆盖率仅 %.0f%%(需 ≥%.0f%%)，数据不足，禁止旋转。"
                            + "扇区无数据不代表安全，可能是遮挡或雷达盲区。",
                    coverage * 100, minCoverage * 100);
        } else if (!clearance) {
            message = String.format(
                    "净空不足: 最近障碍物 %.2fm(方位 %d°)，需要 ≥%.2fm(扫转半径 %.2fm + 余量 %.2fm)，禁止旋转。",
                    minDist, minBin, required, getSweptRadiusM(), safetyMarginM);
        } else {
            message = String.format(
                    "满足旋转条件: 最近障碍物 %.2fm(方位 %d°) > 所需 %.2fm，覆盖率 %.0f%%。",
                    minDist, minBin, required, coverage * 100);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("canRotate",       clearance);
        r.put("sweptRadiusM",    round2(getSweptRadiusM()));
        r.put("requiredRadiusM", round2(required));
        r.put("minClearanceM",   minDist == Double.MAX_VALUE ? null : round2(minDist));
        r.put("nearestAngleDeg", minBin);
        r.put("coverage",        round2(coverage));
        r.put("freshBins",       freshBins);
        r.put("cloudAgeMs",      cloudAge);
        r.put("centerOffsetM",   centerOffsetM);
        r.put("safetyMarginM",   safetyMarginM);
        r.put("message",         message);
        return r;
    }

    // ======================== 工具 ========================

    private static float readFloat(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static double normalizeAngle(double a) {
        while (a >  Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
