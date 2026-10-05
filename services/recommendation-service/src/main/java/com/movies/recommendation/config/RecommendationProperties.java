package com.movies.recommendation.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning knobs for the recommendation blend — deliberately externalized so the blend's
 * behavior is visible/adjustable without a code change. See docs/adr/0005.
 */
@Validated
@ConfigurationProperties(prefix = "app.recommendation")
public record RecommendationProperties(
        @NotNull @Positive Integer topKSimilarUsers,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double contentWeight,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double collabWeight,
        @NotNull @Positive Long cacheTtlSeconds,
        // Ratings are 1-5 (review-service's CreateReviewRequest).
        @NotNull @Min(1) @Max(5) Integer likeThreshold
) {
}
