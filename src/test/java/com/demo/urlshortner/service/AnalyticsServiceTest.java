package com.demo.urlshortner.service;

import com.demo.urlshortner.dto.response.UrlAnalyticsResponse;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.exception.ShortCodeNotFoundException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private UrlMappingRepository repository;

    @InjectMocks
    private AnalyticsService analyticsService;

    @Test
    void returnsAnalyticsForKnownShortCode() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com");
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        UrlAnalyticsResponse analytics = analyticsService.getAnalytics("abc1234");

        assertThat(analytics.shortCode()).isEqualTo("abc1234");
        assertThat(analytics.originalUrl()).isEqualTo("https://example.com");
        assertThat(analytics.clickCount()).isZero();
        assertThat(analytics.createdAt()).isEqualTo(mapping.getCreatedAt());
        assertThat(analytics.expiresAt()).isNull();
        assertThat(analytics.expired()).isFalse();
        assertThat(analytics.lastClickedAt()).isNull();
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void analyticsIncludesExpirationWhenSet() {
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping("abc1234", "https://example.com", expiresAt)));

        assertThat(analyticsService.getAnalytics("abc1234").expiresAt()).isEqualTo(expiresAt);
        assertThat(analyticsService.getAnalytics("abc1234").expired()).isFalse();
    }

    @Test
    void analyticsMarksPastExpirationAsExpired() {
        Instant expiresAt = Instant.now().minus(1, ChronoUnit.DAYS);
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping("abc1234", "https://example.com", expiresAt)));

        assertThat(analyticsService.getAnalytics("abc1234").expired()).isTrue();
    }

    @Test
    void analyticsForUnknownShortCodeThrowsNotFound() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> analyticsService.getAnalytics("missing"))
                .isInstanceOf(ShortCodeNotFoundException.class);
    }
}
