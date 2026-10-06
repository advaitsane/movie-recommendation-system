#!/usr/bin/env bash
# Stops a shared dependency container mid-traffic, holds it down for a while, restarts it,
# and records app-service health throughout. Usage:
#   ./01_dependency_kill.sh <mongo|postgres|kafka|mflix-mongo> [downtime_seconds]
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

TARGET="${1:?usage: 01_dependency_kill.sh <mongo|postgres|kafka|mflix-mongo> [downtime_seconds]}"
DOWNTIME="${2:-30}"
TS=$(date +%Y%m%dT%H%M%S)
OUT="results/01_dependency_kill_${TARGET}_${TS}"
mkdir -p "$OUT"

case "$TARGET" in
  mongo|postgres|kafka)
    CONTAINER="movie-recommendation-system-${TARGET}-1"
    STOP_CMD="docker stop $CONTAINER"
    START_CMD="docker compose up -d --no-deps $TARGET"
    ;;
  mflix-mongo)
    CONTAINER="mflix-mongo"
    STOP_CMD="docker stop $CONTAINER"
    START_CMD="docker start $CONTAINER"
    ;;
  *)
    echo "unknown target: $TARGET (expected mongo|postgres|kafka|mflix-mongo)"; exit 1 ;;
esac

echo "=== chaos experiment: kill dependency '$TARGET' for ${DOWNTIME}s ==="
start_monitor 2 "$OUT/health.csv"
start_background_load $((DOWNTIME + 40)) "$OUT/load.log"
sleep 10

echo "$(date -u +%FT%TZ) stopping $CONTAINER"
eval "$STOP_CMD"

sleep "$DOWNTIME"

echo "$(date -u +%FT%TZ) restarting $CONTAINER"
eval "$START_CMD"

sleep 20
stop_background_load
stop_monitor

summarize_monitor "$OUT/health.csv" | tee "$OUT/summary.txt"
echo "results in $OUT"
