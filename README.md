# movie-recommendation-system

[![CI](https://github.com/advaitsane/movie-recommendation-system/actions/workflows/ci.yml/badge.svg)](https://github.com/advaitsane/movie-recommendation-system/actions/workflows/ci.yml)

The MongoDB `sample-app-java-mflix` monolith, rebuilt as a set of services that each own one
domain. The goal is to study reliability and scalability engineering on a realistic system: CQRS
read models, the outbox pattern, circuit breakers and graceful degradation, each one checked with
load tests and chaos experiments rather than assumed.

## Status

The system runs locally end to end. It was published one build step per pull request, so the
history shows how it was built and why. Each PR links the architecture decision records (ADRs)
behind it.

| Step | Contents | Status |
|---|---|---|
| 0 | Repo scaffolding, infrastructure in docker-compose | ✅ |
| 1 | CI: build and test every service on each PR | ✅ |
| 2 | catalog-service and search-service (CQRS read model over Kafka) | ✅ |
| 3 | review-service (Postgres, outbox pattern) | ✅ |
| 4 | recommendation-service (content + collaborative blend, Redis cache) | ✅ |
| 5 | user-service (registration, JWT issuance, activity events) | ✅ |
| 6 | api-gateway (routing, JWT enforcement, rate limiting, circuit breakers) | ✅ |
| 7 | Observability (OpenTelemetry + Jaeger, Prometheus, Grafana) | ✅ |
| 8 | config-server (git-backed); Eureka left out by design | ✅ |
| 9 | Load testing (k6) and per-container memory limits | ✅ |
| 10 | Chaos testing and hardening | ✅ |
| 11 | assistant-service (LLM movie assistant with tool calling, Spring AI) | ✅ |

## Why this project

The original app (`mongodb/sample-app-java-mflix`) is a single Spring Boot service exposing:
- `/api/movies` CRUD
- `/api/movies/aggregations/{reportingByComments,reportingByYear,reportingByDirectors}`
- `/api/movies/search` (Atlas Search text search)
- `/api/movies/vector-search`, `/api/movies/find-similar-movies` (Voyage AI embeddings)

This repo splits that service into independently deployable services. Each owns its own data
store, and they talk to each other over REST (sync) and Kafka (async). Around them sits the
infrastructure a distributed system needs in production: a gateway, resilience, observability and
centralized config.

## Architecture

```
                               ┌──────────────────┐
                               │   API Gateway    │  :8080
                               │ (Spring Cloud GW)│
                               └────────┬─────────┘
                                        │
       ┌───────────────┬────────────────┼────────────────┬────────────────┐
       │               │                │                │                │
  ┌────▼────┐     ┌────▼─────┐    ┌─────▼─────┐   ┌──────▼───────┐   ┌────▼──────┐
  │ Catalog │     │  Search  │    │  Review   │   │Recommendation│   │   User    │
  │ Service │     │ Service  │    │  Service  │   │   Service    │   │  Service  │
  │  :8081  │     │  :8082   │    │   :8083   │   │    :8084     │   │   :8085   │
  │ (Mongo) │     │ (Mongo + │    │(Postgres) │   │  (Mongo +    │   │(Postgres) │
  │         │     │  Atlas   │    │           │   │   Redis)     │   │           │
  │         │     │  Search) │    │           │   │              │   │           │
  └────┬────┘     └────┬─────┘    └─────┬─────┘   └──────┬───────┘   └────┬──────┘
       │               │                │                │                │
       └───────────────┴────────┬───────┴────────────────┴────────────────┘
                                │
                         ┌──────▼────────┐
                         │     Kafka     │
                         │ movie.created │
                         │ movie.updated │
                         │ movie.deleted │
                         │ review.created│
                         │ rating.updated│
                         │ user.activity │
                         └───────────────┘

   Cross-cutting: Config Server (:8888) · Redis (cache, :6379)
                  Prometheus/Grafana · Jaeger tracing
```

assistant-service (:8086) also sits behind the gateway. It has no data store and no Kafka: an LLM
answers each message by calling search-service and recommendation-service over REST as tools.

## Service ownership (migrated from the monolith)

| Service | Owns | Migrated from mflix | New responsibility |
|---|---|---|---|
| **catalog-service** | movie documents (Mongo, `sample_mflix`) | `MovieController`/`MovieServiceImpl` CRUD and aggregation endpoints | Reads and writes the movie catalog, nothing else. It is the source of truth, and publishes `movie.created`/`movie.updated`/`movie.deleted` to Kafka on every write. |
| **search-service** | its own `movies_search` collection (Mongo, `sample_mflix_search`) | `/api/movies/search` | A CQRS read model, not a passthrough: it consumes catalog's `movie.*` events to keep its own copy, and builds the Atlas Search and vector indexes on that copy. Search results are eventually consistent with the catalog, and the ADRs say so. |
| **review-service** | comments and ratings (Postgres) | comment-related aggregations | Deliberately not Mongo; ADR-0003 explains the choice. Emits `review.created`/`rating.updated` through an outbox. |
| **recommendation-service** | user rating profiles and recommendation metadata (Mongo, `sample_mflix_recsys`), cache (Redis) | `/api/movies/vector-search`, `/api/movies/find-similar-movies` | Builds a collaborative-filtering signal from Kafka events and calls search-service for the content-based (plot-embedding) signal, then blends the two. Falls back to popularity when either is unavailable. |
| **user-service** | users, credentials, activity (Postgres) | (new) | Registration and login. Issues the JWTs that api-gateway validates on every protected route. |
| **assistant-service** | conversations and their messages (Postgres) | (new) | A conversational movie assistant: an OpenAI model, through Spring AI, answers by calling search- and recommendation-service as read-only tools and streams the answer as server-sent events. ADR-0013. |

## Build order

1. Extract **catalog-service** first (closest to the existing code, lowest risk), with **Kafka**
   alongside it so catalog publishes `movie.*` events from day one.
2. Extract **search-service** as a Kafka consumer of those events, maintaining its own synced
   collection and Atlas Search/vector index instead of reading catalog's database.
3. Add **review-service** with an outbox, so a review write and its event are atomic.
4. Build **recommendation-service**: a Kafka consumer for the collaborative signal, plus a
   synchronous call to search-service for content similarity.
5. Add **user-service** and the **api-gateway**, with resilience (Resilience4j circuit breakers,
   timeouts, rate limiting) at the gateway and between services.
6. Add observability (OpenTelemetry + Jaeger, Prometheus/Grafana), and only then load test, so
   the numbers come from running services.
7. Chaos test: kill dependencies and services under load, inject latency, starve memory, and
   record what broke and how it was fixed in `docs/adr/`.
8. Later: move discovery and config to Kubernetes-native equivalents (Service DNS, ConfigMaps)
   on a local cluster.

## Quickstart

Requirements: Docker (at least 4 GB for its VM), `make`, and [k6](https://k6.io) for the load and
chaos tests.

```bash
make up      # builds and starts the whole stack; the first start loads the ~400 MB sample dataset
make smoke   # one pass of both k6 user journeys through api-gateway
make help    # load tests, chaos experiments, logs, teardown
```

Then open Jaeger at http://localhost:16686 and Grafana at http://localhost:3001 (`admin`/`admin`).
[docs/running-locally.md](docs/running-locally.md) covers each service, observability, load and
chaos testing, secrets, and running a single service from the IDE.

## Demo

[`postman/full-demo.postman_collection.json`](postman/full-demo.postman_collection.json) walks
through the whole system in order, entirely through api-gateway (:8080):
1. Register and log in.
2. Browse and search the public catalog.
3. Show the gateway rejecting an unauthenticated request to a protected route.
4. Get cold-start (popularity fallback) recommendations.
5. Add a movie and review it.
6. Watch recommendations personalize once Kafka delivers the rating event.

Import it into Postman; no environment is needed. Each run registers a fresh user, so it can be
repeated. Each service's README links its own, deeper collection (CRUD, validation and error
cases, direct calls to the service port).

## Known limitations

These are known and documented, not fixed yet:

- **No dynamic service discovery.** Every inter-service call uses a configured URL, which is
  enough at one instance per service (ADR-0007).
- **The activity endpoint trusts its path id outside the gateway.**
  - `POST /api/users/{id}/activity` rejects a request whose gateway-forwarded `X-User-Id` doesn't
    match `{id}`.
  - A caller that bypasses api-gateway sends no header, so it is still trusted on the path id
    (ADR-0006, ADR-0011 Update 5).
- **Self-issued HS256 JWTs** with a shared secret. ADR-0012 lays out the move to an external
  OAuth2/OIDC provider and when it becomes worth doing.
- **config-server has no clients yet.** Every service still reads its own `application.yml`
  (ADR-0009).
- **No restart policy for the app services in compose.** An OOM-killed container stays down
  until restarted (ADR-0011, Experiment 4).
- **review-service: a connection lost in the middle of a transaction** is a different failure
  from pool exhaustion. Its circuit breaker doesn't cover it (ADR-0011 Update 6).
- **catalog-service and search-service differ by one movie** (20,287 vs 20,286) after a full
  backfill. This doesn't affect either service's results, and hasn't been investigated.

## Repo layout

```
movie-recommendation-system/
├── Makefile            # make up / smoke / load / chaos / down
├── docker-compose.yml
├── scripts/            # helper scripts used by the Makefile
├── infra/              # config mounted into the compose containers
├── loadtest/           # k6 load test and recorded results (ADR-0010)
├── chaos/              # fault-injection experiments and their findings (ADR-0011)
├── postman/            # end-to-end demo collection through api-gateway
├── services/           # one directory per service, each its own Maven project
└── docs/
    ├── adr/            # architecture decision records, one per significant decision
    ├── running-locally.md
    └── service-hardening-checklist.md
```

## Credits

The starting point is MongoDB's
[`sample-app-java-mflix`](https://github.com/mongodb/sample-app-java-mflix) (Apache License 2.0)
and its `sample_mflix` dataset.

## License

[MIT](LICENSE)
