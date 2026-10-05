package com.movies.review;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owns reviews/ratings in Postgres, publishing review.created/rating.updated to Kafka via a
 * transactional outbox (see {@code OutboxPoller}). {@code @EnableScheduling} activates its
 * poller. Excludes Boot 4's Jackson 3 autoconfiguration since this service and spring-kafka's
 * {@code JsonSerializer} are written against Jackson 2, which Jackson 3 would otherwise
 * silently override in Spring MVC's message conversion.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
@EnableScheduling
@RestController
public class ReviewServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewServiceApplication.class, args);
    }

    @Hidden
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "review-service",
                "version", "1.0.0",
                "description", "Review/rating CRUD backed by Postgres, published to Kafka via a transactional outbox",
                "endpoints", Map.of("reviews", "/api/reviews")
        );
    }
}
