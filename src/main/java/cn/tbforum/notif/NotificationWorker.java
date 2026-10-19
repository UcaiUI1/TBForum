package cn.tbforum.notif;

import cn.tbforum.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private final NotificationTaskStore store;
    private final NotificationService notifications;
    private final NotificationStream stream;
    private final NotificationProperties properties;
    private final MeterRegistry metrics;
    private final boolean redisEnabled;
    private final String consumer = UUID.randomUUID().toString();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean degraded = new AtomicBoolean();
    private ScheduledExecutorService executor;
    private final Map<String, AtomicLong> counts = new HashMap<>();
    private final AtomicLong oldestSeconds = new AtomicLong();

    public NotificationWorker(
            NotificationTaskStore store,
            NotificationService notifications,
            NotificationStream stream,
            NotificationProperties properties,
            AppProperties app,
            MeterRegistry metrics) {
        this.store = store;
        this.notifications = notifications;
        this.stream = stream;
        this.properties = properties;
        this.metrics = metrics;
        redisEnabled = app.getRedis().isEnabled();
        for (String state : new String[] {"READY", "QUEUED", "IN_FLIGHT", "DEAD"}) {
            AtomicLong value = new AtomicLong();
            counts.put(state, value);
            metrics.gauge(
                    "tbforum.notifications.tasks",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of("status", state)),
                    value);
        }
        metrics.gauge("tbforum.notifications.oldest.seconds", oldestSeconds);
    }

    @Override
    public void start() {
        if (!properties.isWorkersEnabled() || !running.compareAndSet(false, true)) return;
        executor = Executors.newScheduledThreadPool(3, task -> {
            Thread thread = new Thread(task, "tbforum-notifications");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(
                () -> guarded(this::relay), 0, properties.getPollInterval().toMillis(), TimeUnit.MILLISECONDS);
        if (redisEnabled && properties.isConsumerEnabled())
            executor.scheduleWithFixedDelay(
                    () -> guarded(this::consume),
                    0,
                    properties.getPollInterval().toMillis(),
                    TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(() -> guarded(this::sample), 0, 5, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(
                () -> guarded(() -> store.purgeCompleted(Instant.now())), 60, 60, TimeUnit.SECONDS);
    }

    private void guarded(Runnable task) {
        if (!running.get()) return;
        try {
            task.run();
        } catch (Exception e) {
            event("worker-error");
            if (degraded.compareAndSet(false, true))
                log.warn("Notification worker degraded: {}", e.getClass().getSimpleName());
        }
    }

    public void relay() {
        for (String id : store.due(Instant.now())) {
            if (Thread.currentThread().isInterrupted()) return;
            var claim = store.claimDue(id, Instant.now());
            if (claim == null) continue;
            event(claim.attempts() > 1 ? "retry" : "claimed");
            if (!redisEnabled) {
                deliver(claim);
                continue;
            }
            if (!store.queued(claim, Instant.now())) continue;
            try {
                stream.publish(id);
                event("enqueued");
            } catch (Exception e) {
                store.failed(claim, Instant.now(), e, true);
                event("publish-failed");
            }
        }
    }

    public void consume() {
        var records = stream.read(consumer);
        if (records != null)
            for (var record : records) {
                if (Thread.currentThread().isInterrupted()) return;
                consumeRecord(record);
            }
        if (Thread.currentThread().isInterrupted()) return;
        for (var record : stream.reclaim(consumer)) {
            if (Thread.currentThread().isInterrupted()) return;
            consumeRecord(record);
        }
    }

    private void consumeRecord(MapRecord<String, String, String> record) {
        String id = record.getValue().get("taskId");
        if (id != null && id.matches("[0-9a-f-]{36}")) {
            var claim = store.claimQueued(id, Instant.now());
            if (claim != null) deliver(claim);
            else event("duplicate-or-stale");
        } else event("invalid-record");
        // The database remains authoritative if a process dies before this acknowledgement.
        stream.acknowledge(record.getId());
    }

    private void deliver(NotificationTaskStore.Claim claim) {
        try {
            notifications.deliver(claim.notificationId());
            if (store.sent(claim, Instant.now())) event("dispatched");
        } catch (Exception e) {
            store.failed(claim, Instant.now(), e, false);
            event("dispatch-failed");
        }
    }

    private void sample() {
        counts.forEach((state, value) -> value.set(store.count(state)));
        oldestSeconds.set((long) store.oldestAge(Instant.now()));
    }

    private void event(String result) {
        metrics.counter("tbforum.notifications.events", "result", result).increment();
    }

    @Override
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
