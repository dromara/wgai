package org.jeecg.modules.demo.video.util.onnx;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.util.VideoDecodePolicy;
import org.jeecg.modules.demo.video.util.VideoFrameGate;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnx;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.opencv.dnn.Net;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * 内存优化版视频处理器 - 基于原代码结构，解决32路摄像头OOM问题
 *
 * 主要优化：
 * 1. 全局共享线程池替代每路独立线程池
 * 2. 严格的内存管理和对象复用机制  
 * 3. 动态负载调整防止内存堆积
 * 4. 紧急降级和清理机制
 *
 * @author wggg
 * @date 2025/5/20 17:41
 */
@Slf4j
public class VideoReadPicNewThreeTwoOnnx implements Runnable {

    // ================== 全局共享资源 - 解决32路摄像头资源爆炸问题 ==================

    // 全局共享线程池 - 替代原来每路摄像头的独立线程池
    private static volatile ExecutorService GLOBAL_SHARED_EXECUTOR;
    private static final Object EXECUTOR_LOCK = new Object();

    // 全局对象池 - 减少Mat、Converter等大对象的创建销毁
    private static final BlockingQueue<Java2DFrameConverter> GLOBAL_CONVERTER_POOL = new LinkedBlockingQueue<>(32);
    private static final BlockingQueue<Mat> GLOBAL_MAT_POOL = new LinkedBlockingQueue<>(100);
    private static final BlockingQueue<BufferedImage> GLOBAL_IMAGE_POOL = new LinkedBlockingQueue<>(100);

    // 全局监控统计
    private static final AtomicInteger ACTIVE_STREAM_COUNT = new AtomicInteger(0);
    private static final AtomicLong GLOBAL_PROCESSED_FRAMES = new AtomicLong(0);
    private static final AtomicLong GLOBAL_DROPPED_FRAMES = new AtomicLong(0);
    private static volatile long LAST_GLOBAL_MEMORY_CHECK = 0;
    private static volatile long LAST_GLOBAL_LOG_TIME = 0;

    // 内存管理 - 关键优化点
    private static final double MEMORY_WARNING_THRESHOLD = 0.75; // 75%内存使用率警告
    private static final double MEMORY_EMERGENCY_THRESHOLD = 0.9; // 85%紧急清理
    private static final long MEMORY_CHECK_INTERVAL = 5000; // 5秒检查一次内存

    // ================== 实例属性 - 保持原代码结构兼容性 ==================

    // 保持原有的ThreadLocal，但优化使用方式
    private static final ThreadLocal<WeakReference<TabAiSubscriptionNew>> threadLocalPushInfo = new ThreadLocal<>();
    private final ThreadLocal<Map<String, Net>> threadLocalNetCache = ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<identifyTypeNewOnnx> identifyTypeNewLocal = ThreadLocal.withInitial(identifyTypeNewOnnx::new);

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;
    private VideoFrameGate frameGate;

    // 实例级线程池 - 改为使用全局共享
    private final ExecutorService processingExecutor;
    private final AtomicBoolean forceShutdown = new AtomicBoolean(false);
    private final Set<Future<?>> activeTasks = ConcurrentHashMap.newKeySet();

    // 帧控制 - 严格限制减少内存压力
    private static final int MAX_PENDING_FRAMES = 1; // 降低到1帧，严格控制内存
    private final AtomicInteger pendingFrames = new AtomicInteger(0);
    private volatile long lastProcessTime = System.currentTimeMillis();

    // 动态帧率控制 - 基于负载自动调整
    private static final long BASE_FRAME_INTERVAL = 1000; // 基础2秒一帧
    private volatile long currentFrameInterval = BASE_FRAME_INTERVAL;
    private volatile long lastFrameTime = 0;

    // OpenCV DNN 缓存 - 保持原有结构
    private static final ThreadLocal<Map<String, Net>> DNN_NET_CACHE =
            ThreadLocal.withInitial(() -> new HashMap<>());

    // 性能监控 - 保持原有结构
    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    // 本地对象池 - 减少全局池竞争
    private final Queue<Mat> localMatPool = new ArrayDeque<>(3);
    private final Queue<BufferedImage> localImagePool = new ArrayDeque<>(3);

    static {
        // 静态初始化，注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[JVM关闭，清理全局视频处理资源]");
            cleanupAllGlobalResources();
        }, "VideoProcessor-ShutdownHook"));

        // 设置OpenCV线程数，避免线程爆炸
        try {
            org.opencv.core.Core.setNumThreads(2); // 限制OpenCV使用2个线程
        } catch (Exception e) {
            log.warn("[设置OpenCV线程数失败]", e);
        }
    }

    public VideoReadPicNewThreeTwoOnnx(TabAiSubscriptionNew tabAiSubscriptionNew, RedisTemplate redisTemplate) {
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId = tabAiSubscriptionNew.getId();

        // 初始化全局共享线程池
        this.processingExecutor = getOrCreateGlobalExecutor();

        // 增加活跃流计数
        int activeCount = ACTIVE_STREAM_COUNT.incrementAndGet();

        // 根据活跃流数量动态调整帧间隔
        adjustFrameIntervalBasedOnLoad(activeCount);

        log.info("[创建视频处理器] 流: {}, 活跃流数: {}, 当前帧间隔: {}ms",
                tabAiSubscriptionNew.getName(), activeCount, currentFrameInterval);
    }

    /**
     * 获取或创建全局共享线程池
     */
    private static ExecutorService getOrCreateGlobalExecutor() {
        if (GLOBAL_SHARED_EXECUTOR == null || GLOBAL_SHARED_EXECUTOR.isShutdown()) {
            synchronized (EXECUTOR_LOCK) {
                if (GLOBAL_SHARED_EXECUTOR == null || GLOBAL_SHARED_EXECUTOR.isShutdown()) {
                    // 核心配置：为32路摄像头优化的线程池
                    int coreSize = Math.max(4, Runtime.getRuntime().availableProcessors() / 4); // 最少4个核心线程
                    int maxSize = Math.max(8, Runtime.getRuntime().availableProcessors() / 2);  // 最大线程数为CPU一半

                    GLOBAL_SHARED_EXECUTOR = new ThreadPoolExecutor(
                            coreSize, maxSize,
                            60L, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(150), // 队列容量150，可容纳多路摄像头任务
                            r -> {
                                Thread t = new Thread(r, "GlobalVideo-" + System.currentTimeMillis());
                                t.setDaemon(true);
                                t.setPriority(Thread.NORM_PRIORITY - 1); // 稍低优先级
                                return t;
                            },
                            new ThreadPoolExecutor.DiscardOldestPolicy() // 丢弃最老任务，防止积压
                    );

                    log.info("[创建全局线程池] 核心: {}, 最大: {}, 队列: 150", coreSize, maxSize);
                }
            }
        }
        return GLOBAL_SHARED_EXECUTOR;
    }

    /**
     * 根据负载调整帧间隔
     */
    private void adjustFrameIntervalBasedOnLoad(int activeStreams) {
        if (activeStreams <= 16) {
            currentFrameInterval = BASE_FRAME_INTERVAL; // 2秒
        } else if (activeStreams <= 24) {
            currentFrameInterval = BASE_FRAME_INTERVAL * 3 / 2; // 3秒  
        } else {
            currentFrameInterval = BASE_FRAME_INTERVAL * 2; // 4秒
        }
    }

    @Override
    public void run() {
        // 设置ThreadLocal，使用WeakReference避免内存泄漏
        threadLocalPushInfo.set(new WeakReference<>(tabAiSubscriptionNew));
        FFmpegFrameGrabber grabber = null;

        try {
            grabber = createOptimizedGrabber();
            identifyTypeNewOnnx identifyTypeAll = identifyTypeNewLocal.get();
            List<NetPush> netPushList = tabAiSubscriptionNew.getNetPushList();

            Frame frame;
            int consecutiveNullFrames = 0;
            long lastMemoryCheck = System.currentTimeMillis();

            log.info("[开始视频流处理] 流: {}, 初始帧间隔: {}ms",
                    tabAiSubscriptionNew.getName(), currentFrameInterval);

            while (!forceShutdown.get()) {
                // 全局内存检查
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastMemoryCheck > MEMORY_CHECK_INTERVAL) {
                    checkGlobalMemoryPressure();
                    lastMemoryCheck = currentTime;
                }

                // 检查流状态
                if (!isStreamActive()) {
                    log.warn("[主动停止推送] {}", tabAiSubscriptionNew.getName());
                    break;
                }
                if(tabAiSubscriptionNew.getDifyStartEnd()!=null&&tabAiSubscriptionNew.getDifyStartTime()!=null){
                    int startHour = tabAiSubscriptionNew.getDifyStartEnd(); // 开始小时
                    int endHour = tabAiSubscriptionNew.getDifyStartTime();    // 结束小时

                    LocalTime now = LocalTime.now();  // 当前时间（时分秒）
                    LocalTime start = LocalTime.of(startHour, 0);
                    LocalTime end = LocalTime.of(endHour, 0);

                    if (!org.jeecg.modules.demo.video.util.onnx.VideoReadPicOnnxNew.isWithinSchedule(now, start, end)) {
                        Thread.sleep(5000);
                        continue;
                    }
                }

                frame = grabber.grabImage();
                if (frame == null) {
                    consecutiveNullFrames++;
                    if (consecutiveNullFrames > 5) { // 减少重试次数
                        log.info("[连续空帧过多，重启视频流] {}", tabAiSubscriptionNew.getName());
                        grabber = restartGrabber(grabber);
                        consecutiveNullFrames = 0;
                    }
                    Thread.sleep(300); // 增加等待时间，减少CPU消耗
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

                // 严格的帧率控制
                if (currentTime - lastFrameTime < currentFrameInterval) {

                    continue;
                }
                lastFrameTime = currentTime;

                // 严格的排队控制 - 防止内存堆积
                if (pendingFrames.get() >= MAX_PENDING_FRAMES) {
                    log.debug("[丢弃帧防止内存堆积] 流: {}, 排队: {}",
                            tabAiSubscriptionNew.getName(), pendingFrames.get());

                    droppedFrames.incrementAndGet();
                    GLOBAL_DROPPED_FRAMES.incrementAndGet();

                    // 动态调整帧间隔
                    dynamicAdjustFrameInterval();
                    continue;
                }

                // 处理前最后内存检查
//                if (!checkMemoryBeforeProcessing()) {
//                    frame.close();
//                    droppedFrames.incrementAndGet();
//                    continue;
//                }

                // 异步处理帧 - 使用全局线程池
                processFrameAsyncOptimized(frame, netPushList, identifyTypeAll);

                // 性能监控
                logPerformanceStats();
                logGlobalPerformanceStats();
            }

        } catch (OutOfMemoryError oom) {
            log.error("[严重内存溢出] 流: {}, 执行紧急清理", tabAiSubscriptionNew.getName(), oom);
            performEmergencyCleanup();
        } catch (Exception exception) {
            log.error("[处理异常] 流: {}", tabAiSubscriptionNew.getName(), exception);
        } finally {
            log.info("[开始清理资源] 流: {}", tabAiSubscriptionNew.getName());
            forceCleanup(grabber);
        }
    }

    /**
     * 全局内存压力检查
     */
    private void checkGlobalMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > MEMORY_EMERGENCY_THRESHOLD) {
            log.error("[全局内存紧急状况] 使用率: {}%, 执行紧急清理",
                    String.format("%.1f", memoryUsage * 100));
            performGlobalEmergencyCleanup();
        } else if (memoryUsage > MEMORY_WARNING_THRESHOLD) {
            log.warn("[全局内存压力警告] 使用率: {}%, 执行预防性清理",
                    String.format("%.1f", memoryUsage * 100));
            performGlobalPreventiveCleanup();
        }
    }

    /**
     * 全局紧急清理
     */
    private void performGlobalEmergencyCleanup() {
        log.warn("[执行全局紧急清理]");

        // 1. 清理全局对象池
        clearGlobalObjectPools();

        // 2. 强制GC
        System.gc();

        // 3. 增加所有流的帧间隔（通过静态变量影响）
        adjustGlobalFrameInterval(true);

        try {
            Thread.sleep(200); // 让GC有时间执行
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.warn("[全局紧急清理完成]");
    }

    /**
     * 全局预防性清理
     */
    private void performGlobalPreventiveCleanup() {
        // 清理部分对象池
        clearPartialGlobalObjectPools();

        // 适度调整帧间隔
        adjustGlobalFrameInterval(false);
    }

    /**
     * 调整全局帧间隔
     */
    private void adjustGlobalFrameInterval(boolean emergency) {
        int activeStreams = ACTIVE_STREAM_COUNT.get();

        // 通过日志通知所有实例调整帧间隔（简单协调机制）
        if (emergency) {
            // 紧急情况：大幅降低处理频率
            log.warn("[全局紧急降频通知] 建议帧间隔调整为: {}ms", BASE_FRAME_INTERVAL * 3);
        } else {
            // 预防性调整
            long suggestedInterval = BASE_FRAME_INTERVAL + (activeStreams > 24 ? 2000 : 1000);
            log.info("[全局调频建议] 建议帧间隔: {}ms", suggestedInterval);
        }
    }

    /**
     * 动态调整帧间隔
     */
    private void dynamicAdjustFrameInterval() {
        int pending = pendingFrames.get();
        int globalActiveStreams = ACTIVE_STREAM_COUNT.get();

        if (pending >= MAX_PENDING_FRAMES || globalActiveStreams > 28) {
            currentFrameInterval = Math.min(currentFrameInterval + 500, 8000); // 最大8秒
        } else if (pending == 0 && globalActiveStreams <= 20) {
            currentFrameInterval = Math.max(currentFrameInterval - 200, BASE_FRAME_INTERVAL);
        }
    }

    /**
     * 处理前内存检查
     */
    private boolean checkMemoryBeforeProcessing() {
        Runtime runtime = Runtime.getRuntime();
        long freeMemory = runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        long maxB= (long) (maxMemory * 0.05);

        // 可用内存少于最大内存的20%时拒绝处理
        if (freeMemory < maxB) {
            log.info(" 【可用内存少于最大内存的5%时拒绝处理可用内存{}-最大内存{}-最大内存的5% -{} 】",freeMemory,maxMemory,maxB);
            return false;
        }
        return true;
    }

    /**
     * 优化的异步帧处理 - 使用全局线程池
     */
    private void processFrameAsyncOptimized(Frame frame, List<NetPush> netPushList, identifyTypeNewOnnx identifyTypeAll) {
        if (forceShutdown.get() || processingExecutor.isShutdown()) {

            return;
        }

        // 创建Frame副本
        Frame frameClone;
        try {
            frameClone = frame.clone();
        } catch (Exception e) {
            log.error("[Frame克隆失败] 流: {}, 错误: {}", tabAiSubscriptionNew.getName(), e.getMessage());

            return;
        }

        pendingFrames.incrementAndGet();
        final Frame finalFrame = frameClone;

        // 提交到全局共享线程池 - 关键优化点
        Future<?> future = processingExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();
            Java2DFrameConverter converter = null;
            BufferedImage image = null;
            Mat matInfo = null;

            try {
                if (forceShutdown.get()) {
                    return;
                }

                // 获取转换器 - 使用全局对象池
                converter = getConverterFromGlobalPool();
                if (converter == null) {
                    return;
                }

                image = converter.getBufferedImage(finalFrame);
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    return;
                }

                if (forceShutdown.get()) {
                    return;
                }

                // 获取Mat - 使用三级获取策略
                matInfo = getMatFromPool();
                if (matInfo == null) {
                    return;
                }

                // 图像转换
                try {
                    Mat tempMat = bufferedImageToMat(image);
                    if (tempMat != null && !tempMat.empty()) {
                        tempMat.copyTo(matInfo);
                        tempMat.release();
                    } else {
                        return;
                    }
                } catch (Exception e) {
                    log.error("[图像转换异常] 流: {}", tabAiSubscriptionNew.getName(), e);
                    return;
                }

                // 处理推理 - 保持原有逻辑
                long inferenceStart = System.currentTimeMillis();
                for (NetPush netPush : netPushList) {
                    if (forceShutdown.get() ) {//|| !checkMemoryBeforeProcessing()
                        break;
                    }

                    // 推理超时检查 - 缩短超时时间
                    if (System.currentTimeMillis() - inferenceStart > 5000) { // 5秒超时
                        log.warn("[推理超时，跳过剩余处理] 流: {}", tabAiSubscriptionNew.getName());
                        break;
                    }

                    Mat mat = getMatFromPool();
                    try {
                        if (mat != null) {
                            matInfo.copyTo(mat);
                            processNetPushOptimized(mat, netPush, identifyTypeAll);
                        }
                    } finally {
                        if (mat != null) {
                            returnMatToPool(mat);
                        }
                    }
                }

                processedFrames.incrementAndGet();
                GLOBAL_PROCESSED_FRAMES.incrementAndGet();
                lastProcessTime = System.currentTimeMillis();

                // 性能警告
                long totalTime = System.currentTimeMillis() - startTime;
                if (totalTime > 3000) {
                    log.warn("[帧处理耗时过长] 流: {}, 耗时: {}ms", tabAiSubscriptionNew.getName(), totalTime);
                }

            } catch (OutOfMemoryError oom) {
                log.error("[帧处理内存溢出] 流: {}", tabAiSubscriptionNew.getName(), oom);
                performEmergencyCleanup();
            } catch (Exception e) {
                log.error("[帧处理异常] 流: {}", tabAiSubscriptionNew.getName(), e);
            } finally {
                // 快速资源清理
                if (matInfo != null) {
                    returnMatToPool(matInfo);
                }
                if (converter != null) {
                    returnConverterToGlobalPool(converter);
                }
                if (finalFrame != null) {
                    finalFrame.close();
                }
                pendingFrames.decrementAndGet();
            }
        });

        // 跟踪活跃任务
        activeTasks.add(future);

        // 定期清理已完成任务
        if (activeTasks.size() > 5) {
            activeTasks.removeIf(Future::isDone);
        }
    }

    /**
     * 从全局池获取转换器
     */
    private Java2DFrameConverter getConverterFromGlobalPool() {
        Java2DFrameConverter converter = GLOBAL_CONVERTER_POOL.poll();
        if (converter != null) {
            return converter;
        }

        try {
            return new Java2DFrameConverter();
        } catch (OutOfMemoryError e) {
            log.error("[转换器创建内存不足] 流: {}", tabAiSubscriptionNew.getName());
            return null;
        }
    }

    /**
     * 归还转换器到全局池
     */
    private void returnConverterToGlobalPool(Java2DFrameConverter converter) {
        if (converter != null && GLOBAL_CONVERTER_POOL.size() < 32) {
            GLOBAL_CONVERTER_POOL.offer(converter);
        }
    }

    /**
     * 三级Mat获取策略：本地池 -> 全局池 -> 新建
     */
    private Mat getMatFromPool() {
        // 1. 本地池
        Mat mat = localMatPool.poll();
        if (mat != null && !mat.empty()) {
            return mat;
        }

        // 2. 全局池
        mat = GLOBAL_MAT_POOL.poll();
        if (mat != null && !mat.empty()) {
            return mat;
        }

        // 3. 新建
        try {
            return new Mat();
        } catch (OutOfMemoryError e) {
            log.error("[Mat创建内存不足] 流: {}", tabAiSubscriptionNew.getName());
            return null;
        }
    }

    /**
     * 三级Mat归还策略：本地池 -> 全局池 -> 释放
     */
    private void returnMatToPool(Mat mat) {
        if (mat != null && !mat.empty()) {
            // 1. 本地池优先
            if (localMatPool.size() < 2) {
                localMatPool.offer(mat);
            }
            // 2. 全局池
            else if (GLOBAL_MAT_POOL.size() < 100) {
                GLOBAL_MAT_POOL.offer(mat);
            }
            // 3. 直接释放
            else {
                try {
                    mat.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 处理NetPush - 保持原有逻辑结构
     */
    private void processNetPushOptimized(Mat mat, NetPush netPush, identifyTypeNewOnnx identifyTypeAll) {
        try {
            if (forceShutdown.get()) {
                return;
            }

            if (netPush.getIsBefor() == 0) {
                processWithPredecessorsOptimized(mat, netPush, identifyTypeAll);
            } else {
                executeDetectionOptimized(mat, netPush, identifyTypeAll, null);
            }

        } catch (Exception e) {
            log.error("[处理NetPush异常] 流: {}, 模型: {}",
                    tabAiSubscriptionNew.getName(),
                    netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown", e);
        }
    }

    private void executeDetectionOptimized(Mat mat, NetPush netPush, identifyTypeNewOnnx identifyTypeAll, List<retureBoxInfo> retureBoxInfos) {
        try {
            if (forceShutdown.get()) {
                return;
            }

            long inferenceStart = System.currentTimeMillis();

            //不再支持v3 视频推理 效率太低 v5-v11
            if(netPush.getDifyType()==2){
                identifyTypeAll.detectObjectsDifyOnnxV5Pose(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
            }else{
                if(netPush.getIsBeforZoom()==0){//开启区域放大
                    identifyTypeAll.detectObjectsDifyOnnxV5WithROI(tabAiSubscriptionNew, mat, netPush, redisTemplate,retureBoxInfos);
                }else {
                    identifyTypeAll.detectObjectsDifyOnnxV5(tabAiSubscriptionNew, mat, netPush, redisTemplate, retureBoxInfos);
                }
            }

            long inferenceTime = System.currentTimeMillis() - inferenceStart;
            if (inferenceTime > 800) { // 降低警告阈值
                log.warn("[推理耗时过长] 流: {}, 模型: {}, 耗时: {}ms",
                        tabAiSubscriptionNew.getName(),
                        netPush.getTabAiModel() != null ? netPush.getTabAiModel().getAiName() : "unknown",
                        inferenceTime);
            }

        } catch (Exception e) {
            log.error("[执行检测异常] 流: {}, 模型类型: {}", tabAiSubscriptionNew.getName(), netPush.getModelType(), e);
        }
    }

    private void processWithPredecessorsOptimized(Mat mat, NetPush netPush, identifyTypeNewOnnx identifyTypeAll) {
        List<NetPush> before = netPush.getListNetPush();
        if (before == null || before.isEmpty()) {
            return;
        }

        retureBoxInfo validationPassed = null;
        for (int i = 0; i < before.size(); i++) {
            if (forceShutdown.get() ) {//|| !checkMemoryBeforeProcessing()
                break;
            }

            NetPush beforePush = before.get(i);

            if (i == 0) {
                validationPassed = validateFirstModelOptimized(mat, beforePush, identifyTypeAll);
                if (validationPassed == null || !validationPassed.isFlag()) {
                    log.warn("[第一个模型验证失败，终止后续处理] 流: {}", tabAiSubscriptionNew.getName());
                    break;
                }
            } else {
                executeDetectionOptimized(mat, beforePush, identifyTypeAll,
                        validationPassed.getInfoList());
            }
        }
    }

    private retureBoxInfo validateFirstModelOptimized(Mat mat, NetPush beforePush, identifyTypeNewOnnx identifyTypeAll) {
        try {
            if (forceShutdown.get()) {
                return null;
            }

            return identifyTypeAll.detectObjectsV5Onnx(
                    tabAiSubscriptionNew, mat, beforePush,redisTemplate);

        } catch (Exception e) {
            log.error("[验证模型异常] 流: {}", tabAiSubscriptionNew.getName(), e);
            return null;
        }
    }

    /**
     * 紧急清理 - 实例级
     */
    private void performEmergencyCleanup() {
        log.warn("[执行实例紧急清理] 流: {}", tabAiSubscriptionNew.getName());

        // 1. 设置停止标志
        forceShutdown.set(true);

        // 2. 清理本地对象池
        clearLocalObjectPools();

        // 3. 取消部分任务
        int cancelCount = 0;
        Iterator<Future<?>> iter = activeTasks.iterator();
        while (iter.hasNext() && cancelCount < activeTasks.size() / 2) {
            Future<?> task = iter.next();
            if (!task.isDone() && task.cancel(true)) {
                cancelCount++;
                iter.remove();
            }
        }

        // 4. 调整处理频率
        currentFrameInterval = Math.min(currentFrameInterval * 2, 10000);

        log.warn("[实例紧急清理完成] 流: {}, 取消任务: {}, 新帧间隔: {}ms",
                tabAiSubscriptionNew.getName(), cancelCount, currentFrameInterval);
    }

    /**
     * 清理本地对象池
     */
    private void clearLocalObjectPools() {
        Mat mat;
        while ((mat = localMatPool.poll()) != null) {
            try {
                mat.release();
            } catch (Exception ignored) {}
        }
        localImagePool.clear();
    }

    /**
     * 清理全局对象池
     */
    private static void clearGlobalObjectPools() {
        // 清理Mat池
        Mat mat;
        int matCount = 0;
        while ((mat = GLOBAL_MAT_POOL.poll()) != null && matCount < 50) {
            try {
                mat.release();
                matCount++;
            } catch (Exception ignored) {}
        }

        // 清理转换器池
        Java2DFrameConverter converter;
        int converterCount = 0;
        while ((converter = GLOBAL_CONVERTER_POOL.poll()) != null && converterCount < 20) {
            try {
                converter.close();
                converterCount++;
            } catch (Exception ignored) {}
        }

        // 清理图像池
        GLOBAL_IMAGE_POOL.clear();

        log.debug("[全局对象池清理] Mat: {}, 转换器: {}", matCount, converterCount);
    }

    /**
     * 部分清理全局对象池
     */
    private static void clearPartialGlobalObjectPools() {
        // 清理一半的Mat
        Mat mat;
        int matCount = 0;
        int targetMatClear = GLOBAL_MAT_POOL.size() / 2;
        while ((mat = GLOBAL_MAT_POOL.poll()) != null && matCount < targetMatClear) {
            try {
                mat.release();
                matCount++;
            } catch (Exception ignored) {}
        }

        // 清理一半的转换器
        Java2DFrameConverter converter;
        int converterCount = 0;
        int targetConverterClear = GLOBAL_CONVERTER_POOL.size() / 2;
        while ((converter = GLOBAL_CONVERTER_POOL.poll()) != null && converterCount < targetConverterClear) {
            try {
                converter.close();
                converterCount++;
            } catch (Exception ignored) {}
        }

        log.debug("[部分全局池清理] Mat: {}, 转换器: {}", matCount, converterCount);
    }

    /**
     * 性能统计日志
     */
    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 60000) { // 60秒间隔
            long processed = processedFrames.get();
            long dropped = droppedFrames.get();
            int pending = pendingFrames.get();
            long processingDelay = currentTime - lastProcessTime;

            log.info("[性能统计] 流: {}, 已处理: {}, 丢弃: {}, 排队: {}, 延迟: {}ms, 丢帧率: {}%, 帧间隔: {}ms",
                    tabAiSubscriptionNew.getName(),
                    processed, dropped, pending, processingDelay,
                    processed > 0 ? String.format("%.1f", (double) dropped / (processed + dropped) * 100) : "0.0",
                    currentFrameInterval);

            lastLogTime = currentTime;
        }
    }

    /**
     * 全局性能统计日志
     */
    private void logGlobalPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - LAST_GLOBAL_LOG_TIME > 120000) { // 2分钟全局统计
            long totalProcessed = GLOBAL_PROCESSED_FRAMES.get();
            long totalDropped = GLOBAL_DROPPED_FRAMES.get();
            int activeStreams = ACTIVE_STREAM_COUNT.get();

            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            double memoryUsage = (double) usedMemory / maxMemory;

            // 获取线程池状态
            String threadPoolInfo = "unknown";
            if (GLOBAL_SHARED_EXECUTOR instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) GLOBAL_SHARED_EXECUTOR;
                threadPoolInfo = String.format("活跃: %d, 队列: %d", tpe.getActiveCount(), tpe.getQueue().size());
            }

            log.info("[全局统计] 活跃流: {}, 总处理: {}, 总丢弃: {}, 全局丢帧率: {}%, " +
                            "内存使用: {}% ({}MB/{}MB), 线程池: {}",
                    activeStreams, totalProcessed, totalDropped,
                    totalProcessed > 0 ? String.format("%.1f", (double) totalDropped / (totalProcessed + totalDropped) * 100) : "0.0",
                    String.format("%.1f", memoryUsage * 100),
                    usedMemory / (1024 * 1024), maxMemory / (1024 * 1024),
                    threadPoolInfo);

            LAST_GLOBAL_LOG_TIME = currentTime;
        }
    }

    private boolean isStreamActive() {
        try {
            return RedisCacheHolder.get(tabAiSubscriptionNew.getId() + "newRunPush");
        } catch (Exception e) {
            log.warn("[检查流状态异常] 流: {}", tabAiSubscriptionNew.getName(), e);
            return false;
        }
    }

    private FFmpegFrameGrabber restartGrabber(FFmpegFrameGrabber grabber) throws Exception {
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }
        return createOptimizedGrabber();
    }

    /**
     * 创建优化的视频采集器
     */
    public FFmpegFrameGrabber createOptimizedGrabber() throws Exception {
        log.info("开始探测流{}",tabAiSubscriptionNew.getBeginEventTypes());
        // 第一步：探测流信息
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("rw_timeout", "5000000"); // 5秒超时
        probe.setOption("stimeout", "5000000");   // 兼容性保留
        probe.setOption("max_delay", "5000000"); // 最大等待延迟
        probe.setOption("buffer_size", "1024000");
        VideoDecodePolicy.start(probe);
        String codecName = probe.getVideoCodecName();
        int codecId = probe.getVideoCodec();
        probe.stop();
        probe.close();
        probe.release();

        log.info("[检测视频编码] 流: {}, 编码: {} (ID={})",
                tabAiSubscriptionNew.getName(), codecName, codecId);

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tabAiSubscriptionNew.getBeginEventTypes());

        // GPU/硬件加速设置
        if ("1".equals(tabAiSubscriptionNew.getEventTypes())) {
            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[使用CUDA加速] 流: {}", tabAiSubscriptionNew.getName());
        } else if ("4".equals(tabAiSubscriptionNew.getEventTypes())) {
            grabber.setOption("hwaccel", "qsv");
            if ("h264".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("h264_qsv");
            } else if ("hevc".equalsIgnoreCase(codecName) || "hevc1".equalsIgnoreCase(codecName)) {
                grabber.setVideoCodecName("hevc_qsv");
            }
            log.info("[使用Intel QSV加速] 流: {}", tabAiSubscriptionNew.getName());
        }

        // 基础设置

        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "3000000");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        // 内存友好的实时流设置



        grabber.setOption("max_delay", "500000");




        // 低帧率设置 - 重要的内存优化




        VideoDecodePolicy.start(grabber);
        frameGate = new VideoFrameGate(tabAiSubscriptionNew.getId());
        return grabber;
    }

    /**
     * 强制清理 - 单个流实例的清理
     */
    private void forceCleanup(FFmpegFrameGrabber grabber) {
        log.info("[开始强制清理] 流: {}", tabAiSubscriptionNew.getName());

        // 1. 设置强制停止标志
        forceShutdown.set(true);

        // 2. 取消所有活跃任务
        log.debug("[取消活跃任务] 流: {}, 任务数: {}", tabAiSubscriptionNew.getName(), activeTasks.size());
        activeTasks.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
        activeTasks.clear();

        // 3. 释放视频资源
        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        // 4. 清理本地对象池
        clearLocalObjectPools();

        // 5. 清理ThreadLocal - 防止内存泄漏
        try {
            DNN_NET_CACHE.remove();
            identifyTypeNewLocal.remove();
            threadLocalPushInfo.remove();
            // 清理原有的ThreadLocal
            threadLocalNetCache.remove();
        } catch (Exception e) {
            log.warn("[ThreadLocal清理异常] 流: {}", tabAiSubscriptionNew.getName(), e);
        }

        // 6. 减少活跃流计数
        int remaining = ACTIVE_STREAM_COUNT.decrementAndGet();
        log.info("[强制清理完成] 流: {}, 剩余活跃流: {}", tabAiSubscriptionNew.getName(), remaining);
    }

    /**
     * 外部调用的停止方法
     */
    public void forceStop() {
        log.info("[外部请求强制停止] 流: {}", tabAiSubscriptionNew.getName());
        forceShutdown.set(true);
    }

    /**
     * 静态方法：清理所有全局资源 - 应用关闭时调用
     */
    public static void cleanupAllGlobalResources() {
        log.info("[开始清理所有全局资源] 活跃流: {}", ACTIVE_STREAM_COUNT.get());

        // 1. 清理全局对象池
        clearGlobalObjectPools();

        // 2. 关闭全局线程池
        if (GLOBAL_SHARED_EXECUTOR != null && !GLOBAL_SHARED_EXECUTOR.isShutdown()) {
            GLOBAL_SHARED_EXECUTOR.shutdown();
            try {
                if (!GLOBAL_SHARED_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                    List<Runnable> pendingTasks = GLOBAL_SHARED_EXECUTOR.shutdownNow();
                    log.warn("[强制关闭全局线程池] 剩余任务: {}", pendingTasks.size());

                    if (!GLOBAL_SHARED_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.error("[全局线程池无法完全关闭]");
                    }
                }
            } catch (InterruptedException e) {
                GLOBAL_SHARED_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 3. 重置全局计数器
        ACTIVE_STREAM_COUNT.set(0);
        GLOBAL_PROCESSED_FRAMES.set(0);
        GLOBAL_DROPPED_FRAMES.set(0);

        log.info("[全局资源清理完成]");
    }

    /**
     * 获取全局统计信息 - 用于监控
     */
    public static Map<String, Object> getGlobalStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeStreams", ACTIVE_STREAM_COUNT.get());
        stats.put("totalProcessedFrames", GLOBAL_PROCESSED_FRAMES.get());
        stats.put("totalDroppedFrames", GLOBAL_DROPPED_FRAMES.get());

        if (GLOBAL_SHARED_EXECUTOR instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) GLOBAL_SHARED_EXECUTOR;
            stats.put("threadPoolActiveCount", tpe.getActiveCount());
            stats.put("threadPoolQueueSize", tpe.getQueue().size());
            stats.put("threadPoolCompletedTaskCount", tpe.getCompletedTaskCount());
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        stats.put("memoryUsed", usedMemory);
        stats.put("memoryTotal", runtime.totalMemory());
        stats.put("memoryMax", runtime.maxMemory());
        stats.put("memoryUsagePercent", (double) usedMemory / runtime.maxMemory() * 100);

        return stats;
    }
}