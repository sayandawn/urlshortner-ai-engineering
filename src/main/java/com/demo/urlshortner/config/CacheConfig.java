package com.demo.urlshortner.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local, per-instance in-memory cache for redirect lookups (no Redis / distributed cache).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String REDIRECT_TARGETS_CACHE = "redirectTargets";

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(REDIRECT_TARGETS_CACHE);
    }
}
