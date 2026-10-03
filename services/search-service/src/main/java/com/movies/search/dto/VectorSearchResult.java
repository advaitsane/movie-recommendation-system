package com.movies.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * A single hit from a $vectorSearch query — the same wire shape as a normal search result, plus
 * the Atlas Vector Search relevance score (higher is more similar; not comparable across
 * different queries or similarity functions).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record VectorSearchResult(MovieSearchResult movie, Double score) {
}
