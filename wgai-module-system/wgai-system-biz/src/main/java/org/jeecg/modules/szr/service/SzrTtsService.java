package org.jeecg.modules.szr.service;

import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字人专用 TTS。
 *
 * <p><b>和 {@code audioTypeAll} 的关键区别：OfflineTts 实例被缓存复用。</b>
 * 原来每次合成都 {@code new OfflineTts(config)} 再 {@code release()}，
 * 而 VITS 模型上百 MB，每次都要重新读盘 + 重新初始化 onnxruntime，
 * 这部分固定开销通常 1~3 秒，远大于合成本身 —— 这才是"TTS 慢"的真正原因，
 * 不是模型选得不对。
 *
 * <p>男声女声也不需要换模型：aishell3 那 174 个 speaker 男女都有，
 * 换 {@code sid} 即可，同一个 OfflineTts 实例就能服务两种音色。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrTtsService {

    @Value("${jeecg.path.upload}")
    private String upLoadPath;

    /** key = 模型文件绝对路径，一个模型一份，长期驻留。 */
    private final Map<String, Handle> ttsCache = new ConcurrentHashMap<>();

    /**
     * 实例 + 它的配置。
     *
     * <p>config 一起留着是为了能读回 silenceScale / ruleFsts 等值打日志，
     * 排查"语速/节奏不对"时能直接看到实际生效的参数，不用猜。
     */
    private static class Handle {
        final OfflineTts tts;
        final OfflineTtsConfig config;

        Handle(OfflineTts tts, OfflineTtsConfig config) {
            this.tts = tts;
            this.config = config;
        }
    }

    /**
     * 合成一句话到 wav 文件。
     *
     * @param cfg      模型配置（模型/词典/规则等），来自 tab_audio_tts 表
     * @param text     要合成的文本
     * @param sid      说话人 id，决定音色（男/女）
     * @param outFile  输出 wav 路径
     * @return 音频时长（秒）
     */
    public double synthesize(TabAudioTts cfg, String text, int sid, File outFile) {
        if (!hasSpeakableContent(text)) {
            log.warn("[SzrTts] 文本无可发音内容，跳过合成（若不跳过会崩 JVM）: {}",
                    text == null ? "null" : "\"" + text + "\"");
            return 0d;
        }
        Handle handle = obtain(cfg);

        // VITS 内部按 lengthScale = 1/speed 换算，speed<=0 会直接除零，
        // 表现是语速完全不对而不是报错。新建记录时 audio_speed 常常是 0 而不是 1，
        // 所以这里必须兜住，不能只判 null。
        float speed = 1.0f;
        if (cfg.getAudioSpeed() != null) {
            float v = cfg.getAudioSpeed().floatValue();
            if (v > 0.01f && v < 10f) {
                speed = v;
            } else {
                log.warn("[SzrTts] audio_speed={} 不合理（应在 0.01~10），已按 1.0 处理。"
                        + "配置 id={}", v, cfg.getId());
            }
        }

        // sid 越界时 sherpa 只在 native 层打一行警告然后自己钳到 0，
        // Java 侧完全看不见。这里显式校验，免得"配了 sid 却没换音色"查不出原因。
        int speakers = handle.tts.getNumSpeakers();
        int useSid = sid;
        if (speakers > 0 && (sid < 0 || sid >= speakers)) {
            log.warn("[SzrTts] sid={} 超出范围（该模型只有 {} 个说话人，合法 0~{}），已按 0 处理",
                    sid, speakers, speakers - 1);
            useSid = 0;
        }

        // silenceScale 必须从 OfflineTtsConfig 显式复制进 GenerationConfig ——
        // 两者默认值不是一回事，漏了这行句间停顿会变，听感就是"节奏不对"。
        GenerationConfig genConfig = new GenerationConfig();
        genConfig.setSid(useSid);
        genConfig.setSpeed(speed);
        genConfig.setSilenceScale(handle.config.getSilenceScale());

        long t0 = System.currentTimeMillis();
        GeneratedAudio audio = handle.tts.generateWithConfigAndCallback(
                text, genConfig, (float[] samples) -> 1);
        double duration = audio.getSamples().length / (double) audio.getSampleRate();

        // 出了 0 长度音频说明 lexicon 一个词都没查到 —— 多半是 lexicon/tokens
        // 和模型不配套（上传时传错文件很常见）。这里必须喊出来，
        // 否则后面只会表现为"数字人不出声"，很难往这上面想。
        if (audio.getSamples().length == 0) {
            log.warn("[SzrTts] ❌ 合成结果为空！lexicon/tokens 很可能与模型不配套。"
                    + " model={} lexicon={} tokens={} 文本={}",
                    cfg.getAudioModel(), cfg.getAudioLexicon(), cfg.getAudioToken(), text);
            return 0d;
        }

        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("[SzrTts] 创建目录失败: {}", parent);
        }
        audio.save(outFile.getAbsolutePath());

        log.info("[SzrTts] 合成完成 sid={} 速度={} 静音={} 采样率={} 时长={}s 耗时={}ms 文本={}",
                useSid, speed, handle.config.getSilenceScale(), audio.getSampleRate(),
                String.format("%.2f", duration), System.currentTimeMillis() - t0, text);
        // 注意：不要 release()，实例是共享的
        return duration;
    }

    /**
     * 文本里有没有真正能发音的内容（中日韩文字 / 字母 / 数字）。
     *
     * <p>⚠ 必须在调 sherpa 之前拦住。纯标点或查不到词的文本会让 lexicon 产出
     * 零个音素，空张量喂进 Conv 层后 onnxruntime 在 C++ 层抛异常，
     * <b>直接崩掉整个 JVM</b>（EXCEPTION_UNCAUGHT_CXX_EXCEPTION），
     * 不是 Java 异常，catch 不住。报错特征：{@code Invalid input shape: {0}}
     */
    private static boolean hasSpeakableContent(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取（或首次创建）某个模型的 OfflineTts 实例。
     *
     * <p><b>不同模型的音素化路线不一样，配置不能一套硬套：</b>
     * <ul>
     *   <li>aishell3 这类中文 VITS —— 查 {@code lexicon.txt} 得音素，还要配 ruleFsts</li>
     *   <li><b>Piper（vits-piper-*）—— 靠 espeak-ng 规则合成音素，必须给
     *       {@code dataDir=espeak-ng-data}，且没有 lexicon</b></li>
     *   <li>vits-zh-hf-* 这类 —— 用 jieba 词典目录 {@code dictDir}</li>
     * </ul>
     *
     * <p>给 Piper 传 lexicon（而且路径多半根本不存在）会让音素化走偏，
     * 生成的音素序列长度不对，听感就是<b>语速不对</b> —— 不会报错，只会念得怪。
     * 所以这里按「哪些文件真实存在」来决定传什么，并把结果打进日志。
     */
    private Handle obtain(TabAudioTts cfg) {
        String model = upLoadPath + File.separator + cfg.getAudioModel();
        return ttsCache.computeIfAbsent(model, key -> {
            long t0 = System.currentTimeMillis();

            String tokens = resolve(cfg.getAudioToken());
            requireExists(tokens, "tokens");
            requireExists(key, "model");

            OfflineTtsVitsModelConfig.Builder vits = OfflineTtsVitsModelConfig.builder()
                    .setModel(key)
                    .setTokens(tokens);

            StringBuilder how = new StringBuilder();

            String lexicon = resolve(cfg.getAudioLexicon());
            if (isFile(lexicon)) {
                vits.setLexicon(lexicon);
                how.append("lexicon ");
            }

            // 同一个字段可能填的是 espeak-ng-data（Piper）或 jieba 词典目录，按内容判断
            String dir = resolve(cfg.getDictDir());
            if (isDir(dir)) {
                if (looksLikeEspeakData(dir)) {
                    vits.setDataDir(dir);
                    how.append("dataDir(espeak-ng) ");
                } else {
                    vits.setDictDir(dir);
                    how.append("dictDir(jieba) ");
                }
            }

            String ruleFsts = buildRuleFsts(cfg);
            if (!ruleFsts.isEmpty()) {
                how.append("ruleFsts ");
            }

            if (how.length() == 0) {
                log.warn("[SzrTts] {} 只配了 model+tokens，lexicon / dictDir / dataDir 一个都没命中。"
                        + "中文 VITS（含 sherpa 重封装的 vits-piper-zh_CN-*）需要 lexicon.txt；"
                        + "英文原版 Piper 需要 espeak-ng-data 目录。缺了音素化会走偏，"
                        + "不报错但念得不对。", key);
            }

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vits.build())
                    .setNumThreads(cfg.getThreadNum() == null ? 2 : cfg.getThreadNum())
                    .setDebug(false)
                    .build();

            OfflineTtsConfig ttsConfig = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setRuleFsts(ruleFsts)
                    .build();
            OfflineTts instance = new OfflineTts(ttsConfig);

            log.info("[SzrTts] 模型已加载并常驻: {} [{}] 采样率={} 说话人={} 静音={} ({}ms) —— 后续请求不再重复加载",
                    key, how.toString().trim(), instance.getSampleRate(), instance.getNumSpeakers(),
                    ttsConfig.getSilenceScale(), System.currentTimeMillis() - t0);
            return new Handle(instance, ttsConfig);
        });
    }

    private String resolve(String relative) {
        if (relative == null || relative.trim().isEmpty()) {
            return null;
        }
        return upLoadPath + File.separator + relative.trim();
    }

    private boolean isFile(String path) {
        return path != null && new File(path).isFile();
    }

    private boolean isDir(String path) {
        return path != null && new File(path).isDirectory();
    }

    /** espeak-ng-data 目录里必有 phontab / phonindex 这些数据文件，jieba 词典目录没有。 */
    private boolean looksLikeEspeakData(String dir) {
        File d = new File(dir);
        if (d.getName().toLowerCase().contains("espeak")) {
            return true;
        }
        return new File(d, "phontab").exists() || new File(d, "phonindex").exists();
    }

    private void requireExists(String path, String what) {
        if (!isFile(path)) {
            throw new IllegalArgumentException("TTS " + what + " 文件不存在: " + path);
        }
    }

    /**
     * 拼 ruleFsts，并且<b>只保留真实存在的文件</b>。
     *
     * <p>Piper 模型根本没有 fst，配置里若残留了 aishell3 的 phone.fst/number.fst 之类，
     * 传进去只会让规则替换出错。不存在的路径直接剔掉并告警，而不是原样塞给底层。
     */
    private String buildRuleFsts(TabAudioTts cfg) {
        if (cfg.getRuleFasts() == null || cfg.getRuleFasts().trim().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String one : cfg.getRuleFasts().split(",")) {
            if (one.trim().isEmpty()) {
                continue;
            }
            String path = upLoadPath + File.separator + one.trim();
            if (new File(path).isFile()) {
                sb.append(path).append(",");
            } else {
                log.warn("[SzrTts] ruleFst 不存在，已跳过: {}", path);
            }
        }
        return sb.length() == 0 ? "" : sb.substring(0, sb.length() - 1);
    }

    @PreDestroy
    public void destroy() {
        ttsCache.forEach((k, v) -> {
            try {
                v.tts.release();
            } catch (Exception e) {
                log.warn("[SzrTts] 释放模型失败 {}: {}", k, e.getMessage());
            }
        });
        ttsCache.clear();
    }
}
