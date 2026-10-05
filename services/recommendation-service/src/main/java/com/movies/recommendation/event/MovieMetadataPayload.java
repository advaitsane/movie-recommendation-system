package com.movies.recommendation.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * recommendation-service's own view of the movie payload embedded in a {@link MovieEvent} — a
 * subset of catalog-service's {@code Movie} fields, shaped for genre-vector profiles and display
 * only. {@code ignoreUnknown = true} because catalog's wire shape carries many fields (plot,
 * cast, awards, ...) this service never needs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MovieMetadataPayload(
        @JsonProperty("_id") String id,
        String title,
        Integer year,
        String poster,
        List<String> genres
) {
}
