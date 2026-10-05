package com.movies.review.exception;

/**
 * Exception thrown when a database operation fails unexpectedly.
 *
 * This exception results in a 500 Internal Server Error response.
 */
public class DatabaseOperationException extends RuntimeException {

    public DatabaseOperationException(String message) {
        super(message);
    }
}
