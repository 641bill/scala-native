#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${SPECJBB_OUTPUT_DIR:-"/tmp/specjbb2005-port-matrix"}
summary=${SPECJBB_SUMMARY:-"${output_dir}/summary.tsv"}
build=${SPECJBB_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${SPECJBB_MODES:-"gc-heap region-scoped-rooted checked-epoch-scoped"}})
if [[ -z "${SPECJBB_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(region-hp-rootless region-stream-rootless checked-epoch-stream)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"SpecJbb2005PortMatrix\")" \
    nativeLink
fi

binary=${SPECJBB_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/SpecJbb2005PortMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing SpecJbb2005PortMatrix native binary; set SPECJBB_BINARY or enable SPECJBB_BUILD" >&2
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
  printf "label\tmode\tmedian_ms\tmin_ms\tmax_ms\tmedian_gc_ms\tmax_gc_ms\tmedian_gc_collections\tmedian_rift_op_ms\tmedian_rift_slow_alloc_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tlogical_region_objects\tregion_freed_object_proxy\tlogical_region_byte_proxy\tregion_freed_byte_proxy\tmax_live_region_object_proxy\tmax_live_region_byte_proxy\tdurable_control_slots\tcandidate_region_object_bp\ttransactions\twarehouses\titerations_per_warehouse\titems_per_order\ttransactions_per_region\tannotation_api_boundaries\texplicit_region_boundaries\tescaped_region_objects\tofficial_specjbb2005\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local label="$1"
  local mode="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=specjbb2005-port-" "${run_log}")
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${label}" \
    "${mode}" \
    "${fields[median_ms]-}" \
    "${fields[min_ms]-}" \
    "${fields[max_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[max_gc_ms]-}" \
    "${fields[median_gc_collections]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_slow_alloc_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[logical_region_objects]-}" \
    "${fields[region_freed_object_proxy]-}" \
    "${fields[logical_region_byte_proxy]-}" \
    "${fields[region_freed_byte_proxy]-}" \
    "${fields[max_live_region_object_proxy]-}" \
    "${fields[max_live_region_byte_proxy]-}" \
    "${fields[durable_control_slots]-}" \
    "${fields[candidate_region_object_bp]-}" \
    "${fields[transactions]-}" \
    "${fields[warehouses]-}" \
    "${fields[iterations_per_warehouse]-}" \
    "${fields[items_per_order]-}" \
    "${fields[transactions_per_region]-}" \
    "${fields[annotation_api_boundaries]-}" \
    "${fields[explicit_region_boundaries]-}" \
    "${fields[escaped_region_objects]-}" \
    "${fields[official_specjbb2005]-}" \
    "${fields[checksum]-}" \
    "${max_rss_bytes}" >> "${summary}"
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
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=specjbb2005-port-" "${run_log}"
  echo "SPECJBB_RSS_RESULT label=${label} mode=${mode} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${label}" "${mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    gc-heap|heap|heap-immix)
      run_mode "gc-heap" "heap" "0"
      ;;
    region-scoped-rooted|safezone-improved-32k|improved-safezone)
      run_mode "region-scoped-rooted" "safezone" "1" "32768"
      ;;
    region-scoped-rootless|safezone-rootless-32k|unsafezone-hp)
      run_mode "region-scoped-rootless" "safezone" "3" "32768"
      ;;
    region-hp-rootless|rift-trusted-hp|rift-hp)
      run_mode "region-hp-rootless" "rift-hp" "0"
      ;;
    region-stream-rootless|rift-trusted-streaming|rift-streaming)
      run_mode "region-stream-rootless" "rift-streaming" "0"
      ;;
    checked-epoch-stream|checked-region-stream|rift-checked-direct-epoch)
      run_mode "checked-epoch-stream" "rift-checked-direct-epoch" "0"
      ;;
    checked-epoch-scoped|checked-region-scoped|rift-checked-safezone-direct-epoch)
      run_mode "checked-epoch-scoped" "rift-checked-safezone-direct-epoch" "1" "32768"
      ;;
    *)
      echo "unknown SPECJBB_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done

echo
echo "SPECjbb2005 workload port matrix complete"
echo "Summary: ${summary}"
