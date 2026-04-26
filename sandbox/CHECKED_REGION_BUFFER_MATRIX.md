# Checked RegionBuffer Matrix

Status: focused checked-API methodology harness with validated smoke and one
default local median.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedRegionBufferMatrix.scala` and run with
`sandbox/run_checked_region_buffer_matrix.sh`.

## Intent

This is not DEBS or a prior-paper artifact reproduction. It is a small
benchmark-shaped check that the safe API can handle ordinary Scala data objects
inside a growable region-backed container.

Heap and Rift use the same logical program:

- each epoch appends ordinary records to a growable buffer;
- the records are folded into durable heap counters;
- the epoch-local records and buffer backing arrays die at the epoch boundary;
- heap mode allocates records and backing arrays on the GC heap;
- `rift-checked` mode allocates records and `RegionBuffer` backing arrays in a
  checked streaming/reset region.

The purpose is to connect Phase 7/8 safety work back to performance evidence:
`RegionBuffer` is a checked owner-token API, not a trusted `HPZone` benchmark
shortcut.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_BUFFER_EPOCHS` | `10` |
| `CHECKED_BUFFER_RECORDS_PER_EPOCH` | `100000` |
| `CHECKED_BUFFER_KEY_SPACE` | `65536` |
| `CHECKED_BUFFER_INITIAL_CAPACITY` | `16` |
| `CHECKED_BUFFER_WARMUPS` | `1` |
| `CHECKED_BUFFER_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `rift-checked`

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_BUFFER_EPOCHS=2 CHECKED_BUFFER_RECORDS_PER_EPOCH=1000 \
CHECKED_BUFFER_BENCHMARK_RUNS=1 CHECKED_BUFFER_WARMUPS=0 \
CHECKED_BUFFER_OUTPUT_DIR=/tmp/checked-region-buffer-smoke \
  zsh sandbox/run_checked_region_buffer_matrix.sh
```

Default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_BUFFER_BUILD=0 CHECKED_BUFFER_OUTPUT_DIR=/tmp/checked-region-buffer-default \
  zsh sandbox/run_checked_region_buffer_matrix.sh
```

## Validation

Validation run on 2026-04-26:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

CHECKED_BUFFER_EPOCHS=2 CHECKED_BUFFER_RECORDS_PER_EPOCH=1000 \
CHECKED_BUFFER_BENCHMARK_RUNS=1 CHECKED_BUFFER_WARMUPS=0 \
CHECKED_BUFFER_OUTPUT_DIR=/tmp/checked-region-buffer-smoke \
  zsh sandbox/run_checked_region_buffer_matrix.sh

CHECKED_BUFFER_BUILD=0 CHECKED_BUFFER_OUTPUT_DIR=/tmp/checked-region-buffer-default \
  zsh sandbox/run_checked_region_buffer_matrix.sh
```

The smoke run native-linked `CheckedRegionBufferMatrix` and both modes matched
checksums. The default run reused the linked binary.

During the first native-link run, the JVM emitted code-cache warnings during
sbt compilation. The native binary still linked and both heap/Rift runs
completed.

## Smoke Result

Configuration:

- `CHECKED_BUFFER_EPOCHS=2`
- `CHECKED_BUFFER_RECORDS_PER_EPOCH=1000`
- `CHECKED_BUFFER_BENCHMARK_RUNS=1`
- `CHECKED_BUFFER_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 0.110 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 5013504 | 3384155569604346698 |
| rift-checked | 0.105 | 0.000 | 0.003 | 2014 | 1 / 1 / 2 | 4931584 | 3384155569604346698 |

## Default Local Median

Configuration:

- `CHECKED_BUFFER_EPOCHS=10`
- `CHECKED_BUFFER_RECORDS_PER_EPOCH=100000`
- `CHECKED_BUFFER_KEY_SPACE=65536`
- `CHECKED_BUFFER_INITIAL_CAPACITY=16`
- `CHECKED_BUFFER_BENCHMARK_RUNS=3`
- `CHECKED_BUFFER_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 33.825 | 7.611 | 0.000 | 0 | 0 / 0 / 0 | 22151168 | 1292086841232902356 |
| rift-checked | 28.654 | 0.000 | 0.301 | 1000140 | 1 / 1 / 10 | 25509888 | 1292086841232902356 |

Interpretation:

- This is the first local median where the checked safe API, not trusted
  `RiftRegion.open`, owns the region allocation boundary for a growable
  collection.
- The checked Rift path removes measured heap GC for the epoch-local records
  and is about 15% faster on this focused workload.
- Rift operation time is small relative to elapsed time (`0.301 ms` median).
- Region objects exceed the one million records because each growth step
  allocates a new backing object array in the region.
- Peak RSS is higher for `rift-checked` in this small run because old backing
  arrays remain until epoch reset. That is the intended region tradeoff and
  should be monitored on larger collection workloads.

Caveats:

- This is a focused checked-container workload, not application-level DEBS
  evidence.
- It compares heap allocation against checked streaming/reset regions only; it
  does not include SafeZone, improved SafeZone, or Commix.
- It is not a replacement for a future `RiftVector`/parallel-collections API.
