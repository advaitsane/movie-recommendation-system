#!/usr/bin/env bash
# Shrinks a running container's memory limit at runtime (docker update --memory), well below
# what it needs, to see whether it degrades gracefully or gets OOM-killed by the kernel — and
# whether docker-compose's declared mem_limit (see ADR-0010) is restored cleanly afterwards.
# Usage:
#   ./04_resource_exhaustion.sh <compose-service-name> <tight_limit_mb> [duration_s]
# e.g.:
#   ./04_resource_exhaustion.sh search-service 96 30
#   ./04_resource_exhaustion.sh postgres 64 30
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

SVC="${1:?usage: 04_resource_exhaustion.sh <compose-service-name> <tight_limit_mb> [duration_s]}"
LIMIT_MB="${2:?tight memory limit in MB required, e.g. 96}"
DOWNTIME="${3:-30}"
CONTAINER="movie-recommendation-system-${SVC}-1"
TS=$(date +%Y%m%dT%H%M%S)
OUT="results/04_resource_exhaustion_${SVC}_${TS}"
mkdir -p "$OUT"

echo "=== chaos experiment: shrink '$SVC' to ${LIMIT_MB}m for ${DOWNTIME}s ==="
start_monitor 2 "$OUT/health.csv"
start_background_load $((DOWNTIME + 40)) "$OUT/load.log"
sleep 10

echo "$(date -u +%FT%TZ) shrinking $CONTAINER to ${LIMIT_MB}m"
docker update --memory "${LIMIT_MB}m" --memory-swap "${LIMIT_MB}m" "$CONTAINER"

sleep "$DOWNTIME"

echo "$(date -u +%FT%TZ) checking OOMKilled state before restore"
docker inspect --format '{{.State.OOMKilled}} {{.State.Status}}' "$CONTAINER" | tee "$OUT/oom_state.txt"

echo "$(date -u +%FT%TZ) restoring $SVC to its compose-declared limits"
# --force-recreate is required here: `docker update --memory` sets a cgroup limit that persists
# across a plain container restart. `docker compose up -d` alone sees no config diff and just
# re-starts the existing (still-capped) container instead of recreating it with the compose file's
# real mem_limit -- discovered the hard way when search-service got stuck OOM-looping at 96m
# after a plain `up -d` "restore". Force-recreate is the only way to actually reapply mem_limit.
docker compose up -d --force-recreate --no-deps "$SVC"

sleep 20
stop_background_load
stop_monitor

summarize_monitor "$OUT/health.csv" | tee "$OUT/summary.txt"
echo "results in $OUT"
