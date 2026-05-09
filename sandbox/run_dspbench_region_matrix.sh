#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
parent_dir=${repo_dir:h}
default_spike_input="${parent_dir}/cache/benchmark-data/dspbench/source/dspbench-threads/data/sensors.dat"
default_fraud_input="${parent_dir}/cache/benchmark-data/dspbench/source/dspbench-threads/data/credit-card.dat"
default_log_input="${parent_dir}/cache/benchmark-data/dspbench/source/dspbench-spark/data/logprocessing/http-server.log"
output_dir=${DSPBENCH_OUTPUT_DIR:-"/tmp/dspbench-region-matrix"}
summary=${DSPBENCH_SUMMARY:-"${output_dir}/summary.tsv"}
build=${DSPBENCH_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${DSPBENCH_MODES:-"heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token"}})
if [[ -z "${DSPBENCH_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(safezone-rootless-32k rift-trusted-hp rift-checked-page-token)
fi
queries=(${(z)${DSPBENCH_QUERIES:-"q0-parse q1-moving-average q2-spike-window"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"DSPBenchRegionMatrix\")" \
    nativeLink
fi

binary=${DSPBENCH_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/DSPBenchRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing DSPBenchRegionMatrix native binary; set DSPBENCH_BINARY or enable DSPBENCH_BUILD" >&2
  exit 1
fi

read_max_rss_bytes() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/maximum resident set size/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/Maximum resident set size/ { gsub(/^[ \t]+/, "", $2); print $2 * 1024; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

write_summary_header() {
  printf "query\tmode\theap_cap\tstatus\tinput\tinput_mode\tloaded_events\tunique_input_lines\tinput_replays\tinput_files\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local query="$1"
  local mode="$2"
  local heap_cap="$3"
  local run_status="$4"
  local binary_mode="$5"
  local run_log="$6"
  local max_rss_bytes="$7"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=dspbench-${query}-${binary_mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${fields[input]-}" \
    "${fields[input_mode]-}" \
    "${fields[loaded_events]-}" \
    "${fields[unique_input_lines]-}" \
    "${fields[input_replays]-}" \
    "${fields[input_files]-}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[max_gc_ms]-}" \
    "${fields[runs_with_gc]-}" \
    "${fields[max_gc_collections]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[checksum]-}" \
    "${fields[output_count]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

write_failed_row() {
  local query="$1"
  local mode="$2"
  local heap_cap="$3"
  local run_status="$4"
  local max_rss_bytes="$5"

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "${max_rss_bytes}" >> "${summary}"
}

run_case() {
  local query="$1"
  local mode="$2"
  local heap_cap="$3"
  local binary_mode="${mode}"
  local roots_mode=""
  local page_size="${SAFEZONE_PAGE_SIZE:-}"
  local safe_heap_cap="${heap_cap//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${query}-${mode}-${safe_heap_cap}.log"
  local time_log="${output_dir}/time-${query}-${mode}-${safe_heap_cap}.log"
  local max_rss_bytes
  local command_status
  local -a env_args
  local query_input="${DSPBENCH_INPUT:-}"
  local query_input_mode="${DSPBENCH_INPUT_MODE:-}"

  case "${mode}" in
    gc-heap|heap-immix)
      binary_mode="heap"
      ;;
    heap-direct-summary-only|heap-direct-epoch|heap-same-shape-direct-epoch|heap-direct-aggregate)
      binary_mode="heap-direct-epoch"
      ;;
    heap-epoch-retained-no-traverse)
      binary_mode="heap-epoch-retained-no-traverse"
      ;;
    safezone-current)
      binary_mode="safezone"
      roots_mode="0"
      ;;
    region-scoped-rooted|safezone-improved|safezone-improved-32k)
      binary_mode="safezone"
      roots_mode="1"
      page_size="32768"
      ;;
    region-scoped-rootless|safezone-rootless-32k|unsafezone-hp)
      binary_mode="safezone"
      roots_mode="3"
      page_size="32768"
      ;;
    region-hp-rootless|rift-trusted-hp)
      binary_mode="rift-hp"
      ;;
    region-stream-rootless|rift-trusted-streaming)
      binary_mode="rift-streaming"
      ;;
    checked-region-stream|rift-checked-page-token)
      binary_mode="rift-checked-page-token"
      ;;
    checked-region-scoped|rift-checked-safezone-page-token)
      binary_mode="rift-checked-safezone-page-token"
      roots_mode="1"
      page_size="32768"
      ;;
    checked-epoch-stream|checked-region-stream-epoch|rift-checked-direct-epoch)
      binary_mode="rift-checked-direct-epoch"
      ;;
    checked-epoch-scoped|checked-region-scoped-epoch|rift-checked-safezone-direct-epoch)
      binary_mode="rift-checked-safezone-direct-epoch"
      roots_mode="1"
      page_size="32768"
      ;;
    checked-epoch-retained-no-traverse|checked-region-stream-retained-epoch)
      binary_mode="checked-epoch-retained-no-traverse"
      ;;
    checked-scoped-epoch-retained-no-traverse|checked-region-scoped-retained-epoch)
      binary_mode="checked-scoped-epoch-retained-no-traverse"
      roots_mode="1"
      page_size="32768"
      ;;
  esac

  env_args=()
  if [[ -n "${roots_mode}" ]]; then
    env_args+=(SAFEZONE_ROOTS_MODE="${roots_mode}")
  fi
  if [[ -n "${page_size}" ]]; then
    env_args+=(SAFEZONE_PAGE_SIZE="${page_size}")
  fi
  if [[ -n "${heap_cap}" && "${heap_cap}" != "uncapped" ]]; then
    env_args+=(GC_MAXIMUM_HEAP_SIZE="${heap_cap}")
  fi
  if [[ -z "${query_input}" && "${binary_mode}" != "heap-direct-epoch" && "${binary_mode}" != "heap-epoch-retained-no-traverse" && "${binary_mode}" != "checked-epoch-retained-no-traverse" && "${binary_mode}" != "checked-scoped-epoch-retained-no-traverse" ]]; then
    if [[ "${query}" == fraud-* && -e "${default_fraud_input}" ]]; then
      query_input="${default_fraud_input}"
    elif [[ "${query}" == log-* && -e "${default_log_input}" ]]; then
      query_input="${default_log_input}"
    elif [[ -e "${default_spike_input}" ]]; then
      query_input="${default_spike_input}"
    fi
  fi
  if [[ -n "${query_input}" ]]; then
    env_args+=(DSPBENCH_INPUT="${query_input}")
    if [[ -z "${query_input_mode}" ]]; then
      query_input_mode="file-backed"
    fi
  fi
  if [[ -n "${query_input_mode}" ]]; then
    env_args+=(DSPBENCH_INPUT_MODE="${query_input_mode}")
  fi

  echo
  echo "== ${query} / ${mode} heap_cap=${heap_cap} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    env "${env_args[@]}" /usr/bin/time -l "${binary}" "${binary_mode}" "${query}" > "${run_log}" 2> "${time_log}"
  else
    env "${env_args[@]}" /usr/bin/time -v "${binary}" "${binary_mode}" "${query}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  if ! grep -q "^RESULT name=dspbench-${query}-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    max_rss_bytes=$(read_max_rss_bytes "${time_log}")
    echo "DSPBENCH_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${query}" "${mode}" "${heap_cap}" "failed:${command_status}" "${max_rss_bytes}"
    return 0
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=dspbench-${query}-${binary_mode} " "${run_log}"
  echo "DSPBENCH_RSS_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${query}" "${mode}" "${heap_cap}" "ok" "${binary_mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header

heap_caps=(${(z)${DSPBENCH_HEAP_CAPS:-"uncapped"}})
for query in "${queries[@]}"; do
  for mode in "${modes[@]}"; do
    if [[ "${mode}" == "heap-immix" || "${mode}" == "gc-heap" ]]; then
      for heap_cap in "${heap_caps[@]}"; do
        run_case "${query}" "${mode}" "${heap_cap}"
      done
    else
      run_case "${query}" "${mode}" "uncapped"
    fi
  done
done

echo
echo "Wrote ${summary}"
