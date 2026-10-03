package com.movies.search.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * search-service's own view of the movie payload embedded in a {@link MovieEvent} — a subset of
 * catalog-service's {@code Movie} fields, shaped for what this service's read model needs. Also
 * reused by {@code CatalogBackfillRunner} to deserialize catalog-service's REST response, since
 * it serializes the same shape. Ignores unknown fields (released, runtime, awards, tomatoes)
 * this service doesn't index.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovieEventPayload(
        @JsonProperty("_id") String id,
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
        Imdb imdb,
        Integer metacritic,
        String type
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Imdb(Double rating, Integer votes, Integer id) {
    }
}
