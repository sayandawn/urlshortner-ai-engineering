package com.demo.urlshortner.config;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class ClickCountingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClickCountingConfig.class);

    @Test
    void defaultsToBoundedAsyncExecutorAndFiveAttemptRetry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TaskExecutor executor = context.getBean(ClickCountingConfig.CLICK_COUNT_EXECUTOR, TaskExecutor.class);
            assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaxPoolSize()).isEqualTo(2);
            assertThat(pool.getQueueCapacity()).isEqualTo(10_000);
            assertThat(context.getBean(Retry.class).getRetryConfig().getMaxAttempts()).isEqualTo(5);
        });
    }

    @Test
    void appliesConfiguredValues() {
        runner.withPropertyValues(
                        "app.click-counting.pool-size=4",
                        "app.click-counting.queue-capacity=50",
                        "app.click-counting.retry.max-attempts=3")
                .run(context -> {
                    ThreadPoolTaskExecutor pool = context.getBean(
                            ClickCountingConfig.CLICK_COUNT_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(pool.getCorePoolSize()).isEqualTo(4);
                    assertThat(pool.getQueueCapacity()).isEqualTo(50);
                    assertThat(context.getBean(Retry.class).getRetryConfig().getMaxAttempts()).isEqualTo(3);
                });
    }

    @Test
    void asyncDisabledUsesCallerThread() {
        runner.withPropertyValues("app.click-counting.async=false").run(context ->
                assertThat(context.getBean(ClickCountingConfig.CLICK_COUNT_EXECUTOR))
                        .isInstanceOf(SyncTaskExecutor.class));
    }

    @Test
    void invalidConfigurationFailsStartup() {
        runner.withPropertyValues("app.click-counting.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.click-counting.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
