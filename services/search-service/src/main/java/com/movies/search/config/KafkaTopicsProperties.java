package com.movies.search.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic names for the movie.* events consumed from catalog-service. Must stay in sync with
 * catalog-service's own {@code app.kafka.topics} values — see docs/adr/0001.
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        @NotBlank String movieCreated,
        @NotBlank String movieUpdated,
        @NotBlank String movieDeleted
) {
}
