#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${OBJECT_ALLOC_OUTPUT_DIR:-"/tmp/object-allocation-lowering"}
summary=${OBJECT_ALLOC_SUMMARY:-"${output_dir}/summary.tsv"}
build=${OBJECT_ALLOC_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${OBJECT_ALLOC_MODES:-"heap-immix rift-checked-rift rift-checked-safezone-improved-32k"}})
if [[ -z "${OBJECT_ALLOC_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(rift-trusted-hp rift-trusted-streaming)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"ObjectAllocationLoweringMatrix\")" \
    nativeLink
fi

binary=${OBJECT_ALLOC_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/ObjectAllocationLoweringMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing ObjectAllocationLoweringMatrix native binary; set OBJECT_ALLOC_BINARY or enable OBJECT_ALLOC_BUILD" >&2
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
  printf "mode\trecord_shape\theap_cap\tstatus\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmedian_rift_op_ms\tmedian_rift_slow_alloc_ms\tmedian_rift_alloc_object_total\tmedian_rift_zero_object_total\tmedian_rift_zero_object_bytes_total\tmedian_rift_zero_skipped_total\tmedian_rift_zero_skipped_bytes_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local mode="$1"
  local heap_cap="$2"
  local run_status="$3"
  local run_log="$4"
  local max_rss_bytes="$5"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=object-allocation-lowering-" "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${mode}" \
    "${fields[record_shape]-}" \
    "${heap_cap}" \
    "${run_status}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[max_gc_ms]-}" \
    "${fields[runs_with_gc]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_slow_alloc_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_zero_object_total]-}" \
    "${fields[median_rift_zero_object_bytes_total]-}" \
    "${fields[median_rift_zero_skipped_total]-}" \
    "${fields[median_rift_zero_skipped_bytes_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[checksum]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

run_mode() {
  local mode="$1"
  local binary_mode="${mode}"
  local heap_cap="${OBJECT_ALLOC_HEAP_CAP:-uncapped}"
  local roots_mode=""
  local page_size=""
  local safe_heap_cap="${heap_cap//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${mode}-${safe_heap_cap}.log"
  local time_log="${output_dir}/time-${mode}-${safe_heap_cap}.log"
  local max_rss_bytes
  local command_status

  case "${mode}" in
    heap)
      binary_mode="heap-immix"
      ;;
    rift-hp)
      binary_mode="rift-trusted-hp"
      ;;
    rift-streaming)
      binary_mode="rift-trusted-streaming"
      ;;
    rift-checked|rift-checked-rift)
      binary_mode="rift-checked-rift"
      ;;
    rift-checked-open-handle|rift-checked-rift-open-handle|checked-rift-open-handle)
      binary_mode="rift-checked-rift-open-handle"
      ;;
    rift-checked-open-handle-nozero-unsafe|rift-checked-rift-open-handle-nozero-unsafe|checked-rift-open-handle-nozero-unsafe)
      binary_mode="rift-checked-rift-open-handle-nozero-unsafe"
      ;;
    rift-checked-open-handle-dirty-slab|rift-checked-rift-open-handle-dirty-slab|checked-rift-open-handle-dirty-slab)
      binary_mode="rift-checked-rift-open-handle-dirty-slab"
      ;;
    rift-checked-safezone-32k|rift-checked-safezone-improved-32k)
      binary_mode="rift-checked-safezone-improved-32k"
      roots_mode="1"
      page_size="32768"
      ;;
  esac

  echo
  echo "== ${mode} heap_cap=${heap_cap} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    if [[ "${heap_cap}" == "uncapped" || -z "${heap_cap}" ]]; then
      SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
        /usr/bin/time -l "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
    else
      GC_MAXIMUM_HEAP_SIZE="${heap_cap}" SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
        /usr/bin/time -l "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
    fi
  else
    if [[ "${heap_cap}" == "uncapped" || -z "${heap_cap}" ]]; then
      SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
        /usr/bin/time -v "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
    else
      GC_MAXIMUM_HEAP_SIZE="${heap_cap}" SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
        /usr/bin/time -v "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
    fi
  fi
  command_status=$?
  set -e

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  if ! grep -q "^RESULT name=object-allocation-lowering-" "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    echo "OBJECT_ALLOC_RESULT mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} max_rss_bytes=${max_rss_bytes}" >&2
    printf "%s\t%s\t%s\tfailed\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t%s\n" \
      "${mode}" "${OBJECT_ALLOC_RECORD_SHAPE:-primitive}" "${heap_cap}" \
      "${max_rss_bytes}" >> "${summary}"
    return 0
  fi

  grep "^RESULT name=object-allocation-lowering-" "${run_log}"
  echo "OBJECT_ALLOC_RSS_RESULT mode=${mode} heap_cap=${heap_cap} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${mode}" "${heap_cap}" "ok" "${run_log}" "${max_rss_bytes}"
}

write_summary_header
for mode in "${modes[@]}"; do
  run_mode "${mode}"
done

echo
echo "Object allocation lowering matrix complete"
echo "Summary: ${summary}"
