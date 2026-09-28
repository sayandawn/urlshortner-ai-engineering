package com.demo.urlshortner.service;

import com.demo.urlshortner.dto.response.UrlAnalyticsResponse;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.exception.ShortCodeNotFoundException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Read-only analytics for short codes. Never redirects, never counts a click,
 * and always reads from the database (authoritative for click counts), not the redirect cache.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final UrlMappingRepository repository;

    @Transactional(readOnly = true)
    public UrlAnalyticsResponse getAnalytics(String shortCode) {
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortCodeNotFoundException(shortCode));
        return new UrlAnalyticsResponse(mapping.getShortCode(), mapping.getOriginalUrl(), mapping.getClickCount(),
                mapping.getCreatedAt(), mapping.getExpiresAt(), mapping.isExpired(Instant.now()),
                mapping.getLastClickedAt());
    }
}
