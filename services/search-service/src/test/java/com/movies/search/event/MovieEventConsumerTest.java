package com.movies.search.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.search.embedding.EmbeddingService;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies MovieEventConsumer builds/maintains the movies_search read model correctly —
 * the actual point of build-order step 2 (this is what makes search-service a real CQRS
 * consumer of catalog-service's events, not just a stub).
 */
@DisplayName("MovieEventConsumer Unit Tests")
class MovieEventConsumerTest {

    private MovieSearchRepository repository;
    private EmbeddingService embeddingService;
    private MovieEventConsumer consumer;

    private ObjectId movieId;
    private MovieEventPayload payload;

    @BeforeEach
    void setUp() {
        repository = mock(MovieSearchRepository.class);
        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embedDocuments(any())).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        consumer = new MovieEventConsumer(repository, embeddingService);

        movieId = new ObjectId();
        payload = new MovieEventPayload(
                movieId.toHexString(),
                "Test Movie",
                2024,
                "A plot",
                "A full plot",
                "http://example.com/poster.jpg",
                List.of("Action", "Drama"),
                List.of("A Director"),
                List.of("A Writer"),
                List.of("An Actor"),
                List.of("USA"),
                List.of("English"),
                "PG-13",
                new MovieEventPayload.Imdb(8.5, 1000, 123),
                75,
                "movie"
        );
    }

    @Test
    @DisplayName("CREATED event with no existing document upserts a new one")
    void testCreated_NewDocument() {
        when(repository.findById(movieId)).thenReturn(Optional.empty());

        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.CREATED, movieId.toHexString(), Instant.now(), payload);
        consumer.onMovieEvent(event);

        ArgumentCaptor<MovieSearchDocument> captor = ArgumentCaptor.forClass(MovieSearchDocument.class);
        verify(repository).save(captor.capture());

        MovieSearchDocument saved = captor.getValue();
        assertEquals(movieId, saved.getId());
        assertEquals("Test Movie", saved.getTitle());
        assertEquals(8.5, saved.getImdbRating());
        assertEquals(event.eventId(), saved.getLastEventId());
        assertEquals(event.occurredAt(), saved.getLastEventAt());
        assertEquals(List.of(0.1, 0.2, 0.3), saved.getPlotEmbedding());
    }

    @Test
    @DisplayName("CREATED/UPDATED event with a null movie payload never calls the embedding service")
    void testUpsert_NullPayload_SkipsEmbedding() {
        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.CREATED, movieId.toHexString(), Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(embeddingService, never()).embedDocuments(any());
    }

    @Test
    @DisplayName("UPDATED event newer than the indexed document overwrites it")
    void testUpdated_NewerThanExisting_Overwrites() {
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        MovieSearchDocument existing = MovieSearchDocument.builder()
                .id(movieId).title("Old Title").lastEventAt(earlier).build();
        when(repository.findById(movieId)).thenReturn(Optional.of(existing));

        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.UPDATED, movieId.toHexString(), Instant.now(), payload);
        consumer.onMovieEvent(event);

        verify(repository).save(any(MovieSearchDocument.class));
    }

    @Test
    @DisplayName("UPDATED event older than the indexed document is dropped as stale")
    void testUpdated_OlderThanExisting_Dropped() {
        Instant now = Instant.now();
        MovieSearchDocument existing = MovieSearchDocument.builder()
                .id(movieId).title("Newer Title").lastEventAt(now).build();
        when(repository.findById(movieId)).thenReturn(Optional.of(existing));

        Instant stalerTimestamp = now.minus(1, ChronoUnit.HOURS);
        MovieEvent staleEvent = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.UPDATED, movieId.toHexString(), stalerTimestamp, payload);
        consumer.onMovieEvent(staleEvent);

        verify(repository, never()).save(any(MovieSearchDocument.class));
    }

    @Test
    @DisplayName("DELETED event removes an existing document")
    void testDeleted_RemovesExisting() {
        MovieSearchDocument existing = MovieSearchDocument.builder()
                .id(movieId).title("Test Movie").lastEventAt(Instant.now().minus(1, ChronoUnit.HOURS)).build();
        when(repository.findById(movieId)).thenReturn(Optional.of(existing));

        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.DELETED, movieId.toHexString(), Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository).deleteById(movieId);
    }

    @Test
    @DisplayName("DELETED event for an id not present in the index is a no-op")
    void testDeleted_AlreadyAbsent_NoOp() {
        when(repository.findById(movieId)).thenReturn(Optional.empty());

        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.DELETED, movieId.toHexString(), Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository, never()).deleteById(any(ObjectId.class));
    }

    @Test
    @DisplayName("CREATED/UPDATED event with a null movie payload is skipped, not saved")
    void testUpsert_NullPayload_Skipped() {
        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.CREATED, movieId.toHexString(), Instant.now(), null);
        consumer.onMovieEvent(event);

        verify(repository, never()).save(any(MovieSearchDocument.class));
        verify(repository, never()).findById(any(ObjectId.class));
    }

    @Test
    @DisplayName("Event with an invalid movie id is skipped rather than throwing")
    void testUpsert_InvalidMovieId_Skipped() {
        MovieEvent event = new MovieEvent(
                UUID.randomUUID().toString(), MovieEventType.CREATED, "not-a-valid-object-id", Instant.now(), payload);
        consumer.onMovieEvent(event);

        verify(repository, never()).save(any(MovieSearchDocument.class));
    }
}
