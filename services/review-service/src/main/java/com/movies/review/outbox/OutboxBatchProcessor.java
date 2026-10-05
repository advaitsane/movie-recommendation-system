package com.movies.review.outbox;

import com.movies.review.config.TransactionConfig;
import com.movies.review.model.OutboxEvent;
import com.movies.review.repository.OutboxEventRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} half of a poll tick — claiming a batch under {@code FOR UPDATE
 * SKIP LOCKED} and relaying each row — kept as a separate bean from {@link OutboxPoller} so
 * {@code postgresCircuitBreaker} (wrapped around the call into this bean, not inside this
 * method) actually sees a Postgres connection failure. Spring's transactional proxy opens its
 * connection before this method body runs, so a breaker wrapped inside it would never see that
 * failure — the same reasoning as {@code ReviewWriteOperations} (see its Javadoc) and the same
 * self-invocation/proxy caveat already documented on {@link OutboxRowPublisher}.
 */
@Component
public class OutboxBatchProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRowPublisher outboxRowPublisher;
    private final OutboxProperties outboxProperties;

    public OutboxBatchProcessor(
            OutboxEventRepository outboxEventRepository,
            OutboxRowPublisher outboxRowPublisher,
            OutboxProperties outboxProperties) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxRowPublisher = outboxRowPublisher;
        this.outboxProperties = outboxProperties;
    }

    @Transactional(TransactionConfig.OUTBOX_TRANSACTION_MANAGER)
    public void claimAndPublishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.findBatchForUpdateSkipLocked(outboxProperties.batchSize());
        for (OutboxEvent event : batch) {
            // Through the outboxRowPublisher proxy, not a self-invoked call — see its Javadoc
            // for why that distinction matters for the NESTED propagation to actually apply.
            outboxRowPublisher.publishOne(event);
        }
    }
}
