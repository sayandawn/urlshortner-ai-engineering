package com.demo.urlshortner.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    public static final String SHORTENER_TAG = "Shortener";
    public static final String REDIRECT_TAG = "Redirect";
    public static final String ANALYTICS_TAG = "Analytics";

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("0.0.1")
                        .description("Shortener Service (POST /api/urls) and Redirect Service (GET /{shortCode})."))
                // Swagger UI shows groups in this order: create → redirect → analytics.
                .tags(List.of(
                        new Tag().name(SHORTENER_TAG).description("Create short URLs"),
                        new Tag().name(REDIRECT_TAG).description("Resolve short codes to original URLs"),
                        new Tag().name(ANALYTICS_TAG).description("Click statistics for short codes")));
    }
}
