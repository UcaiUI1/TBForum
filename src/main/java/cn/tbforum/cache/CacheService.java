package cn.tbforum.cache;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存服务：封装常用操作，Redis 不可用时静默降级（不阻断业务）。
 * 用于版块列表、帖子详情缓存和浏览数防刷。
 */
@Service
public class CacheService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(CacheService.class);
    private final StringRedisTemplate redis;
    private final boolean available;
    private final MeterRegistry meterRegistry;

    public CacheService(@Autowired(required = false) StringRedisTemplate redis,
                        @Autowired(required = false) MeterRegistry registry) {
        this.redis = redis;
        this.available = redis != null;
        this.meterRegistry = registry;
    }

    /** Redis 是否可用（测试环境或 Redis 宕机时返回 false） */
    public boolean isAvailable() { return available; }

    /** 读取字符串缓存，记录命中/未命中指标 */
    public String get(String key) {
        if (!available) return null;
        try {
            String v = redis.opsForValue().get(key);
            recordHitMiss(v != null);
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入字符串缓存（带 TTL） */
    public void set(String key, String value, Duration ttl) {
        if (!available) return;
        try { redis.opsForValue().set(key, value, ttl); } catch (Exception ignored) {}
    }

    /** 删除缓存 */
    public void evict(String key) {
        if (!available) return;
        try { redis.delete(key); } catch (Exception ignored) {}
    }

    /** 按前缀删除缓存（使用 SCAN 避免 KEYS 阻塞） */
    public void evictByPrefix(String prefix) {
        if (!available) return;
        try {
            var opts = org.springframework.data.redis.core.ScanOptions.scanOptions().match(prefix + "*").count(100).build();
            try (var conn = redis.getRequiredConnectionFactory().getConnection()) {
                java.util.Set<String> keys = new java.util.HashSet<>();
                try (var cursor = conn.scan(opts)) {
                    while (cursor.hasNext()) { keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8)); }
                }
                if (!keys.isEmpty()) redis.delete(keys);
            }
        } catch (Exception e) { log.warn("cache evict failed", e); }
    }

    /**
     * 浏览数防刷：检查并标记 IP 访问记录。
     * @return true 表示本次访问应计入浏览数（首次或窗口已过），false 表示重复访问应忽略
     */
    public boolean tryIncrementView(String ip, Long postId, Duration window) {
        if (!available) return true; // Redis 不可用时回退到每次+1
        String key = "view:" + ip + ":" + postId;
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", window.getSeconds(), TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) { return true; } // 异常时降级为允许计数
    }

    /** 记录缓存命中/未命中（用于计算缓存命中率） */
    private void recordHitMiss(boolean hit) {
        if (meterRegistry == null) return;
        try {
            meterRegistry.counter("tbforum.cache.requests", "result", hit ? "hit" : "miss").increment();
        } catch (Exception ignored) {}
    }
}
