package com.demo.urlshortner;

import com.demo.urlshortner.repository.UrlMappingRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BF-02: click counting and the analytics endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UrlAnalyticsIntegrationTest {

    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void newMappingStartsWithZeroClicks() throws Exception {
        String shortCode = createShortCode("https://example.com/new");

        analytics(shortCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/new"))
                .andExpect(jsonPath("$.clickCount").value(0))
                .andExpect(jsonPath("$.createdAt").value(matchesPattern(ISO_INSTANT)))
                .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.lastClickedAt").value(nullValue()))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void analyticsIncludesCreationAndExpirationTimes() throws Exception {
        Instant before = Instant.now();
        String expiresAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String body = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/timed\",\"expiresAt\":\"" + expiresAt + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        String analyticsBody = analytics(shortCode)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(expiresAt))
                .andReturn().getResponse().getContentAsString();

        Instant createdAt = Instant.parse(JsonPath.read(analyticsBody, "$.createdAt"));
        assertThat(createdAt).isBetween(before.minusSeconds(1), Instant.now());
    }

    @Test
    void successfulRedirectIncrementsClickCount() throws Exception {
        String shortCode = createShortCode("https://example.com/one");

        Instant before = Instant.now();
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/one"));

        String analyticsBody = analytics(shortCode)
                .andExpect(jsonPath("$.clickCount").value(1))
                .andExpect(jsonPath("$.lastClickedAt").value(matchesPattern(ISO_INSTANT)))
                .andReturn().getResponse().getContentAsString();
        Instant lastClickedAt = Instant.parse(JsonPath.read(analyticsBody, "$.lastClickedAt"));
        assertThat(lastClickedAt).isBetween(before.minusSeconds(1), Instant.now());
    }

    @Test
    void multipleRedirectsIncrementClickCount() throws Exception {
        String shortCode = createShortCode("https://example.com/many");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());
        }

        analytics(shortCode).andExpect(jsonPath("$.clickCount").value(5));
    }

    @Test
    void unknownShortCodeDoesNotAffectOtherCounts() throws Exception {
        String shortCode = createShortCode("https://example.com/other");

        mockMvc.perform(get("/{shortCode}", "unknown1")).andExpect(status().isNotFound());

        analytics(shortCode).andExpect(jsonPath("$.clickCount").value(0));
    }

    @Test
    void analyticsDoesNotCountAsClick() throws Exception {
        String shortCode = createShortCode("https://example.com/read");

        analytics(shortCode).andExpect(jsonPath("$.clickCount").value(0));
        analytics(shortCode).andExpect(jsonPath("$.clickCount").value(0));
    }

    @Test
    void analyticsForUnknownShortCodeReturnsNotFound() throws Exception {
        analytics("unknown1")
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Short code not found"))
                .andExpect(jsonPath("$.detail").value("Short code not found: unknown1"));
    }

    @Test
    void analyticsIsGroupedUnderItsOwnSwaggerTag() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/urls/{shortCode}/analytics'].get.tags[0]").value("Analytics"))
                .andExpect(jsonPath("$.paths['/{shortCode}'].get.tags[0]").value("Redirect"))
                .andExpect(jsonPath("$.paths['/api/urls/{shortCode}'].get.tags[0]").value("Redirect"))
                .andExpect(jsonPath("$.paths['/api/urls'].post.tags[0]").value("Shortener"))
                .andExpect(jsonPath("$.tags[*].name").value(contains("Shortener", "Redirect", "Analytics")));
    }

    private ResultActions analytics(String shortCode) throws Exception {
        return mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode));
    }

    private String createShortCode(String url) throws Exception {
        String body = mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.shortCode");
    }
}
