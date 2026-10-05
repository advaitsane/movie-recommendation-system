# review-service (:8083)

**Owns:** reviews/ratings, one row per (user_id, movie_id), in Postgres (`mflix_reviews`) —
deliberately not Mongo, see [ADR-0003](../../docs/adr/0003-postgres-for-review-service.md).
Writes to `reviews` and Kafka are kept atomic via a transactional outbox — see
[ADR-0004](../../docs/adr/0004-outbox-pattern-review-service.md).

## How to run
- **Local:** `docker compose up -d postgres kafka` then `SPRING_PROFILES_ACTIVE=local ./mvnw
  spring-boot:run`. No `application-local.yml` is needed — unlike search-service, this service
  has no secrets to hold (`POSTGRES_URL`/`KAFKA_BOOTSTRAP_SERVERS` are non-sensitive and already
  defaulted).
- **Docker/compose:** `docker compose up -d postgres kafka review-service` from the repo root.
- **Tests:** `./mvnw test` — unit tests plus Testcontainers/EmbeddedKafka-backed integration
  tests (`ReviewServiceIntegrationTest`, `OutboxKafkaIntegrationTest`) against a real, ephemeral
  Postgres and Kafka broker.
- **Env vars:** `POSTGRES_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`,
  `CORS_ORIGINS`.

## API
Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/api-docs`. A Postman collection is at
[`postman/review-service.postman_collection.json`](postman/review-service.postman_collection.json)
— import it, no environment needed (`baseUrl`/`gatewayUrl` collection variables default to
`localhost:8083`/`:8080`). One folder hits review-service directly (full CRUD plus validation/404/409
error cases); the other goes through api-gateway to exercise JWT enforcement (401 without a token,
201/200/204 with one from a freshly registered user). Verified with `newman run` against the live
local stack — all 22 requests/32 assertions pass.

```
POST   /api/reviews             Create a review (409 if one already exists for this user+movie)
GET    /api/reviews/{id}        Get a single review
GET    /api/reviews             List reviews, filtered by movieId and/or userId (at least one required)
PATCH  /api/reviews/{id}        Update rating and/or reviewText
DELETE /api/reviews/{id}        Delete a review
```

### Known v1 limitations (documented, not accidental)

- **Deletes publish no event.** There is no `review.deleted` topic or consumer anywhere in
  this system, so an outbox row for it would never be read. A deleted review's earlier
  influence on a recommendation profile can linger — the same class of gap ADR-0002 already
  documents for search-service's missing tombstone-on-delete handling. Revisit if/when a
  consumer actually needs it.
- **A text-only update publishes no event.** `PATCH /api/reviews/{id}` only emits
  `rating.updated` when the numeric rating actually changes, since recommendation-service's
  stated use case ("update a per-user profile vector on each event") only consumes the rating
  signal, not review prose.

## Resilience & observability
- **Postgres outage** — every `ReviewRepository`/`OutboxEventRepository` call runs through
  `postgresCircuitBreaker` (`PostgresCircuitBreakerConfig`), shared by the request path
  (`ReviewServiceImpl` → `ReviewWriteOperations`) and the outbox poller (`OutboxPoller` →
  `OutboxBatchProcessor`), so either side learns from the other's failures. HikariCP's
  `connection-timeout` is tuned to 3s (down from the 10x-slower 30s library default, which also
  made `/actuator/health` itself take 30s to report DOWN). `CallNotPermittedException` maps to a
  clean 503 instead of leaking a raw Hikari exception message. Validation/not-found/duplicate-
  review/serialization outcomes are excluded so they don't trip it. **The circuit breaker cannot
  simply sit inside an `@Transactional` method** — the transactional proxy opens its connection
  before the method body runs, so a breaker wrapped inside would never see that failure. The
  actual DB writes live in separate beans (`ReviewWriteOperations`, `OutboxBatchProcessor`),
  called *through* the breaker from a non-transactional caller. Verified live with a real
  `docker stop` on the `postgres` container — see
  [ADR-0011 Update 6](../../docs/adr/0011-chaos-testing.md), which also caught this exact gap on
  a first attempt that looked correct but never actually opened.
- **Outbox delivery to Kafka** is already retry-forever by design: a failed send leaves a row
  unpublished (`OutboxRowPublisher`), and the next poll tick (`app.outbox.poll-delay-ms`, 500ms)
  tries again — no additional circuit breaker needed for the Kafka side specifically.
- **Residual gap:** `OutboxRowPublisher.markPublished`'s breaker wrap helps when the connection
  pool is exhausted (other callers waiting), but a connection that goes stale or hangs *mid*-
  transaction (already checked out, e.g. Postgres becoming unresponsive after the transaction
  opened) is a different failure mode — a query/socket timeout, not a pool-checkout timeout —
  that HikariCP's `connection-timeout` doesn't govern. Not addressed in this pass.

## Architecture decisions
- [ADR-0003](../../docs/adr/0003-postgres-for-review-service.md) — why Postgres, not Mongo.
- [ADR-0004](../../docs/adr/0004-outbox-pattern-review-service.md) — the outbox pattern design.
- [ADR-0011](../../docs/adr/0011-chaos-testing.md) — Update 6 covers this service's Postgres
  resilience hardening, including the `@Transactional`/circuit-breaker ordering gap it caught.

## Known quirks
- `GlobalExceptionHandler#handleDataIntegrityViolationException` only reports 409 ("already
  exists") when the violated constraint is actually `uq_reviews_user_movie`, checked via the
  wrapped Hibernate `ConstraintViolationException#getConstraintName()`. Any other
  `DataIntegrityViolationException` (a `movie_id` longer than its `VARCHAR(24)` column, say)
  returns 400 instead — found live while building `postman/review-service.postman_collection.json`
  (an oversized test `movieId` came back as a misleading "already exists" 409), fixed, and
  covered by `GlobalExceptionHandlerTest`.
- Two `PlatformTransactionManager` beans share one `DataSource` (`TransactionConfig`): the
  primary JPA one for `reviews` + `outbox_events` inserts (atomic with each other), and a plain
  JDBC one (`outboxTransactionManager`) solely because `OutboxRowPublisher` needs real
  `Propagation.NESTED` savepoints, which `JpaTransactionManager` can't provide in this
  Spring/Hibernate pairing (verified via bytecode, not assumed).
- `OutboxRowPublisher` and `OutboxBatchProcessor` must be called through their Spring-managed
  bean references, never self-invoked from `OutboxPoller` — `@Transactional` only takes effect
  through the proxy, so a self-invoked call would silently skip the transaction/savepoint
  entirely.
