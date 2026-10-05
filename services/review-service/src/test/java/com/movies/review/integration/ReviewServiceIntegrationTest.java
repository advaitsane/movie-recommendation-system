package com.movies.review.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.review.ReviewServiceApplication;
import com.movies.review.dto.CreateReviewRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the full Spring context wires up correctly against a real Postgres
 * instance (an isolated Testcontainers-provisioned container, not whatever POSTGRES_URL points at
 * locally) — exercising the Flyway migration, JPA entity mapping, and a real
 * create-then-read round trip through the REST API. This is the test that would catch a
 * missing flyway-database-postgresql dependency immediately (see pom.xml comment).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = ReviewServiceApplication.class)
@Import(ReviewServicePostgresTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("review-service Integration Test")
class ReviewServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Creates a review via REST and reads it back from the real database")
    void createThenGetReview_roundTrips() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("user-integration-1")
                .movieId("507f1f77bcf86cd799439011")
                .rating(5)
                .reviewText("A movie that only exists to prove the wiring works")
                .build();

        String createResponse = mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(5))
                .andReturn().getResponse().getContentAsString();

        Long reviewId = objectMapper.readTree(createResponse).at("/id").asLong();
        assertThat(reviewId).isPositive();

        mockMvc.perform(get("/api/reviews/{id}", reviewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-integration-1"))
                .andExpect(jsonPath("$.movieId").value("507f1f77bcf86cd799439011"));
    }

    @Test
    @DisplayName("A second POST for the same user+movie is rejected with 409")
    void duplicateReview_returnsConflict() throws Exception {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .userId("user-integration-2")
                .movieId("507f1f77bcf86cd799439012")
                .rating(3)
                .build();

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }
}
