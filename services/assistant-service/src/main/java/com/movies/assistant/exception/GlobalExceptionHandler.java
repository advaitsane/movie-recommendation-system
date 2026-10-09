package com.movies.assistant.exception;

import com.movies.assistant.controller.AssistantController;
import com.movies.assistant.dto.ErrorResponseDto;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

/**
 * Converts exceptions thrown before a stream starts into a lean {@link ErrorResponseDto}. Once
 * the stream has started, failures are reported as an {@code error} event instead (see
 * {@link AssistantController}).
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * No X-User-Id header means the request didn't come through api-gateway with a valid token,
     * so it's an authentication failure, not a malformed request.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponseDto> handleMissingRequestHeaderException(
            MissingRequestHeaderException ex, WebRequest request) {
        if (AssistantController.USER_ID_HEADER.equals(ex.getHeaderName())) {
            logger.warn("Request without {} header", AssistantController.USER_ID_HEADER);
            return buildResponse(request, HttpStatus.UNAUTHORIZED,
                    "Missing user identity: call this endpoint through api-gateway with a bearer token");
        }
        return buildResponse(request, HttpStatus.BAD_REQUEST, "Missing request header '" + ex.getHeaderName() + "'");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        logger.warn("Request validation failed: {}", message);
        return buildResponse(request, HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A request body that couldn't be read: malformed JSON or a value of the wrong type. The
     * parser's own message isn't echoed because it names internal classes; it's logged instead.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException ex, WebRequest request) {
        logger.warn("Unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return buildResponse(request, HttpStatus.BAD_REQUEST,
                "Malformed request body: check the JSON syntax, field names and value types");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(Exception ex, WebRequest request) {
        // Spring MVC's own request errors (unsupported method or media type, unknown path, ...)
        // implement ErrorResponse and carry their own 4xx status.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            logger.warn("Request rejected with {}: {}", status.value(), ex.getMessage());
            String detail = errorResponse.getBody().getDetail();
            return buildResponse(request, status, detail != null ? detail : status.getReasonPhrase());
        }
        // Not ex.getMessage(): an unexpected exception's message can expose internals to the
        // caller. The full stack trace is in the log.
        logger.error("Unexpected error occurred", ex);
        return buildResponse(request, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<ErrorResponseDto> buildResponse(
            WebRequest request, HttpStatus status, String message) {
        ErrorResponseDto errorResponseDto = new ErrorResponseDto(
                request.getDescription(false), status, message, LocalDateTime.now());
        // Content type set explicitly: a streaming client sends Accept: text/event-stream, and
        // without a preset type content negotiation finds no JSON match and drops the body.
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(errorResponseDto);
    }
}
