package com.movies.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.user.event.UserActivityEvent;
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
 * Kafka producer wiring for {@code user.activity} events. user-service is producer-only — no
 * {@code @KafkaListener} anywhere, so {@code @EnableKafka} is never needed. Uses the
 * application's own {@link ObjectMapper} bean (from {@link ObjectMapperConfig}) as the value
 * serializer, matching catalog-service's producer pattern.
 */
@Configuration
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, UserActivityEvent> userActivityEventProducerFactory(ObjectMapper objectMapper) {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                // At-least-once delivery: a future consumer is responsible for idempotency via
                // UserActivityEvent#eventId, per this repo's documented convention.
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                // Bound how long a request can be held up trying to reach Kafka — without this,
                // send() blocks for the client default (60s) when no broker is reachable.
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000
        );

        DefaultKafkaProducerFactory<String, UserActivityEvent> factory =
                new DefaultKafkaProducerFactory<>(configProps);
        factory.setValueSerializer(new JsonSerializer<>(objectMapper));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, UserActivityEvent> userActivityEventKafkaTemplate(
            ProducerFactory<String, UserActivityEvent> userActivityEventProducerFactory) {
        return new KafkaTemplate<>(userActivityEventProducerFactory);
    }
}
