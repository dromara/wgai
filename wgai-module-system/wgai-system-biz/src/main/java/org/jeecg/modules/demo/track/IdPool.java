package org.jeecg.modules.demo.track;
import java.util.TreeSet;
/**
 * @author wggg
 * @date 2026/3/25 15:44
 * 可复用 ID 池
 * 始终分配当前最小可用 ID（1, 2, 3...）
 * 目标离开后 ID 回收，下一个进来的人复用
 */
public class IdPool {

    private final TreeSet<Integer> availableIds = new TreeSet<>();
    private int maxEverUsed = 0;

    /** 分配一个最小可用 ID */
    public synchronized int acquire() {
        if (!availableIds.isEmpty()) {
            // 优先复用已回收的最小 ID
            return availableIds.pollFirst();
        }
        // 没有可回收的，分配新 ID
        return ++maxEverUsed;
    }

    /** 目标离开，回收 ID */
    public synchronized void release(int id) {
        availableIds.add(id);
    }
}