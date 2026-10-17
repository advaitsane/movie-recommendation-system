package com.movies.assistant.exception;

import java.util.UUID;

/** Unknown or another user's conversation; answered with 404. */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID conversationId) {
        super("Conversation " + conversationId + " not found");
    }
}
