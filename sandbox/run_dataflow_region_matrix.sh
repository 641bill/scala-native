#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

operator=${DATAFLOW_OPERATOR:-all}

run_mode() {
  local label="$1"
  local mode="$2"

  echo
  echo "== ${label} =="
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"DataflowRegionMatrix\")" \
    "run ${mode} ${operator}"
}

cd "${repo_dir}"

run_mode "Immix heap" "heap"
run_mode "Rift HPZone" "rift-hp"
run_mode "Rift Streaming" "rift-streaming"
