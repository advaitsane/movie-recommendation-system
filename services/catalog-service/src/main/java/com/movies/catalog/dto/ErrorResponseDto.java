package com.movies.catalog.dto;

import com.movies.catalog.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;

/**
 * Lean error payload returned by {@link GlobalExceptionHandler}.
 */

public record ErrorResponseDto(
        String apiPath, HttpStatus errorCode, String errorMessage, LocalDateTime errorTime) {}
