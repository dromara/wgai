package org.jeecg.modules.demo.video.util;

import java.util.concurrent.atomic.AtomicLong;

/** Timing starts when the decoder returns a frame, not at the camera's physical exposure time. */
public final class RecognitionTrace {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<RecognitionTrace> CURRENT = new ThreadLocal<>();
    public final String taskId;
    public final long frameId;
    public final long decodedNanos;

    private RecognitionTrace(String taskId, long decodedNanos) {
        this.taskId = taskId;
        this.decodedNanos = decodedNanos;
        this.frameId = SEQUENCE.incrementAndGet();
    }

    public static RecognitionTrace begin(String taskId, long decodedNanos) {
        RecognitionTrace trace = new RecognitionTrace(taskId, decodedNanos);
        CURRENT.set(trace);
        return trace;
    }
    public static RecognitionTrace current() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
    public long elapsedMillis() { return (System.nanoTime() - decodedNanos) / 1000000; }
}
