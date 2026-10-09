package com.movies.assistant;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main Spring Boot application class for assistant-service: a conversational movie assistant.
 * An LLM (OpenAI, through Spring AI) answers each message and calls search-service and
 * recommendation-service as tools along the way. Unlike the other services this one runs on
 * Jackson 3, Spring Boot 4's default; see docs/adr/0013.
 */
@SpringBootApplication
@RestController
public class AssistantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantServiceApplication.class, args);
    }

    /**
     * Root endpoint providing basic information about the API. Hidden from Swagger UI.
     */
    @Hidden
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "assistant-service",
                "version", "1.0.0",
                "description", "Conversational movie assistant backed by an LLM with tool calling",
                "endpoints", Map.of("chat", "POST /api/assistant/chat")
        );
    }
}
