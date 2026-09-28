package com.demo.urlshortner.service;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.exception.ShortCodeExpiredException;
import com.demo.urlshortner.exception.ShortCodeNotFoundException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class RedirectServiceTest {

    @Mock
    private UrlMappingRepository repository;

    private RedirectService redirectService;

    @BeforeEach
    void setUp() {
        // Real lookup without the cache proxy (caching: RedirectCacheIntegrationTest) and a synchronous
        // click recorder (async counting and retry: AsyncClickRecorderTest, AsyncClickCountingIntegrationTest).
        redirectService = new RedirectService(new RedirectLookupService(repository), repository::incrementClickCount);
    }

    @Test
    void resolvesKnownShortCodeToOriginalUrl() {
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping("abc1234", "https://example.com")));

        assertThat(redirectService.resolve("abc1234")).isEqualTo("https://example.com");
    }

    @Test
    void throwsForUnknownShortCode() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> redirectService.resolve("missing"))
                .isInstanceOf(ShortCodeNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void successfulResolutionIncrementsClickCount() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com");
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        redirectService.resolve("abc1234");

        verify(repository).incrementClickCount(mapping.getId());
    }

    @Test
    void unknownShortCodeDoesNotIncrementClickCount() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> redirectService.resolve("missing"))
                .isInstanceOf(ShortCodeNotFoundException.class);
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void resolvesMappingThatHasNotExpiredYet() {
        UrlMapping mapping = new UrlMapping("abc1234", "https://example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(mapping));

        assertThat(redirectService.resolve("abc1234")).isEqualTo("https://example.com");
        verify(repository).incrementClickCount(mapping.getId());
    }

    @Test
    void expiredMappingThrowsGoneAndDoesNotIncrementClickCount() {
        UrlMapping mapping = new UrlMapping("old1234", "https://example.com", Instant.now().minusSeconds(1));
        when(repository.findByShortCode("old1234")).thenReturn(Optional.of(mapping));

        assertThatThrownBy(() -> redirectService.resolve("old1234"))
                .isInstanceOf(ShortCodeExpiredException.class)
                .hasMessageContaining("old1234");
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void getOriginalUrlReturnsUrlWithoutCountingClick() {
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new UrlMapping("abc1234", "https://en.wikipedia.org/wiki/URL")));

        assertThat(redirectService.getOriginalUrl("abc1234")).isEqualTo("https://en.wikipedia.org/wiki/URL");
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void getOriginalUrlFollowsRedirectRulesForUnknownAndExpiredCodes() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());
        when(repository.findByShortCode("old1234"))
                .thenReturn(Optional.of(new UrlMapping("old1234", "https://example.com", Instant.now().minusSeconds(1))));

        assertThatThrownBy(() -> redirectService.getOriginalUrl("missing"))
                .isInstanceOf(ShortCodeNotFoundException.class);
        assertThatThrownBy(() -> redirectService.getOriginalUrl("old1234"))
                .isInstanceOf(ShortCodeExpiredException.class);
        verify(repository, never()).incrementClickCount(any());
    }
}
