# Checked Window Fold Matrix

Last updated: 2026-06-01 15:03 CEST

Status: focused checked-operator profile pack for the proposed additive
`RiftRegion.StreamWindowFold` API. This is framework evidence only. The latest
validated 1M focused rerun now passes narrowly after the redundant
`putFoldInBucket` open-check removal, but application integrations still need
their own smokes/L1/L2 gates before using this as Common Crawl, NEXMark Q5, or
DEBS evidence.

## Purpose

This matrix tests the cheap stream-window aggregate shape directly:

- ordinary Scala records are appended into structured child-bucket lifetimes;
- parent metadata keeps additive per-key aggregate state;
- old buckets are bulk-closed through a cursor;
- heap, checked Rift, and trusted Rift use the same logical event stream and
  aggregate semantics.

The heap and trusted controls use a local open-addressed aggregate table. The
checked mode uses `RiftRegion.StreamWindowFold`, which owns the primitive
aggregate table in the parent stream region and stores records in child bucket
regions.

## Commands

```bash
CHECKED_FOLD_EVENTS=1000000 \
CHECKED_FOLD_BENCHMARK_RUNS=3 \
CHECKED_FOLD_WARMUPS=1 \
CHECKED_FOLD_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-fold-valid-20260601-1m \
zsh sandbox/run_checked_window_fold_matrix.sh

CHECKED_FOLD_EVENTS=20000 \
CHECKED_FOLD_BENCHMARK_RUNS=1 \
CHECKED_FOLD_WARMUPS=0 \
CHECKED_FOLD_OUTPUT_DIR=/tmp/checked-fold-20k \
zsh sandbox/run_checked_window_fold_matrix.sh

CHECKED_FOLD_BUILD=0 \
CHECKED_FOLD_EVENTS=100000 \
CHECKED_FOLD_BENCHMARK_RUNS=3 \
CHECKED_FOLD_WARMUPS=1 \
CHECKED_FOLD_OUTPUT_DIR=/tmp/checked-fold-100k \
zsh sandbox/run_checked_window_fold_matrix.sh

CHECKED_FOLD_BUILD=0 \
CHECKED_FOLD_EVENTS=1000000 \
CHECKED_FOLD_BENCHMARK_RUNS=3 \
CHECKED_FOLD_WARMUPS=1 \
CHECKED_FOLD_OUTPUT_DIR=/tmp/checked-fold-1m \
zsh sandbox/run_checked_window_fold_matrix.sh
```

## Results

Latest validated 1M 3-run focused rerun, 2026-06-01:

| Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 99.302 | 10.518 | 0.000 | 0 | 0 / 0 | 75005952 | -6322631816023653086 |
| rift-checked | 97.321 | 0.000 | 0.148 | 1000004 | 41 / 41 | 40386560 | -6322631816023653086 |

Output directory:
`/Users/siyaoliu/rift/cache/checked-fold-valid-20260601-1m`

Build caveat: the fresh native-link build completed successfully but printed
JDK code-cache warnings during sbt compilation. The warnings were in the build
JVM, not the benchmark process. Treat this as a focused local validation row,
not a full final-clean matrix refresh.

Historical gate before the check-removal fix:

20k smoke:

| Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 3.340 | 0.379 | 0.000 | 0 | 0 / 0 | 8273920 |
| rift-checked | 3.429 | 0.000 | 0.104 | 20004 | 2 / 2 | 9682944 |
| rift-trusted-hp | 2.246 | 0.213 | 0.047 | 20000 | 1 / 1 | 8880128 |
| rift-trusted-streaming | 2.046 | 0.183 | 0.036 | 20000 | 1 / 1 | 8863744 |

100k 3-run medians:

| Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 9.263 | 0.000 | 0.000 | 0 | 0 / 0 | 21102592 |
| rift-checked | 11.999 | 0.000 | 0.060 | 100004 | 5 / 5 | 14958592 |
| rift-trusted-hp | 10.334 | 0.097 | 0.030 | 100000 | 4 / 4 | 15990784 |
| rift-trusted-streaming | 10.011 | 0.111 | 0.013 | 100000 | 4 / 4 | 16056320 |

1M 3-run medians:

| Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 103.244 | 11.910 | 0.000 | 0 | 0 / 0 | 75022336 |
| rift-checked | 118.726 | 0.000 | 0.175 | 1000004 | 41 / 41 | 40402944 |
| rift-trusted-hp | 104.919 | 0.000 | 0.117 | 1000000 | 40 / 40 | 46841856 |
| rift-trusted-streaming | 106.088 | 0.000 | 0.120 | 1000000 | 40 / 40 | 46956544 |

All rows matched checksum.

## Interpretation

- The focused checked gate now passes narrowly in the latest validated rerun:
  at 1M, `rift-checked` is `97.321 ms` versus heap `99.302 ms`, with matching
  checksum.
- The memory-management direction is still visible: checked Rift removes
  measured GC, keeps Rift operation time below `1 ms`, and cuts RSS from
  `75005952` bytes to `40386560` bytes in the latest rerun.
- Trusted HPZone/Streaming are close to heap but do not beat it in this matrix.
  That suggests the remaining elapsed gap is not region open/close cost alone;
  it is mostly checked/framework/table access overhead around the reusable API.
- The result unblocks targeted application experiments, but Common Crawl WET,
  NEXMark Q5, and DEBS fold integrations still need their own correctness and
  L1/L2 evidence before becoming application claims.

## Next Target

Profile or reduce `StreamWindowFold` overhead before application integration:

- count table probes and rehashes under `CHECKED_FOLD_DIAG=1`;
- test a dense-key array-backed fold variant for workloads with bounded
  integer keys;
- keep heap controls algorithmically aligned.
