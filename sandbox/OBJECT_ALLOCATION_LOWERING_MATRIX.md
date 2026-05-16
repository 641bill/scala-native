# Object Allocation Lowering Matrix

Date: 2026-05-05
Last updated: 2026-05-16 16:45 CEST

Status: refined retained-region-array matrix validated at 20k smoke, 100k, 1M,
and 10M, plus focused final-clean allocator-counter, cached-stats,
current-slab zeroed-cache, handle-backed allocation, warm/dirty-slab gates,
object zeroing attribution counters, an experimental reusable-slab
bulk-zero policy gate, an unsafe no-zero lower-bound gate, and proof-gated
no-zero lowering for definitely initialized handle-backed records. The runtime
now also has `RIFT_REGION_REUSE_POLICY` as the canonical opt-in slab reuse
policy knob. The no-zero proof now covers both primitive-only records and local
region-linked records with reference fields when every field is definitely
stored before use. This benchmark isolates ordinary Scala object construction
through heap, trusted Rift, checked Rift, and checked SafeZone-backed allocation
paths without stream-window, ranking, aggregation, or output work.

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
- `OBJECT_ALLOC_RECORD_SHAPE=primitive` keeps the original primitive-field
  record shape, while `OBJECT_ALLOC_RECORD_SHAPE=reference` builds a linked
  region-local object graph with reference fields;
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

Zeroing attribution probe, 2026-05-14: the Rift runtime now records, when
allocation stats are enabled, how many managed object allocations actually call
`memset` and how many skip zeroing because the current slab is known fresh and
zero-filled by the OS. These counters are diagnostic L2 evidence only; they
are disabled with the rest of allocation stats in `RIFT_FINAL_CLEAN=1`.

Smoke command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
OBJECT_ALLOC_OBJECTS=20000 \
OBJECT_ALLOC_BENCHMARK_RUNS=1 \
OBJECT_ALLOC_WARMUPS=0 \
OBJECT_ALLOC_MODES="rift-checked-rift-open-handle rift-checked-rift-open-handle-dirty-slab" \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-zeroing-probe-smoke-20260514 \
zsh sandbox/run_object_allocation_lowering_matrix.sh
```

20k smoke rows:

| Mode | Median ms | Zeroed objects | Zeroed bytes | Zero-skipped objects | Zero-skipped bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 0.902 | 0 | 0 | 20,000 | 640,000 | -5014597496877744147 |
| `rift-checked-rift-open-handle-dirty-slab` | 0.444 | 511 | 16,352 | 19,489 | 623,648 | -5014597496877744147 |

Focused diagnostic command:

```sh
OBJECT_ALLOC_BUILD=0 \
OBJECT_ALLOC_OBJECTS=5000000 \
OBJECT_ALLOC_BENCHMARK_RUNS=5 \
OBJECT_ALLOC_WARMUPS=1 \
OBJECT_ALLOC_MODES="rift-checked-rift-open-handle rift-checked-rift-open-handle-dirty-slab" \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-zeroing-probe-5m-20260514 \
zsh sandbox/run_object_allocation_lowering_matrix.sh
```

5M rows:

| Mode | Median ms | GC median ms | Zeroed objects | Zeroed bytes | Zero-skipped objects | Zero-skipped bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 70.197 | 0.070 | 4,198,392 | 134,348,544 | 801,608 | 25,651,456 | -1183547843768457859 |
| `rift-checked-rift-open-handle-dirty-slab` | 70.329 | 0.074 | 4,198,392 | 134,348,544 | 801,608 | 25,651,456 | -1183547843768457859 |

Interpretation: zeroing is now quantified, not guessed. At 5M objects, about
`84%` of managed object allocations are zeroed and about `16%` skip zeroing
because they land on fresh zeroed slabs. That confirms object initialization /
zeroing is a real remaining allocation-body cost, but it still does not justify
unsafe no-zero allocation. The next optimization step needs compiler proof of
definite initialization for final record-like classes, or a runtime policy that
changes this ratio without increasing attach/reset cost.

Reusable-slab bulk-zero experiment, 2026-05-14: the runtime now has an
experimental policy gate, `RIFT_ZERO_REUSED_SLABS=1`, that bulk-zeros dead
non-huge slabs when they are returned to the Rift TLS/global slab cache or
when a reset keeps the first slab. This preserves zero-initialization
semantics: objects are dead at region close/reset, the reusable slab is made
zero-filled before it is marked zeroed, and future object allocations can skip
per-object `memset`. The default policy is unchanged unless the environment
variable is set.

Focused 5M and 10M handle-backed allocation gates, 5 measured runs:

| Scale | Policy | Median ms | GC median ms | Rift op ms | Slow alloc ms | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 5M | default | 68.692 | 0.068 | 2.153 | 1.008 | 4,198,392 | 801,608 | 203751424 | -1183547843768457859 |
| 5M | `RIFT_ZERO_REUSED_SLABS=1` | 65.228 | 0.062 | 4.121 | 0.998 | 0 | 5,000,000 | 203767808 | -1183547843768457859 |
| 10M | default | 144.067 | 0.067 | 10.813 | 5.828 | 4,198,392 | 5,801,608 | 403931136 | -6851333344653324122 |
| 10M | `RIFT_ZERO_REUSED_SLABS=1` | 138.078 | 0.069 | 13.817 | 5.827 | 0 | 10,000,000 | 403947520 | -6851333344653324122 |

Interpretation: bulk-zeroing reusable slabs is a safe runtime-side
zeroing-policy win in this focused allocator row: about `5.0%` faster at 5M
and about `4.2%` faster at 10M with unchanged checksums and effectively
unchanged RSS. It does not eliminate initialization work; it moves zeroing to
region close/reset so the allocation hot path can skip per-object `memset`.
The measured Rift op time rises because close/reset now does the bulk zero
work. Keep this experimental until application gates show the close/reset
tradeoff is favorable for real epoch/page/window workloads.

Rejected high-water follow-up, 2026-05-14: a narrower policy tried to reduce
the close/reset cost by tracking a reusable slab's high-water mark and zeroing
only the prefix ever allocated in that slab. The first version updated the
mark on every allocation and regressed the focused 5M default row to
`76.532 ms`. A transition-only version recorded usage only when switching
slabs and at close/reset, and reused the existing 32-bit slab header slot so
the slab data size was restored. It still failed the focused gate: default
`70.186 ms`, high-water bulk-zero `69.619 ms`, versus the accepted full-slab
bulk-zero gate's `68.692 ms` default and `65.228 ms` enabled result. The
prototype was reverted. Conclusion: high-water bookkeeping is not the right
way to reduce region-op cost for the current object allocation path. Next
zeroing-policy experiments should avoid per-allocation or transition metadata
in the hot runtime and instead test coarser policies such as TLS-cache-only
zeroing, RSS-aware cache release, or compiler-proven no-zero for definitely
initialized record classes.

Rejected local-only zeroing follow-up, 2026-05-14: a second narrow policy tried
to bulk-zero only slabs kept for local reuse, namely the TLS reusable-slab cache
and the first slab retained by `reset`, while leaving slabs that spill to the
global pool dirty. This avoided global-pool page touches but failed the focused
5M allocation gate. In the same linked binary, default was `71.834 ms`, full
`RIFT_ZERO_REUSED_SLABS=1` was `68.614 ms`, and local-only zeroing was
`72.339 ms`. The local-only row still zeroed `4,190,208` objects and skipped
only `809,792`, essentially the same shape as default (`4,198,392` zeroed,
`801,608` skipped), because the focused allocator reuses most slabs through
the global pool rather than the small TLS cache. The prototype was reverted.
Conclusion: TLS/local-only zeroing is too narrow to deliver the allocation-side
benefit. The remaining zeroing choices are either keep the current full-slab
bulk-zeroing policy as a workload-selective knob, add RSS-aware release for
cold cached slabs, or move to compiler-proven no-zero for definitely
initialized record classes.

Rejected pool-cap release follow-up, 2026-05-14: an experimental
`RIFT_REUSABLE_POOL_MAX_BYTES` knob let the global reusable slab pool cap be
lowered at runtime, including to zero, while keeping the existing full
`RIFT_ZERO_REUSED_SLABS=1` policy. The focused 5M allocator row showed that
cold-slab release is not free: default cap/full bulk-zero was `68.124 ms`,
96 MiB was `73.023 ms`, 64 MiB was `74.595 ms`, 32 MiB was `76.415 ms`, and
cap zero was `78.603 ms`. The cap-zero row raised region op time to
`12.248 ms` and slow-allocation time to `5.817 ms`, showing mmap/unmap churn.
The real Theodolite q2 streaming-file check did not recover RSS either: full
bulk-zero/default cap was external `5.52 s`, RSS `78,512,128` bytes, median
loop `932.614 ms`, region op `1.590 ms`; full bulk-zero/cap-zero was external
`5.47 s`, RSS `78,413,824` bytes, median loop `923.325 ms`, region op
`8.405 ms`. The RSS difference was noise-level while region-op cost rose. The
prototype was reverted. Conclusion: a static global-pool cap is too blunt for
the observed RSS issue. Future RSS work should either target untouched-page
release/madvise on cold slabs with evidence that resident pages actually drop,
or avoid touching pages in the first place through compiler-proven no-zero.

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

Rejected stats-branch split follow-up, 2026-05-13: splitting the Rift
managed-object allocation helper into stats-disabled and stats-enabled helper
functions improved the focused 5M `rift-checked-rift` allocation row slightly
(`68.561 ms` versus the accepted current-slab zeroed-cache `68.998 ms`), and
the forced-stats 1M row still reported `1000001` region objects. It was
rejected because application gates moved the wrong way: focused page-token
checked Rift reran at `24.799 ms` versus the accepted `23.930 ms`, and
StreamFlexDesign `checked-epoch-stream` moved to `23.24 s` versus the accepted
`22.31 s`. Keep the accepted single-helper allocation body; the next serious
target is a compiler/backend-known allocation lowering change or proven
zero-initialization work, not another C branch reshuffle.

Rejected backend-specific open-marker prototype, 2026-05-13: a prototype added
Rift-open and SafeZone-open marker traits plus a marker-preserving lowering
path so `allocOpen` could avoid retagging the zone back to generic
`RiftRegion`. The first overload shape made existing generic `allocOpen` calls
ambiguous across many checked operators. A narrower experimental
`allocRiftOpen` mode compiled, but crashed in the focused allocation matrix
with an unrecoverable null allocation path, and the same binary regressed the
baseline `rift-checked-rift` row (`75.706 ms`). The prototype was reverted.
Conclusion: marker traits and casts are not enough. The next backend-known
allocation attempt needs a real compiler/NIR intrinsic that can lower directly
to a handle-level allocation path, or a typed API that carries the handle
without dynamic method lookup.

Focused handle-level lowering prototype, 2026-05-13: the next attempt added a
Rift-only experimental open handle and a distinct allocation intrinsic that
the NIR lowerer can recognize. The lowerer reads the handle field and emits a
direct `scalanative_rift_region_alloc` call, avoiding the virtual
`allocUncheckedImpl` method path. This row is deliberately focused
allocation-lowering evidence, not a final public API decision and not yet an
application row.

Focused 5M checked Rift handle-level allocation gate, 5 measured runs:

| Mode / env | Median ms | GC median ms | GC max ms | Runs with GC | Rift op ms | Slow alloc ms | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `rift-trusted-streaming`, same binary | 87.494 | 0.079 | 0.215 | 5/5 | 2.739 | 1.333 | 203800576 | -1183547843768457859 |
| `rift-checked-rift`, current checked path | 70.247 | 0.000 | 0.000 | 0/5 | 2.843 | 1.238 | 203685888 | -1183547843768457859 |
| `rift-checked-rift-open-handle`, experimental handle path | 59.777 | 0.070 | 0.082 | 5/5 | 2.554 | 1.172 | 203784192 | -1183547843768457859 |

Interpretation: this is the first focused result showing that a real
backend-known handle-level allocation lowering can beat the current checked
path materially: about `14.9%` faster than the same-binary current checked row
and about `13.4%` faster than the accepted current-slab zeroed-cache row
(`68.998 ms`). RSS and checksum are unchanged at this scale. The next step is
to redesign this as an internal/compiler-owned path for safe checked epoch and
page-token APIs, then rerun application gates before exposing or claiming it.
Validation after tightening the handle field visibility: `sandbox3_next/compile`
passed, the focused open-handle 20k native-link smoke passed, and the existing
checked-region compiler/runtime tests passed (`141/141` and `64/64`).

First application gate, 2026-05-13: `StreamFlexDesignMatrix` added an
experimental `checked-epoch-stream-open-handle` row. It keeps the same
stable/transient/capsule workload and the same per-period reset topology as
`checked-epoch-stream`; only the checked transient allocations use the
handle-level lowering.

| Workload | Current checked stream | Open-handle checked stream | Result |
|---|---:|---:|---|
| StreamFlex design throughput, 2M events | `1.09 s`, user `0.75 s`, RSS `6209536` | `0.67 s`, user `0.66 s`, RSS `6045696` | Same checksum/output; positive smoke-scale application gate. |
| StreamFlex design throughput, 20M events | `7.27 s`, user `7.24 s`, RSS `10354688` | `6.60 s`, user `6.56 s`, RSS `9027584` | Same checksum/output; about `9.2%` external-time improvement and lower RSS in this same-binary gate. |

Clean 20M L1 gate, 3 measured runs inside each non-profiled process:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 30.67 | 30.46 | 12435456 | 5305809911915216923 | 19999119 |
| `heap-same-shape` | 30.77 | 30.53 | 12435456 | 5305809911915216923 | 19999119 |
| `region-scoped-rooted` | 28.16 | 28.01 | 12255232 | 5305809911915216923 | 19999119 |
| `checked-epoch-stream` | 22.57 | 22.07 | 12615680 | 5305809911915216923 | 19999119 |
| `checked-epoch-stream-open-handle` | 19.69 | 19.49 | 12550144 | 5305809911915216923 | 19999119 |

Interpretation: the handle-level allocation result is not just a microbenchmark
artifact. It survives a StreamFlex-style application shape: the clean 20M gate
improves current checked stream by about `12.8%` while keeping the same
checksum/output and essentially the same RSS. This still needs L2
interpretation rows and integration behind the normal checked API before it
becomes a public-system claim.

Second application gate, 2026-05-13: generated Common Crawl-shaped q2 now has a
Rift-only `rift-checked-page-token-open-handle` row. It keeps the same
page/window token topology as `rift-checked-page-token`, but the active bucket
allocation owner is a handle lowered directly to `scalanative_rift_region_alloc`.

| Workload | Current checked page-token | Open-handle checked page-token | Result |
|---|---:|---:|---|
| Common Crawl-shaped q2, 20k smoke | `0.41 s`, RSS `63242240` | `0.07 s`, RSS `63242240` | Same checksum/output; positive smoke. |
| Common Crawl-shaped q2, 1M L1, 3 runs/process | `10.62 s`, RSS `63324160` | `9.66 s`, RSS `63340544` | Same checksum/output; about `9.0%` external-time improvement. |
| Common Crawl-shaped q2, 1M L2 median | `3883.742 ms`, GC `21.017 ms` | `3695.704 ms`, GC `20.648 ms` | Same allocation/open/close counts; about `4.8%` internal median improvement. |

Interpretation: the handle-level path transfers to the page/window stream
shape too. The next promotion step is to hide this behind compiler-owned
checked API lowering instead of exposing separate benchmark labels.

Promotion follow-up, 2026-05-13: the normal generated Common Crawl-shaped
`rift-checked-page-token` row now dispatches to the handle-backed Rift
page-token path. The old open-region lowering remains available as
`rift-checked-page-token-legacy`.

| Workload | Legacy checked page-token | Promoted checked page-token | Result |
|---|---:|---:|---|
| Common Crawl-shaped q2, 20k smoke | `0.33 s`, RSS `63242240` | `0.07 s`, RSS `63225856` | Same checksum/output. |
| Common Crawl-shaped q2, 1M L1, 3 runs/process | `11.18 s`, RSS `63324160` | `10.59 s`, RSS `63324160` | Same checksum/output; about `5.3%` external-time improvement. |
| Common Crawl-shaped q2, 1M L2 median | `3909.015 ms`, GC `31.825 ms` | `3562.617 ms`, GC `23.230 ms` | Same allocation/open/close counts; about `8.9%` internal median improvement. |

Interpretation: the default checked page-token benchmark row now reflects the
handle-level allocation win. The remaining cleanup is API/reporting: remove the
temporary `open-handle` label from default scripts once enough legacy controls
are recorded, and make the same internal lowering available for other
operator-owned checked paths that carry a Rift handle.

Validation note: an initial 20k construct-only smoke linked and emitted rows,
but was rejected as evidence because checked allocations could be optimized
away from the Rift allocation counters. The measured rows above use the
retained-region-array/traversal shape.

## Handle-Backed Allocation And Warm/Dirty-Slab Gate

Date/time: 2026-05-13 17:27 CEST.

This gate reruns the focused 5M ordinary-object allocation benchmark after the
normal checked StreamFlex/page-token paths were promoted to the handle-backed
allocation lowering. It also adds
`rift-checked-rift-open-handle-dirty-slab`, which dirties Rift slabs outside
the timed section, resets stats, then times the same handle-backed allocation
body. This is a safe measurement probe only; it does not skip zeroing.

Source:
`/Users/siyaoliu/rift/cache/object-allocation-allopts-20260513`.

Command shape:

```sh
OBJECT_ALLOC_OBJECTS=5000000 \
OBJECT_ALLOC_BENCHMARK_RUNS=5 \
OBJECT_ALLOC_WARMUPS=1 \
OBJECT_ALLOC_MODES="heap-immix rift-checked-rift rift-checked-rift-open-handle rift-checked-rift-open-handle-dirty-slab rift-checked-safezone-improved-32k" \
OBJECT_ALLOC_OUTPUT_DIR=/Users/siyaoliu/rift/cache/object-allocation-allopts-20260513 \
  zsh sandbox/run_object_allocation_lowering_matrix.sh
```

| Mode | Median ms | Median GC ms | Max GC ms | Region op ms | Slow alloc ms | Region objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 82.165 | 8.913 | 11.848 | 0.000 | 0.000 | 0 | 246267904 | -1183547843768457859 |
| `rift-checked-rift` | 78.557 | 0.000 | 0.000 | 2.498 | 1.088 | 5000001 | 203653120 | -1183547843768457859 |
| `rift-checked-rift-open-handle` | 69.422 | 0.071 | 0.074 | 1.836 | 1.027 | 5000000 | 203767808 | -1183547843768457859 |
| `rift-checked-rift-open-handle-dirty-slab` | 67.128 | 0.067 | 0.069 | 1.787 | 0.975 | 5000000 | 203767808 | -1183547843768457859 |
| `rift-checked-safezone-improved-32k` | 68.868 | 0.000 | 0.000 | 0.000 | 0.000 | 0 | 204144640 | -1183547843768457859 |

Interpretation: handle-backed checked Rift allocation is about `11.6%` faster
than the current generic checked Rift allocation in this focused row and is now
close to the checked SafeZone-backed row. The warm/dirty-slab prep does not
show a zeroing penalty; it is slightly faster here, likely because it warms
the region slab pool outside the timed section. Therefore this row does not
justify no-zero allocation. It says the next zeroing experiment needs a lower
level fresh/dirty probe that separates pool warmup from mandatory object
clearing, and any no-zero path still needs compiler proof of definite
initialization before it can ship.

## Unsafe No-Zero Lower-Bound Gate

Date/time: 2026-05-15 00:09 CEST.

This gate adds `rift-checked-rift-open-handle-nozero-unsafe`, an explicit
unsafe lower-bound mode for checked Rift handle-backed allocation. The mode
lowers directly to `scalanative_rift_region_alloc_nozero`, writes the object
RTTI/header word, and deliberately skips the object-body zeroing performed by
the normal checked allocation path.

This is not safe checked evidence and is not a default mode. It exists only to
measure the possible ceiling for a future compiler-proven no-zero lowering.
Shipping such a path would require proof that the allocated class is a final
record-like shape, every field is definitely assigned before escape, no
virtual call or `this` escape happens during construction, superclass fields
are handled, and arrays are excluded.

Focused 5M checked Rift no-zero lower-bound gate, 5 measured runs:

| Mode | Median ms | Region op ms | Slow alloc ms | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 71.320 | 2.272 | 1.069 | 4,198,392 | 801,608 | 203735040 | -1183547843768457859 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 65.196 | 2.472 | 1.204 | 0 | 5,000,000 | 203735040 | -1183547843768457859 |

Focused 10M checked Rift no-zero lower-bound gate, 5 measured runs:

| Mode | Median ms | Region op ms | Slow alloc ms | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 155.947 | 17.571 | 7.500 | 4,198,392 | 5,801,608 | 404037632 | -6851333344653324122 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 142.769 | 14.569 | 6.993 | 0 | 10,000,000 | 404037632 | -6851333344653324122 |

Interpretation: skipping object-body zeroing is a real focused allocation
ceiling: about `8.6%` faster at 5M and about `8.5%` faster at 10M, with
matching checksums in this fully initialized benchmark shape and unchanged RSS.
The result validates compiler-proven no-zero as the next promising target after
rejecting runtime-side slab policy variants. It does not justify enabling
no-zero allocation without the static proof described above.

## Proof-Gated No-Zero Lowering

Date/time: 2026-05-15 00:32 CEST.

The normal `RiftOpenStreamingHandle` allocation lowering now has a narrow
compiler/backend proof gate. During lowering, a class allocation through a
Rift open handle is allowed to call `scalanative_rift_region_alloc_nozero`
only if all of these local NIR conditions hold:

- the allocated class is concrete, non-module, and has no reachable subclass;
- its instance fields are primitive value fields;
- every field is stored after allocation;
- those stores happen before the allocated object is otherwise used, before
  control-flow leaves the block, and before any non-pure operation that could
  observe the object or introduce a safepoint.

If any condition fails, the normal handle-backed path still calls
`scalanative_rift_region_alloc` and preserves object-body zeroing. The unsafe
no-zero mode remains only as a lower-bound/provenance row.

Smoke result, 20k objects:

| Mode | Median ms | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 0.431 | 0 | 20,000 | 4,554,752 | -5014597496877744147 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 0.342 | 0 | 20,000 | 4,554,752 | -5014597496877744147 |

Focused 5M proof-gated no-zero gate, 5 measured runs:

| Mode | Median ms | Region op ms | Slow alloc ms | Zeroed objects | Zero-skipped objects | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 65.528 | 2.707 | 1.214 | 0 | 5,000,000 | -1183547843768457859 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 65.277 | 2.506 | 1.130 | 0 | 5,000,000 | -1183547843768457859 |

Focused 10M proof-gated no-zero gate, 5 measured runs:

| Mode | Median ms | Region op ms | Slow alloc ms | Zeroed objects | Zero-skipped objects | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `rift-checked-rift-open-handle` | 139.832 | 13.327 | 6.845 | 0 | 10,000,000 | -6851333344653324122 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 144.286 | 13.318 | 6.761 | 0 | 10,000,000 | -6851333344653324122 |

Interpretation: the proved normal handle-backed row now reaches the unsafe
lower-bound ceiling on this definitely initialized record shape. Relative to
the pre-proof normal handle-backed rows above, the focused allocation path
improves from `71.320 -> 65.528 ms` at 5M and from
`155.947 -> 139.832 ms` at 10M. This is the first safe no-zero optimization,
but only for the local primitive-field, definitely-stored pattern described
above. Application rows still need separate gates because many stream records
contain references or perform query work that dominates allocation body cost.

## Proof-Gated No-Zero For Reference Fields

Date/time: 2026-05-15 01:40 CEST.

The no-zero proof was broadened from primitive-only record classes to
definitely initialized reference-field records. The lowerer still only applies
the optimization to `RiftOpenStreamingHandle` allocations of concrete,
non-module, no-subclass classes, and it still rejects the allocation if the
object is used, control flow exits, or any non-pure operation occurs before all
fields are stored.

The additional safety argument is specific to Rift-backed open handles:
Rift slabs are not scanned by the Scala Native GC, so uninitialized reference
slots are not GC-visible while the object is still unescaped and before every
field store has executed. Checked region safety still has to reject unsafe
region-to-heap references separately; this lowering change does not make
root-free or mixed-reference claims.

Focused reference-record command shape:

```sh
OBJECT_ALLOC_BUILD=0 \
OBJECT_ALLOC_RECORD_SHAPE=reference \
OBJECT_ALLOC_OBJECTS=5000000 \
OBJECT_ALLOC_BENCHMARK_RUNS=5 \
OBJECT_ALLOC_WARMUPS=1 \
OBJECT_ALLOC_MODES="heap-immix rift-checked-rift-open-handle rift-checked-rift-open-handle-nozero-unsafe rift-checked-safezone-improved-32k" \
OBJECT_ALLOC_OUTPUT_DIR=/private/tmp/rift-ref-nozero-5m-20260515 \
  zsh sandbox/run_object_allocation_lowering_matrix.sh
```

5M linked reference-record rows:

| Mode | Median ms | Median GC ms | Region op ms | Slow alloc ms | Region objects | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 307.568 | 231.522 | 0.000 | 0.000 | 0 | 0 | 0 | 640237568 | 3953966985786210233 |
| `rift-checked-rift-open-handle` | 66.249 | 0.080 | 2.000 | 0.988 | 5000001 | 0 | 5000001 | 203882496 | 3953966985786210233 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 66.177 | 0.073 | 1.960 | 0.993 | 5000001 | 0 | 5000001 | 203882496 | 3953966985786210233 |
| `rift-checked-safezone-improved-32k` | 70.196 | 0.000 | 0.000 | 0.000 | 0 | 0 | 0 | 204193792 | 3953966985786210233 |

Primitive-shape regression gate, same 5M x5 run shape:

| Mode | Median ms | Median GC ms | Region op ms | Slow alloc ms | Region objects | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 79.874 | 8.549 | 0.000 | 0.000 | 0 | 0 | 0 | 246284288 | -1183547843768457859 |
| `rift-checked-rift-open-handle` | 63.303 | 0.078 | 2.089 | 1.027 | 5000000 | 0 | 5000000 | 203882496 | -1183547843768457859 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 63.764 | 0.078 | 2.346 | 1.023 | 5000000 | 0 | 5000000 | 203882496 | -1183547843768457859 |
| `rift-checked-safezone-improved-32k` | 67.309 | 0.000 | 0.000 | 0.000 | 0 | 0 | 0 | 204226560 | -1183547843768457859 |

Selected StreamFlexDesign linked-object transfer gate, 1M throughput x3:

| Mode | Median ms | Median GC ms | Max GC ms | Records/sec | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 502.939 | 98.964 | 104.949 | 1988313.358 | 12517376 | -7120610804659902001 |
| `checked-epoch-stream` | 393.107 | 0.000 | 0.178 | 2543839.092 | 12730368 | -7120610804659902001 |
| `checked-epoch-stream-legacy` | 418.081 | 0.000 | 0.183 | 2391879.329 | 12697600 | -7120610804659902001 |
| `checked-epoch-scoped` | 396.186 | 0.000 | 0.190 | 2524065.647 | 12746752 | -7120610804659902001 |

Interpretation: the focused reference-record row reaches the unsafe no-zero
lower-bound ceiling, so the broadened proof works for ordinary linked
region-local object graphs. The StreamFlexDesign transfer gate remains positive
against heap and legacy, but it is not a new application speedup over the
previous noisy 1M StreamFlexDesign row. That workload is now limited more by
stable-state/query CPU, linked traversal, capsule add/drain, and allocation
body outside memset than by reference-field zeroing alone.

Closure rerun, 2026-05-16 16:30 CEST: the reference-record proof gate was
rerun with the same 5M x 5 shape before closing the five-optimization pass.
The normal checked open-handle row again zero-skipped every object and stayed
at the unsafe no-zero ceiling:

| Mode | Median ms | Median GC ms | Region op ms | Slow alloc ms | Region objects | Zeroed objects | Zero-skipped objects | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 311.085 | 234.608 | 0.000 | 0.000 | 0 | 0 | 0 | 640221184 | 3953966985786210233 |
| `rift-checked-rift-open-handle` | 67.109 | 0.082 | 2.321 | 1.147 | 5000001 | 0 | 5000001 | 203866112 | 3953966985786210233 |
| `rift-checked-rift-open-handle-nozero-unsafe` | 69.435 | 0.087 | 2.428 | 1.149 | 5000001 | 0 | 5000001 | 203866112 | 3953966985786210233 |
| `rift-checked-safezone-improved-32k` | 72.053 | 0.000 | 0.000 | 0.000 | 0 | 0 | 0 | 204226560 | 3953966985786210233 |

Validation for the closure pass: `RiftRegionCheckedCompilerTest` passed
`141/141`, and `RiftRegionCheckedTest` passed `65/65`.

## Throughput/RSS Reuse Policy Gate

Date/time: 2026-05-15 14:45 CEST.

`RIFT_REGION_REUSE_POLICY` is now the canonical runtime knob for opt-in slab
reuse behavior:

| Policy | Meaning |
|---|---|
| `default` | Current memory-conservative behavior; 128 MiB regular-slab pool budget. |
| `bulk-zero-retained` | Legacy `RIFT_ZERO_REUSED_SLABS=1` behavior; old env var remains an alias. |
| `cache-small` | Retain a bounded 256 MiB reusable regular-slab pool without eager bulk zeroing. |
| `cache-large` | Retain a bounded 512 MiB reusable regular-slab pool without eager bulk zeroing. |
| `prezero-large` | Retain the 512 MiB pool and prezero reusable slabs at close/reset. |

Huge slabs and explicit close semantics are unchanged. These policies are
throughput/RSS tradeoff modes: they should not be reported as lower-memory wins
unless peak RSS actually improves.

Focused 5M primitive-record gate, 5 measured runs, mode
`rift-checked-rift-open-handle`:

| Policy | Median ms | Region op ms | Slow alloc ms | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `default` | 67.822 | 2.780 | 1.217 | 203849728 | -1183547843768457859 |
| `bulk-zero-retained` | 69.909 | 5.553 | 1.167 | 203849728 | -1183547843768457859 |
| `cache-small` | 65.161 | 0.472 | 0.225 | 204062720 | -1183547843768457859 |
| `cache-large` | 63.566 | 0.437 | 0.210 | 204062720 | -1183547843768457859 |
| `prezero-large` | 67.521 | 3.257 | 0.214 | 204079104 | -1183547843768457859 |

Focused 10M primitive-record gate, 5 measured runs, same mode:

| Policy | Median ms | Region op ms | Slow alloc ms | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|
| `default` | 149.609 | 17.445 | 7.564 | 404029440 | -6851333344653324122 |
| `bulk-zero-retained` | 155.161 | 21.729 | 7.479 | 404029440 | -6851333344653324122 |
| `cache-small` | 134.237 | 6.597 | 2.534 | 404029440 | -6851333344653324122 |
| `cache-large` | 130.250 | 0.894 | 0.408 | 404209664 | -6851333344653324122 |
| `prezero-large` | 133.345 | 6.818 | 0.534 | 404209664 | -6851333344653324122 |

First application smoke, StreamFlexDesign throughput, 1M events, L1 final-clean,
3 in-process repeats:

| Policy | External real s | External user s | RSS bytes | Checksum |
|---|---:|---:|---:|---:|
| `default` | 0.93 | 0.90 | 7913472 | -7120610804659902001 |
| `bulk-zero-retained` | 0.98 | 0.95 | 7913472 | -7120610804659902001 |
| `cache-small` | 0.93 | 0.91 | 7913472 | -7120610804659902001 |
| `cache-large` | 0.93 | 0.90 | 7913472 | -7120610804659902001 |
| `prezero-large` | 0.97 | 0.95 | 7913472 | -7120610804659902001 |

Matching L2 interpretation for default versus the best microgate policy:

| Policy | Median ms | Records/sec | GC ms | Region op ms | Region objects | Opens | Closes | Resets | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `default` | 373.053 | 2680581.566 | 0.000 | 1.330 | 24997627 | 1 | 1 | 3907 | 12746752 |
| `cache-large` | 372.851 | 2682038.617 | 0.000 | 1.247 | 24997627 | 1 | 1 | 3907 | 12746752 |

Interpretation: `cache-large` is a clear focused allocator win at 10M
(`149.609 -> 130.250 ms`, with region-op time dropping
`17.445 -> 0.894 ms`) and only a negligible RSS change in this single-region
microgate. The first StreamFlexDesign application smoke is neutral after a
warm rerun; its L2 region-op time is already near 1 ms, so stable-state/query
CPU and capsule/traversal work dominate. Keep `cache-large` as the current
throughput-biased candidate, but do not promote it as a broad application win
until at least two representative rows improve under L1.

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
