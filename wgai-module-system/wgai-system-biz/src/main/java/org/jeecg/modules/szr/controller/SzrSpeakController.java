package org.jeecg.modules.szr.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import org.jeecg.modules.demo.audio.service.ITabAudioTtsService;
import org.jeecg.modules.szr.service.SentenceSplitter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * 提交一段文本播报。
     *
     * <p>请求体：{@code {"text":"...", "ttsId":"<tab_audio_tts主键>", "sid":33, "split":true}}
     *
     * <p>{@code split} 默认 true（按标点切句，首句更快开口）；
     * 传 false 则整段当一句，原文一字不动播出去。
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

        String taskId = "szr-" + System.currentTimeMillis();
        log.info("[szr] 任务 {} {} {} 句, sid={}", taskId,
                doSplit ? "切出" : "不切句，共", sentences.size(), speakerId);

        // 订阅是按需启动的：没有播报任务时不连接，免得驱动服务没开就一直重连刷日志。
        // 必须在提交前拉起，否则前几句的播放事件会漏掉。
        eventSubscriber.touch();

        // 全部句子一次性排进队列，不要等一句播完再发下一句 ——
        // Python 侧生成比播放快，提前排队才能让画面连续、句间不回落到静置
        // ⚠ 用 execute 不用 submit：submit 会把异常塞进 Future，
        //   没人调 get() 就彻底消失，表现为"提交了但一条日志都没有"。
        //   再套一层 catch Throwable —— Error（NoSuchMethodError / UnsatisfiedLinkError
        //   / OutOfMemoryError）不是 Exception，下面 dispatch 里的 catch 抓不到。
        pool.execute(() -> {
            try {
                dispatch(taskId, sentences, cfg, speakerId);
            } catch (Throwable t) {
                log.warn("[szr] ❌ 任务 {} 整体失败: {}", taskId, t.toString(), t);
            }
        });

        Map<String, Object> ret = new HashMap<>();
        ret.put("taskId", taskId);
        ret.put("sentences", sentences);
        ret.put("count", sentences.size());
        return Result.OK(ret);
    }

    private void dispatch(String taskId, List<String> sentences, TabAudioTts cfg, int sid) {
        File dir = new File(upLoadPath, "szr_tts");
        log.info("[szr] 任务 {} 开始处理，输出目录 {}", taskId, dir.getAbsolutePath());

        for (int i = 0; i < sentences.size(); i++) {
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
                submitToDriver(wav, tag);
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
    private void submitToDriver(File wav, String tag) {
        if (driverConfig.needUpload()) {
            uploadToDriver(wav, tag);
        } else {
            pathToDriver(wav, tag);
        }
    }

    /**
     * multipart 上传。Java 和驱动服务不在同一台机器时必须走这条 ——
     * 传本地路径过去对方根本读不到。
     */
    private void uploadToDriver(File wav, String tag) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new FileSystemResource(wav));
        form.add("tag", tag);

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
    private void pathToDriver(File wav, String tag) {
        JSONObject body = new JSONObject();
        body.put("audio_path", wav.getAbsolutePath());
        body.put("tag", tag);

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
