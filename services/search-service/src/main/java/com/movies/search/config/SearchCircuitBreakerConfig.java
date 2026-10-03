package com.movies.search.config;

import com.movies.search.exception.ResourceNotFoundException;
import com.movies.search.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker wrapping MongoDB calls on the read-serving path. During an outage,
 * MongoConfig's 10s serverSelectionTimeout would otherwise make every request block that long;
 * once enough calls fail, this opens and fails fast instead. Validation/not-found outcomes don't
 * count toward tripping it — they're business results, not database failures.
 */
@Configuration
public class SearchCircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(SearchCircuitBreakerConfig.class);

    @Bean
    public CircuitBreaker mongoCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(ValidationException.class, ResourceNotFoundException.class)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("mongodb-search", config);
        circuitBreaker.getEventPublisher().onStateTransition(
                event -> logger.warn("Search MongoDB circuit breaker state transition: {}", event));
        return circuitBreaker;
    }
}
