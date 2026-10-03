package com.movies.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Builder;

/**
 * Request payload for creating a movie. Only title is required.
 */
@Builder
public record CreateMovieRequest (
    @NotBlank(message = "Title is required")
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
