#!/usr/bin/env bash
# Shared helpers for the chaos/*.sh experiment scripts. Source, don't execute directly.
set -uo pipefail

SERVICES=(api-gateway:8080 catalog-service:8081 search-service:8082 review-service:8083 recommendation-service:8084 user-service:8085)

# Polls every app service's /actuator/health every $1 seconds, appending
# "epoch,service,http_code,latency_ms" rows to $2 until stop_monitor is called.
start_monitor() {
  local interval="$1" outfile="$2"
  : > "$outfile"
  (
    set +e  # this subshell inherits errexit from the caller; never let one bad tick kill it
    while true; do
      local ts
      ts=$(date +%s)
      for entry in "${SERVICES[@]}"; do
        local name="${entry%%:*}" port="${entry##*:}"
        local t0 code
        t0=$(python3 -c 'import time; print(int(time.time()*1000))')
        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://localhost:${port}/actuator/health" 2>/dev/null || echo "000")
        local t1
        t1=$(python3 -c 'import time; print(int(time.time()*1000))')
        echo "${ts},${name},${code},$((t1 - t0))" >> "$outfile"
      done
      sleep "$interval"
    done
  ) &
  MONITOR_PID=$!
  echo "monitor started, pid=$MONITOR_PID -> $outfile"
}

stop_monitor() {
  if [[ -n "${MONITOR_PID:-}" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
    echo "monitor stopped (pid=$MONITOR_PID)"
  fi
}

# Runs the k6 smoke journey in a loop in the background for roughly $1 seconds
# so there's live traffic during the experiment. Writes k6 stdout to $2.
start_background_load() {
  local duration="$1" outfile="$2"
  (
    set +e  # this subshell inherits errexit from the caller; k6 exits non-zero on threshold
            # breaches, which is expected during a fault-injection window, not a reason to stop
    local end=$(( $(date +%s) + duration ))
    while [[ $(date +%s) -lt $end ]]; do
      SMOKE=1 k6 run "$(dirname "${BASH_SOURCE[0]}")/../loadtest/user-journey.js" >> "$outfile" 2>&1
      sleep 1
    done
  ) &
  LOAD_PID=$!
  echo "background load started, pid=$LOAD_PID -> $outfile"
}

stop_background_load() {
  if [[ -n "${LOAD_PID:-}" ]]; then
    kill "$LOAD_PID" 2>/dev/null || true
    pkill -P "$LOAD_PID" 2>/dev/null || true
    wait "$LOAD_PID" 2>/dev/null || true
    echo "background load stopped (pid=$LOAD_PID)"
  fi
}

# Prints a summary of a monitor CSV: first/last non-200 per service, and total
# downtime windows.
summarize_monitor() {
  local infile="$1"
  echo "--- summary: $infile ---"
  for entry in "${SERVICES[@]}"; do
    local name="${entry%%:*}"
    local bad
    bad=$(awk -F, -v s="$name" '$2==s && $3!=200' "$infile")
    if [[ -z "$bad" ]]; then
      echo "$name: no unhealthy readings"
    else
      local first last count
      first=$(echo "$bad" | head -1 | cut -d, -f1)
      last=$(echo "$bad" | tail -1 | cut -d, -f1)
      count=$(echo "$bad" | wc -l | tr -d ' ')
      echo "$name: $count unhealthy readings, first=$first last=$last (epoch)"
    fi
  done
}
