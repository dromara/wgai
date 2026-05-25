package org.jeecg.modules.demo.audio;

import com.k2fsa.sherpa.onnx.*;

/**
 * @author wggg
 * @date 2024/10/18 10:56
 */
public class test {
    static String path="F:\\JAVAAI\\audio\\sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20\\";
    public static void main(String[] args) {
        // please refer to
        // https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-bilingual-chinese-english
        // to download model files
        String encoder =
                path+"encoder-epoch-99-avg-1.int8.onnx";
        String decoder =
                path+"decoder-epoch-99-avg-1.onnx";
        String joiner =
                path+ "joiner-epoch-99-avg-1.onnx";
        String tokens = path+"tokens.txt";

        String waveFilename =
                path+  "\\test_wavs\\1779413057666_wgaiaudio.wav";

        WaveReader reader = new WaveReader(waveFilename);

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
                        .build();

        OnlineRecognizer recognizer = new OnlineRecognizer(config);
        OnlineStream stream = recognizer.createStream();
        stream.acceptWaveform(reader.getSamples(), reader.getSampleRate());

        float[] tailPaddings = new float[(int) (0.8 * reader.getSampleRate())];
        stream.acceptWaveform(tailPaddings, reader.getSampleRate());

        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }

        String text = recognizer.getResult(stream).getText();

        System.out.printf("filename:%s\nresult:%s\n", waveFilename, text);

        stream.release();
        recognizer.release();
    }
}
