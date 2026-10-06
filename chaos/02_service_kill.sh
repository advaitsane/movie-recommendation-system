#!/usr/bin/env bash
# Kills one of the 6 app services outright (docker kill, no graceful shutdown), holds it down,
# restarts it, and records health of every service throughout (to see how dependents/gateway
# behave while it's gone). Usage:
#   ./02_service_kill.sh <api-gateway|catalog-service|search-service|review-service|recommendation-service|user-service> [downtime_seconds]
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

SVC="${1:?usage: 02_service_kill.sh <service-name> [downtime_seconds]}"
DOWNTIME="${2:-30}"
TS=$(date +%Y%m%dT%H%M%S)
OUT="results/02_service_kill_${SVC}_${TS}"
mkdir -p "$OUT"
CONTAINER="movie-recommendation-system-${SVC}-1"

echo "=== chaos experiment: kill service '$SVC' for ${DOWNTIME}s ==="
start_monitor 2 "$OUT/health.csv"
start_background_load $((DOWNTIME + 40)) "$OUT/load.log"
sleep 10

echo "$(date -u +%FT%TZ) killing $CONTAINER"
docker kill "$CONTAINER"

sleep "$DOWNTIME"

echo "$(date -u +%FT%TZ) restarting $SVC"
docker compose up -d --no-deps "$SVC"

sleep 20
stop_background_load
stop_monitor

summarize_monitor "$OUT/health.csv" | tee "$OUT/summary.txt"
echo "results in $OUT"
