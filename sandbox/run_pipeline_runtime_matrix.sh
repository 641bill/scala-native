#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

runs=${PIPELINE_BENCHMARK_RUNS:-5}
warmups=${PIPELINE_WARMUPS:-1}
size=${PIPELINE_SIZE:-2000000}
workers=${PIPELINE_WORKERS:-4}
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}

run_mode() {
  local label="$1"
  local mode="$2"
  local roots_mode="${3:-${SAFEZONE_ROOTS_MODE:-}}"
  local page_size="${4:-${SAFEZONE_PAGE_SIZE:-}}"

  echo
  echo "== ${label} =="
  SAFEZONE_ROOTS_MODE="${roots_mode}" \
  SAFEZONE_PAGE_SIZE="${page_size}" \
  PIPELINE_BENCHMARK_RUNS="${runs}" \
  PIPELINE_WARMUPS="${warmups}" \
  PIPELINE_SIZE="${size}" \
  PIPELINE_WORKERS="${workers}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"PipelineRuntimeMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

modes=(${(z)${PIPELINE_MODES:-"heap improved-safezone"}})
if [[ -z "${PIPELINE_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp rift-streaming)
fi

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap)
      run_mode "Immix heap baseline" "heap"
      ;;
    current-safezone)
      run_mode "Current SafeZone pipeline" "safezone" "0"
      ;;
    improved-safezone)
      run_mode "Improved SafeZone pipeline" "safezone" "1"
      ;;
    unsafezone-hp)
      run_mode "UnsafeZone-HP pipeline" "safezone" "3" "32768"
      ;;
    rift-hp)
      run_mode "Rift HPZone pipeline" "rift-hp"
      ;;
    rift-streaming)
      run_mode "Rift Streaming pipeline" "rift-streaming"
      ;;
    *)
      echo "unknown PIPELINE_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done
