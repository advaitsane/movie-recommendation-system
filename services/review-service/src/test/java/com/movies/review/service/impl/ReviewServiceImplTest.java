package com.movies.review.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.dto.UpdateReviewRequest;
import com.movies.review.exception.ResourceNotFoundException;
import com.movies.review.exception.ValidationException;
import com.movies.review.model.Review;
import com.movies.review.repository.ReviewRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Verifies ReviewServiceImpl's orchestration: business validation (not-found, rating-range,
 * no-op update) happens here, and reads/writes delegate to {@link ReviewRepository} / {@link
 * ReviewWriteOperations} through the circuit breaker. The actual outbox-writing decisions (does
 * this change write a row, and which event type) are {@link ReviewWriteOperations}'s job — see
 * {@link ReviewWriteOperationsTest}.
 */
@DisplayName("ReviewServiceImpl Unit Tests")
class ReviewServiceImplTest {

    private ReviewRepository reviewRepository;
    private ReviewWriteOperations reviewWriteOperations;
    private ReviewServiceImpl reviewService;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        reviewWriteOperations = mock(ReviewWriteOperations.class);
        reviewService = new ReviewServiceImpl(reviewRepository, reviewWriteOperations,
                CircuitBreaker.ofDefaults("test-postgres-circuit-breaker"));

        when(reviewWriteOperations.createReviewTx(any())).thenAnswer(invocation -> {
            CreateReviewRequest request = invocation.getArgument(0);
            return Review.builder().id(1L).userId(request.userId()).movieId(request.movieId())
                    .rating(request.rating()).reviewText(request.reviewText())
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        });
        when(reviewWriteOperations.updateReviewTx(any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("createReview delegates to ReviewWriteOperations")
    void createReview_delegatesToWriteOperations() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("u1").movieId("m1").rating(5).reviewText("great").build();

        Review result = reviewService.createReview(request);

        verify(reviewWriteOperations, times(1)).createReviewTx(request);
        org.junit.jupiter.api.Assertions.assertEquals("u1", result.getUserId());
    }

    @Test
    @DisplayName("updateReview passes ratingChanged=true to ReviewWriteOperations when the rating actually changes")
    void updateReview_ratingChanged_passesTrue() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(3).reviewText("ok")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(existing));

        reviewService.updateReview(1L, UpdateReviewRequest.builder().rating(5).build());

        verify(reviewWriteOperations, times(1)).updateReviewTx(existing, true);
    }

    @Test
    @DisplayName("updateReview passes ratingChanged=false when only the text changes")
    void updateReview_textOnly_passesFalse() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(3).reviewText("ok")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(existing));

        reviewService.updateReview(1L, UpdateReviewRequest.builder().reviewText("updated text").build());

        verify(reviewWriteOperations, times(1)).updateReviewTx(existing, false);
    }

    @Test
    @DisplayName("updateReview passes ratingChanged=false when the PATCHed rating equals the existing one")
    void updateReview_sameRating_passesFalse() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(3).reviewText("ok")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(existing));

        reviewService.updateReview(1L, UpdateReviewRequest.builder().rating(3).build());

        verify(reviewWriteOperations, times(1)).updateReviewTx(existing, false);
    }

    @Test
    @DisplayName("updateReview rejects an out-of-range rating without calling ReviewWriteOperations")
    void updateReview_invalidRating_throwsValidationException() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(3)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(existing));

        assertThrows(ValidationException.class,
                () -> reviewService.updateReview(1L, UpdateReviewRequest.builder().rating(9).build()));
        verify(reviewWriteOperations, never()).updateReviewTx(any(), anyBoolean());
    }

    @Test
    @DisplayName("deleteReview delegates to ReviewWriteOperations")
    void deleteReview_delegatesToWriteOperations() {
        Review existing = Review.builder().id(1L).userId("u1").movieId("m1").rating(3).build();
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(existing));

        reviewService.deleteReview(1L);

        verify(reviewWriteOperations, times(1)).deleteReviewTx(existing);
    }

    @Test
    @DisplayName("getReviewById throws ResourceNotFoundException when missing")
    void getReviewById_notFound_throws() {
        when(reviewRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> reviewService.getReviewById(99L));
    }

    @Test
    @DisplayName("getReviews requires at least one of movieId or userId")
    void getReviews_noFilters_throwsValidationException() {
        assertThrows(ValidationException.class,
                () -> reviewService.getReviews(null, null, Pageable.unpaged()));
    }
}
