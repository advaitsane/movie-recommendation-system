package com.movies.recommendation.event;

import java.time.Instant;

/**
 * recommendation-service's own view of the Kafka payload published to movie.created /
 * movie.updated / movie.deleted by catalog-service's {@code MovieEventPublisher}. Field-for-field
 * the same wire shape as catalog's {@code MovieEvent}, but with this service's own
 * {@link MovieMetadataPayload} instead of catalog's {@code Movie} type.
 */
public record MovieEvent(
        String eventId,
        MovieEventType eventType,
        String movieId,
        Instant occurredAt,
        MovieMetadataPayload movie
) {
}
