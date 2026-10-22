package com.movies.assistant.config;

import com.movies.assistant.memory.PostgresChatMemory;
import com.movies.assistant.tools.MovieTools;
import com.movies.assistant.tools.ToolCallLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one {@link ChatClient} the assistant uses: the system prompt, the movie tools and chat
 * memory are set once here, so the controller only adds the user's message and the per-request
 * conversation id and user id.
 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class ChatClientConfig {

    /** Replaces Spring AI's in-heap ChatMemory with Postgres (see docs/adr/0013). */
    @Bean
    public ChatMemory chatMemory(JdbcClient jdbcClient, AssistantProperties properties) {
        return new PostgresChatMemory(jdbcClient, properties.memoryMaxMessages());
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory, MovieTools movieTools,
                                 @Value("classpath:prompts/system.st") Resource systemPrompt) {
        return builder
                .defaultSystem(systemPrompt)
                .defaultToolCallbacks(ToolCallLog.recording(ToolCallbacks.from(movieTools)))
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
