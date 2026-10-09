package com.movies.assistant.tools;

import com.movies.assistant.dto.RecommendedMovie;
import java.util.List;

/**
 * One personal recommendation as the model sees it. {@code source} says which signal picked it:
 * CONTENT (similar to movies the user rated highly), COLLABORATIVE (liked by users with similar
 * taste), BOTH, or POPULAR (the user hasn't rated anything yet).
 */
public record Recommendation(String id, String title, Integer year, List<String> genres, String source) {

    static Recommendation from(RecommendedMovie movie) {
        return new Recommendation(movie.movieId(), movie.title(), movie.year(), movie.genres(), movie.source());
    }
}
