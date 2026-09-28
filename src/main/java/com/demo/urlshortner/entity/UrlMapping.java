package com.demo.urlshortner.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistent mapping between a short code and the original URL.
 * The database id is internal only and is never exposed through the API.
 */
@Entity
@Table(name = "url_mapping", uniqueConstraints = @UniqueConstraint(
        name = UrlMapping.SHORT_CODE_UNIQUE_CONSTRAINT, columnNames = "short_code"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UrlMapping {

    public static final int MAX_URL_LENGTH = 2048;
    public static final String SHORT_CODE_UNIQUE_CONSTRAINT = "uk_url_mapping_short_code";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 16)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = MAX_URL_LENGTH)
    private String originalUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    /** Optional; {@code null} means the mapping never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** {@code null} until the first successful redirect; set atomically alongside {@link #clickCount}. */
    @Column(name = "last_clicked_at")
    private Instant lastClickedAt;

    public UrlMapping(String shortCode, String originalUrl) {
        this(shortCode, originalUrl, null);
    }

    public UrlMapping(String shortCode, String originalUrl, Instant expiresAt) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = Instant.now();
        this.clickCount = 0;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
