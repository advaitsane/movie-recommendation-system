package com.movies.search.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.movies.search.config.VectorSearchProperties;
import com.movies.search.dto.MovieSearchQuery;
import com.movies.search.embedding.EmbeddingService;
import com.movies.search.exception.ResourceNotFoundException;
import com.movies.search.exception.ServiceUnavailableException;
import com.movies.search.exception.ValidationException;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.repository.MovieSearchRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService Unit Tests")
class SearchServiceImplTest {

    @Mock
    private MovieSearchRepository repository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private EmbeddingService embeddingService;

    private SearchServiceImpl searchService;

    private ObjectId testId;
    private MovieSearchDocument testDocument;

    @BeforeEach
    void setUp() {
        // Real (not mocked) circuit breaker: in its default CLOSED state, executeSupplier just
        // runs the supplier, so these unit tests exercise the actual wrapping behavior rather
        // than stubbing it away. Mirrors catalog-service's MovieServiceImplTest.
        searchService = new SearchServiceImpl(
                repository, mongoTemplate, embeddingService, new VectorSearchProperties("movie_vector_index"),
                CircuitBreaker.ofDefaults("test-mongo-circuit-breaker"));

        testId = new ObjectId();
        testDocument = MovieSearchDocument.builder()
                .id(testId)
                .title("Test Movie")
                .year(2024)
                .genres(Arrays.asList("Action", "Drama"))
                .build();
    }

    @Test
    @DisplayName("Should search movies with default pagination")
    void testSearchMovies_WithDefaults() {
        MovieSearchQuery query = MovieSearchQuery.builder().build();
        Pageable pageable = PageRequest.of(0, 20);
        when(mongoTemplate.find(any(Query.class), eq(MovieSearchDocument.class)))
                .thenReturn(List.of(testDocument));

        Page<MovieSearchDocument> result = searchService.searchMovies(query, pageable);

        // Content size (1) is under the page size (20), so PageableExecutionUtils infers the
        // total from the content itself — no separate count() call needed.
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals("Test Movie", result.getContent().get(0).getTitle());
        verify(mongoTemplate).find(any(Query.class), eq(MovieSearchDocument.class));
        verify(mongoTemplate, never()).count(any(Query.class), eq(MovieSearchDocument.class));
    }

    @Test
    @DisplayName("Should query the total count when a page is completely full")
    void testSearchMovies_QueriesCountWhenPageIsFull() {
        // A full first page means there might be more results, so PageableExecutionUtils falls
        // back to an explicit count() call.
        MovieSearchQuery query = MovieSearchQuery.builder().build();
        Pageable pageable = PageRequest.of(0, 1);
        when(mongoTemplate.find(any(Query.class), eq(MovieSearchDocument.class)))
                .thenReturn(List.of(testDocument));
        when(mongoTemplate.count(any(Query.class), eq(MovieSearchDocument.class))).thenReturn(5L);

        Page<MovieSearchDocument> result = searchService.searchMovies(query, pageable);

        assertEquals(5L, result.getTotalElements());
        verify(mongoTemplate).count(any(Query.class), eq(MovieSearchDocument.class));
    }

    @Test
    @DisplayName("Should get movie by valid ID")
    void testGetById_ValidId() {
        String validId = testId.toHexString();
        when(repository.findById(testId)).thenReturn(Optional.of(testDocument));

        MovieSearchDocument result = searchService.getById(validId);

        assertNotNull(result);
        assertEquals("Test Movie", result.getTitle());
        verify(repository).findById(testId);
    }

    @Test
    @DisplayName("Should throw ValidationException for invalid ID format")
    void testGetById_InvalidIdFormat() {
        String invalidId = "invalid-id";

        assertThrows(ValidationException.class, () -> searchService.getById(invalidId));
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when movie not indexed")
    void testGetById_NotFound() {
        String validId = testId.toHexString();
        when(repository.findById(testId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> searchService.getById(validId));
        verify(repository).findById(testId);
    }

    @Test
    @DisplayName("vectorSearch should reject a blank query")
    void testVectorSearch_BlankQuery() {
        assertThrows(ValidationException.class, () -> searchService.vectorSearch("   ", null, 10));
        verify(embeddingService, never()).embedQuery(any());
    }

    @Test
    @DisplayName("vectorSearch should report 503 when Voyage isn't configured")
    void testVectorSearch_EmbeddingServiceDisabled() {
        when(embeddingService.isEnabled()).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> searchService.vectorSearch("space adventure", null, 10));
        verify(embeddingService, never()).embedQuery(any());
    }

    @Test
    @DisplayName("vectorSearch should report 503 when Voyage is configured but the embedding call fails")
    void testVectorSearch_EmbeddingCallFails() {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.embedQuery("space adventure")).thenReturn(Optional.empty());

        assertThrows(ServiceUnavailableException.class, () -> searchService.vectorSearch("space adventure", null, 10));
    }

    @Test
    @DisplayName("vectorSearch should map a genre to its stored spelling and pre-filter on it")
    @SuppressWarnings("unchecked")
    void testVectorSearch_GenreFilter() {
        when(mongoTemplate.findDistinct(any(Query.class), eq("genres"), eq(MovieSearchDocument.class), eq(String.class)))
                .thenReturn(List.of("Documentary", "Sci-Fi"));
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.embedQuery("first contact")).thenReturn(Optional.of(List.of(0.1, 0.2)));
        MongoCollection<Document> collection = mock(MongoCollection.class);
        AggregateIterable<Document> results = mock(AggregateIterable.class);
        when(mongoTemplate.getCollection("movies_search")).thenReturn(collection);
        when(collection.aggregate(any(List.class))).thenReturn(results);
        when(results.into(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(searchService.vectorSearch("first contact", "sci-fi", 5)).isEmpty();

        ArgumentCaptor<List<Bson>> pipeline = ArgumentCaptor.forClass(List.class);
        verify(collection).aggregate(pipeline.capture());
        BsonDocument stage = pipeline.getValue().getFirst().toBsonDocument().getDocument("$vectorSearch");
        assertEquals(BsonDocument.parse("{\"genres\": \"Sci-Fi\"}"), stage.getDocument("filter"));
    }

    @Test
    @DisplayName("vectorSearch should reject an unknown genre, listing the known ones, before embedding")
    void testVectorSearch_UnknownGenre() {
        when(mongoTemplate.findDistinct(any(Query.class), eq("genres"), eq(MovieSearchDocument.class), eq(String.class)))
                .thenReturn(List.of("Sci-Fi", "Drama"));
        when(embeddingService.isEnabled()).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> searchService.vectorSearch("first contact", "Science Fiction", 5));
        assertEquals("Unknown genre 'Science Fiction'; known genres: Drama, Sci-Fi", ex.getMessage());
        verify(embeddingService, never()).embedQuery(any());
    }

    @Test
    @DisplayName("findSimilar should reject an invalid movie ID")
    void testFindSimilar_InvalidIdFormat() {
        assertThrows(ValidationException.class, () -> searchService.findSimilar("invalid-id", 10));
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("findSimilar should throw ResourceNotFoundException when the movie isn't indexed")
    void testFindSimilar_NotFound() {
        String validId = testId.toHexString();
        when(repository.findById(testId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> searchService.findSimilar(validId, 10));
    }

    @Test
    @DisplayName("findSimilar should reject a movie with no stored embedding")
    void testFindSimilar_NoStoredEmbedding() {
        String validId = testId.toHexString();
        when(repository.findById(testId)).thenReturn(Optional.of(testDocument));

        assertThrows(ValidationException.class, () -> searchService.findSimilar(validId, 10));
    }
}
