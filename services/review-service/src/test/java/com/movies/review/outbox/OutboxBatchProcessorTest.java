package com.movies.review.outbox;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.movies.review.model.OutboxEvent;
import com.movies.review.repository.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the claim-and-publish orchestration: claim a batch, then delegate each row to
 * {@link OutboxRowPublisher} through its proxy (a real Spring-injected collaborator bean, not a
 * self-invoked call) so the NESTED per-row transaction actually applies.
 */
@DisplayName("OutboxBatchProcessor Unit Tests")
class OutboxBatchProcessorTest {

    @Test
    @DisplayName("claimAndPublishBatch delegates every claimed row to OutboxRowPublisher")
    void claimAndPublishBatch_delegatesEachRow() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        OutboxRowPublisher outboxRowPublisher = mock(OutboxRowPublisher.class);
        OutboxProperties outboxProperties = new OutboxProperties(50, 3000L, 500L);

        OutboxEvent event1 = OutboxEvent.builder().id(1L).eventId(UUID.randomUUID())
                .kafkaKey("m1").eventType("REVIEW_CREATED").payload("{}").build();
        OutboxEvent event2 = OutboxEvent.builder().id(2L).eventId(UUID.randomUUID())
                .kafkaKey("m2").eventType("RATING_UPDATED").payload("{}").build();

        when(outboxEventRepository.findBatchForUpdateSkipLocked(50)).thenReturn(List.of(event1, event2));

        OutboxBatchProcessor processor = new OutboxBatchProcessor(outboxEventRepository, outboxRowPublisher, outboxProperties);
        processor.claimAndPublishBatch();

        verify(outboxRowPublisher, times(1)).publishOne(event1);
        verify(outboxRowPublisher, times(1)).publishOne(event2);
        verify(outboxEventRepository).findBatchForUpdateSkipLocked(eq(50));
    }

    @Test
    @DisplayName("claimAndPublishBatch is a no-op when there's nothing unpublished")
    void claimAndPublishBatch_emptyBatch_doesNothing() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        OutboxRowPublisher outboxRowPublisher = mock(OutboxRowPublisher.class);
        OutboxProperties outboxProperties = new OutboxProperties(50, 3000L, 500L);

        when(outboxEventRepository.findBatchForUpdateSkipLocked(50)).thenReturn(List.of());

        OutboxBatchProcessor processor = new OutboxBatchProcessor(outboxEventRepository, outboxRowPublisher, outboxProperties);
        processor.claimAndPublishBatch();

        verify(outboxRowPublisher, times(0)).publishOne(org.mockito.ArgumentMatchers.any());
    }
}
