package com.movies.search.exception;

/**
 * Thrown when a requested resource is not found. Results in a 404 response.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
