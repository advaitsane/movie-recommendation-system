package com.movies.assistant.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Base URLs and timeouts for the services the assistant's tools call. The timeouts bound how long
 * one tool call can hold up an answer; a call that fails or times out is reported to the model
 * as unavailable instead of failing the whole conversation (see MovieTools).
 */
@Validated
@ConfigurationProperties(prefix = "assistant.downstream")
public record DownstreamProperties(
        @NotBlank String searchServiceUrl,
        @NotBlank String recommendationServiceUrl,
        @Positive long connectTimeoutMs,
        @Positive long readTimeoutMs
) {
}
