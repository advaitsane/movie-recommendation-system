package com.movies.catalog.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import com.movies.catalog.dto.CreateMovieRequest;
import com.movies.catalog.dto.DeleteResponse;
import com.movies.catalog.dto.MovieFilterQuery;
import com.movies.catalog.dto.UpdateMovieRequest;
import com.movies.catalog.event.MovieEventPublisher;
import com.movies.catalog.exception.ResourceNotFoundException;
import com.movies.catalog.exception.ValidationException;
import com.movies.catalog.model.Movie;
import com.movies.catalog.repository.MovieRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Unit tests for MovieServiceImpl, mocking the repository, MongoTemplate, and event
 * publisher dependencies. Search/vector-search endpoints are covered in search-service's
 * own tests, not here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MovieService Unit Tests")
class MovieServiceImplTest {

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private MovieEventPublisher eventPublisher;

    private MovieServiceImpl movieService;

    private ObjectId testId;
    private Movie testMovie;
    private CreateMovieRequest createRequest;
    private UpdateMovieRequest updateRequest;

    @BeforeEach
    void setUp() {
        // A real (not mocked) circuit breaker with default config, always CLOSED — lets calls
        // through unchanged so these tests exercise MovieServiceImpl's own logic, not
        // resilience4j's state machine (that belongs in its own test, if/when added).
        movieService = new MovieServiceImpl(movieRepository, mongoTemplate, objectMapper,
                eventPublisher, CircuitBreaker.ofDefaults("test-mongo-circuit-breaker"));

        testId = new ObjectId();

        testMovie = Movie.builder()
                .id(testId)
                .title("Test Movie")
                .year(2024)
                .plot("A test plot")
                .genres(Arrays.asList("Action", "Drama"))
                .build();

        createRequest = CreateMovieRequest.builder()
                .title("New Movie")
                .year(2024)
                .plot("A new movie plot")
                .build();

        updateRequest = UpdateMovieRequest.builder()
                .title("Updated Title")
                .year(2025)
                .build();
    }

    // ==================== GET ALL MOVIES TESTS ====================

    @Test
    @DisplayName("Should get all movies with default pagination")
    void testGetAllMovies_WithDefaults() {
        // Arrange
        MovieFilterQuery query = MovieFilterQuery.builder().build();
        Pageable pageable = PageRequest.of(0, 20);
        Document doc = new Document("_id", testId);
        MongoConverter converter = mock(MongoConverter.class);

        when(mongoTemplate.getCollectionName(Movie.class)).thenReturn("movies");
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("movies")))
                .thenReturn(List.of(doc));
        when(mongoTemplate.getConverter()).thenReturn(converter);
        when(converter.read(eq(Movie.class), eq(doc))).thenReturn(testMovie);

        // Act
        Page<Movie> result = movieService.getAllMovies(query, pageable);

        // Assert: content size (1) is under the page size (20), so PageableExecutionUtils
        // infers the total from the content itself — no separate count() call needed.
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(testMovie.getTitle(), result.getContent().get(0).getTitle());
        verify(mongoTemplate).find(any(Query.class), eq(Document.class), eq("movies"));
        verify(mongoTemplate, never()).count(any(Query.class), eq(Movie.class));
    }

    @Test
    @DisplayName("Should get all movies with a custom page and size")
    void testGetAllMovies_WithCustomPagination() {
        // Arrange
        MovieFilterQuery query = MovieFilterQuery.builder().build();
        Pageable pageable = PageRequest.of(2, 50);
        Document doc = new Document("_id", testId);
        MongoConverter converter = mock(MongoConverter.class);

        when(mongoTemplate.getCollectionName(Movie.class)).thenReturn("movies");
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("movies")))
                .thenReturn(List.of(doc));
        when(mongoTemplate.getConverter()).thenReturn(converter);
        when(converter.read(eq(Movie.class), eq(doc))).thenReturn(testMovie);

        // Act
        Page<Movie> result = movieService.getAllMovies(query, pageable);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(2, result.getNumber());
        verify(mongoTemplate).find(any(Query.class), eq(Document.class), eq("movies"));
    }

    @Test
    @DisplayName("Should query the total count when a page is completely full")
    void testGetAllMovies_QueriesCountWhenPageIsFull() {
        // Arrange: a full first page means there might be more results, so
        // PageableExecutionUtils falls back to an explicit count() call.
        MovieFilterQuery query = MovieFilterQuery.builder().build();
        Pageable pageable = PageRequest.of(0, 1);
        Document doc = new Document("_id", testId);
        MongoConverter converter = mock(MongoConverter.class);

        when(mongoTemplate.getCollectionName(Movie.class)).thenReturn("movies");
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("movies")))
                .thenReturn(List.of(doc));
        when(mongoTemplate.getConverter()).thenReturn(converter);
        when(converter.read(eq(Movie.class), eq(doc))).thenReturn(testMovie);
        when(mongoTemplate.count(any(Query.class), eq(Movie.class))).thenReturn(5L);

        // Act
        Page<Movie> result = movieService.getAllMovies(query, pageable);

        // Assert
        assertEquals(5L, result.getTotalElements());
        verify(mongoTemplate).count(any(Query.class), eq(Movie.class));
    }

    @Test
    @DisplayName("Should skip a malformed movie document and still return the rest")
    void testGetAllMovies_SkipsMalformedDocument() {
        // Arrange
        MovieFilterQuery query = MovieFilterQuery.builder().build();
        Pageable pageable = PageRequest.of(0, 20);
        Document goodDoc = new Document("_id", testId);
        Document malformedDoc = new Document("_id", new ObjectId());
        MongoConverter converter = mock(MongoConverter.class);

        when(mongoTemplate.getCollectionName(Movie.class)).thenReturn("movies");
        when(mongoTemplate.find(any(Query.class), eq(Document.class), eq("movies")))
                .thenReturn(List.of(malformedDoc, goodDoc));
        when(mongoTemplate.getConverter()).thenReturn(converter);
        when(converter.read(eq(Movie.class), eq(malformedDoc)))
                .thenThrow(new org.springframework.core.convert.ConversionFailedException(
                        org.springframework.core.convert.TypeDescriptor.valueOf(String.class),
                        org.springframework.core.convert.TypeDescriptor.valueOf(Integer.class),
                        "1986è", new NumberFormatException("For input string: \"1986è\"")));
        when(converter.read(eq(Movie.class), eq(goodDoc))).thenReturn(testMovie);

        // Act
        Page<Movie> result = movieService.getAllMovies(query, pageable);

        // Assert: the malformed document is skipped, not thrown, and the good one still comes back
        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals(testMovie.getTitle(), result.getContent().get(0).getTitle());
    }

    // ==================== GET MOVIE BY ID TESTS ====================

    @Test
    @DisplayName("Should get movie by valid ID")
    void testGetMovieById_ValidId() {
        // Arrange
        String validId = testId.toHexString();
        when(movieRepository.findById(testId)).thenReturn(Optional.of(testMovie));

        // Act
        Movie result = movieService.getMovieById(validId);

        // Assert
        assertNotNull(result);
        assertEquals(testMovie.getTitle(), result.getTitle());
        verify(movieRepository).findById(testId);
    }

    @Test
    @DisplayName("Should throw ValidationException for invalid ID format")
    void testGetMovieById_InvalidIdFormat() {
        // Arrange
        String invalidId = "invalid-id";

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.getMovieById(invalidId));
        verify(movieRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when movie not found")
    void testGetMovieById_NotFound() {
        // Arrange
        String validId = testId.toHexString();
        when(movieRepository.findById(testId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> movieService.getMovieById(validId));
        verify(movieRepository).findById(testId);
    }

    // ==================== CREATE MOVIE TESTS ====================

    @Test
    @DisplayName("Should create movie successfully and publish a created event")
    void testCreateMovie_Success() {
        // Arrange
        when(movieRepository.save(any(Movie.class))).thenReturn(testMovie);

        // Act
        Movie result = movieService.createMovie(createRequest);

        // Assert
        assertNotNull(result);
        verify(movieRepository).save(any(Movie.class));
        verify(eventPublisher).publishCreated(testMovie);
    }

    @Test
    @DisplayName("Should throw ValidationException when title is null")
    void testCreateMovie_NullTitle() {
        // Arrange
        CreateMovieRequest invalidRequest = CreateMovieRequest.builder()
                .title(null)
                .year(2024)
                .build();

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.createMovie(invalidRequest));
        verify(movieRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("Should throw ValidationException when title is empty")
    void testCreateMovie_EmptyTitle() {
        // Arrange
        CreateMovieRequest invalidRequest = CreateMovieRequest.builder()
                .title("   ")
                .year(2024)
                .build();

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.createMovie(invalidRequest));
        verify(movieRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // ==================== UPDATE MOVIE TESTS ====================

    @Test
    @DisplayName("Should update movie successfully and publish an updated event")
    void testUpdateMovie_Success() {
        // Arrange
        String validId = testId.toHexString();
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("title", "Updated Title");
        requestMap.put("year", 2025);

        when(objectMapper.convertValue(updateRequest, Map.class)).thenReturn(requestMap);

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class)))
                .thenReturn(updateResult);
        when(movieRepository.findById(testId)).thenReturn(Optional.of(testMovie));

        // Act
        Movie result = movieService.updateMovie(validId, updateRequest);

        // Assert
        assertNotNull(result);
        verify(mongoTemplate).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class));
        verify(movieRepository).findById(testId);
        verify(eventPublisher).publishUpdated(testMovie);
    }

    @Test
    @DisplayName("Should throw ValidationException for invalid ID in update")
    void testUpdateMovie_InvalidId() {
        // Arrange
        String invalidId = "invalid-id";

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.updateMovie(invalidId, updateRequest));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class));
    }

    @Test
    @DisplayName("Should throw ValidationException when update request is empty")
    void testUpdateMovie_EmptyRequest() {
        // Arrange
        String validId = testId.toHexString();
        UpdateMovieRequest emptyRequest = UpdateMovieRequest.builder().build();
        Map<String, Object> emptyMap = new HashMap<>();

        when(objectMapper.convertValue(emptyRequest, Map.class)).thenReturn(emptyMap);

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.updateMovie(validId, emptyRequest));
        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when movie to update not found")
    void testUpdateMovie_NotFound() {
        // Arrange
        String validId = testId.toHexString();
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("title", "Updated Title");

        when(objectMapper.convertValue(updateRequest, Map.class)).thenReturn(requestMap);

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.getMatchedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class)))
                .thenReturn(updateResult);

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> movieService.updateMovie(validId, updateRequest));
        verify(mongoTemplate).updateFirst(any(Query.class), any(org.springframework.data.mongodb.core.query.Update.class), any(Class.class));
        verify(movieRepository, never()).findById(any());
        verifyNoInteractions(eventPublisher);
    }

    // ==================== DELETE MOVIE TESTS ====================

    @Test
    @DisplayName("Should delete movie successfully and publish a deleted event")
    void testDeleteMovie_Success() {
        // Arrange
        String validId = testId.toHexString();
        when(movieRepository.existsById(testId)).thenReturn(true);

        // Act
        DeleteResponse result = movieService.deleteMovie(validId);

        // Assert
        assertNotNull(result);
        assertEquals(1L, result.deletedCount());
        verify(movieRepository).existsById(testId);
        verify(movieRepository).deleteById(testId);
        verify(eventPublisher).publishDeleted(validId);
    }

    @Test
    @DisplayName("Should throw ValidationException for invalid ID in delete")
    void testDeleteMovie_InvalidId() {
        // Arrange
        String invalidId = "invalid-id";

        // Act & Assert
        assertThrows(ValidationException.class, () -> movieService.deleteMovie(invalidId));
        verify(movieRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when movie to delete not found")
    void testDeleteMovie_NotFound() {
        // Arrange
        String validId = testId.toHexString();
        when(movieRepository.existsById(testId)).thenReturn(false);

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> movieService.deleteMovie(validId));
        verify(movieRepository).existsById(testId);
        verify(movieRepository, never()).deleteById(any());
        verifyNoInteractions(eventPublisher);
    }
}
