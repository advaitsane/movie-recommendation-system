package com.movies.review.controller;

import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.dto.ReviewResponse;
import com.movies.review.dto.UpdateReviewRequest;
import com.movies.review.exception.GlobalExceptionHandler;
import com.movies.review.model.Review;
import com.movies.review.service.IReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for review/rating endpoints — see {@code IReviewService} for the CRUD and
 * outbox-write logic. Endpoints return raw DTOs with no success envelope; errors are handled by
 * {@link GlobalExceptionHandler}, which returns a lean {@code ErrorResponseDto}.
 */
@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews", description = "Review/rating CRUD, backed by a transactional outbox to Kafka")
public class ReviewController {

    private final IReviewService reviewService;

    public ReviewController(IReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(
            summary = "Create a review",
            description = "Creates a review/rating for a (userId, movieId) pair. Returns 409 if one already " +
                    "exists for that pair — use PATCH to update it instead. Emits review.created."
    )
    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            @Parameter(description = "Review data to create", required = true)
            @Valid @RequestBody CreateReviewRequest request) {
        Review review = reviewService.createReview(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.from(review));
    }

    @Operation(summary = "Get a review by id")
    @GetMapping("/{id}")
    public ResponseEntity<ReviewResponse> getReviewById(
            @Parameter(description = "Review id", required = true)
            @PathVariable Long id) {
        Review review = reviewService.getReviewById(id);
        return ResponseEntity.ok(ReviewResponse.from(review));
    }

    @Operation(
            summary = "List reviews for a movie and/or user",
            description = "At least one of movieId or userId is required, to avoid an unbounded scan."
    )
    @GetMapping
    public ResponseEntity<Page<ReviewResponse>> getReviews(
            @Parameter(description = "Filter by movie id") @RequestParam(required = false) String movieId,
            @Parameter(description = "Filter by user id") @RequestParam(required = false) String userId,
            @Parameter(description = "Page number (0-based, default 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (default 20)") @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Review> reviews = reviewService.getReviews(movieId, userId, pageable);
        return ResponseEntity.ok(reviews.map(ReviewResponse::from));
    }

    @Operation(
            summary = "Update a review's rating and/or text",
            description = "Only provided (non-null) fields are updated. Emits rating.updated only if the " +
                    "rating value actually changes — a text-only edit publishes no event (see IReviewService)."
    )
    @PatchMapping("/{id}")
    public ResponseEntity<ReviewResponse> updateReview(
            @Parameter(description = "Review id to update", required = true) @PathVariable Long id,
            @Parameter(description = "Fields to update", required = true) @RequestBody UpdateReviewRequest request) {
        Review review = reviewService.updateReview(id, request);
        return ResponseEntity.ok(ReviewResponse.from(review));
    }

    @Operation(
            summary = "Delete a review",
            description = "No Kafka event is published on delete in this v1 — a documented limitation."
    )
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReview(
            @Parameter(description = "Review id to delete", required = true) @PathVariable Long id) {
        reviewService.deleteReview(id);
        return ResponseEntity.noContent().build();
    }
}
