# search-service (:8082)

Owns a synced `movies_search` collection in its own MongoDB database (`sample_mflix_search`) — a
CQRS-style read model built from catalog-service's Kafka events, not a passthrough to
catalog-service's database. Handles text, filtered, and semantic (embedding-based) search;
catalog CRUD is owned by catalog-service.

## Overview

- **Owns:** `sample_mflix_search.movies_search` in its own MongoDB database.
- **Exposes:** `GET /api/movies/search` (text + genre/year/rating filters; paginated with Spring
  Data's standard `page`/`size`/`sort` params, same as catalog-service's `GET /api/movies`),
  `GET /api/movies/search/{id}`, `GET /api/movies/search/vector` (semantic search, `limit`-only —
  see below), `GET /api/movies/search/{id}/similar` (nearest-neighbor by plot, also `limit`-only).
  See `SearchController` or `/swagger-ui.html` for the full contract.
- **Consumes:** `movie.created` / `movie.updated` / `movie.deleted` from catalog-service via
  Kafka (`MovieEventConsumer`), upserting/deleting idempotently by movie id + event timestamp.
  On first boot (or after `docker compose down -v`), `CatalogBackfillRunner` does a one-time
  REST backfill from catalog-service before relying on the Kafka stream going forward.
- **Does not do:** writes to `movies_search` outside the two ingestion paths above, or any
  synchronous call to catalog-service on the request-serving path (only the one-time startup
  backfill calls it — see [Architecture decisions](#architecture-decisions)).

## How to run

### Locally (Maven)

```bash
./mvnw spring-boot:run
```

Needs Mongo and Kafka reachable at the defaults below, or overridden via env vars:

| Env var | Default | Notes |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/sample_mflix_search` | connection string (`spring.mongodb.uri`) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `SERVER_PORT` | `8082` | |
| `CATALOG_SERVICE_URL` | `http://localhost:8081` | used once at startup for backfill only |
| `EMBEDDING_PROVIDER` | `openai` | `voyage` or `openai` — selects which block below is active |
| `VOYAGE_API_KEY` / `OPENAI_API_KEY` | (blank) | only the active provider's key is needed; unset means vector-search endpoints return 503, everything else still works |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | trace export target (Jaeger) |
| `LOG_LEVEL` | `INFO` | |

`SERVER_PORT` and `LOGGING_FILE_NAME` are Spring Boot's own relaxed-binding names for
`server.port`/`logging.file.name`, so they need no placeholder in `application.yml`.

For anything secret/local-only (e.g. a personal embedding-provider key), use a gitignored
`application-local.yml` + `SPRING_PROFILES_ACTIVE=local` rather than a `.env` file.

### Docker / docker-compose

```bash
docker compose build search-service
docker compose up -d search-service
```

Mongo (`mflix-mongo`) runs as a standalone container outside compose and needs its own
`docker start mflix-mongo` if it isn't already up.

### Tests

```bash
./mvnw clean verify
```

Unit tests plus a Testcontainers-backed integration test (`SearchServiceIntegrationTest`) that
spins up a real Mongo (`mongodb/mongodb-atlas-local`) unless `MONGODB_URI` is already set.

## API

- Interactive docs: `/swagger-ui.html`, OpenAPI spec at `/api-docs`.
- A ready-to-import Postman collection: [`search-service.postman_collection.json`](./search-service.postman_collection.json).
- Health/metrics: `/actuator/health`, `/actuator/prometheus`.

## Resilience & observability

- **Circuit breaker on all Mongo-touching calls** (`SearchCircuitBreakerConfig` /
  `SearchServiceImpl`, resilience4j): during a real Mongo outage, the driver's
  `serverSelectionTimeout` (10s, tuned in `MongoConfig`) would otherwise make every request hang
  before failing. Once 5+ calls are seen and the failure rate crosses 50%, the breaker opens and
  fails fast with `503` instead, probing again after 10s. Not-found/validation outcomes are
  ignored by the breaker — they aren't database failures. Scoped to `SearchServiceImpl`
  (the request-serving layer), matching catalog-service's scope — the Kafka consumer and startup
  backfill already have their own independent failure handling (see below).
- **Health reflects real DB state:** `/actuator/health` correctly reports `503 DOWN` during a
  Mongo outage, via Boot's own Mongo health autoconfiguration — wired automatically once a
  `MongoClient` bean exists, see [Architecture decisions](#architecture-decisions).
- **Verified, not just built:** both behaviors above were confirmed against a real killed Mongo
  container (`docker stop mflix-mongo`) — the breaker opened after the 5th failed call, calls
  that previously hung ~10s returned `503` in ~25ms instead, and both the breaker and
  `/actuator/health` recovered cleanly within seconds of `docker start mflix-mongo`. See
  [ADR-0011](../../docs/adr/0011-chaos-testing.md).
- **Embedding provider (Voyage/OpenAI) fails soft:** an unconfigured or failing provider makes
  `GET /api/movies/search/vector` and `/{id}/similar` return `503`, while text/genre/year/rating
  search and the Kafka sync path keep working unaffected — a document simply gets indexed without
  an embedding. `openAiRestClient`/`voyageRestClient`/`catalogRestClient` all carry a
  connect/read timeout (`spring.http.client.*`) so a *hanging* (not just erroring) provider or
  catalog-service can't block a request thread indefinitely — the fail-soft `try`/`catch` alone
  only bounds a thrown exception, not how long the call takes to throw one.
- **Kafka consumer is independently resilient:** idempotent by movie id + event `occurredAt`
  (tolerates at-least-once redelivery and reordering), and `CatalogBackfillRunner`'s one-time
  startup backfill is non-blocking — a failure there is logged, not thrown, so the service still
  starts and keeps consuming the Kafka stream.
- Tracing exports via OTLP/HTTP to Jaeger, metrics are Prometheus-scraped — see
  [ADR-0008](../../docs/adr/0008-observability-otel-prometheus-grafana.md).

## Architecture decisions

Full rationale lives in the monorepo's `docs/adr/`; the ones specific to this service:

- [ADR-0001](../../docs/adr/0001-split-catalog-search-from-monolith.md) — why search was split
  out as its own CQRS read model rather than a passthrough to catalog-service's database.
- **`GET /api/movies/search` uses `Page`/`Pageable`; the two vector-search endpoints don't.**
  Text/filter search is an exact Mongo query (text index + regex/exact-match criteria) against a
  known collection, so `totalElements` is well-defined and cheap to expose via
  `PageableExecutionUtils` — it matches catalog-service's `GET /api/movies` exactly, down to the
  `@EnableSpringDataWebSupport(VIA_DTO)` + `PageableHandlerMethodArgumentResolverCustomizer`
  (100-item page-size cap) setup in `SearchServiceApplication`. `vectorSearch`/`findSimilar` stay
  `limit`-only on purpose: Atlas `$vectorSearch` is approximate nearest-neighbor (ANN) over a
  `numCandidates` pool, not an exact query over the full collection, so there's no meaningful
  "total matches" to report (every document has *some* similarity score) and paging deeper by
  offset isn't guaranteed to return the true next-nearest neighbors the way skip/limit does over
  an exact, indexed query — a single ranked top-N list is the correct shape here instead.
- [ADR-0011](../../docs/adr/0011-chaos-testing.md) — fault-injection testing that found and fixed
  the missing health-indicator gap (Update 1), and the later switch described below (Update 2).
- `MongoConfig` layers a `MongoClientSettingsBuilderCustomizer` (pool/timeout/retry tuning) on
  top of Spring Boot's own Mongo autoconfiguration, rather than extending
  `AbstractMongoClientConfiguration` by hand as it originally did. The hand-built version required
  manually wiring a `MongoHealthIndicator` bean to get outage detection at all (Boot's own
  `MongoHealthContributorAutoConfiguration` never fires when autoconfiguration is bypassed) —
  autoconfig plus the customizer gives the same connection control while picking up the health
  indicator and repository scanning automatically, removing that manual-wiring risk rather than
  just keeping the working patch in place. Re-verified against a real Mongo outage after the
  switch (see above) to confirm no regression.

## Known quirks

- `movies_search` is never written to directly — the Kafka consumer and the one-time startup
  backfill are the only two ingestion paths. If it looks stale or wrong, check catalog-service's
  event publishing and this service's consumer logs, not this service's own write paths (there
  are none on the request-serving side).
- No tombstone for out-of-order deletes: if a create/update for a movie arrives badly out of
  order after that movie's delete event, it resurrects the document (see `MovieEventConsumer`'s
  Javadoc). Accepted for v1; would need an explicit tombstone record to close.
- A `503` from the vector-search endpoints with no other error in the logs almost always means
  the configured embedding provider has no API key set — check `EMBEDDING_PROVIDER` and the
  matching `VOYAGE_API_KEY`/`OPENAI_API_KEY`, not a service bug.
