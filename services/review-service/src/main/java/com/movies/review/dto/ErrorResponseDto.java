package com.movies.review.dto;

import com.movies.review.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;

/**
 * Lean error payload returned by {@link GlobalExceptionHandler}.
 * No success/wrapper envelope — this is the response body itself.
 */
public record ErrorResponseDto(
        String apiPath, HttpStatus errorCode, String errorMessage, LocalDateTime errorTime) {}
