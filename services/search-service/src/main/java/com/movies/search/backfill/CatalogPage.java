package com.movies.search.backfill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.movies.search.event.MovieEventPayload;
import java.util.List;

/**
 * One page of catalog-service's {@code GET /api/movies}, which Spring Data serializes in its
 * {@code VIA_DTO} shape: {@code {"content": [...], "page": {...}}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogPage(List<MovieEventPayload> content, PageMetadata page) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageMetadata(int size, int number, long totalElements, int totalPages) {
    }
}
