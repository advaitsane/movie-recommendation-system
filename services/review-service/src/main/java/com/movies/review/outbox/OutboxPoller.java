package com.movies.review.outbox;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code outbox_events} for unpublished rows and relays them to Kafka — the "relay" half
 * of the transactional outbox pattern ({@code ReviewServiceImpl}'s writes are the other half).
 * The actual claim-and-publish work (including the {@code FOR UPDATE SKIP LOCKED} query and the
 * per-row {@code @Transactional} savepoints) lives in {@link OutboxBatchProcessor}, a separate
 * bean — see its Javadoc for why a circuit breaker has to wrap the call into that bean rather
 * than sit inside an {@code @Transactional} method. Delivery is at-least-once: a crash after a
 * successful send but before commit resends the same {@code eventId} on restart, which a
 * dedupe-by-eventId consumer handles as expected, not an error. An uncaught
 * {@code CallNotPermittedException} here is expected during a Postgres outage: Spring's
 * {@code @Scheduled} logs it and the next fixed-delay tick still fires normally.
 */
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxPoller {

    private final OutboxBatchProcessor outboxBatchProcessor;
    private final CircuitBreaker postgresCircuitBreaker;

    public OutboxPoller(OutboxBatchProcessor outboxBatchProcessor, CircuitBreaker postgresCircuitBreaker) {
        this.outboxBatchProcessor = outboxBatchProcessor;
        this.postgresCircuitBreaker = postgresCircuitBreaker;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:500}")
    public void pollAndPublish() {
        postgresCircuitBreaker.executeRunnable(outboxBatchProcessor::claimAndPublishBatch);
    }
}
