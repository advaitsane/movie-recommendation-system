package com.movies.search;

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
 * Main Spring Boot application class for search-service. Owns a synced read model
 * (sample_mflix_search.movies_search) built from catalog-service's movie.* Kafka events, never
 * by querying catalog-service's database directly (see docs/adr/0001). Excludes Boot 4's default
 * Jackson 3 autoconfiguration since {@code ObjectMapperConfig}'s {@code ObjectIdSerializer} is a
 * Jackson 2 module that Jackson 3 would silently ignore.
 */
@SpringBootApplication(exclude = JacksonAutoConfiguration.class)
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
@RestController
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

    /**
     * Caps GET /api/movies/search?size= at 100 regardless of what a client requests — matches
     * catalog-service's GET /api/movies. Same bound {@code vectorSearch}/{@code findSimilar}
     * already enforce by hand on their own {@code limit} param.
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
                "name", "search-service",
                "version", "1.0.0",
                "description", "Synced CQRS read model for movie search, consuming catalog-service's movie.* Kafka events",
                "endpoints", Map.of("search", "/api/movies/search")
        );
    }
}
