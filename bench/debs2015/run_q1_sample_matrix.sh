#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_Q1_INPUT:-"${script_dir}/sample_q1.csv"}
output_dir=${DEBS2015_Q1_OUTPUT_DIR:-"/tmp/debs2015-q1-matrix"}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"

run_mode() {
  local mode="$1"
  local output="${output_dir}/q1-${mode}.out"

  echo
  echo "== Q1 ${mode} =="
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Run\")" \
    "run ${input} ${output} ${mode}"
}

strip_latency() {
  awk -F ',' 'BEGIN { OFS = "," } { NF -= 1; print }' "$1"
}

cd "${repo_dir}"

run_mode heap
run_mode rift-hp
run_mode rift-streaming

diff -u <(strip_latency "${output_dir}/q1-heap.out") <(strip_latency "${output_dir}/q1-rift-hp.out")
diff -u <(strip_latency "${output_dir}/q1-heap.out") <(strip_latency "${output_dir}/q1-rift-streaming.out")

echo
echo "Q1 sample matrix outputs match in ${output_dir}"
