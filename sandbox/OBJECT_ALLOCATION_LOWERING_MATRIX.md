# Object Allocation Lowering Matrix

Date: 2026-05-05

Status: refined retained-region-array matrix validated at 20k smoke, 100k, 1M,
and 10M. This benchmark isolates ordinary Scala object construction through
heap, trusted Rift, checked Rift, and checked SafeZone-backed allocation paths
without stream-window, ranking, aggregation, or output work.

## Purpose

Recent GH Archive rows show that region close/open cost is small, but checked
region rows can still trail an uncapped Immix heap. This matrix separates the
allocation-lowering question from operator/query CPU:

- object construction is still paid in every mode;
- checked allocation currently reaches the runtime through `allocImpl`;
- region reclaim avoids tracing, but that benefit is invisible when the heap
does not collect;
- the benchmark keeps only a simple retained-array/traversal shape in every
  mode so allocated objects cannot be optimized away;
- any remaining gap here is allocation lowering/object construction overhead,
  not bucket, cursor, aggregation, output, or ranking overhead.

## Modes

- `heap-immix`
- `rift-trusted-hp`
- `rift-trusted-streaming`
- `rift-checked-rift`
- `rift-checked-safezone-improved-32k`

## Command

```sh
cd /Users/siyaoliu/rift/scala-native-rift
OBJECT_ALLOC_OBJECTS=1000000 \
OBJECT_ALLOC_BENCHMARK_RUNS=3 \
OBJECT_ALLOC_WARMUPS=1 \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-allocation-lowering-1m \
zsh sandbox/run_object_allocation_lowering_matrix.sh
```

Heap-budget diagnostic example:

```sh
OBJECT_ALLOC_OBJECTS=1000000 \
OBJECT_ALLOC_HEAP_CAP=1G \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-allocation-lowering-1m-1g \
zsh sandbox/run_object_allocation_lowering_matrix.sh
```

## Current Results

Validation command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Result: compile passed after the retained-region-array matrix change.

Smoke command:

```sh
OBJECT_ALLOC_OBJECTS=20000 \
OBJECT_ALLOC_BENCHMARK_RUNS=1 \
OBJECT_ALLOC_WARMUPS=0 \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-allocation-lowering-smoke-2026-05-05 \
zsh sandbox/run_object_allocation_lowering_matrix.sh
```

Result: all modes matched checksum. The checked Rift row reported
`20001` region object allocations, so the retained shape is no longer being
optimized away from the Rift allocation counters.

Implementation note: the first retained version used `RegionBuffer` in the
checked rows. That was useful for preventing optimization, but it still mixed
generic checked-container overhead into an allocation-lowering matrix. The
current version uses a region-owned array,
`Array[CheckedRecord^{region}]^{region}`, so checked rows retain and traverse
records without the `RegionBuffer` API.

An attempted codegen fast path that retagged checked region allocations to
`RiftRegion.allocImpl` instead of `SafeZone.allocImpl` failed during native
lowering: Scala Native metadata did not expose `allocImpl` as a valid method
lookup entry on `RiftRegion` or its checked subtraits. That probe was reverted;
do not repeat it without changing method-table metadata.

100k retained-region-array rows, 3 measured runs:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Rift op ms | Slow alloc ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 1.691 | 0.000 | 0.414 | 1/3 | 0.000 | 0.000 | 0 | 13533184 |
| `rift-trusted-hp` | 1.932 | 0.000 | 0.000 | 0/3 | 0.005 | 0.004 | 100000 | 10272768 |
| `rift-trusted-streaming` | 1.880 | 0.000 | 0.000 | 0/3 | 0.005 | 0.004 | 100000 | 10272768 |
| `rift-checked-rift` | 1.576 | 0.000 | 0.000 | 0/3 | 0.013 | 0.005 | 100001 | 7520256 |
| `rift-checked-safezone-improved-32k` | 1.395 | 0.000 | 0.000 | 0/3 | 0.000 | 0.000 | 0 | 7749632 |

1M retained-region-array rows, 3 measured runs:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Rift op ms | Slow alloc ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 20.429 | 5.745 | 7.664 | 3/3 | 0.000 | 0.000 | 0 | 63832064 |
| `rift-trusted-hp` | 19.392 | 0.060 | 0.070 | 3/3 | 0.066 | 0.037 | 1000000 | 43859968 |
| `rift-trusted-streaming` | 19.237 | 0.054 | 0.059 | 3/3 | 0.074 | 0.037 | 1000000 | 43859968 |
| `rift-checked-rift` | 16.100 | 0.000 | 0.000 | 0/3 | 0.097 | 0.042 | 1000001 | 43548672 |
| `rift-checked-safezone-improved-32k` | 14.347 | 0.000 | 0.000 | 0/3 | 0.000 | 0.000 | 0 | 43843584 |

10M retained-region-array rows, 3 measured runs:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Rift op ms | Slow alloc ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 271.121 | 105.807 | 322.358 | 2/3 | 0.000 | 0.000 | 0 | 971505664 |
| `rift-trusted-hp` | 199.627 | 0.070 | 0.070 | 3/3 | 11.338 | 6.247 | 10000000 | 403996672 |
| `rift-trusted-streaming` | 200.895 | 0.074 | 0.078 | 3/3 | 11.786 | 6.599 | 10000000 | 403996672 |
| `rift-checked-rift` | 165.774 | 0.000 | 0.000 | 0/3 | 11.623 | 6.245 | 10000001 | 403832832 |
| `rift-checked-safezone-improved-32k` | 143.319 | 0.000 | 0.000 | 0/3 | 0.000 | 0.000 | 0 | 404389888 |

All 100k, 1M, and 10M rows matched checksum at their scale.

First implementation change before measurement: explicit `@noinline` barriers
were removed from the checked Rift and checked SafeZone-backed `allocImpl`
backend methods. This is intentionally narrow; deeper compiler/runtime
intrinsic lowering should wait until these rows are interpreted.

Validation note: an initial 20k construct-only smoke linked and emitted rows,
but was rejected as evidence because checked allocations could be optimized
away from the Rift allocation counters. The measured rows above use the
retained-region-array/traversal shape.

## Interpretation

- At 100k, heap has effectively no GC pressure, but the checked region-array
  rows are now fastest. This means the previous checked slowdown was not
  intrinsic allocation cost; it was mostly the generic checked `RegionBuffer`
  retention path used by the first retained matrix.
- At 1M, checked region-array rows beat heap and trusted rows while cutting
  RSS by about `31%`. The checked SafeZone-backed row is fastest
  (`14.347 ms`), followed by checked Rift (`16.100 ms`).
- At 10M, heap becomes GC/RSS-bound: median timed GC is `105.807 ms`, max GC is
  `322.358 ms`, and RSS reaches about `971 MB`. Trusted Rift is about `26%`
  faster than heap with about `404 MB` RSS, but the checked region-array rows
  are faster still: checked Rift is `165.774 ms`, and checked SafeZone-backed
  allocation is `143.319 ms`.
- The measured Rift allocator-op time at 10M is about `11-12 ms`, far smaller
  than total elapsed. Allocation/reclaim is not the dominant remaining cost in
  this focused shape. Generic checked containers and application traversal are
  now the more likely sources of checked overhead in larger workloads.
- SafeZone-backed checked allocation does not report Rift allocator counters;
  use elapsed/RSS/checksum for those rows.

## Interpretation Rules

- If `heap-immix` has zero GC and wins by more than 10%, allocation lowering is
  still a primary checked-region bottleneck.
- If checked Rift is within 5-10% of heap under zero-GC conditions, application
  losses should be attributed first to operator/query CPU.
- `rift-checked-safezone-improved-32k` rows are backend feasibility evidence;
  they do not imply rootless safety.
