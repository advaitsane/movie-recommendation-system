package com.movies.assistant.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.movies.assistant.exception.ConversationNotFoundException;
import com.movies.assistant.support.PostgresTestContainersConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Against a real Postgres and the Flyway schema. The window is 4 messages to keep cases short. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "assistant.chat.memory-max-messages=4")
@Import(PostgresTestContainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Postgres chat memory")
class PostgresChatMemoryTest {

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("keeps every message but returns only the newest window, oldest first")
    void keepsEveryMessageReturnsNewestWindow() {
        String id = conversationStore.start("user-1").toString();
        for (int turn = 1; turn <= 3; turn++) {
            chatMemory.add(id, new UserMessage("question " + turn));
            chatMemory.add(id, new AssistantMessage("answer " + turn));
        }

        assertThat(texts(chatMemory.get(id))).containsExactly("question 2", "answer 2", "question 3", "answer 3");
        assertThat(storedCount(id)).isEqualTo(6);
    }

    @Test
    @DisplayName("starts the window on a question, never on an answer")
    void windowStartsOnUserMessage() {
        String id = conversationStore.start("user-1").toString();
        chatMemory.add(id, List.of(new UserMessage("question 1"), new AssistantMessage("answer 1"),
                new UserMessage("question 2"), new AssistantMessage("answer 2"), new UserMessage("question 3")));

        // The newest four start with "answer 1", whose question fell out of the window.
        assertThat(texts(chatMemory.get(id))).containsExactly("question 2", "answer 2", "question 3");
    }

    @Test
    @DisplayName("stores only questions and answers")
    void storesOnlyUserAndAssistantMessages() {
        String id = conversationStore.start("user-1").toString();
        chatMemory.add(id, List.of(new SystemMessage("You are a movie assistant"), new UserMessage("Hi")));

        assertThat(texts(chatMemory.get(id))).containsExactly("Hi");
    }

    @Test
    @DisplayName("clear deletes the messages and keeps the conversation")
    void clearKeepsConversation() {
        UUID id = conversationStore.start("user-1");
        chatMemory.add(id.toString(), new UserMessage("Hi"));

        chatMemory.clear(id.toString());

        assertThat(chatMemory.get(id.toString())).isEmpty();
        conversationStore.requireOwnedBy(id, "user-1");
    }

    @Test
    @DisplayName("a conversation is found only for its owner")
    void conversationFoundOnlyForOwner() {
        UUID id = conversationStore.start("user-1");

        conversationStore.requireOwnedBy(id, "user-1");
        assertThatThrownBy(() -> conversationStore.requireOwnedBy(id, "user-2"))
                .isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> conversationStore.requireOwnedBy(UUID.randomUUID(), "user-1"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    @DisplayName("removes a question left without an answer")
    void removesUnansweredQuestion() {
        UUID id = conversationStore.start("user-1");
        chatMemory.add(id.toString(), List.of(new UserMessage("question 1"), new AssistantMessage("answer 1"),
                new UserMessage("question 2")));

        assertThat(conversationStore.removeUnansweredMessage(id, "question 2")).isTrue();
        assertThat(texts(chatMemory.get(id.toString()))).containsExactly("question 1", "answer 1");
    }

    @Test
    @DisplayName("leaves an answered question, and a different newest question, in place")
    void keepsAnsweredOrDifferentQuestion() {
        UUID id = conversationStore.start("user-1");
        chatMemory.add(id.toString(), List.of(new UserMessage("question 1"), new AssistantMessage("answer 1")));

        assertThat(conversationStore.removeUnansweredMessage(id, "question 1")).isFalse();

        chatMemory.add(id.toString(), new UserMessage("question 2"));
        assertThat(conversationStore.removeUnansweredMessage(id, "something else")).isFalse();
        assertThat(storedCount(id.toString())).isEqualTo(3);
    }

    @Test
    @DisplayName("deletes idle conversations with their messages, and keeps recent ones")
    void deletesIdleConversations() {
        UUID idle = conversationStore.start("user-1");
        UUID recent = conversationStore.start("user-1");
        chatMemory.add(idle.toString(), new UserMessage("old question"));
        chatMemory.add(recent.toString(), new UserMessage("new question"));
        jdbcClient.sql("UPDATE chat_conversation SET last_message_at = now() - interval '91 days' WHERE id = :id")
                .param("id", idle)
                .update();

        int deleted = conversationStore.deleteIdleSince(Instant.now().minus(Duration.ofDays(90)));

        assertThat(deleted).isEqualTo(1);
        assertThat(storedCount(idle.toString())).isZero();
        assertThatThrownBy(() -> conversationStore.requireOwnedBy(idle, "user-1"))
                .isInstanceOf(ConversationNotFoundException.class);
        assertThat(texts(chatMemory.get(recent.toString()))).containsExactly("new question");
    }

    @Test
    @DisplayName("a new message moves the conversation's last activity forward")
    void addMovesLastMessageAt() {
        UUID id = conversationStore.start("user-1");
        jdbcClient.sql("UPDATE chat_conversation SET last_message_at = now() - interval '91 days' WHERE id = :id")
                .param("id", id)
                .update();

        chatMemory.add(id.toString(), new UserMessage("Back again"));

        assertThat(conversationStore.deleteIdleSince(Instant.now().minus(Duration.ofDays(90)))).isZero();
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private int storedCount(String conversationId) {
        return jdbcClient.sql("SELECT count(*) FROM chat_message WHERE conversation_id = :id")
                .param("id", UUID.fromString(conversationId))
                .query(Integer.class)
                .single();
    }
}
