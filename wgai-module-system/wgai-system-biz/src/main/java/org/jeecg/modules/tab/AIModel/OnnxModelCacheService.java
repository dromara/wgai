package org.jeecg.modules.tab.AIModel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OnnxModelCacheService {

    private final Map<String, OnnxModelWrapper> cache = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();

    public OnnxModelWrapper getOnnxModel(TabAiModel tabAiModel) {
        String cacheKey = resolveCacheKey(tabAiModel);
        OnnxModelWrapper wrapper = cache.get(cacheKey);
        if (wrapper != null) {
            log.info("【已存在ONNX模型，直接返回】key: {}", cacheKey);
            return wrapper;
        }

        synchronized (cacheLock) {
            wrapper = cache.get(cacheKey);
            if (wrapper != null) {
                log.info("【已存在ONNX模型，直接返回】key: {}", cacheKey);
                return wrapper;
            }
            wrapper = loadAndWarmup(tabAiModel, cacheKey);
            cache.put(cacheKey, wrapper);
            return wrapper;
        }
    }

    private OnnxModelWrapper loadAndWarmup(TabAiModel tabAiModel, String cacheKey) {
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession session;
            boolean preferCuda = tabAiModel.getModelJmType() == null || tabAiModel.getModelJmType() == 1;
            if (preferCuda) {
                try {
                    session = env.createSession(tabAiModel.getAiWeights(), createCudaSessionOptions());
                    log.info("[ONNX推理规则：CUDA GPU]");
                } catch (Exception cudaEx) {
                    log.warn("[ONNX推理规则：CUDA创建Session失败，回退CPU] {}", cudaEx.getMessage());
                    session = env.createSession(tabAiModel.getAiWeights(), createCpuSessionOptions());
                    log.info("[ONNX推理规则：CPU]");
                }
            } else {
                session = env.createSession(tabAiModel.getAiWeights(), createCpuSessionOptions());
                log.info("[ONNX推理规则：CPU]");
            }
            warmupOnnxSession(env, session);
            log.info("【ONNX模型加载成功并缓存】key: {}, path: {}", cacheKey, tabAiModel.getAiWeights());
            return new OnnxModelWrapper(env, session);
        } catch (Exception ex) {
            log.error("【ONNX模型加载失败】key: {}, path: {}", cacheKey, tabAiModel.getAiWeights(), ex);
            throw new RuntimeException("ONNX模型加载失败", ex);
        }
    }

    private OrtSession.SessionOptions createCudaSessionOptions() throws Exception {
        OrtSession.SessionOptions options = createBaseSessionOptions();
        options.addCUDA();
        return options;
    }

    private OrtSession.SessionOptions createCpuSessionOptions() throws Exception {
        OrtSession.SessionOptions options = createBaseSessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setInterOpNumThreads(1);
        options.addCPU(true);
        return options;
    }

    private OrtSession.SessionOptions createBaseSessionOptions() throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        return options;
    }

    private void warmupOnnxSession(OrtEnvironment env, OrtSession session) {
        long[] shape = {1, 3, 640, 640};
        float[] warmupInput = new float[3 * 640 * 640];
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(warmupInput), shape);
             OrtSession.Result ignored = session.run(Collections.singletonMap(
                     session.getInputNames().iterator().next(), inputTensor))) {
            log.info("[ONNX模型预热完成]");
        } catch (Exception e) {
            log.warn("[ONNX模型预热失败，继续运行] {}", e.getMessage());
        }
    }

    private String resolveCacheKey(TabAiModel tabAiModel) {
        if (tabAiModel == null) {
            throw new IllegalArgumentException("TabAiModel不能为空");
        }
        if (StringUtils.isNotBlank(tabAiModel.getId())) {
            return tabAiModel.getId();
        }
        if (StringUtils.isNotBlank(tabAiModel.getAiWeights())) {
            return tabAiModel.getAiWeights();
        }
        throw new IllegalArgumentException("TabAiModel缺少id和aiWeights");
    }
}
