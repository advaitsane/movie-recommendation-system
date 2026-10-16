package com.movies.assistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on what a conversation sends to the model. Both bound cost per message: the remembered
 * history is resent on every turn, and every tool result becomes input tokens.
 *
 * @param memoryMaxMessages how many past messages of one conversation are resent to the model
 * @param toolResultLimit   how many movies a single tool call returns to the model
 */
@Validated
@ConfigurationProperties(prefix = "assistant.chat")
public record AssistantProperties(
        @Min(2) @Max(100) int memoryMaxMessages,
        @Positive @Max(50) int toolResultLimit
) {
}
