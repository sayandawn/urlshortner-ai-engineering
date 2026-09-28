package com.demo.urlshortner;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.demo.urlshortner.service.ShortenerService;
import com.demo.urlshortner.util.ShortCodeGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BF-01: verifies collision handling against the real H2 unique constraint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShortCodeCollisionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private ShortenerService shortenerService;

    @MockitoBean
    private ShortCodeGenerator shortCodeGenerator;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void databaseCollisionIsRetriedWithNewCode() throws Exception {
        repository.saveAndFlush(new UrlMapping("taken01", "https://existing.example.com"));
        when(shortCodeGenerator.generate()).thenReturn("taken01", "fresh02");

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/new\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("fresh02"));

        assertThat(repository.count()).isEqualTo(2);
        assertThat(repository.findByShortCode("taken01")).hasValueSatisfying(mapping ->
                assertThat(mapping.getOriginalUrl()).isEqualTo("https://existing.example.com"));
    }

    @Test
    void retryExhaustionReturnsControlledServerError() throws Exception {
        repository.saveAndFlush(new UrlMapping("taken01", "https://existing.example.com"));
        when(shortCodeGenerator.generate()).thenReturn("taken01");

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/new\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Short code generation failed"))
                .andExpect(jsonPath("$.instance").value("/api/urls"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsDrawingSameCodeBothSucceed() throws Exception {
        // The first two generated codes are identical; later ones are unique.
        AtomicInteger calls = new AtomicInteger();
        when(shortCodeGenerator.generate()).thenAnswer(invocation -> {
            int call = calls.getAndIncrement();
            return call < 2 ? "race001" : "fresh0" + call;
        });

        CountDownLatch start = new CountDownLatch(1);
        Callable<UrlMapping> task = () -> {
            start.await();
            return shortenerService.shorten("https://example.com/concurrent");
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<UrlMapping>> results = List.of(executor.submit(task), executor.submit(task));
            start.countDown();

            String first = results.get(0).get().getShortCode();
            String second = results.get(1).get().getShortCode();

            assertThat(first).isNotEqualTo(second);
            assertThat(List.of(first, second)).contains("race001");
            assertThat(repository.count()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }
}
