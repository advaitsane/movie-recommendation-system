# ADR 0011: Chaos testing — dependency, service, network, and resource fault injection

**Status:** Accepted (4 experiments run against the live docker-compose stack; the one real gap
found — missing Mongo health indicator on catalog-service/search-service — is now fixed and
verified; plus one real bug found and fixed in the chaos harness itself — `docker update` cgroup
overrides don't get restored by a plain `docker compose up -d`)
**Date:** 2026-09-18

## Context

ADR-0010 validated the system under sustained load (30 concurrent VUs, no OOM, latency budgets met)
but never deliberately broke anything — it only observed what happened when the *host* ran out of
capacity, plus one accidental `mflix-mongo` crash discovered along the way. That accidental crash
was the strongest signal in the whole load-testing effort: real dependency failures reveal things
synthetic load never does. Chaos testing formalizes that — deliberately killing containers,
injecting network faults, and shrinking resource limits mid-traffic — to answer the questions load
testing can't: does the system detect a broken dependency, does it degrade gracefully or fail hard,
and does it recover without manual intervention.

## Decision

Built a small, dependency-free chaos harness (`chaos/lib.sh` + four scripts) that: starts continuous
background load (looping `SMOKE=1 k6 run` against `loadtest/user-journey.js`), polls every app
service's `/actuator/health` every 2s throughout, injects one fault, waits, restores, and summarizes
which services (if any) went unhealthy and when. Network faults use `pumba` (a Docker-native netem
wrapper, run ad-hoc via `docker run --rm`, no permanent install) rather than adding Toxiproxy as a
standing infra dependency. Four scenario categories were run once each against the live stack:

1. **Dependency killed mid-traffic** — `docker stop mflix-mongo` for 30s.
2. **Service instance killed** — `docker kill` on `search-service` for 30s.
3. **Network latency injected** — `pumba netem delay 8000ms` on `search-service` for 30s.
4. **Resource exhaustion** — `docker update --memory 96m` on `search-service` (idle RSS ~278MB
   against its normal 384MB limit) for 30s.

## Alternatives considered

- **Toxiproxy** — more realistic, protocol-aware network chaos, but adds a standing proxy layer and
  compose changes for a solo project doing one-off experiments; deferred.
- **Chaos Toolkit / formal framework** — more rigorous and repeatable via declarative experiment
  JSON, but heavier setup than warranted for four ad-hoc experiments; deferred. The bash harness is
  intentionally close to what was already built for load testing (same k6 script, same ADR
  documentation pattern), which kept the marginal cost low.

## Consequences

- **Gets better**: the recommendation-service → search-service fallback pattern (ADR-0010) is now
  verified against *real* failures (kill, timeout, OOM), not just inferred from stress-test tail
  latency — it degraded gracefully in every one of experiments 2, 3, and 4.
- **Tradeoff accepted**: chaos scripts reuse the SMOKE k6 profile (2 VUs) for background load, not
  the full stress profile — enough to generate real traffic during a fault window, not enough to
  reproduce ADR-0010's 30-VU findings simultaneously with a fault. Combining full-scale stress with
  chaos injection is a natural follow-up, not done here.
- **Follow-up work created**: register an explicit Mongo health indicator for catalog-service and
  search-service (see Experiment 1) — this is the one actionable code gap this round of testing
  surfaced.
- **Two bugs found in the harness itself while building it** (both fixed before the results below
  were trusted): `date +%s%3N` isn't portable to macOS's BSD `date` (switched to `python3 -c
  'import time...'`); the background-load subshell inherited `set -e` from the caller and silently
  died after one k6 run whenever a threshold was crossed — exactly the condition chaos experiments
  are designed to trigger (added `set +e` inside both background subshells in `lib.sh`).

## Evidence

### Experiment 1 — kill mflix-mongo for 30s

Real app-level failures, confirmed via service logs, not just k6 metrics:
- catalog-service: `MongoTimeoutException`-driven 500s, e.g. `GET /api/movies/{id} 500 - 14647ms`.
- search-service: cascading `ClientAbortException: Broken pipe` once mongo recovered but slow
  in-flight responses outlived the client's own timeout.
- k6 http_req_failed rate across the 6 smoke runs spanning the outage: 37.5%–87.5%.

**Finding: `/actuator/health` returned 200 for the entire 30s outage on both services** (confirmed
via 2s-interval polling — zero unhealthy readings). An orchestrator's liveness/readiness probe would
not have detected this and would keep routing traffic to a service actively failing 40–90% of
requests. At the time, catalog-service/search-service used a custom MongoConfig bypassing Spring
Boot's Mongo autoconfiguration — likely why no Mongo health indicator was registered (confirmed in
Update 1).
Recovery was clean: catalog-service latency back to ~50-300ms within ~20-60s of mongo returning, no
crash loop, no manual intervention.

### Experiment 2 — kill search-service outright for 30s

`recommendation-service`'s `SearchServiceClient.findSimilar()` caught every failure mode as
search-service died and came back — logged `degrading to no content-based signal` each time:
`Connect timed out` (~6s, right after kill) → `Connection refused` (port closed, ~20-30s while
compose restarted it) → recovery. The fallback pattern ADR-0010 inferred from timeout numbers is
confirmed working end-to-end. search-service's own `/actuator/health` correctly went unhealthy (20
readings) while its container was down — unlike Experiment 1, a service being down *itself* (versus
a downstream dependency) is correctly reflected.

### Experiment 3 — 8000ms network delay on search-service for 30s (via pumba/netem)

`GET /api/recommendations/{id}` avg latency in the first run after injection: 6094.1ms, p99
6152.3ms — bounded right around 6s despite an 8s injected delay, i.e. the timeout triggers and
degrades rather than waiting out the full induced latency. Direct search-service latency (`GET
/api/movies/search/vector`) was only partially elevated (avg 2036ms first run, decaying to normal
~250-450ms within a couple of runs) — likely pumba's netem qdisc taking a moment to actually apply
after `docker run -d` returns; not fully explained, worth a longer injection window if this becomes
a recurring exercise. No health-check impact, as expected (latency isn't a liveness signal).

### Experiment 4 — shrink search-service to 96m under load for 30s

`docker inspect` confirmed a real kernel OOM-kill: `OOMKilled=true`, `ExitCode=137`.
recommendation-service degraded gracefully throughout (same fallback as Experiment 2/3). No restart
policy is configured in `docker-compose.yml` for any app service, so the killed container stayed
`exited` until the script's own restore step.

**Bug found and fixed mid-experiment**: the restore step originally used a plain `docker compose up
-d --no-deps search-service`. Because `docker update --memory` sets a cgroup limit that survives a
container restart, and compose saw no config diff, it just re-*started* the existing (still
96MB-capped) container — which then OOM-looped through its own Spring Boot startup and was left
**actually broken** after the chaos script reported success (exit code 0). Caught via a live health
check (`000`) after the fact, not by the script itself. Fixed by switching to `docker compose up -d
--force-recreate --no-deps` in `chaos/04_resource_exhaustion.sh`; the live environment was manually
recovered the same way before this ADR was written. Same root-cause class as ADR-0010 Update 5's
stale-container-resume bug — different trigger (`docker update` instead of an `Exited` container),
same lesson: never trust a plain `docker compose up -d` to restore a known-good state after any
out-of-band container mutation.

## Update 1 (2026-09-18, same day): Mongo health indicator added and verified

Fixed the one real gap from Experiment 1. Both `catalog-service` and `search-service` bypass Spring
Boot's Mongo autoconfiguration (they define their own `MongoClient`/`MongoDatabaseFactory` via
`AbstractMongoClientConfiguration`), so `MongoHealthContributorAutoConfiguration` never fires and no
Mongo health indicator gets registered — the root cause suspected in Experiment 1's write-up.
Confirmed via `jar tf` against the actual `spring-boot-mongodb-4.1.1.jar` already on the classpath
(pulled transitively by `spring-boot-starter-data-mongodb`) that Spring Boot 4 ships a ready-made
`org.springframework.boot.mongodb.health.MongoHealthIndicator(MongoClient)` — no need to hand-roll
one. Added a `@Bean HealthIndicator mongoHealthIndicator()` to both services' `MongoConfig`,
constructed from the existing `mongoClient()` bean inherited from `AbstractMongoClientConfiguration`.

**Verified against a real outage, not just compiled**: rebuilt both images, redeployed
(`--force-recreate`), then stopped `mflix-mongo` again — `/actuator/health` on both services
correctly flipped from 200 to **503** this time (previously stayed 200 for the entire outage in
Experiment 1), and returned to 200 within seconds of `mflix-mongo` recovering.

**An unrelated host-level failure happened while verifying this**:
`mflix-mongo` was killed by a genuine kernel OOM (`OOMKilled=true`, `ExitCode=0`) shortly after the
rebuilt containers restarted — the host's Docker Desktop VM was under real memory pressure (free
pages down to ~14MB, load average briefly 16-21) after hours of repeated image builds and
chaos-experiment container churn. `search-service`'s startup-time `SearchIndexVerification` runner
failed hard against the dying Mongo and crashed the whole Spring context (same class of behavior as
`CatalogBackfillRunner` in ADR-0010). Recovered by restarting `mflix-mongo`, waiting for it to
report `healthy`, then `docker compose up -d --no-deps` on both app services — no code or config
change needed, purely a transient host-resource issue. Not itself a chaos-testing finding, just a
reminder that a long stretch of local image rebuilds + container churn can eat into the same
3.8GB Docker Desktop VM budget documented in ADR-0010 Update 2.

**Noted, not acted on**: one log line during the flaky pre-crash window showed the new health
indicator itself taking 10035ms to respond (`HealthEndpointSupport: Health contributor ...
MongoHealthIndicator (mongo) took 10035ms to respond`) — consistent with `MongoConfig`'s existing
`serverSelectionTimeout`/`connectTimeout` of 10000ms bounding how long a ping can hang before
failing. This means `/actuator/health` itself can take up to ~10s to respond during a real Mongo
outage, which is acceptable for detecting the outage (still resolves to 503, just not instantly) but
worth knowing if a liveness probe's own timeout is configured tighter than that elsewhere.

## Update 2 (2026-09-21): both services moved off the hand-built MongoConfig Update 1 describes

Update 1's "no need to hand-roll one" framing undersold it — Boot 4's Mongo autoconfiguration
(`spring-boot-starter-data-mongodb`) can be used directly instead of `AbstractMongoClientConfiguration`,
picking up `MongoHealthContributorAutoConfiguration` (and repository scanning) automatically rather
than requiring the manual `@Bean HealthIndicator mongoHealthIndicator()` Update 1 added. catalog-service
switched first (see its README's Architecture decisions section); search-service made the same switch
during its own hardening pass (see search-service's README). Both now layer a
`MongoClientSettingsBuilderCustomizer` on top of autoconfiguration instead of hand-building the
`MongoClient`. Re-verified against a real `mflix-mongo` outage after the switch on each service —
`/actuator/health` still correctly flips 200→503→200, same as Update 1's verification, just without a
manually-wired bean that could silently drift out of sync with a future refactor. This ADR's own
Experiment 1 finding (the gap this whole document is about) is the reason that manual-wiring risk was
worth removing rather than just leaving the working patch in place.

## Update 3 (2026-09-21): recommendation-service's *own* Mongo and Redis, not just its search-service call

Experiments 2-4 (and Update 2's "no further action needed" note) only ever verified
recommendation-service's *outbound* dependency — its synchronous call to search-service. This
service's own two backing stores (a compose-managed `mongo` container distinct from `mflix-mongo`,
and `redis`) had never been chaos-tested, and unlike catalog-service/search-service,
recommendation-service had **no circuit breaker at all** on its Mongo calls (its own
`SearchClientConfig`'s Javadoc had explicitly flagged this as deferred: "no circuit breaker yet").
Closed during this service's hardening pass:

- Added `MongoCircuitBreakerConfig` (resilience4j), mirroring catalog-service's/search-service's
  pattern exactly, wrapping `RecommendationServiceImpl.getRecommendations`'s Mongo-touching reads
  in one span per request.
- Added `MongoConfig` (a `MongoClientSettingsBuilderCustomizer` bounding connect/server-selection
  timeout to 10s) — Boot's Mongo autoconfiguration alone left the driver's untuned defaults in
  place, which the first real test below caught.
- Added a bounded Redis `connect-timeout`/`timeout` (2000ms/1000ms) — `RecommendationCache`
  already caught every failure and degraded to "treat as a cache miss", but nothing bounded how
  long a *hung* Redis could block a request before that catch even triggered.

**Verified against real outages of each, twice for Mongo (before and after the timeout fix):**

- `docker stop` on recommendation-service's own `mongo` container, *before* `MongoConfig` existed:
  every failed call hung ~15s (Mongo driver defaults), `/actuator/health` took ~30s to resolve to
  `503`, and the circuit breaker needed 5 calls to open — over a minute of degraded latency before
  it kicked in.
- Same test *after* adding `MongoConfig`: failed calls now return in ~10s, the breaker opens after
  4-5 calls, `/actuator/health` resolves to `503` in ~10s (matching catalog-service's/
  search-service's already-tuned value), and recovery was clean and fully observed via log state
  transitions: `CLOSED → OPEN → HALF_OPEN → CLOSED`, `/actuator/health` back to `200` within
  seconds of `docker compose up -d --force-recreate --no-deps mongo`.
- `docker stop` on the `redis` container: requests kept returning `200` (bounded at ~2.1s per
  request — one failed cache read plus one failed cache write, each bounded by the 1000ms command
  timeout) with `RecommendationCache` logging "Redis command timed out — treating as a miss" rather
  than hanging, and recovered silently once `redis` returned.

Restored both containers via `docker compose up -d --force-recreate --no-deps <service>` per this
ADR's own Consequences/How-to-apply guidance below, not a plain `up -d`.

## Update 4 (2026-09-21): api-gateway's per-route circuit breakers never actually opened

api-gateway's own hardening pass (ADR-0007) had unit tests, a stub-server smoke test, and
ADR-0010's load-test timeout fix, but no real `docker kill` of a downstream service — this closes
that gap, and it surfaced a real one.

`application.yml`'s `resilience4j.circuitbreaker.configs.default` set `sliding-window-size: 10`
but never set `minimum-number-of-calls`, so it fell back to resilience4j's library default of
**100** — unreachable by a count-based window that only ever holds 10 calls. Every named breaker
(`catalogServiceCB`, `searchServiceCB`, `reviewServiceCB`, `recommendationServiceCB`,
`userServiceCB`, all inheriting `default`) could accumulate failures forever without ever
opening.

**Verified live**, `docker kill` on the real `catalog-service` container, hitting
`GET /api/movies` through the gateway:

- **Before the fix:** 6 consecutive requests each took ~4.0s (the full TimeLimiter budget) before
  falling back to the 503 JSON body — Prometheus confirmed `resilience4j_circuitbreaker_state{...,
  state="closed"}` stayed `1.0` throughout. The fallback controller worked, but the entire point
  of a circuit breaker — failing fast instead of every request paying the timeout — was defeated.
- **After adding `minimum-number-of-calls: 5`:** attempts 1-4 still paid the ~4s timeout
  (accumulating toward the minimum), attempt 5 onward failed in ~8-10ms via
  `CallNotPermittedException` → 503, and Prometheus confirmed `state="open"`.
- **Recovery**, `docker compose up -d --force-recreate --no-deps catalog-service`: after
  `wait-duration-in-open-state` (10s) elapsed, the next request succeeded (`200`, 0.41s — the
  half-open probe) and Prometheus confirmed `CLOSED → OPEN → HALF_OPEN → CLOSED`, matching the
  same recovery shape verified elsewhere in this ADR.

One config line, but it affects all five routes identically since they all inherit `default` —
`recommendationServiceCB`'s override only changes `timeout-duration`, not this.

## Update 5 (2026-09-21): user-service's Postgres outage took 30s to even report DOWN, then leaked internals in a raw 500

user-service's hardening pass (ADR-0006) had 24 passing tests but, like catalog-service and
api-gateway before this ADR closed their gaps, no real Postgres outage test. Unlike the
Mongo-backed services, user-service never bypassed Spring Data JPA's autoconfiguration, so it
already had a `DataSourceHealthIndicator` for free — no missing-bean gap like Update 1's. The gap
here was different: nothing bounded how long a request, or `/actuator/health` itself, would wait
on an exhausted HikariCP pool, and no circuit breaker existed to fail fast once Postgres was
confirmed down — user-service was the only Postgres-backed, JPA service in the fleet with no
resilience wrapping around its database calls at all.

**Verified live**, `docker stop` on the real `postgres` container, hitting the real running
user-service (not a stub):

- **Before the fix:** `/actuator/health` correctly returned `503`/`"status":"DOWN"` — but only
  after **30.04s**, since Boot's health indicator borrows a connection from the same pool it's
  reporting on, and HikariCP's own default `connection-timeout` is 30s. A real request
  (`POST /api/users/login`) took the same ~30s, then returned a **raw 500** whose body was
  Hikari's own exception text verbatim: `"Unable to acquire JDBC Connection [HikariPool-1 -
  Connection is not available, request timed out after 30001ms...]"` — internal pool state
  leaking straight into an API response.
- **Fixed:** `spring.datasource.hikari.connection-timeout: 3000` (fail in 3s, not 30s) plus a new
  `postgresCircuitBreaker` (`PostgresCircuitBreakerConfig`, same shape as catalog-service's
  `MongoCircuitBreakerConfig`) wrapping every `UserRepository` call in `UserServiceImpl`;
  `CallNotPermittedException` maps to a clean 503 in `GlobalExceptionHandler` instead of falling
  through to the generic 500 handler that was leaking Hikari's message. Business exceptions
  (`ValidationException`, `ResourceNotFoundException`, `UserAlreadyExistsException`,
  `InvalidCredentialsException`, `DataIntegrityViolationException`) are excluded so they don't trip
  it.
- **After the fix:** `/actuator/health` reported DOWN in **3.03s**. Login attempts 1-4 still paid
  the new ~3s Hikari timeout (accumulating toward the breaker's `minimum-number-of-calls: 5`) and
  still surfaced as a raw 500 with Hikari's message — expected, matching catalog-service's own
  pre-open behavior, since the breaker itself hasn't tripped yet. Attempt 5 onward failed in
  ~7-9ms via `CallNotPermittedException` → a clean 503 JSON body, no internals leaked. Log
  confirmed `CircuitBreaker 'postgres' changed state from CLOSED to OPEN`.
- **Recovery:** `docker compose up -d --force-recreate --no-deps postgres`, waited past
  `wait-duration-in-open-state` (10s): log confirmed `OPEN to HALF_OPEN`, then, after
  `permitted-number-of-calls-in-half-open-state` (3) successful probes, `HALF_OPEN to CLOSED` —
  the same full recovery shape verified elsewhere in this ADR. Login requests returned to
  `200`/~0.08-0.11s throughout.

Also closed while here, surfaced by the same dead-code/drift audit rather than the chaos test
itself: ADR-0006's "Follow-up work" item 1 (forward a trusted identity from api-gateway once it
exists, so `POST /api/users/{id}/activity` doesn't have to trust a caller-supplied path id) was
half-done — api-gateway forwards `X-User-Id` since its own build-out, but user-service never
consumed it. Now it does: the header, when present, must match the path `{id}` or the request
gets a 403 (`ForbiddenActivityException`). Verified live through the real gateway: a token for
user 33 posting to `/api/users/33/activity` returns 202, the same token posting to
`/api/users/32/activity` returns 403. The header stays optional (falls back to trusting the path
id, unenforced) for any caller that bypasses the gateway, since ADR-0006 already documents that as
an accepted gap, not one this pass closes.

## Update 6 (2026-09-21): review-service's circuit breaker compiled, passed 24 tests, and never actually opened

review-service was flagged in Update 5's "How to apply" item 9 as the one remaining
Postgres-backed service with zero resilience wrapping. Following the same recipe as user-service
(HikariCP `connection-timeout` tuning + a `postgresCircuitBreaker` wrapping every repository
call) looked like a straightforward port — it built clean, all 24 existing tests still passed,
and `/actuator/health` correctly reported `DOWN` in ~3s during a first `docker stop` test. But
**the breaker itself never opened.** Six consecutive `POST /api/reviews` calls during the outage
all returned the same raw 500 (`"Could not open JPA EntityManager for transaction"`) with no
`CircuitBreaker 'postgres' changed state` log line anywhere — the exact failure mode Update 5's
fix was supposed to eliminate, on code that looked identical in shape to user-service's.

**Root cause:** `createReview`/`updateReview`/`deleteReview` are `@Transactional`, and the
circuit breaker was wrapped *inside* each method body (`postgresCircuitBreaker.executeSupplier(()
-> { ...whole method... })`), matching user-service's `register()`. But `@Transactional` only
works via a Spring AOP proxy around the method call, and that proxy's advice opens (or, during an
outage, blocks trying to open) a database connection *before* the method body — and therefore the
breaker call inside it — ever executes. A circuit breaker can only protect code it wraps from the
outside; wrapping it around the inside of the very method whose entry point is the failure point
protects nothing. The same mechanism affected `OutboxPoller.pollAndPublish()`, also
`@Transactional`, with the breaker wrapped around its inner `findBatchForUpdateSkipLocked` call.

This raises a real, not yet independently re-verified question about whether user-service's own
`register()` — also `@Transactional`, also wrapped the same way — has the identical latent gap;
Update 5's live verification exercised `login()`/`getUserById()` (both non-transactional, where
this ordering issue doesn't apply), not `register()` specifically. Flagged in "How to apply"
below rather than assumed fixed by association.

**Fix:** split the `@Transactional` write logic out into separate beans, called *through* the
circuit breaker from a non-transactional caller — the same self-invocation-avoidance pattern this
codebase already used for `OutboxRowPublisher` (kept separate from `OutboxPoller` so its `NESTED`
savepoint actually applies through a real proxy call, not a self-invoked one). Concretely:

- `ReviewWriteOperations` (new bean) now owns `createReviewTx`/`updateReviewTx`/`deleteReviewTx`,
  each `@Transactional`. `ReviewServiceImpl` itself dropped `@Transactional` entirely and now only
  does business validation, then calls into `ReviewWriteOperations` *through*
  `postgresCircuitBreaker.executeSupplier(...)` — so the breaker call is the thing that invokes
  the proxy, not something buried inside it.
- `OutboxBatchProcessor` (new bean) now owns the `@Transactional` claim-and-publish body;
  `OutboxPoller` dropped `@Transactional` and just calls
  `postgresCircuitBreaker.executeRunnable(outboxBatchProcessor::claimAndPublishBatch)`.
- `OutboxRowPublisher.publishOne`'s own `@Transactional(propagation = NESTED)` was left alone —
  a `NESTED` savepoint reuses the *already-open* connection from its caller's transaction rather
  than checking out a new one from the pool, so it doesn't hit this ordering problem. Its
  `markPublished` call is still wrapped in the breaker (helps the "pool exhausted, other threads
  waiting" case), but a genuinely severed connection mid-transaction is a different failure mode
  (a stale-socket/query timeout, not a pool-checkout timeout) that HikariCP's `connection-timeout`
  doesn't govern — not addressed here, noted as a residual gap in the README.

**Re-verified live** after the fix, same `docker stop` on the real `postgres` container:
attempts 1-5 against `POST /api/reviews` still paid the ~3s Hikari timeout and surfaced the raw
500 (expected — same pre-open behavior as user-service, accumulating toward
`minimum-number-of-calls: 5`), then attempt 6 onward failed in milliseconds via
`CallNotPermittedException` → clean 503, with `CircuitBreaker 'postgres' changed state from
CLOSED to OPEN` confirmed in the logs. The outbox poller's own calls (background, on its own
`scheduling-1` thread) fast-failed the same way. On `docker compose up -d --force-recreate
--no-deps postgres`, full recovery: `OPEN to HALF_OPEN` (~10s later) then `HALF_OPEN to CLOSED`
(~7s after that, driven by the poller's own probe calls since nothing else was generating traffic
at the time) — a subsequent `POST /api/reviews` returned `201` normally, and all outbox rows
written during the outage, including ones from before the fix's own throwaway chaos-test data,
published successfully once Postgres came back (`publish_attempts: 1`, no manual intervention).

29 tests pass after the restructuring — the original 24 (2 dropped from `OutboxPollerTest` moved,
unchanged in substance, into a new `OutboxBatchProcessorTest`; 4 from `ReviewServiceImplTest`
moved into a new `ReviewWriteOperationsTest`; net +5 across the split, all outcomes preserved).

## Update 7 (2026-09-21): user-service's `register()` had the exact same latent gap, closed by removing `@Transactional` rather than splitting it

Update 6's "How to apply" item 11 flagged this as unverified: `register()` is `@Transactional`
and wraps the circuit breaker the same way review-service's `createReview` originally did, but
Update 5's live verification only exercised `login()`/`getUserById()` (both non-transactional).
Verified live the same way: `docker stop` on `postgres`, then eight consecutive
`POST /api/users/register` calls — all eight returned a raw 500
(`"Could not open JPA EntityManager for transaction"`), no `CircuitBreaker 'postgres' changed
state` line anywhere in the logs for that window. Confirmed: the identical gap.

**Fix, deliberately different from Update 6's:** `register()`'s `@Transactional` was removed
entirely rather than splitting it into a separate bean. The two fixes solve different problems.
review-service's transaction genuinely had to span two writes (`reviews` + `outbox_events`) for
the outbox pattern's atomicity guarantee, so the transactional boundary itself had to be
preserved — hence the bean split. user-service's `register()` does exactly one write
(`userRepository.save`), which Spring Data JPA already wraps in its own implicit transaction;
the `existsByEmail` pre-check ahead of it was never atomic *with* the save in any way that
mattered, since the real guard against the duplicate-email race is the database's own
`uq_users_email` unique constraint, already backstopped by
`GlobalExceptionHandler#handleDataIntegrityViolationException`. So `@Transactional` here was
protecting nothing that wasn't already protected, and removing it resolves the circuit-breaker-
ordering bug with no added structure — the "re-question existing config choices" step (checklist
step 5) applies to *why the transaction existed at all*, not just to timeout values.

**Re-verified live** after the fix: attempts 1-4 still paid the ~3s Hikari timeout (now surfacing
as the underlying `"Unable to acquire JDBC Connection [HikariPool-1 ...]"` message directly,
since there's no `JpaTransactionManager` wrapping it anymore — a cosmetic difference, not a
regression), attempt 5 onward failed via `CallNotPermittedException` → clean 503, confirmed
`CircuitBreaker 'postgres' changed state from CLOSED to OPEN` in the logs. user-service has no
background poller generating its own traffic (unlike review-service's outbox poller), so the
`OPEN → HALF_OPEN` transition only fired on the next *actual* request after
`wait-duration-in-open-state` elapsed, not on a timer — sent one request to trigger it, then two
more to satisfy `permitted-number-of-calls-in-half-open-state` (3): confirmed
`OPEN to HALF_OPEN` then `HALF_OPEN to CLOSED`, registrations returning `201` normally
throughout. All 26 existing tests pass unchanged.

Also worth recording: this verification was blocked for a while by Docker Desktop itself hanging
(`docker ps`/`docker version` both hung indefinitely) — traced to the *host* Mac being nearly out
of disk (3.6GB free on a 113GB volume, against a 19GB Docker VM image already using a chunk of
that) plus tight memory, with Docker Desktop's GUI process dead and only orphaned backend helpers
left running. Killing the orphaned processes and relaunching Docker Desktop resolved it, but the
disk-space pressure itself wasn't fixed — see "How to apply" below.

## How to apply

1. ~~Register an explicit Mongo health indicator for catalog-service and search-service~~ — **done,
   see Update 1 above** — superseded by Update 2: both services now get the health indicator from
   Boot's own autoconfiguration instead of a manually-wired bean, removing the failure mode
   (hand-wiring drifting out of sync) rather than just patching its one known symptom.
2. Any tooling — chaos scripts, manual ops runbooks — that mutates a running container's resources
   via `docker update` (or otherwise out-of-band from `docker-compose.yml`) must restore via
   `docker compose up -d --force-recreate`, never a plain `up -d`. A plain restart silently makes
   the temporary override permanent.
3. The recommendation-service → search-service fallback pattern is now verified against three
   distinct real failure modes (outright kill, injected latency, OOM) in addition to the load-test
   evidence in ADR-0010 — no further action needed there, it holds.
4. Chaos experiments here used SMOKE-level background load (2 VUs), not full stress scale.
   Combining fault injection with the 30-VU stress profile from ADR-0010 would be the natural next
   escalation if deeper validation is wanted.
5. Any resilience4j `circuitbreaker` config block — here or in any future service — must set
   `minimum-number-of-calls` explicitly rather than relying on the library default. It defaults to
   100, silently disabling the breaker under any `sliding-window-size` smaller than that (see
   Update 4) — a config-review item, not just a one-off gateway fix.
6. `chaos/lib.sh` is reusable for future experiments (new fault types, other target services) — see
   its two portability/errexit fixes in Consequences above before trusting a script built on top of
   it that also uses background subshells.
7. recommendation-service's own Mongo/Redis outage behavior is now hardened and verified (Update
   3) — Boot's default (untuned) Mongo driver timeouts are worth checking on any *other* service
   that uses plain autoconfiguration without an explicit `MongoConfig`-style timeout customizer;
   this round only checked recommendation-service.
8. The same lesson applies beyond Mongo: any connection-pool default (HikariCP's 30s
   `connection-timeout`, or an equivalent in a future non-JPA datastore) is worth checking
   explicitly rather than assuming it's already tuned, and a health indicator that borrows from the
   same pool it's reporting on inherits that pool's untuned timeout too (see Update 5) — a fast
   health check needs a fast-failing pool underneath it, not just a health indicator that exists.
9. ~~review-service is the one remaining Postgres-backed service with no circuit breaker around
   its database calls at all~~ — **done, see Update 6 above.**
10. A circuit breaker wrapped *inside* an `@Transactional` method protects nothing during a full
    outage: the transactional proxy's connection acquisition happens before the method body runs,
    so it fails (or hangs) before the breaker call inside is ever reached (see Update 6). Any
    future `@Transactional` method that needs breaker protection must have its transactional body
    live in a separate bean, called through the breaker from a non-transactional caller — the same
    shape `OutboxRowPublisher` already required for its `NESTED` savepoint to apply through a real
    proxy call. Check this specifically wherever a circuit breaker is added around code that is
    also `@Transactional`, rather than assuming the wrap-the-whole-method-body pattern from a
    non-transactional example (like user-service's `login()`) generalizes.
11. ~~user-service's `register()` is `@Transactional` and wraps the circuit breaker the same
    (now-known-broken) way review-service's `createReview` originally did~~ — **done, see Update
    7 above.** Confirmed the same gap, fixed by removing `@Transactional` (it wasn't protecting
    a genuine multi-statement atomicity requirement) rather than the bean-split Update 6 used —
    check which of the two fixes actually applies before reaching for the heavier one.
12. The host's data volume was down to 3.6GB free (of 113GB) when Docker Desktop's backend hung
    during Update 7's verification — worth clearing space (or at minimum knowing this is the
    likely culprit) before assuming a Docker hang is an application- or compose-level problem.
    Docker Desktop's own VM disk image (`~/Library/Containers/com.docker.docker/Data/vms/0/data/Docker.raw`)
    was already 19GB and only grows; it doesn't shrink itself when host space gets tight.
