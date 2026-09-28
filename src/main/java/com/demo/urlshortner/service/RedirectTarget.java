package com.demo.urlshortner.service;

import com.demo.urlshortner.entity.UrlMapping;

import java.time.Instant;

/**
 * Immutable, cacheable view of a mapping with only what a redirect needs.
 * Deliberately excludes {@code clickCount}: the database stays the source of truth for analytics.
 */
public record RedirectTarget(Long id, String originalUrl, Instant expiresAt) {

    static RedirectTarget from(UrlMapping mapping) {
        return new RedirectTarget(mapping.getId(), mapping.getOriginalUrl(), mapping.getExpiresAt());
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
