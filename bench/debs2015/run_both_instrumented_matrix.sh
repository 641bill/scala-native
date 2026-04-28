#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_BOTH_INPUT:-"${script_dir}/sample_both.csv"}
output_dir=${DEBS2015_BOTH_OUTPUT_DIR:-"/tmp/debs2015-runboth-instrumented"}
summary=${DEBS2015_BOTH_SUMMARY:-"${output_dir}/summary.tsv"}
build=${DEBS2015_BOTH_BUILD:-1}
modes_text=${DEBS2015_BOTH_MODES:-"heap rift-hp rift-streaming"}
modes=(${=modes_text})
platform=$(uname -s)

metric_keys=(
  events
  parsed
  invalid
  q1_outputs
  q2_outputs
  elapsed_ms
  throughput_eps
  q1_p50_ms
  q1_p99_ms
  q1_p999_ms
  q1_max_ms
  q2_p50_ms
  q2_p99_ms
  q2_p999_ms
  q2_max_ms
  phase_read_ns
  phase_parse_ns
  phase_q1_process_ns
  phase_q1_change_ns
  phase_q1_output_ns
  phase_q1_snapshot_ns
  phase_q2_process_ns
  phase_q2_change_ns
  phase_q2_output_ns
  phase_q2_snapshot_ns
  phase_close_ns
  phase_tracked_ns
  phase_untracked_ns
  phase_read_gc_alloc_total
  phase_read_gc_alloc_bytes
  phase_read_gc_alloc_time_ns
  phase_parse_gc_alloc_total
  phase_parse_gc_alloc_bytes
  phase_parse_gc_alloc_time_ns
  phase_q1_process_gc_alloc_total
  phase_q1_process_gc_alloc_bytes
  phase_q1_process_gc_alloc_time_ns
  phase_q1_change_gc_alloc_total
  phase_q1_change_gc_alloc_bytes
  phase_q1_change_gc_alloc_time_ns
  phase_q1_output_gc_alloc_total
  phase_q1_output_gc_alloc_bytes
  phase_q1_output_gc_alloc_time_ns
  phase_q1_snapshot_gc_alloc_total
  phase_q1_snapshot_gc_alloc_bytes
  phase_q1_snapshot_gc_alloc_time_ns
  phase_q2_process_gc_alloc_total
  phase_q2_process_gc_alloc_bytes
  phase_q2_process_gc_alloc_time_ns
  phase_q2_change_gc_alloc_total
  phase_q2_change_gc_alloc_bytes
  phase_q2_change_gc_alloc_time_ns
  phase_q2_output_gc_alloc_total
  phase_q2_output_gc_alloc_bytes
  phase_q2_output_gc_alloc_time_ns
  phase_q2_snapshot_gc_alloc_total
  phase_q2_snapshot_gc_alloc_bytes
  phase_q2_snapshot_gc_alloc_time_ns
  phase_close_gc_alloc_total
  phase_close_gc_alloc_bytes
  phase_close_gc_alloc_time_ns
  gc_collections
  gc_time_ns
  gc_alloc_total
  gc_alloc_bytes_total
  gc_alloc_time_ns
  rift_region_op_ns
  rift_open_ns
  rift_close_ns
  rift_reset_ns
  rift_slow_alloc_ns
  rift_open_total
  rift_close_total
  rift_reset_total
  rift_alloc_raw_total
  rift_alloc_raw_bytes_total
  rift_alloc_object_total
  rift_alloc_slow_total
  rift_mmap_slab_total
  rift_mmap_bytes_total
  rift_mmap_slab_current
  rift_mmap_slab_peak
  rift_mmap_bytes_current
  rift_mmap_bytes_peak
  rift_active_slab_current
  rift_active_slab_peak
  rift_active_bytes_current
  rift_active_bytes_peak
  rift_active_alloc_bytes_current
  rift_active_alloc_bytes_peak
  rift_tls_reuse_total
  rift_pool_reuse_total
  rift_pool_slabs
  rift_pool_bytes
  diag_grid_q1_calls
  diag_grid_q1_hits
  diag_grid_q2_calls
  diag_grid_q2_hits
  diag_q1_rank_adds
  diag_q1_rank_removes
  diag_q1_rank_created
  diag_q1_top10_calls
  diag_q1_result_array_allocs
  diag_q1_result_array_slots
  diag_q2_rank_adds
  diag_q2_rank_removes
  diag_q2_rank_fixes
  diag_q2_rank_created
  diag_q2_rank_heap_compares
  diag_q2_rank_heap_swaps
  diag_q2_top_candidate_compares
  diag_q2_top10_calls
  diag_q2_top10_recomputes
  diag_q2_result_array_allocs
  diag_q2_result_array_slots
  diag_q2_median_computes
  diag_q2_median_values_sorted
  diag_q2_median_reads
  diag_q2_median_heap_adds
  diag_q2_median_heap_removes
  diag_q2_median_rebalances
  diag_q1_snapshot_allocs
  diag_q1_snapshot_slots
  diag_q2_snapshot_allocs
  diag_q2_snapshot_array_allocs
  diag_q2_snapshot_slots
  diag_q1_latency_appends
  diag_q2_latency_appends
  diag_q2_changed_calls
  diag_q2_changed_element_checks
  diag_taxi_lookups
  diag_taxi_hits
  diag_taxi_misses
  diag_taxi_entries_scanned
  diag_taxi_entries_created
)

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
    nativeLink
fi

binary=${DEBS2015_BOTH_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/debs2015.Debs2015RunBoth" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing Debs2015RunBoth native binary; set DEBS2015_BOTH_BINARY or enable DEBS2015_BOTH_BUILD" >&2
  exit 1
fi

write_summary_header() {
  printf "q1_mode" > "${summary}"
  local key
  for key in "${metric_keys[@]}"; do
    printf "\t%s" "${key}" >> "${summary}"
  done
  printf "\tmax_rss_bytes\ttime_real_s\ttime_user_s\ttime_sys_s\n" >> "${summary}"
}

read_max_rss_bytes() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/maximum resident set size/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/Maximum resident set size/ { gsub(/^[ \t]+/, "", $2); print $2 * 1024; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

read_time_real_seconds() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/ real/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk '
      function seconds(value, parts, n) {
        gsub(/^[ \t]+|[ \t]+$/, "", value)
        n = split(value, parts, ":")
        if (n == 3) return parts[1] * 3600 + parts[2] * 60 + parts[3]
        if (n == 2) return parts[1] * 60 + parts[2]
        return value
      }
      /Elapsed \(wall clock\) time/ {
        value = $0
        sub(/^.*: /, "", value)
        print seconds(value)
        found = 1
        exit
      }
      END { if (!found) print "" }
    ' "${time_log}"
  fi
}

read_time_user_seconds() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/ real/ { print $3; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/User time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

read_time_sys_seconds() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/ real/ { print $5; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/System time \(seconds\)/ { gsub(/^[ \t]+/, "", $2); print $2; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

write_summary_row() {
  local mode="$1"
  local metric="$2"
  local max_rss_bytes="$3"
  local time_real_s="$4"
  local time_user_s="$5"
  local time_sys_s="$6"
  typeset -A fields
  local token key value

  for token in ${(z)metric}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s" "${fields[q1_mode]-${mode}}" >> "${summary}"
  for key in "${metric_keys[@]}"; do
    printf "\t%s" "${fields[${key}]-}" >> "${summary}"
  done
  printf "\t%s\t%s\t%s\t%s\n" "${max_rss_bytes}" "${time_real_s}" "${time_user_s}" "${time_sys_s}" >> "${summary}"
}

run_mode() {
  local mode="$1"
  local q1_output="${output_dir}/q1-${mode}.out"
  local q2_output="${output_dir}/q2-${mode}.out"
  local run_log="${output_dir}/run-${mode}.log"
  local time_log="${output_dir}/time-${mode}.log"
  local metric
  local max_rss_bytes
  local time_real_s
  local time_user_s
  local time_sys_s

  echo
  echo "== Instrumented RunBoth Q1 ${mode} =="
  if [[ "${platform}" == "Darwin" ]]; then
    /usr/bin/time -l "${binary}" "${input}" "${q1_output}" "${q2_output}" "${mode}" > "${run_log}" 2> "${time_log}"
  else
    /usr/bin/time -v "${binary}" "${input}" "${q1_output}" "${q2_output}" "${mode}" > "${run_log}" 2> "${time_log}"
  fi

  metric=$(grep "DEBS2015_RUNBOTH_RESULT" "${run_log}" | tail -n 1)
  if [[ -z "${metric}" ]]; then
    echo "missing DEBS2015_RUNBOTH_RESULT in ${run_log}" >&2
    exit 1
  fi

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  time_real_s=$(read_time_real_seconds "${time_log}")
  time_user_s=$(read_time_user_seconds "${time_log}")
  time_sys_s=$(read_time_sys_seconds "${time_log}")
  echo "${metric}"
  echo "DEBS2015_RSS_RESULT q1_mode=${mode} max_rss_bytes=${max_rss_bytes} time_real_s=${time_real_s} time_user_s=${time_user_s} time_sys_s=${time_sys_s}"
  write_summary_row "${mode}" "${metric}" "${max_rss_bytes}" "${time_real_s}" "${time_user_s}" "${time_sys_s}"
}

strip_latency() {
  awk -F ',' 'BEGIN { OFS = "," } { NF -= 1; print }' "$1"
}

write_summary_header

for mode in "${modes[@]}"; do
  run_mode "${mode}"
done

if [[ -f "${output_dir}/q1-heap.out" ]]; then
  for mode in "${modes[@]}"; do
    if [[ "${mode}" != "heap" ]]; then
      diff -u <(strip_latency "${output_dir}/q1-heap.out") <(strip_latency "${output_dir}/q1-${mode}.out")
      diff -u <(strip_latency "${output_dir}/q2-heap.out") <(strip_latency "${output_dir}/q2-${mode}.out")
    fi
  done
fi

echo
echo "RunBoth instrumented matrix outputs match in ${output_dir}"
echo "Summary: ${summary}"
