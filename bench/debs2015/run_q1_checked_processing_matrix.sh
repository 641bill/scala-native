#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_Q1_CHECKED_PROCESSING_INPUT:-"${script_dir}/sample_q1.csv"}
output_dir=${DEBS2015_Q1_CHECKED_PROCESSING_DIR:-"/tmp/debs2015-q1-checked-processing"}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"

run_mode() {
  local mode="$1"
  local output="${output_dir}/q1-${mode}.out"

  echo
  echo "== Q1 checked-processing ${mode} =="
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015Q1CheckedProcessingRun\")" \
    "run ${input} ${output} ${mode}"
}

strip_latency() {
  awk -F ',' 'BEGIN { OFS = "," } { NF -= 1; print }' "$1"
}

cd "${repo_dir}"

run_mode heap
run_mode checked-processing

diff -u \
  <(strip_latency "${output_dir}/q1-heap.out") \
  <(strip_latency "${output_dir}/q1-checked-processing.out")

echo
echo "Q1 checked-processing matrix outputs match in ${output_dir}"
