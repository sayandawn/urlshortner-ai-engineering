package com.demo.urlshortner.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/**
 * Externalized rate-limit settings. Only bound and validated when rate limiting is enabled
 * ({@code app.rate-limit.enabled=true}, the default). Example:
 * <pre>
 * app.rate-limit.limits.create.limit-for-period=10
 * app.rate-limit.limits.create.limit-refresh-period=1m
 * app.rate-limit.limits.create.timeout=0s
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull Map<RateLimitGroup, @Valid @NotNull Limit> limits) {

    /**
     * @param limitForPeriod     requests permitted per refresh period, across all clients
     * @param limitRefreshPeriod length of the period after which permissions are restored
     * @param timeout            how long a request may wait for permission; 0 rejects immediately
     */
    public record Limit(
            @Min(1) int limitForPeriod,
            @NotNull Duration limitRefreshPeriod,
            @DefaultValue("0s") Duration timeout) {
    }
}
