package com.movies.review.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.review.config.KafkaTopicsProperties;
import com.movies.review.model.OutboxEvent;
import com.movies.review.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Verifies the confirmed-send contract that makes the outbox pattern correct: publishedAt is
 * only ever set after a successful, acknowledged send, and a failed send still records the
 * attempt so publish_attempts can distinguish "never tried" from "tried and failed."
 */
@DisplayName("OutboxRowPublisher Unit Tests")
class OutboxRowPublisherTest {

    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxEventRepository outboxEventRepository;
    private OutboxRowPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        KafkaTopicsProperties topics = new KafkaTopicsProperties("review.created", "rating.updated");
        OutboxProperties outboxProperties = new OutboxProperties(50, 3000L, 500L);
        publisher = new OutboxRowPublisher(kafkaTemplate, topics, outboxEventRepository, outboxProperties,
                CircuitBreaker.ofDefaults("test-postgres-circuit-breaker"));
    }

    private OutboxEvent newEvent(String eventType) {
        return OutboxEvent.builder()
                .id(1L)
                .eventId(UUID.randomUUID())
                .aggregateType("review")
                .aggregateId(1L)
                .kafkaKey("m1")
                .eventType(eventType)
                .payload("{}")
                .publishAttempts(0)
                .build();
    }

    @Test
    @DisplayName("A confirmed send sets publishedAt and increments publishAttempts")
    @SuppressWarnings("unchecked")
    void publishOne_success_setsPublishedAt() {
        when(kafkaTemplate.send(eq("review.created"), eq("m1"), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        OutboxEvent event = newEvent("REVIEW_CREATED");
        publisher.publishOne(event);

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getPublishAttempts()).isEqualTo(1);
        verify(outboxEventRepository).markPublished(event);
    }

    @Test
    @DisplayName("A failed send leaves publishedAt null but still increments publishAttempts")
    void publishOne_failure_leavesPublishedAtNull() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(eq("rating.updated"), eq("m1"), eq("{}"))).thenReturn(failed);

        OutboxEvent event = newEvent("RATING_UPDATED");
        publisher.publishOne(event);

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublishAttempts()).isEqualTo(1);
        verify(outboxEventRepository).markPublished(event);
    }

    @Test
    @DisplayName("An unknown event_type fails without setting publishedAt")
    void publishOne_unknownEventType_doesNotPublish() {
        OutboxEvent event = newEvent("SOMETHING_ELSE");

        publisher.publishOne(event);

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPublishAttempts()).isEqualTo(1);
    }
}
