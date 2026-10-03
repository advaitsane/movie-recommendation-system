package com.movies.search.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response body from Voyage AI's {@code POST /v1/embeddings}. {@code data} is not guaranteed
 * to preserve input order per Voyage's docs, hence each entry's own {@code index}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record VoyageEmbeddingResponse(List<EmbeddingData> data, String model, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(List<Double> embedding, int index) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("total_tokens") int totalTokens) {
    }
}
