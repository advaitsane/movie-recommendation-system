package com.movies.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One user message. Omit {@code conversationId} to start a new conversation; the first event of
 * the response carries the id to send with the next message.
 */
public record ChatRequest(
        @NotBlank @Size(max = 2000) String message,
        @Pattern(regexp = "[A-Za-z0-9-]{1,64}", message = "must be 1-64 letters, digits or dashes")
        String conversationId) {
}
