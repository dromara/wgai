package org.jeecg.modules.demo.track;

/**
 * @author wggg
 * @date 2026/3/25 9:02
 */
public class Track {

    public enum State { TENTATIVE, CONFIRMED, LOST }

    // ★ 原来用静态自增 ID，现在改用 IdPool
    private static final IdPool ID_POOL = new IdPool();

    public final int id;
    private KalmanFilter kf;
    private State state;
    private int hitStreak;
    private int missStreak;
    private double[] lastBbox;
    private int classId;
    private float conf;

    public static int MIN_HITS   = 1;   // 出现即追踪
    public static int MAX_MISSED = 10;  // 丢失多少帧后清除

    public Track(double[] bbox, int classId, float conf) {
        // ★ 从 ID 池申请最小可用 ID
        this.id        = ID_POOL.acquire();
        this.kf        = new KalmanFilter(bbox);
        this.state     = State.TENTATIVE;
        this.hitStreak = 1;
        this.missStreak = 0;
        this.lastBbox  = bbox.clone();
        this.classId   = classId;
        this.conf      = conf;
    }

    public double[] predict() {
        lastBbox = kf.predict();
        return lastBbox;
    }

    public void update(double[] bbox, int classId, float conf) {
        kf.update(bbox);
        lastBbox     = bbox.clone();
        this.classId = classId;
        this.conf    = conf;
        hitStreak++;
        missStreak = 0;
        if (state == State.TENTATIVE && hitStreak >= MIN_HITS) {
            state = State.CONFIRMED;
        }
    }

    public void markMissed() {
        missStreak++;
        hitStreak = 0;
        if (missStreak > MAX_MISSED || state == State.TENTATIVE) {
            state = State.LOST;
            // ★ 目标确认消失，回收 ID 给下一个进来的人用
            ID_POOL.release(this.id);
        }
    }

    public boolean isLost()      { return state == State.LOST; }
    public boolean isConfirmed() { return state == State.CONFIRMED; }
    public double[] getBbox()    { return lastBbox; }
    public int   getClassId()    { return classId; }
    public float getConf()       { return conf; }
    public int   getId()         { return id; }
    public State getState()      { return state; }
}

