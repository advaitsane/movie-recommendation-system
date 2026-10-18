#!/usr/bin/env bash
# Waits until every app service's /actuator/health reports UP, printing how long each one took.
# Usage: scripts/wait-healthy.sh [timeout_seconds]
set -uo pipefail

# A first start takes longest: search-service copies the whole catalog (~21k movies, about 6
# minutes on a 4 GB Docker VM) before it reports UP.
TIMEOUT="${1:-900}"
SERVICES=(api-gateway:8080 catalog-service:8081 search-service:8082 review-service:8083
  recommendation-service:8084 user-service:8085 assistant-service:8086)
# config-server only runs under the `config` compose profile.
[[ ",${COMPOSE_PROFILES:-}," == *,config,* ]] && SERVICES+=(config-server:8888)

start=$(date +%s)
pending=("${SERVICES[@]}")
echo "waiting up to ${TIMEOUT}s for ${#pending[@]} services to report UP"
echo "(on a first start, search-service backfills the catalog before it reports UP: ~6 minutes)"

while ((${#pending[@]} > 0)); do
  still=()
  for entry in "${pending[@]}"; do
    name="${entry%%:*}" port="${entry##*:}"
    if curl -sf --max-time 3 "http://localhost:${port}/actuator/health" | grep -q '"status":"UP"'; then
      printf '  %-24s UP after %ss\n' "$name" $(($(date +%s) - start))
    else
      still+=("$entry")
    fi
  done
  pending=("${still[@]+"${still[@]}"}")
  ((${#pending[@]} == 0)) && break

  if (($(date +%s) - start >= TIMEOUT)); then
    echo "timed out; not UP: ${pending[*]%%:*}"
    echo "check them with: docker compose ps, docker compose logs <service>"
    exit 1
  fi
  sleep 3
done

echo "all services UP in $(($(date +%s) - start))s"
