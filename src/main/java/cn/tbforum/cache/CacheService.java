package cn.tbforum.cache;

import cn.tbforum.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis 缓存与发布封装：显式启用，连接失败时短暂熔断并自动探测恢复。 */
@Service
public class CacheService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(CacheService.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meterRegistry;
    private final boolean configured;
    private final long failureCooldownNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong disabledUntil = new AtomicLong();
    private final AtomicBoolean healthy = new AtomicBoolean();
    private final AtomicBoolean degraded = new AtomicBoolean();

    @Autowired
    public CacheService(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<MeterRegistry> registryProvider,
            AppProperties properties) {
        this(redisProvider.getIfAvailable(), registryProvider.getIfAvailable(), properties, System::nanoTime);
    }

    CacheService(StringRedisTemplate redis, MeterRegistry registry, AppProperties properties, LongSupplier nanoTime) {
        this.redis = redis;
        this.meterRegistry = registry;
        this.configured = properties.getRedis().isEnabled() && redis != null;
        Duration cooldown = properties.getRedis().getFailureCooldown();
        this.failureCooldownNanos =
                Math.max(1L, cooldown == null ? Duration.ofSeconds(30).toNanos() : cooldown.toNanos());
        this.nanoTime = nanoTime;
        if (registry != null) {
            registry.gauge("tbforum.redis.available", healthy, value -> value.get() ? 1.0 : 0.0);
        }
    }

    /** 仅在最近一次 Redis 操作成功且当前不处于冷却期时返回 true。 */
    public boolean isAvailable() {
        return configured && healthy.get() && nanoTime.getAsLong() >= disabledUntil.get();
    }

    public String get(String key) {
        if (!canAttempt()) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(key);
            markSuccess();
            recordHitMiss(value != null);
            return value;
        } catch (Exception e) {
            markFailure("get", e);
            return null;
        }
    }

    public void set(String key, String value, Duration ttl) {
        if (!canAttempt()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
            markSuccess();
        } catch (Exception e) {
            markFailure("set", e);
        }
    }

    public void evict(String key) {
        if (!canAttempt()) {
            return;
        }
        try {
            redis.delete(key);
            markSuccess();
        } catch (Exception e) {
            markFailure("evict", e);
        }
    }

    /** 按前缀删除缓存，使用 SCAN 避免 KEYS 阻塞。 */
    public void evictByPrefix(String prefix) {
        if (!canAttempt()) {
            return;
        }
        try {
            var options =
                    ScanOptions.scanOptions().match(prefix + "*").count(100).build();
            try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
                var keys = new HashSet<String>();
                try (var cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                if (!keys.isEmpty()) {
                    redis.delete(keys);
                }
            }
            markSuccess();
        } catch (Exception e) {
            markFailure("evict-prefix", e);
        }
    }

    /** Redis 不可用时允许计数，保证缓存故障不会阻断帖子读取。 */
    public boolean tryIncrementView(String ip, Long postId, Duration window) {
        if (!canAttempt()) {
            return true;
        }
        String key = "view:" + ip + ":" + postId;
        try {
            Boolean inserted = redis.opsForValue().setIfAbsent(key, "1", window.getSeconds(), TimeUnit.SECONDS);
            markSuccess();
            return Boolean.TRUE.equals(inserted);
        } catch (Exception e) {
            markFailure("view-dedup", e);
            return true;
        }
    }

    /** 使用同一熔断状态发布 Pub/Sub 事件。 */
    public boolean publish(String channel, String payload) {
        if (!canAttempt()) {
            return false;
        }
        try {
            redis.convertAndSend(channel, payload);
            markSuccess();
            return true;
        } catch (Exception e) {
            markFailure("publish", e);
            return false;
        }
    }

    private boolean canAttempt() {
        return configured && nanoTime.getAsLong() >= disabledUntil.get();
    }

    private void markFailure(String operation, Exception error) {
        long now = nanoTime.getAsLong();
        long previous = disabledUntil.getAndSet(saturatedAdd(now, failureCooldownNanos));
        healthy.set(false);
        degraded.set(true);
        if (previous <= now) {
            log.warn("Redis 操作失败，进入冷却期 operation={}: {}", operation, error.getMessage());
            recordCircuitEvent("opened");
        }
    }

    private void markSuccess() {
        disabledUntil.set(0L);
        healthy.set(true);
        if (degraded.getAndSet(false)) {
            log.info("Redis 连接已恢复");
            recordCircuitEvent("recovered");
        }
    }

    private void recordHitMiss(boolean hit) {
        if (meterRegistry != null) {
            meterRegistry
                    .counter("tbforum.cache.requests", "result", hit ? "hit" : "miss")
                    .increment();
        }
    }

    private void recordCircuitEvent(String event) {
        if (meterRegistry != null) {
            meterRegistry
                    .counter("tbforum.redis.circuit.events", "event", event)
                    .increment();
        }
    }

    private static long saturatedAdd(long value, long increment) {
        long result = value + increment;
        return result < value ? Long.MAX_VALUE : result;
    }
}
