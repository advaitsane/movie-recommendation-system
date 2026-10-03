package com.movies.search.controller;

import com.movies.search.dto.MovieSearchQuery;
import com.movies.search.dto.MovieSearchResult;
import com.movies.search.dto.VectorSearchResult;
import com.movies.search.exception.GlobalExceptionHandler;
import com.movies.search.model.MovieSearchDocument;
import com.movies.search.service.ISearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for movie search, reading only search-service's own movies_search collection,
 * never catalog-service's database (see docs/adr/0001). {@link #search} returns {@code Page}
 * since it's an exact query with a well-defined total; {@link #vectorSearch} and
 * {@link #findSimilar} stay {@code limit}-only since ANN search has none (see
 * {@link ISearchService}). Errors are handled by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/movies/search")
@Tag(name = "Search", description = "Movie search endpoints backed by search-service's synced read model")
public class SearchController {

    private final ISearchService searchService;

    public SearchController(ISearchService searchService) {
        this.searchService = searchService;
    }

    @Operation(
        summary = "Search movies with optional filtering, sorting, and pagination",
        description = "Full-text search (title/plot/fullplot) via search-service's own text index, " +
                     "plus genre/year/rating filters. Results come from movies_search, a read model " +
                     "synced from catalog-service's movie.* Kafka events — not catalog-service's database. " +
                     "Pagination and sorting use Spring Data's standard page/size/sort query params " +
                     "(e.g. ?page=0&size=20&sort=title,asc), matching catalog-service's GET /api/movies."
    )
    @GetMapping
    public ResponseEntity<Page<MovieSearchResult>> search(
            @Parameter(description = "Text search query (searches title, plot, fullplot)")
            @RequestParam(required = false) String q,
            @Parameter(description = "Filter by genre (case-insensitive partial match)")
            @RequestParam(required = false) String genre,
            @Parameter(description = "Filter by exact year")
            @RequestParam(required = false) Integer year,
            @Parameter(description = "Minimum IMDB rating (inclusive)")
            @RequestParam(required = false) Double minRating,
            @Parameter(description = "Maximum IMDB rating (inclusive)")
            @RequestParam(required = false) Double maxRating,
            @PageableDefault(size = 20, sort = "title") Pageable pageable) {

        MovieSearchQuery query = MovieSearchQuery.builder()
                .q(q)
                .genre(genre)
                .year(year)
                .minRating(minRating)
                .maxRating(maxRating)
                .build();

        Page<MovieSearchDocument> results = searchService.searchMovies(query, pageable);
        return ResponseEntity.ok(results.map(MovieSearchResult::from));
    }

    @Operation(
        summary = "Get a single indexed movie by ID",
        description = "Fetch a movie from search-service's own read model by its MongoDB ObjectId."
    )
    @GetMapping("/{id}")
    public ResponseEntity<MovieSearchResult> getById(
            @Parameter(description = "Movie ObjectId (24-character hex string)", required = true)
            @PathVariable String id) {
        MovieSearchDocument document = searchService.getById(id);
        return ResponseEntity.ok(MovieSearchResult.from(document));
    }

    @Operation(
        summary = "Semantic search over movie plots via embeddings",
        description = "Embeds the query text with the configured embedding provider (Voyage AI or " +
                     "OpenAI) and runs Atlas $vectorSearch against movies_search.plotEmbedding, " +
                     "returning the closest matches by plot meaning rather than keyword overlap. " +
                     "Returns 503 if that provider isn't configured. " +
                     "Deliberately limit-only, not page/pageable, unlike GET /api/movies/search: " +
                     "$vectorSearch is approximate nearest-neighbor (ANN) over a candidate pool, not " +
                     "an exact query, so there's no well-defined total-match count to page against " +
                     "(every document has *some* similarity score) — this returns a single ranked " +
                     "top-N list instead."
    )
    @GetMapping("/vector")
    public ResponseEntity<List<VectorSearchResult>> vectorSearch(
            @Parameter(description = "Free-text description of what to search for", required = true)
            @RequestParam String q,
            @Parameter(description = "Number of results to return (default: 10, max: 100)")
            @RequestParam(defaultValue = "10") Integer limit) {
        return ResponseEntity.ok(searchService.vectorSearch(q, limit));
    }

    @Operation(
        summary = "Find movies with a similar plot to a given movie",
        description = "Uses the given movie's already-stored embedding (no extra embedding-provider " +
                     "call) to run Atlas $vectorSearch for its nearest neighbors by plot similarity. " +
                     "Returns 400 if the movie has no stored embedding yet. " +
                     "Deliberately limit-only, not page/pageable, for the same reason as " +
                     "GET /api/movies/search/vector: ANN similarity search has no well-defined " +
                     "total-match count to page against, so this returns a single ranked top-N " +
                     "list instead."
    )
    @GetMapping("/{id}/similar")
    public ResponseEntity<List<VectorSearchResult>> findSimilar(
            @Parameter(description = "Movie ObjectId (24-character hex string)", required = true)
            @PathVariable String id,
            @Parameter(description = "Number of results to return (default: 10, max: 100)")
            @RequestParam(defaultValue = "10") Integer limit) {
        return ResponseEntity.ok(searchService.findSimilar(id, limit));
    }
}
