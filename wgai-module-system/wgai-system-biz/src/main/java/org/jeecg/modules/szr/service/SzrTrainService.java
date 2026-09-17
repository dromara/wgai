package org.jeecg.modules.szr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.szr.entity.TabSzrDz;
import org.jeecg.modules.demo.szr.entity.TabSzrVideo;
import org.jeecg.modules.demo.szr.service.ITabSzrDzService;
import org.jeecg.modules.demo.szr.service.ITabSzrVideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数字人形象训练 + 启用。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>训练</b>：对一个说话动作的视频跑一次 MuseTalk avatar 预处理，
 *       产出 {@code results/<版本>/avatars/<形象ID>/} 缓存。没有这个缓存就没法说话。</li>
 *   <li><b>启用</b>：把某个数字人的静置画面和说话形象写进驱动服务 config.yaml，
 *       然后重启驱动服务。</li>
 * </ol>
 *
 * <p><b>为什么"启用"必须重启，而"换动作"不用：</b>
 * 说话形象是预加载在内存里的，运行时能随便切；
 * 但静置画面（不说话时循环播的那一路）全局只有一路、启动时就定死了。
 * "当前站在屏幕上的是谁"由静置画面决定，所以换人必须重启。
 *
 * <p>⚠ 本功能要求 Java 和驱动服务在同一台服务器上。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrTrainService {

    /** 训练状态 */
    public static final int TRAIN_NONE = 0;
    public static final int TRAIN_RUNNING = 1;
    public static final int TRAIN_OK = 2;
    public static final int TRAIN_FAIL = 3;

    /**
     * 单次预处理的超时。几百帧的短视频正常 30 秒 ~ 2 分钟，
     * 给到 30 分钟纯粹是防止卡死的进程永远挂着占 GPU。
     */
    private static final long TRAIN_TIMEOUT_MIN = 30;

    /** 重启驱动服务的超时。restart.sh 自己最多等 60 秒模型加载 */
    private static final long RESTART_TIMEOUT_SEC = 180;

    /** 日志窗口保留的行数，够定位问题又不至于把内存吃了 */
    private static final int LOG_KEEP_LINES = 400;

    @Value("${jeecg.path.upload}")
    private String upLoadPath;

    @Autowired
    private SzrTrainConfig trainConfig;

    @Autowired
    private SzrDriverConfig driverConfig;

    @Autowired
    private ITabSzrDzService tabSzrDzService;

    @Autowired
    private ITabSzrVideoService tabSzrVideoService;

    /**
     * 训练串行执行。
     *
     * <p>⚠ 必须单线程：预处理会吃满 GPU，两个一起跑必然 OOM。
     * 队列里排着也比并发跑强。
     */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "szr-train");
        t.setDaemon(true);
        return t;
    });

    /** 当前正在跑的任务，null 表示空闲。用它做"同一时刻只有一个任务"的判断 */
    private final AtomicReference<TrainTask> current = new AtomicReference<>();

    /** 最近一次任务（含已结束的），前端轮询进度用 */
    @Getter
    private volatile TrainTask lastTask;

    /** 一次训练/启用任务的实时状态 */
    @Getter
    public static class TrainTask {
        private final String id = "train-" + System.currentTimeMillis();
        private final String type;
        private final String target;
        private final long startAt = System.currentTimeMillis();
        private volatile long endAt;
        /** running / success / failed */
        private volatile String state = "running";
        private volatile String message = "";
        private final LinkedList<String> logs = new LinkedList<>();

        TrainTask(String type, String target) {
            this.type = type;
            this.target = target;
        }

        void log(String line) {
            synchronized (logs) {
                logs.add(line);
                while (logs.size() > LOG_KEEP_LINES) {
                    logs.removeFirst();
                }
            }
            log.info("[szr-train] {}", line);
        }

        public List<String> snapshotLogs() {
            synchronized (logs) {
                return new ArrayList<>(logs);
            }
        }

        void finish(String state, String message) {
            this.state = state;
            this.message = message;
            this.endAt = System.currentTimeMillis();
        }
    }

    // =========================================================================
    // 训练
    // =========================================================================

    /**
     * 对一个说话动作跑预处理。
     *
     * @param dzId tab_szr_dz 主键
     * @return 任务 id，前端拿它轮询进度
     */
    public String submitTrain(String dzId) {
        trainConfig.ensureReady();

        TabSzrDz dz = tabSzrDzService.getById(dzId);
        if (dz == null) {
            throw new IllegalArgumentException("动作不存在: " + dzId);
        }
        validateTrainable(dz);
        // 用户选的是「先检查，不自动停」：驱动服务在跑就直接拒绝，不去动它。
        // 理由：预处理和常驻服务抢显存必然 OOM，而自动 kill 会把正在演示的数字人打断。
        assertDriverStopped();

        return submit(new TrainTask("train", dz.getSzrTitle()), task -> {
            try {
                task.finish("success", trainOne(task, dz));
            } catch (Exception e) {
                task.finish("failed", brief(e.getMessage()));
            }
        });
    }

    /**
     * 训练【指定的这一个数字人】名下的全部说话动作（站立 / 左介绍 / 右介绍…）。
     *
     * <p>作用域是一个人，不是全库。数字人管理页每一行的「训练」按钮对应这里。
     *
     * <p>串行跑，一个动作失败不影响后面的 —— 三个动作成了两个，那两个照样能用，
     * 没必要因为一个失败让整批白跑。
     */
    public String submitTrainSzr(String szrId) {
        trainConfig.ensureReady();

        TabSzrVideo szr = tabSzrVideoService.getById(szrId);
        if (szr == null) {
            throw new IllegalArgumentException("数字人不存在: " + szrId);
        }
        List<TabSzrDz> targets = new ArrayList<>();
        for (TabSzrDz dz : tabSzrDzService.list(new QueryWrapper<TabSzrDz>()
                .eq("szr_id", szrId).orderByAsc("create_time"))) {
            // 静置动作只是循环播放的素材，不做口型驱动，不用预处理
            if (dz.getDzType() != null && dz.getDzType() == SzrAvatarService.TYPE_IDLE) {
                continue;
            }
            targets.add(dz);
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("数字人「" + szr.getSzrName()
                    + "」下面没有说话动作。请先到「数字人动作管理」里加动作、上传视频、填驱动形象ID");
        }
        // 先把所有动作校验一遍再开跑：跑了 5 分钟才发现第三个没填形象ID 很浪费
        for (TabSzrDz dz : targets) {
            validateTrainable(dz);
        }
        assertDriverStopped();

        return submit(new TrainTask("train", szr.getSzrName() + "（" + targets.size() + " 个动作）"), task -> {
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                TabSzrDz dz = targets.get(i);
                task.log("========== [" + (i + 1) + "/" + targets.size() + "] "
                        + dz.getSzrTitle() + " ==========");
                try {
                    trainOne(task, dz);
                    ok++;
                } catch (Exception e) {
                    task.log("❌ " + dz.getSzrTitle() + " 训练失败: " + e.getMessage());
                    failed.add(dz.getSzrTitle());
                }
            }
            String msg = "完成：成功 " + ok + " 个"
                    + (failed.isEmpty() ? "" : "，失败 " + failed.size() + " 个（" + String.join("、", failed) + "）");
            task.log(msg);
            task.finish(failed.isEmpty() ? "success" : "failed", msg);
        });
    }

    /** 训练前的静态校验，跑之前就能发现的问题不要等跑完才报 */
    private void validateTrainable(TabSzrDz dz) {
        String avatarId = dz.getAvatarId() == null ? "" : dz.getAvatarId().trim();
        if (!SzrTrainConfig.SAFE_ID.matcher(avatarId).matches()) {
            throw new IllegalArgumentException("动作「" + dz.getSzrTitle() + "」的驱动形象ID「"
                    + avatarId + "」不合法。只能用字母、数字、下划线、连字符，长度 1~64，例如 zhang_left。"
                    + "它会被用作目录名，所以不能带路径分隔符和特殊字符");
        }
        if (resolveVideo(dz.getSzrFile()) == null) {
            throw new IllegalArgumentException("动作「" + dz.getSzrTitle()
                    + "」没有上传视频，或文件已丢失: " + dz.getSzrFile());
        }
    }

    /** 训练单个动作并回写状态。失败抛异常。 */
    private String trainOne(TrainTask task, TabSzrDz dz) throws Exception {
        String dzId = dz.getId();
        tabSzrDzService.update(new TabSzrDz().setTrainStatus(TRAIN_RUNNING).setTrainMsg("训练中…"),
                new QueryWrapper<TabSzrDz>().eq("id", dzId));
        try {
            String summary = runPreprocess(task, dz.getAvatarId().trim(), resolveVideo(dz.getSzrFile()));
            tabSzrDzService.update(new TabSzrDz().setTrainStatus(TRAIN_OK).setTrainMsg(summary),
                    new QueryWrapper<TabSzrDz>().eq("id", dzId));
            return summary;
        } catch (Exception e) {
            tabSzrDzService.update(new TabSzrDz().setTrainStatus(TRAIN_FAIL).setTrainMsg(brief(e.getMessage())),
                    new QueryWrapper<TabSzrDz>().eq("id", dzId));
            throw e;
        }
    }

    /** 真正跑预处理，返回成功摘要。失败抛异常。 */
    private String runPreprocess(TrainTask task, String avatarId, File video) throws Exception {
        File cacheDir = trainConfig.avatarDir(avatarId);

        // ⚠ 必须先删旧缓存。realtime_inference 发现目录已存在时会走 input() 交互确认，
        //   无人值守下会永久卡死在那里 —— 表现成"训练一直转圈，GPU 却没动静"。
        if (cacheDir.exists()) {
            task.log("清理旧缓存: " + cacheDir.getAbsolutePath());
            deleteAvatarCache(cacheDir);
        }

        writeRealtimeYaml(task, avatarId, video);

        List<String> cmd = buildPreprocessCommand();
        task.log("开始预处理，形象=" + avatarId + " 视频=" + video.getAbsolutePath());
        task.log("（几百帧的短视频通常 30 秒 ~ 2 分钟）");
        int code = exec(task, cmd, TRAIN_TIMEOUT_MIN * 60);

        // ⚠ 成功与否以【缓存是否完整】为准，不只看退出码。
        //   预处理跑完之后脚本还会拿那段占位音频做一次推理，
        //   那一步失败不影响缓存已经生成，不该判成训练失败。
        if (!trainConfig.cacheComplete(avatarId)) {
            throw new IllegalStateException("预处理没有产出完整缓存(退出码 " + code + ")，"
                    + "常见原因：视频里检不到人脸、显存不足、视频文件损坏。看下方日志");
        }
        int frames = trainConfig.frameCount(avatarId);
        String summary = "训练成功：" + frames + " 帧，缓存 " + cacheDir.getAbsolutePath();
        task.log(summary);
        if (code != 0) {
            task.log("⚠ 脚本退出码 " + code + "，但缓存已完整生成，按成功处理"
                    + "（多半是最后那段占位音频的试推理报错，不影响使用）");
        }
        return summary;
    }

    /**
     * 生成预处理配置。
     *
     * <p>⚠ 这个文件每次训练都被整体覆盖，一次只写一个形象 ——
     * 写多个的话脚本会挨个跑，中间任何一个失败都很难定位是哪个。
     */
    private void writeRealtimeYaml(TrainTask task, String avatarId, File video) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# 本文件由 WGAI 平台自动生成，每次点「训练」都会被整体覆盖，请不要手工编辑。\n");
        sb.append("# 一次只写一个形象，便于定位失败。\n");
        sb.append(avatarId).append(":\n");
        sb.append("  preparation: True\n");
        // v15 强制 bbox_shift=0；和缓存里 avator_info.json 存的值不一致会触发 input() 卡死
        sb.append("  bbox_shift: 0\n");
        sb.append("  video_path: \"").append(yamlEscape(video.getAbsolutePath())).append("\"\n");
        // realtime_inference 的本职是"预处理 + 立刻推理一段"，不给音频跑不起来。
        // 这段音频只是占位，生成出来的结果视频没用。
        sb.append("  audio_clips:\n");
        sb.append("    audio_0: \"").append(yamlEscape(placeholderAudio().getAbsolutePath())).append("\"\n");

        Path conf = Paths.get(trainConfig.getConfPath());
        Files.write(conf, sb.toString().getBytes(StandardCharsets.UTF_8));
        task.log("已写预处理配置: " + conf);
    }

    /**
     * 预处理要用的占位音频。
     *
     * <p>优先用 MuseTalk 自带的样例；没有就生成一段 1 秒静音 wav。
     * 不依赖样例文件是为了少一个"换了台机器就跑不了"的前提。
     */
    private File placeholderAudio() throws IOException {
        File sample = new File(trainConfig.getMuseRoot(), "data/audio/yongen.wav");
        if (sample.isFile()) {
            return sample;
        }
        File silent = new File(upLoadPath, "szr_train/_placeholder_1s.wav");
        if (silent.isFile() && silent.length() > 44) {
            return silent;
        }
        Files.createDirectories(silent.getParentFile().toPath());
        Files.write(silent.toPath(), silentWav16k(1));
        return silent;
    }

    /** 16kHz 单声道 16bit 的静音 wav */
    private static byte[] silentWav16k(int seconds) {
        int sampleRate = 16000;
        int dataLen = sampleRate * 2 * seconds;
        byte[] out = new byte[44 + dataLen];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        b.putInt(36 + dataLen);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(sampleRate);
        b.putInt(sampleRate * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII));
        b.putInt(dataLen);
        return out;
    }

    /**
     * 预处理命令。
     *
     * <p>⚠ 所有变量走 {@code bash -c} 的<b>位置参数</b>传入，绝不拼进命令字符串。
     * 路径来自数据库和上传文件名，拼字符串就是命令注入。
     */
    private List<String> buildPreprocessCommand() {
        String script = "set -e; "
                + "source \"$1\"; "
                + "cd \"$2\"; "
                + "exec python -m scripts.realtime_inference "
                + "--inference_config \"$3\" --result_dir \"$4\" "
                + "--unet_model_path \"$5\" --unet_config \"$6\" "
                + "--version \"$7\" --fps \"$8\"";
        List<String> cmd = new ArrayList<>();
        Collections.addAll(cmd, "bash", "-c", script, "szr-train",
                trainConfig.getVenv(),
                trainConfig.getMuseRoot(),
                trainConfig.getConfPath(),
                "results",
                trainConfig.getUnetModelPath(),
                trainConfig.getUnetConfig(),
                trainConfig.getVersion(),
                String.valueOf(trainConfig.getFps()));
        return cmd;
    }

    // =========================================================================
    // 启用（切换当前数字人）
    // =========================================================================

    /**
     * 启用某个数字人：改驱动服务配置 + 重启。
     *
     * <p>⚠ 会让视频流中断 10~30 秒（要重新加载 UNet/VAE/whisper 和所有形象），
     * 前端需要重连。
     */
    public String submitEnable(String szrId) {
        trainConfig.ensureReady();

        TabSzrVideo szr = tabSzrVideoService.getById(szrId);
        if (szr == null) {
            throw new IllegalArgumentException("数字人不存在: " + szrId);
        }
        List<TabSzrDz> all = tabSzrDzService.list(
                new QueryWrapper<TabSzrDz>().eq("szr_id", szrId).orderByAsc("create_time"));

        // 说话形象必须是训练成功的：填了 id 但没训练过，驱动服务加载时会跳过，
        // 表现成"选了动作画面没变"。这里直接过滤掉，不让它进配置。
        List<TabSzrDz> speakables = new ArrayList<>();
        for (TabSzrDz dz : all) {
            boolean isSpeak = dz.getDzType() == null || dz.getDzType() == SzrAvatarService.TYPE_SPEAK;
            if (isSpeak && dz.getAvatarId() != null && !dz.getAvatarId().trim().isEmpty()
                    && trainConfig.cacheComplete(dz.getAvatarId().trim())) {
                speakables.add(dz);
            }
        }
        if (speakables.isEmpty()) {
            throw new IllegalStateException("数字人「" + szr.getSzrName()
                    + "」还没有任何训练成功的说话动作，无法启用。"
                    + "请先在「数字人动作管理」里给它加说话动作、填驱动形象ID，并逐个点「训练」");
        }
        // 默认形象：优先取标了「默认动作」的那条
        speakables.sort(Comparator.comparing(
                d -> d.getIsDefault() != null && d.getIsDefault() == 1 ? 0 : 1));
        String defaultAvatar = speakables.get(0).getAvatarId().trim();

        List<String> extras = new ArrayList<>();
        for (int i = 1; i < speakables.size(); i++) {
            extras.add(speakables.get(i).getAvatarId().trim());
        }

        // 静置画面：优先用显式登记的静置动作（dz_type=0）。
        // 没有的话就用默认说话动作那条自己的视频 —— 常见做法就是「正常站立」既当默认说话
        // 动作、又当待机画面，这样不用为同一段视频建两条记录。
        String idleVideo = "";
        for (TabSzrDz dz : all) {
            if (dz.getDzType() != null && dz.getDzType() == SzrAvatarService.TYPE_IDLE) {
                File v = resolveVideo(dz.getSzrFile());
                if (v != null) {
                    idleVideo = v.getAbsolutePath();
                }
                break;
            }
        }
        if (idleVideo.isEmpty()) {
            File v = resolveVideo(speakables.get(0).getSzrFile());
            if (v != null) {
                idleVideo = v.getAbsolutePath();
            }
        }

        final String fIdle = idleVideo;
        return submit(new TrainTask("enable", szr.getSzrName()), task -> {
            try {
                task.log("默认形象: " + defaultAvatar);
                task.log("其它说话形象: " + (extras.isEmpty() ? "无" : String.join(", ", extras)));
                task.log("静置画面: " + (fIdle.isEmpty() ? "（用默认形象自身帧序列）" : fIdle));

                patchStreamConfig(task, defaultAvatar, fIdle, extras);

                task.log("重启驱动服务（视频流会中断，前端需要重连）…");
                int code = exec(task, buildRestartCommand(), RESTART_TIMEOUT_SEC);
                if (code != 0) {
                    throw new IllegalStateException("重启脚本退出码 " + code + "，看下方日志");
                }

                // 同一时刻只有一个数字人是启用状态
                tabSzrVideoService.update(new TabSzrVideo().setIsOk(0),
                        new QueryWrapper<TabSzrVideo>().ne("id", szrId));
                tabSzrVideoService.update(new TabSzrVideo().setIsOk(1),
                        new QueryWrapper<TabSzrVideo>().eq("id", szrId));

                String msg = "已启用「" + szr.getSzrName() + "」，可用说话形象 "
                        + (extras.size() + 1) + " 个";
                task.log(msg);
                task.finish("success", msg);
            } catch (Exception e) {
                task.finish("failed", brief(e.getMessage()));
            }
        });
    }

    private List<String> buildRestartCommand() {
        // restart.sh 自己会 source 虚拟环境、等模型加载就绪，这里只管调它
        List<String> cmd = new ArrayList<>();
        Collections.addAll(cmd, "bash", trainConfig.getRestartPath());
        return cmd;
    }

    // =========================================================================
    // 驱动服务 config.yaml 的定点改写
    // =========================================================================

    /**
     * 只改 {@code avatar.id} / {@code avatar.idle_video} / {@code extra_avatars} 三处。
     *
     * <p>⚠ <b>不能用 snakeyaml 读出来再 dump 回去</b> —— 那样文件里所有注释会全部消失。
     * 那份 config.yaml 里写满了"为什么不能调大""调了会怎样"的告警注释，
     * 是排查问题的主要依据，丢了等于把踩过的坑重新埋回去。
     * 所以这里做的是逐行定点替换，其余内容一个字节都不动。
     */
    private void patchStreamConfig(TrainTask task, String avatarId, String idleVideo,
                                   List<String> extras) throws IOException {
        Path path = Paths.get(trainConfig.getStreamConfPath());
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size() + extras.size() + 2);

        boolean inAvatar = false;
        boolean idDone = false;
        boolean idleDone = false;
        boolean extrasDone = false;
        boolean skippingExtraItems = false;

        for (String line : lines) {
            boolean topLevelKey = line.matches("^[A-Za-z_][A-Za-z0-9_-]*:.*$");
            if (topLevelKey) {
                inAvatar = line.startsWith("avatar:");
                skippingExtraItems = false;
            }

            // ---- extra_avatars 段：整体替换 ----
            if (topLevelKey && line.startsWith("extra_avatars:")) {
                out.add(extras.isEmpty() ? "extra_avatars: []" : "extra_avatars:");
                for (String id : extras) {
                    out.add("  - id: " + id);
                }
                extrasDone = true;
                // 后面原有的列表项要丢掉；注释行保留（那是使用说明）
                skippingExtraItems = true;
                continue;
            }
            if (skippingExtraItems && line.matches("^\\s*-\\s.*$")) {
                continue;
            }

            // ---- avatar 段里的两行 ----
            if (inAvatar && !idDone && line.matches("^\\s+id:\\s*.*$")) {
                out.add("  id: " + avatarId);
                idDone = true;
                continue;
            }
            if (inAvatar && !idleDone && line.matches("^\\s+idle_video:\\s*.*$")) {
                out.add("  idle_video: \"" + yamlEscape(idleVideo) + "\"");
                idleDone = true;
                continue;
            }

            out.add(line);
        }

        if (!idDone || !idleDone) {
            throw new IllegalStateException("驱动服务配置里没找到 avatar.id 或 avatar.idle_video，"
                    + "文件结构可能被改过: " + path);
        }
        if (!extrasDone) {
            // 老版本 config.yaml 没有这一段，补到末尾
            out.add("");
            out.add("extra_avatars:" + (extras.isEmpty() ? " []" : ""));
            for (String id : extras) {
                out.add("  - id: " + id);
            }
        }

        // 先备份再覆盖：改坏了还能捡回来
        Path bak = Paths.get(path.toString() + ".bak");
        Files.write(bak, lines, StandardCharsets.UTF_8);
        Files.write(path, out, StandardCharsets.UTF_8);
        task.log("已更新驱动服务配置: " + path + "（原文件已备份为 " + bak.getFileName() + "）");
    }

    // =========================================================================
    // 公共
    // =========================================================================

    /** 有没有任务在跑 */
    public boolean isBusy() {
        TrainTask t = current.get();
        return t != null && "running".equals(t.getState());
    }

    private String submit(TrainTask task, java.util.function.Consumer<TrainTask> body) {
        if (!current.compareAndSet(null, task)) {
            TrainTask running = current.get();
            throw new IllegalStateException("已有任务在执行中（" + (running == null ? "?" : running.getTarget())
                    + "），GPU 一次只能跑一个，请等它结束");
        }
        lastTask = task;
        pool.execute(() -> {
            try {
                body.accept(task);
            } catch (Throwable t) {
                // execute 不吞异常，但 Error 不是 Exception，上面的 catch 抓不到
                task.finish("failed", brief(t.toString()));
                log.warn("[szr-train] 任务异常", t);
            } finally {
                current.set(null);
            }
        });
        return task.getId();
    }

    /**
     * 确认驱动服务没在跑。
     *
     * <p>用户选择的是「先检查，不自动停」：常驻服务占着 7~11G 显存，
     * 预处理再要几个 G，16G 卡上大概率 OOM。这里直接拦住，让人工去停 ——
     * 自动 kill 会把正在演示的数字人打断，那个代价更高。
     */
    private void assertDriverStopped() {
        try {
            org.springframework.http.client.SimpleClientHttpRequestFactory f =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            f.setConnectTimeout(1500);
            f.setReadTimeout(1500);
            new org.springframework.web.client.RestTemplate(f)
                    .getForObject(driverConfig.baseUrl() + "/health", String.class);
        } catch (Exception e) {
            // 连不上 = 没在跑 = 正是我们要的
            return;
        }
        throw new IllegalStateException("驱动服务正在运行，无法训练。"
                + "预处理要吃满 GPU，和常驻服务抢显存会 OOM。"
                + "请先到服务器上停掉它：pkill -f 'python.*server\\.py'，训练完再跑 restart.sh 起回来");
    }

    /**
     * 执行命令并把输出实时收进任务日志。
     *
     * <p>合并 stderr 到 stdout —— Python 的进度条和报错都在 stderr，分开读容易漏。
     */
    private int exec(TrainTask task, List<String> cmd, long timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                task.log(line);
            }
        }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("执行超时（" + timeoutSec + " 秒），已强制结束。"
                    + "若是训练，多半卡在交互确认或显存不足");
        }
        return p.exitValue();
    }

    /**
     * 把动作视频解析成绝对路径。
     *
     * <p>页面上传的是 jeecg 相对路径，落在 {@code jeecg.path.upload} 下。
     * Java 和驱动服务同机，所以不用拷贝到 MuseTalk 目录，直接把这个绝对路径
     * 写进 realtime.yaml 就行，cv2 读得到。
     */
    private File resolveVideo(String stored) {
        if (stored == null || stored.trim().isEmpty()) {
            return null;
        }
        String s = stored.trim();
        // 多文件上传组件会用逗号分隔，取第一个
        int comma = s.indexOf(',');
        if (comma > 0) {
            s = s.substring(0, comma);
        }
        File f = new File(s);
        if (!f.isAbsolute()) {
            f = new File(upLoadPath, s);
        }
        return f.isFile() ? f : null;
    }

    /**
     * 删除形象缓存目录。
     *
     * <p>⚠ 这是本类唯一的删除操作，越权删除的后果很重，所以做两道锁：
     * 目录名必须通过 {@link SzrTrainConfig#SAFE_ID} 校验（挡住 {@code ../}），
     * 且真实路径必须落在 {@code <root>/results/} 之下（挡住软链接绕出去）。
     */
    private void deleteAvatarCache(File dir) throws IOException {
        Path target = dir.getCanonicalFile().toPath();
        Path allowed = new File(trainConfig.getMuseRoot(), "results").getCanonicalFile().toPath();
        if (!target.startsWith(allowed)) {
            throw new IllegalStateException("拒绝删除 " + target + "：不在 " + allowed + " 之下");
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(pp -> {
                try {
                    Files.delete(pp);
                } catch (IOException ignored) {
                    // 单个文件删不掉不致命，后面 cacheComplete 会兜住
                }
            });
        }
    }

    private static String yamlEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String brief(String s) {
        if (s == null) {
            return "未知错误";
        }
        return s.length() > 900 ? s.substring(0, 900) + "…" : s;
    }
}
