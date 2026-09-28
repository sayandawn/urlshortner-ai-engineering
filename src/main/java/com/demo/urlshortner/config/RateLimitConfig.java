package com.demo.urlshortner.config;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service-level rate limiting with Resilience4j: one global, in-memory limiter per
 * {@link RateLimitGroup}, local to this application instance (no per-IP state).
 *
 * <p>Only active when {@code app.rate-limit.enabled=true} (the default). When enabled, missing or
 * invalid configuration for any group fails application startup. When disabled, this class and
 * {@link RateLimitProperties} are not loaded, so group configuration is not required.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimiterRegistry registry;

    public RateLimitConfig(RateLimitProperties properties) {
        this.registry = createRegistry(properties);
    }

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        return registry;
    }

    @Override
    public void addInterceptors(InterceptorRegistry interceptors) {
        interceptors.addInterceptor(new RateLimitInterceptor(registry));
    }

    static String limiterName(RateLimitGroup group) {
        return group.name().toLowerCase(Locale.ROOT);
    }

    private static RateLimiterRegistry createRegistry(RateLimitProperties properties) {
        Set<RateLimitGroup> missing = EnumSet.allOf(RateLimitGroup.class);
        missing.removeAll(properties.limits().keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing app.rate-limit.limits configuration for: " + missing);
        }
        // Each group's settings are registered as a named configuration, so a limiter
        // (re)created by name always gets its own group's limits.
        Map<String, RateLimiterConfig> configs = new HashMap<>();
        properties.limits().forEach((group, limit) -> {
            if (limit.limitRefreshPeriod().isZero() || limit.limitRefreshPeriod().isNegative()) {
                throw new IllegalStateException("app.rate-limit.limits." + limiterName(group)
                        + ".limit-refresh-period must be positive");
            }
            if (limit.timeout().isNegative()) {
                throw new IllegalStateException("app.rate-limit.limits." + limiterName(group)
                        + ".timeout must not be negative");
            }
            configs.put(limiterName(group), RateLimiterConfig.custom()
                    .limitForPeriod(limit.limitForPeriod())
                    .limitRefreshPeriod(limit.limitRefreshPeriod())
                    .timeoutDuration(limit.timeout())
                    .build());
        });
        RateLimiterRegistry registry = RateLimiterRegistry.of(configs);
        configs.keySet().forEach(name -> registry.rateLimiter(name, name));
        return registry;
    }
}
