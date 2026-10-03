package com.movies.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.catalog.config.KafkaTopicsProperties;
import com.movies.catalog.model.Movie;
import java.util.concurrent.CompletableFuture;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Verifies MovieEventPublisher sends to the right topic, keyed by movie id, for each
 * lifecycle event — the actual point of build-order step 1 (search-service, and later
 * recommendation-service, depend on this contract to build their own read models).
 */
@DisplayName("MovieEventPublisher Unit Tests")
class MovieEventPublisherTest {

    private KafkaTemplate<String, MovieEvent> kafkaTemplate;
    private MovieEventPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(String.class), any(String.class), any(MovieEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        KafkaTopicsProperties topics = new KafkaTopicsProperties("movie.created", "movie.updated", "movie.deleted");
        publisher = new MovieEventPublisher(kafkaTemplate, topics);
    }

    @Test
    @DisplayName("publishCreated sends a CREATED event to movie.created, keyed by movie id")
    void testPublishCreated() {
        Movie movie = Movie.builder().id(new ObjectId()).title("Test Movie").build();

        publisher.publishCreated(movie);

        verify(kafkaTemplate).send(eq("movie.created"), eq(movie.getId().toHexString()), argThatMovieEvent(
                event -> event.eventType() == MovieEventType.CREATED
                        && event.movieId().equals(movie.getId().toHexString())
                        && event.movie() == movie
        ));
    }

    @Test
    @DisplayName("publishUpdated sends an UPDATED event to movie.updated, keyed by movie id")
    void testPublishUpdated() {
        Movie movie = Movie.builder().id(new ObjectId()).title("Test Movie").build();

        publisher.publishUpdated(movie);

        verify(kafkaTemplate).send(eq("movie.updated"), eq(movie.getId().toHexString()), argThatMovieEvent(
                event -> event.eventType() == MovieEventType.UPDATED
                        && event.movieId().equals(movie.getId().toHexString())
        ));
    }

    @Test
    @DisplayName("publishDeleted sends a DELETED event to movie.deleted with no movie payload")
    void testPublishDeleted() {
        String movieId = new ObjectId().toHexString();

        publisher.publishDeleted(movieId);

        verify(kafkaTemplate).send(eq("movie.deleted"), eq(movieId), argThatMovieEvent(
                event -> event.eventType() == MovieEventType.DELETED
                        && event.movieId().equals(movieId)
                        && event.movie() == null
        ));
    }

    private static MovieEvent argThatMovieEvent(java.util.function.Predicate<MovieEvent> predicate) {
        return org.mockito.ArgumentMatchers.argThat(event -> {
            boolean matches = predicate.test(event);
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.occurredAt()).isNotNull();
            return matches;
        });
    }
}
