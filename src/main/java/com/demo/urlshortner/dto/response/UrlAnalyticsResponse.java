package com.demo.urlshortner.dto.response;

import java.time.Instant;

/**
 * @param expiresAt     {@code null} when the mapping never expires
 * @param expired       {@code true} once {@code expiresAt} has passed; always {@code false} when it is null
 * @param lastClickedAt {@code null} if the link has never been successfully redirected
 */
public record UrlAnalyticsResponse(String shortCode, String originalUrl, long clickCount, Instant createdAt,
                                    Instant expiresAt, boolean expired, Instant lastClickedAt) {
}
