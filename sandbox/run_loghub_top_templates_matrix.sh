#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${LOGHUB_TOP_OUTPUT_DIR:-"/tmp/loghub-top-templates-matrix"}
summary=${LOGHUB_TOP_SUMMARY:-"${output_dir}/summary.tsv"}
build=${LOGHUB_TOP_BUILD:-1}
platform=$(uname -s)
modes=(${(z)${LOGHUB_TOP_MODES:-"heap-natural heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse checked-epoch-topk-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"LogHubTopTemplatesMatrix\")" \
    nativeLink
fi

binary=${LOGHUB_TOP_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/LogHubTopTemplatesMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing LogHubTopTemplatesMatrix native binary; set LOGHUB_TOP_BINARY or enable LOGHUB_TOP_BUILD" >&2
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
  printf "mode\theap_cap\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tinput\tinput_mode\tloaded_events\tinput_files\tbytes_read\tparse_errors\tlines_per_epoch\ttemplate_buckets\ttop_k\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local mode="$1"
  local heap_cap="$2"
  local run_status="$3"
  local binary_mode="$4"
  local run_log="$5"
  local max_rss_bytes="$6"
  local external_real_s="$7"
  local external_user_s="$8"
  local external_sys_s="$9"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=loghub-top-templates-${binary_mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
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
    "${fields[bytes_read]-}" \
    "${fields[parse_errors]-}" \
    "${fields[lines_per_epoch]-}" \
    "${fields[template_buckets]-}" \
    "${fields[top_k]-}" \
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
  local mode="$1"
  local heap_cap="$2"
  local run_status="$3"
  local max_rss_bytes="$4"
  local external_real_s="$5"
  local external_user_s="$6"
  local external_sys_s="$7"

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${mode}" \
    "${heap_cap}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" \
    "${max_rss_bytes}" >> "${summary}"
}

binary_mode_for() {
  local mode="$1"
  case "${mode}" in
    gc-heap|heap-immix|heap-natural)
      echo "heap-natural"
      ;;
    heap-direct-summary-only|heap-summary-only)
      echo "heap-summary-only"
      ;;
    heap-retained-drop-anchor|heap-epoch-retained-no-traverse)
      echo "heap-retained-drop-anchor"
      ;;
    checked-epoch-retained-no-traverse|checked-region-stream-retained-epoch)
      echo "checked-epoch-retained-no-traverse"
      ;;
    checked-scoped-epoch-retained-no-traverse|checked-region-scoped-retained-epoch)
      echo "checked-scoped-epoch-retained-no-traverse"
      ;;
    checked-epoch-topk-retained-no-traverse|checked-region-stream-epoch-topk|checked-topk-stream)
      echo "checked-epoch-topk-retained-no-traverse"
      ;;
    checked-scoped-epoch-topk-retained-no-traverse|checked-region-scoped-epoch-topk|checked-topk-scoped)
      echo "checked-scoped-epoch-topk-retained-no-traverse"
      ;;
    *)
      echo "${mode}"
      ;;
  esac
}

run_case() {
  local mode="$1"
  local heap_cap="$2"
  local binary_mode
  binary_mode=$(binary_mode_for "${mode}")
  local safe_heap_cap="${heap_cap//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${mode}-${safe_heap_cap}.log"
  local time_log="${output_dir}/time-${mode}-${safe_heap_cap}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status
  local -a env_args

  env_args=()
  case "${mode}" in
    checked-scoped-epoch-retained-no-traverse|checked-region-scoped-retained-epoch)
      env_args+=(SAFEZONE_ROOTS_MODE="1")
      env_args+=(SAFEZONE_PAGE_SIZE="32768")
      ;;
    checked-scoped-epoch-topk-retained-no-traverse|checked-region-scoped-epoch-topk|checked-topk-scoped)
      env_args+=(SAFEZONE_ROOTS_MODE="1")
      env_args+=(SAFEZONE_PAGE_SIZE="32768")
      ;;
  esac
  if [[ -n "${heap_cap}" && "${heap_cap}" != "uncapped" ]]; then
    env_args+=(GC_MAXIMUM_HEAP_SIZE="${heap_cap}")
  fi

  echo
  echo "== top-templates / ${mode} heap_cap=${heap_cap} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    env "${env_args[@]}" /usr/bin/time -l "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
  else
    env "${env_args[@]}" /usr/bin/time -v "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  if ! grep -q "^RESULT name=loghub-top-templates-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    max_rss_bytes=$(read_max_rss_bytes "${time_log}")
    external_real_s=$(read_time_seconds "${time_log}" real)
    external_user_s=$(read_time_seconds "${time_log}" user)
    external_sys_s=$(read_time_seconds "${time_log}" sys)
    echo "LOGHUB_TOP_RESULT mode=${mode} heap_cap=${heap_cap} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${mode}" "${heap_cap}" "failed:${command_status}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
    return 0
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)
  grep "^RESULT name=loghub-top-templates-${binary_mode} " "${run_log}"
  echo "LOGHUB_TOP_EXTERNAL_RESULT mode=${mode} heap_cap=${heap_cap} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${mode}" "${heap_cap}" "ok" "${binary_mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

heap_caps=(${(z)${LOGHUB_TOP_HEAP_CAPS:-"uncapped"}})

for mode in "${modes[@]}"; do
  if [[ "${mode}" == "heap-immix" || "${mode}" == "gc-heap" || "${mode}" == "heap-natural" ]]; then
    for heap_cap in "${heap_caps[@]}"; do
      run_case "${mode}" "${heap_cap}"
    done
  else
    run_case "${mode}" "uncapped"
  fi
done

echo
echo "LogHub top-template matrix complete"
echo "Summary: ${summary}"
