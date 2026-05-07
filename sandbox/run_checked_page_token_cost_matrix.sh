#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR:-"/tmp/checked-page-token-cost"}
summary=${CHECKED_PAGE_TOKEN_COST_SUMMARY:-"${output_dir}/summary.tsv"}
build=${CHECKED_PAGE_TOKEN_COST_BUILD:-1}
platform=$(uname -s)
modes=(${(z)${CHECKED_PAGE_TOKEN_COST_MODES:-"heap-same-shape safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token"}})
workloads=(${(z)${CHECKED_PAGE_TOKEN_COST_WORKLOADS:-"append-only append-drain append-aggregate"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"CheckedPageTokenCostMatrix\")" \
    nativeLink
fi

binary=${CHECKED_PAGE_TOKEN_COST_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/CheckedPageTokenCostMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing CheckedPageTokenCostMatrix native binary; set CHECKED_PAGE_TOKEN_COST_BINARY or enable CHECKED_PAGE_TOKEN_COST_BUILD" >&2
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
  printf "workload\tmode\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local workload="$1"
  local mode="$2"
  local run_log="$3"
  local max_rss_bytes="$4"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=checked-page-token-cost-" "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${workload}" \
    "${mode}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[checksum]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

run_mode() {
  local workload="$1"
  local mode="$2"
  local run_log="${output_dir}/run-${workload}-${mode}.log"
  local time_log="${output_dir}/time-${workload}-${mode}.log"
  local max_rss_bytes
  local command_status
  local roots_mode=""
  local page_size=""

  case "${mode}" in
    safezone-improved-32k|rift-checked-safezone-page-token)
      roots_mode="1"
      page_size="32768"
      ;;
  esac

  echo
  echo "== ${workload} / ${mode} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
      /usr/bin/time -l "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_ROOTS_MODE="${roots_mode}" SAFEZONE_PAGE_SIZE="${page_size}" \
      /usr/bin/time -v "${binary}" "${mode}" "${workload}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  if ! grep -q "^RESULT name=checked-page-token-cost-" "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    exit "${command_status}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=checked-page-token-cost-" "${run_log}"
  grep "^PAGE_TOKEN_COST_DIAG" "${run_log}" || true
  echo "CHECKED_PAGE_TOKEN_COST_RSS_RESULT workload=${workload} mode=${mode} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${workload}" "${mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header
for workload in "${workloads[@]}"; do
  for mode in "${modes[@]}"; do
    run_mode "${workload}" "${mode}"
  done
done

echo
echo "Checked page-token cost matrix complete"
echo "Summary: ${summary}"
