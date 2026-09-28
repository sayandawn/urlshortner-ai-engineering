package com.demo.urlshortner.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UrlMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void mappingWithoutExpirationNeverExpires() {
        assertThat(new UrlMapping("abc1234", "https://example.com").isExpired(NOW.plusSeconds(1_000_000_000)))
                .isFalse();
    }

    @Test
    void mappingIsValidBeforeExpiration() {
        assertThat(new UrlMapping("abc1234", "https://example.com", NOW).isExpired(NOW.minusMillis(1))).isFalse();
    }

    @Test
    void mappingIsExpiredAtAndAfterExpiration() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", NOW);

        assertThat(mapping.isExpired(NOW)).isTrue();
        assertThat(mapping.isExpired(NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void newMappingStartsWithZeroClicks() {
        assertThat(new UrlMapping("abc1234", "https://example.com").getClickCount()).isZero();
    }
}
