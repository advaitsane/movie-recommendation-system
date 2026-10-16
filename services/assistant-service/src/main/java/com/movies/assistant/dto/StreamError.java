package com.movies.assistant.dto;

/**
 * Data of the {@code error} event that ends a stream when the model call fails. The HTTP status is
 * already 200 by the time a streamed answer fails, so the failure has to travel in the stream.
 */
public record StreamError(String error, String message) {
}
