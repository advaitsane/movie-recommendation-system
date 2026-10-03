package com.movies.search.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

/**
 * Customizes the ObjectMapper used for JSON serialization: disables timestamp dates, registers
 * the JavaTimeModule, and teaches Jackson to write ObjectId as a hex string. This same bean is
 * reused for the Kafka consumer's JsonDeserializer ({@code KafkaConsumerConfig}) so movie.*
 * events deserialize with the same date handling as the REST layer.
 */
@Configuration
public class ObjectMapperConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper =
                new ObjectMapper(new JsonFactory())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .registerModule(new JavaTimeModule());
        SimpleModule module = new SimpleModule();
        module.addSerializer(ObjectId.class, new ObjectIdSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    @Bean
    public DataBufferFactory dataBufferFactory() {
        return new DefaultDataBufferFactory();
    }
}
