# Checked Page/Token Append Matrix

Date: 2026-05-03
Last updated: 2026-05-12 15:01 CEST

Status: focused checked page/token gate passed, generated Common Crawl-shaped
q1/q2 application gate passed, the fixed-chunk append control failed to beat
linked page-token, and the 2026-05-07 batch-close/current-bucket fast path is
now rerun from a clean checkpoint. The new no-drain close API is safe as an
operator-owned option, but the cost matrix shows it is not the main remaining
bottleneck. This is generated stressor evidence, not real Common Crawl input
evidence.

## What Changed

`RiftRegion.StreamPageTokenAppendWindow[T]` is an experimental checked
operator for parser/token stream workloads. The operator owns current-bucket
lookup, child-region access, append, and cursor close. User code still
allocates ordinary Scala records in the returned child region, but does not
receive reusable `StreamBucket` tokens for hot-path append.

The owned append path removes these overheads from the focused hot path:

- per-record `bucket.child.checkOpen()`;
- per-record `streamBucketRegion(...)` lookup;
- stale-current `isOpen` check in the operator-owned bucket fast path.
- generic close-time leftover draining when the page-token operator owns the
  bucket and the child region closes immediately after the callback;
- repeated close/open work for monotonic timestamp streams when the current
  bucket cannot expire.

Public low-level append-window APIs remain defensive.

## Validation

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results:

- `sandbox3_next` compile passed.
- checked compiler tests passed: `100/100` after adding chunk-token probes.
- checked runtime tests passed: `51/51` after adding page-token no-drain close
  probes.

New probes:

- positive: page/token records allocated in child bucket regions and drained;
- negative: stale bucket region access after close-before;
- negative: stale bucket region access after close-all;
- negative: direct heap record passed to `appendPageToken`;
- positive: fixed-chunk records allocated in child bucket regions and drained;
- negative: direct heap record passed to `appendChunkToken`;
- existing region-to-heap negative probes continue to reject unrooted heap
  references.

## Focused 1M Gate

Command:

```sh
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap-immix rift-checked-rift rift-checked-page-token rift-checked-safezone-improved-32k rift-checked-safezone-page-token safezone-rootless-32k rift-trusted-hp rift-trusted-streaming" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-1m-2026-05-03 \
zsh sandbox/run_checked_append_window_matrix.sh
```

All rows matched checksum.

| Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | 35.652 | 10.548 | 0.000 | 0 | 75087872 |
| `rift-checked-rift` | 30.819 | 0.000 | 0.066 | 1000000 | 47579136 |
| `rift-checked-page-token` | 27.141 | 0.000 | 0.064 | 1000000 | 47579136 |
| `rift-checked-safezone-improved-32k` | 29.276 | 0.000 | 0.000 | 0 | 47448064 |
| `rift-checked-safezone-page-token` | 26.191 | 0.000 | 0.000 | 0 | 47480832 |
| `safezone-rootless-32k` | 37.252 | 0.000 | 0.000 | 0 | 47448064 |
| `rift-trusted-hp` | 37.585 | 0.000 | 0.059 | 1000000 | 47431680 |
| `rift-trusted-streaming` | 37.871 | 0.000 | 0.063 | 1000000 | 47562752 |

Focused gate result: passed.

- `rift-checked-page-token` improves current checked cursor by about `11.9%`.
- `rift-checked-safezone-page-token` improves checked SafeZone-backed cursor by
  about `10.5%`.
- RSS stays in the same range as existing checked region rows.

## Fixed-Chunk Append Follow-Up

This follow-up tested whether page/batch/window workloads should replace the
linked page-token structure with fixed region-owned object-array chunks. It
adds a fair `heap-immix-chunk` control so this is not a Rift-only data-structure
rewrite.

Command:

```sh
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap-immix heap-immix-chunk rift-checked-page-token rift-checked-chunk-token rift-checked-safezone-page-token rift-checked-safezone-chunk-token" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-append-chunk-1m-2026-05-05 \
zsh sandbox/run_checked_append_window_matrix.sh
```

All rows matched checksum.

| Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | 37.748 | 11.359 | 0.000 | 0 | 75071488 |
| `heap-immix-chunk` | 45.363 | 11.952 | 0.000 | 0 | 75104256 |
| `rift-checked-page-token` | 28.452 | 0.000 | 0.088 | 1000000 | 47579136 |
| `rift-checked-chunk-token` | 34.273 | 0.000 | 0.083 | 1031280 | 47579136 |
| `rift-checked-safezone-page-token` | 27.214 | 0.000 | 0.000 | 0 | 47464448 |
| `rift-checked-safezone-chunk-token` | 33.108 | 0.000 | 0.000 | 0 | 47579136 |

Fixed-chunk gate result: failed.

- The chunk path is correct and lower-RSS than heap, but it is slower than the
  existing linked page-token path in both Rift and SafeZone-backed checked
  modes.
- The heap chunk control is also slower than the heap linked-list control,
  which means the array/chunk traversal shape itself is not a free win here.
- The extra chunk objects/arrays add about `31280` region allocations at 1M
  with the default chunk size of `64`, and that overhead outweighs the saved
  per-record `appendNext` field updates in this append workload.
- Do not move chunk-token into Common Crawl, GH Archive, or DEBS yet. Keep it
  as a control and revisit only if a workload needs variable-size page-local
  random access rather than sequential append/drain.

## 2026-05-07 Batch-Close Fast-Path Follow-Up

This follow-up optimized the existing linked page-token path rather than adding
a benchmark-specific structure:

- `StreamAppendCursor.nextOrNull()` gives close callbacks a single-call cursor
  drain instead of `hasNext` plus `next`.
- Page-token-owned close clears parent bucket `head`/`tail`/`length` first and
  no longer performs a second defensive drain just to null unconsumed links.
  Generic public append-window close still keeps the defensive drain.
- `pageTokenAppendRegionFor` now has a monotonic current-bucket fast path: when
  no bucket can expire and the timestamp stays in the current bucket, it returns
  the cached child region without re-entering general close/open work.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- `RiftRegionCheckedCompilerTest` passed `118/118`.
- `RiftRegionCheckedTest` passed `50/50`.
- 20k smoke matched checksums across heap, linked page-token, SafeZone-backed
  linked page-token, and chunk controls.

Focused 1M command:

```sh
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=5 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap-immix rift-checked-page-token rift-checked-safezone-page-token rift-checked-chunk-token rift-checked-safezone-chunk-token" \
CHECKED_APPEND_OUTPUT_DIR="/tmp/checked-append-page-token-fast-1m" \
zsh sandbox/run_checked_append_window_matrix.sh
```

All rows matched checksum `-2507118467295660905`.

| Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `36.920` | `10.995` | `0.000` | `0` | `75120640` |
| `rift-checked-page-token` | `29.319` | `0.000` | `0.073` | `1000000` | `47579136` |
| `rift-checked-safezone-page-token` | `27.549` | `0.000` | `0.000` | `0` | `47431680` |
| `rift-checked-chunk-token` | `34.737` | `0.000` | `0.080` | `1031280` | `47579136` |
| `rift-checked-safezone-chunk-token` | `32.294` | `0.000` | `0.000` | `0` | `47579136` |

Result: the linked page-token path remains the right checked operator for
append/window workloads. The chunk path is still correct but slower, so it
should not be wired into DSPBench/Common Crawl yet.

Clean committed fast-path rerun:

```sh
CHECKED_APPEND_BUILD=1 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=5 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap-immix rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_APPEND_OUTPUT_DIR="/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-focused-post-nodrain" \
zsh sandbox/run_checked_append_window_matrix.sh
```

All rows matched checksum.

| Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `36.722` | `10.871` | `0.000` | `0` | `75120640` |
| `rift-checked-page-token` | `28.397` | `0.000` | `0.066` | `1000000` | `47579136` |
| `rift-checked-safezone-page-token` | `27.240` | `0.000` | `0.000` | `0` | `47448064` |

This is the current focused safe-fast-path page-token gate. It confirms the
headline page-token path did not regress.

Open-allocation wrapper cleanup, 2026-05-12:

The final `OpenStreamingRegion` implementations now call the Rift/SafeZone
backend allocators directly in `allocUncheckedImpl` instead of routing through
the superclass wrapper. Public defensive `allocImpl` is unchanged; this only
shortens the operator-owned `allocOpen` path.

Validation passed:

- `sandbox3_next/compile`
- `RiftRegionCheckedCompilerTest`: `141/141`
- `RiftRegionCheckedTest`: `64/64`

Focused 1M page-token gate, final-clean, 5 measured runs:

| Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `rift-checked-page-token` | `24.665` | `0.000` | `0.069` | `0` | `47579136` |
| `rift-checked-safezone-page-token` | `24.816` | `0.000` | `0.000` | `0` | `47480832` |

Single-run application sanity gates:

| Benchmark row | Previous object-fast gate | Open-wrapper gate | Result |
|---|---:|---:|---|
| StreamFlex-design throughput `checked-epoch-stream`, 20M events | `8.07 s`, user `7.67 s`, RSS `10289152` | `7.72 s`, user `7.43 s`, RSS `10289152` | Positive single-run confirmation on an allocation-heavy checked stream row. |
| Common Crawl-shaped q2 `rift-checked-page-token`, 1M pages | `3.98 s`, user `3.68 s`, RSS `63193088` | `4.00 s`, user `3.69 s`, RSS `63193088` | Essentially flat; parser/query/traversal work dominates this row. |

Interpretation: the focused page-token path remains strong in final-clean mode,
and the wrapper cleanup is safe, but application impact is workload-sensitive.
Treat it as backend-lowering cleanup rather than a broad application claim.

Focused cost-decomposition follow-up:

`evidence/CHECKED_PAGE_TOKEN_COST_MATRIX.md` adds `append-only`,
`append-drain`, and `append-aggregate` same-shape workloads. At 1M, the
no-drain close API did not materially improve the cost rows:
checked scoped page-token was `74.743 ms` on append-only, `81.628 ms` on
append-drain, and `82.623 ms` on append-aggregate after the safe same-bucket
fast-path rerun. Conclusion: no-drain close is safe and useful as an
operator-owned option, but close traversal is not the main remaining
bottleneck in this focused matrix.

Generated Common Crawl-shaped 100k regression:

```sh
COMMON_CRAWL_WET_BUILD=1 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR="/tmp/common-crawl-page-token-fast-100k" \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All q1/q2 rows matched checksum/output count.

| Query | Mode | Median ms | GC ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|
| q1 | `heap-immix` | `533.406` | `147.793` | `408600576` | `13700000` |
| q1 | `safezone-improved-32k` | `448.479` | `0.000` | `356089856` | `13700000` |
| q1 | `rift-trusted-streaming` | `433.955` | `0.000` | `355975168` | `13700000` |
| q1 | `rift-checked-page-token` | `395.255` | `0.000` | `345096192` | `13700000` |
| q1 | `rift-checked-safezone-page-token` | `370.758` | `0.000` | `345112576` | `13700000` |
| q2 | `heap-immix` | `513.498` | `145.459` | `408600576` | `92994` |
| q2 | `safezone-improved-32k` | `434.138` | `0.000` | `420511744` | `92994` |
| q2 | `rift-trusted-streaming` | `423.630` | `0.000` | `420380672` | `92994` |
| q2 | `rift-checked-page-token` | `404.521` | `0.000` | `409550848` | `92994` |
| q2 | `rift-checked-safezone-page-token` | `377.482` | `0.000` | `409518080` | `92994` |

Result: the fast path strengthens the generated application-shaped gate.
SafeZone-backed checked page-token is now the fastest row on both q1 and q2 in
this 100k regression, beating heap, improved SafeZone, trusted Streaming, and
checked Rift page-token. It remains generated WET-shaped stressor evidence, not
real Common Crawl proof.

Post-fast-path selected 1M regression:

```sh
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR="cache/perf-eval/2026-05-07-post-fast-path-selected/summaries/common-crawl-page-token" \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All q1/q2 rows matched checksum/output count.

| Query | Mode | Median ms | GC ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|
| q1 | `heap-immix` | `5618.631` | `1655.357` | `408600576` | `137000000` |
| q1 | `safezone-improved-32k` | `4777.409` | `33.822` | `474693632` | `137000000` |
| q1 | `rift-trusted-streaming` | `4492.936` | `20.357` | `474497024` | `137000000` |
| q1 | `rift-checked-page-token` | `4069.265` | `20.486` | `463634432` | `137000000` |
| q1 | `rift-checked-safezone-page-token` | `3840.668` | `30.767` | `463650816` | `137000000` |
| q2 | `heap-immix` | `5303.179` | `1599.698` | `408600576` | `929230` |
| q2 | `safezone-improved-32k` | `4436.680` | `32.029` | `474759168` | `929230` |
| q2 | `rift-trusted-streaming` | `4205.312` | `18.431` | `474071040` | `929230` |
| q2 | `rift-checked-page-token` | `4041.548` | `17.698` | `463650816` | `929230` |
| q2 | `rift-checked-safezone-page-token` | `3839.158` | `36.024` | `463765504` | `929230` |

Result: the 1M selected rerun confirms the 100k direction. Checked scoped
page-token is fastest on both q1 and q2; checked Rift page-token also beats
trusted Streaming. Heap still has lower RSS, so this remains an elapsed/GC
win rather than an RSS win.

## Common Crawl-Shaped Application Gate

Commands:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix safezone-improved-32k safezone-rootless-32k rift-trusted-hp rift-trusted-streaming rift-checked-rift rift-checked-page-token rift-checked-safezone-improved-32k rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-page-token-1m-2026-05-03 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All q1/q2 rows matched checksum/output count.

| Query | Mode | Median ms | GC ms | Rift op ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|
| q1 | `heap-immix` | 5412.618 | 1570.724 | 0.000 | 408649728 | 137000000 |
| q1 | `safezone-improved-32k` | 4637.981 | 33.761 | 0.000 | 474808320 | 137000000 |
| q1 | `rift-trusted-hp` | 4367.265 | 19.935 | 10.522 | 474595328 | 137000000 |
| q1 | `rift-checked-rift` | 4855.133 | 20.291 | 10.781 | 474546176 | 137000000 |
| q1 | `rift-checked-page-token` | 3956.366 | 20.114 | 8.165 | 463667200 | 137000000 |
| q1 | `rift-checked-safezone-improved-32k` | 4640.565 | 30.902 | 0.000 | 474775552 | 137000000 |
| q1 | `rift-checked-safezone-page-token` | 3728.286 | 30.616 | 0.000 | 463765504 | 137000000 |
| q2 | `heap-immix` | 5252.803 | 1572.021 | 0.000 | 408649728 | 929230 |
| q2 | `safezone-improved-32k` | 4580.687 | 31.413 | 0.000 | 474775552 | 929230 |
| q2 | `rift-trusted-streaming` | 4212.494 | 19.053 | 10.300 | 474529792 | 929230 |
| q2 | `rift-checked-rift` | 4820.611 | 19.713 | 10.652 | 474562560 | 929230 |
| q2 | `rift-checked-page-token` | 4039.855 | 18.813 | 8.240 | 463683584 | 929230 |
| q2 | `rift-checked-safezone-improved-32k` | 4636.413 | 34.822 | 0.000 | 474808320 | 929230 |
| q2 | `rift-checked-safezone-page-token` | 3816.247 | 35.053 | 0.000 | 463749120 | 929230 |

Application gate result: passed for generated WET-shaped q1/q2.

- q1 `rift-checked-page-token` improves current checked by about `18.5%` and
  beats `rift-trusted-hp` by about `9.4%`.
- q2 `rift-checked-page-token` improves current checked by about `16.2%` and
  beats `rift-trusted-streaming` by about `4.1%`.
- q1 `rift-checked-safezone-page-token` improves checked SafeZone-backed cursor
  by about `19.7%`.
- q2 `rift-checked-safezone-page-token` improves checked SafeZone-backed cursor
  by about `17.7%`.
- Both page-token rows reduce RSS by about `10-11 MiB` relative to current
  checked rows.

## Interpretation

This is the first checked operator/application-shaped result where removing
runtime overhead justified by the static lifetime discipline produces a large
win. The result supports the design direction: cheap checked operators should
own lifetime boundaries so they can avoid redundant per-record checks and
lookup work.

Caveat: the q1/q2 rows are generated WET-shaped stressors. They prove the
memory-management regime and operator direction, not real Common Crawl
end-to-end performance. The next benchmark step is larger real WET/WAT or a
real NDJSON/log stream with material heap GC.

## Real WET Control

Command:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet \
COMMON_CRAWL_WET_PAGES=50000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap-immix safezone-improved-32k rift-trusted-hp rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-real-page-token-2026-05-03 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

Actual loaded output scale: q1 produced `752797` token records, and q2 produced
`18560` domain-window outputs. All rows matched checksum/output count.

| Query | Mode | Median ms | GC ms | Runs with GC | Output count |
|---|---|---:|---:|---:|---:|
| q1 | `heap-immix` | 32.215 | 0.000 | 0 | 752797 |
| q1 | `safezone-improved-32k` | 35.264 | 0.000 | 0 | 752797 |
| q1 | `rift-trusted-hp` | 34.783 | 0.000 | 0 | 752797 |
| q1 | `rift-trusted-streaming` | 34.397 | 0.000 | 0 | 752797 |
| q1 | `rift-checked-page-token` | 32.016 | 0.000 | 0 | 752797 |
| q1 | `rift-checked-safezone-page-token` | 30.474 | 0.000 | 0 | 752797 |
| q2 | `heap-immix` | 31.227 | 0.000 | 0 | 18560 |
| q2 | `safezone-improved-32k` | 35.888 | 0.000 | 0 | 18560 |
| q2 | `rift-trusted-hp` | 34.675 | 0.000 | 0 | 18560 |
| q2 | `rift-trusted-streaming` | 34.295 | 0.000 | 0 | 18560 |
| q2 | `rift-checked-page-token` | 34.499 | 0.000 | 0 | 18560 |
| q2 | `rift-checked-safezone-page-token` | 30.783 | 0.000 | 0 | 18560 |

Interpretation: the SafeZone-backed page-token row is fastest on this real WET
shard, but heap has median/max timed GC of zero. This is a useful real-input
wiring and ceiling control, not a GC-heavy case-study row. A real Common Crawl
claim still needs larger/multiple WET shards or WAT metadata/link extraction
with material heap GC or allocation pressure.

## Real WAT Link-Metadata Control

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

All rows matched checksum/output count. q4 materializes page and link records;
q5 uses the same records to produce per-domain window summaries.

| Query | Mode | Median ms | GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|
| q4 | `heap-immix` | 33.646 | 0.000 | 0 | 968704000 | 1006742 |
| q4 | `safezone-improved-32k` | 39.551 | 0.000 | 0 | 1061011456 | 1006742 |
| q4 | `rift-trusted-hp` | 38.766 | 0.000 | 0 | 1166393344 | 1006742 |
| q4 | `rift-trusted-streaming` | 38.541 | 0.000 | 0 | 1143963648 | 1006742 |
| q4 | `rift-checked-page-token` | 35.085 | 0.000 | 0 | 1142358016 | 1006742 |
| q4 | `rift-checked-safezone-page-token` | 31.792 | 0.000 | 0 | 1171996672 | 1006742 |
| q5 | `heap-immix` | 35.066 | 0.000 | 0 | 957562880 | 293020 |
| q5 | `safezone-improved-32k` | 39.579 | 0.000 | 0 | 1156055040 | 293020 |
| q5 | `rift-trusted-hp` | 38.682 | 0.000 | 0 | 1143980032 | 293020 |
| q5 | `rift-trusted-streaming` | 38.656 | 0.000 | 0 | 1030471680 | 293020 |
| q5 | `rift-checked-page-token` | 36.546 | 0.000 | 0 | 962789376 | 293020 |
| q5 | `rift-checked-safezone-page-token` | 33.937 | 0.000 | 0 | 1144209408 | 293020 |

100k requested-page q4 one-run scale probe:

| Mode | Median ms | GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | 43.274 | 0.000 | 0 | 1301037056 | 1339183 |
| `rift-checked-safezone-page-token` | 42.226 | 0.000 | 0 | 1381793792 | 1339183 |

Interpretation: the WAT path validates real-input page/link object placement
and shows a modest SafeZone-backed page-token win. It still does not provide a
GC-heavy real-data case study because heap timed GC is zero even on the 100k
probe.
