# ADR 0005: recommendation-service owns its own database; blends content + collaborative signals with one synchronous inter-service call

**Status:** Accepted
**Date:** 2026-09-14

## Context

recommendation-service needs "recommended for you" data assembled from three other services'
data: catalog-service's movie metadata (title/genres/poster, for display and for the
genre-vector profile), review-service's ratings (the taste signal itself), and search-service's
plot-similarity index (the content-based signal). None of that data is recommendation-service's
own — it's the one service in this repo whose entire job is synthesizing other services' state
into something new (a per-user recommendation list), so the question of *how* it gets that data
is the whole design problem, not an afterthought.

Two sub-decisions had to be made together: (1) does recommendation-service query the other
services' databases/APIs directly per-request, or keep its own copy; and (2) for the one signal
that genuinely can't be precomputed from Kafka events alone (content-based similarity, which
depends on search-service's live vector index), is a synchronous call acceptable given this
repo's established CQRS-over-Kafka convention (ADR-0001, ADR-0002, ADR-0004) for everything else?

## Decision

**Own database, CQRS read models via Kafka, same convention as search-service:**
recommendation-service owns `sample_mflix_recsys` (Mongo), with three collections built entirely
from Kafka events, never a query against another service's store:

- `movie_metadata` — a denormalized subset of catalog-service's movie (title/year/poster/genres),
  kept current by `MovieMetadataConsumer` from `movie.created`/`updated`/`deleted`.
- `user_ratings` — this service's own copy of "what did this user rate this movie", kept current
  by `RatingEventConsumer` from review-service's `review.created`/`rating.updated`.
- `user_profiles` — a per-user genre-weight vector, *derived* data (not itself consumed from
  Kafka): recomputed from scratch from `user_ratings` × `movie_metadata` every time
  `RatingEventConsumer` handles an event for that user. Full recompute over incremental deltas
  because an incremental update needs the *previous* rating value to correctly subtract its old
  contribution on a change, which means either storing that previous value redundantly or
  re-deriving it anyway — full recompute is simpler and, at this project's data scale, cheap.

**One deliberate exception — a synchronous call for the content-based signal:**
`SearchServiceClient` calls search-service's `GET /api/movies/search/{id}/similar` on every
cache-miss recommendation request. This is the only on-request-path synchronous inter-service
call anywhere in this repo. Building a *third* Kafka-synced read model (a local copy of
search-service's vector index) was rejected: unlike movie metadata or ratings, a plot-similarity
index isn't meaningfully data recommendation-service can "own a copy of" — the embeddings
belong to search-service's chosen provider/model, and duplicating the whole `plotEmbedding`
column plus running `$vectorSearch` a second time here would mean either running two vector
indexes for the same data or reimplementing search-service's job.

The synchronous call is made *safe to have* by bounding it and degrading around it, not by
avoiding it: `SearchClientConfig` sets connect/read timeouts, and `SearchServiceClient` catches
every failure mode (timeout, connection refused, non-2xx, a movie with no stored embedding yet)
and returns an empty list rather than propagating. `RecommendationServiceImpl` treats that empty
list exactly like "search-service had nothing to say" and blends with the collaborative signal
alone — catalog-service and search-service's own read paths never depend on
recommendation-service (ADR-0001's precedent), and symmetrically, recommendation-service's own
read path must degrade rather than fail when search-service is slow or down.

**The blend itself:** two signals, each independently normalized to [0,1] by dividing by its own
max score before combining via configurable `content-weight`/`collab-weight` — normalizing
independently is required because the two signals have no shared scale (search-service's raw
`$vectorSearch` score vs. this service's own similarity-weighted rating sums), so combining raw
values would let whichever signal happens to produce larger numbers dominate regardless of the
configured weights. A user with no ratings yet, or for whom both signals produce zero candidates,
falls back to a simple popularity list (most-frequently-"liked"-across-all-users movies) —
a proxy computed in-memory from `user_ratings` rather than a separate maintained aggregate,
since this is a cold-start fallback, not the primary path.

**Redis cache-aside**, keyed `rec:{userId}`, TTL-based and proactively evicted by
`RatingEventConsumer` on every rating event for that user, exactly like every other
failure-tolerant component here: `RecommendationCache` catches every Redis failure and degrades
to "always recompute" rather than propagating.

## Alternatives considered

- **Query catalog-service/review-service/search-service's own APIs per-request instead of
  building three CQRS read models** — rejected for the same reason ADR-0001 rejected a
  shared database: it's the "cheap join across services" trap that hides the distributed-systems
  problem (staleness, independent scaling, graceful degradation) this project exists to
  practice, and it would make recommendation-service's availability a function of three other
  services' availability simultaneously, not one bounded, explicitly-degraded call.
- **Also build a local copy of search-service's vector index (fully Kafka-synced, zero
  synchronous calls)** — considered, to keep the "no synchronous inter-service calls"
  invariant perfectly clean. Rejected: it duplicates an entire embedding pipeline and vector
  index for data this service doesn't otherwise need to own, purely to avoid one bounded,
  already-degrading-gracefully HTTP call — not a good trade at this project's scale. Worth
  revisiting only if search-service's latency/availability under load makes the synchronous call
  genuinely costly (see chaos-testing build-order step).
- **Incremental profile updates** (adjust `genreWeights` by the delta between old and new
  rating on each event, instead of full recompute) — rejected: correctly computing a delta
  on an update requires knowing the *previous* rating's contribution, which means storing it
  redundantly or re-deriving it from `user_ratings` anyway — at which point a full recompute
  from `user_ratings` is simpler and no more expensive at this project's scale.
- **A separate maintained "movie popularity" collection/aggregate for the cold-start fallback**
  — rejected as premature: computed in-memory from `user_ratings` on the (rare, cold-start-only)
  path that needs it; revisit only if that in-memory grouping becomes a measured bottleneck.

## Consequences

- recommendation-service can run its own read paths (`GET /api/recommendations/{userId}`) with
  zero synchronous dependency on catalog-service or review-service — only on search-service,
  and only for one signal, and only in a way the response degrades around rather than fails on.
- Inherits the same eventual-consistency window ADR-0001/ADR-0002 already established for
  search-service: a just-created movie or just-submitted rating is invisible to this service's
  read models until its Kafka event is consumed. A user rating a brand-new movie before
  `MovieMetadataConsumer` has processed its `movie.created` event will have that rating silently
  excluded from `genreWeights` until the metadata arrives (see `RatingEventConsumer`'s Javadoc)
  — self-healing on the next event for that user, not a permanent loss.
  Also inherits review-service's own documented gap (ADR-0004): a deleted review publishes no
  event, so its rating lingers in `user_ratings` indefinitely.
- The one synchronous call is a real, load-bearing exception to this repo's "services never call
  each other synchronously on the read path" pattern — documented here specifically so it
  isn't mistaken for an oversight elsewhere in the codebase. Worth deliberately load-testing per
  the README's own suggestion: what happens to recommendations when search-service is slow, and
  does the bounded timeout actually prevent it from starving this service's own response time.
- Follow-up work: the popularity fallback and the "recompute from `user_ratings`" cold-start path
  both do an in-memory `groupingBy` over every liked rating in the collection — fine at this
  project's data scale, but the first thing to revisit if this collection grows large enough for
  that to matter (an aggregation pipeline, or a maintained popularity counter updated
  incrementally by `RatingEventConsumer`).

## Evidence

Not yet measured: staleness window from a rating event to that user's next recomputed profile
under real Kafka lag, and p99 impact of the synchronous search-service call under load (the
README's own suggested "deliberately load-test and fail" step for this service). Unit test
coverage (`RecommendationServiceImplTest`, `RatingEventConsumerTest`, `MovieMetadataConsumerTest`)
verifies the blend/normalization/fallback logic and the read-model consumers in isolation;
`RecommendationIntegrationTest` verifies the full Spring context wires up against a real Mongo
instance and that a cold-start user gets the popularity fallback while a user with a
profile-similar peer gets that peer's liked movie recommended — both against data written
directly into the read-model collections, since no live broker, Redis, or search-service is
provisioned for that test (see its Javadoc for why none of the three need to be).

## Update: end-to-end check and a known gap

- **End to end through compose (2026-10-04):** two movies created through catalog-service, and
  three 5-star reviews through review-service (user A likes both movies, user B likes the first).
  User B's profile was recomputed about 5 seconds after the rating events, and B was then
  recommended A's other movie with source `COLLABORATIVE`. A user with no ratings got the
  `POPULAR` fallback. That 5-second delay is the measured staleness window for a rating event.
- **The search-service call under failure** was later measured by load and chaos testing
  ([ADR-0010](0010-load-test-timeout-budget-mismatch.md),
  [ADR-0011](0011-chaos-testing.md) Experiments 2-4). Recommendations degraded to
  collaborative-only in every case and never failed.
- **Known gap: `movie_metadata` has no backfill.** It is filled only from `movie.*` events, and
  catalog-service never emitted events for the movies seeded from `sample_mflix`. So on a fresh
  stack this service knows only the movies created through catalog-service since it started.
  `enrich` drops any candidate without metadata, so content-based hits on seeded movies are
  discarded. Ratings of seeded movies are stored, but without genres they add nothing to
  `genreWeights`, which is the only input to user-to-user similarity. The fix is the one search-service
  already uses: a startup backfill from catalog-service's REST API when the collection is empty
  (ADR-0002's `CatalogBackfillRunner`). Not yet built.
