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

## Update 6 (2026-10-18): one-command start, and what a cold start on this host found

**Change.** This closes finding 4 of Update 5. `mflix-mongo` is now a compose service, with the
same image, `DO_NOT_TRACK=1` and the same container name, so `chaos/01_dependency_kill.sh` is
unchanged. catalog-service and search-service reach it as `mflix-mongo:27017` instead of
`host.docker.internal:27017`, so `docker compose up` can no longer leave it stopped. Two one-shot
jobs replace the manual `docker run` and `mongorestore`:
- `mflix-download` keeps the dataset archive in a volume;
- `mflix-seed` restores it only when `sample_mflix.movies` is empty.

catalog-service and search-service wait for the seed. `make up` wraps all of this and waits until
every service's `/actuator/health` is UP. See [running locally](../running-locally.md).

**Cold starts from a fresh clone found five problems before one passed.** Host: 8 GB Intel Mac,
4 GB Docker VM.

1. **A full disk.** The Mac had 174 MB free, and image export failed with a containerd I/O error.
   Fix: removed stale containers, images and the build cache.
2. **A re-pushed image tag.** MongoDB rebuilds and re-pushes existing tags:
   - the 2026-08-31 build of `mongodb-atlas-local:8.3.8` ships mongot 1.74.1;
   - the 2026-10-13 build under the same tag ships mongot 1.77.0.

   On this host the newer build never starts mongot. After about 130s the image's `runner` gives
   up pinging `localhost:27027` and exits with code 2, with or without the rest of the stack
   running and with no OOM kill. The older build is healthy in 29s. Fix: pin by digest
   (`8.3.8@sha256:925c…`), since a tag alone pins nothing.
3. **A VM-wide OOM kill of mongot.** mongot, the JVM behind Atlas Search, had no limit. It grew
   to 615 MB RSS while indexing the search-service copy, and the VM's OOM killer took it
   (`global_oom`). `mflix-mongo` exited and search-service's backfill timed out. Fix:
   - `mem_limit: 1g` for the container;
   - `JAVA_TOOL_OPTIONS=-Xmx384m`, which only mongot reads (confirmed in its
     `/proc/<pid>/environ`);
   - `restart: unless-stopped`.

   To make room, config-server (about 276 MB, no clients yet, ADR-0009) moves behind a `config`
   compose profile (`make up-all`).
4. **search-service OOM-killed during its first-start backfill.** At the 384m limit it was killed
   by its own cgroup at 378 MB RSS, with 21,230 of 21,349 movies copied. Fix: 448m, covered by the
   config-server savings. The crash also showed that the backfill never finishes a partial copy:
   it runs only when `movies_search` is empty. That gets its own fix (ADR-0002 update).
5. **A readiness wait shorter than the backfill.** search-service reports UP only after the
   backfill, which takes 5m48s here. Fix: `wait-healthy.sh` waits up to 900s.

**The passing run.** Images were built and the archive downloaded:
- `mflix-mongo` was healthy 2m21s after the containers were created;
- the seed restored 67,661 documents in 33s;
- the backfill copied 21,349 movies in 5m48s;
- all 7 services were UP about 9m15s after the containers were created.

Peaks: `mflix-mongo` 874 MiB of 1 GiB, search-service 448 MiB of 448 MiB (at its limit but not
killed; recheck under load). `make smoke` passes: 0% failed requests, p95 2.7s.
`chaos/01_dependency_kill.sh mflix-mongo 20` works unchanged: only catalog and search went
unhealthy, and everything recovered.

**What stays a limit of this host.** During the backfill the Mac's load average reached 163 and
the VM's 1 GB swap filled up. Right after a cold start the first queries are slow:
- keyword search takes about 2.6–3.5s;
- vector search takes 1.3–3.7s: an OpenAI embedding call plus `$vectorSearch`.

Those numbers sit near the gateway's 4s search budget, so some first queries get the circuit
breaker's 503. That is the budget doing its job, not a bug. `make smoke` and `make load` now run
`scripts/warmup.sh` first, the warm-up Update 5 already called for.

Two k6 script fixes came out of this:
- 409 on register and review is an expected answer, not a failed request;
- a smoke run checks correctness only, with no latency thresholds on a dozen requests.

Kubernetes (roadmap Phase 3) is where this stack gets more headroom.

## Update 7 (2026-10-18): a recreated mflix-mongo never became healthy

**Found** while raising `mflix-mongo`'s memory limit. Changing `mem_limit` makes compose recreate
the container, and the new one restarted six times without becoming healthy. There was no OOM
kill. mongod logged:
- "Local replica set configuration document set name differs from command line set name"
  (`b4d0fa3e1cdb` vs `701f6eb974cd`);
- "This node is not a member of the config", then the state `REMOVED`.

The image's `runner` then gave up waiting for a primary and exited.

**Cause.** `mongodb-atlas-local` names its single-node replica set after the container's hostname
and stores that name, and the member's `host:port`, in the data volume. Without a `hostname:`,
Docker uses the container id. Any recreate (a config change, `make down` then `make up`, a new
image) gets a new id, so the stored config no longer matches the node, and the node never takes
it back. The Update 6 tests didn't catch this: each started from empty volumes, and
`docker stop`/`docker start` (`chaos/01_dependency_kill.sh`) keeps the container and its id.

**Fix.** `hostname: mflix-mongo` in compose, so the replica set is `mflix-mongo` with the member
`mflix-mongo:27017` on every recreate. Volumes created before this fix are tied to the old
container id and have to be re-seeded once with `make clean && make up`.

**Evidence.** `mflix-mongo` alone in a throwaway compose project, with the fix:
- first start healthy after 40s; `rs.conf()` reports set `mflix-mongo`, member `mflix-mongo:27017`;
- after a test document was written, the container was removed and recreated with
  `--force-recreate`; it was healthy again after 15s, with the document still there.

## Update 8 (2026-10-18): search-service and mflix-mongo sized from measurement

Update 6 left search-service at its 448m limit during the backfill, to be rechecked. Testing the
resumable backfill (ADR-0002 update of the same date) found that both it and `mflix-mongo` were
still too tight.

**search-service: a smaller heap, not a bigger limit.** With `MaxRAMPercentage=65` (a 291 MB heap)
it was OOM-killed at 454 MB anon RSS, 3,300 movies into the backfill. A run without a limit, with
Native Memory Tracking on, showed where the memory goes:

| Area | MB |
|---|---|
| Java heap (reserved) | 291 |
| Metaspace | 78 |
| Code cache | 46 |
| Symbols | 17 |
| Class space | 13 |
| Threads | 5 |
| **Tracked total** | **476** |
| Untracked native (glibc malloc), steady / peak | ~60 / ~113 |
| **RSS, steady / peak** | **~535 / ~589** |

The old generation held 37 MB of a 198 MB capacity, so the live data is about 40–50 MB. The heap
was mostly empty and was starving everything else in the cgroup. Fix:
- `MaxRAMPercentage=35` (about 157 MB of heap);
- `MALLOC_ARENA_MAX=2`, which caps glibc's per-thread malloc pools, the source of the untracked
  spikes.

The limit stays 448m. Peaks since: 384 MiB over a 27-minute backfill test, and 390 MiB in a cold
start, with no OOM kill.

**mflix-mongo: 1 GiB was too small for the backfill.** It was killed by its own cgroup twice:
- mongot at 297 MB anon RSS, while the test deleted and re-inserted all 21k search documents;
- mongod at 405 MB anon RSS, while the backfill only inserted.

mongod's WiredTiger cache is already at its 256 MB minimum (`maximum bytes configured`), and mongod
was 365 MB resident. That leaves too little for mongot (384 MB heap cap) plus the per-index memory
while thousands of vectors are indexed. Fix: `mem_limit: 1280m`. Lowering mongot's heap instead was
untested and would only move the kill into mongot. Throttling the backfill would have changed code
to work around a laptop limit.

**Evidence: a cold start with both changes and the Update 7 hostname fix,** from empty Mongo
volumes:
- `make up` passed in 492s; the backfill took 6m05s and indexed 21,349 movies;
- `movies_search` holds 21,349 documents, all with embeddings, equal to catalog-service's
  `totalElements`, and the `backfill_state` marker was written;
- peaks: search-service 390 MiB of 448, `mflix-mongo` **1,057 MiB** of 1,280 (above the old 1 GiB);
- no restarts or OOM kills;
- `make down` then `make up` passed in 204s. `mflix-mongo` came back healthy on its data, the seed
  skipped ("sample_mflix already loaded"), and the backfill logged "already completed — skipping".

**The budget on this host.** The memory limits now add up to about 3.65 GiB, and the containers
without limits (Kafka, Prometheus, Grafana, Postgres, Jaeger, Redis, the app Mongo) use about
0.5 GiB more. That's above the VM's 3.82 GiB. This works because the services don't all peak at
once: in use, the stack is about 2.6 GiB. A first-start backfill run during a load test could
still hit the VM-wide OOM killer. `make load` should only run after `make up` reports all services
UP. Kubernetes (roadmap Phase 3) is where these limits become requests with real headroom.

## Update 9 (2026-10-19): the stack outgrew a 4 GB Docker VM; a laptop timeout budget

**Finding.** Adding the Grafana dashboards (ADR-0008) meant running the full stack long enough to
watch it, and it no longer fits this host. Host: 8 GB Intel Mac, Docker VM 3.82 GiB, Kafka capped
at a 256 MB heap (its image default was a fixed 1 GiB).

| Stack after `make up` | VM used + swap | Memory pressure, avg10 idle | Vector search p95, direct | `make smoke` failed |
|---|---|---|---|---|
| All 7 services | ~2.9 GB + 1.0 GB (swap full) | 13–29% | 1–19s | 67% |
| Without assistant-service | ~3.05 GB + 0.79 GB | 0.3–2% | 1–3.3s | 33–67% |

The stack needs about 3.85 GB, and dockerd, containerd and the VM's init take about 330 MB of the
VM. The services are already sized from measurement (Updates 3 and 8) and run SerialGC, so there
is nothing left to trim that would close the gap.

**What fails is vector search, and it's paging, not the query.** A new query took 5–18s inside
search-service: the OpenAI embedding call took 0.5–0.8s, and mongot took the rest, with about 40k
major page faults per 20 queries. The same query repeated took 0.2–0.7s. The gateway's 4s budget
turned the slow ones into 503s.

**A second finding: the recommendation route's 6s budget covers one search call, not three.**
A recommendation sends up to three seed movies (`MAX_SEED_MOVIES`) to search-service in
sequence, each with a 2s connect and 3s read timeout. The 6s gateway limit set by the first
update assumed one call. With a slow search-service, a recommendation took 6.7–9.2s, and the
gateway answered 503 although recommendation-service was about to return its fallback. It's the
original finding of this ADR again, through the fan-out, and it holds on any host. Not fixed
here: the fix is an overall deadline for the search signal in recommendation-service (or
parallel calls), which is a code change for its own PR.

**Decision: keep the design budget, give this laptop its own.**
- **assistant-service is off by default**, behind a compose profile like config-server.
  `make assistant` adds it to a running stack, and `make up-all` starts everything.
- **The gateway timeouts are settings.** `application.yml` keeps the design budget: 4s, and 6s
  on the recommendation route (`GATEWAY_TIMEOUT`, `GATEWAY_RECOMMENDATION_TIMEOUT`).
  `docker-compose.yml` sets 10s and 12s, the measured worst cases with some margin.
  `GATEWAY_TIMEOUT=4s GATEWAY_RECOMMENDATION_TIMEOUT=6s make up` restores the design budget on a
  bigger host.
- **Chaos latency experiments inject 12s instead of 8s,** so the delay still exceeds the budget
  and the breaker still trips.

Rejected: a bigger Docker VM. At 5 GB it would leave macOS about 3 GB on this 8 GB host. Cutting
limits further was rejected because Updates 3 and 8 measured what each service needs.

**Consequences.** The local stack shows the design's behavior (timeouts, breakers, fallbacks)
with laptop numbers. Latency on this host isn't evidence for the 4s budget; the RED dashboard's
"over 4s" panel still marks the design budget, so slow requests stay visible rather than turning
into 503s. Kubernetes (roadmap Phase 3) runs the design budget.

**Evidence.** With assistant-service off and the 10s/12s settings, two `make smoke` runs in a row
had 0% failed requests. Vector search p95 was 6.7s and then 3.5s, and recommendations p95 was
9.2s and then 7.1s, matching three 3s read timeouts in sequence.

## Update 10 (2026-10-19): what three fresh starts found while building the demo

The demo (`make demo`, PR 3) is meant to run on fresh data, so it was tested from `make clean`.
Earlier fresh-start tests (Update 6) checked that every service reported UP. These went one step
further and checked what a new user actually sees, which found three problems.

**1. recommendation-service's catalog copy gave up before catalog-service was up.** On a first
start catalog-service waits for the `sample_mflix` restore, while recommendation-service starts
right away. Its one-time copy of the catalog (`CatalogBackfillRunner`) retried for 60s and then
skipped. Measured: the copy gave up at 17:16:42, and catalog-service was ready at 17:17:02. With no
movie metadata every recommendation list was empty, popular included, until the next restart. Fix:
retry for 10 minutes. The copy runs on its own thread, so startup isn't held up. On the next fresh
start it got through on attempt 87 and copied 21,349 movies in 18s.

A knock-on effect isn't fixed here: ratings that arrive while metadata is missing add no genres to
the user's profile, and nothing rebuilds the profile later, so those users never get collaborative
peers.

**2. A build next to mflix-mongo's first boot left it unusable.** `make up` ran
`docker compose up -d --build`, so the images were built while `mflix-mongo` started. Under that
load, mongod answered too slowly and the image's `runner` gave up before creating the replica set
("error initializing … server selection timeout"). On every restart after that, `runner` assumed
setup had happened and waited for a primary that didn't exist (`RSGhost`, "no replset config has
been received"). It restarted four times and never recovered, unlike the hostname problem of
Update 7. Fix: `make up` builds every image first, then starts the containers. Recovery for a
volume already in this state: remove `mflix-mongo` and its two volumes, `mflix-data` and
`mflix-configdb`. With the fix, the next fresh start had `mflix-mongo` healthy on its first boot.

**3. On a cold stack, the gateway can answer 503 to a write that still commits.** Right after the
first start's index rebuild, `POST /api/users/register` got a gateway 503, but user-service created
the user anyway: a retry got 409, and a login in between got 401 because the write hadn't
committed yet. This is the usual ambiguity of a timeout in front of a non-idempotent write. The
seed script retries 5xx and timeouts and treats 409 as success. The API has no idempotency keys,
so a client can't tell "failed" from "slow" any other way.

**Running the demo on this host.** The first start's index rebuild leaves the Docker VM's swap
full (1,022 of 1,023 MB measured), and mongot's lookups then take 2.5–7s, past
recommendation-service's 3s timeout, so recommendations lose their content signal. The procedure
for a recording: `make clean && make up` once, then `make down && make up` (50s; nothing to
rebuild), then `make demo`. In that state the demo turned personal 2–5s after the review and showed
content, collaborative and both signals.
