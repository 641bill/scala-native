# DEBS 2015 Results

Date: 2026-04-23

This file records DEBS-specific runs. These are application-harness results,
not final Phase 5 headline numbers yet.

## Input Preparation

Downloaded inputs are present at:

- `/Users/siyaoliu/rift/trip_data`
- `/Users/siyaoliu/rift/trip_fare`

The files use the original split NYC taxi layout. The first 100000 rows of
January were checked and are row-aligned between `trip_data_1.csv` and
`trip_fare_1.csv`.

The raw monthly files are not monotonic by dropoff timestamp. A full DEBS
sliding-window run therefore needs a joined stream sorted by dropoff time. For
bounded samples, use:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_LIMIT=10000 DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-10000.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh
```

## Real-Data 10k RunBoth Smoke

Input:

- month: `1`
- rows: `10000`
- joined output: `/tmp/debs2015-month1-10000.csv`
- sorted by dropoff timestamp

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-10000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-month1-10000 \
  zsh bench/debs2015/run_both_sample_matrix.sh
```

Result:

- heap, Rift HPZone, and Rift Streaming outputs match after stripping only the
  final measured-latency column
- `q1_outputs=3941`
- `q2_outputs=1157`

| Q1 mode | Events | Parsed | Elapsed ms | Throughput events/s | Q1 p99.9 ms | Q1 max ms | Q2 p99.9 ms | Q2 max ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 10000 | 10000 | 243.575 | 41055.173 | 0 | 3 | 1 | 3 |
| Rift HPZone | 10000 | 10000 | 251.410 | 39775.738 | 0 | 0 | 1 | 8 |
| Rift Streaming | 10000 | 10000 | 241.298 | 41442.582 | 0 | 0 | 1 | 3 |

## Real-Data 100k RunBoth

Input:

- month: `1`
- rows: `100000`
- joined output: `/tmp/debs2015-month1-100000.csv`
- sorted by dropoff timestamp

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_LIMIT=100000 DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-100000.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-month1-100000 \
  zsh bench/debs2015/run_both_sample_matrix.sh
```

The first 100k attempt used full rescans/sorts for Q1/Q2 ranking and the heap
mode alone took `246360.864 ms` (`405.909 events/s`). After replacing Q1 and Q2
ranking with incremental ordered sets, all modes completed and matched.

| Q1 mode | Events | Parsed | Elapsed ms | Throughput events/s | Q1 p99.9 ms | Q1 max ms | Q2 p99.9 ms | Q2 max ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 100000 | 100000 | 2087.796 | 47897.405 | 0 | 0 | 1 | 1 |
| Rift HPZone | 100000 | 100000 | 2128.833 | 46974.086 | 0 | 3 | 1 | 10 |
| Rift Streaming | 100000 | 100000 | 2131.800 | 46908.722 | 0 | 3 | 1 | 1 |

## Real-Data 1M RunBoth

Input:

- month: `1`
- rows: `1000000`
- joined output: `/tmp/debs2015-month1-1000000.csv`
- sorted by dropoff timestamp
- joined file size: `176 MB`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_LIMIT=1000000 DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-1000000.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-month1-1000000 \
  zsh bench/debs2015/run_both_sample_matrix.sh
```

Result:

- heap, Rift HPZone, and Rift Streaming outputs match after stripping only the
  final measured-latency column
- `q1_outputs=32209`
- `q2_outputs=24969`

| Q1 mode | Events | Parsed | Elapsed ms | Throughput events/s | Q1 p99.9 ms | Q1 max ms | Q2 p99.9 ms | Q2 max ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1000000 | 1000000 | 21658.215 | 46171.857 | 0 | 92 | 2 | 69 |
| Rift HPZone | 1000000 | 1000000 | 22314.989 | 44812.928 | 0 | 1 | 2 | 37 |
| Rift Streaming | 1000000 | 1000000 | 22184.467 | 45076.585 | 0 | 45 | 2 | 94 |

## Instrumented Real-Data RunBoth

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-instrumented-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The instrumented runner executes the native binary directly, captures peak RSS
with `/usr/bin/time -l`, and emits `summary.tsv`. These rows are single-run
instrumented measurements, not final medians.

### 100k Instrumented

Output directory:

- `/tmp/debs2015-runboth-instrumented-100000`

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2018.887 | 49532.241 | 8 | 86.696 | 0.000 | 0 | 0 / 0 | 0 | 348684288 |
| Rift HPZone | 2021.861 | 49459.394 | 8 | 83.432 | 1.036 | 98005 | 1513 / 1513 | 1015808 | 343670784 |
| Rift Streaming | 2018.424 | 49543.610 | 8 | 83.035 | 1.004 | 98005 | 1513 / 1513 | 1015808 | 343638016 |

### 1M Instrumented

Output directory:

- `/tmp/debs2015-runboth-instrumented-1000000`

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22827.882 | 43806.080 | 21 | 1051.752 | 0.000 | 0 | 0 / 0 | 0 | 1148780544 |
| Rift HPZone | 22366.285 | 44710.151 | 21 | 1003.171 | 9.350 | 979699 | 10730 / 10730 | 3342336 | 1152122880 |
| Rift Streaming | 22810.687 | 43839.101 | 21 | 1012.489 | 10.390 | 979699 | 10730 / 10730 | 3342336 | 1152122880 |

Interpretation:

- The real-data 1M run spends about `1s` in GC across all modes; current Q1 Rift
  work does not reduce GC time because Q2 and the surrounding application state
  remain heap-based.
- Rift HPZone's measured region operation time is only `9.350 ms` on the 1M
  run. The current region path is therefore not the dominant Phase 5 bottleneck.
- Both Rift modes allocate about `980k` Q1 entries through the region path with
  zero slow allocations. Region churn is mostly bucket open/close and slab reuse.
- The current-binary 1M single run puts Rift HPZone about `2%` faster than heap
  and Rift Streaming at near parity. Earlier single runs had the opposite
  ordering, so median reruns are required before making a headline throughput
  claim.

## Implementation Notes

- An earlier Rift run crashed because `Q1RiftBuckets` stored heap `Route`
  objects inside region entries. If a logically equal route was already present
  in the heap map, the new `Route` object could become reachable only from
  unscanned region memory. The current implementation stores a packed `Long`
  route key in region entries instead.
- The first real-data 100k run showed that full Q1/Q2 rescans were the dominant
  cost. Q1 now keeps an incremental route ranking set, and Q2 keeps an
  incremental profitable-area ranking set plus cached per-cell medians.
- On the 1M sample, single-run ordering is not stable enough for a headline
  throughput claim. The robust finding so far is that Q2 is still heap-only,
  GC time is about `1s` in every mode, and measured Rift region-operation time
  is only about `9-10 ms`.
- These are still bounded-sample results. Full Phase 5 evidence needs larger
  samples, median instrumented reruns, and Commix/SafeZone comparison modes
  where meaningful.

## Parser Fast-Path And Reusable-Trip Reruns

Date: 2026-04-23

Changes:

- `Trip.parse` no longer uses `String.split(",", -1)` on the hot runner path.
- `Trip.parseOrNull` scans the CSV line once, materializes only the fields used
  by Q1/Q2, parses timestamps and numeric fields from slices, and avoids the
  per-row `Option` allocation in `Debs2015RunBoth`, `Debs2015Q1Run`, and
  `Debs2015Q2Run`.
- A follow-up change made `Trip` a reusable mutable event record in the hot
  file-backed runners. Smoke tests still use `Trip.parse`, which preserves the
  convenient `Option` API for non-hot validation code.
- This is an allocation-pressure reduction, not yet a region-heavy DEBS design:
  `Source.getLines`, the line `String`, taxi/timestamp strings, Q1/Q2 ranking
  objects, and output formatting are still heap-based.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Smoke` compiled and ran successfully.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Scanner Parser

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-parser-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1747.245 | 57232.949 | 8 | 82.372 | 0.000 | 0 | 0 / 0 | 0 | 289832960 |
| Rift HPZone | 1740.999 | 57438.279 | 8 | 79.988 | 0.931 | 98005 | 1513 / 1513 | 1015808 | 290881536 |
| Rift Streaming | 1737.477 | 57554.730 | 8 | 81.272 | 0.933 | 98005 | 1513 / 1513 | 1015808 | 290881536 |

### 1M Instrumented After Scanner Parser

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-parser-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 18705.857 | 53459.192 | 20 | 814.550 | 0.000 | 0 | 0 / 0 | 0 | 1148502016 |
| Rift HPZone | 18655.414 | 53603.742 | 19 | 737.705 | 6.452 | 979699 | 10730 / 10730 | 3342336 | 1123303424 |
| Rift Streaming | 18684.802 | 53519.433 | 19 | 731.286 | 6.255 | 979699 | 10730 / 10730 | 3342336 | 1121910784 |

### 100k Instrumented After Reusable Trip

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-reusable-trip-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1829.605 | 54656.601 | 8 | 83.608 | 0.000 | 0 | 0 / 0 | 0 | 289849344 |
| Rift HPZone | 1869.124 | 53501.013 | 8 | 87.922 | 1.207 | 98005 | 1513 / 1513 | 1015808 | 290897920 |
| Rift Streaming | 1836.948 | 54438.121 | 8 | 82.415 | 1.098 | 98005 | 1513 / 1513 | 1015808 | 290897920 |

### 1M Instrumented After Reusable Trip

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-reusable-trip-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 18744.558 | 53348.818 | 19 | 758.444 | 0.000 | 0 | 0 / 0 | 0 | 1056587776 |
| Rift HPZone | 18533.989 | 53954.926 | 19 | 738.969 | 6.034 | 979699 | 10730 / 10730 | 3342336 | 1012613120 |
| Rift Streaming | 18662.839 | 53582.417 | 18 | 779.228 | 5.923 | 979699 | 10730 / 10730 | 3342336 | 1151827968 |

Interpretation:

- The scanner parser is a real application-level improvement, but not a Rift
  proof by itself. It improves all modes because it removes general heap churn
  from the shared input path.
- Compared with the earlier 1M instrumented rows in this file, elapsed time
  moved from `22827.882 ms` to the `18.7s` band for heap and from
  `22366.285 ms` to the `18.5-18.7s` band for Rift HPZone.
- GC time also dropped into the `0.73-0.82s` band on the 1M reruns, compared
  with roughly `1.0s` before the parser work.
- Reusing `Trip` did not produce a stable elapsed-time improvement in these
  single runs, but the 1M heap and Rift HPZone rows show lower peak RSS than the
  scanner-only rows. Treat this as provisional until medians are run.
- The dominant remaining work is still Q2 and ranking/output heap allocation.
  Median reruns are required before any headline claim.

## Fairness Pivot: Shared Q1 Bucketed Window

Date: 2026-04-23

Motivation:

- The Phase 5 question is not whether the benchmark can be hand-optimized.
  The benchmark should compare heap versus Rift on the same logical program,
  with allocation placement and lifetime management as the intended variable.
- Q1 previously had divergent window implementations: heap used a per-entry
  heap queue, while Rift used per-timestamp region buckets.

Change:

- `Q1Heap` and `Q1RiftBuckets` now share one bucketed-window implementation.
- Heap mode allocates the same `BucketWindowEntry` class using ordinary `new`.
- Rift modes allocate `BucketWindowEntry` with `region.alloc` into a
  per-timestamp region and close that region when the bucket leaves the
  30-minute window.
- Q1 ranking state remains heap-based in all modes. Q2 remains heap-only.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q1Smoke` compiled and ran successfully.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Shared Q1 Buckets

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q1-shared-buckets-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1860.007 | 53763.236 | 8 | 85.489 | 0.000 | 0 | 0 / 0 | 0 | 289849344 |
| Rift HPZone | 1837.997 | 54407.064 | 8 | 82.502 | 1.111 | 98005 | 1513 / 1513 | 1015808 | 290881536 |
| Rift Streaming | 1833.573 | 54538.332 | 8 | 81.977 | 1.090 | 98005 | 1513 / 1513 | 1015808 | 290881536 |

Interpretation:

- This is a fairer Q1 shape than the previous divergent heap/Rift window
  implementations, but it is still not Phase 5 success.
- The result confirms the intended allocation placement: Rift modes allocate
  the Q1 window entries in regions and close 1513 per-timestamp regions on the
  100k sample.
- Q2, ranking state, parser line strings, taxi/timestamp strings, latency
  arrays, and output formatting remain heap-based. Full DEBS medians and a
  fair Q2 backend are still required before making an application-level claim.
