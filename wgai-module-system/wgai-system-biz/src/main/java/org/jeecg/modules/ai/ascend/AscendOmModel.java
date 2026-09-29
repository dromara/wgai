package org.jeecg.modules.ai.ascend;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个 .om 模型在**所有可用 device 上的副本集合**。
 *
 * 310P3 一张卡有两颗 chip（device 0 / 1），各自独立算力和显存。
 * 同一个模型在两颗上各加载一份，单模型吞吐从约 130 FPS 提到约 260 FPS。
 *
 * 副本选择：先扫一遍找空闲的；全忙则轮询挑一个排队等。
 * 这里的 isBusy 判断有竞态，但最坏结果只是多等一帧，不影响正确性。
 *
 * @author wggg
 */
@Slf4j
public class AscendOmModel implements AutoCloseable {

    private final String omPath;
    private final List<AscendOmSession> replicas;
    private final AtomicInteger rr = new AtomicInteger(0);

    /**
     * @param omPath    .om 文件
     * @param deviceIds 要在哪些 device 上各放一份副本
     * @throws IllegalStateException 一个副本都没加载成功时抛出
     */
    public AscendOmModel(String omPath, List<Integer> deviceIds) {
        this.omPath = omPath;
        List<AscendOmSession> list = new ArrayList<>(deviceIds.size());

        for (Integer dev : deviceIds) {
            try {
                list.add(new AscendOmSession(omPath, dev));
            } catch (Throwable t) {
                // 某个 device 失败不影响其它 device，降级到少副本继续跑
                log.warn("[昇腾NPU] {} 在 device{} 上加载失败，跳过该 device: {}",
                        omPath, dev, t.getMessage());
            }
        }

        if (list.isEmpty()) {
            throw new IllegalStateException("om 在所有 device 上都加载失败: " + omPath);
        }
        this.replicas = Collections.unmodifiableList(list);

        StringBuilder devs = new StringBuilder();
        for (AscendOmSession s : list) {
            devs.append(" device").append(s.getDeviceId());
        }
        log.info("[昇腾NPU] {} 就绪, 副本数={} 分布在{}", omPath, list.size(), devs);
    }

    /** 单帧推理，自动选一个空闲副本 */
    public float[][] infer(float[] input) {
        return pick().infer(input);
    }

    private AscendOmSession pick() {
        int n = replicas.size();
        if (n == 1) {
            return replicas.get(0);
        }
        // 先找空闲的
        for (int i = 0; i < n; i++) {
            AscendOmSession s = replicas.get(i);
            if (!s.isBusy()) {
                return s;
            }
        }
        // 全忙 → 轮询挑一个排队
        return replicas.get(Math.abs(rr.getAndIncrement() % n));
    }

    /** 输出形状取自任一副本，同一个 .om 各副本必然一致 */
    public long[] outputShape(int index) {
        return replicas.get(0).outputShape(index);
    }

    public int numOutputs() {
        return replicas.get(0).numOutputs();
    }

    public int replicaCount() {
        return replicas.size();
    }

    public String getOmPath() {
        return omPath;
    }

    @Override
    public void close() {
        replicas.forEach(AscendOmSession::close);
    }
}
