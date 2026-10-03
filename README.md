# movie-recommendation-system

The MongoDB `sample-app-java-mflix` monolith, rebuilt as a set of services that each own one
domain. The goal is to study reliability and scalability engineering on a realistic system: CQRS
read models, the outbox pattern, circuit breakers and graceful degradation, each one checked with
load tests and chaos experiments rather than assumed.

## Status: published in stages

The system is finished and runs locally. It is being published one build step per pull request,
so that the history shows how it was built and why. Each PR links the architecture decision
records (ADRs) behind it.

| Step | Contents | Status |
|---|---|---|
| 0 | Repo scaffolding, infrastructure in docker-compose | ✅ this commit |
| 1 | CI: build and test every service on each PR | ⏳ |
| 2 | catalog-service and search-service (CQRS read model over Kafka) | ⏳ |
| 3 | review-service (Postgres, outbox pattern) | ⏳ |
| 4 | recommendation-service (content + collaborative blend, Redis cache) | ⏳ |
| 5 | user-service (registration, JWT issuance, activity events) | ⏳ |
| 6 | api-gateway (routing, JWT enforcement, rate limiting, circuit breakers) | ⏳ |
| 7 | Observability (OpenTelemetry + Jaeger, Prometheus, Grafana) | ⏳ |
| 8 | config-server | ⏳ |
| 9 | Load testing | ⏳ |
| 10 | Chaos testing and hardening | ⏳ |

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

## Service ownership (migrated from the monolith)

| Service | Owns | Migrated from mflix | New responsibility |
|---|---|---|---|
| **catalog-service** | movie documents (Mongo, `sample_mflix`) | `MovieController`/`MovieServiceImpl` CRUD and aggregation endpoints | Reads and writes the movie catalog, nothing else. It is the source of truth, and publishes `movie.created`/`movie.updated`/`movie.deleted` to Kafka on every write. |
| **search-service** | its own `movies_search` collection (Mongo, `sample_mflix_search`) | `/api/movies/search` | A CQRS read model, not a passthrough: it consumes catalog's `movie.*` events to keep its own copy, and builds the Atlas Search and vector indexes on that copy. Search results are eventually consistent with the catalog, and the ADRs say so. |
| **review-service** | comments and ratings (Postgres) | comment-related aggregations | Deliberately not Mongo; ADR-0003 explains the choice. Emits `review.created`/`rating.updated` through an outbox. |
| **recommendation-service** | user rating profiles and recommendation metadata (Mongo, `sample_mflix_recsys`), cache (Redis) | `/api/movies/vector-search`, `/api/movies/find-similar-movies` | Builds a collaborative-filtering signal from Kafka events and calls search-service for the content-based (plot-embedding) signal, then blends the two. Falls back to popularity when either is unavailable. |
| **user-service** | users, credentials, activity (Postgres) | (new) | Registration and login. Issues the JWTs that api-gateway validates on every protected route. |

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

## Local dev

Requirements: Docker, and JDK 25 to run a service outside a container (each service ships its own
Maven wrapper).

**1. Shared infrastructure** (Postgres, Redis, Kafka, and the Mongo instance used by
recommendation-service):

```bash
docker compose up -d
```

**2. The movie catalog database.** catalog-service and search-service use a separate
`mongodb-atlas-local` container, which supports Atlas Search and vector search locally. Create it
once and load MongoDB's `sample_mflix` dataset (a ~400 MB download):

```bash
docker run -d --name mflix-mongo -p 27017:27017 mongodb/mongodb-atlas-local
curl -sSf https://atlas-education.s3.amazonaws.com/sampledata.archive \
  | docker exec -i mflix-mongo mongorestore --archive --nsInclude='sample_mflix.*'
```

It lives outside compose, so after a restart it needs `docker start mflix-mongo`.

All credentials in `docker-compose.yml` are local-development placeholders. Real secrets (such as
an embedding-provider API key) go in a gitignored `application-local.yml`; see each service's
README as it lands.

## Repo layout

```
movie-recommendation-system/
├── docker-compose.yml
├── infra/              # config mounted into the compose containers
├── services/           # one directory per service, each its own Maven project
└── docs/
    └── adr/            # architecture decision records, one per significant decision
```

## Credits

The starting point is MongoDB's
[`sample-app-java-mflix`](https://github.com/mongodb/sample-app-java-mflix) (Apache License 2.0)
and its `sample_mflix` dataset.

## License

[MIT](LICENSE)
