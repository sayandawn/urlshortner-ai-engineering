package com.demo.urlshortner.service;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.exception.InvalidUrlException;
import com.demo.urlshortner.exception.ShortCodeGenerationException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.demo.urlshortner.util.ShortCodeGenerator;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Shortener logical service: validates long URLs, generates unique short codes
 * and persists the mapping.
 */
@Service
@RequiredArgsConstructor
public class ShortenerService {

    static final int MAX_GENERATION_ATTEMPTS = 5;
    private static final Set<String> SUPPORTED_SCHEMES = Set.of("http", "https");

    private final UrlMappingRepository repository;
    private final ShortCodeGenerator shortCodeGenerator;

    public UrlMapping shorten(String url) {
        return shorten(url, null);
    }

    /**
     * Not {@code @Transactional}: each insert attempt runs in its own repository transaction,
     * so a failed insert can be followed by a retry with a fresh short code.
     *
     * @param expiresAt optional expiration; {@code null} means the mapping never expires
     */
    public UrlMapping shorten(String url, Instant expiresAt) {
        String validatedUrl = validate(url);
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            UrlMapping mapping = new UrlMapping(shortCodeGenerator.generate(), validatedUrl, expiresAt);
            try {
                return repository.saveAndFlush(mapping);
            } catch (DataIntegrityViolationException ex) {
                // The database unique constraint is the final authority on short-code uniqueness.
                if (!isShortCodeCollision(ex)) {
                    throw ex;
                }
            }
        }
        throw new ShortCodeGenerationException(MAX_GENERATION_ATTEMPTS);
    }

    private String validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        String trimmed = url.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is malformed: " + trimmed);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !SUPPORTED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException("Only http and https URLs are supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("URL must contain a valid host");
        }
        return trimmed;
    }

    private static boolean isShortCodeCollision(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null
                    && violation.getConstraintName().toLowerCase(Locale.ROOT)
                            .contains(UrlMapping.SHORT_CODE_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
