package org.jeecg.modules.demo.video.util;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.jeecg.common.util.RestUtil;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.AIModel.pose.FallDetectionResult;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.entity.pushEntity;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.jeecg.modules.demo.audio.util.audioSend.*;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColors;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.base64Image;
import static org.jeecg.modules.tab.AIModel.pose.FallDetectionResult.detectFallOrStand;

/**
 * ONNX目标检测工具类 - 支持双模式预警
 *
 * <p>支持两种预警模式：</p>
 * <ul>
 *   <li>时间间隔预警（warnType=0）：按固定时间间隔报警</li>
 *   <li>目标跟踪预警（warnType=1）：只在新目标进入时报警，类似电子围栏</li>
 * </ul>
 *
 * @author wggg
 * @date 2025/9/30 11:07
 * @version 2.0 - 新增目标跟踪预警功能
 */
@Slf4j
public class identifyTypeNewOnnxTest {

    // ========================================
    // 目标跟踪配置常量
    // ========================================

    /**
     * 目标匹配距离阈值（像素）
     * <p>当前帧检测到的目标与历史目标的中心点距离小于此值时，认为是同一目标</p>
     * <p>建议值：50-150像素，根据场景调整：</p>
     * <ul>
     *   <li>室内近距离监控：50-80像素</li>
     *   <li>室外远距离监控：100-150像素</li>
     *   <li>人流密集区域：80-120像素</li>
     * </ul>
     */
    private static final double TARGET_MATCH_DISTANCE_THRESHOLD = 150.0;

    /**
     * 目标超时时间（毫秒）
     * <p>目标超过此时间未被检测到，则认为已离开监控区域</p>
     * <p>建议值：3000-10000毫秒，根据帧率调整：</p>
     * <ul>
     *   <li>高帧率（>15fps）：3000-5000毫秒</li>
     *   <li>低帧率（<10fps）：6000-10000毫秒</li>
     * </ul>
     */
    private static final long TARGET_TIMEOUT_MS = 10000;

    /**
     * Redis存储目标列表的key后缀
     * <p>完整key格式：{cameraId}:targets</p>
     */
    private static final String REDIS_KEY_SUFFIX_TARGETS = ":targets";

    /**
     * 目标跟踪编号全局计数器（循环0-9999）
     * <p>每个新出现的目标分配一个唯一显示编号，方便在画面上确认跟踪正确性</p>
     */
    private static final java.util.concurrent.atomic.AtomicInteger TRACK_COUNTER =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * ONNX V5 目标检测方法（支持双模式预警）
     *
     * @param pushInfo 推送信息配置
     * @param image 待检测的图像（Mat格式）
     * @param netPush 网络推送配置对象
     * @param redisTemplate Redis操作模板
     * @param retureBoxInfos 前置检测框列表（用于区域过滤）
     * @return true-检测成功并推送，false-检测失败或不满足推送条件
     */
    public boolean detectObjectsDifyOnnxV5(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,
                                           RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos) {

        // ========== 1. 预警模式判断和频率控制 ==========
        Integer warnType = netPush.getTabAiVideoSetting().getWarnType();

        if (warnType == 0) {
            // 时间间隔预警模式：检查距离上次推送的时间间隔
            long intervalTime = netPush.getTabAiVideoSetting().getWarnTime();
            Object lastPushTime = redisTemplate.opsForValue().get(netPush.getId());
            if (lastPushTime != null) {
                log.info("【时间间隔预警】推送间隔未到，跳过本次检测");
                return false;
            }

            log.info("【时间间隔预警】间隔时间: {}s", intervalTime);
        } else if (warnType == 1) {
            // 目标跟踪预警模式：不进行时间间隔检查，由目标跟踪逻辑控制
            log.info("【目标跟踪预警】模式启用 - 推送对象: {}", pushInfo.getName());
        }

        log.info("前置检测框: {}", JSON.toJSONString(retureBoxInfos));

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();

        float thresshold=tabAiModel.getThreshold()==null?0.4f:tabAiModel.getThreshold().floatValue();
        float nmsThresshold=tabAiModel.getNmsThreshold()==null?0.35f:tabAiModel.getNmsThreshold().floatValue();

        long startTime = System.currentTimeMillis();

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();

        DetectionResult detectionResult;
        try {
            detectionResult = runOnnxInference(session, env, inputData, expectedClassCount,thresshold);
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return false;
        }

        // ========== 5. 检测结果验证 ==========
        int detectionCount = detectionResult.confidences.size();
        if (detectionCount <= 0 || detectionCount > 200) {
            log.warn("{}:检测数量异常: {}-{}-阈值{}-nms{}", pushInfo.getName(), tabAiModel.getAiName(), detectionCount,thresshold,nmsThresshold);
            handleNoDetection(pushInfo, netPush, redisTemplate, image, uploadPath, tabAiModel);
            return false;
        }

        log.info("NMS前检测框数量: {}", detectionResult.boxes2d.size());

        // ========== 6. NMS非极大值抑制 ==========
        int[] nmsIndices = performNMS(detectionResult, thresshold, nmsThresshold);
        if (nmsIndices.length > 50) {

            if (netPush.getTabAiSubscriptionNew().getSaveErrorPic() == 0) {
                saveDebugImg(image, nmsIndices.length, netPush.getTabAiSubscriptionNew().getPathSave(),"error");
            }
            log.warn("NMS后检测框数量过多: {}, 超过阈值50", nmsIndices.length);
            return false;
        }
        log.info("NMS后检测框数量: {}", nmsIndices.length);

        // ========== 7. 过滤和收集有效检测目标 ==========
        if (netPush.getTabAiSubscriptionNew().getSaveBeforePic() == 0) {
            saveDebugImg(image, nmsIndices.length, netPush.getTabAiSubscriptionNew().getPathSave(),"before");
        }
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        //  先把自定义检测区域绘制到图像上，方便肉眼核对区域是否正确
        if(netPush.getIsBy()==0&&netPush.getTabAiVideoSetting().getIsByWrite()==0){ //开启区域绘制
            image = drawRegionsOnImage(image, netPush);
        }


        // 收集当前帧所有有效目标（用于目标跟踪）
        List<DetectedTarget> currentFrameTargets = new ArrayList<>();
        DetectionStats stats = new DetectionStats();
        int validCount = 0;

        // ✅ 是否需要将区域名写入推送（前提：开启区域识别 AND 开启区域推送）
        boolean needAreaPush = netPush.getIsBy() == 0 && netPush.getTabAiVideoSetting().getIsByPush() == 0;

        // ✅ 收集当前帧所有命中的区域名称（去重、保留顺序）
        Set<String> hitAreaNames = new LinkedHashSet<>();

        // 延迟绘制队列：先收集，跟踪完再画（以便在标签上显示编号）
        // 每个元素: [BoundingBox, baseLabel, confidence, color, DetectedTarget]
        List<Object[]> drawQueue = new ArrayList<>();

        for (int idx : nmsIndices) {
            Rect2d box = detectionResult.boxes2d.get(idx);
            Integer classId = detectionResult.classIds.get(idx);
            String className = classNames.get(classId);
            float confidence = detectionResult.confidences.get(idx);

            // 获取类别配置
            TabAiBase aiBase = getAiBaseConfig(className);
            if (aiBase == null || shouldSkipClass(aiBase)) {
                log.warn("【跳过类别：{}】", className);
                continue;
            }

            log.info("className：{}",className);
            // 坐标还原到原图
            BoundingBox originalBox = restoreCoordinates(box, scale, dx, dy, image);

            // ✅ 区域过滤：null=丢弃；非null=有效，值为区域名（可能为""）
            String areaName = isValidDetection(pushInfo, netPush, retureBoxInfos, originalBox, box);
            if (areaName == null) {
                log.info("不在区域内，跳过");
                continue;
            }

            // ✅ 当开启区域推送时，收集命中区域名称
            if (needAreaPush && !areaName.isEmpty()) {
                hitAreaNames.add(areaName);
            }



            // 计算目标中心点（用于目标跟踪）
            double centerX = originalBox.x + originalBox.width / 2.0;
            double centerY = originalBox.y + originalBox.height / 2.0;

            // 创建检测目标对象（用于目标跟踪）
            DetectedTarget target = new DetectedTarget();
            target.setCenterX(centerX);
            target.setCenterY(centerY);
            target.setClassName(className);
            target.setConfidence(confidence);
            target.setBoundingBox(originalBox);
            target.setDetectedTime(System.currentTimeMillis());
            currentFrameTargets.add(target);

            // 累计统计信息
            stats.accumulate(aiBase);

            // 加入延迟绘制队列（编号等跟踪完成后补充）
            Scalar color = getColor(aiBase.getRgbColor());
            drawQueue.add(new Object[]{originalBox, aiBase.getChainName(), confidence, color, target});

            validCount++;
        }

        // ========== 8. 目标跟踪逻辑 ==========
        // 始终执行跟踪以获取每个目标的显示编号；是否触发报警由 warnType 决定
        TargetTrackingResult trackingResult = processTargetTracking(
                netPush.getId(),
                currentFrameTargets,
                redisTemplate
        );
        Map<DetectedTarget, Integer> targetNumMap = trackingResult.getTargetNumMap();

        boolean shouldTriggerAlarm = false;
        int newTargetCount = 0;

        if (warnType == 1) {
            // 目标跟踪预警模式
            shouldTriggerAlarm = trackingResult.hasNewTargets();
            newTargetCount = trackingResult.getNewTargetCount();

            log.info("【目标跟踪】当前目标数: {}, 新增目标数: {}, 是否触发报警: {}",
                    currentFrameTargets.size(), newTargetCount, shouldTriggerAlarm);

        } else {
            // 时间间隔预警模式 - 有检测结果就触发
            shouldTriggerAlarm = validCount > 0;
        }

        // ========== 8b. 带编号绘制检测框 ==========
        for (Object[] di : drawQueue) {
            BoundingBox    box       = (BoundingBox)    di[0];
            String         baseName  = (String)          di[1];
            float          conf      = (float)            di[2];
            Scalar         color     = (Scalar)           di[3];
            DetectedTarget dt        = (DetectedTarget)   di[4];
            Integer        trackNum  = targetNumMap.get(dt);

            // 格式：类名-置信度-编号，例如 人-0.92-0001
            String confStr    = String.format("%.2f", conf);
            String numStr     = trackNum != null ? String.format("%04d", trackNum) : "----";
            String fullLabel  = baseName + "-" + confStr + "-" + numStr;

            // 详细日志：方便核对每帧每个目标
            log.info("【目标标注】{} | 置信度:{} | 编号:{} | 框:[x={},y={},w={},h={}] | 中心:({},{})",
                    baseName, confStr, numStr,
                    (int) box.x, (int) box.y, (int) box.width, (int) box.height,
                    (int)(box.x + box.width / 2.0), (int)(box.y + box.height / 2.0));

            image = drawDetection(image, box, fullLabel, color);
        }

        // ========== 9. 推送结果 ==========
        if (!shouldTriggerAlarm || stats.warnNumber <= 0) {
            log.info("【不触发报警】shouldTrigger: {}, warnNumber: {}", shouldTriggerAlarm, stats.warnNumber);
            return false;
        }


        if (warnType == 0) {
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                    netPush.getTabAiVideoSetting().getWarnTime(), TimeUnit.SECONDS);
            log.info("【时间间隔预警】已触发报警，开始计时 {}s", netPush.getTabAiVideoSetting().getWarnTime());
        }

        // 保存图像并推送
        String savePath = uploadPath + File.separator + "push" + File.separator;
        String savedImagePath = saveDetectionImage(image, savePath);

        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}, 新增目标: {}",
                (endTime - startTime), validCount, nmsIndices.length, newTargetCount);

        try {
            // 如果是目标跟踪模式，在报警文本中加入新增目标信息
            String warnText = stats.warnText;
            if (warnType == 1 && newTargetCount > 0) {
                warnText = String.format("检测到%d个新目标进入监控区域！%s", newTargetCount, stats.warnText);
            }

            // ✅ 拼接命中区域名称（多个区域用逗号分隔）
            String modelArea = (needAreaPush && !hitAreaNames.isEmpty())
                    ? String.join(",", hitAreaNames) : "";
            log.info("【区域推送】needAreaPush={} 命中区域: [{}]", needAreaPush, modelArea);

            isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                    stats.audioText, stats.warnNumber, warnText, stats.warnName, savePath, modelArea);
            return true;
        } catch (Exception ex) {
            log.error("推送失败", ex);
            return false;
        }
    }


    /**
     * ONNX V5 Pose检测方法（支持双模式预警）
     * <p>适用于姿态检测场景</p>
     *
     * @param pushInfo 推送信息配置
     * @param image 待检测的图像
     * @param netPush 网络推送配置对象
     * @param redisTemplate Redis操作模板
     * @param retureBoxInfos 前置检测框列表
     * @return true-检测成功并推送，false-检测失败或不满足推送条件
     */
    public boolean detectObjectsDifyOnnxV5Pose(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,
                                               RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos) {

        // ========== 1. 预警模式判断和频率控制 ==========
        Integer warnType = netPush.getTabAiVideoSetting().getWarnType();

        if (warnType == 0) {
            // 时间间隔预警模式：检查距离上次推送的时间间隔
            long intervalTime = netPush.getTabAiVideoSetting().getWarnTime();
            Object lastPushTime = redisTemplate.opsForValue().get(netPush.getId());
            if (lastPushTime != null) {
                log.info("【时间间隔预警】推送间隔未到，跳过本次检测");
                return false;
            }

            log.info("【时间间隔预警】间隔时间: {}s", intervalTime);
        } else if (warnType == 1) {
            // 目标跟踪预警模式：不进行时间间隔检查，由目标跟踪逻辑控制
            log.info("【目标跟踪预警】模式启用 - 推送对象: {}", pushInfo.getName());
        }

        log.info("前置检测框: {}", JSON.toJSONString(retureBoxInfos));

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        float confThreshold=tabAiModel.getThreshold()==null?0.45f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.4f:tabAiModel.getNmsThreshold().floatValue();

        long startTime = System.currentTimeMillis();

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();


        try {
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);


            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();
            List<float[]> keypoints = new ArrayList<>();

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    OnnxValue value = entry.getValue();
                    if (!(value instanceof OnnxTensor)) continue;
                    OnnxTensor tensor = (OnnxTensor) value;

                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    System.out.println("输出维度: [" + tensorShape[0] + ", " + tensorShape[1] + ", " + tensorShape[2] + "]");

                    if (rawOutput instanceof float[][][]) {
                        float[][][] batch = (float[][][]) rawOutput;

                        // 🔍 添加调试信息
                        System.out.println("实际数组维度: [" + batch.length + "]["
                                + batch[0].length + "][" + batch[0][0].length + "]");

                        // ✅ 根据实际维度判断数据格式
                        int dim0 = batch.length;        // 通常是 1
                        int dim1 = batch[0].length;     // 可能是 56 或 8400
                        int dim2 = batch[0][0].length;  // 可能是 8400 或 56
                        int debugCount = 0; // 用于控制调试输出数量

                        if (dim1 == 56 && dim2 > 1000) {
                            System.out.println("✅ 检测到格式: [batch][features][detections]");
                            float[][] detections = batch[0];


                            for (int i = 0; i < dim2; i++) {
                                float centerX = detections[0][i];
                                float centerY = detections[1][i];
                                float width = detections[2][i];
                                float height = detections[3][i];
                                float confidence = detections[4][i];

                                if (confidence > confThreshold) {
                                    float left = centerX - width / 2;
                                    float top = centerY - height / 2;

                                    // 提取关键点数据
                                    float[] kpts = new float[51];
                                    for (int j = 0; j < 51; j++) {
                                        kpts[j] = detections[5 + j][i];
                                    }




                                    // ✅ 验证关键点有效性
                                    int validCoordCount = 0;
                                    int highVisibilityCount = 0;
                                    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
                                    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

                                    for (int k = 0; k < 17; k++) {
                                        float kx = kpts[k * 3];
                                        float ky = kpts[k * 3 + 1];
                                        float visibility = kpts[k * 3 + 2];

                                        boolean coordsInRange = (kx >= 0 && kx <= 640 && ky >= 0 && ky <= 640);
                                        boolean notZero = (kx > 0.1 || ky > 0.1);

                                        if (coordsInRange && notZero) {
                                            validCoordCount++;

                                            if (visibility > 0.5) {
                                                if (i == 0) {
                                                    System.out.println("鼻子坐标: (" + kx + "," + ky + ")");
                                                } else if (i == 9 || i == 10) {
                                                    System.out.println("手腕位置: " + (i == 9 ? "左手" : "右手") + " (" + kx + "," + ky + ")");
                                                }
                                                highVisibilityCount++;
                                                // 只统计高可见性的关键点范围
                                                minX = Math.min(minX, kx);
                                                maxX = Math.max(maxX, kx);
                                                minY = Math.min(minY, ky);
                                                maxY = Math.max(maxY, ky);
                                            }
                                        }
                                    }

                                    // ⭐ 计算关键点分布范围
                                    float keypointWidth = (maxX == Float.MIN_VALUE) ? 0 : (maxX - minX);
                                    float keypointHeight = (maxY == Float.MIN_VALUE) ? 0 : (maxY - minY);

                                    // 计算关键点范围与边界框的比例
                                    float widthRatio = (width > 0) ? (keypointWidth / width) : 0;
                                    float heightRatio = (height > 0) ? (keypointHeight / height) : 0;

                                    if (debugCount < 3) {
                                        System.out.println(String.format("  统计: 有效坐标=%d/17, 高可见性=%d/17",
                                                validCoordCount, highVisibilityCount));
                                        System.out.println(String.format("  边界框: 宽=%.1f, 高=%.1f", width, height));
                                        System.out.println(String.format("  关键点范围: 宽=%.1f, 高=%.1f", keypointWidth, keypointHeight));
                                        System.out.println(String.format("  覆盖率: 宽度%.1f%%, 高度%.1f%%", widthRatio * 100, heightRatio * 100));
                                        debugCount++;
                                    }

                                    // ⭐⭐⭐ 关键过滤条件 ⭐⭐⭐
                                    boolean hasValidKeypoints = false;

                                    if (highVisibilityCount >= 3) {

                                        // 策略2: 根据边界框大小动态调整阈值
                                        float minWidth = Math.min(30, width * 0.3f);   // 最小宽度: 30px 或 边界框30%
                                        float minHeight = Math.min(70, height * 0.4f); // 最小高度: 70px 或 边界框40%

                                        boolean hasReasonableSpread = (keypointWidth > minWidth && keypointHeight > minHeight);

                                        // 策略3: 降低覆盖率要求，支持更多姿态
                                        // 宽度覆盖30%，高度覆盖40% 即可认为有效
                                        boolean coversEnoughArea = (widthRatio > 0.3 && heightRatio > 0.4);

                                        // 策略4: 特殊情况处理 - 如果关键点非常集中但置信度高，也认为有效
                                        boolean isHighConfidenceCompact = (confidence > 0.7f && highVisibilityCount >= 5);

                                        // 综合判断
                                        hasValidKeypoints = hasReasonableSpread && (coversEnoughArea || isHighConfidenceCompact);

                                        if (debugCount <= 3) {
                                            System.out.println(String.format("  验证结果: 合理分布=%s, 覆盖充分=%s, 高置信紧凑=%s, 最终=%s",
                                                    hasReasonableSpread, coversEnoughArea, isHighConfidenceCompact, hasValidKeypoints));
                                        }

                                    } else {
                                        // 策略5: 低可见性但高置信度的兜底方案
                                        // 如果置信度很高(>0.75)且至少有2个关键点，也可以尝试保留
                                        if (confidence > 0.6f && validCoordCount >= 2) {
                                            hasValidKeypoints = true;
                                            if (debugCount <= 3) {
                                                System.out.println(String.format("  验证结果: 高置信度兜底通过 (conf=%.2f, valid=%d)",
                                                        confidence, validCoordCount));
                                            }
                                        } else {
                                            if (debugCount <= 3) {
                                                System.out.println(String.format("  验证结果: 高可见性关键点不足(%d<3)", highVisibilityCount));
                                            }
                                        }
                                    }


                                    if (hasValidKeypoints) {
                                        classIds.add(0);
                                        confidences.add(confidence);
                                        boxes2d.add(new Rect2d(left, top, width, height));
                                        keypoints.add(kpts);

                                        log.info("✅ 检测到有效人体: 置信度={}, 坐标=({},{},{},{}), 关键点覆盖={}x{}",
                                                confidence, left, top, width, height,
                                                String.format("%.0f%%", widthRatio * 100),
                                                String.format("%.0f%%", heightRatio * 100));
                                    } else {
                                        if (debugCount <= 3) {
                                            System.out.println(String.format("❌ 过滤掉检测框[%d]: 关键点分布异常", i));
                                        }
                                    }
                                }
                            }
                        } else if (dim1 > 1000 && dim2 == 56) {
                            // 格式: [1][8400][56] - 检测在前
                            System.out.println("⚠️ 检测到格式: [batch][detections][features]");

                            for (int i = 0; i < dim1; i++) {
                                float[] detection = batch[0][i];  // [56]

                                float centerX = detection[0];
                                float centerY = detection[1];
                                float width = detection[2];
                                float height = detection[3];
                                float confidence = detection[4];

                                if (confidence > confThreshold) {
                                    float left = centerX - width / 2;
                                    float top = centerY - height / 2;

                                    // 提取关键点
                                    float[] kpts = new float[51];
                                    System.arraycopy(detection, 5, kpts, 0, 51);

                                    // 🔍 验证关键点有效性
                                    boolean hasValidKeypoints = false;
                                    for (int k = 0; k < 17; k++) {
                                        float kx = kpts[k * 3];
                                        float ky = kpts[k * 3 + 1];
                                        float visibility = kpts[k * 3 + 2];
                                        if (visibility > 0.3 && kx > 0 && ky > 0) {
                                            hasValidKeypoints = true;
                                            break;
                                        }
                                    }

                                    if (hasValidKeypoints) {
                                        classIds.add(0);
                                        confidences.add(confidence);
                                        boxes2d.add(new Rect2d(left, top, width, height));
                                        keypoints.add(kpts);

                                        log.info("检测到人体: 置信度={}, 坐标=({},{},{},{})",
                                                confidence, left, top, width, height);
                                    } else {
                                        System.out.println("⚠️ 跳过无效检测 (置信度=" + confidence + "): 无有效关键点");
                                    }
                                }
                            }
                        } else {
                            System.err.println("❌ 未知的输出格式: [" + dim0 + "][" + dim1 + "][" + dim2 + "]");
                        }
                    }
                }
            }


            // ========== 5. 检测结果验证 ==========
            log.info("NMS前检测框数量: " + boxes2d.size());
            if(boxes2d.size()<=0){
                log.error("未识别到{}",netPush.getTabAiModel().getAiName());
                return false;
            }
            //  应用非极大值抑制
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();

            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArr = indices.toArray();

            log.info("NMS后检测框数量: {}", indicesArr.length);


            if (netPush.getTabAiSubscriptionNew().getSaveBeforePic() == 0) {
                saveDebugImg(image, indicesArr.length, netPush.getTabAiSubscriptionNew().getPathSave(),"before");
            }
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            if (needDrawArea(netPush)) {
                image = drawRegionsOnImage(image, netPush);
            }

            boolean needAreaPush = needPushArea(netPush);
            Set<String> hitAreaNames = new LinkedHashSet<>();
            DetectionStats stats = new DetectionStats();

            // ✅ 收集当前帧所有有效目标（用于目标跟踪，与 detectObjectsDifyOnnxV5 保持一致）
            List<DetectedTarget> currentFrameTargets = new ArrayList<>();
            // ✅ 延迟绘制队列：先收集，跟踪完再画，以便在标签上显示跟踪编号
            // 每个元素: [BoundingBox, baseLabel, confidence, color, DetectedTarget]
            List<Object[]> drawQueue = new ArrayList<>();
            int validCount = 0;

            for (int idx : indicesArr) {
                Rect2d box = boxes2d.get(idx);
                int classId = classIds.get(idx);
                float conf = confidences.get(idx);
                float[] kpts = keypoints.get(idx);

                // 还原边界框到原图坐标
                double x = (box.x - dx) / scale;
                double y = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                // 确保坐标在图像范围内
                x = Math.max(0, Math.min(x, image.cols() - 1));
                y = Math.max(0, Math.min(y, image.rows() - 1));
                width = Math.min(width, image.cols() - x);
                height = Math.min(height, image.rows() - y);

                BoundingBox originalBox = new BoundingBox(x, y, width, height);
                String areaName = isValidDetection(pushInfo, netPush, retureBoxInfos, originalBox, box);
                if (areaName == null) {
                    log.info("姿态目标不在区域内，跳过");
                    continue;
                }
                if (needAreaPush && StringUtils.isNotEmpty(areaName)) {
                    hitAreaNames.add(areaName);
                }

                // 执行跌倒检测
                FallDetectionResult fallResult = detectFallOrStand(kpts, scale, dx, dy);
                log.info("人体检测: 置信度={}, 状态={}, 原因={}, 报警={}",
                        conf, fallResult.getStatus(), fallResult.getConfidence(),
                        fallResult.getReason(), fallResult.isAlert());
                if (!fallResult.isAlert()) {
                    continue;
                }

                TabAiBase aiBase = VideoSendReadCfg.map.get(fallResult.getStatus());
                if (aiBase != null) {
                    if (StringUtils.isNotEmpty(aiBase.getSpaceThree()) && aiBase.getSpaceThree().equals("N")) {
                        log.warn("【当前不推送：{}】", fallResult.getStatus());
                        continue;
                    }
                } else {
                    aiBase = new TabAiBase();
                    aiBase.setChainName(fallResult.getStatus());
                    log.warn("【未找到当前基础库名称：{}】", fallResult.getStatus());
                }
                stats.accumulate(aiBase);

                // ✅ 计算目标中心点（用于目标跟踪）
                double centerX = x + width / 2.0;
                double centerY = y + height / 2.0;

                // ✅ 创建检测目标对象
                // className 使用姿态状态（如"跌倒"/"站立"），保证同类目标才会匹配
                DetectedTarget target = new DetectedTarget();
                target.setCenterX(centerX);
                target.setCenterY(centerY);
                target.setClassName(fallResult.getStatus());
                target.setConfidence(conf);
                target.setBoundingBox(originalBox);
                target.setDetectedTime(System.currentTimeMillis());
                currentFrameTargets.add(target);

                // ✅ 加入延迟绘制队列（trackNum 等跟踪完成后补充）
                Scalar color = getColor(aiBase.getRgbColor());
                drawQueue.add(new Object[]{originalBox, aiBase.getChainName(), conf, color, target});

                validCount++;
            }

            // ========== 8. 目标跟踪逻辑（与 detectObjectsDifyOnnxV5 完全一致）==========
            // 始终执行跟踪以获取每个目标的显示编号；是否触发报警由 warnType 决定
            TargetTrackingResult trackingResult = processTargetTracking(
                    netPush.getId(),
                    currentFrameTargets,
                    redisTemplate
            );
            Map<DetectedTarget, Integer> targetNumMap = trackingResult.getTargetNumMap();

            boolean shouldTriggerAlarm = false;
            int newTargetCount = 0;

            if (warnType == 1) {
                // 目标跟踪预警模式：只在有新目标时触发
                shouldTriggerAlarm = trackingResult.hasNewTargets();
                newTargetCount = trackingResult.getNewTargetCount();
                log.info("【目标跟踪-Pose】当前目标数: {}, 新增目标数: {}, 是否触发报警: {}",
                        currentFrameTargets.size(), newTargetCount, shouldTriggerAlarm);
            } else {
                // 时间间隔预警模式 - 有检测结果就触发
                shouldTriggerAlarm = validCount > 0;
            }

            // ========== 8b. 带编号绘制检测框（格式：识别内容-置信度-目标编号）==========
            for (Object[] di : drawQueue) {
                BoundingBox    bbox     = (BoundingBox)    di[0];
                String         baseName = (String)          di[1];
                float          conf     = (float)            di[2];
                Scalar         color    = (Scalar)           di[3];
                DetectedTarget dt       = (DetectedTarget)   di[4];
                Integer        trackNum = targetNumMap.get(dt);

                // 格式：类名-置信度-编号，例如 跌倒-0.92-0001
                String confStr   = String.format("%.2f", conf);
                String numStr    = trackNum != null ? String.format("%04d", trackNum) : "----";
                String fullLabel = baseName + "-" + confStr + "-" + numStr;

                log.info("【目标标注-Pose】{} | 置信度:{} | 编号:{} | 框:[x={},y={},w={},h={}] | 中心:({},{})",
                        baseName, confStr, numStr,
                        (int) bbox.x, (int) bbox.y, (int) bbox.width, (int) bbox.height,
                        (int)(bbox.x + bbox.width / 2.0), (int)(bbox.y + bbox.height / 2.0));

                image = drawDetection(image, bbox, fullLabel, color);
            }

            // ========== 9. 推送结果 ==========
            if (!shouldTriggerAlarm || stats.warnNumber <= 0) {
                log.info("【不触发报警-Pose】shouldTrigger: {}, warnNumber: {}", shouldTriggerAlarm, stats.warnNumber);
                return false;
            }
            //  仅在识别到内容并触发报警时，才设置间隔缓存（防止频繁推送）
            if (warnType == 0) {
                redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                        netPush.getTabAiVideoSetting().getWarnTime(), TimeUnit.SECONDS);
                log.info("【时间间隔预警】已触发报警，开始计时 {}s", netPush.getTabAiVideoSetting().getWarnTime());
            }
            // 保存图像并推送
            String savePath = uploadPath + File.separator + "push" + File.separator;
            String savedImagePath = saveDetectionImage(image, savePath);

            long endTime = System.currentTimeMillis();
            log.info("识别耗时: {}ms, 有效检测: {}/{}, 新增目标: {}",
                    (endTime - startTime), validCount, indicesArr.length, newTargetCount);

            try {
                // 如果是目标跟踪模式，在报警文本中加入新增目标信息（与 detectObjectsDifyOnnxV5 一致）
                String warnText = stats.warnText;
                if (warnType == 1 && newTargetCount > 0) {
                    warnText = String.format("检测到%d个新目标进入监控区域！%s", newTargetCount, stats.warnText);
                }

                String modelArea = (needAreaPush && !hitAreaNames.isEmpty())
                        ? String.join(",", hitAreaNames) : "";
                log.info("【区域推送-Pose】needAreaPush={} 命中区域: [{}]", needAreaPush, modelArea);

                isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                        stats.audioText, stats.warnNumber, warnText, stats.warnName, savePath, modelArea);
                return true;
            } catch (Exception ex) {
                log.error("推送失败", ex);
                return false;
            }
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return false;
        }
    }



    /**
     * 改造后的ONNX检测方法：支持ROI裁剪+放大检测
     */
    public boolean detectObjectsDifyOnnxV5WithROI(TabAiSubscriptionNew pushInfo, Mat image,
                                                  NetPush netPush, RedisTemplate redisTemplate,
                                                  List<retureBoxInfo> retureBoxInfos) {

        // ========== 1. 频率控制检查 ==========

        long intervalTime = netPush.getTabAiVideoSetting().getWarnTime();

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();

        long startTime = System.currentTimeMillis();

        // ========== 3. 智能选择检测模式 ==========
        boolean useROIMode = (retureBoxInfos != null && !retureBoxInfos.isEmpty());
        List<FinalDetectionResult> allDetections = new ArrayList<>();

        if (useROIMode) {
            log.info("【启用ROI检测模式】前置检测框数量: {}", retureBoxInfos.size());
            allDetections = detectInROIRegionsOnnx(image, session, env, classNames,
                    expectedClassCount, retureBoxInfos, netPush);
        } else {
            log.info("【启用ROI检测模式】未检测到内容");
            return false ;
        }
        log.info("检测后内容{}",allDetections.size());
        // ========== 4. 处理检测结果 ==========
        return processOnnxDetectionResults(allDetections, image, pushInfo, netPush,
                redisTemplate, uploadPath, tabAiModel,
                retureBoxInfos, startTime, intervalTime);
    }

    /**
     * 核心方法：在每个ROI区域进行ONNX推理
     */
    private List<FinalDetectionResult> detectInROIRegionsOnnx(Mat image, OrtSession session,
                                                              OrtEnvironment env,
                                                              List<String> classNames,
                                                              int expectedClassCount,
                                                              List<retureBoxInfo> retureBoxInfos,
                                                              NetPush netPush) {
        List<FinalDetectionResult> finalResults = new ArrayList<>();
        float confThreshold = 0.35f;  // ROI内降低阈值
        float nmsThreshold = 0.5f;
        log.info("当前需要放大的数量：{}", retureBoxInfos.size());
        for (int roiIndex = 0; roiIndex < retureBoxInfos.size(); roiIndex++) {
            retureBoxInfo personBox = retureBoxInfos.get(roiIndex);
            // ✅ 跳过太小的 ROI
            if (personBox.getWidth() < 50 && personBox .getHeight() < 50) {
                log.warn("ROI[{}]太小，跳过{}x{}", roiIndex,personBox.getWidth(), personBox.getHeight() );
                continue;
            }
            log.info("处理ROI[{}]: x={}, y={}, w={}, h={}",
                    roiIndex, personBox.getX(), personBox.getY(),
                    personBox.getWidth(), personBox.getHeight());

            // ✅ 核心改进：智能裁剪策略
            CropResult cropResult = smartCropROI(personBox, image, netPush);
            if (cropResult == null) {
                log.warn("ROI[{}]裁剪失败，跳过", roiIndex);
                continue;
            }

            Mat croppedMat = cropResult.croppedImage;
            Rect cropRect = cropResult.cropRect;

            // ✅ 保存调试图（可选）
            if (netPush.getTabAiSubscriptionNew().getSaveRoiPic() == 0) {
                saveDebugImg(croppedMat, roiIndex, netPush.getTabAiSubscriptionNew().getPathSave(),"ROIDebug");
            }

            // ✅ 智能缩放：保持宽高比
            ResizeResult resizeResult = smartResize(croppedMat, 640);
            Mat resizedMat = resizeResult.resizedImage;
            // ✅ 修复：BGR → RGB
            Imgproc.cvtColor(resizedMat, resizedMat, Imgproc.COLOR_BGR2RGB);
            // 预处理
            float[] inputData = preprocessImage(resizedMat);

            // ONNX推理
            DetectionResult detectionResult;
            try {

                detectionResult = runOnnxInferenceROI(session, env, inputData, expectedClassCount);
                log.info("ROI[{}]检测到{}个候选框", roiIndex, detectionResult.boxes2d.size());
            } catch (Exception ex) {
                log.error("ROI[{}]推理失败", roiIndex, ex);
                croppedMat.release();
                resizedMat.release();
                continue;
            }

            // NMS去重
            if (detectionResult.boxes2d.isEmpty()) {
                croppedMat.release();
                resizedMat.release();
                continue;
            }

            int[] nmsIndices = performNMS(detectionResult, confThreshold, nmsThreshold);
            log.info("ROI[{}]经NMS后保留{}个检测框", roiIndex, nmsIndices.length);

            // ✅ 坐标转换：考虑letterbox的padding
            for (int idx : nmsIndices) {
                Rect2d box = detectionResult.boxes2d.get(idx);

                // 反向letterbox转换
                double originalX = (box.x - resizeResult.padX) / resizeResult.scale;
                double originalY = (box.y - resizeResult.padY) / resizeResult.scale;
                double originalW = box.width / resizeResult.scale;
                double originalH = box.height / resizeResult.scale;

                // 转换到原图坐标
                originalX += cropRect.x;
                originalY += cropRect.y;

                // 边界检查
                originalX = Math.max(0, Math.min(originalX, image.cols() - 1));
                originalY = Math.max(0, Math.min(originalY, image.rows() - 1));
                originalW = Math.min(originalW, image.cols() - originalX);
                originalH = Math.min(originalH, image.rows() - originalY);

                FinalDetectionResult result = new FinalDetectionResult();
                result.x = originalX;
                result.y = originalY;
                result.width = originalW;
                result.height = originalH;
                result.confidence = detectionResult.confidences.get(idx);
                result.classId = detectionResult.classIds.get(idx);
                result.className = classNames.get(result.classId);
                result.fromROIIndex = roiIndex;
                result.personBox = personBox;
                finalResults.add(result);

                log.info("检测到: {} (置信度:{}) 原图坐标:({},{},{},{})",
                        result.className, result.confidence,
                        originalX, originalY, originalW, originalH);
            }

            // 释放资源
            croppedMat.release();
            resizedMat.release();
        }



        return finalResults;
    }


    private DetectionResult runOnnxInferenceROI(OrtSession session, OrtEnvironment env,
                                                float[] inputData, Integer expectedClassCount) throws Exception {
        long[] shape = new long[]{1, 3, 640, 640};
        DetectionResult result = new DetectionResult();
        float confThreshold = 0.35f;

        // ✅ 创建新的FloatBuffer并确保position为0
        FloatBuffer buffer = FloatBuffer.allocate(inputData.length);
        buffer.put(inputData);
        buffer.flip(); // 重置position到0,这很关键!

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, buffer, shape)) {
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
     * ✅ 智能裁剪：根据ROI大小和任务类型决定裁剪策略
     */
    private CropResult smartCropROI(retureBoxInfo personBox, Mat image, NetPush netPush) {
        int boxWidth = (int) personBox.getWidth();
        int boxHeight = (int) personBox.getHeight();

        // 策略1：如果人体框本身很大（>400px），直接裁剪，只加小padding
        if (boxWidth >= 200 && boxHeight >= 200) {
            int padding = 30;  // 固定30像素padding
            return cropWithPadding(personBox, image, padding);
        }else{
            int padding = getTaskSpecificPadding(netPush);
            log.info("扩展范围{}",padding);
            return cropWithPadding(personBox, image, padding);
        }

        // 策略3：如果人体框很小（<150px），裁剪固定大小的区域
        //     return cropFixedSizeRegion(personBox, image, 640);
    }


    /**
     * 固定padding裁剪
     */
    private CropResult cropWithPadding(retureBoxInfo box, Mat image, int padding) {
        int x = Math.max(0, (int)box.getX() - padding);
        int y = Math.max(0, (int)box.getY() - padding);
        int width = Math.min(image.cols() - x, (int)box.getWidth() + 2 * padding);
        int height = Math.min(image.rows() - y, (int)box.getHeight() + 2 * padding);

        Rect cropRect = new Rect(x, y, width, height);

        if (cropRect.area() < 2500) {
            return null;
        }

        CropResult result = new CropResult();
        result.croppedImage = new Mat(image, cropRect);
        result.cropRect = cropRect;
        return result;
    }

    /**
     * 固定尺寸裁剪（针对小目标）
     */
    private CropResult cropFixedSizeRegion(retureBoxInfo box, Mat image, int size) {
        int centerX = (int)(box.getX() + box.getWidth() / 2);
        int centerY = (int)(box.getY() + box.getHeight() / 2);

        int x = Math.max(0, centerX - size / 2);
        int y = Math.max(0, centerY - size / 2);
        int width = Math.min(image.cols() - x, size);
        int height = Math.min(image.rows() - y, size);

        Rect cropRect = new Rect(x, y, width, height);

        CropResult result = new CropResult();
        result.croppedImage = new Mat(image, cropRect);
        result.cropRect = cropRect;
        return result;
    }

    /**
     * 根据任务类型返回padding大小
     */
    private int getTaskSpecificPadding(NetPush netPush) {
//        String modelName = netPush.getTabAiModel().getAiName().toLowerCase();
//
//        if (modelName.contains("smoking") || modelName.contains("抽烟")) {
//            return netPush.getFollowPosition();  // 抽烟需要包含手到嘴的区域
//        } else if (modelName.contains("helmet") || modelName.contains("安全帽")) {
//            return 40;   // 安全帽只需头部周围
//        } else if (modelName.contains("phone") || modelName.contains("打电话")) {
//            return 80;
//        } else if (modelName.contains("mask") || modelName.contains("口罩")) {
//            return 50;
//        } else {
//            return 60;   // 默认
//        }
        return netPush.getFollowPosition();  // 抽烟需要包含手到嘴的区域
    }

    /**
     * ✅ 智能缩放：保持宽高比，用letterbox填充
     */
    private ResizeResult smartResize(Mat src, int targetSize) {
        double srcWidth = src.cols();
        double srcHeight = src.rows();

        // 计算缩放比例（保持宽高比）
        double scale = Math.min(targetSize / srcWidth, targetSize / srcHeight);

        int newWidth = (int)(srcWidth * scale);
        int newHeight = (int)(srcHeight * scale);

        // 缩放
        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(newWidth, newHeight));

        // 计算padding
        int padX = (targetSize - newWidth) / 2;
        int padY = (targetSize - newHeight) / 2;

        // 创建目标图像（灰色背景）
        Mat output = new Mat(targetSize, targetSize, src.type(), new Scalar(114, 114, 114));

        // 将缩放后的图像放到中心
        Mat roi = output.submat(padY, padY + newHeight, padX, padX + newWidth);
        resized.copyTo(roi);

        ResizeResult result = new ResizeResult();
        result.resizedImage = output;
        result.scale = scale;
        result.padX = padX;
        result.padY = padY;

        resized.release();
        return result;
    }

    /**
     * 裁剪结果封装
     */
    private static class CropResult {
        Mat croppedImage;
        Rect cropRect;
    }

    /**
     * 缩放结果封装
     */
    private static class ResizeResult {
        Mat resizedImage;
        double scale;      // 缩放比例
        int padX;          // X方向padding
        int padY;          // Y方向padding
    }
    /**
     * 处理ONNX检测结果（过滤、绘制、推送）
     */
    private boolean processOnnxDetectionResults(List<FinalDetectionResult> allDetections,
                                                Mat image, TabAiSubscriptionNew pushInfo,
                                                NetPush netPush, RedisTemplate redisTemplate,
                                                String uploadPath, TabAiModel tabAiModel,
                                                List<retureBoxInfo> retureBoxInfos,
                                                long startTime, long intervalTime) {

        if (allDetections.isEmpty()) {
            log.warn("未检测到任何目标");
            handleNoDetection(pushInfo, netPush, redisTemplate, image, uploadPath, tabAiModel);
            return false;
        }

        log.info("共检测到{}个目标，开始过滤和绘制", allDetections.size());

        DetectionStats stats = new DetectionStats();
        int validCount = 0;
        boolean needAreaPush = needPushArea(netPush);
        Set<String> hitAreaNames = new LinkedHashSet<>();

        if (netPush.getTabAiSubscriptionNew().getSaveBeforePic() == 0) {
            saveDebugImg(image, 10000, netPush.getTabAiSubscriptionNew().getPathSave(),"before");
        }

        if (needDrawArea(netPush)) {
            image = drawRegionsOnImage(image, netPush);
        }

        for (FinalDetectionResult det : allDetections) {
            BoundingBox bbox = new BoundingBox(det.x, det.y, det.width, det.height);
            Rect2d areaBox = new Rect2d(det.x, det.y, det.width, det.height);

            String areaName = isValidDetection(pushInfo, netPush, retureBoxInfos, bbox, areaBox);
            if (areaName == null) {
                log.debug("检测框不在指定区域内，跳过");
                continue;
            }
            if (needAreaPush && StringUtils.isNotEmpty(areaName)) {
                hitAreaNames.add(areaName);
            }

            // 2. 前置模型关联过滤（如果使用了ROI检测）
            if (det.fromROIIndex >= 0 && netPush.getIsFollow() == 0) {
                // 检测结果已经在ROI内，无需再次过滤
                log.debug("检测结果来自ROI[{}]，已通过前置过滤", det.fromROIIndex);
            }

            // 3. 获取类别配置
            TabAiBase aiBase = getAiBaseConfig(det.className);
            if (aiBase == null || shouldSkipClass(aiBase)) {
                log.warn("【跳过类别：{}】", det.className);
                continue;
            }

            // 4. 累计统计信息
            stats.accumulate(aiBase);

            // 5. 绘制检测框
            Scalar color = getColor(aiBase.getRgbColor());

            // 添加ROI来源标记
            String label = aiBase.getChainName();
            if (det.fromROIIndex >= 0) {
                label += String.format(" [ROI%d]", det.fromROIIndex);
            }

            image = drawDetection(image, bbox, label, det.confidence, color);

            validCount++;
        }

        // 6. 推送结果验证
        if (validCount <= 0) {
            log.error("【无有效检测结果，不推送】");
            return false;
        }

        // 7. 设置Redis缓存
        redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(), intervalTime, TimeUnit.SECONDS);

        // 8. 保存图像并推送
        String savePath = uploadPath + File.separator + "push" + File.separator;
        String savedImagePath = saveDetectionImage(image, savePath);

        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}",
                (endTime - startTime), validCount, allDetections.size());

        try {
            String modelArea = (needAreaPush && !hitAreaNames.isEmpty())
                    ? String.join(",", hitAreaNames) : "";
            isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                    stats.audioText, stats.warnNumber, stats.warnText, stats.warnName, savePath, modelArea);
            return true;
        } catch (Exception ex) {
            log.error("推送失败", ex);
            return false;
        }
    }

    /**
     * 根据检测类型获取ROI扩展比例
     */
    private double getExpandRatio(NetPush netPush) {
        String modelName = netPush.getTabAiModel().getAiName().toLowerCase();

        // 根据不同的检测任务调整扩展比例
        if (modelName.contains("smoking") || modelName.contains("抽烟")) {
            return 1.4;  // 抽烟：需要更大范围（手部到嘴部）
        } else if (modelName.contains("helmet") || modelName.contains("安全帽")) {
            return 1.15; // 安全帽：只需头部区域
        } else if (modelName.contains("phone") || modelName.contains("打电话")) {
            return 1.3;  // 打电话：需要包含手部
        } else if (modelName.contains("mask") || modelName.contains("口罩")) {
            return 1.2;  // 口罩：头部及周边
        } else {
            return 1.4; // 默认扩展40%
        }
    }

    /**
     * 扩展ROI区域（智能边界处理）
     */
    private Rect expandROI(retureBoxInfo box, Mat image, double expandRatio) {
        // 计算中心点
        int centerX = (int) (box.getX() + box.getWidth() / 2);
        int centerY = (int) (box.getY() + box.getHeight() / 2);

        // 计算新的宽高
        int newWidth = (int) (box.getWidth() * expandRatio);
        int newHeight = (int) (box.getHeight() * expandRatio);

        // 重新计算左上角坐标
        int newX = centerX - newWidth / 2;
        int newY = centerY - newHeight / 2;

        // 边界裁剪
        newX = Math.max(0, newX);
        newY = Math.max(0, newY);
        newWidth = Math.min(image.cols() - newX, newWidth);
        newHeight = Math.min(image.rows() - newY, newHeight);

        return new Rect(newX, newY, newWidth, newHeight);
    }

    /**
     * NMS非极大值抑制
     */
    private int[] performNMS(DetectionResult detectionResult, float confThreshold, float nmsThreshold) {
        if (detectionResult.boxes2d.isEmpty()) {
            return new int[0];
        }

        MatOfRect2d boxesMat = new MatOfRect2d();
        boxesMat.fromList(detectionResult.boxes2d);

        MatOfFloat confMat = new MatOfFloat(Converters.vector_float_to_Mat(detectionResult.confidences));
        MatOfInt indices = new MatOfInt();

        Dnn.NMSBoxes(boxesMat, confMat, confThreshold, nmsThreshold, indices);
        // 检查NMS结果
        if (indices.empty() || indices.rows() == 0) {
            log.warn("NMS未返回任何索引，可能置信度阈值{}过高", confThreshold);
            return new int[0];
        }
        return indices.toArray();
    }


//    private int[] performNMS(DetectionResult detectionResult, float confThreshold, float nmsThreshold) {
//        List<Rect2d> boxes = detectionResult.boxes2d;
//        List<Float> scores = detectionResult.confidences;
//
//        if (boxes.isEmpty() || scores.isEmpty()) {
//            return new int[0];
//        }
//
//        // 1️⃣ 过滤置信度低的框
//        List<Integer> indices = new ArrayList<>();
//        for (int i = 0; i < boxes.size(); i++) {
//            if (scores.get(i) >= confThreshold) {
//                indices.add(i);
//            }
//        }
//
//        // 2️⃣ 按分数降序排序
//        indices.sort((i1, i2) -> Float.compare(scores.get(i2), scores.get(i1)));
//
//        List<Integer> keep = new ArrayList<>();
//
//        // 3️⃣ 执行 NMS
//        while (!indices.isEmpty()) {
//            int bestIdx = indices.remove(0);
//            keep.add(bestIdx);
//
//            List<Integer> remain = new ArrayList<>();
//            Rect2d bestBox = boxes.get(bestIdx);
//
//            for (int idx : indices) {
//                Rect2d box = boxes.get(idx);
//                double iou = computeIOU(bestBox, box);
//                if (iou <= nmsThreshold) {
//                    remain.add(idx);
//                }
//            }
//            indices = remain;
//        }
//
//        // 4️⃣ 返回 int[]
//        return keep.stream().mapToInt(i -> i).toArray();
//    }

    // 计算 IOU
    private double computeIOU(Rect2d a, Rect2d b) {
        double x1 = Math.max(a.x, b.x);
        double y1 = Math.max(a.y, b.y);
        double x2 = Math.min(a.x + a.width, b.x + b.width);
        double y2 = Math.min(a.y + a.height, b.y + b.height);

        double interArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        double unionArea = a.width * a.height + b.width * b.height - interArea;
        return interArea / unionArea;
    }



    /**
     * 保存图片用于调试
     */
    private void saveDebugImg(Mat roiMat, int roiIndex, String uploadPath,String saveFilePath) {
        try {
            String debugPath = uploadPath + File.separator + saveFilePath + File.separator;
            File debugDir = new File(debugPath);
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }
            long count = Files.list(Paths.get(debugPath)).filter(Files::isRegularFile).count();
            if(count>50000){
                log.info("裁剪图片大于50000就删除 以免磁盘满");
                //删除所有重新存储
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(debugPath))) {
                        paths.filter(Files::isRegularFile)
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .limit(5000)
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }
            String filename = debugPath + saveFilePath + roiIndex + "_" + System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(filename, roiMat);
            log.debug("ROI[{}]已保存至: {}", roiIndex, filename);
        } catch (Exception ex) {
            log.error("保存ROI失败", ex);
        }
    }







    /**
     * 统计信息封装类
     */
    static class DetectionStats {
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";

        void accumulate(TabAiBase aiBase) {
            audioText += aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText,
                    StringUtils.isEmpty(aiBase.getRemark()) ?
                            aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName, aiBase.getChainName());
        }
    }

    /**
     * 检测结果封装类
     */
    static class FinalDetectionResult {
        double x, y, width, height;
        float confidence;
        int classId;
        String className;
        int fromROIIndex = -1;  // -1=全图检测, >=0=ROI索引
        retureBoxInfo personBox;
    }

    /**
     * 边界框封装类
     */
    static class BoundingBox {
        double x, y, width, height;

        BoundingBox(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /***
     * 获取区域
     * @param pushInfo
     * @param image
     * @param netPush
     * @return
     */
    public retureBoxInfo detectObjectsV5Onnx(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,RedisTemplate redisTemplate) {

        retureBoxInfo returnBox=new retureBoxInfo();
        returnBox.setFlag(false);
        Object lastPushTime = redisTemplate.opsForValue().get(netPush.getId());
        if (lastPushTime != null) {
            log.info("[推送间隔未到，跳过本次检测]");
            return returnBox;
        }

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();

        float confThreshold=tabAiModel.getThreshold()==null?0.4f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.35f:tabAiModel.getNmsThreshold().floatValue();

        String targetClass=netPush.getBeforText();
        long startTime = System.currentTimeMillis();
        log.info("开始ONNX检测，目标类别: {}, 图像尺寸: {}x{}",targetClass,  image.cols(), image.rows());

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();

        DetectionResult detectionResult;
        try {
            detectionResult = runOnnxInference(session, env, inputData, expectedClassCount,confThreshold);
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return returnBox;
        }

        // ========== 5. 检测结果验证 ==========
        int detectionCount = detectionResult.confidences.size();
        if (detectionCount <= 0 || detectionCount > 200) {
            log.warn("{}:检测数量异常: {}-{}", pushInfo.getName(), tabAiModel.getAiName(), detectionCount);
            return returnBox;
        }

        log.info("NMS前检测框数量: {}", detectionResult.boxes2d.size());

        // ========== 6. NMS非极大值抑制 ==========
        int[] nmsIndices = performNMS(detectionResult, confThreshold, nmsThreshold);
        if (nmsIndices.length > 50) {

            if (netPush.getTabAiSubscriptionNew().getSaveRoiPic() == 0) {
                saveDebugImg(image, nmsIndices.length, netPush.getTabAiSubscriptionNew().getPathSave(),"ROIDebug");
            }
            log.warn("NMS后检测框数量过多: {}, 超过阈值50", nmsIndices.length);
            return returnBox;
        }
        log.info("NMS后检测框数量: {}", nmsIndices.length);



        // ========== 6. 计算坐标还原参数 ==========
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        // ========== 7. 过滤目标类别并收集坐标 ==========
        List<retureBoxInfo> matchedBoxes = new ArrayList<>();
        int matchedCount = 0;

        for (int idx : nmsIndices) {
            Rect2d box = detectionResult.boxes2d.get(idx);
            Integer classId = detectionResult.classIds.get(idx);
            String className = classNames.get(classId);
            float confidence = detectionResult.confidences.get(idx);

            // 坐标还原到原图（640x640）
            BoundingBox originalBox = restoreCoordinates(box, scale, dx, dy, image);

            // 前置检测方法：只做自定义区域过滤，不做最终报警区域推送/区域绘制逻辑
            String areaName = isValidDetectionForBefore(netPush, originalBox, box);
            if (areaName == null) {
                log.info("前置检测框不在有效区域内: {}", className);
                continue;
            }

            // 获取类别配置
            TabAiBase aiBase = VideoSendReadCfg.map.get(className);
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(className);
            }

            // 判断是否为目标类别
            if (StringUtils.isNotEmpty(targetClass) && aiBase.getChainName().equals(targetClass)) {
                log.info("【匹配目标类别】类别: {}, 置信度: {}, 坐标: ({}, {}, {}, {})",
                        className, confidence, originalBox.x, originalBox.y, originalBox.width, originalBox.height);

                // 创建检测框信息
                retureBoxInfo boxInfo = new retureBoxInfo();
                boxInfo.setX(originalBox.x);
                boxInfo.setY(originalBox.y);
                boxInfo.setWidth(originalBox.width);
                boxInfo.setHeight(originalBox.height);
                matchedBoxes.add(boxInfo);
                matchedCount++;
            }
        }

        // ========== 8. 封装返回结果 ==========
        long endTime = System.currentTimeMillis();

        if (matchedCount > 0) {
            returnBox.setFlag(true);
            returnBox.setInfoList(matchedBoxes);
            log.info("【检测成功】目标类别: {}, 检测数量: {}, 耗时: {}ms",
                    targetClass, matchedCount, (endTime - startTime));
        } else {
            log.info("【未检测到目标类别】目标: {}, 总检测数: {}, 耗时: {}ms",
                    targetClass, nmsIndices.length, (endTime - startTime));
        }

        return returnBox;


    }


    /**
     * 推送报警（兼容旧调用，不携带区域名称）。
     * 委托 {@link #isOk(TabAiSubscriptionNew, NetPush, RedisTemplate, String, TabAiModel, String, Integer, String, String, String, String)}
     */
    public boolean isOk(TabAiSubscriptionNew pushInfo, NetPush netPush, RedisTemplate redisTemplate,
                        String saveName, TabAiModel tabAiModel,
                        String audioText, Integer warnNumber,
                        String warnText, String warnName, String savePath) {
        return isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel,
                audioText, warnNumber, warnText, warnName, savePath, "");
    }

    /**
     * 推送报警（完整版，携带区域名称）。
     *
     * @param modelArea 目标命中的区域名称（多个用逗号分隔）；
     *                  空串表示未开启区域推送或旧版单矩形模式
     */
    public boolean isOk(TabAiSubscriptionNew pushInfo, NetPush netPush, RedisTemplate redisTemplate,
                        String saveName, TabAiModel tabAiModel,
                        String audioText, Integer warnNumber,
                        String warnText, String warnName, String savePath,
                        String modelArea) {

        log.warn("model{}-{}", tabAiModel.getAiName(), warnText);
        Thread t = new Thread(() -> {
            try {

                String base64Img = base64Image(saveName);
                // 组装参数
                pushEntity push = new pushEntity();
                push.setCameraName(pushInfo.getName());
                push.setType("图片");
                push.setCameraUrl(pushInfo.getBeginEventTypes());
                push.setAlarmPicData(base64Img);
                push.setTime(System.currentTimeMillis() + "");
                push.setModelId(tabAiModel.getAiName());
                push.setIndexCode(pushInfo.getIndexCode());
                push.setModelName(warnName);
                push.setAiNumber(warnNumber);
                push.setModelText(warnText);
                // ✅ 区域名称（仅开启了 isByPush 且命中区域时才有值）
                if (modelArea != null && !modelArea.isEmpty()) {
                    push.setModelArea(modelArea);
                    log.info("【区域报警】命中区域: {}", modelArea);
                }

                //`使用播报
                if(netPush.getTabAiVideoSetting().getIsAudio() == 0){
                    String audio=audioText;
                    //开启区域推送 开启后播报区域内容消息
                    if(netPush.getTabAiVideoSetting().getIsBy() == 0 && netPush.getTabAiVideoSetting().getIsByPush() == 0){
                        audio=modelArea;
                    }
                    sendAudio(netPush.getTabAudioDevices(),audio);
                }

                String recordVideo = "";
                //是否录像
                if (netPush.getTabAiVideoSetting().getIsRecording() == 0) {
                    log.info("开启录像 录像时常{}", netPush.getTabAiVideoSetting().getRecordTime());
                    long recordTime = netPush.getTabAiVideoSetting().getRecordTime();
                    recordVideo = RecordVideo(pushInfo.getBeginEventTypes(), savePath, recordTime, netPush.getId());
                    if (StringUtils.isNotEmpty(recordVideo)) {
                        log.error("录像完成:{}", recordVideo);
                        if (netPush.getTabAiVideoSetting().getIsAnalysis() == 0) { //是否开启分析
                            //需要分析录像视频逐帧分析
                            log.error("开始分析视频");
                            recordVideo = analysisVideo(recordVideo, netPush, savePath);
                        }
                    }
                } else {
                    log.info("[未开启录像]");
                }



                if (pushInfo.getPushStatic() == 0) {// 0 开启 1未开启
                    log.info("[已经开启推送第三方]：");

                    if(StringUtils.isNotEmpty(recordVideo)){
                        String base64Mp4 = base64Image(recordVideo);
                        push.setVideo(base64Mp4);
                    }
                    JSONObject ob = RestUtil.post(pushInfo.getEventUrl(), (JSONObject) JSONObject.toJSON(push));
                    log.info("返回内容：" + ob);

                } else {
                    log.info("[当前设置为：不推送第三方]");
                }

                if(netPush.getTabAiVideoSetting().getSaveLocalhost()==0){ //保存到本地
                    log.info("[本地也保存]");
                    push.setAlarmPicData(saveName);
                    push.setVideo(recordVideo);
                    // 获取 pushInfo 的路径部分

                    JSONObject ob = RestUtil.post("http://127.0.0.1:9998/wgai/video/tabAiWarning/addPush", (JSONObject) JSONObject.toJSON(push));

                    log.info("返回内容：" + ob);
                }else{
                    log.info("[不保存本地录像]");
                    File imageFile = new File(recordVideo);
                    if (imageFile.exists()) {
                        imageFile.delete();
                    }
                }



            } catch (Exception exception) {
                exception.printStackTrace();
                log.error("[推送失败：{}]", pushInfo.getId());
            }
        });
        t.start();

        log.error("推送结束-间隔时间{}-{}", pushInfo.getId());
        return true;
    }

    //开始录像
    public String RecordVideo(String videoUrl, String savePath, long time, String id) {
        String path = savePath + id + "_" + System.currentTimeMillis() + ".mp4";
        try {

            // 创建抓取器
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
            grabber.setOption("rtsp_transport", "tcp"); // 避免 UDP 丢包
            grabber.setOption("stimeout", "3000000");   // 设置超时时间（可选）
            grabber.start();

            // 创建录制器
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    path,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels()
            );
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setFrameRate(grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 25);
            recorder.setVideoBitrate(2000000); // 2Mbps，可调
            recorder.start();

            long startTime = System.currentTimeMillis();
            long recordDuration = time * 1000; // 默认ms *1000 =s

            Frame frame;
            while ((frame = grabber.grab()) != null) {
                recorder.record(frame);
                if (System.currentTimeMillis() - startTime > recordDuration) {
                    break;
                }
            }

            recorder.stop();
            recorder.release();
            grabber.stop();
            grabber.release();
            log.info("录制完成保存为{}", path);
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("[出错了检查一下]");
            return "";
        }


        return path;

    }

    //分解录像
    public synchronized String analysisVideo(String recoredPath, NetPush netPush, String savePath) {
        try {
            String saveMp4Path = recoredPath.substring(0, recoredPath.lastIndexOf("."));
            //    Thread tt = new Thread(() -> {
            log.info("当前开始分解录像{}", saveMp4Path);
            File file = new File(saveMp4Path);
            if (!file.exists()) {
                file.mkdirs();
            }
            saveMp4Path = saveMp4Path + "/avi.mp4";
            VideoCapture capture = new VideoCapture(recoredPath, Videoio.CAP_ANY);
            if (!capture.isOpened()) {
                log.info("Error: Unable to open video file.");
            }
            double fps = capture.get(Videoio.CAP_PROP_FPS);
            double widthVideo = capture.get(Videoio.CAP_PROP_FRAME_WIDTH);
            double heightVideo = capture.get(Videoio.CAP_PROP_FRAME_HEIGHT);
            double frameCount = capture.get(Videoio.CAP_PROP_FRAME_COUNT);
            // 创建 VideoWriter 对象
            VideoWriter writer = new VideoWriter();
            int[] codecs = {
                    VideoWriter.fourcc('X', 'V', 'I', 'D'),
                    VideoWriter.fourcc('M', 'J', 'P', 'G'),
                    VideoWriter.fourcc('a', 'v', 'c', '1'),
            };
            for (int codec : codecs) {
                writer.open(saveMp4Path, codec, fps, new Size(widthVideo, heightVideo), true);
                if (writer.isOpened()) {
                    log.info("打开成功，使用 codec：" + codec);
                    break;
                } else {
                    log.info("打开失败 codec：" + codec);
                }
            }
            Mat image = new Mat();
            Net net = netPush.getNet();
            List<String> classNames = netPush.getClaseeNames();
            int a = 0;
            while (capture.read(image)) {

                log.info("当前帧:{}",a++);

                // 将图像传递给模型进行目标检测
                Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(640, 640), new Scalar(0), true, false);
                net.setInput(blob);
                // 将图像传递给模型进行目标检测
                List<Mat> result = new ArrayList<>();
                List<String> outBlobNames = net.getUnconnectedOutLayersNames();
                net.forward(result, outBlobNames);

                // 处理检测结果
                float confThreshold = 0.42f;
                float nmsThreshold = 0.41f;
                List<Rect2d> boxes2d = new ArrayList<>();
                List<Float> confidences = new ArrayList<>();
                List<Integer> classIds = new ArrayList<>();

                for (Mat output : result) {
                    int dims = output.dims();
                    int index = (int) output.size(0);
                    int rows = (int) output.size(1);
                    int cols = (int) output.size(2);
                    //
                    // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
                    //    log.info("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols+" row,"+output.row(0)+"index:"+index);
                    Mat detectionMat = output.reshape(1, output.size(1));

                    for (int i = 0; i < detectionMat.rows(); i++) {
                        Mat detection = detectionMat.row(i);
                        Mat scores = detection.colRange(5, cols);
                        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                        float confidence = (float) detection.get(0, 4)[0];
                        Point classIdPoint = minMaxResult.maxLoc;

                        if (confidence > confThreshold) {
                            float centerX = (float) detection.get(0, 0)[0];
                            float centerY = (float) detection.get(0, 1)[0];
                            float width = (float) detection.get(0, 2)[0];
                            float height = (float) detection.get(0, 3)[0];

                            float left = centerX - width / 2;
                            float top = centerY - height / 2;

                            classIds.add((int) classIdPoint.x);
                            confidences.add(confidence);
                            boxes2d.add(new Rect2d(left, top, width, height));
                            //  log.info("识别到了");
                        }
                    }
                }

                if (confidences.size() <= 0||confidences.size()>200) {
                    log.warn("录像当前未检测到内容");
                }
                // 执行非最大抑制，消除重复的边界框
                MatOfRect2d boxes_mat = new MatOfRect2d();
                boxes_mat.fromList(boxes2d);
                log.info("confidences.size{}", confidences.size());
                MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
                MatOfInt indices = new MatOfInt();
                Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
                if (!boxes_mat.empty() && !confidences_mat.empty()) {
                    log.info("不为空");
                    Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
                }

                int[] indicesArray = indices.toArray();
                // 获取保留的边界框

                log.info(confidences.size() + "类别下标啊" + indicesArray.length);
                if(indicesArray.length>50){
                    log.error("怎么可能类别太大 20就是上限");
                    writer.write(image);
                    continue;
                }
                // 在图像上绘制保留的边界框
                int c = 0;
                for (int idx : indicesArray) {
                    // 添加类别标签
                    Rect2d box = boxes2d.get(idx);
                    Integer ab = classIds.get(idx);
                    String name = classNames.get(ab);
                    float conf = confidences.get(idx);
                    double x = box.x;
                    double y = box.y;
                    double width = box.width * ((double) image.cols() / 640);
                    double height = box.height * ((double) image.rows() / 640);
                    double xzb = x * ((double) image.cols() / 640);
                    double yzb = y * ((double) image.rows() / 640);

                    TabAiBase aiBase = VideoSendReadCfg.map.get(name);
                    if (aiBase == null) {
                        aiBase = new TabAiBase();
                        aiBase.setChainName(name);

                    }
                    // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
                    Imgproc.rectangle(image,
                            new Point(xzb, yzb),
                            new Point(xzb + width, yzb + height),
                            CommonColors(c), 2);
                    //    log.info( "类别下标"+ab);
                    image = AIModelYolo3.addChineseText(image, aiBase.getChainName() + conf, new Point(xzb, yzb), CommonColors(c));
                    //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
                    c++;
                }

                writer.write(image);

            }

            writer.release();
            capture.release();
            log.error("视频合成完成：");
//            });
//            tt.start();
//            tt.join();
            return saveMp4Path + "/avi.mp4";
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("录制失败");
            return "";
        }


    }

// ========== 辅助方法 ==========

    /**
     * 图像预处理：HWC -> CHW，归一化
     */
    private float[] preprocessImage(Mat processedImage) {
        Mat blob = null;
        List<Mat> channels = null;

        try {
            blob = new Mat();
            processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);

            channels = new ArrayList<>();
            Core.split(blob, channels);

            float[] inputData = new float[3 * 640 * 640];
            for (int c = 0; c < 3; c++) {
                float[] data = new float[640 * 640];
                channels.get(c).get(0, 0, data);
                System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
            }
            return inputData;

        } finally {
            // ✅ 释放 blob
            if (blob != null) {
                blob.release();
            }

            // ✅ 释放所有 channels
            if (channels != null) {
                for (Mat channel : channels) {
                    if (channel != null) {
                        channel.release();
                    }
                }
            }
        }
    }


    public Scalar getColor(String color){
        if(StringUtils.isNotEmpty(color)){
            String[] parts = color.split(",");
            log.info("颜色内容{}-数组长度{}",color,parts.length);
            if(parts.length<3){
                return  CommonColors(1);
            }
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());

// 注意：OpenCV 中是 BGR 顺序
            Scalar scalar = new Scalar(b, g, r);
            return  scalar;
        }else{
            return  CommonColors(1);
        }

    }

    /**
     * ONNX推理
     */
    private DetectionResult runOnnxInference(OrtSession session, OrtEnvironment env,
                                             float[] inputData, Integer expectedClassCount,    float confThreshold) throws Exception {
        long[] shape = new long[]{1, 3, 640, 640};

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
     * 解析ONNX输出（支持YOLOv5-v11）
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

    /**
     * NMS处理
     */
    private int[] performNMSROI(DetectionResult result, float confThreshold, float nmsThreshold) {
        MatOfRect2d boxesMat = new MatOfRect2d();
        boxesMat.fromList(result.boxes2d);

        MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(result.confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxesMat.empty() && !confidencesMat.empty()) {
            Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
        }

        return indices.toArray();
    }

    /**
     * 坐标还原
     */
    private BoundingBox restoreCoordinates(Rect2d box, double scale, double dx, double dy, Mat image) {
        double x = Math.max(0, Math.min((box.x - dx) / scale, image.cols() - 1));
        double y = Math.max(0, Math.min((box.y - dy) / scale, image.rows() - 1));
        double w = Math.min(box.width / scale, image.cols() - x);
        double h = Math.min(box.height / scale, image.rows() - y);

        return new BoundingBox(x, y, w, h);
    }

    private boolean needDrawArea(NetPush netPush) {
        return netPush != null
                && netPush.getIsBy() == 0
                && netPush.getTabAiVideoSetting() != null
                && netPush.getTabAiVideoSetting().getIsByWrite() != null
                && netPush.getTabAiVideoSetting().getIsByWrite() == 0;
    }

    private boolean needPushArea(NetPush netPush) {
        return netPush != null
                && netPush.getIsBy() == 0
                && netPush.getTabAiVideoSetting() != null
                && netPush.getTabAiVideoSetting().getIsByPush() != null
                && netPush.getTabAiVideoSetting().getIsByPush() == 0;
    }

    /**
     * 前置检测专用：只做自定义区域过滤，不做前置跟随过滤。
     * 返回 null 表示不在区域内；返回空串/区域名表示通过。
     */
    private String isValidDetectionForBefore(NetPush netPush, BoundingBox originalBox, Rect2d box) {
        if (netPush.getIsBy() != 0) {
            return "";
        }

        TabVideoUtil videoUtil = netPush.getTabVideoUtil();
        if (videoUtil == null) {
            log.warn("已开启区域识别，但未配置区域信息");
            return null;
        }

        if (videoUtil.getBzType() == null) {
            boolean inArea = isPointInArea(originalBox.x, originalBox.y,
                    Double.parseDouble(videoUtil.getCanvasStartx()),
                    Double.parseDouble(videoUtil.getCanvasStarty()),
                    Double.parseDouble(videoUtil.getCanvasWidth()),
                    Double.parseDouble(videoUtil.getCanvasHeight()));
            if (!inArea) {
                log.info("前置目标不在旧版自定义矩形区域内 ({},{})", originalBox.x, originalBox.y);
                return null;
            }
            return "";
        }

        String areaName = matchShapeArea(
                originalBox.x, originalBox.y, originalBox.width, originalBox.height,
                videoUtil.getShapeData(), false);
        if (areaName == null) {
            log.info("前置目标不在自定义区域内 ({},{})", originalBox.x, originalBox.y);
            return null;
        }
        return areaName;
    }

    /**
     * 检测有效性验证，同时返回命中的区域名称。
     *
     * <p>返回值语义：</p>
     * <ul>
     *   <li>{@code null}  → 目标不在有效范围内，应丢弃</li>
     *   <li>非 null 字符串 → 目标有效，值为命中的区域名称（旧版矩形或未开启区域时返回 ""）</li>
     * </ul>
     *
     * <p>调用示例：</p>
     * <pre>{@code
     * String areaName = isValidDetection(pushInfo, netPush, retureBoxInfos, originalBox, box);
     * if (areaName == null) continue; // 过滤掉
     * // areaName 即为区域名，可直接用于推送
     * }</pre>
     *
     * @return 区域名称（有效），或 {@code null}（无效/过滤）
     */
    private String isValidDetection(TabAiSubscriptionNew pushInfo, NetPush netPush,
                                    List<retureBoxInfo> retureBoxInfos, BoundingBox originalBox,
                                    Rect2d box) {
        // ── 1. 前置模型区域过滤 ──
        if (netPush.getIsFollow() == 0 && netPush.getIsBefor() == 0) {
            boolean followFlag = retureBoxInfo.getLocalhost(
                    retureBoxInfos, originalBox.x, originalBox.y, netPush.getFollowPosition());
            if (!followFlag) {
                log.info("不在前置模型范围内");
                return null;
            }
        }

        // ── 2. 自定义区域过滤 ──
        if (netPush.getIsBy() == 0) {
            TabVideoUtil videoUtil = netPush.getTabVideoUtil();

            if (videoUtil.getBzType() == null) {
                // 旧版单矩形（canvas 坐标 = 原图坐标）
                boolean inArea = isPointInArea(box.x, box.y,
                        Double.parseDouble(videoUtil.getCanvasStartx()),
                        Double.parseDouble(videoUtil.getCanvasStarty()),
                        Double.parseDouble(videoUtil.getCanvasWidth()),
                        Double.parseDouble(videoUtil.getCanvasHeight()));
                if (!inArea) {
                    log.info("不在旧版自定义矩形区域内 ({},{})", originalBox.x, originalBox.y);
                    return null;
                }
                // 旧版无名称，返回空串表示"有效但无区域名"
                return "";
            } else {
                // ✅ 新版 shapeData：委托 matchShapeArea，同时拿到区域名称
                String areaName = matchShapeArea(
                        originalBox.x, originalBox.y, originalBox.width, originalBox.height,
                        videoUtil.getShapeData(), false);
                if (areaName == null) {
                    log.info("不在自定义区域内 ({},{})", originalBox.x, originalBox.y);
                    return null;
                }
                return areaName; // 区域名（可能含用户自定义名称）
            }
        }

        // 未开启区域过滤，返回空串表示"有效但无区域约束"
        return "";
    }

    /**
     * ✅ 坐标缩放转换：从模型输出坐标系转换到原图坐标系
     * @param coord 模型输出的坐标（640×640）
     * @param modelSize 模型输出尺寸（通常是640）
     * @param originalSize 原图对应维度的尺寸
     * @return 原图坐标系下的坐标
     */
    public static double scaleCoordinate(double coord, int modelSize, double originalSize) {
        return coord * (originalSize / modelSize);
    }
    public static boolean isPointInArea(double px, double py, double x, double y, double width, double height) {
        double x2 = x + width;
        double y2 = y + height;

        // 检查点是否在区域内
        return px >= x && px <= x2 && py >= y && py <= y2;
    }

    // ========== 区域匹配核心方法（唯一实现，其他方法委托此处）==========

    /**
     * 【核心】判断目标框与 shapeData 中哪个区域匹配，并返回该区域的名称。
     *
     * <p>这是整个区域判断的唯一底层实现，所有其他区域相关方法均委托此方法。</p>
     *
     * <ul>
     *   <li>命中任意区域 → 返回该区域的 {@code name} 字段（若为空则回退为"矩形N"/"多边形N"）</li>
     *   <li>未命中任何区域 → 返回 {@code null}</li>
     * </ul>
     *
     * <p><b>判断策略：</b></p>
     * <ul>
     *   <li>矩形（rect）严格模式：目标框四角均在区域内</li>
     *   <li>矩形（rect）宽松模式：目标框与区域交叉面积 ≥ 目标框面积的 5%</li>
     *   <li>多边形（polygon）严格模式：目标框四角均在多边形内</li>
     *   <li>多边形（polygon）宽松模式：11×11 采样点中 ≥ 5% 在多边形内</li>
     * </ul>
     *
     * @param x1           目标框左上角 X（原图坐标）
     * @param y1           目标框左上角 Y（原图坐标）
     * @param w            目标框宽度
     * @param h            目标框高度
     * @param shapeDataJson shapeData JSON 字符串
     * @param strictMode   true=严格（完全包含），false=宽松（5%重叠即可）
     * @return 命中区域的名称（空名称时返回默认编号名），未命中返回 {@code null}
     */
    public static String matchShapeArea(
            double x1, double y1, double w, double h,
            String shapeDataJson, boolean strictMode) {

        if (StringUtils.isEmpty(shapeDataJson)) {
            log.warn("【matchShapeArea】shapeDataJson 为空");
            return null;
        }
        try {
            JSONObject shapeData = JSON.parseObject(shapeDataJson);
            JSONArray  shapes    = shapeData.getJSONArray("shapes");
            if (shapes == null || shapes.isEmpty()) {
                log.warn("【matchShapeArea】shapeData 中没有定义任何形状");
                return null;
            }

            double x2         = x1 + w;
            double y2         = y1 + h;
            double objectArea = w * h;

            log.info("【区域判断】目标框[{},{},{},{}] objectArea={} mode={}",
                    (int)x1, (int)y1, (int)x2, (int)y2, (int)objectArea,
                    strictMode ? "strict" : "loose");

            for (int i = 0; i < shapes.size(); i++) {
                JSONObject shape       = shapes.getJSONObject(i);
                String     type        = shape.getString("type");
                JSONObject coordinates = shape.getJSONObject("coordinates");

                // ✅ 读取区域名称，空时回退为默认编号
                String rawName  = shape.getString("name");
                String areaName = (rawName != null && !rawName.trim().isEmpty())
                        ? rawName.trim()
                        : ("rect".equals(type) ? "矩形" : "多边形") + (i + 1);

                boolean matched = false;

                if ("rect".equals(type)) {
                    double areaMinX = Math.min(coordinates.getDoubleValue("startX"),
                            coordinates.getDoubleValue("endX"));
                    double areaMaxX = Math.max(coordinates.getDoubleValue("startX"),
                            coordinates.getDoubleValue("endX"));
                    double areaMinY = Math.min(coordinates.getDoubleValue("startY"),
                            coordinates.getDoubleValue("endY"));
                    double areaMaxY = Math.max(coordinates.getDoubleValue("startY"),
                            coordinates.getDoubleValue("endY"));

                    if (strictMode) {
                        matched = x1 >= areaMinX && x2 <= areaMaxX
                                && y1 >= areaMinY && y2 <= areaMaxY;
                        log.info("矩形[{}]「{}」严格判断: 目标[{},{},{},{}] 区域[{},{},{},{}] → {}",
                                i+1, areaName,
                                (int)x1,(int)y1,(int)x2,(int)y2,
                                (int)areaMinX,(int)areaMinY,(int)areaMaxX,(int)areaMaxY,
                                matched ? "✅ 命中" : "❌ 未命中");
                    } else {
                        double iw = Math.min(x2, areaMaxX) - Math.max(x1, areaMinX);
                        double ih = Math.min(y2, areaMaxY) - Math.max(y1, areaMinY);
                        if (iw > 0 && ih > 0 && objectArea > 0) {
                            double ratio = (iw * ih) / objectArea;
                            matched = ratio >= 0.05;
                            log.info("矩形[{}]「{}」宽松判断: 目标[{},{},{},{}] 区域[{},{},{},{}] 占比={}% → {}",
                                    i+1, areaName,
                                    (int)x1,(int)y1,(int)x2,(int)y2,
                                    (int)areaMinX,(int)areaMinY,(int)areaMaxX,(int)areaMaxY,
                                    String.format("%.1f", ratio * 100),
                                    matched ? "✅ 命中" : "❌ 未命中");
                        } else {
                            log.info("矩形[{}]「{}」宽松判断: 无交集 → ❌ 未命中", i+1, areaName);
                        }
                    }

                } else if ("polygon".equals(type)) {
                    JSONArray points = coordinates.getJSONArray("points");
                    if (points == null || points.size() < 3) continue;

                    if (strictMode) {
                        matched = isPointInPolygon(x1, y1, points)
                                && isPointInPolygon(x2, y1, points)
                                && isPointInPolygon(x1, y2, points)
                                && isPointInPolygon(x2, y2, points);
                        log.info("多边形[{}]「{}」严格判断: → {}", i+1, areaName,
                                matched ? "✅ 命中" : "❌ 未命中");
                    } else {
                        int sampleN = 10, insideCount = 0, totalCount = 0;
                        double stepX = w / sampleN, stepY = h / sampleN;
                        for (int si = 0; si <= sampleN; si++) {
                            for (int sj = 0; sj <= sampleN; sj++) {
                                totalCount++;
                                if (isPointInPolygon(x1 + si * stepX, y1 + sj * stepY, points)) {
                                    insideCount++;
                                }
                            }
                        }
                        double ratio = (double) insideCount / totalCount;
                        matched = ratio >= 0.05;
                        log.info("多边形[{}]「{}」宽松判断: 采样={}/{} 占比={}% → {}",
                                i+1, areaName, insideCount, totalCount,
                                String.format("%.1f", ratio * 100),
                                matched ? "✅ 命中" : "❌ 未命中");
                    }
                }

                if (matched) {
                    log.info("✅ 目标框命中区域[{}]「{}」", i+1, areaName);
                    return areaName;
                }
            }

            log.info("❌ 目标框不在任何区域内");
            return null;

        } catch (Exception e) {
            log.error("【matchShapeArea】解析失败", e);
            return null;
        }
    }

    /**
     * 判断物体框是否在自定义区域内（原图坐标系版本）。
     * <p>委托 {@link #matchShapeArea} 实现，保持对外签名兼容。</p>
     */
    public static boolean isBoundingBoxInShapeData(
            double x1_original, double y1_original,
            double width_original, double height_original,
            String shapeDataJson, boolean strictMode) {
        return matchShapeArea(x1_original, y1_original, width_original, height_original,
                shapeDataJson, strictMode) != null;
    }



    /**
     * 射线法判断点是否在多边形内
     * 原理：从点向右发射一条射线，计算与多边形边界的交点数
     * 交点数为奇数则点在多边形内，偶数则在外
     */
    private static boolean isPointInPolygon(double px, double py, JSONArray points) {
        int n = points.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            JSONObject pi = points.getJSONObject(i);
            JSONObject pj = points.getJSONObject(j);

            double xi = pi.getDoubleValue("x");
            double yi = pi.getDoubleValue("y");
            double xj = pj.getDoubleValue("x");
            double yj = pj.getDoubleValue("y");

            // 判断射线是否与边相交
            if (((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }

        return inside;
    }
    /**
     * 获取类别配置
     */
    private TabAiBase getAiBaseConfig(String className) {
        TabAiBase aiBase = VideoSendReadCfg.map.get(className);
        if (aiBase == null) {
            aiBase = new TabAiBase();
            aiBase.setChainName(className);
        }
        return aiBase;
    }

    /**
     * 是否跳过该类别
     */
    private boolean shouldSkipClass(TabAiBase aiBase) {
        return StringUtils.isNotEmpty(aiBase.getSpaceThree()) &&
                aiBase.getSpaceThree().equals("N");
    }

    /**
     * 绘制检测框
     * <p>标签格式由调用方控制，例如：人-0.92-0001</p>
     *
     * @param image  原始图像
     * @param box    边界框
     * @param label  完整显示标签（含置信度和编号，调用方拼好）
     * @param color  框和文字颜色
     * @return 绘制后的图像
     */
    private Mat drawDetection(Mat image, BoundingBox box, String label, Scalar color) {
        Imgproc.rectangle(image,
                new Point(box.x, box.y),
                new Point(box.x + box.width, box.y + box.height),
                color, 2);
        return AIModelYolo3.addChineseText(image, label, new Point(box.x, box.y - 5), color);
    }

    /**
     * 兼容旧调用：保留 confidence 参数但由本方法统一格式化拼入标签
     * @deprecated 请直接使用 {@link #drawDetection(Mat, BoundingBox, String, Scalar)}
     */
    @Deprecated
    private Mat drawDetection(Mat image, BoundingBox box, String label, float confidence, Scalar color) {
        String fullLabel = label + String.format("%.2f", confidence);
        return drawDetection(image, box, fullLabel, color);
    }

    /**
     * 将自定义检测区域绘制到图像上（青色边框 + 编号标注）
     * <p>支持旧版矩形（canvasStartx/y/width/height）和新版 shapeData（rect / polygon）</p>
     *
     * @param image   原始图像（将直接在上面绘制）
     * @param netPush 包含区域配置的推送对象
     * @return 绘制后的图像（与传入为同一对象）
     */
    private Mat drawRegionsOnImage(Mat image, NetPush netPush) {
        if (netPush.getIsBy() != 0) {
            // 自定义区域过滤未开启，不绘制
            return image;
        }
        TabVideoUtil videoUtil = netPush.getTabVideoUtil();
        if (videoUtil == null) {
            return image;
        }

        // 青色：在任何背景下都清晰可辨
        Scalar regionColor  = new Scalar(0, 255, 255);
        Scalar labelColor   = new Scalar(0, 200, 200);
        int    thickness    = 2;

        try {
            if (videoUtil.getBzType() == null) {
                // ── 旧版矩形区域（canvas 坐标直接是原图坐标系）──
                double x = Double.parseDouble(videoUtil.getCanvasStartx());
                double y = Double.parseDouble(videoUtil.getCanvasStarty());
                double w = Double.parseDouble(videoUtil.getCanvasWidth());
                double h = Double.parseDouble(videoUtil.getCanvasHeight());

                Imgproc.rectangle(image,
                        new Point(x, y),
                        new Point(x + w, y + h),
                        regionColor, thickness);
                image = AIModelYolo3.addChineseText(image, "检测区域",
                        new Point(x, Math.max(y - 8, 16)), labelColor);

                log.info("【绘制区域】矩形区域 x={} y={} w={} h={}", (int)x, (int)y, (int)w, (int)h);

            } else {
                // ── 新版 shapeData：矩形 / 多边形，坐标映射到当前图像尺寸 ──
                String shapeDataJson = videoUtil.getShapeData();
                if (StringUtils.isEmpty(shapeDataJson)) {
                    log.warn("【绘制区域】shapeData 为空，跳过绘制");
                    return image;
                }

                JSONObject shapeData = JSON.parseObject(shapeDataJson);
                int  srcW   = shapeData.getIntValue("imageWidth");
                int  srcH   = shapeData.getIntValue("imageHeight");
                int  dstW   = image.cols();
                int  dstH   = image.rows();
                double scaleX = (srcW > 0) ? (double) dstW / srcW : 1.0;
                double scaleY = (srcH > 0) ? (double) dstH / srcH : 1.0;

                JSONArray shapes = shapeData.getJSONArray("shapes");
                if (shapes == null || shapes.isEmpty()) {
                    log.warn("【绘制区域】shapeData 中没有形状定义");
                    return image;
                }

                for (int i = 0; i < shapes.size(); i++) {
                    JSONObject shape     = shapes.getJSONObject(i);
                    String type          = shape.getString("type");
                    JSONObject coords    = shape.getJSONObject("coordinates");
                    // ✅ 优先使用用户自定义名称，无名称时回退编号
                    String rawName   = shape.getString("name");
                    String areaLabel = (rawName != null && !rawName.trim().isEmpty())
                            ? rawName.trim() : "区域" + (i + 1);

                    if ("rect".equals(type)) {
                        double sx = coords.getDoubleValue("startX") * scaleX;
                        double sy = coords.getDoubleValue("startY") * scaleY;
                        double ex = coords.getDoubleValue("endX")   * scaleX;
                        double ey = coords.getDoubleValue("endY")   * scaleY;
                        double minX = Math.min(sx, ex), maxX = Math.max(sx, ex);
                        double minY = Math.min(sy, ey), maxY = Math.max(sy, ey);

                        Imgproc.rectangle(image,
                                new Point(minX, minY),
                                new Point(maxX, maxY),
                                regionColor, thickness);
                        image = AIModelYolo3.addChineseText(image, areaLabel,
                                new Point(minX, Math.max(minY - 8, 16)), labelColor);

                        log.info("【绘制区域】矩形{} 原图[{},{} -> {},{}] 映射[{},{} -> {},{}]",
                                i + 1,
                                (int)coords.getDoubleValue("startX"), (int)coords.getDoubleValue("startY"),
                                (int)coords.getDoubleValue("endX"),   (int)coords.getDoubleValue("endY"),
                                (int)minX, (int)minY, (int)maxX, (int)maxY);

                    } else if ("polygon".equals(type)) {
                        JSONArray points = coords.getJSONArray("points");
                        if (points == null || points.size() < 3) continue;

                        List<Point> pts = new ArrayList<>();
                        StringBuilder ptLog = new StringBuilder();
                        for (int j = 0; j < points.size(); j++) {
                            JSONObject pt = points.getJSONObject(j);
                            double px = pt.getDoubleValue("x") * scaleX;
                            double py = pt.getDoubleValue("y") * scaleY;
                            pts.add(new Point(px, py));
                            ptLog.append(String.format("(%d,%d)", (int)px, (int)py));
                            if (j < points.size() - 1) ptLog.append("->");
                        }

                        MatOfPoint matPts = new MatOfPoint();
                        matPts.fromList(pts);
                        Imgproc.polylines(image,
                                java.util.Arrays.asList(matPts),
                                true, regionColor, thickness);
                        image = AIModelYolo3.addChineseText(image, areaLabel,
                                new Point(pts.get(0).x, Math.max(pts.get(0).y - 8, 16)), labelColor);

                        log.info("【绘制区域】多边形{} 顶点: {}", i + 1, ptLog);
                    }
                }
            }
        } catch (Exception e) {
            log.error("【绘制区域】绘制失败", e);
        }

        return image;
    }

    /**
     * 保存检测图像
     */
    private String saveDetectionImage(Mat image, String savePath) {
        File dir = new File(savePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String fileName = savePath + System.currentTimeMillis() + ".jpg";
        File imageFile = new File(fileName);
        if (imageFile.exists()) {
            imageFile.delete();
        }

        Imgcodecs.imwrite(fileName, image);
        log.info("图像保存路径: {}", fileName);

        return fileName;
    }

    /**
     * 处理未检测到目标的情况
     */
    private void handleNoDetection(TabAiSubscriptionNew pushInfo, NetPush netPush,
                                   RedisTemplate redisTemplate, Mat image, String uploadPath,
                                   TabAiModel tabAiModel) {
        if (netPush.getWarinngMethod() == 1) { // 未识别到报警
            log.info("未识别到目标，触发报警");
            String savePath = uploadPath + File.separator + "push" + File.separator;
            String fileName = savePath + System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(fileName, image);

            isOk(pushInfo, netPush, redisTemplate, fileName, tabAiModel,
                    netPush.getNoDifText(), 1, netPush.getNoDifText(),
                    netPush.getNoDifText(), savePath);
        }
    }

// ========== 内部类 ==========

    /**
     * 检测结果
     */
    private static class DetectionResult {
        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        void addDetection(double x, double y, double w, double h, float confidence, int classId) {
            boxes2d.add(new Rect2d(x, y, w, h));
            confidences.add(confidence);
            classIds.add(classId);
        }
    }


    public static String setNmsName(String WareText, String name){

        if (WareText == null || WareText.isEmpty()) {
            // WareText为空时，直接返回name
            return name;
        }
        log.info("[当前内容{}:替换内容{}]",WareText,name);
        if (WareText.contains(name)) {
            // 已经包含，不拼接
            return WareText;
        }
        return WareText + "," + name;

    }
    public static Mat letterboxResize(Mat image, int targetWidth, int targetHeight) {
        int originalWidth = image.cols();
        int originalHeight = image.rows();

        // 计算缩放比例
        double scale = Math.min((double) targetWidth / originalWidth, (double) targetHeight / originalHeight);

        // 计算新的尺寸
        int newWidth = (int) (originalWidth * scale);
        int newHeight = (int) (originalHeight * scale);

        // 缩放图像
        Mat resized = new Mat();
        try {

            Imgproc.resize(image, resized, new Size(newWidth, newHeight));

            // 创建目标尺寸的画布（灰色填充）
            Mat letterboxed = new Mat(targetHeight, targetWidth, image.type(), new Scalar(114, 114, 114));

            // 计算居中位置
            int dx = (targetWidth - newWidth) / 2;
            int dy = (targetHeight - newHeight) / 2;

            // 将缩放后的图像复制到画布中心
            Rect roi = new Rect(dx, dy, newWidth, newHeight);
            Mat roiMat = new Mat(letterboxed, roi);
            try {
                resized.copyTo(roiMat);
                return letterboxed;
            } finally {
                //不单独释放
            }

        } finally {
            if (resized != null) {
                resized.release();
            }
        }
    }



    // ========================================
    // 目标跟踪核心方法
    // ========================================

    /**
     * 处理目标跟踪逻辑
     * <p>工作流程：</p>
     * <ol>
     *   <li>从Redis获取历史目标列表</li>
     *   <li>清理超时目标（超过TARGET_TIMEOUT_MS未检测到）</li>
     *   <li>将当前帧目标与历史目标进行匹配</li>
     *   <li>识别新进入的目标</li>
     *   <li>更新Redis中的目标列表</li>
     * </ol>
     *
     * @param cameraId 摄像头ID
     * @param currentTargets 当前帧检测到的目标列表
     * @param redisTemplate Redis操作模板
     * @return 跟踪结果（是否有新目标、新目标数量等）
     */
    private TargetTrackingResult processTargetTracking(String cameraId,
                                                       List<DetectedTarget> currentTargets,
                                                       RedisTemplate redisTemplate) {
        String redisKey = cameraId + REDIS_KEY_SUFFIX_TARGETS;
        long currentTime = System.currentTimeMillis();

        // 1. 获取历史目标列表
        List<TrackedTarget> historicalTargets = getHistoricalTargets(redisKey, redisTemplate);

        // 2. 清理超时目标（超过X秒未检测到的目标）
        historicalTargets.removeIf(target ->
                (currentTime - target.getLastSeenTime()) > TARGET_TIMEOUT_MS
        );

        log.info("【目标跟踪】历史目标数: {} (清理超时后)", historicalTargets.size());

        // 3. 匹配当前目标与历史目标
        List<TrackedTarget> updatedTargets = new ArrayList<>();
        List<DetectedTarget> newTargets = new ArrayList<>();
        // ⭐ 用 IdentityHashMap 保证以对象引用为 key（DetectedTarget 未重写 equals）
        Map<DetectedTarget, Integer> targetNumMap = new java.util.IdentityHashMap<>();

        for (DetectedTarget currentTarget : currentTargets) {
            // 尝试找到匹配的历史目标
            TrackedTarget matchedTarget = findMatchingTarget(currentTarget, historicalTargets);

            if (matchedTarget != null) {
                // 找到匹配目标，更新其信息
                updateTrackedTarget(matchedTarget, currentTarget, currentTime);
                updatedTargets.add(matchedTarget);
                historicalTargets.remove(matchedTarget); // 移除已匹配的目标
                targetNumMap.put(currentTarget, matchedTarget.getTrackNum()); // 沿用旧编号

                log.debug("【目标匹配】目标ID: {} 编号: {} 更新位置: ({}, {})",
                        matchedTarget.getTargetId(),
                        matchedTarget.getTrackNum(),
                        currentTarget.getCenterX(),
                        currentTarget.getCenterY());
            } else {
                // 未找到匹配目标，认为是新目标
                TrackedTarget newTrackedTarget = createNewTrackedTarget(currentTarget, currentTime);
                updatedTargets.add(newTrackedTarget);
                newTargets.add(currentTarget);
                targetNumMap.put(currentTarget, newTrackedTarget.getTrackNum()); // 分配新编号

                log.info("【新目标进入】目标ID: {}, 编号: {}, 类别: {}, 位置: ({}, {})",
                        newTrackedTarget.getTargetId(),
                        newTrackedTarget.getTrackNum(),
                        currentTarget.getClassName(),
                        currentTarget.getCenterX(),
                        currentTarget.getCenterY());
            }
        }

        // 4. 保存更新后的目标列表到Redis
        saveTargetsToRedis(redisKey, updatedTargets, redisTemplate);

        // 5. 返回跟踪结果
        TargetTrackingResult result = new TargetTrackingResult();
        result.setHasNewTargets(!newTargets.isEmpty());
        result.setNewTargetCount(newTargets.size());
        result.setTotalTargetCount(updatedTargets.size());
        result.setNewTargets(newTargets);
        result.setTargetNumMap(targetNumMap);

        return result;
    }

    /**
     * 从Redis获取历史目标列表
     */
    private List<TrackedTarget> getHistoricalTargets(String redisKey, RedisTemplate redisTemplate) {
        try {
            Object value = redisTemplate.opsForValue().get(redisKey);
            if (value == null) {
                return new ArrayList<>();
            }

            String json = value.toString();
            JSONArray jsonArray = JSONArray.parseArray(json);

            List<TrackedTarget> targets = new ArrayList<>();
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                TrackedTarget target = new TrackedTarget();
                target.setTargetId(obj.getString("targetId"));
                target.setTrackNum(obj.getIntValue("trackNum"));
                target.setCenterX(obj.getDoubleValue("centerX"));
                target.setCenterY(obj.getDoubleValue("centerY"));
                target.setClassName(obj.getString("className"));
                target.setFirstDetectedTime(obj.getLongValue("firstDetectedTime"));
                target.setLastSeenTime(obj.getLongValue("lastSeenTime"));
                target.setConfidence(obj.getFloatValue("confidence"));
                targets.add(target);
            }

            return targets;
        } catch (Exception e) {
            log.error("获取历史目标失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存目标列表到Redis
     * <p>过期时间设置为超时时间的3倍，确保数据不会过早丢失</p>
     */
    private void saveTargetsToRedis(String redisKey, List<TrackedTarget> targets,
                                    RedisTemplate redisTemplate) {
        try {
            JSONArray jsonArray = new JSONArray();
            for (TrackedTarget target : targets) {
                JSONObject obj = new JSONObject();
                obj.put("targetId", target.getTargetId());
                obj.put("trackNum", target.getTrackNum());
                obj.put("centerX", target.getCenterX());
                obj.put("centerY", target.getCenterY());
                obj.put("className", target.getClassName());
                obj.put("firstDetectedTime", target.getFirstDetectedTime());
                obj.put("lastSeenTime", target.getLastSeenTime());
                obj.put("confidence", target.getConfidence());
                jsonArray.add(obj);
            }

            String json = jsonArray.toJSONString();
            // 设置过期时间为超时时间的3倍
            redisTemplate.opsForValue().set(redisKey, json,
                    TARGET_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);

            log.debug("【保存目标】保存{}个目标到Redis", targets.size());
        } catch (Exception e) {
            log.error("保存目标到Redis失败", e);
        }
    }

    /**
     * 查找匹配的历史目标
     * <p>匹配规则：</p>
     * <ul>
     *   <li>类别必须相同</li>
     *   <li>中心点距离小于TARGET_MATCH_DISTANCE_THRESHOLD</li>
     *   <li>选择距离最近的目标作为匹配结果</li>
     * </ul>
     */
    private TrackedTarget findMatchingTarget(DetectedTarget currentTarget,
                                             List<TrackedTarget> historicalTargets) {
        TrackedTarget bestMatch = null;
        double minDistance = Double.MAX_VALUE;

        for (TrackedTarget historical : historicalTargets) {
            // 只匹配相同类别的目标
            if (!currentTarget.getClassName().equals(historical.getClassName())) {
                continue;
            }

            // 计算欧氏距离
            double distance = calculateDistance(
                    currentTarget.getCenterX(), currentTarget.getCenterY(),
                    historical.getCenterX(), historical.getCenterY()
            );

            // 找到距离最小且小于阈值的目标
            if (distance < TARGET_MATCH_DISTANCE_THRESHOLD && distance < minDistance) {
                minDistance = distance;
                bestMatch = historical;
            }
        }

        if (bestMatch != null) {
            log.debug("【目标匹配】距离: {}", minDistance);
        }

        return bestMatch;
    }

    /**
     * 计算两点之间的欧氏距离
     * <p>公式：distance = √[(x1-x2)² + (y1-y2)²]</p>
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 更新已跟踪目标的信息
     */
    private void updateTrackedTarget(TrackedTarget trackedTarget, DetectedTarget currentTarget,
                                     long currentTime) {
        trackedTarget.setCenterX(currentTarget.getCenterX());
        trackedTarget.setCenterY(currentTarget.getCenterY());
        trackedTarget.setLastSeenTime(currentTime);
        trackedTarget.setConfidence(currentTarget.getConfidence());
    }

    /**
     * 创建新的跟踪目标
     */
    private TrackedTarget createNewTrackedTarget(DetectedTarget currentTarget, long currentTime) {
        TrackedTarget newTarget = new TrackedTarget();
        newTarget.setTargetId(generateTargetId());
        // ⭐ 分配4位显示编号（循环 1-9999）
        newTarget.setTrackNum(TRACK_COUNTER.incrementAndGet() % 10000);
        newTarget.setCenterX(currentTarget.getCenterX());
        newTarget.setCenterY(currentTarget.getCenterY());
        newTarget.setClassName(currentTarget.getClassName());
        newTarget.setFirstDetectedTime(currentTime);
        newTarget.setLastSeenTime(currentTime);
        newTarget.setConfidence(currentTarget.getConfidence());
        return newTarget;
    }

    /**
     * 生成唯一的目标ID
     * <p>格式：target_{timestamp}_{uuid前8位}</p>
     */
    private String generateTargetId() {
        return "target_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    // ========================================
    // 目标跟踪相关内部类
    // ========================================

    /**
     * 当前帧检测到的目标
     */
    private static class DetectedTarget {
        private double centerX;              // 中心点X坐标
        private double centerY;              // 中心点Y坐标
        private String className;            // 目标类别
        private float confidence;            // 置信度
        private BoundingBox boundingBox;     // 边界框
        private long detectedTime;           // 检测时间

        // Getters and Setters
        public double getCenterX() { return centerX; }
        public void setCenterX(double centerX) { this.centerX = centerX; }
        public double getCenterY() { return centerY; }
        public void setCenterY(double centerY) { this.centerY = centerY; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public float getConfidence() { return confidence; }
        public void setConfidence(float confidence) { this.confidence = confidence; }
        public BoundingBox getBoundingBox() { return boundingBox; }
        public void setBoundingBox(BoundingBox boundingBox) { this.boundingBox = boundingBox; }
        public long getDetectedTime() { return detectedTime; }
        public void setDetectedTime(long detectedTime) { this.detectedTime = detectedTime; }
    }

    /**
     * 历史跟踪目标
     */
    private static class TrackedTarget {
        private String targetId;          // 目标唯一ID
        private int trackNum;              // 显示编号（4位，用于画面标注）
        private double centerX;            // 中心点X坐标
        private double centerY;            // 中心点Y坐标
        private String className;          // 目标类别
        private long firstDetectedTime;    // 首次检测时间
        private long lastSeenTime;         // 最后一次检测时间
        private float confidence;          // 置信度

        // Getters and Setters
        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }
        public int getTrackNum() { return trackNum; }
        public void setTrackNum(int trackNum) { this.trackNum = trackNum; }
        public double getCenterX() { return centerX; }
        public void setCenterX(double centerX) { this.centerX = centerX; }
        public double getCenterY() { return centerY; }
        public void setCenterY(double centerY) { this.centerY = centerY; }
        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }
        public long getFirstDetectedTime() { return firstDetectedTime; }
        public void setFirstDetectedTime(long firstDetectedTime) { this.firstDetectedTime = firstDetectedTime; }
        public long getLastSeenTime() { return lastSeenTime; }
        public void setLastSeenTime(long lastSeenTime) { this.lastSeenTime = lastSeenTime; }
        public float getConfidence() { return confidence; }
        public void setConfidence(float confidence) { this.confidence = confidence; }
    }

    /**
     * 目标跟踪结果
     */
    private static class TargetTrackingResult {
        private boolean hasNewTargets;              // 是否有新目标
        private int newTargetCount;                 // 新目标数量
        private int totalTargetCount;               // 总目标数量
        private List<DetectedTarget> newTargets;    // 新目标列表
        /** 当前帧每个 DetectedTarget 对应的显示编号（4位整数） */
        private Map<DetectedTarget, Integer> targetNumMap = new java.util.IdentityHashMap<>();

        // Getters and Setters
        public boolean hasNewTargets() { return hasNewTargets; }
        public void setHasNewTargets(boolean hasNewTargets) { this.hasNewTargets = hasNewTargets; }
        public int getNewTargetCount() { return newTargetCount; }
        public void setNewTargetCount(int newTargetCount) { this.newTargetCount = newTargetCount; }
        public int getTotalTargetCount() { return totalTargetCount; }
        public void setTotalTargetCount(int totalTargetCount) { this.totalTargetCount = totalTargetCount; }
        public List<DetectedTarget> getNewTargets() { return newTargets; }
        public void setNewTargets(List<DetectedTarget> newTargets) { this.newTargets = newTargets; }
        public Map<DetectedTarget, Integer> getTargetNumMap() { return targetNumMap; }
        public void setTargetNumMap(Map<DetectedTarget, Integer> targetNumMap) { this.targetNumMap = targetNumMap; }
    }

}