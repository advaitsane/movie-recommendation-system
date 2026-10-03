package com.movies.search.event;

import java.time.Instant;

/**
 * search-service's own view of the Kafka payload published to movie.created / movie.updated /
 * movie.deleted by catalog-service's {@code MovieEventPublisher}. Field-for-field the same
 * wire shape as catalog's {@code MovieEvent}, but with search-service's own
 * {@link MovieEventPayload} instead of catalog's {@code Movie} type — see that record's
 * Javadoc for why this is duplicated rather than shared.
 */
public record MovieEvent(
        String eventId,
        MovieEventType eventType,
        String movieId,
        Instant occurredAt,
        MovieEventPayload movie
) {
}
