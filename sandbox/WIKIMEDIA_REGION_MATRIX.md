# Wikimedia Region Matrix

Date: 2026-05-01

Status: generated TSV-shaped Wikimedia pageview/clickstream probe plus first
real Wikimedia dump input wiring. The real-input path is currently preloaded
before timing to separate gzip/TSV parsing from memory-management behavior.

## Purpose

This matrix tests whether high-volume pageview/clickstream-shaped events expose
a better Rift win envelope than DEBS, NEXMark-lite Q5, or generated Common
Crawl tokenization.

The logical program is the same across heap, SafeZone, and Rift modes:

- generate deterministic TSV-shaped pageview/clickstream fields, or preload
  real Wikimedia pageviews/clickstream TSV rows from `WIKIMEDIA_INPUT`;
- allocate ordinary Scala event objects into event buckets;
- retain only a small bucket list as control metadata;
- bulk-consume and close buckets when the window expires;
- compare checksum and output counts across modes.

Rift changes allocation placement and lifetime policy only. It does not use a
specialized algorithm.

## Modes

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: trusted HPZone per event bucket.
- `rift-streaming`: trusted Streaming zone per event bucket.

No checked mode is included yet. The current checked append/fold APIs do not
justify adding checked Wikimedia claims before a focused checked operator gate
passes.

## Queries

- `q0-pageviews`: one pageview event object per generated TSV row.
- `q1-counts`: pageview plus per-project/article count-update object.
- `q2-clickstream`: pageview plus clickstream edge-update object.

## Commands

Compile:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```bash
WIKIMEDIA_EVENTS=20000 \
WIKIMEDIA_BENCHMARK_RUNS=1 \
WIKIMEDIA_WARMUPS=0 \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-region-smoke \
zsh sandbox/run_wikimedia_region_matrix.sh
```

Real clickstream smoke:

```bash
WIKIMEDIA_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-svwiki-2026-03.tsv.gz \
WIKIMEDIA_INPUT_KIND=clickstream \
WIKIMEDIA_EVENTS=20000 \
WIKIMEDIA_BENCHMARK_RUNS=1 \
WIKIMEDIA_WARMUPS=0 \
WIKIMEDIA_MODES="heap rift-hp" \
WIKIMEDIA_QUERIES="q2-clickstream" \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-real-smoke \
zsh sandbox/run_wikimedia_region_matrix.sh
```

Real pageviews input uses the same path with
`WIKIMEDIA_INPUT_KIND=pageviews`.

100k 3-run medians:

```bash
WIKIMEDIA_BUILD=0 \
WIKIMEDIA_EVENTS=100000 \
WIKIMEDIA_BENCHMARK_RUNS=3 \
WIKIMEDIA_WARMUPS=1 \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-region-100k \
zsh sandbox/run_wikimedia_region_matrix.sh
```

1M 3-run medians:

```bash
WIKIMEDIA_BUILD=0 \
WIKIMEDIA_EVENTS=1000000 \
WIKIMEDIA_BENCHMARK_RUNS=3 \
WIKIMEDIA_WARMUPS=1 \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-region-1m \
zsh sandbox/run_wikimedia_region_matrix.sh
```

10M Q2 single-run scale check:

```bash
WIKIMEDIA_BUILD=0 \
WIKIMEDIA_EVENTS=10000000 \
WIKIMEDIA_BENCHMARK_RUNS=1 \
WIKIMEDIA_WARMUPS=0 \
WIKIMEDIA_QUERIES="q2-clickstream" \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-region-10m-q2 \
zsh sandbox/run_wikimedia_region_matrix.sh
```

## Validation

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- 20k smoke matched checksum/output count across all queries and modes.
- 100k and 1M 3-run medians matched checksum/output count across all queries
  and modes.
- 10M Q2 single-run scale check matched checksum/output count across all modes.
- Real `svwiki` clickstream smoke matched checksum/output count for heap and
  HPZone with `input=real-clickstream-preloaded`. This validates input wiring
  only; it is not median performance evidence.

## 100k Results

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0-pageviews | heap | 6.187 | 1.393 | 0.000 | 0 | 0 / 0 | 7946240 | 100000 |
| q0-pageviews | safezone-current | 6.266 | 0.000 | 0.000 | 0 | 0 / 0 | 8634368 | 100000 |
| q0-pageviews | safezone-improved | 6.014 | 0.000 | 0.000 | 0 | 0 / 0 | 8650752 | 100000 |
| q0-pageviews | rift-hp | 5.943 | 0.000 | 0.015 | 100000 | 40 / 40 | 8617984 | 100000 |
| q0-pageviews | rift-streaming | 6.210 | 0.000 | 0.017 | 100000 | 40 / 40 | 8683520 | 100000 |
| q1-counts | heap | 13.909 | 2.781 | 0.000 | 0 | 0 / 0 | 12402688 | 200000 |
| q1-counts | safezone-current | 15.495 | 0.000 | 0.000 | 0 | 0 / 0 | 13746176 | 200000 |
| q1-counts | safezone-improved | 14.212 | 0.000 | 0.000 | 0 | 0 / 0 | 13762560 | 200000 |
| q1-counts | rift-hp | 14.460 | 0.000 | 0.031 | 200000 | 40 / 40 | 13746176 | 200000 |
| q1-counts | rift-streaming | 14.427 | 0.000 | 0.033 | 200000 | 40 / 40 | 13811712 | 200000 |
| q2-clickstream | heap | 14.462 | 2.877 | 0.000 | 0 | 0 / 0 | 12402688 | 200000 |
| q2-clickstream | safezone-current | 15.113 | 0.000 | 0.000 | 0 | 0 / 0 | 13762560 | 200000 |
| q2-clickstream | safezone-improved | 14.467 | 0.000 | 0.000 | 0 | 0 / 0 | 13762560 | 200000 |
| q2-clickstream | rift-hp | 14.646 | 0.000 | 0.028 | 200000 | 40 / 40 | 13729792 | 200000 |
| q2-clickstream | rift-streaming | 14.843 | 0.000 | 0.035 | 200000 | 40 / 40 | 13811712 | 200000 |

## 1M Results

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0-pageviews | heap | 57.760 | 12.242 | 0.000 | 0 | 0 / 0 | 7946240 | 1000000 |
| q0-pageviews | safezone-current | 60.639 | 0.000 | 0.000 | 0 | 0 / 0 | 8650752 | 1000000 |
| q0-pageviews | safezone-improved | 59.145 | 0.000 | 0.000 | 0 | 0 / 0 | 8650752 | 1000000 |
| q0-pageviews | rift-hp | 59.888 | 0.000 | 0.156 | 1000000 | 400 / 400 | 8617984 | 1000000 |
| q0-pageviews | rift-streaming | 59.182 | 0.000 | 0.174 | 1000000 | 400 / 400 | 8667136 | 1000000 |
| q1-counts | heap | 144.279 | 32.902 | 0.000 | 0 | 0 / 0 | 12402688 | 2000000 |
| q1-counts | safezone-current | 152.042 | 3.128 | 0.000 | 0 | 0 / 0 | 13746176 | 2000000 |
| q1-counts | safezone-improved | 144.272 | 3.205 | 0.000 | 0 | 0 / 0 | 13762560 | 2000000 |
| q1-counts | rift-hp | 146.305 | 2.303 | 0.279 | 2000000 | 400 / 400 | 13746176 | 2000000 |
| q1-counts | rift-streaming | 164.943 | 2.196 | 0.356 | 2000000 | 400 / 400 | 13795328 | 2000000 |
| q2-clickstream | heap | 159.746 | 35.238 | 0.000 | 0 | 0 / 0 | 12402688 | 2000000 |
| q2-clickstream | safezone-current | 153.989 | 3.081 | 0.000 | 0 | 0 / 0 | 13778944 | 2000000 |
| q2-clickstream | safezone-improved | 147.936 | 3.216 | 0.000 | 0 | 0 / 0 | 13778944 | 2000000 |
| q2-clickstream | rift-hp | 147.163 | 2.206 | 0.273 | 2000000 | 400 / 400 | 13746176 | 2000000 |
| q2-clickstream | rift-streaming | 148.364 | 2.211 | 0.315 | 2000000 | 400 / 400 | 13795328 | 2000000 |

## 10M Q2 Scale Check

Single-run only:

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q2-clickstream | heap | 1459.438 | 345.918 | 0.000 | 0 | 0 / 0 | 12402688 | 20000000 |
| q2-clickstream | safezone-current | 1527.829 | 29.021 | 0.000 | 0 | 0 / 0 | 13778944 | 20000000 |
| q2-clickstream | safezone-improved | 1473.088 | 29.698 | 0.000 | 0 | 0 / 0 | 13778944 | 20000000 |
| q2-clickstream | rift-hp | 1462.015 | 21.341 | 2.511 | 20000000 | 4000 / 4000 | 13746176 | 20000000 |
| q2-clickstream | rift-streaming | 1464.663 | 21.189 | 2.830 | 20000000 | 4000 / 4000 | 13729792 | 20000000 |

## Real Enwiki Clickstream Preloaded Results

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-enwiki-2026-03.tsv.gz`

Command:

```bash
WIKIMEDIA_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-enwiki-2026-03.tsv.gz \
WIKIMEDIA_INPUT_KIND=clickstream \
WIKIMEDIA_EVENTS=100000 \
WIKIMEDIA_BENCHMARK_RUNS=3 \
WIKIMEDIA_WARMUPS=1 \
WIKIMEDIA_QUERIES="q2-clickstream" \
WIKIMEDIA_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-real-enwiki-q2-100k \
zsh sandbox/run_wikimedia_region_matrix.sh

WIKIMEDIA_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-enwiki-2026-03.tsv.gz \
WIKIMEDIA_INPUT_KIND=clickstream \
WIKIMEDIA_EVENTS=1000000 \
WIKIMEDIA_BENCHMARK_RUNS=3 \
WIKIMEDIA_WARMUPS=1 \
WIKIMEDIA_QUERIES="q2-clickstream" \
WIKIMEDIA_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
WIKIMEDIA_OUTPUT_DIR=/tmp/wikimedia-real-enwiki-q2-1m \
WIKIMEDIA_BUILD=0 \
zsh sandbox/run_wikimedia_region_matrix.sh
```

These rows preload TSV records before timing, so they are memory-management
probes over real rows, not gzip/TSV parser benchmarks. Checksums and output
counts matched across all modes.

| Events | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 11.534 | 0.000 | 0.000 | 0 | 0 / 0 | 147554304 | 200000 |
| 100k | safezone-current | 15.267 | 0.000 | 0.000 | 0 | 0 / 0 | 148717568 | 200000 |
| 100k | safezone-improved | 14.835 | 0.000 | 0.000 | 0 | 0 / 0 | 148733952 | 200000 |
| 100k | rift-hp | 15.202 | 0.000 | 0.025 | 200000 | 40 / 40 | 148652032 | 200000 |
| 100k | rift-streaming | 15.293 | 0.000 | 0.027 | 200000 | 40 / 40 | 148717568 | 200000 |
| 1M | heap | 126.800 | 0.000 | 0.000 | 0 | 0 / 0 | 1153630208 | 2000000 |
| 1M | safezone-current | 155.202 | 0.000 | 0.000 | 0 | 0 / 0 | 892796928 | 2000000 |
| 1M | safezone-improved | 149.062 | 0.000 | 0.000 | 0 | 0 / 0 | 885784576 | 2000000 |
| 1M | rift-hp | 158.831 | 0.000 | 0.300 | 2000000 | 400 / 400 | 885227520 | 2000000 |
| 1M | rift-streaming | 157.449 | 0.000 | 0.352 | 2000000 | 400 / 400 | 892583936 | 2000000 |

Interpretation:

- Real enwiki clickstream Q2 does not reproduce the generated Q2 elapsed win.
  Heap is fastest at both 100k and 1M.
- Median measured GC is zero, but that does not mean no collection happened:
  the 1M heap row had one timed run with one collection and `67.236 ms` GC,
  while the other two timed runs had none. The reported median is therefore
  `0.000 ms`.
- The large RSS mostly reflects the preloaded real input, not query-local
  retained objects.
- Regions reduce 1M RSS versus heap, but by less than the elapsed slowdown
  would justify as a case-study claim.

## Interpretation

- Generated Wikimedia Q0 and Q1 are not strong Rift cases. Heap or improved
  SafeZone are at least as good in elapsed time.
- Q2 clickstream is the only promising shape at 1M: HPZone is faster than heap
  and narrowly faster than improved SafeZone, while cutting measured GC from
  `35.238 ms` to `2.206 ms`.
- The 10M single-run Q2 check weakens the story: HPZone remains much lower-GC,
  but elapsed is effectively a near-tie with heap and improved SafeZone.
- The real enwiki clickstream row weakens it further: with preloaded real TSV
  rows, heap is fastest and median timed GC is zero, although one heap 1M run
  did collect.
- This generated TSV-shaped matrix should stay as stream-benchmark ladder
  evidence, not a headline case study.
