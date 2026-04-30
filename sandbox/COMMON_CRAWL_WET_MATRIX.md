# Common Crawl WET Matrix

Date: 2026-04-30

Status: first generated WET-shaped detector. This is not a real Common Crawl
artifact run yet. It exists to answer whether an object-heavy page/token stream
creates enough Scala Native Immix pressure to justify moving on to a real WET
file.

## Workload

Implementation:

- `sandbox/src/main/scala-next/CommonCrawlWetMatrix.scala`
- `sandbox/run_common_crawl_wet_matrix.sh`

Input:

- `input=generated-wet-shaped`
- deterministic pages, domains, lines, and token hashes
- no decompression or file I/O

Queries:

- `q0-parse`: allocate page/header and body-line records, then consume them at
  bucket close.
- `q1-tokenize`: allocate page/header, body-line, and token records, then
  consume them at bucket close.

Modes:

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone per page bucket with
  `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: trusted HPZone per page bucket.
- `rift-streaming`: trusted Streaming zone per page bucket.

Checked mode is intentionally not present yet. The focused checked fold gate
failed, and `StreamAppendWindow` is only proven for append/cursor shapes. This
detector first asks whether the underlying workload has enough allocation/GC
pressure to justify a checked Common Crawl implementation.

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
