package com.demo.urlshortner;

import com.demo.urlshortner.config.CacheConfig;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With 1 worker and a queue of 1, a blocked worker fills the queue: further clicks are dropped,
 * but every redirect still succeeds immediately.
 */
@SpringBootTest(properties = {
        "app.click-counting.async=true",
        "app.click-counting.pool-size=1",
        "app.click-counting.queue-capacity=1"
})
@AutoConfigureMockMvc
class ClickCountQueueFullIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private UrlMappingRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @Test
    void fullQueueDropsClicksWithoutFailingRedirects() throws Exception {
        repository.deleteAll();
        cacheManager.getCache(CacheConfig.REDIRECT_TARGETS_CACHE).clear();
        UrlMapping mapping = repository.saveAndFlush(new UrlMapping("queue01", "https://example.com/busy"));
        Answer<?> realRepository = mockingDetails(repository).getMockCreationSettings().getDefaultAnswer();
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return realRepository.answer(invocation);
        }).when(repository).incrementClickCount(any());

        // Click 1 occupies the only worker, click 2 fills the queue, clicks 3-5 are dropped.
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/{shortCode}", "queue01"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/busy"));
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> clickCount(mapping) == 2);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> clickCount(mapping) == 2);
        assertThat(clickCount(mapping)).isEqualTo(2);
    }

    private long clickCount(UrlMapping mapping) {
        return repository.findById(mapping.getId()).orElseThrow().getClickCount();
    }
}
