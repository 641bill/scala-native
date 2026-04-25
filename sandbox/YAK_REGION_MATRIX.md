# Yak Region Matrix

Status: Yak-style methodology reproduction harness with validated smoke,
default median, and pressure median runs.

The harness is implemented in
`sandbox/src/main/scala-next/YakRegionMatrix.scala` and run with
`sandbox/run_yak_region_instrumented_matrix.sh`.

## Benchmark Intent

This is not an exact Yak artifact reproduction. Yak evaluated distributed
Hyracks, Hadoop, and GraphChi workloads on the JVM. This local Scala Native
harness reproduces the key comparison shape:

- durable control metadata stays on the GC heap;
- high-volume data-path objects are allocated per epoch;
- epoch-local data dies together at the epoch boundary;
- heap, SafeZone, and Rift variants run the same logical program.

The current local workloads are:

- `wordcount`: durable heap dictionary counters, epoch-local token objects.
- `graphstep`: durable heap vertex state, epoch-local graph update messages.

The stream/data objects are ordinary Scala objects. Heap mode allocates them
with `new`; SafeZone mode allocates them in Scala Native SafeZone; Rift modes
allocate them with `region.alloc`.

## Default Configuration

| Environment variable | Default |
|---|---:|
| `YAK_EPOCHS` | `20` |
| `YAK_RECORDS_PER_EPOCH` | `100000` |
| `YAK_KEY_SPACE` | `65536` |
| `YAK_VERTICES` | `100000` |
| `YAK_MESSAGES_PER_EPOCH` | `100000` |
| `YAK_WARMUPS` | `1` |
| `YAK_BENCHMARK_RUNS` | `3` |

Use `YAK_WORKLOAD=wordcount`, `graphstep`, or `all`.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

## Result Status

Compile, smoke, default local medians, and one epoch-pressure run have been
recorded. These are local Yak-style methodology numbers, not an exact Yak
artifact reproduction.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-region-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. During the
sbt native-link setup the JVM emitted a code-cache warning, but the native
binary linked and all heap/SafeZone/Rift checksums matched.

## Native-Only Local 3-Run Median, Default Configuration

Configuration:

- `YAK_EPOCHS=20`
- `YAK_RECORDS_PER_EPOCH=100000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=100000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 51.961 | 12.062 | 0.000 | 2000000 | 65536 | 39272448 |
| wordcount | current SafeZone | 41.143 | 0.000 | 0.000 | 2000000 | 65536 | 42237952 |
| wordcount | improved SafeZone | 40.005 | 0.000 | 0.000 | 2000000 | 65536 | 42287104 |
| wordcount | Rift HPZone | 42.075 | 0.000 | 0.053 | 2000000 | 65536 | 42188800 |
| wordcount | Rift Streaming | 41.873 | 0.000 | 0.053 | 2000000 | 65536 | 42172416 |
| graphstep | heap | 56.235 | 9.283 | 0.000 | 2000000 | 100000 | 39272448 |
| graphstep | current SafeZone | 47.417 | 0.000 | 0.000 | 2000000 | 100000 | 42237952 |
| graphstep | improved SafeZone | 46.859 | 0.000 | 0.000 | 2000000 | 100000 | 42287104 |
| graphstep | Rift HPZone | 48.126 | 0.000 | 0.077 | 2000000 | 100000 | 42188800 |
| graphstep | Rift Streaming | 46.909 | 0.000 | 0.072 | 2000000 | 100000 | 42172416 |

Interpretation:

- This reproduces the Yak control/data split locally: durable counters or vertex
  state remain on heap, while two million epoch-local data objects are allocated
  per workload.
- Heap pays measurable GC time. SafeZone and Rift remove the measured heap GC
  path for the epoch-local data objects.
- Rift beats heap on both workloads, but improved SafeZone is the fastest or
  effectively tied at this default scale.
- Rift operation time is small: below `0.1 ms` median per workload.

## Epoch-Pressure 3-Run Median

Configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=250000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 240.541 | 35.225 | 0.000 | 10000000 | 65536 | 75071488 |
| wordcount | current SafeZone | 237.253 | 0.000 | 0.000 | 10000000 | 65536 | 83181568 |
| wordcount | improved SafeZone | 197.373 | 0.000 | 0.000 | 10000000 | 65536 | 83197952 |
| wordcount | Rift HPZone | 214.210 | 0.000 | 0.314 | 10000000 | 65536 | 83099648 |
| wordcount | Rift Streaming | 209.944 | 0.000 | 0.275 | 10000000 | 65536 | 83066880 |
| graphstep | heap | 239.001 | 29.774 | 0.000 | 10000000 | 100000 | 75071488 |
| graphstep | current SafeZone | 275.357 | 0.000 | 0.000 | 10000000 | 100000 | 83181568 |
| graphstep | improved SafeZone | 206.425 | 0.000 | 0.000 | 10000000 | 100000 | 83197952 |
| graphstep | Rift HPZone | 229.265 | 0.000 | 0.406 | 10000000 | 100000 | 83099648 |
| graphstep | Rift Streaming | 224.885 | 0.000 | 0.365 | 10000000 | 100000 | 83066880 |

Interpretation:

- The pressure run scales the epoch-local data path to ten million ordinary
  Scala data objects per workload.
- Rift again removes measured heap GC and beats heap on elapsed time.
- Improved SafeZone is a stronger baseline than Rift in both pressure
  workloads, so this is not a Rift-over-SafeZone win.
- Current SafeZone is workload-sensitive: it is close to heap on `wordcount`
  but slower than heap on `graphstep`.

Caveats:

- This is not an exact Yak reproduction. It does not run Hyracks, Hadoop, or
  GraphChi, and it does not implement Yak's dynamic promotion/write-barrier
  mechanism.
- The benchmark's purpose is to isolate the control/data split and epoch-local
  lifetime shape in Scala Native. Do not compare the absolute speedups directly
  to Yak's distributed JVM results.
- The harness uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future safe capture-checked API.
