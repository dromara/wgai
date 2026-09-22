package org.jeecg.modules.demo.video.util.batch.batch;


import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.video.util.VideoDecodePolicy;
import org.jeecg.modules.demo.video.util.VideoFrameGate;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.opencv.core.Mat;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

/**
 * 改造版视频读取器:使用批量推理调度器
 * @author wggg
 */
@Slf4j
public class VideoReadPicOnnxNewBatch implements Runnable {

    private final TabAiSubscriptionNew tabAiSubscriptionNew;
    private final RedisTemplate redisTemplate;
    private final String streamId;
    private VideoFrameGate frameGate;
    private final BatchInferenceScheduler scheduler;

    private static final long TARGET_FRAME_INTERVAL = 1000; // 1秒一帧
    private volatile long lastFrameTime = 0;

    private final AtomicLong processedFrames = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    private volatile boolean forceShutdown = false;

    private final ThreadLocal<Java2DFrameConverter> converterLocal =
            ThreadLocal.withInitial(Java2DFrameConverter::new);

    public VideoReadPicOnnxNewBatch(
            TabAiSubscriptionNew tabAiSubscriptionNew,
            RedisTemplate redisTemplate,
            BatchInferenceScheduler scheduler) {
        this.tabAiSubscriptionNew = tabAiSubscriptionNew;
        this.redisTemplate = redisTemplate;
        this.streamId = tabAiSubscriptionNew.getId();
        this.scheduler = scheduler;
    }

    @Override
    public void run() {
        FFmpegFrameGrabber grabber = null;

        try {
            grabber = createOptimizedGrabber();
            List<NetPush> netPushList = tabAiSubscriptionNew.getNetPushList();

            log.info("[视频流启动] 摄像头:{}, 模型数:{}",
                    tabAiSubscriptionNew.getName(), netPushList.size());

            Frame frame;
            int consecutiveNullFrames = 0;

            while (!forceShutdown) {
                // 检查停止标志
                if (!isStreamActive()) {
                    log.warn("[主动停止推送] {}", tabAiSubscriptionNew.getName());
                    break;
                }

                frame = grabber.grabImage();
                if (frame == null) {
                    consecutiveNullFrames++;
                    if (consecutiveNullFrames > 10) {
                        log.info("[连续空帧过多,重启视频流]");
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

                // 帧率控制 - 1秒一帧
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < TARGET_FRAME_INTERVAL) {

                    droppedFrames.incrementAndGet();
                    continue;
                }
                lastFrameTime = currentTime;

                // 转换为Mat
                try {
                    Java2DFrameConverter converter = converterLocal.get();
                    BufferedImage image = converter.getBufferedImage(frame);

                    if (image == null || image.getWidth() <= 0) {

                        continue;
                    }

                    Mat matInfo = bufferedImageToMat(image);
                    if (matInfo == null || matInfo.empty()) {

                        continue;
                    }

                    // ========== 核心:提交到批量调度器 ==========
                    CompletableFuture<Boolean> future = scheduler.submitFullInference(
                            streamId,
                            matInfo,      // Mat会在调度器中释放
                            netPushList,
                            tabAiSubscriptionNew
                    );

                    // 异步处理结果(不阻塞主线程)
                    future.whenComplete((success, ex) -> {
                        if (ex != null) {
                            log.error("[推理异常] 摄像头:{}", streamId, ex);
                        } else if (success) {
                            processedFrames.incrementAndGet();
                        }
                    });

                } catch (Exception e) {
                    log.error("[帧处理异常]", e);
                }

                // 性能监控
                logPerformanceStats();
            }

        } catch (Exception exception) {
            log.error("[处理异常]", exception);
        } finally {
            log.info("[开始清理资源] {}", tabAiSubscriptionNew.getName());
            forceCleanup(grabber);
        }
    }

    // ========== 辅助方法 ==========

    private boolean isStreamActive() {
        try {
            return RedisCacheHolder.get(streamId + "newRunPush");
        } catch (Exception e) {
            log.warn("[检查流状态异常]", e);
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

    private FFmpegFrameGrabber createOptimizedGrabber() throws Exception {
        // 探测流信息
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(
                tabAiSubscriptionNew.getBeginEventTypes()
        );
        probe.setOption("rtsp_transport", "tcp");
        probe.setOption("stimeout", "5000000");
        VideoDecodePolicy.start(probe);

        String codecName = probe.getVideoCodecName();
        int codecId = probe.getVideoCodec();

        probe.stop();
        probe.close();
        probe.release();

        log.info("[检测到视频编码] {} (ID={})", codecName, codecId);

        // 创建grabber
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(
                tabAiSubscriptionNew.getBeginEventTypes()
        );

        // GPU设置
        if ("1".equals(tabAiSubscriptionNew.getEventTypes())) {
            grabber.setOption("hwaccel", "cuda");
            grabber.setOption("hwaccel_device", "0");
            grabber.setOption("hwaccel_output_format", "cuda");
            log.info("[使用GPU_CUDA加速解码]");
        } else if ("4".equals(tabAiSubscriptionNew.getEventTypes())) {
            // Intel加速
            grabber.setOption("hwaccel", "qsv");
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
        grabber.setPixelFormat(AV_PIX_FMT_BGR24);

        // 实时流优化

        grabber.setOption("max_delay", "500000");






        // 帧率限制



        VideoDecodePolicy.start(grabber);
        frameGate = new VideoFrameGate(tabAiSubscriptionNew.getId());
        return grabber;
    }

    private void forceCleanup(FFmpegFrameGrabber grabber) {
        log.info("[开始强制清理] 流: {}", tabAiSubscriptionNew.getName());
        forceShutdown = true;

        if (grabber != null) {
            try { grabber.stop(); } catch (Exception ignored) {}
            try { grabber.release(); } catch (Exception ignored) {}
        }

        converterLocal.remove();
        log.info("[强制清理完成]");
    }

    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime > 60000) { // 每60秒
            long processed = processedFrames.get();
            long dropped = droppedFrames.get();

            log.info("[性能统计] 摄像头:{}, 已处理:{}, 丢弃:{}, 丢帧率:{}%",
                    tabAiSubscriptionNew.getName(),
                    processed,
                    dropped,
                    processed > 0 ? (double) dropped / (processed + dropped) * 100 : 0);

            lastLogTime = currentTime;
        }
    }

    public void forceStop() {
        log.info("[外部请求强制停止] 流: {}", tabAiSubscriptionNew.getName());
        forceShutdown = true;
    }
}
