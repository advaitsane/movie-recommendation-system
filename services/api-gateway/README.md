# api-gateway (:8080)

Spring Cloud Gateway (WebFlux/reactive). Single front door for `/api/**`: routes to
catalog/search/review/recommendation/user-service, validates the JWTs user-service issues,
logs every request, rate-limits, and returns a clean 503 fallback instead of a raw connection
error when a downstream service is unreachable. See ADR-0007 for the design decisions and two
deliberately deferred pieces (Eureka-based discovery, config-server externalization).

## Routes
- `/api/movies/search/**` -> search-service (listed before the catalog route below since Spring
  Cloud Gateway matches routes in order — this is a subpath of `/api/movies/**`)
- `/api/movies/**` -> catalog-service
- `/api/reviews/**` -> review-service
- `/api/recommendations/**` -> recommendation-service
- `/api/users/**` -> user-service

## Cross-cutting filters
- **Request logging** — method/path/status/duration for every request.
- **Rate limiting** — a single shared Resilience4j `RateLimiter` (in-memory, 50 req/s by
  default) across the whole gateway; 429 once exhausted.
- **JWT validation** — `/api/users/register` and `/api/users/login` are public; `/api/movies/**`
  (browsing) is public; everything else requires `Authorization: Bearer <token>` signed with the
  same secret user-service uses (`JWT_SECRET`: required, no committed default; compose supplies a
  dev value, and `./mvnw spring-boot:run` needs it exported or set in a gitignored
  `application-local.yml`, otherwise startup fails). A valid token forwards `X-User-Id`/`X-User-Email` headers
  downstream. Invalid/missing tokens on a protected route get a 401 before the request is
  proxied anywhere.
- **Circuit breaker + fallback** — every route wraps the proxy call in a Resilience4j circuit
  breaker; an unreachable/erroring downstream returns a 503 JSON body
  (`GET/POST/... /fallback/{service}`) instead of a hung request or a raw connection error.
  Verified live with a real `docker kill` on catalog-service — see
  [ADR-0011 Update 4](../../docs/adr/0011-chaos-testing.md), which also caught and fixed a real
  gap: the breaker's `minimum-number-of-calls` wasn't set, so it could never open.

## Known limitations (see ADR-0007)
- Routing uses configured base URLs (env vars), not Eureka service discovery — no service in
  this repo registers with Eureka yet.
- Route definitions live in this service's own `application.yml`, not config-server.
- Rate limiting is a single global bucket, not per-route or per-client, and is in-memory (not
  Redis-backed) — fine for one gateway instance, would need revisiting for multiple.
- Downstream services trust the forwarded `X-User-Id`/`X-User-Email` headers. user-service's
  `POST /api/users/{id}/activity` returns 403 when `X-User-Id` doesn't match the path `{id}`,
  but the header is optional, so a caller that reaches a service directly, bypassing this
  gateway, is not checked (see ADR-0006 and ADR-0012).
