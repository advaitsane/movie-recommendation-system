package com.movies.search.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Name of the Atlas Vector Search index that {@code VectorSearchIndexVerification} creates on
 * startup and the vector/similar search endpoints query.
 */
@Validated
@ConfigurationProperties(prefix = "search.vector")
public record VectorSearchProperties(@NotBlank String indexName) {
}
