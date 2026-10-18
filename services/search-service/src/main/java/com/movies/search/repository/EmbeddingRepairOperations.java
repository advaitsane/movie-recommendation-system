package com.movies.search.repository;

import com.movies.search.model.MovieSearchDocument;
import java.util.List;

/** The one write {@code EmbeddingRepairJob} needs that a derived query can't express. */
public interface EmbeddingRepairOperations {

    /**
     * Sets only {@code plotEmbedding}, and only if the document still has none and still has the
     * text the embedding was made from. Returns false if a Kafka update got there first.
     */
    boolean setEmbeddingIfUnchanged(MovieSearchDocument document, List<Double> embedding);
}
