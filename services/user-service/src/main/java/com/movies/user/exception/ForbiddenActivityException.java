package com.movies.user.exception;

/**
 * Thrown when the identity api-gateway forwards (the {@code X-User-Id} header, set only after
 * its own JWT validation — see JwtAuthenticationGlobalFilter) doesn't match the path {@code {id}}
 * a caller is trying to record activity for. Results in a 403 Forbidden.
 */
public class ForbiddenActivityException extends RuntimeException {

    public ForbiddenActivityException(String message) {
        super(message);
    }
}
