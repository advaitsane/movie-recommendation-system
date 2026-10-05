package com.movies.user.event;

import com.movies.user.config.KafkaTopicsProperties;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code user.activity} events, keyed by userId for per-user ordering. Fire-and-forget:
 * a publish failure is logged, not thrown, so a Kafka outage never fails the triggering request —
 * no atomicity guarantee is needed since there's no local write this event must stay consistent
 * with (see ADR-0006).
 */
@Component
public class UserActivityEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(UserActivityEventPublisher.class);

    private final KafkaTemplate<String, UserActivityEvent> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public UserActivityEventPublisher(
            KafkaTemplate<String, UserActivityEvent> kafkaTemplate, KafkaTopicsProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    public void publish(Long userId, UserActivityType type, String movieId, String query) {
        UserActivityEvent event = new UserActivityEvent(
                UUID.randomUUID().toString(), userId, type, movieId, query, Instant.now());

        try {
            kafkaTemplate.send(topics.userActivity(), String.valueOf(userId), event).whenComplete((result, ex) -> {
                if (ex != null) {
                    logger.error("Failed to publish {} activity event for user {}: {}", type, userId, ex.getMessage(), ex);
                } else {
                    logger.debug("Published {} activity event for user {} to {}", type, userId, topics.userActivity());
                }
            });
        } catch (Exception ex) {
            // KafkaTemplate#send can also fail synchronously (e.g. no broker reachable within
            // the producer's bounded max.block.ms) rather than only via the returned future.
            logger.error("Failed to publish {} activity event for user {}: {}", type, userId, ex.getMessage(), ex);
        }
    }
}
