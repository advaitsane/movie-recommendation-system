package com.movies.recommendation.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customizes the ObjectMapper used for JSON serialization/deserialization: disables timestamp
 * dates and registers the JavaTimeModule so {@code Instant} fields (event {@code occurredAt}
 * timestamps) parse correctly. Reused by the Kafka consumers' {@code JsonDeserializer}s so
 * event deserialization matches the REST layer's date handling.
 */
@Configuration
public class ObjectMapperConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper(new JsonFactory())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .registerModule(new JavaTimeModule());
    }
}
