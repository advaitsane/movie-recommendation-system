# ADR 0006: user-service issues JWTs without a gateway to enforce them yet; publishes activity events directly, not via outbox

**Status:** Accepted
**Date:** 2026-09-14

## Context

user-service is the last domain service in the build order (step 5, alongside api-gateway) and
the first one whose entire job is identity: registration, login, and issuing the JWT "other
services trust" per the README's service-ownership table. Two design questions had to be settled
before writing any code, both because this service's correctness requirements are visibly
different from the services already built:

1. **How much of "auth" to build now.** A JWT is only as useful as the enforcement around it —
   validating it on incoming requests, rejecting missing/expired/tampered tokens — and that
   enforcement naturally belongs at api-gateway, which doesn't exist yet. Building request-level
   enforcement inside user-service itself, ahead of the gateway, risks building it twice or
   building it in the wrong place.
2. **Whether `user.activity` events need review-service's transactional-outbox guarantee.**
   review-service adopted an outbox (ADR-0004) because a review write and its Kafka event must be
   atomic — a review that exists in Postgres but was never announced is a real correctness bug
   (recommendation-service's collaborative signal would silently miss it). Activity events
   (VIEW/SEARCH) have no equivalent local row they must stay consistent with; the question is
   whether that difference actually justifies a simpler mechanism or whether it's a rationalization
   for cutting a corner.

## Decision

**JWT issuance only, no enforcement yet.** `JwtService` issues and can parse/validate HS256-signed
tokens (subject = user id, `email` custom claim, configurable expiration), and `POST
/api/users/login` returns one. Deliberately **not** `spring-boot-starter-security`: that starter's
auto-configured filter chain would require every endpoint — including registration and login
themselves — to be explicitly permitted, for zero benefit today, since nothing yet validates the
token on an incoming request anywhere in this system. `spring-security-crypto` alone (BCrypt) is
pulled in for password hashing, without the filter-chain machinery. Actual request-level
enforcement is deferred to api-gateway's build-out, which is the one place a single
filter can protect every downstream service at once rather than duplicating auth logic per service.

**Direct Kafka publish, catalog-service's pattern, not review-service's outbox.**
`UserActivityEventPublisher` publishes straight to `user.activity` after registration/lookup calls
succeed — no outbox table, no local row it needs to stay atomic with. This mirrors
`MovieEventPublisher` (catalog-service): fire-and-forget, at-least-once, a publish failure is
logged and swallowed rather than failing the request, keyed by `userId` for per-user ordering. This
is a real difference from review-service, not an oversight — see Alternatives.

**Login returns the same error for both failure modes.** An unknown email and a correct-email-
wrong-password both raise `InvalidCredentialsException` with the identical message ("Invalid email
or password"). Distinguishing them in the response would let a caller enumerate which emails are
registered one login attempt at a time.

## Alternatives considered

- **Full `spring-boot-starter-security` with a JWT filter now** — rejected as premature: the only
  caller of these endpoints today is a human or a test hitting the service directly, and building
  the enforcement filter before api-gateway exists means either duplicating it later at the
  gateway or wiring every downstream service to validate tokens individually — the opposite of
  the centralized-gateway architecture this repo's README lays out.
- **Give `user.activity` an outbox like review-service** — rejected: the outbox pattern earns its
  complexity by guaranteeing a *local write* and its event never diverge. There is no local write
  here to diverge from — an activity event is the entire side effect of the request, not a
  byproduct of one. Adding an outbox table and poller for a signal that's allowed to be lossy
  (an occasional dropped VIEW event doesn't corrupt any stored state, just under-informs a future
  recommendation signal) would be solving a problem this data doesn't have, at the cost of an
  extra table, a poller, and the operational overhead of monitoring its lag — the same reasoning
  ADR-0001 used to reject over-engineering search-service's read model.
- **Reveal which failure mode caused a login rejection** (distinct "no such user" vs. "wrong
  password" responses) — rejected as a user-enumeration vulnerability for a marginal UX gain;
  same message, same status code, for both.
- **A `/api/users/{id}/activity` endpoint keyed by session/token instead of a path `{id}`** —
  the "correct" long-term shape once a gateway forwards an authenticated identity, but the gateway
  doesn't exist yet to supply that identity trustworthily. Accepting a plain path parameter today
  is a documented, deliberate stopgap, not a security design — nothing currently authenticates
  who is allowed to record activity for which user id.

## Consequences

- user-service can be built, tested, and deployed independently of api-gateway, exactly like the
  other four services — nothing here blocks on infrastructure that doesn't exist yet.
- `POST /api/users/{id}/activity` currently trusts its caller's claimed user id outright — a real,
  temporary security gap, acceptable only because nothing downstream currently consumes
  `user.activity` for anything security-sensitive (no consumer for this topic exists yet anywhere
  in the codebase; it's forward-looking data for recommendation-service).
- Follow-up work: (1) ~~once api-gateway exists, add a filter there that validates the JWT and
  forwards a trusted identity header/claim downstream, removing the need for callers to supply
  `{id}` on the activity endpoint themselves~~ — **done**: api-gateway now enforces JWTs
  centrally (see ADR-0007) and forwards `X-User-Id`; user-service now checks it against the path
  `{id}` on the activity endpoint (403 on mismatch) — see [ADR-0011 Update
  5](0011-chaos-testing.md). The header stays optional, so a caller that bypasses the gateway
  entirely is still trusted unchecked, a residual gap, not a fully closed one; (2) revisit whether
  `user.activity` needs a consumer before this event stream's value can be assessed at all — right
  now it's published into a topic nothing reads.

## Evidence

21 unit tests (`UserServiceImplTest`, `UserControllerTest`, `JwtServiceTest`) cover registration
(duplicate-email 409, BCrypt hash never equals the raw password), login (correct credentials issue
a parseable token; unknown-email and wrong-password both raise the identical exception/message),
JWT round-tripping (issued claims match, a token signed with a different secret is rejected, an
expired token is rejected), and activity recording's type-dependent required-field validation.
`UserServiceIntegrationTest` (Testcontainers Postgres, 3 tests, all passing) verifies the full
Spring context against a real database: register-then-login round-trips through the real REST API
with a real Flyway-migrated schema and a real BCrypt/JWT path, duplicate registration returns 409,
and a wrong password against a real stored hash returns 401. The bean-scoped
`DynamicPropertyRegistrar` pattern (copied from review-service, itself already proven reliable)
worked correctly on the first run — 24/24 tests passing overall, no flakiness observed.

## Update: test suite since this ADR

The suite has grown to 38 tests: 34 unit tests and 4 in `UserServiceIntegrationTest`. The
additions cover config validation (`ConfigurationPropertiesValidationTest`, `JwtPropertiesTest`),
the `X-User-Id` ownership check on the activity endpoint (403 on mismatch), and an unknown JSON
field being rejected with 400 rather than 500.
