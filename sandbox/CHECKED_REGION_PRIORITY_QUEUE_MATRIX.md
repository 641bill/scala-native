# Checked RegionPriorityQueue Matrix

Status: focused checked-API methodology harness with validated smoke and one
default local median.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedRegionPriorityQueueMatrix.scala` and run
with `sandbox/run_checked_region_priority_queue_matrix.sh`.

## Intent

This is not DEBS and not a prior-paper artifact reproduction. It is a focused
check that Rift now has a reusable checked ranking/top-k primitive instead of
only benchmark-local heap arrays.

Heap and Rift use the same logical program:

- each epoch appends ordinary records to a max-priority queue;
- the top-k records are popped and folded into a checksum;
- epoch-local records and queue backing arrays die at the epoch boundary;
- heap mode allocates records and queue arrays on the GC heap;
- `rift-checked` mode allocates records and queue backing arrays in a checked
  streaming/reset region.

The purpose is to connect the Phase 7/8 safe API work to the Phase 5 ranking
problem: `RegionPriorityQueue` is a general owner-token checked container for
ranking-style state, not a Q1/Q2-specific benchmark patch.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_PQ_EPOCHS` | `10` |
| `CHECKED_PQ_RECORDS_PER_EPOCH` | `50000` |
| `CHECKED_PQ_TOP_K` | `64` |
| `CHECKED_PQ_INITIAL_CAPACITY` | `16` |
| `CHECKED_PQ_WARMUPS` | `1` |
| `CHECKED_PQ_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `rift-checked`

## Commands

Compiler probes:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
```

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
CHECKED_PQ_EPOCHS=2 CHECKED_PQ_RECORDS_PER_EPOCH=10000 \
CHECKED_PQ_BENCHMARK_RUNS=1 CHECKED_PQ_WARMUPS=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"CheckedRegionPriorityQueueMatrix\")" \
      "run heap"

ENABLE_EXPERIMENTAL_COMPILER=1 \
CHECKED_PQ_EPOCHS=2 CHECKED_PQ_RECORDS_PER_EPOCH=10000 \
CHECKED_PQ_BENCHMARK_RUNS=1 CHECKED_PQ_WARMUPS=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"CheckedRegionPriorityQueueMatrix\")" \
      "run rift-checked"
```

Default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_PQ_BUILD=0 \
CHECKED_PQ_OUTPUT_DIR=/tmp/checked-region-priority-queue-default \
  zsh sandbox/run_checked_region_priority_queue_matrix.sh
```

## Validation

Validation run on 2026-04-28:

- `RiftRegionCheckedCompilerTest` passed `58/58`.
- `sandbox3_next/compile` passed.
- The small heap and `rift-checked` smoke runs matched checksum
  `6060823502102390797`.
- The default script run matched checksum `-1585717642344797770`.

## Small Smoke Result

Configuration:

- `CHECKED_PQ_EPOCHS=2`
- `CHECKED_PQ_RECORDS_PER_EPOCH=10000`
- `CHECKED_PQ_TOP_K=64`
- `CHECKED_PQ_BENCHMARK_RUNS=1`
- `CHECKED_PQ_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Resets | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| heap | 1.383 | 0.232 | 0.000 | 0 | 0 | 6060823502102390797 |
| rift-checked | 1.575 | 0.000 | 0.037 | 20044 | 2 | 6060823502102390797 |

## Default Local Median

Configuration:

- `CHECKED_PQ_EPOCHS=10`
- `CHECKED_PQ_RECORDS_PER_EPOCH=50000`
- `CHECKED_PQ_TOP_K=64`
- `CHECKED_PQ_INITIAL_CAPACITY=16`
- `CHECKED_PQ_BENCHMARK_RUNS=3`
- `CHECKED_PQ_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 27.369 | 2.160 | 0.000 | 0 | 0 | 26476544 | -1585717642344797770 |
| rift-checked | 28.621 | 0.000 | 0.279 | 500260 | 10 | 16957440 | -1585717642344797770 |

Interpretation:

- This is a framework/API result, not a DEBS result.
- `RegionPriorityQueue` extends the checked owner-token container family from
  append-only buffers to ranking/top-k style state.
- The checked path removes measured GC and has lower RSS in this focused run,
  but it is slightly slower on elapsed time. This should be treated as
  correctness/safety/API evidence, not as a speed claim.
- Rift operation time is small (`0.279 ms` median), so the remaining overhead
  is likely object/container access and checked path shape rather than region
  open/reset/close cost.

Caveats:

- The queue ranks by a single `Long` priority. DEBS Q1 still needs richer
  tie-breaking and durable indexed rank state.
- This is not a replacement for `RegionWindow`, `RegionHashTable`,
  `RegionTopK`, or parallel-collection APIs.
- It does not include SafeZone, improved SafeZone, or Commix controls.
