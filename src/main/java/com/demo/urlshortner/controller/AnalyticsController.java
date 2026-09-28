package com.demo.urlshortner.controller;

import com.demo.urlshortner.config.OpenApiConfig;
import com.demo.urlshortner.config.RateLimitGroup;
import com.demo.urlshortner.config.RateLimited;
import com.demo.urlshortner.dto.response.UrlAnalyticsResponse;
import com.demo.urlshortner.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = OpenApiConfig.ANALYTICS_TAG)
@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "Get click count, target URL, and creation/expiration/last-click times",
            description = "Does not redirect and does not count as a click. Expired codes still return their stats.")
    @RateLimited(RateLimitGroup.ANALYTICS)
    @GetMapping("/api/urls/{shortCode}/analytics")
    public UrlAnalyticsResponse analytics(@PathVariable String shortCode) {
        return analyticsService.getAnalytics(shortCode);
    }
}
