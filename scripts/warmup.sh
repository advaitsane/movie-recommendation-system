#!/usr/bin/env bash
# Sends a few requests to each public read path through api-gateway, so JIT compilation, connection
# pools and Mongo's cache are warm before a measured run. On a small host the first cold queries
# can exceed the gateway's 4s search budget (ADR-0010 Update 6).
# Usage: scripts/warmup.sh [rounds]
set -uo pipefail

ROUNDS="${1:-3}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PATHS=(
  "/api/movies?limit=20"
  "/api/movies/search?q=love&limit=10"
  "/api/movies/search?q=war&limit=10"
  "/api/movies/search/vector?q=an%20astronaut%20stranded%20in%20space&limit=10"
)

echo "warming up api-gateway paths (${ROUNDS} rounds)"
for ((round = 1; round <= ROUNDS; round++)); do
  for path in "${PATHS[@]}"; do
    curl -s -o /dev/null --max-time 10 -w "  round ${round}: %{http_code} %{time_total}s  ${path%%\?*}\n" "${BASE_URL}${path}"
  done
done
