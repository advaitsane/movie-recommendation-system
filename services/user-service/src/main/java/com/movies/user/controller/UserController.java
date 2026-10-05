package com.movies.user.controller;

import com.movies.user.dto.AuthResponse;
import com.movies.user.dto.LoginRequest;
import com.movies.user.dto.RecordActivityRequest;
import com.movies.user.dto.RegisterRequest;
import com.movies.user.dto.UserResponse;
import com.movies.user.exception.ForbiddenActivityException;
import com.movies.user.exception.GlobalExceptionHandler;
import com.movies.user.model.User;
import com.movies.user.service.IUserService;
import com.movies.user.service.LoginResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for identity/auth endpoints. Returns raw DTOs, no success envelope; errors are
 * handled by {@link GlobalExceptionHandler}, which returns a lean {@code ErrorResponseDto}.
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "Identity, auth, and activity-event recording")
public class UserController {

    private final IUserService userService;

    public UserController(IUserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "Register a new account",
            description = "Creates a user with a BCrypt-hashed password. Returns 409 if the email is " +
                    "already registered — log in instead."
    )
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @Parameter(description = "Registration data", required = true)
            @Valid @RequestBody RegisterRequest request) {
        User user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @Operation(
            summary = "Log in",
            description = "Exchanges email/password for a signed JWT. Returns 401 for either an unknown " +
                    "email or a wrong password — the same response for both, so a caller can't use this " +
                    "endpoint to enumerate registered emails."
    )
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Parameter(description = "Login credentials", required = true)
            @Valid @RequestBody LoginRequest request) {
        LoginResult result = userService.login(request);
        return ResponseEntity.ok(AuthResponse.from(result));
    }

    @Operation(summary = "Get a user's profile by id")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(
            @Parameter(description = "User id", required = true)
            @PathVariable Long id) {
        User user = userService.getUserById(id);
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @Operation(
            summary = "Record a VIEW or SEARCH activity",
            description = "Publishes a user.activity event to Kafka. Fire-and-forget: a Kafka outage " +
                    "degrades this side effect, it doesn't fail the request (see UserActivityEventPublisher). " +
                    "403s if the caller is authenticated as a different user than the path id."
    )
    @PostMapping("/{id}/activity")
    public ResponseEntity<Void> recordActivity(
            @Parameter(description = "User id the activity belongs to", required = true) @PathVariable Long id,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String requestingUserId,
            @Parameter(description = "Activity to record", required = true)
            @Valid @RequestBody RecordActivityRequest request) {
        // X-User-Id is set only by api-gateway after its own JWT validation (see
        // JwtAuthenticationGlobalFilter) — trust it over the path id when present. Absent when a
        // caller bypasses the gateway (e.g. these unit tests hitting the controller directly, or
        // any future internal service-to-service call); ADR-0006 already documents that as an
        // accepted gap until every caller is required to go through the gateway.
        if (requestingUserId != null && !requestingUserId.equals(String.valueOf(id))) {
            throw new ForbiddenActivityException("Cannot record activity for another user");
        }
        userService.recordActivity(id, request);
        return ResponseEntity.accepted().build();
    }
}
