package com.movies.recommendation.controller;

import com.movies.recommendation.dto.RecommendedMovie;
import com.movies.recommendation.exception.GlobalExceptionHandler;
import com.movies.recommendation.service.IRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for recommendation-service's one endpoint: {@code GET
 * /api/recommendations/{userId}}, blended content + collaborative recommendations. Never fails
 * because of a downstream dependency — a cold-start user gets a popularity fallback, and a
 * slow/unreachable search-service degrades to collaborative-only rather than erroring. Errors
 * are handled by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/recommendations")
@Tag(name = "Recommendations", description = "Blended content-based + collaborative-filtering recommendations")
public class RecommendationController {

    private final IRecommendationService recommendationService;

    public RecommendationController(IRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Operation(
            summary = "Get recommendations for a user",
            description = "Blends content-based similarity (from search-service) with collaborative "
                    + "filtering (from this service's own genre-weight user profiles), cached in Redis "
                    + "for app.recommendation.cache-ttl-seconds. Falls back to a popularity list for a "
                    + "user with no ratings yet."
    )
    @GetMapping("/{userId}")
    public ResponseEntity<List<RecommendedMovie>> getRecommendations(
            @Parameter(description = "User id to recommend for", required = true) @PathVariable String userId,
            @Parameter(description = "Max number of recommendations to return (default 20)")
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(recommendationService.getRecommendations(userId, limit));
    }
}
