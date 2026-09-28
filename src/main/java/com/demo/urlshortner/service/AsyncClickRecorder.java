package com.demo.urlshortner.service;

import com.demo.urlshortner.config.ClickCountingConfig;
import com.demo.urlshortner.repository.UrlMappingRepository;
import io.github.resilience4j.retry.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Writes click counts on the dedicated click-count executor, off the redirect thread.
 * Each attempt is its own transaction (see {@link UrlMappingRepository#incrementClickCount});
 * temporary failures are retried with backoff, and a click that still fails is logged and dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncClickRecorder implements ClickRecorder {

    private final UrlMappingRepository repository;
    private final Retry clickCountRetry;

    @Async(ClickCountingConfig.CLICK_COUNT_EXECUTOR)
    @Override
    public void recordClick(Long mappingId) {
        try {
            clickCountRetry.executeRunnable(() -> repository.incrementClickCount(mappingId));
        } catch (RuntimeException ex) {
            log.error("Click for mapping id {} was not counted", mappingId, ex);
        }
    }
}
