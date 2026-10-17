package com.movies.assistant.memory;

import com.movies.assistant.config.AssistantProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes conversations idle longer than {@code assistant.chat.retention}. Runs on every replica;
 * the delete is idempotent, so no lock.
 */
@Component
public class ConversationRetentionJob {

    private static final Logger logger = LoggerFactory.getLogger(ConversationRetentionJob.class);

    private final ConversationStore conversationStore;
    private final AssistantProperties properties;

    public ConversationRetentionJob(ConversationStore conversationStore, AssistantProperties properties) {
        this.conversationStore = conversationStore;
        this.properties = properties;
    }

    @Scheduled(cron = "${assistant.chat.retention-cron}")
    public void deleteIdleConversations() {
        int deleted = conversationStore.deleteIdleSince(Instant.now().minus(properties.retention()));
        logger.info("Deleted {} conversation(s) idle for more than {}", deleted, properties.retention());
    }
}
