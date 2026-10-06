# Service hardening checklist

A menu to run against a service once its core functionality is built, before moving to the next
one. Not every item applies to every service — pick the ones that match the service's actual
failure modes and skip the rest. Forcing a step that doesn't fit (e.g. a circuit breaker on a
service with no downstream dependency) is worse than skipping it.

## 1. Dead-code / drift audit

Read through the service looking for anything that no longer matches reality: comments describing
a previous architecture (monolith-era references, removed endpoints), stale HTML/markdown
artifacts, TODOs that were already done, config or dependencies nothing uses. Cheap to do, and it
stops small lies from accumulating in code a reader will trust.

## 2. Identify the failure mode that actually matters

Ask: what does this service depend on that can fail, and what happens to callers when it does?
Not every service has the same answer:

- A database-backed service (Mongo/Postgres) — the DB going away or getting slow.
- A service that calls another service — that service going away or getting slow.
- A gateway/router — a downstream route failing shouldn't take the gateway down with it.
- A pure infrastructure service (discovery, config) — may have no analogous failure mode worth
  building resilience machinery for; don't invent one.
- Anyone getting overloaded by *too many* calls, not a downstream failure — this is rate limiting,
  not circuit breaking, and belongs mainly at api-gateway (see below), not scattered per service.

## 3. Harden against that failure mode, with the failure it enables named up front

Typical shape: wrap the risky calls (resilience4j circuit breaker, timeout, bulkhead — whichever
fits), map the resulting exception to a real HTTP status, and make sure business-logic exceptions
(validation, not-found) are excluded so they don't trip protection meant for infrastructure
failures.

**Rate limiting is the exception to "harden per service":** it mainly belongs at api-gateway, not
scattered across every service.

- **api-gateway** — the single entry point for external traffic, so it's the natural place for
  coarse-grained, per-client/API-key/IP limits. One implementation, one config surface, protects
  the whole fleet from a single chatty client.
- **A specific service** — only when it has a genuinely expensive or fragile operation that needs
  protecting even from *internal* callers that bypass the gateway (service-to-service calls), or
  when different endpoints on the same service have very different costs and shouldn't share one
  bucket (e.g. a plain `GET /{id}` vs. a heavy search/aggregation query). Use a narrow
  resilience4j `RateLimiter` for that one endpoint rather than a blanket per-service limit.
- Don't add per-service rate limiting by default — it duplicates the gateway's job and adds config
  surface for no benefit unless one of the two cases above actually applies.

## 4. Verify the failure mode for real, not just in unit tests

Unit/integration tests prove the code compiles and the happy path works. They don't prove the
resilience mechanism actually engages under the real failure — mocks don't time out the way a real
dependency does. Actually trigger the failure (`docker stop` the dependency, inject latency, kill
`-9` the process) and watch:

- Does `/actuator/health` (or equivalent) correctly reflect the outage?
- Does the protection mechanism (circuit breaker, timeout) actually engage, and with the latency/
  behavior you'd expect — not just "eventually returns an error"?
- Does everything recover cleanly once the dependency comes back?

This is the step most likely to surface a real gap (it did for catalog-service — see below).

## 5. Re-question existing config choices once requirements are known

Config decisions made early (e.g. bypassing framework autoconfiguration for fine-grained control)
are often justified by requirements that later narrow or disappear. Once the service's real shape
is known, ask whether the original justification still holds — and if you change something on this
axis, re-run step 4 rather than trusting the build passing alone, since config changes here are
exactly the kind of thing that silently regresses a resilience behavior.

## 6. README

Same section shape across services so a reader (or you, six months later) knows where to look:

- **Overview** — what the service owns, what it exposes, what it explicitly does *not* do (and
  which service does instead, if relevant).
- **How to run** — local (Maven/Gradle), Docker/compose, tests, with an env var table.
- **API** — link to Swagger/OpenAPI and a Postman collection if one exists.
- **Resilience & observability** — what failure modes are handled and how, with a note that it was
  verified against a real failure (step 4), not just unit-tested.
- **Architecture decisions** — links into `docs/adr/`, not re-explained inline.
- **Known quirks** — anything a maintainer would otherwise rediscover the hard way (data quirks,
  non-obvious property prefixes, etc.).

## 7. ADR, if the decision is non-obvious or reverses an earlier one

Not every change needs an ADR. Write one when a future reader would reasonably ask "why is it
built this way" and the answer isn't visible in the code — especially when a decision reverses an
earlier documented one (update or supersede that ADR rather than leaving it stale).

---

## Worked example: catalog-service

1. **Audit** — removed monolith-era comments/references and the aggregation/reporting endpoints
   ADR-0001 had already retired but the code and README hadn't caught up to.
2. **Failure mode** — Mongo unavailable or slow; every request would otherwise hang on the
   driver's server-selection timeout.
3. **Hardening** — Resilience4j circuit breaker around all Mongo-touching calls
   (`MongoCircuitBreakerConfig`), `CallNotPermittedException` → 503, validation/not-found
   exceptions excluded from tripping it.
4. **Verification** — `docker stop`'d the real Mongo container. Found `/actuator/health` stayed
   `200 UP` during the outage (the gap — see [ADR-0011](adr/0011-chaos-testing.md)), fixed it, then
   confirmed the breaker itself warms up, opens, fails fast with 503, and recovers
   (`OPEN → HALF_OPEN → CLOSED`) once Mongo returned.
5. **Re-questioned config** — `MongoConfig` originally bypassed Boot's Mongo autoconfiguration for
   fine-grained control needed by aggregation queries. Those queries were gone (step 1 removed
   them), so autoconfig + a `MongoClientSettingsBuilderCustomizer` gave equivalent control with
   less hand-built code — and picked up Boot's own Mongo health indicator automatically, removing
   the exact kind of manual wiring that caused the step-4 gap in the first place. Re-ran step 4
   after making the change to confirm no regression.
6. **README** — rewritten to the shape above.
7. **ADRs** — [0011](adr/0011-chaos-testing.md) (chaos testing) already covered the non-obvious
   decisions; no new ADR was needed for the autoconfig switch since the rationale is fully
   captured in the README's architecture-decisions section and doesn't reverse a documented
   decision.
