package com.movies.search.event;

import com.movies.search.embedding.EmbeddingService;
import com.movies.search.embedding.EmbeddingTextBuilder;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds search-service's read model from catalog-service's movie.* events. Delivery is
 * at-least-once and not guaranteed in-order, so this consumer is idempotent (upsert-by-id) and
 * rejects stale deliveries via {@code isStale} (event {@code occurredAt} vs. stored {@code
 * lastEventAt}). Known gap: no tombstone for deletes, so a badly out-of-order create/update can
 * resurrect a deleted document.
 */
@Component
public class MovieEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(MovieEventConsumer.class);

    private final MovieSearchRepository repository;
    private final EmbeddingService embeddingService;

    public MovieEventConsumer(MovieSearchRepository repository, EmbeddingService embeddingService) {
        this.repository = repository;
        this.embeddingService = embeddingService;
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
        if (!ObjectId.isValid(event.movieId())) {
            logger.warn("{} event carried an invalid movie id '{}' — skipping", event.eventType(), event.movieId());
            return;
        }

        ObjectId id = new ObjectId(event.movieId());
        Optional<MovieSearchDocument> existing = repository.findById(id);

        if (existing.isPresent() && isStale(event, existing.get())) {
            logger.debug("Dropping stale {} event for movie {} (event occurredAt={} <= indexed lastEventAt={})",
                    event.eventType(), event.movieId(), event.occurredAt(), existing.get().getLastEventAt());
            return;
        }

        String embeddingText = EmbeddingTextBuilder.forPayload(event.movie());
        List<Double> embedding = embeddingService.embedDocuments(List.of(embeddingText)).get(0);

        MovieSearchDocument document = toDocument(id, event, embedding);
        repository.save(document);
        logger.debug("Indexed {} event for movie {}", event.eventType(), event.movieId());
    }

    private void delete(MovieEvent event) {
        if (!ObjectId.isValid(event.movieId())) {
            logger.warn("DELETED event carried an invalid movie id '{}' — skipping", event.movieId());
            return;
        }

        ObjectId id = new ObjectId(event.movieId());
        Optional<MovieSearchDocument> existing = repository.findById(id);

        if (existing.isEmpty()) {
            logger.debug("DELETED event for movie {} — already absent from movies_search, nothing to do", event.movieId());
            return;
        }
        if (isStale(event, existing.get())) {
            logger.debug("Dropping stale DELETED event for movie {}", event.movieId());
            return;
        }

        repository.deleteById(id);
        logger.debug("Removed movie {} from movies_search", event.movieId());
    }

    private boolean isStale(MovieEvent event, MovieSearchDocument existing) {
        return existing.getLastEventAt() != null && !event.occurredAt().isAfter(existing.getLastEventAt());
    }

    private MovieSearchDocument toDocument(ObjectId id, MovieEvent event, List<Double> embedding) {
        MovieEventPayload movie = event.movie();
        Double imdbRating = movie.imdb() != null ? movie.imdb().rating() : null;
        Integer imdbVotes = movie.imdb() != null ? movie.imdb().votes() : null;

        return MovieSearchDocument.builder()
                .id(id)
                .title(movie.title())
                .year(movie.year())
                .plot(movie.plot())
                .fullplot(movie.fullplot())
                .poster(movie.poster())
                .genres(movie.genres())
                .directors(movie.directors())
                .writers(movie.writers())
                .cast(movie.cast())
                .countries(movie.countries())
                .languages(movie.languages())
                .rated(movie.rated())
                .imdbRating(imdbRating)
                .imdbVotes(imdbVotes)
                .metacritic(movie.metacritic())
                .type(movie.type())
                .plotEmbedding(embedding)
                .lastEventId(event.eventId())
                .lastEventAt(event.occurredAt())
                .build();
    }
}
