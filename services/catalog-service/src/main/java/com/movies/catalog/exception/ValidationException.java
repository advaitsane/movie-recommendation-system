package com.movies.catalog.exception;

/**
 * Thrown when request validation fails. Maps to a 400 Bad Request via
 * GlobalExceptionHandler.
 */
public class ValidationException extends RuntimeException {
    
    public ValidationException(String message) {
        super(message);
    }
}

