package com.movies.catalog.dto;

import lombok.Builder;

/**
 * Filter parameters for GET /api/movies: exact-match genre/year and an inclusive
 * IMDB rating range.
 */
@Builder
public record MovieFilterQuery (
    String genre,
    Integer year,
    Double minRating,
    Double maxRating) {}
