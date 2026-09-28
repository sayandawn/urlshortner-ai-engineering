package com.demo.urlshortner;

import com.demo.urlshortner.config.CacheConfig;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.demo.urlshortner.service.RedirectTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In-memory redirect cache: lookups are cached by short code, while expiration
 * and click counting are still evaluated against every request and the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedirectCacheIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private UrlMappingRepository repository;

    @Autowired
    private CacheManager cacheManager;

    private Cache cache;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        cache = cacheManager.getCache(CacheConfig.REDIRECT_TARGETS_CACHE);
        cache.clear();
        clearInvocations(repository);
    }

    @Test
    void firstLookupReadsFromDatabaseAndPopulatesCache() throws Exception {
        UrlMapping mapping = save("cache01", "https://example.com/first", null);
        assertThat(cache.get("cache01")).isNull();

        mockMvc.perform(get("/{shortCode}", "cache01")).andExpect(status().isFound());

        verify(repository, times(1)).findByShortCode("cache01");
        assertThat(cache.get("cache01", RedirectTarget.class))
                .isEqualTo(new RedirectTarget(mapping.getId(), "https://example.com/first", null));
    }

    @Test
    void subsequentLookupsAreServedFromCacheAndRedirect() throws Exception {
        save("cache02", "https://example.com/second", null);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{shortCode}", "cache02"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/second"));
        }

        verify(repository, times(1)).findByShortCode("cache02");
    }

    @Test
    void unknownShortCodesAreNotCached() throws Exception {
        mockMvc.perform(get("/{shortCode}", "unknown1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/{shortCode}", "unknown1")).andExpect(status().isNotFound());

        assertThat(cache.get("unknown1")).isNull();
        verify(repository, times(2)).findByShortCode("unknown1");
    }

    @Test
    void codeCreatedAfterFailedLookupResolvesOnceItExists() throws Exception {
        mockMvc.perform(get("/{shortCode}", "later01")).andExpect(status().isNotFound());
        save("later01", "https://example.com/later", null);

        mockMvc.perform(get("/{shortCode}", "later01"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/later"));
    }

    @Test
    void cachedExpiredMappingDoesNotRedirect() throws Exception {
        save("expd001", "https://example.com/expired", Instant.now().minus(1, ChronoUnit.MINUTES));

        mockMvc.perform(get("/{shortCode}", "expd001")).andExpect(status().isGone());
        assertThat(cache.get("expd001")).isNotNull();

        mockMvc.perform(get("/{shortCode}", "expd001"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"));

        verify(repository, times(1)).findByShortCode("expd001");
        assertClickCount("expd001", 0);
    }

    @Test
    void mappingCachedWhileValidStopsRedirectingOnceExpired() throws Exception {
        Instant expiresAt = Instant.now().plusMillis(1500);
        save("expd002", "https://example.com/soon", expiresAt);

        mockMvc.perform(get("/{shortCode}", "expd002")).andExpect(status().isFound());
        assertThat(cache.get("expd002")).isNotNull();

        Thread.sleep(Math.max(0, expiresAt.toEpochMilli() - System.currentTimeMillis()) + 100);

        mockMvc.perform(get("/{shortCode}", "expd002"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"));

        verify(repository, times(1)).findByShortCode("expd002");
        assertClickCount("expd002", 1);
    }

    @Test
    void clickCountStaysCorrectWhenRedirectsAreServedFromCache() throws Exception {
        save("count01", "https://example.com/count", null);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/{shortCode}", "count01")).andExpect(status().isFound());
        }

        verify(repository, times(1)).findByShortCode("count01");
        assertClickCount("count01", 4);
        assertThat(repository.findByShortCode("count01"))
                .hasValueSatisfying(mapping -> assertThat(mapping.getClickCount()).isEqualTo(4));
    }

    private UrlMapping save(String shortCode, String url, Instant expiresAt) {
        UrlMapping mapping = repository.saveAndFlush(new UrlMapping(shortCode, url, expiresAt));
        clearInvocations(repository);
        return mapping;
    }

    private void assertClickCount(String shortCode, int expected) throws Exception {
        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(expected));
    }
}
