package com.movies.recommendation.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * catalog-service client settings, used only by the startup backfill of movie_metadata (see
 * {@code CatalogBackfillRunner}). Never on the request-serving path.
 */
@Validated
@ConfigurationProperties(prefix = "catalog.service")
public record CatalogServiceProperties(
        @NotBlank String url,
        @Positive long connectTimeoutMs,
        @Positive long readTimeoutMs
) {
}
