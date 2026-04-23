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

run_mode() {
  local label="$1"
  local mode="$2"

  echo
  echo "== ${label} =="
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

run_mode "Immix heap baseline" "heap"
SAFEZONE_ROOTS_MODE=0 run_mode "Current SafeZone pipeline" "safezone"
SAFEZONE_ROOTS_MODE=1 run_mode "Improved SafeZone pipeline" "safezone"
run_mode "Rift HPZone pipeline" "rift-hp"
run_mode "Rift Streaming pipeline" "rift-streaming"
