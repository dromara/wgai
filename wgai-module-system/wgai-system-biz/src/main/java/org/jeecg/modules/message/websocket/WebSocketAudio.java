package org.jeecg.modules.message.websocket;

import com.alibaba.fastjson.JSONObject;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.base.BaseMap;
import org.jeecg.common.modules.redis.client.JeecgRedisClient;
import org.jeecg.common.util.SpringContextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时语音识别
 * 前端发送：16k / 16bit / 单声道 / little-endian PCM 二进制流
 */
@Component
@Slf4j
@ServerEndpoint("/WebSocketAudio/{userId}")
public class WebSocketAudio {

    /** 线程安全Map */
    private static final ConcurrentHashMap<String, Session> sessionPool = new ConcurrentHashMap<>();

    /** 每个用户一套识别上下文 */
    private static final ConcurrentHashMap<String, AudioSessionContext> audioContextPool = new ConcurrentHashMap<>();

    /** Redis触发监听名字 */
    public static final String REDIS_TOPIC_NAME = "socketHandler";

    @Resource
    private JeecgRedisClient jeecgRedisClient;

    /** 识别状态 */
    private enum RecognitionState {
        WAITING_KEYWORD,
        RECOGNIZING,
        ERROR
    }

    /** 每个连接的识别上下文 */
    private static class AudioSessionContext {
        KeywordSpotter kws;
        OnlineStream kwsStream;

        OnlineRecognizer recognizer;
        OnlineStream recognizerStream;

        RecognitionState state = RecognitionState.WAITING_KEYWORD;

        long lastSpeechTime = 0L;
        StringBuilder recognizedText = new StringBuilder();
        String lastText = "";
        int segmentIndex = 0;

        int sampleRate = 16000;
        int recognitionTimeoutMs = 10000;

        void release() {
            try {
                if (kwsStream != null) {
                    kwsStream.release();
                }
            } catch (Exception e) {
                log.error("释放 kwsStream 失败", e);
            }
            try {
                if (kws != null) {
                    kws.release();
                }
            } catch (Exception e) {
                log.error("释放 kws 失败", e);
            }
            try {
                if (recognizerStream != null) {
                    recognizerStream.release();
                }
            } catch (Exception e) {
                log.error("释放 recognizerStream 失败", e);
            }
            try {
                if (recognizer != null) {
                    recognizer.release();
                }
            } catch (Exception e) {
                log.error("释放 recognizer 失败", e);
            }
        }
    }


    // ==========【websocket接受、推送消息等方法】========================================================================================
    @Value("${audio.keyword-model.encoder}")
    String keywordEncoder;

    @Value("${audio.keyword-model.decoder}")
    String keywordDecoder ;

    @Value("${audio.keyword-model.joiner}")
    String keywordJoiner;

    @Value("${audio.keyword-model.tokens}")
    String keywordTokens ;

    @Value("${audio.keyword-model.keywords}")
    String keywordsFile ;


    // 语音识别模型
    @Value("${audio.recognition-model.encoder}")
    String recognitionEncoder;

    @Value("${audio.recognition-model.decoder}")
    String recognitionDecoder;

    @Value("${audio.recognition-model.joiner}")
    String recognitionJoiner;

    @Value("${audio.recognition-model.tokens}")
    String recognitionTokens;

    @Value("${audio.recognition-model.rule-fsts}")
    String ruleFsts;


    @OnOpen
    public void onOpen(Session session, @PathParam(value = "userId") String userId) {
        try {

            Environment env = SpringContextUtils.getBean(Environment.class);
            keywordEncoder=env.getProperty("audio.keyword-model.encoder");
            keywordDecoder=env.getProperty("audio.keyword-model.decoder");
            keywordJoiner=env.getProperty("audio.keyword-model.joiner");
            keywordTokens=env.getProperty("audio.keyword-model.tokens");
            keywordsFile=env.getProperty("audio.keyword-model.keywords");
            recognitionEncoder=env.getProperty("audio.recognition-model.encoder");
            recognitionDecoder=env.getProperty("audio.recognition-model.decoder");
            recognitionJoiner=env.getProperty("audio.recognition-model.joiner");
            recognitionTokens=env.getProperty("audio.recognition-model.tokens");
            ruleFsts=env.getProperty("audio.recognition-model.rule-fsts");
            sessionPool.put(userId, session);
            if(StringUtils.isNotEmpty(keywordEncoder)){
                File opencv=new File(keywordEncoder);
                if(opencv.exists()) {
                    initRecognizer(userId);

                    log.info("WebSocketAudio连接人：{}", userId);
                    log.info("【语音识别系统 WebSocketAudio】有新的连接，总数为:{}", sessionPool.size());

                    pushJson(userId, "connected", "连接成功", false);
                    pushState(userId, RecognitionState.WAITING_KEYWORD.name());
                }else{
                    pushJson(userId, "error", "初始化失败: 语音模型文件不存在", true);
                }
            }else{
                pushJson(userId, "error", "初始化失败: 语音模型文件不存在", true);
            }

        } catch (Exception e) {
            log.error("WebSocketAudio onOpen 初始化失败", e);
            pushJson(userId, "error", "初始化失败: " + e.getMessage(), true);
        }
    }

    @OnClose
    public void onClose(@PathParam("userId") String userId) {
        try {
            sessionPool.remove(userId);

            AudioSessionContext ctx = audioContextPool.remove(userId);
            if (ctx != null) {
                ctx.release();
            }

            log.info("【语音识别系统 WebSocketAudio】连接断开，总数为:{}", sessionPool.size());
        } catch (Exception e) {
            log.error("onClose 异常", e);
        }
    }

    /**
     * 文本消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam(value = "userId") String userId) {
        log.info("【语音识别系统 WebSocketAudio】收到客户端文本消息:{}", message);

        if (message == null) {
            return;
        }

        try {
            if ("HeartBeat".equalsIgnoreCase(message)) {
                return;
            }

            AudioSessionContext ctx = audioContextPool.get(userId);
            if (ctx == null) {
                return;
            }

            if ("end".equalsIgnoreCase(message) || "stop".equalsIgnoreCase(message)) {
                resetToWaitingKeyword(ctx, userId);
                return;
            }

            if ("reset".equalsIgnoreCase(message)) {
                resetToWaitingKeyword(ctx, userId);
                return;
            }
        } catch (Exception e) {
            log.error("处理文本消息异常", e);
            pushJson(userId, "error", "处理文本消息异常: " + e.getMessage(), true);
        }
    }

    /**
     * 二进制音频消息
     * 前端发送 PCM: 16k/16bit/mono/little-endian
     */
    @OnMessage
    public void onBinary(ByteBuffer audioBuffer, @PathParam(value = "userId") String userId) {
        AudioSessionContext ctx = audioContextPool.get(userId);
        if (ctx == null || audioBuffer == null || !audioBuffer.hasRemaining()) {
            return;
        }

        try {
            byte[] bytes = new byte[audioBuffer.remaining()];
            audioBuffer.get(bytes);

            // 必须是 short 对齐
            if (bytes.length < 2) {
                return;
            }

            int sampleCount = bytes.length / 2;
            float[] samples = new float[sampleCount];

            for (int i = 0; i < sampleCount; i++) {
                int low = bytes[2 * i] & 0xff;
                int high = bytes[2 * i + 1];
                short s = (short) ((high << 8) | low);
                samples[i] = s / 32768.0f;
            }

            switch (ctx.state) {
                case WAITING_KEYWORD:
                    processKeywordState(ctx, userId, samples);
                    break;
                case RECOGNIZING:
                    processRecognizingState(ctx, userId, samples);
                    break;
                case ERROR:
                    // 错误状态下不处理
                    break;
                default:
                    break;
            }

        } catch (Exception e) {
            log.error("处理音频流异常", e);
            if (ctx != null) {
                ctx.state = RecognitionState.ERROR;
            }
            pushState(userId, RecognitionState.ERROR.name());
            pushJson(userId, "error", "处理音频流异常: " + e.getMessage(), true);
        }
    }

    /**
     * 配置错误信息处理
     */
    @OnError
    public void onError(Session session, Throwable t) {
        log.warn("【语音识别系统 WebSocketAudio】消息出现错误");
        if (t != null) {
            log.error("WS异常", t);
        }
    }

    // ==========【识别逻辑】========================================================================================

    private void initRecognizer(String userId) {
        // 模型路径
//        String modelBase = "F:\\JAVAAI\\audio\\";
//
//        // 关键词模型
//        String keywordEncoder = modelBase + "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01\\encoder-epoch-12-avg-2-chunk-16-left-64.onnx";
//        String keywordDecoder = modelBase + "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01\\decoder-epoch-12-avg-2-chunk-16-left-64.onnx";
//        String keywordJoiner = modelBase + "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01\\joiner-epoch-12-avg-2-chunk-16-left-64.onnx";
//        String keywordTokens = modelBase + "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01\\tokens.txt";
//        String keywordsFile = modelBase + "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01\\keywords.txt";
//
//        // 语音识别模型
//        String recognitionEncoder = modelBase + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\encoder-epoch-99-avg-1.int8.onnx";
//        String recognitionDecoder = modelBase + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\decoder-epoch-99-avg-1.onnx";
//        String recognitionJoiner = modelBase + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\joiner-epoch-99-avg-1.onnx";
//        String recognitionTokens = modelBase + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\tokens.txt";
//        String ruleFsts = modelBase + "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\itn_zh_number.fst";

        int recognitionTimeoutMs = 5000;

        AudioSessionContext oldCtx = audioContextPool.remove(userId);
        if (oldCtx != null) {
            oldCtx.release();
        }

        AudioSessionContext ctx = new AudioSessionContext();
        ctx.recognitionTimeoutMs = recognitionTimeoutMs;

        // 1. 关键词识别器
        OnlineTransducerModelConfig kwTransducer = OnlineTransducerModelConfig.builder()
                .setEncoder(keywordEncoder)
                .setDecoder(keywordDecoder)
                .setJoiner(keywordJoiner)
                .build();

        OnlineModelConfig kwModelConfig = OnlineModelConfig.builder()
                .setTransducer(kwTransducer)
                .setTokens(keywordTokens)
                .setNumThreads(1)
                .setDebug(true)
                .build();

        KeywordSpotterConfig kwsConfig = KeywordSpotterConfig.builder()
                .setOnlineModelConfig(kwModelConfig)
                .setKeywordsFile(keywordsFile)
                .build();

        ctx.kws = new KeywordSpotter(kwsConfig);
        ctx.kwsStream = ctx.kws.createStream();

        // 2. 语音识别器
        OnlineTransducerModelConfig recTransducer = OnlineTransducerModelConfig.builder()
                .setEncoder(recognitionEncoder)
                .setDecoder(recognitionDecoder)
                .setJoiner(recognitionJoiner)
                .build();

        OnlineModelConfig recModelConfig = OnlineModelConfig.builder()
                .setTransducer(recTransducer)
                .setTokens(recognitionTokens)
                .setNumThreads(1)
                .setDebug(true)
                .build();

        OnlineRecognizerConfig recognizerConfig = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(recModelConfig)
                .setDecodingMethod("greedy_search")
                .setRuleFsts(ruleFsts)
                .build();

        ctx.recognizer = new OnlineRecognizer(recognizerConfig);
        ctx.recognizerStream = ctx.recognizer.createStream();
        ctx.state = RecognitionState.WAITING_KEYWORD;

        audioContextPool.put(userId, ctx);

        log.info("用户{} 关键词模型和语音识别模型初始化完成", userId);
    }

    private void processKeywordState(AudioSessionContext ctx, String userId, float[] samples) {
        ctx.kwsStream.acceptWaveform(samples, ctx.sampleRate);

        while (ctx.kws.isReady(ctx.kwsStream)) {
            ctx.kws.decode(ctx.kwsStream);
            String keyword = ctx.kws.getResult(ctx.kwsStream).getKeyword();

            if (keyword != null && !keyword.isEmpty()) {
                log.info("用户{} 检测到关键词: {}", userId, keyword);

                // 命中关键词后 reset kws
                ctx.kws.reset(ctx.kwsStream);

                // 切到识别模式
                ctx.state = RecognitionState.RECOGNIZING;
                ctx.lastSpeechTime = System.currentTimeMillis();
                ctx.recognizedText.setLength(0);
                ctx.lastText = "";
                ctx.segmentIndex = 0;
                ctx.recognizer.reset(ctx.recognizerStream);

                pushJson(userId, "keyword", keyword, true);
                pushState(userId, RecognitionState.RECOGNIZING.name());
                pushRecognitionStart(userId, ctx.recognitionTimeoutMs);

                log.info("用户{} 切换到语音识别模式，{}秒内请说话...", userId, ctx.recognitionTimeoutMs / 1000);
                return;
            }
        }
    }

    private void processRecognizingState(AudioSessionContext ctx, String userId, float[] samples) {
        ctx.recognizerStream.acceptWaveform(samples, ctx.sampleRate);

        while (ctx.recognizer.isReady(ctx.recognizerStream)) {
            ctx.recognizer.decode(ctx.recognizerStream);
        }

        String text = ctx.recognizer.getResult(ctx.recognizerStream).getText();
        boolean isEndpoint = ctx.recognizer.isEndpoint(ctx.recognizerStream);

        if (text != null && !text.isEmpty() && !" ".equals(text) && !ctx.lastText.equals(text)) {
            ctx.lastText = text;
            ctx.lastSpeechTime = System.currentTimeMillis();
            pushRecognizing(userId, text, false);
        }

        if (isEndpoint) {
            if (text != null && !text.isEmpty()) {
                log.info("用户{} 识别到完整句子: {}", userId, text);
                ctx.recognizedText.append(text).append(" ");
                ctx.segmentIndex++;
                ctx.lastSpeechTime = System.currentTimeMillis();
                pushRecognizing(userId, text, true);
            }
            ctx.recognizer.reset(ctx.recognizerStream);
        }

        long currentTime = System.currentTimeMillis();
        long elapsed = currentTime - ctx.lastSpeechTime;

        if (elapsed > ctx.recognitionTimeoutMs) {
            String finalResult = ctx.recognizedText.toString().trim();

            log.info("用户{} 语音识别超时（实际等待: {}ms），结果: {}", userId, elapsed, finalResult.isEmpty() ? "无" : finalResult);

            if (finalResult.isEmpty()) {
                pushTimeout(userId, "");
            } else {
                pushRecognitionComplete(userId, finalResult);
            }

            resetToWaitingKeyword(ctx, userId);
        }
    }

    private void resetToWaitingKeyword(AudioSessionContext ctx, String userId) {
        try {
            if (ctx.kws != null && ctx.kwsStream != null) {
                ctx.kws.reset(ctx.kwsStream);
            }
        } catch (Exception e) {
            log.error("reset kws 异常", e);
        }

        try {
            if (ctx.recognizer != null && ctx.recognizerStream != null) {
                ctx.recognizer.reset(ctx.recognizerStream);
            }
        } catch (Exception e) {
            log.error("reset recognizer 异常", e);
        }

        ctx.state = RecognitionState.WAITING_KEYWORD;
        ctx.lastSpeechTime = 0L;
        ctx.recognizedText.setLength(0);
        ctx.lastText = "";
        ctx.segmentIndex = 0;

        pushState(userId, RecognitionState.WAITING_KEYWORD.name());
        log.info("用户{} 回到关键词监听模式...", userId);
    }

    // ==========【推送辅助】========================================================================================

    private void pushState(String userId, String state) {
        JSONObject json = new JSONObject();
        json.put("type", "state");
        json.put("state", state);
        pushMessage(userId, json.toJSONString());
    }

    private void pushRecognitionStart(String userId, int timeoutMs) {
        JSONObject json = new JSONObject();
        json.put("type", "recognitionStart");
        json.put("timeoutMs", timeoutMs);
        pushMessage(userId, json.toJSONString());
    }

    private void pushRecognizing(String userId, String text, boolean isFinal) {
        JSONObject json = new JSONObject();
        json.put("type", "recognizing");
        json.put("text", text);
        json.put("final", isFinal);
        pushMessage(userId, json.toJSONString());
    }

    private void pushRecognitionComplete(String userId, String text) {
        JSONObject json = new JSONObject();
        json.put("type", "complete");
        json.put("text", text);
        pushMessage(userId, json.toJSONString());
    }

    private void pushTimeout(String userId, String text) {
        JSONObject json = new JSONObject();
        json.put("type", "timeout");
        json.put("text", text);
        pushMessage(userId, json.toJSONString());
    }

    private void pushJson(String userId, String type, String text, boolean finalFlag) {
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("text", text);
        json.put("final", finalFlag);
        pushMessage(userId, json.toJSONString());
    }

    /**
     * ws推送消息
     */
    public void pushMessage(String userId, String message) {
        for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
            // userId key值= {用户id + "_"+ 登录token的md5串}
            // TODO vue2未改key新规则，暂时不影响逻辑
            if (item.getKey().contains(userId)) {
                Session session = item.getValue();
                try {
                    synchronized (session) {
                        log.info("【语音识别系统 WebSocketAudio】推送单人消息:{}", message);
                        session.getBasicRemote().sendText(message);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    /**
     * ws遍历群发消息
     */
    public void pushMessage(String message) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                try {
                    synchronized (item) {
                        item.getValue().getAsyncRemote().sendText(message);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("【语音识别系统 WebSocketAudio】群发消息:{}", message);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * ws遍历群发消息
     */
    public void pushMessageByte(byte[] encodedData) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                Session session = item.getValue();
                try {
                    synchronized (session) {
                        session.getBasicRemote().sendBinary(ByteBuffer.wrap(encodedData));
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("【语音识别系统 WebSocketAudio】群发二进制消息");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void broadcastFrame(ByteBuffer buffer) {
        try {
            for (Map.Entry<String, Session> item : sessionPool.entrySet()) {
                Session session = item.getValue();
                try {
                    synchronized (session) {
                        session.getBasicRemote().sendBinary(buffer);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
            log.info("【语音识别系统 WebSocketAudio】群发帧消息");
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    // ==========【采用redis发布订阅模式——推送消息】========================================================================================

    /**
     * 后台发送消息到redis
     */
    public void sendMessage(String message) {
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", "");
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    /**
     * 此为单点消息 redis
     */
    public void sendMessage(String userId, String message) {
        BaseMap baseMap = new BaseMap();
        baseMap.put("userId", userId);
        baseMap.put("message", message);
        jeecgRedisClient.sendMessage(REDIS_TOPIC_NAME, baseMap);
    }

    /**
     * 此为单点消息(多人) redis
     */
    public void sendMessage(String[] userIds, String message) {
        for (String userId : userIds) {
            sendMessage(userId, message);
        }
    }
}