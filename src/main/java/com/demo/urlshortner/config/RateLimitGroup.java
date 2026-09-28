package com.demo.urlshortner.config;

/**
 * Endpoint groups with independent, service-level (global, not per-client) rate limits.
 * Configured under {@code app.rate-limit.limits.<group>} using the lower-case name.
 */
public enum RateLimitGroup {
    CREATE,
    REDIRECT,
    LOOKUP,
    ANALYTICS
}
