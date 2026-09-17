package org.jeecg.modules.szr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.demo.szr.entity.TabSzrPython;
import org.jeecg.modules.demo.szr.service.ITabSzrPythonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 形象训练（MuseTalk avatar 预处理）相关的路径配置。
 *
 * <p>配置放在 <b>tab_szr_python</b> 表 {@code py_name = musetalk-train} 那一行，
 * 通过「数字人训练脚本」页面维护。
 *
 * <p>MuseTalk 根目录和版本号不单独配 —— 直接从驱动服务的 config.yaml 里读
 * {@code musetalk.root} / {@code musetalk.version}，避免两处配置不一致：
 * 训练往 A 目录写缓存、驱动服务去 B 目录找，是最难查的一类错。
 *
 * <p><b>本功能要求 Java 和驱动服务在同一台服务器上。</b>
 * Java 是直接读写本地文件、执行本地命令的，跨机完全不成立。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrTrainConfig {

    /** 训练脚本那一行的 py_name，页面上按这个名字建记录 */
    public static final String TRAIN_NAME = "musetalk-train";

    /**
     * 形象 ID 的合法字符。
     *
     * <p>⚠ 这个校验是<b>安全边界</b>，不是格式洁癖：
     * 形象 ID 会被拼进 yaml、拼进文件路径、参与目录删除。
     * 放开的话，{@code ../../} 能删到 MuseTalk 目录之外，
     * 引号和换行能改写 yaml 的其它键。
     * 只放行字母数字下划线连字符，从根上堵死。
     */
    public static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Autowired
    private ITabSzrPythonService tabSzrPythonService;

    /** 虚拟环境 activate 脚本 */
    @Getter
    private volatile String venv;
    /** 预处理配置 realtime.yaml，每次训练都会被整体覆盖 */
    @Getter
    private volatile String confPath;
    /** 驱动服务 config.yaml */
    @Getter
    private volatile String streamConfPath;
    /** 驱动服务 restart.sh */
    @Getter
    private volatile String restartPath;
    /** MuseTalk 根目录，从 streamConf 的 musetalk.root 读 */
    @Getter
    private volatile String museRoot;
    /** v15 / v1，从 streamConf 的 musetalk.version 读 */
    @Getter
    private volatile String version;
    /** UNet 结构定义，从 streamConf 的 musetalk.unet_config 读 */
    @Getter
    private volatile String unetConfig;
    /** UNet 权重，从 streamConf 的 musetalk.unet_model_path 读 */
    @Getter
    private volatile String unetModelPath;
    /** 帧率，从 streamConf 的 stream.fps 读。预处理必须和推流用同一个帧率 */
    @Getter
    private volatile int fps = 25;

    private volatile long cachedAt;

    private static final long CACHE_MS = 30_000L;

    /**
     * 确保配置可用，不可用直接抛异常并说清楚缺哪一项。
     *
     * <p>不做"缺配置就用默认值"的兜底 —— 训练要删目录、写文件、执行命令，
     * 路径猜错的后果比报错严重得多。
     */
    public synchronized void ensureReady() {
        if (museRoot != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            return;
        }
        QueryWrapper<TabSzrPython> w = new QueryWrapper<>();
        w.eq("py_name", TRAIN_NAME);
        List<TabSzrPython> list = tabSzrPythonService.list(w);
        if (list.isEmpty()) {
            throw new IllegalStateException("「数字人训练脚本」页面里没有 py_name=" + TRAIN_NAME
                    + " 的记录，请先新增一条并填好虚拟环境、预处理配置、驱动服务配置、重启脚本四个路径");
        }
        TabSzrPython row = list.get(0);
        venv = trim(row.getPyVenv());
        confPath = trim(row.getPyConf());
        streamConfPath = trim(row.getPyStreamConf());
        restartPath = trim(row.getPyRestart());

        requireFile(venv, "虚拟环境activate脚本");
        requireFile(streamConfPath, "驱动服务config.yaml");
        requireFile(restartPath, "驱动服务restart.sh");
        if (confPath == null) {
            throw new IllegalStateException("训练脚本配置里「预处理配置」没填");
        }
        // realtime.yaml 允许还不存在（第一次训练时由 Java 创建），但父目录必须在
        File confDir = new File(confPath).getParentFile();
        if (confDir == null || !confDir.isDirectory()) {
            throw new IllegalStateException("预处理配置的所在目录不存在: " + confPath);
        }

        loadFromStreamConf();
        cachedAt = System.currentTimeMillis();
    }

    /** 从驱动服务的 config.yaml 里取 MuseTalk 根目录和版本号 */
    @SuppressWarnings("unchecked")
    private void loadFromStreamConf() {
        try (InputStreamReader in = new InputStreamReader(
                new FileInputStream(streamConfPath), StandardCharsets.UTF_8)) {
            Map<String, Object> cfg = new Yaml().load(in);
            Map<String, Object> mt = cfg == null ? null : (Map<String, Object>) cfg.get("musetalk");
            if (mt == null) {
                throw new IllegalStateException("驱动服务配置里没有 musetalk 段: " + streamConfPath);
            }
            museRoot = trim(String.valueOf(mt.get("root")));
            version = trim(String.valueOf(mt.get("version")));
            unetConfig = trim(String.valueOf(mt.get("unet_config")));
            unetModelPath = trim(String.valueOf(mt.get("unet_model_path")));
            // 预处理的帧率必须和推流一致，否则生成的缓存帧数对不上，播放速度会不对
            Map<String, Object> stream = (Map<String, Object>) cfg.get("stream");
            if (stream != null && stream.get("fps") instanceof Number) {
                fps = ((Number) stream.get("fps")).intValue();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取驱动服务配置失败(" + streamConfPath + "): " + e.getMessage(), e);
        }
        if (museRoot == null || !new File(museRoot).isDirectory()) {
            throw new IllegalStateException("驱动服务配置里的 musetalk.root 不是有效目录: " + museRoot
                    + "（Java 和驱动服务必须在同一台服务器上，本功能才成立）");
        }
        if (version == null) {
            version = "v15";
        }
        if (unetConfig == null || unetModelPath == null) {
            throw new IllegalStateException("驱动服务配置里缺 musetalk.unet_config 或 unet_model_path");
        }
    }

    /** 某个形象的预处理缓存目录：{@code <root>/results/<version>/avatars/<id>} */
    public File avatarDir(String avatarId) {
        return new File(museRoot, "results/" + version + "/avatars/" + avatarId);
    }

    /** 缓存目录里已经有多少帧；目录不存在返回 -1 */
    public int frameCount(String avatarId) {
        File imgs = new File(avatarDir(avatarId), "full_imgs");
        if (!imgs.isDirectory()) {
            return -1;
        }
        String[] files = imgs.list();
        return files == null ? -1 : files.length;
    }

    /** 缓存是否完整（关键产物都在）。目录在但 latents.pt 缺失说明上次训练中途挂了。 */
    public boolean cacheComplete(String avatarId) {
        File dir = avatarDir(avatarId);
        return new File(dir, "latents.pt").isFile()
                && new File(dir, "coords.pkl").isFile()
                && frameCount(avatarId) > 0;
    }

    /** 改完配置想立刻生效时调用 */
    public void refresh() {
        cachedAt = 0L;
        museRoot = null;
    }

    private void requireFile(String path, String desc) {
        if (path == null) {
            throw new IllegalStateException("训练脚本配置里「" + desc + "」没填");
        }
        if (!new File(path).isFile()) {
            throw new IllegalStateException(desc + "不存在: " + path
                    + "（要填绝对路径，且 Java 必须和驱动服务在同一台服务器上）");
        }
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
