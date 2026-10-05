package com.movies.user.exception;

/**
 * Exception thrown when registration is attempted for an email that already has an account.
 * Results in a 409 Conflict; the caller is expected to log in instead.
 */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
