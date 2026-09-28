package com.demo.urlshortner.service;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.exception.InvalidUrlException;
import com.demo.urlshortner.exception.ShortCodeGenerationException;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.demo.urlshortner.util.ShortCodeGenerator;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortenerServiceTest {

    @Mock
    private UrlMappingRepository repository;

    @Mock
    private ShortCodeGenerator shortCodeGenerator;

    @InjectMocks
    private ShortenerService shortenerService;

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/path?q=1", "http://example.com", "HTTPS://Example.com"})
    void shortensValidHttpAndHttpsUrls(String url) {
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UrlMapping mapping = shortenerService.shorten(url);

        assertThat(mapping.getShortCode()).isEqualTo("abc1234");
        assertThat(mapping.getOriginalUrl()).isEqualTo(url);
        verify(repository).saveAndFlush(mapping);
        verify(shortCodeGenerator, times(1)).generate();
    }

    @Test
    void storesOptionalExpiration() {
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS);
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(shortenerService.shorten("https://example.com", expiresAt).getExpiresAt()).isEqualTo(expiresAt);
        assertThat(shortenerService.shorten("https://example.com").getExpiresAt()).isNull();
    }

    @Test
    void retriesWithNewCodeWhenDatabaseReportsShortCodeCollision() {
        when(shortCodeGenerator.generate()).thenReturn("taken01", "fresh02");
        when(repository.saveAndFlush(any(UrlMapping.class)))
                .thenThrow(shortCodeCollision())
                .thenAnswer(invocation -> invocation.getArgument(0));

        UrlMapping mapping = shortenerService.shorten("https://example.com");

        assertThat(mapping.getShortCode()).isEqualTo("fresh02");
        verify(repository, times(2)).saveAndFlush(any(UrlMapping.class));
    }

    @Test
    void failsWithControlledErrorAfterMaxGenerationAttempts() {
        when(shortCodeGenerator.generate()).thenReturn("taken01");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenThrow(shortCodeCollision());

        assertThatThrownBy(() -> shortenerService.shorten("https://example.com"))
                .isInstanceOf(ShortCodeGenerationException.class)
                .hasMessageContaining(String.valueOf(ShortenerService.MAX_GENERATION_ATTEMPTS));
        verify(shortCodeGenerator, times(ShortenerService.MAX_GENERATION_ATTEMPTS)).generate();
        verify(repository, times(ShortenerService.MAX_GENERATION_ATTEMPTS)).saveAndFlush(any(UrlMapping.class));
    }

    @Test
    void doesNotRetryOtherIntegrityViolations() {
        DataIntegrityViolationException otherViolation = new DataIntegrityViolationException("not null",
                new ConstraintViolationException("not null", new SQLException(), "NOT_NULL_ORIGINAL_URL"));
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenThrow(otherViolation);

        assertThatThrownBy(() -> shortenerService.shorten("https://example.com")).isSameAs(otherViolation);
        verify(repository, times(1)).saveAndFlush(any(UrlMapping.class));
    }

    @Test
    void doesNotRetryUnrelatedDatabaseFailures() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("connection lost");
        when(shortCodeGenerator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any(UrlMapping.class))).thenThrow(failure);

        assertThatThrownBy(() -> shortenerService.shorten("https://example.com")).isSameAs(failure);
        verify(repository, times(1)).saveAndFlush(any(UrlMapping.class));
    }

    private static DataIntegrityViolationException shortCodeCollision() {
        return new DataIntegrityViolationException("duplicate short code",
                new ConstraintViolationException("duplicate", new SQLException(),
                        "PUBLIC.UK_URL_MAPPING_SHORT_CODE_INDEX_1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not a url",
            "example.com",
            "ftp://example.com/file",
            "mailto:user@example.com",
            "javascript:alert(1)",
            "http://",
            "https:///path-only",
            "http://exa mple.com"
    })
    void rejectsMissingMalformedOrUnsupportedUrls(String url) {
        assertThatThrownBy(() -> shortenerService.shorten(url))
                .isInstanceOf(InvalidUrlException.class);
        verify(repository, never()).saveAndFlush(any());
    }
}
