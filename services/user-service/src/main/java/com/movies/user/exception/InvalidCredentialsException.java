package com.movies.user.exception;

/**
 * Exception thrown when login fails, for either an unknown email or a wrong password.
 * Deliberately one exception/message for both cases — see {@code UserServiceImpl#login} — so
 * the response never reveals whether a given email is registered. Results in a 401 Unauthorized.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
