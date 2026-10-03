package com.movies.catalog.event;

/**
 * The three lifecycle events catalog-service publishes on writes to the movies
 * collection. search-service (and later recommendation-service) consume these
 * to build their own read models instead of sharing this database.
 */
public enum MovieEventType {
    CREATED,
    UPDATED,
    DELETED
}
