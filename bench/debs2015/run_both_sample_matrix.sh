#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_BOTH_INPUT:-"${script_dir}/sample_both.csv"}
output_dir=${DEBS2015_BOTH_OUTPUT_DIR:-"/tmp/debs2015-runboth-matrix"}

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"

run_mode() {
  local mode="$1"
  local q1_output="${output_dir}/q1-${mode}.out"
  local q2_output="${output_dir}/q2-${mode}.out"

  echo
  echo "== RunBoth Q1 ${mode} =="
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
    "run ${input} ${q1_output} ${q2_output} ${mode}"
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
diff -u <(strip_latency "${output_dir}/q2-heap.out") <(strip_latency "${output_dir}/q2-rift-hp.out")
diff -u <(strip_latency "${output_dir}/q2-heap.out") <(strip_latency "${output_dir}/q2-rift-streaming.out")

echo
echo "RunBoth sample matrix outputs match in ${output_dir}"
