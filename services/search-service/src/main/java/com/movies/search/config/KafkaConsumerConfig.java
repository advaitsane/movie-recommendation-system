package com.movies.search.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movies.search.event.MovieEvent;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Kafka consumer wiring for movie.* events. catalog-service's serializer stamps a
 * {@code __TypeId__} header pointing at its own event class, so {@link
 * JsonDeserializer#setUseTypeHeaders} is disabled to always deserialize into this service's own
 * {@link MovieEvent}. Wrapped in {@link ErrorHandlingDeserializer} so a malformed payload logs
 * and skips the record instead of killing the listener container.
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(KafkaTopicsProperties.class)
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, MovieEvent> movieEventConsumerFactory(ObjectMapper objectMapper) {
        JsonDeserializer<MovieEvent> valueDeserializer = new JsonDeserializer<>(MovieEvent.class, objectMapper);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.setRemoveTypeHeaders(false);

        Map<String, Object> configProps = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class
        );

        return new DefaultKafkaConsumerFactory<>(
                configProps, new StringDeserializer(), new ErrorHandlingDeserializer<>(valueDeserializer));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, MovieEvent> movieEventKafkaListenerContainerFactory(
            ConsumerFactory<String, MovieEvent> movieEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, MovieEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(movieEventConsumerFactory);
        return factory;
    }
}
