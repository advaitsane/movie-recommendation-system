package com.movies.user;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main Spring Boot application class for user-service — owns user identity/credentials in
 * Postgres, issues JWTs on login, and publishes {@code user.activity} events to Kafka
 * fire-and-forget, no outbox (see ADR-0006). Excludes Jackson 3 auto-configuration since this
 * service is written against Jackson 2, like the rest of the repo.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
@RestController
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Hidden
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "user-service",
                "version", "1.0.0",
                "description", "User identity, JWT auth, and activity-event publishing, backed by Postgres",
                "endpoints", Map.of("users", "/api/users")
        );
    }
}
