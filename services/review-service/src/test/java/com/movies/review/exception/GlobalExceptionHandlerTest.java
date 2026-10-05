package com.movies.review.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.movies.review.dto.ErrorResponseDto;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/**
 * Verifies {@link GlobalExceptionHandler#handleDataIntegrityViolationException} only reports 409
 * for the actual (user_id, movie_id) unique-constraint race it exists to backstop — see the
 * handler's Javadoc. Live-verified separately: an oversized movieId hitting the VARCHAR(24)
 * column used to come back as a misleading 409 "already exists" before this fix.
 */
@DisplayName("GlobalExceptionHandler Unit Tests")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final WebRequest request = mock(WebRequest.class);

    @Test
    @DisplayName("uq_reviews_user_movie violation returns 409")
    void uniqueConstraintViolation_returns409() {
        when(request.getDescription(false)).thenReturn("uri=/api/reviews");
        ConstraintViolationException cause =
                new ConstraintViolationException("duplicate key", null, "uq_reviews_user_movie");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("insert failed", cause);

        ResponseEntity<ErrorResponseDto> response = handler.handleDataIntegrityViolationException(ex, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("A review for this user and movie already exists", response.getBody().errorMessage());
    }

    @Test
    @DisplayName("a different constraint violation (e.g. column length) returns 400, not 409")
    void otherConstraintViolation_returns400() {
        when(request.getDescription(false)).thenReturn("uri=/api/reviews");
        ConstraintViolationException cause =
                new ConstraintViolationException("value too long", null, "reviews_movie_id_check");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("insert failed", cause);

        ResponseEntity<ErrorResponseDto> response = handler.handleDataIntegrityViolationException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("a DataIntegrityViolationException with no constraint name available returns 400")
    void noConstraintName_returns400() {
        when(request.getDescription(false)).thenReturn("uri=/api/reviews");
        DataIntegrityViolationException ex = new DataIntegrityViolationException("insert failed");

        ResponseEntity<ErrorResponseDto> response = handler.handleDataIntegrityViolationException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
