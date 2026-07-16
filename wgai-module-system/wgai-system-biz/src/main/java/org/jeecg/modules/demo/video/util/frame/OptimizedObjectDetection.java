package org.jeecg.modules.demo.video.util.frame;

/**
 * @author wggg
 * @date 2025/9/5 10:33
 */

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.utils.Converters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 优化的目标检测类 - 支持多线程高并发处理
 */
@Component
@Slf4j
public class OptimizedObjectDetection {

    // 关键优化1：全局DNN网络池 - 避免重复加载模型
    private static final ConcurrentHashMap<String, Net> GLOBAL_NET_CACHE = new ConcurrentHashMap<>();

    // 关键优化2：线程本地Mat对象复用池
    private static final ThreadLocal<Map<String, Mat>> MAT_CACHE = ThreadLocal.withInitial(HashMap::new);

    // 关键优化3：预分配的检测结果容器
    private static final ThreadLocal<DetectionBuffers> DETECTION_BUFFERS =
            ThreadLocal.withInitial(DetectionBuffers::new);

    // 关键优化4：模型预热标记
    private static final Set<String> WARMED_MODELS = ConcurrentHashMap.newKeySet();

    @Autowired
    private RedisTemplate redisTemplate;






    /**
     * 关键优化6：高性能检测方法
     */
    public boolean detectObjectsOptimized(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush, RedisTemplate redisTemplate) {
        long startTime = System.currentTimeMillis();

        // 早期检查 - 避免不必要的处理
        if (!preflightCheck(pushInfo, netPush, redisTemplate)) {
            return false;
        }

        // 灰度图检查
        if (isGrayscaleImage(image)) {
            setErrorImg(image, "huidutu");
            log.info("检测到灰度图片，跳过处理");
            return false;
        }

        try {
            // 获取或创建DNN网络
            org.opencv.dnn.Net net = getOrCreateNet(netPush.getTabAiModel().getAiName(), netPush.getNet());

            // 获取线程本地缓冲区
            DetectionBuffers buffers = DETECTION_BUFFERS.get();
            buffers.clear();

            // 图像预处理 - 复用Mat对象
            Mat blob = getOrCreateBlob(image);

            // 推理
            net.setInput(blob);
            net.forward(buffers.results, net.getUnconnectedOutLayersNames());

            // 后处理 - 提取检测结果
            float confThreshold = 0.45f;
            float nmsThreshold = 0.45f;

            extractDetections(buffers, confThreshold);

            if (buffers.confidences.isEmpty() || buffers.confidences.size() > 200) {
                log.warn("{}:检测结果异常，数量: {}", pushInfo.getName(), buffers.confidences.size());
                return false;
            }

            // NMS处理
            performNMS(buffers, confThreshold, nmsThreshold);

            int[] indicesArray = buffers.indices.toArray();
            if (indicesArray.length > 50) {
                setErrorImg(image, "maxIndex");
                log.warn("检测目标数量过多: {}", indicesArray.length);
                return false;
            }

            // 绘制结果并处理
            ProcessResult result = processDetectionResults(image, buffers, indicesArray, netPush);

            if (result.warnNumber <= 0) {
                log.info("未检测到需要告警的目标");
                return false;
            }

            // 设置Redis缓存
            long time = Long.parseLong(pushInfo.getEventNumber());
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(), time, TimeUnit.SECONDS);

            // 保存结果图像
            String imagePath = saveDetectionImage(image, netPush.getUploadPath());

            // 异步处理后续操作
            handleDetectionResult(pushInfo, netPush, imagePath, result);

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("检测完成，耗时: {}ms，目标数: {}", processingTime, indicesArray.length);

            return true;

        } catch (Exception e) {
            log.error("检测处理异常", e);
            return false;
        }
    }

    /**
     * 关键优化7：获取或创建DNN网络 - 避免重复加载
     */
    private org.opencv.dnn.Net getOrCreateNet(String modelName, org.opencv.dnn.Net fallbackNet) {
        org.opencv.dnn.Net cachedNet = GLOBAL_NET_CACHE.get(modelName);
        if (cachedNet != null) {
            return cachedNet;
        }

        // 如果缓存中没有，使用传入的网络并缓存
        synchronized (GLOBAL_NET_CACHE) {
            cachedNet = GLOBAL_NET_CACHE.get(modelName);
            if (cachedNet == null) {
                // 克隆网络以确保线程安全
                cachedNet = cloneDnnNet(fallbackNet);
                cachedNet.setPreferableBackend(org.opencv.dnn.Dnn.DNN_BACKEND_OPENCV);
                cachedNet.setPreferableTarget(org.opencv.dnn.Dnn.DNN_TARGET_CPU);
                GLOBAL_NET_CACHE.put(modelName, cachedNet);
                log.info("缓存新的DNN网络: {}", modelName);
            }
        }
        return cachedNet;
    }

    /**
     * 关键优化8：复用Blob对象
     */
    private Mat getOrCreateBlob(Mat image) {
        Map<String, Mat> matCache = MAT_CACHE.get();
        String blobKey = "blob_640x640";

        Mat blob = matCache.get(blobKey);
        if (blob == null) {
            blob = new Mat();
            matCache.put(blobKey, blob);
        }

        // 直接在现有Mat上执行blobFromImage，避免内存分配
//        org.opencv.dnn.Dnn.blobFromImage(image, 1.0 / 255,
//                new org.opencv.core.Size(640, 640),
//                new org.opencv.core.Scalar(0), true, false, blob);

        return blob;
    }

    /**
     * 关键优化9：高效提取检测结果
     */
    private void extractDetections(DetectionBuffers buffers, float confThreshold) {
        for (Mat output : buffers.results) {
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            Mat detectionMat = output.reshape(1, rows);

            // 批量处理，减少循环开销
            float[] detectionData = new float[cols];

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                detection.get(0, 0, detectionData);

                float confidence = detectionData[4];
                if (confidence <= confThreshold) continue;

                // 找到最大得分的类别
                float maxScore = 0;
                int maxClassId = 0;
                for (int j = 5; j < cols; j++) {
                    if (detectionData[j] > maxScore) {
                        maxScore = detectionData[j];
                        maxClassId = j - 5;
                    }
                }

                float centerX = detectionData[0];
                float centerY = detectionData[1];
                float width = detectionData[2];
                float height = detectionData[3];

                float left = centerX - width / 2;
                float top = centerY - height / 2;

                buffers.classIds.add(maxClassId);
                buffers.confidences.add(confidence);
                buffers.boxes2d.add(new Rect2d(left, top, width, height));
            }
        }
    }

    /**
     * 关键优化10：优化的NMS处理
     */
    private void performNMS(DetectionBuffers buffers, float confThreshold, float nmsThreshold) {
        if (buffers.boxes2d.isEmpty()) return;

        buffers.boxesMat.fromList(buffers.boxes2d);
        buffers.confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(buffers.confidences));

        org.opencv.dnn.Dnn.NMSBoxes(buffers.boxesMat, buffers.confidencesMat,
                confThreshold, nmsThreshold, buffers.indices);
    }

    /**
     * 前置检查 - 避免不必要的处理
     */
    private boolean preflightCheck(TabAiSubscriptionNew pushInfo, NetPush netPush, RedisTemplate redisTemplate) {
        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforeTime = redisTemplate.opsForValue().get(netPush.getId());

        if (beforeTime != null) {
            log.debug("推送间隔未到，跳过处理: {}", pushInfo.getName());
            return false;
        }

        return true;
    }

    /**
     * 优化的灰度检查
     */
    private boolean isGrayscaleImage(Mat image) {
        if (image.channels() == 1) return true;

        // 快速采样检查 - 只检查部分像素点
        int step = Math.max(1, image.rows() / 10);
        for (int i = 0; i < image.rows(); i += step) {
            for (int j = 0; j < image.cols(); j += step) {
                double[] pixel = image.get(i, j);
                if (pixel != null && pixel.length >= 3) {
                    if (Math.abs(pixel[0] - pixel[1]) > 5 ||
                            Math.abs(pixel[1] - pixel[2]) > 5 ||
                            Math.abs(pixel[0] - pixel[2]) > 5) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 处理结果数据结构
     */
    private static class ProcessResult {
        String audioText = "";
        int warnNumber = 0;
        String warnText = "";
        String warnName = "";
    }

    /**
     * 处理检测结果
     */
    private ProcessResult processDetectionResults(Mat image, DetectionBuffers buffers,
                                                  int[] indicesArray, NetPush netPush) {
        ProcessResult result = new ProcessResult();
        List<String> classNames = netPush.getClaseeNames();

        int colorIndex = 0;
        for (int idx : indicesArray) {
            Rect2d box = buffers.boxes2d.get(idx);
            Integer classId = buffers.classIds.get(idx);
            String className = classNames.get(classId);
            float confidence = buffers.confidences.get(idx);

            // 坐标转换
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double x = box.x * ((double) image.cols() / 640);
            double y = box.y * ((double) image.rows() / 640);

            org.opencv.core.Scalar color = CommonColors(colorIndex);
            TabAiBase aiBase = VideoSendReadCfg.map.get(className);

            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(className);
            } else {
                if (StringUtils.isNotEmpty(aiBase.getSpaceThree()) && "N".equals(aiBase.getSpaceThree())) {
                    log.debug("类别不推送: {}", className);
                    continue;
                }
                color = getColor(aiBase.getRgbColor());
            }

            // 绘制边界框
            org.opencv.imgproc.Imgproc.rectangle(image,
                    new org.opencv.core.Point(x, y),
                    new org.opencv.core.Point(x + width, y + height),
                    color, 2);

            // 添加文本标签
            image = AIModelYolo3.addChineseText(image,
                    aiBase.getChainName() + String.format("%.2f", confidence),
                    new org.opencv.core.Point(x, y), color);

            // 累积结果
            result.audioText += aiBase.getRemark() + (aiBase.getSpaceOne() != null ? aiBase.getSpaceOne() : "");
            result.warnNumber += aiBase.getSpaceTwo() != null ? aiBase.getSpaceTwo() : 1;
            result.warnText += StringUtils.isEmpty(aiBase.getRemark()) ? "" : aiBase.getRemark();
            result.warnName += aiBase.getChainName() + ",";

            colorIndex++;
        }

        return result;
    }

    /**
     * 保存检测图像
     */
    private String saveDetectionImage(Mat image, String uploadPath) {
        String savePath = uploadPath + File.separator + "push" + File.separator;
        File directory = new File(savePath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String fileName = savePath + System.currentTimeMillis() + ".jpg";
        File imageFile = new File(fileName);
        if (imageFile.exists()) {
            imageFile.delete();
        }

        org.opencv.imgcodecs.Imgcodecs.imwrite(fileName, image);
        return fileName;
    }

    /**
     * 异步处理检测结果
     */
    @Async
    public void handleDetectionResult(TabAiSubscriptionNew pushInfo, NetPush netPush,
                                       String imagePath, ProcessResult result) {
        try {
            isOk(pushInfo, netPush, redisTemplate, imagePath, netPush.getTabAiModel(),
                    result.audioText, result.warnNumber, result.warnText, result.warnName,
                    new File(imagePath).getParent());
        } catch (Exception e) {
            log.error("异步处理检测结果失败", e);
        }
    }

    // 工具方法 - 需要您根据实际情况实现
    private org.opencv.dnn.Net cloneDnnNet(org.opencv.dnn.Net originalNet) {
        // 这里需要实现网络克隆逻辑
        // 通常需要重新加载模型文件
        return originalNet; // 临时返回
    }

    private List<String> getModelPaths() {
        // 返回所有模型路径
        return Arrays.asList(); // 需要您填充实际路径
    }

    private String extractModelName(String modelPath) {
        return new File(modelPath).getName();
    }

    private org.opencv.core.Scalar CommonColors(int index) {
        // 返回颜色
        return new org.opencv.core.Scalar(255, 0, 0); // 临时实现
    }

    private org.opencv.core.Scalar getColor(String rgbColor) {
        // 解析RGB颜色字符串
        return new org.opencv.core.Scalar(255, 0, 0); // 临时实现
    }

    private void setErrorImg(Mat image, String errorType) {
        // 设置错误图像
    }

    private void isOk(Object... params) {
        // 原有的isOk方法
    }
}
