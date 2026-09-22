package org.jeecg.modules.demo.video.util.onnx;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.util.VideoDecodePolicy;
import org.jeecg.modules.demo.video.util.VideoFrameGate;
import org.jeecg.modules.demo.video.util.FrameMatView;
import org.jeecg.modules.demo.video.util.StreamLagTracker;
import org.jeecg.modules.demo.video.util.RecognitionTrace;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnx;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnxTest;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * 视频处理器 - V2.0修复版
 * 修复：1.移除probe探测 2.简化grabber创建 3.添加超时保护
 */
@Slf4j
public class VideoReadPicOnnxNew implements Runnable {

    private static final ThreadLocal<TabAiSubscriptionNew> threadLocalPushInfo = new ThreadLocal<>();
    private final ThreadLocal<identifyTypeNewOnnxTest> identifyTypeNewLocal = ThreadLocal.withInitial(identifyTypeNewOnnxTest::new);
    private final Map<String, long[]> modelStats = new ConcurrentHashMap<>();

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;
    private VideoFrameGate frameGate;

    private final ExecutorService processingExecutor;
    private final AtomicBoolean forceShutdown = new AtomicBoolean(false);

    private static final int MAX_PENDING_FRAMES = 1;
    private final AtomicInteger pendingFrames = new AtomicInteger(0);
    private volatile long lastProcessTime = System.currentTimeMillis();

    private final long targetFrameInterval;
    private final long maxStreamLagMs;
    private final StreamLagTracker streamLag = new StreamLagTracker();
    private long lastLagReconnectMs;
    private volatile long streamLagMs;
    private final AtomicLong processingMillis = new AtomicLong();
    private long previousProcessed;
    private long previousDropped;
    private long previousProcessingMillis;
    private volatile long lastFrameTime = 0;

    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    private final BlockingQueue<Mat> matPool = new LinkedBlockingQueue<>(20);

    public VideoReadPicOnnxNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate) {
        this(tabAiSubscriptionNew, redisTemplate, 300);
    }

    public VideoReadPicOnnxNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate, long frameIntervalMs) {
        this(tabAiSubscriptionNew, redisTemplate, frameIntervalMs, 3000);
    }

    public VideoReadPicOnnxNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate, long frameIntervalMs, long maxStreamLagMs) {
        this.maxStreamLagMs = maxStreamLagMs;
        this.targetFrameInterval = Math.max(1, frameIntervalMs);
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId = tabAiSubscriptionNew.getId();

        this.processingExecutor = new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(5),
                r -> {
                    Thread t = new Thread(r, "VideoProcessor-" + streamId + "-" + tabAiSubscriptionNew.getName());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void run() {
        threadLocalPushInfo.set(tabAiSubscriptionNew);
        FFmpegFrameGrabber grabber = null;

        log.info("[视频任务启动] taskId={} name={}", streamId, tabAiSubscriptionNew.getName());
        lastLogTime = System.currentTimeMillis();
        try {
            if (forceShutdown.get() || Thread.currentThread().isInterrupted()) return;
            grabber = createOptimizedGrabberSimple();
            identifyTypeNewOnnxTest identifyTypeAll = identifyTypeNewLocal.get();
            List<NetPush> netPushList = threadLocalPushInfo.get().getNetPushList();

            Frame frame;
            int consecutiveNullFrames = 0;

            while (!forceShutdown.get() && !Thread.currentThread().isInterrupted()) {
                if (!isStreamActive()) {
                    log.warn("[主动停止] {}", tabAiSubscriptionNew.getName());
                    break;
                }

                // 时间段控制
                if (tabAiSubscriptionNew.getDifyStartEnd() != null && tabAiSubscriptionNew.getDifyStartTime() != null) {
                    int startHour = tabAiSubscriptionNew.getDifyStartEnd();//推送开始时间
                    int endHour= tabAiSubscriptionNew.getDifyStartTime();//推送结束时间

                    LocalTime now = LocalTime.now();
                    LocalTime start = LocalTime.of(startHour, 0);
                    LocalTime end = LocalTime.of(endHour, 0);

                    if (!isWithinSchedule(now, start, end)) {
                        log.info("[非识别时段] taskId={} now={} start={} end={}", streamId, now, start, end);
                        if (grabber != null) {
                            try { grabber.release(); } finally { grabber = null; streamLag.reset(); }
                        }
                        Thread.sleep(5000);
                        continue;
                    }
                }

                if (grabber == null) grabber = createOptimizedGrabberSimple();
                logPerformanceStats();
                frame = grabber.grabImage();
                if (frame == null) {
                    consecutiveNullFrames++;
                    if (consecutiveNullFrames > 10) {
                        log.warn("[连续空帧，重启] 流: {}", tabAiSubscriptionNew.getName());
                        grabber = restartGrabber(grabber);
                        consecutiveNullFrames = 0;
                    }
                    Thread.sleep(100);
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

                // 帧率控制
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < targetFrameInterval) {
                    continue;
                }
                lastFrameTime = currentTime;

                // 队列控制
                if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
                    droppedFrames.incrementAndGet();
                    continue;
                }

                processFrameAsync(frame, netPushList, identifyTypeAll);
                logPerformanceStats();
            }

        } catch (Exception exception) {
            log .warn("[处理异常]", exception);
        } finally {
            log.info("[开始清理资源] 流: {}", tabAiSubscriptionNew.getName());
            forceCleanup(grabber);
        }
    }

    private void processFrameAsync(Frame frame, List<NetPush> netPushList, identifyTypeNewOnnxTest identifyTypeAll) {
        if (forceShutdown.get()) {
            return;
        }

        long decodedNanos = System.nanoTime();
        Frame frameClone = null;
        try {
            frameClone = frame.clone();
        } catch (Exception e) {
            log.warn("[Frame克隆失败]", e);
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
                if (forceShutdown.get()) return;

                matInfo = FrameMatView.bgr(finalFrame);
                if (matInfo == null || matInfo.empty()) {
                    return;
                }

                for (NetPush netPush : netPushList) {
                    if (forceShutdown.get()) break;

                    boolean copyRequired = netPushList.size() > 1;
                    Mat mat = copyRequired ? getMat() : matInfo;
                    long modelStarted = System.nanoTime();
                    String frameThreadName = Thread.currentThread().getName();
                    Thread.currentThread().setName(frameThreadName + "-setting=" + netPush.getId());
                    try {
                        if (copyRequired) matInfo.copyTo(mat);
                        processNetPush(mat, netPush, identifyTypeAll);
                    } finally {
                        log.info("[模型处理] taskId={} frameId={} settingId={} elapsedMs={}", streamId, trace.frameId, netPush.getId(), (System.nanoTime() - modelStarted) / 1000000);
                        Thread.currentThread().setName(frameThreadName);
                        if (copyRequired) returnMat(mat);
                    }
                }

                processedFrames.incrementAndGet();
                lastProcessTime = System.currentTimeMillis();

                long totalTime = System.currentTimeMillis() - startTime;
                if (totalTime > 10000) {
                    log.warn("[帧处理耗时: {}ms]", totalTime);
                }

            } catch (Exception e) {
                log .warn("[帧处理异常]", e);
            } finally {
                if (matInfo != null) matInfo.release();
                RecognitionTrace.clear();
                Thread.currentThread().setName(workerName);
                long elapsedMs = System.currentTimeMillis() - startTime;
                processingMillis.addAndGet(elapsedMs);
                log.info("[帧处理链路] taskId={} name={} frameId={} queueMs={} processMs={} decodedToFinishMs={}",
                        streamId, tabAiSubscriptionNew.getName(), trace.frameId, queueMs, elapsedMs, trace.elapsedMillis());
                if (finalFrame != null) finalFrame.close();
                pendingFrames.decrementAndGet();
            }
        });

        } catch (RejectedExecutionException e) {
            finalFrame.close();
            pendingFrames.decrementAndGet();
            droppedFrames.incrementAndGet();
            if (!forceShutdown.get()) log.warn("[帧提交失败] taskId={}", streamId, e);
        }
    }

    private void processNetPush(Mat mat, NetPush netPush, identifyTypeNewOnnxTest identifyTypeAll) {
        long started = System.nanoTime();
        try {
            if (forceShutdown.get()) return;

            if (netPush.getIsBefor() == 0) {
                processWithPredecessors(mat, netPush, identifyTypeAll);
            } else {
                executeDetection(mat, netPush, identifyTypeAll, null);
            }

        } catch (Exception e) {
            log.warn("[处理模型异常] taskId={} settingId={}", streamId, netPush.getId(), e);
        } finally {
            String key = netPush.getId() == null ? "predecessor" : netPush.getId();
            long[] stats = modelStats.computeIfAbsent(key, ignored -> new long[2]);
            synchronized (stats) { stats[0]++; stats[1] += (System.nanoTime() - started) / 1000000; }
        }
    }

    public void executeDetection(Mat mat, NetPush netPush, identifyTypeNewOnnxTest identifyTypeAll,
                                 List<retureBoxInfo> retureBoxInfos) {
        try {
            if (forceShutdown.get()) return;

            long inferenceStart = System.currentTimeMillis();

            if (netPush.getDifyType() == 2) {  //姿态识别
                identifyTypeAll.detectObjectsDifyOnnxV5Pose(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
            } else {
                if (netPush.getIsBeforZoom() == 0) { //开启区域放大
                    identifyTypeAll.detectObjectsDifyOnnxV5WithROI(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
                } else {
                    identifyTypeAll.detectObjectsDifyOnnxV5(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
                }
            }

            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            if (inferenceTime > 1000) {
                log.info("[模型耗时] taskId={} settingId={} model={} elapsedMs={}", streamId, netPush.getId(), netPush.getTabAiModel().getAiName(), inferenceTime);
            }

        } catch (Exception e) {
            log.warn("[执行检测异常] taskId={} settingId={} model={}", streamId, netPush.getId(), netPush.getTabAiModel().getAiName(), e);
        }
    }

    public void processWithPredecessors(Mat mat, NetPush netPush, identifyTypeNewOnnxTest identifyTypeAll) {
        List<NetPush> before = netPush.getListNetPush();
        if (before == null || before.isEmpty()) return;

        retureBoxInfo validationPassed = null;
        for (int i = 0; i < before.size(); i++) {
            if (forceShutdown.get()) break;

            NetPush beforePush = before.get(i);

            if (i == 0) {
                validationPassed = validateFirstModel(mat, beforePush, identifyTypeAll);
                if (validationPassed == null || !validationPassed.isFlag()) {
                    log.info("[前置模型未命中] taskId={}", streamId);
                    break;
                }
            } else {
                if (validationPassed == null || validationPassed.getInfoList().size() <= 0) {
                    log.info("[前置内容为空] taskId={}", streamId);
                    break;
                }
                executeDetection(mat, beforePush, identifyTypeAll, validationPassed.getInfoList());
            }
        }
    }

    private retureBoxInfo validateFirstModel(Mat mat, NetPush beforePush, identifyTypeNewOnnxTest identifyTypeAll) {
        try {
            if (forceShutdown.get()) return null;
            return identifyTypeAll.detectObjectsV5Onnx(tabAiSubscriptionNew, mat, beforePush, redisTemplate);
        } catch (Exception e) {
            log .warn("[验证模型异常]", e);
            return null;
        }
    }

    private void forceCleanup(FFmpegFrameGrabber grabber) {
        log.info("[强制清理] 流: {}", tabAiSubscriptionNew.getName());

        forceShutdown.set(true);

        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> pending = processingExecutor.shutdownNow();
                log.warn("[强制终止线程池，剩余: {}]", pending.size());
                if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log .warn("[线程池无法终止]");
                }
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        clearObjectPools();

        try {
            identifyTypeNewLocal.remove();
            threadLocalPushInfo.remove();
        } catch (Exception e) {
            log.warn("[ThreadLocal清理异常]", e);
        }

        log.info("[✓ 清理完成] 流: {}", tabAiSubscriptionNew.getName());
    }

    private void clearObjectPools() {
        Mat mat;
        while ((mat = matPool.poll()) != null) {
            try { mat.release(); } catch (Exception ignored) {}
        }
    }

    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 30000) {
            long processed = processedFrames.get();
            long dropped = droppedFrames.get();
            int pending = pendingFrames.get();
            long processingDelay = currentTime - lastProcessTime;

            long count = processed - previousProcessed;
            long totalMs = processingMillis.get();
            log.info("[识别统计] taskId={} name={} windowMs={} frames={} fps={} avgProcessMs={} dropped={} pending={} lastCompletedAgoMs={} accumulatedStreamLagMs={}",
                    streamId, tabAiSubscriptionNew.getName(), currentTime - lastLogTime, count,
                    String.format(java.util.Locale.ROOT, "%.2f", count * 1000.0 / (currentTime - lastLogTime)),
                    count == 0 ? 0 : (totalMs - previousProcessingMillis) / count,
                    dropped - previousDropped, pending, processingDelay, streamLagMs);
            previousProcessed = processed;
            previousDropped = dropped;
            previousProcessingMillis = totalMs;

            modelStats.forEach((settingId, stats) -> {
                synchronized (stats) {
                    if (stats[0] > 0) log.info("[模型统计] taskId={} settingId={} calls={} avgMs={}", streamId, settingId, stats[0], stats[1] / stats[0]);
                    stats[0] = 0; stats[1] = 0;
                }
            });
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

    public static boolean isWithinSchedule(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end)) return true;
        return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end);
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
        return createOptimizedGrabberSimple();
    }

    // ========== ✅ 修复2：简化grabber创建，移除probe ==========
    public FFmpegFrameGrabber createOptimizedGrabberSimple() throws Exception {
        log.info("[连接视频流] taskId={} name={}", streamId, tabAiSubscriptionNew.getName());

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());

        // GPU设置
        if ("1".equals(tabAiSubscriptionNew.getEventTypes())) {
            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[GPU加速]");
        } else if ("4".equals(tabAiSubscriptionNew.getEventTypes())) {
            grabber.setOption("hwaccel", "qsv");
            log.info("[Intel加速]");
        }

        // 基础设置

        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");

        // ========== ✅ 修复3：缩短超时时间，快速失败 ==========
        grabber.setOption("stimeout", "10000000");   // 10秒连接超时（原5秒）
        grabber.setOption("rw_timeout", "10000000"); // 10秒读写超时
        grabber.setOption("timeout", "10000000");    // 10秒总超时

        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        // 实时流优化

        grabber.setOption("max_delay", "500000");






        // Sampling is controlled by targetFrameInterval; limiting decoder threads avoids CPU oversubscription.
        grabber.setVideoOption("threads", "1");

        log.info("[开始start] 可能阻塞...");
        long startTime = System.currentTimeMillis();

        if (forceShutdown.get()) {
            grabber.release();
            throw new InterruptedException("任务已经停止");
        }
        VideoDecodePolicy.start(grabber);
        frameGate = new VideoFrameGate(tabAiSubscriptionNew.getId());

        long duration = System.currentTimeMillis() - startTime;
        log.info("[✓ start完成] 耗时: {}ms", duration);

        return grabber;
    }

    /**
     * 外部停止方法
     */
    public void forceStop() {
        log.info("[外部请求停止] 流: {}", tabAiSubscriptionNew.getName());
        forceShutdown.set(true);
    }
}