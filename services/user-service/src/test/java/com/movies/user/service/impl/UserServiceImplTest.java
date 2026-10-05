package com.movies.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.user.config.JwtProperties;
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
import com.movies.user.service.LoginResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies the decisions that matter in UserServiceImpl: duplicate registration is rejected,
 * login fails identically (same exception, same message) for an unknown email vs. a wrong
 * password, and activity recording validates its type-dependent required field before
 * publishing.
 */
@DisplayName("UserServiceImpl Unit Tests")
class UserServiceImplTest {

    private UserRepository userRepository;
    private UserActivityEventPublisher activityEventPublisher;
    private UserServiceImpl userService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        activityEventPublisher = mock(UserActivityEventPublisher.class);
        JwtService jwtService = new JwtService(new JwtProperties(
                "unit-test-jwt-signing-secret-at-least-32-bytes-long", Duration.ofMinutes(1)));
        userService = new UserServiceImpl(userRepository, passwordEncoder, jwtService, activityEventPublisher,
                CircuitBreaker.ofDefaults("test-postgres-circuit-breaker"));

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(1L);
            }
            return user;
        });
    }

    @Test
    @DisplayName("register throws UserAlreadyExistsException when the email is already registered")
    void register_duplicateEmail_throws() {
        RegisterRequest request = RegisterRequest.builder().email("a@b.com").password("password1").build();
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> userService.register(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("register stores a BCrypt hash, never the raw password")
    void register_success_storesHashedPassword() {
        RegisterRequest request = RegisterRequest.builder().email("a@b.com").password("password1").build();
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);

        User saved = userService.register(request);

        assertThat(saved.getPasswordHash()).isNotEqualTo("password1");
        assertThat(passwordEncoder.matches("password1", saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("login succeeds and returns a token for correct credentials")
    void login_correctCredentials_returnsToken() {
        User existing = User.builder()
                .id(1L).email("a@b.com").passwordHash(passwordEncoder.encode("password1"))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(existing));

        LoginResult result = userService.login(LoginRequest.builder().email("a@b.com").password("password1").build());

        assertThat(result.token()).isNotBlank();
        assertThat(result.user().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("login throws InvalidCredentialsException for an unknown email")
    void login_unknownEmail_throwsInvalidCredentials() {
        when(userRepository.findByEmail("unknown@b.com")).thenReturn(Optional.empty());

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> userService.login(LoginRequest.builder().email("unknown@b.com").password("password1").build()));
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("login throws the same InvalidCredentialsException (same message) for a wrong password")
    void login_wrongPassword_throwsInvalidCredentialsWithSameMessage() {
        User existing = User.builder()
                .id(1L).email("a@b.com").passwordHash(passwordEncoder.encode("password1"))
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(existing));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> userService.login(LoginRequest.builder().email("a@b.com").password("wrong-password").build()));
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("getUserById throws ResourceNotFoundException when missing")
    void getUserById_notFound_throws() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> userService.getUserById(99L));
    }

    @Test
    @DisplayName("recordActivity publishes a VIEW event when movieId is present")
    void recordActivity_view_publishesEvent() {
        when(userRepository.existsById(1L)).thenReturn(true);

        userService.recordActivity(1L, RecordActivityRequest.builder()
                .type(UserActivityType.VIEW).movieId("movie-1").build());

        verify(activityEventPublisher, times(1)).publish(1L, UserActivityType.VIEW, "movie-1", null);
    }

    @Test
    @DisplayName("recordActivity throws ValidationException when a VIEW has no movieId")
    void recordActivity_viewWithoutMovieId_throws() {
        when(userRepository.existsById(1L)).thenReturn(true);

        assertThrows(ValidationException.class, () -> userService.recordActivity(1L,
                RecordActivityRequest.builder().type(UserActivityType.VIEW).build()));
        verify(activityEventPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordActivity throws ValidationException when a SEARCH has no query")
    void recordActivity_searchWithoutQuery_throws() {
        when(userRepository.existsById(1L)).thenReturn(true);

        assertThrows(ValidationException.class, () -> userService.recordActivity(1L,
                RecordActivityRequest.builder().type(UserActivityType.SEARCH).build()));
        verify(activityEventPublisher, never()).publish(any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordActivity throws ResourceNotFoundException for an unknown user")
    void recordActivity_unknownUser_throws() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> userService.recordActivity(99L,
                RecordActivityRequest.builder().type(UserActivityType.SEARCH).query("heist movie").build()));
        verify(activityEventPublisher, never()).publish(any(), any(), any(), any());
    }
}
