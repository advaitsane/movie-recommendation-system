package com.movies.recommendation.event;

import java.time.Instant;

/**
 * recommendation-service's own view of the Kafka payload on review.created/rating.updated —
 * duplicated rather than shared, same convention as {@link MovieEvent}. {@code eventId} is
 * unused for dedup in practice: upserting by (userId, movieId) is already idempotent since a
 * {@code RATING_UPDATED} payload carries the review's full current state, not a diff.
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
