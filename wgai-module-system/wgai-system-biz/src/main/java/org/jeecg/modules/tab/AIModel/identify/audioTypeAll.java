package org.jeecg.modules.tab.AIModel.identify;

import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;

import javax.sound.sampled.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wggg
 * @date 2025/3/19 20:04
 */
@Slf4j
public class audioTypeAll {




    /**
     * 由 PCM 样本计算逐帧口型开合包络（与前端旧逻辑等价，挪到后端预生成）。
     * @param samples    GeneratedAudio.getSamples()，范围约 -1..1
     * @param sampleRate GeneratedAudio.getSampleRate()
     * @param frameStep  每帧时长（秒），建议 0.02（20ms）
     * @return 归一化后的开合度列表，元素 0..1
     */
    public static List<Double> computeMouthEnvelope(float[] samples, int sampleRate, double frameStep) {
        int stepSamples = Math.max(1, (int) (sampleRate * frameStep));
        List<Double> rmsList = new ArrayList<>();
        double maxRms = 0;

        for (int start = 0; start < samples.length; start += stepSamples) {
            int end = Math.min(samples.length, start + stepSamples);
            double sum = 0;
            int cnt = 0;
            for (int i = start; i < end; i++) {
                double s = samples[i];
                sum += s * s;
                cnt++;
            }
            double rms = Math.sqrt(sum / Math.max(1, cnt));
            rmsList.add(rms);
            if (rms > maxRms) maxRms = rms;
        }

        double noiseFloor = Math.max(maxRms * 0.02, 0.001);
        List<Double> out = new ArrayList<>(rmsList.size());
        for (double rms : rmsList) {
            double norm = Math.max(0, (rms - noiseFloor) / Math.max(0.0001, maxRms - noiseFloor));
            // 0.5 次幂提升小音量段的可见度，避免轻声时嘴几乎不动
            out.add(Math.round(Math.pow(norm, 0.5) * 1000.0) / 1000.0); // 保留 3 位小数，压缩传输体积
        }
        return out;
    }

    /**
     * 改造后的 TTS：保存 WAV 的同时返回口型包络等同步数据。
     * 返回 Map，供 Controller 组装进 Result.result。
     */
    public static Map<String, Object> textToTtsNotDictMap(String upload, TabAudioTts tabAudioTts) {
        long starTime = System.currentTimeMillis();
        String model = upload + File.separator + tabAudioTts.getAudioModel();
        String tokens = upload + File.separator + tabAudioTts.getAudioToken();
        String lexicon = upload + File.separator + tabAudioTts.getAudioLexicon();
        Integer sid = tabAudioTts.getAudioSid();
        Integer numThread = tabAudioTts.getThreadNum();

        String[] ruleFstsStr = tabAudioTts.getRuleFasts().split(",");
        StringBuilder rb = new StringBuilder();
        for (int i = 0; i < ruleFstsStr.length; i++) {
            rb.append(upload).append(File.separator).append(ruleFstsStr[i]).append(",");
        }
        String ruleFsts = rb.substring(0, rb.length() - 1);
        String text = tabAudioTts.getAudioText();

        OfflineTtsVitsModelConfig vitsModelConfig =
                OfflineTtsVitsModelConfig.builder()
                        .setModel(model)
                        .setTokens(tokens)
                        .setLexicon(lexicon)
                        .build();

        OfflineTtsModelConfig modelConfig =
                OfflineTtsModelConfig.builder()
                        .setVits(vitsModelConfig)
                        .setNumThreads(numThread)
                        .setDebug(true)
                        .build();

        OfflineTtsConfig config =
                OfflineTtsConfig.builder().setModel(modelConfig).setRuleFsts(ruleFsts).build();

        OfflineTts tts = new OfflineTts(config);
        float speed = 1.0f;
        GeneratedAudio audio = tts.generate(text, sid, speed);

        int sampleRate = audio.getSampleRate();
        float[] samples = audio.getSamples();
        double duration = samples.length / (double) sampleRate;
        double frameStep = 0.02;

        // 关键新增：用同一份 PCM 算口型包络
        List<Double> mouth = computeMouthEnvelope(samples, sampleRate, frameStep);

        // 建议：文件名带时间戳，避免前端/CDN 缓存到上一条音频
        String waveFilename = "TextToTts_" + starTime + ".wav";
        String savePath = upload + File.separator + waveFilename;
        audio.save(savePath);
        tts.release();

        Map<String, Object> ret = new HashMap<>();
        ret.put("fileName", waveFilename);
        ret.put("text", text);
        ret.put("sampleRate", sampleRate);
        ret.put("duration", duration);
        ret.put("frameStep", frameStep);
        ret.put("mouth", mouth); // 逐帧开合度数组，0..1
        return ret;
    }


    /***
     * 文本转语音NotDict
     */
    public static String  textToTtsNotDict(String upload,  TabAudioTts tabAudioTts){
        long starTime= System.currentTimeMillis();
        String model = upload+ File.separator+tabAudioTts.getAudioModel();
        String tokens = upload+ File.separator+tabAudioTts.getAudioToken();
        String lexicon = upload+ File.separator+tabAudioTts.getAudioLexicon();
        Integer sid= tabAudioTts.getAudioSid();
        Integer numThread= tabAudioTts.getThreadNum();
     //   String dictDir = "F:\\home\\vits-icefall-zh-aishell3\\vits-icefall-zh-aishell3\\dict";
        String [] ruleFstsStr=tabAudioTts.getRuleFasts().split(",");
        String ruleFsts = "";
        for (int i = 0; i <ruleFstsStr.length ; i++) {
            ruleFsts+= upload+ File.separator+ruleFstsStr[i]+",";
        }
        ruleFsts=ruleFsts.substring(0,ruleFsts.length()-1);
                //"F:\\home\\vits-icefall-zh-aishell3\\vits-icefall-zh-aishell3\\phone.fst,F:\\home\\vits-icefall-zh-aishell3\\vits-icefall-zh-aishell3\\date.fst,F:\\home\\vits-icefall-zh-aishell3\\vits-icefall-zh-aishell3\\number.fst";
        String text = tabAudioTts.getAudioText();

        OfflineTtsVitsModelConfig vitsModelConfig =
                OfflineTtsVitsModelConfig.builder()
                        .setModel(model)
                        .setTokens(tokens)
                        .setLexicon(lexicon)
                    //    .setDictDir(dictDir)

                        .build();

        OfflineTtsModelConfig modelConfig =
                OfflineTtsModelConfig.builder()
                        .setVits(vitsModelConfig)
                        .setNumThreads(numThread)
                        .setDebug(true)
                        .build();

        OfflineTtsConfig config =
                OfflineTtsConfig.builder().setModel(modelConfig).setRuleFsts(ruleFsts).build();

        OfflineTts tts = new OfflineTts(config);

       // int sid = 4;
        float speed = 1.0f;
        long start = System.currentTimeMillis();
        GeneratedAudio audio = tts.generate(text, sid, speed);
        long stop = System.currentTimeMillis();

        float timeElapsedSeconds = (stop - start) / 1000.0f;

        float audioDuration = audio.getSamples().length / (float) audio.getSampleRate();
        float real_time_factor = timeElapsedSeconds / audioDuration;

        String waveFilename = "TextToTts.wav";
        String savePath=upload+File.separator+waveFilename;

        audio.save(savePath);
        System.out.printf("-- elapsed : %.3f seconds\n", timeElapsedSeconds);
        System.out.printf("-- audio duration: %.3f seconds\n", timeElapsedSeconds);
        System.out.printf("-- real-time factor (RTF): %.3f\n", real_time_factor);
        System.out.printf("-- text: %s\n", text);
        System.out.printf("-- Saved to %s\n", waveFilename);
        System.out.printf("-- startTime to %s\n", starTime);

        tts.release();
        long endTime= System.currentTimeMillis();
        System.out.printf("-- endTime to %s\n", endTime);
        System.out.println((float) (starTime-endTime)/1000);
        return  waveFilename;
    }
    /***
     * 文本转语音NotDict
     */

    public static String   textToTtsDict(String upload, TabAudioTts tabAudioTts){

//        TabAudioDevice tabAudioDevice=new TabAudioDevice();
//        tabAudioDevice.setDeivceUrl("http://192.168.0.160:8307");
//        getToken(tabAudioDevice);


        // please visit
        // https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
        // to download model files
        String model = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C.onnx";
        String tokens = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\tokens.txt";
        String lexicon = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\lexicon.txt";
        String dictDir = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\dict";
        String ruleFsts = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\phone.fst,F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\date.fst,F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\number.fst";
        String text = "各位领导上午好，下面由我来给各位领导汇报省级储备粮智慧管理平台。\n" +
                "本平台建设以加强省级储备粮全面监管为目标，运用“数字孪生、物联网、边缘计算、七二零全景、三维建模、人工智能大模型”等技术，对库区业务数据智能化采集与分析，实现监管预警，展示“储备一张图、出入库一张图、监控一张图、粮情一张图、预警一张图”，通过一张图对全省30+省级粮库信息逐层穿透式下钻。打造了“一中心、一张图、两平台、N应用”的信息化综合监管与应用平台。\n" +
                "目前已接入省级储备库的仓房总数是623个，仓容总量221.7万吨，储备规模194.8万吨，其中小麦储备规模92万吨，稻谷储备规模97万吨，油脂储备规模3.8万吨，成品粮储备规模2万吨。现有储备总量151万吨，轮换占比68.8%。 ";

        OfflineTtsVitsModelConfig vitsModelConfig =
                OfflineTtsVitsModelConfig.builder()
                        .setModel(model)
                        .setTokens(tokens)
                        .setLexicon(lexicon)
                        .setDictDir(dictDir)
                        .build();

        OfflineTtsModelConfig modelConfig =
                OfflineTtsModelConfig.builder()
                        .setVits(vitsModelConfig)
                        .setNumThreads(10)
                        .setDebug(true)
                        .build();

        OfflineTtsConfig config =
                OfflineTtsConfig.builder().setModel(modelConfig).setRuleFsts(ruleFsts).build();

        OfflineTts tts = new OfflineTts(config);

        int sid = 100;
        float speed = 1.0f;
        long start = System.currentTimeMillis();
        GeneratedAudio audio = tts.generate(text, sid, speed);
        long stop = System.currentTimeMillis();

        float timeElapsedSeconds = (stop - start) / 1000.0f;

        float audioDuration = audio.getSamples().length / (float) audio.getSampleRate();
        float real_time_factor = timeElapsedSeconds / audioDuration;

        String waveFilename = "F:\\JAVAAI\\audio\\tts-vits-zh.wav";
        audio.save(waveFilename);
        System.out.printf("-- elapsed : %.3f seconds\n", timeElapsedSeconds);
        System.out.printf("-- audio duration: %.3f seconds\n", timeElapsedSeconds);
        System.out.printf("-- real-time factor (RTF): %.3f\n", real_time_factor);
        System.out.printf("-- text: %s\n", text);
        System.out.printf("-- Saved to %s\n", waveFilename);
        tts.release();
        return  waveFilename;
    }



    public static void main(String[] args) {
        // please refer to
        // https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-bilingual-chinese-english
        // to download model files
        String encoder =
                "F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/encoder-epoch-99-avg-1.int8.onnx";
        String decoder =
                "F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/decoder-epoch-99-avg-1.onnx";
        String joiner =
                "F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/joiner-epoch-99-avg-1.onnx";
        String tokens = "F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/tokens.txt";

        // https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/itn_zh_number.fst
        String ruleFsts = "F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\itn_zh_number.fst";

        int sampleRate = 16000;

        OnlineTransducerModelConfig transducer =
                OnlineTransducerModelConfig.builder()
                        .setEncoder(encoder)
                        .setDecoder(decoder)
                        .setJoiner(joiner)
                        .build();

        OnlineModelConfig modelConfig =
                OnlineModelConfig.builder()
                        .setTransducer(transducer)
                        .setTokens(tokens)
                        .setNumThreads(1)
                        .setDebug(true)
                        .build();

        OnlineRecognizerConfig config =
                OnlineRecognizerConfig.builder()
                        .setOnlineModelConfig(modelConfig)
                        .setDecodingMethod("greedy_search")
                        .setRuleFsts(ruleFsts)
                        .build();

        OnlineRecognizer recognizer = new OnlineRecognizer(config);
        OnlineStream stream = recognizer.createStream();

        // https://docs.oracle.com/javase/8/docs/api/javax/sound/sampled/AudioFormat.html
        // Linear PCM, 16000Hz, 16-bit, 1 channel, signed, little endian
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);

        // https://docs.oracle.com/javase/8/docs/api/javax/sound/sampled/DataLine.Info.html#Info-java.lang.Class-javax.sound.sampled.AudioFormat-int-
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine targetDataLine;
        try {

            targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
            targetDataLine.open(format);
            targetDataLine.start();
        } catch (LineUnavailableException e) {
            System.out.println("Failed to open target data line: " + e.getMessage());
            recognizer.release();
            stream.release();
            return;
        }

        String lastText = "";
        int segmentIndex = 0;

        // You can choose an arbitrary number
        int bufferSize = 1600; // 0.1 seconds for 16000Hz
        byte[] buffer = new byte[bufferSize * 2]; // a short has 2 bytes
        float[] samples = new float[bufferSize];

        System.out.println("Started! Please speak");
        while (targetDataLine.isOpen()) {
            int n = targetDataLine.read(buffer, 0, buffer.length);
            if (n <= 0) {
                System.out.printf("Got %d bytes. Expected %d bytes.\n", n, buffer.length);
                continue;
            }
            for (int i = 0; i != bufferSize; ++i) {
                short low = buffer[2 * i];
                short high = buffer[2 * i + 1];
                int s = (high << 8) + low;
                samples[i] = (float) s / 32768;
            }
            stream.acceptWaveform(samples, sampleRate);

            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }

            String text = recognizer.getResult(stream).getText();
            boolean isEndpoint = recognizer.isEndpoint(stream);
            if (!text.isEmpty() && text != " " && lastText != text) {
                lastText = text;
                System.out.printf("开始输出%d: %s\r", segmentIndex, text);
            }

            if (isEndpoint) {
                if (!text.isEmpty()) {
                    System.out.println();
                    segmentIndex += 1;
                }

                recognizer.reset(stream);
            }
        } // while (targetDataLine.isOpen())

        stream.release();
        recognizer.release();
    }



//    public static void main(String[] args) {
//        textToTtsDict("",null);
//    }
}
