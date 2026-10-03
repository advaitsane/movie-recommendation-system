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
