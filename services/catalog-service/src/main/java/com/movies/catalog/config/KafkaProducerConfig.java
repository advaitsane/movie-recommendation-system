package com.movies.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.catalog.event.MovieEvent;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka producer wiring for movie.* events.
 *
 * Uses the application's own ObjectMapper bean (see ObjectMapperConfig) so events get
 * the same ObjectId-as-hex-string and ISO-8601 date handling as the REST API.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, MovieEvent> movieEventProducerFactory(ObjectMapper objectMapper) {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                // At-least-once delivery: consumers (search-service) are responsible for
                // idempotency via MovieEvent#eventId, per this repo's documented convention.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                // Bound how long a catalog write can be held up trying to reach Kafka — without
                // this, send() blocks for the client default (60s) when no broker is reachable,
                // and MovieEventPublisher's try/catch only helps once that block returns.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000
        );

        DefaultKafkaProducerFactory<String, MovieEvent> factory =
                new DefaultKafkaProducerFactory<>(configProps);
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, MovieEvent> movieEventKafkaTemplate(
            ProducerFactory<String, MovieEvent> movieEventProducerFactory) {
        return new KafkaTemplate<>(movieEventProducerFactory);
    }
}
