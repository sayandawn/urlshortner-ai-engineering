package com.demo.urlshortner.config;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Click counting runs off the redirect path: a dedicated, bounded executor for {@code @Async}
 * click recording, plus a Resilience4j retry for temporary database failures.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties(ClickCountingProperties.class)
public class ClickCountingConfig {

    public static final String CLICK_COUNT_EXECUTOR = "clickCountExecutor";

    @Bean(name = CLICK_COUNT_EXECUTOR)
    public TaskExecutor clickCountExecutor(ClickCountingProperties properties) {
        if (!properties.async()) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("click-count-");
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        // Queue full: drop the click rather than block or fail the redirect.
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("Click-count queue is full ({} pending); click dropped", pool.getQueue().size()));
        // Graceful shutdown writes queued clicks first, up to the configured wait.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationMillis(properties.shutdownAwait().toMillis());
        return executor;
    }

    @Bean
    public Retry clickCountRetry(ClickCountingProperties properties) {
        return buildClickCountRetry(properties.retry());
    }

    /**
     * Retries only temporary failures: timeouts, lock contention, lost or unavailable connections.
     * Permanent errors fail on the first attempt because retrying cannot fix them.
     */
    public static Retry buildClickCountRetry(ClickCountingProperties.Retry retry) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(retry.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(retry.initialInterval(), retry.multiplier()))
                .retryExceptions(
                        TransientDataAccessException.class,
                        RecoverableDataAccessException.class,
                        DataAccessResourceFailureException.class,
                        CannotCreateTransactionException.class)
                .failAfterMaxAttempts(false)
                .build();
        return Retry.of("clickCount", config);
    }
}
