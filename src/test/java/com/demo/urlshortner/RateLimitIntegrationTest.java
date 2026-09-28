package com.demo.urlshortner;

import com.demo.urlshortner.config.RateLimitGroup;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Service-level rate limiting with small, test-only limits. The 1-hour refresh period
 * guarantees no permissions are restored while a test runs; limiters are reset per test.
 */
@SpringBootTest(properties = {
        "app.rate-limit.limits.create.limit-for-period=3",
        "app.rate-limit.limits.create.limit-refresh-period=1h",
        "app.rate-limit.limits.redirect.limit-for-period=3",
        "app.rate-limit.limits.redirect.limit-refresh-period=1h",
        "app.rate-limit.limits.lookup.limit-for-period=2",
        "app.rate-limit.limits.lookup.limit-refresh-period=1h",
        "app.rate-limit.limits.analytics.limit-for-period=100",
        "app.rate-limit.limits.analytics.limit-refresh-period=1h"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private RateLimiterRegistry registry;

    @BeforeEach
    void reset() {
        repository.deleteAll();
        for (RateLimitGroup group : RateLimitGroup.values()) {
            String name = group.name().toLowerCase(Locale.ROOT);
            registry.remove(name);
            registry.rateLimiter(name, name);
        }
    }

    @Test
    void createWithinLimitSucceedsAndExcessReturns429() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("https://example.com/" + i).andExpect(status().isCreated());
        }

        assertTooManyRequests(create("https://example.com/over-limit"), "create", "/api/urls");
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void createLimitIsCheckedBeforeRequestValidation() throws Exception {
        for (int i = 0; i < 3; i++) {
            create("not a url").andExpect(status().isBadRequest());
        }

        assertTooManyRequests(create("not a url"), "create", "/api/urls");
    }

    @Test
    void redirectWithinLimitSucceedsAndExcessReturns429() throws Exception {
        String shortCode = createShortCode("https://example.com/target");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{shortCode}", shortCode))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/target"));
        }

        assertTooManyRequests(mockMvc.perform(get("/{shortCode}", shortCode)), "redirect", "/" + shortCode)
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void rateLimitedRedirectDoesNotIncrementClickCount() throws Exception {
        String shortCode = createShortCode("https://example.com/counted");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());
        }
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(3));
        assertThat(repository.findByShortCode(shortCode))
                .hasValueSatisfying(mapping -> assertThat(mapping.getClickCount()).isEqualTo(3));
    }

    @Test
    void lookupHasItsOwnLimit() throws Exception {
        String shortCode = createShortCode("https://example.com/lookup");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/urls/{shortCode}", shortCode)).andExpect(status().isOk());
        }

        assertTooManyRequests(mockMvc.perform(get("/api/urls/{shortCode}", shortCode)),
                "lookup", "/api/urls/" + shortCode);
    }

    @Test
    void exhaustedCreateLimitDoesNotAffectOtherGroups() throws Exception {
        String shortCode = createShortCode("https://example.com/independent");
        create("https://example.com/2").andExpect(status().isCreated());
        create("https://example.com/3").andExpect(status().isCreated());
        create("https://example.com/4").andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());
        mockMvc.perform(get("/api/urls/{shortCode}", shortCode)).andExpect(status().isOk());
        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode)).andExpect(status().isOk());
    }

    @Test
    void exhaustedRedirectLimitDoesNotAffectOtherGroups() throws Exception {
        String shortCode = createShortCode("https://example.com/independent");
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());
        }
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isTooManyRequests());

        create("https://example.com/still-allowed").andExpect(status().isCreated());
        mockMvc.perform(get("/api/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/independent"));
        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode)).andExpect(status().isOk());
    }

    @Test
    void staticResourcesAndApiDocumentationAreNotRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/index.html")).andExpect(status().isOk());
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }
    }

    private ResultActions assertTooManyRequests(ResultActions result, String group, String path) throws Exception {
        return result
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.detail").value("Rate limit exceeded for " + group + " requests. Try again later."))
                .andExpect(jsonPath("$.instance").value(path));
    }

    private ResultActions create(String url) throws Exception {
        return mockMvc.perform(post("/api/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}"));
    }

    private String createShortCode(String url) throws Exception {
        String body = create(url).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.shortCode");
    }
}
