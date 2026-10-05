package com.movies.recommendation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.recommendation.cache.RecommendationCache;
import com.movies.recommendation.client.SearchServiceClient;
import com.movies.recommendation.config.RecommendationProperties;
import com.movies.recommendation.dto.RecommendationSource;
import com.movies.recommendation.dto.RecommendedMovie;
import com.movies.recommendation.dto.SearchMovieResult;
import com.movies.recommendation.dto.SearchSimilarResult;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.model.UserProfile;
import com.movies.recommendation.model.UserRating;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.movies.recommendation.repository.UserProfileRepository;
import com.movies.recommendation.repository.UserRatingRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the blending logic that is the actual point of recommendation-service: cold-start
 * falls back to popularity, each signal is normalized before blending (so neither dominates
 * purely from raw magnitude), a candidate scored by both signals is marked
 * {@link RecommendationSource#BOTH}, and a cache hit large enough for the request short-circuits
 * both signals entirely.
 */
@DisplayName("RecommendationServiceImpl Unit Tests")
class RecommendationServiceImplTest {

    private UserRatingRepository userRatingRepository;
    private UserProfileRepository userProfileRepository;
    private MovieMetadataRepository movieMetadataRepository;
    private SearchServiceClient searchServiceClient;
    private RecommendationCache recommendationCache;
    private RecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        userRatingRepository = mock(UserRatingRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        movieMetadataRepository = mock(MovieMetadataRepository.class);
        searchServiceClient = mock(SearchServiceClient.class);
        recommendationCache = mock(RecommendationCache.class);

        RecommendationProperties properties = new RecommendationProperties(10, 0.5, 0.5, 300L, 4);

        // A real (not mocked) circuit breaker with default config, always CLOSED — lets calls
        // through unchanged so these tests exercise RecommendationServiceImpl's own logic, not
        // resilience4j's state machine (that belongs in its own test, if/when added) — mirrors
        // catalog-service's MovieServiceImplTest setup.
        service = new RecommendationServiceImpl(
                userRatingRepository, userProfileRepository, movieMetadataRepository,
                searchServiceClient, recommendationCache, properties,
                CircuitBreaker.ofDefaults("test-mongo-circuit-breaker"));

        when(recommendationCache.get(anyString())).thenReturn(Optional.empty());
        when(userProfileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(userProfileRepository.findByUserIdNot(anyString())).thenReturn(List.of());
        when(searchServiceClient.findSimilar(anyString(), anyInt())).thenReturn(List.of());
        when(userRatingRepository.findByUserIdInAndRatingGreaterThanEqual(any(), any())).thenReturn(List.of());
        when(userRatingRepository.findByRatingGreaterThanEqual(any())).thenReturn(List.of());
    }

    private UserRating rating(String userId, String movieId, int rating, Instant lastEventAt) {
        return UserRating.builder()
                .id(userId + ":" + movieId)
                .userId(userId).movieId(movieId).rating(rating)
                .lastEventAt(lastEventAt).build();
    }

    private MovieMetadata metadata(String movieId, String title, List<String> genres) {
        return MovieMetadata.builder().id(movieId).title(title).year(2020).genres(genres).build();
    }

    @Test
    @DisplayName("returns the cached list, trimmed to limit, when the cache already has enough entries")
    void getRecommendations_cacheHitLargeEnough_shortCircuits() {
        List<RecommendedMovie> cached = List.of(
                RecommendedMovie.builder().movieId("m1").score(1.0).source(RecommendationSource.POPULAR).build(),
                RecommendedMovie.builder().movieId("m2").score(0.9).source(RecommendationSource.POPULAR).build());
        when(recommendationCache.get("u1")).thenReturn(Optional.of(cached));

        List<RecommendedMovie> result = service.getRecommendations("u1", 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).movieId()).isEqualTo("m1");
        verify(userRatingRepository, never()).findByUserId(anyString());
    }

    @Test
    @DisplayName("a user with no ratings yet falls back to the popularity list")
    void getRecommendations_coldStart_returnsPopular() {
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of());
        when(userRatingRepository.findByRatingGreaterThanEqual(4)).thenReturn(List.of(
                rating("other1", "m1", 5, Instant.now()),
                rating("other2", "m1", 4, Instant.now()),
                rating("other3", "m2", 5, Instant.now())));
        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of(
                metadata("m1", "Popular Movie", List.of("Drama")),
                metadata("m2", "Less Popular Movie", List.of("Comedy"))));

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).movieId()).isEqualTo("m1");
        assertThat(result.get(0).source()).isEqualTo(RecommendationSource.POPULAR);
        assertThat(result.get(0).score()).isEqualTo(1.0);
        verify(recommendationCache).put(eq("u1"), any());
    }

    @Test
    @DisplayName("popular fallback skips a movie whose metadata hasn't synced yet")
    void popularRecommendations_missingMetadata_skipped() {
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of());
        when(userRatingRepository.findByRatingGreaterThanEqual(4)).thenReturn(List.of(
                rating("other1", "m1", 5, Instant.now())));
        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of());

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("content-only signal ranks the search-service similarity results, excluding already-rated movies")
    void getRecommendations_contentSignalOnly() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                rating("u1", "seed1", 5, now)));

        when(searchServiceClient.findSimilar("seed1", 20)).thenReturn(List.of(
                new SearchSimilarResult(new SearchMovieResult("m1", "Similar A", 2020, null, List.of("Drama")), 0.9),
                new SearchSimilarResult(new SearchMovieResult("seed1", "Seed", 2020, null, List.of("Drama")), 0.99),
                new SearchSimilarResult(new SearchMovieResult("m2", "Similar B", 2020, null, List.of("Drama")), 0.5)));

        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of(
                metadata("m1", "Similar A", List.of("Drama")),
                metadata("m2", "Similar B", List.of("Drama"))));

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).extracting(RecommendedMovie::movieId).containsExactly("m1", "m2");
        assertThat(result).extracting(RecommendedMovie::source)
                .containsOnly(RecommendationSource.CONTENT);
        // normalized content score of 1.0 (m1 is the highest-scoring content candidate) * the
        // configured 0.5 content-weight
        assertThat(result.get(0).score()).isEqualTo(0.5);
        assertThat(result).noneMatch(m -> m.movieId().equals("seed1"));
    }

    @Test
    @DisplayName("collaborative-only signal weights peer ratings by profile cosine similarity")
    void getRecommendations_collaborativeSignalOnly() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                rating("u1", "watched1", 5, now)));

        UserProfile own = UserProfile.builder().userId("u1")
                .genreWeights(Map.of("Drama", 5.0)).updatedAt(now).build();
        UserProfile identicalPeer = UserProfile.builder().userId("peer1")
                .genreWeights(Map.of("Drama", 5.0)).updatedAt(now).build();
        UserProfile unrelatedPeer = UserProfile.builder().userId("peer2")
                .genreWeights(Map.of("Comedy", 5.0)).updatedAt(now).build();

        when(userProfileRepository.findById("u1")).thenReturn(Optional.of(own));
        when(userProfileRepository.findByUserIdNot("u1")).thenReturn(List.of(identicalPeer, unrelatedPeer));
        when(userRatingRepository.findByUserIdInAndRatingGreaterThanEqual(List.of("peer1"), 4))
                .thenReturn(List.of(rating("peer1", "m1", 5, now)));

        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of(
                metadata("m1", "Recommended via peer", List.of("Drama"))));

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).movieId()).isEqualTo("m1");
        assertThat(result.get(0).source()).isEqualTo(RecommendationSource.COLLABORATIVE);
    }

    @Test
    @DisplayName("a candidate scored by both signals is marked BOTH")
    void getRecommendations_bothSignals_marksBoth() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                rating("u1", "seed1", 5, now)));

        when(searchServiceClient.findSimilar("seed1", 20)).thenReturn(List.of(
                new SearchSimilarResult(new SearchMovieResult("m1", "Shared", 2020, null, List.of("Drama")), 0.8)));

        UserProfile own = UserProfile.builder().userId("u1")
                .genreWeights(Map.of("Drama", 5.0)).updatedAt(now).build();
        UserProfile peer = UserProfile.builder().userId("peer1")
                .genreWeights(Map.of("Drama", 5.0)).updatedAt(now).build();
        when(userProfileRepository.findById("u1")).thenReturn(Optional.of(own));
        when(userProfileRepository.findByUserIdNot("u1")).thenReturn(List.of(peer));
        when(userRatingRepository.findByUserIdInAndRatingGreaterThanEqual(List.of("peer1"), 4))
                .thenReturn(List.of(rating("peer1", "m1", 5, now)));

        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of(
                metadata("m1", "Shared", List.of("Drama"))));

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo(RecommendationSource.BOTH);
    }

    @Test
    @DisplayName("a user with ratings but no candidates from either signal falls back to popularity")
    void getRecommendations_ratedUserNoCandidates_fallsBackToPopular() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                rating("u1", "watched1", 2, now)));
        when(userRatingRepository.findByRatingGreaterThanEqual(4)).thenReturn(List.of(
                rating("other1", "m1", 5, now)));
        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of(
                metadata("m1", "Popular Movie", List.of("Drama"))));

        List<RecommendedMovie> result = service.getRecommendations("u1", 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo(RecommendationSource.POPULAR);
    }
}
