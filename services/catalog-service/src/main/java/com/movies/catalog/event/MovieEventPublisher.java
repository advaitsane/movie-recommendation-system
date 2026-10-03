package com.movies.catalog.event;

import com.movies.catalog.config.KafkaTopicsProperties;
import com.movies.catalog.model.Movie;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes movie.* events after a Mongo write succeeds. Keyed by movie id so Kafka
 * preserves per-movie ordering. Delivery is at-least-once and fire-and-forget — a publish
 * failure is logged, not thrown, so a Kafka outage never fails the catalog write itself.
 */
@Component
public class MovieEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(MovieEventPublisher.class);

    private final KafkaTemplate<String, MovieEvent> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public MovieEventPublisher(KafkaTemplate<String, MovieEvent> kafkaTemplate, KafkaTopicsProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    public void publishCreated(Movie movie) {
        publish(topics.movieCreated(), MovieEventType.CREATED, movie.getId().toHexString(), movie);
    }

    public void publishUpdated(Movie movie) {
        publish(topics.movieUpdated(), MovieEventType.UPDATED, movie.getId().toHexString(), movie);
    }

    public void publishDeleted(String movieId) {
        publish(topics.movieDeleted(), MovieEventType.DELETED, movieId, null);
    }

    private void publish(String topic, MovieEventType eventType, String movieId, Movie movie) {
        MovieEvent event = new MovieEvent(UUID.randomUUID().toString(), eventType, movieId, Instant.now(), movie);

        try {
            kafkaTemplate.send(topic, movieId, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.error("Failed to publish {} event for movie {}: {}", eventType, movieId, ex.getMessage(), ex);
                } else {
                    logger.debug("Published {} event for movie {} to {}", eventType, movieId, topic);
                }
            });
        } catch (Exception ex) {
            // KafkaTemplate#send can also fail synchronously (e.g. no broker reachable within
            // the producer's bounded max.block.ms) rather than only via the returned future.
            // Catch that here too so a Kafka outage degrades this side-effect, not the catalog write.
            logger.error("Failed to publish {} event for movie {}: {}", eventType, movieId, ex.getMessage(), ex);
        }
    }
}
