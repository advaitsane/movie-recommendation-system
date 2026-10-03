package com.movies.search.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movies.search.dto.MovieSearchQuery;
import com.movies.search.dto.MovieSearchResult;
import com.movies.search.dto.VectorSearchResult;
import com.movies.search.exception.ResourceNotFoundException;
import com.movies.search.exception.ServiceUnavailableException;
import com.movies.search.exception.ValidationException;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.service.ISearchService;
import java.util.Arrays;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
@DisplayName("SearchController Unit Tests")
class SearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ISearchService searchService;

    private ObjectId testId;
    private MovieSearchDocument testDocument;

    @BeforeEach
    void setUp() {
        testId = new ObjectId();
        testDocument = MovieSearchDocument.builder()
                .id(testId)
                .title("Test Movie")
                .year(2024)
                .genres(Arrays.asList("Action", "Drama"))
                .build();
    }

    @Test
    @DisplayName("GET /api/movies/search - Should return a page of matching movies")
    void testSearch_Success() throws Exception {
        Page<MovieSearchDocument> resultPage = new PageImpl<>(Arrays.asList(testDocument));
        when(searchService.searchMovies(any(MovieSearchQuery.class), any(Pageable.class))).thenReturn(resultPage);

        mockMvc.perform(get("/api/movies/search").param("q", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Test Movie"))
                .andExpect(jsonPath("$.content[0].year").value(2024));
    }

    @Test
    @DisplayName("GET /api/movies/search/{id} - Should return the indexed movie")
    void testGetById_Success() throws Exception {
        String id = testId.toHexString();
        when(searchService.getById(id)).thenReturn(testDocument);

        mockMvc.perform(get("/api/movies/search/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Test Movie"));
    }

    @Test
    @DisplayName("GET /api/movies/search/{id} - Should return 404 when not indexed")
    void testGetById_NotFound() throws Exception {
        String id = testId.toHexString();
        when(searchService.getById(id)).thenThrow(new ResourceNotFoundException("Movie not found in search index"));

        mockMvc.perform(get("/api/movies/search/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("404 NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/movies/search/{id} - Should return 400 for invalid ID")
    void testGetById_InvalidId() throws Exception {
        String invalidId = "invalid-id";
        when(searchService.getById(invalidId)).thenThrow(new ValidationException("Invalid movie ID format"));

        mockMvc.perform(get("/api/movies/search/{id}", invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("400 BAD_REQUEST"));
    }

    @Test
    @DisplayName("GET /api/movies/search/vector - Should return semantic search results")
    void testVectorSearch_Success() throws Exception {
        VectorSearchResult result = VectorSearchResult.builder()
                .movie(MovieSearchResult.from(testDocument))
                .score(0.92)
                .build();
        when(searchService.vectorSearch("a heist movie", 10)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/movies/search/vector").param("q", "a heist movie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movie.title").value("Test Movie"))
                .andExpect(jsonPath("$[0].score").value(0.92));
    }

    @Test
    @DisplayName("GET /api/movies/search/vector - Should return 503 when Voyage isn't configured")
    void testVectorSearch_ServiceUnavailable() throws Exception {
        when(searchService.vectorSearch("a heist movie", 10))
                .thenThrow(new ServiceUnavailableException("Vector search is unavailable: VOYAGE_API_KEY is not configured"));

        mockMvc.perform(get("/api/movies/search/vector").param("q", "a heist movie"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("503 SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET /api/movies/search/{id}/similar - Should return similar movies")
    void testFindSimilar_Success() throws Exception {
        String id = testId.toHexString();
        VectorSearchResult result = VectorSearchResult.builder()
                .movie(MovieSearchResult.from(testDocument))
                .score(0.87)
                .build();
        when(searchService.findSimilar(id, 10)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/movies/search/{id}/similar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movie.title").value("Test Movie"));
    }

    @Test
    @DisplayName("GET /api/movies/search/{id}/similar - Should return 400 when the movie has no stored embedding")
    void testFindSimilar_NoEmbedding() throws Exception {
        String id = testId.toHexString();
        when(searchService.findSimilar(id, 10))
                .thenThrow(new ValidationException("Movie " + id + " has no stored embedding yet"));

        mockMvc.perform(get("/api/movies/search/{id}/similar", id))
                .andExpect(status().isBadRequest());
    }
}
