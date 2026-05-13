#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

operator=${DATAFLOW_OPERATOR:-all}
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${DATAFLOW_MODES:-"gc-heap region-scoped-rooted checked-region-stream checked-page-token checked-region-scoped-page-token checked-region-scoped-epoch"}})
if [[ -z "${DATAFLOW_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp rift-streaming checked-epoch-fold checked-epoch-stream)
fi

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

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap|gc-heap|heap-immix)
      run_mode "Immix heap" "heap"
      ;;
    current-safezone|safezone-current)
      run_mode "Current SafeZone" "safezone" "0"
      ;;
    improved-safezone|safezone-improved|safezone-improved-32k|region-scoped-rooted)
      run_mode "Improved SafeZone" "safezone" "1"
      ;;
    unsafezone-hp|safezone-rootless-32k|region-scoped-rootless)
      run_mode "UnsafeZone-HP" "safezone" "3" "32768"
      ;;
    rift-hp|rift-trusted-hp|region-hp-rootless)
      run_mode "Rift HPZone" "rift-hp"
      ;;
    rift-streaming|rift-trusted-streaming|region-stream-rootless)
      run_mode "Rift Streaming" "rift-streaming"
      ;;
    rift-checked|checked-region-stream)
      run_mode "Rift checked RegionBuffer" "rift-checked"
      ;;
    checked-page-token|checked-page-token-stream|checked-region-stream-page-token)
      run_mode "Checked page-token SELECT" "checked-page-token" "" "" "select"
      ;;
    checked-page-token-scoped|checked-region-scoped-page-token)
      run_mode "Checked scoped page-token SELECT" "checked-page-token-scoped" "1" "32768" "select"
      ;;
    checked-epoch-fold|checked-region-stream-epoch-fold)
      run_mode "Checked epoch-fold AGGREGATE" "checked-epoch-fold" "" "" "aggregate"
      ;;
    checked-epoch-stream|checked-region-stream-epoch)
      run_mode "Checked direct epoch stream" "checked-epoch-stream"
      ;;
    checked-epoch-stream-legacy|checked-region-stream-epoch-legacy)
      run_mode "Checked direct epoch stream legacy" "checked-epoch-stream-legacy"
      ;;
    checked-epoch-stream-open-handle|checked-region-stream-epoch-open-handle)
      run_mode "Checked direct epoch stream open handle" "checked-epoch-stream-open-handle"
      ;;
    checked-epoch-scoped|checked-region-scoped-epoch)
      run_mode "Checked direct epoch scoped" "checked-epoch-scoped" "1" "32768"
      ;;
    *)
      echo "unknown DATAFLOW_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done
