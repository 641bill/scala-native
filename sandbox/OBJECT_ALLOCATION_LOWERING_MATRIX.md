# Object Allocation Lowering Matrix

Date: 2026-05-05
Last updated: 2026-05-13 00:33 CEST

Status: refined retained-region-array matrix validated at 20k smoke, 100k, 1M,
and 10M, plus focused final-clean allocator-counter, cached-stats, and
current-slab zeroed-cache gates. This
benchmark isolates ordinary Scala object construction through heap, trusted
Rift, checked Rift, and checked SafeZone-backed allocation paths without
stream-window, ranking, aggregation, or output work.

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

Second implementation change, 2026-05-12: Rift allocation counters are now
disabled automatically when `RIFT_FINAL_CLEAN=1`, with `RIFT_ALLOC_STATS=1`
available to force them back on for diagnostics. This removes per-allocation
raw/object/byte counter maintenance from L1-style headline binaries while
leaving L2/stat runs unchanged by default.

Focused 5M checked Rift allocation-counter gate, 5 measured runs:

| Mode / env | Median ms | GC median ms | Region objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `rift-checked-rift`, `RIFT_FINAL_CLEAN=1` | 76.345 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| `rift-checked-rift`, `RIFT_FINAL_CLEAN=1 RIFT_ALLOC_STATS=1` | 87.999 | 0.000 | 5000001 | 203653120 | -1183547843768457859 |

Interpretation: disabling Rift allocation counters in final-clean mode improves
this focused checked Rift allocation path by about `13%` without changing the
object graph or checksum. This is a measurement-clean/headline-mode
optimization; L2 rows should keep allocation stats enabled when object/open/
close counts are needed for interpretation.

Application impact gate, 2026-05-12:

These rows compare `RIFT_FINAL_CLEAN=1` against
`RIFT_FINAL_CLEAN=1 RIFT_ALLOC_STATS=1` on selected Rift-native checked
application shapes. They are single external-process gates, intended to check
whether the focused allocation-counter result is visible outside the allocation
matrix.

| Benchmark row | Counters disabled | Counters forced on | Result |
|---|---:|---:|---|
| Dataflow AGGREGATE `checked-epoch-stream`, 20 x 500k docs | `1.06 s`, RSS `23560192` | `0.90 s`, RSS `23576576` | Process-level timing noise dominates; no claim. |
| Yak `graphstep` `checked-epoch-stream`, 50M messages | `1.98 s`, user `1.66 s`, RSS `204800000` | `1.81 s`, user `1.76 s`, RSS `204800000` | User time moves in the expected direction, but real time is noisy; no headline claim. |
| StreamFlex-design throughput `checked-epoch-stream`, 20M events | `8.57 s`, user `8.26 s`, RSS `10305536` | `9.18 s`, user `9.15 s`, RSS `10338304` | Application-level confirmation for Rift-native checked stream allocation. |
| Common Crawl-shaped q2 `rift-checked-page-token`, 1M pages | `4.23 s`, user `3.95 s`, RSS `63176704` | `4.33 s`, user `4.26 s`, RSS `63193088` | Modest page/window confirmation; query/traversal work still dominates. |

Interpretation: this cleanup is real and visible on allocation-heavy
Rift-native stream rows, but it is not a universal application-speedup knob.
Use it as part of the final-clean measurement-cleaning story and continue to
treat L2 allocation counters as interpretation-only.

Third implementation change, 2026-05-12: Rift managed-object allocation now has
a dedicated pointer-aligned fast path instead of routing every object through
the generic raw-allocation helper. Object zero/init and header setup are
unchanged; the specialization only removes generic alignment normalization and
keeps the slow path for slab refill.

Focused 5M checked Rift managed-object fast-path gate, 5 measured runs:

| Mode / env | Median ms | GC median ms | Region objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `rift-checked-rift`, previous final-clean baseline | 76.345 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| `rift-checked-rift`, object fast path, `RIFT_FINAL_CLEAN=1` | 69.912 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| `rift-checked-rift`, object fast path, `RIFT_FINAL_CLEAN=1 RIFT_ALLOC_STATS=1` | 89.081 | 0.000 | 5000001 | 203653120 | -1183547843768457859 |

Application impact gate, object fast path, 2026-05-12:

| Benchmark row | Previous final-clean gate | Object fast-path gate | Result |
|---|---:|---:|---|
| StreamFlex-design throughput `checked-epoch-stream`, 20M events | `8.57 s`, user `8.26 s`, RSS `10305536` | `8.07 s`, user `7.67 s`, RSS `10289152` | Single-run application confirmation for Rift-native checked stream allocation. |
| Common Crawl-shaped q2 `rift-checked-page-token`, 1M pages | `4.23 s`, user `3.95 s`, RSS `63176704` | `3.98 s`, user `3.68 s`, RSS `63193088` | Single-run page/window confirmation; query/traversal work still dominates. |

Interpretation: this is a real allocation-lowering improvement on the
Rift-native checked backend. It does not affect SafeZone-backed checked rows
directly. The stats-forced row stays slower because L2 allocation accounting is
still per-object by design; use it for interpretation, not headline timing.

Rejected zeroing follow-up, 2026-05-12: bulk-zeroing dirty slabs when they are
attached to a region was tested and reverted. It preserved zero-initialization
semantics, but the focused 5M `rift-checked-rift` final-clean row was slightly
worse (`70.044 ms`) than the committed object fast-path baseline (`69.912 ms`),
with slow-allocation/attach time rising to `3.277 ms`. Keep the current
per-object zeroing policy for dirty reused slabs until a better measured policy
exists.

Cached allocation-stats mode follow-up, 2026-05-12: the L4 clean-winner profile
still sampled `scalanative_rift_alloc_stats_enabled`, so the Rift runtime now
caches the allocation-stats mode on each `scalanative_rift_region` at open.
Raw allocation, managed-object allocation, and slow allocation read the cached
region flag. `RIFT_ALLOC_STATS=1` still forces diagnostics.

Focused 5M checked Rift cached-stats gate, 5 measured runs:

| Mode / env | Median ms | GC median ms | Region objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `rift-checked-rift`, object fast-path baseline | 69.912 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| rejected helper-call attempt | 75.705 / 77.332 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| accepted region-cached stats, `RIFT_FINAL_CLEAN=1` | 71.702 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| accepted region-cached stats, `RIFT_FINAL_CLEAN=1 RIFT_ALLOC_STATS=1`, 1M objects | 17.141 | 0.000 | 1000001 | 43532288 | 3257734543759152236 |

Interpretation: caching stats mode removes the sampled helper from final-clean
profiles and preserves opt-in allocation accounting, but it does not improve
the focused allocation-lowering row relative to the earlier object fast-path
baseline. Treat this as a profile cleanup with modest application benefit, not
as a focused object-allocation speedup.

Current-slab zeroed-cache follow-up, 2026-05-13: object allocation previously
read the current slab's flags on every object to decide whether dirty reused
slabs needed `_platform_memset`. The Rift region now caches that current-slab
zeroed state when a slab is opened, appended, or reset. This keeps the same
zeroing policy; it only removes one allocation-body flag load.

Focused 5M checked Rift zeroed-cache gate, 5 measured runs:

| Mode / env | Median ms | GC median ms | Region objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `rift-checked-rift`, object fast-path baseline | 69.912 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| region-cached stats | 71.702 | 0.000 | 0 | 203669504 | -1183547843768457859 |
| current-slab zeroed cache, `RIFT_FINAL_CLEAN=1` | 68.998 | 0.000 | 0 | 203669504 | -1183547843768457859 |

Interpretation: unlike the cached-stats-only row, the current-slab zeroed
cache beats the prior object-fast baseline in the focused allocation matrix.
It is still a narrow allocation-body optimization: object construction,
mandatory zeroing on dirty slabs, and field stores remain.

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
