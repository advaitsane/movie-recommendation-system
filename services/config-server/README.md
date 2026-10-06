# config-server (:8888)

Spring Cloud Config Server. Serves config from git: it clones this project's GitHub repo and
reads `config-repo/` (via `search-paths`) on `main`, so a config change goes live only by being
merged, and its history is the project's own PR history. See `docs/adr/0009`.

**Status: server built and live-verified; no client service consumes it yet.** All 6 domain
services still own their config entirely via their own `application.yml` +
`docker-compose.yml` env vars, unchanged. `config-repo/`'s per-service files are a seed/preview of
what centralizing would look like, not something anything currently reads at runtime.

## Endpoints
Standard Spring Cloud Config Server API, e.g.:
- `GET /{application}/{profile}` — JSON `Environment` (e.g. `GET /catalog-service/default`)
- `GET /{application}-{profile}.yml` — same content as resolved YAML
- `GET /actuator/health`, `/actuator/prometheus` — same observability stack as every other
  service (ADR-0008)

## Run locally
```
./mvnw spring-boot:run
curl http://localhost:8888/catalog-service/default
```
Needs network access: it clones `https://github.com/advaitsane/movie-recommendation-system` at
startup and serves `main`. Local edits to `config-repo/` are not served until they're merged. To
try a change first, either request a branch as the label (`/` becomes `(_)`):
```
curl 'http://localhost:8888/catalog-service/default/my(_)branch'
```
or point `CONFIG_REPO_URI` at another repo, such as a fork or a local clone
(`CONFIG_REPO_URI=file:///path/to/clone`). The test suite does the latter: it builds a throwaway
git repo from this checkout's `config-repo/` files, so it runs offline.

## Next step (not started)
Migrating a service to actually pull from this server: add `spring-cloud-starter-config`, set
`spring.config.import=optional:configserver:http://config-server:8888`, then delete the migrated
settings from that service's own `application.yml` — one service at a time, live-verified each
time. See `docs/adr/0009`'s Consequences section.
