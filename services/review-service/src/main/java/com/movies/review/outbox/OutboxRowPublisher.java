package com.movies.review.outbox;

import com.movies.review.config.KafkaTopicsProperties;
import com.movies.review.config.TransactionConfig;
import com.movies.review.model.OutboxEvent;
import com.movies.review.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a single outbox row to Kafka in its own {@code NESTED} (savepoint) transaction.
 * Kept as a separate bean from {@link OutboxPoller}: {@code @Transactional} only takes effect
 * through Spring's proxy, so a self-invoked call from inside {@code OutboxPoller} would
 * silently skip the savepoint. The send is synchronous and bounded ({@code .get(timeout)}) so
 * {@code publishedAt} is only ever set after a confirmed send. A Kafka-side failure is already
 * handled by the outbox's own retry-forever design (caught below, row stays unpublished, the
 * next poll tick tries again) — no circuit breaker needed for that. {@code markPublished}'s
 * Postgres write runs through the same {@code postgresCircuitBreaker} as everything else
 * touching this DataSource (see {@code PostgresCircuitBreakerConfig}).
 */
@Component
public class OutboxRowPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRowPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopicsProperties topics;
    private final OutboxEventRepository outboxEventRepository;
    private final OutboxProperties outboxProperties;
    private final CircuitBreaker postgresCircuitBreaker;

    public OutboxRowPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaTopicsProperties topics,
            OutboxEventRepository outboxEventRepository,
            OutboxProperties outboxProperties,
            CircuitBreaker postgresCircuitBreaker) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.outboxEventRepository = outboxEventRepository;
        this.outboxProperties = outboxProperties;
        this.postgresCircuitBreaker = postgresCircuitBreaker;
    }

    /**
     * On success, sets {@code publishedAt}. On failure, leaves it {@code null} but still
     * records the attempt — a {@code NESTED} rollback here only undoes this row's savepoint,
     * so one row's Kafka failure never blocks an earlier row in the same batch from
     * committing its own {@code publishedAt}.
     */
    @Transactional(value = TransactionConfig.OUTBOX_TRANSACTION_MANAGER, propagation = Propagation.NESTED)
    public void publishOne(OutboxEvent event) {
        try {
            String topic = topicFor(event.getEventType());
            kafkaTemplate.send(topic, event.getKafkaKey(), event.getPayload())
                    .get(outboxProperties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
            event.setPublishedAt(Instant.now());
            logger.debug("Published outbox event {} ({}) to {}", event.getEventId(), event.getEventType(), topic);
        } catch (Exception ex) {
            logger.error(
                    "Failed to publish outbox event {} ({}): {}",
                    event.getEventId(), event.getEventType(), ex.getMessage(), ex);
        }
        event.setPublishAttempts(event.getPublishAttempts() + 1);
        postgresCircuitBreaker.executeRunnable(() -> outboxEventRepository.markPublished(event));
    }

    private String topicFor(String eventType) {
        return switch (eventType) {
            case "REVIEW_CREATED" -> topics.reviewCreated();
            case "RATING_UPDATED" -> topics.ratingUpdated();
            default -> throw new IllegalStateException("Unknown outbox event_type: " + eventType);
        };
    }
}
