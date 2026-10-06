# eureka-server (:8761) — not built, by design

Spring Cloud Netflix Eureka would provide service discovery, so the gateway and services don't
hardcode each other's hosts and ports. That only pays off once a service runs more than one
instance. This deployment runs one instance of everything, and every inter-service call
already uses a configured URL (`CATALOG_SERVICE_URL`, `SEARCH_SERVICE_URL`, ...).

So this directory stays a stub, and `docker-compose.yml` doesn't run it. If discovery is ever
needed, the README's build order (step 8) points to Kubernetes Service DNS rather than Eureka.
See [ADR-0007](../../docs/adr/0007-api-gateway-routing-and-deferred-discovery.md), including its
update closing this out.
