package cn.tbforum.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        var container = new NonAutoStartingRedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setRecoveryInterval(5_000);
        container.setMaxSubscriptionRegistrationWaitingTime(1_000);
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
    RedisListenerLifecycle redisListenerLifecycle(
            RedisMessageListenerContainer container, ObjectProvider<MeterRegistry> registryProvider) {
        return new RedisListenerLifecycle(container, registryProvider.getIfAvailable());
    }
}

final class NonAutoStartingRedisMessageListenerContainer extends RedisMessageListenerContainer {
    @Override
    public boolean isAutoStartup() {
        return false;
    }
}

/** 在后台建立首次订阅，避免 Redis 暂时不可达时阻止应用启动。 */
final class RedisListenerLifecycle implements SmartLifecycle {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(RedisListenerLifecycle.class);
    private final RedisMessageListenerContainer container;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean listening = new AtomicBoolean();
    private final AtomicBoolean degraded = new AtomicBoolean();
    private ScheduledExecutorService executor;

    RedisListenerLifecycle(RedisMessageListenerContainer container, MeterRegistry registry) {
        this.container = container;
        if (registry != null) {
            registry.gauge("tbforum.redis.listener.available", listening, value -> value.get() ? 1.0 : 0.0);
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tbforum-redis-listener");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::ensureListening, 0, 5, TimeUnit.SECONDS);
    }

    private void ensureListening() {
        if (!running.get() || container.isListening()) {
            listening.set(container.isListening());
            return;
        }
        try {
            if (container.isRunning()) {
                container.stop();
            }
            container.start();
            listening.set(container.isListening());
            if (listening.get() && degraded.getAndSet(false)) {
                log.info("Redis Pub/Sub 监听已恢复");
            }
        } catch (RuntimeException e) {
            listening.set(false);
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // 下一轮继续尝试，避免清理异常终止恢复任务。
            }
            if (degraded.compareAndSet(false, true)) {
                log.warn("Redis Pub/Sub 暂不可用，将后台重试: {}", e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        running.set(false);
        listening.set(false);
        if (executor != null) {
            executor.shutdownNow();
        }
        if (container.isRunning()) {
            container.stop();
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
