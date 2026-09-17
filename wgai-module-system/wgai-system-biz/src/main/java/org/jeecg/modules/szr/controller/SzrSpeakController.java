package org.jeecg.modules.szr.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import org.jeecg.modules.demo.audio.service.ITabAudioTtsService;
import org.jeecg.modules.demo.szr.entity.TabSzrDz;
import org.jeecg.modules.szr.service.SentenceSplitter;
import org.jeecg.modules.szr.service.SzrAvatarService;
import org.jeecg.modules.szr.service.SzrDriverConfig;
import org.jeecg.modules.szr.service.SzrEventSubscriber;
import org.jeecg.modules.szr.service.SzrPlayEventService;
import org.jeecg.modules.szr.service.SzrTtsService;
import org.jeecg.modules.szr.websocket.WebSocketSzr;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数字人播报编排。
 *
 * <p>整体链路：
 * <pre>
 * 前端 POST /szr/speak/text
 *   -> 切句
 *   -> 逐句 sherpa TTS 出 wav
 *   -> 逐句 POST 到 Python 驱动服务 /speak_upload（带 tag）
 *   -> Python 的 scheduler 真正播到那一句时回调 /szr/speak/onPlayEvent
 *   -> 本类通过 WebSocket 推给前端，前端据此调对应方法
 * </pre>
 *
 * <p>为什么播放进度必须由 Python 回调、不能在 Java 侧按时长估算：
 * 中间隔着生成排队、ffmpeg 编码、RTMP、ZLM 转封装、播放器缓冲，
 * 每一段都有不固定的延迟，估算必然越播越偏。
 *
 * @author wggg
 */
@Slf4j
@Api(tags = "数字人播报")
@RestController
@RequestMapping("/szr/speak")
public class SzrSpeakController {

    /** wav 临时目录 */
    @Value("${jeecg.path.upload}")
    private String upLoadPath;

    @Autowired
    private SzrTtsService szrTtsService;

    @Autowired
    private ITabAudioTtsService tabAudioTtsService;

    /** Python 驱动服务地址来自 tab_szr_python 表，不写死在配置文件里 */
    @Autowired
    private SzrDriverConfig driverConfig;

    /** 播放事件统一处理（SSE 订阅和 HTTP 回调两条来路都汇到这里） */
    @Autowired
    private SzrPlayEventService playEventService;

    @Autowired
    private SzrEventSubscriber eventSubscriber;

    /** 动作主键 -> 驱动形象 id 的翻译 */
    @Autowired
    private SzrAvatarService avatarService;

    /**
     * ⚠ 必须设超时。RestTemplate 默认无限等待，驱动服务地址填错或机器不可达时
     * 会把 szr-speak 这个单线程池整个卡住，后续所有播报请求排队不动，
     * 而且没有任何日志 —— 极难排查。
     */
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(30000);
        return new RestTemplate(f);
    }

    /** 切句提交是串行的耗时操作，放线程池里跑，别占住 HTTP 线程 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "szr-speak");
        t.setDaemon(true);
        return t;
    });

    /**
     * 打断用的版本号。每次打断 +1。
     *
     * <p>提交时记下当时的值，{@link #dispatch} 每合成一句前比一次，
     * 对不上就整个任务作废。比给每个任务留一个 Future 去 cancel 简单可靠 ——
     * {@code Future.cancel} 打断不了正卡在 TTS 合成里的线程，
     * 而且线程池里还没开始跑的任务也得一并作废。
     */
    private final AtomicLong speakEpoch = new AtomicLong();

    /**
     * 提交一段文本播报。
     *
     * <p>请求体：{@code {"text":"...", "ttsId":"<tab_audio_tts主键>", "sid":33,
     * "split":true, "dzId":"<tab_szr_dz主键>"}}
     *
     * <p>{@code split} 默认 true（按标点切句，首句更快开口）；
     * 传 false 则整段当一句，原文一字不动播出去。
     *
     * <p><b>用哪个动作说话</b>，按下面顺序取第一个能解析出形象的：
     * <ol>
     *   <li>{@code dzId} —— 动作主键，「数字人动作管理」里选</li>
     *   <li>{@code szrId} —— 数字人主键，用它配的默认说话动作</li>
     *   <li>{@code avatar} —— 直接给驱动形象 id，给不走本平台配置的第三方留的口子</li>
     *   <li>都没有 —— 驱动服务用自己的默认形象</li>
     * </ol>
     *
     * <p>⚠ 一次请求内所有句子用同一个动作。中途换动作是两段素材硬切，
     * 姿态会明显跳一下，别在一段播报里换。
     *
     * <p><b>{@code interrupt}</b>（默认 false）：传 true 则先把正在说的内容
     * 全部作废，再说这一段。人机问答里用户连问两句时用它 ——
     * 不传的话新文本会排在旧文本后面，得等旧的说完。
     */
    @ApiOperation(value = "提交播报文本", notes = "切句 -> TTS -> 推送数字人")
    @PostMapping("/text")
    public Result<Map<String, Object>> speakText(@RequestBody JSONObject body) {
        String text = body.getString("text");
        if (text == null || text.trim().isEmpty()) {
            return Result.error("文本不能为空");
        }
        String ttsId = body.getString("ttsId");
        Integer sid = body.getInteger("sid");
        int minChars = body.getIntValue("minChars") > 0 ? body.getIntValue("minChars") : 12;
        int maxChars = body.getIntValue("maxChars") > 0 ? body.getIntValue("maxChars") : 40;
        // split=false 时整段当一句处理，不做任何切分。
        // 适合调用方已经自己控制好粒度、或者要求原文一字不动播出去的场景。
        Boolean splitFlag = body.getBoolean("split");
        boolean doSplit = splitFlag == null || splitFlag;

        // 解析这次用哪个形象说话。解析不到就传 null，让驱动服务用它的默认形象 ——
        // 配置没配好不该表现成"点了没反应"。
        String avatar = avatarService.resolveByDzId(body.getString("dzId"));
        if (avatar == null) {
            avatar = avatarService.resolveDefault(body.getString("szrId"));
        }
        if (avatar == null) {
            avatar = body.getString("avatar");
        }

        TabAudioTts cfg = ttsId == null ? null : tabAudioTtsService.getById(ttsId);
        if (cfg == null) {
            return Result.error("找不到 TTS 配置，ttsId=" + ttsId);
        }
        int speakerId = sid != null ? sid : (cfg.getAudioSid() == null ? 0 : cfg.getAudioSid());

        List<String> sentences;
        if (doSplit) {
            sentences = SentenceSplitter.split(text, minChars, maxChars);
        } else {
            // 不切句：整段作为一句。仍要去掉首尾空白，
            // 纯标点/纯空白的文本送进 TTS 会产出零音素并崩掉合成进程。
            sentences = new ArrayList<>();
            String whole = text.trim();
            if (!whole.isEmpty()) {
                sentences.add(whole);
            }
        }
        if (sentences.isEmpty()) {
            return Result.error(doSplit ? "切句结果为空" : "文本为空");
        }

        // 打断必须在排队之前做：先作废在途内容，本次任务才拿得到干净的 epoch。
        // 顺序反了的话本次任务会被自己的打断作废掉。
        Map<String, Object> interruptInfo = null;
        if (Boolean.TRUE.equals(body.getBoolean("interrupt"))) {
            interruptInfo = interruptNow();
        }
        final long myEpoch = speakEpoch.get();

        String taskId = "szr-" + System.currentTimeMillis();
        log.info("[szr] 任务 {} {} {} 句, sid={}, 形象={}, epoch={}", taskId,
                doSplit ? "切出" : "不切句，共", sentences.size(), speakerId,
                avatar == null ? "驱动服务默认" : avatar, myEpoch);

        // 订阅是按需启动的：没有播报任务时不连接，免得驱动服务没开就一直重连刷日志。
        // 必须在提交前拉起，否则前几句的播放事件会漏掉。
        eventSubscriber.touch();

        // 全部句子一次性排进队列，不要等一句播完再发下一句 ——
        // Python 侧生成比播放快，提前排队才能让画面连续、句间不回落到静置
        // ⚠ 用 execute 不用 submit：submit 会把异常塞进 Future，
        //   没人调 get() 就彻底消失，表现为"提交了但一条日志都没有"。
        //   再套一层 catch Throwable —— Error（NoSuchMethodError / UnsatisfiedLinkError
        //   / OutOfMemoryError）不是 Exception，下面 dispatch 里的 catch 抓不到。
        final String useAvatar = avatar;
        pool.execute(() -> {
            try {
                dispatch(taskId, sentences, cfg, speakerId, useAvatar, myEpoch);
            } catch (Throwable t) {
                log.warn("[szr] ❌ 任务 {} 整体失败: {}", taskId, t.toString(), t);
            }
        });

        Map<String, Object> ret = new HashMap<>();
        ret.put("taskId", taskId);
        ret.put("sentences", sentences);
        ret.put("count", sentences.size());
        ret.put("avatar", avatar);
        if (interruptInfo != null) {
            ret.put("interrupted", interruptInfo);
        }
        return Result.OK(ret);
    }

    /**
     * 立刻作废所有在途的播报内容。
     *
     * <p>要清的东西分散在三处，少清一处都会表现成"打断了还在说"：
     * <ol>
     *   <li>Java 线程池里排队/正在跑的任务 —— 靠 {@link #speakEpoch} 自查作废</li>
     *   <li>驱动服务里排队等 GPU 的段、正在生成的段、已生成待播的帧 ——
     *       调它的 {@code POST /interrupt}</li>
     *   <li>前端的"播报中"状态 —— 推 {@code task_cancelled}</li>
     * </ol>
     *
     * <p>⚠ 做不到零延迟静音：已经进了 ffmpeg 的帧、RTMP/ZLM 在途数据、
     * 播放器自身缓冲加起来通常 1~2 秒，这段声音追不回来。这是链路的物理下限。
     */
    private Map<String, Object> interruptNow() {
        long ep = speakEpoch.incrementAndGet();
        Set<String> cancelledTasks = playEventService.cancelAll();

        Map<String, Object> info = new HashMap<>();
        info.put("epoch", ep);
        info.put("cancelledTasks", cancelledTasks);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String json = restTemplate.postForObject(driverConfig.baseUrl() + "/interrupt",
                    new HttpEntity<>("{}", headers), String.class);
            JSONObject r = JSONObject.parseObject(json);
            info.put("driverDroppedFrames", r == null ? null : r.get("dropped_frames"));
            log.info("[szr] 打断 epoch={}，作废任务 {}，驱动服务丢弃待播 {} 帧",
                    ep, cancelledTasks, r == null ? "?" : r.get("dropped_frames"));
        } catch (Exception e) {
            // 老版本驱动服务没有 /interrupt，会 404。此时 Java 侧仍然停发后续句子，
            // 但已经提交过去的那几句还是会播完 —— 必须让调用方看得见这个降级。
            info.put("driverError", e.getMessage());
            log.warn("[szr] 打断驱动服务失败({}/interrupt): {}。"
                    + "若是 404，说明驱动服务是旧版，需要更新 server.py 并重启，"
                    + "否则已提交的句子仍会播完", driverConfig.baseUrl(), e.getMessage());
        }
        return info;
    }

    private void dispatch(String taskId, List<String> sentences, TabAudioTts cfg,
                          int sid, String avatar, long myEpoch) {
        File dir = new File(upLoadPath, "szr_tts");
        log.info("[szr] 任务 {} 开始处理，输出目录 {}", taskId, dir.getAbsolutePath());

        for (int i = 0; i < sentences.size(); i++) {
            // 每句开头查一次。TTS 合成一句要一两秒，长文本切成十几句时
            // 绝大部分时间都耗在这个循环里，不在这儿拦就等于没打断。
            if (myEpoch != speakEpoch.get()) {
                log.info("[szr] 任务 {} 被打断，剩余 {} 句不再合成",
                        taskId, sentences.size() - i);
                return;
            }
            String sentence = sentences.get(i);
            String tag = taskId + "-" + i;
            // ⚠ 必须抓 Throwable 不能只抓 Exception：
            //   换 sherpa jar 后常见的 NoSuchMethodError / UnsatisfiedLinkError 都是 Error，
            //   只抓 Exception 的话会静默穿透，连一行日志都没有。
            try {
                File wav = new File(dir, tag + ".wav");

                log.info("[szr] {} ① 开始合成 ({}/{}) {}", tag, i + 1, sentences.size(), sentence);
                double sec = szrTtsService.synthesize(cfg, sentence, sid, wav);
                if (sec <= 0 || !wav.isFile() || wav.length() == 0) {
                    throw new IllegalStateException("合成未产出有效音频，wav="
                            + wav.getAbsolutePath() + " exists=" + wav.isFile()
                            + " size=" + (wav.isFile() ? wav.length() : -1));
                }
                log.info("[szr] {} ② 合成完毕 {}s，文件 {} bytes", tag,
                        String.format("%.2f", sec), wav.length());

                playEventService.register(tag, taskId, i, sentences.size(), sentence);

                log.info("[szr] {} ③ 提交驱动服务 {} ({})", tag, driverConfig.baseUrl(),
                        driverConfig.needUpload() ? "上传文件" : "同机路径");
                submitToDriver(wav, tag, avatar);
                log.info("[szr] {} ✅ 已提交 ({}/{})", tag, i + 1, sentences.size());
            } catch (Throwable t) {
                log.warn("[szr] {} ❌ 第 {} 句失败 [{}]: {}",
                        tag, i + 1, t.getClass().getSimpleName(), t.getMessage(), t);
                playEventService.push("sentence_error", tag, sentence, i, sentences.size());
            }
        }
        log.info("[szr] 任务 {} 处理结束", taskId);
    }

    /**
     * 把一段音频交给驱动服务，走哪条路由「数字人-Python脚本」里的
     * <b>是否上传音频</b> 决定。
     */
    private void submitToDriver(File wav, String tag, String avatar) {
        if (driverConfig.needUpload()) {
            uploadToDriver(wav, tag, avatar);
        } else {
            pathToDriver(wav, tag, avatar);
        }
    }

    /**
     * multipart 上传。Java 和驱动服务不在同一台机器时必须走这条 ——
     * 传本地路径过去对方根本读不到。
     */
    private void uploadToDriver(File wav, String tag, String avatar) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(wav));
        form.add("tag", tag);
        if (avatar != null && !avatar.isEmpty()) {
            form.add("avatar", avatar);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                driverConfig.baseUrl() + "/speak_upload",
                new HttpEntity<>(form, headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("上传失败，驱动服务返回 " + resp.getStatusCode());
        }
    }

    /**
     * 只把绝对路径给驱动服务，省掉一次文件传输。
     *
     * <p>⚠ 仅当两边在同一台服务器（或共享同一个挂载点）时可用。
     * 路径对不上驱动服务会直接 400 file not found，这里把提示写清楚，
     * 免得又去查网络和推流。
     */
    private void pathToDriver(File wav, String tag, String avatar) {
        JSONObject body = new JSONObject();
        body.put("audio_path", wav.getAbsolutePath());
        body.put("tag", tag);
        if (avatar != null && !avatar.isEmpty()) {
            body.put("avatar", avatar);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    driverConfig.baseUrl() + "/speak",
                    new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("驱动服务返回 " + resp.getStatusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("以路径方式提交失败（" + wav.getAbsolutePath()
                    + "）。若 Java 与驱动服务不在同一台机器，请在「数字人-Python脚本」里把"
                    + "「是否上传音频」改成【是】。原始错误：" + e.getMessage(), e);
        }
    }

    /**
     * Python 驱动服务的播放事件回调。
     *
     * <p>请求体：{@code {"event":"sentence_start|sentence_end","tag":"...","ts":1757...}}
     */
    @PostMapping("/onPlayEvent")
    public Map<String, Object> onPlayEvent(@RequestBody JSONObject body) {
        playEventService.handle(body.getString("event"), body.getString("tag"),
                body.getLong("seq"), "回调");
        Map<String, Object> ok = new HashMap<>();
        ok.put("ok", true);
        return ok;
    }


    /**
     * 立刻闭嘴，不说新内容。
     *
     * <p>和 {@code /text} 带 {@code interrupt:true} 是同一套作废逻辑，区别只是
     * 这个不接着说新的 —— 用户点「停止」、或识别到用户开口说话时用它。
     *
     * <p>⚠ 打断后声音还会再响 1~2 秒（编码 + 推流 + 播放器缓冲，追不回来）。
     * 没有正在播的内容时调它也不会报错，返回的 cancelledTasks 是空的。
     */
    @ApiOperation(value = "打断播报", notes = "作废在途内容，画面回到待机")
    @PostMapping("/interrupt")
    public Result<Map<String, Object>> interrupt() {
        return Result.OK(interruptNow());
    }

    /** 只切句不合成，调参用 */
    @PostMapping("/preview")
    public Result<List<String>> preview(@RequestBody JSONObject body) {
        String text = body.getString("text");
        int minChars = body.getIntValue("minChars") > 0 ? body.getIntValue("minChars") : 12;
        int maxChars = body.getIntValue("maxChars") > 0 ? body.getIntValue("maxChars") : 40;
        return Result.OK(SentenceSplitter.split(text, minChars, maxChars));
    }

    /**
     * 手动启停播放事件订阅。
     *
     * <p>正常不用调 —— 提交播报时会自动拉起，空闲 10 分钟自动断开。
     * 手动 start 之后不受空闲自动停约束，适合长时间盯事件调试。
     */
    @PostMapping("/subscriber/{action}")
    public Result<String> subscriber(@PathVariable("action") String action) {
        if ("start".equals(action)) {
            eventSubscriber.start(true);
            return Result.OK("订阅已手动启动（不会因空闲自动断开）");
        }
        if ("stop".equals(action)) {
            eventSubscriber.stop();
            return Result.OK("订阅已停止");
        }
        return Result.error("action 只支持 start / stop");
    }

    /**
     * 某个数字人可用于说话的动作列表，前端下拉框用。
     *
     * <p>每条会带一个 {@code loaded} 标记：这个动作的驱动形象在驱动服务上
     * <b>是不是真的加载了</b>。
     *
     * <p>⚠ 这个标记是本功能最容易踩的坑的照妖镜：
     * 库里配了 avatar_id、但驱动服务上没做过预处理、或没写进 config.yaml 的
     * {@code extra_avatars}，播报时驱动服务会<b>静默退回默认形象</b>，
     * 现象就是"选了左介绍但画面没变"，很容易误判成前端没传参。
     * loaded=false 的动作在页面上应当标成不可用。
     *
     * <p>驱动服务连不上时 loaded 一律给 null（未知），不要当成 false。
     */
    @GetMapping("/actions")
    public Result<List<Map<String, Object>>> actions(
            @RequestParam(value = "szrId", required = false) String szrId) {
        Set<String> loaded = fetchDriverAvatars();

        List<Map<String, Object>> ret = new ArrayList<>();
        for (TabSzrDz dz : avatarService.listSpeakActions(szrId)) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", dz.getId());
            row.put("szrId", dz.getSzrId());
            row.put("szrName", dz.getSzrName());
            row.put("title", dz.getSzrTitle());
            row.put("avatarId", dz.getAvatarId());
            row.put("isDefault", dz.getIsDefault());
            String avatarId = dz.getAvatarId();
            if (loaded == null || avatarId == null || avatarId.trim().isEmpty()) {
                // 驱动服务不可达，或压根没填形象 id —— 都不算"确认没加载"
                row.put("loaded", loaded == null ? null : Boolean.FALSE);
            } else {
                row.put("loaded", loaded.contains(avatarId.trim()));
            }
            ret.add(row);
        }
        return Result.OK(ret);
    }

    /**
     * 驱动服务上实际加载了哪些形象。
     *
     * @return 形象 id 集合；驱动服务不可达时返回 null（"未知"，区别于"一个都没有"）
     */
    private Set<String> fetchDriverAvatars() {
        try {
            String json = restTemplate.getForObject(
                    driverConfig.baseUrl() + "/avatars", String.class);
            JSONObject obj = JSONObject.parseObject(json);
            Set<String> ids = new HashSet<>();
            JSONArray items = obj.getJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    String id = items.getJSONObject(i).getString("id");
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("[szr] 取驱动服务形象列表失败({}): {}", driverConfig.baseUrl(), e.getMessage());
            return null;
        }
    }

    /** 直接透传驱动服务的形象清单，核对配置是否生效用 */
    @GetMapping("/avatars")
    public Result<Object> avatars() {
        try {
            String json = restTemplate.getForObject(
                    driverConfig.baseUrl() + "/avatars", String.class);
            return Result.OK(JSONObject.parse(json));
        } catch (Exception e) {
            return Result.error("驱动服务不可达(" + driverConfig.baseUrl() + "): " + e.getMessage());
        }
    }

    /** 转发驱动服务健康状态，顺带看 WS 在线数 */
    @GetMapping("/health")
    public Result<Object> health() {
        try {
            String driver = restTemplate.getForObject(driverConfig.baseUrl() + "/health", String.class);
            Map<String, Object> ret = new HashMap<>();
            ret.put("driver", JSONObject.parse(driver));
            ret.put("wsOnline", WebSocketSzr.onlineCount());
            ret.put("driverBaseUrl", driverConfig.baseUrl());
            ret.put("audioNeedUpload", driverConfig.needUpload());
            ret.put("sseRunning", eventSubscriber.isRunning());
            ret.put("sseConnected", eventSubscriber.isConnected());
            ret.put("sseLastSeq", eventSubscriber.getLastSeq());
            return Result.OK(ret);
        } catch (Exception e) {
            return Result.error("驱动服务不可达(" + driverConfig.baseUrl() + "): " + e.getMessage());
        }
    }

    /** 数字人当前占用情况（GPU 同时只允许一个 WebSocket 客户端）。 */
    @GetMapping("/connection/list")
    public Result<Map<String, Object>> connectionList() {
        Map<String, Object> ret = new HashMap<>();
        ret.put("total", WebSocketSzr.onlineCount());
        ret.put("records", WebSocketSzr.onlineConnections());
        return Result.OK(ret);
    }

    /** 管理员强制移除指定用户的数字人连接。 */
    @DeleteMapping("/connection/{userId}")
    public Result<String> disconnect(@PathVariable("userId") String userId) {
        if (WebSocketSzr.disconnect(userId)) {
            return Result.OK("已移除用户数字人连接");
        }
        return Result.error("连接不存在或已断开，请刷新后重试");
    }
}
