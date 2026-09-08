package org.jeecg.modules.szr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.szr.entity.TabSzrPython;
import org.jeecg.modules.demo.szr.service.ITabSzrPythonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 数字人驱动服务（Python 侧）的地址配置。
 *
 * <p>配置放在 <b>tab_szr_python</b> 表里，通过「数字人-Python脚本」页面维护，
 * 不写在 application.yml —— 换机器、切测试/生产环境时不用改配置文件重启。
 *
 * <p>约定：<code>py_name = {@value #DRIVER_NAME}</code> 的那一行，
 * <code>py_url</code> 就是驱动服务的根地址，例如 {@code http://192.168.100.82:8088}。
 * 没有这一行时回落到 {@link #FALLBACK_URL}。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrDriverConfig {

    /** 驱动服务那一行的 py_name，页面上按这个名字建记录 */
    public static final String DRIVER_NAME = "musetalk-driver";

    /** 表里查不到时的兜底地址 */
    public static final String FALLBACK_URL = "http://192.168.100.82:8088";

    /** 缓存时长：改了配置最多这么久生效，避免每句话都查库 */
    private static final long CACHE_MS = 30_000L;

    @Autowired
    private ITabSzrPythonService tabSzrPythonService;

    private volatile String cachedUrl;
    private volatile boolean cachedNeedUpload = true;
    private volatile long cachedAt;

    /** 驱动服务根地址，末尾不带斜杠。 */
    public String baseUrl() {
        ensureFresh();
        return cachedUrl;
    }

    /**
     * 音频要不要上传给驱动服务。
     *
     * <p>true  —— multipart 推 /speak_upload。Java 和 Python 不在同一台机器时必须这样。
     * <p>false —— 只给绝对路径调 /speak，省一次文件传输。
     *             <b>前提是两边同机或共享挂载</b>，否则驱动服务会报 file not found。
     * <p>取不到配置时默认 true：跨机是更保险的假设，猜错了顶多慢一点，
     *    反过来猜错则是直接播不出来。
     */
    public boolean needUpload() {
        ensureFresh();
        return cachedNeedUpload;
    }

    private void ensureFresh() {
        long now = System.currentTimeMillis();
        if (cachedUrl != null && now - cachedAt < CACHE_MS) {
            return;
        }
        queryFromDb();
        cachedAt = now;
    }

    /** 改完配置想立刻生效时调用。 */
    public void refresh() {
        cachedAt = 0L;
    }

    private void queryFromDb() {
        try {
            QueryWrapper<TabSzrPython> wrapper = new QueryWrapper<>();
            wrapper.eq("py_name", DRIVER_NAME);
            wrapper.orderByAsc("pysort");
            List<TabSzrPython> list = tabSzrPythonService.list(wrapper);
            for (TabSzrPython row : list) {
                String url = row.getPyUrl();
                if (url != null && !url.trim().isEmpty()) {
                    cachedUrl = normalize(url.trim());
                    // 库里没填时按 true 处理，理由见 needUpload() 注释
                    cachedNeedUpload = row.getNeedUpload() == null || row.getNeedUpload() != 0;
                    log.info("[szr] 驱动服务配置: {} 音频{}",
                            cachedUrl, cachedNeedUpload ? "上传" : "同机直读路径");
                    return;
                }
            }
            log.warn("[szr] tab_szr_python 里没有 py_name={} 且 py_url 非空的记录，" +
                    "回落到 {}。请在「数字人-Python脚本」页面新增一条。", DRIVER_NAME, FALLBACK_URL);
        } catch (Exception e) {
            log.warn("[szr] 读取驱动服务配置失败，回落到 {}: {}", FALLBACK_URL, e.getMessage());
        }
        cachedUrl = FALLBACK_URL;
        cachedNeedUpload = true;
    }

    private String normalize(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
