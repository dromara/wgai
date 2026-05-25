package org.jeecg.modules.ros2.util;

import lombok.extern.slf4j.Slf4j;

/**
 * cmd_vel(linear.x, angular.z) → (mode, rpm, steer_deg)
 *
 * 物理模型:
 *   车体是标准差速底盘(4 万向 + 2 驱动),但 PLC 工程师把对外接口包装成
 *   "速度 + 转角"——PLC 内部根据这两个值自己解算左右轮转速差。
 *
 * 所以 Java 这边按虚拟阿克曼模型计算转角:
 *   steerDeg = atan2(L_virtual × angular, linear)
 *
 *   L_virtual 是一个调节系数,不是真实物理量。值越大转弯越急,越小越平缓。
 *   建议从 0.5 起步,后期实测调整。
 *
 * 三种输出模式对应不同的 PLC 按钮:
 *   STOP     → 松开所有模式按钮,速度=0
 *   STRAIGHT → 按下 M1.4(直行),写 rpm + steer_deg
 *   ROTATE   → 按下 M3.5(旋转),写 rpm,steer=0(PLC内部让两轮反转实现原地旋转)
 */
@Slf4j
public final class DiffDriveSteerAdapter {

    // ====================== 标定参数(实车调试调整) ======================

    /** 虚拟轴距,用于阿克曼公式。越大转弯越急,越小越平缓。建议 0.4~0.8 */
    public static final double L_VIRTUAL = 0.5;

    /** 驱动轮半径(米),用于 m/s → rpm 换算。实测! */
    public static final double WHEEL_RADIUS = 0.10;

    // ====================== 安全限速(用户强调:慢但安全) ======================

    /** 最大线速度限幅 m/s。0.25 m/s ≈ 慢走速度,新车调试上限 */
    public static final double MAX_LINEAR_MPS = 0.25;

    /** 最大角速度限幅 rad/s */
    public static final double MAX_ANGULAR_RPS = 0.8;

    /** 最大 rpm 输出(对应 PLC IW1204 范围 0~3000,我们保守限制到 500) */
    public static final int MAX_RPM = 500;

    /** 直行模式最大转角(度) - 来自 PLC 工程师约定 */
    public static final double MAX_STEER_DEG = 45.0;

    // ====================== 判定阈值 ======================

    /** 线/角速度都小于此值视为停车 */
    public static final double LIN_DEADZONE = 0.02;
    public static final double ANG_DEADZONE = 0.05;

    /** |linear| 小于此值且角速度足够,进入"原地旋转"模式 */
    public static final double ROTATE_LIN_THRESHOLD = 0.05;

    public enum DriveMode { STOP, STRAIGHT, ROTATE }

    public static final class Command {
        public DriveMode mode;
        public int  speedRpm;     // → IW1204
        public int  steerDeg;     // → IW1206  (单位:1°,范围 -45 ~ +45)
        public double debugMps;
        public double debugAngVel;

        @Override
        public String toString() {
            return String.format("%s rpm=%d steer=%d° (源: lin=%.3fm/s ang=%.3frad/s)",
                    mode, speedRpm, steerDeg, debugMps, debugAngVel);
        }
    }

    private DiffDriveSteerAdapter() {}

    public static Command twistToCmd(double linear, double angular) {
        // ① 限幅
        linear  = clamp(linear,  -MAX_LINEAR_MPS,  MAX_LINEAR_MPS);
        angular = clamp(angular, -MAX_ANGULAR_RPS, MAX_ANGULAR_RPS);

        Command c = new Command();
        c.debugMps    = linear;
        c.debugAngVel = angular;

        boolean linZero = Math.abs(linear)  < LIN_DEADZONE;
        boolean angZero = Math.abs(angular) < ANG_DEADZONE;

        // ② 停车
        if (linZero && angZero) {
            c.mode = DriveMode.STOP;
            c.speedRpm = 0;
            c.steerDeg = 0;
            return c;
        }

        // ③ 原地旋转: 线速度近 0 但角速度存在
        if (Math.abs(linear) < ROTATE_LIN_THRESHOLD) {
            c.mode = DriveMode.ROTATE;
            // 旋转用低速,最大不超过 200 rpm
            double rpm = Math.abs(angular) / MAX_ANGULAR_RPS * 200;
            c.speedRpm = (int) Math.min(rpm, 200);
            // 旋转方向(左转/右转)由 PLC 通过其他位判断,这里 steer 发 0
            // 如果 PLC 需要 steer 符号表达旋转方向,改成:
            //   c.steerDeg = (int) Math.signum(angular) * 1;
            c.steerDeg = 0;
            return c;
        }

        // ④ 直行模式(含弧线)
        c.mode = DriveMode.STRAIGHT;

        // 倒车防御: Nav2 应该配 allow_reversing:false,这里取绝对值兜底
        double absLin = Math.abs(linear);

        // 阿克曼转角公式
        double steerRad = Math.atan2(L_VIRTUAL * angular, absLin);
        double steerDeg = Math.toDegrees(steerRad);
        steerDeg = clamp(steerDeg, -MAX_STEER_DEG, MAX_STEER_DEG);

        // m/s → rpm:  rpm = v × 60 / (2π × r)
        double rpm = absLin * 60.0 / (2 * Math.PI * WHEEL_RADIUS);
        c.speedRpm = (int) clamp(rpm, 0, MAX_RPM);
        c.steerDeg = (int) Math.round(steerDeg);

        return c;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}