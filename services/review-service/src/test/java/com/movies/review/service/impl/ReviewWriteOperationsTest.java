package com.movies.review.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.exception.ReviewAlreadyExistsException;
import com.movies.review.model.OutboxEvent;
import com.movies.review.model.Review;
import com.movies.review.repository.OutboxEventRepository;
import com.movies.review.repository.ReviewRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies the outbox write-path decisions that are the whole point of review-service: a
 * duplicate create is rejected before any write, a create writes exactly one REVIEW_CREATED
 * row, and an update writes RATING_UPDATED only when told the rating actually changed (that
 * decision itself is {@code ReviewServiceImpl}'s job — see {@link ReviewServiceImplTest}).
 */
@DisplayName("ReviewWriteOperations Unit Tests")
class ReviewWriteOperationsTest {

    private ReviewRepository reviewRepository;
    private OutboxEventRepository outboxEventRepository;
    private ReviewWriteOperations reviewWriteOperations;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        reviewWriteOperations = new ReviewWriteOperations(reviewRepository, outboxEventRepository, objectMapper);

        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            if (review.getId() == null) {
                review.setId(1L);
            }
            return review;
        });
    }

    @Test
    @DisplayName("createReviewTx throws ReviewAlreadyExistsException when (userId, movieId) already exists")
    void createReviewTx_duplicate_throws() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("u1").movieId("m1").rating(5).build();
        when(reviewRepository.existsByUserIdAndMovieId("u1", "m1")).thenReturn(true);

        assertThrows(ReviewAlreadyExistsException.class, () -> reviewWriteOperations.createReviewTx(request));
        verify(outboxEventRepository, never()).insert(any());
    }

    @Test
    @DisplayName("createReviewTx writes exactly one REVIEW_CREATED outbox row")
    void createReviewTx_success_writesOneOutboxRow() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("u1").movieId("m1").rating(5).reviewText("great").build();
        when(reviewRepository.existsByUserIdAndMovieId("u1", "m1")).thenReturn(false);

        reviewWriteOperations.createReviewTx(request);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).insert(captor.capture());
        assertEquals("REVIEW_CREATED", captor.getValue().getEventType());
        assertEquals("m1", captor.getValue().getKafkaKey());
    }

    @Test
    @DisplayName("updateReviewTx writes a RATING_UPDATED outbox row when told the rating changed")
    void updateReviewTx_ratingChanged_writesOutboxRow() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(5).reviewText("ok")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        reviewWriteOperations.updateReviewTx(existing, true);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).insert(captor.capture());
        assertEquals("RATING_UPDATED", captor.getValue().getEventType());
    }

    @Test
    @DisplayName("updateReviewTx writes no outbox row when told the rating didn't change")
    void updateReviewTx_ratingUnchanged_writesNoOutboxRow() {
        Review existing = Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(3).reviewText("updated text")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();

        reviewWriteOperations.updateReviewTx(existing, false);

        verify(outboxEventRepository, never()).insert(any());
    }

    @Test
    @DisplayName("deleteReviewTx removes the row and writes no outbox event")
    void deleteReviewTx_writesNoOutboxRow() {
        Review existing = Review.builder().id(1L).userId("u1").movieId("m1").rating(3).build();

        reviewWriteOperations.deleteReviewTx(existing);

        verify(reviewRepository, times(1)).delete(existing);
        verify(outboxEventRepository, never()).insert(any());
    }
}
