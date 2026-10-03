package com.movies.search.service;

import com.movies.search.dto.MovieSearchQuery;
import com.movies.search.dto.VectorSearchResult;
import com.movies.search.model.MovieSearchDocument;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read-only query logic over the movies_search read model, covering both the text/filter search
 * ({@link #searchMovies}) and embedding-based semantic search ({@link #vectorSearch}, {@link
 * #findSimilar}) via whichever provider {@code embedding.provider} selects (Voyage AI or
 * OpenAI).
 */
public interface ISearchService {

    /**
     * Text/filter search over a real Mongo query — exact match/regex/text-index criteria against
     * a known collection, so a total-match count is well-defined and cheap enough (via {@code
     * PageableExecutionUtils}) to expose. Returns {@code Page} for the same reason
     * catalog-service's {@code getAllMovies} does: clients need {@code totalElements} to render
     * "page 3 of 12", not just the next 20 rows.
     */
    Page<MovieSearchDocument> searchMovies(MovieSearchQuery query, Pageable pageable);

    MovieSearchDocument getById(String id);

    /**
     * Semantic search: embeds {@code q} via the configured embedding provider and returns the
     * {@code limit} nearest movies by plot similarity. Throws {@code ServiceUnavailableException}
     * if the provider isn't configured. Deliberately {@code limit}-only, not {@code Page}: ANN
     * search over a candidate pool has no well-defined total-match count to page against.
     */
    List<VectorSearchResult> vectorSearch(String q, Integer limit);

    /**
     * Finds movies with the most similar plot to the movie identified by {@code id}, using its
     * already-stored embedding (no embedding-provider call needed). Throws {@code
     * ResourceNotFoundException} if unindexed, or {@code ValidationException} if it has no stored
     * embedding yet. {@code limit}-only for the same ANN reason as {@link #vectorSearch}.
     */
    List<VectorSearchResult> findSimilar(String id, Integer limit);
}
