package com.movies.user.config;

import com.movies.user.exception.InvalidCredentialsException;
import com.movies.user.exception.ResourceNotFoundException;
import com.movies.user.exception.UserAlreadyExistsException;
import com.movies.user.exception.ValidationException;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Circuit breaker wrapping Postgres calls. During an outage, HikariCP's connection-timeout (see
 * application.yml) makes every request block that long before failing; once enough calls fail,
 * this opens and fails fast instead, then periodically probes for recovery. Validation/not-found/
 * duplicate-email outcomes are business results, not database failures, so they don't count
 * toward tripping it — same convention as catalog-service's MongoCircuitBreakerConfig.
 */
@Configuration
public class PostgresCircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(PostgresCircuitBreakerConfig.class);

    /** Holds the breaker only so its metrics can be bound; resilience4j 2.2 binds from a registry. */
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();

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
                        UserAlreadyExistsException.class,
                        InvalidCredentialsException.class,
                        DataIntegrityViolationException.class)
                .build();

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("postgres", config);
        circuitBreaker.getEventPublisher().onStateTransition(
                event -> logger.warn("Postgres circuit breaker state transition: {}", event));
        return circuitBreaker;
    }

    /** State, call outcomes and rejected calls, as resilience4j_circuitbreaker_* (ADR-0008). */
    @Bean
    public MeterBinder circuitBreakerMetrics() {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }
}
