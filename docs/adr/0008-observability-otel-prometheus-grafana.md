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
- **Dashboards:** still none provisioned. The deferral above stands: only the datasource is
  automated, and panels are built by hand when needed.
