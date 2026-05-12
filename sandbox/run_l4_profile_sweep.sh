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
  streamit-filterbank-checked
  streamit-filterbank-heap
  streamit-beamformer-checked
  streamit-beamformer-heap
  yak-graphreal-checked-scoped
  yak-graphreal-heap
  yak-graphstep-checked-scoped
  yak-graphstep-heap
  dataflow-aggregate-checked-scoped
  dataflow-aggregate-epoch-fold
  dataflow-aggregate-heap
  specjbb-checked-scoped
  specjbb-heap
  reml-msort-checked-scoped
  reml-msort-heap
  reml-ratio-checked-scoped
  reml-ratio-heap
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
      env_spec="RIFT_FINAL_CLEAN=1 DSPBENCH_INPUT_MODE=${RIFT_PROFILE_DSPBENCH_INPUT_MODE:-file-backed} DSPBENCH_INPUT=${RIFT_PROFILE_DSPBENCH_INPUT:-${parent_dir}/cache/benchmark-data/dspbench/source/dspbench-threads/data/credit-card.dat} DSPBENCH_EVENTS=${RIFT_PROFILE_DSPBENCH_EVENTS:-5000000} DSPBENCH_BENCHMARK_RUNS=1 DSPBENCH_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    dspbench-fraud-q2-heap)
      main_class="DSPBenchRegionMatrix"
      args="heap fraud-q2-alert-window"
      env_spec="RIFT_FINAL_CLEAN=1 DSPBENCH_INPUT_MODE=${RIFT_PROFILE_DSPBENCH_INPUT_MODE:-file-backed} DSPBENCH_INPUT=${RIFT_PROFILE_DSPBENCH_INPUT:-${parent_dir}/cache/benchmark-data/dspbench/source/dspbench-threads/data/credit-card.dat} DSPBENCH_EVENTS=${RIFT_PROFILE_DSPBENCH_EVENTS:-5000000} DSPBENCH_BENCHMARK_RUNS=1 DSPBENCH_WARMUPS=0"
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
    streamit-filterbank-checked)
      main_class="StreamItKernelMatrix"
      args="checked-epoch-scoped filterbank throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_FILTERBANK_ITERATIONS=${RIFT_PROFILE_STREAMIT_FILTERBANK_ITERATIONS:-4096} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    streamit-filterbank-heap)
      main_class="StreamItKernelMatrix"
      args="heap filterbank throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_FILTERBANK_ITERATIONS=${RIFT_PROFILE_STREAMIT_FILTERBANK_ITERATIONS:-4096} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0"
      ;;
    streamit-beamformer-checked)
      main_class="StreamItKernelMatrix"
      args="checked-epoch-scoped beamformer throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_BEAMFORMER_FRAMES=${RIFT_PROFILE_STREAMIT_BEAMFORMER_FRAMES:-256} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    streamit-beamformer-heap)
      main_class="StreamItKernelMatrix"
      args="heap beamformer throughput"
      env_spec="RIFT_FINAL_CLEAN=1 STREAMIT_BEAMFORMER_FRAMES=${RIFT_PROFILE_STREAMIT_BEAMFORMER_FRAMES:-256} STREAMIT_BENCHMARK_RUNS=1 STREAMIT_WARMUPS=0"
      ;;
    yak-graphreal-checked-scoped)
      main_class="YakRegionMatrix"
      args="checked-epoch-scoped graphreal"
      env_spec="RIFT_FINAL_CLEAN=1 YAK_GRAPH_INPUT=${RIFT_PROFILE_YAK_GRAPH_INPUT:-${parent_dir}/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz} YAK_GRAPH_INPUT_EDGES=${RIFT_PROFILE_YAK_GRAPH_INPUT_EDGES:-50000000} YAK_GRAPH_INPUT_EDGES_PER_EPOCH=${RIFT_PROFILE_YAK_GRAPH_INPUT_EDGES_PER_EPOCH:-5000000} YAK_GRAPH_INPUT_VERTICES=${RIFT_PROFILE_YAK_GRAPH_INPUT_VERTICES:-5000000} YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    yak-graphreal-heap)
      main_class="YakRegionMatrix"
      args="heap graphreal"
      env_spec="RIFT_FINAL_CLEAN=1 YAK_GRAPH_INPUT=${RIFT_PROFILE_YAK_GRAPH_INPUT:-${parent_dir}/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz} YAK_GRAPH_INPUT_EDGES=${RIFT_PROFILE_YAK_GRAPH_INPUT_EDGES:-50000000} YAK_GRAPH_INPUT_EDGES_PER_EPOCH=${RIFT_PROFILE_YAK_GRAPH_INPUT_EDGES_PER_EPOCH:-5000000} YAK_GRAPH_INPUT_VERTICES=${RIFT_PROFILE_YAK_GRAPH_INPUT_VERTICES:-5000000} YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0"
      ;;
    yak-graphstep-checked-scoped)
      main_class="YakRegionMatrix"
      args="checked-epoch-scoped graphstep"
      env_spec="RIFT_FINAL_CLEAN=1 YAK_EPOCHS=${RIFT_PROFILE_YAK_EPOCHS:-10} YAK_MESSAGES_PER_EPOCH=${RIFT_PROFILE_YAK_MESSAGES_PER_EPOCH:-5000000} YAK_VERTICES=${RIFT_PROFILE_YAK_VERTICES:-5000000} YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    yak-graphstep-heap)
      main_class="YakRegionMatrix"
      args="heap graphstep"
      env_spec="RIFT_FINAL_CLEAN=1 YAK_EPOCHS=${RIFT_PROFILE_YAK_EPOCHS:-10} YAK_MESSAGES_PER_EPOCH=${RIFT_PROFILE_YAK_MESSAGES_PER_EPOCH:-5000000} YAK_VERTICES=${RIFT_PROFILE_YAK_VERTICES:-5000000} YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0"
      ;;
    dataflow-aggregate-checked-scoped)
      main_class="DataflowRegionMatrix"
      args="rift-checked-safezone-direct-epoch aggregate"
      env_spec="RIFT_FINAL_CLEAN=1 DATAFLOW_EPOCHS=${RIFT_PROFILE_DATAFLOW_EPOCHS:-20} DATAFLOW_DOCS_PER_EPOCH=${RIFT_PROFILE_DATAFLOW_DOCS_PER_EPOCH:-500000} DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    dataflow-aggregate-epoch-fold)
      main_class="DataflowRegionMatrix"
      args="rift-checked-epoch-fold aggregate"
      env_spec="RIFT_FINAL_CLEAN=1 DATAFLOW_EPOCHS=${RIFT_PROFILE_DATAFLOW_EPOCHS:-20} DATAFLOW_DOCS_PER_EPOCH=${RIFT_PROFILE_DATAFLOW_DOCS_PER_EPOCH:-500000} DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0"
      ;;
    dataflow-aggregate-heap)
      main_class="DataflowRegionMatrix"
      args="heap aggregate"
      env_spec="RIFT_FINAL_CLEAN=1 DATAFLOW_EPOCHS=${RIFT_PROFILE_DATAFLOW_EPOCHS:-20} DATAFLOW_DOCS_PER_EPOCH=${RIFT_PROFILE_DATAFLOW_DOCS_PER_EPOCH:-500000} DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0"
      ;;
    specjbb-checked-scoped)
      main_class="SpecJbb2005PortMatrix"
      args="rift-checked-safezone-direct-epoch"
      env_spec="RIFT_FINAL_CLEAN=1 SPECJBB_WAREHOUSES=${RIFT_PROFILE_SPECJBB_WAREHOUSES:-4} SPECJBB_ITERATIONS_PER_WAREHOUSE=${RIFT_PROFILE_SPECJBB_ITERATIONS_PER_WAREHOUSE:-250000} SPECJBB_BENCHMARK_RUNS=1 SPECJBB_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    specjbb-heap)
      main_class="SpecJbb2005PortMatrix"
      args="heap"
      env_spec="RIFT_FINAL_CLEAN=1 SPECJBB_WAREHOUSES=${RIFT_PROFILE_SPECJBB_WAREHOUSES:-4} SPECJBB_ITERATIONS_PER_WAREHOUSE=${RIFT_PROFILE_SPECJBB_ITERATIONS_PER_WAREHOUSE:-250000} SPECJBB_BENCHMARK_RUNS=1 SPECJBB_WARMUPS=0"
      ;;
    reml-msort-checked-scoped)
      main_class="ReMLRegionMatrix"
      args="msort checked-region-scoped"
      env_spec="RIFT_FINAL_CLEAN=1 REML_LIST_SIZE=${RIFT_PROFILE_REML_LIST_SIZE:-1000000} REML_BENCHMARK_RUNS=1 REML_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    reml-msort-heap)
      main_class="ReMLRegionMatrix"
      args="msort gc-heap"
      env_spec="RIFT_FINAL_CLEAN=1 REML_LIST_SIZE=${RIFT_PROFILE_REML_LIST_SIZE:-1000000} REML_BENCHMARK_RUNS=1 REML_WARMUPS=0"
      ;;
    reml-ratio-checked-scoped)
      main_class="ReMLRegionMatrix"
      args="ratio checked-region-scoped"
      env_spec="RIFT_FINAL_CLEAN=1 REML_RATIO_COUNT=${RIFT_PROFILE_REML_RATIO_COUNT:-2000000} REML_BENCHMARK_RUNS=1 REML_WARMUPS=0 SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768"
      ;;
    reml-ratio-heap)
      main_class="ReMLRegionMatrix"
      args="ratio gc-heap"
      env_spec="RIFT_FINAL_CLEAN=1 REML_RATIO_COUNT=${RIFT_PROFILE_REML_RATIO_COUNT:-2000000} REML_BENCHMARK_RUNS=1 REML_WARMUPS=0"
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
