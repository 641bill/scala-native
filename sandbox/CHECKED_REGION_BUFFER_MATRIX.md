# Checked RegionBuffer Matrix

Status: focused checked-API methodology harness with validated smoke, default
local median, and a 2026-05-05 buffer-vs-array decomposition.

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

- `heap` / `heap-buffer`
- `heap-array`
- `rift-checked` / `rift-checked-buffer`
- `rift-checked-object-buffer`
- `rift-checked-array`

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

Buffer/array decomposition:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_BUFFER_BUILD=0 \
CHECKED_BUFFER_EPOCHS=10 \
CHECKED_BUFFER_RECORDS_PER_EPOCH=100000 \
CHECKED_BUFFER_BENCHMARK_RUNS=3 \
CHECKED_BUFFER_WARMUPS=1 \
CHECKED_BUFFER_MODES="heap-buffer heap-array rift-checked-buffer rift-checked-object-buffer rift-checked-array" \
CHECKED_BUFFER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-region-buffer-objectbuffer-default-2026-05-05 \
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

## Buffer/Array Decomposition, 2026-05-05

This follow-up was run after `ObjectAllocationLoweringMatrix` showed that raw
checked allocation is fast in a retained-region-array shape. The goal is to
separate growable `RegionBuffer` overhead from allocation/reclaim cost.

The matrix now includes exact-array controls:

- `heap-array`: same heap record objects, retained in an exact heap array;
- `rift-checked-array`: same checked region record objects, retained in a
  region-owned exact array, `Array[Record^{region}]^{region}`;
- `rift-checked-object-buffer`: same checked records retained in the existing
  fixed-capacity checked `ObjectBuffer`;
- existing `heap-buffer` and `rift-checked-buffer` remain growable-buffer
  controls.

Smoke command:

```sh
CHECKED_BUFFER_EPOCHS=2 \
CHECKED_BUFFER_RECORDS_PER_EPOCH=1000 \
CHECKED_BUFFER_BENCHMARK_RUNS=1 \
CHECKED_BUFFER_WARMUPS=0 \
CHECKED_BUFFER_MODES="heap-buffer heap-array rift-checked-buffer rift-checked-object-buffer rift-checked-array" \
CHECKED_BUFFER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-region-buffer-objectbuffer-smoke-2026-05-05 \
  zsh sandbox/run_checked_region_buffer_matrix.sh
```

Result: all five modes matched checksum.

Default 10 x 100k rows, initial capacity `16`, 3 measured runs:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `heap-buffer` | 34.097 | 8.493 | 0.000 | 0 | 0 / 0 / 0 | 22167552 |
| `heap-array` | 21.089 | 3.267 | 0.000 | 0 | 0 / 0 / 0 | 22429696 |
| `rift-checked-buffer` | 29.968 | 0.000 | 0.428 | 1000140 | 1 / 1 / 10 | 25542656 |
| `rift-checked-object-buffer` | 25.788 | 0.000 | 0.148 | 1000010 | 1 / 1 / 10 | 24379392 |
| `rift-checked-array` | 18.574 | 0.000 | 0.137 | 1000010 | 1 / 1 / 10 | 24379392 |

Pre-sized buffer control, initial capacity `100000`, 3 measured runs:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `heap-buffer` | 24.922 | 3.373 | 0.000 | 0 | 0 / 0 / 0 | 22396928 |
| `heap-array` | 21.230 | 3.386 | 0.000 | 0 | 0 / 0 / 0 | 22429696 |
| `rift-checked-buffer` | 25.980 | 0.000 | 0.174 | 1000010 | 1 / 1 / 10 | 24608768 |
| `rift-checked-object-buffer` | 25.408 | 0.000 | 0.136 | 1000010 | 1 / 1 / 10 | 24592384 |
| `rift-checked-array` | 18.466 | 0.000 | 0.107 | 1000010 | 1 / 1 / 10 | 24592384 |

Interpretation:

- Exact arrays are much faster than growable buffers for both heap and checked
  Rift, so the next checked-container target is not raw region allocation.
- Fixed-capacity `ObjectBuffer` removes the grow/copy overhead but still trails
  exact arrays: `25.788 ms` versus `18.574 ms` in the default row.
- Pre-sizing `RegionBuffer` now lands near `ObjectBuffer` (`25.980 ms` versus
  `25.408 ms`), so the remaining gap to `rift-checked-array` (`18.466 ms`) is
  generic buffer access/layout/dispatch overhead rather than growth alone.
- Application operators with known batch sizes should prefer operator-owned
  array/chunk fast paths. Existing `ObjectBuffer` is the bounded ergonomic
  fallback; `RegionBuffer` remains the growable fallback unless a focused
  layout/access patch narrows this gap.
