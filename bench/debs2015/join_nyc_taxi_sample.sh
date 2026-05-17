#!/usr/bin/env zsh

set -euo pipefail

month=${DEBS2015_MONTH:-1}
limit=${DEBS2015_LIMIT:-100000}
data_default="/Users/siyaoliu/rift/trip_data/trip_data_${month}.csv"
fare_default="/Users/siyaoliu/rift/trip_fare/trip_fare_${month}.csv"
data_archive=${DEBS2015_TRIP_DATA_ARCHIVE:-"/Users/siyaoliu/rift/cache/benchmark-data/debs2015/trip_data.7z"}
fare_archive=${DEBS2015_TRIP_FARE_ARCHIVE:-"/Users/siyaoliu/rift/cache/benchmark-data/debs2015/trip_fare.7z"}
if [[ ! -f "${data_default}" && -f "${data_default}.gz" ]]; then
  data_default="${data_default}.gz"
elif [[ ! -f "${data_default}" && -f "${data_archive}" ]]; then
  data_default="7z:${data_archive}!trip_data_${month}.csv"
fi
if [[ ! -f "${fare_default}" && -f "${fare_default}.gz" ]]; then
  fare_default="${fare_default}.gz"
elif [[ ! -f "${fare_default}" && -f "${fare_archive}" ]]; then
  fare_default="7z:${fare_archive}!trip_fare_${month}.csv"
fi
data_file=${DEBS2015_TRIP_DATA:-"${data_default}"}
fare_file=${DEBS2015_TRIP_FARE:-"${fare_default}"}
output=${DEBS2015_JOINED_OUTPUT:-"/tmp/debs2015-month${month}-${limit}.csv"}
sort_output=${DEBS2015_SORT:-1}

mkdir -p "${output:h}"
tmp=$(mktemp "${TMPDIR:-/tmp}/debs2015-joined.XXXXXX")
trap 'rm -f "${tmp}"' EXIT

stream_csv_body() {
  local input=$1
  case "${input}" in
    7z:*)
      local spec=${input#7z:}
      local archive=${spec%%!*}
      local member=${spec#*!}
      if [[ "${spec}" == "${member}" ]]; then
        echo "invalid 7z input spec: ${input}" >&2
        return 2
      fi
      bsdtar -xOf "${archive}" "${member}" | tail -n +2
      ;;
    *.gz) gzip -dc -- "${input}" | tail -n +2 ;;
    *) tail -n +2 -- "${input}" ;;
  esac
}

set +o pipefail
paste -d '\t' \
  <(stream_csv_body "${data_file}") \
  <(stream_csv_body "${fare_file}") |
awk -F '\t' -v limit="${limit}" -v output="${tmp}" '
function trim(value) {
  gsub(/^[ \t\r]+|[ \t\r]+$/, "", value)
  return value
}

{
  split($1, data, ",")
  split($2, fare, ",")

  for (i = 1; i <= 14; i++) data[i] = trim(data[i])
  for (i = 1; i <= 11; i++) fare[i] = trim(fare[i])

  dataKey = data[1] "," data[2] "," data[3] "," data[6]
  fareKey = fare[1] "," fare[2] "," fare[3] "," fare[4]
  if (dataKey != fareKey) {
    printf("mismatched row %d: data=%s fare=%s\n", NR, dataKey, fareKey) > "/dev/stderr"
    exit 2
  }

  print data[1] "," data[2] "," data[6] "," data[7] "," data[9] "," data[10] "," \
        data[11] "," data[12] "," data[13] "," data[14] "," fare[5] "," fare[6] "," \
        fare[7] "," fare[8] "," fare[9] "," fare[10] "," fare[11] >> output

  if (limit > 0 && NR >= limit) exit
}

END {
  if (NR == 0) {
    print "no rows joined" > "/dev/stderr"
    exit 3
  }
}
'
awk_status=${pipestatus[2]}
set -o pipefail

if [[ "${awk_status}" -ne 0 ]]; then
  exit "${awk_status}"
fi

if [[ "${sort_output}" == "1" ]]; then
  sort -t ',' -k4,4 -s "${tmp}" > "${output}"
else
  mv "${tmp}" "${output}"
  trap - EXIT
fi

echo "Wrote joined DEBS sample to ${output}"
