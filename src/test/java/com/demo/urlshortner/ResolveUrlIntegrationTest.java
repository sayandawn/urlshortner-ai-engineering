package com.demo.urlshortner;

import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.repository.UrlMappingRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/urls/{shortCode}: returns the original URL as JSON for clients (e.g. Swagger UI)
 * that cannot follow a cross-origin redirect.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResolveUrlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void returnsOriginalUrlAsJsonWithoutRedirecting() throws Exception {
        String shortCode = createShortCode("https://en.wikipedia.org/wiki/URL_shortening");

        mockMvc.perform(get("/api/urls/{shortCode}", shortCode))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.originalUrl").value("https://en.wikipedia.org/wiki/URL_shortening"))
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void lookupDoesNotCountAsClick() throws Exception {
        String shortCode = createShortCode("https://example.com/lookup");

        mockMvc.perform(get("/api/urls/{shortCode}", shortCode)).andExpect(status().isOk());
        mockMvc.perform(get("/api/urls/{shortCode}", shortCode)).andExpect(status().isOk());
        mockMvc.perform(get("/{shortCode}", shortCode)).andExpect(status().isFound());

        mockMvc.perform(get("/api/urls/{shortCode}/analytics", shortCode))
                .andExpect(jsonPath("$.clickCount").value(1));
    }

    @Test
    void unknownCodeReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/urls/{shortCode}", "unknown1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Short code not found"))
                .andExpect(jsonPath("$.detail").value("Short code not found: unknown1"));
    }

    @Test
    void expiredCodeReturnsGone() throws Exception {
        repository.saveAndFlush(new UrlMapping("resexp1", "https://example.com/old",
                Instant.now().minus(1, ChronoUnit.MINUTES)));

        mockMvc.perform(get("/api/urls/{shortCode}", "resexp1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Short code expired"))
                .andExpect(jsonPath("$.detail").value("Short code has expired: resexp1"));
    }

    @Test
    void endpointIsDocumentedInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/urls/{shortCode}'].get").exists())
                .andExpect(jsonPath("$.paths['/{shortCode}'].get.description")
                        .value(containsString("GET /api/urls/{shortCode}")));
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
