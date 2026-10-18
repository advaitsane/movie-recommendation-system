# ADR 0002: Search index as a synced materialized view (CQRS via Kafka) vs. a shared database with catalog-service

**Status:** Accepted
**Date:** 2026-09-12

## Context

ADR-0001 split catalog-service and search-service apart but deferred the
actual sync mechanism: how does `movies_search` (search-service's own Mongo
database, `sample_mflix_search`) stay up to date with catalog-service's
`sample_mflix`, without the two services sharing a database connection?

search-service also needed to add vector/semantic search (embedding-based
similarity, not just text/genre/year filters) on top of whatever sync
mechanism was chosen, which raised the stakes on getting the read-model
shape right — every synced document needs a `plotEmbedding` field kept in
step with the source text, not just the catalog fields themselves.

## Decision

search-service consumes catalog-service's `movie.created` / `movie.updated`
/ `movie.deleted` Kafka events (published per ADR-0001) and maintains its
own `movies_search` collection as a CQRS-style read model — never querying
`sample_mflix` directly, never sharing catalog-service's database connection.

- `MovieEventConsumer` (`@KafkaListener`) applies each event to
  `movies_search`: upsert on created/updated, delete on deleted. On
  created/updated it also generates the document's `plotEmbedding` via
  whichever embedding provider is configured (see "Related decision" below)
  before saving, so the read model is never missing embeddings for movies
  that arrive after startup.
- `CatalogBackfillRunner` runs once at startup if `movies_search` is empty,
  pulling all pages from catalog-service's REST API directly (not Kafka) to
  avoid waiting for the full event history to replay from the start of the
  topic — Kafka is the ongoing sync mechanism, not the bootstrap mechanism.
- Atlas `$vectorSearch` and text search both run against `movies_search`
  only; search-service has no code path that reads catalog-service's Mongo
  at request time.

### Related decision: embedding generation is provider-agnostic

While building the read model, embedding generation (needed for
`plotEmbedding`) was implemented behind an `EmbeddingService` interface
rather than calling one vendor's API directly, since the concrete provider
(Voyage AI vs. OpenAI) turned out to be a runtime config choice, not an
architectural one — both are just "text in, vector out" over HTTP. Exactly
one implementation (`VoyageEmbeddingService` / `OpenAiEmbeddingService`) is
registered via `@ConditionalOnProperty(embedding.provider=voyage|openai)`,
and `VectorSearchIndexVerification` reads the active provider's
`getDimensions()` to build the Atlas index, so the index dimension can never
silently drift from what's actually stored.

## Alternatives considered

- **Shared Mongo database between catalog-service and search-service** —
  ruled out in ADR-0001 already; restated here because it was the most
  tempting shortcut specifically for search-service, since Atlas Search
  indexes *can* be built directly on `sample_mflix` with zero sync code.
  Rejected for the same reason as before: it hides the eventual-consistency
  and service-boundary problems this project exists to practice.
- **Synchronous REST call from search-service to catalog-service per
  search request** (no local read model at all) — avoids the sync problem
  entirely, but every search request's latency and availability become
  bounded by catalog-service's, and there's no independent index to run
  Atlas `$vectorSearch`/`$search` against (those require an actual Mongo
  collection with an index defined on it, not a REST response).
- **CDC (e.g. Debezium on catalog-service's Mongo oplog) instead of
  application-level Kafka publishing** — would remove the requirement that
  catalog-service explicitly publish events, but adds an extra
  infrastructure component (Debezium connector + Kafka Connect) for a
  two-service project, and ties the event schema to Mongo's internal
  change-stream format rather than a clean `MovieEvent` contract
  search-service can consume. Deferred, not rejected outright — worth
  revisiting if a third read-model consumer shows up.
- **Single embedding provider hard-coded (Voyage only, as originally
  built)** — simpler, but ties the entire vector-search feature to one
  vendor's key/pricing/availability. Switched to the pluggable
  `EmbeddingService` interface after acquiring an OpenAI key made "which
  provider" a config decision, not a rebuild.

## Consequences

- search-service can be read-load-tested, scaled, and deployed independently
  of catalog-service, with its own indexes (text, genre/year/rating, and now
  vector) tuned for read traffic without touching catalog-service at all.
- Eventual consistency is real and now measured (see Evidence), not
  hand-waved: there's a window after a catalog write where `movies_search`
  is stale until the Kafka event is consumed.
- The event contract (`MovieEvent`/`MovieEventType`) is a hard dependency
  between the two services, same tradeoff noted in ADR-0001 — now proven
  out by an actual consumer.
- Two bug classes surfaced specifically because this is a Kafka consumer
  system, and both produced **zero error output**, which is itself a
  finding worth carrying into the resilience/observability build-order
  steps (5-6): a broken consumer group and a bean that's registered but
  never wired into a listener container both fail silently. See Evidence.
- Switching the embedding provider or its model requires manually dropping
  the existing Atlas vector index and clearing `movies_search` before
  restart — `VectorSearchIndexVerification` only creates the index when
  absent, it never migrates dimensions on an existing one. This is a manual
  runbook step today, not automated; acceptable for a dev-scale project, but
  would need an actual migration path in a real deployment.
- Follow-up: review-service (build-order step 3) will face the same
  "how does this service's data get published reliably" question, but from
  the write side (outbox pattern, ADR-0004) rather than the read side.

## Evidence

- **Sync correctness at full scale:** after fixing the year-field bug
  (some seeded documents store `year` as a mangled string; see
  catalog-service's `YearStringToIntegerConverter`), `movies_search` was cleared and
  `CatalogBackfillRunner` re-run from empty — indexed all 20,287 movies
  successfully (previously died partway through, before that fix).
- **Kafka sync was silently broken, twice, before it worked:**
  1. `kafka`'s default internal-topic replication factor (3) can never
     succeed on a single-broker dev cluster, so `__consumer_offsets` was
     never created and no consumer group could ever form — no error, just
     a hang. Fixed via `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` (+
     transaction-log settings) in `docker-compose.yml`.
  2. search-service's `KafkaConsumerConfig` was missing `@EnableKafka`, so
     `MovieEventConsumer` was a registered bean that Spring never wired into
     a running listener container — again, no error at any log level.
     Fixed by adding `@EnableKafka` and explicitly pointing `@KafkaListener`
     at the JSON-deserializing container factory instead of relying on
     Spring Boot's default bean-name lookup.
  - **Staleness window, measured after both fixes:** create/update/delete on
    catalog-service propagates to `movies_search` within ~1-2 seconds,
    confirmed by direct observation of Kafka consumption timing during
    manual end-to-end testing.
- **Vector search validated at real scale with a real OpenAI key:**
  full backfill against `text-embedding-3-small` (1536 dims) — 20,287/20,287
  movies indexed, 20,185 with a stored embedding (100 lost to one transient
  TLS `bad_record_mac` network error mid-run; fail-soft design logged and
  skipped that page rather than crashing the backfill — no code fix needed,
  this is the intended behavior). Query correctness spot-checked:
  `GET /api/movies/search/vector?q=a heist where a crew steals from a casino`
  returned Ocean's Eleven in the top 3; `GET /api/movies/search/{id}/similar`
  on Jaws returned its own sequels plus other shark movies. Live Kafka path
  also confirmed generating real embeddings: a newly created movie about an
  astronaut was matched to Marooned/Space Cowboys via find-similar within
  ~3 seconds of the create event.
- **Known minor gap, not yet root-caused:** after the full backfill,
  `movies_search`'s document count (20,285) is slightly below catalog's
  (20,287) — off by 2, not investigated, not blocking any functionality
  above. Worth revisiting if it turns out to matter (e.g. during
  chaos-testing consumer idempotency, build-order step 7).

## Update: the backfill's request didn't match catalog-service's API (2026-10-08)

Found while building recommendation-service's backfill (ADR-0005). `CatalogBackfillRunner`
requested `GET /api/movies?limit=100&skip=N` and parsed the response as a JSON array. The published
catalog-service takes Spring Data's `page`/`size`/`sort` parameters and returns a page object
(`{"content": [...], "page": {...}}`). Checked live: `?limit=100&skip=0` returns that object with
20 movies, since both parameters are ignored. Parsing an object as an array fails, so on an empty
`movies_search` the backfill would log an error and index nothing. Existing stacks weren't affected
because their `movies_search` wasn't empty.

Fixed: the runner now requests `page`, `size=100` (catalog-service's cap) and `sort=_id`, parses the
page object, and stops after `totalPages`. Sorting by `_id` matters: catalog-service's default sort
is by title, which isn't unique, so pages can overlap or skip movies. That may explain the
off-by-two count above, but it hasn't been re-checked. `sort=id` doesn't work either: catalog-service
sorts raw documents, so `id` isn't mapped to `_id`.
`CatalogBackfillRunnerTest` runs the runner against a stub that serves catalog-service's real
page format.

The fix exposed a test problem the bug had hidden. `SearchServiceIntegrationTest` ran the backfill
at startup against `localhost:8081`. With a local stack running, it now copied all 20,293 catalog
movies into the test database, and the test's own movie was no longer the top search hit. The
runner can now be turned off with `catalog.backfill.enabled=false`, and the test profile does so.

## Update: a backfill that crashed part-way never finished (2026-10-18)

Found during the one-command-start cold start (ADR-0010 Update 6). search-service was OOM-killed
during its first-start backfill with 21,230 of 21,349 movies copied. On the next start the runner
logged "already has 21230 documents — skipping": it inferred "done" from a non-empty
`movies_search`. The missing 119 movies would only have arrived if a `movie.updated` event
happened to come in for one of them. Nothing compared the copy against catalog-service. This is
the likely cause of the off-by-two and off-by-one counts noted above and in the README.

**Fix.**
- **Done is recorded, not inferred.** After the last page the runner saves a `backfill_state`
  document (`_id: "catalog"`, `completedAt`, `movies`). With the marker present, a start skips the
  backfill. Without it, the backfill runs, even when `movies_search` already has documents.
- **A rerun fills gaps only.** Every page is fetched again, but the runner looks up that page's
  ids in `movies_search` and embeds and saves only the missing ones. A rerun repeats no embedding
  calls, and never overwrites a copy that Kafka updated in the meantime.
- **No resume by page number.** A movie deleted from the catalog shifts every later page, so a
  saved page number can skip movies. Walking all 214 pages costs one id lookup each.

**Limit of this fix.** "Present" means the document exists. A movie saved with
`plotEmbedding: null` (no API key, an outage, a 429) counts as present, and the marker is written
even if every embedding failed. Repairing those is a separate job, for both the backfill and the
Kafka consumer.

**Evidence.**
- `CatalogBackfillRunnerTest`: an empty index is filled and the marker saved; a marker skips the
  run; a run that fails on page 2 and then restarts adds only the missing movie, with no repeated
  embedding call; a movie Kafka already stored isn't overwritten.
- On the local stack, a backfill stopped at 3,300 movies resumed with "indexed 18049 movies from
  catalog-service (3300 already present)". `movies_search` then held 21,349 documents, the same as
  catalog-service's `totalElements`, all with embeddings, and the marker was written.
- A real crash: `mflix-mongo` was OOM-killed (ADR-0010 Update 8) with 6,457 movies copied. The
  runner logged the failure and wrote no marker. The next start logged "An earlier backfill didn't
  complete (6457 documents present) — adding the missing movies...".
- A cold start from empty volumes indexed all 21,349 movies, with embeddings, and wrote the
  marker. After `make down` and `make up`, the runner logged "already completed — skipping".
