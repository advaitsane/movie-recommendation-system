package com.movies.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;

/**
 * Main Spring Boot application class for catalog-service.
 * Owns movie documents in MongoDB (sample_mflix.movies) and publishes
 * movie.created/updated/deleted to Kafka on writes so other services (search-service,
 * recommendation-service) can build their own read models instead of sharing this database.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
