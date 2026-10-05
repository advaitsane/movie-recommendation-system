package com.movies.user.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Origins allowed to call this API cross-origin, from {@code CORS_ORIGINS} (comma-separated;
 * Boot binds it to a list).
 */
@Validated
@ConfigurationProperties(prefix = "cors.allowed")
public record CorsProperties(@NotEmpty List<String> origins) {
}
