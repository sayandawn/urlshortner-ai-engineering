package com.demo.urlshortner.service;

import com.demo.urlshortner.config.ClickCountingConfig;
import com.demo.urlshortner.config.ClickCountingProperties;
import com.demo.urlshortner.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry rules of the click recorder, called directly (no @Async proxy) with a near-zero backoff.
 */
@ExtendWith(MockitoExtension.class)
class AsyncClickRecorderTest {

    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private UrlMappingRepository repository;

    private AsyncClickRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new AsyncClickRecorder(repository, ClickCountingConfig.buildClickCountRetry(
                new ClickCountingProperties.Retry(MAX_ATTEMPTS, Duration.ofMillis(1), 1.0)));
    }

    @Test
    void countsClickOnFirstAttempt() {
        when(repository.incrementClickCount(1L)).thenReturn(1);

        recorder.recordClick(1L);

        verify(repository, times(1)).incrementClickCount(1L);
    }

    @Test
    void retriesTemporaryFailuresThenCountsClick() {
        when(repository.incrementClickCount(1L))
                .thenThrow(new QueryTimeoutException("timeout"))
                .thenThrow(new CannotAcquireLockException("lock"))
                .thenReturn(1);

        recorder.recordClick(1L);

        verify(repository, times(3)).incrementClickCount(1L);
    }

    @Test
    void retriesConnectionFailures() {
        when(repository.incrementClickCount(1L))
                .thenThrow(new DataAccessResourceFailureException("connection refused"))
                .thenThrow(new CannotCreateTransactionException("no connection"))
                .thenThrow(new RecoverableDataAccessException("recoverable"))
                .thenReturn(1);

        recorder.recordClick(1L);

        verify(repository, times(4)).incrementClickCount(1L);
    }

    @Test
    void givesUpAfterMaxAttemptsWithoutThrowing() {
        when(repository.incrementClickCount(1L)).thenThrow(new TransientDataAccessResourceException("down"));

        assertThatCode(() -> recorder.recordClick(1L)).doesNotThrowAnyException();

        verify(repository, times(MAX_ATTEMPTS)).incrementClickCount(1L);
    }

    @Test
    void doesNotRetryPermanentErrors() {
        when(repository.incrementClickCount(1L)).thenThrow(new DataIntegrityViolationException("constraint"));

        assertThatCode(() -> recorder.recordClick(1L)).doesNotThrowAnyException();

        verify(repository, times(1)).incrementClickCount(1L);
    }

    @Test
    void doesNotRetryProgrammingErrors() {
        when(repository.incrementClickCount(1L)).thenThrow(new InvalidDataAccessApiUsageException("bad usage"));

        assertThatCode(() -> recorder.recordClick(1L)).doesNotThrowAnyException();

        verify(repository, times(1)).incrementClickCount(1L);
    }
}
