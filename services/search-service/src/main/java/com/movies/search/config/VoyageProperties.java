package com.movies.search.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Voyage AI embeddings settings, bound only when {@code embedding.provider=voyage}.
 * {@code embeddingDimensions} must match the model's output size, since it also sizes the Atlas
 * vector index (see {@code VectorSearchIndexVerification}).
 */
@Validated
@ConfigurationProperties(prefix = "voyage")
public record VoyageProperties(
        @Valid @NotNull Api api,
        @NotBlank String model,
        @Positive int embeddingDimensions
) {

    /** {@code key} may be blank: the embedding service then disables itself instead of failing. */
    public record Api(@NotBlank String baseUrl, String key) {

        public boolean hasKey() {
            return key != null && !key.isBlank();
        }
    }
}
