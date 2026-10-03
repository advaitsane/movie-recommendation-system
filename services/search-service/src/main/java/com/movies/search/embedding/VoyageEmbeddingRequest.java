package com.movies.search.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for Voyage AI's {@code POST /v1/embeddings}. {@code inputType} must be
 * "document" when embedding movies for storage and "query" when embedding a user's search
 * text — Voyage applies asymmetric encoding for the two, which materially affects relevance.
 */
record VoyageEmbeddingRequest(
        List<String> input,
        String model,
        @JsonProperty("input_type") String inputType) {
}
