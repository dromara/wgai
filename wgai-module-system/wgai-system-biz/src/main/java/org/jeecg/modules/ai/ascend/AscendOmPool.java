package org.jeecg.modules.ai.ascend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 昇腾 .om 模型池。按 om 文件路径缓存 {@link AscendOmModel}，一个模型只加载一次，
 * 每个模型在所有配置的 device 上各放一份副本。
 *
 * 后端选择复用 tab_ai_model 已有的 model_jm_type 字段（字典 jm_type）：
 *   1 = GPU/CUDA 加速（现状）
 *   2 = NPU 加速   ← 走这里
 *   其它/null = CPU
 *
 * BatchOnnxInference 是静态工具类没法注入，所以这里暴露一个静态 INSTANCE，
 * 由 Spring 在 @PostConstruct 里回填。取不到就当 NPU 不可用，回退 ONNX。
 *
 * @author wggg
 */
@Slf4j
@Component
public class AscendOmPool {

    /**
     * tab_ai_subscription_new.model_jm_type（字典 model_type）= 100 表示权重是 .om，
     * 走国产 NPU。同一字段 20 = ONNX Runtime，其它 = OpenCV DNN。
     */
    public static final int MODEL_TYPE_OM = 100;

    private static volatile AscendOmPool INSTANCE;

    /** JNI 动态库路径；Windows 开发机上这个文件不存在，会静默回退 ONNX */
    @Value("${ai.ascend.lib-path:/home/data/libascend_om.so}")
    private String libPath;

    /**
     * NPU device 列表，逗号分隔。310P3 单卡两颗 chip 就配 "0,1"，
     * 每个模型会在每个 device 上各加载一份副本，吞吐翻倍。
     * 用 npu-smi info 看第二列 Device 那一栏确认实际编号。
     */
    @Value("${ai.ascend.device-ids:0}")
    private String deviceIdsRaw;

    /** .om 文件名后缀，atc_batch.sh 生成的是 xxx_310p3.om */
    @Value("${ai.ascend.om-suffix:_310p3}")
    private String omSuffix;

    /** 实际初始化成功的 device */
    private List<Integer> activeDevices = Collections.emptyList();

    private final Map<String, AscendOmModel> cache = new ConcurrentHashMap<>();
    /** 找不到 .om 的模型记一次日志就够了，别每帧刷屏 */
    private final Map<String, Boolean> missWarned = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        INSTANCE = this;

        int[] want = parseDeviceIds(deviceIdsRaw);
        activeDevices = AscendOmSession.tryInit(libPath, want);

        if (!activeDevices.isEmpty()) {
            log.info("[昇腾NPU] 模型池就绪, devices={}, omSuffix={} (om 与 onnx 同目录)",
                    activeDevices, omSuffix);
        } else {
            log.info("[昇腾NPU] 不可用({}), model_jm_type=2 的模型将回退 ONNX",
                    AscendOmSession.getUnavailableReason());
        }
    }

    private int[] parseDeviceIds(String raw) {
        List<Integer> ids = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Integer.parseInt(s));
                } catch (NumberFormatException e) {
                    log.warn("[昇腾NPU] device-ids 里的 \"{}\" 不是数字，已忽略", s);
                }
            }
        }
        if (ids.isEmpty()) {
            ids.add(0);
        }
        int[] arr = new int[ids.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = ids.get(i);
        }
        return arr;
    }

    public static AscendOmPool getInstance() {
        return INSTANCE;
    }

    /** NPU 这条路整体是否可用 */
    public static boolean isAvailable() {
        return INSTANCE != null && AscendOmSession.isNativeReady();
    }

    /**
     * 订阅是否配置成 om 模型。注意这里**只看配置、不看 NPU 是否可用** ——
     * 因为只上传 .om 时没有 ONNX 可回退，配了 om 却跑不了必须报错而不是静默降级。
     *
     * @param modelJmType tab_ai_subscription_new.model_jm_type
     */
    public static boolean isOmModel(Integer modelJmType) {
        return modelJmType != null && modelJmType == MODEL_TYPE_OM;
    }

    /**
     * 启动/预热阶段提前把 om 加载进来，让"文件不存在 / NPU 不可用"这类问题
     * 在订阅启动时就暴露，而不是等到第一帧推理。
     *
     * @throws IllegalStateException NPU 不可用或 om 加载失败
     */
    public static AscendOmModel preload(String weightPath) {
        AscendOmPool pool = INSTANCE;
        if (pool == null || !isAvailable()) {
            throw new IllegalStateException(
                    "模型配置为 om(model_type=100)，但昇腾 NPU 不可用: "
                            + AscendOmSession.getUnavailableReason()
                            + "。只上传了 .om 时没有 ONNX 可回退，请检查 "
                            + "ai.ascend.lib-path / device-ids 以及 npu-smi 状态。");
        }
        AscendOmModel m = pool.getModel(weightPath);
        if (m == null) {
            throw new IllegalStateException("om 模型加载失败: " + weightPath);
        }
        return m;
    }

    /**
     * 按 .onnx 权重路径取对应的 .om 模型（含各 device 上的副本）。
     * 拿不到（没转过、加载失败）返回 null，调用方回退 ONNX —— 不抛异常，
     * 现场少一个 .om 不能让整条识别链路挂掉。
     */
    public AscendOmModel getModel(String onnxWeightPath) {
        if (onnxWeightPath == null || onnxWeightPath.isEmpty() || activeDevices.isEmpty()) {
            return null;
        }
        String omPath = resolveOmPath(onnxWeightPath);

        AscendOmModel m = cache.get(omPath);
        if (m != null) {
            return m;
        }
        if (!new File(omPath).isFile()) {
            if (missWarned.putIfAbsent(omPath, Boolean.TRUE) == null) {
                log.warn("[昇腾NPU] 找不到 om 文件，回退 ONNX: {} (源模型 {})", omPath, onnxWeightPath);
            }
            return null;
        }

        // computeIfAbsent 保证同一个 om 只加载一次
        return cache.computeIfAbsent(omPath, p -> {
            try {
                return new AscendOmModel(p, activeDevices);
            } catch (Throwable t) {
                log.error("[昇腾NPU] om 加载失败，回退 ONNX: {}", p, t);
                return null;
            }
        });
    }

    /**
     * 目录一律跟着数据库的 ai_weights 走，不另外配。两种上传方式都支持：
     *
     * 1. 直接上传 .om（推荐，onnx2om.sh 转好就传）
     *    /xxx/upload/yolo11n.om   →  原样使用
     * 2. 上传 .onnx，.om 放在它旁边
     *    /xxx/upload/yolo11n.onnx →  /xxx/upload/yolo11n{om-suffix}.om
     */
    private String resolveOmPath(String weightPath) {
        // 库里存的本来就是 .om，直接用
        if (weightPath.toLowerCase().endsWith(".om")) {
            return weightPath;
        }

        File onnx = new File(weightPath);
        String base = onnx.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        File parent = onnx.getParentFile();
        String fileName = base + omSuffix + ".om";
        return parent == null ? fileName : new File(parent, fileName).getPath();
    }

    public String getStatistics() {
        StringBuilder sb = new StringBuilder("\n===== 昇腾 NPU 模型池 =====\n");
        sb.append("可用: ").append(isAvailable());
        if (!isAvailable()) {
            sb.append(" (").append(AscendOmSession.getUnavailableReason()).append(")");
        }
        sb.append("\ndevices: ").append(activeDevices);
        sb.append("\n已加载 om 数: ").append(cache.size()).append("\n");
        cache.forEach((p, m) -> sb.append("  ").append(p)
                .append(" 副本数=").append(m == null ? "加载失败" : m.replicaCount()).append("\n"));
        sb.append("==========================\n");
        return sb.toString();
    }

    @PreDestroy
    public void shutdown() {
        cache.values().forEach(m -> {
            if (m != null) {
                m.close();
            }
        });
        cache.clear();
        log.info("[昇腾NPU] 模型池已关闭");
    }
}
