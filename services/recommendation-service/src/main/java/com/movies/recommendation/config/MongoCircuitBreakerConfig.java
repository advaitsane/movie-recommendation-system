package com.movies.recommendation.config;

import com.movies.recommendation.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker wrapping this service's own Mongo calls, mirroring catalog-service's and
 * search-service's {@code MongoCircuitBreakerConfig} exactly: once enough calls fail, this opens
 * and fails fast instead of every request hanging on the driver's server-selection timeout.
 * Scoped to {@code RecommendationServiceImpl} only — the Kafka consumers have their own failure
 * handling, and the search-service call has its own separate bounded-timeout degrade story.
 */
@Configuration
public class MongoCircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoCircuitBreakerConfig.class);

    /** Holds the breaker only so its metrics can be bound; resilience4j 2.2 binds from a registry. */
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

    @Bean
    public CircuitBreaker mongoCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(ValidationException.class)
                .build();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("mongodb", config);
        circuitBreaker.getEventPublisher().onStateTransition(
                event -> logger.warn("MongoDB circuit breaker state transition: {}", event));
        return circuitBreaker;
    }

    /** State, call outcomes and rejected calls, as resilience4j_circuitbreaker_* (ADR-0008). */
    @Bean
    public MeterBinder circuitBreakerMetrics() {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }
}
