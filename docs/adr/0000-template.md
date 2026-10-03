# ADR 000X: <short decision title>

**Status:** Proposed | Accepted | Superseded by ADR-000Y
**Date:** YYYY-MM-DD

## Context
What problem forced this decision? Include the failure mode or requirement
that surfaced it (e.g. "recommendation-service p99 spiked to 2s under load,
starving the gateway's thread pool").

## Decision
What you did, stated plainly in one or two sentences.

## Alternatives considered
- Option A — why not
- Option B — why not

## Consequences
- What gets better
- What tradeoff you're accepting (there's always one — name it)
- Follow-up work this creates, if any

## Evidence
Numbers, if you have them: latency before/after, error rate, throughput.
This is the section that makes the ADR worth reading later — it's the
difference between "I added a circuit breaker" and "p99 during a simulated
catalog-service outage dropped from timeout-then-500 to a 40ms fallback
response."
