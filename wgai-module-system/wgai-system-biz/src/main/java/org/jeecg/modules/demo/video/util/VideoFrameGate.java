package org.jeecg.modules.demo.video.util;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.Frame;

/** Per-reader state; called on the decode thread before cloning or sampling. */
@Slf4j
public final class VideoFrameGate {
    private final String taskId;
    private boolean waitingForKeyFrame = true;
    private long waitStartedNanos = System.nanoTime();
    private long skipped;

    public VideoFrameGate(String taskId) { this.taskId = taskId; }

    public void reset() {
        waitingForKeyFrame = true;
        waitStartedNanos = System.nanoTime();
        skipped = 0;
    }

    public boolean accept(Frame frame) {
        if (frame == null || frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
            if (!waitingForKeyFrame) reset();
            return false;
        }
        if (waitingForKeyFrame) {
            if (!frame.keyFrame) {
                skipped++;
                if (skipped == 1 || skipped % 100 == 0) {
                    log.info("[等待视频关键帧] taskId={} skipped={}", taskId, skipped);
                }
                return false;
            }
            waitingForKeyFrame = false;
            log.info("[视频关键帧就绪] taskId={} skipped={} width={} height={}",
                    taskId, skipped, frame.imageWidth, frame.imageHeight);
        }
        return true;
    }

    public boolean isWaitingTooLong() {
        return waitingForKeyFrame && System.nanoTime() - waitStartedNanos > 30000000000L;
    }
}
