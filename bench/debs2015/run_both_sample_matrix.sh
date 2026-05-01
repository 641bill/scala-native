#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h:h}
input=${DEBS2015_BOTH_INPUT:-"${script_dir}/sample_both.csv"}
output_dir=${DEBS2015_BOTH_OUTPUT_DIR:-"/tmp/debs2015-runboth-matrix"}
modes_text=${DEBS2015_BOTH_MODES:-"heap safezone-current safezone-improved unsafezone-hp rift-hp rift-streaming rift-checked"}
modes=(${=modes_text})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"

run_mode() {
  local mode="$1"
  local roots_mode=""
  local page_size="${SAFEZONE_PAGE_SIZE:-}"
  local q1_output="${output_dir}/q1-${mode}.out"
  local q2_output="${output_dir}/q2-${mode}.out"

  case "${mode}" in
    safezone-current)
      roots_mode="0"
      ;;
    safezone-improved)
      roots_mode="1"
      ;;
    unsafezone-hp)
      roots_mode="3"
      page_size="32768"
      ;;
  esac

  echo
  echo "== RunBoth Q1 ${mode} =="
  SAFEZONE_ROOTS_MODE="${roots_mode}" \
  SAFEZONE_PAGE_SIZE="${page_size}" \
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
    "run ${input} ${q1_output} ${q2_output} ${mode}"
}

strip_latency() {
  awk -F ',' 'BEGIN { OFS = "," } { NF -= 1; print }' "$1"
}

cd "${repo_dir}"

for mode in "${modes[@]}"; do
  run_mode "${mode}"
done

for mode in "${modes[@]}"; do
  if [[ "${mode}" != "heap" ]]; then
    diff -u <(strip_latency "${output_dir}/q1-heap.out") <(strip_latency "${output_dir}/q1-${mode}.out")
    diff -u <(strip_latency "${output_dir}/q2-heap.out") <(strip_latency "${output_dir}/q2-${mode}.out")
  fi
done

echo
echo "RunBoth sample matrix outputs match in ${output_dir}"
