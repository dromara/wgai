package org.jeecg.modules.demo.video.util.onnx;

import lombok.extern.slf4j.Slf4j;
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
    private final ThreadLocal<Java2DFrameConverter> converterLocal = ThreadLocal.withInitial(Java2DFrameConverter::new);

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;

    private final ExecutorService processingExecutor;
    private final AtomicBoolean forceShutdown = new AtomicBoolean(false);
    private final Set<Future<?>> activeTasks = ConcurrentHashMap.newKeySet();

    private static final int MAX_PENDING_FRAMES = 3;
    private final AtomicInteger pendingFrames = new AtomicInteger(0);
    private volatile long lastProcessTime = System.currentTimeMillis();

    private static final long TARGET_FRAME_INTERVAL = 300; // 1秒3帧
    private volatile long lastFrameTime = 0;

    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    private final BlockingQueue<Mat> matPool = new LinkedBlockingQueue<>(20);
    private final BlockingQueue<BufferedImage> imagePool = new LinkedBlockingQueue<>(20);

    public VideoReadPicOnnxNew(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate) {
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId = tabAiSubscriptionNew.getId();

        this.processingExecutor = new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(5),
                r -> {
                    Thread t = new Thread(r, "VideoProcessor-" + tabAiSubscriptionNew.getName());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Override
    public void run() {
        threadLocalPushInfo.set(tabAiSubscriptionNew);
        FFmpegFrameGrabber grabber = null;

        log.info("========== [VideoReadPicOnnxNew启动] ==========");
        log.info("[流信息] 名称: {}, ID: {}", tabAiSubscriptionNew.getName(), tabAiSubscriptionNew.getId());
        log.info("[视频URL] {}", tabAiSubscriptionNew.getBeginEventTypes());

        // ========== ✅ 修复1：使用超时机制创建grabber ==========
        try {
            log.info("[准备创建grabber] 流: {}", tabAiSubscriptionNew.getName());

            // 使用Future来限制创建时间
            ExecutorService grabberCreator = Executors.newSingleThreadExecutor();
            Future<FFmpegFrameGrabber> grabberFuture = grabberCreator.submit(() -> {
                log.info("[子线程] 开始创建grabber");
                FFmpegFrameGrabber g = createOptimizedGrabberSimple();
                log.info("[子线程] grabber创建成功");
                return g;
            });

            try {
                grabber = grabberFuture.get(60, TimeUnit.SECONDS); // 60秒超时
                log.info("[✓ grabber创建成功] 流: {}", tabAiSubscriptionNew.getName());
            } catch (TimeoutException e) {
                log .warn("[ grabber创建超时] 流: {}, 超过60秒", tabAiSubscriptionNew.getName());
                grabberFuture.cancel(true);
                grabberCreator.shutdownNow();
                return;
            } catch (Exception e) {
                log .warn("[ grabber创建失败] 流: {}", tabAiSubscriptionNew.getName(), e);
                grabberCreator.shutdownNow();
                return;
            } finally {
                grabberCreator.shutdown();
            }

        } catch (Exception e) {
            log .warn("[流创建异常] 流: {}", tabAiSubscriptionNew.getName(), e);
            return;
        }

        // ========== 正常处理流程 ==========
        try {
            identifyTypeNewOnnxTest identifyTypeAll = identifyTypeNewLocal.get();
            List<NetPush> netPushList = threadLocalPushInfo.get().getNetPushList();

            Frame frame;
            int consecutiveNullFrames = 0;

            while (!forceShutdown.get()) {
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

                    if (now.isBefore(start) || now.isAfter(end)) {
                        log.warn("不在有效时段 {} ({}~{}) ({}~{})",now,startHour,endHour, startHour, endHour);
                        Thread.sleep(5000);
                        continue;
                    }
                }

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

                // 帧率控制
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < TARGET_FRAME_INTERVAL) {
                    frame.close();
                    continue;
                }
                lastFrameTime = currentTime;

                // 队列控制
                if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
                    log.warn("[丢帧] 排队: {}", pendingFrames.get());
                    frame.close();
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
            frame.close();
            return;
        }

        Frame frameClone = null;
        try {
            frameClone = frame.clone();
        } catch (Exception e) {
            log.warn("[Frame克隆失败]", e);
            return;
        }

        pendingFrames.incrementAndGet();
        final Frame finalFrame = frameClone;

        Future<?> future = processingExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();
            BufferedImage image = null;
            Mat matInfo = null;

            try {
                if (forceShutdown.get()) return;

                Java2DFrameConverter converter = converterLocal.get();
                image = converter.getBufferedImage(finalFrame);

                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    return;
                }

                if (forceShutdown.get()) return;

                matInfo = bufferedImageToMat(image);
                if (matInfo == null || matInfo.empty()) {
                    return;
                }

                long inferenceStart = System.currentTimeMillis();
                for (NetPush netPush : netPushList) {
                    if (forceShutdown.get()) break;

                    if (System.currentTimeMillis() - inferenceStart > 10000) {
                        log.warn("[推理超时，跳过]");
                        break;
                    }

                    Mat mat = getMat();
                    try {
                        matInfo.copyTo(mat);
                        processNetPush(mat, netPush, identifyTypeAll);
                    } finally {
                        returnMat(mat);
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
                if (matInfo != null) returnMat(matInfo);
                if (image != null) returnBufferedImage(image);
                if (finalFrame != null) finalFrame.close();
                pendingFrames.decrementAndGet();
            }
        });

        activeTasks.add(future);
        activeTasks.removeIf(Future::isDone);
    }

    private void processNetPush(Mat mat, NetPush netPush, identifyTypeNewOnnxTest identifyTypeAll) {
        try {
            if (forceShutdown.get()) return;

            if (netPush.getIsBefor() == 0) {
                processWithPredecessors(mat, netPush, identifyTypeAll);
            } else {
                executeDetection(mat, netPush, identifyTypeAll, null);
            }

        } catch (Exception e) {
            log .warn("[处理NetPush异常]", e);
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
                log.warn("[推理耗时: {}ms]", inferenceTime);
            }

        } catch (Exception e) {
            log .warn("[执行检测异常]", e);
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
                    log.warn("[第一个模型验证失败]");
                    break;
                }
            } else {
                if (validationPassed == null || validationPassed.getInfoList().size() <= 0) {
                    log.warn("[前置内容为空]");
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

        log.info("[取消任务: {}个]", activeTasks.size());
        activeTasks.forEach(future -> {
            if (!future.isDone()) future.cancel(true);
        });
        activeTasks.clear();

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
            converterLocal.remove();
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
        imagePool.clear();
    }

    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 30000) {
            long processed = processedFrames.get();
            long dropped = droppedFrames.get();
            int pending = pendingFrames.get();
            long processingDelay = currentTime - lastProcessTime;

            log.info("[性能] 已处理: {}, 丢弃: {}, 排队: {}, 延迟: {}ms, 丢帧率: {:.2f}%",
                    processed, dropped, pending, processingDelay,
                    processed > 0 ? (double) dropped / (processed + dropped) * 100 : 0);

            lastLogTime = currentTime;
        }
    }

    private Mat getMat() {
        Mat mat = matPool.poll();
        return mat != null ? mat : new Mat();
    }

    private void returnMat(Mat mat) {
        if (mat != null && !mat.empty()) {
            if (matPool.size() < 20) {
                matPool.offer(mat);
            } else {
                mat.release();
            }
        }
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
            return RedisCacheHolder.get(tabAiSubscriptionNew.getId() + "newRunPush");
        } catch (Exception e) {
            log.warn("[检查流状态异常]", e);
            return false;
        }
    }

    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        if (grabber != null) {
            grabber.stop();
            grabber.release();
        }
        return createOptimizedGrabberSimple();
    }

    // ========== ✅ 修复2：简化grabber创建，移除probe ==========
    public FFmpegFrameGrabber createOptimizedGrabberSimple() throws Exception {
        log.info("[创建grabber] URL: {}", tabAiSubscriptionNew.getBeginEventTypes());

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());

        // GPU设置
        if (tabAiSubscriptionNew.getEventTypes().equals("1")) {
            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[GPU加速]");
        } else if (tabAiSubscriptionNew.getEventTypes().equals("4")) {
            grabber.setOption("hwaccel", "qsv");
            log.info("[Intel加速]");
        }

        // 基础设置
        grabber.setOption("loglevel", "-8");
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");

        // ========== ✅ 修复3：缩短超时时间，快速失败 ==========
        grabber.setOption("stimeout", "10000000");   // 10秒连接超时（原5秒）
        grabber.setOption("rw_timeout", "10000000"); // 10秒读写超时
        grabber.setOption("timeout", "10000000");    // 10秒总超时

        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        // 实时流优化
        grabber.setOption("flags", "low_delay");
        grabber.setOption("max_delay", "500000");
        grabber.setOption("buffer_size", "512000");
        grabber.setOption("fflags", "nobuffer+flush_packets+discardcorrupt");
        grabber.setOption("flags2", "fast");
        grabber.setOption("err_detect", "compliant");
        grabber.setOption("framedrop", "1");

        grabber.setFrameRate(2.0);
        grabber.setOption("r", "2");

        log.info("[开始start] 可能阻塞...");
        long startTime = System.currentTimeMillis();

        grabber.start(); // 可能阻塞，但有超时保护

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