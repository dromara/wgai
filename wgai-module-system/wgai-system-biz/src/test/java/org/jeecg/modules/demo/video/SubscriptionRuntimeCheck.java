package org.jeecg.modules.demo.video;

import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabAiWarning;
import org.jeecg.modules.demo.video.service.impl.AlarmWorkService;
import org.jeecg.modules.demo.video.service.impl.TabAiSubscriptionNewServiceImpl;
import org.jeecg.modules.demo.video.service.impl.TabAiWarningServiceImpl;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.demo.video.util.onnx.VideoReadPicOnnxNew;
import org.jeecg.modules.tab.entity.pushEntity;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.lang.reflect.*;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone checks; no camera, model, database or Redis server is needed. */
public class SubscriptionRuntimeCheck {
    public static void main(String[] args) throws Exception {
        checkSchedule();
        checkAlarmPersistence();
        checkAlarmQueue();
        checkRecovery();
        checkLifecycle();
        System.out.println("PASS: schedule, alarm mapping, async queue saturation, task completion, duplicate start, startup recovery, shutdown intent");
    }

    private static void checkSchedule() {
        check(VideoReadPicOnnxNew.isWithinSchedule(LocalTime.of(23, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)), "overnight evening");
        check(VideoReadPicOnnxNew.isWithinSchedule(LocalTime.of(2, 0), LocalTime.of(22, 0), LocalTime.of(6, 0)), "overnight morning");
        check(!VideoReadPicOnnxNew.isWithinSchedule(LocalTime.NOON, LocalTime.of(22, 0), LocalTime.of(6, 0)), "outside overnight");
        check(!VideoReadPicOnnxNew.isWithinSchedule(LocalTime.of(18, 0), LocalTime.of(8, 0), LocalTime.of(18, 0)), "end exclusive");
        check(VideoReadPicOnnxNew.isWithinSchedule(LocalTime.NOON, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT), "whole day");
    }

    private static void checkAlarmPersistence() {
        AtomicReference<TabAiWarning> saved = new AtomicReference<>();
        TabAiWarningServiceImpl service = new TabAiWarningServiceImpl() {
            @Override public boolean save(TabAiWarning row) { saved.set(row); return true; }
        };
        pushEntity push = new pushEntity();
        push.setCameraName("camera-1"); push.setModelName("helmet"); push.setModelId("model-1");
        push.setModelText("missing"); push.setModelArea("zone-A");
        push.setAlarmPicData("alarm.jpg"); push.setVideo("alarm.mp4");
        service.savePush(push);
        check("alarm.jpg".equals(saved.get().getWarningPic()), "image path persisted");
        check("alarm.mp4".equals(saved.get().getWarningCome()), "video path persisted");
        check("zone-A".equals(saved.get().getRemake()), "area preserved");
        check(saved.get().getWarningTime() != null, "alarm timestamp");
    }

    private static void checkAlarmQueue() throws Exception {
        AlarmWorkService service = new AlarmWorkService();
        set(service, AlarmWorkService.class, "workers", 1);
        set(service, AlarmWorkService.class, "queueCapacity", 1);
        service.init();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), queued = new CountDownLatch(1);
        String caller = Thread.currentThread().getName();
        AtomicReference<String> worker = new AtomicReference<>();
        try {
            check(service.submit("one", () -> {
                worker.set(Thread.currentThread().getName()); entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }), "first alarm accepted");
            check(entered.await(3, TimeUnit.SECONDS), "worker started");
            check(service.submit("two", queued::countDown), "second alarm queued");
            check(!service.submit("three", () -> { throw new AssertionError("must not run inline"); }), "full queue rejected");
            check(!caller.equals(worker.get()), "off caller thread");
            release.countDown();
            check(queued.await(3, TimeUnit.SECONDS), "queued alarm drained");
        } finally { release.countDown(); service.shutdown(); }
    }

    private static void checkRecovery() throws Exception {
        List<TabAiSubscriptionNew> tasks = new ArrayList<>();
        for (String id : Arrays.asList("disabled", "failed", "resumed")) {
            TabAiSubscriptionNew task = new TabAiSubscriptionNew();
            task.setId(id); task.setName(id); task.setRunState("disabled".equals(id) ? 0 : 1); tasks.add(task);
        }
        List<String> attempts = new CopyOnWriteArrayList<>();
        CountDownLatch restored = new CountDownLatch(1);
        TabAiSubscriptionNewServiceImpl service = new TabAiSubscriptionNewServiceImpl() {
            @Override public List<TabAiSubscriptionNew> list(com.baomidou.mybatisplus.core.conditions.Wrapper<TabAiSubscriptionNew> query) { return tasks; }
            @Override public TabAiSubscriptionNew getById(java.io.Serializable id) {
                return tasks.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
            }
            @Override public void startAi(TabAiSubscriptionNew task) {
                attempts.add(task.getId());
                if ("failed".equals(task.getId())) throw new IllegalStateException("simulated load failure");
                restored.countDown();
            }
        };
        try {
            service.resumeSubscriptions();
            check(restored.await(3, TimeUnit.SECONDS), "recovery continues after one failure");
            check(attempts.equals(Arrays.asList("failed", "resumed")), "only enabled subscriptions restored");
        } finally {
            Field executor = TabAiSubscriptionNewServiceImpl.class.getDeclaredField("recoveryExecutor");
            executor.setAccessible(true); ((ExecutorService) executor.get(service)).shutdownNow();
        }
    }

    private static void checkLifecycle() throws Exception {
        AtomicReference<Integer> state = new AtomicReference<>(1);
        CountDownLatch stopped = new CountDownLatch(1);
        TabAiSubscriptionNewServiceImpl service = new TabAiSubscriptionNewServiceImpl() {
            @Override public boolean updateById(TabAiSubscriptionNew task) {
                state.set(task.getRunState()); stopped.countDown(); return true;
            }
        };
        ValueOperations values = (ValueOperations) Proxy.newProxyInstance(ValueOperations.class.getClassLoader(),
                new Class[]{ValueOperations.class}, (proxy, method, args) -> null);
        RedisTemplate redis = new RedisTemplate() { @Override public ValueOperations opsForValue() { return values; } };
        set(service, TabAiSubscriptionNewServiceImpl.class, "redisTemplate", redis);
        Method submit = TabAiSubscriptionNewServiceImpl.class.getDeclaredMethod("submitGenericTask", TabAiSubscriptionNew.class, Runnable.class);
        submit.setAccessible(true);
        TabAiSubscriptionNew task = new TabAiSubscriptionNew(); task.setId("test-task"); task.setName("test-camera");
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        submit.invoke(service, task, (Runnable) () -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        check(entered.await(3, TimeUnit.SECONDS), "task started");
        service.startAi(task); // Must return without querying a database or starting another reader.
        finish.countDown();
        check(stopped.await(3, TimeUnit.SECONDS), "completion clears state");
        check(state.get() == 0, "run state cleared");
        check(Boolean.FALSE.equals(RedisCacheHolder.get("test-tasknewRunPush")), "local flag cleared");
        state.set(1);
        set(service, TabAiSubscriptionNewServiceImpl.class, "shuttingDown", true);
        submit.invoke(service, task, (Runnable) () -> {});
        service.shutdown(); // Waits for completion, preserving persisted restart intent.
        check(state.get() == 1, "shutdown preserves restart intent");
    }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
