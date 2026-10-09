package com.movies.assistant.dto;

import com.movies.assistant.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;

/**
 * Lean error payload returned by {@link GlobalExceptionHandler}, for requests rejected before a
 * stream starts. No success/wrapper envelope — this is the response body itself.
 */
public record ErrorResponseDto(
        String apiPath, HttpStatus errorCode, String errorMessage, LocalDateTime errorTime) {}
