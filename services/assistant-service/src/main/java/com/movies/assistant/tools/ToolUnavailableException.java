package com.movies.assistant.tools;

/**
 * Thrown by a tool when the service behind it fails or times out. Spring AI's default
 * ToolExecutionExceptionProcessor sends the message back to the model as the tool's result
 * ({@code spring.ai.tools.throw-exception-on-error} stays false), so the model can tell the user
 * that part of the answer is missing instead of the whole conversation failing. The message is
 * written for the model and never includes the underlying error.
 */
public class ToolUnavailableException extends RuntimeException {

    public ToolUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
