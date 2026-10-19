# Preset PromQL queries

The incident copilot (roadmap Phase 3) will query Prometheus through a read-only tool. Instead of
writing PromQL from scratch, it starts from these presets: the queries behind the Grafana
dashboards (ADR-0008 Update 2), each answering one question an on-call engineer asks first. Each
one has been run against this stack. The last column names the chaos experiment or load test that
should move it, which makes it the copilot's test case for that query.

Every query uses a 5-minute window, which is long enough to smooth a 15s scrape interval and short
enough to show an incident in progress.

## Is it up, and is it failing?

| Question | Query | Check with |
|---|---|---|
| Which services are down? | `up{job!="assistant-service"} == 0` | `02_service_kill.sh` |
| Error share per service | `sum by (job) (rate(http_server_requests_seconds_count{status=~"5..",uri!~"/actuator.*"}[5m])) / sum by (job) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))` | `01_dependency_kill.sh` |
| p95 latency per service | `histogram_quantile(0.95, sum by (job, le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))` | `make load` |
| p95 per gateway route | `histogram_quantile(0.95, sum by (spring_cloud_gateway_route_id, le) (rate(http_client_requests_seconds_bucket{job="api-gateway"}[5m])))` | `03_network_fault.sh delay` |
| Share of requests over the 4s design budget | `1 - sum by (job) (rate(http_server_requests_seconds_bucket{le="4.0",uri!~"/actuator.*"}[5m])) / sum by (job) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))` | `make load` on a 4 GB host |

`up` leaves out assistant-service, which is off by default (`make assistant`), so it would always
look down.

## Is it protecting itself?

| Question | Query | Check with |
|---|---|---|
| Which breakers are open or half-open? | `resilience4j_circuitbreaker_state{state=~"open\|half_open"} == 1` | `03_network_fault.sh delay` |
| Calls rejected by an open breaker | `sum by (job, name) (rate(resilience4j_circuitbreaker_not_permitted_calls_total[5m]))` | `make load` on a 4 GB host |
| Breaker failures, timeouts included | `sum by (job, name) (rate(resilience4j_circuitbreaker_calls_seconds_count{kind="failed"}[5m]))` | `02_service_kill.sh` |
| Recommendations served degraded, by reason | `sum by (reason) (rate(recommendation_fallback_total[5m]))` | `02_service_kill.sh search-service` |
| Gateway fallback responses | `sum(rate(http_server_requests_seconds_count{job="api-gateway",status="503"}[5m]))` | `02_service_kill.sh` |

## Is data flowing?

| Question | Query | Check with |
|---|---|---|
| Kafka consumer lag per service and topic | `sum by (job, topic) (kafka_consumer_fetch_manager_records_lag)` | `01_dependency_kill.sh kafka` |
| Reviews written but not yet published | `review_outbox_unpublished` | `01_dependency_kill.sh kafka` |
| Movies search can't find by meaning | `search_movies_without_embedding` | a search-service start without an embedding key |

## Is it running out of something?

| Question | Query | Check with |
|---|---|---|
| Threads waiting for a Postgres connection | `hikaricp_connections_pending` | `01_dependency_kill.sh postgres` |
| Threads waiting for a Mongo connection | `sum by (job) (mongodb_driver_pool_waitqueuesize)` | `01_dependency_kill.sh mongo` |
| Heap used against its cap | `sum by (job) (jvm_memory_used_bytes{area="heap"}) / sum by (job) (jvm_memory_max_bytes{area="heap"})` | `04_resource_exhaustion.sh` |
| Share of time in GC | `sum by (job) (rate(jvm_gc_pause_seconds_sum[5m]))` | `04_resource_exhaustion.sh` |

## What the presets can't see

- **Container memory against `mem_limit`:** there's no cAdvisor. An OOM kill shows only as
  `up == 0` followed by a restart; `docker inspect` (or Kubernetes events, later) says why.
- **The Docker VM itself:** swap and memory pressure aren't in Prometheus. ADR-0010 Update 9 measured
  them with `/proc/pressure/memory`.
- **Why a request was slow:** a preset finds the slow route; the trace in Jaeger shows where the
  time went. The copilot needs both tools.
