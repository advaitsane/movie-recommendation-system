package com.movies.catalog.exception;

/**
 * Thrown when a requested movie doesn't exist. Maps to a 404 Not Found via
 * GlobalExceptionHandler.
 */
public class ResourceNotFoundException extends RuntimeException {
    
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

