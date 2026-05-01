#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${SAFEZONE_COST_OUTPUT_DIR:-"/tmp/safezone-cost-matrix"}
summary=${SAFEZONE_COST_SUMMARY:-"${output_dir}/summary.tsv"}
build=${SAFEZONE_COST_BUILD:-1}
platform=$(uname -s)
configs=(${(z)${SAFEZONE_COST_CONFIGS:-"current-default:0: improved-default:1: chunk-default:2: unsafe-hp-32k:3:32768 improved-32k:1:32768"}})
benches=(${(z)${SAFEZONE_COST_BENCHES:-"gcbench listoflists-linked listoflists-flat dataflow common-crawl-q1"}})
runs=${SAFEZONE_COST_RUNS:-${RIFT_EVAL_RUNS:-3}}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"
export GCBENCH_BENCHMARK_RUNS="${GCBENCH_BENCHMARK_RUNS:-${runs}}"
export LISTBENCH_BENCHMARK_RUNS="${LISTBENCH_BENCHMARK_RUNS:-${runs}}"
export DATAFLOW_BENCHMARK_RUNS="${DATAFLOW_BENCHMARK_RUNS:-${runs}}"
export COMMON_CRAWL_WET_BENCHMARK_RUNS="${COMMON_CRAWL_WET_BENCHMARK_RUNS:-${runs}}"
export COMMON_CRAWL_WET_WARMUPS="${COMMON_CRAWL_WET_WARMUPS:-${SAFEZONE_COST_COMMON_CRAWL_WARMUPS:-1}}"
export COMMON_CRAWL_WET_PAGES="${COMMON_CRAWL_WET_PAGES:-${SAFEZONE_COST_COMMON_CRAWL_PAGES:-100000}}"

mkdir -p "${output_dir}"
cd "${repo_dir}"

read_max_rss_bytes() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/maximum resident set size/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/Maximum resident set size/ { gsub(/^[ \t]+/, "", $2); print $2 * 1024; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

trace_value() {
  local time_log="$1"
  local key="$2"
  awk -v key="${key}" '{
    for (i = 1; i <= NF; i++) {
      if ($i ~ "^" key "=") {
        split($i, parts, "=")
        print parts[2]
        found = 1
        exit
      }
    }
  } END { if (!found) print "" }' "${time_log}"
}

write_summary_header() {
  printf "benchmark\tname\tconfig\troots_mode\tpage_size\tmedian_ms\tavg_ms\tmin_ms\tmax_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tchecksum\toutput_count\tmax_rss_bytes\tclaim_calls\treclaim_calls\treclaimed_pages\troot_add_calls\troot_remove_calls\troot_add_claim_calls\troot_add_chunk_calls\troot_remove_coalesced_pages\tchunk_allocs\tpage_allocs\tclaim_time_ms\treclaim_time_ms\troot_add_time_ms\troot_remove_time_ms\treclaim_sort_calls\treclaim_runs\treclaim_sort_time_ms\treclaim_bookkeeping_time_ms\tchunk_alloc_time_ms\tpage_alloc_time_ms\n" > "${summary}"
}

field_from_result() {
  local line="$1"
  local key="$2"
  awk -v key="${key}" '{
    for (i = 1; i <= NF; i++) {
      if ($i ~ "^" key "=") {
        split($i, parts, "=")
        print parts[2]
        found = 1
        exit
      }
    }
  } END { if (!found) print "" }' <<< "${line}"
}

write_result_rows() {
  local bench="$1"
  local config_label="$2"
  local roots_mode="$3"
  local page_size="$4"
  local run_log="$5"
  local time_log="$6"
  local max_rss_bytes="$7"
  local line name median_ms avg_ms min_ms max_ms median_gc_ms max_gc_ms runs_with_gc checksum output_count

  while IFS= read -r line; do
    name=$(field_from_result "${line}" "name")
    median_ms=$(field_from_result "${line}" "median_ms")
    avg_ms=$(field_from_result "${line}" "avg_ms")
    min_ms=$(field_from_result "${line}" "min_ms")
    max_ms=$(field_from_result "${line}" "max_ms")
    median_gc_ms=$(field_from_result "${line}" "median_gc_ms")
    max_gc_ms=$(field_from_result "${line}" "max_gc_ms")
    runs_with_gc=$(field_from_result "${line}" "runs_with_gc")
    checksum=$(field_from_result "${line}" "checksum")
    output_count=$(field_from_result "${line}" "output_count")

    printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
      "${bench}" \
      "${name}" \
      "${config_label}" \
      "${roots_mode}" \
      "${page_size:-default}" \
      "${median_ms}" \
      "${avg_ms}" \
      "${min_ms}" \
      "${max_ms}" \
      "${median_gc_ms}" \
      "${max_gc_ms}" \
      "${runs_with_gc}" \
      "${checksum}" \
      "${output_count}" \
      "${max_rss_bytes}" \
      "$(trace_value "${time_log}" claim_calls)" \
      "$(trace_value "${time_log}" reclaim_calls)" \
      "$(trace_value "${time_log}" reclaimed_pages)" \
      "$(trace_value "${time_log}" root_add_calls)" \
      "$(trace_value "${time_log}" root_remove_calls)" \
      "$(trace_value "${time_log}" root_add_claim_calls)" \
      "$(trace_value "${time_log}" root_add_chunk_calls)" \
      "$(trace_value "${time_log}" root_remove_coalesced_pages)" \
      "$(trace_value "${time_log}" chunk_allocs)" \
      "$(trace_value "${time_log}" page_allocs)" \
      "$(trace_value "${time_log}" claim_time_ms)" \
      "$(trace_value "${time_log}" reclaim_time_ms)" \
      "$(trace_value "${time_log}" root_add_time_ms)" \
      "$(trace_value "${time_log}" root_remove_time_ms)" \
      "$(trace_value "${time_log}" reclaim_sort_calls)" \
      "$(trace_value "${time_log}" reclaim_runs)" \
      "$(trace_value "${time_log}" reclaim_sort_time_ms)" \
      "$(trace_value "${time_log}" reclaim_bookkeeping_time_ms)" \
      "$(trace_value "${time_log}" chunk_alloc_time_ms)" \
      "$(trace_value "${time_log}" page_alloc_time_ms)" >> "${summary}"
  done < <(grep "^RESULT name=" "${run_log}")
}

build_main() {
  local main_class="$1"
  if [[ "${build}" != "0" ]]; then
    sbt \
      "project sandbox3_next" \
      "set Compile / mainClass := Some(\"${main_class}\")" \
      nativeLink
  fi
}

native_binary() {
  local main_class="$1"
  find sandbox/.3-next/target -path "*/native/${main_class}" -type f -perm -111 -print | sort | tail -n 1
}

run_binary() {
  local bench="$1"
  local main_class="$2"
  local config_label="$3"
  local roots_mode="$4"
  local page_size="$5"
  shift 5
  local -a command_args=("$@")
  local binary run_log time_log command_status max_rss_bytes

  binary=$(native_binary "${main_class}")
  if [[ -z "${binary}" || ! -x "${binary}" ]]; then
    echo "missing ${main_class} native binary; set SAFEZONE_COST_BUILD=1" >&2
    exit 1
  fi

  run_log="${output_dir}/run-${bench}-${config_label}.log"
  time_log="${output_dir}/time-${bench}-${config_label}.log"

  echo
  echo "== ${bench} / ${config_label} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    SAFEZONE_TRACE=1 \
    SAFEZONE_ROOTS_MODE="${roots_mode}" \
    SAFEZONE_PAGE_SIZE="${page_size}" \
    /usr/bin/time -l "${binary}" "${command_args[@]}" > "${run_log}" 2> "${time_log}"
  else
    SAFEZONE_TRACE=1 \
    SAFEZONE_ROOTS_MODE="${roots_mode}" \
    SAFEZONE_PAGE_SIZE="${page_size}" \
    /usr/bin/time -v "${binary}" "${command_args[@]}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  if [[ "${command_status}" != "0" ]] || ! grep -q "^RESULT name=" "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    exit "${command_status}"
  fi

  grep "^RESULT name=" "${run_log}"
  grep "^\[SafeZoneTracePool\]" "${time_log}" || true
  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  echo "SAFEZONE_COST_RSS_RESULT benchmark=${bench} config=${config_label} roots_mode=${roots_mode} page_size=${page_size:-default} max_rss_bytes=${max_rss_bytes}"
  write_result_rows "${bench}" "${config_label}" "${roots_mode}" "${page_size}" "${run_log}" "${time_log}" "${max_rss_bytes}"
}

run_bench() {
  local bench="$1"
  local main_class args_mode extra_arg

  case "${bench}" in
    gcbench)
      main_class="GCBenchRuntimeMatrix"
      build_main "${main_class}"
      for config in "${configs[@]}"; do
        local parts=(${(s/:/)config})
        run_binary "${bench}" "${main_class}" "${parts[1]}" "${parts[2]}" "${parts[3]:-}" "safezone"
      done
      ;;
    listoflists-linked)
      main_class="ListOfListsRuntimeMatrix"
      build_main "${main_class}"
      for config in "${configs[@]}"; do
        local parts=(${(s/:/)config})
        run_binary "${bench}" "${main_class}" "${parts[1]}" "${parts[2]}" "${parts[3]:-}" "safezone"
      done
      ;;
    listoflists-flat)
      main_class="ListOfListsFlatMatrix"
      build_main "${main_class}"
      for config in "${configs[@]}"; do
        local parts=(${(s/:/)config})
        run_binary "${bench}" "${main_class}" "${parts[1]}" "${parts[2]}" "${parts[3]:-}" "safezone"
      done
      ;;
    dataflow)
      main_class="DataflowRegionMatrix"
      build_main "${main_class}"
      for config in "${configs[@]}"; do
        local parts=(${(s/:/)config})
        run_binary "${bench}" "${main_class}" "${parts[1]}" "${parts[2]}" "${parts[3]:-}" "safezone" "${SAFEZONE_COST_DATAFLOW_OPERATOR:-all}"
      done
      ;;
    common-crawl-q1)
      main_class="CommonCrawlWetMatrix"
      build_main "${main_class}"
      for config in "${configs[@]}"; do
        local parts=(${(s/:/)config})
        run_binary "${bench}" "${main_class}" "${parts[1]}" "${parts[2]}" "${parts[3]:-}" "safezone" "q1-tokenize"
      done
      ;;
    *)
      echo "unknown SAFEZONE_COST_BENCHES entry '${bench}'" >&2
      exit 1
      ;;
  esac
}

write_summary_header
for bench in "${benches[@]}"; do
  run_bench "${bench}"
done

echo
echo "SafeZone cost matrix complete"
echo "Summary: ${summary}"
