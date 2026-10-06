# Experiment 2: kill search-service outright (30s downtime)

**Graceful degradation confirmed working under real chaos**: `recommendation-service`'s
`SearchServiceClient.findSimilar()` caught every failure mode as search-service died and came back —
"Connect timed out" (~6s, right after kill, before the port fully closed) -> "Connection refused" (once
compose issued the restart but the JVM hadn't bound port 8082 yet, ~20-30s) -> recovery -- and logged
"degrading to no content-based signal" each time rather than propagating an error to the client. This is
exactly the resilience pattern ADR-0010 described, now verified end-to-end rather than just inferred from
stress-test timeout numbers.

**search-service's own health check correctly went unhealthy** (20/~40 readings over the outage) while its
container was down -- unlike Experiment 1's mflix-mongo finding, a service being down itself (not just a
dependency) IS correctly reflected in /actuator/health. The blind spot from Experiment 1 is specific to
downstream dependency failures, not service-liveness failures.

**Residual ~40% http_req_failed rate** persisted before/during/after the outage -- matches the already-known
k6 test-script artifact from ADR-0010 (duplicate 409s on fixed per-VU test data), not a new chaos-specific
issue. Not investigated further here.

**How to apply**: no code change needed -- this experiment is a positive confirmation that the
recommendation-service fallback pattern holds under real chaos, not just synthetic stress-test timeouts.
