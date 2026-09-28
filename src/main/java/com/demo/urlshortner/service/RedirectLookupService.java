package com.demo.urlshortner.service;

import com.demo.urlshortner.config.CacheConfig;
import com.demo.urlshortner.exception.ShortCodeNotFoundException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cached short-code lookup for the Redirect Service. A separate bean so the
 * {@code @Cacheable} proxy applies (self-invocation inside RedirectService would bypass it).
 * Unknown codes throw, and exceptions are never cached.
 */
@Service
@RequiredArgsConstructor
public class RedirectLookupService {

    private final UrlMappingRepository repository;

    @Cacheable(cacheNames = CacheConfig.REDIRECT_TARGETS_CACHE, key = "#shortCode")
    public RedirectTarget findTarget(String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(RedirectTarget::from)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
    }
}
