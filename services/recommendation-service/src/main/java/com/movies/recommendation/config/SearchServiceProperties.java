package com.movies.recommendation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * search-service client settings. The timeouts bound how long a slow search-service can hold up
 * this service's own response before it degrades to collaborative-only (see docs/adr/0005).
 */
@Validated
@ConfigurationProperties(prefix = "search.service")
public record SearchServiceProperties(
        @NotBlank String url,
        @Positive long connectTimeoutMs,
        @Positive long readTimeoutMs
) {
}
