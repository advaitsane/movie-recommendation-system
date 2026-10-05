package com.movies.recommendation.consumer;

import com.movies.recommendation.event.MovieEvent;
import com.movies.recommendation.event.MovieMetadataPayload;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.repository.MovieMetadataRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds recommendation-service's local copy of movie genre/title/poster metadata from
 * catalog-service's movie.* events, the same CQRS-read-model pattern search-service's
 * {@code MovieEventConsumer} established. Idempotent and out-of-order-tolerant: upsert-by-id
 * plus {@link #isStale} rejects an event older than what's already stored.
 */
@Component
public class MovieMetadataConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MovieMetadataConsumer.class);

    private final MovieMetadataRepository repository;

    public MovieMetadataConsumer(MovieMetadataRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(
            topics = {
                "${app.kafka.topics.movie-created}",
                "${app.kafka.topics.movie-updated}",
                "${app.kafka.topics.movie-deleted}"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "movieEventKafkaListenerContainerFactory"
    )
    public void onMovieEvent(MovieEvent event) {
        logger.debug("Received {} event for movie {}", event.eventType(), event.movieId());

        switch (event.eventType()) {
            case CREATED, UPDATED -> upsert(event);
            case DELETED -> delete(event);
        }
    }

    private void upsert(MovieEvent event) {
        if (event.movie() == null) {
            logger.warn("{} event for movie {} carried no movie payload — skipping", event.eventType(), event.movieId());
            return;
        }

        Optional<MovieMetadata> existing = repository.findById(event.movieId());
        if (existing.isPresent() && isStale(event, existing.get())) {
            logger.debug("Dropping stale {} event for movie {}", event.eventType(), event.movieId());
            return;
        }

        MovieMetadataPayload payload = event.movie();
        MovieMetadata metadata = MovieMetadata.builder()
                .id(event.movieId())
                .title(payload.title())
                .year(payload.year())
                .poster(payload.poster())
                .genres(payload.genres())
                .lastEventId(event.eventId())
                .lastEventAt(event.occurredAt())
                .build();

        repository.save(metadata);
        logger.debug("Indexed {} event for movie {}", event.eventType(), event.movieId());
    }

    private void delete(MovieEvent event) {
        Optional<MovieMetadata> existing = repository.findById(event.movieId());
        if (existing.isEmpty()) {
            logger.debug("DELETED event for movie {} — already absent, nothing to do", event.movieId());
            return;
        }
        if (isStale(event, existing.get())) {
            logger.debug("Dropping stale DELETED event for movie {}", event.movieId());
            return;
        }

        repository.deleteById(event.movieId());
        logger.debug("Removed movie {} from movie_metadata", event.movieId());
    }

    private boolean isStale(MovieEvent event, MovieMetadata existing) {
        return existing.getLastEventAt() != null && !event.occurredAt().isAfter(existing.getLastEventAt());
    }
}
