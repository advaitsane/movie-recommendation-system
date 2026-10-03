package com.movies.search.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Response body from OpenAI's {@code POST /v1/embeddings}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiEmbeddingResponse(List<EmbeddingData> data, String model) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(List<Double> embedding, int index) {
    }
}
