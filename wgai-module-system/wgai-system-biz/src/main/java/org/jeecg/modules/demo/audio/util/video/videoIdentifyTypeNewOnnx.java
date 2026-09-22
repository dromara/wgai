package org.jeecg.modules.demo.audio.util.video;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.modules.demo.video.service.ITabAiWarningService;
import org.jeecg.modules.demo.video.service.impl.AlarmWorkService;
import org.apache.commons.lang.StringUtils;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.jeecg.common.util.RestUtil;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
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

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.CommonColors;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.base64Image;
import static org.jeecg.modules.tab.AIModel.pose.FallDetectionResult.detectFallOrStand;

/**
 * @author wggg
 * @date 2025/9/30 11:07
 */
@Slf4j
public class videoIdentifyTypeNewOnnx {

    /***
     * 识别方法
     * @param pushInfo
     * @param image
     * @param netPush
     * @param redisTemplate
     * @param retureBoxInfos
     * @return
     */
    public boolean detectObjectsDifyOnnxV5(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,
                                           RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos) {

        // ========== 1. 频率控制检查 ==========
        long intervalTime = Long.parseLong(pushInfo.getEventNumber());
        Object lastPushTime = redisTemplate.opsForValue().get(netPush.getId());
        if (lastPushTime != null) {
            log.info("推送间隔未到，跳过本次检测");
            return false;
        }
        log.info("间隔时间: {}s, 推送对象: {}, 前置检测框: {}", intervalTime, pushInfo.getName(),
                JSON.toJSONString(retureBoxInfos));

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();


        long startTime = System.currentTimeMillis();

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
      //  Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();

        DetectionResult detectionResult;
        try {
            detectionResult = runOnnxInference(session, env, inputData, expectedClassCount);
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return false;
        }

        // ========== 5. 检测结果验证 ==========
        int detectionCount = detectionResult.confidences.size();
        if (detectionCount <= 0 || detectionCount > 200) {
            log.warn("{}:检测数量异常: {}-{}", pushInfo.getName(), tabAiModel.getAiName(), detectionCount);
            handleNoDetection(pushInfo, netPush, redisTemplate, image, uploadPath, tabAiModel);
            return false;
        }

        log.info("NMS前检测框数量: {}", detectionResult.boxes2d.size());

        // ========== 6. NMS非极大值抑制 ==========
        int[] nmsIndices = performNMS(detectionResult, 0.4f, 0.4f);
        if (nmsIndices.length > 50) {
            setErrorImg(image, "maxIndex");
            log.warn("NMS后检测框数量过多: {}, 超过阈值50", nmsIndices.length);
            return false;
        }
        log.info("NMS后检测框数量: {}", nmsIndices.length);

        // ========== 7. 过滤和绘制检测框 ==========
        setBeforeImg(image, "end");
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        DetectionStats stats = new DetectionStats();
        int validCount = 0;

        for (int idx : nmsIndices) {
            Rect2d box = detectionResult.boxes2d.get(idx);
            Integer classId = detectionResult.classIds.get(idx);
            String className = classNames.get(classId);
            float confidence = detectionResult.confidences.get(idx);

            // 坐标还原到原图
            BoundingBox originalBox = restoreCoordinates(box, scale, dx, dy, image);

            // 区域过滤
            if (!isValidDetection(pushInfo, netPush, retureBoxInfos, originalBox, box)) {
                continue;
            }

            // 获取类别配置
            TabAiBase aiBase = getAiBaseConfig(className);
            if (aiBase == null || shouldSkipClass(aiBase)) {
                log.warn("【跳过类别：{}】", className);
                continue;
            }

            // 累计统计信息
            stats.accumulate(aiBase);

            // 绘制检测框
            Scalar color = getColor(aiBase.getRgbColor());
            image=drawDetection(image, originalBox, aiBase.getChainName(), confidence, color);

            validCount++;
        }

        // ========== 8. 推送结果 ==========
        if (stats.warnNumber <= 0) {
            log.error("【无有效检测结果，不推送】");
            return false;
        }

        // 设置Redis缓存防止频繁推送
        redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                intervalTime, TimeUnit.SECONDS);

        // 保存图像并推送
        String savePath = uploadPath + File.separator + "push" + File.separator;
        String savedImagePath = saveDetectionImage(image, savePath);

        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}", (endTime - startTime), validCount, nmsIndices.length);

        try {
            isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                    stats.audioText, stats.warnNumber, stats.warnText, stats.warnName, savePath);
            return true;
        } catch (Exception ex) {
            log.error("推送失败", ex);
            return false;
        }
    }


    public boolean detectObjectsDifyOnnxV5Pose(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,
                                           RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos) {

        // ========== 1. 频率控制检查 ==========
        long intervalTime = Long.parseLong(pushInfo.getEventNumber());
        Object lastPushTime = redisTemplate.opsForValue().get(netPush.getId());
        if (lastPushTime != null) {
            log.info("推送间隔未到，跳过本次检测");
            return false;
        }
        log.info("间隔时间: {}s, 推送对象: {}, 前置检测框: {}", intervalTime, pushInfo.getName(),
                JSON.toJSONString(retureBoxInfos));

        // ========== 2. 初始化参数 ==========
        List<String> classNames = netPush.getClaseeNames();
        Integer expectedClassCount = classNames.size();
        String uploadPath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();


        long startTime = System.currentTimeMillis();

        // ========== 3. 图像预处理 ==========
        Mat processedImage = letterboxResize(image, 640, 640);
        float[] inputData = preprocessImage(processedImage);

        // ========== 4. ONNX推理 ==========
        OrtSession session = netPush.getSession();
        OrtEnvironment env = netPush.getEnv();


        try {
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);

            // 6. 推理并解析结果
            float confThreshold = 0.45f;
            float nmsThreshold = 0.4f;

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


            setBeforeImg(image, "end");
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            DetectionStats stats = new DetectionStats();
            int colorIdx = 0;
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

                // 执行跌倒检测
                FallDetectionResult fallResult = detectFallOrStand(kpts, scale, dx, dy);
                log.info("人体检测: 置信度={}, 状态={} , 原因={}, 报警={}",
                        conf, fallResult.getStatus(), fallResult.getConfidence(),
                        fallResult.getReason(), fallResult.isAlert());
                if(fallResult.isAlert()==false){
                    continue;
                }

                // 绘制边界框
//                Imgproc.rectangle(image,
//                        new Point(x, y),
//                        new Point(x + width, y + height),
//                        CommonColors(colorIdx), 2);
//
//                String label = "person: " + String.format("%.2f", conf);
//                Imgproc.putText(image, label, new Point(x, y - 10),
//                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(colorIdx), 2);
                TabAiBase aiBase = VideoSendReadCfg.map.get(fallResult.getStatus());
                if(aiBase!=null){
                    if(StringUtils.isNotEmpty(aiBase.getSpaceThree())&&aiBase.getSpaceThree().equals("N")){
                        log.warn("【当前不推送：{}】",fallResult.getStatus());
                        continue;
                    }
                }else{
                    aiBase = new TabAiBase();
                    aiBase.setChainName(fallResult.getStatus());
                    log.warn("【未找到当前基础库名称：{}】",fallResult.getStatus());

                }
                stats.accumulate(aiBase);

                image=drawDetection(image, new BoundingBox(x, y, width, height),aiBase.getChainName(),conf,CommonColors(colorIdx));
                // 绘制关键点和骨骼连接
             //   drawPoseKeypoints(image, kpts, scale, dx, dy);

                colorIdx++;
            }


        // ========== 8. 推送结果 ==========
        if (stats.warnNumber <= 0) {
            log.error("【无有效检测结果，不推送】");
            return false;
        }

        // 设置Redis缓存防止频繁推送
        redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                intervalTime, TimeUnit.SECONDS);

        // 保存图像并推送
        String savePath = uploadPath + File.separator + "push" + File.separator;
        String savedImagePath = saveDetectionImage(image, savePath);

        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}", (endTime - startTime));

        try {
            isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                    stats.audioText, stats.warnNumber, stats.warnText, stats.warnName, savePath);
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
        long intervalTime = Long.parseLong(pushInfo.getEventNumber());

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
            if (netPush.getIsBeforZoom() == 0) {
                saveROIForDebug(croppedMat, roiIndex, netPush.getUploadPath());
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
//                if (netPush.getIsBeforZoom() == 0) {
//                    saveROIForDebug(resizedMat, roiIndex, netPush.getUploadPath());
//                }
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
        float confThreshold = 0.3f;

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

        setBeforeImg(image, "end");

        for (FinalDetectionResult det : allDetections) {
            // 1. 区域过滤（如果配置了检测区域）
            if (netPush.getIsBy() == 0) {
                boolean isPointFlag = isPointInArea(det.x, det.y,
                        Double.parseDouble(netPush.getTabVideoUtil().getCanvasStartx()),
                        Double.parseDouble(netPush.getTabVideoUtil().getCanvasStarty()),
                        Double.parseDouble(netPush.getTabVideoUtil().getCanvasWidth()),
                        Double.parseDouble(netPush.getTabVideoUtil().getCanvasHeight()));
                if (!isPointFlag) {
                    log.info("检测框不在指定区域内，跳过");
                    continue;
                }
            }

            // 2. 前置模型关联过滤（如果使用了ROI检测）
            if (det.fromROIIndex >= 0 && netPush.getIsFollow() == 0) {
                // 检测结果已经在ROI内，无需再次过滤
                log.info("检测结果来自ROI[{}]，已通过前置过滤", det.fromROIIndex);
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
            BoundingBox bbox = new BoundingBox(det.x, det.y, det.width, det.height);

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
        redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                intervalTime, TimeUnit.SECONDS);

        // 8. 保存图像并推送
        String savePath = uploadPath + File.separator + "push" + File.separator;
        String savedImagePath = saveDetectionImage(image, savePath);

        long endTime = System.currentTimeMillis();
        log.info("识别耗时: {}ms, 有效检测: {}/{}",
                (endTime - startTime), validCount, allDetections.size());

        try {
            isOk(pushInfo, netPush, redisTemplate, savedImagePath, tabAiModel,
                    stats.audioText, stats.warnNumber, stats.warnText, stats.warnName, savePath);
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
     * 保存ROI用于调试
     */
    private void saveROIForDebug(Mat roiMat, int roiIndex, String uploadPath) {
        try {
            String debugPath = uploadPath + File.separator + "debug" + File.separator;
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
            String filename = debugPath + "roi_" + roiIndex + "_" +
                    System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(filename, roiMat);
            log.info("ROI[{}]已保存至: {}", roiIndex, filename);
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
            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
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
     * @param image
     * @param netPush
     * @return
     */
    public retureBoxInfo detectObjectsV5Onnx( Mat image, NetPush netPush,RedisTemplate redisTemplate) {

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
            detectionResult = runOnnxInference(session, env, inputData, expectedClassCount);
        } catch (Exception ex) {
            log.error("ONNX推理失败", ex);
            return returnBox;
        }

        // ========== 5. 检测结果验证 ==========
        int detectionCount = detectionResult.confidences.size();
        if (detectionCount <= 0 || detectionCount > 200) {
            log.warn("{}:检测数量异常: {}-{}",  tabAiModel.getAiName(), detectionCount);
            return returnBox;
        }

        log.info("NMS前检测框数量: {}", detectionResult.boxes2d.size());

        // ========== 6. NMS非极大值抑制 ==========
        int[] nmsIndices = performNMS(detectionResult, 0.4f, 0.45f);
        if (nmsIndices.length > 50) {
            setErrorImg(image, "maxIndex");
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

//            // 区域过滤（如果启用）
//            if (!isValidDetection(pushInfo, netPush, retureBoxInfos, originalBox, box)) {
//                log.info("检测框不在有效区域内: {}", className);
//                continue;
//            }

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


    public boolean isOk(TabAiSubscriptionNew pushInfo, NetPush netPush, RedisTemplate redisTemplate, String saveName, TabAiModel tabAiModel,
                        String audioText,
                        Integer warnNumber,
                        String warnText,
                        String warnName,
                        String savePath
    ) {

        log.warn("model{}-{}", tabAiModel.getAiName(), warnText);
        boolean accepted = SpringContextUtils.getBean(AlarmWorkService.class).submit(pushInfo.getId(), () -> {
            try {


                String base64Img = null;
                //组装参数
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


                String recordVideo = "";
                //是否录像
                if (pushInfo.getIsRecording() == 0) {
                    log.info("开启录像 录像时常{}", pushInfo.getRecordTime());
                    long recordTime = pushInfo.getRecordTime();
                    recordVideo = RecordVideo(pushInfo.getBeginEventTypes(), savePath, recordTime, netPush.getId());
                    if (StringUtils.isNotEmpty(recordVideo)) {
                        log.info("录像完成:{}", recordVideo);
                        if (pushInfo.getIsBegin() == 0) {
                            //需要分析录像视频逐帧分析
                            log.info("开始分析视频");
                            recordVideo = analysisVideo(recordVideo, netPush, savePath);
                        }
                    }
                } else {
                    log.info("[未开启录像]");
                }

                if(pushInfo.getSaveLocalhost()==0){ //保存到本地
                    log.info("[本地也保存]");
                    push.setAlarmPicData(saveName);
                    push.setVideo(recordVideo);
                    // 获取 pushInfo 的路径部分

                    SpringContextUtils.getBean(ITabAiWarningService.class).savePush(push);
                }

                if (pushInfo.getPushStatic() == 0) {// 0 开启 1未开启
                    push.setAlarmPicData(base64Image(saveName));
                    push.setVideo(null);
                    log.info("[推送第三方结果]：");
                    if(!pushInfo.getEventUrl().equals("localhost")){ //不进行推送
                        if(StringUtils.isNotEmpty(recordVideo)){
                            String base64Mp4 = base64Image(recordVideo);
                            push.setVideo(base64Mp4);
                        }
                        JSONObject ob = RestUtil.post(pushInfo.getEventUrl(), (JSONObject) JSONObject.toJSON(push));
                        log.info("返回内容：" + ob);
                    }
                } else {
                    log.info("[当前设置为：不推送第三方]");
                }



                if (pushInfo.getSaveRecord() != 0 && StringUtils.isNotEmpty(recordVideo)&&pushInfo.getSaveLocalhost()!=0) {  //不保存本地录像
                    log.info("[不保存本地录像]");
                    File imageFile = new File(recordVideo);
                    if (imageFile.exists()) {
                        imageFile.delete();
                    }
                }


            } catch (Exception exception) {
                log.error("[报警处理失败] taskId={}", pushInfo.getId(), exception);
            }
        });


        return accepted;
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
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);

        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);

        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }
        return inputData;
    }

    public void setBeforeImg(Mat image,String txt){
        String saveName="D://error//"+txt;
        try {
            log.info("错误存储地址{}", saveName);
            File imageFile = new File(saveName);
            if (!imageFile.exists()) {
                imageFile.mkdirs();
            }
            long count = Files.list(Paths.get(saveName)).filter(Files::isRegularFile).count();
            if(count>10000){
                log.info("不通过前置图片文件大于10000不再存储 以免磁盘满");
                //删除所有重新存储
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(saveName))) {
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

            SimpleDateFormat simpleDateFormat=new SimpleDateFormat("yyyyMMddHHmmss");
            Random random = new Random();
            int number = 10000 + random.nextInt(90000); // 10000 ~ 99999
            String c=simpleDateFormat.format(new Date())+number;
            log.info("当前文件名称{}",c);
            Imgcodecs.imwrite(saveName+"/"+txt+c+".jpg", image);
        }catch (Exception exception){
            exception.printStackTrace();
            log.info("【存储错误】");
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
                                             float[] inputData, Integer expectedClassCount) throws Exception {
        long[] shape = new long[]{1, 3, 640, 640};

        DetectionResult result = new DetectionResult();
        float confThreshold = 0.45f;

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

    public void setErrorImg(Mat image,String txt){
        String saveName="D://error";
        try {
            log.info("错误存储地址{}", saveName);
            File imageFile = new File(saveName);
            if (!imageFile.exists()) {
                imageFile.mkdirs();
            }
            long count = Files.list(Paths.get(saveName)).filter(Files::isRegularFile).count();
            if(count>10000){
                log.info("错误文件大于5000不再存储 以免磁盘满");
                //只删 2000 张最旧的
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(saveName))) {
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

            Imgcodecs.imwrite(saveName+"/"+txt+System.currentTimeMillis()+".jpg", image);
        }catch (Exception exception){
            exception.printStackTrace();
        }
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

    /**
     * 检测有效性验证
     */
    private boolean isValidDetection(TabAiSubscriptionNew pushInfo, NetPush netPush,
                                     List<retureBoxInfo> retureBoxInfos, BoundingBox originalBox,
                                     Rect2d box) {
        // 前置模型区域过滤有前置 且 跟随前置区域
        if (netPush.getIsFollow() == 0&&netPush.getIsBefor() == 0) {
            boolean followFlag = retureBoxInfo.getLocalhost(retureBoxInfos, originalBox.x, originalBox.y, netPush.getFollowPosition());
            if (!followFlag) {
                log.info("不在前置模型范围内");
                return false;
            }
        }

        // 自定义区域过滤
        if (netPush.getIsBy() == 0) {
            boolean inArea = isPointInArea(box.x, box.y,
                    Double.parseDouble(netPush.getTabVideoUtil().getCanvasStartx()),
                    Double.parseDouble(netPush.getTabVideoUtil().getCanvasStarty()),
                    Double.parseDouble(netPush.getTabVideoUtil().getCanvasWidth()),
                    Double.parseDouble(netPush.getTabVideoUtil().getCanvasHeight()));

            if (!inArea) {
                log.info("不在自定义区域内");
                return false;
            }
        }

        return true;
    }
    public static boolean isPointInArea(double px, double py, double x, double y, double width, double height) {
        double x2 = x + width;
        double y2 = y + height;

        // 检查点是否在区域内
        return px >= x && px <= x2 && py >= y && py <= y2;
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
     */
    private Mat drawDetection(Mat image, BoundingBox box, String label, float confidence, Scalar color) {
        Imgproc.rectangle(image,
                new Point(box.x, box.y),
                new Point(box.x + box.width, box.y + box.height),
                color, 2);

       return  AIModelYolo3.addChineseText(image, label + confidence, new Point(box.x, box.y), color);
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
        Imgproc.resize(image, resized, new Size(newWidth, newHeight));

        // 创建目标尺寸的画布（灰色填充）
        Mat letterboxed = new Mat(targetHeight, targetWidth, image.type(), new Scalar(114, 114, 114));

        // 计算居中位置
        int dx = (targetWidth - newWidth) / 2;
        int dy = (targetHeight - newHeight) / 2;

        // 将缩放后的图像复制到画布中心
        Rect roi = new Rect(dx, dy, newWidth, newHeight);
        Mat roiMat = new Mat(letterboxed, roi);
        resized.copyTo(roiMat);

        return letterboxed;
    }

}
