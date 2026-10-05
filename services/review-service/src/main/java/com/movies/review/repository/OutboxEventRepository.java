package com.movies.review.repository;

import com.movies.review.model.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC repository for {@code outbox_events} — deliberately not Spring Data JPA, since
 * this table's row-locking claims and per-row savepoints need transaction semantics {@code
 * JpaTransactionManager} doesn't provide in this stack (see {@code TransactionConfig}).
 * {@link #insert} runs inside {@code ReviewServiceImpl}'s JPA-{@code @Transactional} methods
 * and shares their exact physical connection, not an unrelated autocommit one.
 */
@Repository
public class OutboxEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(OutboxEvent event) {
        jdbcTemplate.update(
                "INSERT INTO outbox_events "
                        + "(event_id, aggregate_type, aggregate_id, kafka_key, event_type, payload, occurred_at, publish_attempts) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                event.getEventId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getKafkaKey(),
                event.getEventType(),
                event.getPayload(),
                Timestamp.from(event.getOccurredAt()),
                event.getPublishAttempts());
    }

    /**
     * Claims a batch of unpublished rows for this poller tick. {@code FOR UPDATE SKIP LOCKED}
     * is what makes concurrent polling (multiple review-service replicas) safe: a second
     * poller skips rows a first poller already has locked instead of blocking on them or
     * double-publishing once both commit.
     */
    public List<OutboxEvent> findBatchForUpdateSkipLocked(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM outbox_events WHERE published_at IS NULL "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                ROW_MAPPER,
                limit);
    }

    public void markPublished(OutboxEvent event) {
        jdbcTemplate.update(
                "UPDATE outbox_events SET published_at = ?, publish_attempts = ? WHERE id = ?",
                event.getPublishedAt() != null ? Timestamp.from(event.getPublishedAt()) : null,
                event.getPublishAttempts(),
                event.getId());
    }

    public Optional<OutboxEvent> findByAggregateId(Long aggregateId) {
        List<OutboxEvent> rows = jdbcTemplate.query(
                "SELECT * FROM outbox_events WHERE aggregate_id = ?", ROW_MAPPER, aggregateId);
        return rows.stream().findFirst();
    }

    private static final RowMapper<OutboxEvent> ROW_MAPPER = OutboxEventRepository::mapRow;

    private static OutboxEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return OutboxEvent.builder()
                .id(rs.getLong("id"))
                .eventId(rs.getObject("event_id", UUID.class))
                .aggregateType(rs.getString("aggregate_type"))
                .aggregateId(rs.getLong("aggregate_id"))
                .kafkaKey(rs.getString("kafka_key"))
                .eventType(rs.getString("event_type"))
                .payload(rs.getString("payload"))
                .occurredAt(rs.getTimestamp("occurred_at").toInstant())
                .publishedAt(publishedAt != null ? publishedAt.toInstant() : null)
                .publishAttempts(rs.getInt("publish_attempts"))
                .build();
    }
}
