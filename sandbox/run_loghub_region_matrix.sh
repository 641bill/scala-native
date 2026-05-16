#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${LOGHUB_OUTPUT_DIR:-"/tmp/loghub-region-matrix"}
summary=${LOGHUB_SUMMARY:-"${output_dir}/summary.tsv"}
build=${LOGHUB_BUILD:-1}
platform=$(uname -s)
include_controls=${RIFT_BENCH_INCLUDE_CONTROLS:-${RIFT_EVAL_INCLUDE_CONTROLS:-0}}
modes=(${(z)${LOGHUB_MODES:-"heap-immix safezone-improved-32k rift-checked-safezone-page-token"}})
if [[ -z "${LOGHUB_MODES:-}" && ( "${include_controls}" == "1" || "${include_controls}" == "true" || "${include_controls}" == "yes" ) ]]; then
  modes+=(safezone-rootless-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token)
fi
queries=(${(z)${LOGHUB_QUERIES:-"q0-lines q1-tokens q2-window-counts"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"LogHubRegionMatrix\")" \
    nativeLink
fi

binary=${LOGHUB_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/LogHubRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing LogHubRegionMatrix native binary; set LOGHUB_BINARY or enable LOGHUB_BUILD" >&2
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
  printf "query\tmode\theap_cap\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tinput\tinput_mode\tloaded_events\tinput_files\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local query="$1"
  local mode="$2"
  local heap_cap="$3"
  local run_status="$4"
  local binary_mode="$5"
  local run_log="$6"
  local max_rss_bytes="$7"
  local external_real_s="$8"
  local external_user_s="$9"
  local external_sys_s="${10}"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=loghub-${query}-${binary_mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${fields[input]-}" \
    "${fields[input_mode]-}" \
    "${fields[loaded_events]-}" \
    "${fields[input_files]-}" \
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
  local external_real_s="$6"
  local external_user_s="$7"
  local external_sys_s="$8"

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "" \
    "${max_rss_bytes}" >> "${summary}"
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
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status
  local -a env_args

  case "${mode}" in
    gc-heap|heap-immix)
      binary_mode="heap"
      ;;
    heap-direct-summary-only|heap-direct-epoch|heap-same-shape-direct-epoch|heap-direct-aggregate)
      binary_mode="heap-direct-epoch"
      ;;
    heap-epoch-retained-no-traverse)
      binary_mode="heap-epoch-retained-no-traverse"
      ;;
    safezone-current)
      binary_mode="safezone"
      roots_mode="0"
      ;;
    safezone-improved|safezone-improved-32k)
      binary_mode="safezone"
      roots_mode="1"
      if [[ "${mode}" == "safezone-improved-32k" ]]; then
        page_size="32768"
      fi
      ;;
    safezone-rootless-32k|unsafezone-hp)
      binary_mode="safezone"
      roots_mode="3"
      page_size="32768"
      ;;
    rift-trusted-hp)
      binary_mode="rift-hp"
      ;;
    rift-trusted-streaming)
      binary_mode="rift-streaming"
      ;;
    rift-checked-page-token)
      binary_mode="${mode}"
      ;;
    rift-checked-page-token-open-handle)
      binary_mode="${mode}"
      ;;
    rift-checked-page-token-legacy)
      binary_mode="${mode}"
      ;;
    rift-checked-safezone-page-token)
      binary_mode="${mode}"
      roots_mode="1"
      page_size="32768"
      ;;
    checked-epoch-stream|checked-region-stream-epoch|rift-checked-direct-epoch)
      binary_mode="rift-checked-direct-epoch"
      ;;
    checked-epoch-stream-open-handle|checked-region-stream-epoch-open-handle|rift-checked-direct-epoch-open-handle)
      binary_mode="rift-checked-direct-epoch-open-handle"
      ;;
    checked-epoch-stream-legacy|checked-region-stream-epoch-legacy|rift-checked-direct-epoch-legacy)
      binary_mode="rift-checked-direct-epoch-legacy"
      ;;
    checked-epoch-scoped|checked-region-scoped-epoch|rift-checked-safezone-direct-epoch)
      binary_mode="rift-checked-safezone-direct-epoch"
      roots_mode="1"
      page_size="32768"
      ;;
    checked-epoch-retained-no-traverse|checked-region-stream-retained-epoch)
      binary_mode="checked-epoch-retained-no-traverse"
      ;;
    checked-scoped-epoch-retained-no-traverse|checked-region-scoped-retained-epoch)
      binary_mode="checked-scoped-epoch-retained-no-traverse"
      roots_mode="1"
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

  if ! grep -q "^RESULT name=loghub-${query}-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    max_rss_bytes=$(read_max_rss_bytes "${time_log}")
    external_real_s=$(read_time_seconds "${time_log}" real)
    external_user_s=$(read_time_seconds "${time_log}" user)
    external_sys_s=$(read_time_seconds "${time_log}" sys)
    echo "LOGHUB_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${query}" "${mode}" "${heap_cap}" "failed:${command_status}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
    return 0
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  grep "^RESULT name=loghub-${query}-${binary_mode} " "${run_log}"
  echo "LOGHUB_EXTERNAL_RESULT query=${query} mode=${mode} heap_cap=${heap_cap} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${query}" "${mode}" "${heap_cap}" "ok" "${binary_mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

heap_caps=(${(z)${LOGHUB_HEAP_CAPS:-"uncapped"}})

for query in "${queries[@]}"; do
  for mode in "${modes[@]}"; do
    if [[ "${mode}" == "heap-immix" ]]; then
      for heap_cap in "${heap_caps[@]}"; do
        run_case "${query}" "${mode}" "${heap_cap}"
      done
    else
      run_case "${query}" "${mode}" "uncapped"
    fi
  done
done

echo
echo "LogHub region matrix complete"
echo "Summary: ${summary}"
