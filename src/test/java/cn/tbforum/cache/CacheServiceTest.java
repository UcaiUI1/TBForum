package cn.tbforum.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Test
    void concurrentMissesShareOneLoadEvenWhenRedisIsDisabled() throws Exception {
        properties.getRedis().setEnabled(false);
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(16);
        var tasks = new ArrayList<Future<String>>();
        try {
            tasks.add(executor.submit(() -> cache.getOrLoadFeed("feed:latest:0:20", Duration.ofMinutes(2), () -> {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "shared";
            })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 15; i++) {
                tasks.add(executor.submit(() -> cache.getOrLoadFeed("feed:latest:0:20", Duration.ofMinutes(2), () -> {
                    calls.incrementAndGet();
                    return "unexpected";
                })));
            }
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                var counter = registry.find("tbforum.cache.feed.rebuild")
                        .tag("result", "coalesced")
                        .counter();
                if (counter != null && counter.count() == 15) break;
                Thread.sleep(5);
            }
            assertEquals(
                    15,
                    registry.get("tbforum.cache.feed.rebuild")
                            .tag("result", "coalesced")
                            .counter()
                            .count());
            release.countDown();
            for (var task : tasks) assertEquals("shared", task.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedLoadDoesNotPoisonLaterLoads() {
        properties.getRedis().setEnabled(false);
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> cache.getOrLoadFeed("feed:latest:0:20", Duration.ofMinutes(2), () -> {
                    throw new IllegalArgumentException("database failure");
                }));
        assertEquals("recovered", cache.getOrLoadFeed("feed:latest:0:20", Duration.ofMinutes(2), () -> "recovered"));
    }

    @Test
    void transactionalInvalidationWaitsForCommitAndRollbackDoesNotInvalidate() {
        when(redis.opsForValue()).thenReturn(values);
        var factory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        @SuppressWarnings("unchecked")
        Cursor<byte[]> cursor = mock(Cursor.class);
        when(redis.getRequiredConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.scan(org.mockito.ArgumentMatchers.any())).thenReturn(cursor);
        CacheService cache = new CacheService(redis, registry, properties, ticker::get);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictByPrefix("feed:");
            verify(values, never()).increment("cache-generation:feed");
            var callback =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            callback.afterCommit();
            verify(values).increment("cache-generation:feed");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictByPrefix("feed:");
            TransactionSynchronizationManager.getSynchronizations()
                    .get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(values).increment("cache-generation:feed");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
