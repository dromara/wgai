package org.jeecg.modules.demo.track;

import java.util.Arrays;
import java.util.List;



/**
 * @author wggg
 * @date 2026/3/25 9:10
 */
public class test {

    public static void main(String[] args) {
        ByteTracker tracker = new ByteTracker();

        // 模拟第1帧：两个目标
        List<ByteTracker.Detection> frame1 = Arrays.asList(
                new ByteTracker.Detection(100, 100, 200, 200, 0.9f, 0),
                new ByteTracker.Detection(300, 300, 400, 400, 0.8f, 0)
        );

        // 模拟第2帧：目标稍微移动了
        List<ByteTracker.Detection> frame2 = Arrays.asList(
                new ByteTracker.Detection(105, 102, 205, 202, 0.88f, 0),
                new ByteTracker.Detection(298, 302, 398, 402, 0.85f, 0)
        );

        // 逐帧调用
        for (int i = 0; i < 5; i++) {
            List<ByteTracker.Detection> dets = (i % 2 == 0) ? frame1 : frame2;
            List<ByteTracker.TrackResult> results = tracker.update(dets);

            System.out.println("=== 第 " + (i+1) + " 帧 ===");
            for (ByteTracker.TrackResult r : results) {
                System.out.printf("  ID=%d  cls=%d  conf=%.2f  bbox=[%.0f,%.0f,%.0f,%.0f]%n",
                        r.trackId, r.classId, r.conf, r.x1, r.y1, r.x2, r.y2);
            }
        }
    }
}
