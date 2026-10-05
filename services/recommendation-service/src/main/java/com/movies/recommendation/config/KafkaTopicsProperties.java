package com.movies.recommendation.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names for the events consumed from catalog-service and review-service. Must stay in
 * sync with each producer's own {@code app.kafka.topics} values.
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        @NotBlank String movieCreated,
        @NotBlank String movieUpdated,
        @NotBlank String movieDeleted,
        @NotBlank String reviewCreated,
        @NotBlank String ratingUpdated
) {
}
