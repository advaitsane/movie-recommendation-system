package com.movies.assistant.tools;

import com.movies.assistant.dto.SearchMovie;
import java.util.List;

/**
 * What a list-returning tool hands the model for each movie: enough to choose and describe it,
 * and the id the other tools take. Cast, directors and the full plot are left to
 * {@code getMovieDetails}, since every field here is paid for as input tokens.
 */
public record MovieSummary(
        String id,
        String title,
        Integer year,
        List<String> genres,
        Double imdbRating,
        String plot) {

    static MovieSummary from(SearchMovie movie) {
        return new MovieSummary(
                movie.id(), movie.title(), movie.year(), movie.genres(), movie.imdbRating(), movie.plot());
    }
}
