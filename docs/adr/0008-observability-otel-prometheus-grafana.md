# ADR 0008: OpenTelemetry tracing + Prometheus/Grafana metrics across all six services

**Status:** Accepted
**Date:** 2026-09-14

## Context

Observability is README build-order step 6 ("Add observability (OpenTelemetry + Jaeger,
Prometheus/Grafana), and only then load test, so the numbers come from running services"), the step right after api-gateway's resilience work (ADR-0007) and right
before step 7's chaos testing. `docker-compose.yml` already scaffolded `jaeger`, `prometheus`,
and `grafana` containers, but **none of the six application services (api-gateway plus the five
domain services) exposed any metrics or trace endpoint, and `infra/prometheus.yml` — the file the
`prometheus` container's compose block already mounted — didn't exist.** The infrastructure
containers ran; nothing fed them.

This turned out to need more live verification than any prior ADR in this repo, because static
inspection — even careful decompilation — was twice actively misleading:

- `spring-boot-actuator-autoconfigure-4.1.1.jar` — the module that carried tracing/observation
  autoconfiguration in Boot 3.x — now has **zero** tracing- or observation-related classes in it
  (confirmed: `unzip -l` on the jar, grep for `tracing`/`observation`, no hits). That logic moved
  to dedicated modules (`spring-boot-micrometer-observation`, `spring-boot-micrometer-tracing`,
  `spring-boot-micrometer-tracing-opentelemetry`, `spring-boot-opentelemetry`), and the correct
  dependency turned out to be Boot's own bundled `spring-boot-starter-opentelemetry` — confirmed
  by reading `spring-boot-dependencies-4.1.1.pom`'s `<dependencyManagement>` directly, not the
  Boot-3-era `micrometer-tracing-bridge-otel`/`opentelemetry-exporter-otlp` pair a web search
  would suggest.
- **The property that actually enables the OTLP trace exporter is not the one decompilation
  pointed to.** `javap`-decompiling `OtlpTracingProperties` in
  `spring-boot-micrometer-tracing-opentelemetry-4.1.1.jar` showed a real, bound class with a real
  `endpoint` field under `management.otlp.tracing.*` — a completely reasonable property to set,
  and the config-metadata JSON lists it with no hint that anything is wrong. Setting
  `management.otlp.tracing.endpoint` compiled, started cleanly, produced **no errors or warnings
  of any kind**, and simply exported nothing: `curl localhost:16686/api/services` stayed at
  `{"data":null,"total":0}` no matter how many requests were made. The only thing that caught
  this was running with `--debug` and reading Spring's own condition-evaluation report, which
  showed `OtlpTracingConfigurations.ConnectionDetails#otlpTracingConnectionDetails` — the bean
  that actually wires up the exporter — gated on a *different* property entirely:
  `management.opentelemetry.tracing.export.otlp.endpoint`. `management.otlp.tracing.*` is a real,
  separately-bound properties class that Boot 4.1.1 ships alongside the real gate, and nothing
  about compiling, starting, or decompiling it reveals that it isn't the one wired to anything —
  only a live trace check (or the `--debug` report) does. This is the strongest example yet in
  this repo of "verify live, not just via decompilation" — even
  decompiling the *correct-looking* class was not enough this time.
- The default `transport` for that exporter **is** HTTP, confirmed via the same decompilation —
  so the endpoint is `<host>:4318/v1/traces`, not Jaeger's OTLP/gRPC port `4317` already exposed
  in compose for other tooling.

Separately, auditing the repo's existing outbound HTTP clients (`SearchClientConfig` in
recommendation-service; `CatalogClientConfig`, `OpenAiClientConfig`, `VoyageClientConfig` in
search-service) found all four built their `RestClient` via the static `RestClient.builder()`
factory method rather than an injected `RestClient.Builder` bean. Only the Spring-managed builder
bean carries the `ObservationRestClientCustomizer` that propagates trace context and emits a
client-side span — the static factory bypasses it entirely. Fixing this surfaced a second real
runtime failure once tried live: **`spring-boot-starter-web` does not transitively provide the
auto-configured `RestClient.Builder` bean in Boot 4.1.1** (that autoconfiguration now lives in a
separate `spring-boot-restclient` module, needing the explicit `spring-boot-starter-restclient`
dependency) — both services hard-failed at startup (`BeanCreationException`: no qualifying bean
of type `RestClient$Builder`) until that starter was added. The static-builder code had silently
never needed it before; only switching to the correct, traceable pattern exposed the gap.

A third thing only live testing caught: `spring-boot-starter-opentelemetry` also auto-enables an
OTLP **metrics** push exporter by default (`management.otlp.metrics.export.enabled` defaults to
`true`), independent of the tracing exporter. Jaeger only ingests OTLP traces (and logs), not
metrics — this repo's metrics story is Prometheus *scraping* `/actuator/prometheus` (pull), not
services pushing to Jaeger. Left on, every service logged a `WARN` every export interval:
`Failed to publish metrics to OTLP receiver ... 404 page not found`. Harmless, but needless noise
that would mask a real failure later — explicitly disabled.

## Decision

**Every service** (api-gateway, catalog-service, search-service, review-service,
recommendation-service, user-service) gets the same three additions:

1. `spring-boot-starter-actuator` + `io.micrometer:micrometer-registry-prometheus` — exposes
   `/actuator/health`, `/actuator/info`, `/actuator/prometheus` (Prometheus text-format metrics,
   auto-registered once both are on the classpath).
2. `spring-boot-starter-opentelemetry` — Boot 4's own bundled starter for the Micrometer
   Observation → OpenTelemetry span bridge and OTLP export.
3. The same `management:` block in every `application.yml`:
   ```yaml
   management:
     endpoints:
       web:
         exposure:
           include: health,prometheus,info
     tracing:
       sampling:
         probability: 1.0
     opentelemetry:
       tracing:
         export:
           otlp:
             endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}
     otlp:
       metrics:
         export:
           enabled: false
   ```
   **100% sampling is deliberate**, not an oversight — this is a low-traffic local/demo
   deployment where seeing every trace matters more than sampling overhead; would need to drop
   sharply (e.g. 0.05–0.1) before any real deployment with real traffic.

`docker-compose.yml`: `jaeger` now also publishes `4318:4318` (OTLP/HTTP, what every service
actually exports to — `4317` stays published for any other OTLP/gRPC tooling). Every service's
compose block gets `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: http://jaeger:4318/v1/traces`. `infra/prometheus.yml` is
created (it was referenced by compose but never existed) with one `scrape_config` per service
targeting its `/actuator/prometheus` over the compose network. `grafana`'s compose block mounts
`./infra/grafana/provisioning:/etc/grafana/provisioning`, which auto-registers Prometheus as a
datasource on container start (`infra/grafana/provisioning/datasources/datasource.yml`) — no
manual "Add data source" click required.

**No service gets a hard `depends_on` on `jaeger`/`prometheus`/`grafana`.** Trace/metric export is
best-effort: the OTLP exporter silently drops spans if Jaeger isn't reachable yet, and Prometheus
scrapes (pull, not push) whenever it's ready. This is categorically different from `depends_on:
[kafka]`/`[postgres]`, which exist because those failures block startup by design.

**Fixed the four `RestClient` beans** (`SearchClientConfig`, `CatalogClientConfig`,
`OpenAiClientConfig`, `VoyageClientConfig`) to accept an injected `RestClient.Builder` parameter
and build from it, instead of calling the static `RestClient.builder()` factory — and added
`spring-boot-starter-restclient` to search-service and recommendation-service (the two that use
`RestClient`) so that builder bean actually exists to inject.

## Alternatives considered

- **An OpenTelemetry Collector between the services and Jaeger** — the more "production-shaped"
  topology, and the standard recommendation once you have multiple trace/metric backends or need
  collector-side processing (batching, PII scrubbing, fan-out to multiple backends). Rejected for
  now: Jaeger natively accepts OTLP directly, there's exactly one trace backend, and adding a
  Collector here would be a moving part with no current consumer of what it'd add — same
  "complexity isn't earned yet" reasoning as ADR-0007's config-server deferral. Revisit if a
  second telemetry backend is ever added.
- **Zipkin/Brave bridge instead of the OpenTelemetry bridge** — `spring-boot-micrometer-tracing`
  supports either; rejected because the README explicitly names "OpenTelemetry + Jaeger," and
  OTel is the more actively-developed, vendor-neutral standard of the two.
- **Pre-built Grafana dashboards, provisioned alongside the datasource** — rejected for this pass:
  without real load yet (that's step 6's other half, load testing, and step 7's chaos testing),
  a hand-built dashboard would be guessing at which panels matter rather than reflecting an
  actual incident or bottleneck. Revisit once step 7 produces a real "here's what broke and what
  the graph looked like" case worth pinning permanently.
- **Metrics only, defer tracing** — a lighter first cut, rejected because distributed tracing is
  specifically what will make the gateway's routing/circuit-breaker fan-out and the one real
  cross-service call legible end-to-end, which is exactly what step 7's chaos testing needs to be
  able to show.

## Consequences

- Every service now exposes `/actuator/health`, `/actuator/info`, and `/actuator/prometheus`;
  `docker compose up` brings up a working Jaeger UI (:16686), Prometheus (:9090, with all six
  services as scrape targets), and Grafana (:3001, Prometheus pre-wired as a datasource).
- Trace continuity depends on both ends of any call being instrumented via the Spring-managed
  `RestClient.Builder`/`WebClient.Builder`. This repo's one on-request-path inter-service call
  (recommendation-service → search-service) and api-gateway's reactive proxying (Spring Cloud
  Gateway's own reactor-netty client is auto-instrumented once Observation is on the classpath,
  no code change needed there) are both covered; any *future* inter-service HTTP client must
  use the injected `RestClient.Builder`/`WebClient.Builder` (never the static factory) and, if
  it's the first `RestClient` user in that service, needs `spring-boot-starter-restclient` added
  explicitly — or it will silently produce no client span at all, the same way the static factory
  did here. OpenAI/Voyage calls are instrumented too (client-side span for latency visibility),
  even though those providers won't honor the propagated trace headers themselves.
- 100% trace sampling and a stateless export path add negligible overhead at
  this traffic volume; would need revisiting (lower sampling, batching tuned for throughput)
  before any deployment with real production traffic.
- Grafana still requires a human to build panels/dashboards manually — only the datasource
  connection is automated.

## Evidence

Verified live end-to-end across all six services, not just "the app started" — and the two
property-naming mistakes above were only caught this way:

- Brought up `jaeger`, `prometheus`, `grafana`, `mongo`, `redis`, `kafka` via `docker compose`,
  then ran all six services' jars against them (`--skip-tests package` + `java -jar`, env vars
  pointed at the compose-published ports).
- **First pass failed two different ways**, both fixed and re-verified (see Context): (1) with
  `management.otlp.tracing.endpoint` set, `curl localhost:16686/api/services` returned
  `{"data":null,"total":0}` — zero spans from any service despite no errors anywhere; switching to
  `management.opentelemetry.tracing.export.otlp.endpoint` (found via `--debug`'s condition report)
  fixed it. (2) search-service and recommendation-service hard-failed at startup
  (`BeanCreationException`, no `RestClient.Builder` bean) after switching their `RestClient` beans
  to the injected builder, until `spring-boot-starter-restclient` was added to both.
- After both fixes, `curl localhost:8081/actuator/prometheus` (catalog), `:8082` (search), `:8084`
  (recommendation), `:8083` (review), `:8085` (user), `:8080` (api-gateway, WebFlux) each returned
  real Prometheus text-format metrics (60–80 `# HELP` lines apiece) — confirms the
  actuator+micrometer-registry-prometheus wiring works on both the servlet stack and api-gateway's
  reactive WebFlux stack.
- Exercised one endpoint per service (`GET /api/movies/genres`, `/api/reviews/1`, `/api/users/1`,
  etc.) then queried `curl localhost:16686/api/services`: all six service names appeared
  (`catalog-service`, `search-service`, `review-service`, `recommendation-service`, `user-service`,
  `api-gateway`), alongside Jaeger's own `jaeger-all-in-one`.
- Seeded a rating directly in recommendation-service's Mongo collection, called
  `GET /api/recommendations/{userId}` (triggering the real content-based signal call to
  search-service), then fetched that trace from Jaeger's API
  (`localhost:16686/api/traces?service=recommendation-service`). The result: **one trace, one
  `traceID`**, containing `recommendation-service http get /api/recommendations/{userId}`
  (inbound), `recommendation-service http get` (the outbound `RestClient` call, ~86ms),
  `search-service http get /api/movies/search/{id}/similar` (search-service's inbound span for
  that exact call), plus the Redis `set`/`get` spans — proving the injected-`RestClient.Builder`
  fix actually connects the two services' spans into one trace rather than two orphaned ones.
- Rebuilt catalog-service as a real container (`docker compose up -d --build catalog-service`,
  not a host-run jar) specifically to verify `infra/prometheus.yml`'s compose-network DNS
  resolution (`catalog-service:8081`) — `curl localhost:9090/api/v1/targets` showed
  `{"job":"catalog-service","health":"up"}` once the container was running. (The other five
  targets stay `down` if you point Prometheus at host-run jars instead of containers — expected,
  not a config bug, since compose DNS names only resolve to other containers on that network.)
- Grafana's Prometheus datasource (`localhost:3001`, provisioned, no manual setup):
  `curl -u admin:admin localhost:3001/api/datasources` returned the auto-provisioned "Prometheus"
  entry (`url: http://prometheus:9090`) with no manual configuration step.

## Update: how this was used later

- **Prometheus as evidence:** chaos testing used the gateway's `/actuator/prometheus` metrics to
  confirm circuit breaker state rather than inferring it from response times.
  `resilience4j_circuitbreaker_state` showed a breaker stuck `closed` under a real outage, then,
  after the fix, `CLOSED → OPEN → HALF_OPEN → CLOSED`
  ([ADR-0011 Update 4](0011-chaos-testing.md)).
- **Dashboards:** none were provisioned at the time; Update 2 provisions them.

## Update 2 (2026-10-19): three provisioned dashboards, and the metrics they needed

**Why now.** The deferral above waited for a real incident worth pinning. Chaos testing
(ADR-0011) and the load tests (ADR-0010) produced several, and each was diagnosed from raw
`/actuator/prometheus` output. Three dashboards now ship as provisioning JSON in
`infra/grafana/provisioning/dashboards/json/`:
- **Service overview (RED):** which services are up, request rate, 5xx share, p50/p95/p99 per
  service, the share of requests over the 4s design budget, gateway status codes and p95 per route.
- **Resilience:** breaker state, rejected calls, failure rate and call outcomes for every breaker,
  recommendation fallbacks by reason, gateway 503s, and the Hikari and Mongo pools.
- **Events and JVM:** Kafka consumer lag and throughput, the review outbox backlog, listener time,
  movies without an embedding, heap against its `MaxRAMPercentage` cap, GC share and threads.

They're read-only in the UI (`allowUiUpdates: false`), so the JSON in git is the source of truth.
Grafana allows anonymous viewing and opens on the RED dashboard. The datasource has a fixed uid
(`prometheus`) that the dashboards refer to.

**Building them found seven gaps.** Each panel was checked against live Prometheus, and these
had nothing to show until fixed:

1. **The services' own breakers exported nothing.** The datastore breakers in catalog, search,
   review, user and recommendation-service (ADR-0011) were built outside any registry, and
   `resilience4j-micrometer` wasn't on the classpath. Each config now creates its breaker through
   a `CircuitBreakerRegistry` and binds it with `TaggedCircuitBreakerMetrics` (resilience4j 2.2
   binds from a registry only).
2. **No Kafka consumer metrics in search or recommendation-service.** Their consumer factories are
   built by hand, to use the app's Jackson 2 `ObjectMapper` (ADR-0013), so Boot never adds its
   `MicrometerConsumerListener`. The factories now add it themselves. The trade-off: a hand-built
   factory skips every Boot customizer, so the next one missed would fail the same way. The Jackson
   3 migration should drop these factories for Boot's.
3. **No latency histograms.** p95 needs buckets. Every service now publishes fixed SLO buckets for
   `http.server.requests` (50ms to 8s, 8 per endpoint) instead of Micrometer's ~70-bucket
   percentile histogram, which would have multiplied the series count on a memory-bound host.
4. **The gateway tags every proxied request `uri=UNKNOWN`.** Per-route latency comes from its
   proxied call instead (`http.client.requests`, tagged with the route id), which got its own
   buckets, up to the laptop budgets of ADR-0010 Update 9.
5. **resilience4j's time-limiter metrics stay at 0 on the gateway,** even when the search route
   times out. Spring Cloud CircuitBreaker applies the route timeout itself, and a timeout counts
   as a `failed` call on the breaker. The dashboard shows breaker call outcomes instead.
6. **No signal for recommendations served without one of their signals.** New counter
   `recommendation.fallback{reason}`: `cold_start` (no ratings yet), `no_candidates` (ratings,
   but nothing liked enough to seed from) and `search_unavailable` (search-service failed or timed
   out). A 4xx from search isn't counted: search is up, it just has no vector for that movie. Each
   reason is registered at startup, so a panel shows 0 rather than no data.
7. **No outbox backlog.** New gauge `review.outbox.unpublished`, the number of rows not yet
   relayed to Kafka. It's counted every 15s on a schedule, not on scrape, so a hung Postgres can't
   stall Prometheus. The count goes through the Postgres breaker, so an outage can't hold the
   scheduler thread the outbox poller also uses, and during an outage the gauge keeps its last
   value. The partial index the poller already uses (`idx_outbox_unpublished`) serves the count.

**Not covered.**
- Container memory against `mem_limit`: there's no cAdvisor, so container limits aren't in
  Prometheus. The JVM panel shows heap against its cap; `docker stats` covers the rest. Adding
  cAdvisor would cost memory this host doesn't have (ADR-0010 Update 9).
- The assistant's tokens, cost and time to first token, which belong to roadmap Phase 2 step 8.

**Evidence: `make load VUS=5`** with assistant-service off and the laptop timeouts of ADR-0010
Update 9. Every panel's query returned data. The Resilience dashboard showed the run as it
happened:
- search-service slowed down under paging, and the gateway's `searchServiceCB` opened and rejected
  211 calls fast instead of letting them wait out the timeout;
- recommendation-service kept answering 200, falling back 35 times with `search_unavailable` and
  10 times with `no_candidates`;
- the other routes answered 200, or 409 for repeated test data, with no rejected calls.

The 16.7% failed requests in k6's summary are those rejected search calls. Peaks: `mflix-mongo`
732 MiB of 1,280, search-service 284 of 448, api-gateway 255 of 384, Kafka 223 of 512.

## Update 3 (2026-10-23): an assistant dashboard, with tokens, cost and time to first token

A fourth dashboard, **Assistant (LLM)** (`movies-assistant.json`), covers what Update 2 left for
roadmap Phase 2 step 8. Its panels are answers by outcome, time to first token, tokens per minute
and per answer, model calls per answer, model call p95, cost in the time range and per hour, and
tool calls by tool and outcome, with tool latency.

**What Spring AI already exported:** `gen_ai_client_token_usage_total` (by model and token type),
`gen_ai_client_operation_seconds` (one model call) and `spring_ai_tool_seconds` (one tool call).
Four gaps:

1. **Failed tool calls were counted as successes.** A tool that throws has its message sent to the
   model as the result (ADR-0013), so Spring AI's tool observation never sees an error and tags
   every call `error="none"` (todo 21). New counter `assistant.tool.calls{tool, outcome}`, counted
   in the `ToolCallLog` wrapper, which already sees the exception. A call refused by the tool-call
   limit isn't counted, because the tool never runs; an integration test checks both.
2. **No time to first token.** New timer `assistant.chat.first.token`, from the request to the
   first streamed token. Tool calls and the model calls around them come first, so it is most of
   what the user waits for. The evals measure the same thing from the client side.
3. **No answer outcome.** New counter `assistant.chat.answers{outcome}`: done, error or
   cancelled (the client stopped reading).
4. **No latency buckets on model or tool calls.** SLO buckets, as in Update 2: 250ms to 32s for
   `gen_ai.client.operation`, 500ms to 32s for the first token and 50ms to 8s for tools. The
   meter name has an underscore, so its config key needs brackets (`"[gen_ai.client.operation]"`);
   without them the setting matched nothing and the p95 panel stayed empty.

**Cost is computed in Prometheus, not in the service.** `infra/prometheus-rules.yml` holds one
constant series per model and token type, `gen_ai_price_usd_per_million_tokens`, and the panels
multiply the token counter by it with a join on `gen_ai_request_model` and `gen_ai_token_type`.
The service keeps exporting tokens, the raw fact; a price change is a config change in one place;
and the same rule serves any model. The prices are the ones in `evals/prices.json`, and a test in
`evals/tests/test_prices.py` fails if the two differ. Like the evals, cost is an upper bound:
cached input is cheaper, and the token metric doesn't separate it.

**Not covered.** Spring AI creates its token counter and tool timers on first use, so `rate()`
misses a series' first observation after a restart. The new meters are registered at startup and
start at 0. The model's own reasoning time isn't separate from the model call.

**Evidence.** On the eval stack plus Prometheus and Grafana (`make eval-up eval-metrics`): 11
answers from the eval scenarios, one of them with recommendation-service stopped.
- Every panel query returned data. Answers 11 done, 0 error; model calls 13, matching the harness
  for the first 5 answers; cost $0.0024 against the harness's $0.0022, the gap being how
  `increase()` extrapolates to the edges of the range.
- With recommendation-service stopped, `assistant_tool_calls_total{outcome="failure",
  tool="getMyRecommendations"}` went to 1, while `spring_ai_tool_seconds_count` for the same call
  still said `error="none"`.
- About 3,400 input and 105 output tokens per answer, 2.4 model calls per answer. Input is 97% of
  the tokens: every model call resends the system prompt, the history and the tool results.
- Prometheus and Grafana add about 140 MiB to the eval stack.

![Assistant dashboard](../images/grafana-assistant.png)
