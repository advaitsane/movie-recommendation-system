package com.movies.recommendation.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.recommendation.config.RecommendationProperties;
import com.movies.recommendation.dto.RecommendedMovie;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside layer for per-user recommendation lists in Redis, keyed {@code rec:{userId}} with
 * a TTL. Stores plain JSON via {@link StringRedisTemplate} rather than Spring Data Redis's
 * polymorphic serializer, since this cache only ever holds one shape. Any read/write failure is
 * caught and logged, never propagated — a miss just means "recompute", consistent with this
 * service's degrade-gracefully design (see docs/adr/0005).
 */
@Component
public class RecommendationCache {

    private static final Logger logger = LoggerFactory.getLogger(RecommendationCache.class);
    private static final String KEY_PREFIX = "rec:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RecommendationCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, RecommendationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(properties.cacheTtlSeconds());
    }

    public Optional<List<RecommendedMovie>> get(String userId) {
        try {
            String json = redisTemplate.opsForValue().get(key(userId));
            if (json == null) {
                return Optional.empty();
            }
            RecommendedMovie[] movies = objectMapper.readValue(json, RecommendedMovie[].class);
            return Optional.of(List.of(movies));
        } catch (Exception ex) {
            logger.warn("Failed to read recommendation cache for user {} — treating as a miss: {}",
                    userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(String userId, List<RecommendedMovie> recommendations) {
        try {
            String json = objectMapper.writeValueAsString(recommendations);
            redisTemplate.opsForValue().set(key(userId), json, ttl);
        } catch (JsonProcessingException ex) {
            logger.warn("Failed to serialize recommendations for user {} — not caching: {}",
                    userId, ex.getMessage());
        } catch (Exception ex) {
            logger.warn("Failed to write recommendation cache for user {}: {}", userId, ex.getMessage());
        }
    }

    public void evict(String userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (Exception ex) {
            logger.warn("Failed to evict recommendation cache for user {}: {}", userId, ex.getMessage());
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
