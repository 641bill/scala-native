# Common Crawl-Like Object-Heavy Stream Matrix

Status: initial WET-shaped query expansion implemented; full results pending.

Date: 2026-05-01

## Purpose

Common Crawl WET-shaped tokenization is currently the clearest local
stream/object-pressure detector: heap spends material time in GC on the
generated 1M tokenization row, but improved SafeZone still beats trusted Rift.
This matrix expands that workload family before more DEBS-specific tuning.

## Implemented Queries

Current runner: `sandbox/run_common_crawl_wet_matrix.sh`.

| Query | Meaning | Region candidate |
|---|---|---|
| `q0-parse` | Allocate page and line records, no token records. | Page/line records with bucket lifetime. |
| `q1-tokenize` | Allocate page, line, and token records retained until bucket close. | Ordinary token records in bucket regions. |
| `q2-domain-window` | Allocate page/line/token records, then aggregate per domain at bucket close. | Bucket records plus close-time aggregate summaries. |
| `q3-parser-scratch` | Allocate page/line/token scratch records and consume them immediately. | Parser scratch objects with bucket/page-like lifetime. |

The default runner now includes all four queries. It still has only heap,
SafeZone-family, and trusted Rift modes. Checked modes remain out until the
corresponding checked append/scratch/window operator clears a focused gate.

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

## Next Similar Workloads

If WET-shaped q1/q2/q3 remain promising, add one more text/object-heavy stream:

- WAT-like metadata/link extraction;
- NDJSON/log-event parsing and tokenization;
- DSPBench or HiBench streaming text workloads with Kafka removed and
  preloaded/local controls.
