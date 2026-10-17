package com.movies.assistant.memory;

import com.movies.assistant.exception.ConversationNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code chat_conversation}: who owns a conversation and when it was last used. */
@Repository
public class ConversationStore {

    private final JdbcClient jdbcClient;

    public ConversationStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** The id is random, never chosen by the client. */
    public UUID start(String userId) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO chat_conversation (id, user_id) VALUES (:id, :userId)")
                .param("id", id)
                .param("userId", userId)
                .update();
        return id;
    }

    /**
     * Unknown and someone else's conversation get the same exception, so a caller can't tell
     * whether an id exists.
     */
    public void requireOwnedBy(UUID id, String userId) {
        boolean owned = jdbcClient.sql("""
                        SELECT EXISTS (SELECT 1 FROM chat_conversation WHERE id = :id AND user_id = :userId)""")
                .param("id", id)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
        if (!owned) {
            throw new ConversationNotFoundException(id);
        }
    }

    /**
     * Deletes the newest message if it is the question {@code text}, left without an answer by a
     * failed or cancelled stream. An answered question isn't the newest message, so it stays.
     */
    public boolean removeUnansweredMessage(UUID id, String text) {
        return jdbcClient.sql("""
                        DELETE FROM chat_message
                        WHERE id = (SELECT max(id) FROM chat_message WHERE conversation_id = :id)
                          AND role = 'USER'
                          AND content = :text""")
                .param("id", id)
                .param("text", text)
                .update() > 0;
    }

    /** Messages go with their conversation ({@code ON DELETE CASCADE}). */
    public int deleteIdleSince(Instant cutoff) {
        return jdbcClient.sql("DELETE FROM chat_conversation WHERE last_message_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
