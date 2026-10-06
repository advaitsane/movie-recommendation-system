#!/usr/bin/env bash
# Injects a network fault against one container and records health of every service throughout.
# Modes:
#   delay <container> [ms] [duration_s]        - adds latency via pumba/netem (no in-container tools needed)
#   loss <container> [percent] [duration_s]     - drops packets via pumba/netem
#   partition <container> [duration_s]          - fully disconnects the container from the compose network
#
# Usage:
#   ./03_network_fault.sh delay search-service 3000 30
#   ./03_network_fault.sh loss recommendation-service 50 30
#   ./03_network_fault.sh partition search-service 30
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

MODE="${1:?usage: 03_network_fault.sh <delay|loss|partition> <container-short-name> [param] [duration_s]}"
SVC="${2:?container short name required, e.g. search-service}"
NETWORK="movie-recommendation-system_default"
CONTAINER="movie-recommendation-system-${SVC}-1"

case "$MODE" in
  delay)
    MS="${3:-3000}"
    DOWNTIME="${4:-30}"
    ;;
  loss)
    PCT="${3:-50}"
    DOWNTIME="${4:-30}"
    ;;
  partition)
    DOWNTIME="${3:-30}"
    ;;
  *)
    echo "unknown mode: $MODE (expected delay|loss|partition)"; exit 1 ;;
esac

TS=$(date +%Y%m%dT%H%M%S)
OUT="results/03_network_${MODE}_${SVC}_${TS}"
mkdir -p "$OUT"

echo "=== chaos experiment: network $MODE on '$SVC' for ${DOWNTIME}s ==="
start_monitor 2 "$OUT/health.csv"
start_background_load $((DOWNTIME + 40)) "$OUT/load.log"
sleep 10

echo "$(date -u +%FT%TZ) injecting $MODE on $CONTAINER"
case "$MODE" in
  delay)
    docker run --rm -d --name pumba_chaos_run -v /var/run/docker.sock:/var/run/docker.sock \
      gaiaadm/pumba pumba netem --duration "${DOWNTIME}s" delay --time "$MS" "$CONTAINER" > "$OUT/pumba.log" 2>&1
    ;;
  loss)
    docker run --rm -d --name pumba_chaos_run -v /var/run/docker.sock:/var/run/docker.sock \
      gaiaadm/pumba pumba netem --duration "${DOWNTIME}s" loss --percent "$PCT" "$CONTAINER" > "$OUT/pumba.log" 2>&1
    ;;
  partition)
    docker network disconnect "$NETWORK" "$CONTAINER"
    ;;
esac

sleep "$DOWNTIME"

if [[ "$MODE" == "partition" ]]; then
  echo "$(date -u +%FT%TZ) reconnecting $CONTAINER to $NETWORK"
  docker network connect "$NETWORK" "$CONTAINER"
fi

sleep 20
stop_background_load
stop_monitor

summarize_monitor "$OUT/health.csv" | tee "$OUT/summary.txt"
echo "results in $OUT"
