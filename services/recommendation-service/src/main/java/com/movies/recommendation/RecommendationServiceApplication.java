package com.movies.recommendation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * Main Spring Boot application class for recommendation-service: consumes catalog-service's
 * movie.* and review-service's review/rating events into its own MongoDB database, blends that
 * with a synchronous content-based call to search-service, and caches results in Redis. Excludes
 * Jackson 3 autoconfiguration since this codebase is written against Jackson 2.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class RecommendationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendationServiceApplication.class, args);
    }
}
