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
  throughput claim. The robust finding so far is that GC time is similar across
  modes because ranking, median, parser, and output paths remain heap-heavy.
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
- Q1 ranking state remains heap-based in all modes. Q2 was still heap-only at
  this point; the next section records the Q2 window-backend conversion.

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

## Fairness Pivot: Shared Q2 Window Entries

Date: 2026-04-24

Motivation:

- Q2 previously used only heap queues for profit and empty-taxi windows, even
  in Rift modes.
- The fair Phase 5 comparison needs the same Q2 algorithm in heap and Rift
  modes, with only allocation placement and bucket close policy changing.

Change:

- `Q2Heap` and `Q2RiftWindows` now share one bucketed-window implementation.
- Heap mode allocates `ProfitEntry` and `EmptyEntry` with ordinary `new`.
- Rift modes allocate the same entry classes with `region.alloc` into
  per-timestamp regions and close those regions when each 15-minute profit
  bucket or 30-minute empty-taxi bucket expires.
- Region entries store primitive cell and taxi keys, not heap `Cell` or
  `String` references. This preserves the no region-to-GC ownership rule.
- Q2 ranking, `ProfitStats` control metadata, median sorting arrays,
  `ProfitableArea` outputs, parser strings, latency arrays, and output
  formatting remain heap-based.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q2Smoke` compiled and ran successfully in `heap`, `rift-hp`, and
  `rift-streaming` modes.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Shared Q2 Windows

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-shared-windows-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1837.501 | 54421.745 | 8 | 90.119 | 0.000 | 0 | 0 / 0 | 0 | 289832960 |
| Rift HPZone | 1794.929 | 55712.509 | 8 | 80.709 | 2.323 | 294284 | 4545 / 4545 | 2555904 | 292372480 |
| Rift Streaming | 1843.627 | 54240.908 | 8 | 85.730 | 2.479 | 294284 | 4545 / 4545 | 2555904 | 292388864 |

Interpretation:

- The intended allocation-placement variable is now active for Q1 and Q2
  window entries on the 100k sample.
- Rift modes allocate about `294k` region objects, compared with about `98k`
  when only Q1 bucket entries were region-backed.
- This still is not Phase 5 success. GC time remains similar because Q2
  ranking/median state, parser strings, output arrays, and formatting still
  allocate on the heap.
- The single-run ordering is not stable enough for a performance claim. This
  rerun has HPZone slightly faster than heap while Streaming is slightly
  slower; the previous run had the opposite ordering.

## Q2 Profit State: Region Entries As Source Of Truth

Date: 2026-04-24

Motivation:

- After the shared Q2 window change, Rift allocated Q2 window entries in
  regions, but each active profit was still duplicated in a heap
  `ArrayBuffer[Double]` inside `ProfitStats`.
- That violated the intended data/control split: the actual stream data should
  live in the window entries, while heap state should be limited to indexes and
  scalar metadata when possible.

Change:

- `ProfitStats` now keeps only heap control metadata: list head, count, dirty
  bit, and cached median.
- Active profit values live in `ProfitEntry` objects. Heap mode allocates those
  entries on the GC heap; Rift modes allocate the same entries in per-timestamp
  regions.
- `ProfitEntry` objects are linked into per-cell lists and unlinked before
  their bucket region is closed.
- Median recomputation still allocated a temporary heap `Array[Double]` for
  sorting at this point. The next section records the scratch-region variant.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q2Smoke` passed in `heap`, `rift-hp`, and `rift-streaming` modes.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Region-Backed Q2 Profit Values

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-region-profit-values-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1816.773 | 55042.642 | 8 | 88.809 | 0.000 | 0 | 0 / 0 | 0 | 289816576 |
| Rift HPZone | 1791.195 | 55828.658 | 8 | 82.747 | 2.421 | 294284 | 4545 / 4545 | 2555904 | 292372480 |
| Rift Streaming | 1802.942 | 55464.905 | 8 | 83.438 | 2.676 | 294284 | 4545 / 4545 | 2555904 | 292356096 |

Interpretation:

- Q2 active profit values are no longer duplicated into a heap `ArrayBuffer`.
- Region allocation counts are unchanged because the values already lived in
  the same `ProfitEntry` objects; the change removes a heap-side copy and makes
  the region entries the source of truth for Q2 profit data.
- This still is not Phase 5 success. Median scratch arrays, ranking/output
  objects, parser strings, latency arrays, and collection metadata remain
  heap-based.

## Q2 Median Scratch Arrays In Rift Scratch Region

Date: 2026-04-24

Motivation:

- Q2 active profit values live in window entries, but median recomputation
  still copied those values into a heap `Array[Double]` for sorting.
- For the streaming data path, that temporary sort buffer is manipulated data
  with a single-call lifetime, so Rift modes should allocate it in a scoped or
  streaming scratch region.

Change:

- `Q2BucketedWindow` now opens one median scratch region in Rift modes.
- `ProfitStats.medianProfit` allocates the temporary `Array[Double]` in that
  scratch region for Rift modes, sorts it, caches the scalar median, and resets
  the scratch region immediately.
- Heap mode still uses an ordinary heap `Array[Double]` through the same median
  logic.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q2Smoke` passed in `heap`, `rift-hp`, and `rift-streaming` modes.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Region-Backed Q2 Median Scratch

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-region-median-scratch-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift resets | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1881.304 | 53154.619 | 8 | 89.754 | 0.000 | 0 | 0 | 0 / 0 | 0 | 289767424 |
| Rift HPZone | 1920.875 | 52059.615 | 8 | 83.657 | 52.162 | 465481 | 171197 | 4546 / 4546 | 2588672 | 292405248 |
| Rift Streaming | 1937.911 | 51601.955 | 8 | 83.435 | 52.375 | 465481 | 171197 | 4546 / 4546 | 2588672 | 292388864 |

Interpretation:

- The temporary Q2 median sort buffers are now region-allocated in Rift modes.
- This moves more manipulated stream data out of the GC heap, but the current
  implementation resets the scratch region for every median recomputation.
  That adds about `171k` resets and about `52 ms` of Rift region-operation time
  on the 100k sample.
- This is still not Phase 5 success. It is a useful control point showing that
  region placement alone is not enough; scratch-region use must be batched or
  reused at the operator/event level to avoid high reset counts.
- Remaining heap-heavy data paths include Q1/Q2 ranking objects, output arrays,
  parser/input strings, latency arrays, and collection metadata.

## RunBoth Phase Breakdown And Q2 Scratch Batching

Date: 2026-04-24

Motivation:

- The 100k DEBS runs showed similar GC time across heap and Rift, and Rift
  region-operation time was also small relative to total elapsed time.
- The next question was where the elapsed time actually goes, so
  `Debs2015RunBoth` now reports coarse phase timers for input read, parse, Q1
  process/output, Q2 process/output, close, tracked, and untracked time.

Change:

- `Debs2015RunBothRunner` accumulates phase timings around the existing shared
  logical program.
- `run_both_instrumented_matrix.sh` records the new `phase_*` metrics.
- Q2 median scratch arrays remain region-backed in Rift modes, but the scratch
  region is reset once per processed trip instead of once per dirty-cell median
  recomputation.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q2Smoke` passed in `heap`, `rift-hp`, and `rift-streaming`.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Phase Breakdown Before Scratch Batching

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-phase-breakdown-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Read % | Parse % | Q1 process % | Q1 output % | Q2 process % | Q2 output % | GC % | Rift op % | Rift resets |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1857.565 | 15.8 | 7.0 | 13.2 | 4.3 | 48.4 | 9.2 | 4.8 | 0.0 | 0 |
| Rift HPZone | 1897.927 | 15.4 | 6.9 | 14.5 | 3.3 | 48.2 | 9.5 | 4.5 | 2.7 | 171197 |
| Rift Streaming | 1906.186 | 15.3 | 6.8 | 13.8 | 3.4 | 49.6 | 9.1 | 4.5 | 2.7 | 171197 |

Interpretation:

- Q2 processing is the dominant measured phase at roughly `48-50%` of elapsed
  time.
- Read plus parse is about `22%`; Q2 output is about `9%`.
- GC time is about `4.5-4.8%`, and Rift region operations are about `2.7%`
  before scratch batching. Therefore neither GC nor Rift bookkeeping alone
  explains total elapsed time.

### 100k Instrumented After Per-Trip Scratch Reset

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-scratch-per-trip-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Q2 process ms | GC ms | Rift op ms | Rift reset ms | Rift resets | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1748.744 | 803.373 | 84.364 | 0.000 | 0.000 | 0 | 0 | 289816576 |
| Rift HPZone | 1775.350 | 823.036 | 79.702 | 28.004 | 25.860 | 87438 | 465481 | 292421632 |
| Rift Streaming | 1768.716 | 843.495 | 78.562 | 27.830 | 25.746 | 87438 | 465481 | 292438016 |

Interpretation:

- Batching scratch reset at the trip/operator boundary preserved output
  equality and cut resets from `171197` to `87438`.
- Rift region-operation time dropped from about `51.5 ms` to about `28 ms`.
- Q2 processing remains the dominant phase. This means further Phase 5 work
  should target Q2 data structures and parser/output allocation boundaries, not
  only raw allocator tuning.

## Q2 Primitive Cell Keys In Ranking Path

Date: 2026-04-24

Motivation:

- Q2 ranking still allocated `Cell` objects and formatted cell-id strings in
  the hot comparator/output path.
- That is accidental heap noise, not part of the logical query. The data path
  should keep packed primitive cell keys internally and format IDs only at the
  output boundary.

Change:

- `ProfitableArea` now stores `cellKey: Int` internally and exposes `cell` only
  as a compatibility accessor.
- Q2 ranking tie-breaking compares packed cell keys with a no-allocation
  decimal-lexicographic comparator equivalent to comparing `"east.south"`.
- `Q2Output` compares `cellKey` and appends cell IDs directly into its
  `StringBuilder`.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

zsh bench/debs2015/run_both_sample_matrix.sh
```

Results:

- `Debs2015Q2Smoke` passed in `heap`, `rift-hp`, and `rift-streaming`.
- RunBoth sample matrix outputs match across heap, Rift HPZone, and Rift
  Streaming.

### 100k Instrumented After Primitive Q2 Ranking Keys

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-primitive-rank-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Q2 process ms | Q2 output ms | GC ms | Rift op ms | Rift resets | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1813.074 | 880.566 | 155.863 | 90.540 | 0.000 | 0 | 0 | 289816576 |
| Rift HPZone | 1863.806 | 909.965 | 160.252 | 84.775 | 28.815 | 87438 | 465481 | 292421632 |
| Rift Streaming | 1832.249 | 895.328 | 156.544 | 82.728 | 28.681 | 87438 | 465481 | 292405248 |

Interpretation:

- The primitive-key change is a fairness/noise cleanup and boundary tightening,
  not by itself a Rift-specific win.
- This single run does not show a stable elapsed-time improvement; variance is
  visible across adjacent 100k runs. It should be kept because it removes
  accidental heap allocation from the shared Q2 hot path and makes the
  region/heap boundary cleaner.
- The remaining highest-value work is a measured parser/taxi-id boundary and a
  Q2 median/ranking data-structure diagnosis. Full Phase 5 claims still require
  medians and larger inputs.
