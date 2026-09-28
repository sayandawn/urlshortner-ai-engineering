package com.demo.urlshortner.controller;

import com.demo.urlshortner.config.OpenApiConfig;
import com.demo.urlshortner.config.RateLimitGroup;
import com.demo.urlshortner.config.RateLimited;
import com.demo.urlshortner.dto.request.ShortenUrlRequest;
import com.demo.urlshortner.dto.response.ShortenUrlResponse;
import com.demo.urlshortner.entity.UrlMapping;
import com.demo.urlshortner.service.ShortenerService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@Tag(name = OpenApiConfig.SHORTENER_TAG)
@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class ShortenerController {

    private final ShortenerService shortenerService;

    @RateLimited(RateLimitGroup.CREATE)
    @PostMapping
    public ResponseEntity<ShortenUrlResponse> shorten(@Valid @RequestBody ShortenUrlRequest request) {
        UrlMapping mapping = shortenerService.shorten(request.url(), request.expiresAt());
        URI shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{shortCode}")
                .buildAndExpand(mapping.getShortCode())
                .toUri();
        ShortenUrlResponse body = new ShortenUrlResponse(
                mapping.getShortCode(), shortUrl.toString(), mapping.getOriginalUrl());
        return ResponseEntity.created(shortUrl).body(body);
    }
}
