package com.movies.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.movies.search.model.MovieSearchDocument;
import java.util.List;
import lombok.Builder;

/**
 * Wire-level shape returned by search-service's endpoints. Keeps the Mongo
 * {@link MovieSearchDocument} entity (Spring Data annotations, read-model bookkeeping fields
 * like lastEventId/lastEventAt) out of the response body; {@link #from} maps one to the other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record MovieSearchResult(
        String _id,
        String title,
        Integer year,
        String plot,
        String fullplot,
        String poster,
        List<String> genres,
        List<String> directors,
        List<String> writers,
        List<String> cast,
        List<String> countries,
        List<String> languages,
        String rated,
        Double imdbRating,
        Integer imdbVotes,
        Integer metacritic,
        String type) {

    public static MovieSearchResult from(MovieSearchDocument doc) {
        return MovieSearchResult.builder()
                ._id(doc.getId() != null ? doc.getId().toHexString() : null)
                .title(doc.getTitle())
                .year(doc.getYear())
                .plot(doc.getPlot())
                .fullplot(doc.getFullplot())
                .poster(doc.getPoster())
                .genres(doc.getGenres())
                .directors(doc.getDirectors())
                .writers(doc.getWriters())
                .cast(doc.getCast())
                .countries(doc.getCountries())
                .languages(doc.getLanguages())
                .rated(doc.getRated())
                .imdbRating(doc.getImdbRating())
                .imdbVotes(doc.getImdbVotes())
                .metacritic(doc.getMetacritic())
                .type(doc.getType())
                .build();
    }
}
