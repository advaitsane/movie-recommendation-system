package com.movies.review.event;

/**
 * The two events review-service publishes. Unlike catalog-service, deletes publish nothing
 * in this v1 (documented limitation — see {@code ReviewServiceImpl#deleteReview}), and a
 * text-only edit (rating unchanged) also publishes nothing, since recommendation-service's
 * stated use case only consumes the numeric rating signal.
 */
public enum ReviewEventType {
    REVIEW_CREATED,
    RATING_UPDATED
}
