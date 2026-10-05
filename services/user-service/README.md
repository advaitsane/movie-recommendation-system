# user-service (:8085)

**Owns:** user identity, auth, watch history (Postgres). Does not enforce JWTs on incoming
requests itself — that happens once, centrally, at api-gateway (see ADR-0007); this service only
issues them and trusts the identity api-gateway forwards.

Registration/login with BCrypt-hashed passwords, JWT issuance on login, and direct (non-outbox)
Kafka publishing of `user.activity` events. See ADR-0006 for why this service doesn't pull in
`spring-boot-starter-security`'s filter chain (enforcement belongs at api-gateway, not
duplicated per service) and why activity events skip review-service's outbox pattern.

## How to run
- **Local:** `./mvnw spring-boot:run` (needs a local Postgres at `mflix_users` and Kafka
  reachable — see the env var table below).
- **Docker/compose:** `docker compose up -d postgres kafka user-service` from the repo root.
- **Tests:** `./mvnw test` — unit tests plus a Testcontainers-backed integration test
  (`UserServiceIntegrationTest`) against a real, ephemeral Postgres.
- **Env vars:** `POSTGRES_URL`/`POSTGRES_USER`/`POSTGRES_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`,
  `JWT_SECRET` (must match api-gateway's exactly), `JWT_EXPIRATION` (a duration, default `1h`),
  `CORS_ORIGINS`. All config is bound to `@Validated` `@ConfigurationProperties` records, so a
  missing or invalid value fails startup instead of the first request that uses it.
- **JWT secret is required, no committed default.** docker-compose supplies a dev value. For
  `./mvnw spring-boot:run`, export `JWT_SECRET` (32+ bytes, same value as api-gateway) or set
  `jwt.secret` in a gitignored `application-local.yml` with `SPRING_PROFILES_ACTIVE=local`.
  Startup fails with a message naming the fix if it's missing or too short.

## API
Swagger UI at `/swagger-ui.html`, OpenAPI JSON at `/api-docs`. A Postman collection is at
[`postman/user-service.postman_collection.json`](postman/user-service.postman_collection.json) —
import it, no environment needed (`baseUrl`/`gatewayUrl` collection variables default to
`localhost:8085`/`:8080`). One folder hits user-service directly (register/login/get/activity plus
their error cases); the other goes through api-gateway to exercise JWT enforcement and the
`X-User-Id` activity-ownership check with a real second user, ending in the 403 case. Verified with
`newman run` against the live local stack — all 17 requests/21 assertions pass.

## Endpoints
- `POST /api/users/register` — create an account (409 if the email is already registered)
- `POST /api/users/login` — exchange credentials for a JWT (401 for either an unknown email
  or a wrong password — same response for both, to avoid email enumeration)
- `GET /api/users/{id}` — fetch a user's profile
- `POST /api/users/{id}/activity` — record a `VIEW`/`SEARCH` activity, published to
  `user.activity` on Kafka (fire-and-forget; nothing consumes this topic yet). 403s if the
  caller's authenticated identity (api-gateway's forwarded `X-User-Id`) doesn't match the path
  `{id}`.

## Resilience & observability
- **Postgres outage** — every `UserRepository` call runs through `postgresCircuitBreaker`
  (`PostgresCircuitBreakerConfig`): HikariCP's `connection-timeout` is tuned to 3s (down from the
  10x-slower 30s library default, which also made `/actuator/health` itself take 30s to report
  DOWN, since Boot's health indicator borrows from the same pool), and once enough calls fail the
  breaker opens and fails fast with a clean 503 instead of leaking a raw Hikari exception message.
  Business exceptions (validation, not-found, duplicate-email, wrong-password) are excluded so
  they don't trip it. `register()` is deliberately **not** `@Transactional`: a breaker wrapped
  inside an `@Transactional` method never sees a connection failure (the transactional proxy
  opens its connection before the method body runs), and the single `save()` call here doesn't
  need a transaction anyway — the real guard against the duplicate-email race is the DB's unique
  constraint, not a transactional boundary. Verified live with a real `docker stop` on the
  `postgres` container — see [ADR-0011 Update 5](../../docs/adr/0011-chaos-testing.md) (original
  30s-hang gap) and [Update 7](../../docs/adr/0011-chaos-testing.md) (the `@Transactional`/
  circuit-breaker ordering gap, caught first in review-service then confirmed here too).
- **Kafka outage** — `user.activity` publishing is fire-and-forget with a bounded
  `max.block.ms` (5s); a broker outage is logged and swallowed, never fails the triggering
  request (see `UserActivityEventPublisher`).
- **Activity endpoint authorization** — `X-User-Id`, forwarded only after api-gateway's own JWT
  validation, is now checked against the path `{id}` (see ADR-0011 Update 5) — closing half of
  ADR-0006's documented follow-up gap. The header stays optional so a caller that bypasses the
  gateway entirely still works, unenforced; ADR-0006 already documents that residual gap.

## Architecture decisions
- [ADR-0006](../../docs/adr/0006-user-service-auth-and-activity-events.md) — JWT issuance without
  request-level enforcement, and why activity events skip the outbox pattern.
- [ADR-0007](../../docs/adr/0007-api-gateway-routing-and-deferred-discovery.md) — where and how
  the JWTs this service issues are actually enforced and forwarded.
- [ADR-0011](../../docs/adr/0011-chaos-testing.md) — Update 5 covers this service's Postgres
  resilience hardening and the activity-endpoint authorization fix.

## Known limitations (see ADR-0006)
- `POST /api/users/{id}/activity` still trusts a caller's claimed `{id}` outright if that caller
  bypasses api-gateway entirely (no `X-User-Id` header present) — accepted since nothing currently
  calls this service directly other than tests.
