#!/usr/bin/env bash
#
# The algorithm benchmarks: no infrastructure, no network, entirely reproducible.
#
#   ./bench/run-algorithms.sh [quick|full]
#
# "quick" is a ~2 minute smoke test of the harness. "full" is what the numbers
# in BENCHMARKS.md come from and takes roughly 25 minutes.
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="${1:-full}"
mkdir -p bench-results

echo "==> building"
mvn -q -B -DskipTests package

echo "==> routing quality (distribution, churn, footprint) - deterministic, no timing"
java -cp benchmarks/target/heimdall-benchmarks.jar \
  com.example.heimdall.bench.report.RoutingQualityReport \
  bench-results/routing-quality.md

echo "==> JMH throughput suite (${MODE})"
java -jar benchmarks/target/heimdall-benchmarks.jar \
  "$MODE" "com.example.heimdall.bench.*" bench-results/jmh-results.json \
  | tee bench-results/jmh-results.txt

echo
echo "==> results in bench-results/"
