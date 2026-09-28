package com.demo.urlshortner.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Asynchronous click counting, e.g.
 * <pre>
 * app.click-counting.async=true
 * app.click-counting.pool-size=2
 * app.click-counting.queue-capacity=10000
 * app.click-counting.shutdown-await=10s
 * app.click-counting.retry.max-attempts=5
 * app.click-counting.retry.initial-interval=100ms
 * app.click-counting.retry.multiplier=2.0
 * </pre>
 *
 * @param async          false runs counting on the caller's thread (used by the general test suite)
 * @param poolSize       worker threads that write click counts
 * @param queueCapacity  pending clicks held in memory; when full, new clicks are dropped (logged)
 * @param shutdownAwait  how long a graceful shutdown waits for queued clicks to be written
 */
@Validated
@ConfigurationProperties(prefix = "app.click-counting")
public record ClickCountingProperties(
        @DefaultValue("true") boolean async,
        @DefaultValue("2") @Min(1) int poolSize,
        @DefaultValue("10000") @Min(1) int queueCapacity,
        @DefaultValue("10s") @NotNull Duration shutdownAwait,
        @DefaultValue @Valid Retry retry) {

    /**
     * @param maxAttempts     total attempts per click, including the first
     * @param initialInterval wait before the first retry
     * @param multiplier      exponential backoff multiplier between retries
     */
    public record Retry(
            @DefaultValue("5") @Min(1) int maxAttempts,
            @DefaultValue("100ms") @NotNull Duration initialInterval,
            @DefaultValue("2.0") @DecimalMin("1.0") double multiplier) {
    }
}
