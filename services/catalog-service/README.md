# catalog-service (:8081)

Owns movie documents in MongoDB (`sample_mflix.movies`) and is the system of record for catalog
CRUD. Search, filtering by relevance/text, and recommendations are handled by other services that
build their own read models off this one's Kafka events — this service does not do aggregation
or search itself.

## Overview

- **Owns:** `sample_mflix.movies` in MongoDB.
- **Exposes:** CRUD on `/api/movies` (create, get one, list with exact-match filter/sort/page,
  patch, delete). See `MovieController` or `/swagger-ui.html` for the full contract.
- **Publishes:** `movie.created` / `movie.updated` / `movie.deleted` to Kafka on writes, so
  search-service and recommendation-service can build their own read models instead of sharing
  this database. Delivery is at-least-once; consumers own idempotency.
- **Does not do:** relevance-ranked text/semantic search (that's search-service's
  `GET /api/movies/search`) or reporting/aggregation pipelines (removed — see
  [Architecture decisions](#architecture-decisions)).

## How to run

### Locally (Maven)

```bash
./mvnw spring-boot:run
```

Needs Mongo and Kafka reachable at the defaults below, or overridden via env vars:

| Env var | Default | Notes |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/sample_mflix` | connection string (`spring.mongodb.uri`) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `SERVER_PORT` | `8081` | |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | trace export target (Jaeger) |
| `LOG_LEVEL` | `INFO` | |

`SERVER_PORT` and `LOGGING_FILE_NAME` are Spring Boot's own relaxed-binding names for
`server.port`/`logging.file.name`, so they need no placeholder in `application.yml`.

For anything secret/local-only (e.g. a personal Atlas URI), use a gitignored
`application-local.yml` + `SPRING_PROFILES_ACTIVE=local` rather than a `.env` file.

### Docker / docker-compose

```bash
docker compose build catalog-service
docker compose up -d catalog-service
```

Mongo (`mflix-mongo`) runs as a standalone container outside compose and needs its own
`docker start mflix-mongo` if it isn't already up.

### Tests

```bash
./mvnw clean verify
```

Unit tests plus a Testcontainers-backed integration test (`CatalogServiceIntegrationTest`) that
spins up a real Mongo (`mongodb/mongodb-atlas-local`) unless `MONGODB_URI` is already set, and
exercises `MongoConfig`, `DatabaseVerification`, Kafka producer config, and a full
create-then-read round trip through the REST API.

## API

- Interactive docs: `/swagger-ui.html`, OpenAPI spec at `/api-docs`.
- A ready-to-import Postman collection: [`catalog-service.postman_collection.json`](./catalog-service.postman_collection.json).
- Health/metrics: `/actuator/health`, `/actuator/prometheus`.

## Resilience & observability

- **Circuit breaker on all Mongo-touching calls** (`MongoCircuitBreakerConfig` /
  `MovieServiceImpl`, resilience4j): during a real Mongo outage, the driver's
  `serverSelectionTimeout` (10s, tuned in `MongoConfig`) would otherwise make every request hang
  before failing. Once 5+ calls are seen and the failure rate crosses 50%, the breaker opens and
  fails fast with `503` instead, probing again after 10s. Business outcomes (validation errors,
  not-found, duplicate key) are ignored by the breaker — they aren't database failures.
- **Health reflects real DB state:** `/actuator/health` correctly reports `503 DOWN` during a
  Mongo outage (via Boot's own Mongo health autoconfiguration, wired automatically once a
  `MongoClient` bean exists — see [Architecture decisions](#architecture-decisions)).
- **Verified, not just built:** both behaviors above were confirmed against a real killed Mongo
  container (`docker stop mflix-mongo`), not just unit-tested. See
  [ADR-0011](../../docs/adr/0011-chaos-testing.md).
- Tracing exports via OTLP/HTTP to Jaeger, metrics are Prometheus-scraped — see
  [ADR-0008](../../docs/adr/0008-observability-otel-prometheus-grafana.md).

## Architecture decisions

Full rationale lives in the monorepo's `docs/adr/`; the ones specific to this service:

- [ADR-0001](../../docs/adr/0001-split-catalog-search-from-monolith.md) — why catalog and search
  were split out of the mflix monolith, and why aggregation/reporting endpoints were dropped here
  (they belonged to a monolith-era reporting feature, not the catalog CRUD this service owns).
- [ADR-0011](../../docs/adr/0011-chaos-testing.md) — fault-injection testing (kill/latency/OOM)
  that surfaced and fixed the health-indicator gap, and validated the circuit breaker above.
- `MongoConfig` currently layers a `MongoClientSettingsBuilderCustomizer` (pool/timeout/retry
  tuning) on top of Spring Boot's own Mongo autoconfiguration, rather than extending
  `AbstractMongoClientConfiguration` by hand. The custom subclass was originally needed for
  fine-grained control around aggregation queries; now that this service only does CRUD, Boot's
  autoconfiguration plus the customizer gives the same control while also picking up the Mongo
  health indicator and repository scanning automatically instead of needing to wire them by hand.

## Known data quirks

A handful of documents seeded from the original `sample_mflix` dataset store `year` as a mangled
string (e.g. `"1986è"`) instead of an int. `YearStringToIntegerConverter` handles the valid cases;
documents that don't parse are skipped with a warning rather than failing the whole read.
