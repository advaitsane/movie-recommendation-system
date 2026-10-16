package com.movies.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A movie as search-service returns it (its {@code MovieSearchResult}). Only the fields the tools
 * pass on to the model are mapped. The annotations are Jackson 3's too: Jackson 3 kept them in
 * {@code com.fasterxml.jackson.annotation}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchMovie(
        @JsonProperty("_id") String id,
        String title,
        Integer year,
        String plot,
        String fullplot,
        List<String> genres,
        List<String> directors,
        List<String> cast,
        String rated,
        Double imdbRating) {
}
