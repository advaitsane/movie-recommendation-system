package com.movies.search.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** catalog-service's base URL, used only by the startup backfill ({@code CatalogBackfillRunner}). */
@Validated
@ConfigurationProperties(prefix = "catalog.service")
public record CatalogServiceProperties(@NotBlank String url) {
}
