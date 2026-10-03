package com.movies.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.movies.catalog.model.Movie;
import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/**
 * Wire-level representation of a movie returned by the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record MovieResponse (
    String _id,
    String title,
    Integer year,
    String plot,
    String fullplot,
    LocalDate released,
    Integer runtime,
    String poster,
    List<String> genres,
    List<String> directors,
    List<String> writers,
    List<String> cast,
    List<String> countries,
    List<String> languages,
    String rated,
    Movie.Awards awards,
    Movie.Imdb imdb,
    Movie.Tomatoes tomatoes,
    Integer metacritic,
    String type) {

    /**
     * Maps a Movie entity to its wire-level response DTO.
     */
    public static MovieResponse from(Movie movie) {
        return MovieResponse.builder()
                ._id(movie.getId() != null ? movie.getId().toHexString() : null)
                .title(movie.getTitle())
                .year(movie.getYear())
                .plot(movie.getPlot())
                .fullplot(movie.getFullplot())
                .released(movie.getReleased())
                .runtime(movie.getRuntime())
                .poster(movie.getPoster())
                .genres(movie.getGenres())
                .directors(movie.getDirectors())
                .writers(movie.getWriters())
                .cast(movie.getCast())
                .countries(movie.getCountries())
                .languages(movie.getLanguages())
                .rated(movie.getRated())
                .awards(movie.getAwards())
                .imdb(movie.getImdb())
                .tomatoes(movie.getTomatoes())
                .metacritic(movie.getMetacritic())
                .type(movie.getType())
                .build();
    }
}
