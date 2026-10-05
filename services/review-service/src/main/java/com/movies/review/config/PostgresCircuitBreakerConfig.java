package com.movies.review.config;

import com.movies.review.exception.DatabaseOperationException;
import com.movies.review.exception.ResourceNotFoundException;
import com.movies.review.exception.ReviewAlreadyExistsException;
import com.movies.review.exception.ValidationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Circuit breaker wrapping Postgres calls, shared by {@code ReviewServiceImpl}'s request-path
 * repository calls and {@code OutboxPoller}/{@code OutboxRowPublisher}'s background polling —
 * both hit the same DataSource, so a single breaker gives the poller the benefit of what the
 * request path already learned (and vice versa) instead of each discovering the outage
 * independently. During an outage, HikariCP's connection-timeout (see application.yml) makes
 * every call block that long before failing; once enough calls fail, this opens and fails fast
 * instead, then periodically probes for recovery. Validation/not-found/duplicate-review/
 * serialization outcomes are business results, not database failures, so they don't count toward
 * tripping it — same convention as catalog-service's MongoCircuitBreakerConfig and user-service's
 * PostgresCircuitBreakerConfig.
 */
@Configuration
public class PostgresCircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCircuitBreakerConfig.class);

    @Bean
    public CircuitBreaker postgresCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(
                        ValidationException.class,
                        ResourceNotFoundException.class,
                        ReviewAlreadyExistsException.class,
                        DatabaseOperationException.class,
                        DataIntegrityViolationException.class)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("postgres", config);
        circuitBreaker.getEventPublisher().onStateTransition(
                event -> logger.warn("Postgres circuit breaker state transition: {}", event));
        return circuitBreaker;
    }
}
