# ADR 0004: Outbox pattern for review-service → Kafka

**Status:** Accepted
**Date:** 2026-09-12

## Context

review-service needs its Postgres write (a new/updated `reviews` row) and its Kafka publish
(`review.created` / `rating.updated`) to appear atomic to consumers — recommendation-service
must never observe a review that exists in Postgres but never got its event, nor an event
with no corresponding row. Postgres and Kafka don't share a distributed transaction
coordinator (no XA/2PC across the two), so the write and the publish can't be one operation.

## Decision

Local-transaction outbox table (`outbox_events`) + polling relay (`OutboxPoller` /
`OutboxRowPublisher`):

- `ReviewServiceImpl`'s create/update methods insert a `reviews` row and an `outbox_events`
  row in the same `@Transactional` method — the atomicity guarantee is ordinary ACID on a
  single Postgres connection, nothing Kafka-specific.
- `OutboxPoller` runs on a `@Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:500}")`
  tick, claims a batch of unpublished rows with `SELECT ... FOR UPDATE SKIP LOCKED` (safe for
  multiple replicas — a second poller skips rows a first has already claimed rather than
  blocking or double-publishing), and delegates each row to `OutboxRowPublisher`.
- `OutboxRowPublisher.publishOne` runs in a `Propagation.NESTED` (savepoint) transaction per
  row: a synchronous, bounded `kafkaTemplate.send(...).get(timeout)` (deliberately not
  fire-and-forget, unlike catalog-service's `MovieEventPublisher` — `published_at` must only
  be set after a *confirmed* send). A failed send rolls back only that row's savepoint
  (`published_at` stays null) without touching already-succeeded earlier rows' commits in the
  same batch.
- **Two `PlatformTransactionManager` beans, not one** (`config/TransactionConfig`): the
  primary, `@Primary` `transactionManager` is JPA-backed (`JpaTransactionManager`, used by
  `ReviewServiceImpl`'s CRUD methods), and a second `outboxTransactionManager`
  (`DataSourceTransactionManager`, plain JDBC) is used exclusively by `OutboxPoller` /
  `OutboxRowPublisher`. `OutboxEventRepository` itself is plain JDBC (`JdbcTemplate`), not
  Spring Data JPA — see Evidence below for why. The primary transaction manager is configured
  with `setDataSource(...)` specifically so `OutboxEventRepository#insert` (a `JdbcTemplate`
  write, called from inside `ReviewServiceImpl`'s JPA-`@Transactional` methods) shares the
  exact same physical connection as the JPA `reviews` write — that shared connection is what
  makes the write side's atomicity guarantee real, not just two separate writes that happen
  to be adjacent in code.
- `event_id` is generated in application code and embedded in the payload at write time, so a
  resend after a crash carries the identical id — consumers dedupe by it
  (recommendation-service's stated contract: "idempotent consumer: dedupe by event id").

## Alternatives considered

- **CDC via Debezium reading Postgres's write-ahead log** — deferred for the same reason
  ADR-0002 deferred it for Mongo's oplog: an extra infrastructure component (a Kafka Connect
  + Debezium connector) for a project with one producer here, and it ties the event schema to
  Postgres's logical-decoding format rather than a clean `ReviewEvent` contract. Worth
  revisiting if a second write-path producer needing the same pattern shows up.
- **Synchronous dual-write** (write the `reviews` row, then call Kafka inline, no outbox
  table at all) — rejected: a crash between the two leaves them permanently inconsistent with
  no recovery path, which is exactly the failure mode the outbox pattern exists to close.
- **Releasing the outbox row locks before the Kafka network call** (claim-then-release,
  rather than holding them for the whole tick) — considered and rejected as unnecessary
  complexity: `SKIP LOCKED` already removes the reason (contention) the usual
  "never hold a DB lock across a network call" advice protects against here. The only cost of
  holding them is another replica's batch occasionally returning fewer rows, not blocking.

## Consequences

- At-least-once delivery, not exactly-once — recommendation-service's consumer must dedupe by
  `eventId`. This is a real, load-bearing assumption, not hand-waved.
- Worst-case publish latency after commit is bounded by the poll cadence (~500ms) plus the
  send timeout (3s) — acceptable for a recommendation-profile update, not acceptable for a
  synchronous request/response path (this service never claims otherwise; all endpoints
  return immediately after the local Postgres commit, before any Kafka send happens).
- No distributed scheduler lock (e.g. ShedLock) yet — running multiple review-service replicas
  means redundant `SELECT ... FOR UPDATE SKIP LOCKED` polling across them. Not a correctness
  issue (locking already prevents double-publish), just wasted duplicate queries. Left on the
  table deliberately, revisit only if replica count grows enough to matter.
- Two intentional scope cuts ride on top of this mechanism rather than being outbox concerns
  themselves: deletes publish no event, and a text-only update publishes no event (see
  `ReviewServiceImpl`'s class Javadoc) — both because no downstream consumer reads them today.

## Evidence

- **A real, reproducible platform limitation, not a hypothetical one:** the first
  implementation used a single `JpaTransactionManager` for everything, with
  `OutboxRowPublisher.publishOne` annotated `@Transactional(propagation = NESTED)`. Every
  attempt failed at runtime with `NestedTransactionNotSupportedException: JpaDialect does not
  support savepoints - check your JPA provider's capabilities` — reproducible regardless of
  `nestedTransactionAllowed`, `HibernateJpaDialect` wiring, or Hibernate's
  `connection.handling_mode` setting (all tried, in that order, before concluding it was a
  dead end). Decompiling `HibernateJpaDialect.beginTransaction()` (Spring Framework 7.0.9,
  Hibernate ORM 7.4.5) confirmed why: it always returns a `SessionTransactionData` object,
  which does not implement Spring's `SavepointManager` interface — so
  `EntityManagerHolder.savepointManager` is never populated, and `JpaTransactionManager`'s
  nested-transaction path is architecturally unreachable through Hibernate in this
  Spring/Hibernate pairing, independent of configuration. Switching `OutboxEventRepository`
  and the poller/publisher's transaction manager to plain JDBC
  (`DataSourceTransactionManager`, which implements real JDBC `Connection.setSavepoint()`
  savepoints natively) resolved it immediately, no further tuning needed.
- **Full write-then-poll-then-publish path verified end to end**
  (`OutboxKafkaIntegrationTest`, real Postgres via Testcontainers + `@EmbeddedKafka`): POST a
  review → `OutboxPoller.pollAndPublish()` invoked directly → the message is consumed from
  `review.created` with its `eventId` matching the outbox row, and that row's `published_at`
  is confirmed non-null in Postgres afterward. All 24 tests (unit + both integration suites)
  pass.
- Not yet captured, worth adding once this runs against the full docker-compose stack: measured
  latency from a review's `created_at` to observed Kafka consume time under the real 500ms
  poll cadence, and a deliberately-injected Kafka outage showing `publish_attempts` climbing
  while `published_at` stays null, then recovering on the next tick once Kafka is reachable
  again.
