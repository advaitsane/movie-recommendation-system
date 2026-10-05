package com.movies.user.dto;

import com.movies.user.service.LoginResult;
import java.time.Instant;
import lombok.Builder;

/**
 * Response body for {@code POST /api/users/login}: the issued JWT plus enough user context
 * that a caller doesn't need a second round trip to know who just logged in.
 */
@Builder
public record AuthResponse(
        String token,
        Long userId,
        String email,
        Instant expiresAt) {

    public static AuthResponse from(LoginResult result) {
        return AuthResponse.builder()
                .token(result.token())
                .userId(result.user().getId())
                .email(result.user().getEmail())
                .expiresAt(result.expiresAt())
                .build();
    }
}
