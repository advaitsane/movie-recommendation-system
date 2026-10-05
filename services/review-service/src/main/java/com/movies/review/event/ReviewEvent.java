package com.movies.review.event;

import java.time.Instant;

/**
 * Kafka payload for {@code review.created}/{@code rating.updated}, serialized into {@code
 * outbox_events.payload} at write time and republished as-is by the poller. {@code eventId} is
 * the outbox row's own {@code event_id} — what downstream consumers dedupe on under
 * at-least-once delivery. Carries the review's full current state, not a diff, so no consumer
 * needs a callback.
 */
public record ReviewEvent(
        String eventId,
        ReviewEventType eventType,
        Long reviewId,
        String userId,
        String movieId,
        Integer rating,
        String reviewText,
        Instant occurredAt
) {
}
