package com.demo.urlshortner.config;

import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup behaviour of rate-limit configuration: fail fast when enabled and misconfigured,
 * and never require group configuration when disabled.
 */
class RateLimitConfigTest {

    private static final String[] ALL_GROUPS = {
            "app.rate-limit.limits.create.limit-for-period=10",
            "app.rate-limit.limits.create.limit-refresh-period=1m",
            "app.rate-limit.limits.redirect.limit-for-period=60",
            "app.rate-limit.limits.redirect.limit-refresh-period=1m",
            "app.rate-limit.limits.lookup.limit-for-period=60",
            "app.rate-limit.limits.lookup.limit-refresh-period=1m",
            "app.rate-limit.limits.analytics.limit-for-period=30",
            "app.rate-limit.limits.analytics.limit-refresh-period=1m"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitConfig.class);

    @Test
    void enabledWithAllGroupsCreatesOneLimiterPerGroup() {
        runner.withPropertyValues(ALL_GROUPS).run(context -> {
            assertThat(context).hasNotFailed();
            RateLimiterRegistry registry = context.getBean(RateLimiterRegistry.class);
            assertThat(registry.getAllRateLimiters()).hasSize(RateLimitGroup.values().length);
            assertThat(registry.rateLimiter("create", "create").getRateLimiterConfig().getLimitForPeriod())
                    .isEqualTo(10);
            assertThat(registry.rateLimiter("redirect", "redirect").getRateLimiterConfig().getLimitRefreshPeriod())
                    .isEqualTo(Duration.ofMinutes(1));
            assertThat(registry.rateLimiter("create", "create").getRateLimiterConfig().getTimeoutDuration())
                    .isZero();
        });
    }

    @Test
    void enabledIsTheDefault() {
        runner.withPropertyValues(ALL_GROUPS)
                .run(context -> assertThat(context).hasSingleBean(RateLimiterRegistry.class));
    }

    @Test
    void enabledWithMissingGroupFailsStartup() {
        runner.withPropertyValues(
                        "app.rate-limit.enabled=true",
                        "app.rate-limit.limits.create.limit-for-period=10",
                        "app.rate-limit.limits.create.limit-refresh-period=1m")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("Missing app.rate-limit.limits configuration")
                        .hasMessageContaining("REDIRECT"));
    }

    @Test
    void enabledWithNoGroupsFailsStartup() {
        runner.withPropertyValues("app.rate-limit.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithInvalidLimitFailsStartup() {
        runner.withPropertyValues(ALL_GROUPS)
                .withPropertyValues("app.rate-limit.limits.create.limit-for-period=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithNonPositiveRefreshPeriodFailsStartup() {
        runner.withPropertyValues(ALL_GROUPS)
                .withPropertyValues("app.rate-limit.limits.redirect.limit-refresh-period=0s")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("redirect.limit-refresh-period must be positive"));
    }

    @Test
    void disabledWithoutGroupConfigurationStartsWithoutRateLimiting() {
        runner.withPropertyValues("app.rate-limit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RateLimiterRegistry.class);
            assertThat(context).doesNotHaveBean(RateLimitProperties.class);
        });
    }
}
