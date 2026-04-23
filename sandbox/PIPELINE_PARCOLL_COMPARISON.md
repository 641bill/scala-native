# Pipeline Parallel-Collections Comparison

Date: 2026-04-23

This note compares the current Rift pipeline surrogate with the amordo
`ZoneParVector` benchmark on the same machine. The comparison is useful, but
not yet apples-to-apples:

- The amordo benchmark uses `ZoneParVector`/`ParVector` collection APIs.
- The Rift pipeline surrogate uses raw Scala arrays allocated on heap,
  SafeZone, or Rift regions.
- The Rift surrogate is therefore a lower-level allocator/kernel comparison,
  not proof that Rift has replaced a parallel collection implementation.

## amordo BroomPipelineBenchmark

Command:

```sh
cd /Users/siyaoliu/rift/scala-parallel-collections-amordo
JAVA_HOME="$(cs java-home --jvm temurin:17)" PATH="$JAVA_HOME/bin:$PATH" \
  sbt "junitNative/testOnly scala.collection.parallel.immutable.BroomPipelineBenchmark"
```

Configuration from the benchmark source:

- records: `100000`
- keys: `64`
- runs: `5`

| Mode | Samples ms | Median ms |
|---|---|---:|
| ZoneParVector explicit stage release | `56.981, 58.105, 55.068, 54.566, 53.553` | 55.07 |
| on-heap ParVector | `24.349, 27.479, 31.290, 37.257, 30.753` | 30.75 |
| ZoneParVector region per stage | `30.360, 30.689, 31.898, 29.868, 29.758` | 30.36 |

## Rift Raw-Array Pipeline Surrogate

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
PIPELINE_SIZE=100000 PIPELINE_KEYS=64 PIPELINE_BENCHMARK_RUNS=5 PIPELINE_WARMUPS=1 \
  zsh sandbox/run_pipeline_runtime_matrix.sh
```

| Mode | Samples ms | Median ms |
|---|---|---:|
| Immix heap raw arrays | `1.771, 1.831, 1.845, 1.870, 1.888` | 1.845 |
| current SafeZone raw arrays | `1.962, 1.969, 1.976, 2.113, 2.114` | 1.976 |
| improved SafeZone raw arrays | `1.884, 1.906, 1.907, 1.915, 1.999` | 1.907 |
| Rift HPZone raw arrays | `2.487, 2.490, 2.502, 2.593, 2.705` | 2.502 |
| Rift Streaming raw arrays | `2.505, 2.549, 2.652, 2.838, 2.881` | 2.652 |

## Interpretation

- The raw-array Rift pipeline is much faster than the amordo `ZoneParVector`
  benchmark, but this mostly measures collection overhead versus tight array
  loops.
- Within the raw-array surrogate, heap and SafeZone are slightly faster than
  Rift at `100000` records; this benchmark is not currently a Rift win.
- The fair next comparison is a `RiftParVector` or equivalent region-backed
  collection using the same `ParVector`/`ZoneParVector` API shape.
