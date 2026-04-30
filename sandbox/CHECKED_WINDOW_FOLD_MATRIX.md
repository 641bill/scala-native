# Checked Window Fold Matrix

Status: focused checked-operator profile pack for the proposed additive
`RiftRegion.StreamWindowFold` API. This is framework evidence only. The 1M
checked gate failed, so this operator must not be used as Common Crawl,
NEXMark Q5, or DEBS evidence yet.

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

- The focused checked gate fails: at 1M, `rift-checked` is `118.726 ms`
  versus heap `103.244 ms`.
- The memory-management direction is still visible: checked Rift removes
  measured GC, keeps Rift operation time below `1 ms`, and cuts RSS from
  `75022336` bytes to `40402944` bytes.
- Trusted HPZone/Streaming are close to heap but do not beat it in this matrix.
  That suggests the remaining elapsed gap is not region open/close cost alone;
  it is mostly checked/framework/table access overhead around the reusable API.
- Per the gate, Common Crawl WET, NEXMark Q5 fold integration, and DEBS
  integration are blocked until this operator is cheaper or a different
  object-heavy shape passes a focused matrix.

## Next Target

Profile or reduce `StreamWindowFold` overhead before application integration:

- compare a checked mode that appends records but does not update the aggregate
  table;
- count table probes and rehashes under `CHECKED_FOLD_DIAG=1`;
- test a dense-key array-backed fold variant for workloads with bounded
  integer keys;
- keep heap controls algorithmically aligned.
