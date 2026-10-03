package com.movies.catalog.exception;

/**
 * Thrown when a database operation fails unexpectedly. Maps to a 500 Internal Server
 * Error via GlobalExceptionHandler.
 */
public class DatabaseOperationException extends RuntimeException {
    
    public DatabaseOperationException(String message) {
        super(message);
    }
}

