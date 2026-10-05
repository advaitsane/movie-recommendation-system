package com.movies.recommendation.exception;

import com.movies.recommendation.dto.ErrorResponseDto;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Converts exceptions thrown by controllers into a lean {@link ErrorResponseDto}.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationException(
            ValidationException ex, WebRequest request) {
        logger.error("Validation error: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponseDto> handleCallNotPermittedException(
            CallNotPermittedException ex, WebRequest request) {
        logger.error("MongoDB circuit breaker is open: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.SERVICE_UNAVAILABLE,
                "Database temporarily unavailable, please retry shortly");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleConstraintViolationException(
            ConstraintViolationException ex, WebRequest request) {
        logger.error("Request validation failed: {}", ex.getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST, ex.getMessage());
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
