package com.movies.search.embedding;

import java.util.List;

/**
 * Request body for OpenAI's {@code POST /v1/embeddings}. Unlike Voyage, OpenAI has no
 * query-vs-document input type distinction. {@code dimensions} is optional per OpenAI's API but
 * always sent here (non-null) so the stored/queried vector length is pinned to {@code
 * openai.embedding-dimensions} rather than drifting if OpenAI ever changes a model's default —
 * text-embedding-3-* models support shortening their native output via this param.
 */
record OpenAiEmbeddingRequest(List<String> input, String model, Integer dimensions) {
}
