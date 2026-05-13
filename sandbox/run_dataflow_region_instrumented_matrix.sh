#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${DATAFLOW_OUTPUT_DIR:-"/tmp/dataflow-region-instrumented"}
summary=${DATAFLOW_SUMMARY:-"${output_dir}/summary.tsv"}
build=${DATAFLOW_BUILD:-1}
operator=${DATAFLOW_OPERATOR:-all}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${DATAFLOW_MODES:-"heap improved-safezone rift-checked"}})
if [[ -z "${DATAFLOW_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp rift-streaming)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"DataflowRegionMatrix\")" \
    nativeLink
fi

binary=${DATAFLOW_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/DataflowRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing DataflowRegionMatrix native binary; set DATAFLOW_BINARY or enable DATAFLOW_BUILD" >&2
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
      *) print "" ;;
    esac
  else
    case "${field}" in
      user) awk -F ':' '/User time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      sys) awk -F ':' '/System time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      real) awk -F ':' '/Elapsed \(wall clock\) time/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}" ;;
      *) print "" ;;
    esac
  fi
}

write_summary_header() {
  printf "label\tmode\texternal_real_s\texternal_user_s\texternal_sys_s\toperator\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_rows() {
  local label="$1"
  local mode="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local external_real_s="$5"
  local external_user_s="$6"
  local external_sys_s="$7"
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
    op=${name#dataflow-}
    op=${op%-${mode}}
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "${label}" \
      "${mode}" \
      "${external_real_s}" \
      "${external_user_s}" \
      "${external_sys_s}" \
      "${op}" \
      "${fields[median_ms]-}" \
      "${fields[median_gc_ms]-}" \
      "${fields[median_rift_op_ms]-}" \
      "${fields[median_rift_alloc_object_total]-}" \
      "${fields[median_rift_open_total]-}" \
      "${fields[median_rift_close_total]-}" \
      "${fields[median_rift_reset_total]-}" \
      "${fields[checksum]-}" \
      "${max_rss_bytes}" >> "${summary}"
  done < <(grep "^RESULT name=dataflow-" "${run_log}")
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
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" "${operator}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" "${operator}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  grep "^RESULT name=dataflow-" "${run_log}"
  echo "DATAFLOW_EXTERNAL_RESULT label=${label} mode=${mode} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_rows "${label}" "${mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap|gc-heap|heap-immix)
      run_mode "heap" "heap" "0"
      ;;
    current-safezone)
      run_mode "current-safezone" "safezone" "0"
      ;;
    improved-safezone|safezone-improved)
      run_mode "improved-safezone" "safezone" "1"
      ;;
    safezone-improved-32k|region-scoped-rooted)
      run_mode "region-scoped-rooted" "safezone" "1" "32768"
      ;;
    unsafezone-hp)
      run_mode "unsafezone-hp" "safezone" "3" "32768"
      ;;
    rift-hp|rift-trusted-hp|region-hp-rootless)
      run_mode "rift-hp" "rift-hp" "0"
      ;;
    rift-streaming|rift-trusted-streaming|region-stream-rootless)
      run_mode "rift-streaming" "rift-streaming" "0"
      ;;
    rift-checked|checked-region-stream)
      run_mode "rift-checked" "rift-checked" "0"
      ;;
    checked-page-token|checked-page-token-stream|rift-checked-page-token)
      run_mode "checked-page-token" "rift-checked-page-token" "0"
      ;;
    checked-page-token-scoped|checked-region-scoped-page-token|rift-checked-safezone-page-token)
      run_mode "checked-page-token-scoped" "rift-checked-safezone-page-token" "1" "32768"
      ;;
    checked-epoch-stream|checked-region-stream-epoch)
      run_mode "checked-epoch-stream" "rift-checked-direct-epoch" "0"
      ;;
    checked-epoch-stream-legacy|checked-region-stream-epoch-legacy)
      run_mode "checked-epoch-stream-legacy" "rift-checked-direct-epoch-legacy" "0"
      ;;
    checked-epoch-stream-open-handle|checked-region-stream-epoch-open-handle)
      run_mode "checked-epoch-stream-open-handle" "rift-checked-direct-epoch-open-handle" "0"
      ;;
    checked-epoch-scoped|checked-region-scoped-epoch)
      run_mode "checked-epoch-scoped" "rift-checked-safezone-direct-epoch" "1" "32768"
      ;;
    *)
      echo "unknown DATAFLOW_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done

echo
echo "Dataflow instrumented matrix complete"
echo "Summary: ${summary}"
