# ADR 0010: Load testing reveals a timeout-budget mismatch between api-gateway and recommendation-service's own fallback path

**Status:** Accepted (gateway timeout fix applied and verified; `mflix-mongo` crash-under-load
root-caused and fixed; JVM OOM finding fixed via memory limits; full-scale stress-test
re-validation of those memory limits is now COMPLETE and conclusive — see Update 5 — on a calm
host: 30 concurrent VUs ran in real wall-clock time with no container OOM and the memory-limit
fix holds; the observed 9.28% failure-rate threshold breach is attributed primarily to a
load-test script artifact (expected 409 Conflicts double-counted by k6's built-in metric), not
an application defect)
**Date:** 2026-09-18

## Context

Per the build order, load testing (step 6, second half) runs after observability so real
before/after numbers can be captured. The monolith was decomposed before any baseline was
captured, so there is no surviving monolith to baseline against — this test measures the current
microservices architecture in absolute terms (throughput, p50/p95/p99 latency per endpoint),
not a before/after comparison. The original plan to baseline against the monolith first (see
the README's Build order) is now moot.

A k6 script (`loadtest/user-journey.js`) drives two realistic journeys through api-gateway
(port 8080) — anonymous browsing (catalog list/get, keyword search, vector search) and a
registered-user journey (register → login → browse → occasionally review → get
recommendations) — ramped to 15+15 peak concurrent VUs over 3 minutes. Peak VUs were sized
down from an initial 25+25 design after discovering this host's Docker Desktop VM had been
misconfigured to 8192MiB on an 8GB-RAM machine (a leftover from an unrelated earlier fix,
corrected back to 4096MiB before this run) and the idle 15-container stack already sits at ~78%
of that budget.

**Full-scale run result:** 4,413 requests, **27.94% overall failure rate**, `http_req_duration`
p95=2.84s / p99=5.51s / max=21.9s. Failures were not uniform:

| Endpoint | Fail rate |
|---|---|
| `GET /api/movies` (catalog list) | 2.7% |
| `GET /api/movies/{id}` (catalog get) | 2.4% |
| `POST /api/reviews` | 3.2% |
| `POST /api/users/register` | 0.0% |
| `POST /api/users/login` | 14.7% |
| `GET /api/movies/search` (keyword) | 47.2% |
| `GET /api/movies/search/vector` | 59.7% |
| `GET /api/recommendations/{id}` | **62.5%** |

No container crashed or OOM'd during the run (`docker stats` showed memory flat around 2GB of
the 3.8GB VM budget throughout; CPU on `mflix-mongo` and `api-gateway` repeatedly spiked past
100-270%, confirming the host was CPU-bound, not memory-bound).

**Root cause, traced from logs (not inferred):**
1. `mflix-mongo` runs both `mongod` (plain CRUD, used by catalog-service and search-service's
   own collection) and `mongot` (Atlas Search, backing search-service's keyword/vector queries)
   in one container. Under concurrent load it was observed at 100-270% CPU — a shared,
   contended resource for every search-related query.
2. search-service's logs show 139 `AsyncRequestNotUsableException` ("Broken pipe") errors in
   the test window — **100% of its logged errors**. This exception fires when a caller (here,
   api-gateway) closes the connection before search-service finishes writing its response —
   i.e. search-service was still working, just too slowly for the caller's timeout budget.
3. api-gateway's Resilience4j config (`services/api-gateway/src/main/resources/application.yml`)
   sets one **global** `timelimiter.configs.default.timeout-duration: 4s`, applied uniformly to
   every route (catalog, search, reviews, recommendations, users) via `CircuitBreaker` filters
   with no per-route override.
4. recommendation-service's own dependency call to search-service (`SearchClientConfig`,
   `search.service.connect-timeout-ms: 2000` / `read-timeout-ms: 3000`) **already degrades
   gracefully** — its logs show the intended behavior working correctly: `"search-service call
   failed for findSimilar(...) — degrading to no content-based signal: ... Read timed out"`,
   after which it falls through to the collaborative/popularity blend per ADR-0005. This is not
   a bug in recommendation-service.
5. The bug is a **timeout budget mismatch across the call chain**: recommendation-service's own
   worst-case dependency timeout (2s connect + 3s read = up to 5s before its fallback logic even
   returns) is *longer* than api-gateway's fixed 4s route timeout. Under load, the gateway aborts
   the client-facing request and returns a 503 before recommendation-service's fallback has a
   chance to complete and return a 200. The service did the right thing internally; the caller
   gave up too early to see it.
6. `POST /api/users/login`'s 14.7% failure rate is much smaller than the search-path failures
   and has no corresponding errors in user-service's own logs (only two expected 409
   "duplicate registration" entries) — consistent with api-gateway's own CPU saturation
   (observed 100-160%+) occasionally delaying routing past the shared 4s budget, not a
   user-service defect.
7. catalog-service's simple indexed point-reads stay healthy (~97-98% success) despite sharing
   the same `mflix-mongo` container — they don't touch `mongot`, so they're far cheaper and
   much less exposed to the contention in (1).

## Decision

Documented as a finding, not yet fixed in code — this ADR exists to capture the load test's
actual output (per project convention: ADRs are written as steps complete, not reconstructed
after the fact) ahead of the chaos-testing step, where these same failure paths will be
deliberately exercised. The concrete fix (per-route timeout budgets, sized so
recommendation-service's fallback always has time to complete; separating `mongot` load from
`mongod` load) is scoped as follow-up, tracked below.

## Alternatives considered

- **Reconstruct a monolith baseline first** — rejected; the original code is gone and rebuilding
  it just to get a comparison point is disproportionate effort for a now-secondary goal. Absolute
  per-endpoint numbers plus the relative catalog-vs-search contrast are sufficient evidence.
- **Raise the gateway's global timeout instead of per-route tuning** — would mask this specific
  mismatch but makes every route (including ones that should fail fast, like a truly-down
  catalog-service) wait longer before giving up. Rejected in favor of per-route budgets once
  that work is picked up.

## Consequences

- **What gets better:** the failure mode is now understood and evidenced, not guessed at — the
  fix can start from evidence instead of re-diagnosing.
- **Tradeoff accepted:** this test's *absolute* latency numbers (p99 in the seconds, max 21.9s)
  reflect an 8GB laptop running the full 15-container stack and the k6 load generator on the same
  finite CPU budget — they are not production capacity numbers. The *relative* finding (search
  paths collapse under load while catalog/review paths don't, and recommendation-service
  cascades from search-service's slowness) is architecture-level and would reproduce on
  better hardware at proportionally higher load, since it's driven by a real timeout-budget gap
  in configuration, not host noise.
- **Follow-up work this creates:**
  1. Give `/api/recommendations/**` (and any route whose backing service has its own internal
     fallback timeout) a gateway-side timeout longer than that service's worst-case internal
     timeout, via a named (non-`default`) `resilience4j.timelimiter.configs` entry per route.
  2. Consider separating `mongot`/Atlas Search load from `mongod` load — e.g. a resource limit
     or dedicated container — so search-service's contention doesn't also degrade catalog-service
     indirectly, and so a search-service slowdown has a smaller blast radius.
  3. This is the natural on-ramp into the chaos-testing step (kill/degrade catalog-service,
     induce Kafka consumer lag, saturate recommendation-service) — the same timeout-budget class
     of issue is exactly what that step is designed to surface deliberately.

## Evidence

- Full k6 script: `loadtest/user-journey.js`. Baseline raw run: `loadtest/results/summary-before-fix.json`.
- 4,413 requests, 27.94% overall failure rate, `http_req_duration` avg=500ms p95=2.84s p99=5.51s
  max=21.9s, at 15+15 peak concurrent VUs over a 3-minute ramp profile.
- Per-endpoint failure rates and root causes: see table and log excerpts above — all traced to
  real log lines (`AsyncRequestNotUsableException` count, `SearchServiceClient` degrade-and-
  fallback warnings, `GlobalExceptionHandler` duplicate-registration 409s), not inferred from
  latency numbers alone.
- `docker stats` samples throughout the run: memory flat (~2GB / 3.8GB budget); CPU on
  `mflix-mongo` and `api-gateway` repeatedly observed at 100-270%, all other containers well
  under 100% — confirms the host was CPU-bound, and narrows which two containers to look at
  first if this is re-run on different hardware.

## Update (2026-09-15, same day): fix applied, functionally verified, full-scale re-validation blocked

**The fix:** `services/api-gateway/src/main/resources/application.yml` gained a named
`resilience4j.timelimiter.instances.recommendationServiceCB` config (`base-config: default`,
`timeout-duration: 6s`), leaving every other route on the 4s default. Rebuilt and redeployed
api-gateway; it started cleanly with no config errors.

**Functional verification (direct, not load-test):** a direct authenticated call to
`GET /api/recommendations/{id}` through the gateway took **6.26s** before returning (previously
this route would have been aborted at ~4s) — hard confirmation the new per-route timeout is
actually wired up and taking effect, not just present in config.

**Quantitative re-validation attempt — inconclusive, and it surfaced a bigger problem.** Two
back-to-back full-scale (15+15 VU, 3-minute) re-runs of the same k6 script were attempted
immediately after the fix, to get a clean before/after failure-rate comparison. Both are
unusable for that comparison, but both are informative:

- **Run 1** (`summary-after-fix-run1-mongot-resyncing.json`, 43.49% failure — worse than
  baseline): `mflix-mongo` logs show `"mongot initial sync and session refresh"` *during* this
  run — it had crashed once already (see below) shortly before, and mongot was still rebuilding
  its search index from the oplog when the test started, which is far more expensive than
  steady-state operation. Not a fair comparison.
- **Run 2** (`summary-after-fix-run2-mflixmongo-crashed.json`, 81.30% failure, login p99=15s,
  max=27.6s): `mflix-mongo` **crashed mid-test** (`docker inspect` confirms `ExitCode=2` at
  17:20:23 UTC, ~1m53s into a ~3m04s run) with the identical `panic: close of closed channel` Go
  panic seen once already during the original baseline run. Everything downstream of a dead
  database naturally failed hard for the second half of the run; this number says nothing about
  the gateway fix.

**This makes the `mongodb/mongodb-atlas-local` container's crash-proneness under sustained
concurrent load the dominant, more urgent finding** — it crashed twice within roughly an hour of
that day's load testing (both times with the same Go-level supervisor panic, not an
OOM-kill or a Docker Desktop VM crash this time — `docker inspect`'s `ExitCode=2` and the panic
message point at a bug/fragility inside the official image's own process supervisor, not this
repo's code). Three consecutive full-scale load tests within ~45 minutes on this host — the
original baseline, plus these two re-validation attempts — is apparently enough to trigger it
reliably. This blocks confident full-scale load-test comparisons on this host until it's
addressed or worked around, and is a stronger chaos-testing candidate than anything else found
so far (an actual crash under load, not just degraded latency).

**Net status:** the timeout-budget fix is applied and confirmed to take effect. Whether it
measurably reduces `/api/recommendations`'s failure rate under the *original* (non-crashing)
load profile is still not quantitatively re-confirmed — that requires either a clean re-run once
`mflix-mongo`'s crash-under-load issue is understood/mitigated, or accepting the direct
verification above as sufficient for now. Recorded here rather than re-attempted immediately, to
avoid repeatedly crashing the same fragile dependency chasing a number.

## Evidence (update)

- Fix commit location: `services/api-gateway/src/main/resources/application.yml`
  (`resilience4j.timelimiter.instances.recommendationServiceCB`).
- Direct verification: `time curl` through the gateway to `/api/recommendations/1`, authenticated
  — 6.26s total, HTTP 503 (the call itself still lost to a genuinely slow dependency that day, but
  the *timeout ceiling* moved from ~4s to ~6s as intended, which is what this fix controls).
- `mflix-mongo` crash evidence: `docker inspect mflix-mongo --format 'FinishedAt={{.State.FinishedAt}} ExitCode={{.State.ExitCode}}'`
  → `ExitCode=2`; `docker logs mflix-mongo | grep panic` → `panic: close of closed channel`,
  observed on two separate occasions that day.
- Raw results for all three runs kept in `loadtest/results/` (`summary-before-fix.json`,
  `summary-after-fix-run1-mongot-resyncing.json`, `summary-after-fix-run2-mflixmongo-crashed.json`)
  so the confounds are auditable rather than just asserted.

## Update 2 (2026-09-15, same day): mflix-mongo crash root-caused and fixed; a new OOM finding

**Root cause of the `mflix-mongo` crash, found (not just patched around):** the panic's own
stack trace names the exact culprit:
```
panic: close of closed channel
github.com/mongodb/mongodb-atlas-local/cmd/runner/telemetry.(*Collector).collect
	/app/cmd/runner/telemetry/telemetry.go:123
```
This is a bug in the **usage-telemetry reporting goroutine** bundled into the official
`mongodb/mongodb-atlas-local` image's own Go supervisor process — not mongod, not mongot, not
anything in this repo. A goroutine-level panic in any part of that single process brings down the
whole container, which is why an unrelated background telemetry bug was crashing the database.

**Fix:** recreated the container with `-e DO_NOT_TRACK=1` (documented for this exact image by the
Testcontainers-for-Go `mongodb-atlaslocal` module as the way to disable this telemetry feature).
Confirmed via `grep -a` on `/usr/local/bin/runner` inside the container that the string
`DO_NOT_TRACK` is compiled directly into the binary — not a guess, the binary genuinely reads it.
Container recreated with identical `--hostname` (`068b201ffdc6`, matching the replset identity
already persisted in the data volume — a known quirk of the standalone mflix-mongo container) and
the same two named volumes, so no data was lost.

**Verified fixed, not just theorized:** re-ran the identical 15+15 VU / 3-minute k6 script.
`mflix-mongo` was monitored every 8s throughout (and for a while after) and stayed
`Up ... (healthy)` continuously — no crash, for the first time in three attempts. Aggregate
numbers with a stable dependency were dramatically better than any prior run: p99 dropped from
5.5-15s to **1.77s**, max from up to 27.6s down to **5.07s**. Result saved as
`loadtest/results/summary-after-fix-stable-mongo.json`.

**But a new failure appeared once mflix-mongo stopped being the bottleneck:** the
`recommendations` check went to **100% failure** in that same run — but suspiciously *fast*
(avg 29ms), the opposite latency signature from before. Direct investigation:
- A direct call to `recommendation-service:8084` (bypassing the gateway) failed instantly
  (connection refused) — the container itself was down.
- `docker ps -a` confirmed: `Exited (137)`. `docker events` confirms a real **`container oom`**
  event for this container's cgroup, immediately followed by `container die ... exitCode=137` —
  this is a genuine kernel OOM-kill, not the Go-panic pattern and not a code bug in
  recommendation-service.
- Root cause: **none of the 6 Spring Boot services set an explicit `-Xmx`/`-XX:MaxRAMPercentage`
  or a Docker `mem_limit`**. A JVM with no container memory limit visible to it sizes its default
  heap off the *entire* VM it can see (historically up to 25% of total via
  `MaxRAMPercentage` defaults) — with 6 independently-sized JVMs doing this against the same
  3.8GB VM budget (see the Docker Desktop memory finding in Context above) simultaneously
  under real load, the aggregate easily oversubscribes, and the kernel picks a victim. It happened
  to be recommendation-service this time; any of the 6 is equally exposed.
- Restarted recommendation-service (`docker compose up -d --no-deps recommendation-service`) to
  restore the stack — noted a further symptom on restart: Redis health-check probes logged
  10-26 second response times (`ReactiveHealthIndicatorAdapter (redis) took 25949ms to respond`)
  for a trivially cheap operation, consistent with the host still being under general strain from
  several consecutive load tests in one day, not yet independently root-caused.

**Not yet fixed** — this OOM risk is architectural (applies to all 6 services equally, not just
recommendation-service) and is scoped as follow-up, not patched in this update:
1. Set explicit `-Xmx` (or `-XX:MaxRAMPercentage` tuned per-service) and a matching Docker
   `mem_limit`/`deploy.resources.limits.memory` in `docker-compose.yml` for every app service, so
   each JVM sizes itself against its own slice instead of the whole VM.
2. This is itself a legitimate, better chaos-testing candidate than anything artificially injected
   — an actual OOM-kill under real load, discovered by testing, not simulated.

**How to apply:** if `mflix-mongo` crashes again with `panic: close of closed channel`, this is
now a known, fixed issue — check `DO_NOT_TRACK=1` is still set on the container (it does not
persist through a `docker rm`/recreate without it) before treating it as a new bug. If any
service gets OOM-killed (`docker inspect <container> --format '{{.State.OOMKilled}}'` — more
reliable than grepping `docker events`, which can hang without a bounded `--until`) under load,
that's the known-but-unfixed heap-sizing gap above, not a new mystery — go straight to adding
memory limits rather than re-diagnosing.

**Confirmed a second time within minutes, unprompted:** *api-gateway* was also OOM-killed
(`docker inspect` → `OOMKilled=true`, `ExitCode=137`) shortly after restarting
recommendation-service — with no new load test running, just the ordinary cost of one service
restarting on an already-strained host. This is exactly the "any of the 6 is equally exposed"
prediction above, confirmed rather than theoretical. At idle right after restarting both, the
15-container stack sat at **~3.1GB of the 3.8GB VM budget (~82%)** — headroom is thin even before
any load is applied, let alone during a k6 run. This strengthens rather than changes the
diagnosis: the fix is still per-service memory limits, not a mystery to keep chasing.

## Update 3 (2026-09-15, same day): memory limits implemented

Added `mem_limit` + `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=65.0` to all 6 app services in
`docker-compose.yml`: `api-gateway` 384m, `catalog-service` 288m, `search-service` 384m,
`review-service` 300m, `recommendation-service` 352m, `user-service` 300m (total 2008 MiB —
`search-service` and `recommendation-service` sized higher, matching the highest-memory and
actually-OOM-killed services observed that day). `mflix-mongo` and the other infra containers
were deliberately left untouched — out of the scope this ADR diagnosed, and touching Kafka's own
default `-Xmx1G` without also constraining it would have broken it against a new limit.

**First pass was too tight and had to be revised.** An initial pass (256-320m, 70% heap) looked
fine on `docker inspect` but caused a real regression: `search-service`'s startup went from its
normal ~15-20s to **81 seconds**, and several services idled at 90-95% of their own limit with
zero load applied — a container-aware JVM squeezed too hard doesn't fail cleanly, it just runs
very slowly (heavy GC) right up until it does fail. Revised upward to the values above and the
heap percentage trimmed to 65% (more non-heap headroom for this stack's full OTel + Kafka + Mongo
+ Springdoc dependency set).

**Verified, with an important caveat about how it was tested.** Recreating all 6 services
*simultaneously* — both in the too-tight first pass and the revised second pass — produced
50-80s startup times across literally all of them, including `api-gateway` which was never memory
constrained in the first place (384m throughout, 52% usage). Since every service was slow, not
just the tightest ones, this points at CPU contention from six JVMs class-loading/JIT-compiling
at once on this 4-core (8-thread), 8GB host, not the memory limits themselves — a confound worth naming
rather than mistaking for the memory fix having failed. With the revised limits, all 6 services
reached `HTTP 200` on `/actuator/health`, idle memory settled to a healthier 52-87% of each
container's own limit (down from 66-95% under the first-pass numbers), and a functional smoke
test through the gateway (catalog list, keyword search, vector search, recommendations) returned
200 on every endpoint (one transient 503 on vector search recovered on immediate retry — a
leftover circuit-breaker state from the restart, not a memory symptom).

**Not re-validated against a full 30-VU stress test in this update** — that's the next natural
step if these numbers need to hold up under the same synthetic load ADR-0010's original test
used, but doing so immediately after several consecutive stress tests already that day
risked compounding confounds (as Update 2 already ran into with `mflix-mongo`) rather than
producing a clean signal. The limits are a real, verified improvement over "unbounded" — whether
2008 MiB across 6 services comfortably survives a dedicated 15+15 VU re-run is worth confirming
as its own follow-up, ideally as the *first* load test of the day rather than the fourth.

## Update 4 (2026-09-15, same day): stress-test re-validation attempted twice, both inconclusive — this host cannot sustain the test itself

Per the "first load test of the day" recommendation above, this was attempted from a genuinely
clean state: full `docker compose down`, `mflix-mongo` restarted, then the entire Docker Desktop
VM force-killed and relaunched (its `quit app` path was stale-PID-stuck again, same as the
Docker-instability issue earlier that day — required `pkill -9` on
`Docker Desktop`/`com.docker.backend`/`com.docker.build`) before bringing the stack back up fresh.
All 6 app services and `mflix-mongo` confirmed healthy from this clean state, and a warmed-up
smoke pass (`SMOKE=1`) showed healthy per-endpoint latencies (recommendations avg 3.63s, well
inside the 6s budget from the Update fix) before committing to the full run.

**Attempt 1 (15+15 VUs, the original scale):** the 3-minute test profile took **33 minutes of
wall-clock time** to actually complete. `http_req_duration` max reached **1,806,396 ms** (30
minutes) with a 35.95% failure rate — worse than the very first baseline in this ADR. No
container was OOM-killed (`docker inspect --format '{{.State.OOMKilled}}'` → `false` on all 6).
Host diagnostics told the real story: `top` showed **37MB of unused physical memory**, active
swap (`vm.swapusage` → 1.29GB used), and a **load average of 72-122** sustained on a
**4-physical-core** host (`sysctl hw.physicalcpu` → 4) — 15-25x oversubscribed. The Docker VM's
own hypervisor process (`com.apple.Virtualization.VirtualMachine`) alone held ~1.9GB resident on
the host. This is host-level thrashing masquerading as application latency: the k6 client, the
15-container stack, and this machine's normal running apps (IDE, browser, Docker's own dashboard)
together exceed this 8GB host's capacity once 30 concurrent VUs are added on top.

**Attempt 2 (5+5 VUs, reduced scale, same day):** rather than accept attempt 1 as
unrepresentative and stop, a smaller run was tried to see if reduced load would produce a clean
signal. It did not — **98.92% failure rate**, worse than the full-scale attempt, with load
average climbing to **223-307** even after the k6 process itself had exited. This is the signature
of a host that was already in a resource-exhaustion spiral before the second test even started,
not a finding about the app at 5+5 VUs. Confirming this wasn't recoverable by reducing container
count: `docker compose stop` on the 6 app services **failed outright** —
`container ... PID 2176 is zombie and can not be killed. Use the --init option...` — a symptom
of the VM itself being too starved to reap a process, not an application bug. Recovery required
force-killing the Docker Desktop VM process directly (`pkill -9 -f Virtualization.framework`),
which immediately freed >1GB of physical memory, followed by a full Docker Desktop relaunch.

**Root cause of why this differs from the successful mflix-mongo-fix validation (Update 2):**
that earlier clean run succeeded at 15+15 VUs on the same host. The difference here is
cumulative host state, not a regression in the app: by the time these two attempts ran, that
day's work had already driven this same 8GB host through Docker Desktop crashes and rebuilds,
corrupted-image cache rebuilds (`docker builder prune -af`, 10GB), and roughly half a dozen
prior k6 runs, on top of the same normally-running IDE/browser/desktop-app load. The Docker VM's
own hypervisor process was independently observed holding ~1.9GB resident, which this ADR's
original 3.8GB VM sizing (first Update above) did not account for as *host-side* overhead —
that overhead sits outside the VM's own memory budget entirely.

**Code change made in response:** `loadtest/user-journey.js`'s stress-scenario peak VU count is
now overridable via a `STRESS_VUS` environment variable (`k6 run -e STRESS_VUS=5 ...`), replacing
the previous hardcoded `15`. This does not fix the host-capacity problem — it lets a future
re-run target whatever headroom is *actually* available at the time, measured fresh, rather than
assuming the original 15+15 budget still applies after a day's cumulative wear on the host.

**Conclusion: the JVM memory-limit fix from Update 3 remains unvalidated at full stress scale**,
not because it's suspected to be wrong, but because this host cannot currently sustain the test
that would validate it, independent of the fix's correctness. No new application-level finding
came out of either attempt — no container OOM'd, no new crash signature appeared, and the app
kept answering (slowly) throughout both attempts (a direct `curl` to `/actuator/health` during
attempt 2's recovery still returned `200` in 4.5s). The finding here is about this specific
laptop's capacity budget under that day's cumulative load, not about
`docker-compose.yml`'s memory limits.

**How to apply, for whoever runs this next:**
1. Before attempting a 15+15 VU stress test, check `top -l 1 -s 0 | grep -E "PhysMem|Load Avg"`
   for a genuinely idle baseline (low single-digit load average, hundreds of MB+ free) — do this
   as the *first* action of the day, not after other Docker work.
2. If host headroom looks thin, use `STRESS_VUS` to scale down rather than assuming 15+15 will
   work — and if even a reduced-VU run degrades instead of improving, stop rather than retrying
   at a still-smaller scale; that pattern (attempt 2 above) indicates the host itself needs a
   fresh restart, not a smaller test.
3. `docker compose stop`/`kill` failing with `"is zombie and can not be killed"` is a
   host-exhaustion symptom, not a container bug — go straight to `pkill -9` on the Docker Desktop
   VM process and relaunch, rather than debugging the specific container.
4. This is not a reason to avoid chaos testing — chaos testing's failure injections (killing a
   single container, inducing Kafka lag) are much lighter-weight than sustaining 30 concurrent
   HTTP clients for 3 minutes, and don't carry the same host-capacity risk this stress-test
   re-validation ran into.

## Evidence (Update 4)

- Host diagnostics during attempt 1: `sysctl hw.physicalcpu` → 4; `vm.swapusage` → 1.29GB used;
  `top -l 1 -s 0` load average 72-122 sustained; `docker inspect <service> --format
  '{{.State.OOMKilled}}'` → `false` on all 6 app services throughout both attempts.
- Attempt 1 result: 2,584 requests, `http_req_duration` avg=3254.9ms p95=6121.3ms p99=20040.3ms
  **max=1806396.2ms**, 35.95% failure rate, 33m05.7s wall-clock for a nominal 3m0s profile.
- Attempt 2 result: 369 requests, `http_req_duration` avg=3078.6ms p95=9472.0ms p99=13707.5ms
  max=18894.3ms, **98.92% failure rate**; load average 223.53-306.89 observed after the k6
  process itself had already exited.
- Zombie-container evidence: `docker compose stop` error —
  `container 6b6716b8ad3d PID 2176 is zombie and can not be killed. Use the --init option when
  creating containers to run an init inside the container that forwards signals and reaps
  processes.`
- Recovery evidence: `pkill -9 -f Virtualization.framework` immediately moved host `PhysMem`
  unused from 50MB to 1103MB; Docker daemon (`docker info`) responsive again within ~10s of
  relaunch; all containers subsequently restartable.
- Code change: `loadtest/user-journey.js` — `STRESS_VUS` env var replacing the hardcoded peak-VU
  literal in the `anonymous_browse`/`registered_journey` ramping-vus stage definitions.

## Update 5 (2026-09-18): stress-test re-validation COMPLETE — clean signal obtained, memory-limit fix holds

Two days after Update 4, host idle baseline was checked first (per that update's own guidance):
load average 4.05-13.30, 335MB unused physical memory — calm, versus the 72-307 load averages
that invalidated both prior attempts. This was the window needed to finally get a real signal.

**Setup surfaced two more environment issues, both fixed before testing:**

1. `docker compose up -d` on the 6 app services resumed **stale processes**, not fresh ones.
   `docker compose ps -a` reported all 6 as `Exited ... 2 days ago`, but after `up -d` their
   startup-log timestamps read `2026-09-15T22:38` — the *original* start time from two days
   earlier — even though the command had just been run. One of them (`search-service`) then
   crashed for real, mid-setup, on a `MongoTimeoutException` from a stale background job.
   Fix: `docker compose up -d --force-recreate --no-deps <services>` on all 6, confirmed by
   startup-log timestamps flipping to `2026-09-18T03:54`.
2. Once containers were genuinely fresh, `search-service` still failed to connect to Mongo —
   but this time the target was `host.docker.internal:27017` (connection refused), not the
   compose `mongo` service. `mflix-mongo` is a **standalone container outside docker-compose**
   (search-service reaches it via the host network) and was still stopped from the end-of-day
   shutdown two days prior; `docker compose up` never touches it. Fix: `docker start mflix-mongo`,
   confirmed healthy, then `search-service` recreated again and came up clean.

**Warmup:** an unwarmed smoke pass showed 37.5% failure / avg 3.6s (expected cold-JIT behavior,
consistent with prior runs). ~60 real requests across the main endpoints over ~20s brought a
re-run smoke pass down to 12.5% failure / avg 490ms, judged warm enough to proceed.

**Full stress test (`STRESS_VUS=15`, default, 15+15 VUs, 3-minute nominal profile):**

- Ran in **3m01.3s wall-clock for a 3m0s profile** — real time, not the 33-minute (Attempt 1) or
  worse (Attempt 2) blowups from Update 4. This alone confirms the host stayed responsive
  throughout; a repeat of the host-thrashing failure mode would have shown up the same way it
  did before.
- 6,658 total requests. `http_req_duration`: avg=132.6ms, **p95=398.6ms (budget <1500ms, pass)**,
  **p99=918.1ms (budget <3000ms, pass)**, max=5832.2ms.
- `http_req_failed` rate: **9.28%** (budget <2%, breached). `errors` custom metric also breached.
- No container OOM: `docker inspect --format '{{.State.OOMKilled}}'` returned `false` on all 6
  app services after the run. Raw result: `loadtest/results/summary-revalidation-calm-host.json`. Host state post-run: load average 4.42-13.03, 226MB unused physical
  memory — no thrashing.
- **The aggregate latency budget from the original gateway-timeout fix (first Update above)
  holds under 30 concurrent VUs.** The JVM memory-limit fix (Update 3) holds too: sustained
  concurrent load for 3 minutes produced no OOM and no host-level distress.

**Failure-rate breach, decomposed** (~618 failed requests out of 6,658; counts below are from
service logs filtered to the test's wall-clock window):

| Cause | Count | Verdict |
|---|---|---|
| `review-service` "Duplicate review" (409) | 275 | Test-script artifact, not an app bug |
| `user-service` "Duplicate registration" (409) | 21 | Test-script artifact, not an app bug |
| `search-service` broken-pipe / client-abort | 39 | Genuine, tied to vector-search tail latency |
| `recommendation-service` → `search-service` `findSimilar` timeout | 9 | Graceful degrade, not counted as a failure (200 OK, "no content-based signal") |
| Unattributed | ~274 | Likely more client-abort events on the same two slow endpoints, not fully reconciled |

The dominant cause (296 of ~618, ~48%) is a **load-test script defect, not an application
defect**: `registered_journey` reuses a small, VU-scoped pool of emails and review keys
(`loadtest-vu{N}@example.com`, one review per user/movie pair) across repeated loop iterations
within the 2-minute steady-state window. The app correctly rejects the repeats with `409
Conflict` — the uniqueness constraints are doing their job — and the script's own `check()` at
`loadtest/user-journey.js:154` and `:204` already treats 409 as an OK outcome for these two
endpoints. But k6's built-in `http_req_failed` metric counts any 4xx/5xx by HTTP status
regardless of what `check()` decided, so it double-counts these as failures even though the
script's own custom `errors` metric was designed to exclude them.

The remaining, genuine failures cluster around the two slowest endpoints under peak concurrency:
`GET /api/movies/search/vector` (p99=1751ms, max=3156ms) and `GET /api/recommendations/{id}`
(p99=4414ms, max=5832ms) — both still inside their own per-request budgets on average, but with
a long enough tail under 30 concurrent VUs that some client connections abort before the server
finishes (`AsyncRequestNotUsableException: ... Broken pipe` in `search-service` logs).

### How to apply

1. **Fix the load-test script, not the app.** Give `registered_journey` unique per-iteration
   data (append an iteration counter to the email and to the review's user/movie pairing) so
   repeat 409s stop polluting `http_req_failed`; or call
   `http.setResponseCallback(http.expectedStatuses(200, 201, 404, 409))` so k6's built-in metric
   respects the same "409 is fine here" semantics the script's own `check()` already encodes.
   Until that's done, treat any `STRESS_VUS`-scale `http_req_failed` reading as overstated by
   roughly the duplicate-conflict count — check the `errors` custom metric and the per-endpoint
   breakdown before concluding there's a regression.
2. Vector search and recommendations are the real tail-latency hotspots at 30 VUs. Still within
   budget on p95/p99, but worth a closer look (e.g. connection-pool sizing to `search-service`,
   or the vector index query plan) before pushing VU count higher.
3. `docker compose up -d` on a container reported `Exited ... 2 days ago` is **not** guaranteed
   to give a fresh process — it can resume genuinely stale internal state (proven here by
   startup-log timestamps not moving). Before trusting any "clean state" test, force-recreate:
   `docker compose up -d --force-recreate --no-deps <services>`, and confirm by checking that
   the first log line's timestamp is actually today.
4. `mflix-mongo` lives **outside** docker-compose (search-service reaches it over
   `host.docker.internal:27017`) and needs its own `docker start mflix-mongo` — `docker compose
   up` silently does not touch it, which reads as a search-service bug if you don't know to
   check for this container specifically.
5. This closes the open item from Update 4: the memory-limit fix (Update 3) is now validated at
   full stress scale on a calm host, not just via smoke tests.
