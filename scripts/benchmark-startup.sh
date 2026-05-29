#!/usr/bin/env bash
# Benchmark startup time and memory footprint for klag variants.
#
#   - startup time : launch -> "Klag started successfully" log line
#   - RSS          : steady state, ~3s after ready
#
# Requires a reachable Kafka (startup blocks on describeCluster):
#   docker compose up -d kafka
#
# Usage:
#   scripts/benchmark-startup.sh <label> <java-home|-> <jar-or-binary> [base-port]
#     java-home "-" => run target as a native binary
set -euo pipefail

LABEL="${1:?label}"
JAVA_HOME_ARG="${2:?java-home or -}"
TARGET="${3:?target}"
BASE_PORT="${4:-8900}"
RUNS="${RUNS:-3}"

export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
export METRICS_REPORTER="${METRICS_REPORTER:-prometheus}"
export LOG_LEVEL_KLAG=INFO
marker="Klag started successfully"

run_once() {
  local port="$1" logf pid start end rss
  logf="$(mktemp)"
  export HTTP_PORT="$port"
  if [[ "$JAVA_HOME_ARG" == "-" ]]; then
    "$TARGET" >"$logf" 2>&1 &
  else
    "$JAVA_HOME_ARG/bin/java" ${JAVA_OPTS:-} -jar "$TARGET" >"$logf" 2>&1 &
  fi
  pid=$!
  start=$(python3 -c 'import time;print(int(time.time()*1000))')
  while :; do
    grep -q "$marker" "$logf" && break
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "DIED" >&2; tail -5 "$logf" >&2; rm -f "$logf"; return 1
    fi
  done
  end=$(python3 -c 'import time;print(int(time.time()*1000))')
  sleep 3
  rss=$(ps -o rss= -p "$pid" | tr -d ' ')
  kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true
  rm -f "$logf"
  echo "$((end - start)) ${rss:-0}"
}

echo "== $LABEL ($RUNS runs) =="
tms=0; trss=0
for i in $(seq 1 "$RUNS"); do
  res=$(run_once "$((BASE_PORT + i))")
  ms=${res% *}; rss=${res#* }
  printf "  run %d: startup=%5d ms  rss=%6d KB (%d MB)\n" "$i" "$ms" "$rss" "$((rss/1024))"
  tms=$((tms+ms)); trss=$((trss+rss))
done
printf "  AVG  : startup=%5d ms  rss=%6d KB (%d MB)\n" "$((tms/RUNS))" "$((trss/RUNS))" "$((trss/RUNS/1024))"
