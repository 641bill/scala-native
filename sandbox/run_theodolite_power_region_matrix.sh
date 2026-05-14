#!/usr/bin/env zsh

set -euo pipefail

script_dir=${0:A:h}
repo_dir=${script_dir:h}
output_dir=${THEODOLITE_POWER_OUTPUT_DIR:-"/tmp/theodolite-power-region-matrix"}
summary=${THEODOLITE_POWER_SUMMARY:-"${output_dir}/summary.tsv"}
build=${THEODOLITE_POWER_BUILD:-1}
platform=$(uname -s)
modes=(${(z)${THEODOLITE_POWER_MODES:-"heap-immix region-scoped-rooted region-stream-rootless checked-epoch-scoped"}})
queries=(${(z)${THEODOLITE_POWER_QUERIES:-"q1-downsample q2-hierarchical"}})

export ENABLE_EXPERIMENTAL_COMPILER=1
export JAVA_HOME="$(cs java-home --jvm temurin:17)"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "${output_dir}"
cd "${repo_dir}"

if [[ "${build}" != "0" ]]; then
  sbt \
    "project sandbox3_next" \
    "set Compile / mainClass := Some(\"TheodolitePowerRegionMatrix\")" \
    nativeLink
fi

binary=${THEODOLITE_POWER_BINARY:-}
if [[ -z "${binary}" ]]; then
  binary=$(find sandbox/.3-next/target -path "*/native/TheodolitePowerRegionMatrix" -type f -perm -111 -print | sort | tail -n 1)
fi

if [[ -z "${binary}" || ! -x "${binary}" ]]; then
  echo "missing TheodolitePowerRegionMatrix native binary; set THEODOLITE_POWER_BINARY or enable THEODOLITE_POWER_BUILD" >&2
  exit 1
fi

read_max_rss_bytes() {
  local time_log="$1"
  if [[ "${platform}" == "Darwin" ]]; then
    awk '/maximum resident set size/ { print $1; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  else
    awk -F ':' '/Maximum resident set size/ { gsub(/^[ \t]+/, "", $2); print $2 * 1024; found = 1; exit } END { if (!found) print "" }' "${time_log}"
  fi
}

read_time_seconds() {
  local time_log="$1"
  local field="$2"
  if [[ "${platform}" == "Darwin" ]]; then
    awk -v field="${field}" '
      {
        for (i = 1; i <= NF; i++) {
          if ($i == field && i > 1) {
            print $(i - 1)
            found = 1
            exit
          }
        }
      }
      END { if (!found) print "" }
    ' "${time_log}"
  else
    awk -v field="${field}" -F ':' '
      field == "real" && /Elapsed \(wall clock\) time/ {
        gsub(/^[ \t]+/, "", $2)
        split($2, parts, ":")
        if (length(parts) == 3)
          print parts[1] * 3600 + parts[2] * 60 + parts[3]
        else if (length(parts) == 2)
          print parts[1] * 60 + parts[2]
        else
          print $2
        found = 1
        exit
      }
      field == "user" && /User time/ {
        gsub(/^[ \t]+/, "", $2)
        print $2
        found = 1
        exit
      }
      field == "sys" && /System time/ {
        gsub(/^[ \t]+/, "", $2)
        print $2
        found = 1
        exit
      }
      END { if (!found) print "" }
    ' "${time_log}"
  fi
}

write_summary_header() {
  printf "query\tmode\tstatus\texternal_real_s\texternal_user_s\texternal_sys_s\tinput\tinput_mode\trecords\trecords_per_epoch\tgroups\tmedian_ms\tmedian_gc_ms\tmax_gc_ms\truns_with_gc\tmax_gc_collections\tmedian_rift_op_ms\tmedian_rift_alloc_object_total\tmedian_rift_open_total\tmedian_rift_close_total\tmedian_rift_reset_total\tchecksum\toutput_count\tmax_rss_bytes\n" > "${summary}"
}

write_result_row() {
  local query="$1"
  local mode="$2"
  local run_status="$3"
  local binary_mode="$4"
  local run_log="$5"
  local max_rss_bytes="$6"
  local external_real_s="$7"
  local external_user_s="$8"
  local external_sys_s="$9"
  local line token key value
  typeset -A fields

  line=$(grep "^RESULT name=theodolite-power-${query}-${binary_mode} " "${run_log}" | tail -n 1)
  fields=()
  for token in ${(z)line}; do
    if [[ "${token}" == *=* ]]; then
      key=${token%%=*}
      value=${token#*=}
      fields[${key}]=${value}
    fi
  done

  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" \
    "${query}" \
    "${mode}" \
    "${run_status}" \
    "${external_real_s}" \
    "${external_user_s}" \
    "${external_sys_s}" \
    "${fields[input]-}" \
    "${fields[input_mode]-}" \
    "${fields[records]-}" \
    "${fields[records_per_epoch]-}" \
    "${fields[groups]-}" \
    "${fields[median_ms]-}" \
    "${fields[median_gc_ms]-}" \
    "${fields[max_gc_ms]-}" \
    "${fields[runs_with_gc]-}" \
    "${fields[max_gc_collections]-}" \
    "${fields[median_rift_op_ms]-}" \
    "${fields[median_rift_alloc_object_total]-}" \
    "${fields[median_rift_open_total]-}" \
    "${fields[median_rift_close_total]-}" \
    "${fields[median_rift_reset_total]-}" \
    "${fields[checksum]-}" \
    "${fields[output_count]-}" \
    "${max_rss_bytes}" >> "${summary}"
}

run_case() {
  local query="$1"
  local mode="$2"
  local binary_mode="${mode}"
  local roots_mode=""
  local page_size="${SAFEZONE_PAGE_SIZE:-}"
  local safe_mode="${mode//[^A-Za-z0-9_.-]/_}"
  local run_log="${output_dir}/run-${query}-${safe_mode}.log"
  local time_log="${output_dir}/time-${query}-${safe_mode}.log"
  local max_rss_bytes
  local external_real_s
  local external_user_s
  local external_sys_s
  local command_status
  local -a env_args

  case "${mode}" in
    gc-heap|heap-immix)
      binary_mode="heap"
      ;;
    region-scoped-rooted|safezone-improved|safezone-improved-32k)
      binary_mode="safezone"
      roots_mode="1"
      page_size="32768"
      ;;
    region-stream-rootless|rift-trusted-streaming)
      binary_mode="rift-streaming"
      ;;
    checked-epoch-stream|checked-region-stream)
      binary_mode="checked-epoch-stream"
      ;;
    checked-epoch-stream-open-handle|checked-region-stream-open-handle)
      binary_mode="checked-epoch-stream-open-handle"
      ;;
    checked-epoch-stream-legacy|checked-region-stream-legacy)
      binary_mode="checked-epoch-stream-legacy"
      ;;
    checked-epoch-scoped|checked-region-scoped)
      binary_mode="checked-epoch-scoped"
      roots_mode="1"
      page_size="32768"
      ;;
  esac

  env_args=()
  if [[ -n "${roots_mode}" ]]; then
    env_args+=(SAFEZONE_ROOTS_MODE="${roots_mode}")
  fi
  if [[ -n "${page_size}" ]]; then
    env_args+=(SAFEZONE_PAGE_SIZE="${page_size}")
  fi

  echo
  echo "== ${query} / ${mode} =="
  set +e
  if [[ "${platform}" == "Darwin" ]]; then
    env "${env_args[@]}" /usr/bin/time -l "${binary}" "${binary_mode}" "${query}" > "${run_log}" 2> "${time_log}"
  else
    env "${env_args[@]}" /usr/bin/time -v "${binary}" "${binary_mode}" "${query}" > "${run_log}" 2> "${time_log}"
  fi
  command_status=$?
  set -e

  max_rss_bytes=$(read_max_rss_bytes "${time_log}")
  external_real_s=$(read_time_seconds "${time_log}" real)
  external_user_s=$(read_time_seconds "${time_log}" user)
  external_sys_s=$(read_time_seconds "${time_log}" sys)

  if ! grep -q "^RESULT name=theodolite-power-${query}-${binary_mode} " "${run_log}"; then
    cat "${run_log}" >&2
    cat "${time_log}" >&2
    echo "THEODOLITE_POWER_RESULT query=${query} mode=${mode} status=failed exit_status=${command_status} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}" >&2
    return 0
  fi

  grep "^RESULT name=theodolite-power-${query}-${binary_mode} " "${run_log}"
  echo "THEODOLITE_POWER_EXTERNAL_RESULT query=${query} mode=${mode} external_real_s=${external_real_s} external_user_s=${external_user_s} external_sys_s=${external_sys_s} max_rss_bytes=${max_rss_bytes}"
  write_result_row "${query}" "${mode}" "ok" "${binary_mode}" "${run_log}" "${max_rss_bytes}" "${external_real_s}" "${external_user_s}" "${external_sys_s}"
}

write_summary_header

for query in "${queries[@]}"; do
  for mode in "${modes[@]}"; do
    run_case "${query}" "${mode}"
  done
done

echo
echo "Theodolite power region matrix complete"
echo "Summary: ${summary}"
