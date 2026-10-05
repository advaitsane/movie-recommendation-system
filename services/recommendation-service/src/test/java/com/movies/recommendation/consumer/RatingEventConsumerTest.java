package com.movies.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.recommendation.cache.RecommendationCache;
import com.movies.recommendation.event.ReviewEvent;
import com.movies.recommendation.event.ReviewEventType;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.model.UserProfile;
import com.movies.recommendation.model.UserRating;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.movies.recommendation.repository.UserProfileRepository;
import com.movies.recommendation.repository.UserRatingRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Verifies RatingEventConsumer builds recommendation-service's user_ratings read model from
 * review-service's events and keeps each affected user's genre-weight UserProfile current —
 * the "update a per-user profile vector on each event" behavior the README asks for.
 */
@DisplayName("RatingEventConsumer Unit Tests")
class RatingEventConsumerTest {

    private UserRatingRepository userRatingRepository;
    private MovieMetadataRepository movieMetadataRepository;
    private UserProfileRepository userProfileRepository;
    private RecommendationCache recommendationCache;
    private RatingEventConsumer consumer;

    @BeforeEach
    void setUp() {
        userRatingRepository = mock(UserRatingRepository.class);
        movieMetadataRepository = mock(MovieMetadataRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        recommendationCache = mock(RecommendationCache.class);
        consumer = new RatingEventConsumer(
                userRatingRepository, movieMetadataRepository, userProfileRepository, recommendationCache);

        when(userRatingRepository.findByUserIdAndMovieId(any(), any())).thenReturn(Optional.empty());
    }

    private ReviewEvent event(ReviewEventType type, String userId, String movieId, int rating, Instant occurredAt) {
        return new ReviewEvent(UUID.randomUUID().toString(), type, 1L, userId, movieId, rating, "text", occurredAt);
    }

    @Test
    @DisplayName("REVIEW_CREATED upserts the rating, recomputes the profile, and evicts the cache")
    void reviewCreated_upsertsRecomputesEvicts() {
        Instant now = Instant.now();
        ReviewEvent event = event(ReviewEventType.REVIEW_CREATED, "u1", "m1", 5, now);

        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                UserRating.builder().userId("u1").movieId("m1").rating(5).lastEventAt(now).build()));
        when(movieMetadataRepository.findByIdIn(List.of("m1"))).thenReturn(List.of(
                MovieMetadata.builder().id("m1").genres(List.of("Drama", "Action")).build()));

        consumer.onReviewEvent(event);

        ArgumentCaptor<UserRating> ratingCaptor = ArgumentCaptor.forClass(UserRating.class);
        verify(userRatingRepository).save(ratingCaptor.capture());
        assertThat(ratingCaptor.getValue().getUserId()).isEqualTo("u1");
        assertThat(ratingCaptor.getValue().getMovieId()).isEqualTo("m1");
        assertThat(ratingCaptor.getValue().getRating()).isEqualTo(5);

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getGenreWeights()).containsEntry("Drama", 5.0).containsEntry("Action", 5.0);

        verify(recommendationCache).evict("u1");
    }

    @Test
    @DisplayName("RATING_UPDATED on an existing rating reuses its id instead of creating a duplicate")
    void ratingUpdated_existingRow_reusesId() {
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant now = Instant.now();
        UserRating existing = UserRating.builder().id("existing-id").userId("u1").movieId("m1")
                .rating(3).lastEventAt(earlier).build();
        when(userRatingRepository.findByUserIdAndMovieId("u1", "m1")).thenReturn(Optional.of(existing));
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(existing));
        when(movieMetadataRepository.findByIdIn(any())).thenReturn(List.of());

        ReviewEvent event = event(ReviewEventType.RATING_UPDATED, "u1", "m1", 5, now);
        consumer.onReviewEvent(event);

        ArgumentCaptor<UserRating> captor = ArgumentCaptor.forClass(UserRating.class);
        verify(userRatingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("existing-id");
        assertThat(captor.getValue().getRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("a stale event (not after the existing row's lastEventAt) is dropped")
    void staleEvent_dropped() {
        Instant now = Instant.now();
        UserRating existing = UserRating.builder().id("existing-id").userId("u1").movieId("m1")
                .rating(5).lastEventAt(now).build();
        when(userRatingRepository.findByUserIdAndMovieId("u1", "m1")).thenReturn(Optional.of(existing));

        ReviewEvent staleEvent = event(ReviewEventType.RATING_UPDATED, "u1", "m1", 1, now.minus(1, ChronoUnit.HOURS));
        consumer.onReviewEvent(staleEvent);

        verify(userRatingRepository, never()).save(any());
        verify(recommendationCache, never()).evict(any());
    }

    @Test
    @DisplayName("profile recompute skips a rated movie whose metadata hasn't synced yet")
    void recompute_missingMetadata_skipsContribution() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of(
                UserRating.builder().userId("u1").movieId("m1").rating(5).lastEventAt(now).build()));
        when(movieMetadataRepository.findByIdIn(List.of("m1"))).thenReturn(List.of());

        consumer.onReviewEvent(event(ReviewEventType.REVIEW_CREATED, "u1", "m1", 5, now));

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getGenreWeights()).isEmpty();
    }

    @Test
    @DisplayName("no remaining ratings for the user after upsert skips profile recompute entirely")
    void recompute_noRatings_skipped() {
        Instant now = Instant.now();
        when(userRatingRepository.findByUserId("u1")).thenReturn(List.of());

        consumer.onReviewEvent(event(ReviewEventType.REVIEW_CREATED, "u1", "m1", 5, now));

        verify(userProfileRepository, never()).save(any());
        verify(recommendationCache, times(1)).evict("u1");
    }
}
