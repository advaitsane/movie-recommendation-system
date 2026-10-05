package com.movies.review.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.event.ReviewEvent;
import com.movies.review.event.ReviewEventType;
import com.movies.review.exception.DatabaseOperationException;
import com.movies.review.exception.ReviewAlreadyExistsException;
import com.movies.review.model.OutboxEvent;
import com.movies.review.model.Review;
import com.movies.review.repository.OutboxEventRepository;
import com.movies.review.repository.ReviewRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} half of review writes, kept as a separate bean from {@link
 * ReviewServiceImpl} for the same reason {@code OutboxRowPublisher} is split from {@code
 * OutboxPoller}: Spring's transactional proxy opens (and, during a Postgres outage, blocks or
 * fails to open) its connection <b>before</b> the method body runs — so a circuit breaker
 * wrapped inside an {@code @Transactional} method never sees that failure. Verified live: with
 * the breaker wrapped inside {@code ReviewServiceImpl.createReview} instead of around a call
 * into this class, six consecutive Postgres-outage requests all returned a raw 500 straight
 * from {@code JpaTransactionManager} and the breaker never opened (see ADR-0011 Update 6). Callers
 * must invoke these methods through the injected bean, not self-invoked, or {@code @Transactional}
 * silently no-ops.
 */
@Component
public class ReviewWriteOperations {

    private static final Logger logger = LoggerFactory.getLogger(ReviewWriteOperations.class);

    private final ReviewRepository reviewRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public ReviewWriteOperations(
            ReviewRepository reviewRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this.reviewRepository = reviewRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Review createReviewTx(CreateReviewRequest request) {
        if (reviewRepository.existsByUserIdAndMovieId(request.userId(), request.movieId())) {
            throw new ReviewAlreadyExistsException(
                    "A review by user '%s' for movie '%s' already exists — use PATCH /api/reviews/{id} to update it"
                            .formatted(request.userId(), request.movieId()));
        }

        Instant now = Instant.now();
        Review review = Review.builder()
                .userId(request.userId())
                .movieId(request.movieId())
                .rating(request.rating())
                .reviewText(request.reviewText())
                .createdAt(now)
                .updatedAt(now)
                .build();

        Review saved = reviewRepository.save(review);

        writeOutboxEvent(saved, ReviewEventType.REVIEW_CREATED);

        return saved;
    }

    @Transactional
    public Review updateReviewTx(Review review, boolean ratingChanged) {
        Review saved = reviewRepository.save(review);

        if (ratingChanged) {
            writeOutboxEvent(saved, ReviewEventType.RATING_UPDATED);
        } else {
            logger.debug("Review {} updated with no rating change — no outbox event written", review.getId());
        }

        return saved;
    }

    @Transactional
    public void deleteReviewTx(Review review) {
        // No outbox row written here — see ReviewServiceImpl's class Javadoc for why deletes
        // are a documented v1 gap rather than a review.deleted event nobody downstream consumes.
        reviewRepository.delete(review);
    }

    private void writeOutboxEvent(Review review, ReviewEventType eventType) {
        UUID eventId = UUID.randomUUID();
        ReviewEvent payload = new ReviewEvent(
                eventId.toString(),
                eventType,
                review.getId(),
                review.getUserId(),
                review.getMovieId(),
                review.getRating(),
                review.getReviewText(),
                Instant.now());

        String serializedPayload;
        try {
            serializedPayload = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new DatabaseOperationException("Failed to serialize outbox payload for review " + review.getId());
        }

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .eventId(eventId)
                .aggregateType("review")
                .aggregateId(review.getId())
                .kafkaKey(review.getMovieId())
                .eventType(eventType.name())
                .payload(serializedPayload)
                .occurredAt(Instant.now())
                .publishAttempts(0)
                .build();

        outboxEventRepository.insert(outboxEvent);
    }
}
