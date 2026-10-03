package com.movies.catalog.controller;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.catalog.dto.CreateMovieRequest;
import com.movies.catalog.dto.DeleteResponse;
import com.movies.catalog.dto.MovieFilterQuery;
import com.movies.catalog.dto.UpdateMovieRequest;
import com.movies.catalog.exception.ResourceNotFoundException;
import com.movies.catalog.exception.ValidationException;
import com.movies.catalog.model.Movie;
import com.movies.catalog.service.IMovieService;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Unit tests for MovieController's REST endpoints, mocking the service layer via MockMvc.
 * Single-resource endpoints return raw DTOs; GET (list) returns a Spring Data Page. Search/
 * vector-search/find-similar-movies routes live in search-service, not here — see
 * testSearchEndpointsAreGone_FallThroughToInvalidIdLookup for what those old paths do now.
 */
@WebMvcTest(MovieController.class)
@DisplayName("MovieController Unit Tests")
class MovieControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IMovieService movieService;

    private ObjectId testId;
    private Movie testMovie;
    private CreateMovieRequest createRequest;
    private UpdateMovieRequest updateRequest;

    @BeforeEach
    void setUp() {
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
    @DisplayName("GET /api/movies - Should return a page of movies")
    void testGetAllMovies_Success() throws Exception {
        // Arrange
        Page<Movie> moviePage = new PageImpl<>(Arrays.asList(testMovie));
        when(movieService.getAllMovies(any(MovieFilterQuery.class), any(Pageable.class))).thenReturn(moviePage);

        // Act & Assert
        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Test Movie"))
                .andExpect(jsonPath("$.content[0].year").value(2024));
    }

    @Test
    @DisplayName("GET /api/movies - Should handle query parameters")
    void testGetAllMovies_WithQueryParams() throws Exception {
        // Arrange
        Page<Movie> moviePage = new PageImpl<>(Arrays.asList(testMovie));
        when(movieService.getAllMovies(any(MovieFilterQuery.class), any(Pageable.class))).thenReturn(moviePage);

        // Act & Assert
        mockMvc.perform(get("/api/movies")
                        .param("genre", "Action")
                        .param("year", "2024")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    // ==================== GET MOVIE BY ID TESTS ====================

    @Test
    @DisplayName("GET /api/movies/{id} - Should return movie by ID")
    void testGetMovieById_Success() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        when(movieService.getMovieById(movieId)).thenReturn(testMovie);

        // Act & Assert
        mockMvc.perform(get("/api/movies/{id}", movieId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test Movie"))
                .andExpect(jsonPath("$.year").value(2024));
    }

    @Test
    @DisplayName("GET /api/movies/{id} - Should return 404 when movie not found")
    void testGetMovieById_NotFound() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        when(movieService.getMovieById(movieId))
                .thenThrow(new ResourceNotFoundException("Movie not found"));

        // Act & Assert
        mockMvc.perform(get("/api/movies/{id}", movieId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("404 NOT_FOUND"))
                .andExpect(jsonPath("$.errorMessage").value("Movie not found"));
    }

    @Test
    @DisplayName("GET /api/movies/{id} - Should return 400 for invalid ID")
    void testGetMovieById_InvalidId() throws Exception {
        // Arrange
        String invalidId = "invalid-id";
        when(movieService.getMovieById(invalidId))
                .thenThrow(new ValidationException("Invalid movie ID format"));

        // Act & Assert
        mockMvc.perform(get("/api/movies/{id}", invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("400 BAD_REQUEST"));
    }

    // ==================== CREATE MOVIE TESTS ====================

    @Test
    @DisplayName("POST /api/movies - Should create movie successfully")
    void testCreateMovie_Success() throws Exception {
        // Arrange
        when(movieService.createMovie(any(CreateMovieRequest.class))).thenReturn(testMovie);

        // Act & Assert
        mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Movie"));
    }

    @Test
    @DisplayName("POST /api/movies - Should return 400 for validation error")
    void testCreateMovie_ValidationError() throws Exception {
        // Arrange
        when(movieService.createMovie(any(CreateMovieRequest.class)))
                .thenThrow(new ValidationException("Title is required"));

        // Act & Assert
        mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("400 BAD_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/movies - Should return 400 with field-level details when @Valid fails")
    void testCreateMovie_MethodArgumentNotValid() throws Exception {
        // Arrange: title is @NotBlank on CreateMovieRequest, so omitting it must fail
        // bean validation before the request ever reaches MovieService.
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("year", 2024);
        requestBody.put("plot", "A plot with no title");

        // Act & Assert: bean-validation failures return a raw field-to-message map, not
        // an ErrorResponseDto.
        mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Title is required"));

        verify(movieService, never()).createMovie(any(CreateMovieRequest.class));
    }

    // ==================== UPDATE MOVIE TESTS ====================

    @Test
    @DisplayName("PATCH /api/movies/{id} - Should update movie successfully")
    void testUpdateMovie_Success() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        Movie updatedMovie = Movie.builder()
                .id(testId)
                .title("Updated Title")
                .year(2025)
                .build();

        when(movieService.updateMovie(eq(movieId), any(UpdateMovieRequest.class)))
                .thenReturn(updatedMovie);

        // Act & Assert
        mockMvc.perform(patch("/api/movies/{id}", movieId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.year").value(2025));
    }

    @Test
    @DisplayName("PATCH /api/movies/{id} - Should return 404 when movie not found")
    void testUpdateMovie_NotFound() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        when(movieService.updateMovie(eq(movieId), any(UpdateMovieRequest.class)))
                .thenThrow(new ResourceNotFoundException("Movie not found"));

        // Act & Assert
        mockMvc.perform(patch("/api/movies/{id}", movieId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("404 NOT_FOUND"));
    }

    // ==================== DELETE MOVIE TESTS ====================

    @Test
    @DisplayName("DELETE /api/movies/{id} - Should delete movie successfully")
    void testDeleteMovie_Success() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        DeleteResponse response = new DeleteResponse(1L);

        when(movieService.deleteMovie(movieId)).thenReturn(response);

        // Act & Assert
        mockMvc.perform(delete("/api/movies/{id}", movieId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1));
    }

    @Test
    @DisplayName("DELETE /api/movies/{id} - Should return 404 when movie not found")
    void testDeleteMovie_NotFound() throws Exception {
        // Arrange
        String movieId = testId.toHexString();
        when(movieService.deleteMovie(movieId))
                .thenThrow(new ResourceNotFoundException("Movie not found"));

        // Act & Assert
        mockMvc.perform(delete("/api/movies/{id}", movieId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("404 NOT_FOUND"));
    }

    // ==================== SEARCH/VECTOR-SEARCH SPLIT ====================

    /**
     * There's no @GetMapping("/search") (etc.) left in this controller — those routes moved
     * to search-service. Spring MVC doesn't 404 a decommissioned literal segment on its own,
     * though: with no more specific mapping to win, "/api/movies/search" falls through to
     * the "/{id}" handler with id="search". Since none of these old path names are valid
     * 24-char ObjectId hex, that always ends in a 400 from MovieServiceImpl's own ID
     * validation — never a 200 with made-up data. That's the actual, verifiable contract
     * (a real client would reach search-service via api-gateway's routing before ever
     * hitting catalog-service with one of these paths).
     */
    @Test
    @DisplayName("Search, vector-search, and find-similar-movies routes fall through to /{id} and 400, not 200")
    void testSearchEndpointsAreGone_FallThroughToInvalidIdLookup() throws Exception {
        for (String decommissionedPath : List.of("search", "vector-search", "find-similar-movies")) {
            when(movieService.getMovieById(decommissionedPath))
                    .thenThrow(new ValidationException("Invalid movie ID format"));

            mockMvc.perform(get("/api/movies/{id}", decommissionedPath))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("400 BAD_REQUEST"));
        }
    }
}
