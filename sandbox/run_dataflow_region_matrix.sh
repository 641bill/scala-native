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
  local roots_mode="${3:-${SAFEZONE_ROOTS_MODE:-}}"
  local page_size="${4:-${SAFEZONE_PAGE_SIZE:-}}"
  local mode_operator="${5:-${operator}}"

  echo
  echo "== ${label} =="
  SAFEZONE_ROOTS_MODE="${roots_mode}" \
  SAFEZONE_PAGE_SIZE="${page_size}" \
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"DataflowRegionMatrix\")" \
    "run ${mode} ${mode_operator}"
}

cd "${repo_dir}"

run_mode "Immix heap" "heap"
run_mode "Current SafeZone" "safezone" "0"
run_mode "Improved SafeZone" "safezone" "1"
run_mode "UnsafeZone-HP" "safezone" "3" "32768"
run_mode "Rift HPZone" "rift-hp"
run_mode "Rift Streaming" "rift-streaming"
run_mode "Rift checked RegionBuffer" "rift-checked"
run_mode "Checked page-token SELECT" "checked-page-token" "" "" "select"
run_mode "Checked scoped page-token SELECT" "checked-page-token-scoped" "1" "32768" "select"
run_mode "Checked epoch-fold AGGREGATE" "checked-epoch-fold" "" "" "aggregate"
