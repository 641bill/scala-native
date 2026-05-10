# GH Archive Region Matrix

Date: 2026-05-03
Last updated: 2026-05-10 17:15 CEST

Status: first real NDJSON/log-event stream matrix added, with both preloaded
and file-backed q1/q2 rows. The preloaded rows time object allocation/query
processing after extracting primitive metadata before warmups.
`GITHUB_ARCHIVE_INPUT_MODE=file-backed` rereads and parses gzip JSON lines
inside every timed run. File-backed rows now support two parser paths:
`GITHUB_ARCHIVE_FILE_PARSER=string` for the legacy `BufferedReader`/`String`
path and `GITHUB_ARCHIVE_FILE_PARSER=byte-slice` for the current default
reusable byte-line reader.

## Input

Fetched through the parent data script:

```sh
cd /Users/siyaoliu/rift
RIFT_FETCH_GHARCHIVE_SAMPLE=1 \
RIFT_GHARCHIVE_HOUR=2026-04-01-0 \
RIFT_FETCH_LARGE=0 \
RIFT_FETCH_COMMON_CRAWL_SAMPLE=0 \
RIFT_FETCH_COMMON_CRAWL_WAT_SAMPLE=0 \
bash scripts/fetch-benchmark-data.sh
```

Local input:

`/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz`

GH Archive publishes hourly gzip-compressed JSON-lines event files. By default
the matrix extracts event type, repo, actor, field-count, and a stable line
hash into primitive preloaded arrays, then times object allocation/query
processing. With `GITHUB_ARCHIVE_INPUT_MODE=file-backed`, the same logical
query rereads the gzip file and parses the JSON line fields during every timed
run. The default file-backed parser is now `byte-slice`, which reuses input
buffers and extracts JSON string fields from raw UTF-8 bytes. The legacy
`string` parser remains available as a control.

## Queries

| Query | Meaning | Region candidate |
|---|---|---|
| `q0-events` | Allocate one ordinary event record per real GitHub event. | Event records with bucket lifetime. |
| `q1-fields` | Allocate one event record plus parsed-field records per event. | Event and field records with bucket lifetime. |
| `q2-repo-window` | Allocate event/field records, then aggregate by repo bucket at close. | Bucket-owned event/field records plus close-time summaries. |

## Modes

The runner accepts canonical reporting labels and maps SafeZone-family
environment settings internally:

- `heap-immix`
- `safezone-improved-32k`
- `safezone-rootless-32k`
- `rift-trusted-hp`
- `rift-trusted-streaming`
- `rift-checked-page-token`
- `rift-checked-safezone-page-token`
- `heap-direct-epoch`
- `rift-checked-direct-epoch`
- `rift-checked-safezone-direct-epoch`

The direct-epoch modes currently support generated/preloaded `q2-repo-window`
only. They reject file-backed rows because file-backed input is a sequential
reader path; page-token remains the checked mode for file-backed GH Archive.

## Commands

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
GITHUB_ARCHIVE_BUILD=1 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_EVENTS=20000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=1 \
GITHUB_ARCHIVE_WARMUPS=0 \
GITHUB_ARCHIVE_QUERIES="q0-events q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix rift-checked-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-smoke-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

100k all-mode row:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k safezone-rootless-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-100k-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

The first 100k run accidentally ran `rift-checked-safezone-page-token` without
the intended SafeZone roots/page settings. The script was fixed and that mode
was rerun with `SAFEZONE_ROOTS_MODE=1` and `SAFEZONE_PAGE_SIZE=32768`:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-100k-checked-safezone-fix-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

Whole-hour q1 row:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k safezone-rootless-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-fullhour-q1-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

Corrected SafeZone-backed page-token full-hour q1 rerun:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields" \
GITHUB_ARCHIVE_MODES="rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-fullhour-checked-safezone-fix-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

File-backed q1 smoke and 100k row:

```sh
GITHUB_ARCHIVE_BUILD=1 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=20000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=1 \
GITHUB_ARCHIVE_WARMUPS=0 \
GITHUB_ARCHIVE_QUERIES="q1-fields" \
GITHUB_ARCHIVE_MODES="heap-immix rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-file-backed-smoke-2026-05-06 \
zsh sandbox/run_github_archive_region_matrix.sh
```

File-backed q2 heap-cap row:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_HEAP_CAPS="uncapped 2G 1400M 1G" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-file-backed-100k-q2-caps-2026-05-06 \
zsh sandbox/run_github_archive_region_matrix.sh
```

Byte-slice file-backed 100k and 2-hour rows:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz" \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_FILE_PARSER=byte-slice \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-byte-parser-100k-2026-05-07 \
zsh sandbox/run_github_archive_region_matrix.sh
```

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-1.json.gz" \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_FILE_PARSER=byte-slice \
GITHUB_ARCHIVE_EVENTS=200000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-byte-parser-2h-200k-2026-05-07 \
zsh sandbox/run_github_archive_region_matrix.sh
```

File-backed q1 heap-cap row:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_HEAP_CAPS="uncapped 2G 1400M 1G" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-file-backed-100k-q1-caps-2026-05-06 \
zsh sandbox/run_github_archive_region_matrix.sh
```

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=100000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-file-backed-100k-q1-rss-2026-05-06 \
zsh sandbox/run_github_archive_region_matrix.sh
```

## Smoke Results

At 20k events all checksums matched.

| Query | Mode | Median ms | GC ms | Output count |
|---|---|---:|---:|---:|
| q0-events | `heap-immix` | `1.745` | `0.000` | `20000` |
| q0-events | `rift-checked-page-token` | `1.946` | `0.000` | `20000` |
| q1-fields | `heap-immix` | `6.113` | `0.000` | `260000` |
| q1-fields | `rift-checked-page-token` | `7.762` | `0.000` | `260000` |
| q2-repo-window | `heap-immix` | `5.435` | `0.000` | `3874` |
| q2-repo-window | `rift-checked-page-token` | `7.798` | `0.000` | `3874` |

Interpretation: 20k is too small; heap has no GC pressure.

## 100k Results

At 100k requested events, q1/q2 materialized `1300000` ordinary event/field
records. All rows matched checksum/output count.

| Query | Mode | Median ms | GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `46.309` | `15.777` | `58.617` | 2 | `207699968` | `1300000` |
| q1-fields | `safezone-improved-32k` | `37.956` | `0.000` | `0.000` | 0 | `272760832` | `1300000` |
| q1-fields | `safezone-rootless-32k` | `37.922` | `0.000` | `0.000` | 0 | `272711680` | `1300000` |
| q1-fields | `rift-trusted-hp` | `35.206` | `0.000` | `0.000` | 0 | `212713472` | `1300000` |
| q1-fields | `rift-trusted-streaming` | `35.161` | `0.000` | `0.000` | 0 | `212779008` | `1300000` |
| q1-fields | `rift-checked-page-token` | `37.283` | `0.000` | `0.000` | 0 | `272990208` | `1300000` |
| q1-fields | `rift-checked-safezone-page-token` | `33.656` | `0.000` | `0.000` | 0 | `269860864` | `1300000` |
| q2-repo-window | `heap-immix` | `28.454` | `0.000` | `62.988` | 1 | `291225600` | `15877` |
| q2-repo-window | `safezone-improved-32k` | `34.891` | `0.000` | `0.000` | 0 | `269942784` | `15877` |
| q2-repo-window | `safezone-rootless-32k` | `35.419` | `0.000` | `0.000` | 0 | `269877248` | `15877` |
| q2-repo-window | `rift-trusted-hp` | `32.180` | `0.000` | `0.000` | 0 | `269762560` | `15877` |
| q2-repo-window | `rift-trusted-streaming` | `32.205` | `0.000` | `0.000` | 0 | `272728064` | `15877` |
| q2-repo-window | `rift-checked-page-token` | `34.803` | `0.000` | `0.000` | 0 | `214974464` | `15877` |
| q2-repo-window | `rift-checked-safezone-page-token` | `33.568` | `0.000` | `0.000` | 0 | `269926400` | `15877` |

Interpretation:

- q1 is the first real NDJSON row with material heap GC and clear region wins.
  `rift-trusted-streaming` is about `24.1%` faster than heap, and
  checked SafeZone-backed page-token is about `27.3%` faster than heap.
- q2 is a mixed/negative row. Heap has a GC outlier, but median elapsed is
  fastest because the close-time repo aggregation CPU dominates.

## Whole-Hour Q1 Results

The same hourly file contains `153082` events. q1 materialized `1990066`
ordinary event/field records. All rows matched checksum/output count.

| Mode | Median ms | GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `45.772` | `0.000` | `57.411` | 1 | `297959424` | `1990066` |
| `safezone-improved-32k` | `57.561` | `0.000` | `0.000` | 0 | `270401536` | `1990066` |
| `safezone-rootless-32k` | `57.642` | `0.000` | `0.000` | 0 | `270319616` | `1990066` |
| `rift-trusted-hp` | `53.725` | `0.000` | `0.000` | 0 | `274776064` | `1990066` |
| `rift-trusted-streaming` | `53.410` | `0.000` | `0.000` | 0 | `274841600` | `1990066` |
| `rift-checked-page-token` | `53.796` | `0.000` | `0.000` | 0 | `279101440` | `1990066` |
| `rift-checked-safezone-page-token` | `51.391` | `0.000` | `0.000` | 0 | `274890752` | `1990066` |

Interpretation: whole-hour q1 is not a headline win because heap has only one
GC outlier and wins the median. It is still useful evidence: region modes
remove the heap GC outlier and reduce RSS versus heap, but CPU/cache behavior
dominates median elapsed at this scale.

## Current Decision

The original 100k and whole-hour rows used `runHeap(query)` inside each process
to compute the expected checksum. That is correct for output checking, but it
allocates the heap version of the workload before timing every mode. It can
therefore contaminate RSS for region rows and perturb the GC state. The
multi-hour rows below use a no-allocation checksum oracle instead. Treat the
100k row as a promising first signal; use the oracle rows as the cleaner
current interpretation.

## Multi-Hour Oracle Harness

The runner now accepts comma-separated `GITHUB_ARCHIVE_INPUTS`, reports
`loaded_events`, `input_files`, `heap_cap`, and `status`, and computes expected
q0/q1/q2 output with a no-allocation oracle. Failed heap-capped runs are
recorded as failed rows instead of aborting the whole matrix. The eight-hour
sample uses:

```sh
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-1.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-2.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-3.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-4.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-5.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-6.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-7.json.gz"
```

The no-allocation oracle smoke matched q0/q1/q2 checksums for heap and
SafeZone-backed page-token checked modes.

### 1M Eight-Hour Rows

Command shape:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUTS="$GITHUB_ARCHIVE_INPUTS" \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k safezone-rootless-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-8h-1m-oracle-2026-05-03 \
zsh sandbox/run_github_archive_region_matrix.sh
```

All rows loaded exactly `1000000` real events from 8 input files. q1
materialized `13000000` event/field records; q2 output `159411` repo-window
rows.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `293.204` | `0.000` | `135.368` | 1 | `1740275712` | `13000000` |
| q1-fields | `safezone-improved-32k` | `374.923` | `0.000` | `0.000` | 0 | `1718042624` | `13000000` |
| q1-fields | `safezone-rootless-32k` | `374.446` | `0.000` | `0.000` | 0 | `1717944320` | `13000000` |
| q1-fields | `rift-trusted-hp` | `344.064` | `0.000` | `0.000` | 0 | `1745453056` | `13000000` |
| q1-fields | `rift-trusted-streaming` | `340.820` | `0.000` | `0.000` | 0 | `1728462848` | `13000000` |
| q1-fields | `rift-checked-page-token` | `367.733` | `0.000` | `0.000` | 0 | `1766408192` | `13000000` |
| q1-fields | `rift-checked-safezone-page-token` | `348.817` | `0.000` | `0.000` | 0 | `1803026432` | `13000000` |
| q2-repo-window | `heap-immix` | `271.880` | `0.000` | `136.353` | 1 | `1703968768` | `159411` |
| q2-repo-window | `safezone-improved-32k` | `363.049` | `0.000` | `0.000` | 0 | `1766653952` | `159411` |
| q2-repo-window | `safezone-rootless-32k` | `360.899` | `0.000` | `0.000` | 0 | `1766555648` | `159411` |
| q2-repo-window | `rift-trusted-hp` | `336.214` | `0.000` | `0.000` | 0 | `1766506496` | `159411` |
| q2-repo-window | `rift-trusted-streaming` | `325.665` | `0.000` | `0.000` | 0 | `1802862592` | `159411` |
| q2-repo-window | `rift-checked-page-token` | `358.908` | `0.000` | `0.000` | 0 | `1692827648` | `159411` |
| q2-repo-window | `rift-checked-safezone-page-token` | `347.033` | `0.000` | `0.000` | 0 | `1766637568` | `159411` |

Interpretation:

- Uncapped `heap-immix` wins median elapsed on both q1 and q2.
- Heap still has GC tail risk: one timed run collected in each query, with
  max GC around `135-136 ms`.
- Region modes remove timed GC, but this real input is not yet a throughput
  case-study win under an unconstrained heap.
- The first `rift-trusted-streaming` q1 RSS row was anomalous
  (`3650453504` bytes). A same-input rerun gives `340.820 ms` and
  `1728462848` bytes RSS, which is the value reported above.

### Heap-Budget Diagnostics

These are diagnostics, not headline rows. The runner now supports:

```sh
GITHUB_ARCHIVE_HEAP_CAPS="uncapped 2G 1400M 1G" \
GITHUB_ARCHIVE_MODES="heap-immix" \
zsh sandbox/run_github_archive_region_matrix.sh
```

`GC_MAXIMUM_HEAP_SIZE=512M` fails during GH Archive input preload. With
`GC_MAXIMUM_HEAP_SIZE=1G`, q1 completes but q2 crashes in a later process. The
successful q1 row is:

| Query | Mode | Heap cap | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `1G` | `395.295` | `92.347` | `101.174` | 2 | `1087995904` | `13000000` |

Under this heap budget, the uncapped checked SafeZone-backed page-token q1 row
from the oracle matrix (`348.817 ms`, zero timed GC) is faster than the capped
heap row. This supports a memory-budget/tail-latency story, not an uncapped
throughput story.

For q2, `GC_MAXIMUM_HEAP_SIZE=1400M` completes:

| Query | Mode | Heap cap | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q2-repo-window | `heap-immix` | `1400M` | `279.959` | `0.000` | `106.097` | 1 | `1481392128` | `159411` |

Heap still wins q2 median at this budget; q2 remains mostly a CPU/aggregation
row with GC tail risk.

### Eight-Hour Full-Input Q1 Probe

Requesting `2000000` events over the eight-hour sample loads all `1260474`
available events and materializes `16386162` q1 records.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `391.761` | `0.000` | `0.000` | 0 | `2688237568` | `16386162` |
| `rift-checked-safezone-page-token` | `442.941` | `0.000` | `0.000` | 0 | `2527526912` | `16386162` |

Interpretation: with an unconstrained heap, the all-eight-hour q1 row is a
high-RSS heap-throughput win, not a Rift throughput win. It is still useful
because it shows how Immix can avoid collection by retaining a multi-GB heap.

## File-Backed q1 Result

The first file-backed run includes gzip read/decompression and JSON string
field extraction in every timed run. It uses one real GH Archive hourly file,
100k events, and materializes 1.3M event/field records. All checksums matched.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `3999.933` | `158.149` | `160.001` | 3 | `1218805760` | `1300000` |
| `safezone-improved-32k` | `3924.979` | `107.125` | `111.397` | 3 | `674807808` | `1300000` |
| `rift-trusted-streaming` | `3908.972` | `73.055` | `73.953` | 3 | `495943680` | `1300000` |
| `rift-checked-safezone-page-token` | `3937.394` | `106.248` | `106.904` | 3 | `674791424` | `1300000` |

Interpretation: file-backed q1 is a real-data RSS win and modest throughput
win for region modes, but not yet a decisive checked case study. The parser and
string-extraction work still allocate on the heap, so checked scoped
page-token removes event/field record tracing but not all file-backed GC.
Trusted streaming has the best first row because it combines region event
records with lower region/backend overhead.

Heap-cap rerun:

| Mode | Heap cap | Status | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|
| `heap-immix` | uncapped | ok | `4014.909` | `157.495` | `162.940` | 3 | `1218822144` |
| `heap-immix` | `2G` | ok | `4005.975` | `149.868` | `151.631` | 3 | `1168179200` |
| `heap-immix` | `1400M` | ok | `4093.234` | `166.523` | `201.304` | 3 | `1168162816` |
| `heap-immix` | `1G` | failed: signal 11 | n/a | n/a | n/a | n/a | `1076805632` |
| `safezone-improved-32k` | uncapped | ok | `4000.812` | `105.804` | `116.457` | 3 | `673808384` |
| `rift-trusted-streaming` | uncapped | ok | `3995.238` | `82.368` | `85.641` | 3 | `673611776` |
| `rift-checked-safezone-page-token` | uncapped | ok | `4023.883` | `113.334` | `115.980` | 3 | `674742272` |

Interpretation: file-backed q1 is mostly an RSS/fixed-memory win. Trusted
Streaming is slightly faster than uncapped heap and uses much less RSS, but
checked SafeZone-backed page-token is a near tie/slight elapsed loss while
cutting RSS by about `45%`. The heap `1G` cap fails, and the `1400M` cap shows
a larger max-GC tail (`201.304 ms`). Parser/string allocation still causes
timed GC in every successful row.

## File-Backed q2 Result

The first file-backed q2 run uses the same 100k real events and 1.3M
event/field records, then aggregates by repo bucket on bucket close. All
successful rows matched checksums and output count `15877`.

| Mode | Heap cap | Status | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|
| `heap-immix` | uncapped | ok | `3995.632` | `158.277` | `161.727` | 3 | `1218428928` |
| `heap-immix` | `2G` | ok | `4066.670` | `167.292` | `170.908` | 3 | `1218428928` |
| `heap-immix` | `1400M` | ok | `3983.441` | `157.255` | `161.990` | 3 | `1218805760` |
| `heap-immix` | `1G` | failed: signal 11 | n/a | n/a | n/a | n/a | `1077067776` |
| `safezone-improved-32k` | uncapped | ok | `3934.094` | `105.445` | `107.688` | 3 | `672088064` |
| `rift-trusted-streaming` | uncapped | ok | `3906.291` | `81.402` | `81.851` | 3 | `673644544` |
| `rift-checked-safezone-page-token` | uncapped | ok | `3921.127` | `106.352` | `111.654` | 3 | `673824768` |

Per-run tail notes:

- uncapped heap: elapsed `3995.632`, `4012.669`, `3985.808 ms`; GC
  `157.131`, `161.727`, `158.277 ms`.
- trusted Streaming: elapsed `3907.191`, `3893.933`, `3906.291 ms`; GC
  `63.343`, `81.402`, `81.851 ms`.
- checked SafeZone-backed page-token: elapsed `3899.822`, `3921.127`,
  `3932.747 ms`; GC `81.327`, `106.352`, `111.654 ms`.

Interpretation: once parsing is timed, q2 is no longer simply
aggregation-CPU-bound. Region rows modestly beat uncapped heap and cut RSS by
about `45%`, but they do not eliminate GC because gzip/JSON/string parsing
still allocates on the heap. The `1G` heap cap fails before a result row, so
this is also fixed-memory evidence. The next question is whether q1 shows the
same cap behavior under file-backed timing and whether parser/string allocation
can be isolated or moved into reusable region scratch APIs.

## Two-Hour File-Backed Rows

Command:

```sh
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-1.json.gz" \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=200000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/Users/siyaoliu/rift/cache/github-archive-file-backed-2h-200k-2026-05-06 \
zsh sandbox/run_github_archive_region_matrix.sh
```

All rows loaded exactly `200000` real GH Archive events from two hourly gzip
JSON-line files and matched checksums/output counts.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `7549.355` | `198.535` | `247.303` | 3 | `2432679936` | `2600000` |
| q1-fields | `rift-trusted-streaming` | `7448.838` | `154.497` | `154.930` | 3 | `925466624` | `2600000` |
| q1-fields | `rift-checked-safezone-page-token` | `7489.923` | `193.910` | `197.487` | 3 | `925614080` | `2600000` |
| q2-repo-window | `heap-immix` | `7641.540` | `199.876` | `268.416` | 3 | `2431680512` | `31794` |
| q2-repo-window | `rift-trusted-streaming` | `7442.005` | `138.692` | `143.186` | 3 | `724779008` | `31794` |
| q2-repo-window | `rift-checked-safezone-page-token` | `7498.263` | `197.692` | `198.651` | 3 | `925630464` | `31794` |

Interpretation:

- Scaling file-backed GH Archive to two hours strengthens the RSS/fixed-memory
  story: region rows use about `0.72-0.93 GB` RSS while heap uses about
  `2.43 GB`.
- Throughput is still modest: trusted Streaming is about `1.3%` faster on q1
  and `2.6%` faster on q2; checked scoped page-token is only slightly faster
  than heap.
- Timed GC remains in all modes because file-backed parsing still constructs
  heap strings/builders while reading gzip JSON lines. The region rows move
  event/field records, not the current parser/string scratch path.

## File-Backed q1 Profile

Diagnostic profile, not headline timing:

```sh
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-1.json.gz" \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_EVENTS=200000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
SAFEZONE_ROOTS_MODE=1 \
SAFEZONE_PAGE_SIZE=32768 \
/usr/bin/sample <running GithubArchiveRegionMatrix pid> 10 1 -file /Users/siyaoliu/rift/cache/profile-gharchive-q1-checked-2026-05-06/sample.txt
```

Profiled row:

| Query | Mode | Median ms | GC ms | Max GC ms | RSS note |
|---|---|---:|---:|---:|---|
| q1-fields | `rift-checked-safezone-page-token` | `7393.602` | `192.570` | `193.221` | sampled diagnostic run |

Top sampled symbols were dominated by parser/string/decompression work:
`java.io.BufferedReader.readLine`, UTF-8 decoder loops,
`java.lang.AbstractStringBuilder.append0`, `java.lang.String.charAt`,
`BufferedReader.prepareRead`, `StringBuilder.append`,
`GithubArchiveRegionMatrixHelpers.countJsonFields`, stable hashing, and zlib
inflate. Allocator/GC symbols were visible but not the dominant stack.

Interpretation: the next GH Archive optimization should not be another
page-token allocator tweak. It should be a parser/string scratch path, such as
a byte-slice or char-slice NDJSON reader that avoids per-field substring/string
allocation, then a rerun of file-backed q1/q2.

## Byte-Slice File-Backed Parser

The parser-scratch prototype replaces the legacy `BufferedReader.readLine`
and per-line `String` field extraction path with a reusable byte-line reader.
It scans gzip/plain input into a reusable byte buffer, extracts the q1/q2 JSON
fields from raw UTF-8 bytes, and hashes byte slices directly. This is intended
as a general NDJSON/log-event parser-scratch shape: the same pattern should
apply to GH Archive, JSON logs, security events, GDELT-like records, and other
field-extraction stream workloads.

Important checksum note: q1 byte-slice checksums are not expected to match the
legacy string-parser q1 checksum because q1 now hashes raw UTF-8 byte slices
instead of decoded UTF-16 `String` characters. Heap and region rows match
within the same parser, which is the correctness criterion for these rows. q2
checksums happen to match the string path for the measured files.

### 20k Parser Control

The 20k smoke compared byte-slice and string parser paths on the same hourly
file. All heap/region rows matched checksum within each parser.

| Parser | Query | Mode | Median ms | Median GC ms | RSS bytes | Output count |
|---|---|---|---:|---:|---:|---:|
| byte-slice | q1-fields | `heap-immix` | `385.247` | `0.000` | `30654464` | `260000` |
| byte-slice | q1-fields | `rift-checked-safezone-page-token` | `379.485` | `0.000` | `37027840` | `260000` |
| string | q1-fields | `heap-immix` | `784.301` | `17.557` | `257572864` | `260000` |
| string | q1-fields | `rift-checked-safezone-page-token` | `770.319` | `11.507` | `269598720` | `260000` |
| byte-slice | q2-repo-window | `heap-immix` | `393.716` | `0.000` | `30605312` | `3874` |
| byte-slice | q2-repo-window | `rift-checked-safezone-page-token` | `382.408` | `0.000` | `37044224` | `3874` |
| string | q2-repo-window | `heap-immix` | `777.890` | `17.464` | `257540096` | `3874` |
| string | q2-repo-window | `rift-checked-safezone-page-token` | `780.185` | `11.050` | `269647872` | `3874` |

Interpretation: byte-slice parsing roughly halves file-backed elapsed at 20k
and removes the immediate parser-string GC/RSS cliff. This supports the
earlier profile result: the string parser, not page-token allocation, was the
dominant avoidable file-backed overhead.

### 100k Byte-Slice Rows

All rows loaded exactly `100000` events from one hourly file, materialized
`1300000` q1/q2 event-field records, and matched checksum/output count within
each query.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `1957.637` | `0.000` | `53.889` | 1 | `152911872` | `1300000` |
| q1-fields | `rift-trusted-streaming` | `1964.748` | `0.000` | `0.000` | 0 | `147243008` | `1300000` |
| q1-fields | `rift-checked-safezone-page-token` | `1957.640` | `0.000` | `0.000` | 0 | `147406848` | `1300000` |
| q2-repo-window | `heap-immix` | `1957.715` | `0.000` | `52.762` | 1 | `152911872` | `15877` |
| q2-repo-window | `rift-trusted-streaming` | `1968.853` | `0.000` | `0.000` | 0 | `147292160` | `15877` |
| q2-repo-window | `rift-checked-safezone-page-token` | `2005.000` | `0.000` | `0.000` | 0 | `147406848` | `15877` |

Interpretation: compared with the earlier 100k string-parser file-backed rows
around `4.0 s` and `1.2 GB` RSS, byte-slice parsing cuts elapsed and RSS
sharply. It also lowers median heap GC to zero at 100k, so this scale becomes
a near-tie/ceiling row rather than a strong region throughput win. Region rows
still remove heap GC outliers and use slightly less RSS.

### 200k Two-Hour Byte-Slice Rows

All rows loaded exactly `200000` real events from two hourly files and matched
checksum/output count.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `3806.120` | `57.685` | `73.922` | 2 | `290177024` | `2600000` |
| q1-fields | `rift-trusted-streaming` | `3626.219` | `0.000` | `0.000` | 0 | `211075072` | `2600000` |
| q1-fields | `rift-checked-safezone-page-token` | `3629.193` | `0.000` | `0.000` | 0 | `211238912` | `2600000` |
| q2-repo-window | `heap-immix` | `3756.950` | `61.625` | `67.231` | 2 | `290193408` | `31794` |
| q2-repo-window | `rift-trusted-streaming` | `3645.458` | `0.000` | `0.000` | 0 | `211075072` | `31794` |
| q2-repo-window | `rift-checked-safezone-page-token` | `3626.107` | `0.000` | `0.000` | 0 | `211222528` | `31794` |

Interpretation:

- Byte-slice parsing keeps the real file-backed workload realistic while
  removing the avoidable `String` parser allocation cliff.
- At 200k/two-hour scale, both trusted Streaming and checked scoped page-token
  are modest throughput wins and clear RSS wins: heap uses about `290 MB`
  while region rows use about `211 MB`.
- Region rows remove timed GC entirely. Heap collects in 2/3 timed runs for
  both q1 and q2.
- This is a better GH Archive story than the legacy string-parser rows, but it
  remains a modest real-input win rather than a GC-heavy case study. Heap GC is
  visible but small relative to total elapsed time.

### L1 Final-Clean 200k Two-Hour Byte-Slice Rows

Child support commit: `54bf38c45`.

These rows rerun the same two-hour byte-slice file-backed shape in
`RIFT_FINAL_CLEAN=1` mode. The benchmark binary prints only checksum/output
metadata; `/usr/bin/time -l` supplies external process real/user/sys time and
max RSS. L2 standard-stats rows above remain the GC interpretation source.

Command shape:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_INPUTS="/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-0.json.gz,/Users/siyaoliu/rift/cache/benchmark-data/gharchive/2026-04-01-1.json.gz" \
GITHUB_ARCHIVE_INPUT_MODE=file-backed \
GITHUB_ARCHIVE_FILE_PARSER=byte-slice \
GITHUB_ARCHIVE_EVENTS=200000 \
GITHUB_ARCHIVE_EVENTS_PER_BUCKET=25000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=0 \
GITHUB_ARCHIVE_QUERIES="q1-fields q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
GITHUB_ARCHIVE_OUTPUT_DIR=/tmp/rift-l1-gharchive-byte-200k-x3-54bf38c45-r1 \
zsh sandbox/run_github_archive_region_matrix.sh
```

Valid external repeats: `r1`, `r2e`, and `r3e`. One sandboxed repeat without
escalation was discarded because `/usr/bin/time -l` could not read max RSS.

| Query | Mode | External real median | External user median | Max RSS median | Output count |
|---|---|---:|---:|---:|---:|
| q1-fields | `heap-immix` | `13.17 s` | `12.42 s` | `265142272` | `2600000` |
| q1-fields | `safezone-improved-32k` | `13.10 s` | `12.62 s` | `101466112` | `2600000` |
| q1-fields | `rift-trusted-streaming` | `12.81 s` | `12.49 s` | `101351424` | `2600000` |
| q1-fields | `rift-checked-safezone-page-token` | `12.89 s` | `12.59 s` | `101416960` | `2600000` |
| q2-repo-window | `heap-immix` | `13.18 s` | `12.91 s` | `244039680` | `31794` |
| q2-repo-window | `safezone-improved-32k` | `12.90 s` | `12.58 s` | `101892096` | `31794` |
| q2-repo-window | `rift-trusted-streaming` | `12.79 s` | `12.53 s` | `101826560` | `31794` |
| q2-repo-window | `rift-checked-safezone-page-token` | `12.87 s` | `12.36 s` | `101859328` | `31794` |

Checksums matched across modes within each query:

- q1-fields: `818187435331427579`
- q2-repo-window: `3318970041429315053`

Interpretation: the L1 final-clean row confirms the L2 direction without using
internal GC/region counters in the timed binary. GH Archive byte-slice
file-backed q1/q2 is a modest real-input elapsed win and a clear RSS win for
region modes. Checked scoped page-token improves q1 by about `2%` and q2 by
about `2.4%` over heap, while cutting RSS by roughly `62%` and `58%`
respectively. This remains a real-input modest/RSS row rather than GC-heavy
flagship evidence; L2 shows heap GC is only about `1.5-1.6%` of elapsed.

## Current Decision

GH Archive remains in the real-input ladder, but its role is narrower than the
first generated/preloaded row suggested:

- It is promising for memory-budget and GC-tail experiments.
- With the legacy string parser it was mostly an RSS/fixed-memory row because
  parser allocation dominated. With the byte-slice parser, the 200k/two-hour
  file-backed q1/q2 rows become modest real-input throughput/RSS/tail wins, not
  GC-heavy rows.
- q1 is the useful shape: many ordinary field/event records with bucket
  lifetimes.
- q2 is no longer only a repo-aggregation CPU ceiling once parser allocation is
  reduced; the byte-slice two-hour q2 row also favors regions modestly.

## Generated/Preloaded Direct-Epoch q2 Follow-Up

Direct-epoch q2 modes were added after the reusable epoch topology work:

```bash
cd /Users/siyaoliu/rift/scala-native-rift
GITHUB_ARCHIVE_INPUT= \
GITHUB_ARCHIVE_INPUTS= \
GITHUB_ARCHIVE_INPUT_MODE=preloaded \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_QUERIES="q2-repo-window" \
GITHUB_ARCHIVE_MODES="gc-heap heap-direct-epoch checked-epoch-stream checked-epoch-scoped" \
GITHUB_ARCHIVE_BUILD=0 \
zsh sandbox/run_github_archive_region_matrix.sh
```

1M generated/preloaded direct-epoch same-shape rows:

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | `287.380` | `84.904` | `103.436` | `3/3` | `206618624` | `163487` |
| `heap-direct-epoch` | `54.642` | `0.000` | `0.000` | `0/3` | `6078464` | `163487` |
| `checked-epoch-stream` | `56.167` | `0.000` | `0.000` | `0/3` | `6111232` | `163487` |
| `checked-epoch-scoped` | `56.013` | `0.000` | `0.000` | `0/3` | `6111232` | `163487` |

Interpretation: this is an excellent direct-aggregate topology result, not a
pure memory-placement result. The checked direct-epoch q2 path allocates
ordinary event/field objects inside each bucket epoch and retains only primitive
repo counts until the original close point. The `heap-direct-epoch` control
shows that almost all of the speedup comes from avoiding generic page-token
close traversal and retaining summaries instead of records. Checked regions are
within about 3% of same-shape heap here.

## Generated/Preloaded Retained-Epoch q2 Reclaim Control

Retained no-traverse modes were added on 2026-05-09. These modes update the
same primitive summaries on append but keep ordinary Scala event/field records
linked and alive until the epoch closes. Close touches only head/tail anchors
and does not traverse records.

Command:

```bash
cd /Users/siyaoliu/rift/scala-native-rift
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_EVENTS_PER_BUCKET=25000 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_INPUT_MODE=preloaded \
GITHUB_ARCHIVE_QUERIES="q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-direct-summary-only heap-epoch-retained-no-traverse checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
zsh sandbox/run_github_archive_region_matrix.sh
```

All rows matched checksum `7294087528134281006` and output count `163487`.

| Mode | Evidence class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | topology lower bound | `70.416` | `0.000` | `0.000` | `0/3` | `6078464` |
| `heap-epoch-retained-no-traverse` | retained heap control | `244.988` | `69.552` | `76.134` | `3/3` | `147275776` |
| `checked-epoch-retained-no-traverse` | retained checked region | `191.064` | `0.000` | `0.000` | `0/3` | `15138816` |
| `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `181.345` | `0.000` | `0.000` | `0/3` | `15187968` |

Interpretation: the retained control turns the generated/preloaded GH-shaped q2
row into strong memory-management evidence. Checked scoped retained epoch is
`26.0%` faster than retained heap, removes `69.552 ms` median timed GC, and
uses roughly `90%` less RSS. The summary-only row is still topology/operator
evidence only.

Next useful GH Archive work:

1. Scale byte-slice file-backed q1/q2 to more hours or 1M events if the
   machine can tolerate the run, and report heap cap/RSS explicitly.
2. Generalize the byte-slice parser-scratch path into a reusable NDJSON/log
   extraction operator before adding GH-specific tuning.
3. Add latency/tail metrics or per-run elapsed tables, since median elapsed
   hides the GC outlier that regions remove.
