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

  echo
  echo "== ${label} =="
  GCBENCH_BENCHMARK_RUNS="${runs}" \
  SAFEZONE_PAGE_SIZE="${page_size}" \
  SAFEZONE_BATCH_SIZE="${batch_size}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"GCBenchTopologyMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

run_mode "Immix heap baseline" "heap"
SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone topology A" "topology-a"
SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone topology B" "topology-b"
SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone topology A" "topology-a"
SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone topology B" "topology-b"
