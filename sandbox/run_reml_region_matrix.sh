#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${REML_OUTPUT_DIR:-"/tmp/reml-region-matrix"}
summary=${REML_SUMMARY:-"${output_dir}/summary.tsv"}
build=${REML_BUILD:-1}
platform=$(uname -s)
workloads=(${(z)${REML_WORKLOADS:-"fib37 tak mandel msort msort-r life fft ratio"}})
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${REML_MODES:-"gc-heap region-scoped-rooted checked-region-stream checked-region-scoped"}})
if [[ -z "${REML_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(region-scoped-rootless region-hp-rootless region-stream-rootless)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"ReMLRegionMatrix\")" \
    nativeLink
fi

binary=${REML_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/ReMLRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing ReMLRegionMatrix native binary; set REML_BINARY or enable REML_BUILD" >&2
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
  printf "workload\tmode\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmedian_rift_op_ms\tmedian_rift_slow_alloc_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local workload="$1"
  local mode="$2"
  local run_status="$3"
  local run_log="$4"
  local max_rss_bytes="$5"
  local external_real_s="$6"
  local external_user_s="$7"
  local external_sys_s="$8"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=reml-region-" "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${workload}" \
    "${mode}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[max_gc_ms]-}" \
    "${fields[runs_with_gc]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_slow_alloc_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[checksum]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

run_one() {
  local workload="$1"
  local mode="$2"
  local roots_mode=""
  local page_size=""
  local run_log="${output_dir}/run-${workload}-${mode}.log"
  local time_log="${output_dir}/time-${workload}-${mode}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status

  case "${mode}" in
    region-scoped-rooted|safezone-improved|safezone-improved-32k|checked-region-scoped|rift-checked-safezone-improved-32k)
      roots_mode="1"
      page_size="32768"
      ;;
    region-scoped-rootless|safezone-rootless-32k|unsafezone-hp)
      roots_mode="3"
      page_size="32768"
      ;;
  esac

  echo
  echo "== workload=${workload} mode=${mode} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
      /usr/bin/time -l "${binary}" "${workload}" "${mode}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
      /usr/bin/time -v "${binary}" "${workload}" "${mode}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  if ! grep -q "^RESULT name=reml-region-" "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    echo "REML_RESULT workload=${workload} mode=${mode} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    printf "%s\t%s\tfailed\t%s\t%s\t%s\t\t\t\t\t\t\t\t\t\t\t\t%s\n" \
      "${workload}" "${mode}" "${external_real_s}" "${external_user_s}" "${external_sys_s}" "${max_rss_bytes}" >> "${summary}"
    return 0
  fi

  grep "^RESULT name=reml-region-" "${run_log}"
  echo "REML_EXTERNAL_RESULT workload=${workload} mode=${mode} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${workload}" "${mode}" "ok" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header
for workload in "${workloads[@]}"; do
  for mode in "${modes[@]}"; do
    run_one "${workload}" "${mode}"
  done
done

echo
echo "ReML-shaped region matrix complete"
echo "Summary: ${summary}"
