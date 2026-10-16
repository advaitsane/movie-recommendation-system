package com.movies.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One result of search-service's vector search or similar-movies endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VectorHit(SearchMovie movie, Double score) {
}
