package com.movies.catalog.event;

import com.movies.catalog.model.Movie;
import java.time.Instant;

/**
 * Kafka payload published to movie.created / movie.updated / movie.deleted. eventId lets
 * consumers dedupe under at-least-once delivery. movie carries the full document on
 * create/update and is null on delete, where only the id is needed.
 */
public record MovieEvent(
        String eventId,
        MovieEventType eventType,
        String movieId,
        Instant occurredAt,
        Movie movie
) {
}
