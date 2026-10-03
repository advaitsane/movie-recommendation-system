# ADR 0001: Split catalog-service and search-service out of the monolith

**Status:** Accepted
**Date:** 2026-09-09

## Context

The source monolith (`mongodb/sample-app-java-mflix`) exposes movie CRUD,
aggregation reporting, text search, and vector/embedding-based similarity
search all from one `MovieController`/`MovieServiceImpl`, backed by a single
`sample_mflix` Mongo database. That coupling means:

- Search and vector-search traffic (read-heavy, latency-sensitive, likely to
  need Elasticsearch or a different indexing strategy later) shares the same
  deploy, scaling, and failure domain as catalog writes (CRUD, low-volume,
  needs strong consistency).
- There's no natural point to introduce eventual consistency, CQRS, or an
  independent scaling story — everything reads and writes the same
  collection synchronously.
- It's the piece of the monolith closest to the existing code, making it the
  lowest-risk place to prove out the extraction pattern (Kafka publishing,
  service boundaries, own datastore) before applying it to review-service
  and recommendation-service.

## Decision

Split movie catalog ownership from movie search/similarity ownership into
two services from day one, rather than extracting one combined
"movie-service" and splitting search out later:

- **catalog-service** owns the canonical `movie` documents in Mongo
  (`sample_mflix`). It keeps CRUD (`/api/movies`) and the reporting
  aggregations (`reportingByComments`, `reportingByYear`,
  `reportingByDirectors`) — the parts of the monolith that require strong
  consistency with the write path. On every create/update/delete it
  publishes `movie.created` / `movie.updated` / `movie.deleted` to Kafka.
- **search-service** (not yet built) will own a separately-synced
  `movies_search` collection (`sample_mflix_search`), populated by
  consuming catalog's `movie.*` events — a CQRS-style read model, not a
  passthrough to catalog's Mongo. Text search, Atlas Search, and
  vector-search/find-similar-movies move here.

catalog-service's `MovieController` explicitly does not implement text
search, vector search, or find-similar-movies — those were stripped out at
extraction time, ahead of search-service existing, so the event contract
(`MovieEvent`/`MovieEventType` + `MovieEventPublisher`) had to be right
before any consumer was built against it.

## Alternatives considered

- **One combined `movie-service` (CRUD + search + vector-search), split
  later** — lower short-term effort, but defers the exact problem this
  project exists to practice (data ownership boundaries, eventual
  consistency) and risks the split happening under time pressure instead of
  deliberately.
- **Shared Mongo database, separate services** — services stay logically
  separate but both query `sample_mflix` directly. Rejected: this is the
  "cheap join across tables" trap of a shared database — it hides the distributed-systems problems this repo
  is meant to surface (staleness, event-driven sync, independent scaling)
  rather than solving them.
- **Synchronous REST call from search-service to catalog-service on every
  search request** — avoids building a synced read model, but couples
  search-service's availability and latency to catalog-service's, and gives
  up the CQRS/eventual-consistency story entirely (no interesting failure
  mode to chaos-test later).

## Consequences

- catalog-service is small and single-purpose: it can be extracted, tested,
  and run standalone against just Mongo + Kafka, with no dependency on
  search infrastructure — this is already true, confirmed by catalog-service
  building, passing its unit + Testcontainers integration tests, and running
  live alongside Kafka and Mongo via docker compose.
- search-service inherits an eventual-consistency problem: after a catalog
  write, there's a window where search results are stale until the Kafka
  event is consumed and `movies_search` is updated. This has to be measured
  and explicitly documented once search-service exists, not hand-waved.
- The event contract (`MovieEvent`/`MovieEventType`) is now a public
  interface between two services — changing it later requires coordinating
  both sides, unlike a shared-database change.
- Follow-up work: build search-service as a Kafka consumer of `movie.*`
  (build-order step 2); once it exists, measure and document catalog-write
  → search-visible staleness as the "Evidence" for this ADR's tradeoff.

## Evidence

catalog-service currently runs standalone (verified 2026-09-09): builds
clean, unit tests + Testcontainers Mongo integration tests pass, and the
service is live via `docker compose up -d` alongside Kafka and Mongo with
no search-service dependency. Search-service doesn't exist yet, so the
staleness-window number this ADR predicts is not yet measured — to be
added once search-service is built and can be load-tested against a
catalog write.

## Update: search-service built; reporting aggregations dropped from catalog-service

- search-service now exists as the `movie.*` consumer this ADR anticipated. Its sync mechanism,
  and the staleness window this ADR left unmeasured (~1-2 seconds from a catalog write to
  `movies_search`), are recorded in [ADR-0002](0002-search-index-cqrs-kafka-sync.md).
- The Decision above kept the three reporting aggregations (`reportingByComments`,
  `reportingByYear`, `reportingByDirectors`) in catalog-service. They were later removed: they
  served a monolith-era reporting feature rather than the catalog CRUD this service owns, and
  `reportingByComments` needs comment data that now belongs to review-service (ADR-0003).
  catalog-service is CRUD plus event publishing only.
