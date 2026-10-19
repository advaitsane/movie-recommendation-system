# Experiment 2 again: kill search-service (30s), watched on the Resilience dashboard

Rerun on 2026-10-19 to capture `docs/images/grafana-resilience-search-down.jpg` for the demo
(`docs/demo.md`, act 3), now that the services' breaker metrics and the
`recommendation.fallback` counter exist (ADR-0008 Update 2). Laptop stack: assistant-service off,
gateway budget 10s (ADR-0010 Update 9).

**What the dashboard showed** (times local, 2026-10-19):
- 12:24:15: search-service killed. Its own `mongodb-search` breaker series stops, since the process
  is gone. The gateway's `searchServiceCB` goes from closed to open.
- 12:24:30 to 12:25:15: the breaker alternates between half-open and open. Each half-open probe
  fails while search-service is still restarting.
- 12:25:15: search-service is back (17 unhealthy health readings over 51s, `summary.txt`).
- 12:25:30: the breaker goes half-open, its probes succeed, and it closes.
- Calls rejected by the open breaker peaked at 0.4 req/s; `recommendation.fallback{reason=
  "search_unavailable"}` rose and fell over the same window. Every other service stayed healthy.

**Matches experiment 2 of 2026-09-17**: recommendations degrade to their other signals instead of
failing. New since then: the breaker's state and the fallbacks are visible on a dashboard rather
than only in logs.

**A note on reading breaker state.** A breaker moves from open to half-open on the next call after
its 10s wait, not on a timer. With no traffic it stays "open" on the dashboard indefinitely: the
first attempt at this capture showed `searchServiceCB` open for two minutes after a load test
had ended, before this experiment started. The capture above was taken after closing it with
traffic first.
