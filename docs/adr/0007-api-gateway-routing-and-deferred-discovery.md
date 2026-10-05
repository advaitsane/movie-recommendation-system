# ADR 0007: api-gateway routes via configured URLs, not Eureka/config-server; enforces JWTs; single in-memory rate limiter

**Status:** Accepted
**Date:** 2026-09-14

## Context

api-gateway is README build-order step 5 ("layer in resilience... at the gateway") and the
first service whose job is purely cross-cutting rather than owning a domain. Its own stub
README listed four things to build first: routes resolved via Eureka and pulled from
config-server, global filters (rate limiting, request logging, JWT validation), and circuit
breaker fallbacks. Two of those four assume infrastructure — Eureka and config-server — that
doesn't exist yet: both remain README-only stubs, and **no domain service in this repo
currently registers with Eureka or reads from config-server**, even though `docker-compose.yml`
already wires `depends_on: [eureka-server]` into every domain service (a leftover from the
original scaffold, not evidence any service actually uses it). Every existing inter-service
call in this codebase (recommendation-service -> search-service, and now the gateway -> all
five domain services) already uses a plain configured base URL (`SEARCH_SERVICE_URL`-style env
var), resolved directly, not through service discovery.

This meant a real scope decision up front: build Eureka-based discovery routing now (which
would require retrofitting all five existing, already-shipped, already-tested domain services to
register as Eureka clients, just so the gateway's routing config could look them up dynamically),
or route by configured URL like every other inter-service call in this repo already does, and
defer discovery until it's actually needed (multiple instances of a service, or dynamic
scale-out — neither applies to a local `docker-compose` deployment with one instance of
everything).

A second, unrelated problem surfaced only at runtime, not at compile/test time: Spring Cloud
2025.1.0's `CompatibilityVerifierAutoConfiguration` hardcodes "Spring Boot 4.0.x" as the only
compatible line and refuses to start against this repo's pinned Boot 4.1.1 — confirmed by
running the actual jar, not guessed, since `mvn compile`/`test` had already passed cleanly and
only `java -jar` surfaced it.

## Decision

**Configured URLs, not Eureka discovery; routes in this service's own `application.yml`, not
config-server.** Same pattern as every existing inter-service call — `CATALOG_SERVICE_URL`,
`SEARCH_SERVICE_URL`, etc., wired via `docker-compose.yml` environment blocks, defaulting to
`localhost:<port>` for running a service standalone via `mvnw`. `eureka-server` and
`config-server` are left as README stubs; api-gateway's compose block no longer lists them in
`depends_on` since it doesn't actually call either.

**Spring Cloud Gateway (WebFlux) with YAML-declared routes**, `/api/movies/search/**` listed
before the broader `/api/movies/**` catch-all (route order is match-order in this library — the
more specific path must come first or it's unreachable).

**JWT enforcement now lives here, closing the gap ADR-0006 (user-service) explicitly deferred.**
`/api/users/register`, `/api/users/login`, and all of `/api/movies/**` (catalog/search browsing)
are public; everything else requires a valid `Authorization: Bearer` token signed with the same
secret user-service issues with (`JWT_SECRET`, defined once in `docker-compose.yml` and shared by
both services; neither service has a committed default, so outside compose it must be set
explicitly or startup fails). A valid token's claims are forwarded downstream as `X-User-Id`/`X-User-Email` headers.
This is the single enforcement point the architecture always intended — no downstream service
validates a token itself.

**Circuit breaker + fallback per route, Resilience4j** (matches build-order step 5's explicit
mention of Resilience4j, not a generic "some circuit breaker"). Each route's `CircuitBreaker`
filter forwards to `/fallback/{service}` on failure, which returns a clear 503 JSON body instead
of a raw connection error or a hung request — verified live against a real unreachable
downstream (see Evidence).

**A single shared, in-memory Resilience4j `RateLimiter` (50 req/s default) across the whole
gateway — not per-route, not per-client, not Redis-backed**, despite the README's original
"Redis-backed" suggestion.

**`spring.cloud.compatibility-verifier.enabled=false`** to work around the hardcoded Boot-4.0.x
check — the same "trust the actual toolchain over a library's own version-guard list" call
already made for other Spring Boot 4.1.1 quirks in this repo (e.g. review-service's Flyway and
Testcontainers module splits, documented in its `pom.xml`).

## Alternatives considered

- **Wire Eureka discovery now, since the architecture diagram lists it as cross-cutting infra**
  — rejected as scope creep well beyond "build api-gateway": it would mean modifying all five
  already-shipped domain services to register as Eureka clients, for a benefit (multi-instance
  load balancing / dynamic host resolution) this single-instance local deployment doesn't need
  yet. Revisit if/when any service needs to run more than one instance.
- **Pull route definitions from config-server** — rejected for the same reason ADR-0006 rejected
  an outbox for activity events and ADR-0001 rejected over-engineering search-service's read
  model: config-server would just relocate this same YAML into a git-backed store, with no
  current consumer needing a dynamic reload — the complexity isn't earned yet.
- **Redis-backed distributed rate limiting** — the README's original suggestion, and Redis is
  already a dependency elsewhere (recommendation-service's cache), so it wasn't ruled out for
  lack of existing infra. Rejected anyway: distributed rate limiting only pays for itself across
  multiple gateway instances sharing one limit, and this deployment runs exactly one gateway
  instance. An in-memory Resilience4j limiter gives the same protection today at a fraction of
  the complexity; revisit alongside the Eureka decision if this ever needs more than one
  instance.
- **Have user-service's activity endpoint immediately switch to trusting the gateway's
  `X-User-Id` header instead of its path `{id}`** — the header is forwarded and ready, but
  changing user-service's controller is out of scope for "build api-gateway" on an already
  shipped, tested, documented service. Left open, same as ADR-0006 flagged. (Since closed as a
  partial check: user-service now rejects a mismatched `X-User-Id` with 403; see the update below.)

## Update (2026-09-14): eureka-server closed out as a deliberate stub, config-server built

`config-server` was built (server-only scope, no client migration — see `docs/adr/0009`) after
comparing it against `eureka-server`: config-server has value at single-instance scale
(centralizing config), Eureka does not (nothing here needs dynamic multi-instance discovery).

`eureka-server` was evaluated the same way and **closed out as intentionally staying a
README-only stub** — not deferred/pending, decided. The reasoning above (single-instance
deployment, configured-URL routing already working end-to-end across every inter-service call)
hasn't changed. Worth restating explicitly: step 8 of the README's build order already frames Eureka as
a stepping stone this repo would replace with Kubernetes-native Service DNS if it ever needed
real discovery — so building Eureka now would mean building something the roadmap's own endpoint
state makes redundant. Revisit only if this deployment ever needs more than one instance of a
service, per the Alternatives section above.

## Consequences

- api-gateway can be built, tested, and deployed independently of eureka-server/config-server —
  neither needs to exist for `docker compose up --no-deps api-gateway` (or the whole stack, once
  `depends_on` no longer names them) to work.
- If a service is ever scaled to multiple instances, the configured-URL routing here (and every
  other inter-service call in this repo) will need to become discovery-based all at once — this
  is a known, deliberate, single point of future rework, not an oversight discovered later.
- The gateway is now the only place enforcing identity. Any downstream service reachable by
  another path (e.g. hit directly on its own port during local dev, bypassing the gateway) is
  not protected by this filter — acceptable for local dev, would need revisiting before any real
  deployment where downstream ports are reachable directly.
- Rate limiting resets if the gateway restarts (in-memory) and does not coordinate across
  multiple instances — acceptable today; see Alternatives for when to revisit.

## Evidence

12 unit tests (`JwtValidatorTest`, `JwtAuthenticationGlobalFilterTest`, `RateLimitingGlobalFilterTest`,
`FallbackControllerTest`) cover token validation (round-trip, wrong secret, expired),
public-vs-protected path enforcement, identity-header forwarding, rate-limit exhaustion, and the
fallback response shape — all passing. Also manually verified live: built the jar, ran it
against a real stub HTTP server standing in for catalog-service plus three intentionally
unreachable ports for the other services, and confirmed: `GET /api/movies/genres` proxies
through (200, catalog); `GET /api/movies/search/vector` (a subpath) correctly routes to the
search fallback rather than catalog, confirming route-order precedence; `GET /api/reviews/1`
with no token returns 401 without ever reaching the proxy; the same request with a valid,
independently-minted HS256 token (signed with the same secret, verified outside the app) passes
the auth filter and reaches the circuit breaker, which correctly falls back to a 503 JSON body
once it hits the genuinely-unreachable downstream; and a garbage token is rejected with 401.

## Update: changes since this ADR

- **Tests:** the suite has grown from 12 to 20 unit tests. The additions cover config validation
  (`ConfigurationPropertiesValidationTest`, `GatewayJwtPropertiesTest`: a missing or short
  `JWT_SECRET` fails startup) and header stripping: client-supplied `X-User-Id`/`X-User-Email` are
  now removed on every route, public ones included, so those headers can only come from a
  validated token (found while writing [ADR-0012](0012-oauth2-oidc-migration-path.md)).
- **Circuit breakers:** chaos testing found that the per-route breakers could never open, because
  `minimum-number-of-calls` was left at the library default of 100 against a window of 10. Now set
  to 5; see [ADR-0011 Update 4](0011-chaos-testing.md).
- **`X-User-Id` downstream:** user-service now compares the forwarded header with the path `{id}`
  on the activity endpoint and returns 403 on a mismatch ([ADR-0011 Update 5](0011-chaos-testing.md)).
  The header is still optional, so a caller that bypasses the gateway is not checked.
