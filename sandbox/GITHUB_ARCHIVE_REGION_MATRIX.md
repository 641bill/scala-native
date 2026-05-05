# GH Archive Region Matrix

Date: 2026-05-03

Status: first real NDJSON/log-event stream matrix added and smoke-tested.
This is real preloaded input evidence using GH Archive hourly GitHub events,
not a generated stream. The benchmark excludes network/decompression timing
from the timed loop by preloading event metadata before warmups.

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

GH Archive publishes hourly gzip-compressed JSON-lines event files. The matrix
extracts event type, repo, actor, field-count, and a stable line hash into
primitive preloaded arrays, then times object allocation/query processing.

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

## Current Decision

GH Archive remains in the real-input ladder, but its role is narrower than the
first 100k row suggested:

- It is promising for memory-budget and GC-tail experiments.
- It is not yet an uncapped throughput case study, because heap can win median
  elapsed by growing to a large heap and collecting rarely.
- q1 is the useful shape: many ordinary field/event records with bucket
  lifetimes.
- q2 is mostly a repo-aggregation CPU row.

Next useful GH Archive work:

1. Add a file-backed timed variant to include parse/string allocation, because
   the current rows preload primitive metadata before timing.
2. Add a larger multi-hour/day run only if the machine can tolerate multi-GB
   heap RSS, and report heap cap/RSS explicitly.
3. Add latency/tail metrics or per-run elapsed tables, since median elapsed
   hides the GC outlier that regions remove.
