# Checked StreamWindowIndexedRank Matrix

Status: focused checked-API methodology harness. Results should be treated as
framework/API evidence, not DEBS evidence.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedStreamWindowRankMatrix.scala` and run with
`sandbox/run_checked_stream_window_rank_matrix.sh`.

## Intent

This harness exercises the checked stream-window ranking abstraction added
after `RegionIndexedPriorityQueue`.

Heap and Rift use the same logical program:

- process a monotonic stream of keyed events;
- group events into fixed time buckets and retain a fixed number of live
  buckets;
- create an ordinary Scala record object the first time a key appears in a
  bucket;
- mutate that record and update its priority while the key stays in the same
  bucket;
- replace the key's ranked value when it moves into a newer bucket;
- remove all parent-visible references for records whose bucket is closing;
- sample the current highest-ranked record during the stream and fold a final
  top-k snapshot into a checksum.

Heap mode allocates records and control arrays on the GC heap. `rift-checked`
allocates records in checked child bucket regions and uses
`StreamWindowIndexedRank` for parent-owned dense-key ranking state.
The current `rift-checked` path uses `putWindowRankInBucket`, which records
the child bucket that owns each dense key. Bucket close automatically removes
those keys from parent-owned rank state before closing the child region.

The purpose is to validate a reusable framework shape for stream operators:
ordinary Scala objects live in structured bucket regions, parent control
metadata is explicit, and the rank API removes bucket-local references before
the child region closes.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_SWR_EVENTS` | `1000000` |
| `CHECKED_SWR_EVENTS_PER_BUCKET` | `25000` |
| `CHECKED_SWR_KEY_CAPACITY` | `65536` |
| `CHECKED_SWR_LONG_KEY_SPACE` | `65536` |
| `CHECKED_SWR_BUCKET_SECONDS` | `60` |
| `CHECKED_SWR_WINDOW_BUCKETS` | `8` |
| `CHECKED_SWR_INITIAL_RANK_CAPACITY` | `1024` |
| `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY` | `131072` |
| `CHECKED_SWR_TOP_K` | `128` |
| `CHECKED_SWR_SAMPLE_EVERY` | `4096` |
| `CHECKED_SWR_WARMUPS` | `1` |
| `CHECKED_SWR_BENCHMARK_RUNS` | `3` |
| `CHECKED_SWR_TABLE_DIAG` | `0` |

Modes:

- `heap`
- `rift-checked`
- `heap-long`
- `rift-checked-long`
- `rift-checked-table`
- `rift-checked-table-long`

`CHECKED_SWR_TABLE_DIAG=1` enables opt-in diagnostics for
`StreamWindowTableRank`. Diagnostic runs print `TABLE_DIAG` lines to stdout
and the mode run log with lookup/probe, insert/replace, priority-update,
heap-sift/swap, bucket-move, bucket-close, rehash, and final load/deleted
counters. Keep this off for headline timing rows.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_EVENTS=20000 \
CHECKED_SWR_EVENTS_PER_BUCKET=1000 \
CHECKED_SWR_KEY_CAPACITY=4096 \
CHECKED_SWR_LONG_KEY_SPACE=4096 \
CHECKED_SWR_WINDOW_BUCKETS=4 \
CHECKED_SWR_BENCHMARK_RUNS=1 \
CHECKED_SWR_WARMUPS=0 \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-rank-smoke \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-rank \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Long-key 100k median:

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
CHECKED_SWR_MODES="heap-long rift-checked-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-long-rank-100000-rss \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Long-key default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_MODES="heap-long rift-checked-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-long-rank-1000000 \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

## Validation

Validation run on 2026-04-28 and updated on 2026-04-29:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `72/72`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `23/23`.
- The smoke matrix native-linked `CheckedStreamWindowRankMatrix`.
- The small heap and `rift-checked` smoke runs matched checksum
  `-3490531581377742567`.
- After the auto-cleanup update, a 100k smoke matrix matched checksum
  `-476315670107920613`.
- The post-auto-cleanup default local matrix matched checksum
  `6881312641757835670`.
- After the entry-cleanup update, a 100k 3-run smoke matched checksum
  `-476315670107920613`.
- The post-entry-cleanup default local matrix matched checksum
  `6881312641757835670`.
- After the remove-with-value queue primitive update, a 100k 3-run smoke
  matched checksum `-476315670107920613`.
- The post-remove-with-value default local matrix matched checksum
  `6881312641757835670`.
- After the lexicographic priority API update, a 100k 3-run smoke matched
  checksum `-476315670107920613`.
- The post-lexicographic priority default local matrix matched checksum
  `6881312641757835670`.
- After the long-key matrix update, `sandbox3_next/compile` passed and the
  long-key 20k smoke, 100k 3-run matrix, and default 1M 3-run matrix matched
  heap-long and `rift-checked-long` checksums.
- After the fused `StreamWindowTableRank` update,
  `sandbox3_next/compile` passed, the checked compiler test suite passed
  `85/85`, and the checked native runtime suite passed `32/32`.
- The fused table-rank 20k smoke, 100k 3-run matrix, and 1M 3-run matrix
  matched heap-long checksums. The 100k long-key gate passed; the 1M long-key
  gate did not pass the strict within-15%-of-heap target.
- After the fast bucket-close removal, directional heap-repair, and opt-in
  diagnostics update, `sandbox3_next/compile` passed, the checked compiler test
  suite passed `86/86`, and the checked native runtime suite passed `33/33`.
- The post-update 20k smoke and 100k/1M focused matrices matched heap-long
  checksums. The 100k long-key TableRank gate still passed, but the 1M gate
  still failed and TableRank was intentionally not re-integrated into DEBS Q1.
- `bench/debs2015/run_q1_checked_processing_matrix.sh` matched heap and
  checked-processing sample output after the provisional DEBS Q1 TableRank
  integration was backed out.
- `CHECKED_SWR_TABLE_DIAG=1` was verified to print `TABLE_DIAG` counters from
  `sandbox/run_checked_stream_window_rank_matrix.sh`.

## Small Smoke Result

Configuration:

- `CHECKED_SWR_EVENTS=20000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=1000`
- `CHECKED_SWR_KEY_CAPACITY=4096`
- `CHECKED_SWR_WINDOW_BUCKETS=4`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 5.755 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6864896 | -3490531581377742567 |
| rift-checked | 7.415 | 0.390 | 0.055 | 18676 | 21 | 21 | 0 | 8486912 | -3490531581377742567 |

## Auto-Cleanup Smoke Result

This run uses the newer `putWindowRankInBucket` path, where the rank
collection owns the key-to-bucket cleanup list.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 25.043 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 20578304 | -476315670107920613 |
| rift-checked | 33.499 | 0.909 | 0.296 | 82548 | 5 | 5 | 0 | 30556160 | -476315670107920613 |

## Entry-Cleanup Smoke Result

This run uses `closeWindowRankBucketsBeforeWithEntries`, which reports the
rank entries being unlinked so the checked operator can clean its side table
without maintaining a second bucket-local key list.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22.271 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35176448 | -476315670107920613 |
| rift-checked | 33.840 | 0.901 | 0.117 | 82545 | 5 | 5 | 0 | 29999104 | -476315670107920613 |

## Remove-With-Value Close Probe Result

This run adds an internal queue primitive that removes a key and returns its
ranked value in one trusted operation. Bucket close uses that primitive instead
of `contains` + `get` + `remove`. A runtime regression test covers the
already-popped-key case, where a bucket-owned key may no longer be present in
the rank heap when the bucket closes.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 25.360 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35225600 | -476315670107920613 |
| rift-checked | 38.823 | 1.021 | 0.162 | 82545 | 5 | 5 | 0 | 30015488 | -476315670107920613 |

## Lexicographic Priority API Control Result

This checkpoint adds `regionIndexedPriorityQueueLexicographic` and
`streamWindowIndexedRankLexicographic`. The existing matrix still uses the
single-`Long` priority path; this control checks that the shared queue changes
do not break the current stream-window rank harness. Separate compiler/runtime
tests exercise the new four-component lexicographic path directly.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22.211 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35209216 | -476315670107920613 |
| rift-checked | 37.404 | 0.908 | 0.110 | 82545 | 5 | 5 | 0 | 30031872 | -476315670107920613 |

## Long-Key Smoke Result

This run uses `heap-long` and `rift-checked-long`, where keys are arbitrary
`Long` values generated from the same event stream. Heap mode uses an
open-addressed heap long-key rank queue; checked Rift uses
`StreamWindowLongIndexedRank`. The checked mode uses the no-entry close helper
because the rank collection itself owns lookup state in this long-key shape.

Configuration:

- `CHECKED_SWR_EVENTS=20000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=1000`
- `CHECKED_SWR_KEY_CAPACITY=4096`
- `CHECKED_SWR_LONG_KEY_SPACE=4096`
- `CHECKED_SWR_WINDOW_BUCKETS=4`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 6.007 | 0.149 | 0.000 | 0 | 0 | 0 | 0 | 9879552 | -715513143181030887 |
| rift-checked-long | 9.440 | 0.310 | 0.128 | 18679 | 21 | 21 | 0 | 13254656 | -715513143181030887 |

## Long-Key 100k Median

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_LONG_KEY_SPACE=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY=131072`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 37.045 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 38830080 | -2863780563714953957 |
| rift-checked-long | 49.450 | 0.000 | 0.178 | 82547 | 5 | 5 | 0 | 33882112 | -2863780563714953957 |

## Fused TableRank 20k Smoke

This checkpoint adds `StreamWindowTableRank`, a fused parent-owned checked
rank structure where one open-addressed table owns key lookup, value reference,
priority, heap index, and bucket close links. The heap stores table-slot ids
instead of duplicating key/value entries. Existing dense-key and long-key
window-rank implementations remain as controls.

Configuration:

- `CHECKED_SWR_EVENTS=20000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=5000`
- `CHECKED_SWR_KEY_CAPACITY=16384`
- `CHECKED_SWR_LONG_KEY_SPACE=16384`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 7.498 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 11321344 | 2201034068585435575 |
| rift-checked-table-long | 9.246 | 0.000 | 0.292 | 17781 | 5 | 5 | 0 | 16269312 | 2201034068585435575 |

## Fused TableRank 100k Gate

Same long-key stream shape as the long-key 100k median, with the fused
TableRank path added as a control.

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 40.582 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 38780928 | -2863780563714953957 |
| rift-checked-long | 54.018 | 0.000 | 0.215 | 82547 | 5 | 5 | 0 | 33931264 | -2863780563714953957 |
| rift-checked-table-long | 41.069 | 0.000 | 0.164 | 82533 | 5 | 5 | 0 | 49938432 | -2863780563714953957 |

Interpretation:

- The 100k gate passes: `rift-checked-table-long` is below the planned
  `43 ms` threshold and materially faster than the old checked-long control in
  the same run.
- RSS is higher than both heap-long and old checked-long at 100k, so this is
  a CPU result, not a memory-footprint win.

## Default Local Median

Configuration:

- `CHECKED_SWR_EVENTS=1000000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_BUCKET_SECONDS=60`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 205.849 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 145801216 | 6881312641757835670 |
| rift-checked | 329.761 | 7.523 | 0.352 | 826643 | 41 | 41 | 0 | 87457792 | 6881312641757835670 |

## Long-Key Default Local Median

Configuration:

- `CHECKED_SWR_EVENTS=1000000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_LONG_KEY_SPACE=65536`
- `CHECKED_SWR_BUCKET_SECONDS=60`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY=131072`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 358.988 | 6.973 | 0.000 | 0 | 0 | 0 | 0 | 111394816 | 4222832129898301078 |
| rift-checked-long | 503.906 | 5.470 | 0.491 | 826645 | 41 | 41 | 0 | 128286720 | 4222832129898301078 |

## Fused TableRank 1M Gate

Same default 1M long-key stream shape, with `rift-checked-table-long` added.

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 413.664 | 7.080 | 0.000 | 0 | 0 | 0 | 0 | 111443968 | 4222832129898301078 |
| rift-checked-long | 568.427 | 5.821 | 0.449 | 826645 | 41 | 41 | 0 | 128335872 | 4222832129898301078 |
| rift-checked-table-long | 491.918 | 5.927 | 0.475 | 826631 | 41 | 41 | 0 | 126468096 | 4222832129898301078 |

Interpretation:

- The 1M gate does not pass the strict target. `rift-checked-table-long`
  improves over same-run old checked-long by about `13%`, and RSS is slightly
  lower than old checked-long, but it is still about `19%` slower than
  same-run heap-long.
- Measured Rift runtime operation time remains below `1 ms`; the remaining
  gap is checked container CPU/layout overhead, not slab allocation/close cost.
- A follow-up probe that removed bounded probe counters from the fused table
  lookup was rejected: same-run 1M `rift-checked-table-long` regressed to
  `569.633 ms` with `0.713 ms` Rift op time. Keep the bounded probe loop.

## Fused TableRank Fast-Close / Directional-Repair Follow-Up

This checkpoint keeps `StreamWindowTableRank` as a focused framework primitive
and backs it out of DEBS Q1 until the 1M gate clears. It adds two general
container changes:

- bucket-close fast remove: closing a known bucket removes table slots without
  the normal owner-list unlink path or bucket scan;
- directional heap removal: when a heap removal moves the last slot into a
  hole, repair chooses `siftUp` or `siftDown` based on the moved slot and its
  parent instead of running the old generic repair path.

It also adds opt-in diagnostics with `CHECKED_SWR_TABLE_DIAG=1`. Diagnostics
are intended for profile direction only, not headline elapsed results.

20k smoke:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 11.297 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 11288576 | 2201034068585435575 |
| rift-checked-table-long | 8.595 | 0.000 | 0.141 | 17781 | 5 | 5 | 0 | 16203776 | 2201034068585435575 |

100k gate:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 34.620 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 38846464 | -2863780563714953957 |
| rift-checked-long | 44.762 | 0.000 | 0.144 | 82547 | 5 | 5 | 0 | 33898496 | -2863780563714953957 |
| rift-checked-table-long | 32.330 | 0.000 | 0.093 | 82533 | 5 | 5 | 0 | 49922048 | -2863780563714953957 |

1M gate:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 300.667 | 4.252 | 0.000 | 0 | 0 | 0 | 0 | 111394816 | 4222832129898301078 |
| rift-checked-long | 476.015 | 6.541 | 0.498 | 826645 | 41 | 41 | 0 | 128221184 | 4222832129898301078 |
| rift-checked-table-long | 512.764 | 5.964 | 0.490 | 826631 | 41 | 41 | 0 | 126451712 | 4222832129898301078 |

100k diagnostic counter sample, `CHECKED_SWR_TABLE_DIAG=1`, one run:

```text
TABLE_DIAG mode=rift-checked-table-long lookups=300000 probes=385422 inserts=52382 replacements=30135 priority_updates=17483 heap_sift_steps=266870 heap_swaps=168244 bucket_moves=30135 bucket_close_removals=52254 rehashes=0 topk_candidate_compares=0 table_active=0 table_used=52382 table_deleted=52382 table_capacity=131072 heap_used=0 heap_capacity=65536
```

Interpretation:

- The 20k smoke matches checksum and the 100k gate remains strong:
  `rift-checked-table-long` is faster than same-run heap-long and old
  checked-long at 100k.
- The 1M gate still fails. In this same run, TableRank is `1.71x` heap-long and
  slower than old checked-long, although its RSS is slightly lower than old
  checked-long and measured Rift op time remains below `1 ms`.
- Because the 1M gate failed, the provisional DEBS Q1 TableRank integration was
  reverted. DEBS checked runs continue to use the previous rank-arena path.
- The next TableRank work should be profiling/result-pack work, not DEBS
  reintegration. The most visible counters are probe count and heap repair
  work; the rejected nullable lookup, unbounded probe-loop, post-sift lookup,
  and backward-shift deletion probes should not be repeated.

## TableRank Profile Pack

Source: `sandbox/TABLERANK_PROFILE.md`.

Fresh profile runs after the checkpoint recorded same-run 100k and 1M
non-diagnostic rows plus diagnostic-only `TABLE_DIAG` counters. The 1M
non-diagnostic profile was:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|
| heap-long | 437.702 | 8.367 | 0.000 | 0 | 111411200 |
| rift-checked-long | 610.358 | 5.817 | 1.035 | 826645 | 128253952 |
| rift-checked-table-long | 568.572 | 8.064 | 0.562 | 826631 | 126418944 |

The 1M diagnostic counters are:

```text
TABLE_DIAG mode=rift-checked-table-long lookups=3000000 probes=4803987 inserts=107838 replacements=718777 priority_updates=173385 heap_sift_steps=1861921 heap_swaps=1116618 bucket_moves=718777 bucket_close_removals=107710 rehashes=0 topk_candidate_compares=0 table_active=0 table_used=65536 table_deleted=65536 table_capacity=131072 heap_used=0 heap_capacity=65536
```

Derived 1M rates: `3.000` lookups/event, `4.804` probes/event, `0.719`
replacements/event, `0.719` bucket moves/event, `1.862` heap sift steps/event,
and `1.117` heap swaps/event. This profile does not identify one low-risk
representation patch, so no post-profile optimization was applied. The fresh
100k profile also did not reproduce the earlier strong `<= 43 ms` TableRank
row; treat that 100k pass as unstable until rerun under controlled conditions.

## Interpretation

- This is a general stream-window rank benchmark, not a DEBS-specific
  optimization.
- It tests the design goal that structured-lifetime stream data can be ordinary
  Scala objects in regions while control metadata stays explicit.
- The first version used dense integer keys and one `Long` priority. The
  lexicographic priority API now covers Q1-style count/time/sequence/key
  tie-breakers while keeping dense keys and the same close discipline.
  Long-key modes now exercise the hash-keyed stream-window rank path with
  arbitrary `Long` keys, so packed route-style keys no longer require a
  benchmark-specific dense remapping layer.
- The auto-cleanup path strengthens close discipline: the rank collection now
  tracks bucket-owned keys and removes them before closing child regions. This
  is a framework-level improvement, not a DEBS-specific cleanup convention.
- A first auto-cleanup implementation with linear unlinking was rejected during
  this checkpoint: the 100k checked smoke took `1745.677 ms`. The committed
  version uses per-key previous/next links and reduces that smoke to
  `33.499 ms`.
- A direct heap bucket-reference owner table was also tested and rejected: the
  default checked median regressed to `341.705 ms`. The current entry-cleanup
  path keeps the primitive owner tables and removes duplicate operator-side
  bucket nodes in checked mode.
- The default local median is still not a speed win: checked Rift is slower
  (`329.761 ms` vs heap `205.849 ms`) even though measured Rift runtime
  operation cost is low (`0.352 ms`) and peak RSS is lower. The entry-cleanup
  API improved the previous auto-cleanup default (`313.572 ms` checked) and RSS
  (`94732288` bytes) by avoiding a second checked-side key list, but remained
  slower than the previous manual-cleanup median (`254.050 ms` checked). The
  remove-with-value primitive is a simpler close path and validates the
  already-popped-key behavior, but it did not produce a measured speedup. The
  lexicographic API is a functionality step toward Q1-style ordering, not a
  speed claim.
- The long-key mode uses the no-entry close helper because there is no
  operator-side lookup table to clean. That improves the 100k checked median
  compared with the first close-with-entry run (`49.450 ms` vs `54.574 ms`),
  but it does not close the main gap.
- The long-key default median is also not a speed win: `rift-checked-long` is
  slower than `heap-long` (`503.906 ms` vs `358.988 ms`) and has higher RSS at
  1M events, although the 100k long-key row has lower checked RSS. This means
  the long-key stream-window API is functionally validated but should not be
  treated as ready to improve DEBS throughput without another container-CPU or
  memory-layout pass. The 1M absolute times moved between adjacent reruns, so
  use the same-run ratio and checksum result rather than over-interpreting the
  exact millisecond values.
- The fused `StreamWindowTableRank` is a real framework improvement at 100k,
  but it is not yet a full performance win. The fast-close/directional-repair
  follow-up did not clear the 1M gate, so TableRank should be treated as an
  experimental checked operator primitive whose next step is
  profiling/reducing container CPU and RSS before broad DEBS integration.
- The result is still useful because it validates the general pattern that
  bucket-lifetime records can be ordinary Scala objects, stored in checked
  region-backed ranking state, sampled during the stream, and automatically
  unlinked from parent rank state before bucket close with heap-equivalent
  output.

## Caveats

- This is not an exact Broom, Yak, StreamFlex, or Stancu artifact
  reproduction.
- It does not include SafeZone, improved SafeZone, or Commix controls.
- It is intended to decide whether the reusable checked stream-window rank API
  is worth integrating into DEBS or needs another API iteration first.
- Heap mode still uses primitive bucket-list nodes for per-key record-table
  cleanup. Checked mode now uses `closeWindowRankBucketsBeforeWithEntries` for
  that side-table cleanup and no longer maintains its own duplicate key list.
