package org.jeecg.modules.demo.tab.util;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * 用 ffmpeg 将任意音频转成 单声道/16kHz/32位浮点 的裸 PCM,
 * 直接读成 sherpa 需要的 float[],不经过 WAV header 解析。
 * Windows / Linux 行为一致。
 */
public class FfmpegPcmLoader {

    private static final int TARGET_SAMPLE_RATE = 16000; // 按你的模型改

    public static class AudioData {
        public final float[] samples;
        public final int sampleRate;
        public AudioData(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    public static AudioData load(String inputPath) throws IOException, InterruptedException {
        // -f f32le: 裸 32 位浮点小端; pipe:1 输出到 stdout
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", inputPath,
                "-ac", "1",
                "-ar", String.valueOf(TARGET_SAMPLE_RATE),
                "-f", "f32le",
                "-acodec", "pcm_f32le",
                "pipe:1"
        );
        // 不要 redirectErrorStream(true),否则 stderr 的日志会污染 PCM 数据
        Process process = pb.start();

        // stdout = PCM 数据;另起线程把 stderr 抽干,防止缓冲区写满死锁
        ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream();
        Thread errDrain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // 需要排查时打开
                    // System.err.println(line);
                }
            } catch (IOException ignored) {}
        });
        errDrain.start();

        // 主线程读 stdout 的 PCM
        try (InputStream in = process.getInputStream()) {
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) {
                pcmBuf.write(tmp, 0, n);
            }
        }

        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        errDrain.join(2000);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg 超时");
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("ffmpeg 转换失败,exitCode=" + exitCode);
        }

        byte[] bytes = pcmBuf.toByteArray();
        // 关键:对齐到 4 字节,丢弃任何不完整的尾部字节,避免读出垃圾采样
        int sampleCount = bytes.length / 4;
        float[] samples = new float[sampleCount];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = bb.getFloat();
        }
        return new AudioData(samples, TARGET_SAMPLE_RATE);
    }
}