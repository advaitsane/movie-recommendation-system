# Experiment 3: 8000ms network delay injected on search-service (pumba/netem, 30s)

**Confirms the ~6s timeout budget from ADR-0010 directly**: the first load-test run immediately after
injecting the delay showed `GET /api/recommendations/{id}` avg=6094.1ms, p99=6152.3ms -- bounded right
around 6s even though the injected delay was 8s, i.e. recommendation-service's call to search-service times
out and degrades rather than waiting the full induced latency. This is the same timeout ADR-0010 inferred
from stress-test tail latency, now reproduced directly and deterministically.

**Direct search-service latency (GET /api/movies/search/vector) was only partially elevated** (avg 2036ms
first run, decaying to normal ~250-450ms in later runs) rather than consistently ~8000ms -- likely because
pumba's netem delay takes a moment to actually apply after `docker run -d` returns, and/or because k6's
persistent HTTP connections meant only some requests hit a freshly-delayed path. Not fully explained; if
network-latency chaos becomes a regular exercise, worth injecting for a longer window (60s+) and confirming
via pumba's own logs when the qdisc actually became active, rather than relying on wall-clock timing alone.

**No health-check impact** -- as expected, `/actuator/health` doesn't measure latency, so no service was
ever flagged unhealthy by this fault, consistent with Experiment 1's health-check-blind-spot finding
(these are liveness checks, not latency/dependency-health checks).

**How to apply**: no code change needed -- this is a second, more precise confirmation that the
recommendation-service degrade-on-timeout pattern holds.
