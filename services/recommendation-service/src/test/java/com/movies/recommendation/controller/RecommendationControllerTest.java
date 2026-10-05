package com.movies.recommendation.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movies.recommendation.dto.RecommendationSource;
import com.movies.recommendation.dto.RecommendedMovie;
import com.movies.recommendation.service.IRecommendationService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RecommendationController.class)
@DisplayName("RecommendationController Unit Tests")
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IRecommendationService recommendationService;

    @Test
    @DisplayName("GET /api/recommendations/{userId} - returns the blended recommendation list")
    void getRecommendations_success() throws Exception {
        RecommendedMovie movie = RecommendedMovie.builder()
                .movieId("m1").title("Test Movie").year(2024).genres(List.of("Drama"))
                .score(0.87).source(RecommendationSource.BOTH).build();
        when(recommendationService.getRecommendations("u1", null)).thenReturn(List.of(movie));

        mockMvc.perform(get("/api/recommendations/{userId}", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].movieId").value("m1"))
                .andExpect(jsonPath("$[0].source").value("BOTH"));
    }

    @Test
    @DisplayName("GET /api/recommendations/{userId}?limit=5 - passes the limit through")
    void getRecommendations_withLimit_passesThrough() throws Exception {
        when(recommendationService.getRecommendations("u1", 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/recommendations/{userId}", "u1").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/recommendations/{userId} - an unexpected service error becomes a 500 without leaking its message")
    void getRecommendations_serviceError_returns500() throws Exception {
        when(recommendationService.getRecommendations("u1", null))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get("/api/recommendations/{userId}", "u1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorMessage").value("Internal server error"));
    }
}
