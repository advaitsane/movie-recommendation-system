package com.movies.review.outbox;

import com.movies.review.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code review.outbox.unpublished}: rows written but not yet relayed to Kafka. It climbs while
 * Kafka is down and drains after, the outbox's guarantee made visible. Counted on a schedule, not
 * on scrape, so a hung Postgres can't stall Prometheus; through the breaker, so an outage doesn't
 * hold the scheduler thread the poller also runs on. During one the gauge keeps its last value.
 */
@Component
public class OutboxBacklogGauge {

    private static final Logger logger = LoggerFactory.getLogger(OutboxBacklogGauge.class);

    private final OutboxEventRepository outboxEventRepository;
    private final CircuitBreaker postgresCircuitBreaker;
    private final AtomicLong unpublished = new AtomicLong();

    public OutboxBacklogGauge(
            OutboxEventRepository outboxEventRepository,
            CircuitBreaker postgresCircuitBreaker,
            MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.postgresCircuitBreaker = postgresCircuitBreaker;
        Gauge.builder("review.outbox.unpublished", unpublished, AtomicLong::doubleValue)
                .description("Outbox rows not yet published to Kafka, as of the last count")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.backlog-count-delay-ms:15000}")
    public void count() {
        try {
            unpublished.set(postgresCircuitBreaker.executeSupplier(outboxEventRepository::countUnpublished));
        } catch (Exception e) {
            logger.debug("Couldn't count the outbox backlog: {}", e.getMessage());
        }
    }
}
