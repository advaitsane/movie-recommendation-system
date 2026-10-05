package com.movies.review.dto;

import lombok.Builder;

/**
 * Request body for {@code PATCH /api/reviews/{id}}. All fields optional — only non-null
 * fields are applied. No bean validation here (mirrors catalog-service's
 * {@code UpdateMovieRequest} convention); range/length checks happen in
 * {@code ReviewServiceImpl} so a partial update can still return a precise error message.
 */
@Builder
public record UpdateReviewRequest(
        Integer rating,
        String reviewText) {
}
