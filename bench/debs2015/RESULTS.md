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

## RunBoth Region-Backed Input Buffer And Byte Parser

Date: 2026-04-24

Motivation:

- The previous parser work removed `String.split`, per-row `Option`, and
  per-row `Trip` allocation, but `Source.getLines()` still allocated one heap
  `String` per input row.
- For the intended heap/Rift comparison, stream input bytes have a structured
  run/operator lifetime. Heap mode should use an ordinary heap buffer; Rift
  modes should be able to place the same buffer in region memory while keeping
  the Q1/Q2 logical program unchanged.

Change:

- `Debs2015RunBoth` now uses `CsvLineReader` instead of `Source.getLines()`.
- Heap mode allocates the CSV buffer as `new Array[Byte]`.
- Rift HPZone and Rift Streaming allocate the same CSV buffer with
  `region.alloc(new Array[Byte](...))` in a run-lifetime region closed at the
  end of the input scan.
- `Trip` can now parse either string slices or byte slices. RunBoth parses the
  reusable `Trip` directly from byte slices and avoids per-row line strings,
  timestamp substrings, and taxi-id substrings. Durable taxi IDs are still
  interned as heap metadata only when first seen.
- The first byte-parser 100k run produced `invalid=3`; those rows used
  scientific notation in coordinate fields. The byte parser was fixed to parse
  `E`/`e` exponents, after which the 100k and 1M runs parsed all rows.

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
- 100k and 1M instrumented matrices parsed all rows and output comparisons
  matched across modes.

### 100k Instrumented After Region-Backed Input Buffer

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-input-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Throughput events/s | Read ms | Parse ms | Q2 process ms | GC ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1557.171 | 64219.023 | 42.270 | 107.611 | 897.935 | 60.805 | 0.000 | 0 | 273645568 |
| Rift HPZone | 1703.600 | 58699.235 | 41.091 | 109.454 | 985.149 | 60.293 | 29.121 | 465482 | 238534656 |
| Rift Streaming | 1591.372 | 62838.873 | 40.476 | 109.614 | 910.766 | 59.293 | 28.482 | 465482 | 238665728 |

### 1M Instrumented After Region-Backed Input Buffer

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-input-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

These rows are single-run measurements, not medians.

| Mode | Elapsed ms | Throughput events/s | Read ms | Parse ms | Q2 process ms | GC ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 18692.484 | 53497.439 | 440.898 | 1126.119 | 11856.488 | 731.171 | 0.000 | 0 | 1147895808 |
| Rift HPZone | 17789.410 | 56213.218 | 423.321 | 1109.615 | 11192.911 | 644.394 | 291.402 | 4700055 | 1157398528 |
| Rift Streaming | 17280.431 | 57868.927 | 415.302 | 1100.768 | 11058.619 | 643.015 | 286.841 | 4700055 | 1157955584 |

Interpretation:

- This is the first DEBS input-path step where Rift places a stream data buffer
  in region memory. It is still not a final application-level claim because the
  rows are single-run measurements and Q2 ranking/metadata/output remain
  heap-heavy.
- The shared byte reader is a fair benchmark change: heap and Rift run the same
  parser and query code; the allocation-placement difference is the input
  buffer lifetime.
- Read plus parse is now roughly `9-10%` of 100k elapsed, down from about
  `22%` in the earlier 100k phase breakdown. The main remaining measured phase
  is Q2 processing: about `57-64%` of elapsed time in these runs.
- GC time is lower than the earlier line-string runs, but it is still similar
  across modes because ranking metadata, output formatting, latency arrays, and
  collection state are still heap-managed.
- The 1M single run shows Rift HPZone and Rift Streaming faster than heap, but
  this must be treated as provisional until median reruns confirm stability.

## Region-Backed Input Buffer Median Rerun

Date: 2026-04-24

Command shape:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
for run in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-input-median-100000/run-${run} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for run in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-input-median-1000000/run-${run} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

All runs parsed all rows and the heap/Rift outputs matched after stripping only
the measured latency column.

### 100k Median After Region-Backed Input Buffer

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1591.907 | 61.845 | 246.749 | 67.512 | 923.195 | 159.414 | 0.000 | 0 | 0 | 0 | 273661952 |
| Rift HPZone | 1621.480 | 59.031 | 244.521 | 80.168 | 950.705 | 155.630 | 29.024 | 465482 | 87438 | 3686400 | 238501888 |
| Rift Streaming | 1635.015 | 60.308 | 249.894 | 70.616 | 953.593 | 170.553 | 29.648 | 465482 | 87438 | 3686400 | 239173632 |

### 1M Median After Region-Backed Input Buffer

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 17047.611 | 684.338 | 2630.878 | 466.648 | 10741.201 | 1388.926 | 0.000 | 0 | 0 | 0 | 1148403712 |
| Rift HPZone | 17119.396 | 640.062 | 2784.165 | 441.880 | 10797.924 | 1306.058 | 288.815 | 4700055 | 894660 | 9519104 | 1157922816 |
| Rift Streaming | 17210.458 | 628.808 | 2654.853 | 441.438 | 11013.298 | 1205.141 | 288.920 | 4700055 | 894660 | 9519104 | 1157922816 |

Interpretation:

- The single-run apparent 1M Rift win after the byte-reader change did not hold
  as a median. At 1M, Rift reduces GC time by about `44-56 ms`, but region
  operation time is about `289 ms`, and elapsed time is near parity to slower.
- The robust finding is not "Rift is faster on DEBS." The robust finding is
  that the input/parser boundary is no longer the dominant allocation source,
  and Q2 process/output/ranking remain the next target.

## Superseded Region Ranking And Snapshot Output Experiment

Date: 2026-04-24

Changes:

- Q1 and Q2 ranking objects are now ordinary Scala objects allocated in a
  run-lifetime Rift region in Rift modes:
  - Q1: `RankedRoute`, `Route`, and `Cell` objects in the ranking set.
  - Q2: `ProfitableArea` objects in the ranking set.
- Heap mode still uses ordinary `new` through the same query algorithms.
- Result arrays were allocated in a resettable snapshot region in Rift modes.
  Runners kept only small heap primitive snapshots between outputs, so they did
  not retain region-backed arrays after the next `process` call.
- Q1/Q2 output now writes rows directly to the `Writer` and uses shared
  fixed-decimal formatting instead of hot `StringBuilder.toString` rows and Q2
  `f""` formatting. This is a shared noise reduction, not a Rift-only change.

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

- `Debs2015Q2Smoke` passed for `heap`, `rift-hp`, and `rift-streaming`.
- RunBoth sample matrix outputs matched across heap, Rift HPZone, and Rift
  Streaming.
- A first attempt used a run-lifetime region for result arrays as well as
  ranking objects. It reduced GC time but mmaped about `467 MB` at 1M and made
  Rift slower; that version is not the intended lifetime design.
- This version used a resettable snapshot region for result arrays. It avoided
  retaining every per-event result array, but reset overhead was too high.

### 100k Single Run With Snapshot Result Region

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-rank-output-snapshot-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1510.731 | 69.349 | 238.531 | 67.432 | 949.885 | 60.960 | 0.000 | 0 | 0 | 0 | 205881344 |
| Rift HPZone | 1582.878 | 61.587 | 298.642 | 63.732 | 974.285 | 52.494 | 92.276 | 1512592 | 287438 | 29081600 | 234897408 |
| Rift Streaming | 1646.102 | 61.395 | 301.537 | 77.975 | 1009.632 | 61.085 | 94.426 | 1512592 | 287438 | 29081600 | 234897408 |

### 1M Single Run With Snapshot Result Region

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-rank-output-snapshot-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 16363.012 | 601.615 | 2665.909 | 374.220 | 10966.219 | 428.833 | 0.000 | 0 | 0 | 0 | 1148436480 |
| Rift HPZone | 17243.387 | 503.198 | 3011.132 | 352.674 | 11537.931 | 416.090 | 933.640 | 15564420 | 2894660 | 275628032 | 1088684032 |
| Rift Streaming | 17301.238 | 529.115 | 3067.009 | 359.010 | 11500.769 | 445.956 | 936.641 | 15564420 | 2894660 | 275628032 | 1088618496 |

Interpretation:

- Moving ranking objects and result arrays into regions does reduce GC time:
  about `98 ms` at 1M for Rift HPZone in this single run.
- It also reduces peak RSS below heap in the snapshot-region 1M run, but not at
  100k.
- This was not a Phase 5 success. Region operations became the visible cost:
  about `934 ms` at 1M, dominated by nearly `2.9M` resets and snapshot/ranking
  allocation bookkeeping. Q1/Q2 process time also rises.
- This result is superseded by the lower-overhead reusable ranking/result-array
  backend below. Keep it as a diagnostic showing why reset-per-event result
  regions are a bad lifetime shape for DEBS.

## Reusable Ranking Arrays And Lazy-Zero Rift Backend

Date: 2026-04-24

Changes:

- Q1 `RankedRoute` and Q2 `ProfitableArea` are now mutable ranking nodes.
  Heap and Rift modes use the same `TreeSet` update algorithm: remove the node,
  mutate scalar fields, and reinsert it.
- Rift modes still allocate Q1 `RankedRoute`/`Route`/`Cell` and Q2
  `ProfitableArea` objects in run-lifetime regions, but top-k result arrays are
  now cached by exact size and reused instead of allocated in a resettable
  per-event snapshot region.
- Q2 median scratch is now a capacity-grown array reused for the operator
  lifetime. It is heap-allocated in heap mode and region-allocated in Rift
  modes.
- `RiftRuntime.c` no longer eagerly zeroes reused slabs on acquire or zeroes the
  retained first slab on reset. Reused slabs are marked non-zeroed, and managed
  object/array allocation already zeroes the exact object payload when needed.
  This preserves object initialization while avoiding slab-wide `memset` work
  for small lifetime scopes.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
      nativeLink

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "tests3/testOnly scala.scalanative.memory.RiftRegionTest"

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-lazy-reset-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-lazy-reset-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-lazy-reset-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Notes:

- The sample, 100k, and 1M instrumented matrices all matched heap output after
  stripping only the measured latency column.
- `/usr/bin/time -l` needs to run outside the macOS sandbox to collect RSS.
- A stale `Debs2015RunBoth` binary can silently preserve old reset behavior.
  After changing Q1/Q2 or the C runtime, rebuild the RunBoth native binary
  before benchmarking with `DEBS2015_BOTH_BUILD=0`.

### 100k 3-Run Median With Reusable Ranking Arrays

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1286.990 | 61.087 | 196.563 | 55.925 | 789.440 | 54.458 | 0.000 | 0 | 0 | 0 | 205848576 |
| Rift HPZone | 1262.091 | 35.098 | 193.392 | 63.255 | 773.109 | 61.605 | 1.430 | 561849 | 0 | 10141696 | 205094912 |
| Rift Streaming | 1265.775 | 35.855 | 194.045 | 64.477 | 767.458 | 63.398 | 1.420 | 561849 | 0 | 10141696 | 205111296 |

### 1M 3-Run Median With Reusable Ranking Arrays

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 14633.019 | 513.199 | 2192.382 | 367.014 | 9768.249 | 456.305 | 0.000 | 0 | 0 | 0 | 813105152 |
| Rift HPZone | 14234.470 | 457.726 | 2109.021 | 348.674 | 9538.889 | 426.264 | 13.003 | 5304781 | 0 | 67223552 | 876101632 |
| Rift Streaming | 14392.097 | 478.912 | 2198.905 | 377.039 | 9570.330 | 486.997 | 14.166 | 5304781 | 0 | 67223552 | 876101632 |

Interpretation:

- The unacceptable `~950 ms` Rift operation overhead from the snapshot-region
  experiment is gone in the current rebuilt binary. Median HPZone region
  operation time at 1M is `13.003 ms`; Streaming is `14.166 ms`.
- Rift HPZone is faster than heap in the 1M 3-run median and reduces median GC
  time by about `55 ms`. This is promising application-level evidence, but it
  is still not final because the dataset is bounded, Commix/SafeZone modes are
  missing, and RSS is higher.
- Peak RSS is higher for Rift in this run because run-lifetime ranking objects,
  cached result arrays, and region slabs stay resident until close. That is a
  tradeoff to measure, not a final success claim.
- The remaining Phase 5 question is no longer reset overhead. It is whether
  moving more dominant Q1/Q2 control and collection state into safe region
  structures can reduce GC materially without growing RSS or diverging from the
  heap logical program.

## Q2 Cell Tables For Bounded Control Metadata

Date: 2026-04-24

Changes:

- Q2 replaced four boxed `mutable.HashMap[Int, ...]` cell tables with fixed
  arrays indexed by the existing packed Q2 cell key:
  - active `ProfitStats`
  - empty-taxi counts
  - latest sequence per cell
  - current ranked `ProfitableArea`
- Heap and Rift modes use the same logical table layout. Heap allocates the
  arrays and `ProfitStats` with ordinary `new`; Rift modes allocate them in the
  run-lifetime ranking/control region.
- This is a bounded-domain control/data-structure change, not a
  hand-specialized Rift-only benchmark. It removes boxed map operations from
  both heap and Rift while preserving the allocation-placement split.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
      nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-celltables-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The sample, 100k, and 1M instrumented matrices all matched heap output after
stripping only the measured latency column.

### 100k 3-Run Median With Q2 Cell Tables

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1037.748 | 45.193 | 184.481 | 62.231 | 549.354 | 47.715 | 0.000 | 0 | 0 | 0 | 217137152 |
| Rift HPZone | 997.997 | 33.453 | 185.366 | 55.824 | 511.303 | 60.119 | 1.745 | 573277 | 0 | 27869184 | 172113920 |
| Rift Streaming | 983.928 | 32.947 | 177.921 | 55.415 | 515.019 | 48.587 | 1.349 | 573277 | 0 | 27869184 | 172097536 |

### 1M 3-Run Median With Q2 Cell Tables

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11471.085 | 478.636 | 1970.359 | 323.773 | 6978.174 | 379.032 | 0.000 | 0 | 0 | 0 | 609730560 |
| Rift HPZone | 11442.637 | 419.658 | 1965.570 | 341.229 | 6853.565 | 420.838 | 11.759 | 5386213 | 0 | 87703552 | 490766336 |
| Rift Streaming | 11477.431 | 428.339 | 1998.458 | 326.847 | 6895.311 | 398.340 | 11.421 | 5386213 | 0 | 87703552 | 490799104 |

Interpretation:

- The cell-table change is a large shared algorithm/data-structure improvement:
  compared with the reusable ranking-array median, 1M heap elapsed drops from
  `14633.019 ms` to `11471.085 ms`, and Rift HPZone drops from `14234.470 ms`
  to `11442.637 ms`.
- The Rift-vs-heap elapsed gap at 1M is now small, but HPZone still reduces
  median GC time by about `59 ms` and peak RSS by about `119 MB` on this
  bounded sample.
- Rift region-operation time remains small: `11.759 ms` for HPZone and
  `11.421 ms` for Streaming at 1M, with `0` resets.
- This strengthens the application story by moving bounded Q2 control tables
  into region-backed storage in Rift modes. It is still not final Phase 5
  success because SafeZone/Commix/full-month comparisons and safe API
  boundaries remain open.

## Q1 Primitive Route Table

Date: 2026-04-24

Changes:

- Q1 replaced `mutable.HashMap[Long, RouteState]` and
  `mutable.HashMap[Long, RankedRoute]` with a shared primitive open-addressed
  route table.
- Heap and Rift modes use the same route-table algorithm. Heap mode allocates
  the backing arrays with ordinary `new`; Rift modes allocate the same arrays
  in the run-lifetime ranking/control region.
- The table uses cheap tombstone deletion and lazy compaction near high
  occupancy. A backshift-deletion variant was rejected during this checkpoint
  because expiration deletes made deletion maintenance visible in the 100k
  run.
- Q1 still uses a heap `TreeSet` for ranking order. This patch moves boxed map
  state and `RouteState` churn out of the hot path; it does not finish the Q1
  control-metadata story.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Smoke\")" \
      run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
      nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q1table-lazycompact-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The sample, 100k, and 1M instrumented matrices all matched heap output after
stripping only the measured latency column.

### 100k 3-Run Median With Q1 Primitive Route Table

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1067.019 | 46.067 | 149.216 | 67.843 | 603.133 | 52.221 | 0.000 | 0 | 0 | 0 | 217038848 |
| Rift HPZone | 1031.787 | 31.820 | 143.182 | 70.204 | 563.016 | 50.947 | 1.911 | 573322 | 0 | 30572544 | 174833664 |
| Rift Streaming | 1026.118 | 32.090 | 142.173 | 62.371 | 571.077 | 50.884 | 1.877 | 573322 | 0 | 30572544 | 174833664 |

### 1M 3-Run Median With Q1 Primitive Route Table

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11957.721 | 463.555 | 1618.613 | 375.427 | 7628.967 | 406.993 | 0.000 | 0 | 0 | 0 | 433209344 |
| Rift HPZone | 11659.223 | 397.162 | 1627.696 | 380.382 | 7277.400 | 451.454 | 15.102 | 5386268 | 0 | 96747520 | 381222912 |
| Rift Streaming | 11739.195 | 398.268 | 1633.238 | 364.504 | 7374.528 | 422.975 | 15.164 | 5386268 | 0 | 96747520 | 381190144 |

Interpretation:

- This is a fair structural change: heap and Rift use the same primitive route
  table, and only allocation placement differs for the backing arrays.
- Q1 processing improves materially compared with the Q2-cell-table median
  (`1M` heap Q1 process `1970.359 ms` -> `1618.613 ms`; HPZone
  `1965.570 ms` -> `1627.696 ms`).
- Median GC and RSS improve versus the Q2-cell-table checkpoint, especially in
  Rift modes, but total elapsed is not a clean cross-checkpoint improvement
  because Q2 processing remains dominant and moved in the opposite direction in
  this run set.
- The valid claim is narrower: the patch removes boxed Q1 route-table state
  from the hot path and preserves low Rift operation time. It is not final
  Phase 5 success.

## Q2 Latest-Empty Taxi Table

Date: 2026-04-24

Changes:

- Q2 replaced `mutable.HashMap[Int, EmptyEntry]` for latest empty-taxi state
  with a growable dense array indexed by the existing `TaxiIds` integer key.
- Heap and Rift modes use the same logical structure. Heap mode allocates the
  array with ordinary `new`; Rift modes allocate the same array in the
  run-lifetime ranking/control region.
- Durable taxi-id string interning remains heap metadata. The array only tracks
  the latest active empty-window entry for each taxi.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
      nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_BINARY=/Users/siyaoliu/rift/scala-native-rift/sandbox/.3-next/target/scala-3.8.4-RC1-bin-20260402-44bbcdf-NIGHTLY/native/debs2015.Debs2015RunBoth \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2taxiarray-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The 100k and 1M instrumented matrices all matched heap output after stripping
only the measured latency column.

### 100k 3-Run Median With Q2 Latest-Empty Taxi Table

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1072.361 | 47.078 | 156.147 | 70.102 | 589.466 | 57.379 | 0.000 | 0 | 0 | 0 | 217071616 |
| Rift HPZone | 1005.303 | 31.095 | 144.338 | 65.305 | 536.047 | 54.093 | 2.161 | 573324 | 0 | 30703616 | 174964736 |
| Rift Streaming | 996.131 | 31.187 | 147.592 | 63.525 | 534.269 | 56.636 | 2.136 | 573324 | 0 | 30703616 | 174964736 |

### 1M 3-Run Median With Q2 Latest-Empty Taxi Table

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11649.547 | 487.774 | 1566.073 | 401.445 | 7240.096 | 456.895 | 0.000 | 0 | 0 | 0 | 609304576 |
| Rift HPZone | 11263.680 | 381.099 | 1538.659 | 371.771 | 6941.270 | 408.753 | 14.536 | 5386271 | 0 | 97026048 | 381501440 |
| Rift Streaming | 11188.144 | 382.734 | 1544.227 | 368.389 | 6886.643 | 406.135 | 14.991 | 5386271 | 0 | 97026048 | 381501440 |

Interpretation:

- This is a fair allocation-placement change: the taxi state was already keyed
  by dense integer IDs, and both heap and Rift use the same growable-array
  structure.
- The change improves Q2 processing and strengthens the bounded-sample Rift
  result. On the 1M median, HPZone is `385.867 ms` faster than heap, and
  Streaming is `461.403 ms` faster than heap.
- Rift GC time drops by about `106 ms` vs heap at 1M, and peak RSS is about
  `228 MB` lower for both Rift modes in this run set.
- This is still not final Phase 5 success. SafeZone/Commix/full-month controls
  and the static safety story for heap references to region entries remain
  open.

## Q2 Array-Backed Ranking

Date: 2026-04-24

Changes:

- Q2 replaced the heap `java.util.TreeSet[ProfitableArea]` ranking index with a
  shared indexed binary heap over `ProfitableArea` objects.
- Heap and Rift modes use the same ranking algorithm. Heap mode allocates the
  heap/index arrays with ordinary `new`; Rift modes allocate those arrays in
  the run-lifetime ranking/control region.
- `ProfitableArea` remains an ordinary Scala object. In Rift modes those
  ranking objects were already region-allocated; this change also moves the
  ranking index arrays out of the GC heap and removes Java tree-node allocation.
- The cell-to-heap index table uses zero as the empty marker, so construction
  does not fill the full bounded Q2 cell-key array with `-1`.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
      run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
      nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2rankheap2-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2rankheap2-100000-run${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2rankheap2-1000000-run${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

The sample, 100k, and 1M instrumented matrices all matched heap output after
stripping only the measured latency column.

### 100k 3-Run Median With Q2 Array-Backed Ranking

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1005.710 | 45.375 | 170.420 | 60.831 | 514.494 | 59.849 | 0.000 | 0 | 0 | 0 | 216858624 |
| Rift HPZone | 965.099 | 30.805 | 146.223 | 63.046 | 488.345 | 63.123 | 1.981 | 573328 | 0 | 33210368 | 176029696 |
| Rift Streaming | 947.514 | 30.700 | 147.621 | 74.937 | 481.715 | 51.620 | 1.932 | 573328 | 0 | 33210368 | 176013312 |

### 1M 3-Run Median With Q2 Array-Backed Ranking

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Region resets | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11202.975 | 423.932 | 1657.542 | 380.362 | 6761.082 | 450.613 | 0.000 | 0 | 0 | 0 | 431718400 |
| Rift HPZone | 10808.901 | 348.901 | 1634.977 | 383.865 | 6430.000 | 432.656 | 14.861 | 5386275 | 0 | 99532800 | 383631360 |
| Rift Streaming | 10804.756 | 344.376 | 1607.291 | 379.125 | 6462.097 | 422.689 | 14.684 | 5386275 | 0 | 99532800 | 383631360 |

Interpretation:

- This is the strongest bounded-sample DEBS checkpoint so far. On the 1M
  median, HPZone is `394.074 ms` faster than heap, and Streaming is
  `398.219 ms` faster than heap.
- The change reduces Q2 processing on both heap and Rift compared with the
  Q2 latest-empty taxi-table checkpoint, and it removes Java `TreeSet` node
  allocation from the Q2 ranking path.
- Rift GC time drops by about `75 ms` for HPZone and about `80 ms` for
  Streaming versus heap at 1M. Peak RSS is about `48 MB` lower for Rift modes
  in this run set.
- Rift operation time remains small: about `15 ms` at 1M with `0` resets.
- This still is not final Phase 5 success. At this checkpoint, Q1 ranking,
  durable taxi-id metadata, latency arrays, SafeZone/Commix modes, full-month
  scale, and safe API boundaries remained open.

Superseded side note:

- An earlier uncommitted Q1 indexed-heap ranking replacement preserved output
  correctness but regressed Q1 processing. It was not kept. The later Q1
  indexed-ranking checkpoint below is a different measured implementation and
  supersedes this warning.

## RunBoth Heap-Churn Diagnostics And Packed Grid Cell Keys

Date: 2026-04-25

Purpose:

- Add low-overhead counters for the remaining suspected heap/control paths
  before doing another data-structure rewrite.
- Remove the hot temporary `Option[Cell]` / `Cell` allocation path in Q1/Q2 by
  adding a shared packed `Grid.cellKeyOrZero` helper. This applies to heap and
  Rift modes equally; it is accidental allocation removal, not a Rift-only
  benchmark specialization.

Changed instrumentation:

- `Debs2015RunBoth` now emits `diag_*` counters in `DEBS2015_RUNBOTH_RESULT`.
- The instrumented matrix script records the new fields in `summary.tsv`.
- Counters cover grid calls/hits, Q1 ranking updates, Q2 ranking updates,
  Q2 median recomputation, output snapshots, latency appends, and taxi-id
  lookup/intern activity.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-10000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-cellkey-10000 \
  zsh bench/debs2015/run_both_sample_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-cellkey-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-cellkey-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-cellkey-100000-run${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-cellkey-1000000-run${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

All sample, 100k, 1M, and median-rerun matrix outputs matched after stripping only the
measured latency column.

### Pre-Cell-Key 1M Diagnostic Counters

This row was gathered after adding diagnostics and before switching Q1/Q2 to
`Grid.cellKeyOrZero`. It is a single-run diagnostic, not a median.

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Grid calls / hits | Q1 rank add/remove | Q2 rank fixes | Q2 median computes | Q2 values sorted | Taxi misses | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11853.215 | 420.998 | 1755.047 | 7257.464 | 3981885 / 3923963 | 1385875 / 1385870 | 3252279 | 1757976 | 19156244 | 9244 | 609665024 |
| Rift HPZone | 12227.943 | 412.319 | 2039.186 | 7141.753 | 3981885 / 3923963 | 1385875 / 1385870 | 3252279 | 1757976 | 19156244 | 9244 | 383025152 |
| Rift Streaming | 11480.581 | 355.576 | 1821.644 | 6805.569 | 3981885 / 3923963 | 1385875 / 1385870 | 3252279 | 1757976 | 19156244 | 9244 | 383025152 |

Interpretation:

- Output snapshots and taxi-id metadata are not the dominant counters at 1M.
- The hottest remaining allocation/noise source was grid lookup: about `3.9M`
  successful cell lookups, previously creating temporary `Some(Cell)`/`Cell`
  objects before packing cell IDs.
- The dominant processing signal remains Q2 median/ranking work:
  `1.76M` median recomputations, `19.16M` values sorted, and `3.25M` rank-heap
  fixes.

### Packed Cell-Key 100k Diagnostic

Single-run diagnostic after replacing hot Q1/Q2 grid use with
`Grid.cellKeyOrZero`.

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 median computes | Q2 values sorted | Rift op ms | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 904.682 | 21.671 | 120.788 | 475.180 | 171197 | 1365087 | 0.000 | 207667200 |
| Rift HPZone | 909.360 | 17.536 | 127.240 | 471.635 | 171197 | 1365087 | 1.851 | 172851200 |
| Rift Streaming | 950.545 | 17.993 | 142.159 | 486.864 | 171197 | 1365087 | 1.942 | 172916736 |

### Packed Cell-Key 100k 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 rank fixes | Q2 median computes | Q2 values sorted | Rift op ms | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1015.327 | 25.319 | 144.820 | 529.717 | 312906 | 171197 | 1365087 | 0.000 | 207667200 |
| Rift HPZone | 944.513 | 18.366 | 142.511 | 476.838 | 312906 | 171197 | 1365087 | 2.528 | 172916736 |
| Rift Streaming | 919.782 | 17.883 | 136.554 | 469.416 | 312906 | 171197 | 1365087 | 2.260 | 172900352 |

### Packed Cell-Key 1M Diagnostic

Single-run diagnostic after replacing hot Q1/Q2 grid use with
`Grid.cellKeyOrZero`.

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 median computes | Q2 values sorted | Rift op ms | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 11377.011 | 398.359 | 1605.176 | 6950.836 | 1757976 | 19156244 | 0.000 | 431783936 |
| Rift HPZone | 11484.136 | 384.130 | 1645.314 | 7039.925 | 1757976 | 19156244 | 17.230 | 383008768 |
| Rift Streaming | 10996.388 | 353.959 | 1585.959 | 6682.096 | 1757976 | 19156244 | 17.904 | 383041536 |

### Packed Cell-Key 1M 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 rank fixes | Q2 median computes | Q2 values sorted | Rift op ms | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 10969.616 | 383.827 | 1432.781 | 6760.217 | 3252279 | 1757976 | 19156244 | 0.000 | 431767552 |
| Rift HPZone | 10770.260 | 349.488 | 1494.196 | 6565.218 | 3252279 | 1757976 | 19156244 | 15.016 | 352665600 |
| Rift Streaming | 10665.729 | 334.689 | 1470.182 | 6452.297 | 3252279 | 1757976 | 19156244 | 14.873 | 368214016 |

Interpretation:

- Packed cell keys preserve output correctness and remove a large temporary
  object path shared by heap and Rift.
- The 3-run medians now supersede the single-run packed-cell diagnostics for
  this checkpoint. The pre-cell-key diagnostic table remains useful only for
  explaining why the helper was added.
- The next Phase 5 target is not another parser/input tweak. Q2 process still
  dominates: median maintenance and rank updates are now the largest measured
  work. Any next Q2 change must preserve the same logical query for heap and
  Rift, with allocation placement/lifetime policy as the experimental variable.

## Q2 Taxi-ID Table With Heap/Rift Allocation Placement

Date: 2026-04-25

Purpose:

- Move another structured-lifetime Q2 data path out of ad hoc heap metadata.
- Replace the heap `mutable.HashMap[Int, TaxiIdEntry]` plus durable taxi-id
  `String` values with a shared hash table that stores taxi IDs as byte arrays.
- Keep heap and Rift on the same logical table: heap uses ordinary `new` for
  the table, entries, and taxi-id byte copies; Rift allocates the same table,
  entries, and byte arrays in the Q2 run-lifetime ranking/control region.
- Avoid unscanned region-to-GC references by not storing heap `String` objects
  inside region entries.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Smoke\")" run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" run

DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-taxi-region-sample \
  zsh bench/debs2015/run_both_sample_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-taxi-region-median-100000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-taxi-region-median-1000000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

`Debs2015Q1Smoke`, `Debs2015Q2Smoke`, sample RunBoth, 100k medians, and 1M
medians all matched heap/Rift outputs after stripping only the measured latency
column.

### Taxi-ID Table 100k 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Taxi misses | Taxi entry scans | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 793.461 | 18.122 | 98.323 | 404.781 | 44.358 | 0.000 | 0 | 6444 | 125219 | 199983104 |
| Rift HPZone | 787.594 | 23.902 | 99.861 | 393.280 | 47.565 | 1.443 | 586219 | 6444 | 125219 | 69402624 |
| Rift Streaming | 785.273 | 19.621 | 97.949 | 391.787 | 43.413 | 1.421 | 586219 | 6444 | 125219 | 69402624 |

### Taxi-ID Table 1M 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Taxi misses | Taxi entry scans | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 9876.359 | 376.602 | 1172.605 | 6052.167 | 436.103 | 0.000 | 0 | 9244 | 1246099 | 431751168 |
| Rift HPZone | 9676.525 | 358.127 | 1154.971 | 5897.306 | 382.232 | 13.216 | 5404766 | 9244 | 1246099 | 169312256 |
| Rift Streaming | 9667.365 | 270.482 | 1193.662 | 5883.429 | 375.936 | 13.915 | 5404766 | 9244 | 1246099 | 169295872 |

Interpretation:

- This is a fair shared-data-shape change. Heap and Rift use the same taxi-id
  table; the experimental variable is allocation placement for table arrays,
  entries, and taxi-id byte copies.
- The change removes durable taxi-id `String` materialization from both modes,
  so cross-checkpoint elapsed improvements are not pure Rift effects.
- Within the checkpoint, Rift still wins on 1M bounded-sample medians and cuts
  peak RSS sharply: heap `431751168` bytes vs about `169 MB` for both Rift
  modes.
- Streaming shows the strongest GC-time result at 1M: `270.482 ms` vs heap
  `376.602 ms`.
- Q2 processing still dominates: 1M median Q2 process time is `6052.167 ms`
  for heap and `5883.429 ms` for Streaming. The next useful Phase 5 work is
  still median/rank maintenance or a safe collection/control API, not parser
  cleanup.

## Q1 Indexed Ranking With Heap/Rift Allocation Placement

Date: 2026-04-25

Purpose:

- Move Q1 ranking-index metadata out of Java `TreeSet` nodes.
- Keep heap and Rift on the same logical rank-maintenance structure: heap uses
  ordinary `new` arrays, while Rift allocates the same heap arrays and
  slot-index arrays in the Q1 run-lifetime ranking/control region.
- Continue using ordinary Scala `RankedRoute`, `Route`, and `Cell` objects;
  the Rift path allocates those objects in the same ranking/control region.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Smoke\")" run

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" run

DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q1-indexed-sample \
  zsh bench/debs2015/run_both_sample_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q1-indexed-median-100000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q1-indexed-median-1000000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

`Debs2015Q1Smoke`, `Debs2015Q2Smoke`, sample RunBoth, 100k medians, and 1M
medians all matched heap/Rift outputs after stripping only the measured latency
column.

### Q1 Indexed Ranking 100k 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 903.449 | 19.414 | 146.858 | 57.487 | 446.249 | 53.939 | 0.000 | 0 | 184090624 |
| Rift HPZone | 874.280 | 18.821 | 141.575 | 60.185 | 425.737 | 53.619 | 1.820 | 586237 | 43024384 |
| Rift Streaming | 863.219 | 18.578 | 140.475 | 56.244 | 418.070 | 50.923 | 1.778 | 586237 | 43024384 |

### Q1 Indexed Ranking 1M 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 9746.536 | 312.151 | 1369.716 | 336.080 | 5811.892 | 374.661 | 0.000 | 0 | 431669248 |
| Rift HPZone | 9441.064 | 320.025 | 1307.938 | 311.925 | 5599.931 | 362.853 | 10.528 | 5404786 | 108445696 |
| Rift Streaming | 9442.026 | 306.311 | 1306.795 | 312.877 | 5595.953 | 359.654 | 10.094 | 5404786 | 108969984 |

Interpretation:

- This is a fair shared-data-shape change for Q1 ranking. Heap and Rift use the
  same indexed ranking heap; the experimental variable is allocation placement
  for the arrays and the ranking object graph.
- The change also improves heap versus the previous Q2 taxi-id checkpoint, so
  cross-checkpoint elapsed improvements are not pure Rift effects.
- Within the checkpoint, Rift still wins on elapsed time at both 100k and 1M.
  The largest effect is memory footprint: 1M peak RSS drops from `431669248`
  bytes in heap mode to about `108-109 MB` in Rift modes.
- GC time is mixed: Streaming is lower than heap at 1M, HPZone is slightly
  higher. The stronger evidence is that more stream/ranking state is now
  region-resident without increasing Rift operation time materially.
- Q2 process time remains the dominant bottleneck, so the next Phase 5 target
  should still be Q2 median/rank maintenance or the safe region-backed
  collection/control API.

## RunBoth Output Snapshots With Heap/Rift Allocation Placement

Date: 2026-04-25

Purpose:

- Move another RunBoth stream-output state path into Rift regions.
- Keep the snapshot logic shared: heap uses ordinary `new` for Q1/Q2 previous
  output snapshots; Rift allocates the same Q1/Q2 snapshot arrays and Q2
  `Snapshot` objects in a run-lifetime snapshot region.
- Preserve the existing writer-based output path and the same changed-output
  logic.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-snapshots-sample \
  zsh bench/debs2015/run_both_sample_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-snapshots-median-100000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-region-snapshots-median-1000000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

Compile, sample RunBoth, 100k medians, and 1M medians all matched heap/Rift
outputs after stripping only the measured latency column.

### Region Snapshot 100k 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 829.612 | 18.383 | 133.139 | 53.505 | 406.670 | 50.021 | 0.000 | 0 | 184107008 |
| Rift HPZone | 811.389 | 18.242 | 129.597 | 54.135 | 393.350 | 46.312 | 1.615 | 608409 | 44613632 |
| Rift Streaming | 815.476 | 20.286 | 130.665 | 57.026 | 393.740 | 46.427 | 1.604 | 608409 | 44597248 |

### Region Snapshot 1M 3-Run Median

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 8983.464 | 290.712 | 1253.609 | 288.575 | 5393.901 | 323.657 | 0.000 | 0 | 431652864 |
| Rift HPZone | 8815.087 | 292.155 | 1209.080 | 289.984 | 5221.554 | 337.780 | 10.053 | 5561840 | 119865344 |
| Rift Streaming | 8836.488 | 296.305 | 1216.835 | 290.024 | 5233.309 | 349.659 | 10.061 | 5561840 | 119898112 |

Interpretation:

- This is a valid allocation-placement checkpoint, not a headline performance
  win. It moves Q1/Q2 previous-output snapshots into a run-lifetime region in
  Rift modes while keeping the heap and Rift logical output path shared.
- GC time is not materially better at 1M; it is slightly higher in the Rift
  medians. This confirms that output snapshots are not the dominant remaining
  GC source.
- Peak Rift RSS rises versus the Q1 indexed-ranking checkpoint because the
  snapshots now remain in the run-lifetime snapshot region until close. That is
  expected for this lifetime shape.
- Q2 processing remains the main bottleneck. The next useful work is still Q2
  median/rank maintenance or a safe region-backed collection/control API, not
  further output snapshot tuning.

## JVM Same-Input GC Probe

Date: 2026-04-25

Purpose:

- Check whether a normal managed JVM automatically spends large time in GC on
  the same bounded DEBS CSV inputs.
- Use the same joined/sorted CSV files as RunBoth, but do not claim exact DEBS
  Q1/Q2 equivalence. This Java probe intentionally measures per-row object
  churn and bounded 30-minute window retention over the same input.
- Distinguish "dataset is large" from "live heap is large enough to make GC the
  bottleneck."

Harness:

- `bench/debs2015/jvm/DebsJvmGcProbe.java`
- `churn` mode parses each row into JVM objects and drops them immediately.
- `window` mode keeps a 30-minute object window plus a taxi-id map.
- GC counters come from `GarbageCollectorMXBean`.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
javac -d /tmp/debs-jvm-probe bench/debs2015/jvm/DebsJvmGcProbe.java

java -cp /tmp/debs-jvm-probe DebsJvmGcProbe /tmp/debs2015-month1-1000000.csv churn
java -cp /tmp/debs-jvm-probe DebsJvmGcProbe /tmp/debs2015-month1-1000000.csv window
java -Xms64m -Xmx64m -cp /tmp/debs-jvm-probe DebsJvmGcProbe /tmp/debs2015-month1-1000000.csv window
java -Xms16m -Xmx16m -cp /tmp/debs-jvm-probe DebsJvmGcProbe /tmp/debs2015-month1-1000000.csv window
java -Xms8m -Xmx8m -cp /tmp/debs-jvm-probe DebsJvmGcProbe /tmp/debs2015-month1-1000000.csv window
```

Single-run local results:

| Input | JVM heap | Mode | Elapsed ms | GC collections | GC ms | Heap used bytes | Max window | Taxi IDs |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| 100k | default | churn | 128.297 | 1 | 0 | 140941912 | 0 | 0 |
| 100k | default | window | 148.206 | 1 | 1 | 144665408 | 6473 | 6444 |
| 1M | default | churn | 558.514 | 8 | 3 | 211152784 | 0 | 0 |
| 1M | default | window | 532.061 | 8 | 6 | 126348456 | 9301 | 9244 |
| 1M | 64 MB | churn | 456.300 | 48 | 9 | 7734144 | 0 | 0 |
| 1M | 64 MB | window | 549.432 | 50 | 34 | 3333200 | 9301 | 9244 |
| 1M | 16 MB | window | 633.831 | 350 | 119 | 3859088 | 9301 | 9244 |
| 1M | 8 MB | window | 1864.021 | 1109 | 1322 | 4358976 | 9301 | 9244 |

Interpretation:

- A large input file does not by itself imply large GC time. In this streaming
  shape, the live set is bounded by the 30-minute window and taxi-id table.
- With the default JVM heap, even object-heavy parsing over 1M rows reports only
  a few milliseconds of GC. This matches the Scala Native DEBS observation that
  parsing/output/median CPU can dominate while GC remains secondary.
- GC becomes the bottleneck when heap headroom is constrained. At `-Xmx8m`, the
  same 1M window probe spends `1322 ms` in GC out of `1864 ms` elapsed.
- This is a probe, not a full JVM DEBS baseline. A fair JVM Q1/Q2 comparison
  would need the same query logic ported or generated for the JVM.

## Q2 Incremental Median Heaps

Date: 2026-04-26

Purpose:

- Existing diagnostics identified Q2 median/rank maintenance as the dominant
  remaining processing path: at 1M, the previous checkpoint recorded about
  `1.76M` dirty median recomputations, `19.16M` values copied/sorted, and
  `3.25M` Q2 rank fixes.
- Replace per-dirty-cell copy/sort median recomputation with a shared per-cell
  two-heap median structure.
- Keep heap and Rift logically aligned: heap and Rift use the same Q2 window,
  ranking, and median-maintenance algorithm. Heap mode allocates median/control
  arrays with ordinary `new`; Rift modes allocate the same `ProfitStats` and
  median heap arrays in the Q2 run-lifetime ranking/control region.
- Durable taxi-id metadata and the existing output path were not changed in
  this checkpoint.

Validation:

```sh
cd /Users/siyaoliu/.codex/worktrees/8d31/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" run

DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-incremental-median-sample \
  zsh bench/debs2015/run_both_sample_matrix.sh

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-incremental-median-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-incremental-median-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

`sandbox3_next` compiled, `Debs2015Q2Smoke` passed for heap, Rift HPZone, and
Rift Streaming, and the sample, 100k, and 1M RunBoth matrices matched heap
outputs after stripping only the measured latency column.

These rows are single-run instrumented diagnostics, not medians.

Coordinator rerun after merging the branch into `feature/rift`:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" run

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-coordinator-q2-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The coordinator 100k rerun passed, and RunBoth outputs matched heap after
stripping only the measured latency column. It agrees with the worker result:
median-copy sorting remains eliminated and Rift RSS is much lower than heap.

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 677.280 | 8.745 | 138.271 | 224.260 | 53.904 | 0.000 | 0 | 97370112 |
| Rift HPZone | 661.426 | 5.743 | 136.537 | 205.504 | 51.117 | 3.972 | 602450 | 40173568 |
| Rift Streaming | 646.588 | 5.833 | 132.890 | 199.458 | 48.812 | 2.322 | 602450 | 40173568 |

Common coordinator Q2 diagnostics:

| Events | Q2 rank fixes | Median sort computes | Median values sorted | Median reads | Median heap adds | Median heap removes | Median rebalances |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100000 | 312906 | 0 | 0 | 321758 | 98214 | 98213 | 86767 |

### 100k Single Run With Incremental Median Heaps

| Mode | Elapsed ms | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 673.637 | 8.352 | 143.438 | 210.383 | 54.081 | 0.000 | 0 | 97386496 |
| Rift HPZone | 683.219 | 7.128 | 144.419 | 200.590 | 67.487 | 1.980 | 602450 | 40173568 |
| Rift Streaming | 672.708 | 7.098 | 147.105 | 203.167 | 56.016 | 2.162 | 602450 | 40173568 |

Common Q2 diagnostics:

| Events | Q2 rank fixes | Median sort computes | Median values sorted | Median reads | Median heap adds | Median heap removes | Median rebalances |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100000 | 312906 | 0 | 0 | 321758 | 98214 | 98213 | 86767 |

### 1M Single Run With Incremental Median Heaps

| Mode | Elapsed ms | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Rift alloc objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 7001.241 | 71.783 | 1530.498 | 566.199 | 2238.863 | 696.061 | 0.000 | 0 | 305741824 |
| Rift HPZone | 7832.895 | 54.735 | 1598.990 | 808.177 | 2256.581 | 1142.072 | 16.201 | 5494550 | 114458624 |
| Rift Streaming | 6729.748 | 56.712 | 1494.611 | 546.894 | 2115.207 | 600.078 | 15.108 | 5494550 | 113934336 |

Common Q2 diagnostics:

| Events | Q2 rank fixes | Median sort computes | Median values sorted | Median reads | Median heap adds | Median heap removes | Median rebalances |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1000000 | 3252279 | 0 | 0 | 3320865 | 981885 | 981883 | 898934 |

Interpretation:

- This checkpoint removes the old Q2 median copy/sort path from both heap and
  Rift. The old `diag_q2_median_computes` and
  `diag_q2_median_values_sorted` counters are now zero; new counters track
  median reads, heap adds/removes, and rebalances.
- The change is a shared query-structure improvement, not a separate Rift
  benchmark. The allocation-placement split remains the experimental variable
  for the median/control arrays.
- Q2 process time drops sharply compared with the previous output-snapshot
  medians, but the comparison is cross-checkpoint and single-run. It is useful
  as a direction signal, not a headline result.
- Rift RSS is much lower than heap in these runs, but 1M elapsed ordering is
  still noisy: Streaming is faster than heap in this single run, while HPZone
  is slower because Q1/Q2 output phases are unusually high. Median reruns are
  required before making a performance claim.
- Q2 rank fixes remain about `3.25M` at 1M. The next measured Q2 target should
  be rank-maintenance cost and output-phase variance, not reintroducing
  per-event scratch regions.

### 100k And 1M 3-Run Medians With Incremental Median Heaps

Date: 2026-04-26

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=$([[ $i == 1 ]] && echo 1 || echo 0) \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-incremental-current-100000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-incremental-current-1000000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

All runs matched heap/Rift outputs after stripping only the measured latency
column. These rows supersede the single-run Q2 incremental-median diagnostics
above for headline current-checkpoint claims.

100k medians:

| Mode | Elapsed ms | Throughput events/s | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 619.735 | 161359.399 | 8.091 | 135.542 | 196.361 | 46.384 | 0.000 | 0 | 97370112 |
| Rift HPZone | 626.609 | 159589.101 | 5.839 | 133.007 | 180.546 | 48.361 | 1.518 | 602450 | 40173568 |
| Rift Streaming | 610.258 | 163865.186 | 5.781 | 133.449 | 183.060 | 47.792 | 1.573 | 602450 | 40173568 |

1M medians:

| Mode | Elapsed ms | Throughput events/s | GC ms | Q1 process ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 5976.447 | 167323.501 | 67.086 | 1387.651 | 2027.508 | 419.937 | 0.000 | 0 | 305758208 |
| Rift HPZone | 5794.879 | 172566.172 | 59.658 | 1351.446 | 1905.210 | 377.610 | 10.737 | 5494550 | 113950720 |
| Rift Streaming | 5948.755 | 168102.414 | 55.056 | 1403.377 | 1922.138 | 386.790 | 11.172 | 5494550 | 113934336 |

Common diagnostics:

| Input | Q2 rank fixes | Median sort computes | Median values sorted | Median reads | Median heap adds | Median heap removes | Median rebalances |
|---|---:|---:|---:|---:|---:|---:|---:|
| 100k | 312906 | 0 | 0 | 321758 | 98214 | 98213 | 86767 |
| 1M | 3252279 | 0 | 0 | 3320865 | 981885 | 981883 | 898934 |

Interpretation:

- The Q2 incremental median checkpoint is now median-backed on the bounded
  100k and 1M samples.
- It is a shared algorithmic cleanup: both heap and Rift stop copying/sorting
  dirty median arrays. The allocation-placement variable remains that Rift
  modes allocate the median/control arrays and related ordinary Scala objects
  in run-lifetime regions.
- At 1M, HPZone is `181.568 ms` faster than heap and Streaming is
  `27.692 ms` faster than heap. Both Rift modes reduce GC time versus heap and
  cut peak RSS from about `306 MB` to about `114 MB`.
- The main remaining bottleneck is not Rift allocation bookkeeping. Rift
  operation time is about `11 ms` at 1M, while Q2 rank maintenance still
  performs about `3.25M` rank fixes and Q2 process time remains about
  `1.9-2.0 s`.

### Checked-Guard Boundary And Region-Backed Latency Buffers

Date: 2026-04-26

Change:

- Narrowed the checked Rift allocation guard so it applies to the checked
  `RiftRegion.ScopedRegion`/`RiftRegion.StreamingRegion` API surface, not to
  trusted low-level `RiftRegion.open(...)` benchmark allocations. This keeps
  the safe API checks active while allowing trusted benchmark linked structures
  to use ordinary region allocation without the v1 mixed-reference rejection.
- Replaced RunBoth's generic `mutable.ArrayBuffer[Long]` latency collectors
  with a shared `LongSampleBuffer`. Heap mode uses a heap `Array[Long]`; Rift
  modes allocate the backing `Array[Long]` in the existing RunBoth snapshot
  region and copy the final primitive arrays to heap before closing the region
  because `Metrics` outlives the run region.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Smoke\")" \
      run

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-latency-100000 \
  zsh bench/debs2015/run_both_sample_matrix.sh
```

Validation:

- `RiftRegionCheckedCompilerTest`: passed `30/30`.
- `Debs2015Smoke`: passed.
- 100k RunBoth sample matrix: heap/Rift outputs matched.

100k single-run sample matrix:

| Mode | Elapsed ms | Throughput events/s | GC ms | Q1 process ms | Q1 output ms | Q2 process ms | Q2 output ms | Rift op ms | Region objects |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 728.465 | 137275.033 | 12.690 | 149.200 | 76.313 | 222.304 | 80.431 | 0.000 | 0 |
| Rift HPZone | 651.938 | 153388.849 | 5.190 | 144.700 | 58.584 | 200.772 | 49.300 | 2.370 | 602457 |
| Rift Streaming | 633.442 | 157867.702 | 5.318 | 136.581 | 60.215 | 185.881 | 55.154 | 1.874 | 602457 |

Interpretation:

- This is a validation checkpoint, not a new headline benchmark. The latest
  3-run median table above remains the current stronger bounded-sample DEBS
  evidence.
- The latency collector change removes generic `ArrayBuffer` allocation noise
  from both modes while preserving the same logical program: heap uses ordinary
  heap arrays and Rift uses region-backed arrays at the same lifetime boundary.
- The checked guard now reflects the intended API split: `scoped`/`streaming`
  are checked; `RiftRegion.open` remains a documented trusted/unsafe low-level
  path for benchmark and runtime experiments.

### Q2 Rank/Output Attribution Counters

Date: 2026-04-26

Change:

- Added RunBoth phase timers for Q1/Q2 output-change checks and snapshot work:
  `phase_q1_change_ns`, `phase_q1_snapshot_ns`, `phase_q2_change_ns`, and
  `phase_q2_snapshot_ns`.
- Added Q2 diagnostic counters for rank-heap comparisons/swaps, top-candidate
  comparisons during top-10 extraction, and Q2 changed-output comparisons.
- Updated `bench/debs2015/run_both_instrumented_matrix.sh` so these fields are
  written into `summary.tsv`.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-attribution-final-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Validation:

- 100k RunBoth instrumented matrix completed.
- Heap/Rift outputs matched after stripping only the measured latency column.

100k single-run attribution:

| Mode | Elapsed ms | Q2 process ms | Q2 change ms | Q2 output ms | Q2 snapshot ms | Q2 rank compares | Q2 rank swaps | Q2 top-candidate compares | Q2 changed calls | Q2 changed element checks |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 643.082 | 200.551 | 18.765 | 47.617 | 0.672 | 5075427 | 68022 | 4445398 | 99457 | 976407 |
| Rift HPZone | 663.542 | 187.618 | 18.601 | 47.133 | 0.703 | 5075427 | 68022 | 4445398 | 99457 | 976407 |
| Rift Streaming | 616.502 | 186.946 | 18.478 | 46.855 | 0.696 | 5075427 | 68022 | 4445398 | 99457 | 976407 |

Interpretation:

- Q2 snapshot allocation/copy time is small at 100k: about `0.7 ms`.
- Q2 changed-output checks are visible but not dominant: about `18-19 ms`.
- Q2 rank/top-10 extraction remains the major Q2 CPU path. At 100k, about
  `4.45M` of `5.08M` rank comparisons are from the temporary top-candidate
  scan used to extract the top 10 from the rank heap.
- A small binary heap for top candidates was tested during this session and
  rejected before commit: it increased top-candidate comparisons to about
  `5.39M` and did not improve Q2 process time on the 100k sample. Do not redo
  that variant without a different comparison strategy.

### Q2 Cached Top-10 Extraction

Date: 2026-04-26

Change:

- Added a shared heap/Rift Q2 top-10 cache. `process` still returns the current
  top-k ranking on every event, but the expensive heap frontier extraction is
  recomputed only when a rank update can affect the cached top 10.
- The invalidation rule is conservative: updates to current top-10 cells,
  additions while fewer than 10 cells are ranked, removals from the top 10, or
  non-top cells that can beat the cached tenth entry mark the cache dirty.
- Added `diag_q2_top10_recomputes` to distinguish logical top-10 calls from
  actual top-k extraction work.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Smoke\")" \
      run

DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-top-cache-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-top-cache-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-top-cache-median-100000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done

for i in 1 2 3; do
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
  DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-q2-top-cache-median-1000000/run-${i} \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

Validation:

- `Debs2015Smoke`: passed.
- 100k and 1M RunBoth instrumented matrices completed.
- 100k and 1M 3-run median matrices completed.
- Heap/Rift outputs matched after stripping only the measured latency column.

100k single-run checkpoint:

| Mode | Elapsed ms | Q2 process ms | Q2 output ms | GC ms | Rift op ms | Q2 top10 calls | Q2 top10 recomputes | Q2 rank compares | Q2 top-candidate compares | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 628.690 | 127.308 | 80.264 | 9.825 | 0.000 | 100000 | 4189 | 774978 | 144949 | 102367232 |
| Rift HPZone | 550.082 | 114.019 | 47.548 | 5.314 | 1.605 | 100000 | 4189 | 774978 | 144949 | 41500672 |
| Rift Streaming | 553.841 | 115.732 | 47.588 | 5.371 | 1.735 | 100000 | 4189 | 774978 | 144949 | 41484288 |

1M single-run checkpoint:

| Mode | Elapsed ms | Q2 process ms | Q2 output ms | GC ms | Rift op ms | Q2 top10 calls | Q2 top10 recomputes | Q2 rank compares | Q2 top-candidate compares | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 5367.670 | 1321.860 | 372.546 | 68.932 | 0.000 | 1000000 | 29983 | 7374791 | 1162935 | 305627136 |
| Rift HPZone | 5149.720 | 1210.340 | 371.787 | 35.475 | 11.813 | 1000000 | 29983 | 7374791 | 1162935 | 116604928 |
| Rift Streaming | 5191.038 | 1220.904 | 370.809 | 35.887 | 12.376 | 1000000 | 29983 | 7374791 | 1162935 | 116588544 |

100k 3-run medians:

| Mode | Elapsed ms | Throughput events/s | Q1 process ms | Q2 process ms | Q2 output ms | GC ms | Rift op ms | Region objects | Q2 top10 recomputes | Q2 top-candidate compares | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 607.176 | 164696.869 | 153.854 | 142.477 | 49.822 | 9.254 | 0.000 | 0 | 4189 | 144949 | 102350848 |
| Rift HPZone | 571.465 | 174988.755 | 141.815 | 125.495 | 49.499 | 5.348 | 2.677 | 602458 | 4189 | 144949 | 41500672 |
| Rift Streaming | 590.900 | 169233.444 | 146.598 | 132.482 | 50.538 | 5.093 | 2.620 | 602458 | 4189 | 144949 | 41500672 |

1M 3-run medians:

| Mode | Elapsed ms | Throughput events/s | Q1 process ms | Q2 process ms | Q2 output ms | GC ms | Rift op ms | Region objects | Q2 top10 recomputes | Q2 top-candidate compares | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 6192.692 | 161480.658 | 1625.747 | 1608.462 | 444.281 | 70.762 | 0.000 | 0 | 29983 | 1162935 | 304463872 |
| Rift HPZone | 5697.948 | 175501.773 | 1528.989 | 1427.353 | 388.500 | 36.231 | 18.824 | 5494563 | 29983 | 1162935 | 116588544 |
| Rift Streaming | 5657.424 | 176758.892 | 1521.009 | 1409.247 | 400.798 | 36.288 | 21.925 | 5494563 | 29983 | 1162935 | 96239616 |

Interpretation:

- This is still a shared query-algorithm cleanup, not a Rift-only change.
  Heap and Rift use the same top-10 cache; the experimental variable remains
  allocation placement for region-backed data/control structures.
- The cache directly addresses the Q2 attribution finding: top-k extraction is
  no longer paid on every event. At 100k, top-candidate comparisons fall from
  `4.45M` to `144949`; at 1M, only `29983` of `1000000` logical top-10 calls
  recompute the heap frontier.
- The 3-run medians confirm the single-run direction. At 1M, HPZone is
  `494.744 ms` faster than heap and Streaming is `535.268 ms` faster than heap.
  GC time falls from `70.762 ms` to about `36 ms`, and peak RSS falls from
  `304463872` bytes to `116588544` bytes for HPZone and `96239616` bytes for
  Streaming.
- The median rerun also shows variance: one 1M HPZone run was slower than heap.
  The current claim should therefore be bounded-sample median evidence, not a
  final full-DEBS application result.

### GC Heap Allocation Attribution

Date: 2026-04-26

Purpose:

- `gc_time_ns` measures only collection time. It does not include the mutator
  cost of calling the heap allocator for ordinary `new` and array allocation.
- Add opt-in GC heap allocation counters for attribution:
  `SCALANATIVE_GC_ALLOC_STATS=1`.
- The counters record heap allocation calls, rounded heap bytes requested, and
  nanoseconds spent in measured GC allocation calls.

Caveat:

- This mode times every GC heap allocation call and uses atomic counters, so it
  perturbs elapsed timings. Use it to explain where time goes, not as the
  headline throughput comparison. The normal 3-run medians above remain the
  performance checkpoint.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-gc-alloc-stats-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-gc-alloc-stats-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Validation:

- 100k and 1M attribution matrices completed.
- Heap/Rift outputs matched after stripping only the measured latency column.

100k single-run attribution:

| Mode | Elapsed ms | GC collect ms | GC alloc calls | GC alloc bytes | GC alloc time ms | Rift objects | Rift op ms | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 698.664 | 8.333 | 2790996 | 116791680 | 82.020 | 0 | 0.000 | 102350848 |
| Rift HPZone | 655.328 | 4.955 | 2193091 | 69285392 | 57.251 | 602458 | 2.058 | 41467904 |
| Rift Streaming | 654.947 | 4.980 | 2193109 | 69285776 | 57.495 | 602458 | 2.072 | 41484288 |

1M single-run attribution:

| Mode | Elapsed ms | GC collect ms | GC alloc calls | GC alloc bytes | GC alloc time ms | Rift objects | Rift op ms | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 6495.025 | 68.739 | 19924383 | 679841024 | 582.629 | 0 | 0.000 | 305643520 |
| Rift HPZone | 6373.794 | 37.610 | 14462042 | 455349920 | 385.807 | 5494563 | 20.131 | 116588544 |
| Rift Streaming | 6169.514 | 37.617 | 14462060 | 455350304 | 384.031 | 5494563 | 18.424 | 116555776 |

Interpretation:

- The median speedup is not explained by collection time alone. In this
  attribution mode at 1M, collection time drops by about `31 ms`, but measured
  GC allocator-call time drops by about `197-199 ms`.
- Rift also removes about `5.46M` heap allocation calls and about `224 MB` of
  rounded heap allocation requests at 1M by moving structured-lifetime data and
  control objects into regions.
- This supports the current design interpretation: the win comes from placing
  structured-lifetime Scala objects in regions, not from a Rift-only DEBS
  algorithm. The Q2 top-10 cache is shared by heap and Rift; allocation
  placement and lifetime policy remain the experimental variable.
- The remaining elapsed delta beyond collection plus measured allocator-call
  time is still application work: locality, cache behavior, phase-level CPU
  differences, output, and ranking/control operations. Do not collapse it into
  "GC pause time."

### Phase-Level GC Heap Allocation Attribution

Date: 2026-04-26

Purpose:

- Identify which DEBS phases still allocate on the GC heap after moving Q1/Q2
  structured-lifetime state into Rift regions.
- Keep this diagnostic separate from headline elapsed medians because
  `SCALANATIVE_GC_ALLOC_STATS=1` still times every heap allocation call.

Measurement correction:

- A first implementation sampled Scala-side GC allocation counters around every
  phase. That was rejected because reading the counters allocated and polluted
  the buckets. At 1M, parse/change-style buckets gained millions of fake tiny
  allocation calls.
- The current numbers below use C-side thread-local phase bucketing inside the
  GC allocation hook. The Scala benchmark only enters/leaves a phase; it does
  not read allocation counters on the hot path.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-phase-gc-alloc-fixed-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-phase-gc-alloc-fixed-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Validation:

- 100k and 1M phase-attribution matrices completed.
- Heap/Rift outputs matched after stripping only the measured latency column.
- The clean buckets no longer show fake parse/change allocation spikes.

100k clean phase attribution:

| Mode | Total GC alloc calls | Total GC alloc bytes | Total GC alloc ms | Q1 process calls / bytes / ms | Q2 process calls / bytes / ms | Q1 output calls / bytes / ms | Q2 output calls / bytes / ms | Snapshot calls / bytes / ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2793038 | 116855232 | 78.098 | 358318 / 12895904 / 14.900 | 227619 / 9676608 / 6.421 | 1153940 / 37077216 / 27.947 | 1028446 / 31947104 / 24.168 | 22172 / 1725184 / 0.615 |
| Rift HPZone | 2195129 | 69348816 | 54.992 | 3074 / 97600 / 0.074 | 7150 / 211424 / 0.168 | 1153937 / 36962480 / 29.311 | 1028444 / 31897920 / 25.355 | 0 / 0 / 0.000 |
| Rift Streaming | 2195148 | 69349184 | 55.658 | 3074 / 97600 / 0.074 | 7150 / 211424 / 0.164 | 1153937 / 36962480 / 29.558 | 1028444 / 31897920 / 25.790 | 0 / 0 / 0.000 |

1M clean phase attribution:

| Mode | Total GC alloc calls | Total GC alloc bytes | Total GC alloc ms | Q1 process calls / bytes / ms | Q2 process calls / bytes / ms | Q1 output calls / bytes / ms | Q2 output calls / bytes / ms | Snapshot calls / bytes / ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 19926437 | 679904992 | 537.038 | 3284649 / 105197520 / 86.237 | 2088994 / 84904608 / 55.307 | 6452447 / 206957216 / 149.152 | 7940120 / 246770352 / 238.504 | 157054 / 12147840 / 4.149 |
| Rift HPZone | 14464093 | 455413840 | 350.305 | 21512 / 689088 / 0.460 | 46869 / 1439440 / 0.996 | 6452442 / 206449232 / 161.202 | 7940115 / 246262368 / 187.258 | 0 / 0 / 0.000 |
| Rift Streaming | 14464112 | 455414144 | 352.571 | 21512 / 689088 / 0.515 | 46869 / 1439440 / 1.064 | 6452442 / 206449232 / 156.849 | 7940115 / 246262368 / 194.056 | 0 / 0 / 0.000 |

Interpretation:

- Rift is doing what this stage intended for Q1/Q2 processing state: at 1M,
  Q1 process heap allocation falls from `3.28M` calls to `21.5k`, and Q2
  process heap allocation falls from `2.09M` calls to `46.9k`. Snapshot heap
  allocation is also eliminated in Rift modes.
- The dominant remaining heap churn is output formatting/writing. At 1M, Q1
  and Q2 output still account for about `14.39M` heap allocation calls in both
  heap and Rift modes. That explains why total GC allocation bytes remain high
  even after process/snapshot objects move into regions.
- The next DEBS step should target shared output-row construction and formatting
  without changing the logical Q1/Q2 algorithms. Durable file writers can remain
  heap objects; per-row scratch builders/formatting intermediates are the
  structured-lifetime candidates.

## RunBoth Byte-Oriented Output Writer

Date: 2026-04-26

Purpose:

- Act on the phase-attribution finding above: output construction was the
  dominant remaining heap allocation source.
- Replace RunBoth's `java.io.BufferedWriter`/`Writer` character path with a
  shared byte-oriented row writer. Heap mode uses the same writer with a heap
  byte buffer; Rift modes allocate the writer's reusable byte buffer in the
  existing run snapshot region. Durable file handles remain heap objects.
- Preserve logical-program shape: Q1/Q2 algorithms, output rows, rankings, and
  changed-output semantics are unchanged.

Implementation:

- `OutputSupport.ByteRowWriter` buffers ASCII CSV bytes and flushes to a
  `FileOutputStream`.
- `Trip`, `Q1Output`, `Q2Output`, and `Q2Support` have byte-writer overloads
  alongside the existing `Writer` paths.
- `Debs2015RunBoth` uses the byte writer; the single-query Q1/Q2 runners still
  use the existing `Writer` path.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"debs2015.Debs2015Smoke\")" \
      run

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-byte-output-alloc-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

SCALANATIVE_GC_ALLOC_STATS=1 \
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-byte-output-alloc-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

- `Debs2015Smoke`: passed.
- 100k and 1M attribution matrices completed.
- Heap/Rift outputs matched after stripping only the measured latency column.

Clean allocation-attribution checkpoints after byte output:

| Input | Mode | Total GC alloc calls | Total GC alloc bytes | Total GC alloc ms | Q1 output calls / bytes / ms | Q2 output calls / bytes / ms | GC collect ms | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 674381 | 49120368 | 29.327 | 75 / 115888 / 0.013 | 63676 / 1067968 / 1.543 | 8.789 | 55394304 |
| 100k | Rift HPZone | 76467 | 1482832 | 1.751 | 72 / 1152 / 0.002 | 63674 / 1018784 / 1.431 | 0.000 | 38830080 |
| 100k | Rift Streaming | 76487 | 1483216 | 1.746 | 72 / 1152 / 0.002 | 63674 / 1018784 / 1.432 | 0.000 | 38813696 |
| 1M | heap | 6025143 | 235159552 | 171.868 | 397 / 514256 / 0.055 | 490897 / 8362256 / 10.491 | 20.156 | 159940608 |
| 1M | Rift HPZone | 562793 | 10537200 | 12.923 | 392 / 6272 / 0.011 | 490892 / 7854272 / 11.227 | 0.670 | 116785152 |
| 1M | Rift Streaming | 562813 | 10537584 | 12.801 | 392 / 6272 / 0.010 | 490892 / 7854272 / 11.127 | 0.618 | 116785152 |

Non-attribution 3-run medians after byte output:

| Input | Mode | Elapsed ms | Throughput events/s | Q1 process ms | Q2 process ms | Q1 output ms | Q2 output ms | GC collect ms | Rift op ms | Region objects | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 478.524 | 208975.789 | 135.282 | 123.914 | 10.811 | 9.945 | 8.212 | 0.000 | 0 | 55377920 |
| 100k | Rift HPZone | 463.141 | 215917.084 | 132.179 | 112.300 | 10.848 | 10.132 | 0.000 | 1.704 | 602460 | 38830080 |
| 100k | Rift Streaming | 470.538 | 212522.838 | 134.977 | 116.505 | 10.946 | 10.194 | 0.000 | 1.823 | 602460 | 38830080 |
| 1M | heap | 4640.593 | 215489.696 | 1339.997 | 1240.706 | 65.032 | 78.033 | 21.025 | 0.000 | 0 | 159907840 |
| 1M | Rift HPZone | 4524.706 | 221008.853 | 1313.434 | 1143.983 | 60.996 | 82.963 | 0.685 | 10.245 | 5494565 | 116785152 |
| 1M | Rift Streaming | 4522.308 | 221126.019 | 1307.018 | 1146.500 | 59.912 | 83.045 | 0.635 | 9.626 | 5494565 | 116785152 |

Interpretation:

- The output allocation diagnosis was correct. At 1M, total heap allocation in
  Rift modes falls from about `14.46M` calls / `455 MB` before byte output to
  about `0.56M` calls / `10.5 MB` after byte output.
- The heap mode also improves because the noisy `Writer` path was shared. This
  is intended: it removes accidental output overhead from both arms rather than
  inventing a Rift-only output algorithm.
- Rift now spends almost no time in GC collection on the bounded 1M RunBoth
  sample (`0.6-0.7 ms` in the single attribution run; `0.6-0.7 ms` median
  collection time in non-attribution runs). The remaining elapsed gap is mostly
  query CPU and I/O, not GC.
- This strengthens Phase 5 evidence but still does not seal it. The latest
  checkpoint is bounded-sample RunBoth evidence; full-month/Commix/SafeZone
  controls and safe API integration remain open.

## Q1 Checked-Output Safe-API Probe

Date: 2026-04-27

Purpose:

- Start moving DEBS evidence from trusted `RiftRegion.open` benchmark code
  toward the checked `RiftRegion.streaming`/`reset` API.
- Keep the Q1 algorithm fixed: both modes use the existing `Q1Heap` engine.
  The experimental variable is only output/ranking materialization. Heap mode
  writes the existing `RankedRoute` result with `Q1Output`; checked mode
  materializes transient `CheckedCell`, `CheckedRoute`, and
  `CheckedRankedRoute` objects plus a `RegionBuffer` inside a reset region,
  writes the row while the region is live, and retains only the durable
  primitive snapshot on the heap.

Harness:

- `sandbox/src/main/scala-next/debs2015/Debs2015Q1CheckedOutputRun.scala`
- `bench/debs2015/run_q1_checked_output_matrix.sh`

Commands:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
zsh bench/debs2015/run_q1_checked_output_matrix.sh

DEBS2015_Q1_CHECKED_OUTPUT_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_Q1_CHECKED_OUTPUT_DIR=/tmp/debs2015-q1-checked-output-100000 \
zsh bench/debs2015/run_q1_checked_output_matrix.sh
```

Validated:

- Scala-next sandbox compile passed.
- Sample output matched after stripping only latency.
- 100k sorted-sample output matched after stripping only latency.

Single-run local results:

| Input | Mode | Events | Outputs | Elapsed ms | Throughput events/s |
|---|---|---:|---:|---:|---:|
| sample | heap-output | 3 | 3 | 0.395 | 7596.533 |
| sample | checked-output | 3 | 3 | 0.363 | 8272.074 |
| 100k sorted | heap-output | 100000 | 5942 | 653.038 | 153130.543 |
| 100k sorted | checked-output | 100000 | 5942 | 652.211 | 153324.644 |

Interpretation:

- This is safe-API coverage for real DEBS-shaped ranking/output objects, not a
  new DEBS speed claim and not a RunBoth replacement.
- It proves the current checked API can express a small rich-object output
  region with ordinary Scala objects and a checked `RegionBuffer` boundary.
- Because the core Q1 window/ranking engine is still heap in both modes, this
  probe does not measure the benefit of moving Q1 processing state to checked
  regions. That remains the next larger Phase 5/7 integration step.

## Q1 Checked-Processing Safe-API Probe

Date: 2026-04-27

Purpose:

- Move a real Q1 processing lifetime boundary into the checked API, beyond the
  earlier output-only probe.
- Validate ordinary Scala objects in region-managed Q1 processing state under
  `RiftRegion.streaming`.

Harness:

- `sandbox/src/main/scala-next/debs2015/Debs2015Q1CheckedProcessingRun.scala`
- `bench/debs2015/run_q1_checked_processing_matrix.sh`

What is region-managed in `checked-processing`:

- per-second Q1 bucket control objects captured by the parent streaming region;
- one `RiftRegion.childWindow` per Q1 bucket, backed by a checked child
  streaming region;
- live Q1 route events as path-dependent `bucket.RouteEvent` objects in the
  bucket window's child region, with the child region closed at bucket eviction;
- route/rank table arrays in the streaming region;
- `CheckedCell`, `CheckedRoute`, and `CheckedRankedRoute` objects in the
  streaming region;
- reusable top-10 result storage in the streaming region.

Important caveats:

- This is a Q1 standalone probe, not RunBoth and not a new Phase 5 headline.
- Heap mode uses the existing bucketed Q1 runner. Checked mode now also groups
  events by per-second checked buckets and uses the same logical
  sliding-window query. It is still a standalone probe rather than the shared
  RunBoth Q1 backend.
- The checked bucket lifetime now reclaims live event nodes per bucket. Ranking
  arrays and rank objects remain in the parent run-lifetime streaming region.
- `RiftRegion.childWindow` is now the preferred checked child-lifetime wrapper
  for stream windows and buckets. It uses the raw `childStreaming` primitive,
  so the child handle cannot escape the parent stream, but close ordering is
  still explicit rather than affine-checked. The Q1 probe keeps child-owned
  events in a path-dependent bucket event type and clears bucket fields before
  closing. A broader checked bucket/window API with stronger close discipline is
  still needed before treating this as the final DEBS safe API shape.
- During implementation, the checker rejected the more direct bucket-head
  pattern where a mutable selected field (`bucket.head`) was passed into a new
  region object constructor. The accepted bucketed probe appends entries with
  explicit `head`/`tail` field updates after allocating the new event.

Commands:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
zsh bench/debs2015/run_q1_checked_processing_matrix.sh

DEBS2015_Q1_CHECKED_PROCESSING_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_Q1_CHECKED_PROCESSING_DIR=/tmp/debs2015-q1-checked-processing-childwindow-100000 \
zsh bench/debs2015/run_q1_checked_processing_matrix.sh

DEBS2015_Q1_CHECKED_PROCESSING_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_Q1_CHECKED_PROCESSING_DIR=/tmp/debs2015-q1-checked-processing-childwindow-1000000 \
zsh bench/debs2015/run_q1_checked_processing_matrix.sh
```

Validated:

- Scala-next sandbox compile passed.
- `RiftRegionCheckedCompilerTest` passed 51 checked-region compiler probes,
  including raw child-streaming bucket-event graph acceptance, child-handle
  escape rejection, `ChildWindow` bucket-event graph acceptance, `ChildWindow`
  escape rejection, and owner-token child-region widening.
- After refactoring Q1 to `RiftRegion.childWindow`, sample, 100k sorted-sample,
  and 1M sorted-sample outputs matched after stripping only latency.

Single-run local results after the `ChildWindow` wrapper refactor:

| Input | Mode | Events | Outputs | Elapsed ms | Throughput events/s |
|---|---|---:|---:|---:|---:|
| sample | heap | 3 | 3 | 0.266 | 11260.585 |
| sample | checked-processing | 3 | 3 | 0.351 | 8544.964 |
| 100k sorted | heap | 100000 | 5942 | 738.999 | 135318.272 |
| 100k sorted | checked-processing | 100000 | 5942 | 652.811 | 153183.664 |
| 1M sorted | heap | 1000000 | 32209 | 6803.066 | 146992.548 |
| 1M sorted | checked-processing | 1000000 | 32209 | 6024.772 | 165981.383 |

Interpretation:

- This is the first checked DEBS processing-state probe, and it validates that
  Q1 live stream event and ranking objects can be ordinary Scala objects in
  checked Rift regions.
- The timing is provisional: it is single-run and Q1-only. With per-bucket
  event reclaim included, the checked probe is modestly faster on the 100k and
  1M sorted slices, but this is not yet a median or full RunBoth result.
- The checked representation now matches the production per-second event
  lifetime for Q1 event nodes, but only as a disciplined pattern. The broader
  API still needs a safer bucket/window abstraction so callers cannot
  accidentally widen child-region values into parent-lived containers before
  closing the child region.

## Q2 Checked-Processing Safe-API Probe

Date: 2026-04-27

Purpose:

- Move the larger Q2 processing object graph into the checked API, beyond Q1
  and output-only probes.
- Validate that Q2 window entries, median heap entries, ranking objects,
  bounded tables, taxi-id entries/bytes, and result storage can be expressed
  with checked region ownership while preserving heap output.

Harness:

- `sandbox/src/main/scala-next/debs2015/Debs2015Q2CheckedProcessingRun.scala`
- `bench/debs2015/run_q2_checked_processing_matrix.sh`

What is region-managed in `checked-processing`:

- one `RiftRegion.childWindow` per profit window and per empty-taxi window;
- `ProfitEntry` and `EmptyEntry` stream objects allocated in the corresponding
  child window region and closed at window eviction;
- median heap backing arrays and the entries referenced by those heaps;
- `CheckedProfitableArea` rank objects, rank heaps, top-k result storage, and
  bounded per-cell arrays in the parent streaming region;
- taxi-id table entries and copied taxi-id bytes in the parent streaming region.

Important caveats:

- This is a standalone Q2 probe, not RunBoth and not a new Phase 5 headline.
- `ProfitStats` objects are heap control metadata captured by the checked
  stream. They own region-backed median heap arrays and point at region entries,
  but they are not themselves region-allocated because the current checked
  lowering rejected the local class allocation due to hidden/local captures.
- Q2 uses `RiftRegion.childRegion(stream, window)` as an explicit owner-token
  accessor when parent-lived control metadata must retain records allocated in
  a child window until eviction. This documents the widening point but does not
  make close ordering affine-checked.
- The child-window close discipline is still explicit, as in the Q1 probe. A
  reusable checked bucket/window API with stronger close discipline remains the
  right next safety step.
- Timings are single-run local diagnostics.

Commands:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
zsh bench/debs2015/run_q2_checked_processing_matrix.sh

DEBS2015_Q2_CHECKED_PROCESSING_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_Q2_CHECKED_PROCESSING_DIR=/tmp/debs2015-q2-checked-processing-childwindow-100000 \
zsh bench/debs2015/run_q2_checked_processing_matrix.sh

DEBS2015_Q2_CHECKED_PROCESSING_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_Q2_CHECKED_PROCESSING_DIR=/tmp/debs2015-q2-checked-processing-childwindow-1000000 \
zsh bench/debs2015/run_q2_checked_processing_matrix.sh
```

Validated:

- Scala-next sandbox compile passed.
- After refactoring Q2 to `RiftRegion.childWindow` and the owner-token
  `RiftRegion.childRegion(stream, window)` accessor, sample, 100k sorted-sample,
  and 1M sorted-sample outputs matched after stripping only latency.

Single-run local results after the `ChildWindow` wrapper refactor:

| Input | Mode | Events | Outputs | Elapsed ms | Throughput events/s |
|---|---|---:|---:|---:|---:|
| sample | heap | 2 | 2 | 5.526 | 361.925 |
| sample | checked-processing | 2 | 2 | 5.895 | 339.283 |
| 100k sorted | heap | 100000 | 3246 | 692.603 | 144382.874 |
| 100k sorted | checked-processing | 100000 | 3246 | 661.308 | 151215.556 |
| 1M sorted | heap | 1000000 | 24969 | 6800.401 | 147050.152 |
| 1M sorted | checked-processing | 1000000 | 24969 | 6316.186 | 158323.392 |

Interpretation:

- This is the first checked Q2 processing-state probe and covers a richer
  object graph than Q1: two window kinds, median heaps, ranking objects,
  taxi-id lookup state, and output-change snapshots.
- Directionally, checked Q2 is modestly faster than heap on the 100k and 1M
  sorted slices in this single-run probe. Treat that as encouraging but
  provisional until medians and RunBoth integration exist.
- The main safety/design gap is now clearer: the current checked API can
  express this Q2 shape, but only with heap control metadata and explicit child
  close ordering. The next safe-API step should factor Q1/Q2 child-window
  patterns into a checked reusable abstraction or extend the checker so local
  region-owned control classes like `ProfitStats` do not need to remain heap
  metadata.

## RunBoth Checked-Processing Integration

Date: 2026-04-27

Purpose:

- Move the checked Q1 and checked Q2 processing-state probes into the shared
  single-pass RunBoth loop.
- Preserve the fair-backend rule: heap and checked modes parse the same input,
  execute the same Q1/Q2 logical queries in the same event order, and write the
  same Q1/Q2 outputs; the checked mode changes allocation placement and checked
  lifetime boundaries for processing objects.

Harness:

- `sandbox/src/main/scala-next/debs2015/Debs2015RunBoth.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q1CheckedProcessingRun.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q2CheckedProcessingRun.scala`
- `bench/debs2015/run_both_instrumented_matrix.sh`

What changed:

- RunBoth now accepts `q1_mode=rift-checked`.
- `Debs2015Q1CheckedProcessingRunner` and
  `Debs2015Q2CheckedProcessingRunner` expose capture-aware checked processor
  adapters, so RunBoth can call both checked processors in one shared byte-parser
  loop without exposing the region-captured object types.
- Checked Q1 processing keeps per-bucket event nodes in `childWindow` regions
  and rank/control objects in the parent checked stream.
- Checked Q2 processing keeps profit/empty window entries, median heaps, rank
  objects, taxi-id entries/bytes, and top-k storage in checked region-owned
  state, using explicit `childRegion(parent, window)` widening for parent-held
  child entries.

Important caveats:

- The first single-run integration rows are kept below for provenance. They are
  now superseded for bounded-sample performance by the 3-run median table.
- Q1/Q2 processing object graphs use the checked API. RunBoth still uses the
  existing trusted runtime helpers for the byte-reader buffer and output/latency
  scratch buffers, and previous-output snapshots are primitive heap control
  metadata. That is acceptable for this checkpoint because the target was
  integrating checked processing, not eliminating every remaining heap control
  object.
- `ProfitStats` remains heap control metadata in checked Q2, as in the
  standalone Q2 probe.

Commands:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=bench/debs2015/sample_both.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-checked-sample \
zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-checked-100000 \
zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-checked-1000000 \
zsh bench/debs2015/run_both_instrumented_matrix.sh

for n in 100000 1000000; do
  for run in 1 2 3; do
    DEBS2015_BOTH_BUILD=0 \
    DEBS2015_BOTH_MODES="heap rift-hp rift-streaming rift-checked" \
    DEBS2015_BOTH_INPUT="/tmp/debs2015-month1-${n}.csv" \
    DEBS2015_BOTH_OUTPUT_DIR="/tmp/debs2015-runboth-median-${n}-run${run}" \
      zsh bench/debs2015/run_both_instrumented_matrix.sh
  done
done
```

Validated:

- Scala-next sandbox compile passed.
- Sample, 100k sorted-sample, and 1M sorted-sample Q1/Q2 outputs matched after
  stripping only latency.
- The 100k and 1M 3-run median matrices completed for `heap`, `rift-hp`,
  `rift-streaming`, and `rift-checked`; every non-heap output matched heap
  after stripping only latency.

First single-run local results:

| Input | Mode | Events | Q1 outputs | Q2 outputs | Elapsed ms | GC ms | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| sample | heap | 2 | 1 | 2 | 8.263 | 6.524 | 28164096 |
| sample | rift-checked | 2 | 1 | 2 | 6.922 | 0.103 | 21381120 |
| 100k sorted | heap | 100000 | 5942 | 3246 | 472.415 | 8.389 | 55459840 |
| 100k sorted | rift-checked | 100000 | 5942 | 3246 | 447.141 | 0.072 | 39878656 |
| 1M sorted | heap | 1000000 | 32209 | 24969 | 4932.913 | 20.505 | 160022528 |
| 1M sorted | rift-checked | 1000000 | 32209 | 24969 | 4776.641 | 2.499 | 123977728 |

Selected 1M diagnostics:

| Mode | Q1 process ms | Q2 process ms | Q1 change ms | Q2 change ms | Rift op ms | Rift opens/closes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 1446.427 | 1376.742 | 118.922 | 188.439 | 0.000 | 0 / 0 |
| rift-checked | 1433.050 | 1313.680 | 84.682 | 142.722 | 13.988 | 32217 / 32217 |

3-run bounded-sample medians:

| Input | Mode | Elapsed ms | GC ms | RSS bytes | Q1 process ms | Q2 process ms | Q1 change ms | Q2 change ms | Rift op ms | Region objects | Q1 result arrays | Q2 result arrays |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 553.059 | 9.541 | 55459840 | 159.721 | 154.348 | 12.328 | 19.509 | 0.000 | 0 | 10 | 10 |
| 100k | Rift HPZone | 529.613 | 0.000 | 38895616 | 155.811 | 142.131 | 12.201 | 19.360 | 3.616 | 602460 | 10 | 10 |
| 100k | Rift Streaming | 512.808 | 0.000 | 38912000 | 150.434 | 135.390 | 11.972 | 19.050 | 2.610 | 602460 | 10 | 10 |
| 100k | rift-checked | 508.056 | 0.077 | 39878656 | 148.586 | 138.436 | 8.506 | 14.544 | 2.685 | 578538 | 0 | 0 |
| 1M | heap | 5363.257 | 21.226 | 159989760 | 1601.825 | 1547.054 | 125.201 | 201.664 | 0.000 | 0 | 10 | 10 |
| 1M | Rift HPZone | 5224.005 | 0.834 | 116867072 | 1587.368 | 1440.551 | 122.895 | 197.381 | 17.597 | 5494565 | 10 | 10 |
| 1M | Rift Streaming | 5209.104 | 0.862 | 116850688 | 1560.170 | 1423.187 | 124.926 | 199.633 | 17.497 | 5494565 | 10 | 10 |
| 1M | rift-checked | 5043.240 | 2.473 | 123977728 | 1508.359 | 1418.395 | 99.851 | 147.038 | 16.128 | 5333055 | 0 | 0 |

Selective allocation-attribution diagnostic:

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

for n in 100000 1000000; do
  SCALANATIVE_GC_ALLOC_STATS=1 \
  DEBS2015_BOTH_BUILD=0 \
  DEBS2015_BOTH_MODES="heap rift-checked" \
  DEBS2015_BOTH_INPUT="/tmp/debs2015-month1-${n}.csv" \
  DEBS2015_BOTH_OUTPUT_DIR="/tmp/debs2015-runboth-checked-alloc-${n}" \
    zsh bench/debs2015/run_both_instrumented_matrix.sh
done
```

These rows are single-run diagnostics. `SCALANATIVE_GC_ALLOC_STATS=1` times
heap allocation entry points, so elapsed time is not a headline throughput
number.

| Input | Mode | Total GC alloc calls | Total GC alloc bytes | Total GC alloc ms | Q1 process calls / bytes / ms | Q2 process calls / bytes / ms | Q1 snapshot calls / bytes / ms | Q2 snapshot calls / bytes / ms | Q2 output calls / bytes / ms | GC collect ms | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 674402 | 49121120 | 28.527 | 358318 / 12895904 / 9.890 | 227619 / 9676608 / 12.801 | 5942 / 545248 / 0.179 | 16230 / 1179936 / 0.442 | 63676 / 1067968 / 1.475 | 8.774 | 55443456 |
| 100k | rift-checked | 103818 | 8342448 | 2.954 | 4540 / 145312 / 0.116 | 10827 / 374160 / 0.262 | 5942 / 545248 / 0.202 | 16230 / 1179936 / 0.516 | 63674 / 1018784 / 1.433 | 0.076 | 39878656 |
| 1M | heap | 6025149 | 235159840 | 173.578 | 3284649 / 105197520 / 93.768 | 2088994 / 84904608 / 55.198 | 32209 / 3059888 / 7.144 | 124845 / 9087952 / 3.099 | 490897 / 8362256 / 10.483 | 20.773 | 160022528 |
| 1M | rift-checked | 752568 | 28785632 | 20.514 | 32191 / 1030144 / 0.795 | 68889 / 2275440 / 1.681 | 32209 / 3059888 / 1.174 | 124845 / 9087952 / 5.626 | 490892 / 7854272 / 10.815 | 2.191 | 123944960 |

Interpretation:

- This is the first end-to-end checked RunBoth evidence: both Q1 and Q2 checked
  processors run together over the shared byte-parser input and produce the same
  outputs as heap.
- The 3-run medians make the bounded-sample direction stronger. On 1M,
  `rift-checked` is fastest among the four modes in this matrix: heap
  `5363.257 ms`, trusted Streaming `5209.104 ms`, and checked `5043.240 ms`.
  The heap-to-checked elapsed delta is about `320 ms`, while measured GC
  collection time drops by about `18.8 ms`; the speedup is therefore not only
  shorter GC pauses. It is consistent with reduced heap allocation/object churn
  and different placement for result/ranking state.
- `rift-checked` also has zero Q1/Q2 result-array allocations in this matrix,
  compared with ten cached result-array allocations in heap and trusted Rift
  modes. This reflects checked processors' different result-storage path.
- The allocation-attribution diagnostic explains much of the memory-management
  side of the 1M result. Checked RunBoth drops total GC heap allocation calls
  from `6025149` to `752568`, rounded heap bytes from `235159840` to
  `28785632`, and measured heap allocation-call time from `173.578 ms` to
  `20.514 ms`. Most of the reduction is in Q1/Q2 processing, while output and
  snapshot/control allocations are still visible.
- Compared with trusted `rift-streaming`, checked RunBoth is faster in these
  medians despite slightly higher RSS and GC collection time at 1M. Do not
  overinterpret that as "checked is intrinsically faster": checked and trusted
  paths are not identical implementation shapes yet.
- Treat this as median-backed bounded-sample Phase 5/7 evidence, not final DEBS
  proof. The next evidence steps are SafeZone DEBS if a fair closeable backend
  is added, full-month scale, and stronger checked bucket/window safety beyond
  the current close helper.

## Checked Child-Window Close Discipline And Commix Control

Date: 2026-04-27

Purpose:

- Strengthen the checked `childWindow` API before adding more DEBS region
  objects.
- Make direct child-window close unavailable to normal user code; close should
  happen through an explicit parent-owned cleanup boundary.
- Run the first missing DEBS GC control by relinking the same RunBoth harness
  with Scala Native `GC.commix`.

Implementation changes:

- `RiftRegion.ChildWindow` now tracks open/closed state. Reusing a closed
  window through `RiftRegion.childRegion(parent, window)` throws
  `IllegalStateException`.
- `ChildWindow.close()` is now `private[memory]`, so user code cannot directly
  close a child window.
- New helper:

```scala
RiftRegion.closeChildWindow(parent, window) {
  // clear or detach parent-visible references to child-window values here
}
```

- Q1 checked processing now decrements route/rank metadata, unlinks the bucket,
  clears parent-visible child references, and closes the event child window
  inside this helper.
- Q2 checked processing now does the same for profit and empty-taxi bucket
  windows.

This is stronger checked close discipline, not a full affine lifetime system.
It does not prove that every possible reference to a child-window object is
cleared before close; it makes the close boundary explicit, rejects direct user
close, and gives the checker/runtime a single API shape to harden next.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=bench/debs2015/sample_both.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-childwindow-close-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Results:

- `sandbox3_next/compile` passed.
- `RiftRegionCheckedCompilerTest` passed `52/52`; the new negative probe
  rejects `window.close()` from user code.
- `RiftRegionCheckedTest` passed `14/14`; the new runtime probe confirms
  close-through-cleanup and child-region reuse rejection after close.
- Sample RunBoth `heap` vs `rift-checked` outputs matched after stripping
  latency.

Commix build/control commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
  "set nativeConfig ~= (_.withGC(scala.scalanative.build.GC.commix))" \
  nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-commix-checked-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

for n in 100000 1000000; do
  for run in 1 2 3; do
    DEBS2015_BOTH_BUILD=0 \
    DEBS2015_BOTH_MODES="heap rift-checked" \
    DEBS2015_BOTH_INPUT="/tmp/debs2015-month1-${n}.csv" \
    DEBS2015_BOTH_OUTPUT_DIR="/tmp/debs2015-runboth-commix-checked-median-${n}-run${run}" \
      zsh bench/debs2015/run_both_instrumented_matrix.sh
  done
done
```

Commix 3-run median controls:

| Input | Mode | Elapsed ms | GC ms | RSS bytes | Q1 process ms | Q2 process ms | Q1 change ms | Q2 change ms | Rift op ms | Region objects |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 492.372 | 4.942 | 56885248 | 142.856 | 122.890 | 11.613 | 18.903 | 0.000 | 0 |
| 100k | rift-checked | 472.477 | 0.092 | 39534592 | 137.291 | 117.245 | 8.499 | 14.236 | 1.942 | 578538 |
| 1M | heap | 4968.773 | 8.206 | 158924800 | 1466.113 | 1369.003 | 120.312 | 194.535 | 0.000 | 0 |
| 1M | rift-checked | 4745.291 | 1.137 | 125698048 | 1409.319 | 1251.375 | 86.510 | 144.526 | 11.444 | 5333055 |

Interpretation:

- The Commix medians match the Immix direction: checked Rift is faster at both
  100k and 1M, has much lower measured GC collection time, and uses less RSS.
- The 1M Commix elapsed delta is about `224 ms`, while measured GC collection
  time drops by only about `7 ms`. As with Immix, the win should be interpreted
  as allocation-placement/object-churn reduction plus locality/backend effects,
  not simply shorter GC pauses.
- One earlier single 1M Commix control had checked Rift slower than heap, so
  do not use single-run Commix rows as claims. The 3-run median is the current
  control result.
- SafeZone is still not a completed DEBS control. The current RunBoth harness
  has no fair closeable SafeZone-window mode equivalent to Rift child-window
  eviction. Existing SafeZone controls remain in the runtime/literature
  harnesses; a DEBS SafeZone control would need a dedicated backend before it
  is meaningful.

## Reusable Checked ChildBucket Abstraction

Date: 2026-04-27

Purpose:

- Move from per-benchmark raw `ChildWindow` fields toward a reusable checked
  stream-bucket abstraction.
- Keep the same Q1/Q2 logical programs and allocation placement while reducing
  the amount of benchmark-specific lifetime plumbing.

Implementation changes:

- Added `RiftRegion.ChildBucket`, a heap control wrapper around a child window
  plus the child region path used by checked stream operators.
- Added owner-token helpers:

```scala
val bucket = RiftRegion.childBucket
val childRegion = RiftRegion.childBucketRegion(parent, bucket)

RiftRegion.closeChildBucket(parent, bucket) {
  // unlink parent-visible child references
}
```

- Migrated Q1 checked processing buckets from raw `ChildWindow` fields to
  `ChildBucket`.
- Migrated Q2 checked profit/empty buckets from raw `ChildWindow` fields to
  `ChildBucket`.
- Added compiler probes for a child-bucket event graph and rejection of raw
  child-window access through `child.window`.
- Added a runtime smoke for close-through-cleanup and reuse-after-close
  rejection through `ChildBucket`.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"

DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=bench/debs2015/sample_both.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-childbucket-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Results:

- `sandbox3_next/compile` passed.
- `RiftRegionCheckedCompilerTest` passed `54/54`.
- `RiftRegionCheckedTest` passed `15/15`.
- Sample RunBoth `heap` vs `rift-checked` outputs matched after stripping
  latency.

Interpretation:

- This is a safety/API milestone, not a new performance claim.
- `ChildBucket` is still not a full affine close proof. It hides the raw child
  window from user code and centralizes child-bucket region access and close,
  but the checker still does not prove every parent-visible child reference is
  cleared before close.
- The next safety step is to make the bucket cleanup shape more typed, for
  example by moving parent unlink/clear obligations into a reusable operator
  or by adding compiler support for linear close tokens.

## JVM RunBoth Cross-check

Date: 2026-04-25

Purpose:

- Replace the earlier same-input JVM probe with a fairer JVM cross-check that
  runs the same logical RunBoth shape: byte-buffer CSV input, Q1 route ranking,
  Q2 profitability ranking, changed-output snapshots, and Q1/Q2 output files.
- Preserve the key comparison caveat: this is a Java/JVM port, not the same
  Scala source compiled by both backends. Elapsed JVM-vs-Scala-Native numbers
  include JIT/backend/code-shape differences. The GC counters are the main
  reason for this check.

Harness:

- `bench/debs2015/jvm/DebsJvmRunBoth.java`
- GC counters come from `GarbageCollectorMXBean`.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift-jvm-comparison
javac -d /tmp/debs-jvm-runboth bench/debs2015/jvm/DebsJvmRunBoth.java

java -cp /tmp/debs-jvm-runboth DebsJvmRunBoth \
  bench/debs2015/sample_both.csv \
  /tmp/debs-jvm-runboth-sample/q1.out \
  /tmp/debs-jvm-runboth-sample/q2.out

java -cp /tmp/debs-jvm-runboth DebsJvmRunBoth \
  /tmp/debs2015-month1-100000.csv \
  /tmp/debs-jvm-runboth-100000/q1.out \
  /tmp/debs-jvm-runboth-100000/q2.out

java -cp /tmp/debs-jvm-runboth DebsJvmRunBoth \
  /tmp/debs2015-month1-1000000.csv \
  /tmp/debs-jvm-runboth-1000000/q1.out \
  /tmp/debs-jvm-runboth-1000000/q2.out

java -Xms64m -Xmx64m -cp /tmp/debs-jvm-runboth DebsJvmRunBoth \
  /tmp/debs2015-month1-1000000.csv \
  /tmp/debs-jvm-runboth-1000000-xmx64m/q1.out \
  /tmp/debs-jvm-runboth-1000000-xmx64m/q2.out

java -Xms32m -Xmx32m -cp /tmp/debs-jvm-runboth DebsJvmRunBoth \
  /tmp/debs2015-month1-1000000.csv \
  /tmp/debs-jvm-runboth-1000000-xmx32m/q1.out \
  /tmp/debs-jvm-runboth-1000000-xmx32m/q2.out
```

Correctness checks:

- Sample output matched native heap after stripping only the measured latency
  column.
- 100k output matched native heap after stripping only the measured latency
  column: Q1 `5942` rows, Q2 `3246` rows.
- 1M output matched native heap after stripping only the measured latency
  column: Q1 `32209` rows, Q2 `24969` rows.

Single-run local results:

| Input | Runtime / heap | Elapsed ms | GC collections | GC ms | Q1 process ms | Q2 process ms | Q1 output ms | Q2 output ms | Heap used bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| sample | JVM default | 6.833 | 0 | 0 | 0.265 | 1.296 | 0.041 | 0.072 | 30411856 |
| 100k | JVM default | 243.645 | 0 | 0 | 44.061 | 101.694 | 13.743 | 10.802 | 67111936 |
| 100k | Scala Native heap | 1065.696 | 5 | 23.101 | 181.112 | 508.613 | 64.913 | 77.647 | n/a |
| 1M | JVM default | 1525.857 | 2 | 2 | 422.430 | 662.385 | 40.016 | 49.174 | 55945184 |
| 1M | JVM 64 MB | 1420.590 | 8 | 5 | 301.610 | 666.697 | 43.272 | 58.998 | 39230656 |
| 1M | JVM 32 MB | 1511.132 | 84 | 28 | 424.841 | 639.594 | 40.880 | 47.755 | 26273800 |
| 1M | JVM 16 MB | failed | n/a | n/a | n/a | n/a | n/a | n/a | `OutOfMemoryError` during Q2 engine initialization |
| 1M | Scala Native heap | 10361.643 | 15 | 340.698 | 1588.808 | 6193.386 | 341.279 | 360.115 | n/a |

Interpretation:

- The earlier Java numbers were fast because they were only a same-input GC
  probe, not full DEBS RunBoth. This cross-check includes Q1/Q2 ranking and
  output and therefore is a better control.
- The JVM still spends little time in GC on the full 1M RunBoth workload unless
  heap headroom is constrained. At 32 MB, GC rises to `28 ms`; at 16 MB the
  current full harness cannot initialize Q2's fixed arrays.
- The JVM elapsed time is much lower than Scala Native heap in this local
  single-run comparison, but that is not a Rift-vs-GC result. It reflects
  backend/JIT and Java-port differences as well as memory management.
- For Rift, this reinforces the current DEBS finding: the remaining bottleneck
  is mostly Q2 processing and output/formatting CPU, not GC alone. Region work
  still matters for footprint and bounded-lifetime placement, but DEBS is not
  currently a benchmark where GC tracing time dominates under roomy heaps.

## Full-Month RunBoth First Control

Date: 2026-04-27

Purpose:

- Move beyond bounded 100k/1M samples and verify that the checked RunBoth path
  can process the full January joined stream with heap-equivalent output.
- Test the current `ChildBucket` checked processing shape at full-month scale.
- Add external `/usr/bin/time` real/user/sys columns to the instrumented
  summary so future full-scale rows can distinguish benchmark work from
  scheduler/descheduling noise.

Input generation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

DEBS2015_MONTH=1 \
DEBS2015_LIMIT=0 \
DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-full.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh

wc -l /tmp/debs2015-month1-full.csv
```

Generated input:

- `/tmp/debs2015-month1-full.csv`
- size: about `2.5G`
- rows: `14776615`
- parsed rows in RunBoth: `14776529`
- invalid rows in RunBoth: `86`

First full-month control command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-childbucket \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Validation:

- Heap and `rift-checked` Q1/Q2 outputs matched after stripping only the
  measured latency column.
- Output counts matched: `q1_outputs=274667`, `q2_outputs=180215`.
- This validates full-month correctness for the current checked RunBoth path,
  but it is not a valid wall-clock performance comparison.

Single-run metrics:

| Input | Mode | Parsed | Invalid | Elapsed s | External real s | User+sys s | GC s | RSS MiB |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| month 1 full | heap | 14776529 | 86 | 69.754 | 69.78 | 69.64 | 0.288 | 596.0 |
| month 1 full | rift-checked | 14776529 | 86 | 1258.714 | 1258.76 | 63.97 | 0.143 | 981.7 |

Phase/counter details:

| Mode | Read s | Parse s | Q1 process s | Q2 process s | Rift op s | Opens/closes | Region objects | Mmap MiB |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 5.786 | 15.503 | 20.039 | 21.149 | 0.000 | 0 / 0 | 0 | 0.0 |
| rift-checked | 198.794 | 885.594 | 18.422 | 149.721 | 0.679 | 6890164 / 6890164 | 68834523 | 932.5 |

Harness update validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

zsh -n bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=bench/debs2015/sample_both.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-timecols-sample \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The sample verification passed and `summary.tsv` now includes:

- `max_rss_bytes`
- `time_real_s`
- `time_user_s`
- `time_sys_s`

Interpretation:

- The full-month `rift-checked` elapsed row is polluted by descheduling or
  equivalent external waiting. `/usr/bin/time -l` reports only about
  `63.97 s` of user+sys CPU time for that process while wall time is
  `1258.76 s`. Do not treat the `1258.714 s` in-process elapsed value as a
  Rift performance result.
- The full-month correctness result is still useful: the checked RunBoth path
  produced heap-equivalent Q1/Q2 outputs over all January rows.
- The memory/counter signal is real enough to guide the next step: full-month
  checked RunBoth opens and closes about `6.89M` child buckets, allocates about
  `68.8M` region objects, and reaches about `982 MiB` peak RSS versus heap's
  about `596 MiB` in this run. The next full-scale work should reduce bucket
  churn / slab retention and rerun under controlled load with external CPU
  time columns recorded.
- The next performance comparison should not use single full-month wall-clock
  rows. Use repeated controlled runs, record external user/sys time, and avoid
  running under scheduler pressure.

## Streaming First-Slab And Pool-Cap Control

Date: 2026-04-27

Purpose:

- Address the full-month scale signal from the first checked RunBoth control:
  the checked path ended with about `780 MiB` retained in the Rift closed-slab
  pool.
- Keep the change in the runtime backend rather than specializing DEBS. The
  logical Q1/Q2 program and checked `ChildBucket` lifetimes are unchanged.

Implementation:

- Streaming regions now use a small page-rounded first slab
  (`SCALANATIVE_RIFT_SMALL_SLAB_SIZE`, 4 KiB before page rounding) and still
  use regular 32 KiB slabs on overflow.
- The global closed-slab pool is capped at `128 MiB`; slabs beyond the cap are
  returned to the OS with `munmap`.
- Public Rift C and Scala APIs are unchanged.

Validation commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
  nativeLink

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-poolcap-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-poolcap-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-poolcap \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

The full-month checked outputs were compared against the earlier full-month
heap outputs from `/tmp/debs2015-runboth-fullmonth-childbucket` after stripping
only latency; both Q1 and Q2 matched.

Bounded-sample single runs after the runtime change:

| Input | Mode | Elapsed s | External user+sys s | GC s | RSS MiB | Rift op s | Pool MiB | Mmap MiB | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| 100k | heap | 0.443 | 0.44 | 0.008 | 52.9 | 0.000 | 0.0 | 0.0 | matched |
| 100k | rift-checked | 0.435 | 0.43 | 0.000 | 38.5 | 0.001 | 9.3 | 30.3 | matched |
| 1M | heap | 4.778 | 4.76 | 0.022 | 151.5 | 0.000 | 0.0 | 0.0 | matched |
| 1M | rift-checked | 4.381 | 4.37 | 0.002 | 121.0 | 0.012 | 65.8 | 94.8 | matched |

Full-month checked run after the runtime change:

| Mode | Parsed | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Pool MiB | Mmap MiB | Region objects | Opens/closes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rift-checked | 14776529 | 70.831 | 70.87 | 68.86 | 0.166 | 846.0 | 0.998 | 128.0 | 864.4 | 68834523 | 6890164 / 6890164 |

Comparison to the first full-month checked run:

| Metric | Before cap | After cap |
|---|---:|---:|
| External real time | 1258.76 s | 70.87 s |
| External user+sys time | 63.97 s | 68.86 s |
| Pool bytes at metrics snapshot | 780.4 MiB | 128.0 MiB |
| Peak RSS | 981.7 MiB | 846.0 MiB |
| Cumulative Rift mmap bytes | 932.5 MiB | 864.4 MiB |
| Rift op time | 0.679 s | 0.998 s |

Interpretation:

- The first full-month checked wall-clock row was indeed scheduler polluted;
  the capped run has wall time and user+sys time in the same range.
- The pool cap works: closed-slab pool residency is bounded at `128 MiB`.
- Peak RSS improves but remains much higher than heap. The remaining memory is
  not only closed-slab retention; long-lived parent-stream tables, rank objects,
  taxi-id metadata, and live window data still dominate at full-month scale.
- The cap adds some region-operation cost through extra unmaps, but Rift op
  time remains about `1 s` over the full month.
- This is still single-run full-month evidence. It should be promoted to a
  headline claim only after repeated controlled full-month runs and any needed
  SafeZone/Commix controls.

### Same-Run Full-Month Pool-Cap Control

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-poolcap-samerun \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The first sandboxed attempt completed the heap process but failed while
collecting `/usr/bin/time -l` fields because `sysctl kern.clockrate` was denied.
The command above was rerun with the benchmark script allowed to collect
external time/RSS fields. The completed run matched heap and `rift-checked`
Q1/Q2 outputs after stripping only latency.

Same-run full-month rows:

| Mode | Parsed | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Pool MiB | Mmap MiB | Region objects | Opens/closes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 14776529 | 71.919 | 71.96 | 69.58 | 0.323 | 579.1 | 0.000 | 0.0 | 0.0 | 0 | 0 / 0 |
| rift-checked | 14776529 | 77.947 | 77.98 | 74.24 | 0.190 | 695.2 | 1.137 | 128.0 | 864.4 | 68834523 | 6890164 / 6890164 |

Key phase rows:

| Mode | Read s | Parse s | Q1 process s | Q2 process s | Q1/Q2 change s | Q1/Q2 output s |
|---|---:|---:|---:|---:|---:|---:|
| heap | 8.036 | 15.790 | 20.138 | 20.508 | 4.471 | 1.141 |
| rift-checked | 8.377 | 16.579 | 21.829 | 24.290 | 3.496 | 1.334 |

Interpretation:

- This is the first same-binary full-month heap-vs-checked control after the
  pool cap. It supersedes comparing the checked-only pool-cap row against the
  older heap run when discussing full-month scale.
- Checked Rift is slower on this single full-month run by about `6.0 s`, while
  measured GC collection time drops by about `0.13 s`.
- Checked peak RSS is now much closer to heap than before the cap:
  `695.2 MiB` vs heap `579.1 MiB`, rather than the earlier `981.7 MiB`
  checked row.
- Rift operation time is about `1.14 s` across `6.89M` opens/closes and
  `68.8M` region object allocations. The remaining checked slowdown is mostly
  Q1/Q2 processing and parse/read differences, not region bookkeeping or GC
  collection.
- This still is not a headline full-DEBS median. The next full-month step is
  repeated same-run medians or targeted memory-pressure work on long-lived
  parent-stream state.

## Checked ChildBucket Single-Control-Object Control

Date: 2026-04-28

Purpose:

- Separate backend cost from checked-API shape after the post-pool-cap
  full-month rows.
- The trusted `rift-streaming` full-month control showed that the region
  backend itself can beat heap at full-month scale, while checked was still
  slower before this change.
- `ChildBucket` was allocating both a `ChildWindow` and a `ChildBucket` for
  every checked stream bucket. Since DEBS opens about `6.89M` child buckets on
  the full-month input, the extra heap control object was a plausible checked
  API overhead.

Implementation:

- `RiftRegion.ChildBucket` now owns the child `StreamingRegion` directly.
- `RiftRegion.childBucket` now opens one child region and creates one
  `ChildBucket` heap control object instead of a `ChildWindow` plus
  `ChildBucket`.
- `RiftRegion.closeChildBucket` now checks and closes the bucket directly while
  preserving the structured cleanup boundary.
- `ChildWindow` and `closeChildWindow` remain available for explicit window
  users.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest" \
  "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"

DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-childbucket-singleobject-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-streaming" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-poolcap-trusted-streaming \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-childbucket-singleobject \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="rift-checked heap" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-childbucket-singleobject-reverse \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-childbucket-repeat-a \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-fullmonth-childbucket-repeat-b \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

Results:

- Checked compiler probes passed `54/54`.
- Checked runtime tests passed `15/15`.
- 1M heap/checked outputs matched.
- Full-month trusted Streaming outputs matched heap.
- Full-month checked outputs matched heap in both run orders.

Full-month trusted backend control, before the `ChildBucket` API change:

| Mode | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Pool MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 73.888 | 73.92 | 70.90 | 0.360 | 595.7 | 0.000 | 0.0 |
| rift-streaming | 68.990 | 69.00 | 68.39 | 0.088 | 801.7 | 0.898 | 128.0 |

1M checked single run after the `ChildBucket` API change:

| Mode | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Pool MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 4.810 | 5.16 | 4.76 | 0.020 | 152.6 | 0.000 | 0.0 |
| rift-checked | 4.678 | 4.68 | 4.58 | 0.002 | 119.9 | 0.017 | 65.8 |

Full-month checked same-run control after the `ChildBucket` API change:

| Mode | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Pool MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 70.872 | 70.90 | 69.02 | 0.315 | 586.4 | 0.000 | 0.0 |
| rift-checked | 65.928 | 65.95 | 65.60 | 0.086 | 866.6 | 0.816 | 128.0 |

Full-month same-order 3-run control after the `ChildBucket` API change:

| Run | Mode | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Q1 process s | Q2 process s |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| first | heap | 70.872 | 70.90 | 69.02 | 0.315 | 586.4 | 0.000 | 19.676 | 19.823 |
| first | rift-checked | 65.928 | 65.95 | 65.60 | 0.086 | 866.6 | 0.816 | 18.863 | 19.106 |
| repeat A | heap | 73.166 | 73.20 | 71.05 | 0.334 | 576.4 | 0.000 | 20.534 | 22.154 |
| repeat A | rift-checked | 75.100 | 75.12 | 73.25 | 0.104 | 855.3 | 1.206 | 20.979 | 24.215 |
| repeat B | heap | 73.029 | 73.06 | 71.54 | 0.306 | 587.2 | 0.000 | 20.507 | 22.328 |
| repeat B | rift-checked | 67.670 | 67.69 | 67.18 | 0.085 | 983.9 | 0.885 | 19.233 | 20.142 |
| median | heap | 73.029 | 73.06 | 71.05 | 0.315 | 586.4 | 0.000 | 20.507 | 22.154 |
| median | rift-checked | 67.670 | 67.69 | 67.18 | 0.086 | 866.6 | 0.885 | 19.233 | 20.142 |

Reverse-order check:

| Mode | Elapsed s | External real s | User+sys s | GC s | RSS MiB | Rift op s | Evidence |
|---|---:|---:|---:|---:|---:|---:|---|
| rift-checked | 68.435 | 68.46 | 66.95 | 0.086 | 910.7 | 0.893 | usable single run |
| heap | 1871.373 | 1871.42 | 89.32 | 0.401 | 583.3 | 0.000 | invalid wall-clock row |

Interpretation:

- The trusted `rift-streaming` full-month row confirms that the post-pool-cap
  backend is not the current blocker: trusted Streaming was faster than heap in
  that same-run control.
- Collapsing `ChildBucket` from two heap control objects to one makes the
  checked full-month path faster on the same-order 3-run median:
  `67.670 s` checked versus `73.029 s` heap. This is a `5.359 s`, about
  `7.3%`, elapsed improvement. Measured GC collection time drops from
  `0.315 s` to `0.086 s`, so the elapsed win is larger than GC pause-time
  reduction alone.
- The result is still noisy: repeat A has checked slower (`75.100 s` checked
  versus `73.166 s` heap), while the other two same-order rows and the usable
  reverse-order checked row are faster. Treat the 3-run median as stronger than
  the earlier single-run result, not as the final full-DEBS claim.
- The reverse-order heap row is invalid as wall-clock evidence because real
  time was `1871.42 s` while user+sys time was only about `89.32 s`.
- RSS is not improved by this change in the full-month rows. Checked median RSS
  is `866.6 MiB` versus heap `586.4 MiB`, and one checked repeat reaches
  `983.9 MiB`, even though the closed-slab pool remains capped at `128 MiB`.
  Do not claim a memory-footprint win from this change.
- This is the first median-backed full-month checked DEBS result after the pool
  cap and `ChildBucket` simplification. It still needs SafeZone controls and a
  memory-pressure follow-up before becoming a final Phase 5 claim.

## Rift Mapped/Active Memory Diagnostics

Date: 2026-04-28

Purpose:

- Diagnose why full-month checked RunBoth has higher RSS than heap even after
  the closed-slab pool cap.
- Separate closed-slab retention from currently active region slabs and from
  application-requested region allocation bytes.
- Keep the diagnosis in the runtime counters rather than inferring from RSS
  alone.

Implementation:

- `RiftRuntime.c/h` now exposes current and peak mapped slab counters:
  `rift_mmap_slab_current`, `rift_mmap_slab_peak`,
  `rift_mmap_bytes_current`, and `rift_mmap_bytes_peak`.
- The runtime also exposes current and peak active-region slab counters:
  `rift_active_slab_current`, `rift_active_slab_peak`,
  `rift_active_bytes_current`, and `rift_active_bytes_peak`.
- Requested-allocation counters track raw bytes requested by region allocation:
  `rift_alloc_raw_bytes_total`, `rift_active_alloc_bytes_current`, and
  `rift_active_alloc_bytes_peak`.
- `RunBoth` and `run_both_instrumented_matrix.sh` include these fields in the
  metrics output.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DEBS2015_LIMIT=100000 \
DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-100000.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh

DEBS2015_BOTH_MODES="heap rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-100000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-requested-stats-100000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh

DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_MODES="rift-checked" \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-full.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-requested-stats-fullmonth-checked \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The 100k heap and checked outputs matched after stripping only latency. The
full-month diagnostic was run as checked-only and therefore should be used as a
memory attribution row, not a heap-vs-checked correctness comparison by itself.

100k requested-memory validation:

| Input | Mode | Elapsed ms | GC ms | RSS bytes | Mmap peak bytes | Active mapped peak bytes | Active requested peak bytes | Final mapped bytes | Pool bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 501.220 | 10.326 | 55476224 | 0 | 0 | 0 | 0 | 0 |
| 100k | rift-checked | 511.224 | 0.077 | 40239104 | 31817728 | 31670272 | 29575648 | 10010624 | 9748480 |

Full-month checked requested-memory diagnostic:

| Input | Mode | Elapsed s | GC s | RSS bytes | Mmap total bytes | Mmap peak bytes | Active mapped peak bytes | Active requested peak bytes | Final mapped bytes | Pool bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| month 1 full | rift-checked | 73.457 | 0.083 | 1086668800 | 906346496 | 906346496 | 904790016 | 823153856 | 134479872 | 134217728 |

Earlier active-only full-month diagnostic before requested-byte counters:

| Input | Mode | Elapsed s | RSS bytes | Mmap peak bytes | Active mapped peak bytes | Final mapped bytes | Pool bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| month 1 full | rift-checked | 66.857 | 1085194240 | 906346496 | 904790016 | 134479872 | 134217728 |

Interpretation:

- The closed-slab pool is not the main source of the full-month RSS regression:
  final active bytes return to zero, and final mapped bytes are about the
  configured `128 MiB` pool cap.
- Peak RSS instead tracks peak mapped region memory. In the requested-byte run,
  peak mapped bytes are `906.3 MB`, and peak active mapped bytes are
  `904.8 MB`.
- Most of that active mapped footprint is real live region payload under the
  current lifetimes: peak active requested bytes are `823.2 MB`, leaving about
  `81.6 MB` of slab/slack overhead at the peak.
- Lowering the pool cap further may reduce final residency but will not fix
  peak full-month RSS. The next memory-pressure step is lifetime attribution:
  identify which checked Q1/Q2 region families are simultaneously live and
  either shorten those lifetimes or make their storage more compact without
  changing the logical DEBS algorithm.
- This is a single full-month diagnostic, not a new headline performance
  median. Keep the existing 3-run full-month elapsed median separate from this
  attribution row.
