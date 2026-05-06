#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

runs=${LISTBENCH_BENCHMARK_RUNS:-3}
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
  LISTBENCH_BENCHMARK_RUNS="${runs}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"ListOfListsTopologyMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

modes=(${(z)${LISTBENCH_TOPOLOGY_MODES:-"heap improved-one improved-nested improved-mixed"}})
if [[ -z "${LISTBENCH_TOPOLOGY_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-one current-nested current-mixed unsafe-one unsafe-nested unsafe-mixed rift-one rift-nested rift-mixed)
fi

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap)
      run_mode "Immix heap" "heap"
      ;;
    current-one)
      run_mode "Current SafeZone one-region" "safezone-one" "0"
      ;;
    current-nested)
      run_mode "Current SafeZone nested" "safezone-nested" "0"
      ;;
    current-mixed)
      run_mode "Current SafeZone mixed rooted heap-values" "safezone-mixed" "0"
      ;;
    improved-one)
      run_mode "Improved SafeZone one-region" "safezone-one" "1"
      ;;
    improved-nested)
      run_mode "Improved SafeZone nested" "safezone-nested" "1"
      ;;
    improved-mixed)
      run_mode "Improved SafeZone mixed rooted heap-values" "safezone-mixed" "1"
      ;;
    unsafe-one)
      run_mode "UnsafeZone-HP one-region" "safezone-one" "3" "32768"
      ;;
    unsafe-nested)
      run_mode "UnsafeZone-HP nested" "safezone-nested" "3" "32768"
      ;;
    unsafe-mixed)
      run_mode "UnsafeZone-HP mixed rooted heap-values" "safezone-mixed" "3" "32768"
      ;;
    rift-one)
      run_mode "Rift HPZone one-region" "rift-one"
      ;;
    rift-nested)
      run_mode "Rift HPZone nested" "rift-nested"
      ;;
    rift-mixed)
      run_mode "Rift HPZone mixed rooted heap-values" "rift-mixed"
      ;;
    *)
      echo "unknown LISTBENCH_TOPOLOGY_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done
