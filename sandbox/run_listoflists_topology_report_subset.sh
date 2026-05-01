#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

runs=${LISTBENCH_BENCHMARK_RUNS:-3}
n=${LISTBENCH_N:-3000}
structures=${LISTBENCH_STRUCTURES:-40}

run_mode() {
  local label="$1"
  local mode="$2"
  local roots_mode="${3:-${SAFEZONE_ROOTS_MODE:-}}"
  local page_size="${4:-${SAFEZONE_PAGE_SIZE:-}}"

  echo
  echo "== ${label} =="
  SAFEZONE_ROOTS_MODE="${roots_mode}" \
  SAFEZONE_PAGE_SIZE="${page_size}" \
  LISTBENCH_N="${n}" \
  LISTBENCH_STRUCTURES="${structures}" \
  LISTBENCH_BENCHMARK_RUNS="${runs}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"ListOfListsTopologyMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

run_mode "Immix heap" "heap"

if [[ "${LISTBENCH_INCLUDE_CURRENT_SAFEZONE_ONE:-0}" == "1" ]]; then
  run_mode "Current SafeZone one-region" "safezone-one" "0"
else
  echo
  echo "== Current SafeZone one-region =="
  echo "skipped: use LISTBENCH_INCLUDE_CURRENT_SAFEZONE_ONE=1 to rerun the known slow path"
fi

run_mode "Current SafeZone nested" "safezone-nested" "0"
run_mode "Current SafeZone mixed rooted heap-values" "safezone-mixed" "0"

run_mode "Improved SafeZone one-region" "safezone-one" "1"
run_mode "Improved SafeZone nested" "safezone-nested" "1"
run_mode "Improved SafeZone mixed rooted heap-values" "safezone-mixed" "1"

run_mode "UnsafeZone-HP one-region" "safezone-one" "3" "32768"
run_mode "UnsafeZone-HP nested" "safezone-nested" "3" "32768"
run_mode "UnsafeZone-HP mixed rooted heap-values" "safezone-mixed" "3" "32768"

run_mode "Rift HPZone one-region" "rift-one"
run_mode "Rift HPZone nested" "rift-nested"
run_mode "Rift HPZone mixed rooted heap-values" "rift-mixed"
