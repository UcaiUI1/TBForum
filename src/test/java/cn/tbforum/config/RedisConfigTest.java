package cn.tbforum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class RedisConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RedisConfig.class)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void disabledDoesNotCreateListenerContainer() {
        contextRunner
                .withPropertyValues("app.redis.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class));
    }

    @Test
    void enabledCreatesListenerContainer() {
        contextRunner
                .withPropertyValues("app.redis.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RedisMessageListenerContainer.class));
    }

    @Test
    void initialConnectionFailureDoesNotBlockApplicationLifecycle() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new IllegalStateException("redis down")).when(container).start();
        RedisListenerLifecycle lifecycle = new RedisListenerLifecycle(container, null);
        try {
            lifecycle.start();

            assertThat(lifecycle.isRunning()).isTrue();
            verify(container, timeout(2_000)).start();
        } finally {
            lifecycle.stop();
        }
    }
}
