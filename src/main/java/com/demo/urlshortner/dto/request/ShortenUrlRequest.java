package com.demo.urlshortner.dto.request;

import com.demo.urlshortner.entity.UrlMapping;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ShortenUrlRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = UrlMapping.MAX_URL_LENGTH, message = "must be at most {max} characters")
        String url,

        /* Optional ISO-8601 timestamp, e.g. 2026-10-01T12:00:00Z; omitted means no expiration. */
        @Future(message = "must be in the future")
        Instant expiresAt) {
}
