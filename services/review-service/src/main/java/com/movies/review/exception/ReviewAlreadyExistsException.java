package com.movies.review.exception;

/**
 * Exception thrown when a POST would create a second review for the same (userId, movieId)
 * pair — this service enforces one review per user per movie. Results in a 409 Conflict;
 * the caller is expected to PATCH the existing review instead.
 */
public class ReviewAlreadyExistsException extends RuntimeException {

    public ReviewAlreadyExistsException(String message) {
        super(message);
    }
}
