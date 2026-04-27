#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_Q2_CHECKED_PROCESSING_INPUT:-"${script_dir}/sample_q2.csv"}
output_dir=${DEBS2015_Q2_CHECKED_PROCESSING_DIR:-"/tmp/debs2015-q2-checked-processing"}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"

run_mode() {
  local mode="$1"
  local output="${output_dir}/q2-${mode}.out"

  echo
  echo "== Q2 checked-processing ${mode} =="
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015Q2CheckedProcessingRun\")" \
    "run ${input} ${output} ${mode}"
}

strip_latency() {
  awk -F ',' 'BEGIN { OFS = "," } { NF -= 1; print }' "$1"
}

cd "${repo_dir}"

run_mode heap
run_mode checked-processing

diff -u \
  <(strip_latency "${output_dir}/q2-heap.out") \
  <(strip_latency "${output_dir}/q2-checked-processing.out")

echo
echo "Q2 checked-processing matrix outputs match in ${output_dir}"
