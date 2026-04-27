package org.jeecg.modules.demo.track;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author wggg
 * @date 2026/3/25 9:03
 * 简化版 ByteTrack：
 * 高置信检测 → 匹配已有轨迹 → 低置信检测补救匹配 → 管理轨迹生命周期
 */
public class ByteTracker {

    // ---- 可调参数 ----
    private static final float HIGH_THRESH  = 0.6f;   // 高置信度阈值
    private static final float LOW_THRESH   = 0.1f;   // 低置信度阈值（ByteTrack 特色）
    private static final float MATCH_THRESH = 0.5f;   // IoU 匹配阈值（越小越严）

    private final List<Track> tracks = new ArrayList<>();

    public static class Detection {
        public float x1, y1, x2, y2, conf;
        public int cls;

        public Detection(float x1, float y1, float x2, float y2, float conf, int cls) {
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
            this.conf = conf; this.cls = cls;
        }

        /** 转为 [cx, cy, w, h] */
        public double[] toCxCyWH() {
            double w = x2 - x1, h = y2 - y1;
            return new double[]{x1 + w / 2, y1 + h / 2, w, h};
        }
    }

    public static class TrackResult {
        public int trackId, classId;
        public float x1, y1, x2, y2, conf;
    }

    /**
     * 每帧调用一次
     * @param detections ONNX 检测结果列表
     * @return 当前帧所有已确认的轨迹
     */
    public List<TrackResult> update(List<Detection> detections) {

        // 1. Kalman 预测所有现有轨迹
        for (Track t : tracks) t.predict();

        // 2. 按置信度分组
        List<Detection> highDets = new ArrayList<>(), lowDets = new ArrayList<>();
        for (Detection d : detections) {
            if (d.conf >= HIGH_THRESH) highDets.add(d);
            else if (d.conf >= LOW_THRESH) lowDets.add(d);
        }

        // 3. 高置信检测 匹配已有轨迹
        List<Track> unmatched = new ArrayList<>(tracks);
        Set<Integer> matchedDetIdx = new HashSet<>();

        if (!highDets.isEmpty() && !unmatched.isEmpty()) {
            double[][] iouCost = buildIoUCost(unmatched, highDets);
            int[] assignment = HungarianAlgorithm.assign(iouCost);

            List<Track> stillUnmatched = new ArrayList<>();
            for (int i = 0; i < assignment.length; i++) {
                int j = assignment[i];
                if (j >= 0 && j < highDets.size()
                        && iouCost[i][j] <= (1.0 - MATCH_THRESH)) {
                    Detection d = highDets.get(j);
                    unmatched.get(i).update(d.toCxCyWH(), d.cls, d.conf);
                    matchedDetIdx.add(j);
                } else {
                    stillUnmatched.add(unmatched.get(i));
                }
            }
            unmatched = stillUnmatched;
        }

        // 4. 低置信检测 再次尝试匹配剩余轨迹（ByteTrack 核心）
        if (!lowDets.isEmpty() && !unmatched.isEmpty()) {
            double[][] iouCost = buildIoUCost(unmatched, lowDets);
            int[] assignment = HungarianAlgorithm.assign(iouCost);
            List<Track> stillUnmatched = new ArrayList<>();
            for (int i = 0; i < assignment.length; i++) {
                int j = assignment[i];
                if (j >= 0 && j < lowDets.size()
                        && iouCost[i][j] <= (1.0 - MATCH_THRESH)) {
                    Detection d = lowDets.get(j);
                    unmatched.get(i).update(d.toCxCyWH(), d.cls, d.conf);
                } else {
                    stillUnmatched.add(unmatched.get(i));
                }
            }
            unmatched = stillUnmatched;
        }

        // 5. 未匹配轨迹：标记丢失
        for (Track t : unmatched) t.markMissed();

        // 6. 未匹配高置信检测：新建轨迹
        for (int j = 0; j < highDets.size(); j++) {
            if (!matchedDetIdx.contains(j)) {
                Detection d = highDets.get(j);
                tracks.add(new Track(d.toCxCyWH(), d.cls, d.conf));
            }
        }

        // 7. 删除 LOST 轨迹
        tracks.removeIf(Track::isLost);

        // 8. 返回已确认轨迹结果
        List<TrackResult> results = new ArrayList<>();
        for (Track t : tracks) {
            if (!t.isConfirmed()) continue;
            double[] bbox = t.getBbox();  // [cx, cy, w, h]
            TrackResult r = new TrackResult();
            r.trackId  = t.getId();
            r.classId  = t.getClassId();
            r.conf     = t.getConf();
            r.x1 = (float)(bbox[0] - bbox[2] / 2);
            r.y1 = (float)(bbox[1] - bbox[3] / 2);
            r.x2 = (float)(bbox[0] + bbox[2] / 2);
            r.y2 = (float)(bbox[1] + bbox[3] / 2);
            results.add(r);
        }
        return results;
    }

    // ---- 构建 IoU 代价矩阵（1 - IoU，值越小越好匹配）----
    private double[][] buildIoUCost(List<Track> trackList, List<Detection> detList) {
        double[][] cost = new double[trackList.size()][detList.size()];
        for (int i = 0; i < trackList.size(); i++) {
            double[] b = trackList.get(i).getBbox();  // [cx,cy,w,h]
            float tx1 = (float)(b[0]-b[2]/2), ty1 = (float)(b[1]-b[3]/2);
            float tx2 = (float)(b[0]+b[2]/2), ty2 = (float)(b[1]+b[3]/2);
            for (int j = 0; j < detList.size(); j++) {
                Detection d = detList.get(j);
                cost[i][j] = 1.0 - iou(tx1, ty1, tx2, ty2, d.x1, d.y1, d.x2, d.y2);
            }
        }
        return cost;
    }

    private double iou(float ax1, float ay1, float ax2, float ay2,
                       float bx1, float by1, float bx2, float by2) {
        float ix1 = Math.max(ax1, bx1), iy1 = Math.max(ay1, by1);
        float ix2 = Math.min(ax2, bx2), iy2 = Math.min(ay2, by2);
        float inter = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
        if (inter == 0) return 0;
        float areaA = (ax2-ax1)*(ay2-ay1), areaB = (bx2-bx1)*(by2-by1);
        return inter / (areaA + areaB - inter);
    }
}