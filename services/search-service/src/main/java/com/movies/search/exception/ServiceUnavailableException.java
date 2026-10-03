package com.movies.search.exception;

/**
 * Thrown when a request depends on an external dependency that's unavailable or unconfigured
 * (e.g. vector search when {@code VOYAGE_API_KEY} isn't set). Results in a 503 response.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
