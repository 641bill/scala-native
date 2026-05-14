# Common Crawl-Like Object-Heavy Stream Matrix

Status: WET-shaped q1/q2/q3 generated follow-up recorded; q1/q2 rerun after
Rift fast-allocation counter cleanup; checked `StreamAppendWindow` follow-up
recorded for q1/q2; checked SafeZone-backed backend follow-up recorded; new
checked page/token operator follow-up recorded and clears the generated
application-shaped gate.

Date: 2026-05-03
Last updated: 2026-05-14 14:41 CEST

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
| `q4-wat-links` | On real WAT input, allocate page records plus extracted link/URL records. | WAT link records with bucket/page lifetime. |
| `q5-wat-link-domain-window` | On real WAT input, allocate page/link records, then aggregate link domains at bucket close. | Bucket-owned link records plus close-time domain summaries. |

The default runner now includes the generated WET-shaped queries. Real WAT
q4/q5 are opt-in because they require a WAT input file. The default mode set still
uses heap, SafeZone-family, and trusted Rift modes. The SafeZone-family labels
include `safezone-improved-32k` and `safezone-chunk` so non-trace follow-up
rows can be compared against `unsafezone-hp` without ambiguous environment
overrides. `rift-checked` is available as an opt-in q1/q2 follow-up mode using
the reusable checked `StreamAppendWindow` cursor API. The newer
`rift-checked-page-token` and `rift-checked-safezone-page-token` modes use
`StreamPageTokenAppendWindow`, an operator-owned path that removes redundant
per-record checks and child-region lookups.
`rift-checked-page-token-open-handle` is an experimental Rift-only lowering
gate for the same page-token topology: it keeps the checked page/window API
shape but sends record allocation through the handle-level Rift allocator path.
`rift-checked-count-by-key` and `rift-checked-safezone-count-by-key` are
available as opt-in q2/q5-only controls using `PageTokenCountByKey`, which
updates domain counts during append and closes buckets without record drain.

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

## Checked SafeZone-Backed Backend Follow-Up

Detailed source: `evidence/CHECKED_SAFEZONE_BACKEND_MATRIX.md`.

## Final-Clean Page-Token Rerun After Open Allocation Cleanup

Date/time: 2026-05-12 15:55 CEST.

This is an L1 final-clean rerun from child `c22c78d57`, after the
operator-owned open-allocation wrapper cleanup. Each process executes three
benchmark runs under external `/usr/bin/time -l`; no diagnostic timers,
allocation attribution, or region/stat reads are used in the timed section.

Source:
`/Users/siyaoliu/rift/cache/clean-rerun-20260512-commoncrawl-page-token`.

| Query | Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---|---:|---:|---:|---:|---:|
| q1-tokenize | `heap-immix` | 17.49 | 16.86 | 408518656 | `-3166891223384968696` | 137000000 |
| q1-tokenize | `rift-checked-page-token` | 10.88 | 10.69 | 63242240 | `-3166891223384968696` | 137000000 |
| q1-tokenize | `rift-checked-safezone-page-token` | 13.29 | 13.04 | 63373312 | `-3166891223384968696` | 137000000 |
| q2-domain-window | `heap-immix` | 16.73 | 16.32 | 408518656 | `1076064953308107199` | 929230 |
| q2-domain-window | `rift-checked-page-token` | 11.49 | 11.24 | 63258624 | `1076064953308107199` | 929230 |
| q2-domain-window | `rift-checked-safezone-page-token` | 14.84 | 14.20 | 63340544 | `1076064953308107199` | 929230 |

Interpretation: this is generated WET-shaped stream-object pressure evidence.
Checked Rift page-token is the fastest row in both q1 and q2 in this L1 rerun,
and both checked page-token rows cut RSS substantially versus heap. Because the
input is generated, report it as a methodology/stressor win, not real-input
proof.

The 2026-05-03 follow-up adds `rift-checked-safezone-32k`, a benchmark-only
checked backend that runs the existing checked `StreamAppendWindow` q1/q2 path
over SafeZone allocator internals with `SAFEZONE_ROOTS_MODE=1` and
`SAFEZONE_PAGE_SIZE=32768`.

At 1M generated pages:

| Query | heap | improved-32k | unsafezone-hp | trusted best | rift-checked | checked SafeZone-backed | Interpretation |
|---|---:|---:|---:|---:|---:|---:|---|
| q1-tokenize | `5412.668 ms` | `4570.772 ms` | `4534.978 ms` | HPZone `4278.440 ms` | `4744.872 ms` | `4512.743 ms` | SafeZone-backed checked is `4.9%` faster than current checked and close to improved-32k, but still trails trusted HPZone. |
| q2-domain-window | `5190.246 ms` | `4362.405 ms` | `4342.620 ms` | HPZone `4075.431 ms` | `4698.903 ms` | `4431.865 ms` | SafeZone-backed checked is `5.7%` faster than current checked, but trails improved-32k and trusted Rift. |

All q1/q2 rows matched checksum/output count. Heap remains strongly GC-heavy
at 1M (`1.55-1.59 s` median GC), while region/SafeZone-family rows reduce GC
to about `19-32 ms`. The checked SafeZone-backed backend is useful backend
evidence, but it misses the application follow-up gate and should not be used
as a checked application-speed claim.

## Checked Page/Token Operator Follow-Up

Detailed source: `evidence/CHECKED_PAGE_TOKEN_APPEND_MATRIX.md`.

The 2026-05-03 follow-up adds `StreamPageTokenAppendWindow`, a checked
operator-owned page/token append path. It still allocates ordinary Scala record
objects in child bucket regions, but user code does not receive reusable bucket
tokens on the hot append path. The operator owns bucket lookup, child-region
caching, append, and close.

At 1M generated pages:

| Query | heap | improved-32k | trusted best | current checked | checked page-token | checked SafeZone page-token | Interpretation |
|---|---:|---:|---:|---:|---:|---:|---|
| q1-tokenize | `5412.618 ms` | `4637.981 ms` | HPZone `4367.265 ms` | `4855.133 ms` | `3956.366 ms` | `3728.286 ms` | Page-token checked improves current checked by `18.5%` and beats trusted HPZone by `9.4%`; SafeZone-backed page-token is fastest in this generated row. |
| q2-domain-window | `5252.803 ms` | `4580.687 ms` | Streaming `4212.494 ms` | `4820.611 ms` | `4039.855 ms` | `3816.247 ms` | Page-token checked improves current checked by `16.2%` and beats trusted Streaming by `4.1%`; SafeZone-backed page-token is fastest in this generated row. |

All rows matched checksum/output count. Heap GC remains about `1.57 s`, while
region/SafeZone-family rows reduce GC to about `19-35 ms`. Both page-token
rows reduce RSS by about `10-11 MiB` relative to the previous checked rows.

Interpretation: this is the first checked operator/application-shaped Common
Crawl-like result that clears the generated q1/q2 gate. It directly supports
the static-safety overhead-removal story: when the operator owns the lifetime
boundary, it can avoid redundant per-record dynamic checks without changing the
logical program. Caveat: it remains generated WET-shaped stressor evidence, not
real Common Crawl input proof.

### Page-Token Handle-Level Lowering Gate

Date/time: 2026-05-13 02:37 CEST.

This gate tests whether the positive handle-level allocation result from
`ObjectAllocationLoweringMatrix` and `StreamFlexDesignMatrix` also transfers to
the page/window stream shape. The new mode keeps the same q2
`StreamPageTokenAppendWindow` topology and close traversal, but
`pageTokenAppendRiftOpenHandleFor` returns a Rift backend handle for the active
bucket and record construction uses `RiftAllocator.allocateOpenHandle`.
SafeZone-backed page-token rows are deliberately not routed through this path.

20k smoke:

| Mode | External real s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|
| `rift-checked-page-token` | 0.41 | 63242240 | -7246040220793005327 | 18592 |
| `rift-checked-page-token-open-handle` | 0.07 | 63242240 | -7246040220793005327 | 18592 |

1M L1, three measured runs inside each non-profiled process:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `rift-checked-page-token` | 10.62 | 10.56 | 63324160 | 1076064953308107199 | 929230 |
| `rift-checked-page-token-open-handle` | 9.66 | 9.61 | 63340544 | 1076064953308107199 | 929230 |

1M L2 interpretation, three measured runs:

| Mode | Median ms | GC median ms | Rift op ms | Slow alloc ms | Region objects | Opens/closes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-page-token` | 3883.742 | 21.017 | 8.803 | 6.271 | 137000000 | 401/401 | 1076064953308107199 |
| `rift-checked-page-token-open-handle` | 3695.704 | 20.648 | 10.365 | 6.831 | 137000000 | 401/401 | 1076064953308107199 |

Interpretation: handle-level allocation also helps the generated page/window
stream shape. L1 improves by about `9.0%` with the same checksum/output and
nearly identical RSS; L2 improves by about `4.8%` with the same allocation and
open/close counts. This is still generated stressor evidence and an
experimental lowering gate, not a final public API row.

### Promoted Page-Token Label Gate

Date/time: 2026-05-13 02:49 CEST.

The normal `rift-checked-page-token` benchmark label now uses the handle-backed
Rift page-token path. The previous `OpenStreamingRegion` lowering remains
available as `rift-checked-page-token-legacy` for comparison and regression
tracking.

20k smoke:

| Mode | External real s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|
| `rift-checked-page-token-legacy` | 0.33 | 63242240 | -7246040220793005327 | 18592 |
| `rift-checked-page-token` | 0.07 | 63225856 | -7246040220793005327 | 18592 |

1M L1, three measured runs inside each non-profiled process:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `rift-checked-page-token-legacy` | 11.18 | 11.10 | 63324160 | 1076064953308107199 | 929230 |
| `rift-checked-page-token` | 10.59 | 10.11 | 63324160 | 1076064953308107199 | 929230 |

1M L2 interpretation, three measured runs:

| Mode | Median ms | GC median ms | Rift op ms | Slow alloc ms | Region objects | Opens/closes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-page-token-legacy` | 3909.015 | 31.825 | 8.532 | 6.252 | 137000000 | 401/401 | 1076064953308107199 |
| `rift-checked-page-token` | 3562.617 | 23.230 | 8.568 | 6.166 | 137000000 | 401/401 | 1076064953308107199 |

Interpretation: after promotion, the default Rift checked page-token row is
the handle-backed path. The comparison to `rift-checked-page-token-legacy` is
the fair before/after control: L1 improves by about `5.3%`, and L2 median time
improves by about `8.9%`, with matching checksum/output, identical object
counts, identical open/close counts, and identical RSS in the L1 row.

### Page-Token Diagnostic Follow-Up

Diagnostic source:
`/Users/siyaoliu/rift/cache/common-crawl-page-token-diag2-2026-05-07`.

Command shape:

```sh
COMMON_CRAWL_WET_DIAG=1 \
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-page-token-diag2-2026-05-07 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

These are non-headline one-run diagnostic rows. `bucket_switch_ms` includes
expired-bucket close because `pageTokenAppendRegionFor` closes old buckets
synchronously. Use `estimated_bucket_open_ms` for the open/switch cost after
subtracting expired close traversal.

| Query | Mode | Elapsed ms | Appended/closed records | Estimated bucket open ms | Append ms | Close cursor ms | Aggregate ms | Interpretation |
|---|---|---:|---:|---:|---:|---:|---:|---|
| q1-tokenize | `rift-checked-page-token` | `4231.669` | `137000000` | `2.672` | `3444.176` | `757.595` | `0.000` | Open/switch is negligible; append and close traversal dominate. |
| q1-tokenize | `rift-checked-safezone-page-token` | `3976.030` | `137000000` | `9.829` | `3177.917` | `760.700` | `0.000` | SafeZone-backed checked is faster mainly in append/allocation. |
| q2-domain-window | `rift-checked-page-token` | `4356.286` | `137000000` | `2.914` | `3515.311` | `810.644` | `18.309` | Domain aggregation is small relative to append/traversal. |
| q2-domain-window | `rift-checked-safezone-page-token` | `4055.473` | `137000000` | `9.363` | `3215.592` | `802.633` | `18.347` | Same conclusion as q1; backend affects append more than traversal. |

Decision: do not spend the next patch on bucket opening. The realistic
remaining levers are reducing per-record close traversal where query work can
be completed on append, or making cursor/node traversal cheaper.

### Count-By-Key Application Gate

Detailed source:
`/Users/siyaoliu/rift/cache/common-crawl-count-by-key-100k-2026-05-07`.

Change:

- Added q2/q5-only Common Crawl modes:
  - `rift-checked-count-by-key`
  - `rift-checked-safezone-count-by-key`
- These modes still allocate ordinary page/line/token records in child bucket
  regions, but update per-domain counts during append via
  `PageTokenCountByKey`.
- Bucket close emits domain summaries with no per-record drain.

Smoke:

```sh
COMMON_CRAWL_WET_BUILD=1 \
COMMON_CRAWL_WET_PAGES=20000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_QUERIES="q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix rift-checked-page-token rift-checked-count-by-key rift-checked-safezone-page-token rift-checked-safezone-count-by-key" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-count-by-key-smoke-2026-05-07 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

100k median:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix rift-checked-page-token rift-checked-count-by-key rift-checked-safezone-page-token rift-checked-safezone-count-by-key" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-count-by-key-100k-2026-05-07 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

| Query | Mode | Median elapsed | Median GC | Output count | Interpretation |
|---|---|---:|---:|---:|---|
| q2-domain-window | `heap-immix` | `525.285` ms | `151.164` ms | `92994` | Heap spends material GC at 100k generated pages. |
| q2-domain-window | `rift-checked-page-token` | `433.051` ms | `0.000` ms | `92994` | Existing page-token remains faster than count-by-key. |
| q2-domain-window | `rift-checked-count-by-key` | `476.665` ms | `0.000` ms | `92994` | Correct, but slower than existing checked page-token. |
| q2-domain-window | `rift-checked-safezone-page-token` | `406.413` ms | `0.000` ms | `92994` | Fastest checked row in this application-shaped q2 rerun. |
| q2-domain-window | `rift-checked-safezone-count-by-key` | `450.289` ms | `0.000` ms | `92994` | Correct, but slower than checked SafeZone-backed page-token. |

Decision:

- Stop before 1M for this application path. The focused 1M
  `PageTokenCountByKey` row is a modest win, but Common Crawl-shaped q2 does
  not benefit at 100k: per-record count updates cost more than the close-time
  traversal they remove.
- Keep the mode as a negative/application-gated control. The existing
  `StreamPageTokenAppendWindow` path remains the Common Crawl q2 application
  row.
- If this operator is tried again, use a workload where append-time aggregates
  are semantically required or where close-time traversal is much more
  expensive than one primitive count update per record.

## Real WAT Link-Metadata Control

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wat`

Command:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wat \
COMMON_CRAWL_WET_PAGES=50000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q4-wat-links q5-wat-link-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix safezone-improved-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-wat-links-2026-05-03 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All rows matched checksum/output count. These rows use real Common Crawl WAT
metadata and materialize ordinary page/link records, but heap still reports
zero timed GC on this shard.

| Query | Mode | Median ms | GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|
| q4-wat-links | `heap-immix` | `33.646` | `0.000` | 0 | `968704000` | `1006742` |
| q4-wat-links | `safezone-improved-32k` | `39.551` | `0.000` | 0 | `1061011456` | `1006742` |
| q4-wat-links | `rift-trusted-hp` | `38.766` | `0.000` | 0 | `1166393344` | `1006742` |
| q4-wat-links | `rift-trusted-streaming` | `38.541` | `0.000` | 0 | `1143963648` | `1006742` |
| q4-wat-links | `rift-checked-page-token` | `35.085` | `0.000` | 0 | `1142358016` | `1006742` |
| q4-wat-links | `rift-checked-safezone-page-token` | `31.792` | `0.000` | 0 | `1171996672` | `1006742` |
| q5-wat-link-domain-window | `heap-immix` | `35.066` | `0.000` | 0 | `957562880` | `293020` |
| q5-wat-link-domain-window | `safezone-improved-32k` | `39.579` | `0.000` | 0 | `1156055040` | `293020` |
| q5-wat-link-domain-window | `rift-trusted-hp` | `38.682` | `0.000` | 0 | `1143980032` | `293020` |
| q5-wat-link-domain-window | `rift-trusted-streaming` | `38.656` | `0.000` | 0 | `1030471680` | `293020` |
| q5-wat-link-domain-window | `rift-checked-page-token` | `36.546` | `0.000` | 0 | `962789376` | `293020` |
| q5-wat-link-domain-window | `rift-checked-safezone-page-token` | `33.937` | `0.000` | 0 | `1144209408` | `293020` |

One 100k requested-page q4 probe also stayed median/max-GC-zero: heap
`43.274 ms`, SafeZone-backed page-token `42.226 ms`, output count `1339183`.

Interpretation: real WAT link extraction is a useful correctness and
real-input control, and the SafeZone-backed page-token path is modestly fastest
on this shard. It is not a GC-heavy case-study row because heap has zero timed
collections even at the larger one-run probe.

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

## Checked StreamAppendWindow Follow-Up

Run outputs:

- 100k summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-checked-2026-05-02-100k/summary.tsv`
- 1M RSS-complete summary:
  `/Users/siyaoliu/rift/cache/common-crawl-like-checked-2026-05-02-1m-rss/summary.tsv`
- earlier 1M timing-only summary, before rerunning for RSS:
  `/Users/siyaoliu/rift/cache/common-crawl-like-checked-2026-05-02-1m/summary.tsv`

Implementation being measured:

- `rift-checked` allocates ordinary page, line, and token `CheckedRecord`
  objects in checked child bucket regions.
- Records are retained through `RiftRegion.StreamAppendWindow` and consumed at
  close with `StreamAppendCursor`.
- q2 still uses a heap `Array[Int]` as close-time aggregate scratch, matching
  the heap/trusted logical aggregation shape. This follow-up checks the record
  lifetime path, not a checked aggregate-table path.
- A helper that hid record provenance was rejected by the checked compiler; the
  final implementation appends immediately at the allocation site. Treat that
  as positive safety evidence for the owner-token boundary.

Commands:

```sh
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap safezone-improved-32k unsafezone-hp rift-hp rift-streaming rift-checked" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-like-checked-2026-05-02-100k \
zsh sandbox/run_common_crawl_wet_matrix.sh

COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap safezone-improved-32k unsafezone-hp rift-hp rift-streaming rift-checked" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-like-checked-2026-05-02-1m-rss \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All checked q1/q2 rows matched heap checksum/output count. A separate 2k
q0/q3 smoke also matched heap checksum/output count, but those rows are not
headline evidence.

### 100k Checked Medians

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Region objects | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 542.053 | 152.272 | 165.341 | 0.000 | 0 | 408567808 |
| q1-tokenize | safezone-improved-32k | 464.362 | 0.000 | 0.000 | 0.000 | 0 | 356057088 |
| q1-tokenize | unsafezone-hp | 461.104 | 0.000 | 0.000 | 0.000 | 0 | 356089856 |
| q1-tokenize | rift-hp | 435.515 | 0.000 | 0.000 | 1.052 | 13700000 | 355975168 |
| q1-tokenize | rift-streaming | 439.115 | 0.000 | 0.000 | 1.154 | 13700000 | 355926016 |
| q1-tokenize | rift-checked | 502.220 | 0.000 | 0.000 | 1.080 | 13700000 | 355975168 |
| q2-domain-window | heap | 519.357 | 147.222 | 147.278 | 0.000 | 0 | 408567808 |
| q2-domain-window | safezone-improved-32k | 446.110 | 0.000 | 0.000 | 0.000 | 0 | 417546240 |
| q2-domain-window | unsafezone-hp | 446.831 | 0.000 | 0.000 | 0.000 | 0 | 417513472 |
| q2-domain-window | rift-hp | 425.856 | 0.000 | 0.000 | 1.051 | 13700000 | 420315136 |
| q2-domain-window | rift-streaming | 420.024 | 0.000 | 0.000 | 1.043 | 13700000 | 420265984 |
| q2-domain-window | rift-checked | 495.141 | 0.000 | 0.000 | 1.048 | 13700000 | 420364288 |

### 1M Checked Medians, RSS-Complete Rerun

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Region objects | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 5670.270 | 1745.758 | 1759.718 | 0.000 | 0 | 408567808 |
| q1-tokenize | safezone-improved-32k | 4644.747 | 31.787 | 37.550 | 0.000 | 0 | 474726400 |
| q1-tokenize | unsafezone-hp | 4704.843 | 26.283 | 27.625 | 0.000 | 0 | 474628096 |
| q1-tokenize | rift-hp | 4403.007 | 20.323 | 20.773 | 11.058 | 137000000 | 474546176 |
| q1-tokenize | rift-streaming | 4412.562 | 23.385 | 25.489 | 11.014 | 137000000 | 474480640 |
| q1-tokenize | rift-checked | 5088.712 | 20.942 | 27.348 | 11.291 | 137000000 | 474529792 |
| q2-domain-window | heap | 5342.373 | 1605.929 | 1626.253 | 0.000 | 0 | 408567808 |
| q2-domain-window | safezone-improved-32k | 4444.954 | 31.255 | 32.049 | 0.000 | 0 | 474742784 |
| q2-domain-window | unsafezone-hp | 4473.369 | 18.939 | 20.839 | 0.000 | 0 | 474628096 |
| q2-domain-window | rift-hp | 4258.549 | 21.095 | 22.736 | 10.900 | 137000000 | 474546176 |
| q2-domain-window | rift-streaming | 4268.362 | 20.499 | 21.368 | 10.807 | 137000000 | 474480640 |
| q2-domain-window | rift-checked | 5061.479 | 24.889 | 27.841 | 11.230 | 137000000 | 474529792 |

### Checked Follow-Up Interpretation

- Checked q1/q2 now validate the safe record-lifetime story in this
  object-heavy workload: `137000000` ordinary records are region allocated,
  checksums/output counts match, and heap GC drops from about `1.6-1.7 s` to
  about `20-25 ms`.
- The checked mode does beat heap elapsed in the 1M rerun: q1 by about `10%`
  and q2 by about `5%`.
- It does not clear the case-study gate because improved SafeZone-32k and
  trusted Rift are substantially faster. At 1M, checked q1 is `444 ms` slower
  than improved-32k and `686 ms` slower than trusted HPZone; checked q2 is
  `617 ms` slower than improved-32k and `803 ms` slower than trusted HPZone.
- Measured Rift runtime op time is only about `11 ms`, so the gap is not
  allocator open/close/reclaim. The likely cost is checked
  `StreamAppendWindow` append/cursor/container overhead in application-shaped
  hot loops.
- RSS is still not a region-family win at 1M: heap RSS is lower than the
  SafeZone/Rift rows. Use this follow-up as correctness and checked-overhead
  evidence, not a checked application performance claim.

## Checked Page-Token Owned Cursor Rerun

After `StreamAppendCursor.nextOwnedOrNull()` was added for operator-owned
page-token close callbacks, generated q1/q2 were rerun with the checked
page-token modes. The change removes per-record link clearing during close
when the parent bucket refs have already been cleared and the child region is
about to close.

100k command:

```sh
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-page-token-nextowned-100k-2026-05-07 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

1M command:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-page-token-nextowned-1m-2026-05-07 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All rows matched checksums/output counts.

| Scale | Query | Mode | Median ms | Median GC ms | RSS bytes | Output count |
|---|---|---|---:|---:|---:|---:|
| 100k | q1-tokenize | `heap-immix` | `545.252` | `156.148` | `408584192` | `13700000` |
| 100k | q1-tokenize | `rift-checked-page-token` | `387.976` | `0.000` | `345079808` | `13700000` |
| 100k | q1-tokenize | `rift-checked-safezone-page-token` | `363.216` | `0.000` | `345063424` | `13700000` |
| 100k | q2-domain-window | `heap-immix` | `511.596` | `148.646` | `408584192` | `92994` |
| 100k | q2-domain-window | `rift-checked-page-token` | `406.151` | `0.000` | `409387008` | `92994` |
| 100k | q2-domain-window | `rift-checked-safezone-page-token` | `378.822` | `0.000` | `409370624` | `92994` |
| 1M | q1-tokenize | `heap-immix` | `5392.344` | `1577.850` | `408584192` | `137000000` |
| 1M | q1-tokenize | `rift-checked-page-token` | `3877.427` | `20.026` | `463634432` | `137000000` |
| 1M | q1-tokenize | `rift-checked-safezone-page-token` | `3643.680` | `31.585` | `463716352` | `137000000` |
| 1M | q2-domain-window | `heap-immix` | `5201.862` | `1579.132` | `408584192` | `929230` |
| 1M | q2-domain-window | `rift-checked-page-token` | `4029.776` | `19.500` | `463634432` | `929230` |
| 1M | q2-domain-window | `rift-checked-safezone-page-token` | `3790.138` | `31.517` | `463667200` | `929230` |

Interpretation:

- This is a validated checked application win on the generated stressor.
  Checked scoped page-token improves q1 by about `32.4%` and q2 by about
  `27.1%` versus heap at 1M, while cutting timed GC from about `1.58 s` to
  about `32 ms`.
- The change is not a new algorithm: heap and checked rows still materialize
  and close the same page/line/token record shape. The runtime saving comes
  from removing defensive link cleanup that static epochal ownership makes
  unnecessary.
- RSS remains a caveat at 1M: checked rows are faster but use about `55 MB`
  more RSS than heap on this generated input.

## Next Similar Workloads

If WET-shaped q1/q2/q3 remain promising, add one more text/object-heavy stream:

- WAT-like metadata/link extraction;
- NDJSON/log-event parsing and tokenization;
- DSPBench or HiBench streaming text workloads with Kafka removed and
  preloaded/local controls.
