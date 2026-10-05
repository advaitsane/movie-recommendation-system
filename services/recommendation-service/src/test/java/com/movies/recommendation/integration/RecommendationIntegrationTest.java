package com.movies.recommendation.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.movies.recommendation.RecommendationServiceApplication;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.model.UserProfile;
import com.movies.recommendation.model.UserRating;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.movies.recommendation.repository.UserProfileRepository;
import com.movies.recommendation.repository.UserRatingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the full Spring context wires up correctly against a real Mongo
 * instance (an isolated Testcontainers-provisioned container) and that
 * {@code GET /api/recommendations/{userId}} produces correct results from data written
 * directly into this service's own read-model collections — the way MovieMetadataConsumer /
 * RatingEventConsumer eventually would (see their own unit tests for that consumer coverage;
 * this test needs no live broker).
 *
 * <p>Neither Redis nor search-service is running here: the cache degrades to "always a miss"
 * and the content-based signal degrades to empty, both by design (see
 * {@code RecommendationServiceImpl}'s Javadoc) — so both cases below exercise the
 * collaborative/popularity paths, which is what real Mongo data can actually drive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = RecommendationServiceApplication.class)
@Import(MongoDBTestContainersConfig.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("recommendation-service Integration Test")
class RecommendationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MovieMetadataRepository movieMetadataRepository;

    @Autowired
    private UserRatingRepository userRatingRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Test
    @DisplayName("a user with no ratings gets the popularity fallback")
    void coldStartUser_getsPopularFallback() throws Exception {
        movieMetadataRepository.save(MovieMetadata.builder()
                .id("movie-popular").title("Widely Rated Movie").year(2020).genres(List.of("Drama")).build());
        userRatingRepository.save(rating("peerA", "movie-popular", 5));
        userRatingRepository.save(rating("peerB", "movie-popular", 4));

        mockMvc.perform(get("/api/recommendations/{userId}", "cold-start-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movieId").value("movie-popular"))
                .andExpect(jsonPath("$[0].source").value("POPULAR"));
    }

    @Test
    @DisplayName("a user with a similar peer profile gets that peer's liked movie recommended")
    void userWithSimilarPeer_getsCollaborativeRecommendation() throws Exception {
        movieMetadataRepository.save(MovieMetadata.builder()
                .id("movie-watched").title("Already Watched").year(2019).genres(List.of("Sci-Fi")).build());
        movieMetadataRepository.save(MovieMetadata.builder()
                .id("movie-recommended").title("Peer Liked This").year(2021).genres(List.of("Sci-Fi")).build());

        userRatingRepository.save(rating("target-user", "movie-watched", 5));
        userProfileRepository.save(UserProfile.builder()
                .userId("target-user").genreWeights(Map.of("Sci-Fi", 5.0)).updatedAt(Instant.now()).build());

        userProfileRepository.save(UserProfile.builder()
                .userId("similar-peer").genreWeights(Map.of("Sci-Fi", 5.0)).updatedAt(Instant.now()).build());
        userRatingRepository.save(rating("similar-peer", "movie-recommended", 5));

        mockMvc.perform(get("/api/recommendations/{userId}", "target-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movieId").value("movie-recommended"))
                .andExpect(jsonPath("$[0].source").value("COLLABORATIVE"));
    }

    private UserRating rating(String userId, String movieId, int value) {
        return UserRating.builder()
                .userId(userId).movieId(movieId).rating(value).lastEventAt(Instant.now()).build();
    }
}
