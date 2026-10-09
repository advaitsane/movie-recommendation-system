package com.movies.recommendation.backfill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.movies.recommendation.event.MovieMetadataPayload;
import java.util.List;

/**
 * One page of catalog-service's {@code GET /api/movies}, which Spring Data serializes in its
 * {@code VIA_DTO} shape: {@code {"content": [...], "page": {...}}}. The movies reuse
 * {@link MovieMetadataPayload}, since the REST response and the Kafka event payload are the
 * same {@code MovieResponse} shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogPage(List<MovieMetadataPayload> content, PageMetadata page) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageMetadata(int size, int number, long totalElements, int totalPages) {
    }
}
