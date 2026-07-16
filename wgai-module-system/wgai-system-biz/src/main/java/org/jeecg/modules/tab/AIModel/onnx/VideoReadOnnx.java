package org.jeecg.modules.tab.AIModel.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.track.ByteTracker;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.utils.Converters;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jeecg.modules.demo.video.util.identifyTypeNewOnnx.letterboxResize;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColorsVue;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;
import static org.jeecg.modules.tab.AIModel.onnx.VideoReadOnnxPose.isBoundingBoxInShapeData;

/**
 * @author wggg
 * @date 2025/10/31 15:16
 *
 * v3 重构说明：
 *
 * 根本原因分析（来自日志 trackId 41-47 全是同一人）：
 *   旧设计依赖 ByteTracker.TrackResult 的坐标存快照，但 ByteTracker 的 Kalman 滤波器
 *   在 300ms 大帧间隔下预测严重漂移，快照位置与下帧实际检测位置偏差被放大，
 *   导致 findLostMatch 在 MAX_MATCH_DIST=150px 附近频繁 miss（实际移动 136-180px）。
 *
 * v3 解法：
 *   1. PermanentIdMapper 完全不再依赖 ByteTracker 的坐标，改为直接使用检测框坐标
 *   2. 新方法 assignPermIds(List<DetCache>) 用贪心最近邻（Greedy-NN）一次完成全帧分配：
 *      - 计算所有 (检测框, 历史快照) 对的欧式距离
 *      - 按距离升序排序，优先匹配最近的对（近似 Hungarian）
 *      - 已认领的检测框/快照跳过（防止多对多争抢）
 *   3. MAX_MATCH_DIST 提升到 300px，dynThresh = 0.5×max(w,h)（对 500px 高的人≈250px）
 *   4. 快照存储真实检测坐标，而非 Kalman 预测坐标
 *   5. ByteTracker 仍运行（保留以备扩展），但不参与 permId 分配
 */
@Slf4j
public class VideoReadOnnx implements Runnable {

    private RedisTemplate redisTemplate;
    public Integer TARGET_FRAME_INTERVAL = 300;
    public String videoUrl;
    public String userId;
    private volatile long lastFrameTime = 0;
    public String namesUrl;
    public String cfgUrl;
    public String weightUrl;
    public WebSocket webSocket;
    public TabAiModelBund tabAiModelBund;
    public TabAudioDevice tabAudioDevice;
    NetPush netpush;
    String uuid;
    private String codecName;
    private static final long STREAM_PTS_BASE_UNSET = Long.MIN_VALUE;
    private volatile long streamPtsBaseMs = STREAM_PTS_BASE_UNSET;
    private final AtomicInteger startupProcessLogCounter = new AtomicInteger();
    private final AtomicInteger emptyDetectionLogCounter = new AtomicInteger();

    private final Java2DFrameConverter converter = new Java2DFrameConverter();

    public VideoReadOnnx(NetPush netpush, TabAudioDevice tabAudioDevice,
                         TabAiModelBund tabAiModelBund, String videoUrl,
                         RedisTemplate redisTemplate, String userId,
                         String namesUrl, WebSocket webSocket) {
        this.videoUrl = videoUrl;
        this.redisTemplate = redisTemplate;
        this.userId = userId;
        this.namesUrl = namesUrl;
        this.webSocket = webSocket;
        this.netpush = netpush;
        this.tabAiModelBund = tabAiModelBund;
        this.tabAudioDevice = tabAudioDevice;
    }

    // =========================================================================
    // PermanentIdMapper v3
    //
    // 设计原则：
    //   - 完全使用检测框坐标（非 Kalman 预测坐标）存快照和做匹配
    //   - 贪心最近邻分配，O(n² + n²log n)，n 一般 < 50，完全可接受
    //   - MAX_MATCH_DIST=300px，动态阈值 0.5×max(w,h) ≤ 300px
    //     对 500px 高人体 → 250px；对 200px 高小人 → 100px
    // =========================================================================
    private static class PermanentIdMapper {

        private final AtomicInteger permIdCounter = new AtomicInteger(1);

        /** permId → snapshot，LinkedHashMap 保持插入顺序（遍历稳定）*/
        private final Map<Integer, LostSnapshot> recentLost = new LinkedHashMap<>();

        /** 快照保留帧数：20 × 300ms = 6s */
        private static final int   LOST_KEEP_FRAMES = 20;
        private static final int   BRIDGE_MISSING_FRAMES = 3;
        /** 动态阈值基础值 */
        private static final float BASE_MATCH_DIST  = 80f;
        /** 动态阈值系数：max(w,h) × DYNAMIC_FACTOR，适应近大远小 */
        private static final float DYNAMIC_FACTOR   = 0.5f;
        /** 动态阈值硬上限，防止超远程误匹配 */
        private static final float MAX_MATCH_DIST   = 300f;

        static class LostSnapshot {
            int   permId;
            float cx, cy, w, h;
            int   classId;
            float conf;
            int   framesLost;
        }

        static class LostRender {
            int permId;
            BoundingBox box;
            int classId;
            float conf;
            int framesLost;
        }

        /**
         * 核心方法：一次完成整帧的 permId 分配。
         *
         * 算法：
         *   1. 老化快照
         *   2. 构建 (检测索引, 快照permId, 距离) 候选对，按距离排序
         *   3. 贪心分配：距离最小的对优先认领，已认领的跳过
         *   4. 未匹配的检测 → 新 permId
         *   5. 用检测框坐标更新快照
         *
         * @param cache NMS 后的检测框列表
         * @return 与 cache 同索引的 permId 列表
         */
        List<Integer> assignPermIds(List<DetCache> cache) {
            // ── 1. 老化所有快照 ────────────────────────────────────────────
            recentLost.values().removeIf(s -> ++s.framesLost > LOST_KEEP_FRAMES);

            int n = cache.size();
            int[] result = new int[n];
            Arrays.fill(result, -1);

            // ── 2. 构建候选对（检测 i ↔ 快照 permId，距离 < 动态阈值）──────
            if (!recentLost.isEmpty()) {
                // [detIdx, permId, dist*1000（整数，用于排序）]
                List<int[]> candidates = new ArrayList<>();

                for (int i = 0; i < n; i++) {
                    DetCache c   = cache.get(i);
                    float    cx  = (float)(c.box.x + c.box.width  / 2);
                    float    cy  = (float)(c.box.y + c.box.height / 2);
                    float    w   = (float) c.box.width;
                    float    h   = (float) c.box.height;
                    // 动态阈值：目标越大允许偏移越多；硬上限 300px
                    float thresh = Math.min(MAX_MATCH_DIST,
                            Math.max(BASE_MATCH_DIST, Math.max(w, h) * DYNAMIC_FACTOR));

                    for (LostSnapshot s : recentLost.values()) {
                        float dist = (float) Math.hypot(cx - s.cx, cy - s.cy);
                        if (dist < thresh) {
                            candidates.add(new int[]{i, s.permId, (int)(dist * 1000)});
                        }
                    }
                }

                // ── 3. 按距离升序排序，贪心认领 ───────────────────────────
                candidates.sort((a, b) -> a[2] - b[2]);

                Set<Integer> claimedDets  = new HashSet<>();
                Set<Integer> claimedPerms = new HashSet<>();

                for (int[] cand : candidates) {
                    int di     = cand[0];
                    int permId = cand[1];
                    if (claimedDets.contains(di) || claimedPerms.contains(permId)) continue;
                    result[di] = permId;
                    claimedDets.add(di);
                    claimedPerms.add(permId);
                    log.debug("[重用ID] det[{}] cx={} → permId={} dist={}px",
                            di,
                            (int)(cache.get(di).box.x + cache.get(di).box.width / 2),
                            permId, cand[2] / 1000);
                }
            }

            // ── 4. 未匹配的检测 → 新 permId ───────────────────────────────
            for (int i = 0; i < n; i++) {
                if (result[i] == -1) {
                    result[i] = permIdCounter.getAndIncrement();
                    log.debug("[新目标] det[{}] → permId={}", i, result[i]);
                }
            }

            // ── 5. 用检测坐标更新快照（framesLost=0，位置=检测框中心）─────
            //      注意：这里存的是真实检测坐标，不是 Kalman 预测坐标
            for (int i = 0; i < n; i++) {
                DetCache     c     = cache.get(i);
                int          permId = result[i];
                LostSnapshot s     = recentLost.computeIfAbsent(permId, k -> new LostSnapshot());
                s.permId     = permId;
                s.cx         = (float)(c.box.x + c.box.width  / 2);
                s.cy         = (float)(c.box.y + c.box.height / 2);
                s.w          = (float) c.box.width;
                s.h          = (float) c.box.height;
                s.classId    = c.classId;
                s.conf       = c.conf;
                s.framesLost = 0;
            }

            // 把 int[] 转成 List<Integer> 返回
            List<Integer> permIds = new ArrayList<>(n);
            for (int v : result) permIds.add(v);
            return permIds;
        }

        /** 无检测帧：仅老化快照，避免空场景后旧快照无限留存 */
        void tickEmpty() {
            recentLost.values().removeIf(s -> ++s.framesLost > LOST_KEEP_FRAMES);
        }

        List<LostRender> bridgeMissingDetections() {
            tickEmpty();
            List<LostRender> renders = new ArrayList<>();
            for (LostSnapshot s : recentLost.values()) {
                if (s.framesLost > BRIDGE_MISSING_FRAMES) continue;
                LostRender render = new LostRender();
                render.permId = s.permId;
                render.box = new BoundingBox(s.cx - s.w / 2, s.cy - s.h / 2, s.w, s.h);
                render.classId = s.classId;
                render.conf = s.conf;
                render.framesLost = s.framesLost;
                renders.add(render);
            }
            return renders;
        }
    }

    private final Map<String, PermanentIdMapper> idMapperMap = new ConcurrentHashMap<>();
    private PermanentIdMapper getIdMapper(NetPush netPush) {
        return idMapperMap.computeIfAbsent(netPush.getId().toString(),
                k -> new PermanentIdMapper());
    }

    // =========================================================================
    // ByteTracker（保留运行，不再用于 permId 分配）
    // =========================================================================
    private final Map<String, ByteTracker> trackerMap = new ConcurrentHashMap<>();
    private ByteTracker getTracker(NetPush netPush) {
        return trackerMap.computeIfAbsent(netPush.getId().toString(), k -> new ByteTracker());
    }

    // =========================================================================
    // 主循环
    // =========================================================================
    @Override
    public void run() {
        FFmpegFrameGrabber grabber = null;
        int consecutiveNullFrames  = 0;
        long streamOpenStartMs = System.currentTimeMillis();
        boolean firstImageLogged = false;
        boolean startupAckSent = false;
        try {
            grabber = createOptimizedGrabber();
            log.info("[视频流打开完成] 耗时={}ms, 地址={}", System.currentTimeMillis() - streamOpenStartMs, videoUrl);
            lastFrameTime = 0;
            resetStreamPtsBase();
            while (true) {
                if (!isStreamActive()) { log.warn("[主动停止推送]{}", uuid); break; }

                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (++consecutiveNullFrames > 10) {
                        log.info("[连续空帧过多，重启视频流]");
                        streamOpenStartMs = System.currentTimeMillis();
                        grabber = restartGrabber(grabber);
                        resetStreamPtsBase();
                        log.info("[视频流重连完成] 耗时={}ms, 地址={}", System.currentTimeMillis() - streamOpenStartMs, videoUrl);
                        firstImageLogged = false;
                        consecutiveNullFrames = 0;
                    }
                    Thread.sleep(100);
                    continue;
                }
                consecutiveNullFrames = 0;
                if (!firstImageLogged) {
                    long firstFrameCostMs = System.currentTimeMillis() - streamOpenStartMs;
                    log.info("[首帧图像已读取] 耗时={}ms, 流时间戳={}ms", firstFrameCostMs, grabber.getTimestamp() / 1000L);
                    if (firstFrameCostMs > 3000) {
                        log.warn("[首帧图像读取较慢] 耗时={}ms, 通常是在等待RTSP关键帧/IDR，请检查摄像头GOP/I帧间隔", firstFrameCostMs);
                    }
                    firstImageLogged = true;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < TARGET_FRAME_INTERVAL) {
                    frame.close();
                    continue;
                }
                lastFrameTime = currentTime;

                long grabTimestamp = System.currentTimeMillis();
                long rawStreamPts  = grabber.getTimestamp() / 1000L;
                long streamPts     = normalizeStreamPts(rawStreamPts);
                if (!startupAckSent) {
                    sendEmptyVideoFrame(grabTimestamp, streamPts, rawStreamPts, "startup");
                    startupAckSent = true;
                }
                processFrame(frame, grabTimestamp, streamPts, rawStreamPts);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetStreamPtsBase() {
        streamPtsBaseMs = STREAM_PTS_BASE_UNSET;
    }

    private long normalizeStreamPts(long rawStreamPts) {
        if (streamPtsBaseMs == STREAM_PTS_BASE_UNSET) {
            streamPtsBaseMs = rawStreamPts;
        }
        return rawStreamPts - streamPtsBaseMs;
    }

    private void processFrame(Frame frame, long grabTimestamp, long streamPts, long rawStreamPts) {
        Frame frameClone = null;
        Mat   matInfo    = null;
        long frameStartMs = System.currentTimeMillis();
        try {
            frameClone = frame.clone();
            frame.close();

            BufferedImage image = converter.getBufferedImage(frameClone);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return;

            matInfo = bufferedImageToMat(image);
            if (matInfo == null || matInfo.empty()) return;

            detectObjectsDifyOnnx(matInfo, netpush, redisTemplate, null, grabTimestamp, streamPts, rawStreamPts);

        } catch (Exception e) {
            log.error("[processFrame 异常]", e);
        } finally {
            int frameIndex = startupProcessLogCounter.incrementAndGet();
            long costMs = System.currentTimeMillis() - frameStartMs;
            if (frameIndex <= 10 || costMs > 1000) {
                log.info("[视频帧处理完成] 序号={}, 耗时={}ms, 流时间戳={}ms", frameIndex, costMs, streamPts);
            }
            if (frameClone != null) try { frameClone.close(); } catch (Exception ignored) {}
            if (matInfo   != null) matInfo.release();
        }
    }

    private void sendEmptyVideoFrame(long grabTimestamp, long streamPts, long rawStreamPts, String reason) {
        JSONObject bja = new JSONObject();
        bja.put("cmd",       "video");
        bja.put("number",    grabTimestamp);
        bja.put("streamPts", rawStreamPts);
        bja.put("rawStreamPts", rawStreamPts);
        bja.put("spaceFive", getSpaceFiveValue());
        bja.put("tracking",  true);
        bja.put("list",      new ArrayList<>());
        bja.put("reason",    reason);
        webSocket.sendMessage(bja.toJSONString());
    }

    public boolean detectObjectsDifyOnnx(Mat image, NetPush netPush,
                                         RedisTemplate redisTemplate,
                                         List<retureBoxInfo> retureBoxInfos,
                                         long grabTimestamp, long streamPts, long rawStreamPts) {
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        long startTime = System.currentTimeMillis();

        Mat processedImage = null;
        try {
            processedImage = letterboxResize(image, 640, 640);
            float[] inputData = preprocessImage(processedImage);
            processedImage.release();
            processedImage = null;

            OrtSession     session = netPush.getSession();
            OrtEnvironment env     = netPush.getEnv();
            float confThreshold = getConfThreshold(netPush);
            float nmsThreshold = getNmsThreshold(netPush);

            DetectionResult detectionResult;
            try {
                detectionResult = runOnnxInference(session, env, inputData, expectedClassCount, confThreshold);
            } catch (Exception ex) {
                log.error("ONNX推理失败", ex);
                return false;
            }

            int detectionCount = detectionResult.confidences.size();
            if (detectionCount <= 0) {
                // 无检测：仍老化快照，防止空场景后旧快照无限留存
                JSONObject bja = buildBridgeFrame(netPush, classNames, grabTimestamp, rawStreamPts);
                long inferMs = System.currentTimeMillis() - startTime;
                int emptyIndex = emptyDetectionLogCounter.incrementAndGet();
                if (emptyIndex <= 10 || inferMs > 1000) {
                    log.info("目标检测耗时: {}ms, 检测: 0, 追踪: 0, emptyIndex={}", inferMs, emptyIndex);
                }
                webSocket.sendMessage(bja.toJSONString());
                return true;
            }
            if (detectionCount > 200) { log.warn("检测数量异常: {}", detectionCount); return false; }

            int[] nmsIndices = performNMS(detectionResult, confThreshold, nmsThreshold);
            if (nmsIndices.length > 50) { log.warn("NMS后检测框数量过多: {}", nmsIndices.length); return false; }

            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx    = (640 - image.cols() * scale) / 2;
            double dy    = (640 - image.rows() * scale) / 2;

            // ── 构建检测缓存（原始图像坐标）────────────────────────────────
            List<ByteTracker.Detection> detections = new ArrayList<>();
            List<DetCache> cache = new ArrayList<>();
            for (int idx : nmsIndices) {
                Rect2d  box        = detectionResult.boxes2d.get(idx);
                Integer classId    = detectionResult.classIds.get(idx);
                float   confidence = detectionResult.confidences.get(idx);


                if (netPush.getIsBy() == 0) {
                    TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                    boolean inArea;
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    if (!isBoundingBoxInShapeData(box.x,box.y,box.width,box.height, videoUtil.getShapeData(),false)) { // false=有交集就算, true=完全在区域内
                        log.warn("检测框不在指定区域内，跳过");
                        continue;
                    }
                }

                BoundingBox ob = restoreCoordinates(box, scale, dx, dy, image);
                cache.add(new DetCache(ob, classId, confidence));
                detections.add(new ByteTracker.Detection(
                        (float) ob.x, (float) ob.y,
                        (float)(ob.x + ob.width), (float)(ob.y + ob.height),
                        confidence, classId));
            }

            // ── ByteTracker 仍运行（不用于 permId 分配）────────────────────
            if (cache.isEmpty()) {
                JSONObject bja = buildBridgeFrame(netPush, classNames, grabTimestamp, rawStreamPts);
                webSocket.sendMessage(bja.toJSONString());
                return true;
            }

            ByteTracker tracker = getTracker(netPush);
            List<ByteTracker.TrackResult> trackResults = tracker.update(detections);

            // ── v3 核心：直接用检测坐标做 permId 分配，不依赖 ByteTracker ID ──
            PermanentIdMapper idMapper = getIdMapper(netPush);
            List<Integer> permIds = idMapper.assignPermIds(cache);

            // ── 构建输出（支持多类别）────────────────────────────────────────
            List<JSONObject> jsonlist = new ArrayList<>();
            for (int i = 0; i < cache.size(); i++) {
                DetCache c       = cache.get(i);
                int      permId  = permIds.get(i);
                String   className = classNames.get(c.classId);
                TabAiBase aiBase = getAiBaseConfig(className);

                JSONObject bj = new JSONObject();
                bj.put("x",         c.box.x);
                bj.put("y",         c.box.y);
                bj.put("width",     c.box.width);
                bj.put("height",    c.box.height);
                bj.put("url",       videoUrl);
                bj.put("name",      className + "_" + permId);
                bj.put("className", aiBase.getChainName());
                bj.put("color",     CommonColorsVue(c.classId));
                bj.put("number",    grabTimestamp);
                bj.put("streamPts", rawStreamPts);
                bj.put("rawStreamPts", rawStreamPts);
                bj.put("trackId",   permId);
                bj.put("spaceFive", getSpaceFiveValue());
                bj.put("tracking",  true);
                bj.put("conf",      String.format("%.2f", c.conf));
                jsonlist.add(bj);
            }

            JSONObject bja = new JSONObject();
            bja.put("cmd",       "video");
            bja.put("number",    grabTimestamp);
            bja.put("streamPts", rawStreamPts);
            bja.put("rawStreamPts", rawStreamPts);
            bja.put("spaceFive", getSpaceFiveValue());
            bja.put("tracking",  true);
            bja.put("list",      jsonlist);

            long inferMs = System.currentTimeMillis() - startTime;
            log.info("目标检测耗时: {}ms, 检测: {}, 追踪: {}", inferMs, cache.size(), trackResults.size());

            webSocket.sendMessage(bja.toJSONString());
            return true;

        } finally {
            if (processedImage != null) processedImage.release();
        }
    }

    // =========================================================================
    // 推理 / NMS / 预处理
    // =========================================================================

    /**
     * ONNX推理 - 支持YOLOv5/v8/v11多种格式
     */
    private DetectionResult runOnnxInference(OrtSession session, OrtEnvironment env,
                                             float[] inputData, Integer expectedClassCount,
                                             float confThreshold) throws Exception {
        long[] shape = {1, 3, 640, 640};
        DetectionResult result = new DetectionResult();

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)) {
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    if (!(entry.getValue() instanceof OnnxTensor)) continue;

                    OnnxTensor tensor = (OnnxTensor) entry.getValue();
                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    parseOnnxOutput(rawOutput, tensorShape, expectedClassCount, confThreshold, result);
                }
            }
        }

        return result;
    }

    /**
     * 解析ONNX输出（支持YOLOv5-v11多种格式）
     */
    private void parseOnnxOutput(Object rawOutput, long[] tensorShape, Integer expectedClassCount,
                                 float confThreshold, DetectionResult result) {
        if (rawOutput instanceof float[][][]) {
            float[][][] batch = (float[][][]) rawOutput;

            // 判断是否需要转置 (YOLOv11: [1, 84, 8400])
            boolean needTranspose = tensorShape.length == 3 &&
                    tensorShape[1] < tensorShape[2] &&
                    tensorShape[1] <= (expectedClassCount + 5);

            for (float[][] detections : batch) {
                if (needTranspose) {
                    parseTransposedDetections(detections, tensorShape, expectedClassCount, confThreshold, result);
                } else {
                    parseStandardDetections(detections, expectedClassCount, confThreshold, result);
                }
            }
        } else if (rawOutput instanceof float[][]) {
            parseStandardDetections((float[][]) rawOutput, expectedClassCount, confThreshold, result);
        }
    }

    /**
     * 解析转置格式（YOLOv11）
     */
    private void parseTransposedDetections(float[][] detections, long[] tensorShape,
                                           Integer expectedClassCount, float confThreshold,
                                           DetectionResult result) {
        int numFeatures = (int) tensorShape[1];
        int numDetections = (int) tensorShape[2];
        int numClasses = numFeatures - 4;

        for (int i = 0; i < numDetections; i++) {
            float cx = detections[0][i];
            float cy = detections[1][i];
            float w = detections[2][i];
            float h = detections[3][i];

            // 找最高分类别
            float maxScore = 0;
            int classId = 0;
            for (int c = 0; c < numClasses; c++) {
                if (detections[4 + c][i] > maxScore) {
                    maxScore = detections[4 + c][i];
                    classId = c;
                }
            }

            if (maxScore > confThreshold && classId < expectedClassCount) {
                result.addDetection(cx - w / 2, cy - h / 2, w, h, maxScore, classId);
            }
        }
    }

    /**
     * 解析标准格式（YOLOv5/v8）
     */
    private void parseStandardDetections(float[][] detections, Integer expectedClassCount,
                                         float confThreshold, DetectionResult result) {
        for (float[] det : detections) {
            boolean hasObjectness = det.length > 5;
            int startIdx = hasObjectness ? 5 : 4;

            // 找最高分类别
            float maxScore = 0;
            int classId = 0;
            for (int i = startIdx; i < det.length; i++) {
                if (det[i] > maxScore) {
                    maxScore = det[i];
                    classId = i - startIdx;
                }
            }

            float confidence = hasObjectness ? det[4] * maxScore : maxScore;

            if (confidence > confThreshold && classId < expectedClassCount) {
                float cx = det[0], cy = det[1], w = det[2], h = det[3];
                result.addDetection(cx - w / 2, cy - h / 2, w, h, confidence, classId);
            }
        }
    }

    private int[] performNMS(DetectionResult dr, float confThr, float nmsThr) {
        if (dr.boxes2d.isEmpty()) return new int[0];
        MatOfRect2d boxesMat = new MatOfRect2d();
        MatOfFloat  confMat  = new MatOfFloat();
        MatOfInt    indices  = new MatOfInt();
        try {
            boxesMat.fromList(dr.boxes2d);
            confMat = new MatOfFloat(Converters.vector_float_to_Mat(dr.confidences));
            Dnn.NMSBoxes(boxesMat, confMat, confThr, nmsThr, indices);
            if (indices.empty() || indices.rows() == 0) {
                log.warn("NMS未返回索引，置信度阈值{}可能过高", confThr);
                return new int[0];
            }
            return indices.toArray();
        } finally {
            boxesMat.release(); confMat.release(); indices.release();
        }
    }

    private float[] preprocessImage(Mat processedImage) {
        Mat blob = new Mat();
        List<Mat> channels = new ArrayList<>();
        try {
            processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
            Core.split(blob, channels);
            float[] inputData = new float[3 * 640 * 640];
            for (int c = 0; c < 3; c++) {
                float[] data = new float[640 * 640];
                channels.get(c).get(0, 0, data);
                System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
            }
            return inputData;
        } finally {
            blob.release();
            for (Mat ch : channels) ch.release();
        }
    }

    // =========================================================================
    // 辅助类
    // =========================================================================
    static class DetCache {
        final BoundingBox box; final int classId; final float conf;
        DetCache(BoundingBox b, int c, float f) { box = b; classId = c; conf = f; }
    }
    static class BoundingBox {
        double x, y, width, height;
        BoundingBox(double x, double y, double w, double h) {
            this.x = x; this.y = y; width = w; height = h;
        }
    }
    static class DetectionStats {
        String audioText = ""; Integer warnNumber = 0; String warnText = "", warnName = "";
        void accumulate(TabAiBase a) {
            audioText  += a.getRemark() + a.getSpaceOne();
            warnNumber += a.getSpaceTwo() == null ? 1 : a.getSpaceTwo();
        }
    }
    private static class DetectionResult {
        List<Rect2d>  boxes2d     = new ArrayList<>();
        List<Float>   confidences = new ArrayList<>();
        List<Integer> classIds    = new ArrayList<>();
        void addDetection(double x, double y, double w, double h, float conf, int cid) {
            boxes2d.add(new Rect2d(x, y, w, h)); confidences.add(conf); classIds.add(cid);
        }
    }
    private TabAiBase getAiBaseConfig(String className) {
        TabAiBase a = VideoSendReadCfg.map.get(className);
        if (a == null) { a = new TabAiBase(); a.setChainName(className); }
        return a;
    }
    private BoundingBox restoreCoordinates(Rect2d box, double scale,
                                           double dx, double dy, Mat image) {
        double x = Math.max(0, Math.min((box.x - dx) / scale, image.cols() - 1));
        double y = Math.max(0, Math.min((box.y - dy) / scale, image.rows() - 1));
        double w = Math.min(box.width  / scale, image.cols() - x);
        double h = Math.min(box.height / scale, image.rows() - y);
        return new BoundingBox(x, y, w, h);
    }

    private String getSpaceFiveValue() {
        return tabAiModelBund == null ? null : tabAiModelBund.getSpaceFive();
    }

    private float getConfThreshold(NetPush netPush) {
        TabAiModel tabAiModel = netPush == null ? null : netPush.getTabAiModel();
        return tabAiModel == null || tabAiModel.getThreshold() == null
                ? 0.45f
                : tabAiModel.getThreshold().floatValue();
    }

    private float getNmsThreshold(NetPush netPush) {
        TabAiModel tabAiModel = netPush == null ? null : netPush.getTabAiModel();
        return tabAiModel == null || tabAiModel.getNmsThreshold() == null
                ? 0.4f
                : tabAiModel.getNmsThreshold().floatValue();
    }

    private JSONObject buildBridgeFrame(NetPush netPush, List<String> classNames,
                                        long grabTimestamp, long rawStreamPts) {
        List<JSONObject> jsonlist = new ArrayList<>();
        for (PermanentIdMapper.LostRender lost : getIdMapper(netPush).bridgeMissingDetections()) {
            if (lost.classId < 0 || lost.classId >= classNames.size()) continue;
            String className = classNames.get(lost.classId);
            TabAiBase aiBase = getAiBaseConfig(className);

            JSONObject bj = new JSONObject();
            bj.put("x", lost.box.x);
            bj.put("y", lost.box.y);
            bj.put("width", lost.box.width);
            bj.put("height", lost.box.height);
            bj.put("url", videoUrl);
            bj.put("name", className + "_" + lost.permId);
            bj.put("className", aiBase.getChainName());
            bj.put("color", CommonColorsVue(lost.classId));
            bj.put("number", grabTimestamp);
            bj.put("streamPts", rawStreamPts);
            bj.put("rawStreamPts", rawStreamPts);
            bj.put("trackId", lost.permId);
            bj.put("spaceFive", getSpaceFiveValue());
            bj.put("tracking", true);
            bj.put("conf", String.format("%.2f", lost.conf));
            bj.put("predicted", true);
            bj.put("lostFrames", lost.framesLost);
            jsonlist.add(bj);
        }

        JSONObject bja = new JSONObject();
        bja.put("cmd", "video");
        bja.put("number", grabTimestamp);
        bja.put("streamPts", rawStreamPts);
        bja.put("rawStreamPts", rawStreamPts);
        bja.put("spaceFive", getSpaceFiveValue());
        bja.put("tracking", true);
        bja.put("list", jsonlist);
        bja.put("bridgeMissing", !jsonlist.isEmpty());
        return bja;
    }

    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        if (grabber != null) { grabber.stop(); grabber.release(); }
        return createOptimizedGrabber();
    }
    private boolean isStreamActive() {
        try { return RedisCacheHolder.get(tabAiModelBund.getId() + "videoRead"); }
        catch (Exception e) { log.warn("[检查流状态异常]", e); return false; }
    }
    public FFmpegFrameGrabber createOptimizedGrabber() throws Exception {
        Exception lastException = null;

        try {
            return startGrabber("NVIDIA", "cuda", resolveNvidiaCodecName());
        } catch (Exception e) {
            lastException = e;
            log.warn("[NVIDIA解码失败，切换Intel解码] {}", e.getMessage());
        }

        try {
            return startGrabber("Intel", "qsv", resolveQsvCodecName());
        } catch (Exception e) {
            lastException = e;
            log.warn("[Intel解码失败，切换CPU解码] {}", e.getMessage());
        }

        try {
            return startGrabber("CPU", null, null);
        } catch (Exception e) {
            if (lastException != null) {
                e.addSuppressed(lastException);
            }
            throw e;
        }
    }

    private FFmpegFrameGrabber startGrabber(String mode, String hwaccel, String videoCodecName) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
        try {
            if (isHttpFlvUrl()) {
                grabber.setFormat("flv");
            }
            applyLowLatencyOptions(grabber);
            if (hwaccel != null && hwaccel.length() > 0) {
                grabber.setOption("hwaccel", hwaccel);
            }
            if (videoCodecName != null && videoCodecName.length() > 0) {
                grabber.setVideoCodecName(videoCodecName);
            }
            log.info("[尝试{}解码] 硬件加速={}, 编码器={}", mode, hwaccel, videoCodecName);
            grabber.start();
            codecName = grabber.getVideoCodecName();
            log.info("[{}解码已启动] 视频编码={}, 编码ID={}", mode, codecName, grabber.getVideoCodec());
            return grabber;
        } catch (Exception e) {
            releaseGrabberQuietly(grabber);
            throw e;
        }
    }

    private void applyLowLatencyOptions(FFmpegFrameGrabber grabber) {
        grabber.setOption("loglevel",       "-8");
        grabber.setOption("stimeout",       "3000000");
        grabber.setOption("rw_timeout",     "3000000");
        grabber.setOption("allowed_media_types", "video");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        grabber.setOption("flags",          "low_delay");
        grabber.setOption("buffer_size",    "512000");
     //   grabber.setOption("use_wallclock_as_timestamps", "1");
        grabber.setOption("flags2",         "fast");
        grabber.setOption("err_detect",     "compliant");
        grabber.setOption("framedrop",      "1");

        if (isHttpFlvUrl()) {
            grabber.setOption("probesize",      "5000000");
            grabber.setOption("analyzeduration","5000000");
            grabber.setOption("max_delay",      "500000");
            grabber.setOption("fflags",         "flush_packets+discardcorrupt");
            return;
        }

        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags",     "prefer_tcp");
        grabber.setOption("probesize",      "100000");
        grabber.setOption("analyzeduration","500000");
        grabber.setOption("avioflags",      "direct");
        grabber.setOption("max_delay",      "0");
        grabber.setOption("reorder_queue_size", "0");
        grabber.setOption("fflags",         "nobuffer+flush_packets+discardcorrupt");
    }

    private boolean isHttpFlvUrl() {
        if (videoUrl == null) {
            return false;
        }
        String lowerUrl = videoUrl.toLowerCase(Locale.ROOT);
        return (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://"))
                && lowerUrl.contains(".flv");
    }

    private String resolveNvidiaCodecName() {
        if ("h264".equalsIgnoreCase(codecName)) {
            return "h264_cuvid";
        }
        if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
            return "hevc_cuvid";
        }
        return null;
    }

    private String resolveQsvCodecName() {
        if ("h264".equalsIgnoreCase(codecName)) {
            return "h264_qsv";
        }
        if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
            return "hevc_qsv";
        }
        return null;
    }

    private void releaseGrabberQuietly(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
        }
    }

    private FFmpegFrameGrabber createLegacyOptimizedGrabber() throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
        grabber.setOption("hwaccel", "qsv");
        if ("h264".equalsIgnoreCase(codecName)) {
            grabber.setVideoCodecName("h264_qsv");
        } else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
            grabber.setVideoCodecName("hevc_qsv");
        }
        log.info("[使用Intel加速解码]");
        grabber.setOption("loglevel",       "-8");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags",     "prefer_tcp");
        grabber.setOption("stimeout",       "3000000");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        grabber.setOption("flags",          "low_delay");
        grabber.setOption("max_delay",      "500000");
        grabber.setOption("buffer_size",    "512000");
        grabber.setOption("fflags",         "nobuffer+flush_packets+discardcorrupt");
        grabber.setOption("flags2",         "fast");
        grabber.setOption("err_detect",     "compliant");
        grabber.setOption("framedrop",      "1");
        grabber.start();
        codecName = grabber.getVideoCodecName();
        log.info("检测到视频编码: {} (ID={})", codecName, grabber.getVideoCodec());
        return grabber;
    }
}
