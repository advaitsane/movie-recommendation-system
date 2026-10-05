package com.movies.recommendation;

import com.movies.recommendation.config.RecommendationProperties;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main Spring Boot application class for recommendation-service: consumes catalog-service's
 * movie.* and review-service's review/rating events into its own MongoDB database, blends that
 * with a synchronous content-based call to search-service, and caches results in Redis. Excludes
 * Jackson 3 autoconfiguration since this codebase is written against Jackson 2.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
@EnableConfigurationProperties(RecommendationProperties.class)
@RestController
public class RecommendationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecommendationServiceApplication.class, args);
    }

    /**
     * Root endpoint providing basic information about the API. Hidden from Swagger UI.
     */
    @Hidden
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "recommendation-service",
                "version", "1.0.0",
                "description", "Blended content-based + collaborative-filtering recommendations, cached in Redis",
                "endpoints", Map.of("recommendations", "/api/recommendations/{userId}")
        );
    }
}
