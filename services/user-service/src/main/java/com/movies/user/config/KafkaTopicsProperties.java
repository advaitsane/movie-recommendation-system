package com.movies.user.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Topic name for user activity events, externalized so it's one place to change (and so a
 * future recommendation-service consumer can be kept in sync by reading the same value).
 */
@Validated
@ConfigurationProperties(prefix = "app.kafka.topics")
public record KafkaTopicsProperties(
        @NotBlank String userActivity
) {
}
