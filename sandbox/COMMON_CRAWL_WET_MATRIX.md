# Common Crawl WET Matrix

Date: 2026-05-01

Status: generated WET-shaped detector plus first real Common Crawl WET input
wiring. Real WET input is currently preloaded before timing so parser and
decompression cost do not hide memory-management behavior. The latest code
adds two follow-up WET-shaped queries for domain-window aggregation and parser
scratch allocation; those rows are not headline evidence until rerun.

## Workload

Implementation:

- `sandbox/src/main/scala-next/CommonCrawlWetMatrix.scala`
- `sandbox/run_common_crawl_wet_matrix.sh`

Input:

- `input=generated-wet-shaped`
- deterministic pages, domains, lines, and token hashes
- or `COMMON_CRAWL_WET_INPUT=/path/to/file.warc.wet` for real WET records
- Common Crawl WET shards are concatenated gzip streams; use the decompressed
  `.warc.wet` sample prepared by `scripts/fetch-benchmark-data.sh` for Scala
  Native runs.

Queries:

- `q0-parse`: allocate page/header and body-line records, then consume them at
  bucket close.
- `q1-tokenize`: allocate page/header, body-line, and token records, then
  consume them at bucket close.
- `q2-domain-window`: allocate page/header, body-line, and token records, then
  aggregate per-domain bucket summaries at close.
- `q3-parser-scratch`: allocate page/header, body-line, and token scratch
  records and consume them immediately; bucket regions still provide the bulk
  lifetime boundary.

Modes:

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=1`.
- `safezone-improved-32k`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=1` and `SAFEZONE_PAGE_SIZE=32768`.
- `safezone-chunk`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=2`.
- `unsafezone-hp`: benchmark-only rootless SafeZone with
  `SAFEZONE_ROOTS_MODE=3` and `SAFEZONE_PAGE_SIZE=32768`.
- `rift-hp`: trusted HPZone per page bucket.
- `rift-streaming`: trusted Streaming zone per page bucket.

Checked mode is intentionally not present yet. The focused checked fold gate
failed, and `StreamAppendWindow` is only proven for append/cursor shapes. This
detector first asks whether the underlying workload has enough allocation/GC
pressure to justify a checked Common Crawl implementation.

The q2/q3 expansion and the 100k/1M SafeZone-family follow-up are summarized in
`COMMON_CRAWL_LIKE_MATRIX.md`.

## Commands

Compile:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

5k smoke:

```bash
COMMON_CRAWL_WET_PAGES=5000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-wet-smoke \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

Real WET smoke:

```bash
COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet \
COMMON_CRAWL_WET_PAGES=100 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_MODES="heap rift-hp" \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-real-smoke \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

100k default bucket medians with current/improved SafeZone:

```bash
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-wet-q1-100k-roots \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

100k small-bucket control with current/improved SafeZone:

```bash
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_PAGES_PER_BUCKET=250 \
COMMON_CRAWL_WET_LIVE_BUCKETS=1 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-wet-q1-100k-small-buckets-roots \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

## Results

### Real WET Smoke

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet`

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 0.121 | 0.000 | 0.000 | 0 | 0 / 0 | 12648448 | 3402 |
| q1-tokenize | rift-hp | 0.172 | 0.000 | 0.011 | 3402 | 1 / 1 | 12746752 | 3402 |

This is a plumbing smoke only. The row count is too small for performance
claims.

### Real WET Preloaded Tokenization

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet`

Command:

```bash
COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet \
COMMON_CRAWL_WET_PAGES=10000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-real-q1-10k \
zsh sandbox/run_common_crawl_wet_matrix.sh

COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet \
COMMON_CRAWL_WET_PAGES=50000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-real-q1-50k \
COMMON_CRAWL_WET_BUILD=0 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

These rows preload WET-derived page/line/token hashes before timing. They are
memory-management probes, not WET parser or decompression benchmarks. The 50k
request only found `21425` usable conversion records in this shard, so that row
is a larger small-input control rather than headline 50k evidence. Checksums
and output counts matched across all modes.

The post-refocus control pass also ran `q0-parse` with the same real WET shard
after the runner gained max-GC/outlier reporting:

```bash
COMMON_CRAWL_WET_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wet \
COMMON_CRAWL_WET_PAGES=10000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES=q0-parse \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-real-q0-10k \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

| Query | Requested pages | Actual pages | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-parse | 10000 | 10000 | heap | 2.372 | 0.000 | 0.000 | 0 | 0.000 | 0 | 0 / 0 | 415498240 | 88024 |
| q0-parse | 10000 | 10000 | safezone-current | 2.968 | 0.000 | 0.000 | 0 | 0.000 | 0 | 0 / 0 | 412893184 | 88024 |
| q0-parse | 10000 | 10000 | safezone-improved | 2.669 | 0.000 | 0.000 | 0 | 0.000 | 0 | 0 / 0 | 412860416 | 88024 |
| q0-parse | 10000 | 10000 | rift-hp | 2.862 | 0.000 | 0.000 | 0 | 0.006 | 88024 | 4 / 4 | 419020800 | 88024 |
| q0-parse | 10000 | 10000 | rift-streaming | 2.912 | 0.000 | 0.000 | 0 | 0.007 | 88024 | 4 / 4 | 419069952 | 88024 |

Interpretation for `q0-parse`: this real preloaded parse row is heap-fastest
and reports zero median and max timed GC. It does not expose memory-management
headroom for Rift.

| Requested pages | Actual pages | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 10000 | 10000 | heap | 12.079 | 0.000 | 0.000 | 0 | 0 / 0 | 415465472 | 349709 |
| 10000 | 10000 | safezone-current | 19.825 | 0.000 | 0.000 | 0 | 0 / 0 | 429719552 | 349709 |
| 10000 | 10000 | safezone-improved | 16.093 | 0.000 | 0.000 | 0 | 0 / 0 | 429752320 | 349709 |
| 10000 | 10000 | rift-hp | 16.155 | 0.000 | 0.027 | 349709 | 4 / 4 | 429572096 | 349709 |
| 10000 | 10000 | rift-streaming | 15.651 | 0.000 | 0.029 | 349709 | 4 / 4 | 429588480 | 349709 |
| 50000 | 21425 | heap | 26.452 | 0.000 | 0.000 | 0 | 0 / 0 | 814252032 | 752797 |
| 50000 | 21425 | safezone-current | 46.710 | 0.000 | 0.000 | 0 | 0 / 0 | 842645504 | 752797 |
| 50000 | 21425 | safezone-improved | 30.730 | 0.000 | 0.000 | 0 | 0 / 0 | 842612736 | 752797 |
| 50000 | 21425 | rift-hp | 32.809 | 0.000 | 0.050 | 752797 | 9 / 9 | 842465280 | 752797 |
| 50000 | 21425 | rift-streaming | 33.103 | 0.000 | 0.049 | 752797 | 9 / 9 | 841154560 | 752797 |

Interpretation:

- Real WET tokenization does not reproduce the generated WET allocation win.
  Heap is fastest at both requested scales.
- Median measured GC is zero in the timed section. The dominant cost here is
  not collection time after preloading; it is token/hash loop CPU plus the live
  preloaded input footprint.
- Current/improved SafeZone and Rift all add region object/linking overhead in
  this real shard. Do not continue tuning Common Crawl from this row without a
  new input shard or a focused cheap checked page/token operator.

### 5k Smoke

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0-parse | heap | 3.092 | 1.694 | 0.000 | 0 | 0 / 0 | 7929856 | 45000 |
| q0-parse | safezone-current | 1.669 | 0.000 | 0.000 | 0 | 0 / 0 | 7995392 | 45000 |
| q0-parse | rift-hp | 1.530 | 0.000 | 0.068 | 45000 | 2 / 2 | 7979008 | 45000 |
| q0-parse | rift-streaming | 1.681 | 0.000 | 0.083 | 45000 | 2 / 2 | 7979008 | 45000 |
| q1-tokenize | heap | 46.212 | 29.122 | 0.000 | 0 | 0 / 0 | 75022336 | 685000 |
| q1-tokenize | safezone-current | 39.876 | 0.000 | 0.000 | 0 | 0 / 0 | 69222400 | 685000 |
| q1-tokenize | rift-hp | 23.652 | 0.000 | 1.090 | 685000 | 2 / 2 | 68878336 | 685000 |
| q1-tokenize | rift-streaming | 23.656 | 0.000 | 1.077 | 685000 | 2 / 2 | 68878336 | 685000 |

This smoke is a direction check only. It shows the intended allocation-pressure
shape but should not be used as a headline because it is one timed run.

### 100k Default Bucket

Default bucket settings:

- `COMMON_CRAWL_WET_PAGES_PER_BUCKET=2500`
- `COMMON_CRAWL_WET_LIVE_BUCKETS=4`
- `COMMON_CRAWL_WET_LINES_PER_PAGE=8`
- `COMMON_CRAWL_WET_TOKENS_PER_LINE=16`

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 427.942 | 149.149 | 0.000 | 0 | 0 / 0 | 408305664 | 13700000 |
| q1-tokenize | safezone-current | 1675.962 | 0.000 | 0.000 | 0 | 0 / 0 | 345276416 | 13700000 |
| q1-tokenize | safezone-improved | 381.006 | 0.000 | 0.000 | 0 | 0 / 0 | 345292800 | 13700000 |
| q1-tokenize | rift-hp | 404.123 | 0.000 | 0.949 | 13700000 | 40 / 40 | 344670208 | 13700000 |
| q1-tokenize | rift-streaming | 403.935 | 0.000 | 0.926 | 13700000 | 40 / 40 | 344735744 | 13700000 |

Interpretation:

- This generated WET-shaped tokenization workload does stress heap allocation:
  heap reports `149.149 ms` of GC and much higher RSS than the region modes.
- Current SafeZone is pathological at this scale, but improved SafeZone is the
  fastest row.
- Trusted Rift removes measured GC and cuts RSS, but it does not beat improved
  SafeZone.
- This is useful as a memory-pressure detector, not a Rift case-study win.

### 100k Small-Bucket Control

Small-bucket settings:

- `COMMON_CRAWL_WET_PAGES_PER_BUCKET=250`
- `COMMON_CRAWL_WET_LIVE_BUCKETS=1`

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 386.807 | 106.615 | 0.000 | 0 | 0 / 0 | 21348352 | 13700000 |
| q1-tokenize | safezone-current | 400.242 | 2.527 | 0.000 | 0 | 0 / 0 | 22790144 | 13700000 |
| q1-tokenize | safezone-improved | 381.109 | 2.606 | 0.000 | 0 | 0 / 0 | 22790144 | 13700000 |
| q1-tokenize | rift-hp | 406.536 | 2.219 | 0.835 | 13700000 | 400 / 400 | 22724608 | 13700000 |
| q1-tokenize | rift-streaming | 419.779 | 2.240 | 0.916 | 13700000 | 400 / 400 | 22724608 | 13700000 |

Interpretation:

- Making the structured lifetime more precise helps heap substantially too,
  because objects become unreachable sooner and RSS collapses.
- Improved SafeZone is fastest by a small margin; Rift loses elapsed time under
  this bucket policy.
- This is an important guardrail: Common Crawl tokenization should not become a
  benchmark-specific story where Rift wins only because heap is forced to hold
  data for too long.

## Current Conclusion

Common Crawl WET remains useful as a *memory-pressure detector*, but the
generated workload does not give a strong Rift case study:

- default buckets show lower GC/RSS for region modes, but improved SafeZone is
  faster than trusted Rift;
- smaller, more natural token lifetimes make heap and improved SafeZone
  competitive or faster;
- current SafeZone is poor on the large default-bucket tokenization case, but
  improved SafeZone is not;
- no checked Common Crawl mode should be added until a cheap checked page/token
  append operator can match the trusted shape.

Next benchmark candidate: Wikimedia-style pageview/clickstream aggregation,
with both generated and real TSV input controls, because it can test whether
Rift helps high-volume event objects with lower per-event token fanout.
