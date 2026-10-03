package com.movies.catalog;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main Spring Boot application class for catalog-service.
 * Owns movie documents in MongoDB (sample_mflix.movies) and publishes
 * movie.created/updated/deleted to Kafka on writes so other services (search-service,
 * recommendation-service) can build their own read models instead of sharing this database.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
@RestController
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }

    /**
     * Caps GET /api/movies?size= at 100 regardless of what a client requests.
     */
    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> resolver.setMaxPageSize(100);
    }

    /**
     * Root endpoint providing basic information about the API.
     * Hidden from Swagger UI documentation.
     */
    @Hidden
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "catalog-service",
                "version", "1.0.0",
                "description", "Movie catalog CRUD and reporting aggregations",
                "endpoints", Map.of("movies", "/api/movies")
        );
    }
}
