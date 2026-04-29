# TableRank Profile Pack

Date: 2026-04-29

Status: focused Phase 7 profile evidence. This is not DEBS application
evidence, and diagnostic elapsed times are not headline timing rows.

## Intent

This pack profiles `RiftRegion.StreamWindowTableRank` after the fast
bucket-close and directional heap-repair checkpoint. The goal is to decide
whether the next change should be another focused TableRank representation pass
or DEBS Q1 integration.

Current decision: do not integrate TableRank into DEBS Q1. The 1M focused gate
still fails.

## Commands

Non-diagnostic 100k profile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_EVENTS=100000 \
CHECKED_SWR_EVENTS_PER_BUCKET=25000 \
CHECKED_SWR_KEY_CAPACITY=65536 \
CHECKED_SWR_LONG_KEY_SPACE=65536 \
CHECKED_SWR_WINDOW_BUCKETS=8 \
CHECKED_SWR_BENCHMARK_RUNS=3 \
CHECKED_SWR_WARMUPS=1 \
CHECKED_SWR_MODES="heap-long rift-checked-long rift-checked-table-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-tablerank-profile-100k \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Non-diagnostic 1M profile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_MODES="heap-long rift-checked-long rift-checked-table-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-tablerank-profile-1m \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Diagnostic 100k and 1M profiles:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_TABLE_DIAG=1 \
CHECKED_SWR_EVENTS=100000 \
CHECKED_SWR_EVENTS_PER_BUCKET=25000 \
CHECKED_SWR_KEY_CAPACITY=65536 \
CHECKED_SWR_LONG_KEY_SPACE=65536 \
CHECKED_SWR_WINDOW_BUCKETS=8 \
CHECKED_SWR_BENCHMARK_RUNS=1 \
CHECKED_SWR_WARMUPS=0 \
CHECKED_SWR_MODES="rift-checked-table-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-tablerank-profile-diag-100k \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh

CHECKED_SWR_BUILD=0 \
CHECKED_SWR_TABLE_DIAG=1 \
CHECKED_SWR_BENCHMARK_RUNS=1 \
CHECKED_SWR_WARMUPS=0 \
CHECKED_SWR_MODES="rift-checked-table-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-tablerank-profile-diag-1m \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

## Non-Diagnostic Results

All rows matched the expected checksum for the corresponding input size.

100k, 3-run median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 41.941 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 38862848 | -2863780563714953957 |
| rift-checked-long | 58.441 | 0.000 | 0.218 | 82547 | 5 | 5 | 0 | 33898496 | -2863780563714953957 |
| rift-checked-table-long | 56.585 | 0.000 | 0.198 | 82533 | 5 | 5 | 0 | 49905664 | -2863780563714953957 |

1M, 3-run median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 437.702 | 8.367 | 0.000 | 0 | 0 | 0 | 0 | 111411200 | 4222832129898301078 |
| rift-checked-long | 610.358 | 5.817 | 1.035 | 826645 | 41 | 41 | 0 | 128253952 | 4222832129898301078 |
| rift-checked-table-long | 568.572 | 8.064 | 0.562 | 826631 | 41 | 41 | 0 | 126418944 | 4222832129898301078 |

## Diagnostic Counters

100k diagnostic row:

```text
TABLE_DIAG mode=rift-checked-table-long lookups=300000 probes=385422 inserts=52382 replacements=30135 priority_updates=17483 heap_sift_steps=266870 heap_swaps=168244 bucket_moves=30135 bucket_close_removals=52254 rehashes=0 topk_candidate_compares=0 table_active=0 table_used=52382 table_deleted=52382 table_capacity=131072 heap_used=0 heap_capacity=65536
```

1M diagnostic row:

```text
TABLE_DIAG mode=rift-checked-table-long lookups=3000000 probes=4803987 inserts=107838 replacements=718777 priority_updates=173385 heap_sift_steps=1861921 heap_swaps=1116618 bucket_moves=718777 bucket_close_removals=107710 rehashes=0 topk_candidate_compares=0 table_active=0 table_used=65536 table_deleted=65536 table_capacity=131072 heap_used=0 heap_capacity=65536
```

Derived counters:

| Input | Lookups/event | Probes/event | Inserts/event | Replacements/event | Priority updates/event | Heap sift steps/event | Heap swaps/event | Bucket moves/event | Bucket removals/event | Final deleted/capacity |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | 3.000 | 3.854 | 0.524 | 0.301 | 0.175 | 2.669 | 1.682 | 0.301 | 0.523 | 0.400 |
| 1M | 3.000 | 4.804 | 0.108 | 0.719 | 0.173 | 1.862 | 1.117 | 0.719 | 0.108 | 0.500 |

## Interpretation

- The fresh 100k profile does not reproduce the earlier strong 100k gate row:
  `rift-checked-table-long` is `56.585 ms`, above the earlier `<= 43 ms`
  threshold, though still slightly faster than same-run `rift-checked-long`.
  Treat the earlier 100k pass as unstable under current local conditions.
- The 1M focused gate still fails: `rift-checked-table-long` is faster than
  same-run `rift-checked-long` (`568.572 ms` vs `610.358 ms`) but remains about
  `1.30x` same-run `heap-long` (`437.702 ms`).
- RSS remains above heap: `126418944` bytes for TableRank versus `111411200`
  bytes for heap-long at 1M.
- Rift op time is below `1 ms` for TableRank in the non-diagnostic 1M run
  (`0.562 ms`), so the gap is container CPU/layout, not region open/close or
  allocation bookkeeping.
- The counters do not isolate one safe patch. At 1M, TableRank performs three
  lookups per event, about `4.8` table probes per event, about `0.72`
  replacements and bucket moves per event, and about `1.86` heap sift steps per
  event. That points to combined lookup/update/heap maintenance pressure.
- Do not repeat the rejected nullable lookup, unbounded probe-loop,
  post-sift lookup, or backward-shift deletion experiments. The next useful
  optimization needs a deeper representation/profile pass, likely around
  reducing duplicate lookup/update work without reintroducing the rejected
  nullable-lookup shape, or changing the table/heap layout to reduce probe and
  heap-repair pressure together.

## Decision

No post-profile optimization was applied in this checkpoint. The profile does
not justify a low-risk representation patch yet. TableRank remains a focused
checked-operator primitive and must stay out of DEBS Q1 until the 1M gate
passes.
