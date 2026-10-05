package com.movies.user.service.impl;

import com.movies.user.dto.LoginRequest;
import com.movies.user.dto.RecordActivityRequest;
import com.movies.user.dto.RegisterRequest;
import com.movies.user.event.UserActivityEventPublisher;
import com.movies.user.event.UserActivityType;
import com.movies.user.exception.InvalidCredentialsException;
import com.movies.user.exception.ResourceNotFoundException;
import com.movies.user.exception.UserAlreadyExistsException;
import com.movies.user.exception.ValidationException;
import com.movies.user.model.User;
import com.movies.user.repository.UserRepository;
import com.movies.user.security.JwtService;
import com.movies.user.service.IUserService;
import com.movies.user.service.LoginResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Service layer for registration, login, profile lookup, and activity-event recording.
 * {@link #login} deliberately throws the same {@link InvalidCredentialsException} (same message)
 * whether the email doesn't exist or the password is wrong — a different error per case would
 * let a caller enumerate registered emails one guess at a time. Postgres calls run through
 * postgresCircuitBreaker (see PostgresCircuitBreakerConfig) so a database outage fails fast
 * instead of blocking every request for HikariCP's connection-timeout.
 */
@Service
public class UserServiceImpl implements IUserService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserActivityEventPublisher activityEventPublisher;
    private final CircuitBreaker postgresCircuitBreaker;

    public UserServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            UserActivityEventPublisher activityEventPublisher,
            CircuitBreaker postgresCircuitBreaker) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.activityEventPublisher = activityEventPublisher;
        this.postgresCircuitBreaker = postgresCircuitBreaker;
    }

    @Override
    public User register(RegisterRequest request) {
        // Deliberately not @Transactional: it's a single userRepository.save() write (Spring
        // Data JPA already wraps that in its own implicit transaction), and a circuit breaker
        // wrapped inside an @Transactional method never sees a connection failure — the
        // transactional proxy opens its connection before this method body runs, so it fails
        // (or hangs) before the breaker call below is ever reached. Verified live: this exact
        // shape left review-service's breaker never opening during a real Postgres outage — see
        // ADR-0011 Update 6. The existsByEmail() pre-check below and the save() call are two
        // separate statements either way; the real atomicity guarantee against a duplicate-email
        // race is the DB's unique constraint, backstopped by
        // GlobalExceptionHandler#handleDataIntegrityViolationException, not this method's
        // transactional boundary.
        return postgresCircuitBreaker.executeSupplier(() -> {
            if (userRepository.existsByEmail(request.email())) {
                throw new UserAlreadyExistsException(
                        "An account with email '%s' already exists".formatted(request.email()));
            }

            Instant now = Instant.now();
            User user = User.builder()
                    .email(request.email())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .displayName(request.displayName())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            return userRepository.save(user);
        });
    }

    @Override
    public LoginResult login(LoginRequest request) {
        User user = postgresCircuitBreaker.executeSupplier(() -> userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        String token = jwtService.issueToken(user.getId(), user.getEmail());
        return new LoginResult(user, token, jwtService.expirationFor(token));
    }

    @Override
    public User getUserById(Long id) {
        return postgresCircuitBreaker.executeSupplier(() -> userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id)));
    }

    @Override
    public void recordActivity(Long userId, RecordActivityRequest request) {
        postgresCircuitBreaker.executeRunnable(() -> {
            if (!userRepository.existsById(userId)) {
                throw new ResourceNotFoundException("User not found: " + userId);
            }
        });

        if (request.type() == UserActivityType.VIEW && (request.movieId() == null || request.movieId().isBlank())) {
            throw new ValidationException("movieId is required for a VIEW activity");
        }
        if (request.type() == UserActivityType.SEARCH && (request.query() == null || request.query().isBlank())) {
            throw new ValidationException("query is required for a SEARCH activity");
        }

        activityEventPublisher.publish(userId, request.type(), request.movieId(), request.query());
    }
}
