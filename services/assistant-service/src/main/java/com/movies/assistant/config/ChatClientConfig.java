package com.movies.assistant.config;

import com.movies.assistant.tools.MovieTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * The one {@link ChatClient} the assistant uses: the system prompt, the movie tools and chat
 * memory are set once here, so the controller only adds the user's message and the per-request
 * conversation id and user id.
 */
@Configuration
public class ChatClientConfig {

    /**
     * Replaces Spring AI's auto-configured ChatMemory (a 20-message window) to make the window
     * size a property. The repository is still the auto-configured in-memory one: conversations
     * live in this instance's heap and are lost on restart (see docs/adr/0013).
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AssistantProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.memoryMaxMessages())
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, MovieTools movieTools,
                                 @Value("classpath:prompts/system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(movieTools)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
