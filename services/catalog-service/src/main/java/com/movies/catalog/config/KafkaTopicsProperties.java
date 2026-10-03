package com.movies.catalog.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names for the movie.* events, externalized so they're one place to change
 * (and so search-service's consumer config can be kept in sync by reading the same values).
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        @NotBlank String movieCreated,
        @NotBlank String movieUpdated,
        @NotBlank String movieDeleted
) {
}
