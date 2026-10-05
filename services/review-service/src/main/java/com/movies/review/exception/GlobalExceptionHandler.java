package com.movies.review.exception;

import com.movies.review.dto.ErrorResponseDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Converts exceptions thrown by controllers into a lean {@link ErrorResponseDto} — no
 * success/wrapper envelope, this is the response body itself. Bean-validation failures are the
 * one exception: they return a raw field-to-message map, the shape client form-error code
 * actually wants.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDto> handleResourceNotFoundException(
            ResourceNotFoundException ex, WebRequest request) {
        logger.error("Resource not found: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationException(
            ValidationException ex, WebRequest request) {
        logger.error("Validation error: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ReviewAlreadyExistsException.class)
    public ResponseEntity<ErrorResponseDto> handleReviewAlreadyExistsException(
            ReviewAlreadyExistsException ex, WebRequest request) {
        logger.error("Duplicate review: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex) {
        logger.error("Request validation failed: {}", ex.getMessage());

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return new ResponseEntity<>(fieldErrors, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, WebRequest request) {
        logger.error("Missing request parameter: {}", ex.getMessage());
        String message = String.format("Required parameter '%s' is missing", ex.getParameterName());
        return buildResponse(request, HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(DatabaseOperationException.class)
    public ResponseEntity<ErrorResponseDto> handleDatabaseOperationException(
            DatabaseOperationException ex, WebRequest request) {
        logger.error("Database operation error: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    /**
     * Backstop for the narrow TOCTOU race between {@code ReviewServiceImpl}'s
     * existsByUserIdAndMovieId pre-check and the insert: the database's unique constraint on
     * (user_id, movie_id) is the real guarantee, this turns its violation into the same 409
     * shape as {@link ReviewAlreadyExistsException}. Every other {@link
     * DataIntegrityViolationException} (a column-length or NOT NULL violation, say — verified
     * live: a movie_id longer than the VARCHAR(24) column hit this exact path) is a malformed
     * request, not a duplicate, and gets 400 instead — reporting it as "already exists" would be
     * actively misleading.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleDataIntegrityViolationException(
            DataIntegrityViolationException ex, WebRequest request) {
        if (isReviewUniqueConstraintViolation(ex)) {
            logger.error("Duplicate review (user_id, movie_id) race: {}", ex.getMessage());
            return buildResponse(request, HttpStatus.CONFLICT, "A review for this user and movie already exists");
        }
        logger.error("Data integrity violation: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST,
                "Review data violates a database constraint (e.g. a field is too long or missing)");
    }

    private boolean isReviewUniqueConstraintViolation(DataIntegrityViolationException ex) {
        return ex.getCause() instanceof ConstraintViolationException constraintViolationException
                && "uq_reviews_user_movie".equals(constraintViolationException.getConstraintName());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponseDto> handleCallNotPermittedException(
            CallNotPermittedException ex, WebRequest request) {
        logger.error("Postgres circuit breaker open, rejecting call: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.SERVICE_UNAVAILABLE,
                "Database temporarily unavailable, please try again shortly");
    }

    /**
     * A request body that couldn't be read: malformed JSON, an unknown field, or a value of the
     * wrong type. That's the caller's mistake, so 400, not 500. The parser's own message isn't
     * echoed because it names internal classes; it's logged instead.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {
        logger.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST,
                "Malformed request body: check the JSON syntax, field names and value types");
    }

    /** A path variable or query parameter that doesn't convert to its declared type. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        logger.warn("Type mismatch for parameter '{}': {}", ex.getName(), ex.getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(Exception ex, WebRequest request) {
        // Spring MVC's own request errors (missing header or parameter, unsupported method or
        // media type, unknown path, ...) implement ErrorResponse and carry their own 4xx status.
        // Without this branch they'd all surface as a 500 for what is a client mistake.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            logger.warn("Request rejected with {}: {}", status.value(), ex.getMessage());
            String detail = errorResponse.getBody().getDetail();
            return buildResponse(request, status, detail != null ? detail : status.getReasonPhrase());
        }
        // Not ex.getMessage(): an unexpected exception's message can expose internals (SQL,
        // hostnames, class names) to the caller. The full stack trace is in the log.
        logger.error("Unexpected error occurred", ex);
        return buildResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ErrorResponseDto> buildResponse(
            WebRequest request, HttpStatus status, String message) {
        ErrorResponseDto errorResponseDto = new ErrorResponseDto(
                request.getDescription(false), status, message, LocalDateTime.now());
        return new ResponseEntity<>(errorResponseDto, status);
    }
}
