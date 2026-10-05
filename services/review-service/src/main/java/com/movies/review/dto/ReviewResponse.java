package com.movies.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.movies.review.model.Review;
import java.time.Instant;
import lombok.Builder;

/**
 * Wire-level shape for every endpoint that hands back a review. Keeps the JPA {@link Review}
 * entity out of the response body; {@link #from(Review)} maps one to the other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record ReviewResponse(
        Long id,
        String userId,
        String movieId,
        Integer rating,
        String reviewText,
        Instant createdAt,
        Instant updatedAt) {

    public static ReviewResponse from(Review review) {
        return ReviewResponse.builder()
                .id(review.getId())
                .userId(review.getUserId())
                .movieId(review.getMovieId())
                .rating(review.getRating())
                .reviewText(review.getReviewText())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}
