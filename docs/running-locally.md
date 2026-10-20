# Running locally

Requirements: Docker (give its VM at least 4 GB of memory), `make`, and [k6](https://k6.io) for
the load and chaos tests. JDK 25 is needed only to run a service outside a container; each service
ships its own Maven wrapper.

## Hardware

The whole stack is tested on an 8 GB Intel Mac with a 4 GB Docker VM. It runs, with little room
to spare (ADR-0010 Update 6):

- **The first start takes about 10 minutes** after the images are built:
  - `mflix-mongo` is healthy after about 2.5 minutes;
  - the dataset restore takes about 30s;
  - search-service's one-time copy of the catalog takes about 6 minutes.

  Later starts take under a minute.
- **Right after a cold start, the first searches are slow** (2.5–3.5s). That's close to the
  gateway's 4s search budget, so a few get the circuit breaker's 503. `make smoke` and
  `make load` warm up first (`make warmup`).
- **The stack needs about 3.85 GB, so a 4 GB VM swaps** (ADR-0010 Update 9):
  - assistant-service is off by default; `make assistant` adds it to a running stack, and
    `make up-all` starts it with config-server;
  - the gateway's timeout is 10s under compose instead of its 4s design budget, because a vector
    search that pages takes 5–18s, and 12s instead of 6s on the recommendation route. On a bigger
    host, `GATEWAY_TIMEOUT=4s GATEWAY_RECOMMENDATION_TIMEOUT=6s make up` restores the design budget;
  - a latency delay injected by `make chaos` has to exceed 10s to trip the breaker.
- **The full load test (`make load`) is heavy for this host.** Use `make load VUS=5`, and close
  other apps first. ADR-0010 Updates 4–5 show what an overloaded host does to the numbers.

16 GB of RAM, with 6 GB or more for Docker, removes all of this. Roadmap Phase 3 moves the stack
to Kubernetes.

## 1. Start everything

```bash
make up        # docker compose up -d --build, then wait until every service reports UP
make demo      # walk a new user through the system, step by step
make help      # every other target
```

`make demo` warms up the read paths, seeds eight fans with recognizable tastes
(`scripts/seed-demo-data.sh`, safe to rerun), then runs `scripts/demo.sh`:
1. a new user registers;
2. searches by keyword and by meaning;
3. gets cold-start recommendations;
4. reviews two movies;
5. waits until recommendations turn personal, and prints how long the review event took to arrive.

With assistant-service running (`make assistant`), it ends with one streamed answer.
`DEMO_PAUSE=2 make demo` slows it down for a recording.

Run it on fresh data. On a 4 GB Docker VM the first start's index rebuild leaves the VM's swap
full, so restart once before recording: `make clean && make up`, then `make down && make up`,
then `make demo` (ADR-0010 Update 10). Each run adds a user who liked exactly The Matrix
and Blade Runner. Later demo users pick those as their closest collaborative peers, though they
have nothing new to recommend, so after a few runs the collaborative signal empties.

On the first start, two one-shot compose jobs load MongoDB's `sample_mflix` dataset into
`mflix-mongo`:
- `mflix-download` fetches the ~400 MB archive into the `mflix-dump` volume;
- `mflix-seed` restores it.

Both are no-ops after that, so later starts skip straight to the services. `mflix-mongo` runs
`mongodb-atlas-local`, which supports Atlas Search and vector search locally; catalog-service and
search-service use it. `DO_NOT_TRACK=1` turns off the image's usage telemetry, whose reporter
crashed the container under load (ADR-0010).

On its first start, search-service copies the catalog (about 20,000 movies) into its own index.

config-server is off by default, since no service reads from it yet (ADR-0009), and so is
assistant-service, to fit a 4 GB Docker VM (see [hardware](#hardware)). `make assistant` adds
assistant-service to a running stack; `make up-all` starts both.

`make eval-up` starts only what the assistant's tools reach: assistant-service, search-service,
recommendation-service, their stores and Jaeger, about 2.2 GB instead of 3.85. There's no
gateway, so call assistant-service on :8086 with an `X-User-Id` header. Kafka and catalog-service
stay down, so no events flow and nothing is backfilled; run `make up` once first to load the data.
`docker-compose.eval.yml` holds the overrides. `make down` stops it.

`make down` stops everything and keeps the data. `make clean` also deletes the data volumes, but
keeps the downloaded archive.

**Coming from the older setup,** where `mflix-mongo` was started with `docker run`: remove that
container first (`docker rm -f mflix-mongo`), because compose now creates one with the same name.
Its anonymous data volume isn't deleted, but compose doesn't use it; the seed job loads the
dataset again into the `mflix-data` volume.

## 2. The services

Each service's README covers its endpoints, configuration and tests, and ships a Postman
collection:

- [catalog-service](../services/catalog-service/README.md) (:8081)
- [search-service](../services/search-service/README.md) (:8082). Vector search needs an
  embedding-provider key; without one those two endpoints return 503 and everything else works.
- [review-service](../services/review-service/README.md) (:8083). Uses the compose Postgres;
  Flyway creates its schema on first start.
- [recommendation-service](../services/recommendation-service/README.md) (:8084). Uses the compose
  Mongo and Redis; builds its read models from catalog and review events.
- [user-service](../services/user-service/README.md) (:8085). Uses its own `mflix_users` database
  on the compose Postgres. It issues the JWTs that api-gateway validates.
- [assistant-service](../services/assistant-service/README.md) (:8086, `make assistant` or
  `make up-all`). Keeps chat memory in its
  own `mflix_assistant` database on the compose Postgres (on an older data volume, create it once:
  `docker compose exec postgres createdb -U mflix mflix_assistant`). Needs an OpenAI key
  (`OPENAI_API_KEY`, or a gitignored `application-local.yml`); without one it starts, and every
  answer ends in an `error` event.
- [api-gateway](../services/api-gateway/README.md) (:8080). The single entry point for `/api/**`:
  routes to the services above and requires a valid JWT on every route except registration,
  login and movie browsing.
- [config-server](../services/config-server/README.md) (:8888, `make up-all` only). Serves the
  per-service files in `services/config-server/config-repo/`, cloned from this repo's `main`
  branch on GitHub, so it needs network access. No service reads from it yet (ADR-0009). There is no Eureka server:
  every call uses a configured URL, and `services/eureka-server` is a stub by design (ADR-0007).

To run one service from the IDE instead, start only the infrastructure with
`docker compose up -d mongo postgres redis kafka mflix-mongo mflix-seed`. Then run the service
with its `local` profile; its README has the details.

## 3. Observability

`make up` also starts Jaeger, Prometheus and Grafana. Every service exposes `/actuator/health` and
`/actuator/prometheus` and exports traces over OTLP (see ADR-0008):

- Jaeger UI: http://localhost:16686
- Prometheus: http://localhost:9090 (one scrape target per service)
- Grafana: http://localhost:3001, no login needed to view (`admin`/`admin` to edit). Three
  dashboards are provisioned from `infra/grafana/provisioning/dashboards/json/`, in the Movies
  folder:
  - **Service overview (RED):** rate, errors and latency per service and per gateway route;
  - **Resilience:** circuit breakers, recommendation fallbacks and connection pools, the one to
    watch during `make chaos`;
  - **Events and JVM:** Kafka lag, the review outbox backlog and JVM memory.

  They're read-only in the UI: edit the JSON and Grafana reloads it. ADR-0008 Update 2 explains
  each panel's metrics. [preset-queries.md](preset-queries.md) lists the same queries as plain
  PromQL, one per on-call question.

## 4. Load testing

[`loadtest/user-journey.js`](../loadtest/user-journey.js) is a k6 script that drives two journeys
through api-gateway: anonymous browsing (catalog, keyword and vector search) and a registered user
(register, log in, browse, review, get recommendations). With the full stack up:

```bash
make smoke       # warm-up, then 2+2 VUs, one iteration: checks the system end to end
make load        # warm-up, then 15+15 VUs over 3 minutes
make load VUS=5  # scale down on a smaller host
```

The full run is heavy for a laptop: check the host is idle first. ADR-0010 records what the runs
found:
- a gateway timeout shorter than recommendation-service's own fallback;
- a crash in the `mongodb-atlas-local` image;
- JVMs OOM-killed before each service had a memory limit.

It also records how each was fixed. The raw k6 summaries are kept in `loadtest/results/`.

## 5. Chaos testing

[`chaos/`](../chaos/) holds four bash experiments. Each one:
1. runs looping k6 smoke traffic;
2. polls every service's `/actuator/health` every 2 seconds;
3. injects one fault and then restores it;
4. summarizes which services went unhealthy and when.

```bash
make chaos CHAOS="01_dependency_kill.sh mflix-mongo 30"              # stop mongo, postgres, kafka or mflix-mongo
make chaos CHAOS="02_service_kill.sh search-service 30"              # docker kill an app service
make chaos CHAOS="03_network_fault.sh delay search-service 12000 30" # latency, packet loss or partition (pumba)
make chaos CHAOS="04_resource_exhaustion.sh search-service 96 30"    # shrink a container's memory limit
```

Results go to `chaos/results/<experiment>_<timestamp>/`: the health CSV, a summary and a written
`FINDINGS.md` (the k6 logs are not kept). ADR-0011 records the experiments and the hardening that
followed them, including three gaps that tests alone had not caught:
- a Mongo outage that `/actuator/health` did not report
- gateway circuit breakers that could never open
- Postgres circuit breakers wrapped inside `@Transactional` methods, where they protected nothing

[`service-hardening-checklist.md`](service-hardening-checklist.md) is the per-service checklist
that came out of that work.

## Secrets

All credentials in `docker-compose.yml` are local-development placeholders. Real secrets (such as
an embedding-provider API key) go in a gitignored `application-local.yml` when running from the
IDE; see each service's README. Each service's `.dockerignore` keeps that file out of its image, so
under Docker Compose pass them from the shell instead (for example
`OPENAI_API_KEY=... make up`).
