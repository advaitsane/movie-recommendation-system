package com.movies.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;

/**
 * One entry in a recommendation list. {@code score} is this service's own blended score
 * (normalized [0,1] combination of the content/collaborative signals, or the global average
 * rating for a {@link RecommendationSource#POPULAR} cold-start pick) — not comparable to
 * search-service's raw {@code $vectorSearch} relevance score.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record RecommendedMovie(
        String movieId,
        String title,
        Integer year,
        String poster,
        List<String> genres,
        Double score,
        RecommendationSource source) {
}
