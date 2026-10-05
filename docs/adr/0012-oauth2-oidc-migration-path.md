# ADR 0012: Migration path from self-issued HS256 JWTs to an external OAuth2/OIDC provider

**Status:** Proposed (deferred until a trigger below is hit)
**Date:** 2026-10-03

## Context

Authentication today is deliberately minimal (ADR-0006, ADR-0007):

- **user-service is its own token issuer.** `POST /api/users/login` checks a BCrypt password hash
  in its own Postgres table, then `JwtService` signs an HS256 JWT (`sub` = user id, `email`
  claim, 1h expiry).
- **api-gateway validates every token** on non-public routes with the *same* symmetric key
  (`JwtValidator`), then forwards `X-User-Id`/`X-User-Email` downstream. No downstream service
  sees a token or the key.
- **The key is a shared secret** (`JWT_SECRET`). There is no committed default: docker-compose
  defines one dev value for both services, and anywhere else it must be supplied explicitly or
  startup fails (`JwtProperties`/`GatewayJwtProperties`).

That is the right amount of auth for a single-instance local deployment with no real users, but
it has limits that grow with the system rather than staying fixed:

1. **Symmetric key = anyone who can verify can also mint.** The gateway holds the signing key
   only to validate tokens, yet a leak from the gateway (logs, heap dump, env dump) is enough to
   forge a token for any user. Every additional component that needs to validate tokens widens
   that exposure, and the secret has to be distributed to each of them.
2. **No key rotation.** Tokens carry no `kid`, so changing `JWT_SECRET` invalidates every live
   token at once and requires both services to be redeployed in lockstep.
3. **No revocation or refresh.** A stolen token is valid until it expires; users re-login every
   hour; there is no logout beyond the client discarding the token.
4. **user-service owns credentials.** Password storage, lockout, reset flows, MFA, social login
   and SSO would all have to be built and secured here. None of them is the domain this service
   exists for (profiles and activity).
5. **No standard client flows.** A browser or mobile client has only "POST your password to
   user-service"; there is no Authorization Code + PKCE, no consent, no client registration.

None of these is a problem *yet*. This ADR records the target shape and the migration path so
the move is a planned step, not a rewrite under pressure.

## Decision

**Keep the current HS256 design for now.** Move to an external OAuth2/OIDC provider when any of
these triggers is hit:

- a real frontend or third-party client needs to log users in (needs Authorization Code + PKCE);
- MFA, social login or SSO is required;
- a second component beyond api-gateway needs to validate tokens;
- the system is deployed to a shared environment where the secret would live in more than a
  developer's compose file, or key rotation without downtime is required.

**Target architecture once triggered:**

- **Identity provider:** Keycloak for local development (one container in compose, realm
  imported from a committed JSON file with dev-only users), and a managed provider (Amazon
  Cognito, Auth0, Okta, or Keycloak itself) in any deployed environment. The services depend only
  on standard OIDC, so the provider is swappable by changing `issuer-uri`.
- **api-gateway becomes an OAuth2 resource server.** Replace `JwtValidator` and the shared secret
  with `spring-boot-starter-oauth2-resource-server` (WebFlux):
  `spring.security.oauth2.resourceserver.jwt.issuer-uri` points to the provider. The gateway
  fetches and caches the provider's public keys from its JWKS endpoint and validates RS256
  signatures, `iss`, `aud` (this API's audience), and `exp`. It never holds a signing key.
- **Identity forwarding keeps the same downstream contract.** The gateway still maps the
  validated `sub`/`email` claims to `X-User-Id`/`X-User-Email`, so no domain service changes. It
  also **strips any client-supplied `X-User-*` headers on every route, public ones included**, so
  the headers can only ever come from a validated token.
- **user-service stops being an auth server.** `/login` and password storage are removed; it keeps
  profiles and activity, keyed by the provider's `sub`. A Flyway migration adds an
  `external_subject` column; existing users are linked on first login by verified email.
  Registration moves to the provider (self-service sign-up or admin-created users), and
  user-service creates the profile row on first authenticated request.
- **Service-to-service calls** (if any ever need user-independent auth) use the Client
  Credentials grant against the same provider, not a second shared secret.

**Migration in four steps, each its own PR, none requiring a big-bang cutover:**

1. Add Keycloak to compose with a realm import; document the dev login flow.
2. Gateway accepts **both** issuers during the transition: Spring Security's
   `JwtIssuerReactiveAuthenticationManagerResolver` routes RS256 tokens from Keycloak to the
   JWKS validator and legacy HS256 tokens (no or local `iss`) to the existing validator. Add an
   `iss` claim to user-service tokens first so the two are distinguishable.
3. Clients switch to Authorization Code + PKCE; user-service links existing accounts by `sub`.
   Its `/login` endpoint is deprecated (logs a warning, returns a `Deprecation` header).
4. Remove the legacy path: `JwtService`, `JwtValidator`, `/login`, the password column, and
   `JWT_SECRET` from every config file and from compose.

## Alternatives considered

- **Keep HS256 indefinitely.** Fine while the triggers above are absent, which is why it is the
  current choice. It does not scale past one validator without spreading the signing key, and it
  leaves credential management in a service that shouldn't own it.
- **Keep user-service as issuer, but switch to RS256 and publish a JWKS endpoint.** The gateway
  would then hold only a public key, which removes the shared secret (point 1) and enables `kid`
  based rotation (point 2) for roughly a day of work. A reasonable intermediate step if the
  "second validator" trigger arrives before the others. Rejected as the end state because it
  still leaves points 3–5: this repo would own refresh tokens, revocation, MFA and standard
  client flows.
- **Embed Spring Authorization Server in user-service.** A real, standards-compliant OIDC
  provider, but the repo would then own and operate the most security-sensitive component in the
  system, including its patching, key management and flow-level vulnerabilities. A dedicated
  provider does this better and is replaceable.
- **Opaque tokens with introspection.** Gives instant revocation, but adds a network call to the
  provider on every gateway request (or a cache that reintroduces staleness). Self-contained JWTs
  with short expiry plus refresh tokens are the better default here; introspection can be added
  for specific high-risk routes later.
- **Validate tokens in every service instead of the gateway.** Defense in depth, but it
  contradicts ADR-0007's single enforcement point and multiplies configuration. Reconsider only
  if services become reachable without going through the gateway (see Consequences).

## Consequences

- **Better:** no shared signing secret anywhere in this repo or its deployments; key rotation is
  the provider's job and transparent to the gateway (new `kid` in JWKS); refresh tokens, logout,
  MFA, SSO and social login come from the provider instead of custom code; user-service shrinks
  to its actual domain.
- **Tradeoff: a new runtime dependency on the identity provider.** Login is unavailable if the
  provider is down. Validation of already-issued tokens keeps working because the gateway caches
  the JWKS, until the provider rotates keys. This should be verified with a chaos experiment in
  the style of ADR-0011 (stop Keycloak, confirm authenticated requests still succeed and new
  logins fail cleanly).
- **Tradeoff: heavier local development.** One more container (Keycloak, ~500 MB RAM) and a
  browser-based login flow instead of a single `curl` to `/login`. Mitigated by a committed realm
  with dev users and a documented `curl` using the Resource Owner Password grant **for local dev
  only** (that grant is deprecated by OAuth 2.1 and must stay disabled outside the dev realm).
- **Tradeoff: user data is split** between the provider (credentials, email verification) and
  user-service (profile, activity), joined by `sub`. Account deletion has to touch both.
- **Testing changes:** gateway tests use `spring-security-test`'s `mockJwt()` for unit tests and
  a Testcontainers Keycloak for one end-to-end login test, replacing the current tests that sign
  tokens with a known test secret.
- **Independent of this migration:** the gateway used to overwrite `X-User-Id`/`X-User-Email`
  only on authenticated routes and passed client-supplied values through on public ones. Found
  while writing this ADR and **fixed** in the same change: `JwtAuthenticationGlobalFilter` now
  strips inbound `X-User-*` headers on every route (covered by
  `JwtAuthenticationGlobalFilterTest`). Still open: a caller that bypasses the gateway entirely
  is trusted unchecked (the residual gap ADR-0006 already notes). Network policy that lets only
  the gateway reach domain services closes that once the system runs on Kubernetes.

## Evidence

None yet: this ADR is Proposed and describes a deferred change. When it is implemented, record
here:

- gateway p50/p99 latency with cached-JWKS RS256 validation vs. the current HS256 path, using the
  ADR-0010 load-test script;
- behavior with the identity provider stopped (expected: existing tokens accepted, logins fail
  with a clean 503 rather than a timeout);
- confirmation that `JWT_SECRET` no longer appears in any config file, compose file or
  deployment manifest.
