#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

runs=${LISTBENCH_BENCHMARK_RUNS:-3}

run_mode() {
  local label="$1"
  local mode="$2"

  echo
  echo "== ${label} =="
  LISTBENCH_BENCHMARK_RUNS="${runs}" \
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"ListOfListsTopologyMatrix\")" \
      "run ${mode}"
}

cd "${repo_dir}"

run_mode "Immix heap" "heap"

SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone one-region" "safezone-one"
SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone nested" "safezone-nested"
SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone mixed rooted heap-values" "safezone-mixed"

SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone one-region" "safezone-one"
SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone nested" "safezone-nested"
SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone mixed rooted heap-values" "safezone-mixed"

run_mode "Rift HPZone one-region" "rift-one"
run_mode "Rift HPZone nested" "rift-nested"
run_mode "Rift HPZone mixed rooted heap-values" "rift-mixed"
