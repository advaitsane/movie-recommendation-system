package com.movies.recommendation.exception;

/**
 * Thrown when request validation fails. Results in a 400 response.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
