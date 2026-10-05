package com.movies.review.outbox;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tuning knobs for {@link OutboxPoller}, externalized rather than hardcoded so they can be
 * adjusted per-environment without a code change.
 */
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @NotNull @Positive Integer batchSize,
        @NotNull @Positive Long sendTimeoutMs,
        @NotNull @Positive Long pollDelayMs
) {
}
