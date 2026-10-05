package com.movies.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * recommendation-service's own view of search-service's {@code VectorSearchResult} response
 * shape, returned by {@code GET /api/movies/search/{id}/similar}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchSimilarResult(SearchMovieResult movie, Double score) {
}
