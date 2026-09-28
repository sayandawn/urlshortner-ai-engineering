package com.demo.urlshortner.dto.response;

public record ShortenUrlResponse(String shortCode, String shortUrl, String originalUrl) {
}
