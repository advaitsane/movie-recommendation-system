package com.movies.recommendation.consumer;

import com.movies.recommendation.cache.RecommendationCache;
import com.movies.recommendation.event.ReviewEvent;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.model.UserProfile;
import com.movies.recommendation.model.UserRating;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.movies.recommendation.repository.UserProfileRepository;
import com.movies.recommendation.repository.UserRatingRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Builds recommendation-service's local "what did this user rate this movie" copy from
 * review-service's review.created/rating.updated events, and recomputes that user's
 * genre-weight {@link UserProfile} from scratch after every upsert (simpler and less bug-prone
 * than incremental deltas). Evicts this user's Redis cache entry so the next request recomputes
 * fresh. Known gap inherited from review-service: a deleted review never publishes an event.
 */
@Component
public class RatingEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(RatingEventConsumer.class);

    private final UserRatingRepository userRatingRepository;
    private final MovieMetadataRepository movieMetadataRepository;
    private final UserProfileRepository userProfileRepository;
    private final RecommendationCache recommendationCache;

    public RatingEventConsumer(
            UserRatingRepository userRatingRepository,
            MovieMetadataRepository movieMetadataRepository,
            UserProfileRepository userProfileRepository,
            RecommendationCache recommendationCache) {
        this.userRatingRepository = userRatingRepository;
        this.movieMetadataRepository = movieMetadataRepository;
        this.userProfileRepository = userProfileRepository;
        this.recommendationCache = recommendationCache;
    }

    @KafkaListener(
            topics = {
                "${app.kafka.topics.review-created}",
                "${app.kafka.topics.rating-updated}"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "reviewEventKafkaListenerContainerFactory"
    )
    public void onReviewEvent(ReviewEvent event) {
        logger.debug("Received {} event for user {} / movie {}", event.eventType(), event.userId(), event.movieId());

        Optional<UserRating> existing = userRatingRepository.findByUserIdAndMovieId(event.userId(), event.movieId());
        if (existing.isPresent() && isStale(event, existing.get())) {
            logger.debug("Dropping stale {} event for user {} / movie {}",
                    event.eventType(), event.userId(), event.movieId());
            return;
        }

        UserRating rating = existing.map(UserRating::getId)
                .map(id -> UserRating.builder()
                        .id(id)
                        .userId(event.userId())
                        .movieId(event.movieId())
                        .rating(event.rating())
                        .lastEventId(event.eventId())
                        .lastEventAt(event.occurredAt())
                        .build())
                .orElseGet(() -> UserRating.builder()
                        .userId(event.userId())
                        .movieId(event.movieId())
                        .rating(event.rating())
                        .lastEventId(event.eventId())
                        .lastEventAt(event.occurredAt())
                        .build());

        userRatingRepository.save(rating);
        recomputeProfile(event.userId());
        recommendationCache.evict(event.userId());

        logger.debug("Applied {} event for user {} / movie {}", event.eventType(), event.userId(), event.movieId());
    }

    private void recomputeProfile(String userId) {
        List<UserRating> ratings = userRatingRepository.findByUserId(userId);
        if (ratings.isEmpty()) {
            return;
        }

        List<String> movieIds = ratings.stream().map(UserRating::getMovieId).toList();
        Map<String, MovieMetadata> metadataByMovieId = new HashMap<>();
        for (MovieMetadata metadata : movieMetadataRepository.findByIdIn(movieIds)) {
            metadataByMovieId.put(metadata.getId(), metadata);
        }

        Map<String, Double> genreWeights = new HashMap<>();
        for (UserRating rating : ratings) {
            MovieMetadata metadata = metadataByMovieId.get(rating.getMovieId());
            if (metadata == null || metadata.getGenres() == null) {
                // Metadata hasn't arrived yet (consumer lag/ordering) — skip this movie's
                // contribution this pass; it self-heals on the next event for this user.
                continue;
            }
            for (String genre : metadata.getGenres()) {
                genreWeights.merge(genre, rating.getRating().doubleValue(), Double::sum);
            }
        }

        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .genreWeights(genreWeights)
                .updatedAt(Instant.now())
                .build();
        userProfileRepository.save(profile);
    }

    private boolean isStale(ReviewEvent event, UserRating existing) {
        return existing.getLastEventAt() != null && !event.occurredAt().isAfter(existing.getLastEventAt());
    }
}
