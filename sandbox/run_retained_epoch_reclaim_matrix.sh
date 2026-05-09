#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${RETAINED_EPOCH_OUTPUT_DIR:-"/tmp/retained-epoch-reclaim-matrix"}
summary=${RETAINED_EPOCH_SUMMARY:-"${output_dir}/summary.tsv"}
build=${RETAINED_EPOCH_BUILD:-1}
platform=$(uname -s)
modes=(${(z)${RETAINED_EPOCH_MODES:-"heap-direct-summary-only heap-epoch-retained-no-traverse checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"RetainedEpochReclaimMatrix\")" \
    nativeLink
fi

binary=${RETAINED_EPOCH_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/RetainedEpochReclaimMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing RetainedEpochReclaimMatrix native binary; set RETAINED_EPOCH_BINARY or enable RETAINED_EPOCH_BUILD" >&2
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
  printf "mode\tstatus\ttopology\trecords\trecords_per_epoch\tkey_buckets\tmedian_ms\tmin_ms\tmax_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local mode="$1"
  local run_status="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=retained-epoch-reclaim-${mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${mode}" \
    "${run_status}" \
    "${fields[topology]-}" \
    "${fields[records]-}" \
    "${fields[records_per_epoch]-}" \
    "${fields[key_buckets]-}" \
    "${fields[median_ms]-}" \
    "${fields[min_ms]-}" \
    "${fields[max_ms]-}" \
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
  local run_status="$2"
  local max_rss_bytes="$3"
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${mode}" "${run_status}" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "" "${max_rss_bytes}" >> "${summary}"
}

run_case() {
  local mode="$1"
  local binary_mode="${mode}"
  local roots_mode=""
  local page_size="${SAFEZONE_PAGE_SIZE:-}"
  local run_log="${output_dir}/run-${mode}.log"
  local time_log="${output_dir}/time-${mode}.log"
  local max_rss_bytes
  local command_status
  local -a env_args

  case "${mode}" in
    checked-scoped-epoch-retained-no-traverse|checked-region-scoped-retained-epoch)
      binary_mode="checked-scoped-epoch-retained-no-traverse"
      roots_mode="1"
      page_size="32768"
      ;;
    checked-epoch-retained-no-traverse|checked-region-stream-retained-epoch)
      binary_mode="checked-epoch-retained-no-traverse"
      ;;
    heap-direct-summary-only|heap-direct-epoch)
      binary_mode="heap-direct-summary-only"
      ;;
    heap-epoch-retained-no-traverse)
      binary_mode="heap-epoch-retained-no-traverse"
      ;;
  esac

  env_args=()
  if [[ -n "${roots_mode}" ]]; then
    env_args+=(SAFEZONE_ROOTS_MODE="${roots_mode}")
  fi
  if [[ -n "${page_size}" ]]; then
    env_args+=(SAFEZONE_PAGE_SIZE="${page_size}")
  fi

  echo
  echo "== retained epoch / ${mode} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    env "${env_args[@]}" /usr/bin/time -l "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
  else
    env "${env_args[@]}" /usr/bin/time -v "${binary}" "${binary_mode}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  if ! grep -q "^RESULT name=retained-epoch-reclaim-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    max_rss_bytes=$(read_max_rss_bytes "${time_log}")
    echo "RETAINED_EPOCH_RESULT mode=${mode} status=failed exit_status=${command_status} max_rss_bytes=${max_rss_bytes}" >&2
    write_failed_row "${mode}" "failed:${command_status}" "${max_rss_bytes}"
    return 0
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=retained-epoch-reclaim-${binary_mode} " "${run_log}"
  echo "RETAINED_EPOCH_RSS_RESULT mode=${mode} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${binary_mode}" "ok" "${run_log}" "${max_rss_bytes}"
}

write_summary_header
for mode in "${modes[@]}"; do
  run_case "${mode}"
done

echo
echo "summary: ${summary}"
