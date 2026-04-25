# StreamFlex Region Matrix

Status: StreamFlex-style methodology reproduction harness with validated local
smoke, default median, and allocation-pressure median runs.

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
