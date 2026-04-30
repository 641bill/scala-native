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
- `safezone`: closeable SafeZone per page bucket.
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

100k default bucket medians:

```bash
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-wet-q1-100k \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

100k small-bucket control:

```bash
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_PAGES_PER_BUCKET=250 \
COMMON_CRAWL_WET_LIVE_BUCKETS=1 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize" \
COMMON_CRAWL_WET_OUTPUT_DIR=/tmp/common-crawl-wet-q1-100k-small-buckets \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

## Results

### 5k Smoke

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0-parse | heap | 3.092 | 1.694 | 0.000 | 0 | 0 / 0 | 7929856 | 45000 |
| q0-parse | safezone | 1.669 | 0.000 | 0.000 | 0 | 0 / 0 | 7995392 | 45000 |
| q0-parse | rift-hp | 1.530 | 0.000 | 0.068 | 45000 | 2 / 2 | 7979008 | 45000 |
| q0-parse | rift-streaming | 1.681 | 0.000 | 0.083 | 45000 | 2 / 2 | 7979008 | 45000 |
| q1-tokenize | heap | 46.212 | 29.122 | 0.000 | 0 | 0 / 0 | 75022336 | 685000 |
| q1-tokenize | safezone | 39.876 | 0.000 | 0.000 | 0 | 0 / 0 | 69222400 | 685000 |
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
| q1-tokenize | heap | 452.840 | 160.268 | 0.000 | 0 | 0 / 0 | 408010752 | 13700000 |
| q1-tokenize | safezone | 1706.540 | 0.000 | 0.000 | 0 | 0 / 0 | 345309184 | 13700000 |
| q1-tokenize | rift-hp | 427.984 | 0.000 | 1.145 | 13700000 | 40 / 40 | 344670208 | 13700000 |
| q1-tokenize | rift-streaming | 428.040 | 0.000 | 1.125 | 13700000 | 40 / 40 | 344752128 | 13700000 |

Interpretation:

- This generated WET-shaped tokenization workload does stress heap allocation:
  heap reports `160.268 ms` of GC and much higher RSS than Rift.
- Trusted Rift removes measured GC and cuts RSS, but elapsed improves only
  about `5.5%` at this scale.
- SafeZone is not competitive here despite removing measured GC.
- This is useful evidence that parser/token streams can expose heap pressure,
  but it is not yet a strong case-study result.

### 100k Small-Bucket Control

Small-bucket settings:

- `COMMON_CRAWL_WET_PAGES_PER_BUCKET=250`
- `COMMON_CRAWL_WET_LIVE_BUCKETS=1`

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 384.951 | 94.533 | 0.000 | 0 | 0 / 0 | 21364736 | 13700000 |
| q1-tokenize | safezone | 411.583 | 1.771 | 0.000 | 0 | 0 / 0 | 22806528 | 13700000 |
| q1-tokenize | rift-hp | 425.050 | 2.346 | 0.969 | 13700000 | 400 / 400 | 22740992 | 13700000 |
| q1-tokenize | rift-streaming | 423.951 | 2.307 | 1.003 | 13700000 | 400 / 400 | 22740992 | 13700000 |

Interpretation:

- Making the structured lifetime more precise helps heap substantially too,
  because objects become unreachable sooner and RSS collapses.
- Rift no longer wins elapsed time under this bucket policy.
- This is an important guardrail: Common Crawl tokenization should not become a
  benchmark-specific story where Rift wins only because heap is forced to hold
  data for too long.

## Current Conclusion

Common Crawl WET remains promising as a *memory-pressure detector*, but the
generated workload does not yet give a strong checked-Rift case study:

- default buckets show lower GC/RSS and a modest trusted Rift elapsed win;
- smaller, more natural token lifetimes make heap competitive or faster;
- SafeZone is poor on the large default-bucket tokenization case;
- no checked Common Crawl mode should be added until a cheap checked page/token
  append operator can match the trusted shape.

Next benchmark candidate: Wikimedia-style pageview/clickstream aggregation,
with both generated and real TSV input controls, because it can test whether
Rift helps high-volume event objects with lower per-event token fanout.
