package com.movies.review.service;

import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.dto.UpdateReviewRequest;
import com.movies.review.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for review/rating business logic, including the write side of the
 * outbox pattern (inserting {@code outbox_events} rows in the same transaction as the
 * {@code reviews} write they describe).
 */
public interface IReviewService {

    Review createReview(CreateReviewRequest request);

    Review getReviewById(Long id);

    Page<Review> getReviews(String movieId, String userId, Pageable pageable);

    Review updateReview(Long id, UpdateReviewRequest request);

    void deleteReview(Long id);
}
