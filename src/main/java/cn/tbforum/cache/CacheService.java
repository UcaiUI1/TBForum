package cn.tbforum.cache;

import cn.tbforum.config.AppProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private static final String FEED_GENERATION_KEY = "cache-generation:feed";
    private static final Duration REBUILD_LEASE = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> PUBLISH_FEED = script("redis/feed-cache-publish.lua");
    private static final DefaultRedisScript<Long> RELEASE_LEASE = script("redis/cache-release-lease.lua");
    private final ConcurrentHashMap<String, CompletableFuture<String>> feedLoads = new ConcurrentHashMap<>();
    private final AtomicLong feedGeneration = new AtomicLong();

    private static DefaultRedisScript<Long> script(String path) {
        var script = new DefaultRedisScript<Long>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

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

    /** Merge concurrent homepage misses. Only anonymous data may be returned by the loader. */
    public String getOrLoadFeed(String key, Duration ttl, Supplier<String> loader) {
        String cached = get(key);
        if (cached != null) return cached;
        var mine = new CompletableFuture<String>();
        var existing = feedLoads.putIfAbsent(key, mine);
        if (existing != null) {
            recordFeedEvent("coalesced");
            try {
                return existing.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw e;
            }
        }
        try {
            cached = get(key);
            String value = cached != null ? cached : rebuildFeed(key, ttl, loader);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            feedLoads.remove(key, mine);
        }
    }

    private String rebuildFeed(String key, Duration ttl, Supplier<String> loader) {
        if (!canAttempt()) return loader.get();
        String leaseKey = "cache-rebuild:" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (canAttempt()) {
            boolean acquired;
            try {
                acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(leaseKey, token, REBUILD_LEASE));
                markSuccess();
            } catch (Exception e) {
                markFailure("feed-lease", e);
                break;
            }
            if (acquired) {
                try {
                    return buildAndPublishFeed(key, leaseKey, token, ttl, loader);
                } finally {
                    releaseLease(leaseKey, token);
                }
            }
            String cached = get(key);
            if (cached != null) return cached;
            // A slow/unreachable owner must not block readers indefinitely.
            // A fallback result is deliberately not published without ownership.
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for feed rebuild", e);
            }
        }
        return loader.get();
    }

    private String buildAndPublishFeed(
            String key, String leaseKey, String token, Duration ttl, Supplier<String> loader) {
        String generation;
        long localGeneration = feedGeneration.get();
        try {
            generation = redis.opsForValue().get(FEED_GENERATION_KEY);
            markSuccess();
        } catch (Exception e) {
            markFailure("feed-generation", e);
            return loader.get();
        }
        String value = loader.get();
        if (value != null && localGeneration == feedGeneration.get() && canAttempt()) {
            try {
                Long published = redis.execute(
                        PUBLISH_FEED,
                        List.of(key, FEED_GENERATION_KEY, leaseKey),
                        generation == null ? "0" : generation,
                        token,
                        value,
                        Long.toString(Math.max(1L, ttl.toMillis())));
                markSuccess();
                recordFeedEvent(Long.valueOf(1L).equals(published) ? "published" : "superseded");
            } catch (Exception e) {
                markFailure("feed-publish", e);
            }
        }
        return value;
    }

    private void releaseLease(String key, String token) {
        if (!canAttempt()) return;
        try {
            redis.execute(RELEASE_LEASE, List.of(key), token);
            markSuccess();
        } catch (Exception e) {
            markFailure("feed-release", e);
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
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictByPrefixNow(prefix);
                }
            });
            return;
        }
        evictByPrefixNow(prefix);
    }

    private void evictByPrefixNow(String prefix) {
        if ("feed:".equals(prefix)) feedGeneration.incrementAndGet();
        if (!canAttempt()) {
            return;
        }
        try {
            if ("feed:".equals(prefix)) redis.opsForValue().increment(FEED_GENERATION_KEY);
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

    private void recordFeedEvent(String event) {
        if (meterRegistry != null) {
            meterRegistry.counter("tbforum.cache.feed.rebuild", "result", event).increment();
        }
    }

    private static long saturatedAdd(long value, long increment) {
        long result = value + increment;
        return result < value ? Long.MAX_VALUE : result;
    }
}
