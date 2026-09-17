package org.jeecg.modules.ros2.service;

import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.ros2.controller.MapController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.jeecg.modules.ros2.service.ObstacleGuardService.*;

/**
 * 导航任务编排：Nav2 负责路上(直行模式弧线 + 限制倒车)，本服务负责起点和终点的机动。
 *
 * <pre>
 *   发目标 ─→ ① 起点预旋转：目标在身后(方位差 > pre-rotate-min-deg)且净空够 → 原地转向目标方位
 *          ─→ ② 交给 Nav2(有侧向对位时开到"终点右移 sideApproach"的预备位)
 *          ─→ Nav2 到达 ─→ ③ 终点对位：原地转到目标朝向 → 平移消掉横向偏差(含侧向对位那段)
 *          ─→ Nav2 失败 ─→ ④ 窄通道侧移：一侧贴得太近 → 平移摆到中间 → 重发一次目标
 * </pre>
 *
 * 为什么不让 Nav2 自己在路上转/平移：底盘一次只能处于一种模式，切模式要停车等舵轮转到位，
 * Nav2 Humble 没有能表达这种约束的控制器(RPP 连原地转和倒车都不许同时开)。
 *
 * ⚠ 安全：每个机动动作前都要同时过「点云」和「静态地图」两道判定，任一不过就不动(fail-safe)。
 *   点云管动态物但车身后段被自己挡住看不全，静态地图管墙和料堆但看不到人 —— 两道都过也不是绝对安全，
 *   所以速度必须慢、单次平移距离有上限。
 */
@Slf4j
@Service
public class ManeuverService {

    @Autowired private RobotHardwareService  hw;
    @Autowired private ROS2BridgeService     bridge;
    @Autowired private RotationSafetyService rotationSafety;
    @Autowired private ObstacleGuardService  guard;
    @Autowired private NavigationService     navigation;
    @Autowired private MapController         mapController;
    @Autowired private WebSocketPushService  push;

    /** 总开关。false 时退化成"直接把目标交给 Nav2"，和以前一样 */
    @Value("${plc.maneuver.enabled:true}")
    private volatile boolean enabled;

    /** 目标方位与车头朝向相差超过这个角度才预旋转(目标在身后)。小于它交给 Nav2 走弧线 */
    @Value("${plc.maneuver.pre-rotate-min-deg:100}")
    private volatile double preRotateMinDeg;

    /** Nav2 到达后是否做终点对位(转到目标朝向 + 横向纠偏)。侧向对位的目标不受它控制，总会做 */
    @Value("${plc.maneuver.goal-align:true}")
    private volatile boolean goalAlign;

    @Value("${plc.maneuver.yaw-tol-deg:3.0}")
    private volatile double yawTolDeg;

    @Value("${plc.maneuver.lateral-tol-m:0.05}")
    private volatile double lateralTolM;

    /** 单次平移最大距离。车身后段两侧雷达看不全，不许一口气横移太远 */
    @Value("${plc.maneuver.lateral-max-m:1.0}")
    private volatile double lateralMaxM;

    /** 平移后车身侧边离障碍至少还要留多少 */
    @Value("${plc.maneuver.lateral-margin-m:0.3}")
    private volatile double lateralMarginM;

    @Value("${plc.maneuver.rotate-rpm:150}")
    private volatile int rotateRpm;
    @Value("${plc.maneuver.rotate-slow-rpm:50}")
    private volatile int rotateSlowRpm;
    /** 离目标角度小于它换慢速 */
    @Value("${plc.maneuver.rotate-slow-deg:15}")
    private volatile double rotateSlowDeg;

    @Value("${plc.maneuver.lateral-rpm:100}")
    private volatile int lateralRpm;
    @Value("${plc.maneuver.lateral-slow-rpm:40}")
    private volatile int lateralSlowRpm;
    @Value("${plc.maneuver.lateral-slow-m:0.2}")
    private volatile double lateralSlowM;

    /** 单个机动动作最长时间(含切模式等舵轮) */
    @Value("${plc.maneuver.timeout-ms:60000}")
    private volatile long timeoutMs;

    /** 停车后等多久再做判定：旋转判定要累积 ~1s 的点云扇区，车刚停时是空的 */
    @Value("${plc.maneuver.settle-ms:1500}")
    private volatile long settleMs;

    /** Nav2 失败后是否尝试窄通道侧移 + 重发一次 */
    @Value("${plc.maneuver.corridor-shift:true}")
    private volatile boolean corridorShift;
    /** 较近那一侧净空小于它才算"太挤"，否则失败原因不在侧向，侧移没意义 */
    @Value("${plc.maneuver.corridor-tight-m:0.4}")
    private volatile double corridorTightM;
    @Value("${plc.maneuver.corridor-shift-max-m:0.6}")
    private volatile double corridorShiftMaxM;

    private static final class Mission {
        final long id;
        final double gx, gy, gth, side;
        /** 精确停车：到达后原地转 + 平移对位；false = 到附近就算完成 */
        final boolean precise;
        volatile boolean aborted;
        /** 当前在等哪次 Nav2 目标的结果：发目标时刻(纳秒)，0 = 没在等 */
        volatile long waitingSinceNs;
        volatile boolean shiftTried;
        Mission(long id, double gx, double gy, double gth, double side, boolean precise) {
            this.id = id; this.gx = gx; this.gy = gy; this.gth = gth; this.side = side; this.precise = precise;
        }
    }

    /** 拒绝执行：判定没过，车一动没动，可以放心走下一步(比如交给 Nav2) */
    private static final class Refused extends Exception {
        Refused(String m) { super(m); }
    }

    private static final class Aborted extends RuntimeException {
        Aborted(String m) { super(m); }
    }

    private final AtomicLong seq = new AtomicLong();
    private volatile Mission mission;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maneuver");
        t.setDaemon(true);
        return t;
    });

    /**
     * 发目标后这么久还没收到任何 /plan，就判定"规划不出来"。
     * 起点压障碍(Starting point in lethal space)时 Nav2 自己要把 清图→等待 恢复动作轮 6 遍才报失败，
     * 半分钟以上干等；这里提前取消，诊断并侧移重试。要大于 Smac 的 max_planning_time(10s)，否则正常的慢规划会被误杀
     */
    @Value("${plc.maneuver.plan-timeout-ms:12000}")
    private volatile long planTimeoutMs;

    private volatile long lastPlanMs = 0L;
    private final java.util.concurrent.ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "maneuver-timer");
                t.setDaemon(true);
                return t;
            });

    @PostConstruct
    public void init() {
        bridge.addNavTerminalListener(this::onNavTerminal);
        bridge.addPlanListener(() -> lastPlanMs = System.currentTimeMillis());
    }

    @PreDestroy
    public void destroy() {
        abort("服务关闭");
        worker.shutdownNow();
        timer.shutdownNow();
    }

    // ======================== 对外 ========================

    /** 目标点放不下车身时抛出，带上车身框和压到的障碍格，前端直接画出来看哪里压住了 */
    public static final class GoalRejectedException extends IllegalArgumentException {
        public final Map<String, Object> data;
        GoalRejectedException(String msg, Map<String, Object> data) { super(msg); this.data = data; }
    }

    /**
     * 开始一次导航任务(异步)。
     *
     * 目标点 = **车头雷达**停车位置(和「设置初始点位」、车图标同一个锚点，现场习惯)。
     * 车身从这个点往后伸 5m，前端会画出车身框。
     *
     * 目标位姿放不下车身时先在附近微调(朝向 / 横向)：点击和拖箭头都有几厘米、几度的手抖，
     * 窄车位差 2° 车尾就压墙，不能因为这个直接拒绝。
     *
     * @param theta 目标朝向(rad)，null = 取"当前位置 → 目标"的方位角
     * @param side  侧向对位距离(m，左正)，null/0 = 不用
     * @return x/y/theta 实际采用的(可能微调过)目标；note 微调/余量提示
     */
    public Map<String, Object> startMission(double x, double y, Double theta, Double side, boolean precise) {
        double s = side == null ? 0 : side;
        if (Math.abs(s) > lateralMaxM) {
            throw new IllegalArgumentException(String.format("侧向对位 %.2fm 超过单次平移上限 %.2fm", s, lateralMaxM));
        }
        double[] cur = bridge.getLastAmclPose();
        // 不指定朝向 = 车身轴线顺着"当前位置→目标"的连线：目标在车头这半边就车头朝行进方向(开过去)，
        // 在车尾这半边就车尾朝行进方向(倒过去，车头朝向基本不变)。
        //   · 纯方位角(v1)：点车后方变成要求掉头停车，倒几米的事被规划成绕一大圈(2026-09-14)
        //   · 保持当前车头朝向(v2)：点侧面/拐角后的目标，到了还得朝原方向 → 路径 S 形来回调(2026-09-15 "规划很繁琐")
        double base;
        if (theta != null) {
            base = theta;
        } else if (cur == null) {
            base = 0;
        } else {
            double bearing = Math.atan2(y - cur[1], x - cur[0]);
            base = Math.abs(wrap(bearing - cur[2])) <= Math.PI / 2 ? bearing : wrap(bearing + Math.PI);
        }

        double[] fit = findGoalFit(x, y, base, s, theta != null);
        if (fit == null) {
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("x", x); d.put("y", y); d.put("theta", base);
            List<double[]> hits = mapController.staticMapOccupiedPoints(footprintPoly(x, y, base, 0), 400);
            d.put("hits", hits);
            throw new GoalRejectedException(String.format(
                    "%s车身放不下(红框为车身，红点为压到的障碍格，共 %d 处)。%s",
                    theta != null ? "该朝向附近(±6°、横向±0.2m)" : "任何朝向", hits.size(),
                    theta != null ? "换位置，或拖箭头时对齐车位方向" : "离障碍远一点，或用「指定朝向目标」按车位方向拖"), d);
        }
        double gx = fit[0], gy = fit[1], th = fit[2];

        StringBuilder note = new StringBuilder();
        double dTh = Math.toDegrees(wrap(th - base)), dLat = fit[3];
        if (Math.abs(dTh) > 0.5 || Math.abs(dLat) > 0.01) {
            note.append(String.format("%s放不下，已微调: 朝向%+.0f° 横向%+.2fm", theta != null ? "原位姿" : "按行进方向", dTh, dLat));
        }
        if (fit[4] == 0) {
            // 实车放得下但带外扩放不下：不拦，Smac 按外扩判碰撞，大概率只能停到附近(容差 0.5m)再靠终点对位补
            if (note.length() > 0) note.append("；");
            note.append(String.format("车身四周余量不足 %.2fm(ros.nav2.footprint-padding)，Nav2 可能停不到正位",
                    mapController.getGlobalFootprintPadding()));
        }
        if (cur != null && mapController.staticMapPolygonHasOccupied(footprintPoly(cur[0], cur[1], cur[2], 0))) {
            // 不拦(车就停在这)，但这几乎必然让 Smac 从起点就动不了：多半是定位偏了或地图里有残影
            log.warn("[导航目标] ⚠ 车当前位姿的车身就压着地图障碍，Smac 起步会失败。先检查定位(手动设初始位姿)或地图残影");
        }

        abort("新目标覆盖");
        hw.resetReverseTracker();
        Mission m = new Mission(seq.incrementAndGet(), gx, gy, th, s, precise || s != 0);
        mission = m;
        log.info("[任务#{}] 目标(雷达) ({}, {}) 朝向 {}°{}{}{} {}", m.id, fmt(gx), fmt(gy), fmt(Math.toDegrees(th)),
                theta == null ? "(自动)" : "", s != 0 ? " 侧向对位 " + fmt(s) + "m" : "",
                m.precise ? " 精确停车" : " 非固定(到附近即停)", note);
        if (note.length() > 0) status(m, "navigating", note.toString(), true);
        worker.submit(() -> runStart(m));

        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("x", gx);
        r.put("y", gy);
        r.put("theta", th);
        r.put("note", note.toString());
        return r;
    }


    /** 雷达位姿下车身(四周外扩 pad)的四个角，map 系 */
    private static double[][] footprintPoly(double x, double y, double th, double pad) {
        double[] pose = {x, y, th};
        double f = FOOTPRINT_FRONT_M + pad, b = FOOTPRINT_BACK_M - pad;
        double l = FOOTPRINT_LEFT_M + pad,  r = -(FOOTPRINT_RIGHT_M + pad);
        return new double[][]{bodyToMap(pose, f, r), bodyToMap(pose, f, l), bodyToMap(pose, b, l), bodyToMap(pose, b, r)};
    }

    /** 车身在此位姿(含侧向对位预备位)是否放得下 */
    private boolean goalFits(double x, double y, double th, double side, double pad) {
        if (mapController.staticMapPolygonHasOccupied(footprintPoly(x, y, th, pad))) return false;
        return side == 0 || !mapController.staticMapPolygonHasOccupied(
                footprintPoly(x + side * Math.sin(th), y - side * Math.cos(th), th, pad));
    }

    /**
     * 在 (x,y,base) 附近找放得下车身的位姿，调整量越小越优先；先要求带外扩放得下，找不到再只要求实车放得下。
     * 指定朝向：朝向 ±6°(1°步) × 横向 ±0.2m(0.05 步)；自动朝向：全圆每 3° × 横向 0/±0.1/±0.2。
     * @return {x, y, theta, 横向调整m, 1=带外扩放得下/0=仅实车放得下}，都放不下返回 null
     */
    private double[] findGoalFit(double x, double y, double base, double side, boolean fixedHeading) {
        List<double[]> cands = new java.util.ArrayList<>();   // {dThetaDeg, dLat, score}
        double[] lats = fixedHeading ? new double[]{0, -0.05, 0.05, -0.1, 0.1, -0.15, 0.15, -0.2, 0.2}
                                     : new double[]{0, -0.1, 0.1, -0.2, 0.2};
        int maxDeg = fixedHeading ? 6 : 180, stepDeg = fixedHeading ? 1 : 3;
        for (int d = -maxDeg; d <= maxDeg; d += stepDeg) {
            for (double lat : lats) {
                // 1° 朝向 ≈ 0.05m 横向的代价，都往"改得最少"排
                cands.add(new double[]{d, lat, Math.abs(d) / (double) stepDeg + Math.abs(lat) / 0.05});
            }
        }
        cands.sort((a, b) -> Double.compare(a[2], b[2]));
        // 外扩和 Smac 用同一个值(MapController 写进 global_costmap 的 footprint_padding)
        for (double pad : new double[]{mapController.getGlobalFootprintPadding(), 0}) {
            for (double[] c : cands) {
                double th = wrap(base + Math.toRadians(c[0]));
                double gx = x - c[1] * Math.sin(th), gy = y + c[1] * Math.cos(th);   // 沿车身左向平移 lat
                if (goalFits(gx, gy, th, side, pad)) {
                    return new double[]{gx, gy, th, c[1], pad > 0 ? 1 : 0};
                }
            }
        }
        return null;
    }

    /** 取消任务：正在做的机动会在下一拍(≤50ms)停车退出 */
    public void abort(String why) {
        Mission m = mission;
        if (m != null && !m.aborted) {
            m.aborted = true;
            log.info("[任务#{}] 中止: {}", m.id, why);
        }
    }

    // ======================== 流程 ========================

    private void runStart(Mission m) {
        if (enabled) {
            try {
                double[] est = bridge.getEstimatedMapPose();
                double[] p = est != null ? est : bridge.getLastAmclPose();
                // 只看"目标朝向和当前车头差多少"，不看目标在不在身后 —— 在身后但朝向一样，倒过去就行
                if (p != null) {
                    double diff = Math.abs(wrap(m.gth - p[2]));
                    if (diff > Math.toRadians(preRotateMinDeg)) {
                        status(m, "pre-rotate", "目标朝向与车头相差 " + fmt(Math.toDegrees(diff)) + "°，尝试先原地转到目标朝向", true);
                        rotateTo(m, m.gth, false);
                    }
                }
            } catch (Refused e) {
                status(m, "pre-rotate", "不原地转(" + e.getMessage() + ")，交给 Nav2 走弧线/倒车调头", true);
            } catch (Aborted e) {
                return;
            } catch (Exception e) {
                // 已经动过车了还出错(模式反馈超时、方向相反等)，不能当没事继续交给 Nav2
                status(m, "failed", "起点旋转出错，任务终止: " + e.getMessage(), false);
                return;
            }
        }
        if (isStale(m)) return;
        sendGoal(m);
    }

    private void sendGoal(Mission m) {
        // 侧向对位：Nav2 先去"终点往反方向挪 side"的预备位，到了再平移过去
        double sx = m.gx + m.side * Math.sin(m.gth);
        double sy = m.gy - m.side * Math.cos(m.gth);
        final long sentMs = System.currentTimeMillis();
        final long sentNs = sentMs * 1_000_000L;
        m.waitingSinceNs = sentNs;
        navigation.sendNavigationGoal(sx, sy, m.gth);
        status(m, "navigating", m.side != 0
                ? String.format("Nav2 前往预备位 (%.2f, %.2f)，到达后平移 %.2fm", sx, sy, m.side)
                : "Nav2 导航中", true);
        timer.schedule(() -> checkPlanTimeout(m, sentMs, sentNs), planTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 发目标 planTimeoutMs 后仍没收到任何路径：取消 Nav2，诊断车身哪边被挡，能侧移就侧移后重发 */
    private void checkPlanTimeout(Mission m, long sentMs, long sentNs) {
        if (isStale(m) || m.waitingSinceNs != sentNs || lastPlanMs >= sentMs) return;
        m.waitingSinceNs = 0;   // 这次目标的终态(取消)不再处理，免得和下面的流程打架
        boolean cancelled = bridge.cancelAllNavGoals();
        log.warn("[任务#{}] 发目标 {}s 仍没有路径，判定规划失败，已{}取消 Nav2 目标", m.id, planTimeoutMs / 1000,
                cancelled ? "" : "尝试(调用未确认)");
        worker.submit(() -> {
            diagnoseStartCollision(m);
            if (enabled && corridorShift && !m.shiftTried) {
                runShiftRetry(m);
            } else {
                status(m, "failed", String.format("Nav2 %ds 内规划不出路径%s", planTimeoutMs / 1000,
                        m.shiftTried ? "(侧移后重试仍失败)" : ""), false);
            }
        });
    }

    private void onNavTerminal(int status, long stampNs) {
        Mission m = mission;
        if (m == null || m.aborted || m.waitingSinceNs == 0) return;
        // status_list 会重播上一个目标的终态；比本次发目标还早(留 2s 时钟余量)的一律不是我们的
        if (stampNs < m.waitingSinceNs - 2_000_000_000L) return;
        m.waitingSinceNs = 0;

        if (status == 4) {
            if (enabled && ((goalAlign && m.precise) || m.side != 0)) {
                worker.submit(() -> runArrive(m));
            } else {
                status(m, "done", "到达目标", true);
            }
        } else if (status == 6) {
            diagnoseStartCollision(m);
            if (enabled && corridorShift && !m.shiftTried) {
                worker.submit(() -> runShiftRetry(m));
            } else {
                status(m, "failed", "Nav2 导航失败", false);
            }
        } else {
            status(m, "canceled", "Nav2 目标已取消", false);
        }
    }

    private void runArrive(Mission m) {
        try {
            sleep(m, settleMs);
            double[] p = freshMapPose(m, true);
            double yawErr = wrap(m.gth - p[2]);
            if (Math.abs(yawErr) > Math.toRadians(yawTolDeg)) {
                try {
                    status(m, "align", "终点朝向差 " + fmt(Math.toDegrees(yawErr)) + "°，原地旋转对正", true);
                    rotateTo(m, m.gth, true);
                    sleep(m, settleMs);
                    p = freshMapPose(m, true);
                } catch (Refused e) {
                    status(m, "align", "不做朝向对正: " + e.getMessage(), true);
                }
            }
            // 平移方向是按当前车身横向走的，朝向还差得多时横移会斜着走，宁可不移
            if (Math.abs(wrap(m.gth - p[2])) > Math.toRadians(5)) {
                status(m, "done", "到达，但朝向偏差 " + fmt(Math.toDegrees(wrap(m.gth - p[2])))
                        + "° > 5°，跳过横向对位", m.side == 0);
                return;
            }
            double dx = m.gx - p[0], dy = m.gy - p[1];
            double ex =  Math.cos(m.gth) * dx + Math.sin(m.gth) * dy;
            double ey = -Math.sin(m.gth) * dx + Math.cos(m.gth) * dy;
            if (Math.abs(ey) > lateralTolM) {
                status(m, "align", String.format("横向偏差 %.2fm(纵向 %.2fm 不处理)，平移对位", ey, ex), true);
                lateralMove(m, ey);
            }
            status(m, "done", "到达并完成对位", true);
        } catch (Refused e) {
            status(m, "done", "到达，对位未执行: " + e.getMessage(), m.side == 0);
        } catch (Aborted e) {
            // 被新目标/取消打断，什么都不用报
        } catch (Exception e) {
            status(m, "failed", "终点对位出错: " + e.getMessage(), false);
        }
    }

    private void runShiftRetry(Mission m) {
        m.shiftTried = true;
        try {
            sleep(m, settleMs);
            double[] p = freshMapPose(m, true);
            double left  = sideClearance(p, true);
            double right = sideClearance(p, false);
            if (Math.min(left, right) >= corridorTightM) {
                status(m, "failed", String.format("Nav2 导航失败；两侧净空 左%.2fm/右%.2fm 都不紧，不是侧向被挡，不侧移"
                        + "(若是车头/车尾贴着东西，侧移解决不了，需手动挪车)", left, right), false);
                return;
            }
            if (Math.abs(left - right) < lateralTolM * 2) {
                status(m, "failed", String.format("Nav2 导航失败；两侧净空 左%.2fm/右%.2fm 一样挤，往哪边移都没用", left, right), false);
                return;
            }
            // 往空的一侧移，移到挤的那侧留出 外扩+0.15m 就够(不强行居中：空的那侧可能很远，没必要一口气移很多)
            boolean toLeft = left > right;
            double need = mapController.getGlobalFootprintPadding() + 0.15 - Math.min(left, right);
            double dist = Math.min(corridorShiftMaxM, Math.max(0.10, need));
            double shift = toLeft ? dist : -dist;
            boolean blockedLeft = !toLeft;
            double blockedCloud = blockedLeft ? guard.getLeftClearanceM() : guard.getRightClearanceM();
            status(m, "shift", String.format("规划失败，%s侧被挡(左%.2fm/右%.2fm，被挡侧点云 %s)，向%s平移 %.2fm 后重试一次",
                    blockedLeft ? "左" : "右", left, right, fmtClear(blockedCloud), toLeft ? "左" : "右", dist), true);
            lateralMove(m, shift);
            if (isStale(m)) return;

            // 移完先停稳、让点云和代价地图跟上，再判断到底移开没有
            sleep(m, settleMs);
            double after = blockedLeft ? guard.getLeftClearanceM() : guard.getRightClearanceM();
            if (blockedCloud < 0.10 && after < 0.10) {
                // 移了 dist 米，被挡那侧点云净空还是 0 → 那些点跟着车走，是车身自己的点(自身点过滤箱没罩住)
                status(m, "failed", String.format("平移 %.2fm 后%s侧点云净空仍是 %s：挡住的是车身自己的点(跟着车走)，侧移解决不了。"
                        + "检查机器人上 mapping.launch.py 里 livox_self_filter 的过滤箱是否已加余量", dist,
                        blockedLeft ? "左" : "右", fmtClear(after)), false);
                return;
            }
            // 代价地图里平移前标的旧障碍格还在，直接重发 Smac 大概率还说起点压障碍 —— 先清一次
            bridge.callService("/global_costmap/clear_entirely_global_costmap", "nav2_msgs/srv/ClearEntireCostmap",
                    new JsonObject(), 2000);
            bridge.callService("/local_costmap/clear_entirely_local_costmap", "nav2_msgs/srv/ClearEntireCostmap",
                    new JsonObject(), 2000);
            sleep(m, 1500);   // global_costmap 1Hz 刷新，等它按新位置重新标一遍
            sendGoal(m);
        } catch (Refused e) {
            status(m, "failed", "Nav2 导航失败；侧移未执行: " + e.getMessage(), false);
        } catch (Aborted e) {
            // 被打断
        } catch (Exception e) {
            status(m, "failed", "侧移出错: " + e.getMessage(), false);
        }
    }

    // ======================== 机动原子动作 ========================

    /** 原地旋转到 map 系朝向 targetYaw。闭环用 /Odometry 的偏航增量(连续、50Hz，AMCL 小角度不更新) */
    private void rotateTo(Mission m, double targetYaw, boolean freshPose) throws Exception {
        double[] p = freshMapPose(m, freshPose);
        double[] o0 = bridge.getOdomPose(1500);
        if (o0 == null) throw new Refused("/Odometry 没有新数据");
        double err0 = wrap(targetYaw - p[2]);
        double tol = Math.toRadians(yawTolDeg);
        if (Math.abs(err0) <= tol) return;
        if (!hw.isPlcWritable()) throw new Refused("PLC 未启用/未连接，机动不执行");

        Map<String, Object> rc = rotationSafety.check();
        if (!Boolean.TRUE.equals(rc.get("canRotate"))) throw new Refused("旋转判定: " + rc.get("message"));
        double[] c = bodyToMap(p, -rotationSafety.getCenterOffsetM(), 0);
        String why = mapController.staticMapBlockedCircle(c[0], c[1], rotationSafety.getRequiredRadiusM());
        if (why != null) throw new Refused("静态地图扫转圆(半径 " + fmt(rotationSafety.getRequiredRadiusM()) + "m)不空: " + why);

        log.info("[任务#{}] 原地旋转 {}°", m.id, fmt(Math.toDegrees(err0)));
        long t0 = System.currentTimeMillis();
        hw.beginManeuver();
        try {
            double turned = 0, prev = o0[2];
            int lastDir = 0, reversals = 0;
            while (true) {
                checkAlive(m, t0);
                hw.maneuverKeepalive();
                // 1s：/Odometry 走 rosbridge、和大点云挤同一条 WebSocket，偶尔晚到几百毫秒是正常的
                double[] o = bridge.getOdomPose(1000);
                if (o == null) throw new IllegalStateException("/Odometry 超过 1s 没更新，无法闭环");
                turned += wrap(o[2] - prev);
                prev = o[2];
                double rem = err0 - turned;
                if (Math.abs(rem) <= tol) break;
                // 第一段就往反方向转了 3° 以上 = 左右旋点位映射和预期相反，立刻停
                if (reversals == 0 && Math.abs(turned) > Math.toRadians(3) && Math.signum(turned) != Math.signum(err0)) {
                    throw new IllegalStateException("旋转方向与指令相反(V1001.5/V1001.6 映射需现场核对)");
                }
                int dir = rem > 0 ? +1 : -1;               // +1 = 左旋 = 偏航增大
                if (lastDir != 0 && dir != lastDir && ++reversals > 2) {
                    log.warn("[任务#{}] 旋转来回过冲 {} 次，停在剩余 {}°", m.id, reversals, fmt(Math.toDegrees(rem)));
                    break;
                }
                lastDir = dir;
                int rpm = Math.abs(rem) < Math.toRadians(rotateSlowDeg) ? rotateSlowRpm : rotateRpm;
                hw.maneuverDrive(RobotHardwareService.ManeuverKind.ROTATE, dir, rpm);
                Thread.sleep(50);
            }
        } finally {
            hw.endManeuver();
        }
    }

    /** 沿车身横向平移 dyLeft 米(左正)。闭环用 /Odometry 位移在起始车身系的横向分量 */
    private void lateralMove(Mission m, double dyLeft) throws Exception {
        if (Math.abs(dyLeft) <= lateralTolM) return;
        if (Math.abs(dyLeft) > lateralMaxM) {
            throw new Refused(String.format("需平移 %.2fm 超过单次上限 %.2fm", dyLeft, lateralMaxM));
        }
        if (!hw.isPlcWritable()) throw new Refused("PLC 未启用/未连接，机动不执行");
        double[] p = freshMapPose(m, true);
        double[] o0 = bridge.getOdomPose(1500);
        if (o0 == null) throw new Refused("/Odometry 没有新数据");
        boolean toLeft = dyLeft > 0;
        double need = Math.abs(dyLeft) + lateralMarginM;

        // ① 点云：看得见的那段侧边外 need 米内不能有东西
        long age = guard.getAgeMs();
        if (age < 0 || age > 1000) throw new Refused("侧向点云判定数据过期(" + age + "ms)");
        double seen = toLeft ? guard.getLeftClearanceM() : guard.getRightClearanceM();
        if (seen < need) throw new Refused(String.format("%s侧点云净空 %.2fm < 需要 %.2fm", toLeft ? "左" : "右", seen, need));

        // ② 静态地图：整条车身长度的扫掠带(车身后段点云看不到，靠它兜)
        double edge  = toLeft ? FOOTPRINT_LEFT_M : -FOOTPRINT_RIGHT_M;
        double outer = edge + (toLeft ? need : -need);
        double[][] strip = {
                bodyToMap(p, FOOTPRINT_FRONT_M, edge), bodyToMap(p, FOOTPRINT_FRONT_M, outer),
                bodyToMap(p, FOOTPRINT_BACK_M, outer), bodyToMap(p, FOOTPRINT_BACK_M, edge)};
        String why = mapController.staticMapBlockedPolygon(strip);
        if (why != null) throw new Refused("静态地图平移扫掠带不空: " + why);

        log.info("[任务#{}] 平移 {}{}m", m.id, toLeft ? "向左 " : "向右 ", fmt(Math.abs(dyLeft)));
        long t0 = System.currentTimeMillis();
        hw.beginManeuver();
        try {
            int lastDir = 0, reversals = 0;
            while (true) {
                checkAlive(m, t0);
                hw.maneuverKeepalive();
                // 1s：/Odometry 走 rosbridge、和大点云挤同一条 WebSocket，偶尔晚到几百毫秒是正常的
                double[] o = bridge.getOdomPose(1000);
                if (o == null) throw new IllegalStateException("/Odometry 超过 1s 没更新，无法闭环");
                double wx = o[0] - o0[0], wy = o[1] - o0[1];
                double moved = -Math.sin(o0[2]) * wx + Math.cos(o0[2]) * wy;   // 起始车身系横向位移，左正
                if (Math.abs(wrap(o[2] - o0[2])) > Math.toRadians(5)) {
                    throw new IllegalStateException("平移中车头偏转超过 5°，停止");
                }
                double rem = dyLeft - moved;
                if (Math.abs(rem) <= lateralTolM) break;
                if (reversals == 0 && Math.abs(moved) > 0.05 && Math.signum(moved) != Math.signum(dyLeft)) {
                    throw new IllegalStateException("平移方向与指令相反(平移模式 V1001.5=左/V1001.6=右 需现场核对)");
                }
                // 移动中持续盯点云：侧边到障碍小于余量立刻停
                double live = rem > 0 ? guard.getLeftClearanceM() : guard.getRightClearanceM();
                long liveAge = guard.getAgeMs();
                if (liveAge < 0 || liveAge > 1000) throw new IllegalStateException("平移中点云判定中断");
                if (live < lateralMarginM) {
                    throw new IllegalStateException(String.format("平移中侧边净空 %.2fm < 余量 %.2fm，停止", live, lateralMarginM));
                }
                // 现场确认(2026-09-15)：平移模式 V1001.5("前进")=向左，V1001.6("后退")=向右
                int dir = rem > 0 ? +1 : -1;               // 需要向左 → V1001.5
                if (lastDir != 0 && dir != lastDir && ++reversals > 2) {
                    log.warn("[任务#{}] 平移来回过冲，停在剩余 {}m", m.id, fmt(rem));
                    break;
                }
                lastDir = dir;
                int rpm = Math.abs(rem) < lateralSlowM ? lateralSlowRpm : lateralRpm;
                hw.maneuverDrive(RobotHardwareService.ManeuverKind.LATERAL, dir, rpm);
                Thread.sleep(50);
            }
        } finally {
            hw.endManeuver();
        }
    }

    // ======================== 工具 ========================

    /**
     * 车当前在 map 系的位姿(雷达点)。
     * AMCL 只在车动过 update_min_d/a 才出新值，车刚停时缓存里可能是 0.25m 之前的位置 ——
     * 对位要精确就先调 /request_nomotion_update 逼它就地更新一次，等到新值再用。
     */
    private double[] freshMapPose(Mission m, boolean requireFresh) throws Exception {
        if (requireFresh) {
            // ⚠ 调一次不一定出位姿：AMCL 每 resample_interval(=3) 次滤波更新才重采样并发布 /amcl_pose，
            //   只 nomotion 一次常常等不到(现场报"3s 内没有给出新位姿")。每 400ms 催一次，最多等 5s
            long before = System.currentTimeMillis(), lastKick = 0;
            while (bridge.getLastAmclPoseMs() <= before) {
                checkAlive(m, before);
                long now = System.currentTimeMillis();
                if (now - before > 3000) {
                    // 催不出新位姿时退回"上次 AMCL + 里程计推算"，别因为这个直接放弃机动
                    if (bridge.getOdomPose(1500) == null) {
                        // Java 这边收不到 ≠ fast_lio 挂了：更常见的是 rosbridge 订阅丢了(ROS2BridgeService 会自动重订阅)
                        throw new Refused("Java 超过 1.5s 没收到 /Odometry。先在机器人上 ros2 topic hz /Odometry："
                                + "有频率 = rosbridge 订阅丢了(几秒内会自动重订阅，稍后重试)；没频率 = fast_lio 没在跑");
                    }
                    double[] est = bridge.getEstimatedMapPose();
                    if (est == null) {
                        throw new Refused("AMCL 没有给出新位姿，且没有可推算的历史位姿(先设置初始点位)");
                    }
                    log.info("[任务#{}] AMCL 3s 没出新位姿(fast_lio 正常)，用 上次AMCL+里程计 推算位姿 ({}, {}, {}°)",
                            m.id, fmt(est[0]), fmt(est[1]), fmt(Math.toDegrees(est[2])));
                    return est;
                }
                if (now - lastKick >= 400) {
                    bridge.callService("/request_nomotion_update", "std_srvs/srv/Empty", new JsonObject(), 1000);
                    lastKick = System.currentTimeMillis();
                }
                Thread.sleep(50);
            }
        }
        // 优先用"AMCL + 里程计推算"：车停着时 AMCL 缓存可能是停车前 0.25m 处的旧值
        double[] est = bridge.getEstimatedMapPose();
        double[] p = est != null ? est : bridge.getLastAmclPose();
        if (p == null) throw new Refused("还没有 AMCL 位姿");
        return p;
    }

    /** 车身某一侧(沿整车长度取前/中/后三处)到障碍的净空：点云与静态地图取小 */
    private double sideClearance(double[] p, boolean left) {
        double cloud = left ? guard.getLeftClearanceM() : guard.getRightClearanceM();
        double edge  = left ? FOOTPRINT_LEFT_M + 0.05 : -(FOOTPRINT_RIGHT_M + 0.05);
        double ux = -Math.sin(p[2]) * (left ? 1 : -1), uy = Math.cos(p[2]) * (left ? 1 : -1);
        double map = Double.MAX_VALUE;
        for (double bx : new double[]{FOOTPRINT_FRONT_M, (FOOTPRINT_FRONT_M + FOOTPRINT_BACK_M) / 2, FOOTPRINT_BACK_M}) {
            double[] s = bodyToMap(p, bx, edge);
            map = Math.min(map, mapController.staticMapFreeDistance(s[0], s[1], ux, uy, 3.0));
        }
        return Math.min(cloud, map);
    }

    private static String fmtClear(double v) {
        return v == Double.MAX_VALUE ? "3m 内无" : String.format("%.2fm", v);
    }

    private static double[] bodyToMap(double[] pose, double bx, double by) {
        double c = Math.cos(pose[2]), s = Math.sin(pose[2]);
        return new double[]{pose[0] + c * bx - s * by, pose[1] + s * bx + c * by};
    }

    private void checkAlive(Mission m, long t0) {
        if (m.aborted || mission != m) throw new Aborted("任务已中止");
        if (hw.isEmergencyStopActive()) throw new IllegalStateException("急停触发");
        if (System.currentTimeMillis() - t0 > timeoutMs) throw new IllegalStateException("机动超时 " + timeoutMs + "ms");
    }

    private void sleep(Mission m, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (m.aborted || mission != m) throw new Aborted("任务已中止");
            Thread.sleep(50);
        }
    }

    private boolean isStale(Mission m) {
        return m.aborted || mission != m;
    }

    /**
     * Nav2 失败时查一下"车现在停的地方车身(含外扩)压没压静态地图障碍"。
     * Nav2 报 `Starting point in lethal space` 时起点就规划不出来，controller 一条速度都不会发，
     * 前端只会说"0 条控制指令、查 TF"，完全看不出是车身贴着东西。这里把压到的格子推给前端画在车身上。
     * 静态地图不压 → 说明是雷达实时看到的东西贴着车身(料堆/人/车体自身点没滤干净)。
     */
    private void diagnoseStartCollision(Mission m) {
        double[] est = bridge.getEstimatedMapPose();
        double[] p = est != null ? est : bridge.getLastAmclPose();
        if (p == null) return;
        double pad = mapController.getGlobalFootprintPadding();
        List<double[]> hits = mapController.staticMapOccupiedPoints(footprintPoly(p[0], p[1], p[2], pad), 400);
        if (hits.isEmpty()) {
            log.info("[任务#{}] 失败诊断: 起点车身(外扩 {}m)不压静态地图。若 planner 报 Starting point in lethal space，"
                    + "是实时点云障碍贴着车身，看车周围 {}m 内有没有东西", m.id, pad, pad);
            return;
        }
        Map<String, Object> extra = new java.util.LinkedHashMap<>();
        extra.put("x", p[0]); extra.put("y", p[1]); extra.put("theta", p[2]); extra.put("hits", hits);
        status(m, "failed", String.format("车当前位置车身(含外扩 %.2fm)压着地图障碍 %d 格(红点)，Nav2 从起点就规划不出来。"
                + "可能是定位偏了(重设初始点位)、车真的贴着障碍(手动挪开)，或地图残影(擦除地图)", pad, hits.size()), false, extra);
    }

    private void status(Mission m, String phase, String msg, boolean ok) {
        status(m, phase, msg, ok, null);
    }

    private void status(Mission m, String phase, String msg, boolean ok, Map<String, Object> extra) {
        if (ok) log.info("[任务#{}] {} | {}", m.id, phase, msg);
        else    log.warn("[任务#{}] {} | {}", m.id, phase, msg);
        JSONObject d = new JSONObject();
        if (extra != null) d.putAll(extra);
        d.put("missionId", m.id);
        d.put("phase", phase);
        d.put("message", msg);
        d.put("ok", ok);
        push.pushToAll("maneuver_status", d);
    }

    private static double wrap(double a) {
        while (a >  Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
