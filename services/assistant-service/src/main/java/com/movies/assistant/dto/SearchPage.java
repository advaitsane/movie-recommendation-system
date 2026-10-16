package com.movies.assistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * search-service's paged text-search response. Only {@code content} is read; the page metadata
 * doesn't matter to the model, which only ever sees the first page.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchPage(List<SearchMovie> content) {
}
