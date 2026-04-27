package org.jeecg.modules.demo.track;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wggg
 * @date 2026/3/25 9:04
 */
@Service
public class TrackingService {

    private final ByteTracker tracker = new ByteTracker();

    /**
     * @param onnxOutput  你的 ONNX 模型输出，格式：[[x1,y1,x2,y2,conf,cls], ...]
     * @return 带 track_id 的追踪结果
     */
    public List<ByteTracker.TrackResult> processFrame(float[][] onnxOutput) {
        List<ByteTracker.Detection> dets = new ArrayList<>();
        for (float[] row : onnxOutput) {
            // 对应你模型的输出格式，按实际调整字段顺序
            dets.add(new ByteTracker.Detection(row[0], row[1], row[2], row[3], row[4], (int)row[5]));
        }
        return tracker.update(dets);
    }
}
