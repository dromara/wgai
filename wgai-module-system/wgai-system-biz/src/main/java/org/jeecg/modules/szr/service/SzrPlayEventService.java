package org.jeecg.modules.szr.service;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.szr.websocket.WebSocketSzr;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放事件的统一处理入口。
 *
 * <p>事件有两条来路，都汇到这里，保证行为一致：
 * <ol>
 *   <li>{@link SzrEventSubscriber} —— Java 反向订阅 Python 的 SSE（推荐，只需开放 Python 端口）</li>
 *   <li>Python 主动 POST 回调 <code>/szr/speak/onPlayEvent</code>（需要 Java 侧也可达）</li>
 * </ol>
 *
 * <p>两条路同时开着会收到重复事件，靠 {@code seq}/{@code tag+event} 去重。
 *
 * @author wggg
 */
@Slf4j
@Service
public class SzrPlayEventService {

    /** tag -> 句子元信息，由 SzrSpeakController 在提交时登记 */
    private final Map<String, SentenceMeta> sentenceIndex = new ConcurrentHashMap<>();

    /** 已处理过的事件，用于两条来路同时开启时去重 */
    private final Map<String, Long> handled = new ConcurrentHashMap<>();

    /**
     * 被打断作废的句子 tag。
     *
     * <p>打断之后驱动服务还会为「已经在播的那一句」补一条 sentence_end 上来
     * （它切回静置画面就会认为这句播完了），那条事件必须丢掉：
     * 推给前端会让前端以为这句正常说完了，做错收尾动作。
     */
    private final Map<String, Long> cancelled = new ConcurrentHashMap<>();

    /** 去重记录的保留时长，超过就清掉，避免无限增长 */
    private static final long DEDUP_TTL_MS = 5 * 60_000L;

    public static class SentenceMeta {
        public String taskId;
        public int index;
        public int total;
        public String text;
    }

    public void register(String tag, String taskId, int index, int total, String text) {
        SentenceMeta meta = new SentenceMeta();
        meta.taskId = taskId;
        meta.index = index;
        meta.total = total;
        meta.text = text;
        sentenceIndex.put(tag, meta);
    }

    /**
     * 处理一条播放事件。
     *
     * @param event 事件名：sentence_start / sentence_end
     * @param tag   句子标识
     * @param seq   SSE 的序号；回调来路没有，传 null
     * @param from  来源标记，只用于日志
     */
    public void handle(String event, String tag, Long seq, String from) {
        if (event == null || tag == null) {
            return;
        }
        // 两条来路都开着时会收到同一件事两次，去重
        String key = (seq != null ? "seq:" + seq : event + "@" + tag);
        long now = System.currentTimeMillis();
        Long prev = handled.putIfAbsent(key, now);
        if (prev != null) {
            log.debug("[szr] 重复事件已忽略 {} {} (来自{})", event, tag, from);
            return;
        }
        cleanupDedup(now);

        if (cancelled.containsKey(tag)) {
            // 打断前就已经在播的那一句，驱动服务切回静置后仍会补一条 sentence_end。
            // 静默丢掉：前端已经收到 task_cancelled 了，再来一条 end 只会让它误判。
            log.info("[szr] 忽略已打断句子的事件 {} {} (来自{})", event, tag, from);
            return;
        }

        SentenceMeta meta = sentenceIndex.get(tag);
        if (meta == null) {
            // 常见于：Java 重启后内存索引丢了，但驱动服务还在播之前排队的音频；
            // 或 SSE 断线重连时用 ?since= 补发了更早的事件。
            //
            // ⚠ 这种事件不能推给前端 —— 推过去就是 index=-1 / text=""，
            //   前端拿它去匹配句子会错位，比收不到还糟。直接丢弃。
            log.warn("[szr] 丢弃未知 tag 的播放事件: {} {} (来自{})，"
                    + "多半是本服务重启前提交的任务，或 SSE 补发的历史事件", event, tag, from);
            return;
        }

        push(event, tag, meta.text, meta.index, meta.total);

        if ("sentence_end".equals(event)) {
            sentenceIndex.remove(tag);
            if (meta.index == meta.total - 1) {
                push("task_end", meta.taskId, "", meta.index, meta.total);
            }
        }
    }

    /**
     * 打断：作废所有在途句子，并给每个还没播完的任务推一条 {@code task_cancelled}。
     *
     * <p>必须推这条事件 —— {@code task_end} 只在最后一句播完时才发，
     * 被打断的任务永远等不到它，前端会一直卡在"播报中"。
     *
     * @return 被作废的 taskId 集合
     */
    public Set<String> cancelAll() {
        long now = System.currentTimeMillis();
        Set<String> taskIds = new LinkedHashSet<>();
        for (Map.Entry<String, SentenceMeta> e : sentenceIndex.entrySet()) {
            taskIds.add(e.getValue().taskId);
            cancelled.put(e.getKey(), now);
        }
        sentenceIndex.clear();
        for (String taskId : taskIds) {
            JSONObject msg = new JSONObject();
            msg.put("event", "task_cancelled");
            msg.put("tag", taskId);
            msg.put("text", "");
            msg.put("ts", now);
            WebSocketSzr.broadcast(msg.toJSONString());
            log.info("[szr] 推送前端 task_cancelled {}", taskId);
        }
        cleanupCancelled(now);
        return taskIds;
    }

    /** 合成失败等本地产生的事件，直接推前端 */
    public void push(String event, String tag, String text, int index, int total) {
        JSONObject msg = new JSONObject();
        msg.put("event", event);
        msg.put("tag", tag);
        msg.put("text", text);
        msg.put("index", index);
        msg.put("total", total);
        msg.put("ts", System.currentTimeMillis());
        WebSocketSzr.broadcast(msg.toJSONString());
        log.info("[szr] 推送前端 {} [{}/{}] {}", event, index + 1, total, text);
    }

    private void cleanupDedup(long now) {
        if (handled.size() < 500) {
            return;
        }
        handled.entrySet().removeIf(e -> now - e.getValue() > DEDUP_TTL_MS);
    }

    private void cleanupCancelled(long now) {
        cancelled.entrySet().removeIf(e -> now - e.getValue() > DEDUP_TTL_MS);
    }
}
