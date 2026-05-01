# Linear Road Region Matrix

Date: 2026-05-01

Status: generated Linear Road-shaped methodology probe plus first official
Linear Road test-data input wiring. This is still not an exact Linear Road
benchmark reproduction because it does not run the full query set or validator.

## Purpose

This matrix tests a stream shape that differs from DEBS, NEXMark-lite, Common
Crawl, and Wikimedia:

- generated position reports, or preloaded official Data Driver `.dat` rows
  from `LINEAR_ROAD_INPUT`;
- durable vehicle/segment state in heap primitive arrays;
- short-lived position report, toll output, and accident/congestion candidate
  objects in bucket lifetimes;
- bulk bucket close;
- sampled event and bucket-close latency metrics.

The heap, SafeZone, and Rift modes use the same logical program and differ only
in allocation placement for short-lived per-event objects.

## Modes

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: trusted HPZone per event bucket.
- `rift-streaming`: trusted Streaming zone per event bucket.

No checked mode is included. A checked Linear Road mode should be added only if
a focused checked operator first clears its gate for this shape.

## Queries

- `q0-reports`: one position-report object per event.
- `q1-tolls`: position report plus toll-output object; vehicle/segment counts
  remain durable heap primitive metadata.
- `q2-accidents`: position report plus accident/congestion candidate object;
  vehicle stop counters and segment accident state remain durable heap primitive
  metadata.

## Commands

Compile:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```bash
LINEAR_ROAD_EVENTS=20000 \
LINEAR_ROAD_BENCHMARK_RUNS=1 \
LINEAR_ROAD_WARMUPS=0 \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-region-smoke \
zsh sandbox/run_linear_road_region_matrix.sh
```

Official test-data smoke:

```bash
LINEAR_ROAD_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/linear-road/test-data/datafile20seconds.dat \
LINEAR_ROAD_EVENTS=20000 \
LINEAR_ROAD_BENCHMARK_RUNS=1 \
LINEAR_ROAD_WARMUPS=0 \
LINEAR_ROAD_MODES="heap rift-hp" \
LINEAR_ROAD_QUERIES="q1-tolls" \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-real-smoke \
zsh sandbox/run_linear_road_region_matrix.sh
```

100k 3-run medians:

```bash
LINEAR_ROAD_BUILD=0 \
LINEAR_ROAD_EVENTS=100000 \
LINEAR_ROAD_BENCHMARK_RUNS=3 \
LINEAR_ROAD_WARMUPS=1 \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-region-100k \
zsh sandbox/run_linear_road_region_matrix.sh
```

1M 3-run medians:

```bash
LINEAR_ROAD_BUILD=0 \
LINEAR_ROAD_EVENTS=1000000 \
LINEAR_ROAD_BENCHMARK_RUNS=3 \
LINEAR_ROAD_WARMUPS=1 \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-region-1m \
zsh sandbox/run_linear_road_region_matrix.sh
```

## Validation

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- 20k smoke matched checksum/output count across all queries and modes.
- 100k and 1M 3-run medians matched checksum/output count across all queries
  and modes.
- No 10M scale check was run because no 1M row cleared the continuation gate.
- Official 20-second Data Driver input smoke matched checksum/output count for
  heap and HPZone with `input=real-linear-road-preloaded`. This validates
  input wiring only, not full Linear Road semantics.

## Real Test-Data Smoke

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/linear-road/test-data/datafile20seconds.dat`

| Query | Mode | Median ms | GC ms | Rift op ms | Objects | Opens/closes | RSS bytes | Event p95 us | Bucket close max us | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q1-tolls | heap | 2.781 | 0.000 | 0.000 | 0 | 0 / 0 | 8044544 | 0.833 | 11.083 | 558 |
| q1-tolls | rift-hp | 2.303 | 0.000 | 0.008 | 558 | 1 / 1 | 8093696 | 21.791 | 8.333 | 558 |

## Official Data Driver Preloaded Results

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/linear-road/test-data/datafile3hours.dat`

Command:

```bash
LINEAR_ROAD_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/linear-road/test-data/datafile3hours.dat \
LINEAR_ROAD_EVENTS=100000 \
LINEAR_ROAD_BENCHMARK_RUNS=3 \
LINEAR_ROAD_WARMUPS=1 \
LINEAR_ROAD_QUERIES="q0-reports q1-tolls q2-accidents" \
LINEAR_ROAD_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-real-100k \
zsh sandbox/run_linear_road_region_matrix.sh

LINEAR_ROAD_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/linear-road/test-data/datafile3hours.dat \
LINEAR_ROAD_EVENTS=1000000 \
LINEAR_ROAD_BENCHMARK_RUNS=3 \
LINEAR_ROAD_WARMUPS=1 \
LINEAR_ROAD_QUERIES="q0-reports q1-tolls q2-accidents" \
LINEAR_ROAD_MODES="heap safezone-current safezone-improved rift-hp rift-streaming" \
LINEAR_ROAD_OUTPUT_DIR=/tmp/linear-road-real-1m \
LINEAR_ROAD_BUILD=0 \
zsh sandbox/run_linear_road_region_matrix.sh
```

These rows preload official Data Driver position reports before timing. They
are stream-memory probes over official rows, not full Linear Road validation.
Checksums and output counts matched across all modes.

100k medians:

| Query | Mode | Median ms | GC ms | Rift op ms | Objects | Opens/closes | RSS bytes | Event p95 us | Bucket close max us | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-reports | heap | 11.517 | 0.000 | 0.000 | 0 | 0 / 0 | 122421248 | 0.167 | 258.250 | 100000 |
| q0-reports | safezone-improved | 12.103 | 0.000 | 0.000 | 0 | 0 / 0 | 95797248 | 0.208 | 270.500 | 100000 |
| q0-reports | rift-hp | 12.185 | 0.000 | 0.012 | 100000 | 4 / 4 | 96190464 | 0.208 | 257.292 | 100000 |
| q0-reports | rift-streaming | 12.137 | 0.000 | 0.013 | 100000 | 4 / 4 | 96108544 | 0.167 | 258.834 | 100000 |
| q1-tolls | heap | 19.149 | 0.000 | 0.000 | 0 | 0 / 0 | 147210240 | 0.250 | 516.916 | 200000 |
| q1-tolls | safezone-improved | 20.322 | 0.000 | 0.000 | 0 | 0 / 0 | 128417792 | 0.292 | 537.583 | 200000 |
| q1-tolls | rift-hp | 20.558 | 0.000 | 0.025 | 200000 | 4 / 4 | 128204800 | 0.292 | 514.125 | 200000 |
| q1-tolls | rift-streaming | 20.391 | 0.000 | 0.025 | 200000 | 4 / 4 | 128155648 | 0.250 | 514.875 | 200000 |
| q2-accidents | heap | 19.945 | 0.000 | 0.000 | 0 | 0 / 0 | 147226624 | 0.250 | 518.333 | 200000 |
| q2-accidents | safezone-improved | 21.019 | 0.000 | 0.000 | 0 | 0 / 0 | 128417792 | 0.292 | 541.000 | 200000 |
| q2-accidents | rift-hp | 20.984 | 0.000 | 0.026 | 200000 | 4 / 4 | 128696320 | 0.291 | 519.083 | 200000 |
| q2-accidents | rift-streaming | 21.325 | 0.000 | 0.022 | 200000 | 4 / 4 | 128614400 | 0.292 | 524.125 | 200000 |

1M medians:

| Query | Mode | Median ms | GC ms | Rift op ms | Objects | Opens/closes | RSS bytes | Event p95 us | Bucket close max us | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-reports | heap | 88.750 | 0.000 | 0.000 | 0 | 0 / 0 | 816283648 | 0.167 | 266.792 | 1000000 |
| q0-reports | safezone-improved | 103.086 | 0.000 | 0.000 | 0 | 0 / 0 | 686751744 | 0.208 | 283.584 | 1000000 |
| q0-reports | rift-hp | 100.949 | 0.000 | 0.125 | 1000000 | 40 / 40 | 686882816 | 0.167 | 281.709 | 1000000 |
| q0-reports | rift-streaming | 99.769 | 0.000 | 0.124 | 1000000 | 40 / 40 | 687063040 | 0.167 | 275.666 | 1000000 |
| q1-tolls | heap | 162.668 | 0.000 | 0.000 | 0 | 0 / 0 | 819593216 | 0.209 | 537.375 | 2000000 |
| q1-tolls | safezone-improved | 179.205 | 0.000 | 0.000 | 0 | 0 / 0 | 829784064 | 0.291 | 575.000 | 2000000 |
| q1-tolls | rift-hp | 180.277 | 0.000 | 0.241 | 2000000 | 40 / 40 | 829603840 | 0.250 | 519.500 | 2000000 |
| q1-tolls | rift-streaming | 180.964 | 0.000 | 0.227 | 2000000 | 40 / 40 | 829521920 | 0.250 | 529.000 | 2000000 |
| q2-accidents | heap | 167.811 | 0.000 | 0.000 | 0 | 0 / 0 | 819593216 | 0.250 | 612.292 | 2000000 |
| q2-accidents | safezone-improved | 199.116 | 0.000 | 0.000 | 0 | 0 / 0 | 821641216 | 0.292 | 597.292 | 2000000 |
| q2-accidents | rift-hp | 202.528 | 0.000 | 0.299 | 2000000 | 40 / 40 | 829636608 | 0.292 | 781.125 | 2000000 |
| q2-accidents | rift-streaming | 198.863 | 0.000 | 0.300 | 2000000 | 40 / 40 | 829554688 | 0.292 | 685.000 | 2000000 |

Interpretation:

- Official-input rows are more negative than the generated Linear Road rows:
  measured timed GC is zero in all 100k and 1M timed sections.
- Heap is fastest for all three 1M queries. Region modes lower RSS only for
  q0 reports; they do not improve RSS on q1/q2 toll/accident outputs.
- Current SafeZone remains much slower than improved SafeZone, but improved
  SafeZone is still slower than heap on this preloaded official input.

## 100k Results

| Query | Mode | Median ms | GC ms | Rift op ms | Objects | Opens/closes | RSS bytes | Event p95 us | Bucket close max us | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-reports | heap | 10.219 | 0.000 | 0.000 | 0 | 0 / 0 | 39092224 | 0.125 | 259.125 | 100000 |
| q0-reports | safezone-current | 12.606 | 0.000 | 0.000 | 0 | 0 / 0 | 26574848 | 0.125 | 874.125 | 100000 |
| q0-reports | safezone-improved | 11.082 | 0.000 | 0.000 | 0 | 0 / 0 | 26640384 | 0.167 | 271.292 | 100000 |
| q0-reports | rift-hp | 11.202 | 0.000 | 0.016 | 100000 | 4 / 4 | 26542080 | 0.125 | 264.083 | 100000 |
| q0-reports | rift-streaming | 11.080 | 0.000 | 0.016 | 100000 | 4 / 4 | 26476544 | 0.125 | 259.542 | 100000 |
| q1-tolls | heap | 18.797 | 0.000 | 0.000 | 0 | 0 / 0 | 74809344 | 0.208 | 538.833 | 200000 |
| q1-tolls | safezone-current | 26.494 | 0.000 | 0.000 | 0 | 0 / 0 | 53657600 | 0.250 | 2931.917 | 200000 |
| q1-tolls | safezone-improved | 20.198 | 0.000 | 0.000 | 0 | 0 / 0 | 53624832 | 0.292 | 589.750 | 200000 |
| q1-tolls | rift-hp | 20.115 | 0.000 | 0.036 | 200000 | 4 / 4 | 53428224 | 0.291 | 562.334 | 200000 |
| q1-tolls | rift-streaming | 19.898 | 0.000 | 0.025 | 200000 | 4 / 4 | 53395456 | 0.250 | 519.708 | 200000 |
| q2-accidents | heap | 19.025 | 0.000 | 0.000 | 0 | 0 / 0 | 74809344 | 0.250 | 520.334 | 200000 |
| q2-accidents | safezone-current | 25.476 | 0.000 | 0.000 | 0 | 0 / 0 | 53592064 | 0.208 | 2727.292 | 200000 |
| q2-accidents | safezone-improved | 20.453 | 0.000 | 0.000 | 0 | 0 / 0 | 53575680 | 0.209 | 538.042 | 200000 |
| q2-accidents | rift-hp | 21.206 | 0.000 | 0.026 | 200000 | 4 / 4 | 53428224 | 0.209 | 519.375 | 200000 |
| q2-accidents | rift-streaming | 21.314 | 0.000 | 0.026 | 200000 | 4 / 4 | 53362688 | 0.209 | 517.708 | 200000 |

## 1M Results

| Query | Mode | Median ms | GC ms | Rift op ms | Objects | Opens/closes | RSS bytes | Event p95 us | Bucket close max us | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-reports | heap | 93.798 | 10.413 | 0.000 | 0 | 0 / 0 | 75038720 | 0.125 | 371.708 | 1000000 |
| q0-reports | safezone-current | 115.970 | 0.000 | 0.000 | 0 | 0 / 0 | 69369856 | 0.125 | 1158.416 | 1000000 |
| q0-reports | safezone-improved | 92.615 | 0.000 | 0.000 | 0 | 0 / 0 | 69386240 | 0.125 | 312.125 | 1000000 |
| q0-reports | rift-hp | 94.908 | 0.000 | 0.153 | 1000000 | 40 / 40 | 69271552 | 0.125 | 378.541 | 1000000 |
| q0-reports | rift-streaming | 97.679 | 0.000 | 0.153 | 1000000 | 40 / 40 | 69238784 | 0.125 | 324.042 | 1000000 |
| q1-tolls | heap | 170.464 | 24.819 | 0.000 | 0 | 0 / 0 | 146604032 | 0.167 | 604.417 | 2000000 |
| q1-tolls | safezone-current | 269.848 | 0.000 | 0.000 | 0 | 0 / 0 | 160989184 | 0.208 | 2975.542 | 2000000 |
| q1-tolls | safezone-improved | 196.138 | 0.000 | 0.000 | 0 | 0 / 0 | 160923648 | 0.209 | 942.583 | 2000000 |
| q1-tolls | rift-hp | 191.896 | 0.000 | 0.335 | 2000000 | 40 / 40 | 160792576 | 0.209 | 1084.042 | 2000000 |
| q1-tolls | rift-streaming | 228.226 | 0.000 | 0.379 | 2000000 | 40 / 40 | 160727040 | 0.292 | 1010.583 | 2000000 |
| q2-accidents | heap | 194.520 | 27.604 | 0.000 | 0 | 0 / 0 | 146587648 | 0.209 | 570.375 | 2000000 |
| q2-accidents | safezone-current | 286.844 | 0.000 | 0.000 | 0 | 0 / 0 | 160956416 | 0.209 | 2953.667 | 2000000 |
| q2-accidents | safezone-improved | 215.808 | 0.000 | 0.000 | 0 | 0 / 0 | 160972800 | 0.250 | 783.333 | 2000000 |
| q2-accidents | rift-hp | 203.793 | 0.000 | 0.300 | 2000000 | 40 / 40 | 160776192 | 0.209 | 569.292 | 2000000 |
| q2-accidents | rift-streaming | 217.685 | 0.000 | 0.365 | 2000000 | 40 / 40 | 160727040 | 0.250 | 1187.833 | 2000000 |

## Interpretation

- Linear Road-shaped generated streams did not clear the Rift case-study gate.
- At 1M, q0 is a near-tie where improved SafeZone is fastest.
- At 1M, heap is fastest for q1 tolls and q2 accidents despite measurable GC.
  HPZone removes measured GC and is faster than improved SafeZone on q1/q2, but
  remains slower than heap.
- Region modes do not reliably improve RSS on q1/q2 at this bucket size; the
  per-bucket retained event/output objects dominate the live set.
- Current SafeZone is much worse than improved SafeZone on close-heavy q1/q2,
  confirming that the improved SafeZone baseline must remain in every claim.
- The next step should not be Linear Road-specific tuning. Either test real
  Wikimedia TSV input or return to focused checked-operator overhead work.
