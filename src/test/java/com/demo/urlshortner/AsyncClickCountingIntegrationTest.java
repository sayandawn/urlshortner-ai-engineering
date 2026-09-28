package com.demo.urlshortner;

import com.demo.urlshortner.config.CacheConfig;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asynchronous click counting with the real bounded executor: redirects never wait for
 * or fail because of the click-count update, and counts become eventually consistent.
 */
@SpringBootTest(properties = {
        "app.click-counting.async=true",
        "app.click-counting.retry.initial-interval=10ms"
})
@AutoConfigureMockMvc
class AsyncClickCountingIntegrationTest {

    private static final Duration EVENTUALLY = Duration.ofSeconds(5);

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private UrlMappingRepository repository;

    @Autowired
    private CacheManager cacheManager;

    /** The spy's pass-through behaviour, used to call the real repository from a stub. */
    private Answer<?> realRepository;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        cacheManager.getCache(CacheConfig.REDIRECT_TARGETS_CACHE).clear();
        realRepository = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        clearInvocations(repository);
    }

    @Test
    void redirectReturnsWithoutWaitingForClickCount() throws Exception {
        UrlMapping mapping = save("async01", "https://example.com/async");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return realRepository.answer(invocation);
        }).when(repository).incrementClickCount(any());

        mockMvc.perform(get("/{shortCode}", "async01"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/async"));

        // The redirect has returned while the click-count update is still blocked on another thread.
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(workerThread.get()).startsWith("click-count-");
        assertThat(clickCount(mapping)).isZero();

        release.countDown();
        await().atMost(EVENTUALLY).until(() -> clickCount(mapping) == 1);
    }

    @Test
    void clickCountIsEventuallyConsistent() throws Exception {
        UrlMapping mapping = save("async02", "https://example.com/many");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/{shortCode}", "async02")).andExpect(status().isFound());
        }

        await().atMost(EVENTUALLY).until(() -> clickCount(mapping) == 5);
    }

    @Test
    void temporaryFailuresAreRetriedAndClickIsCounted() throws Exception {
        UrlMapping mapping = save("async03", "https://example.com/retry");
        doThrow(new QueryTimeoutException("timeout"))
                .doThrow(new QueryTimeoutException("timeout"))
                .doAnswer(invocation -> realRepository.answer(invocation))
                .when(repository).incrementClickCount(any());

        mockMvc.perform(get("/{shortCode}", "async03")).andExpect(status().isFound());

        await().atMost(EVENTUALLY).until(() -> clickCount(mapping) == 1);
        verify(repository, times(3)).incrementClickCount(mapping.getId());
    }

    @Test
    void clickIsDroppedAfterMaxAttemptsButRedirectStillSucceeds() throws Exception {
        UrlMapping mapping = save("async04", "https://example.com/down");
        doThrow(new TransientDataAccessResourceException("database down"))
                .when(repository).incrementClickCount(any());

        mockMvc.perform(get("/{shortCode}", "async04"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/down"));

        await().atMost(EVENTUALLY).untilAsserted(() ->
                verify(repository, times(5)).incrementClickCount(mapping.getId()));
        assertThat(clickCount(mapping)).isZero();
    }

    @Test
    void permanentFailureIsNotRetried() throws Exception {
        UrlMapping mapping = save("async05", "https://example.com/permanent");
        doThrow(new DataIntegrityViolationException("permanent"))
                .when(repository).incrementClickCount(any());

        mockMvc.perform(get("/{shortCode}", "async05")).andExpect(status().isFound());

        await().atMost(EVENTUALLY).during(Duration.ofMillis(300)).untilAsserted(() ->
                verify(repository, times(1)).incrementClickCount(mapping.getId()));
    }

    private UrlMapping save(String shortCode, String url) {
        UrlMapping mapping = repository.saveAndFlush(new UrlMapping(shortCode, url));
        clearInvocations(repository);
        return mapping;
    }

    private long clickCount(UrlMapping mapping) {
        return repository.findById(mapping.getId()).orElseThrow().getClickCount();
    }
}
