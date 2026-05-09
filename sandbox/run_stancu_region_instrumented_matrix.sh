#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${STANCU_OUTPUT_DIR:-"/tmp/stancu-region-instrumented"}
summary=${STANCU_SUMMARY:-"${output_dir}/summary.tsv"}
build=${STANCU_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${STANCU_MODES:-"heap improved-safezone"}})
if [[ -z "${STANCU_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"StancuRegionMatrix\")" \
    nativeLink
fi

binary=${STANCU_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/StancuRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing StancuRegionMatrix native binary; set STANCU_BINARY or enable STANCU_BUILD" >&2
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

read_time_seconds() {
  local time_log="$1"
  local field="$2"
  if [[ "${platform}" == "Darwin" ]]; then
    case "${field}" in
      real) awk '/ real .* user .* sys/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      user) awk '/ real .* user .* sys/ { print $3; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      sys) awk '/ real .* user .* sys/ { print $5; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
    esac
  else
    case "${field}" in
      user) awk -F ':' '/User time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      sys) awk -F ':' '/System time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      real) awk -F ':' '/Elapsed \(wall clock\) time/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
    esac
  fi
}

write_summary_header() {
  printf "label\tmode\texternal_real_s\texternal_user_s\texternal_sys_s\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_slow_alloc_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tlogical_region_objects\tdurable_control_slots\tcandidate_region_object_bp\ttransactions_per_region\texplicit_region_boundaries\tescaped_region_objects\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local label="$1"
  local mode="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local external_real_s="$5"
  local external_user_s="$6"
  local external_sys_s="$7"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=stancu-transactions-" "${run_log}")
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${label}" \
    "${mode}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_slow_alloc_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[logical_region_objects]-}" \
    "${fields[durable_control_slots]-}" \
    "${fields[candidate_region_object_bp]-}" \
    "${fields[transactions_per_region]-}" \
    "${fields[explicit_region_boundaries]-}" \
    "${fields[escaped_region_objects]-}" \
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
  local external_real_s
  local external_user_s
  local external_sys_s

  echo
  echo "== ${label} =="
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  grep "^RESULT name=stancu-transactions-" "${run_log}"
  echo "STANCU_EXTERNAL_RESULT label=${label} mode=${mode} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${label}" "${mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap)
      run_mode "heap" "heap" "0"
      ;;
    current-safezone)
      run_mode "current-safezone" "safezone" "0"
      ;;
    improved-safezone)
      run_mode "improved-safezone" "safezone" "1"
      ;;
    unsafezone-hp)
      run_mode "unsafezone-hp" "safezone" "3" "32768"
      ;;
    rift-hp)
      run_mode "rift-hp" "rift-hp" "0"
      ;;
    rift-streaming)
      run_mode "rift-streaming" "rift-streaming" "0"
      ;;
    rift-checked-direct-epoch)
      run_mode "rift-checked-direct-epoch" "rift-checked-direct-epoch" "0"
      ;;
    rift-checked-safezone-direct-epoch)
      run_mode "rift-checked-safezone-direct-epoch" "rift-checked-safezone-direct-epoch" "1" "32768"
      ;;
    *)
      echo "unknown STANCU_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done

echo
echo "Stancu instrumented matrix complete"
echo "Summary: ${summary}"
