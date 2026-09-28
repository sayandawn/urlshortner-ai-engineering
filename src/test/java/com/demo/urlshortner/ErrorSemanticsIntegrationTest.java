package com.demo.urlshortner;

import com.demo.urlshortner.service.RedirectService;
import com.demo.urlshortner.service.ShortenerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BF-04: server-side failures and framework errors use the same problem format.
 * 400/404/410 cases are covered in the Greenfield, analytics and expiration integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorSemanticsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortenerService shortenerService;

    @MockitoBean
    private RedirectService redirectService;

    @Test
    void unexpectedFailureReturnsGenericServerErrorWithoutInternalDetails() throws Exception {
        when(redirectService.resolve(anyString())).thenThrow(new IllegalStateException("secret internal detail"));

        mockMvc.perform(get("/{shortCode}", "abc1234"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.instance").value("/abc1234"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void unrelatedDatabaseFailureOnCreateReturnsServerError() throws Exception {
        when(shortenerService.shorten(anyString(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(post("/api/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(content().string(not(containsString("connection refused"))));
    }

    @Test
    void unsupportedMethodKeepsMethodNotAllowedStatus() throws Exception {
        mockMvc.perform(put("/api/urls").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Content-Type", containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedMediaTypeKeepsUnsupportedMediaTypeStatus() throws Exception {
        mockMvc.perform(post("/api/urls").contentType(MediaType.TEXT_PLAIN).content("https://example.com"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }
}
