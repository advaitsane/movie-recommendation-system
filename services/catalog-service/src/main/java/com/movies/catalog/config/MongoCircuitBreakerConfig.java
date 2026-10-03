package com.movies.catalog.config;

import com.mongodb.MongoWriteException;
import com.movies.catalog.exception.ResourceNotFoundException;
import com.movies.catalog.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker wrapping MongoDB calls. During an outage, MongoConfig's serverSelectionTimeout
 * (10s) makes every request block that long before failing; once enough calls fail, this opens
 * and fails fast instead, then periodically probes for recovery. Validation/not-found/duplicate-key
 * outcomes are business results, not database failures, so they don't count toward tripping it.
 */
@Configuration
public class MongoCircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoCircuitBreakerConfig.class);

    @Bean
    public CircuitBreaker mongoCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(
                        ValidationException.class,
                        ResourceNotFoundException.class,
                        MongoWriteException.class)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("mongodb", config);
        circuitBreaker.getEventPublisher().onStateTransition(
                event -> logger.warn("MongoDB circuit breaker state transition: {}", event));
        return circuitBreaker;
    }
}
