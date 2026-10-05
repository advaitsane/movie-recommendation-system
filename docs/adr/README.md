# Architecture decision records

One ADR per significant decision, written while the work happened rather than
reconstructed afterwards. Each one records the context that forced the
decision, the alternatives rejected, the tradeoff accepted, and the evidence
(test output, latency numbers, failure reproductions) behind it. New ADRs
start from [`0000-template.md`](0000-template.md).

Each ADR is added to this index in the same pull request as the work it describes.

| ADR | Decision |
|---|---|
| [0001](0001-split-catalog-search-from-monolith.md) | Split catalog-service and search-service out of the monolith |
| [0002](0002-search-index-cqrs-kafka-sync.md) | Search index as a synced materialized view (CQRS via Kafka) instead of a shared database |
| [0003](0003-postgres-for-review-service.md) | Postgres for review-service instead of Mongo |
| [0004](0004-outbox-pattern-review-service.md) | Outbox pattern for review-service → Kafka |
| [0005](0005-recommendation-service-own-database-and-blend.md) | recommendation-service owns its data; blends content and collaborative signals |
| [0006](0006-user-service-auth-and-activity-events.md) | user-service issues JWTs but leaves enforcement to the gateway; activity events skip the outbox |
