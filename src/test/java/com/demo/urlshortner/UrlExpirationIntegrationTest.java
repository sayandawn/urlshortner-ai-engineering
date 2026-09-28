package com.demo.urlshortner;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BF-03: optional URL expiration.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlExpirationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void requestWithoutExpirationStoresNoExpirationAndRedirects() throws Exception {
        String shortCode = createShortCode("{\"url\":\"https://example.com/forever\"}");

        assertThat(repository.findByShortCode(shortCode))
                .hasValueSatisfying(mapping -> assertThat(mapping.getExpiresAt()).isNull());
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/forever"));
    }

    @Test
    void futureExpirationIsStoredAndRedirectsBeforeExpiry() throws Exception {
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String shortCode = createShortCode(
                "{\"url\":\"https://example.com/soon\",\"expiresAt\":\"" + expiresAt + "\"}");

        assertThat(repository.findByShortCode(shortCode))
                .hasValueSatisfying(mapping -> assertThat(mapping.getExpiresAt()).isEqualTo(expiresAt));
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/soon"));
        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode))
                .andExpect(jsonPath("$.clickCount").value(1));
    }

    @Test
    void explicitNullExpirationMeansNoExpiration() throws Exception {
        String shortCode = createShortCode("{\"url\":\"https://example.com/null\",\"expiresAt\":null}");

        assertThat(repository.findByShortCode(shortCode))
                .hasValueSatisfying(mapping -> assertThat(mapping.getExpiresAt()).isNull());
    }

    @Test
    void pastExpirationIsRejected() throws Exception {
        String past = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString();

        shorten("{\"url\":\"https://example.com\",\"expiresAt\":\"" + past + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("expiresAt: must be in the future"));
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"tomorrow\"", "\"2026-13-45T99:00:00Z\"", "\"2026-10-01T12:00:00\"", "true"})
    void malformedExpirationIsRejected(String expiresAt) throws Exception {
        shorten("{\"url\":\"https://example.com\",\"expiresAt\":" + expiresAt + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(
                        "expiresAt: must be an ISO-8601 timestamp with time zone, e.g. 2026-10-01T12:00:00Z"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void expiredMappingReturnsGoneAndDoesNotCountClick() throws Exception {
        repository.saveAndFlush(new UrlMapping("expired1", "https://example.com/old",
                Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(get("/{shortCode}", "expired1"))
                .andExpect(status().isGone())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.title").value("Short code expired"))
                .andExpect(jsonPath("$.detail").value("Short code has expired: expired1"))
                .andExpect(jsonPath("$.instance").value("/expired1"));

        mockMvc.perform(get("/api/urls/{shortCode}/analytics", "expired1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.expired").value(true));
    }

    @Test
    void expiredMappingIsNotDeleted() throws Exception {
        repository.saveAndFlush(new UrlMapping("expired2", "https://example.com/old",
                Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(get("/{shortCode}", "expired2")).andExpect(status().isGone());
        mockMvc.perform(get("/{shortCode}", "expired2")).andExpect(status().isGone());

        assertThat(repository.findByShortCode("expired2")).isPresent();
    }

    private ResultActions shorten(String json) throws Exception {
        return mockMvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String createShortCode(String json) throws Exception {
        String body = shorten(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.shortCode");
    }
}
