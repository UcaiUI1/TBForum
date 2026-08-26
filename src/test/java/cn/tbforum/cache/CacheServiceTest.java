package cn.tbforum.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {
    @Mock
    StringRedisTemplate redis;

    @Mock
    ValueOperations<String, String> values;

    private AppProperties properties;
    private AtomicLong ticker;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getRedis().setEnabled(true);
        properties.getRedis().setFailureCooldown(Duration.ofSeconds(30));
        ticker = new AtomicLong(1L);
        registry = new SimpleMeterRegistry();
    }

    @Test
    void disabledRedisNeverAttemptsConnection() {
        properties.getRedis().setEnabled(false);
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);

        assertNull(cache.get("key"));
        assertFalse(cache.publish("channel", "payload"));
        assertTrue(cache.tryIncrementView("127.0.0.1", 1L, Duration.ofMinutes(5)));
        assertFalse(cache.isAvailable());
        verify(redis, never()).opsForValue();
        verify(redis, never()).convertAndSend("channel", "payload");
    }

    @Test
    void successfulOperationMarksRedisHealthy() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("key")).thenReturn("value");
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);

        assertEquals("value", cache.get("key"));
        assertTrue(cache.isAvailable());
        assertEquals(1.0, registry.get("tbforum.redis.available").gauge().value());
    }

    @Test
    void failureOpensCircuitAndSuccessfulProbeRecovers() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("key"))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn("recovered");
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);

        assertNull(cache.get("key"));
        assertFalse(cache.isAvailable());
        assertNull(cache.get("key"));
        verify(values).get("key");

        ticker.addAndGet(Duration.ofSeconds(30).toNanos());
        assertEquals("recovered", cache.get("key"));
        assertTrue(cache.isAvailable());
        verify(values, org.mockito.Mockito.times(2)).get("key");
        assertEquals(
                1.0,
                registry.get("tbforum.redis.circuit.events")
                        .tag("event", "opened")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("tbforum.redis.circuit.events")
                        .tag("event", "recovered")
                        .counter()
                        .count());
    }
}
