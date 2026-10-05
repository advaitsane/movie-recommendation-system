package com.movies.user.service;

import com.movies.user.model.User;
import java.time.Instant;

/**
 * Internal result of a successful login — the freshly-issued token plus the user it belongs
 * to, so {@code UserController} can build {@code AuthResponse} without a second lookup.
 */
public record LoginResult(User user, String token, Instant expiresAt) {
}
