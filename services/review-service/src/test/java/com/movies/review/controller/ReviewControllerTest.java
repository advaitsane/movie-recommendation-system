package com.movies.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.review.dto.CreateReviewRequest;
import com.movies.review.dto.UpdateReviewRequest;
import com.movies.review.exception.ResourceNotFoundException;
import com.movies.review.exception.ReviewAlreadyExistsException;
import com.movies.review.model.Review;
import com.movies.review.service.IReviewService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@DisplayName("ReviewController Unit Tests")
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IReviewService reviewService;

    private Review sampleReview() {
        return Review.builder()
                .id(1L).userId("u1").movieId("m1").rating(4).reviewText("nice")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    @Test
    @DisplayName("POST /api/reviews returns 201 with the created review")
    void createReview_returnsCreated() throws Exception {
        when(reviewService.createReview(any())).thenReturn(sampleReview());

        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("u1").movieId("m1").rating(4).reviewText("nice").build();

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(4));
    }

    @Test
    @DisplayName("POST /api/reviews returns 400 when rating is missing")
    void createReview_missingRating_returnsBadRequest() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder().userId("u1").movieId("m1").build();

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/reviews returns 409 when the service reports a duplicate")
    void createReview_duplicate_returnsConflict() throws Exception {
        when(reviewService.createReview(any()))
                .thenThrow(new ReviewAlreadyExistsException("A review by this user for this movie already exists"));

        CreateReviewRequest request = CreateReviewRequest.builder().userId("u1").movieId("m1").rating(4).build();

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/reviews/{id} returns 404 when missing")
    void getReviewById_notFound_returns404() throws Exception {
        when(reviewService.getReviewById(99L)).thenThrow(new ResourceNotFoundException("Review not found: 99"));

        mockMvc.perform(get("/api/reviews/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/reviews returns a page of reviews")
    void getReviews_returnsPage() throws Exception {
        when(reviewService.getReviews(eq("m1"), eq(null), any()))
                .thenReturn(new PageImpl<>(java.util.List.of(sampleReview())));

        mockMvc.perform(get("/api/reviews").param("movieId", "m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].movieId").value("m1"));
    }

    @Test
    @DisplayName("PATCH /api/reviews/{id} returns the updated review")
    void updateReview_returnsOk() throws Exception {
        Review updated = sampleReview();
        updated.setRating(5);
        when(reviewService.updateReview(eq(1L), any())).thenReturn(updated);

        mockMvc.perform(patch("/api/reviews/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateReviewRequest.builder().rating(5).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5));
    }

    @Test
    @DisplayName("DELETE /api/reviews/{id} returns 204")
    void deleteReview_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/reviews/{id}", 1L))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(reviewService).deleteReview(anyLong());
    }
}
