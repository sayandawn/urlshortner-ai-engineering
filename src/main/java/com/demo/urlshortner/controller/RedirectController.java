package com.demo.urlshortner.controller;

import com.demo.urlshortner.config.OpenApiConfig;
import com.demo.urlshortner.config.RateLimitGroup;
import com.demo.urlshortner.config.RateLimited;
import com.demo.urlshortner.dto.response.ResolvedUrlResponse;
import com.demo.urlshortner.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@Tag(name = OpenApiConfig.REDIRECT_TAG)
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final RedirectService redirectService;

    // Restricted to Base62 so static resources (index.html, swagger-ui.html) are not captured.
    @Operation(summary = "Redirect to the original URL (302)",
            description = "Swagger UI cannot follow a cross-origin redirect (CORS), so 'Try it out' may show "
                    + "'Failed to fetch' even though the redirect works in a browser. "
                    + "Use GET /api/urls/{shortCode} to see the original URL instead.")
    @RateLimited(RateLimitGroup.REDIRECT)
    @GetMapping("/{shortCode:[0-9A-Za-z]+}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String originalUrl = redirectService.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(originalUrl))
                .build();
    }

    @Operation(summary = "Get the original URL without redirecting",
            description = "Returns 404 for an unknown code and 410 for an expired code, like the redirect. "
                    + "Does not count as a click.")
    @RateLimited(RateLimitGroup.LOOKUP)
    @GetMapping("/api/urls/{shortCode}")
    public ResolvedUrlResponse resolve(@PathVariable String shortCode) {
        return new ResolvedUrlResponse(shortCode, redirectService.getOriginalUrl(shortCode));
    }
}
