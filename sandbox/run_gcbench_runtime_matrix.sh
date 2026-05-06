#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

JAVA_HOME="$(cs java-home --jvm temurin:17)"
PATH="$JAVA_HOME/bin:$PATH"
RUNS="${GCBENCH_BENCHMARK_RUNS:-5}"
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}

run_case() {
  local label="$1"
  local mode="$2"
  local roots_mode="${3:-}"
  local page_size="${4:-}"

  echo
  echo "== $label =="
  if [[ -n "$roots_mode" && -n "$page_size" ]]; then
    SAFEZONE_ROOTS_MODE="$roots_mode" \
    SAFEZONE_PAGE_SIZE="$page_size" \
    ENABLE_EXPERIMENTAL_COMPILER=1 \
    JAVA_HOME="$JAVA_HOME" \
    PATH="$PATH" \
    GCBENCH_BENCHMARK_RUNS="$RUNS" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"GCBenchRuntimeMatrix\")" \
      "run $mode"
  elif [[ -n "$roots_mode" ]]; then
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

modes=(${(z)${GCBENCH_MODES:-"heap improved-safezone"}})
if [[ -z "${GCBENCH_MODES:-}" && ( "$include_controls" == "1" || "$include_controls" == "true" || "$include_controls" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp)
fi

for selected_mode in "${modes[@]}"; do
  case "$selected_mode" in
    heap)
      run_case "Immix heap baseline" "heap"
      ;;
    current-safezone)
      run_case "Current SafeZone baseline" "safezone" "0"
      ;;
    improved-safezone)
      run_case "Improved SafeZone baseline" "safezone" "1"
      ;;
    unsafezone-hp)
      run_case "UnsafeZone-HP baseline" "safezone" "3" "32768"
      ;;
    rift-hp)
      run_case "Rift HPZone" "rift-hp"
      ;;
    *)
      echo "unknown GCBENCH_MODES entry: $selected_mode" >&2
      exit 1
      ;;
  esac
done
