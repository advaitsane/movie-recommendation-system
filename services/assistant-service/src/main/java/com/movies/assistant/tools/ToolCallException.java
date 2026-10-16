package com.movies.assistant.tools;

/**
 * Thrown by a tool that can't answer: an unknown id, rejected arguments, or a service that failed
 * or timed out. Spring AI sends the message back to the model as the tool's result
 * ({@code spring.ai.tools.throw-exception-on-error} stays false) instead of failing the answer.
 * The message is written for the model and never includes the underlying error.
 */
public class ToolCallException extends RuntimeException {

    public ToolCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
