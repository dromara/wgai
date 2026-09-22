package org.jeecg.modules.demo.video.util;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.util.VideoDecodePolicy;
import org.jeecg.modules.demo.video.util.VideoFrameGate;
import org.jeecg.modules.demo.video.util.FrameMatView;
import org.jeecg.modules.demo.video.util.StreamLagTracker;
import org.jeecg.modules.demo.video.util.RecognitionTrace;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;


import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * 优化后的视频处理器 - 解决延迟累积和线程无法终止问题
 * @author wggg
 * @date 2025/5/20 17:41
 */
@Slf4j
public class VideoReadPicNew implements Runnable {

    private static final ThreadLocal<TabAiSubscriptionNew> threadLocalPushInfo = new ThreadLocal<>();
    private final ThreadLocal<Map<String, Net>> threadLocalNetCache = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<identifyTypeNew> identifyTypeNewLocal = ThreadLocal.withInitial(identifyTypeNew::new);

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;
    private VideoFrameGate frameGate;
    // 关键修改1：使用独立的线程池，支持强制终止
    private final ExecutorService processingExecutor;
    private final AtomicBoolean forceShutdown = new AtomicBoolean(false);

    // 关键修改2：帧丢弃策略 - 解决延迟累积问题
    private static final int MAX_PENDING_FRAMES = 1; // 最大排队帧数
    private final AtomicInteger pendingFrames = new AtomicInteger(0);
    private volatile long lastProcessTime = System.currentTimeMillis();

    // 关键修改3：实时帧率控制
    private final long targetFrameInterval;
    private final long maxStreamLagMs;
    private final StreamLagTracker streamLag = new StreamLagTracker();
    private long lastLagReconnectMs;
    private volatile long streamLagMs;
    private final AtomicLong processingMillis = new AtomicLong();
    private long previousProcessed, previousDropped, previousProcessingMillis;
    private volatile long lastFrameTime = 0;

    // OpenCV DNN 优化 - 线程本地存储
    private static final ThreadLocal<Map<String, org.opencv.dnn.Net>> DNN_NET_CACHE =
            ThreadLocal.withInitial(() -> new HashMap<>());

    // 性能监控
    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    // 资源管理
    private static volatile Java2DFrameConverter SHARED_CONVERTER;
    private final BlockingQueue<Mat> matPool = new LinkedBlockingQueue<>(20); // 减小池大小
    private final BlockingQueue<BufferedImage> imagePool = new LinkedBlockingQueue<>(20);

    public VideoReadPicNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate) {
        this(tabAiSubscriptionNew, redisTemplate, 1000);
    }

    public VideoReadPicNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate, long frameIntervalMs) {
        this(tabAiSubscriptionNew, redisTemplate, frameIntervalMs, 3000);
    }

    public VideoReadPicNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate, long frameIntervalMs, long maxStreamLagMs) {
        this.maxStreamLagMs = maxStreamLagMs;
        this.targetFrameInterval = Math.max(1, frameIntervalMs);
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId=tabAiSubscriptionNew.getId();
        // 创建专用线程池 - 支持强制终止
        this.processingExecutor = new ThreadPoolExecutor(
                1, // 核心线程数减少
                1, // 最大线程数减少
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(5), // 限制队列大小，防止积压
                r -> {
                    Thread t = new Thread(r, "VideoProcessor-" + streamId + "-" + tabAiSubscriptionNew.getName());
                    t.setDaemon(true); // 设置为守护线程
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void run() {
        threadLocalPushInfo.set(tabAiSubscriptionNew);
        lastLogTime = System.currentTimeMillis();
        FFmpegFrameGrabber grabber = null;

        try {
            grabber = createOptimizedGrabber();
            identifyTypeNew identifyTypeAll = identifyTypeNewLocal.get();
            List<NetPush> netPushList = threadLocalPushInfo.get().getNetPushList();

            Frame frame;
            int consecutiveNullFrames = 0;

            while (!forceShutdown.get() && !Thread.currentThread().isInterrupted()) {
                // 检查停止标志 - 更频繁的检查
                if (!isStreamActive()) {
                    log.warn("[主动停止推送]{}", tabAiSubscriptionNew.getName());
                    break;
                }

                if(tabAiSubscriptionNew.getDifyStartEnd()!=null&&tabAiSubscriptionNew.getDifyStartTime()!=null){
                    int startHour = tabAiSubscriptionNew.getDifyStartEnd(); // 开始小时
                    int endHour = tabAiSubscriptionNew.getDifyStartTime();    // 结束小时

                    LocalTime now = LocalTime.now();  // 当前时间（时分秒）
                    LocalTime start = LocalTime.of(startHour, 0);
                    LocalTime end = LocalTime.of(endHour, 0);

                    if (!org.jeecg.modules.demo.video.util.onnx.VideoReadPicOnnxNew.isWithinSchedule(now, start, end)) {
                        if (grabber != null) {
                            try { grabber.release(); } finally { grabber = null; streamLag.reset(); }
                        }
                        Thread.sleep(5000);
                        continue;
                    }
                }

                if (grabber == null) grabber = createOptimizedGrabber();
                logPerformanceStats();
                frame = grabber.grabImage();
                if (frame == null) {
                    consecutiveNullFrames++;
                    if (consecutiveNullFrames > 10) {
                        log.info("[连续空帧过多，重启视频流]");
                        grabber = restartGrabber(grabber);
                        consecutiveNullFrames = 0;
                    }
                    Thread.sleep(100); // 减少等待时间
                    continue;
                }
                consecutiveNullFrames = 0;
                if (!frameGate.accept(frame)) {
                    if (frameGate.isWaitingTooLong()) {
                        log.warn("[等待关键帧超时，重新连接] taskId={}", tabAiSubscriptionNew.getId());
                        grabber = restartGrabber(grabber);
                    }
                    continue;
                }
                streamLagMs = streamLag.observe(grabber.getTimestamp(), System.nanoTime());
                if (maxStreamLagMs > 0 && streamLagMs > maxStreamLagMs
                        && System.currentTimeMillis() - lastLagReconnectMs > 15000) {
                    log.warn("[视频积压重连] taskId={} name={} accumulatedLagMs={} limitMs={}",
                            streamId, tabAiSubscriptionNew.getName(), streamLagMs, maxStreamLagMs);
                    lastLagReconnectMs = System.currentTimeMillis();
                    grabber = restartGrabber(grabber);
                    droppedFrames.incrementAndGet();
                    continue;
                }

                // 关键修改4：严格的实时帧率控制
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < targetFrameInterval) {
                    continue;
                }
                lastFrameTime = currentTime;

                // 关键修改5：队列长度控制 - 防止延迟累积
                if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
                    log.info("[丢弃帧以防止延迟累积] 当前排队: {}", pendingFrames.get());
                    droppedFrames.incrementAndGet();
                    continue;
                }

                // 异步处理帧 - 改进版
                processFrameAsyncOptimized(frame, netPushList, identifyTypeAll);

                // 性能监控
                logPerformanceStats();
            }

        } catch (Exception exception) {
            log.error("[处理异常]", exception);
        } finally {
            log.info("[开始清理资源]");
            forceCleanup(grabber);
        }
    }

    /**
     * 关键修改6：优化的异步帧处理 - 解决延迟问题
     */
    private void processFrameAsyncOptimized(Frame frame, List<NetPush> netPushList, identifyTypeNew identifyTypeAll) {
        if (forceShutdown.get()) {
            return;
        }
        // 创建Frame的副本
        long decodedNanos = System.nanoTime();
        Frame frameClone = null;
        try {
            frameClone = frame.clone();
        } catch (Exception e) {
            log.error("[Frame克隆失败]: {}", e.getMessage());
            droppedFrames.incrementAndGet();
            return;
        }
        pendingFrames.incrementAndGet();
        final Frame finalFrame = frameClone;
        try {
        processingExecutor.execute(() -> {
            long startTime = System.currentTimeMillis();
            Mat matInfo = null;
            RecognitionTrace trace = RecognitionTrace.begin(streamId, decodedNanos);
            long queueMs = trace.elapsedMillis();
            String workerName = Thread.currentThread().getName();
            Thread.currentThread().setName(workerName + "-frame=" + trace.frameId);

            try {
                // 快速检查是否需要终止
                if (forceShutdown.get()) {
                    return;
                }

                // 快速转换
                matInfo = FrameMatView.bgr(finalFrame);
                if (matInfo == null || matInfo.empty()) {
                    return;
                }

                // 处理推理 - 加入超时控制
                for (NetPush netPush : netPushList) {
                    if (forceShutdown.get()) {
                        break;
                    }

                    boolean copyRequired = netPushList.size() > 1;
                    Mat mat = copyRequired ? getMat() : matInfo;
                    long modelStarted = System.nanoTime();
                    String frameThreadName = Thread.currentThread().getName();
                    Thread.currentThread().setName(frameThreadName + "-setting=" + netPush.getId());
                    try {
                        if (copyRequired) matInfo.copyTo(mat);
                        processNetPushOptimized(mat, netPush, identifyTypeAll);
                    } finally {
                        log.info("[模型处理] taskId={} frameId={} settingId={} elapsedMs={}", streamId, trace.frameId, netPush.getId(), (System.nanoTime() - modelStarted) / 1000000);
                        Thread.currentThread().setName(frameThreadName);
                        if (copyRequired) returnMat(mat);
                    }
                }

                processedFrames.incrementAndGet();
                lastProcessTime = System.currentTimeMillis();
                //setBeforeImg(matInfo,"cc");
                // 性能警告
                long totalTime = System.currentTimeMillis() - startTime;
                if (totalTime > 10000) {
                    log.warn("[帧处理耗时过长: {}ms] 可能导致延迟累积", totalTime);
                }

            } catch (Exception e) {
                log.error("[帧处理异常]", e);
            } finally {
                // 快速资源清理
                if (matInfo != null) {
                    matInfo.release();
                }
                RecognitionTrace.clear();
                Thread.currentThread().setName(workerName);
                finalFrame.close();
                long elapsedMs = System.currentTimeMillis() - startTime;
                processingMillis.addAndGet(elapsedMs);
                log.info("[帧处理链路] taskId={} name={} frameId={} queueMs={} processMs={} decodedToFinishMs={}",
                        streamId, tabAiSubscriptionNew.getName(), trace.frameId, queueMs, elapsedMs, trace.elapsedMillis());
                pendingFrames.decrementAndGet();
            }
        });

        } catch (RejectedExecutionException e) {
            finalFrame.close(); pendingFrames.decrementAndGet(); droppedFrames.incrementAndGet();
            if (!forceShutdown.get()) log.warn("[帧提交失败] taskId={}", streamId, e);
        }
    }

    /**
     * 关键修改7：优化的推理处理 - 使用线程本地DNN网络
     */
    private void processNetPushOptimized(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll) {
        try {
            if (forceShutdown.get()) {
                return;
            }

            // 使用线程本地的DNN网络缓存
//            String modelKey = netPush.getTabAiModel().getAiName();
//            Map<String, org.opencv.dnn.Net> netCache = DNN_NET_CACHE.get();
//
//            org.opencv.dnn.Net dnnNet = netCache.get(modelKey);
//            if (dnnNet == null) {
//                // 创建新的DNN网络实例 - 线程安全
//                dnnNet = cloneDnnNet(netPush.getNet());
//                // 关键修改8：OpenCV DNN线程优化
//                dnnNet.setPreferableBackend(org.opencv.dnn.Dnn.DNN_BACKEND_OPENCV);
//                dnnNet.setPreferableTarget(org.opencv.dnn.Dnn.DNN_TARGET_CPU);
//                // 设置线程数为1，避免线程竞争
//                org.opencv.core.Core.setNumThreads(1);
//
//                netCache.put(modelKey, dnnNet);
//                log.info("[创建线程本地DNN网络] 模型: {}", modelKey);
//            }

            if (netPush.getIsBefor() == 0) {
                processWithPredecessorsOptimized(mat, netPush, identifyTypeAll);
            } else {
                executeDetectionOptimized(mat, netPush, identifyTypeAll,null);
            }

        } catch (Exception e) {
            log.error("[处理NetPush异常] 模型: {}",
                    netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown", e);
        }
    }

    /**
     * 克隆DNN网络 - 为每个线程创建独立实例
     */
    private org.opencv.dnn.Net cloneDnnNet(org.opencv.dnn.Net originalNet) {
        // 这里需要根据你的实际情况实现DNN网络克隆
        // 一般是重新加载模型文件
        return originalNet; // 临时返回原网络，需要根据实际情况修改
    }

    private void executeDetectionOptimized(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll,List<retureBoxInfo> retureBoxInfos) {
        try {
            if (forceShutdown.get()) {
                return;
            }

            // 添加推理超时检查
            long inferenceStart = System.currentTimeMillis();
            if(netPush.getDifyType()==2){ // 1.图像 2.人体姿态
                identifyTypeAll.detectObjectsDifyV5Pose(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
            }else{
                if ("1".equals(netPush.getModelType())) {
                    identifyTypeAll.detectObjectsDify(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
                } else {
                    if(netPush.getIsBeforZoom()==0){//开启区域放大
                        identifyTypeAll.detectObjectsDifyV5WithROI(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
                    }else{
                        identifyTypeAll.detectObjectsDifyV5(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
                    }

                }
            }


            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            if (inferenceTime > 1000) {
                log.warn("[推理耗时过长: {}ms] 模型: {}", inferenceTime, netPush.getTabAiModel().getAiName());
            }

        } catch (Exception e) {
            log.error("[执行检测异常] 模型类型: {}", netPush.getModelType(), e);
        }
    }

    private void processWithPredecessorsOptimized(Mat mat, NetPush netPush, identifyTypeNew identifyTypeAll) {
        List<NetPush> before = netPush.getListNetPush();
        if (before == null || before.isEmpty()) {
            return;
        }
        retureBoxInfo validationPassed=null;
        for (int i = 0; i < before.size(); i++) {
            if (forceShutdown.get()) {
                break;
            }

            NetPush beforePush = before.get(i);

            if (i == 0) {
                validationPassed = validateFirstModelOptimized(mat, beforePush, identifyTypeAll);
                if (validationPassed!=null&&!validationPassed.isFlag()) {
                    log.warn("[第一个模型验证失败，终止后续处理]");
                    break;
                }
            } else {
                if(validationPassed==null||validationPassed.getInfoList().size()<=0){
                    log.warn("[前置内容为空,终止后续处理]");
                    break;
                }
                executeDetectionOptimized(mat, beforePush, identifyTypeAll,validationPassed.getInfoList());
            }
        }
    }

    private retureBoxInfo validateFirstModelOptimized(Mat mat, NetPush beforePush, identifyTypeNew identifyTypeAll) {
        try {
            if (forceShutdown.get()) {
                return null;
            }

            if ("1".equals(beforePush.getModelType())) {
                return identifyTypeAll.detectObjects(
                        tabAiSubscriptionNew, mat, beforePush.getNet(),
                        beforePush.getClaseeNames(), beforePush);
            } else {
                return identifyTypeAll.detectObjectsV5(
                        tabAiSubscriptionNew, mat, beforePush.getNet(),
                        beforePush.getClaseeNames(), beforePush,redisTemplate);
            }
        } catch (Exception e) {
            log.error("[验证模型异常]", e);
            return null;
        }
    }

    /**
     * 关键修改9：强制清理 - 确保线程能够终止
     */
    private void forceCleanup(FFmpegFrameGrabber grabber) {
        log.info("[开始强制清理] 流: {}", tabAiSubscriptionNew.getName());

        // 1. 设置强制停止标志
        forceShutdown.set(true);

        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> pendingTasks = processingExecutor.shutdownNow();
                log.warn("[强制终止线程池，剩余任务: {}]", pendingTasks.size());

                // 再等待2秒
                if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.error("[线程池无法完全终止]");
                }
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. 释放视频资源
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        // 5. 清理对象池
        clearObjectPools();

        // 6. 清理ThreadLocal
        try {
            DNN_NET_CACHE.remove();
            identifyTypeNewLocal.remove();
            threadLocalPushInfo.remove();
        } catch (Exception e) {
            log.warn("[ThreadLocal清理异常]", e);
        }

        log.info("[强制清理完成] 流: {}", tabAiSubscriptionNew.getName());
    }

    private void clearObjectPools() {
        Mat mat;
        while ((mat = matPool.poll()) != null) {
            try {
                mat.release();
            } catch (Exception e) {
                log.info("[Mat释放异常]", e);
            }
        }
        imagePool.clear();
    }

    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 30000) { // 每30秒
            long processed = processedFrames.get();
            long dropped = droppedFrames.get();
            int pending = pendingFrames.get();

            // 计算实时延迟
            long processingDelay = currentTime - lastProcessTime;

            long count = processed - previousProcessed;
            long elapsed = currentTime - lastLogTime;
            long totalMs = processingMillis.get();
            log.info("[识别统计] taskId={} name={} windowMs={} frames={} fps={} avgProcessMs={} dropped={} pending={} lastCompletedAgoMs={} accumulatedStreamLagMs={}",
                    streamId, tabAiSubscriptionNew.getName(), elapsed, count,
                    String.format(java.util.Locale.ROOT, "%.2f", count * 1000.0 / elapsed),
                    count == 0 ? 0 : (totalMs - previousProcessingMillis) / count,
                    dropped - previousDropped, pending, processingDelay, streamLagMs);
            previousProcessed = processed; previousDropped = dropped; previousProcessingMillis = totalMs;
            lastLogTime = currentTime;
        }
    }


    private Mat getMat() {
        Mat mat = matPool.poll();
        return mat != null ? mat : new Mat();
    }

    private void returnMat(Mat mat) {
        if (mat != null && (forceShutdown.get() || !matPool.offer(mat))) mat.release();
    }

    private BufferedImage getBufferedImage(int width, int height, int type) {
        BufferedImage image = imagePool.poll();
        if (image != null && image.getWidth() == width &&
                image.getHeight() == height && image.getType() == type) {
            return image;
        }
        return new BufferedImage(width, height, type);
    }

    private void returnBufferedImage(BufferedImage image) {
        if (image != null && imagePool.size() < 20) {
            imagePool.offer(image);
        }
    }

    private boolean isStreamActive() {
        try {
            return Boolean.TRUE.equals(RedisCacheHolder.get(streamId + "newRunPush"));
        } catch (Exception e) {
            log.warn("[检查流状态异常]", e);
            return false;
        }
    }

    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        streamLag.reset();
        if (grabber != null) {
            grabber.stop();
            grabber.release();
        }
        return createOptimizedGrabber();
    }

    public FFmpegFrameGrabber createOptimizedGrabber() throws Exception {

        // 第一步：先探测流信息
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("stimeout", "5000000");
        VideoDecodePolicy.start(probe);
        String codecName = probe.getVideoCodecName();
        int codecId = probe.getVideoCodec();
        probe.stop();
        probe.close();
        probe.release();
        log.info(" 检测到视频编码: " + codecName + " (ID=" + codecId + ")");
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());

        // GPU设置
        if ("1".equals(tabAiSubscriptionNew.getEventTypes())) {


            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[使用GPU_CUDA加速解码]");
        } else if ("4".equals(tabAiSubscriptionNew.getEventTypes())) {
            //intel 加速
            grabber.setOption("hwaccel", "qsv");          // Intel QuickSync
           //grabber.setVideoCodecName("hevc_qsv");         // H.265 QSV
            if ("h264".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("h264_qsv");
            } else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("hevc_qsv");
            }
            log.info("[使用Intel加速解码]");
        }
        // 基础设置

        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "3000000");
        grabber.setOption("rw_timeout", "10000000");
        grabber.setVideoOption("threads", "1");

        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        // 实时流优化

        grabber.setOption("max_delay", "500000");







        // 严格的实时设置



        VideoDecodePolicy.start(grabber);
        frameGate = new VideoFrameGate(tabAiSubscriptionNew.getId());
        return grabber;
    }

    /**
     * 外部调用的停止方法
     */
    public void forceStop() {
        log.info("[外部请求强制停止] 流: {}", tabAiSubscriptionNew.getName());
        forceShutdown.set(true);
    }
}