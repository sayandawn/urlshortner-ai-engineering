package com.demo.urlshortner;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UrlShortenerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsShortUrlAndPersistsMapping() throws Exception {
        String body = shorten("{\"url\":\"https://example.com/some/long/path?x=1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(matchesPattern("http://localhost/[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/some/long/path?x=1"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(header().string("Location", matchesPattern("http://localhost/[0-9A-Za-z]{7}")))
                .andReturn().getResponse().getContentAsString();

        String shortCode = JsonPath.read(body, "$.shortCode");
        assertThat(repository.findByShortCode(shortCode))
                .hasValueSatisfying(mapping ->
                        assertThat(mapping.getOriginalUrl()).isEqualTo("https://example.com/some/long/path?x=1"));
    }

    @Test
    void redirectsShortCodeToOriginalUrl() throws Exception {
        String body = shorten("{\"url\":\"http://example.org/target\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://example.org/target"));
    }

    @Test
    void sameUrlShortenedTwiceGetsDistinctCodes() throws Exception {
        String first = shorten("{\"url\":\"https://example.com\"}").andReturn().getResponse().getContentAsString();
        String second = shorten("{\"url\":\"https://example.com\"}").andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(first, "$.shortCode"))
                .isNotEqualTo(JsonPath.read(second, "$.shortCode"));
        assertThat(repository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "ftp://example.com/file", "example.com", "javascript:alert(1)", "http://",
            "http://example.com/\\r\\nSet-Cookie:evil=1"})
    void rejectsMalformedOrUnsupportedUrl(String url) throws Exception {
        shorten("{\"url\":\"" + url + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid URL"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/urls"));
        assertThat(repository.count()).isZero();
    }

    /**
     * Percent-encoded control characters (as opposed to raw CR/LF, rejected above) are valid URI
     * text and pass validation. They must never be decoded into a real header injection: the stored
     * value is reflected into the redirect's Location header byte-for-byte, as literal text, and no
     * extra header is smuggled in.
     */
    @Test
    void percentEncodedControlCharactersAreNeverDecodedIntoAHeaderInjection() throws Exception {
        String url = "http://example.com/%0d%0aSet-Cookie:%20evil=1";
        String body = shorten("{\"url\":\"" + url + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalUrl").value(url))
                .andReturn().getResponse().getContentAsString();
        String shortCode = JsonPath.read(body, "$.shortCode");

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", url))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"url\":null}", "{\"url\":\"\"}", "{\"url\":\"   \"}"})
    void rejectsMissingOrBlankUrl(String requestBody) throws Exception {
        shorten(requestBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(containsString("url")));
        assertThat(repository.count()).isZero();
    }

    @Test
    void rejectsTooLongUrl() throws Exception {
        String url = "https://example.com/" + "a".repeat(2048);
        shorten("{\"url\":\"" + url + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{not json"})
    void rejectsMissingOrMalformedBody(String requestBody) throws Exception {
        shorten(requestBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or malformed"));
    }

    @Test
    void returnsNotFoundForUnknownShortCode() throws Exception {
        mockMvc.perform(get("/{shortCode}", "unknown1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Short code not found"))
                .andExpect(jsonPath("$.detail").value("Short code not found: unknown1"))
                .andExpect(jsonPath("$.instance").value("/unknown1"));
    }

    @Test
    void returnsNotFoundForShortCodeWithInvalidCharacters() throws Exception {
        mockMvc.perform(get("/bad-code.txt"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void servesHtmlTestPage() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>URL Shortener</title>")))
                .andExpect(content().string(containsString("id=\"shorten-form\"")))
                .andExpect(content().string(containsString("id=\"redirect-form\"")))
                .andExpect(content().string(containsString("id=\"analytics-form\"")));
    }

    @Test
    void exposesOpenApiDocsForBothServices() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("URL Shortener API"))
                .andExpect(jsonPath("$.paths['/api/urls'].post").exists())
                .andExpect(jsonPath("$.paths['/{shortCode}'].get").exists());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    private ResultActions shorten(String json) throws Exception {
        return mockMvc.perform(post("/api/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
