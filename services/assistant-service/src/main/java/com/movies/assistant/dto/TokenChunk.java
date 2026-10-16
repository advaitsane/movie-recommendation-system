package com.movies.assistant.dto;

/**
 * Data of one {@code token} event: the next piece of the answer. Sent as JSON rather than as the
 * raw text because SSE parsers strip one leading space from each data line, which would glue
 * words together ("Hello" + " world").
 */
public record TokenChunk(String text) {
}
