#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${CHECKED_BUFFER_OUTPUT_DIR:-"/tmp/checked-region-buffer"}
summary=${CHECKED_BUFFER_SUMMARY:-"${output_dir}/summary.tsv"}
build=${CHECKED_BUFFER_BUILD:-1}
platform=$(uname -s)
modes=(${(z)${CHECKED_BUFFER_MODES:-"heap-buffer heap-array rift-checked-buffer rift-checked-object-buffer rift-checked-array"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"CheckedRegionBufferMatrix\")" \
    nativeLink
fi

binary=${CHECKED_BUFFER_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/CheckedRegionBufferMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing CheckedRegionBufferMatrix native binary; set CHECKED_BUFFER_BINARY or enable CHECKED_BUFFER_BUILD" >&2
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
  printf "mode\tmedian_ms\tmedian_gc_ms\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local mode="$1"
  local run_log="$2"
  local max_rss_bytes="$3"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=checked-buffer-" "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
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
  local mode="$1"
  local run_log="${output_dir}/run-${mode}.log"
  local time_log="${output_dir}/time-${mode}.log"
  local max_rss_bytes

  echo
  echo "== ${mode} =="
  if [[ "${platform}" == "Darwin" ]]; then
    /usr/bin/time -l "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  else
    /usr/bin/time -v "${binary}" "${mode}" > "${run_log}" 2> "${time_log}"
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  grep "^RESULT name=checked-buffer-" "${run_log}"
  echo "CHECKED_BUFFER_RSS_RESULT mode=${mode} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${mode}" "${run_log}" "${max_rss_bytes}"
}

write_summary_header
for mode in "${modes[@]}"; do
  run_mode "${mode}"
done

echo
echo "Checked RegionBuffer matrix complete"
echo "Summary: ${summary}"
