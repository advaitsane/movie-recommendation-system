package com.movies.search.embedding;

import java.util.List;
import java.util.Optional;

/**
 * Provider-agnostic embedding generation for document storage and query embedding.
 * {@code embedding.provider} selects the active implementation ({@link VoyageEmbeddingService}
 * or {@link OpenAiEmbeddingService}). Implementations must fail soft: an unconfigured or
 * unreachable provider should make {@link #isEnabled()} (or an empty/null result) reflect that,
 * not throw.
 */
public interface EmbeddingService {

    boolean isEnabled();

    /**
     * Output vector size of whatever model this provider is configured with — must match the
     * Atlas Vector Search index's {@code numDimensions}, which {@code
     * VectorSearchIndexVerification} reads from here rather than a separately-configured value,
     * so switching providers/models can never leave the two out of sync.
     */
    int getDimensions();

    /**
     * Embeds a batch of movie texts (title + plot) for storage. Returns a list the same length
     * as {@code texts}, with a {@code null} entry wherever that text was blank or the call
     * failed — callers should save the document anyway with a null embedding rather than drop
     * it.
     */
    List<List<Double>> embedDocuments(List<String> texts);

    /**
     * Embeds a single free-text search query. Empty on failure or when disabled — callers on
     * the read path should treat that as "vector search unavailable," not "no matches."
     */
    Optional<List<Double>> embedQuery(String text);
}
