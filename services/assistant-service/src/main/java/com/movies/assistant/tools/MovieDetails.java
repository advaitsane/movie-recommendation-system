package com.movies.assistant.tools;

import com.movies.assistant.dto.SearchMovie;
import java.util.List;

/** The full description of one movie, returned by {@code getMovieDetails}. */
public record MovieDetails(
        String id,
        String title,
        Integer year,
        List<String> genres,
        List<String> directors,
        List<String> cast,
        String rated,
        Double imdbRating,
        String plot) {

    /** The first few billed actors are enough to describe a movie. */
    private static final int CAST_LIMIT = 6;

    static MovieDetails from(SearchMovie movie) {
        List<String> cast = movie.cast() == null ? null : movie.cast().stream().limit(CAST_LIMIT).toList();
        String plot = movie.fullplot() != null ? movie.fullplot() : movie.plot();
        return new MovieDetails(movie.id(), movie.title(), movie.year(), movie.genres(), movie.directors(),
                cast, movie.rated(), movie.imdbRating(), plot);
    }
}
