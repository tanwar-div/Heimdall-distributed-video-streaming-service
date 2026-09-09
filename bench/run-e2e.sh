#!/usr/bin/env bash
#
# Brings up Heimdall plus every comparison target, waits for all of them to be
# genuinely serving, runs the end-to-end benchmark, and writes the markdown
# results. Leaves the stack running so a failed run can be investigated;
# pass --down to tear it down afterwards.
#
#   ./bench/run-e2e.sh [objectSizeMiB] [measureSeconds]
#
set -euo pipefail

cd "$(dirname "$0")/.."

OBJECT_SIZE_MIB="${1:-64}"
MEASURE_SECONDS="${2:-15}"
RESULTS="bench-results/e2e-results.md"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.bench.yml)

# Every target must answer a real request before anything is timed - a container
# that is "running" is not the same as a service that is serving, and starting a
# benchmark against a still-initialising node produces numbers that look like a
# performance result and are actually a startup artifact.
wait_for() {
  local name="$1" url="$2" attempts="${3:-60}"
  printf '[wait] %-14s ' "$name"
  for _ in $(seq "$attempts"); do
    if curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null; then
      echo "ready"
      return 0
    fi
    sleep 2
  done
  echo "NOT READY after $((attempts * 2))s ($url)"
  return 1
}

echo "==> building and starting the full stack"
"${COMPOSE[@]}" up -d --build

echo "==> waiting for services"
wait_for gateway    "http://localhost:8080/actuator/health"
wait_for minio      "http://localhost:9000/minio/health/live"
wait_for nginx      "http://localhost:8090/"        || true
wait_for seaweedfs  "http://localhost:8333/"        || true

echo "==> building the benchmark jar"
mvn -q -B -DskipTests package

echo "==> running the end-to-end benchmark (${OBJECT_SIZE_MIB} MiB object, ${MEASURE_SECONDS}s per cell)"
mkdir -p bench-results
java -cp benchmarks/target/heimdall-benchmarks.jar \
  com.example.heimdall.bench.e2e.EndToEndBenchmark \
  "$OBJECT_SIZE_MIB" "$MEASURE_SECONDS" "$RESULTS"

echo
echo "==> results written to $RESULTS"

if [[ "${3:-}" == "--down" ]]; then
  echo "==> tearing down"
  "${COMPOSE[@]}" down -v
fi
