package com.movies.catalog.dto;

import java.util.List;
import lombok.Builder;

/**
 * Partial update payload for an existing movie. All fields are optional; only
 * non-null fields are applied.
 */
@Builder
public record UpdateMovieRequest (
    String title,
    Integer year,
    String plot,
    String fullplot,
    List<String> genres,
    List<String> directors,
    List<String> writers,
    List<String> cast,
    List<String> countries,
    List<String> languages,
    String rated,
    Integer runtime,
    String poster) {}
