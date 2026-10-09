# recommendation-service (:8084)

Blends two recommendation signals into one ranked "recommended for you" list: content-based
similarity (via a synchronous call to search-service) and collaborative filtering (computed from
this service's own user-profile vectors). Owns its own MongoDB database and a Redis cache; never
reads catalog-service's or search-service's databases directly.

## Overview

- **Owns:** `sample_mflix_recsys` in its own MongoDB database (user ratings, user genre-weight
  profiles, a synced copy of movie metadata) plus a Redis cache-aside layer for computed
  recommendation lists.
- **Exposes:** `GET /api/recommendations/{userId}` — blended content + collaborative
  recommendations, falling back to a popularity list for a cold-start user with no ratings yet.
  See `RecommendationController` or `/swagger-ui.html` for the full contract.
- **Consumes:** `movie.created`/`movie.updated`/`movie.deleted` from catalog-service
  (`MovieMetadataConsumer`, syncing just enough metadata — title/year/poster/genres — to render a
  recommendation) and `review.created`/`rating.updated` from review-service (`RatingEventConsumer`,
  updating the per-user rating history and genre-weight profile that drives collaborative
  filtering), both idempotent by event id.
- **Backfills at startup:** `CatalogBackfillRunner` pages through catalog-service's
  `GET /api/movies` whenever its movie metadata holds fewer movies than catalog-service, so the
  movies seeded from `sample_mflix` (which never produced a `movie.*` event) can be recommended.
  It inserts only missing movies and never overwrites one already synced from Kafka.
- **Does not do:** read catalog-service's or search-service's databases directly (see
  [ADR-0005](../../docs/adr/0005-recommendation-service-own-database-and-blend.md)) — the one
  synchronous inter-service call on the request path is to search-service's
  `GET /api/movies/search/{id}/similar` (`SearchServiceClient`), for the content-based signal only.

## How to run

### Locally (Maven)

```bash
./mvnw spring-boot:run
```

Needs Mongo, Redis, Kafka, and search-service reachable at the defaults below, or overridden via
env vars:

| Env var | Default | Notes |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/sample_mflix_recsys` | connection string (`spring.mongodb.uri`) |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | recommendation cache |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `SERVER_PORT` | `8084` | |
| `SEARCH_SERVICE_URL` | `http://localhost:8082` | the one synchronous inter-service call — content-based signal |
| `CATALOG_SERVICE_URL` | `http://localhost:8081` | startup backfill of movie metadata only, never the request path |
| `CORS_ORIGINS` | `http://localhost:3000` | |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | trace export target (Jaeger) |
| `LOG_LEVEL` | `INFO` | |

`SERVER_PORT` and `LOGGING_FILE_NAME` are Spring Boot's own relaxed-binding names for
`server.port`/`logging.file.name`, so they need no placeholder in `application.yml`.

For anything secret/local-only, use a gitignored `application-local.yml` +
`SPRING_PROFILES_ACTIVE=local` rather than a `.env` file.

### Docker / docker-compose

```bash
docker compose build recommendation-service
docker compose up -d recommendation-service
```

> **Quirk:** this service's Mongo is compose-managed (service name `mongo`, its own container,
> `sample_mflix_recsys`) — a **different** container from `mflix-mongo`, the standalone container
> catalog-service/search-service use for `sample_mflix`. `docker stop mflix-mongo` has no effect on
> this service; use `docker stop movie-recommendation-system-mongo-1` (or `docker compose stop
> mongo`) to exercise this service's own Mongo failure path.

### Tests

```bash
./mvnw clean verify
```

Unit tests plus a Testcontainers-backed integration test (`RecommendationIntegrationTest`) that
spins up a real Mongo. Neither Redis nor search-service runs in that test — both degrade by design
(see [Resilience & observability](#resilience--observability)), so it exercises the
collaborative/popularity paths, which real Mongo data can actually drive.

## API

- Interactive docs: `/swagger-ui.html`, OpenAPI spec at `/api-docs`.
- Health/metrics: `/actuator/health`, `/actuator/prometheus`.
- A Postman collection is at
  [`postman/recommendation-service.postman_collection.json`](postman/recommendation-service.postman_collection.json)
  — import it, no environment needed (`baseUrl`/`gatewayUrl`/`catalogUrl`/`reviewUrl` collection
  variables default to `localhost:8084`/`:8080`/`:8081`/`:8083`). Three folders: direct-to-service
  behavior (cold-start/popularity fallback, `limit` handling, response shape), JWT enforcement
  through api-gateway, and an end-to-end folder that seeds a movie via catalog-service and a rating
  via review-service and re-checks this user's recommendations — since Kafka consumption is async,
  its final assertions are deliberately lenient about timing (see the folder's own description; a
  manual re-send of the last request shows the personalized result once the consumers catch up).
  Verified with `newman run` against the live local stack — all 12 requests/18 assertions pass.

## Resilience & observability

- **Circuit breaker on this service's own Mongo calls** (`MongoCircuitBreakerConfig` /
  `RecommendationServiceImpl`, resilience4j) — added during this service's hardening pass; it had
  none before, unlike catalog-service/search-service. A single `mongoCircuitBreaker.executeSupplier`
  span wraps the whole per-request read (across the ratings/profiles/metadata repositories) rather
  than one span per repository call, since one recommendation request reads all three as a single
  cohesive unit of work. Once 5+ calls are seen and the failure rate crosses 50%, the breaker opens
  and fails fast with `503` instead, probing again after 10s.
- **Mongo timeouts tuned, not left at driver defaults** (`MongoConfig`) — also added this pass.
  **Verified, not just built:** a real `docker stop` of this service's own `mongo` container
  first exposed the gap (untuned driver defaults left every failed call hanging ~15s, and
  `/actuator/health` took ~30s to resolve to `503` — over a minute of degraded latency before the
  breaker had even seen its 5th failure) and then confirmed the fix (10s-bounded connect/
  server-selection timeout, matching catalog-service's/search-service's already-tuned value):
  failed calls now return in ~10s, the breaker opens after ~4-5 calls, `/actuator/health` resolves
  to `503` in ~10s, and everything recovered cleanly (`CLOSED → OPEN → HALF_OPEN → CLOSED`,
  `/actuator/health` back to `200`) within seconds of the container returning.
- **Redis cache fails soft, with a bounded timeout** (`RecommendationCache`) — any cache read/write
  failure is caught and logged rather than propagated (a miss just means "recompute"), and
  `spring.data.redis.connect-timeout`/`timeout` (2000ms/1000ms) bound how long a *hung* Redis can
  block a request before that catch even triggers — the try/catch alone only bounds thrown
  exceptions, not how long the call takes to throw one. **Verified**: a real `docker stop` of the
  `redis` container produced `200`s in ~2.1s (bounded, not hanging) with "Redis command timed out —
  treating as a miss" logged, and a clean, silent recovery once Redis returned.
  `RecommendationCache`'s Javadoc has the write-up.
- **search-service call degrades to collaborative-only, not an error** (`SearchServiceClient`,
  `SearchClientConfig`) — bounded connect/read timeouts (2s/3s) plus a catch-all that turns any
  failure (timeout, connection refused, 4xx/5xx, a movie with no embedding yet) into an empty
  content-based signal. **Verified against three distinct real failures** (outright kill, 8s
  injected network latency, an OOM kill) in
  [ADR-0011](../../docs/adr/0011-chaos-testing.md) Experiments 2-4 — degraded gracefully in all
  three, confirming the pattern [ADR-0010](../../docs/adr/0010-load-test-timeout-budget-mismatch.md)
  first inferred from stress-test tail latency.
- **Kafka consumers are independently resilient:** both `MovieMetadataConsumer` and
  `RatingEventConsumer` are idempotent by event id, and neither is wrapped by
  `mongoCircuitBreaker` — consistent with catalog-service's/search-service's scoping decision that
  the breaker covers the request-serving layer only, since the consumers have their own failure
  handling (retry via the consumer group, not a fail-fast breaker).
- Tracing exports via OTLP/HTTP to Jaeger, metrics are Prometheus-scraped — see
  [ADR-0008](../../docs/adr/0008-observability-otel-prometheus-grafana.md).

## Architecture decisions

Full rationale lives in the monorepo's `docs/adr/`; the ones specific to this service:

- [ADR-0005](../../docs/adr/0005-recommendation-service-own-database-and-blend.md) — why this
  service owns its own database and blends content + collaborative signals with exactly one
  synchronous inter-service call.
- [ADR-0008](../../docs/adr/0008-observability-otel-prometheus-grafana.md) — tracing/metrics setup.
- [ADR-0010](../../docs/adr/0010-load-test-timeout-budget-mismatch.md) — load testing; this was the
  one service observed OOM-killed under sustained load, informing its `mem_limit` in
  `docker-compose.yml`.
- [ADR-0011](../../docs/adr/0011-chaos-testing.md) — chaos testing; Experiments 2-4 verified the
  search-service fallback (Update 2 notes this needed no further action). This service's *own*
  Mongo/Redis outage behavior wasn't covered by that round — added and verified during this
  service's own hardening pass (Update 3).
- **No custom `MongoConfig` bypassing autoconfiguration, unlike catalog-service's/search-service's
  earlier (now-superseded) approach.** This service went straight to Boot's own Mongo
  autoconfiguration plus a `MongoClientSettingsBuilderCustomizer` for timeout tuning — the pattern
  ADR-0011 Update 2 moved catalog-service/search-service *to*, not the hand-built
  `AbstractMongoClientConfiguration` approach they moved *away from*. No custom type conversion or
  direct `MongoDatabase` bean is needed here (unlike catalog-service), so this service's
  `MongoConfig` is just the timeout customizer.

## Known quirks

- This service's Mongo is a **separate, compose-managed container** from `mflix-mongo` (the
  standalone container catalog-service/search-service share) — see
  [How to run](#docker--docker-compose) above. Easy to `docker stop` the wrong one when testing a
  Mongo outage.
- A stale/missing recommendation for a movie almost always means neither the startup backfill nor
  `MovieMetadataConsumer` has synced that movie yet (its own copy of metadata, not catalog-service's) — `popularRecommendations`
  and `enrich` both silently skip a candidate with no synced metadata rather than showing a
  blank/broken entry.
- `GET /api/recommendations/{userId}` never errors because of a downstream dependency being down —
  a slow/unreachable search-service or Redis degrades the response rather than failing it (see
  [Resilience & observability](#resilience--observability)). A `503` from this endpoint specifically
  means *this service's own* Mongo circuit breaker is open, not a downstream service.
