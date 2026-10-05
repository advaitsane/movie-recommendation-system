package com.movies.review.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Kafka producer wiring for the outbox poller. review-service is producer-only, like
 * catalog-service — no {@code @KafkaListener} anywhere, so {@code @EnableKafka} isn't needed
 * (that annotation wires listener containers for consumers; ADR-0002's gap was specific to
 * that). Don't add it on the assumption it's missing.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> reviewEventProducerFactory(ObjectMapper objectMapper) {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                // Values are pre-serialized JSON strings (outbox_events.payload) — plain
                // StringSerializer, not JsonSerializer, since the poller republishes bytes
                // that were already serialized once at write time.
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                // At-least-once delivery: consumers (recommendation-service) are responsible
                // for idempotency via the outbox row's eventId, per this repo's convention.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                // Bound how long a send can block when no broker is reachable.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000
        );

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, String> reviewEventKafkaTemplate(
            ProducerFactory<String, String> reviewEventProducerFactory) {
        return new KafkaTemplate<>(reviewEventProducerFactory);
    }
}
