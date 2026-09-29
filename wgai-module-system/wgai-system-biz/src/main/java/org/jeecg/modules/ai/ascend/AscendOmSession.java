package org.jeecg.modules.ai.ascend;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 昇腾 NPU 上一个 .om 模型在**某一个 device** 上的会话。
 *
 * 只负责"把 float[] 送进 NPU、把 float[] 取回来"这一步，
 * letterbox 预处理和 YOLO 输出解析全部留在调用方，和 ONNX 路径共用。
 *
 * ⚠ 两个约束（改动前务必看）：
 * 1. .om 的 batch 是编译期固定的（atc --input_shape="images:1,3,640,640" → batch=1），
 *    变长批量要在外面循环调用，不能一次塞 6 帧进来。
 * 2. aclmdlExecute 复用同一份 device 侧输入/输出缓冲，**同一个 session 不能并发调用**，
 *    所以 infer 用 ReentrantLock 串行化。单 session 单帧 7.71ms → 约 130 FPS 上限。
 *    要更高并发就在多个 device 上各放一份副本，见 {@link AscendOmModel}。
 *
 * @author wggg
 */
@Slf4j
public class AscendOmSession implements AutoCloseable {

    // ==================== JNI ====================
    // 对应 libascend_om.so 里的 Java_org_jeecg_modules_ai_ascend_AscendOmSession_*
    private static native int      nativeInit(int deviceId);
    private static native long     nativeLoad(String omPath, int deviceId);
    private static native float[][] nativeInfer(long handle, float[] input);
    private static native long[]   nativeOutShape(long handle, int index);
    private static native int      nativeNumOut(long handle);
    private static native void     nativeUnload(long handle);

    /** .so 是否加载成功；失败时调用方回退 ONNX */
    private static volatile boolean libLoaded = false;
    private static volatile String  unavailableReason = "尚未初始化";
    /** 已经 aclInit + 建好 context 的 device */
    private static final java.util.Set<Integer> readyDevices =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * 加载 .so 并为每个 device 初始化 ACL context。失败不抛异常，只返回空列表 ——
     * Windows 开发机上没有 libascend_om.so，必须能静默回退到 ONNX 路径。
     *
     * @return 实际初始化成功的 device 列表
     */
    public static synchronized java.util.List<Integer> tryInit(String libPath, int[] deviceIds) {
        java.util.List<Integer> ok = new java.util.ArrayList<>();

        if (!libLoaded) {
            try {
                System.load(libPath);
                libLoaded = true;
            } catch (Throwable t) {
                // UnsatisfiedLinkError / NoClassDefFoundError 都在这里兜住
                unavailableReason = t.getClass().getSimpleName() + ": " + t.getMessage();
                log.warn("[昇腾NPU] .so 加载失败，将回退 ONNX: {}", unavailableReason);
                return ok;
            }
        }

        for (int dev : deviceIds) {
            if (readyDevices.contains(dev)) {
                ok.add(dev);
                continue;
            }
            try {
                int rc = nativeInit(dev);
                if (rc != 0) {
                    log.warn("[昇腾NPU] device {} 初始化失败, nativeInit 返回 {}", dev, rc);
                    continue;
                }
                readyDevices.add(dev);
                ok.add(dev);
                log.info("[昇腾NPU] device {} 初始化成功", dev);
            } catch (Throwable t) {
                log.warn("[昇腾NPU] device {} 初始化异常: {}", dev, t.getMessage());
            }
        }

        if (ok.isEmpty() && libLoaded) {
            unavailableReason = "没有任何 device 初始化成功";
        }
        return ok;
    }

    public static boolean isNativeReady() {
        return libLoaded && !readyDevices.isEmpty();
    }

    public static String getUnavailableReason() {
        return unavailableReason;
    }

    // ==================== 实例 ====================

    private final String omPath;
    private final int    deviceId;
    private final long   handle;
    /** 每个输出张量的形状，直接从 om 描述里读，不硬编码 */
    private final long[][] outputShapes;
    /** ACL 不允许同一 session 并发 execute，这里串行化 */
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed = false;

    public AscendOmSession(String omPath, int deviceId) {
        if (!libLoaded) {
            throw new IllegalStateException("昇腾 NPU 未初始化: " + unavailableReason);
        }
        this.omPath = omPath;
        this.deviceId = deviceId;
        this.handle = nativeLoad(omPath, deviceId);
        if (handle == 0) {
            throw new IllegalStateException("om 模型加载失败: " + omPath + " @device" + deviceId);
        }

        int n = nativeNumOut(handle);
        this.outputShapes = new long[n][];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            outputShapes[i] = nativeOutShape(handle, i);
            sb.append(" out[").append(i).append("]=").append(java.util.Arrays.toString(outputShapes[i]));
        }
        log.info("[昇腾NPU] 模型加载成功 {} @device{} 输出数={}{}", omPath, deviceId, n, sb);
    }

    /**
     * 单帧推理。输入必须是 NCHW 展平的 float[]，长度要和 om 编译时的 batch 对上
     * （batch=1 的 640 模型 = 1*3*640*640 = 1228800）。
     *
     * @return 每个输出张量一个扁平 float[]
     */
    public float[][] infer(float[] input) {
        if (closed) {
            throw new IllegalStateException("session 已关闭: " + omPath);
        }
        lock.lock();
        try {
            return nativeInfer(handle, input);
        } finally {
            lock.unlock();
        }
    }

    /** 当前是否有线程正在这个 session 上推理，用于副本选择 */
    public boolean isBusy() {
        return lock.isLocked();
    }

    /** 第 index 个输出张量的形状，例如 yolo11 的 [1, 84, 8400] */
    public long[] outputShape(int index) {
        return outputShapes[index];
    }

    public int numOutputs() {
        return outputShapes.length;
    }

    public String getOmPath() {
        return omPath;
    }

    public int getDeviceId() {
        return deviceId;
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            nativeUnload(handle);
            log.info("[昇腾NPU] 模型已卸载 {} @device{}", omPath, deviceId);
        } catch (Throwable t) {
            log.warn("[昇腾NPU] 卸载失败 {} @device{}", omPath, deviceId, t);
        } finally {
            lock.unlock();
        }
    }
}
