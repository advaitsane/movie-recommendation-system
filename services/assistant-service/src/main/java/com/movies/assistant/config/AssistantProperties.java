package com.movies.assistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Limits on what a conversation sends to the model, and how long it is kept. The first two bound
 * cost per message: history is resent every turn, and tool results become input tokens.
 *
 * @param memoryMaxMessages how many past messages of one conversation are resent to the model
 * @param toolResultLimit   how many movies a single tool call returns to the model
 * @param retention         how long a conversation is kept after its last message
 * @param streamToolCalls   whether a {@code tools} event with each tool call's arguments and result
 *                          ends the stream, for the evals; off outside them
 */
@Validated
@ConfigurationProperties(prefix = "assistant.chat")
public record AssistantProperties(
        @Min(2) @Max(100) int memoryMaxMessages,
        @Positive @Max(50) int toolResultLimit,
        @NotNull Duration retention,
        boolean streamToolCalls
) {
}
