package org.jeecg.modules.demo.video.util;

/** Estimates accumulated lag from PTS progression; does not claim absolute camera/network latency. */
public final class StreamLagTracker {
    private boolean initialized;
    private long basePtsUs, baseNanos, lastPtsUs;

    public long observe(long ptsUs, long nowNanos) {
        if (ptsUs <= 0) return 0;
        if (!initialized || ptsUs < lastPtsUs) {
            initialized = true; basePtsUs = ptsUs; baseNanos = nowNanos;
        }
        lastPtsUs = ptsUs;
        long lagMs = (nowNanos - baseNanos) / 1000000 - (ptsUs - basePtsUs) / 1000;
        if (lagMs < 0) {
            basePtsUs = ptsUs; baseNanos = nowNanos;
            return 0;
        }
        return lagMs;
    }

    public void reset() { initialized = false; }
}
