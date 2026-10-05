# ADR 0003: Choosing Postgres for review-service over Mongo

**Status:** Accepted
**Date:** 2026-09-12

## Context

Every service built so far (catalog-service, search-service) is MongoDB. review-service owns
reviews/ratings, and unlike movie documents this data is genuinely relational: a fixed set of
columns per row, a natural uniqueness constraint (one review per user per movie), and — per
ADR-0004 — a requirement for row-level locking semantics (`SELECT ... FOR UPDATE SKIP LOCKED`)
to make the outbox poller safe under concurrent replicas. Mongo has no equivalent to
`SKIP LOCKED`; emulating it (e.g. via `findOneAndUpdate` claim documents) is possible but is
solving a problem Postgres already solves natively.

## Decision

review-service uses Postgres, in its own dedicated database (`mflix_reviews`, per the
"no shared database between services" principle from ADR-0001/0002 — applied here to SQL
instead of Mongo). Schema is Flyway-owned (`db/migration/V1__create_reviews_and_outbox.sql`);
Hibernate's `ddl-auto` is set to `validate`, never `update`, so the schema always has exactly
one owner.

## Alternatives considered

- **Mongo with a unique compound index on `(user_id, movie_id)`** — would satisfy the
  uniqueness constraint fine, but the outbox pattern's "claim a batch of rows, hold the claim
  across a network call, release atomically" mechanism is Postgres's actual home turf
  (`FOR UPDATE SKIP LOCKED`), and the entire point of this service is to demonstrate that
  pattern cleanly rather than work around Mongo's lack of it.
- **A single shared Postgres database across review-service and the future user-service** —
  rejected for the same reason ADR-0001/0002 rejected a shared Mongo database between
  catalog-service and search-service: it would remove the service-ownership boundary this
  decomposition exists to practice, even though both services would use the same engine.

## Consequences

- review-service is the first polyglot-persistence service in this system — a concrete answer
  to "why isn't everything just Mongo" beyond the abstract argument.
- Flyway migration ownership is now an established pattern; user-service (also planned as
  Postgres, not yet built) should follow the same `db/migration/V{n}__description.sql`
  convention rather than inventing its own.
- No FK from `reviews.movie_id` to catalog-service's movies collection — different databases,
  different engines. A review referring to a since-deleted movie is accepted as valid data;
  catching that is catalog-service's/consumers' concern, not a constraint this schema enforces.

## Evidence

To be filled in once the service is running end-to-end: confirm the Flyway migration applies
cleanly against a fresh `mflix_reviews` database, and that the `uq_reviews_user_movie` unique
constraint actually produces a 409 (via `GlobalExceptionHandler`'s
`DataIntegrityViolationException` handler) under a concurrent double-POST, not just the
pre-check's sequential 409.

## Update: evidence captured

- **Flyway migration on a fresh database:** `V1__create_reviews_and_outbox.sql` applies cleanly
  to an empty Postgres in every integration-test run (Testcontainers, `postgres:16`), and the
  service starts against it with `ddl-auto: validate`, so the entity mappings match the migrated
  schema.
- **Concurrent double-POST:** 20 simultaneous `POST /api/reviews` for the same
  `(userId, movieId)` against the compose stack returned one 201 and nineteen 409s, with no 500s.
  Ten of the 409s came from the sequential pre-check. The other nine got past it and were stopped
  by `uq_reviews_user_movie`, which `GlobalExceptionHandler` maps to 409. Afterwards there was
  exactly one `reviews` row and one `outbox_events` row for the pair, so the losing requests left
  no orphaned events behind.
