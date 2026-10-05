package com.movies.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.recommendation.event.MovieEvent;
import com.movies.recommendation.event.MovieEventType;
import com.movies.recommendation.event.MovieMetadataPayload;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.repository.MovieMetadataRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies MovieMetadataConsumer builds/maintains recommendation-service's local movie_metadata
 * read model from catalog-service's movie.* events — idempotent upsert, stale-event rejection,
 * and real deletes (unlike RatingEventConsumer's inherited review-delete gap).
 */
@DisplayName("MovieMetadataConsumer Unit Tests")
class MovieMetadataConsumerTest {

    private MovieMetadataRepository repository;
    private MovieMetadataConsumer consumer;

    private MovieMetadataPayload payload;

    @BeforeEach
    void setUp() {
        repository = mock(MovieMetadataRepository.class);
        consumer = new MovieMetadataConsumer(repository);

        payload = new MovieMetadataPayload("m1", "Test Movie", 2024, "http://poster", List.of("Action", "Drama"));
    }

    @Test
    @DisplayName("CREATED event with no existing document upserts a new one")
    void created_newDocument_upserts() {
        when(repository.findById("m1")).thenReturn(Optional.empty());

        MovieEvent event = new MovieEvent(UUID.randomUUID().toString(), MovieEventType.CREATED, "m1", Instant.now(), payload);
        consumer.onMovieEvent(event);

        ArgumentCaptor<MovieMetadata> captor = ArgumentCaptor.forClass(MovieMetadata.class);
        verify(repository).save(captor.capture());
        MovieMetadata saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("m1");
        assertThat(saved.getTitle()).isEqualTo("Test Movie");
        assertThat(saved.getGenres()).containsExactly("Action", "Drama");
        assertThat(saved.getLastEventId()).isEqualTo(event.eventId());
    }

    @Test
    @DisplayName("CREATED/UPDATED event with no movie payload is skipped, not saved")
    void upsert_nullPayload_skipped() {
        MovieEvent event = new MovieEvent(UUID.randomUUID().toString(), MovieEventType.CREATED, "m1", Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository, never()).save(any(MovieMetadata.class));
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("UPDATED event older than the stored document is dropped as stale")
    void updated_olderThanExisting_dropped() {
        Instant now = Instant.now();
        MovieMetadata existing = MovieMetadata.builder().id("m1").title("Newer Title").lastEventAt(now).build();
        when(repository.findById("m1")).thenReturn(Optional.of(existing));

        MovieEvent staleEvent = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.UPDATED, "m1", now.minus(1, ChronoUnit.HOURS), payload);
        consumer.onMovieEvent(staleEvent);

        verify(repository, never()).save(any(MovieMetadata.class));
    }

    @Test
    @DisplayName("DELETED event removes an existing document")
    void deleted_removesExisting() {
        MovieMetadata existing = MovieMetadata.builder()
                .id("m1").title("Test Movie").lastEventAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(repository.findById("m1")).thenReturn(Optional.of(existing));

        MovieEvent event = new MovieEvent(UUID.randomUUID().toString(), MovieEventType.DELETED, "m1", Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository).deleteById("m1");
    }

    @Test
    @DisplayName("DELETED event for an id not present in the store is a no-op")
    void deleted_alreadyAbsent_noOp() {
        when(repository.findById("m1")).thenReturn(Optional.empty());

        MovieEvent event = new MovieEvent(UUID.randomUUID().toString(), MovieEventType.DELETED, "m1", Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository, never()).deleteById(any());
    }
}
