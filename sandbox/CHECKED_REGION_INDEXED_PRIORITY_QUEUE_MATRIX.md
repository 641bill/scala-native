# Checked RegionIndexedPriorityQueue Matrix

Status: focused checked-API methodology harness with validated smoke and one
default local median.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedRegionIndexedPriorityQueueMatrix.scala`
and run with
`sandbox/run_checked_region_indexed_priority_queue_matrix.sh`.

## Intent

This is not DEBS and not a prior-paper artifact reproduction. It is a focused
check that Rift now has a reusable checked container for durable keyed ranking
state.

Heap and Rift use the same logical program:

- each epoch processes a stream of events with dense integer keys;
- the first event for a key creates an ordinary Scala record object;
- later events for the same key fetch and mutate that record, then update its
  priority in an indexed max-priority queue;
- top-k records are popped and folded into a checksum;
- heap mode allocates records and queue arrays on the GC heap;
- `rift-checked` mode allocates records and queue backing/index arrays in a
  checked streaming/reset region.

The purpose is to connect the Phase 7/8 safe API work to the Phase 5 Q1/Q2
ranking problem without adding a DEBS-specific algorithm. Unlike
`RegionPriorityQueue`, this container supports keyed replacement, priority
updates, membership checks, and key lookup. That is the general shape needed
for stream operators whose durable per-key state changes many times inside a
structured lifetime.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_IPQ_EPOCHS` | `8` |
| `CHECKED_IPQ_EVENTS_PER_EPOCH` | `125000` |
| `CHECKED_IPQ_KEY_CAPACITY` | `65536` |
| `CHECKED_IPQ_INITIAL_CAPACITY` | `1024` |
| `CHECKED_IPQ_TOP_K` | `128` |
| `CHECKED_IPQ_WARMUPS` | `1` |
| `CHECKED_IPQ_BENCHMARK_RUNS` | `3` |

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
CHECKED_IPQ_EPOCHS=2 \
CHECKED_IPQ_EVENTS_PER_EPOCH=20000 \
CHECKED_IPQ_KEY_CAPACITY=4096 \
CHECKED_IPQ_BENCHMARK_RUNS=1 \
CHECKED_IPQ_WARMUPS=0 \
CHECKED_IPQ_OUTPUT_DIR=/tmp/checked-region-indexed-priority-queue-smoke \
  zsh sandbox/run_checked_region_indexed_priority_queue_matrix.sh
```

Default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_IPQ_BUILD=0 \
CHECKED_IPQ_OUTPUT_DIR=/tmp/checked-region-indexed-priority-queue \
  zsh sandbox/run_checked_region_indexed_priority_queue_matrix.sh
```

## Validation

Validation run on 2026-04-28:

- `RiftRegionCheckedCompilerTest` passed `63/63`.
- `sandbox3_next/compile` passed.
- The small heap and `rift-checked` smoke runs matched checksum
  `5325953295303277301`.
- The default script run matched checksum `1716925704794878405`.

## Small Smoke Result

Configuration:

- `CHECKED_IPQ_EPOCHS=2`
- `CHECKED_IPQ_EVENTS_PER_EPOCH=20000`
- `CHECKED_IPQ_KEY_CAPACITY=4096`
- `CHECKED_IPQ_TOP_K=128`
- `CHECKED_IPQ_BENCHMARK_RUNS=1`
- `CHECKED_IPQ_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 3.882 | 0.000 | 0.000 | 0 | 0 | 6307840 | 5325953295303277301 |
| rift-checked | 4.359 | 0.000 | 0.014 | 8191 | 2 | 4898816 | 5325953295303277301 |

## Default Local Median

Configuration:

- `CHECKED_IPQ_EPOCHS=8`
- `CHECKED_IPQ_EVENTS_PER_EPOCH=125000`
- `CHECKED_IPQ_KEY_CAPACITY=65536`
- `CHECKED_IPQ_INITIAL_CAPACITY=1024`
- `CHECKED_IPQ_TOP_K=128`
- `CHECKED_IPQ_BENCHMARK_RUNS=3`
- `CHECKED_IPQ_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 100.254 | 2.201 | 0.000 | 0 | 0 | 22085632 | 1716925704794878405 |
| rift-checked | 103.052 | 0.000 | 0.406 | 451112 | 8 | 26279936 | 1716925704794878405 |

Interpretation:

- This is a framework/API result, not a DEBS result.
- `RegionIndexedPriorityQueue` extends the checked owner-token container family
  from append-only buffers and append/pop priority queues to mutable keyed
  ranking state.
- The checked path removes measured GC with low region-operation overhead
  (`0.406 ms` median), but it is slightly slower on elapsed time and higher on
  RSS in the default run. Treat it as correctness/safety/API evidence, not as a
  speed claim.
- The important capability is that ordinary Scala records are created once per
  key per epoch, then fetched, mutated, and re-ranked in region-managed storage
  without allocating a new rank object for every refresh.

Caveats:

- The queue ranks by one `Long` priority and uses dense integer keys. DEBS Q1
  still needs richer tie-breaking and either dense route-key mapping or a
  higher-level region hash/index table.
- This is not a replacement for `RegionWindow`, `RegionHashTable`,
  `RegionTopK`, or parallel-collection APIs.
- It does not include SafeZone, improved SafeZone, or Commix controls.
