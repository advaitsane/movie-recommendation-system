package com.movies.review.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies OutboxPoller's one job: delegate each scheduled tick to {@link OutboxBatchProcessor}
 * through the postgres circuit breaker. The actual claim-and-publish logic is covered by
 * {@link OutboxBatchProcessorTest}.
 */
@DisplayName("OutboxPoller Unit Tests")
class OutboxPollerTest {

    @Test
    @DisplayName("pollAndPublish delegates to OutboxBatchProcessor through the circuit breaker")
    void pollAndPublish_delegatesToBatchProcessor() {
        OutboxBatchProcessor outboxBatchProcessor = mock(OutboxBatchProcessor.class);
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test-postgres-circuit-breaker");

        OutboxPoller poller = new OutboxPoller(outboxBatchProcessor, circuitBreaker);
        poller.pollAndPublish();

        verify(outboxBatchProcessor, times(1)).claimAndPublishBatch();
    }
}
