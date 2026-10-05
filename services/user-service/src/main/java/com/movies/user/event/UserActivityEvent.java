package com.movies.user.event;

import java.time.Instant;

/**
 * Kafka payload published to {@code user.activity}. {@code eventId} is what a downstream
 * idempotent consumer (recommendation-service, eventually) would dedupe on under this repo's
 * standard at-least-once delivery convention.
 */
public record UserActivityEvent(
        String eventId,
        Long userId,
        UserActivityType activityType,
        String movieId,
        String query,
        Instant occurredAt
) {
}
