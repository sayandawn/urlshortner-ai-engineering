package com.demo.urlshortner.service;

import com.demo.urlshortner.exception.ShortCodeExpiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Redirect logical service: resolves a short code to its original URL
 * and records successful redirects for analytics.
 */
@Service
@RequiredArgsConstructor
public class RedirectService {

    private final RedirectLookupService lookupService;
    private final ClickRecorder clickRecorder;

    /**
     * The mapping may come from the cache, but expiration is evaluated on every request.
     * The click is handed to the {@link ClickRecorder} and counted asynchronously, so the
     * redirect never waits for, or fails because of, the click-count update.
     */
    public String resolve(String shortCode) {
        RedirectTarget target = findValidTarget(shortCode);
        clickRecorder.recordClick(target.id());
        return target.originalUrl();
    }

    /**
     * Same rules as {@link #resolve} (404 unknown, 410 expired) but without redirecting or
     * counting a click. For clients that cannot follow cross-origin redirects, e.g. Swagger UI.
     */
    public String getOriginalUrl(String shortCode) {
        return findValidTarget(shortCode).originalUrl();
    }

    private RedirectTarget findValidTarget(String shortCode) {
        RedirectTarget target = lookupService.findTarget(shortCode);
        if (target.isExpired(Instant.now())) {
            throw new ShortCodeExpiredException(shortCode);
        }
        return target;
    }
}
