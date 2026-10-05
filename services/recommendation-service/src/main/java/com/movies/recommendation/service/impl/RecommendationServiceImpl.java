package com.movies.recommendation.service.impl;

import com.movies.recommendation.cache.RecommendationCache;
import com.movies.recommendation.client.SearchServiceClient;
import com.movies.recommendation.config.RecommendationProperties;
import com.movies.recommendation.dto.RecommendationSource;
import com.movies.recommendation.dto.RecommendedMovie;
import com.movies.recommendation.dto.SearchSimilarResult;
import com.movies.recommendation.model.MovieMetadata;
import com.movies.recommendation.model.UserProfile;
import com.movies.recommendation.model.UserRating;
import com.movies.recommendation.repository.MovieMetadataRepository;
import com.movies.recommendation.repository.UserProfileRepository;
import com.movies.recommendation.repository.UserRatingRepository;
import com.movies.recommendation.service.IRecommendationService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Blends content-based similarity (a synchronous call to search-service) with collaborative
 * filtering (cosine similarity between user genre-weight profiles) into one ranked list, each
 * signal normalized to [0,1] first so neither dominates by raw magnitude alone. Falls back to a
 * popularity list for cold-start users or when neither signal yields a candidate. All Mongo reads
 * run inside one {@code mongoCircuitBreaker} span per request — see docs/adr/0005 and the README.
 */
@Service
public class RecommendationServiceImpl implements IRecommendationService {

    private static final int MAX_SEED_MOVIES = 3;
    private static final int SIMILAR_FETCH_SIZE = 20;
    private static final int DEFAULT_LIMIT = 20;

    private final UserRatingRepository userRatingRepository;
    private final UserProfileRepository userProfileRepository;
    private final MovieMetadataRepository movieMetadataRepository;
    private final SearchServiceClient searchServiceClient;
    private final RecommendationCache recommendationCache;
    private final RecommendationProperties properties;
    private final CircuitBreaker mongoCircuitBreaker;

    public RecommendationServiceImpl(
            UserRatingRepository userRatingRepository,
            UserProfileRepository userProfileRepository,
            MovieMetadataRepository movieMetadataRepository,
            SearchServiceClient searchServiceClient,
            RecommendationCache recommendationCache,
            RecommendationProperties properties,
            CircuitBreaker mongoCircuitBreaker) {
        this.userRatingRepository = userRatingRepository;
        this.userProfileRepository = userProfileRepository;
        this.movieMetadataRepository = movieMetadataRepository;
        this.searchServiceClient = searchServiceClient;
        this.recommendationCache = recommendationCache;
        this.properties = properties;
        this.mongoCircuitBreaker = mongoCircuitBreaker;
    }

    @Override
    public List<RecommendedMovie> getRecommendations(String userId, Integer limit) {
        int effectiveLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;

        Optional<List<RecommendedMovie>> cached = recommendationCache.get(userId);
        if (cached.isPresent() && cached.get().size() >= effectiveLimit) {
            return cached.get().subList(0, effectiveLimit);
        }

        List<RecommendedMovie> recommendations = mongoCircuitBreaker.executeSupplier(() -> {
            List<UserRating> ratings = userRatingRepository.findByUserId(userId);
            return ratings.isEmpty()
                    ? popularRecommendations(effectiveLimit)
                    : personalizedRecommendations(userId, ratings, effectiveLimit);
        });

        recommendationCache.put(userId, recommendations);
        return recommendations;
    }

    private List<RecommendedMovie> personalizedRecommendations(
            String userId, List<UserRating> ratings, int limit) {
        Set<String> alreadyRated = ratings.stream().map(UserRating::getMovieId).collect(Collectors.toSet());

        Map<String, Double> contentScores = normalize(contentSignal(ratings, alreadyRated));
        Map<String, Double> collabScores = normalize(collaborativeSignal(userId, alreadyRated));

        Set<String> candidateIds = new HashSet<>();
        candidateIds.addAll(contentScores.keySet());
        candidateIds.addAll(collabScores.keySet());

        if (candidateIds.isEmpty()) {
            return popularRecommendations(limit);
        }

        double contentWeight = properties.contentWeight();
        double collabWeight = properties.collabWeight();

        List<ScoredCandidate> scored = new ArrayList<>();
        for (String movieId : candidateIds) {
            double contentScore = contentScores.getOrDefault(movieId, 0.0);
            double collabScore = collabScores.getOrDefault(movieId, 0.0);
            double blended = contentScore * contentWeight + collabScore * collabWeight;
            RecommendationSource source = (contentScore > 0 && collabScore > 0)
                    ? RecommendationSource.BOTH
                    : (contentScore > 0 ? RecommendationSource.CONTENT : RecommendationSource.COLLABORATIVE);
            scored.add(new ScoredCandidate(movieId, blended, source));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
        List<ScoredCandidate> top = scored.size() > limit ? scored.subList(0, limit) : scored;
        return enrich(top);
    }

    private Map<String, Double> contentSignal(List<UserRating> ratings, Set<String> alreadyRated) {
        List<String> seedMovieIds = ratings.stream()
                .filter(r -> r.getRating() != null && r.getRating() >= properties.likeThreshold())
                .sorted(Comparator.comparing(UserRating::getLastEventAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UserRating::getMovieId)
                .distinct()
                .limit(MAX_SEED_MOVIES)
                .toList();

        if (seedMovieIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> scores = new HashMap<>();
        for (String seedMovieId : seedMovieIds) {
            List<SearchSimilarResult> similar = searchServiceClient.findSimilar(seedMovieId, SIMILAR_FETCH_SIZE);
            for (SearchSimilarResult result : similar) {
                if (result.movie() == null || result.movie().id() == null || result.score() == null) {
                    continue;
                }
                if (alreadyRated.contains(result.movie().id())) {
                    continue;
                }
                scores.merge(result.movie().id(), result.score(), Double::sum);
            }
        }
        return scores;
    }

    private Map<String, Double> collaborativeSignal(String userId, Set<String> alreadyRated) {
        Optional<UserProfile> ownProfile = userProfileRepository.findById(userId);
        if (ownProfile.isEmpty() || isEmpty(ownProfile.get().getGenreWeights())) {
            return Map.of();
        }
        Map<String, Double> ownVector = ownProfile.get().getGenreWeights();

        Map<String, Double> similarityByUser = new HashMap<>();
        for (UserProfile other : userProfileRepository.findByUserIdNot(userId)) {
            double similarity = cosineSimilarity(ownVector, other.getGenreWeights());
            if (similarity > 0) {
                similarityByUser.put(other.getUserId(), similarity);
            }
        }

        List<String> topSimilarUserIds = similarityByUser.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(properties.topKSimilarUsers())
                .map(Map.Entry::getKey)
                .toList();

        if (topSimilarUserIds.isEmpty()) {
            return Map.of();
        }

        List<UserRating> likedByPeers = userRatingRepository.findByUserIdInAndRatingGreaterThanEqual(
                topSimilarUserIds, properties.likeThreshold());

        Map<String, Double> scores = new HashMap<>();
        for (UserRating rating : likedByPeers) {
            if (alreadyRated.contains(rating.getMovieId()) || rating.getRating() == null) {
                continue;
            }
            double weight = similarityByUser.getOrDefault(rating.getUserId(), 0.0) * rating.getRating();
            scores.merge(rating.getMovieId(), weight, Double::sum);
        }
        return scores;
    }

    private List<RecommendedMovie> popularRecommendations(int limit) {
        List<UserRating> likedRatings = userRatingRepository.findByRatingGreaterThanEqual(properties.likeThreshold());

        Map<String, Long> countsByMovie = likedRatings.stream()
                .collect(Collectors.groupingBy(UserRating::getMovieId, Collectors.counting()));

        List<Map.Entry<String, Long>> ranked = countsByMovie.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();

        if (ranked.isEmpty()) {
            return List.of();
        }

        double max = ranked.get(0).getValue();
        Map<String, MovieMetadata> metadataById = metadataFor(ranked.stream().map(Map.Entry::getKey).toList());

        List<RecommendedMovie> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : ranked) {
            MovieMetadata metadata = metadataById.get(entry.getKey());
            if (metadata == null) {
                // This service's copy of the movie hasn't been synced by MovieMetadataConsumer
                // yet — skip rather than return a recommendation with no title/poster to show.
                continue;
            }
            results.add(toRecommendedMovie(metadata, entry.getValue() / max, RecommendationSource.POPULAR));
        }
        return results;
    }

    private List<RecommendedMovie> enrich(List<ScoredCandidate> candidates) {
        Map<String, MovieMetadata> metadataById =
                metadataFor(candidates.stream().map(ScoredCandidate::movieId).toList());

        List<RecommendedMovie> results = new ArrayList<>();
        for (ScoredCandidate candidate : candidates) {
            MovieMetadata metadata = metadataById.get(candidate.movieId());
            if (metadata == null) {
                continue;
            }
            results.add(toRecommendedMovie(metadata, candidate.score(), candidate.source()));
        }
        return results;
    }

    private Map<String, MovieMetadata> metadataFor(List<String> movieIds) {
        return movieMetadataRepository.findByIdIn(movieIds).stream()
                .collect(Collectors.toMap(MovieMetadata::getId, m -> m));
    }

    private RecommendedMovie toRecommendedMovie(MovieMetadata metadata, double score, RecommendationSource source) {
        return RecommendedMovie.builder()
                .movieId(metadata.getId())
                .title(metadata.getTitle())
                .year(metadata.getYear())
                .poster(metadata.getPoster())
                .genres(metadata.getGenres())
                .score(score)
                .source(source)
                .build();
    }

    private Map<String, Double> normalize(Map<String, Double> scores) {
        if (scores.isEmpty()) {
            return scores;
        }
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0) {
            return Map.of();
        }
        Map<String, Double> normalized = new HashMap<>();
        scores.forEach((movieId, score) -> normalized.put(movieId, score / max));
        return normalized;
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (isEmpty(a) || isEmpty(b)) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (Map.Entry<String, Double> entry : a.entrySet()) {
            double aValue = entry.getValue();
            normA += aValue * aValue;
            Double bValue = b.get(entry.getKey());
            if (bValue != null) {
                dot += aValue * bValue;
            }
        }
        for (double bValue : b.values()) {
            normB += bValue * bValue;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private boolean isEmpty(Map<String, Double> map) {
        return map == null || map.isEmpty();
    }

    private record ScoredCandidate(String movieId, double score, RecommendationSource source) {
    }
}
