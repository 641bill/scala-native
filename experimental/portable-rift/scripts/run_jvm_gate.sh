#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${MODULE_DIR}/../.." && pwd)"

OUT_DIR="${PORTABLE_RIFT_OUT_DIR:-/private/tmp/portable-rift-jvm-gate-$(date +%Y%m%d-%H%M%S)}"
RECORDS="${PORTABLE_RIFT_RECORDS:-1000000}"
EPOCH_SIZE="${PORTABLE_RIFT_EPOCH_SIZE:-10000}"
ACTIVE_TIMESTAMPS="${PORTABLE_RIFT_ACTIVE_TIMESTAMPS:-16}"
RUNS="${PORTABLE_RIFT_RUNS:-3}"
WARMUPS="${PORTABLE_RIFT_WARMUPS:-1}"
JAVA_HEAP="${PORTABLE_RIFT_JAVA_HEAP:-2g}"
JAVA_GC="${PORTABLE_RIFT_JAVA_GC:-UseG1GC}"

mkdir -p "${OUT_DIR}/gc" "${OUT_DIR}/time" "${OUT_DIR}/rows"

SUMMARY="${OUT_DIR}/summary.tsv"
{
  printf "workload\tmode\trecords\tepoch_size\tactive_timestamps\tjvm_heap\tjvm_gc\trow_output\ttime_output\tgc_log\n"
} > "${SUMMARY}"

run_one() {
  local workload="$1"
  local mode="$2"
  local row_out="${OUT_DIR}/rows/${workload}-${mode}.tsv"
  local time_out="${OUT_DIR}/time/${workload}-${mode}.txt"
  local gc_log="${OUT_DIR}/gc/${workload}-${mode}.log"

  /usr/bin/time -l -o "${time_out}" \
    scala-cli run --server=false "${MODULE_DIR}" \
      --java-opt "-Xms${JAVA_HEAP}" \
      --java-opt "-Xmx${JAVA_HEAP}" \
      --java-opt "-XX:+${JAVA_GC}" \
      --java-opt "-Xlog:gc*:file=${gc_log}:time,level,tags" \
      --main-class rift.portable.PortableRiftMicrobench -- \
      --mode "${mode}" \
      --workload "${workload}" \
      --records "${RECORDS}" \
      --epoch-size "${EPOCH_SIZE}" \
      --active-timestamps "${ACTIVE_TIMESTAMPS}" \
      --warmups "${WARMUPS}" \
      --runs "${RUNS}" \
    > "${row_out}"

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${workload}" "${mode}" "${RECORDS}" "${EPOCH_SIZE}" "${ACTIVE_TIMESTAMPS}" \
    "${JAVA_HEAP}" "${JAVA_GC}" "${row_out}" "${time_out}" "${gc_log}" >> "${SUMMARY}"
}

cd "${REPO_DIR}"
scala-cli compile --server=false "${MODULE_DIR}"

for workload in retained-epoch broom-aggregate; do
  for mode in heap-gc analysis-only jvm-pool-arena; do
    run_one "${workload}" "${mode}"
  done
done

echo "portable JVM gate output: ${OUT_DIR}"
echo "summary: ${SUMMARY}"
