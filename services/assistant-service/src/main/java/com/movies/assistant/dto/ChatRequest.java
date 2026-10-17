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
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "must be a conversation id from an earlier response")
        String conversationId) {
}
