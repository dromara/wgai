package org.jeecg.modules.demo.video.service.impl;

import ai.onnxruntime.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.audio.mapper.TabAudioDeviceMapper;
import org.jeecg.modules.demo.tab.service.impl.TabAiBaseServiceImpl;
import org.jeecg.modules.demo.video.entity.*;
import org.jeecg.modules.demo.video.mapper.*;
import org.jeecg.modules.demo.video.service.ITabAiSubscriptionNewService;
import org.jeecg.modules.demo.video.util.*;
import org.jeecg.modules.demo.video.util.batch.batch.*;
import org.jeecg.modules.demo.video.util.onnx.*;
import org.jeecg.modules.tab.AIModel.*;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.mapper.TabAiModelMapper;
import org.opencv.core.Mat;
import org.opencv.dnn.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.jeecg.modules.demo.video.util.frame.FrameQualityFilter.printAverageRGB;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;

/**
 * @Description: 多程第三方订阅 - 针对64路视频优化
 * @Version: V2.1 - 64路视频专用优化版
 */
@Slf4j
@Service
public class TabAiSubscriptionNewServiceImpl extends ServiceImpl<TabAiSubscriptionNewMapper, TabAiSubscriptionNew>
        implements ITabAiSubscriptionNewService {

    // ==========   针对64路视频的线程池配置 ==========
    /**
     * 视频流处理线程池
     *
     * 计算依据：
     * - 最大64路视频
     * - 每路视频1个主线程 + 1个处理线程 = 2个线程
     * - grabber创建时阻塞，需要额外缓冲
     * - 核心线程: 64 (常驻，对应64路视频)
     * - 最大线程: 128 (应对grabber创建阻塞)
     * - 队列: 64 (额外缓冲)
     */
    private static final ExecutorService executor = new ThreadPoolExecutor(
            64,   // 核心线程数 = 最大视频路数
            128,  // 最大线程数 = 64路 × 2 (主线程+处理线程)
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),  // 队列大小 = 视频路数
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "VideoStream-" + threadNumber.getAndIncrement());
                    t.setDaemon(false);
                    return t;
                }
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    /**
     *   跟踪活跃任务 - 最多64个
     */
    private static final Map<String, Future<?>> ACTIVE_TASKS = new ConcurrentHashMap<>(64);
    private static final Map<String, Runnable> STOP_ACTIONS = new ConcurrentHashMap<>(64);

    // 原有缓存
    private static final ConcurrentHashMap<String, Net> GLOBAL_NET_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, OnnxModelWrapper> GLOBAL_NET_CACHE_ONNX = new ConcurrentHashMap<>();
    private static final ExecutorService MODEL_PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "subscription-model-preload");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean shuttingDown;
    private final AtomicBoolean recoveryStarted = new AtomicBoolean();
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "subscription-recovery"));
    @Value("${ai.subscription.resume-on-startup:true}") private boolean resumeOnStartup = true;
    @Value("${ai.subscription.frame-interval-ms:300}") private long frameIntervalMs = 300;
    @Value("${ai.subscription.opencv-frame-interval-ms:1000}") private long opencvFrameIntervalMs = 1000;
    @Value("${ai.subscription.max-stream-lag-ms:3000}") private long maxStreamLagMs = 3000;
    private final AtomicBoolean modelPreloadStarted = new AtomicBoolean(false);

    @Autowired TabAiVideoSettingMapper tabAiVideoSettingMapper;

    @Autowired
    TabAudioDeviceMapper tabAudioDeviceMapper;
    @Autowired TabAiModelMapper tabAiModelMapper;
    @Autowired OnnxModelCacheService onnxModelCacheService;
    @Autowired TabAiBaseServiceImpl tabAiBaseService;
    @Autowired TabVideoUtilServiceImpl tabVideoUtilServiceImpl;
    @Autowired private BatchInferenceScheduler batchScheduler;
    @Autowired RedisTemplate redisTemplate;
    @Value("${jeecg.path.upload}") private String upLoadPath;
    @Value("${cameraNum}") private Integer cameraNum;
    /** 默认不在应用启动时加载全部订阅模型，避免抢占 GPU / 拉长启动时间。 */
    @Value("${ai.subscription.model-preload.enabled:false}") private boolean modelPreloadEnabled;

    @PostConstruct
    public void init() {
        if (modelPreloadEnabled) {
            startModelPreloadAsync();
        } else {
            log.info("[订阅模型启动预热] 已通过 ai.subscription.model-preload.enabled=false 关闭");
        }

        log.info("[订阅服务] capacity=64 resumeOnStartup={} frameIntervalMs={}", resumeOnStartup, frameIntervalMs);

    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumeSubscriptions() {
        if (!resumeOnStartup || !recoveryStarted.compareAndSet(false, true)) return;
        recoveryExecutor.execute(() -> {
            try {
                List<TabAiSubscriptionNew> tasks = list(new QueryWrapper<TabAiSubscriptionNew>().eq("run_state", 1));
                log.info("[任务恢复开始] count={}", tasks.size());
                for (TabAiSubscriptionNew task : tasks) {
                    if (shuttingDown || Thread.currentThread().isInterrupted()) break;
                    synchronized (this) {
                        TabAiSubscriptionNew current = getById(task.getId());
                        if (current == null || !Integer.valueOf(1).equals(current.getRunState())) continue;
                        try {
                            startAi(current);
                        } catch (Exception e) {
                            log.error("[任务恢复失败] taskId={} name={}", task.getId(), task.getName(), e);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[任务恢复查询失败]", e);
            }
        });
    }

    private void markStopped(String id) {
        RedisCacheHolder.put(id + "newRunPush", false);
        try { redisTemplate.opsForValue().set(id + "newRunPush", false); }
        catch (Exception e) { log.warn("[运行状态缓存更新失败] taskId={}", id, e); }
        TabAiSubscriptionNew state = new TabAiSubscriptionNew();
        state.setId(id);
        state.setRunState(0);
        updateById(state);
    }

    private void startModelPreloadAsync() {
        if (!modelPreloadStarted.compareAndSet(false, true)) {
            return;
        }
        MODEL_PRELOAD_EXECUTOR.submit(this::preloadSubscriptionModels);
    }

    private void preloadSubscriptionModels() {
        long startMs = System.currentTimeMillis();
        int success = 0;
        int failed = 0;
        int skipped = 0;
        try {
            List<TabAiSubscriptionNew> subscriptions = this.list();
            log.info("[订阅模型启动预热] 订阅数量={}", subscriptions.size());

            for (TabAiSubscriptionNew subscription : subscriptions) {
                if (subscription == null || StringUtils.isBlank(subscription.getId())) {
                    skipped++;
                    continue;
                }
                List<TabAiVideoSetting> settings = tabAiVideoSettingMapper.selectList(
                        new QueryWrapper<TabAiVideoSetting>().eq("sub_id", subscription.getId())
                );
                Set<String> modelIds = collectPreloadModelIds(settings);
                if (modelIds.isEmpty()) {
                    skipped++;
                    log.info("[订阅模型启动预热跳过] 订阅ID={}, 订阅名称={}, 未配置模型",
                            subscription.getId(), subscription.getName());
                    continue;
                }

                for (String modelId : modelIds) {
                    TabAiModel model = tabAiModelMapper.selectById(modelId);
                    if (model == null) {
                        skipped++;
                        log.warn("[订阅模型启动预热跳过] 订阅ID={}, 模型ID={}, 模型不存在",
                                subscription.getId(), modelId);
                        continue;
                    }

                    long modelStartMs = System.currentTimeMillis();
                    try {
                        TabAiModel preloadModel = getTabAiModelInfo(model, "");
                        if (subscription.getModelJmType() != null && subscription.getModelJmType() == 20) {
                            getOnnxModel(subscription, preloadModel);
                        } else {
                            getNetModel(subscription, preloadModel);
                        }
                        success++;
                        log.info("[订阅模型启动预热完成] 订阅ID={}, 订阅名称={}, 模型ID={}, 模型名称={}, 耗时={}ms",
                                subscription.getId(), subscription.getName(), modelId, model.getAiName(),
                                System.currentTimeMillis() - modelStartMs);
                    } catch (Exception e) {
                        failed++;
                        log.warn("[订阅模型启动预热失败] 订阅ID={}, 订阅名称={}, 模型ID={}, 模型名称={}, 原因={}",
                                subscription.getId(), subscription.getName(), modelId, model.getAiName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[订阅模型启动预热异常] {}", e.getMessage());
        }
        log.info("[订阅模型启动预热结束] 成功={}, 跳过={}, 失败={}, 总耗时={}ms",
                success, skipped, failed, System.currentTimeMillis() - startMs);
    }

    private Set<String> collectPreloadModelIds(List<TabAiVideoSetting> settings) {
        Set<String> modelIds = new LinkedHashSet<>();
        if (settings == null) {
            return modelIds;
        }
        for (TabAiVideoSetting setting : settings) {
            if (setting == null) {
                continue;
            }
            if ("0".equals(setting.getIsBefor()) && StringUtils.isNotBlank(setting.getModelId())) {
                modelIds.add(setting.getModelId());
            }
            if (StringUtils.isNotBlank(setting.getNextMode())) {
                modelIds.add(setting.getNextMode());
            }
        }
        return modelIds;
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        recoveryExecutor.shutdownNow();
        log.info("[关闭服务] 停止所有视频流 (最多64路)...");

        int count = 0;
        for (Runnable stop : STOP_ACTIONS.values()) {
            try {
                stop.run();
                count++;
            } catch (Exception e) {
                log.warn("[停止流失败]", e);
            }
        }
        log.info("[已停止{}路视频]", count);

        MODEL_PRELOAD_EXECUTOR.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                List<Runnable> pending = executor.shutdownNow();
                log.warn("[强制关闭线程池，剩余任务: {}]", pending.size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("[✓ 服务关闭完成]");
    }

    @Override
    public synchronized void startAi(TabAiSubscriptionNew requested) {
        if (shuttingDown) throw new IllegalStateException("服务正在关闭");
        String id = requested.getId();
        Future<?> existing = ACTIVE_TASKS.get(id);
        if (existing != null && !existing.isDone()) {
            log.info("[任务已运行，忽略重复启动] taskId={}", id);
            return;
        }
        if (ACTIVE_TASKS.size() >= 64) throw new IllegalStateException("识别任务已达到64路上限");
        TabAiSubscriptionNew task = getById(id);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + id);
        try {
            startAiInternal(task);
            log.info("[任务已提交] taskId={} name={} active={}", id, task.getName(), ACTIVE_TASKS.size());
        } catch (Exception e) {
            try { markStopped(id); } catch (Exception stateError) { e.addSuppressed(stateError); }
            log.error("[任务启动失败] taskId={} name={}", id, task.getName(), e);
            throw new IllegalStateException("识别任务启动失败: " + task.getName(), e);
        }
    }

    /**
     *   完全停止流
     */
    private void stopStreamCompletely(String streamId) {
        log.info("[完全停止] ID: {}, 活跃流: {}/64", streamId, ACTIVE_TASKS.size());

        Runnable stop = STOP_ACTIONS.remove(streamId);
        if (stop != null) {
            try {
                stop.run();
                log.info("[✓ 实例停止] ID: {}", streamId);
            } catch (Exception e) {
                log.warn("[停止实例失败]", e);
            }
        }

        Future<?> task = ACTIVE_TASKS.remove(streamId);
        if (task != null && !task.isDone()) {
            task.cancel(true);
            log.info("[✓ 任务取消] ID: {}", streamId);
        }

        RedisCacheHolder.put(streamId + "newRunPush", false);
        try {
            redisTemplate.opsForValue().set(streamId + "newRunPush", false);
        } catch (Exception e) {
            log.warn("[清理Redis失败]", e);
        }



        log.info("[✓ 停止完成] 剩余活跃流: {}/64", ACTIVE_TASKS.size());
    }

    /**
     * 内部启动逻辑
     */
    private void startAiInternal(TabAiSubscriptionNew tabAiSubscriptionNew) {
        try {
            tabAiBaseService.SendRedisBase();
            redisTemplate.opsForValue().set(tabAiSubscriptionNew.getId()+"newRunPush", true);
            RedisCacheHolder.put(tabAiSubscriptionNew.getId()+"newRunPush", true);
            List<NetPush> NetPushList = new ArrayList<>();
            //获取配置
            List<TabAiVideoSetting> tabAiVideoSettingList = tabAiVideoSettingMapper.selectList(
                    new QueryWrapper<TabAiVideoSetting>().eq("sub_id", tabAiSubscriptionNew.getId())
            );

            if (tabAiVideoSettingList.size() > 0) {
                for (TabAiVideoSetting tabAiVideoSetting : tabAiVideoSettingList) {
                    NetPush allPush = new NetPush();
                    TabVideoUtil tabVideoUtil = new TabVideoUtil();

                    if (tabAiVideoSetting.getIsBy() != null && tabAiVideoSetting.getIsBy() == 0) {
                        tabVideoUtil = tabVideoUtilServiceImpl.getOne(
                                new QueryWrapper<TabVideoUtil>().eq("id", tabAiVideoSetting.getId())
                        );
                        log.info("开启区域入侵识别{}-{}", tabAiSubscriptionNew.getName(), tabVideoUtil);
                    } else {
                        log.info("未开启区域入侵识别{}", tabAiSubscriptionNew.getName());
                    }

                    if ("0".equals(tabAiVideoSetting.getIsBefor())) {
                        List<NetPush> BeforNetPushList = new ArrayList<>();

                        TabAiModel before = getTabAiModelInfo(
                                tabAiModelMapper.selectById(tabAiVideoSetting.getModelId()),
                                tabAiVideoSetting.getModelTxt()
                        );

                        NetPush beforePush = new NetPush();
                        beforePush.setIsBy(tabAiVideoSetting.getIsBy() == null ? 1 : tabAiVideoSetting.getIsBy());
                        beforePush.setTabVideoUtil(tabVideoUtil);
                        beforePush.setTabAiVideoSetting(tabAiVideoSetting);
                        beforePush.setTabAiSubscriptionNew(tabAiSubscriptionNew);
                        beforePush.setDifyType(tabAiVideoSetting.getDifyType() == null ? 1 : tabAiVideoSetting.getDifyType());
                        beforePush.setIsBeforZoom(tabAiVideoSetting.getIsBeforZoom() == null ? 1 : tabAiVideoSetting.getIsBeforZoom());
                        beforePush.setId(tabAiVideoSetting.getId());
                        beforePush.setIsBefor(0);
                        beforePush.setBeforText(tabAiVideoSetting.getModelTxt());
                        beforePush.setClaseeNames(Files.readAllLines(Paths.get(before.getAiNameName())));

                        if (tabAiSubscriptionNew.getModelJmType() != null && tabAiSubscriptionNew.getModelJmType() == 20) { //解码类型 onnx opencv
                            OnnxModelWrapper endNet = getOnnxModel(tabAiSubscriptionNew, before);
                            beforePush.setSession(endNet.getSession());
                            beforePush.setEnv(endNet.getEnv());
                        } else {
                            Net beforeNet = getNetModel(tabAiSubscriptionNew, before);
                            beforePush.setNet(beforeNet);
                        }

                        beforePush.setModelType(before.getSpareOne());
                        beforePush.setTabAiModel(before);
                        beforePush.setUploadPath(upLoadPath);
                        BeforNetPushList.add(beforePush);

                        TabAiModel theEnd = getTabAiModelInfo(
                                tabAiModelMapper.selectById(tabAiVideoSetting.getNextMode()), ""
                        );

                        NetPush endPush = new NetPush();
                        endPush.setId(tabAiVideoSetting.getId());
                        endPush.setIsBefor(0);
                        endPush.setBeforText("");
                        endPush.setIsBy(tabAiVideoSetting.getIsBy() == null ? 1 : tabAiVideoSetting.getIsBy());
                        endPush.setTabVideoUtil(tabVideoUtil);
                        endPush.setTabAiSubscriptionNew(tabAiSubscriptionNew);
                        endPush.setTabAiVideoSetting(tabAiVideoSetting);
                        endPush.setDifyType(tabAiVideoSetting.getDifyType() == null ? 1 : tabAiVideoSetting.getDifyType());
                        endPush.setIsBeforZoom(tabAiVideoSetting.getIsBeforZoom() == null ? 1 : tabAiVideoSetting.getIsBeforZoom());
                        endPush.setClaseeNames(Files.readAllLines(Paths.get(theEnd.getAiNameName())));

                        if (tabAiSubscriptionNew.getModelJmType() != null && tabAiSubscriptionNew.getModelJmType() == 20) {//解码类型 onnx opencv
                            OnnxModelWrapper endNet = getOnnxModel(tabAiSubscriptionNew, theEnd);
                            endPush.setSession(endNet.getSession());
                            endPush.setEnv(endNet.getEnv());
                        } else {
                            Net endNet = getNetModel(tabAiSubscriptionNew, theEnd);
                            endPush.setNet(endNet);
                        }

                        endPush.setModelType(theEnd.getSpareOne());
                        endPush.setTabAiModel(theEnd);
                        endPush.setUploadPath(upLoadPath);
                        endPush.setIsFollow(tabAiVideoSetting.getIsFollow() == null ? 1 : tabAiVideoSetting.getIsFollow());
                        endPush.setFollowPosition(tabAiVideoSetting.getFollowPosition() == null ? 1 : tabAiVideoSetting.getFollowPosition());
                        endPush.setWarinngMethod(tabAiVideoSetting.getWarinngMethod() == null ? 0 : tabAiVideoSetting.getWarinngMethod());
                        endPush.setNoDifText(StringUtils.isEmpty(tabAiVideoSetting.getNoDifText()) ? "" : tabAiVideoSetting.getNoDifText());
                        BeforNetPushList.add(endPush);

                        allPush.setIsBefor(0);
                        allPush.setListNetPush(BeforNetPushList);
                    } else {
                        TabAiModel tabAiModel = getTabAiModelInfo(
                                tabAiModelMapper.selectById(tabAiVideoSetting.getNextMode()), ""
                        );

                        allPush.setId(tabAiVideoSetting.getId());
                        allPush.setBeforText("");
                        allPush.setClaseeNames(Files.readAllLines(Paths.get(tabAiModel.getAiNameName())));

                        if (tabAiSubscriptionNew.getModelJmType() != null && tabAiSubscriptionNew.getModelJmType() == 20) {
                            OnnxModelWrapper endNet = getOnnxModel(tabAiSubscriptionNew, tabAiModel);
                            allPush.setSession(endNet.getSession());
                            allPush.setEnv(endNet.getEnv());
                        } else {
                            Net endNet = getNetModel(tabAiSubscriptionNew, tabAiModel);
                            allPush.setNet(endNet);
                        }

                        allPush.setIsBy(tabAiVideoSetting.getIsBy() == null ? 1 : tabAiVideoSetting.getIsBy());
                        allPush.setTabVideoUtil(tabVideoUtil);
                        allPush.setTabAiVideoSetting(tabAiVideoSetting);
                        allPush.setTabAiSubscriptionNew(tabAiSubscriptionNew);
                        allPush.setDifyType(tabAiVideoSetting.getDifyType() == null ? 1 : tabAiVideoSetting.getDifyType());
                        allPush.setIsBeforZoom(tabAiVideoSetting.getIsBeforZoom() == null ? 1 : tabAiVideoSetting.getIsBeforZoom());
                        allPush.setTabAiModel(tabAiModel);
                        allPush.setModelType(tabAiModel.getSpareOne());
                        allPush.setUploadPath(upLoadPath);
                        allPush.setIsBefor(1);
                        allPush.setIsFollow(tabAiVideoSetting.getIsFollow() == null ? 1 : tabAiVideoSetting.getIsFollow());
                        allPush.setWarinngMethod(tabAiVideoSetting.getWarinngMethod() == null ? 0 : tabAiVideoSetting.getWarinngMethod());
                        allPush.setNoDifText(StringUtils.isEmpty(tabAiVideoSetting.getNoDifText()) ? "未定义" : tabAiVideoSetting.getNoDifText());
                        if(Integer.valueOf(0).equals(tabAiVideoSetting.getIsAudio()) && StringUtils.isNotBlank(tabAiVideoSetting.getAudioDevice())){//开启播报 组装播报设备
                           List<TabAudioDevice> tabAudioDeviceList=tabAudioDeviceMapper.selectBatchIds(Arrays.asList(tabAiVideoSetting.getAudioDevice().split(",")));
                           log.info("[开启播报：播报设备数{}]",tabAudioDeviceList.size());
                           allPush.setTabAudioDevices(tabAudioDeviceList);
                        }
                    }

                    NetPushList.add(allPush);
                }
            } else {
                throw new IllegalArgumentException("任务未配置识别模型");
            }

            tabAiSubscriptionNew.setRunState(1);
            this.updateById(tabAiSubscriptionNew);
            tabAiSubscriptionNew.setNetPushList(NetPushList);


            log.info("[开始推理]{}-共{}路", tabAiSubscriptionNew.getName(), cameraNum);

            if (tabAiSubscriptionNew.getModelJmType() != null && tabAiSubscriptionNew.getModelJmType() == 20) {  //onnx
                log.info("onnx推理");
                if (cameraNum == null || cameraNum < 32) {
                    submitVideoTask(tabAiSubscriptionNew, new VideoReadPicOnnxNew(tabAiSubscriptionNew, redisTemplate, frameIntervalMs, maxStreamLagMs));
                } else if (cameraNum == 32) {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNewThreeTwoOnnx(tabAiSubscriptionNew, redisTemplate));
                } else {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicOnnxNewBatch(tabAiSubscriptionNew, redisTemplate, batchScheduler));
                }
            } else { // opencv
                log.info("opencv dnn推理");
                if (cameraNum == null || cameraNum < 32) {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNew(tabAiSubscriptionNew, redisTemplate, opencvFrameIntervalMs, maxStreamLagMs));
                } else if (cameraNum == 32) {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNewThreeTwo(tabAiSubscriptionNew, redisTemplate));
                } else {
                    throw new IllegalArgumentException("OpenCV模式不支持cameraNum大于32");
                }
            }

        } catch (IOException ex) {
            throw new IllegalStateException("模型配置文件读取失败", ex);
        }
    }

    /**
     *   提交视频任务并跟踪（支持VideoReadPicOnnxNew类型）
     */
    private void submitVideoTask(TabAiSubscriptionNew tab, VideoReadPicOnnxNew task) {
        submitGenericTask(tab, task);
    }

    private void submitGenericTask(TabAiSubscriptionNew tab, Runnable task) {
        String id = tab.getId();
        FutureTask<Void> future = new FutureTask<Void>(() -> {
            String previousName = Thread.currentThread().getName();
            Thread.currentThread().setName("VideoStream-" + id + "-" + tab.getName());
            try { task.run(); }
            catch (Throwable e) { log.error("[任务执行失败] taskId={} name={}", id, tab.getName(), e); }
            finally { Thread.currentThread().setName(previousName); }
            return null;
        }) {
            @Override protected void done() {
                synchronized (TabAiSubscriptionNewServiceImpl.this) {
                    if (ACTIVE_TASKS.remove(id, this)) {
                        STOP_ACTIONS.remove(id);
                        if (!shuttingDown) {
                            try { markStopped(id); }
                            catch (Exception e) { log.error("[任务结束状态更新失败] taskId={}", id, e); }
                        }
                        log.info("[任务已结束] taskId={} name={} cancelled={} active={}",
                                id, tab.getName(), isCancelled(), ACTIVE_TASKS.size());
                    }
                }
            }
        };
        Runnable stop = null;
        if (task instanceof VideoReadPicOnnxNew) stop = ((VideoReadPicOnnxNew) task)::forceStop;
        else if (task instanceof VideoReadPicNew) stop = ((VideoReadPicNew) task)::forceStop;
        else if (task instanceof VideoReadPicNewThreeTwo) stop = ((VideoReadPicNewThreeTwo) task)::forceStop;
        else if (task instanceof VideoReadPicNewThreeTwoOnnx) stop = ((VideoReadPicNewThreeTwoOnnx) task)::forceStop;
        else if (task instanceof VideoReadPicOnnxNewBatch) stop = ((VideoReadPicOnnxNewBatch) task)::forceStop;
        if (stop != null) STOP_ACTIONS.put(id, stop);
        ACTIVE_TASKS.put(id, future);
        try { executor.execute(future); }
        catch (RuntimeException e) {
            ACTIVE_TASKS.remove(id, future);
            Runnable cleanup = STOP_ACTIONS.remove(id);
            if (cleanup != null) cleanup.run();
            throw e;
        }
    }

    public TabAiModel getTabAiModelInfo(TabAiModel tabAiModel, String name) {
        if ("1".equals(tabAiModel.getSpareOne())) {
            tabAiModel.setAiConfig(upLoadPath + File.separator + tabAiModel.getAiConfig());
        }
        tabAiModel.setAiWeights(upLoadPath + File.separator + tabAiModel.getAiWeights());
        tabAiModel.setAiNameName(upLoadPath + File.separator + tabAiModel.getAiNameName());
        if (StringUtils.isNotEmpty(name)) {
            tabAiModel.setAiName(name);
        }
        tabAiModel.setThreshold(tabAiModel.getThreshold() == null ? 0.4 : tabAiModel.getThreshold());
        tabAiModel.setNmsThreshold(tabAiModel.getNmsThreshold() == null ? 0.4 : tabAiModel.getNmsThreshold());
        return tabAiModel;
    }

    public Net getNetModel(TabAiSubscriptionNew tabAiSubscriptionNew, TabAiModel tabAiModel) {
        Net net = GLOBAL_NET_CACHE.get(tabAiSubscriptionNew.getId() + "_" + tabAiModel.getId());
        if (net == null) {
            if ("1".equals(tabAiModel.getSpareOne())) {
                net = Dnn.readNetFromDarknet(tabAiModel.getAiConfig(), tabAiModel.getAiWeights());
            } else if ("2".equals(tabAiModel.getSpareOne()) || "3".equals(tabAiModel.getSpareOne()) || "11".equals(tabAiModel.getSpareOne())) {
                net = Dnn.readNetFromONNX(tabAiModel.getAiWeights());
            }

            if (tabAiModel.getModelJmType() != null && tabAiModel.getModelJmType() == 1) {  //GPU
                net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
                net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);
            } else if (tabAiModel.getModelJmType() != null && tabAiModel.getModelJmType() == 0) { //cpu
                net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
                net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            }
            GLOBAL_NET_CACHE.put(tabAiSubscriptionNew.getId() + "_" + tabAiModel.getId(), net);
        }
        return net;
    }

    public OnnxModelWrapper getOnnxModel(TabAiSubscriptionNew tabAiSubscriptionNew, TabAiModel tabAiModel) {
        return onnxModelCacheService.getOnnxModel(tabAiModel);
    }

    public OnnxModelWrapper getOnnxModelLocal(TabAiSubscriptionNew tabAiSubscriptionNew, TabAiModel tabAiModel) {
        String cacheKey = tabAiSubscriptionNew.getId() + "_" + tabAiModel.getId();
        OnnxModelWrapper wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);

        if (wrapper == null) {
            synchronized (this) {
                wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);
                if (wrapper == null) {
                    try {
                        OrtEnvironment env = OrtEnvironment.getEnvironment();
                        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

                        if (tabAiModel.getModelJmType() != null && tabAiModel.getModelJmType() == 1) {
                            log.info("开启GPU加速");
                            options.addCUDA();
                        } else if (tabAiModel.getModelJmType() != null && tabAiModel.getModelJmType() == 0) {
                            log.info("开启CPU加速");
                            options.setIntraOpNumThreads(1);
                            options.setInterOpNumThreads(1);
                            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                            options.addCPU(true);
                        }else if (tabAiModel.getModelJmType() != null && tabAiModel.getModelJmType() == 2) {
                            log.info("开启NPU加速,需要额外编译");
                          //  options.addCANN(0);

                        }

                        OrtSession session = env.createSession(tabAiModel.getAiWeights(), options);
                        wrapper = new OnnxModelWrapper(env, session);
                        GLOBAL_NET_CACHE_ONNX.put(cacheKey, wrapper);
                        log.info("【ONNX模型缓存】key: {}", cacheKey);
                    } catch (Exception ex) {
                        log.warn("【ONNX加载失败】", ex);
                        throw new RuntimeException("ONNX模型加载失败", ex);
                    }
                }
            }
        }
        return wrapper;
    }

    @Override
    public synchronized void stopAi(TabAiSubscriptionNew tabAiSubscriptionNew) {
        String streamId = tabAiSubscriptionNew.getId();
        log.info("[停止流] ID: {}, 活跃流: {}/64", streamId, ACTIVE_TASKS.size());
        stopStreamCompletely(streamId);
        tabAiSubscriptionNew.setRunState(0);

        this.updateById(tabAiSubscriptionNew);

        List<TabAiVideoSetting> tabAiVideoSettingList = tabAiVideoSettingMapper.selectList(
                new QueryWrapper<TabAiVideoSetting>().eq("sub_id", tabAiSubscriptionNew.getId())
        );
        for (TabAiVideoSetting taSetting:tabAiVideoSettingList) {
            redisTemplate.delete(taSetting.getId());

        }
    }

    @Override
    public void setBox(TabAiSubscriptionNew tabAiSubscriptionNew) {}

    @Override
    public Result<String> getVideoPic(String id) {
        String picName = id + ".jpg";
        FFmpegFrameGrabber grabber = null;
        Mat image = null;
        try {
            TabAiSubscriptionNew tab = getById(id);
            if (tab == null) return Result.error("任务不存在");
            grabber = createOptimizedGrabber(tab);
            VideoFrameGate gate = new VideoFrameGate(id);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            // Decode all reference pictures normally. Never fall back to an unvalidated P/B frame.
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                Frame frame = grabber.grabImage();
                if (frame == null) break;
                if (!gate.accept(frame)) continue;
                image = FrameMatView.bgr(frame);
                String path = upLoadPath + File.separator + picName;
                if (!Imgcodecs.imwrite(path, image)) throw new IOException("图片写入失败: " + path);
                redisTemplate.opsForValue().set(id, picName);
                log.info("[视频截图完成] taskId={} keyFrame={} width={} height={}", id, frame.keyFrame, frame.imageWidth, frame.imageHeight);
                return Result.OK(picName);
            }
            log.warn("[视频截图失败，未取得有效关键帧] taskId={}", id);
            return Result.error("未取得有效关键帧，请检查视频流");
        } catch (Exception e) {
            log.error("[视频截图失败] taskId={}", id, e);
            return Result.error("获取视频图片失败");
        } finally {
            if (image != null) image.release();
            safeRelease(null, grabber);
        }
    }

    /**
     * 安全释放资源
     */
    private void safeRelease( Java2DFrameConverter converter, FFmpegFrameGrabber grabber) {
        if (converter != null) {
            try { converter.close(); } catch (Exception e) { }
        }
        if (grabber != null) {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) { }
        }
    }


    @Override
    public Result<?> test(String id) {
        return Result.OK("test");
    }

    @Async
    @Override
    public void restartAi(String videoId) {
        try{

            TabAiSubscriptionNew obj =
                    this.getById(videoId);

            log.info("停止执行");
            this.stopAi(obj);
            Thread.sleep(3000);
            log.info("开始执行");
            this.startAi(obj);

        }catch(Exception e){
            log.error("重启失败",e);
        }
    }


    public FFmpegFrameGrabber createOptimizedGrabber(TabAiSubscriptionNew tab) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(tab.getBeginEventTypes());
        log.info("当前解码类型{}",tab.getEventTypes());
        // GPU设置
//        if (tab.getEventTypes().equals("1")) {
//            grabber.setOption("hwaccel", "cuda");
//            grabber.setOption("hwaccel_device", "0");
//            grabber.setOption("hwaccel_output_format", "cuda");
//            log.info("[GPU加速]");
//        } else {
//            grabber.setOption("hwaccel", "qsv");
//            log.info("[Intel加速]");
//        }

        // 基础设置
        // ========== 网络和RTSP设置 ==========
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "8000000");    // 8秒连接超时
        grabber.setOption("rw_timeout", "8000000");
        grabber.setOption("timeout", "8000000");

        // ========== 关键：HEVC解码优化 ==========
        grabber.setOption("probesize", "5000000");     // 增大探测大小，帮助找到关键帧
        grabber.setOption("analyzeduration", "5000000"); // 增大分析时长

        // ========== 实时流优化 ==========


        grabber.setOption("max_delay", "500000");


        // ========== 关键：丢弃损坏帧 ==========



        // 像素格式
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        log.info("[开始start] ...");
        long startTime = System.currentTimeMillis();
        VideoDecodePolicy.start(grabber);
        log.info("[✓ start完成] 耗时: {}ms", System.currentTimeMillis() - startTime);

        return grabber;
    }
}
