#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${NEXMARK_OUTPUT_DIR:-"/tmp/nexmark-region-matrix"}
summary=${NEXMARK_SUMMARY:-"${output_dir}/summary.tsv"}
build=${NEXMARK_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${NEXMARK_MODES:-"heap safezone-improved rift-checked"}})
if [[ -z "${NEXMARK_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(safezone-current unsafezone-hp rift-hp rift-streaming)
fi
queries=(${(z)${NEXMARK_QUERIES:-"q0 q1 q2 q3 q4 q5 q8 q9 q11"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"NexmarkRegionMatrix\")" \
    nativeLink
fi

binary=${NEXMARK_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/NexmarkRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing NexmarkRegionMatrix native binary; set NEXMARK_BINARY or enable NEXMARK_BUILD" >&2
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
  printf "query\tmode\theap_cap\tstatus\tinput\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
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

  line=$(grep "^RESULT name=nexmark-${query}-${binary_mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${fields[input]-}" \
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

  printf "%s\t%s\t%s\t%s\t\t\t\t\t\t\t\t\t\t\t\t\t\t%s\n" \
    "${query}" "${mode}" "${heap_cap}" "${run_status}" "${max_rss_bytes}" >> "${summary}"
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

  case "${mode}" in
    heap-immix)
      binary_mode="heap"
      ;;
    safezone-current)
      binary_mode="safezone"
      roots_mode="0"
      ;;
    safezone-improved)
      binary_mode="safezone"
      roots_mode="1"
      ;;
    unsafezone-hp)
      binary_mode="safezone"
      roots_mode="3"
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

  if ! grep -q "^RESULT name=nexmark-${query}-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    max_rss_bytes=$(read_max_rss_bytes "${time_log}")
    echo "NEXMARK_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${query}" "${mode}" "${heap_cap}" "failed:${command_status}" "${max_rss_bytes}"
    return 0
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=nexmark-${query}-${binary_mode} " "${run_log}"
  echo "NEXMARK_RSS_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${query}" "${mode}" "${heap_cap}" "ok" "${binary_mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header
for query in "${queries[@]}"; do
  for mode in "${modes[@]}"; do
    if [[ ("${mode}" == "heap" || "${mode}" == "heap-immix") && -n "${NEXMARK_HEAP_CAPS:-}" ]]; then
      heap_caps=(${(z)${NEXMARK_HEAP_CAPS}})
      for heap_cap in "${heap_caps[@]}"; do
        run_case "${query}" "${mode}" "${heap_cap}"
      done
    elif [[ "${mode}" != "heap" && "${mode}" != "heap-immix" && -n "${NEXMARK_REGION_HEAP_CAPS:-}" ]]; then
      region_caps=(${(z)${NEXMARK_REGION_HEAP_CAPS}})
      for heap_cap in "${region_caps[@]}"; do
        run_case "${query}" "${mode}" "${heap_cap}"
      done
    else
      run_case "${query}" "${mode}" "${NEXMARK_HEAP_CAP:-uncapped}"
    fi
  done
done

echo
echo "NEXMark region matrix complete"
echo "Summary: ${summary}"
