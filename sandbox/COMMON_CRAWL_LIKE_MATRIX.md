# Common Crawl-Like Object-Heavy Stream Matrix

Status: WET-shaped q1/q2/q3 generated follow-up recorded; q1/q2 rerun after
Rift fast-allocation counter cleanup.

Date: 2026-05-02

## Purpose

Common Crawl WET-shaped tokenization is currently the clearest local
stream/object-pressure detector: heap spends material time in GC on generated
1M q1/q2 rows, and trusted Rift now beats heap plus improved SafeZone-32k after
removing default per-allocation global byte-counter atomics from the Rift fast
path. This matrix expands that workload family before more DEBS-specific
tuning.

## Implemented Queries

Current runner: `sandbox/run_common_crawl_wet_matrix.sh`.

| Query | Meaning | Region candidate |
|---|---|---|
| `q0-parse` | Allocate page and line records, no token records. | Page/line records with bucket lifetime. |
| `q1-tokenize` | Allocate page, line, and token records retained until bucket close. | Ordinary token records in bucket regions. |
| `q2-domain-window` | Allocate page/line/token records, then aggregate per domain at bucket close. | Bucket records plus close-time aggregate summaries. |
| `q3-parser-scratch` | Allocate page/line/token scratch records and consume them immediately. | Parser scratch objects with bucket/page-like lifetime. |

The default runner now includes all four queries. It still has only heap,
SafeZone-family, and trusted Rift modes. The SafeZone-family labels include
`safezone-improved-32k` and `safezone-chunk` so non-trace follow-up rows can be
compared against `unsafezone-hp` without ambiguous environment overrides.
Checked modes remain out until the corresponding checked append/scratch/window
operator clears a focused gate.

## Command

```sh
cd /Users/siyaoliu/rift/scala-native-rift
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

For a smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
COMMON_CRAWL_WET_PAGES=20000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_QUERIES="q2-domain-window q3-parser-scratch" \
COMMON_CRAWL_WET_MODES="heap safezone-improved unsafezone-hp rift-hp" \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

## Interpretation Rules

- Use generated rows to expose allocation topology and object-pressure shape.
- Use real/preloaded WET rows only as real-input validation; parser/file effects
  must not hide memory-management effects.
- A row is a serious case-study candidate only if Rift or a checked backend
  beats heap and improved SafeZone by about 10%, or materially cuts GC/RSS with
  no more than 5% elapsed overhead.
- UnsafeZone rows are backend lower bounds, not safety evidence.

## Smoke Result

Command:

```sh
COMMON_CRAWL_WET_PAGES=2000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_QUERIES="q2-domain-window q3-parser-scratch" \
COMMON_CRAWL_WET_MODES="heap safezone-improved unsafezone-hp rift-hp" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-like-smoke \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

| Query | Mode | Median ms | GC ms | Output count | Interpretation |
|---|---|---:|---:|---:|---|
| q2-domain-window | heap | 14.005 | 4.327 | 1878 | Smoke control. |
| q2-domain-window | safezone-improved | 9.470 | 0.000 | 1878 | Smoke only; SafeZone-family wins this tiny row. |
| q2-domain-window | unsafezone-hp | 9.338 | 0.000 | 1878 | Unsafe lower-bound control. |
| q2-domain-window | rift-hp | 10.334 | 0.000 | 1878 | Trusted Rift close behind. |
| q3-parser-scratch | heap | 20.944 | 2.228 | 274000 | Heap is fastest on this tiny scratch row. |
| q3-parser-scratch | safezone-improved | 25.152 | 3.066 | 274000 | Smoke only; not a win. |
| q3-parser-scratch | unsafezone-hp | 22.571 | 0.548 | 274000 | Unsafe lower-bound control. |
| q3-parser-scratch | rift-hp | 23.118 | 0.544 | 274000 | Trusted row, not a win at this scale. |

All rows matched checksums/output counts. These are validation rows, not
headline evidence.

## Generated 100k/1M Follow-Up

Run outputs:

- 100k summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-2026-05-01-100k/summary.tsv`
- 1M summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-2026-05-01-1m/summary.tsv`

Commands:

```sh
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window q3-parser-scratch" \
COMMON_CRAWL_WET_MODES="heap safezone-improved safezone-improved-32k safezone-chunk unsafezone-hp rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-like-2026-05-01-100k \
zsh sandbox/run_common_crawl_wet_matrix.sh

COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window q3-parser-scratch" \
COMMON_CRAWL_WET_MODES="heap safezone-improved safezone-improved-32k safezone-chunk unsafezone-hp rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-like-2026-05-01-1m \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All rows matched checksum/output count within each query.

### 100k Medians

| Query | Mode | Median ms | GC ms | Max GC ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 542.940 | 152.224 | 163.172 | 408518656 | 13700000 |
| q1-tokenize | safezone-improved | 462.713 | 0.000 | 0.000 | 356532224 | 13700000 |
| q1-tokenize | safezone-improved-32k | 461.260 | 0.000 | 0.000 | 356007936 | 13700000 |
| q1-tokenize | safezone-chunk | 474.707 | 0.000 | 0.000 | 356302848 | 13700000 |
| q1-tokenize | unsafezone-hp | 461.226 | 0.000 | 0.000 | 356007936 | 13700000 |
| q1-tokenize | rift-hp | 493.272 | 0.000 | 0.000 | 355926016 | 13700000 |
| q1-tokenize | rift-streaming | 494.277 | 0.000 | 0.000 | 355860480 | 13700000 |
| q2-domain-window | heap | 520.972 | 150.045 | 151.369 | 408518656 | 92994 |
| q2-domain-window | safezone-improved | 448.033 | 0.000 | 0.000 | 420888576 | 92994 |
| q2-domain-window | safezone-improved-32k | 444.125 | 0.000 | 0.000 | 420331520 | 92994 |
| q2-domain-window | safezone-chunk | 461.407 | 0.000 | 0.000 | 420626432 | 92994 |
| q2-domain-window | unsafezone-hp | 446.613 | 0.000 | 0.000 | 420315136 | 92994 |
| q2-domain-window | rift-hp | 479.030 | 0.000 | 0.000 | 420249600 | 92994 |
| q2-domain-window | rift-streaming | 476.392 | 0.000 | 0.000 | 420167680 | 92994 |
| q3-parser-scratch | heap | 1034.587 | 87.623 | 88.935 | 8159232 | 13700000 |
| q3-parser-scratch | safezone-improved | 2733.309 | 1632.760 | 1669.492 | 74792960 | 13700000 |
| q3-parser-scratch | safezone-improved-32k | 2588.460 | 1497.756 | 1498.895 | 74285056 | 13700000 |
| q3-parser-scratch | safezone-chunk | 2852.432 | 1768.004 | 1768.027 | 75546624 | 13700000 |
| q3-parser-scratch | unsafezone-hp | 1129.705 | 36.999 | 37.248 | 74203136 | 13700000 |
| q3-parser-scratch | rift-hp | 1150.879 | 35.293 | 35.631 | 74121216 | 13700000 |
| q3-parser-scratch | rift-streaming | 1141.775 | 36.239 | 38.345 | 74039296 | 13700000 |

### 1M Medians

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 5531.233 | 1575.099 | 1618.345 | 0.000 | 408535040 | 137000000 |
| q1-tokenize | safezone-improved | 4709.218 | 33.286 | 34.427 | 0.000 | 475168768 | 137000000 |
| q1-tokenize | safezone-improved-32k | 4674.258 | 35.554 | 35.603 | 0.000 | 474693632 | 137000000 |
| q1-tokenize | safezone-chunk | 4825.260 | 36.167 | 36.632 | 0.000 | 475938816 | 137000000 |
| q1-tokenize | unsafezone-hp | 4665.711 | 20.924 | 25.295 | 0.000 | 474578944 | 137000000 |
| q1-tokenize | rift-hp | 4966.111 | 20.897 | 20.933 | 12.757 | 474480640 | 137000000 |
| q1-tokenize | rift-streaming | 5084.024 | 20.912 | 21.538 | 12.397 | 474431488 | 137000000 |
| q2-domain-window | heap | 5344.266 | 1606.364 | 1629.713 | 0.000 | 408535040 | 929230 |
| q2-domain-window | safezone-improved | 4546.604 | 33.696 | 33.865 | 0.000 | 475168768 | 929230 |
| q2-domain-window | safezone-improved-32k | 4471.463 | 32.228 | 33.652 | 0.000 | 474644480 | 929230 |
| q2-domain-window | safezone-chunk | 4658.703 | 34.553 | 36.130 | 0.000 | 475922432 | 929230 |
| q2-domain-window | unsafezone-hp | 4511.995 | 19.950 | 21.140 | 0.000 | 474595328 | 929230 |
| q2-domain-window | rift-hp | 4738.091 | 19.827 | 21.685 | 12.631 | 474480640 | 929230 |
| q2-domain-window | rift-streaming | 4782.135 | 20.992 | 35.024 | 12.652 | 474431488 | 929230 |
| q3-parser-scratch | heap | 10330.962 | 859.220 | 862.683 | 0.000 | 8175616 | 137000000 |
| q3-parser-scratch | safezone-improved | 27715.527 | 16817.812 | 16838.088 | 0.000 | 74825728 | 137000000 |
| q3-parser-scratch | safezone-improved-32k | 26535.424 | 15629.667 | 15730.313 | 0.000 | 74301440 | 137000000 |
| q3-parser-scratch | safezone-chunk | 28426.126 | 17537.924 | 17613.807 | 0.000 | 75563008 | 137000000 |
| q3-parser-scratch | unsafezone-hp | 11065.693 | 362.074 | 365.415 | 0.000 | 74219520 | 137000000 |
| q3-parser-scratch | rift-hp | 11233.751 | 364.479 | 366.564 | 15.971 | 74104832 | 137000000 |
| q3-parser-scratch | rift-streaming | 11206.504 | 351.160 | 357.819 | 16.858 | 74055680 | 137000000 |

Interpretation:

- q1 and q2 are real GC-heavy generated WET-shaped stream rows under Scala
  Native: heap spends about `1.6 s` in GC at 1M.
- SafeZone-family modes convert that GC pressure into elapsed wins. On q1,
  `unsafezone-hp` is narrowly best, with `safezone-improved-32k` only
  `8.547 ms` slower. On q2, `safezone-improved-32k` is best.
- Current trusted Rift HP/Streaming reduce GC and RSS similarly, but are
  slower than SafeZone-family modes on q1/q2. The gap is not Rift open/close
  bookkeeping alone: measured Rift op time is only about `12-13 ms` at 1M.
- q3-parser-scratch is a negative/ceiling control, not a case-study row. Heap
  wins elapsed despite material GC. Improved/chunk SafeZone are particularly
  bad here because the scratch shape produces large GC time in those modes.
  UnsafeZone-HP and Rift cut GC but still lose to heap elapsed.
- The `SAFEZONE_TRACE=1` unsafe-hp q1 pathology from
  `SAFEZONE_COST_MATRIX.md` does not reproduce without tracing. Keep it as a
  tracing/instrumentation warning.

## Rift Fast Allocation Counter Follow-Up

Run outputs:

- 100k summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-fastalloc-2026-05-02-100k/summary.tsv`
- 1M summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-fastalloc-2026-05-02-1m/summary.tsv`
- precise-allocation-stats control:
  `/Users/siyaoliu/rift/cache/common-crawl-like-precise-stats-2026-05-02-100k/summary.tsv`

Implementation change being measured:

- Rift still counts region objects and total raw bytes.
- Total raw bytes are now accumulated in the region and flushed at close/reset
  instead of updating global/family atomics on every allocation.
- Precise active allocated-byte current/peak counters are now diagnostic-only:
  set `RIFT_PRECISE_ALLOC_STATS=1` when those active-byte counters matter.

All rows below matched checksum/output count within each query.

### 100k Medians After Counter Cleanup

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Rift slow alloc ms | Region objects | Raw region bytes | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 541.742 | 150.036 | 161.032 | 0.000 | 0.000 | 0 | 0 | 408584192 |
| q1-tokenize | safezone-improved-32k | 460.259 | 0.000 | 0.000 | 0.000 | 0.000 | 0 | 0 | 356073472 |
| q1-tokenize | unsafezone-hp | 458.006 | 0.000 | 0.000 | 0.000 | 0.000 | 0 | 0 | 356057088 |
| q1-tokenize | rift-hp | 439.129 | 0.000 | 0.000 | 1.061 | 0.745 | 13700000 | 657600000 | 355991552 |
| q1-tokenize | rift-streaming | 438.521 | 0.000 | 0.000 | 1.062 | 0.739 | 13700000 | 657600000 | 355909632 |
| q2-domain-window | heap | 518.950 | 147.000 | 150.176 | 0.000 | 0.000 | 0 | 0 | 408584192 |
| q2-domain-window | safezone-improved-32k | 444.618 | 0.000 | 0.000 | 0.000 | 0.000 | 0 | 0 | 420397056 |
| q2-domain-window | unsafezone-hp | 446.103 | 0.000 | 0.000 | 0.000 | 0.000 | 0 | 0 | 420413440 |
| q2-domain-window | rift-hp | 424.586 | 0.000 | 0.000 | 1.043 | 0.739 | 13700000 | 657600000 | 420331520 |
| q2-domain-window | rift-streaming | 422.458 | 0.000 | 0.000 | 1.067 | 0.757 | 13700000 | 657600000 | 420265984 |

### 1M Medians After Counter Cleanup

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Rift slow alloc ms | Region objects | Raw region bytes | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 5466.535 | 1580.847 | 1602.118 | 0.000 | 0.000 | 0 | 0 | 408584192 |
| q1-tokenize | safezone-improved-32k | 4608.641 | 30.657 | 33.693 | 0.000 | 0.000 | 0 | 0 | 474710016 |
| q1-tokenize | unsafezone-hp | 4640.245 | 20.298 | 20.603 | 0.000 | 0.000 | 0 | 0 | 474644480 |
| q1-tokenize | rift-hp | 4386.590 | 20.298 | 20.326 | 10.863 | 7.609 | 137000000 | 6576000000 | 474546176 |
| q1-tokenize | rift-streaming | 4395.599 | 20.227 | 20.489 | 11.235 | 7.903 | 137000000 | 6576000000 | 474480640 |
| q2-domain-window | heap | 5267.784 | 1561.851 | 1591.108 | 0.000 | 0.000 | 0 | 0 | 408584192 |
| q2-domain-window | safezone-improved-32k | 4425.273 | 28.307 | 34.794 | 0.000 | 0.000 | 0 | 0 | 474742784 |
| q2-domain-window | unsafezone-hp | 4437.924 | 19.419 | 21.288 | 0.000 | 0.000 | 0 | 0 | 474644480 |
| q2-domain-window | rift-hp | 4176.919 | 20.335 | 20.799 | 10.631 | 7.510 | 137000000 | 6576000000 | 474546176 |
| q2-domain-window | rift-streaming | 4164.288 | 20.044 | 20.112 | 10.446 | 7.293 | 137000000 | 6576000000 | 474480640 |

### Precise Stats Control

At 100k q1, `rift-hp` with default fast allocation counters is `439.129 ms`.
With `RIFT_PRECISE_ALLOC_STATS=1`, the same mode is `473.750 ms`.

This supports the diagnosis that the previous Common Crawl-like Rift gap was
partly measurement overhead from per-allocation global/family allocated-byte
atomics. Keep precise active allocated-byte counters as diagnostics, not
default benchmark instrumentation.

### Updated Interpretation

- q1 and q2 are now the strongest local GC-heavy stream/object evidence for
  current Rift HP/Streaming: the 1M rows region-allocate `137000000` ordinary
  token/page/line objects and `6.576 GB` of raw region payload, cut heap GC
  from about `1.56-1.58 s` to about `20 ms`, and beat heap by about
  `20-21%`.
- After removing default per-allocation global byte-counter atomics, Rift also
  beats improved SafeZone-32k and UnsafeZone-HP on q1/q2 elapsed time.
- RSS is not a Rift win at 1M: heap peak RSS is lower in these runs, while
  SafeZone/Rift retain a larger pool/working set. Use these rows as elapsed/GC
  wins, not memory-footprint wins.
- q3-parser-scratch remains a negative control from the earlier follow-up:
  heap wins elapsed despite material GC, so not every object-heavy parser
  shape benefits from region placement.

## Next Similar Workloads

If WET-shaped q1/q2/q3 remain promising, add one more text/object-heavy stream:

- WAT-like metadata/link extraction;
- NDJSON/log-event parsing and tokenization;
- DSPBench or HiBench streaming text workloads with Kafka removed and
  preloaded/local controls.
