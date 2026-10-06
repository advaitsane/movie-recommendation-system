# ADR 0009: Stand up config-server (server only, no client migration yet)

**Status:** Accepted
**Date:** 2026-09-14

## Context
`config-server` had been a README-only stub since the project's build order was first sketched
(step 8 in the README's Build order, optional infra). Every service currently owns its
config entirely in its own `application.yml` plus per-service env vars in `docker-compose.yml` —
that has worked fine at 6 services, but means a setting that needs to change everywhere (an OTLP
endpoint, an actuator exposure list) has to be edited in 6+ places by hand.

Two infra pieces were left as stubs: config-server and eureka-server. Discovery
(`eureka-server`) only pays off once something needs dynamic multi-instance routing — this repo
is a deliberately single-instance local deployment with configured-URL routing that already works
(ADR-0007). Config-server has more immediate value even at single-instance scale: centralizing
config is useful regardless of how services find each other. Built config-server first for that
reason.

**Scope decision, made explicitly up front:** build and live-verify config-server itself; do
**not** also migrate the 6 existing, already-live-verified services to actually fetch their
config from it. That migration means adding `spring-cloud-starter-config` +
`spring.config.import=configserver:` to every service and moving real settings out of their own
`application.yml` — a bigger, riskier change touching every working service at once, better done
as its own separately-verified step later.

## Decision
Built `config-server` as a standalone Spring Cloud Config Server (`spring-cloud-config-server`,
Spring Cloud 2025.1.0 BOM — same version api-gateway already pinned, confirmed compatible with
this repo's Boot 4.1.1 the same way, including the same `compatibility-verifier.enabled: false`
workaround from ADR-0007).

**Git-backed, not native/classpath-backed**, per the original README stub's stated design ("a Git
repo of config files so changes are versioned and auditable") — but backed by a **local** git repo
(`services/config-server/config-repo/`, its own nested `.git`, committed and versioned
independently of the outer project, which isn't under git at all in this workspace) rather than a
real remote (GitHub, etc.), so the server is fully self-contained for local dev with no external
dependency. `spring.cloud.config.server.git.uri` defaults to `file://${user.dir}/config-repo`
(correct when run via `./mvnw spring-boot:run`, cwd = `services/config-server`) and is overridden
via `CONFIG_REPO_URI` in `docker-compose.yml` to `file:///config-repo`, where the repo is mounted
read-only as a volume — no image rebuild needed to pick up a config change.

Populated `config-repo/` with one file per service (`catalog-service.yml`, `search-service.yml`,
`review-service.yml`, `recommendation-service.yml`, `user-service.yml`, `api-gateway.yml`) plus a
shared `application.yml`, mirroring each service's *current* `application.yml`/`docker-compose.yml`
settings — a preview/seed of what centralizing would look like, proving the server actually serves
real, representative content, without any client reading from it yet. `config-repo/README.md`
documents this explicitly so a future reader doesn't mistake the seed files for something live.

Added the same observability stack every other service has (`spring-boot-starter-actuator`,
`micrometer-registry-prometheus`, `spring-boot-starter-opentelemetry`) — config-server is
infrastructure, but cheap to keep consistent with ADR-0008's pattern, and it means a future outage
in config-server itself (once something depends on it) is visible in the same Jaeger/Prometheus
stack as everything else.

## Alternatives considered
- **Native (classpath/filesystem) backend instead of git** — rejected: simpler, but the stub
  README's stated design explicitly calls for git-backed, versioned config; a local git repo gets
  that property (real commit history, `git log` shows every config change) for the same amount of
  local-dev complexity as native would have been.
- **Build server + migrate all 6 clients in one pass** — rejected; scoped to "server only"
  before starting. Bigger blast radius (every already-verified
  service) for no immediate payoff, since nothing today actually needs centralized config to
  function.
- **Bake `config-repo/` into the Docker image (COPY, not a volume mount)** — rejected: would
  require an image rebuild for every config change, defeating the point of separating config from
  code. A bind mount lets `docker compose restart config-server` alone pick up new config-repo
  commits.
- **Mount `config-repo/` read-only (`:ro`)** — tried first, since the container only ever *reads*
  config; reverted after live testing failed it. JGit needs to write its own lock/index files
  (`.git/index.lock`, etc.) directly into the mounted directory on every request, even for a pure
  read operation against a local `file://` URI — a `:ro` mount fails with `Read-only file system`
  creating that lock file (confirmed via `docker run`, then reproduced identically through real
  `docker compose up`). Mounted read-write instead; nothing else writes to this path, and
  config-server itself never commits new content to it.

## Consequences
- config-server runs and serves real config today, independently verified both via its own test
  suite (`ConfigServerApplicationTest`, hitting the live REST API with a real embedded git repo,
  not mocks) and by running the packaged jar standalone and curling it directly.
- No existing service's behavior changed — all 6 already-live-verified services still read their
  own `application.yml` exactly as before. Zero risk to what's already working.
- **Follow-up work this creates:** migrating any service to actually consume config-server is a
  separate, not-yet-started task. When it happens: add `spring-cloud-starter-config`, set
  `spring.config.import=optional:configserver:http://config-server:8888` (leading `optional:` so a
  service still starts if config-server is briefly unreachable — a real design choice to make
  explicitly, not a default to inherit accidentally), and only then delete the migrated settings
  from that service's own `application.yml`, one service at a time, live-verifying each.
- **Known, harmless log noise:** every config fetch logs
  `WARN ... MultipleJGitEnvironmentRepository : Could not merge remote for main remote: null` —
  JGit's clone-and-track machinery expects a real remote to merge from on refresh; a pure local
  `file://` repo has none. Config is still served correctly (proven by the evidence below); this
  is cosmetic, not a functional gap, and not worth suppressing for a server nothing depends on yet.

## Evidence
Live, three separate ways — the packaged jar run standalone, a raw `docker run` against the built
image, and the real `docker compose up -d --no-deps config-server` path — not just the test suite:

```
$ curl -s http://localhost:8888/actuator/health
{"groups":["liveness","readiness"],"status":"UP"}

$ curl -s http://localhost:8888/catalog-service/default | python3 -m json.tool
{
    "name": "catalog-service",
    "profiles": ["default"],
    "propertySources": [
        {
            "name": "file:///.../config-repo/catalog-service.yml",
            "source": {
                "server.port": 8081,
                "spring.mongodb.uri": "${MONGODB_URI:mongodb://localhost:27017/sample_mflix}",
                "spring.mongodb.database": "sample_mflix",
                "spring.kafka.bootstrap-servers": "${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}"
            }
        },
        {
            "name": "file:///.../config-repo/application.yml",
            "source": { "management.endpoints.web.exposure.include": "health,prometheus,info", ... }
        }
    ]
}

$ curl -s http://localhost:8888/recommendation-service-default.yml
# YAML resolver format, placeholders resolved from real env — confirms both REST formats work
```

Test suite: 3/3 passing (`ConfigServerApplicationTest`) — asserts catalog-service and
search-service resolve to their own distinct database names, the shared `application.yml`
defaults merge in correctly, and an application with no dedicated file still resolves (falls back
to shared defaults only, does not 404 — verified this is Spring Cloud Config's actual behavior,
not assumed).

Docker: `docker compose up -d --no-deps config-server` — built, started, and served
`catalog-service`'s config correctly through the actual compose network/volume path (not just a
manual `docker run` approximation of it) before being torn down again.

## Update (2026-10-05): config-repo/ is read from this project's GitHub repo

The nested-repo design above did not survive publishing the project. Once the outer project went
under git, `config-repo/` could not keep its own `.git`: git records a nested repo as a gitlink
with no files, so a fresh clone (and CI) would get an empty directory, and config-server's
`clone-on-start` failed. Three replacements were considered:

- **Git backend pointed at this project's GitHub repo** (chosen). `uri` defaults to
  `https://github.com/advaitsane/movie-recommendation-system` with
  `search-paths: services/config-server/config-repo` and `default-label: main`. This is the usual
  production shape: a config change goes live only by being merged, and its review and audit trail
  is the project's PR history rather than a separate repo's. A branch can be served before merge
  by asking for it as the label (`/catalog-service/default/my(_)branch`).
- **Native (filesystem) backend over `config-repo/`.** Simplest, works offline, and the outer repo
  would still version the files, but it reverses this ADR's git-backed decision and drops
  per-label serving.
- **Git backend pointed at the local checkout** (`file://` repo root plus search-paths). Works
  offline, but CI's checkout is a detached merge commit with no local `main` branch, so the test
  would still need its own repo, and compose would have to mount the whole project.

What changed:
- `application.yml`: new default `uri`, plus `search-paths`. `CONFIG_REPO_URI` still overrides
  it, for a fork or a local clone.
- `docker-compose.yml`: the `config-repo/` bind mount and the `file:///config-repo` override are
  gone, which also retires the read-only-mount problem in Alternatives above.
- `ConfigServerApplicationTest` builds a throwaway git repo (JGit, already on the classpath) holding
  this checkout's `config-repo/` files at the same search path, and points `uri` at it. The test
  runs offline, still exercises the real git backend and `search-paths`, and checks the files on
  the branch under test rather than whatever is on `main`. 3/3 pass.

The `Could not merge remote for main remote: null` warning described in Consequences no longer
appears: the server now clones a real remote, so JGit has something to merge from.

Tradeoffs accepted: config-server now needs network access at startup, and a local edit to
`config-repo/` is not served until it is merged (or requested by branch label). Both are
acceptable while no service consumes config-server. Before any service depends on it, the
`optional:` import in Consequences matters more, since a GitHub outage would then surface as a
config-server startup failure.
