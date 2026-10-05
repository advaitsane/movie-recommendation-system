package com.movies.user.event;

/**
 * The kinds of activity this service records and publishes to Kafka for recommendation-service
 * (or any future consumer) to build engagement-based signals from.
 */
public enum UserActivityType {
    VIEW,
    SEARCH
}
