package com.movies.assistant.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Chat memory in {@code chat_message}: every message is kept, and the model gets the newest
 * {@code maxMessages}. Conversation ids come from {@link ConversationStore}. See docs/adr/0013.
 */
public class PostgresChatMemory implements ChatMemory {

    private static final Logger logger = LoggerFactory.getLogger(PostgresChatMemory.class);

    private final JdbcClient jdbcClient;
    private final int maxMessages;

    public PostgresChatMemory(JdbcClient jdbcClient, int maxMessages) {
        this.jdbcClient = jdbcClient;
        this.maxMessages = maxMessages;
    }

    /**
     * Appends questions and answers and moves {@code last_message_at} in one statement. The system
     * prompt is added per call, and tool messages never reach the memory advisor.
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        UUID id = UUID.fromString(conversationId);
        for (Message message : messages) {
            MessageType type = message.getMessageType();
            if (type != MessageType.USER && type != MessageType.ASSISTANT) {
                logger.debug("Not storing a {} message in conversation {}", type, id);
                continue;
            }
            int inserted = jdbcClient.sql("""
                            WITH touched AS (
                                UPDATE chat_conversation SET last_message_at = now() WHERE id = :id
                                RETURNING id)
                            INSERT INTO chat_message (conversation_id, role, content)
                            SELECT id, :role, :content FROM touched""")
                    .param("id", id)
                    .param("role", type.name())
                    .param("content", message.getText() != null ? message.getText() : "")
                    .update();
            if (inserted == 0) {
                logger.warn("Conversation {} no longer exists; {} message not stored", id, type);
            }
        }
    }

    /** The newest {@code maxMessages}, oldest first, starting on a question. */
    @Override
    public List<Message> get(String conversationId) {
        List<Message> newestFirst = jdbcClient.sql("""
                        SELECT role, content FROM chat_message
                        WHERE conversation_id = :id
                        ORDER BY id DESC
                        LIMIT :limit""")
                .param("id", UUID.fromString(conversationId))
                .param("limit", maxMessages)
                .query((rs, rowNum) -> toMessage(rs.getString("role"), rs.getString("content")))
                .list();

        List<Message> window = new ArrayList<>(newestFirst.reversed());
        while (!window.isEmpty() && window.getFirst().getMessageType() != MessageType.USER) {
            window.removeFirst();
        }
        return window;
    }

    /** Deletes the messages; the conversation stays. */
    @Override
    public void clear(String conversationId) {
        jdbcClient.sql("DELETE FROM chat_message WHERE conversation_id = :id")
                .param("id", UUID.fromString(conversationId))
                .update();
    }

    private static Message toMessage(String role, String content) {
        return MessageType.USER.name().equals(role) ? new UserMessage(content) : new AssistantMessage(content);
    }
}
