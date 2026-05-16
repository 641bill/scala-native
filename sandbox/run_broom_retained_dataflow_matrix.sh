#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${BROOM_OUTPUT_DIR:-"/tmp/broom-retained-dataflow-matrix"}
summary=${BROOM_SUMMARY:-"${output_dir}/summary.tsv"}
build=${BROOM_BUILD:-1}
platform=$(uname -s)
workloads=(${(z)${BROOM_WORKLOADS:-"aggregate join"}})
modes=(${(z)${BROOM_MODES:-"heap-gc checked-rift"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"BroomRetainedDataflowMatrix\")" \
    nativeLink
fi

binary=${BROOM_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/BroomRetainedDataflowMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing BroomRetainedDataflowMatrix native binary; set BROOM_BINARY or enable BROOM_BUILD" >&2
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
  printf "workload\tmode\theap_cap\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tmeasurement_level\trecords\trecords_per_timestamp\tactive_timestamps\tkey_space\tmedian_ms\tmin_ms\tmax_ms\trecords_per_sec\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tretained_object_proxy\tregion_freed_object_proxy\tmax_live_object_proxy\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local workload="$1"
  local mode="$2"
  local heap_cap="$3"
  local run_status="$4"
  local run_log="$5"
  local max_rss_bytes="$6"
  local external_real_s="$7"
  local external_user_s="$8"
  local external_sys_s="$9"
  local line token key value level
  typeset -A fields

  line=$(grep "^RESULT name=broom-retained-dataflow-${workload}-${mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  level=${fields[measurement_level]-L2}
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${workload}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${level}" \
    "${fields[records]-}" \
    "${fields[records_per_timestamp]-}" \
    "${fields[active_timestamps]-}" \
    "${fields[key_space]-}" \
    "${fields[median_ms]-}" \
    "${fields[min_ms]-}" \
    "${fields[max_ms]-}" \
    "${fields[records_per_sec]-}" \
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
    "${fields[retained_object_proxy]-}" \
    "${fields[region_freed_object_proxy]-}" \
    "${fields[max_live_object_proxy]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

write_failed_row() {
  local workload="$1"
  local mode="$2"
  local heap_cap="$3"
  local run_status="$4"
  local max_rss_bytes="$5"
  local external_real_s="$6"
  local external_user_s="$7"
  local external_sys_s="$8"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t%s\n" \
    "${workload}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${max_rss_bytes}" >> "${summary}"
}

run_case() {
  local workload="$1"
  local mode="$2"
  local heap_cap="$3"
  local safe_heap_cap="${heap_cap//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${workload}-${mode}-${safe_heap_cap}.log"
  local time_log="${output_dir}/time-${workload}-${mode}-${safe_heap_cap}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status

  echo
  local -a env_args
  env_args=()
  if [[ -n "${heap_cap}" && "${heap_cap}" != "uncapped" ]]; then
    env_args+=(GC_MAXIMUM_HEAP_SIZE="${heap_cap}")
  fi

  echo "== ${workload} / ${mode} heap_cap=${heap_cap} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    env "${env_args[@]}" /usr/bin/time -l "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  else
    env "${env_args[@]}" /usr/bin/time -v "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)

  if ! grep -q "^RESULT name=broom-retained-dataflow-${workload}-${mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    echo "BROOM_RESULT workload=${workload} mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${workload}" "${mode}" "${heap_cap}" "failed:${command_status}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
    return 0
  fi

  grep "^RESULT name=broom-retained-dataflow-${workload}-${mode} " "${run_log}"
  echo "BROOM_EXTERNAL_RESULT workload=${workload} mode=${mode} heap_cap=${heap_cap} exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${workload}" "${mode}" "${heap_cap}" "ok" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header
heap_caps=(${(z)${BROOM_HEAP_CAPS:-"uncapped"}})
for workload in "${workloads[@]}"; do
  for mode in "${modes[@]}"; do
    if [[ "${mode}" == "heap-gc" || "${mode}" == "gc-heap" || "${mode}" == "heap-immix" || "${mode}" == "heap" ]]; then
      for heap_cap in "${heap_caps[@]}"; do
        run_case "${workload}" "${mode}" "${heap_cap}"
      done
    else
      run_case "${workload}" "${mode}" "uncapped"
    fi
  done
done

echo
echo "Broom retained dataflow matrix complete"
echo "Summary: ${summary}"
