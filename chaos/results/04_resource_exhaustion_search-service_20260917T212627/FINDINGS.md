# Experiment 4: shrink search-service to 96m under load (30s)

**Kernel OOM-kill confirmed**: `docker inspect` showed `OOMKilled=true`, `ExitCode=137` right after the
96MB cap was applied under SMOKE load (idle RSS was already ~278MB against the normal 384MB limit, so 96MB
was always going to trigger it). recommendation-service degraded gracefully throughout (same
`SearchServiceClient` fallback pattern as Experiments 2/3), confirming the fallback path is triggered
uniformly regardless of *why* search-service is unreachable.

**No restart policy is configured in docker-compose.yml** for any app service, so the OOM-killed container
just stayed `exited` -- nothing auto-restarted it. This is expected/known, not a new finding.

**Real bug found and fixed during this experiment**: the restore step originally used a plain
`docker compose up -d --no-deps search-service`. Since `docker update --memory` sets a cgroup limit that
survives a container restart, and compose saw no config diff (image/command unchanged), it just re-started
the existing container -- still capped at 96MB. search-service then OOM-looped through its own Spring Boot
startup (which needs more than 96MB to boot) and was left **actually broken** after the "cleanup" step
completed successfully. Caught via a live health check (000) after the experiment reported success. Fixed
by switching to `docker compose up -d --force-recreate --no-deps` in `04_resource_exhaustion.sh`, which
correctly reapplies the compose file's mem_limit=384m. Manually recovered the live environment the same way
before writing this up.

**How to apply**: any tooling (chaos scripts, manual ops runbooks) that uses `docker update` to temporarily
change a running container's resources MUST restore via `--force-recreate`, never a plain `up -d` --
otherwise the temporary override silently becomes permanent. This is the same root-cause class as the
stale-container-resume bug from ADR-0010 Update 5 (docker compose reusing an existing container instead of
giving you a truly fresh one) -- different trigger, same lesson: never trust `docker compose up -d` alone
to restore a known-good state after any out-of-band `docker update`/`docker exec` mutation.
