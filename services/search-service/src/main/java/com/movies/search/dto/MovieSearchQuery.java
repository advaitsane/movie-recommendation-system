package com.movies.search.dto;

import lombok.Builder;

/**
 * Filter parameters for GET /api/movies/search. Pagination and sorting are Pageable's job (see
 * SearchController), not this record's — kept separate so this stays a pure Mongo-criteria
 * shape, mirroring catalog-service's MovieFilterQuery.
 */
@Builder
public record MovieSearchQuery(

    /**
     * Full-text search query. Searches title, plot, and fullplot via this service's Mongo
     * text index.
     */
    String q,

    /**
     * Filter by genre (case-insensitive partial match).
     */
    String genre,

    /**
     * Filter by exact year.
     */
    Integer year,

    /**
     * Minimum IMDB rating (inclusive).
     */
    Double minRating,

    /**
     * Maximum IMDB rating (inclusive).
     */
    Double maxRating) {
}
