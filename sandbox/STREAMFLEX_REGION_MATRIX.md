# StreamFlex Region Matrix

Last updated: 2026-05-08 20:58 CEST

Status: StreamFlex-style methodology reproduction harness with validated local
smoke, default median, allocation-pressure median runs, checked
TransactionRegion rows, and direct checked epoch rows.

The harness is implemented in
`sandbox/src/main/scala-next/StreamFlexRegionMatrix.scala` and run with
`sandbox/run_streamflex_region_instrumented_matrix.sh`.

## Benchmark Intent

This is not an exact StreamFlex/Ovm reproduction. It is a local methodology
benchmark for the axes StreamFlex makes central: stream throughput, per-event
latency tails, and deadline-miss-style counts.

Heap, SafeZone, and Rift variants run the same logical stream pipeline:

- Create packet fragments for each stream event.
- Decode packet fragments.
- Classify decoded events.
- Emit alert objects for selected classifications.
- Traverse alerts to compute a checksum.

The stream data objects are ordinary Scala objects. Heap mode allocates them
with `new`; SafeZone mode allocates them in Scala Native SafeZone; Rift modes
allocate them with `region.alloc`.

## Default Configuration

| Environment variable | Default |
|---|---:|
| `STREAMFLEX_EVENTS` | `200000` |
| `STREAMFLEX_BATCH_SIZE` | `256` |
| `STREAMFLEX_OBJECTS_PER_EVENT` | `4` |
| `STREAMFLEX_LATENCY_EVENTS` | `10000` |
| `STREAMFLEX_LATENCY_OBJECTS_PER_EVENT` | `16` |
| `STREAMFLEX_PERIOD_NS` | `80000` |
| `STREAMFLEX_WARMUPS` | `1` |
| `STREAMFLEX_BENCHMARK_RUNS` | `3` |

Use `STREAMFLEX_WORKLOAD=throughput`, `latency`, or `all`.

Default script modes now skip current SafeZone because root-mode-0 is retained
only as an explicit provenance control. The default set is:

- `heap`
- `improved-safezone`
- `unsafezone-hp`
- `rift-hp`
- `rift-streaming`
- `rift-checked-epoch-buffer`
- `rift-checked-safezone-epoch-buffer`
- `rift-checked-transaction-region`
- `rift-checked-safezone-transaction-region`

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMFLEX_EVENTS=2000 STREAMFLEX_LATENCY_EVENTS=500 STREAMFLEX_BENCHMARK_RUNS=1 STREAMFLEX_WARMUPS=0 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMFLEX_BUILD=0 STREAMFLEX_BENCHMARK_RUNS=3 STREAMFLEX_WARMUPS=1 \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-region-instrumented \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

## Result Status

Compile, smoke, default local medians, and one allocation-pressure latency run
have been recorded. These are local StreamFlex-style methodology numbers, not
an exact StreamFlex/Ovm artifact reproduction.

Latest staged headline sweep: `evidence/COMPREHENSIVE_SWEEP_2026_05_06.md`.
At the latest throughput scale, trusted Rift HP/Streaming are still fastest
around `36.4 ms`; scoped checked `TransactionRegion` is the best checked row
at `39.019 ms`, faster than heap `42.860 ms` and improved SafeZone
`41.327 ms`.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

STREAMFLEX_EVENTS=2000 STREAMFLEX_LATENCY_EVENTS=500 STREAMFLEX_BENCHMARK_RUNS=1 STREAMFLEX_WARMUPS=0 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh

STREAMFLEX_BUILD=0 STREAMFLEX_BENCHMARK_RUNS=3 STREAMFLEX_WARMUPS=1 \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-region-instrumented \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh

STREAMFLEX_BUILD=0 STREAMFLEX_BENCHMARK_RUNS=3 STREAMFLEX_WARMUPS=1 \
STREAMFLEX_EVENTS=1000000 STREAMFLEX_OBJECTS_PER_EVENT=8 \
STREAMFLEX_LATENCY_EVENTS=50000 STREAMFLEX_LATENCY_OBJECTS_PER_EVENT=64 \
STREAMFLEX_PERIOD_NS=80000 \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-region-pressure \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. During the
sbt native-link setup the JVM emitted a code-cache warning, but the native
binary linked and all heap/SafeZone/Rift checksums matched.

## Checked EpochBuffer Follow-Up

Date/time: 2026-05-05 22:37:14 CEST.

This follow-up wires the reusable `RiftRegion.EpochBuffer[T]` into the
StreamFlex-shaped pipeline. The checked path uses four epoch buffers per
batch/event: packets, decoded records, classified records, and alerts. That is
safe and checksum-equivalent, but it also means four child regions are opened
and closed for every batch in throughput mode and every event in latency mode.

Smoke command:

```sh
STREAMFLEX_EVENTS=20000 \
STREAMFLEX_LATENCY_EVENTS=1000 \
STREAMFLEX_BENCHMARK_RUNS=1 \
STREAMFLEX_WARMUPS=0 \
STREAMFLEX_MODES="heap improved-safezone rift-hp rift-streaming rift-checked-epoch-buffer rift-checked-safezone-epoch-buffer" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-epoch-buffer-smoke \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

Default-scale throughput command:

```sh
STREAMFLEX_BUILD=0 \
STREAMFLEX_WORKLOAD=throughput \
STREAMFLEX_EVENTS=200000 \
STREAMFLEX_BENCHMARK_RUNS=3 \
STREAMFLEX_WARMUPS=1 \
STREAMFLEX_MODES="heap improved-safezone rift-hp rift-streaming rift-checked-epoch-buffer rift-checked-safezone-epoch-buffer" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-epoch-buffer-throughput-200k \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

Smoke rows:

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Peak RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|---:|---:|
| throughput | heap | 6.148 | 1.096 | 0.000 | 0 | 7929856 | 334131456973800 |
| throughput | improved SafeZone | 5.343 | 0.000 | 0.000 | 0 | 8093696 | 334131456973800 |
| throughput | Rift HPZone | 4.100 | 0.000 | 0.031 | 249937 | 8093696 | 334131456973800 |
| throughput | Rift Streaming | 3.707 | 0.000 | 0.019 | 249937 | 8044544 | 334131456973800 |
| throughput | checked EpochBuffer | 4.756 | 0.000 | 0.048 | 249937 | 8077312 | 334131456973800 |
| throughput | scoped checked EpochBuffer | 4.378 | 0.000 | 0.000 | 0 | 8028160 | 334131456973800 |
| latency | heap | 0.863 | 0.000 | 0.000 | 0 | 7929856 | 62885581450470 |
| latency | improved SafeZone | 1.400 | 0.000 | 0.000 | 0 | 8093696 | 62885581450470 |
| latency | Rift HPZone | 1.208 | 0.000 | 0.075 | 49910 | 8093696 | 62885581450470 |
| latency | Rift Streaming | 0.972 | 0.000 | 0.023 | 49910 | 8044544 | 62885581450470 |
| latency | checked EpochBuffer | 2.376 | 0.000 | 0.329 | 49910 | 8077312 | 62885581450470 |
| latency | scoped checked EpochBuffer | 2.075 | 0.000 | 0.000 | 0 | 8028160 | 62885581450470 |

Default-scale throughput rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| heap | 42.504 | 8.942 | 0.000 | 0 | 7946240 | 3320210680833752 |
| improved SafeZone | 40.224 | 0.000 | 0.000 | 0 | 8077312 | 3320210680833752 |
| Rift HPZone | 38.201 | 0.000 | 0.168 | 2499843 | 8093696 | 3320210680833752 |
| Rift Streaming | 36.357 | 0.000 | 0.119 | 2499843 | 8044544 | 3320210680833752 |
| checked EpochBuffer | 47.462 | 0.000 | 0.386 | 2499843 | 8077312 | 3320210680833752 |
| scoped checked EpochBuffer | 44.504 | 0.000 | 0.000 | 0 | 8044544 | 3320210680833752 |

Interpretation: this is not a checked StreamFlex win. The checked rows remove
heap GC, but the four-buffer implementation pays too much operator/lifecycle
overhead. It is still useful: the next reusable operator for multi-stage
pipelines should be a transaction or multi-list epoch region that opens one
child region per batch and owns several internal lists, rather than stacking
several independent `EpochBuffer`s.

## Checked TransactionRegion Follow-Up

Date/time: 2026-05-05 23:47:54 CEST.

`TransactionRegion` is the reusable checked operator for a multi-stage
transaction/batch lifetime. It opens one child region for the active
transaction, exposes several typed internal append lists, drains each list with
a cursor, and closes the child region once at transaction end. This is the
general API shape that replaces the StreamFlex-specific stack of four
independent `EpochBuffer`s.

The first implementation kept list heads/tails/lengths in indexed arrays. That
cut child-region opens/closes but was slower than stacked `EpochBuffer` on
throughput. The implementation was then changed so each `TransactionList` owns
its hot `head`/`tail`/`length` fields; the parent `TransactionRegion` keeps only
the active child region, a reusable cursor, and the list registry used for
close cleanup.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results: compile passed; checked compiler suite passed `110/110`; checked
native runtime suite passed `49/49`.

Smoke command after list-state optimization:

```sh
STREAMFLEX_EVENTS=20000 \
STREAMFLEX_LATENCY_EVENTS=1000 \
STREAMFLEX_BENCHMARK_RUNS=1 \
STREAMFLEX_WARMUPS=0 \
STREAMFLEX_MODES="heap rift-streaming rift-checked-epoch-buffer rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-transaction-smoke3 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

200k throughput command:

```sh
STREAMFLEX_BUILD=0 \
STREAMFLEX_WORKLOAD=throughput \
STREAMFLEX_EVENTS=200000 \
STREAMFLEX_BENCHMARK_RUNS=3 \
STREAMFLEX_WARMUPS=1 \
STREAMFLEX_MODES="heap improved-safezone rift-streaming rift-checked-epoch-buffer rift-checked-safezone-epoch-buffer rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-transaction-throughput-200k-v2 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

Latency command:

```sh
STREAMFLEX_BUILD=0 \
STREAMFLEX_WORKLOAD=latency \
STREAMFLEX_LATENCY_EVENTS=10000 \
STREAMFLEX_BENCHMARK_RUNS=3 \
STREAMFLEX_WARMUPS=1 \
STREAMFLEX_MODES="heap improved-safezone rift-streaming rift-checked-epoch-buffer rift-checked-safezone-epoch-buffer rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-transaction-latency-10k-v2 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

20k smoke rows:

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Peak RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|---:|---:|
| throughput | heap | 5.056 | 0.824 | 0.000 | 0 | 7962624 | 334131456973800 |
| throughput | Rift Streaming | 4.472 | 0.000 | 0.030 | 249937 | 8093696 | 334131456973800 |
| throughput | checked EpochBuffer | 4.897 | 0.000 | 0.055 | 249937 | 8126464 | 334131456973800 |
| throughput | checked TransactionRegion | 4.624 | 0.000 | 0.033 | 249937 | 8142848 | 334131456973800 |
| throughput | scoped checked TransactionRegion | 4.141 | 0.000 | 0.000 | 0 | 8126464 | 334131456973800 |
| latency | heap | 0.954 | 0.000 | 0.000 | 0 | 7962624 | 62885581450470 |
| latency | Rift Streaming | 0.980 | 0.000 | 0.025 | 49910 | 8093696 | 62885581450470 |
| latency | checked EpochBuffer | 2.885 | 0.000 | 0.373 | 49910 | 8126464 | 62885581450470 |
| latency | checked TransactionRegion | 1.519 | 0.000 | 0.084 | 49910 | 8142848 | 62885581450470 |
| latency | scoped checked TransactionRegion | 1.430 | 0.000 | 0.000 | 0 | 8126464 | 62885581450470 |

200k throughput rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Region opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---|---:|---:|
| heap | 41.995 | 8.135 | 0.000 | 0 | 0 / 0 / 0 | 7979008 | 3320210680833752 |
| improved SafeZone | 41.871 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8142848 | 3320210680833752 |
| Rift Streaming | 36.365 | 0.000 | 0.127 | 2499843 | 1 / 1 / 781 | 8093696 | 3320210680833752 |
| checked EpochBuffer | 48.052 | 0.000 | 0.403 | 2499843 | 3129 / 3129 / 0 | 8126464 | 3320210680833752 |
| scoped checked EpochBuffer | 44.987 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8077312 | 3320210680833752 |
| checked TransactionRegion | 44.881 | 0.000 | 0.189 | 2499843 | 783 / 783 / 0 | 8142848 | 3320210680833752 |
| scoped checked TransactionRegion | 41.375 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8126464 | 3320210680833752 |

10k latency rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | p50 ns | p99 ns | p999 ns | Max ns | Deadline misses | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 10.033 | 1.185 | 0.000 | 0 | 708 | 917 | 1333 | 296250 | 4 | 7962624 |
| improved SafeZone | 11.046 | 0.283 | 0.000 | 0 | 917 | 1208 | 2709 | 25041 | 0 | 8028160 |
| Rift Streaming | 9.804 | 0.000 | 0.238 | 499789 | 792 | 1042 | 1541 | 17417 | 0 | 7995392 |
| checked EpochBuffer | 27.454 | 1.293 | 3.508 | 499789 | 2250 | 2916 | 8834 | 399167 | 4 | 8077312 |
| scoped checked EpochBuffer | 23.072 | 1.221 | 0.000 | 0 | 1958 | 2541 | 7250 | 323583 | 4 | 8093696 |
| checked TransactionRegion | 15.493 | 0.298 | 0.824 | 499789 | 1292 | 1667 | 2000 | 39625 | 0 | 8044544 |
| scoped checked TransactionRegion | 13.580 | 0.287 | 0.000 | 0 | 1125 | 1458 | 2375 | 20000 | 0 | 8044544 |

Interpretation:

- `TransactionRegion` fixes the specific `EpochBuffer` granularity problem:
  at 200k throughput, checked Rift opens/closes `783` child regions instead of
  `3129`, and measured Rift op time drops from `0.403 ms` to `0.189 ms`.
- The SafeZone-backed checked transaction row is the best checked StreamFlex
  row so far: `41.375 ms`, slightly faster than heap (`41.995 ms`) and
  improved SafeZone (`41.871 ms`), but still slower than trusted Rift Streaming
  (`36.365 ms`).
- The Rift-native checked transaction row improves over checked EpochBuffer
  but remains slower than heap on throughput (`44.881 ms` vs `41.995 ms`).
  Remaining cost is checked/operator CPU, not collection.
- Latency improves materially versus stacked EpochBuffers. Checked
  TransactionRegion removes the heap/EpochBuffer deadline misses in this local
  configuration, but trusted Rift Streaming is still the fastest latency row.

## Native-Only Local 3-Run Median, Default Configuration

Configuration:

- `STREAMFLEX_EVENTS=200000`
- `STREAMFLEX_BATCH_SIZE=256`
- `STREAMFLEX_OBJECTS_PER_EVENT=4`
- `STREAMFLEX_LATENCY_EVENTS=10000`
- `STREAMFLEX_LATENCY_OBJECTS_PER_EVENT=16`
- `STREAMFLEX_PERIOD_NS=80000`
- `STREAMFLEX_BENCHMARK_RUNS=3`
- `STREAMFLEX_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | p50 ns | p99 ns | p999 ns | Max ns | Deadline misses | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| throughput | heap | 41.444 | 8.944 | 0.000 | 0 |  |  |  |  |  | 7962624 |
| throughput | current SafeZone | 41.263 | 0.000 | 0.000 | 0 |  |  |  |  |  | 8093696 |
| throughput | improved SafeZone | 47.436 | 0.000 | 0.000 | 0 |  |  |  |  |  | 8060928 |
| throughput | Rift HPZone | 37.638 | 0.000 | 0.131 | 2499843 |  |  |  |  |  | 8093696 |
| throughput | Rift Streaming | 37.683 | 0.000 | 0.082 | 2499843 |  |  |  |  |  | 8060928 |
| latency | heap | 9.982 | 1.218 | 0.000 | 0 | 667 | 875 | 2291 | 309875 | 4 | 7962624 |
| latency | current SafeZone | 11.478 | 0.000 | 0.000 | 0 | 916 | 1208 | 6834 | 18041 | 0 | 8093696 |
| latency | improved SafeZone | 11.827 | 0.000 | 0.000 | 0 | 958 | 1125 | 1333 | 13375 | 0 | 8060928 |
| latency | Rift HPZone | 10.340 | 0.000 | 0.526 | 499789 | 875 | 1000 | 1333 | 15084 | 0 | 8093696 |
| latency | Rift Streaming | 10.334 | 0.000 | 0.172 | 499789 | 792 | 958 | 3042 | 45542 | 0 | 8060928 |

Interpretation:

- The default run already shows the StreamFlex-style axis: heap reports GC time
  and four latency deadline misses; SafeZone and Rift remove those misses on
  this local configuration.
- Rift is faster than heap for throughput and close to heap for the latency
  workload while keeping measured GC at zero.
- Improved SafeZone is not universally faster than current SafeZone in this
  harness; keep both rows rather than treating improved SafeZone as a monotonic
  replacement.
- Rift operation time remains small at this scale.

## Allocation-Pressure 3-Run Median

Configuration:

- `STREAMFLEX_EVENTS=1000000`
- `STREAMFLEX_BATCH_SIZE=256`
- `STREAMFLEX_OBJECTS_PER_EVENT=8`
- `STREAMFLEX_LATENCY_EVENTS=50000`
- `STREAMFLEX_LATENCY_OBJECTS_PER_EVENT=64`
- `STREAMFLEX_PERIOD_NS=80000`
- `STREAMFLEX_BENCHMARK_RUNS=3`
- `STREAMFLEX_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | p50 ns | p99 ns | p999 ns | Max ns | Deadline misses | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| throughput | heap | 441.436 | 108.755 | 0.000 | 0 |  |  |  |  |  | 7962624 |
| throughput | current SafeZone | 524.746 | 0.000 | 0.000 | 0 |  |  |  |  |  | 8208384 |
| throughput | improved SafeZone | 406.979 | 0.000 | 0.000 | 0 |  |  |  |  |  | 8192000 |
| throughput | Rift HPZone | 385.368 | 0.000 | 1.284 | 25000266 |  |  |  |  |  | 8192000 |
| throughput | Rift Streaming | 381.738 | 0.000 | 0.851 | 25000266 |  |  |  |  |  | 8175616 |
| latency | heap | 174.279 | 30.379 | 0.000 | 0 | 2666 | 3375 | 303542 | 477209 | 89 | 7962624 |
| latency | current SafeZone | 175.123 | 0.772 | 0.000 | 0 | 3250 | 4208 | 8292 | 251792 | 1 | 8208384 |
| latency | improved SafeZone | 180.706 | 0.831 | 0.000 | 0 | 3292 | 4125 | 13625 | 512042 | 5 | 8192000 |
| latency | Rift HPZone | 165.043 | 0.507 | 2.669 | 10000396 | 3042 | 3875 | 9125 | 30667 | 0 | 8192000 |
| latency | Rift Streaming | 158.773 | 0.472 | 0.829 | 10000396 | 2917 | 3750 | 9542 | 34500 | 0 | 8175616 |

Interpretation:

- This pressure run is the current local StreamFlex-style signal: heap spends
  `108.755 ms` in GC on throughput and has `89` deadline misses on the latency
  workload.
- Rift removes the heap GC path for stream data objects and has zero deadline
  misses in both HPZone and Streaming modes.
- Streaming has lower region-op time than HPZone in the latency workload
  because it opens once and resets across events.
- SafeZone removes almost all GC time but still has occasional latency misses
  in this run and is slower than Rift on the pressure throughput workload.

## Direct Checked Epoch Follow-Up

Date/time: 2026-05-08 20:58 CEST.

This follow-up applies the reusable `RiftRegion.epoch { ... }` topology to the
StreamFlex-shaped pipeline. It keeps the same logical four-stage program as
heap/trusted Rift:

- packet fragments are allocated first;
- decoded records are derived by traversing packets;
- classified records are derived by traversing decoded records;
- alerts are derived and then traversed for the checksum.

The difference from the older checked rows is topology. Stacked
`EpochBuffer`s used four child regions per batch/event, and
`TransactionRegion` used one child region with four framework-owned lists.
The new direct checked epoch row uses one checked epoch block and ordinary
region-owned linked Scala objects. That makes it the StreamFlex analogue of the
direct epoch topology already used for Yak and Dataflow.

Smoke command:

```sh
STREAMFLEX_EVENTS=20000 \
STREAMFLEX_LATENCY_EVENTS=1000 \
STREAMFLEX_BENCHMARK_RUNS=1 \
STREAMFLEX_WARMUPS=0 \
STREAMFLEX_MODES="heap rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-direct-epoch-smoke-2026-05-08 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

Default 200k throughput + 10k latency command:

```sh
STREAMFLEX_BUILD=0 \
STREAMFLEX_WORKLOAD=all \
STREAMFLEX_EVENTS=200000 \
STREAMFLEX_LATENCY_EVENTS=10000 \
STREAMFLEX_BENCHMARK_RUNS=3 \
STREAMFLEX_WARMUPS=1 \
STREAMFLEX_MODES="heap improved-safezone rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/Users/siyaoliu/rift/cache/streamflex-direct-epoch-200k-2026-05-08 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

1M throughput command:

```sh
STREAMFLEX_BUILD=0 \
STREAMFLEX_WORKLOAD=throughput \
STREAMFLEX_EVENTS=1000000 \
STREAMFLEX_BENCHMARK_RUNS=3 \
STREAMFLEX_WARMUPS=1 \
STREAMFLEX_MODES="heap improved-safezone rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch rift-checked-transaction-region rift-checked-safezone-transaction-region" \
STREAMFLEX_OUTPUT_DIR=/Users/siyaoliu/rift/cache/streamflex-direct-epoch-throughput-1m-2026-05-08 \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

20k smoke rows:

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Deadline misses | Peak RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| throughput | heap | 4.181 | 0.790 | 0.000 | 0 |  | 8011776 | 334131456973800 |
| throughput | Rift Streaming | 3.648 | 0.000 | 0.021 | 249937 |  | 8142848 | 334131456973800 |
| throughput | checked direct epoch | 3.631 | 0.000 | 0.023 | 249937 |  | 8126464 | 334131456973800 |
| throughput | scoped checked direct epoch | 3.353 | 0.000 | 0.000 | 0 |  | 8159232 | 334131456973800 |
| throughput | checked TransactionRegion | 4.616 | 0.000 | 0.036 | 249937 |  | 8175616 | 334131456973800 |
| throughput | scoped checked TransactionRegion | 4.155 | 0.000 | 0.000 | 0 |  | 8175616 | 334131456973800 |
| latency | heap | 0.836 | 0.000 | 0.000 | 0 | 0 | 8011776 | 62885581450470 |
| latency | Rift Streaming | 0.961 | 0.000 | 0.025 | 49910 | 0 | 8142848 | 62885581450470 |
| latency | checked direct epoch | 1.137 | 0.000 | 0.028 | 49910 | 0 | 8126464 | 62885581450470 |
| latency | scoped checked direct epoch | 1.897 | 0.000 | 0.000 | 0 | 1 | 8159232 | 62885581450470 |
| latency | checked TransactionRegion | 1.553 | 0.000 | 0.085 | 49910 | 0 | 8175616 | 62885581450470 |
| latency | scoped checked TransactionRegion | 1.368 | 0.000 | 0.000 | 0 | 0 | 8175616 | 62885581450470 |

200k throughput and 10k latency rows:

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | p99 ns | p999 ns | Max ns | Deadline misses | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| throughput | heap | 46.737 | 9.294 | 0.000 | 0 |  |  |  |  | 8011776 |
| throughput | improved SafeZone | 59.703 | 0.000 | 0.000 | 0 |  |  |  |  | 8110080 |
| throughput | Rift Streaming | 47.897 | 0.000 | 0.174 | 2499843 |  |  |  |  | 8142848 |
| throughput | checked direct epoch | 34.874 | 0.000 | 0.128 | 2499843 |  |  |  |  | 8142848 |
| throughput | scoped checked direct epoch | 32.029 | 0.000 | 0.000 | 0 |  |  |  |  | 8126464 |
| throughput | checked TransactionRegion | 45.923 | 0.000 | 0.195 | 2499843 |  |  |  |  | 8175616 |
| throughput | scoped checked TransactionRegion | 41.173 | 0.000 | 0.000 | 0 |  |  |  |  | 8175616 |
| latency | heap | 11.219 | 1.657 | 0.000 | 0 | 958 | 10875 | 387084 | 4 | 8011776 |
| latency | improved SafeZone | 11.307 | 0.000 | 0.000 | 0 | 1209 | 5208 | 18125 | 0 | 8110080 |
| latency | Rift Streaming | 9.901 | 0.000 | 0.240 | 499789 | 1000 | 1125 | 3500 | 0 | 8142848 |
| latency | checked direct epoch | 10.692 | 0.000 | 0.244 | 499789 | 1125 | 8666 | 65625 | 0 | 8142848 |
| latency | scoped checked direct epoch | 9.636 | 0.000 | 0.000 | 0 | 1042 | 7000 | 23000 | 0 | 8126464 |
| latency | checked TransactionRegion | 17.040 | 0.314 | 0.896 | 499789 | 2584 | 18584 | 317042 | 2 | 8175616 |
| latency | scoped checked TransactionRegion | 13.511 | 0.310 | 0.000 | 0 | 1459 | 3500 | 312750 | 1 | 8175616 |

1M throughput rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Region opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---|---:|---:|
| heap | 218.582 | 44.681 | 0.000 | 0 | 0 / 0 / 0 | 8011776 | 16677201917717579 |
| improved SafeZone | 208.653 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8142848 | 16677201917717579 |
| Rift Streaming | 182.246 | 0.000 | 0.604 | 12500758 | 1 / 1 / 3906 | 8126464 | 16677201917717579 |
| checked direct epoch | 185.550 | 0.000 | 0.636 | 12500758 | 1 / 1 / 3907 | 8126464 | 16677201917717579 |
| scoped checked direct epoch | 163.339 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8126464 | 16677201917717579 |
| checked TransactionRegion | 226.059 | 0.000 | 1.001 | 12500758 | 3908 / 3908 / 0 | 8175616 | 16677201917717579 |
| scoped checked TransactionRegion | 205.929 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8159232 | 16677201917717579 |

Interpretation:

- Direct checked epoch supersedes `TransactionRegion` for this StreamFlex-shaped
  throughput row. At 1M events, scoped direct epoch is `163.339 ms`, versus
  heap `218.582 ms`, improved SafeZone `208.653 ms`, trusted Streaming
  `182.246 ms`, and scoped checked TransactionRegion `205.929 ms`.
- The Stream backend direct epoch is also competitive with trusted Streaming:
  `185.550 ms` versus `182.246 ms`, with similar region object counts and
  near-zero region-operation time.
- Latency at 10k events also improves versus `TransactionRegion`: scoped
  direct epoch is fastest in this same-run latency matrix (`9.636 ms`) with no
  deadline misses, but this should be treated as methodology evidence rather
  than exact StreamFlex/Ovm evidence.
- The result reinforces the topology rule: when all temporary stage objects die
  at the same batch/epoch boundary, a direct checked epoch block is the right
  reusable API. `TransactionRegion` remains useful when code needs separate
  typed append lists that cannot be consumed as direct linked objects.

Caveats:

- The benchmark does not implement StreamIt BeamFormer/FilterBank or the exact
  Ovm/StreamFlex runtime. It is a local reproduction of the comparison axes:
  throughput, per-event latency distribution, deadline misses, and GC pressure.
- The latency metric measures per-event processing duration, not full
  scheduler/queuing delay in a periodic real-time runtime.
- The harness uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future safe capture-checked API.

## Post Allocation-Counter Fix Pressure Verification

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMFLEX_BENCHMARK_RUNS=3 STREAMFLEX_WARMUPS=1 \
STREAMFLEX_EVENTS=1000000 STREAMFLEX_OBJECTS_PER_EVENT=8 \
STREAMFLEX_LATENCY_EVENTS=50000 STREAMFLEX_LATENCY_OBJECTS_PER_EVENT=64 \
STREAMFLEX_PERIOD_NS=80000 \
STREAMFLEX_OUTPUT_DIR=/tmp/streamflex-region-pressure-fixed \
  zsh sandbox/run_streamflex_region_instrumented_matrix.sh
```

This rerun was taken after moving Rift allocation counters out of the
per-object atomic hot path and flushing counts at region reset/close.

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | p99 ns | p999 ns | Max ns | Deadline misses | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| throughput | heap | 634.472 | 141.804 | 0.000 |  |  |  |  | 7962624 |
| throughput | current SafeZone | 409.749 | 0.000 | 0.000 |  |  |  |  | 8192000 |
| throughput | improved SafeZone | 412.827 | 0.000 | 0.000 |  |  |  |  | 8192000 |
| throughput | Rift HPZone | 331.740 | 0.000 | 1.237 |  |  |  |  | 8192000 |
| throughput | Rift Streaming | 329.896 | 0.000 | 0.835 |  |  |  |  | 12615680 |
| latency | heap | 169.331 | 29.931 | 0.000 | 2792 | 327625 | 467958 | 89 | 7962624 |
| latency | current SafeZone | 174.989 | 0.757 | 0.000 | 3542 | 8166 | 299042 | 1 | 8192000 |
| latency | improved SafeZone | 181.317 | 0.827 | 0.000 | 4250 | 13625 | 310084 | 6 | 8192000 |
| latency | Rift HPZone | 143.955 | 0.525 | 2.927 | 3250 | 3625 | 247500 | 1 | 8192000 |
| latency | Rift Streaming | 157.792 | 0.496 | 1.106 | 3541 | 6917 | 28209 | 0 | 12615680 |

Interpretation:

- This is now the strongest StreamFlex-style result: Rift throughput is about
  `330 ms` versus heap `634 ms`, and faster than both SafeZone modes.
- Streaming keeps zero deadline misses and a much smaller max latency than heap.
- HPZone has one deadline miss in this rerun, so the safest latency claim is
  about Streaming.
