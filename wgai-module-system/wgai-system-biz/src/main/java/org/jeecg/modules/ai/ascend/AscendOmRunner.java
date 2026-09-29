package org.jeecg.modules.ai.ascend;

import lombok.extern.slf4j.Slf4j;

/**
 * 把变长 batch 的推理请求，拆成对 batch=1 的 .om 的多次调用，
 * 再把结果拼回 ONNX Runtime 那侧一样的 float[B][d1][d2] 形状。
 *
 * 这样 BatchOnnxInference 里现成的 parseBatchOutput / parseBatchPoseOutput
 * 一行都不用改 —— 它们只认 float[][][] + long[] tensorShape。
 *
 * 为什么不用 om 的动态 batch：atc --dynamic_batch_size 要额外调
 * aclmdlSetDynamicBatchSize，且档位要预先枚举。而 NPU 单帧只 7.7ms，
 * 循环 6 次 ≈ 46ms，已经比 CPU 快一个数量级，先用简单方案。
 *
 * @author wggg
 */
@Slf4j
public final class AscendOmRunner {

    private AscendOmRunner() {
    }

    /** 推理结果：和 ONNX 侧 tensor.getValue() / getInfo().getShape() 对齐 */
    public static class BatchOutput {
        /** float[batchSize][d1][d2] */
        public final float[][][] rawOutput;
        /** [batchSize, d1, d2] */
        public final long[] tensorShape;

        BatchOutput(float[][][] rawOutput, long[] tensorShape) {
            this.rawOutput = rawOutput;
            this.tensorShape = tensorShape;
        }
    }

    /**
     * @param model          已加载的 om 模型（batch=1，可能有多 device 副本）
     * @param batchInputData 整批 NCHW 展平数据，长度 = batchSize * 3 * 640 * 640
     * @param batchSize      这一批实际帧数
     * @return 拼好的输出；形状不是 3 维等异常情况返回 null，调用方回退 ONNX
     */
    public static BatchOutput runBatch(AscendOmModel model, float[] batchInputData, int batchSize) {
        if (model == null || batchSize <= 0) {
            return null;
        }

        long[] omShape = model.outputShape(0);
        if (omShape == null || omShape.length != 3) {
            log.warn("[昇腾NPU] 输出不是 3 维 (实际 {} 维)，回退 ONNX: {}",
                    omShape == null ? "null" : omShape.length, model.getOmPath());
            return null;
        }
        int d1 = (int) omShape[1];
        int d2 = (int) omShape[2];

        int frameLen = batchInputData.length / batchSize;
        if (frameLen * batchSize != batchInputData.length) {
            log.warn("[昇腾NPU] 输入长度 {} 不能被 batchSize {} 整除，回退 ONNX",
                    batchInputData.length, batchSize);
            return null;
        }

        float[][][] out = new float[batchSize][][];
        float[] frame = new float[frameLen];

        try {
            for (int b = 0; b < batchSize; b++) {
                System.arraycopy(batchInputData, b * frameLen, frame, 0, frameLen);

                float[][] omOut = model.infer(frame);
                float[] flat = omOut[0];

                if (flat.length != d1 * d2) {
                    log.warn("[昇腾NPU] 输出长度 {} 与形状 [{},{}] 不符，回退 ONNX",
                            flat.length, d1, d2);
                    return null;
                }

                // 扁平 → [d1][d2]，和 ONNX 的 float[][][] 内存顺序一致
                float[][] plane = new float[d1][d2];
                for (int i = 0; i < d1; i++) {
                    System.arraycopy(flat, i * d2, plane[i], 0, d2);
                }
                out[b] = plane;
            }
        } catch (Throwable t) {
            log.error("[昇腾NPU] 推理异常，回退 ONNX: {}", model.getOmPath(), t);
            return null;
        }

        return new BatchOutput(out, new long[]{batchSize, d1, d2});
    }
}
