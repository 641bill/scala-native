#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

JAVA_HOME="$(cs java-home --jvm temurin:17)"
PATH="$JAVA_HOME/bin:$PATH"
RUNS="${GCBENCH_BENCHMARK_RUNS:-5}"

run_case() {
  local label="$1"
  local mode="$2"
  local roots_mode="${3:-}"

  echo
  echo "== $label =="
  if [[ -n "$roots_mode" ]]; then
    SAFEZONE_ROOTS_MODE="$roots_mode" \
    ENABLE_EXPERIMENTAL_COMPILER=1 \
    JAVA_HOME="$JAVA_HOME" \
    PATH="$PATH" \
    GCBENCH_BENCHMARK_RUNS="$RUNS" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"GCBenchRuntimeMatrix\")" \
      "run $mode"
  else
    ENABLE_EXPERIMENTAL_COMPILER=1 \
    JAVA_HOME="$JAVA_HOME" \
    PATH="$PATH" \
    GCBENCH_BENCHMARK_RUNS="$RUNS" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"GCBenchRuntimeMatrix\")" \
      "run $mode"
  fi
}

echo "Running GCBench runtime matrix from $ROOT_DIR"
echo "GCBENCH_BENCHMARK_RUNS=$RUNS"
echo "SAFEZONE_PAGE_SIZE=${SAFEZONE_PAGE_SIZE:-default}"
echo "SAFEZONE_BATCH_SIZE=${SAFEZONE_BATCH_SIZE:-default}"

run_case "Immix heap baseline" "heap"
run_case "Current SafeZone baseline" "safezone" "0"
run_case "Improved SafeZone baseline" "safezone" "1"
run_case "Rift HPZone" "rift-hp"
