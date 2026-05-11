#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${STREAMIT_KERNEL_OUTPUT_DIR:-"/tmp/streamit-kernel-matrix"}
summary=${STREAMIT_KERNEL_SUMMARY:-"${output_dir}/summary.tsv"}
build=${STREAMIT_KERNEL_BUILD:-1}
benchmark=${STREAMIT_KERNEL_BENCHMARK:-both}
workload=${STREAMIT_KERNEL_WORKLOAD:-all}
platform=$(uname -s)
modes=(${(z)${STREAMIT_KERNEL_MODES:-"heap checked-epoch-scoped"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"StreamItKernelMatrix\")" \
    nativeLink
fi

binary=${STREAMIT_KERNEL_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/StreamItKernelMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing StreamItKernelMatrix native binary; set STREAMIT_KERNEL_BINARY or enable STREAMIT_KERNEL_BUILD" >&2
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
    awk -v field="${field}" '
      {
        for (i = 1; i <= NF; i++) {
          if ($i == field && i > 1) {
            print $(i - 1)
            found = 1
            exit
          }
        }
      }
      END { if (!found) print "" }
    ' "${time_log}"
  else
    awk -v field="${field}" -F ':' '
      field == "real" && /Elapsed \(wall clock\) time/ {
        gsub(/^[ \t]+/, "", $2)
        split($2, parts, ":")
        if (length(parts) == 3)
          print parts[1] * 3600 + parts[2] * 60 + parts[3]
        else if (length(parts) == 2)
          print parts[1] * 60 + parts[2]
        else
          print $2
        found = 1
        exit
      }
      field == "user" && /User time/ {
        gsub(/^[ \t]+/, "", $2)
        print $2
        found = 1
        exit
      }
      field == "sys" && /System time/ {
        gsub(/^[ \t]+/, "", $2)
        print $2
        found = 1
        exit
      }
      END { if (!found) print "" }
    ' "${time_log}"
  fi
}

write_summary_header() {
  printf "label\tmode\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tbenchmark\tworkload\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tmedian_p50_ns\tmedian_p95_ns\tmedian_p99_ns\tmedian_p999_ns\tmedian_max_ns\tmedian_deadline_misses\tperiod_ns\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_rows() {
  local label="$1"
  local mode="$2"
  local run_status="$3"
  local run_log="$4"
  local max_rss_bytes="$5"
  local external_real_s="$6"
  local external_user_s="$7"
  local external_sys_s="$8"
  local line token key value p50 p95 p99 p999 max misses
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
    p50=${fields[median_p50_ns]-${fields[p50_ns]-}}
    p95=${fields[median_p95_ns]-${fields[p95_ns]-}}
    p99=${fields[median_p99_ns]-${fields[p99_ns]-}}
    p999=${fields[median_p999_ns]-${fields[p999_ns]-}}
    max=${fields[median_max_ns]-${fields[max_ns]-}}
    misses=${fields[median_deadline_misses]-${fields[deadline_misses]-}}
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "${label}" \
      "${mode}" \
      "${run_status}" \
      "${external_real_s}" \
      "${external_user_s}" \
      "${external_sys_s}" \
      "${fields[benchmark]-}" \
      "${fields[workload]-}" \
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
      "${p50}" \
      "${p95}" \
      "${p99}" \
      "${p999}" \
      "${max}" \
      "${misses}" \
      "${fields[period_ns]-}" \
      "${fields[checksum]-}" \
      "${fields[output_count]-}" \
      "${max_rss_bytes}" >> "${summary}"
  done < <(grep "^RESULT name=streamit-" "${run_log}" || true)
}

run_mode() {
  local mode="$1"
  local label="${mode}"
  local safe_label="${label//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${safe_label}.log"
  local time_log="${output_dir}/time-${safe_label}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status
  local roots_mode="${SAFEZONE_ROOTS_MODE:-}"
  local page_size="${SAFEZONE_PAGE_SIZE:-}"

  case "${mode}" in
    checked-epoch-scoped)
      roots_mode="1"
      page_size="32768"
      ;;
  esac

  echo
  echo "== ${label} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" "${benchmark}" "${workload}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" "${benchmark}" "${workload}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)

  if [[ -z "$(grep "^RESULT name=streamit-" "${run_log}" || true)" ]]; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    echo "STREAMIT_KERNEL_RESULT label=${label} mode=${mode} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    write_result_rows "${label}" "${mode}" "failed" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
    return 0
  fi

  grep "^RESULT name=streamit-" "${run_log}"
  echo "STREAMIT_KERNEL_EXTERNAL_RESULT label=${label} mode=${mode} exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_rows "${label}" "${mode}" "ok" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header
for mode in "${modes[@]}"; do
  run_mode "${mode}"
done

echo
echo "StreamIt kernel matrix complete"
echo "Summary: ${summary}"
