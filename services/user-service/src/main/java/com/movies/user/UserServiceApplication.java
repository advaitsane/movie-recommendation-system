package com.movies.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * Main Spring Boot application class for user-service — owns user identity/credentials in
 * Postgres, issues JWTs on login, and publishes {@code user.activity} events to Kafka
 * fire-and-forget, no outbox (see ADR-0006). Excludes Jackson 3 auto-configuration since this
 * service is written against Jackson 2, like the rest of the repo.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
