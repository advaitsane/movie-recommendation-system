package com.movies.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * recommendation-service's own view of search-service's {@code MovieSearchResult} response
 * shape — a subset of its fields (only what's needed to render a {@link RecommendedMovie}), own
 * copy rather than a shared dependency, same convention as the Kafka event records in the
 * {@code event} package. {@code @JsonIgnoreProperties(ignoreUnknown = true)} because
 * search-service's response includes many fields (plot, cast, directors, ratings, ...) this
 * service doesn't need.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchMovieResult(
        @JsonProperty("_id") String id,
        String title,
        Integer year,
        String poster,
        List<String> genres
) {
}
