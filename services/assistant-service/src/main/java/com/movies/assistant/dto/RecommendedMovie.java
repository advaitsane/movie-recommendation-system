package com.movies.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One entry of recommendation-service's response. {@code source} is CONTENT, COLLABORATIVE, BOTH
 * or POPULAR; it is passed on so the model can explain why a movie was picked.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendedMovie(
        String movieId,
        String title,
        Integer year,
        List<String> genres,
        String source) {
}
