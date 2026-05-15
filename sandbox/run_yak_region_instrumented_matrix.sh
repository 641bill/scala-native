#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${YAK_OUTPUT_DIR:-"/tmp/yak-region-instrumented"}
summary=${YAK_SUMMARY:-"${output_dir}/summary.tsv"}
build=${YAK_BUILD:-1}
workload=${YAK_WORKLOAD:-all}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${YAK_MODES:-"heap improved-safezone yak-runtime"}})
if [[ -z "${YAK_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(current-safezone unsafezone-hp rift-hp rift-streaming heap-promotion yak-runtime-promotion)
fi

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"YakRegionMatrix\")" \
    nativeLink
fi

binary=${YAK_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/YakRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing YakRegionMatrix native binary; set YAK_BINARY or enable YAK_BUILD" >&2
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
  printf "label\tmode\texternal_real_s\texternal_user_s\texternal_sys_s\tworkload\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_slow_alloc_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tmedian_yak_barrier_checks\tmedian_yak_remembered_refs\tmedian_yak_promoted_objects\tlogical_data_objects\tcontrol_slots\tchecksum\tmax_rss_bytes\n" > "${summary}"
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
    op=${name#yak-}
    op=${op%-${mode}}
    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "${label}" \
      "${mode}" \
      "${external_real_s}" \
      "${external_user_s}" \
      "${external_sys_s}" \
      "${op}" \
      "${fields[median_ms]-}" \
      "${fields[median_gc_ms]-}" \
      "${fields[median_rift_op_ms]-}" \
      "${fields[median_rift_slow_alloc_ms]-}" \
      "${fields[median_rift_alloc_object_total]-}" \
      "${fields[median_rift_open_total]-}" \
      "${fields[median_rift_close_total]-}" \
      "${fields[median_rift_reset_total]-}" \
      "${fields[median_yak_barrier_checks]-}" \
      "${fields[median_yak_remembered_refs]-}" \
      "${fields[median_yak_promoted_objects]-}" \
      "${fields[logical_data_objects]-}" \
      "${fields[control_slots]-}" \
      "${fields[checksum]-}" \
      "${max_rss_bytes}" >> "${summary}"
  done < <(grep "^RESULT name=yak-" "${run_log}")
}

run_mode() {
  local label="$1"
  local mode="$2"
  local roots_mode="$3"
  local mode_workload="${4:-${workload}}"
  local page_size="${5:-${SAFEZONE_PAGE_SIZE:-}}"
  local run_log="${output_dir}/run-${label}.log"
  local time_log="${output_dir}/time-${label}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s

  echo
  echo "== ${label} =="
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -l "${binary}" "${mode}" "${mode_workload}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" /usr/bin/time -v "${binary}" "${mode}" "${mode_workload}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  grep "^RESULT name=yak-" "${run_log}"
  echo "YAK_EXTERNAL_RESULT label=${label} mode=${mode} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_rows "${label}" "${mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

for selected_mode in "${modes[@]}"; do
  case "${selected_mode}" in
    heap|gc-heap|heap-immix)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "gc-heap" "heap" "0"
      fi
      ;;
    current-safezone)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "current-safezone" "safezone" "0"
      fi
      ;;
    improved-safezone|safezone-improved|safezone-improved-32k|region-scoped-rooted)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "region-scoped-rooted" "safezone" "1" "${workload}" "32768"
      fi
      ;;
    unsafezone-hp|safezone-rootless-32k|region-scoped-rootless)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "region-scoped-rootless" "safezone" "3" "${workload}" "32768"
      fi
      ;;
    rift-hp|rift-trusted-hp|region-hp-rootless)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "region-hp-rootless" "rift-hp" "0"
      fi
      ;;
    rift-streaming|rift-trusted-streaming|region-stream-rootless)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "region-stream-rootless" "rift-streaming" "0"
      fi
      ;;
    checked-region-stream|rift-checked-graphreal)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-region-stream" "checked-region-stream" "0"
      else
        echo "checked-region-stream is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-region-scoped|rift-checked-safezone-graphreal)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-region-scoped" "checked-region-scoped" "1" "${workload}" "32768"
      else
        echo "checked-region-scoped is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-page-token-stream)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-page-token-stream" "checked-page-token-stream" "0"
      else
        echo "checked-page-token-stream is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-page-token-scoped)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-page-token-scoped" "checked-page-token-scoped" "1" "${workload}" "32768"
      else
        echo "checked-page-token-scoped is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-whole-run-stream)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-whole-run-stream" "checked-whole-run-stream" "0"
      else
        echo "checked-whole-run-stream is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-whole-run-scoped)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-whole-run-scoped" "checked-whole-run-scoped" "1" "${workload}" "32768"
      else
        echo "checked-whole-run-scoped is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-stream)
      if [[ "${workload}" != "all" && "${workload}" != "promotion" ]]; then
        run_mode "checked-epoch-stream" "checked-epoch-stream" "0"
      else
        echo "checked-epoch-stream is currently implemented for wordcount, graphstep, sort, topword, graphchi, and graphreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-stream-legacy)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-epoch-stream-legacy" "checked-epoch-stream-legacy" "0"
      else
        echo "checked-epoch-stream-legacy is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-scoped)
      if [[ "${workload}" != "all" && "${workload}" != "promotion" ]]; then
        run_mode "checked-epoch-scoped" "checked-epoch-scoped" "1" "${workload}" "32768"
      else
        echo "checked-epoch-scoped is currently implemented for wordcount, graphstep, sort, topword, graphchi, and graphreal" >&2
        exit 1
      fi
      ;;
    heap-epoch-topk|heap-topk-retained-no-traverse)
      if [[ "${workload}" == "topword" || "${workload}" == "topwordreal" ]]; then
        run_mode "heap-topk-retained-no-traverse" "heap-epoch-topk" "0"
      else
        echo "heap-epoch-topk is currently implemented only for YAK_WORKLOAD=topword or topwordreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-topk-stream)
      if [[ "${workload}" == "topword" || "${workload}" == "topwordreal" ]]; then
        run_mode "checked-epoch-topk-stream" "checked-epoch-topk-stream" "0"
      else
        echo "checked-epoch-topk-stream is currently implemented only for YAK_WORKLOAD=topword or topwordreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-topk-scoped)
      if [[ "${workload}" == "topword" || "${workload}" == "topwordreal" ]]; then
        run_mode "checked-epoch-topk-scoped" "checked-epoch-topk-scoped" "1" "${workload}" "32768"
      else
        echo "checked-epoch-topk-scoped is currently implemented only for YAK_WORKLOAD=topword or topwordreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-buffer-stream)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-epoch-buffer-stream" "checked-epoch-buffer-stream" "0"
      else
        echo "checked-epoch-buffer-stream is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    checked-epoch-buffer-scoped)
      if [[ "${workload}" == "graphreal" ]]; then
        run_mode "checked-epoch-buffer-scoped" "checked-epoch-buffer-scoped" "1" "${workload}" "32768"
      else
        echo "checked-epoch-buffer-scoped is currently implemented only for YAK_WORKLOAD=graphreal" >&2
        exit 1
      fi
      ;;
    yak-runtime)
      if [[ "${workload}" != "promotion" ]]; then
        run_mode "yak-runtime" "yak-runtime" "0"
      fi
      ;;
    heap-promotion)
      if [[ "${workload}" == "all" || "${workload}" == "promotion" ]]; then
        run_mode "heap-promotion" "heap" "0" "promotion"
      fi
      ;;
    yak-runtime-promotion)
      if [[ "${workload}" == "all" || "${workload}" == "promotion" ]]; then
        run_mode "yak-runtime-promotion" "yak-runtime" "0" "promotion"
      fi
      ;;
    *)
      echo "unknown YAK_MODES entry: ${selected_mode}" >&2
      exit 1
      ;;
  esac
done

echo
echo "Yak instrumented matrix complete"
echo "Summary: ${summary}"
