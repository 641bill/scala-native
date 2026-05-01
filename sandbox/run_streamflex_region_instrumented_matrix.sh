#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${STREAMFLEX_OUTPUT_DIR:-"/tmp/streamflex-region-instrumented"}
summary=${STREAMFLEX_SUMMARY:-"${output_dir}/summary.tsv"}
build=${STREAMFLEX_BUILD:-1}
workload=${STREAMFLEX_WORKLOAD:-all}
platform=$(uname -s)

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"StreamFlexRegionMatrix\")" \
    nativeLink
fi

binary=${STREAMFLEX_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/StreamFlexRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing StreamFlexRegionMatrix native binary; set STREAMFLEX_BINARY or enable STREAMFLEX_BUILD" >&2
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
  printf "label\tmode\tworkload\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_p50_ns\tmedian_p99_ns\tmedian_p999_ns\tmedian_max_ns\tmedian_deadline_misses\tperiod_ns\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_rows() {
  local label="$1"
  local mode="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local line token key value name op
  typeset -A fields

  while IFS= read -r line; do
    fields=()
    for token in ${(z)line}; do
      if [[ "${token}" == *=* ]]; then
        key=${token%%=*}
        value=${token#*=}
        fields[${key}]=${value}
      fi
    done
    name=${fields[name]-}
    op=${name#streamflex-}
    op=${op%-${mode}}
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "${label}" \
      "${mode}" \
      "${op}" \
      "${fields[median_ms]-}" \
      "${fields[median_gc_ms]-}" \
      "${fields[median_rift_op_ms]-}" \
      "${fields[median_rift_alloc_object_total]-}" \
      "${fields[median_p50_ns]-}" \
      "${fields[median_p99_ns]-}" \
      "${fields[median_p999_ns]-}" \
      "${fields[median_max_ns]-}" \
      "${fields[median_deadline_misses]-}" \
      "${fields[period_ns]-}" \
      "${fields[checksum]-}" \
      "${max_rss_bytes}" >> "${summary}"
  done < <(grep "^RESULT name=streamflex-" "${run_log}")
}

run_mode() {
  local label="$1"
  local mode="$2"
  local roots_mode="$3"
  local page_size="${4:-${SAFEZONE_PAGE_SIZE:-}}"
  local run_log="${output_dir}/run-${label}.log"
  local time_log="${output_dir}/time-${label}.log"
  local max_rss_bytes

  echo
  echo "== ${label} =="
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=streamflex-" "${run_log}"
  echo "STREAMFLEX_RSS_RESULT label=${label} mode=${mode} max_rss_bytes=${max_rss_bytes}"
  write_result_rows "${label}" "${mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header

run_mode "heap" "heap" "0"
run_mode "current-safezone" "safezone" "0"
run_mode "improved-safezone" "safezone" "1"
run_mode "unsafezone-hp" "safezone" "3" "32768"
run_mode "rift-hp" "rift-hp" "0"
run_mode "rift-streaming" "rift-streaming" "0"

echo
echo "StreamFlex instrumented matrix complete"
echo "Summary: ${summary}"
