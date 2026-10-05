package com.movies.review.service.impl;

import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.dto.UpdateReviewRequest;
import com.movies.review.exception.ResourceNotFoundException;
import com.movies.review.exception.ValidationException;
import com.movies.review.model.Review;
import com.movies.review.repository.ReviewRepository;
import com.movies.review.service.IReviewService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Service layer for review/rating CRUD. Business validation (duplicate/rating-range/no-op
 * checks) lives here; the actual {@code @Transactional} writes — including the outbox pattern's
 * write side, an {@code outbox_events} row inserted in the same local transaction as the {@code
 * reviews} write — live in {@link ReviewWriteOperations}, a separate bean (see its Javadoc for
 * why: a circuit breaker wrapped inside an {@code @Transactional} method never sees a connection
 * failure, since the transactional proxy opens its connection before the method body runs).
 * Two intentional v1 gaps: {@link #updateReview} only emits {@code RATING_UPDATED} when the
 * numeric rating actually changes, and {@link #deleteReview} publishes no event at all — no
 * {@code review.deleted} topic/consumer exists anywhere in this system. All Postgres-touching
 * calls run through {@code postgresCircuitBreaker} (see {@code PostgresCircuitBreakerConfig}).
 */
@Service
public class ReviewServiceImpl implements IReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewWriteOperations reviewWriteOperations;
    private final CircuitBreaker postgresCircuitBreaker;

    public ReviewServiceImpl(
            ReviewRepository reviewRepository,
            ReviewWriteOperations reviewWriteOperations,
            CircuitBreaker postgresCircuitBreaker) {
        this.reviewRepository = reviewRepository;
        this.reviewWriteOperations = reviewWriteOperations;
        this.postgresCircuitBreaker = postgresCircuitBreaker;
    }

    @Override
    public Review createReview(CreateReviewRequest request) {
        return postgresCircuitBreaker.executeSupplier(() -> reviewWriteOperations.createReviewTx(request));
    }

    @Override
    public Review getReviewById(Long id) {
        return findReviewOrThrow(id);
    }

    @Override
    public Page<Review> getReviews(String movieId, String userId, Pageable pageable) {
        boolean hasMovieId = movieId != null && !movieId.isBlank();
        boolean hasUserId = userId != null && !userId.isBlank();

        if (!hasMovieId && !hasUserId) {
            throw new ValidationException("At least one of movieId or userId is required");
        }

        return postgresCircuitBreaker.executeSupplier(() -> {
            if (hasMovieId && hasUserId) {
                return reviewRepository.findByMovieIdAndUserId(movieId, userId, pageable);
            }
            if (hasMovieId) {
                return reviewRepository.findByMovieId(movieId, pageable);
            }
            return reviewRepository.findByUserId(userId, pageable);
        });
    }

    @Override
    public Review updateReview(Long id, UpdateReviewRequest request) {
        Review review = findReviewOrThrow(id);

        if (request == null || (request.rating() == null && request.reviewText() == null)) {
            throw new ValidationException("No update data provided");
        }

        boolean ratingChanged = false;

        if (request.rating() != null) {
            if (request.rating() < 1 || request.rating() > 5) {
                throw new ValidationException("rating must be between 1 and 5");
            }
            ratingChanged = !Objects.equals(request.rating(), review.getRating());
            review.setRating(request.rating());
        }

        if (request.reviewText() != null) {
            if (request.reviewText().length() > 2000) {
                throw new ValidationException("reviewText must be at most 2000 characters");
            }
            review.setReviewText(request.reviewText());
        }

        review.setUpdatedAt(Instant.now());
        boolean finalRatingChanged = ratingChanged;
        return postgresCircuitBreaker.executeSupplier(
                () -> reviewWriteOperations.updateReviewTx(review, finalRatingChanged));
    }

    @Override
    public void deleteReview(Long id) {
        Review review = findReviewOrThrow(id);
        postgresCircuitBreaker.executeRunnable(() -> reviewWriteOperations.deleteReviewTx(review));
    }

    private Review findReviewOrThrow(Long id) {
        Optional<Review> found = postgresCircuitBreaker.executeSupplier(() -> reviewRepository.findById(id));
        return found.orElseThrow(() -> new ResourceNotFoundException("Review not found: " + id));
    }
}
