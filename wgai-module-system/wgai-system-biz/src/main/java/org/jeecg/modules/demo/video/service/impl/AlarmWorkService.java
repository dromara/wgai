package org.jeecg.modules.demo.video.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Recording, notification and persistence must never run on the inference thread. */
@Slf4j
@Service
public class AlarmWorkService {
    @Value("${ai.subscription.alarm.workers:8}") private int workers = 8;
    @Value("${ai.subscription.alarm.queue-capacity:64}") private int queueCapacity = 64;
    private ThreadPoolExecutor executor;

    @PostConstruct
    public void init() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(Math.max(1, workers), Math.max(1, workers),
                0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), r -> {
            Thread thread = new Thread(r, "AlarmWorker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(String taskId, Runnable work) {
        long submitted = System.nanoTime();
        org.jeecg.modules.demo.video.util.RecognitionTrace trace = org.jeecg.modules.demo.video.util.RecognitionTrace.current();
        if (trace != null) log.info("[识别到报警提交] taskId={} frameId={} decodedToSubmitMs={}", taskId, trace.frameId, trace.elapsedMillis());
        try {
            executor.execute(() -> {
                String oldName = Thread.currentThread().getName();
                Thread.currentThread().setName(oldName + "-task=" + taskId);
                long start = System.currentTimeMillis();
                log.info("[报警队列开始] taskId={} frameId={} queueMs={} decodedToWorkerMs={}", taskId,
                        trace == null ? null : trace.frameId, (System.nanoTime() - submitted) / 1000000, trace == null ? null : trace.elapsedMillis());
                try {
                    work.run();
                } catch (Exception e) {
                    log.error("[报警处理失败] taskId={}", taskId, e);
                } finally {
                    log.info("[报警处理结束] taskId={} elapsedMs={} queued={}", taskId,
                            System.currentTimeMillis() - start, executor.getQueue().size());
                    Thread.currentThread().setName(oldName);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.error("[报警任务拒绝] taskId={} active={} queued={}，请检查录像耗时和报警频率",
                    taskId, executor.getActiveCount(), executor.getQueue().size());
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("[报警关闭超时] 未执行任务数={}", executor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
