package com.movies.recommendation.event;

/**
 * Mirrors catalog-service's {@code com.movies.catalog.event.MovieEventType} — the three
 * lifecycle events catalog-service publishes. Duplicated here rather than shared through a
 * common module: the event contract is deliberately treated as a cross-service wire interface
 * (JSON in, JSON out), not a shared Java dependency each service would otherwise have to
 * version together. See docs/adr/0001's "Consequences" section.
 */
public enum MovieEventType {
    CREATED,
    UPDATED,
    DELETED
}
