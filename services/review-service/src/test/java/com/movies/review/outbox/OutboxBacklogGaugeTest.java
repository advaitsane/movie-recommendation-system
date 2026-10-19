package com.movies.review.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.movies.review.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@DisplayName("OutboxBacklogGauge Unit Tests")
class OutboxBacklogGaugeTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxEventRepository repository;
    private OutboxBacklogGauge gauge;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        gauge = new OutboxBacklogGauge(repository, CircuitBreaker.ofDefaults("test-postgres"), meterRegistry);
    }

    @Test
    @DisplayName("Reports the count of unpublished rows")
    void reportsCount() {
        when(repository.countUnpublished()).thenReturn(42L);

        gauge.count();

        assertThat(value()).isEqualTo(42);
    }

    @Test
    @DisplayName("Postgres failing: keeps the last value instead of throwing")
    void keepsLastValueOnFailure() {
        when(repository.countUnpublished()).thenReturn(7L).thenThrow(new DataAccessResourceFailureException("down"));

        gauge.count();
        gauge.count();

        assertThat(value()).isEqualTo(7);
    }

    private double value() {
        return meterRegistry.get("review.outbox.unpublished").gauge().value();
    }
}
