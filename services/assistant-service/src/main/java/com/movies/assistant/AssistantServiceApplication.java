package com.movies.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot application class for assistant-service: a conversational movie assistant.
 * An LLM (OpenAI, through Spring AI) answers each message and calls search-service and
 * recommendation-service as tools along the way. Unlike the other services this one runs on
 * Jackson 3, Spring Boot 4's default; see docs/adr/0013.
 */
@SpringBootApplication
public class AssistantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantServiceApplication.class, args);
    }
}
