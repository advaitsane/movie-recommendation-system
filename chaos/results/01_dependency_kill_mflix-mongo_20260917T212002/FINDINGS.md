# Experiment 1: kill mflix-mongo mid-traffic (30s downtime)

**Real app-level failures occurred**, confirmed via service logs (not just k6 metrics):
- catalog-service: `MongoTimeoutException`-driven 500s, e.g. `GET /api/movies/573a13b8f29313caabd4bd33 500 - 14647ms`
  (~14.6s before giving up — matches Mongo driver's default server-selection timeout).
- search-service: cascading `ClientAbortException: Broken pipe` — k6's client timed out and disconnected
  before search-service finished a slow in-flight response once Mongo came back.
- k6-observed http_req_failed rate across the 6 smoke runs spanning the outage: 37.5%-87.5%.

**Key finding — health-check blind spot**: `/actuator/health` on both catalog-service and search-service
returned 200 for the ENTIRE outage (confirmed via 2s-interval polling, zero unhealthy readings). An
orchestrator's liveness/readiness probe would not have detected this and would keep routing traffic to a
service that's actively failing ~40-90% of requests. Consistent with catalog-service/search-service using a custom MongoConfig that
bypasses Spring Boot's Mongo autoconfiguration — likely why no Mongo health indicator is registered.
(Fixed afterwards; see ADR-0011 Updates 1-2.)

**Recovery**: self-healing, no crash loop, no manual intervention needed. catalog-service latency was back
to normal (~50-300ms) within ~20-60s of mflix-mongo becoming healthy again.

**How to apply**: register an explicit Mongo health indicator (or a custom one) for catalog-service and
search-service so `/actuator/health` actually reflects Mongo connectivity — otherwise Kubernetes-style
liveness/readiness probes provide no protection against this failure mode.
