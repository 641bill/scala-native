#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

runs=${GCBENCH_BENCHMARK_RUNS:-5}
page_size=${SAFEZONE_PAGE_SIZE:-4096}
batch_size=${SAFEZONE_BATCH_SIZE:-1}

run_mode() {
  local label="$1"
  local mode="$2"
  local roots_mode="${3:-${SAFEZONE_ROOTS_MODE:-}}"
  local mode_page_size="${4:-${page_size}}"

  echo
  echo "== ${label} =="
  GCBENCH_BENCHMARK_RUNS="${runs}" \
  SAFEZONE_ROOTS_MODE="${roots_mode}" \
  SAFEZONE_PAGE_SIZE="${mode_page_size}" \
  SAFEZONE_BATCH_SIZE="${batch_size}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"GCBenchTopologyMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

run_mode "Immix heap baseline" "heap"
run_mode "Current SafeZone topology A" "topology-a" "0"
run_mode "Current SafeZone topology B" "topology-b" "0"
run_mode "Improved SafeZone topology A" "topology-a" "1"
run_mode "Improved SafeZone topology B" "topology-b" "1"
run_mode "UnsafeZone-HP topology A" "topology-a" "3" "32768"
run_mode "UnsafeZone-HP topology B" "topology-b" "3" "32768"
