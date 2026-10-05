package com.movies.review.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names for the review events, externalized so they're one place to change (and so
 * recommendation-service's future consumer config can be kept in sync by reading the same
 * values).
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        @NotBlank String reviewCreated,
        @NotBlank String ratingUpdated
) {
}
