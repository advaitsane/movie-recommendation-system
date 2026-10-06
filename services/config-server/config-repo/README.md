# config-repo

The config `config-server` serves. It isn't a separate repo: config-server clones this project's
GitHub repo and reads this directory on `main` (see `docs/adr/0009`). Each file below is served to
a matching Spring Cloud Config *client* at `GET /{application}/{profile}` once a client actually
exists; today **no service reads from this directory** — every service still owns its own
`application.yml`, unchanged.

Files here are a seed/preview of what each service's config would look like centralized, mirroring
the values currently hardcoded in that service's own `application.yml` / `docker-compose.yml`
environment block, kept here for the config-server to serve and be tested against. They are not
consumed anywhere yet — migrating a service to actually pull from `config-server` (adding
`spring-cloud-starter-config` + a `spring.config.import=configserver:` entry, and deleting the
now-redundant settings from that service's own `application.yml`) is deliberately out of scope for
now (bigger, riskier change touching every already-working, live-verified service at once).

- `application.yml` — global defaults shared by every application (Boot 4.1.1 compatibility-
  verifier opt-out, actuator exposure, OTLP endpoint placeholder).
- `catalog-service.yml`, `search-service.yml`, `review-service.yml`,
  `recommendation-service.yml`, `user-service.yml`, `api-gateway.yml` — per-service overrides,
  named to match each service's `spring.application.name` (Spring Cloud Config's lookup key).
