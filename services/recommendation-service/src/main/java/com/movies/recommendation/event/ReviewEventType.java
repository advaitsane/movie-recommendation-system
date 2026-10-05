package com.movies.recommendation.event;

/**
 * Mirrors review-service's {@code com.movies.review.event.ReviewEventType}. review-service
 * publishes nothing on delete and nothing on a text-only edit (rating unchanged) — see that
 * enum's Javadoc — so this consumer only ever needs to handle these two values.
 */
public enum ReviewEventType {
    REVIEW_CREATED,
    RATING_UPDATED
}
