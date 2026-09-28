package com.demo.urlshortner.config;

import com.demo.urlshortner.exception.RateLimitExceededException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Enforces {@link RateLimited} after request routing and before the controller method runs,
 * so a rejected request never reaches business logic, the redirect cache, the database,
 * or click counting.
 */
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterRegistry registry;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method) {
            RateLimited rateLimited = method.getMethodAnnotation(RateLimited.class);
            if (rateLimited != null) {
                String name = RateLimitConfig.limiterName(rateLimited.value());
                RateLimiter limiter = registry.rateLimiter(name, name);
                if (!limiter.acquirePermission()) {
                    throw new RateLimitExceededException(name, retryAfterSeconds(limiter));
                }
            }
        }
        return true;
    }

    /** Estimated wait until the next permission, rounded up to whole seconds (minimum 1). */
    private static long retryAfterSeconds(RateLimiter limiter) {
        long nanos = limiter instanceof AtomicRateLimiter atomic
                ? atomic.getDetailedMetrics().getNanosToWait()
                : limiter.getRateLimiterConfig().getLimitRefreshPeriod().toNanos();
        return Math.max(1, (nanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
    }
}
