package com.movies.recommendation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the blend's tuning knobs ({@link RecommendationProperties}), used by
 * {@code RecommendationServiceImpl} and {@code RecommendationCache}.
 */
@Configuration
@EnableConfigurationProperties(RecommendationProperties.class)
public class RecommendationConfig {
}
