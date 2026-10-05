package com.movies.review.model;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A row in the transactional outbox: written in the same local transaction as the
 * {@link Review} write it describes, later published to Kafka by {@code OutboxPoller}.
 * {@code publishedAt == null} is the entire unpublished/published state — no separate status
 * column. Deliberately a plain JDBC-mapped POJO, not a JPA entity: the poller's SKIP LOCKED
 * claim and per-row NESTED savepoint both need JDBC transaction semantics
 * {@code JpaTransactionManager} can't provide here.
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OutboxEvent {

    @ToString.Include
    @EqualsAndHashCode.Include
    private Long id;

    @ToString.Include
    private UUID eventId;

    @Builder.Default
    private String aggregateType = "review";

    private Long aggregateId;

    private String kafkaKey;

    @ToString.Include
    private String eventType;

    private String payload;

    private Instant occurredAt;

    private Instant publishedAt;

    @Builder.Default
    private Integer publishAttempts = 0;
}
