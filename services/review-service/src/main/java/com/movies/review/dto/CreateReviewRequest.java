package com.movies.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Request body for {@code POST /api/reviews}. {@code userId}/{@code movieId} are treated as
 * opaque strings — no Mongo-ObjectId-shape assumption is baked in here, to avoid coupling
 * review-service's validation to catalog-service's specific id representation.
 */
@Builder
public record CreateReviewRequest(

        @NotBlank(message = "userId is required")
        String userId,

        @NotBlank(message = "movieId is required")
        String movieId,

        @NotNull(message = "rating is required")
        @Min(value = 1, message = "rating must be between 1 and 5")
        @Max(value = 5, message = "rating must be between 1 and 5")
        Integer rating,

        @Size(max = 2000, message = "reviewText must be at most 2000 characters")
        String reviewText) {
}
