package com.movies.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * Main Spring Boot application class for search-service. Owns a synced read model
 * (sample_mflix_search.movies_search) built from catalog-service's movie.* Kafka events, never
 * by querying catalog-service's database directly (see docs/adr/0001). Excludes Boot 4's default
 * Jackson 3 autoconfiguration since {@code ObjectMapperConfig}'s {@code ObjectIdSerializer} is a
 * Jackson 2 module that Jackson 3 would silently ignore.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
