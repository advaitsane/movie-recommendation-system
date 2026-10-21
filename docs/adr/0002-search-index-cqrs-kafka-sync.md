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

## Update: movies saved without an embedding are repaired (2026-10-18)

The previous update left one gap open. Embedding fails soft: with no API key, an outage or a 429,
`embedDocuments` returns `null` for that text. Both writers then save the movie anyway, so keyword
search still finds it, but vector search and find-similar don't. Nothing ever filled those vectors
in. A first start without `OPENAI_API_KEY` wrote the backfill marker over 21k movies with no
vectors, and they stayed that way until a Kafka event happened to touch one.

**Fix: `EmbeddingRepairJob`,** a scheduled job, one minute after start and then every 15 minutes
(`embedding.repair.*`). It covers both writers.
- **Waits for the backfill marker,** so the two never embed at the same time.
- **Batches of 100 in `_id` order,** over `plotEmbedding: null`, which also matches a missing
  field. Paging by `_id` means a run always ends, even when some movies can't be embedded.
- **Sets only `plotEmbedding`, and only if nothing changed.** The update matches the document's
  id, a null embedding, and the title and plot the vector was made from. If a Kafka update
  rewrote the movie in the meantime, the update matches nothing and that copy is left alone.
- **Stops a run early when a whole batch comes back empty,** rather than sending all 214 batches
  to a provider that's down. The next run tries again.
- **Provider disabled:** no calls, and a warning with the count.
- **A gauge, `search_movies_without_embedding`,** set from a count after each run, not on every
  scrape, so Prometheus doesn't trigger a collection scan every 15 seconds. Movies with no title
  or plot have nothing to embed and stay counted.

**Not done:** an embedding made by an older model or with other dimensions isn't detected. That
belongs with a model change, which also needs a new vector index (see "Related decision" above).

**Evidence.** `EmbeddingRepairJobTest` (6 tests):
- only the movies without a vector are embedded, and the gauge drops to zero;
- provider disabled: no calls, and the gauge reports the gap;
- no marker: nothing runs;
- a batch with no embeddings stops the run after one call;
- 100 movies with nothing to embed don't stop the run reaching a later batch;
- a movie a Kafka update changed in the meantime isn't counted as repaired.

On the local stack: `plotEmbedding` was removed from 250 movies and search-service restarted. The
backfill skipped ("already completed"), and one minute later the job logged "250 movies have no
embedding — repairing..." and, 20 seconds after that, "250 repaired, 0 still without an
embedding". All 250 had 1536-dimension vectors again, every other field was byte-for-byte
unchanged, and `search_movies_without_embedding` read 0.

## Update: the repair job's count scanned every document (2026-10-19)

**Found while preparing the Grafana dashboards.** The smoke test failed: vector searches through
the gateway hit its 4-second budget and came back 503. Mongo's own timings for `movies_search`
aggregations averaged 4 seconds. The cause was the job above. Its `plotEmbedding: null` count
can't use an index, so every run read all 21,349 documents, about 260 MB of vectors, through a
WiredTiger cache of 256 MB. That pushed mongot's vector index out of the page cache, and the
searches after it waited on disk.

Measured on the local stack, 20 distinct vector queries each, sent straight to search-service:

| | p50 | p95 | max | major faults |
|---|---|---|---|---|
| Steady state | 0.47 s | 0.67 s | 2.13 s | 6,248 |
| Right after the count (17.5 s, 21,349 docs examined) | 0.64 s | 2.39 s | 3.49 s | 45,622 |

That happened one minute after every start, when the cache is coldest, and every 15 minutes
after that: a sawtooth on any latency panel.

**Fix: a marker field with a partial index.**
- `embeddingMissing: true` is stored only while `plotEmbedding` is null. The model's setter and
  builder keep the two in step, so the backfill and the Kafka consumer need no changes. The
  repair's conditional update unsets it along with setting the vector.
- `embedding_missing_index` on `{embeddingMissing: 1, _id: 1}`, partial on
  `embeddingMissing: true`, created at startup with the other indexes. It holds only the movies
  the job has to find, so it's empty in the normal case.
- The count and the batch query read that index. The count is now a `COUNT_SCAN` that examines
  no documents: 13 ms instead of 17.5 s. The batch query is an `IXSCAN`.

**Not done: a migration.** A movie saved without a vector by an earlier version has no marker,
and this job won't find it. A one-off `updateMany` would be one more full scan and one more
piece of code for a case that doesn't exist here. This deployment had 0 such movies when the
change went in, and a fresh start never has any. If it's ever needed, it's one shell command:
`updateMany({plotEmbedding: null}, {$set: {embeddingMissing: true}})`.

**Evidence.**
- `MovieSearchDocumentTest` (3 tests) checks the marker for the builder (with or without a
  `plotEmbedding` call) and the setter. `EmbeddingRepairJobTest`'s 6 tests now run against the
  marker.
- On the local stack: 100 movies had their vector removed and the marker set, and search-service
  was restarted. The index already existed. One minute later the job logged "100 movies have no
  embedding — repairing..." and, 4 seconds after that, "100 repaired, 0 still without an
  embedding". 21,349 of 21,349 movies have 1536-dimension vectors, no markers are left, and
  `search_movies_without_embedding` reads 0.
- Running the new count by hand between two latency samples left vector search no slower
  (p95 1.85 s before, 0.91 s after).

**The noise that's left is the host.** Those samples are noisier than the steady state above.
At the time the Docker VM (3.82 GiB) had its 1 GiB of swap full and 112 MB free, after
search-service's image had just been rebuilt inside it. Swapped and evicted pages come back as
major faults on the next search. Its sizing is in ADR-0010.

## Update: vector search can filter by genre (2026-10-21)

**Context.** The assistant's evals (ADR-0014) failed `d-first-contact` ("quiet science fiction
about first contact") in every run. Vector search ranked *The Visit* (2015, Documentary) first
and *Intruders* (1992, Biography/Horror/Mystery) second, and the model recommended them. A
plot embedding can't tell a documentary about aliens from a film about them. Only the keyword
search had a genre filter.

**Decision.** `GET /api/movies/search/vector` takes an optional `genre`, applied as a
`$vectorSearch` pre-filter, so the top N are all of that genre. Filtering after the search
would return fewer than N. A pre-filter needs the field in the index, so `genres` is now a
`filter` field of `movie_vector_index`.
- A pre-filter matches exact values only. `sci-fi` is mapped to the stored `Sci-Fi` against the
  distinct genres, and an unknown genre is a 400 that lists the real ones. The assistant passes
  that message to the model, so it can retry with a name that exists.
- `findSimilar` has no filter. "Movies like Inception" names no genre.

**An existing index is dropped and created again.** This mongot (atlas-local 8.3.8) rejects
`updateSearchIndex` for a vector index with `"mappings" is required`, from the driver and from
a raw command alike, and the command has no `type` field to fix that. That's the same bug as
`createSearchIndexes` without `SearchIndexType.vectorSearch()`. The other way, building a second
index under a new name and then switching, would hold two indexes in mongot's memory at once,
and mflix-mongo was at 893 MiB of its 1.25 GiB before the change (ADR-0010 Update 8 had an OOM
kill under that limit). While the index rebuilds, vector search returns no results.

**Evidence.**
- On the local stack: search-service restarted, dropped the old index and created the new one.
  It was `READY` 35 s later, with mflix-mongo at 1.04 GiB at the peak and no restart.
- The same query, top 5: without a genre, *The Visit*, *Intruders*, *Alien Nation*, *The
  Invasion*, *The Arrival*; with `genre=sci-fi`, *Alien Nation*, *The Invasion*, *The Arrival*,
  *The Andromeda Strain*, *Alien Trespass*. Same scores, the two non-Sci-Fi movies gone.
- `SearchServiceImplTest` checks the filter in the pipeline and the 400 for an unknown genre
  (made before any embedding call). `VectorSearchIndexVerificationTest` covers create, recreate
  and leave alone.
