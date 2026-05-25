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
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     *   优化配置：允许2个流同时启动
     *
     * 为什么改为2：
     * 1. 完全串行（Semaphore=1）启动64路太慢（64×15秒=16分钟）
     * 2. 允许2个同时启动可以提速50%（~8分钟）
     * 3. 2个同时创建grabber不会导致严重的资源竞争
     * 4. 线程池有128个线程，足够支持
     */
    private static final Semaphore STARTUP_SEMAPHORE = new Semaphore(2);

    /**
     * 启动等待超时时间（秒）
     * 64路视频最坏情况：64 ÷ 2 × 15秒 ≈ 8分钟
     */
    private static final int STARTUP_WAIT_TIMEOUT = 900; // 15分钟

    /**
     *   跟踪活跃任务 - 最多64个
     */
    private static final Map<String, Future<?>> ACTIVE_TASKS = new ConcurrentHashMap<>(64);
    private static final Map<String, VideoReadPicOnnxNew> ACTIVE_INSTANCES = new ConcurrentHashMap<>(64);

    // 原有缓存
    private static final ConcurrentHashMap<String, Net> GLOBAL_NET_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, OnnxModelWrapper> GLOBAL_NET_CACHE_ONNX = new ConcurrentHashMap<>();

    @Autowired TabAiVideoSettingMapper tabAiVideoSettingMapper;

    @Autowired
    TabAudioDeviceMapper tabAudioDeviceMapper;
    @Autowired TabAiModelMapper tabAiModelMapper;
    @Autowired TabAiBaseServiceImpl tabAiBaseService;
    @Autowired TabVideoUtilServiceImpl tabVideoUtilServiceImpl;
    @Autowired private BatchInferenceScheduler batchScheduler;
    @Autowired RedisTemplate redisTemplate;
    @Value("${jeecg.path.upload}") private String upLoadPath;
    @Value("${cameraNum}") private Integer cameraNum;

    @PostConstruct
    public void init() {

        log.info("[视频流服务V2.1] 64路视频专用优化版");
        log.info("[线程池配置] 核心:64 | 最大:128 | 队列:64");
        log.info("[启动控制] 允许2个流同时启动 (提速50%)");
        log.info("[最大容量] 支持最多64路视频同时运行");
        log.info("[预计启动时间] 批量启动64路: ~8分钟");

    }

    @PreDestroy
    public void shutdown() {
        log.info("[关闭服务] 停止所有视频流 (最多64路)...");

        int count = 0;
        for (VideoReadPicOnnxNew stream : ACTIVE_INSTANCES.values()) {
            try {
                stream.forceStop();
                count++;
            } catch (Exception e) {
                log.warn("[停止流失败]", e);
            }
        }
        log.info("[已停止{}路视频]", count);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                List<Runnable> pending = executor.shutdownNow();
                log.warn("[强制关闭线程池，剩余任务: {}]", pending.size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        log.info("[✓ 服务关闭完成]");
    }

    @Override
    public void startAi(TabAiSubscriptionNew tabAiSubscriptionNew) {
        String streamId = tabAiSubscriptionNew.getId();

        // ==========   检查容量限制 ==========
        if (ACTIVE_TASKS.size() >= 64) {
            log.warn("[ 超出容量限制] 当前活跃: {}/64, 无法启动新流: {}",
                    ACTIVE_TASKS.size(), tabAiSubscriptionNew.getName());
            return;
        }

        // ========== 检查并清理旧流 ==========
        if (ACTIVE_TASKS.containsKey(streamId)) {
            log.warn("[流已存在，先停止] ID: {}, 当前活跃: {}/64",
                    streamId, ACTIVE_TASKS.size());
            stopStreamCompletely(streamId);
            try { Thread.sleep(2000); } catch (InterruptedException e) { }
        }

        boolean acquired = false;
        try {
            log.info("[排队启动] 流: {}, 排队数: {}, 活跃流: {}/64",
                    tabAiSubscriptionNew.getName(),
                    STARTUP_SEMAPHORE.getQueueLength(),
                    ACTIVE_TASKS.size());

            acquired = STARTUP_SEMAPHORE.tryAcquire(STARTUP_WAIT_TIMEOUT, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[ 启动超时] 流: {} 等待{}秒未获得许可",
                        tabAiSubscriptionNew.getName(), STARTUP_WAIT_TIMEOUT);
                return;
            }

            log.info("[✓ 开始启动] 流: {} ({}/64)",
                    tabAiSubscriptionNew.getName(), ACTIVE_TASKS.size() + 1);

            startAiInternal(tabAiSubscriptionNew);

            // ========== 等待真正启动 ==========
            Thread.sleep(5000);

            Future<?> task = ACTIVE_TASKS.get(streamId);
            if (task != null && task.isDone()) {
                try {
                    task.get();
                    log.warn("[流已结束] {}", tabAiSubscriptionNew.getName());
                } catch (ExecutionException e) {
                    log.warn("[ 启动失败] {}", tabAiSubscriptionNew.getName(), e.getCause());
                }
            } else {
                log.info("[✓✓✓ 启动成功] {} | 活跃流: {}/64",
                        tabAiSubscriptionNew.getName(), ACTIVE_TASKS.size());
            }

        } catch (InterruptedException e) {
            log.warn("[被中断] {}", tabAiSubscriptionNew.getName(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[异常] {}", tabAiSubscriptionNew.getName(), e);
        } finally {
            if (acquired) {
                STARTUP_SEMAPHORE.release();
                log.info("[✓ 释放许可] {} | 可用许可: {}/2",
                        tabAiSubscriptionNew.getName(),
                        STARTUP_SEMAPHORE.availablePermits());
            }
        }
    }

    /**
     *   完全停止流
     */
    private void stopStreamCompletely(String streamId) {
        log.info("[完全停止] ID: {}, 活跃流: {}/64", streamId, ACTIVE_TASKS.size());

        VideoReadPicOnnxNew instance = ACTIVE_INSTANCES.remove(streamId);
        if (instance != null) {
            try {
                instance.forceStop();
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

        try {
            redisTemplate.opsForValue().set(streamId + "newRunPush", false);
            RedisCacheHolder.put(streamId + "newRunPush", false);
        } catch (Exception e) {
            log.warn("[清理Redis失败]", e);
        }

        try { Thread.sleep(1000); } catch (InterruptedException e) { }

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

                    if (tabAiVideoSetting.getIsBefor().equals("0")) {
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
                        if(tabAiVideoSetting.getIsAudio()==0){//开启播报 组装播报设备
                           List<TabAudioDevice> tabAudioDeviceList=tabAudioDeviceMapper.selectBatchIds(Arrays.asList(tabAiVideoSetting.getAudioDevice().split(",")));
                           log.info("[开启播报：播报设备数{}]",tabAudioDeviceList.size());
                           allPush.setTabAudioDevices(tabAudioDeviceList);
                        }
                    }

                    NetPushList.add(allPush);
                }
            } else {
                log.info("[未配置，不启动]");
                return;
            }

            tabAiSubscriptionNew.setRunState(1);
            this.updateById(tabAiSubscriptionNew);
            tabAiSubscriptionNew.setNetPushList(NetPushList);


            log.info("[开始推理]{}-共{}路", tabAiSubscriptionNew.getName(), cameraNum);

            if (tabAiSubscriptionNew.getModelJmType() != null && tabAiSubscriptionNew.getModelJmType() == 20) {  //onnx
                log.info("onnx推理");
                if (cameraNum == null || cameraNum < 32) {
                    submitVideoTask(tabAiSubscriptionNew, new VideoReadPicOnnxNew(tabAiSubscriptionNew, redisTemplate));
                } else if (cameraNum == 32) {
                    new Timer().schedule(new TimerTask() {
                        public void run() {
                            submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNewThreeTwoOnnx(tabAiSubscriptionNew, redisTemplate));
                        }
                    }, 3000);
                } else {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicOnnxNewBatch(tabAiSubscriptionNew, redisTemplate, batchScheduler));
                }
            } else { // opencv
                log.info("opencv dnn推理");
                if (cameraNum == null || cameraNum < 32) {
                    submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNew(tabAiSubscriptionNew, redisTemplate));
                } else if (cameraNum == 32) {
                    new Timer().schedule(new TimerTask() {
                        public void run() {
                            submitGenericTask(tabAiSubscriptionNew, new VideoReadPicNewThreeTwo(tabAiSubscriptionNew, redisTemplate));
                        }
                    }, 30000);
                }
            }

        } catch (IOException ex) {
            log.warn("[文件读取错误]", ex);
        }
    }

    /**
     *   提交视频任务并跟踪（支持VideoReadPicOnnxNew类型）
     */
    private void submitVideoTask(TabAiSubscriptionNew tab, VideoReadPicOnnxNew task) {
        String id = tab.getId();
        Future<?> future = executor.submit(task);
        ACTIVE_TASKS.put(id, future);
        ACTIVE_INSTANCES.put(id, task);
        log.info("[任务已提交] ID: {}, 活跃数: {}/64", id, ACTIVE_TASKS.size());
    }

    /**
     *   提交通用Runnable任务（用于其他视频处理器类型）
     */
    private void submitGenericTask(TabAiSubscriptionNew tab, Runnable task) {
        String id = tab.getId();
        Future<?> future = executor.submit(task);
        ACTIVE_TASKS.put(id, future);
        // 注意：非VideoReadPicOnnxNew类型无法放入ACTIVE_INSTANCES
        log.info("[任务已提交] ID: {}, 活跃数: {}/64", id, ACTIVE_TASKS.size());
    }

    public TabAiModel getTabAiModelInfo(TabAiModel tabAiModel, String name) {
        if (tabAiModel.getSpareOne().equals("1")) {
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
            if (tabAiModel.getSpareOne().equals("1")) {
                net = Dnn.readNetFromDarknet(tabAiModel.getAiConfig(), tabAiModel.getAiWeights());
            } else if (tabAiModel.getSpareOne().equals("2") || tabAiModel.getSpareOne().equals("3") || tabAiModel.getSpareOne().equals("11")) {
                net = Dnn.readNetFromONNX(tabAiModel.getAiWeights());
            }

            if (tabAiModel.getModelJmType().equals("1")) {  //GPU
                net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
                net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);
            } else if (tabAiModel.getModelJmType().equals("0")) { //cpu
                net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
                net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            }
            GLOBAL_NET_CACHE.put(tabAiSubscriptionNew.getId() + "_" + tabAiModel.getId(), net);
        }
        return net;
    }

    public OnnxModelWrapper getOnnxModel(TabAiSubscriptionNew tabAiSubscriptionNew, TabAiModel tabAiModel) {
        String cacheKey = tabAiSubscriptionNew.getId() + "_" + tabAiModel.getId();
        OnnxModelWrapper wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);

        if (wrapper == null) {
            synchronized (this) {
                wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);
                if (wrapper == null) {
                    try {
                        OrtEnvironment env = OrtEnvironment.getEnvironment();
                        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

                        if (tabAiModel.getModelJmType().equals("1")) {
                            log.info("开启GPU加速");
                            options.addCUDA();
                        } else if (tabAiModel.getModelJmType().equals("0")) {
                            log.info("开启CPU加速");
                            options.setIntraOpNumThreads(1);
                            options.setInterOpNumThreads(1);
                            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                            options.addCPU(true);
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
    public void stopAi(TabAiSubscriptionNew tabAiSubscriptionNew) {
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
        log.info("=== 开始获取图片 ===");
        String outputPath = upLoadPath + File.separator;
        String picName = id + ".jpg";

        FFmpegFrameGrabber grabber = null;
        Java2DFrameConverter converter = new Java2DFrameConverter();

        try {
            TabAiSubscriptionNew tab = this.getById(id);
            log.info("RTSP地址: {}", tab.getBeginEventTypes());

            grabber = createOptimizedGrabber(tab);

            log.info("=== 流信息 ===");
            log.info("视频编码: {}", grabber.getVideoCodecName());
            log.info("图像尺寸: {}x{}", grabber.getImageWidth(), grabber.getImageHeight());

            Frame frame = null;
            Mat mat = null;


            int tryCount = 0;
            int maxTries = 100;
            boolean foundValidFrame = false;

            log.info("开始查找有效视频帧...");

            while (tryCount < maxTries && !foundValidFrame) {
                tryCount++;

                // ✅ 关键修改1: 优先获取关键帧
                frame = grabber.grabKeyFrame();

                // 如果grabKeyFrame不支持，回退到普通方式
                if (frame == null) {
                    frame = grabber.grabImage();
                }

                if (frame == null || frame.image == null) {
                    if (tryCount % 20 == 0) {
                        log.info("已尝试 {} 次，继续...", tryCount);
                    }
                    continue;
                }else if (frame.imageWidth <= 0 || frame.imageHeight <= 0){
                    if (tryCount % 20 == 0) {
                        log.info("已尝试 {} 次，继续...", tryCount);
                    }
                    continue;

                }

                BufferedImage image = converter.getBufferedImage(frame);
                mat = bufferedImageToMat(image);
                log.info("✓ 第 {} 次尝试获取到有效视频帧", tryCount);

                log.info("帧信息 - {}x{}, 通道:{}", frame.imageWidth, frame.imageHeight, frame.imageChannels);
                 foundValidFrame = true;
                 break;


//
//
//                if (mat != null && !mat.empty()) {
//                    // ✅ 关键修改3: 检查图片是否有效（非纯灰/纯黑）
//                    if (isValidImage(mat)) {
//                        log.info("✓ 第 {} 次尝试获取到有效视频帧", tryCount);
//                        log.info("帧信息 - {}x{}, 通道:{}",
//                                frame.imageWidth, frame.imageHeight, frame.imageChannels);
//                        foundValidFrame = true;
//                        break;
//                    } else {
//                        log.warn("第 {} 次获取的帧为无效图像(灰色/黑色)，继续尝试...", tryCount);
//                        mat.release();
//                        mat = null;
//                    }
//                }
            }

            if (!foundValidFrame || mat == null) {
                log.error("尝试了 {} 次后仍未获取到有效帧", maxTries);
                return Result.error(picName);
            }

            // 保存图片
            String fullPath = outputPath + picName;

            Imgcodecs.imwrite(fullPath, mat);
            //保存到redis

            redisTemplate.opsForValue().set(id,picName);



            log.info("✓ 成功保存图片: {}", picName);


        } catch (Exception ex) {
            log.error("获取失败!", ex);
            return Result.error(picName);
        } finally {
            safeRelease(converter, grabber);
        }

        return Result.OK(picName);
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
        grabber.setOption("flags", "low_delay");
        grabber.setOption("fflags", "nobuffer+discardcorrupt");
        grabber.setOption("max_delay", "500000");
        grabber.setOption("buffer_size", "1024000");  // 增大缓冲区

        // ========== 关键：丢弃损坏帧 ==========
        grabber.setOption("skip_frame", "nokey");     // 跳过非关键帧（加速）
        grabber.setOption("err_detect", "careful");   // 更严格的错误检测

        // 像素格式
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);

        log.info("[开始start] ...");
        long startTime = System.currentTimeMillis();
        grabber.start();
        log.info("[✓ start完成] 耗时: {}ms", System.currentTimeMillis() - startTime);

        return grabber;
    }
}