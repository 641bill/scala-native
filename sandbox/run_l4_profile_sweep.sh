#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
parent_dir=${repo_dir:h}
output_dir=${RIFT_PROFILE_OUTPUT_DIR:-"${parent_dir}/cache/profile-sweep-$(date +%Y%m%d-%H%M%S)"}
summary=${RIFT_PROFILE_SUMMARY:-"${output_dir}/summary.tsv"}
build=${RIFT_PROFILE_BUILD:-1}
seconds=${RIFT_PROFILE_SECONDS:-5}
delay=${RIFT_PROFILE_DELAY_SECONDS:-0.5}
platform=$(uname -s)

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

default_cases=(
  streamflex-design-checked
  streamflex-design-heap
  commoncrawl-q2-checked-scoped
  commoncrawl-q2-heap
  dspbench-fraud-q2-checked-scoped
  dspbench-fraud-q2-heap
  loghub-hdfs-stream-topk-checked
  loghub-hdfs-stream-topk-heap
  streamit-beamformer-checked
  streamit-beamformer-heap
)

cases=(${(z)${RIFT_PROFILE_CASES:-"streamflex-design-checked streamflex-design-heap"}})
if [[ "${#cases[@]}" == "1" && "${cases[1]}" == "all" ]]; then
  cases=("${default_cases[@]}")
fi

write_summary_header() {
  printf "case\tstatus\ttool\tseconds\tprofile_path\tmain_class\targs\tenv\tpid\texit_status\n" > "${summary}"
}

append_summary() {
  local case_name="$1"
  local case_status="$2"
  local tool="$3"
  local profile_path="$4"
  local main_class="$5"
  local args="$6"
  local env_spec="$7"
  local pid="$8"
  local exit_status="$9"

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${case_name}" "${case_status}" "${tool}" "${seconds}" "${profile_path}" \
    "${main_class}" "${args}" "${env_spec}" "${pid}" "${exit_status}" >> "${summary}"
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

find_binary() {
  local main_class="$1"
  find sandbox/.3-next/target -path "*/native/${main_class}" -type f -perm -111 -print | sort | tail -n 1
}

case_config() {
  local case_name="$1"
  case "${case_name}" in
    streamflex-design-checked)
      main_class="StreamFlexDesignMatrix"
      args="checked-epoch-scoped throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMFLEX_DESIGN_EVENTS=${RIFT_PROFILE_STREAMFLEX_EVENTS:-20000000} STREAMFLEX_DESIGN_BENCHMARK_RUNS=1 STREAMFLEX_DESIGN_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    streamflex-design-heap)
      main_class="StreamFlexDesignMatrix"
      args="gc-heap throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMFLEX_DESIGN_EVENTS=${RIFT_PROFILE_STREAMFLEX_EVENTS:-20000000} STREAMFLEX_DESIGN_BENCHMARK_RUNS=1 STREAMFLEX_DESIGN_WARMUPS=0"
      ;;
    commoncrawl-q2-checked-scoped)
      main_class="CommonCrawlWetMatrix"
      args="rift-checked-safezone-page-token q2-domain-window"
      env_spec="RIFT_FINAL_CLEAN=1 COMMON_CRAWL_WET_PAGES=${RIFT_PROFILE_COMMON_CRAWL_PAGES:-2000000} COMMON_CRAWL_WET_BENCHMARK_RUNS=1 COMMON_CRAWL_WET_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    commoncrawl-q2-heap)
      main_class="CommonCrawlWetMatrix"
      args="heap q2-domain-window"
      env_spec="RIFT_FINAL_CLEAN=1 COMMON_CRAWL_WET_PAGES=${RIFT_PROFILE_COMMON_CRAWL_PAGES:-2000000} COMMON_CRAWL_WET_BENCHMARK_RUNS=1 COMMON_CRAWL_WET_WARMUPS=0"
      ;;
    dspbench-fraud-q2-checked-scoped)
      main_class="DSPBenchRegionMatrix"
      args="rift-checked-safezone-page-token fraud-q2-alert-window"
      env_spec="RIFT_FINAL_CLEAN=1 DSPBENCH_INPUT_MODE=${RIFT_PROFILE_DSPBENCH_INPUT_MODE:-file-backed} DSPBENCH_EVENTS=${RIFT_PROFILE_DSPBENCH_EVENTS:-5000000} DSPBENCH_BENCHMARK_RUNS=1 DSPBENCH_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    dspbench-fraud-q2-heap)
      main_class="DSPBenchRegionMatrix"
      args="heap fraud-q2-alert-window"
      env_spec="RIFT_FINAL_CLEAN=1 DSPBENCH_INPUT_MODE=${RIFT_PROFILE_DSPBENCH_INPUT_MODE:-file-backed} DSPBENCH_EVENTS=${RIFT_PROFILE_DSPBENCH_EVENTS:-5000000} DSPBENCH_BENCHMARK_RUNS=1 DSPBENCH_WARMUPS=0"
      ;;
    loghub-hdfs-stream-topk-checked)
      main_class="LogHubTopTemplatesMatrix"
      args="checked-scoped-epoch-topk-retained-no-traverse"
      env_spec="RIFT_FINAL_CLEAN=1 LOGHUB_TOP_INPUT_MODE=streaming-file LOGHUB_TOP_INPUT=${RIFT_PROFILE_LOGHUB_HDFS_INPUT:-${parent_dir}/cache/benchmark-data/loghub/HDFS_1/HDFS.log} LOGHUB_TOP_LINES=${RIFT_PROFILE_LOGHUB_LINES:-5000000} LOGHUB_TOP_LINES_PER_EPOCH=${RIFT_PROFILE_LOGHUB_LINES_PER_EPOCH:-100000} LOGHUB_TOP_BENCHMARK_RUNS=1 LOGHUB_TOP_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    loghub-hdfs-stream-topk-heap)
      main_class="LogHubTopTemplatesMatrix"
      args="heap-retained-drop-anchor"
      env_spec="RIFT_FINAL_CLEAN=1 LOGHUB_TOP_INPUT_MODE=streaming-file LOGHUB_TOP_INPUT=${RIFT_PROFILE_LOGHUB_HDFS_INPUT:-${parent_dir}/cache/benchmark-data/loghub/HDFS_1/HDFS.log} LOGHUB_TOP_LINES=${RIFT_PROFILE_LOGHUB_LINES:-5000000} LOGHUB_TOP_LINES_PER_EPOCH=${RIFT_PROFILE_LOGHUB_LINES_PER_EPOCH:-100000} LOGHUB_TOP_BENCHMARK_RUNS=1 LOGHUB_TOP_WARMUPS=0"
      ;;
    streamit-beamformer-checked)
      main_class="StreamItKernelMatrix"
      args="checked-epoch-scoped beamformer throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_BEAMFORMER_FRAMES=${RIFT_PROFILE_STREAMIT_BEAMFORMER_FRAMES:-3000000} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    streamit-beamformer-heap)
      main_class="StreamItKernelMatrix"
      args="heap beamformer throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_BEAMFORMER_FRAMES=${RIFT_PROFILE_STREAMIT_BEAMFORMER_FRAMES:-3000000} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0"
      ;;
    *)
      echo "unknown RIFT_PROFILE_CASES entry: ${case_name}" >&2
      exit 1
      ;;
  esac
}

profile_case() {
  local case_name="$1"
  local main_class args env_spec binary profile run_log err_log out_log pid command_status sample_status tool
  main_class=""
  args=""
  env_spec=""
  case_config "${case_name}"
  build_main "${main_class}"
  binary=$(find_binary "${main_class}")
  if [[ -z "${binary}" || ! -x "${binary}" ]]; then
    echo "missing native binary for ${main_class}" >&2
    append_summary "${case_name}" "missing-binary" "" "" "${main_class}" "${args}" "${env_spec}" "" ""
    return 0
  fi

  profile="${output_dir}/${case_name}.sample.txt"
  run_log="${output_dir}/${case_name}.run.log"
  err_log="${output_dir}/${case_name}.run.err"
  out_log="${output_dir}/${case_name}.profiler.out"

  echo
  echo "== profile ${case_name} =="
  echo "main=${main_class} args=${args}"
  echo "env=${env_spec}"

  local -a env_args arg_list
  env_args=(${(z)env_spec})
  arg_list=(${(z)args})

  set +e
  env "${env_args[@]}" "${binary}" "${arg_list[@]}" > "${run_log}" 2> "${err_log}" &
  pid=$!
  sleep "${delay}"
  if [[ "${platform}" == "Darwin" ]]; then
    tool="sample"
    /usr/bin/sample "${pid}" "${seconds}" -file "${profile}" > "${out_log}" 2>&1
    sample_status=$?
  else
    tool="perf"
    perf record -F 99 -g -p "${pid}" -o "${profile}.perf.data" -- sleep "${seconds}" > "${out_log}" 2>&1
    sample_status=$?
    profile="${profile}.perf.data"
  fi
  wait "${pid}"
  command_status=$?
  set -e

  if [[ "${sample_status}" == "0" && -s "${profile}" ]]; then
    append_summary "${case_name}" "ok" "${tool}" "${profile}" "${main_class}" "${args}" "${env_spec}" "${pid}" "${command_status}"
  else
    cat "${out_log}" >&2 || true
    append_summary "${case_name}" "profile-failed:${sample_status}" "${tool}" "${profile}" "${main_class}" "${args}" "${env_spec}" "${pid}" "${command_status}"
  fi
  tail -n 2 "${run_log}" || true
}

write_summary_header
for case_name in "${cases[@]}"; do
  profile_case "${case_name}"
done

echo
echo "L4 profile sweep complete"
echo "Summary: ${summary}"
