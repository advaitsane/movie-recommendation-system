package com.movies.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * Owns reviews/ratings in Postgres, publishing review.created/rating.updated to Kafka via a
 * transactional outbox (see {@code OutboxPoller}). Excludes Boot 4's Jackson 3 autoconfiguration
 * since this service and spring-kafka's {@code JsonSerializer} are written against Jackson 2,
 * which Jackson 3 would otherwise silently override in Spring MVC's message conversion.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class ReviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewServiceApplication.class, args);
    }
}
