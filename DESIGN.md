# Rift Design

Status: active research design for the Scala Native fork.

Active worktree: `/Users/siyaoliu/rift/scala-native-rift`

Branch: `feature/rift`

Head at time of this revision: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

This document supersedes the older standalone-library framing. The older design
pack in `/Users/siyaoliu/rift/Claude_output` is useful provenance, but the
active artifact is the Scala Native fork.

## 1. Objective

Rift is a hybrid region-GC memory system for Scala Native. The intended
contribution is not merely "a faster SafeZone." The target is a system that
combines:

- Fast region allocation and bulk close/reset inside the Scala Native runtime.
- A GC heap for ordinary control objects and objects whose lifetimes are not
  statically predictable.
- Region-managed memory for data-path objects with structured lifetimes.
- Scala-facing region APIs that can eventually be checked by Scala 3 capture
  checking, especially for closures and higher-order code.
- Region-shaped stream and collection libraries that make logical lifetimes
  visible to the allocator.
- A mechanized core safety argument for containment and safe close/reset.

The current implementation has only part of that system. The runtime path,
compiler lowering, baseline corrections, benchmark harnesses, and DEBS scaffold
exist. The capture-checked safe API, region-heavy DEBS path, region-backed
parallel collections, and Lean proof are still open.

## 2. Evidence Levels

Use these labels in all claims:

| Label | Meaning |
|---|---|
| Validated | Backed by checked-in commands, recorded run counts, and matching outputs or tests. |
| Partially validated | Implemented and exercised, but missing full test coverage, medians, or comparison modes. |
| Provisional | Single-run, surrogate, reconstructed, or generated from uncommitted code. |
| Planned | Design target only; no implementation evidence yet. |

Current reliable evidence:

- Phase 0 GCBench and ListOfLists runtime medians are validated enough to use as
  local baseline evidence.
- Phase 4 topology and layout studies are validated enough to move on, with the
  explicit caveat that several results use uncommitted runtime changes.
- DEBS correctness on bounded sorted samples is partially validated.
- DEBS performance is provisional and not yet an application-level Rift win.
  Current phase timers show Q2 processing is the dominant measured phase, while
  GC and Rift region operations are both small shares of total elapsed time on
  the 100k bounded sample.
- The raw-array pipeline is a surrogate and must not be presented as a
  replacement for Broom-style or `ZoneParVector` collection evidence.

## 3. What Changed

The original project story over-indexed on replacing SafeZone with a standalone
arena. The fork evidence and literature review require a narrower, stronger
framing:

- SafeZone must remain in the comparison set. `SAFEZONE_ROOTS_MODE=1` changes
  the baseline materially and closes much of the old root-bookkeeping gap.
- Runtime speed still matters. It is not acceptable to explain every win as
  "the data structure changed." Rift must keep same-layout runtime wins.
- Layout and topology are first-order effects. They must be reported separately
  from allocator effects.
- Application evidence must allocate the application's dominant data operations
  in regions. Current DEBS region-allocates Q1/Q2 window entries, Q2 active
  profit values, Q2 median scratch, the RunBoth input buffer, and provisional
  ranking/result objects in Rift modes. It still does not prove an
  application-level win because ranking/result reset costs are visible and
  control collections remain heap-managed.
- The literature-review target is broader than local baselines: Rift must be
  compared against Broom, StreamFlex, Yak, Stancu-style hybrid static analysis,
  the MLKit typed-region lineage, and Reggio/Verona-style capabilities.

## 4. Comparison Contract

Rift should be judged against prior systems on performance, safety,
annotations, support for Scala-like higher-order OO code, closure capture,
native compatibility, and proof obligations.

| Prior system | Main strength | Main limitation | Current Rift addresses | Still needed to surpass or differentiate |
|---|---|---|---|---|
| Broom | Maps dataflow lifetimes to transferable, actor, and temporary regions; reports large synthetic allocation wins. | Prototype has no enforced safety, no formal model, no closure story, and CLR/Bartok dependency. | Rift adopts the dataflow-lifetime insight and has in-fork region allocation plus DEBS scaffolding. | Build a real region-backed operator/collection API, show end-to-end stream wins, and enforce containment statically. |
| StreamFlex | Statically safe zero-GC stream processing with implicit ownership and very low latency. | Domain-specific filter/channel model, primitive-only capsules, no lambdas/closures, no mechanized proof. | Rift targets stream workloads in Scala Native and keeps GC for control objects rather than requiring full heap isolation. | Implement a safe streaming API, prove closure/capture constraints, and show latency/GC-pause evidence on streaming workloads. |
| Yak | Hybrid control-space GC plus data-space regions; dynamic promotion makes epochs safe with very low annotation burden. | Purely dynamic, write-barrier overhead, STW during epoch end, JVM-specific, no formal type story. | Rift adopts the two-space control/data split and avoids write barriers as a design goal. | Replace Yak's dynamic safety with static capture checking, then show comparable GC-time reductions without barriers/STW promotion. |
| Stancu et al. `@RegionScope` | Static points-to analysis infers region allocation from few annotations and falls back to GC. | Closed-world whole-program analysis, coarse phases, no higher-order Scala story, no formal proof. | Rift is already compiler-integrated and aims for lightweight region boundaries plus inference. | Implement capture-guided allocation/fallback, count annotations per KLOC, and avoid closed-world assumptions where possible. |
| Tofte-Talpin / MLKit | Deep formal basis, automatic region inference, region polymorphism, zero annotations in ML. | ML-specific, region size problem, unpredictable lifetimes with closures, no Scala subtyping/object model. | Rift borrows region polymorphism and containment as proof targets. | Keep annotation burden low without pretending full Scala inference is tractable; mechanize the higher-order case. |
| Hallenberg-Elsman-Tofte, PLDI 2002 | Regions plus intra-region GC handle region leaks and preserve region safety. | Copying GC and tags conflict with Scala Native's native/non-moving assumptions; conservative closure restrictions. | Rift keeps a non-moving GC heap and region slabs compatible with native code. | Decide whether safe mixed region/GC references require handles, GC-scanned metadata, or stricter static rejection. |
| Elsman-Hallenberg typed regions | Typed regions enable tag-free layouts and write-barrier-free generational GC. | ML-only, stack discipline, rare mutation, STW, no mechanized proof, later soundness concerns in the lineage. | Rift's native backend could eventually exploit typed-region/BIBOP-style layout information. | This is not implemented. A future phase must define region type metadata and prove layout/GC safety. |
| Reggio / Verona | Static reference capabilities isolate regions and permit per-region memory strategies. | Forest topology, single mutable window, pervasive annotations, limited higher-order support, no mechanized proof. | Rift plans to use capture/separation checking as a lower-annotation capability analogue. | Show multiple active regions and Scala closures can be checked safely without Reggio's annotation burden. |
| Pony / Orca | Actor-local heaps, ownership transfer, no STW pauses or barriers in the actor model. | Actor model is a major programming-model constraint. | Rift can borrow the principle that type-system isolation enables cheaper runtime memory management. | Demonstrate low pause times without requiring users to rewrite code into actors. |
| Scala Native SafeZone | Existing in-tree region-like mechanism with GC root integration. | Current mode has root-bookkeeping pathologies; safety is dynamic/root-based, not capture-proven. | Rift work improved the SafeZone baseline and exposed the relevant runtime costs. | Rift must beat improved SafeZone, not only old SafeZone, and must offer a checked safe path rather than only HPZone. |

The contribution bar is therefore a combination claim:

- Performance: same-layout runtime wins plus application wins where region
  memory covers dominant data operations.
- Safety: statically checked non-escape and safe close/reset for the safe API.
- Ergonomics: far fewer annotations than capability-heavy systems, closer to
  phase-boundary annotations plus capture inference.
- Scala fit: higher-order functions and closures are central, not optional.
- Native fit: non-moving GC assumptions and C interop must remain valid.
- Proof: a mechanized core containment theorem, not only prose.

## 5. Memory Model

Rift uses two memory spaces:

| Space | Purpose | Current status |
|---|---|---|
| `H_GC` | Scala Native heap managed by Immix/Commix for ordinary Scala objects and unpredictable lifetimes. | Existing Scala Native runtime. |
| `H_R` | Rift-managed slab regions for structured-lifetime data objects. | Implemented for HPZone-style allocation in the fork. |

Region modes:

| Mode | Intended meaning | Current status |
|---|---|---|
| `HPZone` | Trusted fast region path for benchmarks and hot loops. No static escape guarantee. | Implemented as a runtime kind and exercised by benchmarks. |
| `Scoped` | Lexically scoped, capture-checked region. | Runtime kind exists; static safe API is not implemented. |
| `Streaming` | Resettable streaming region with capture/use-after-reset constraints. | Runtime kind exists; static safe API is not implemented. |

The important corrected invariant is about GC visibility:

- GC-to-region references are forbidden for safe regions unless the type system
  proves the region is live wherever the heap object can be reached.
- Region-to-GC references are not automatically safe in the implementation.
  Scala Native's GC does not scan Rift region memory. A heap object reachable
  only from a Rift region may be collected.
- Safe mixed region/GC designs must use one of three strategies: reject owning
  region-to-GC references statically, register/scavenge region metadata with
  the GC, or require another GC-visible root/handle for every heap object
  referenced from a region.
- `HPZone` remains a trusted path. It may be used to measure runtime potential,
  but it is not the safety story.

Region-managed values are allowed to be ordinary Scala objects. Rift is not a
primitive-record-only system. Packed primitive keys in the current DEBS code are
an interim boundary technique used where the runtime cannot yet prove or expose
mixed-reference safety. The intended v1 safe mixed-reference policy is:

- `GC -> region`: allowed only when the heap object's type/capture set proves
  the heap object cannot outlive the region it points into.
- `region -> GC`: allowed only for immutable/static referents or explicit
  GC-visible root handles. A region object must not be the sole owner of a
  collectible heap object because Scala Native's GC does not scan Rift slabs.
- No GC scanning of arbitrary region slabs in v1; that would complicate
  non-moving native-runtime assumptions and should be evaluated only after the
  explicit-root/static-capture design is tested.

Stable heap references used by region objects must therefore be independently
rooted metadata, static/immutable objects, or future checked root handles. The
current benchmark HPZone paths are trusted experiments and must be labeled as
such when they store heap metadata or heap collection entries that point at
region objects.

This correction comes from two repo-grounded findings:

- `sandbox/PHASE4_TOPOLOGY.md` records an unrooted mixed Rift topology checksum
  mismatch when region nodes pointed at heap value objects.
- `bench/debs2015/RESULTS.md` records an earlier Q1 issue where region entries
  stored heap `Route` objects; the fixed Q1 path stores packed `Long` keys.

## 6. Runtime Design

The active in-tree runtime is under:

- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`
- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.h`

Current runtime properties:

- 32 KB regular slabs.
- `mmap`-backed slab acquisition.
- Thread-local cache up to 8 slabs.
- Global Treiber stack for free slabs.
- Region open, close, reset, raw allocation, and managed object allocation.
- Slab resident counters and instrumentation counters.
- Fresh mmapped slabs are treated as zero-filled.
- Reused slabs are zeroed once when reacquired, not per object.
- The reset path zeroes the retained first slab before reuse.
- Huge slabs avoid the synchronous `MAP_POPULATE`/`MADV_HUGEPAGE` path that
  caused a flat-layout regression.

SafeZone changes are part of the design, not noise:

- `SAFEZONE_ROOTS_MODE=0` is the current baseline.
- `SAFEZONE_ROOTS_MODE=1` coalesces root removal and is the improved baseline.
- `SAFEZONE_TRACE` and `SAFEZONE_PAGE_SIZE` support diagnosis.
- `GC_Roots_RemoveByRange` was fixed for fully covered root ranges.

Open runtime issues:

- The stats functions exposed to Scala externs are not declared in
  `RiftRuntime.h`.
- Full Scala Native tests have not been run.
- Commix coverage is missing from most benchmark matrices.
- Instrumentation timing on macOS is useful for diagnosis but not a final
  low-overhead design.

## 7. Compiler And Scala API

Current compiler/API implementation:

- `RiftRegion` extends the allocation-zone shape used by SafeZone.
- `region.alloc(new T(...))` lowers through compiler-plugin support.
- The plugin added `RIFT_ALLOC`, `RuntimeRiftAllocator_allocate`, and a
  generalized allocation-zone attachment.
- Scala 3 companion support exists for the allocation syntax.
- Scala 2 compatibility surface exists where needed.

Key files:

- `nativelib/src/main/scala/scala/scalanative/memory/RiftRegion.scala`
- `nativelib/src/main/scala/scala/scalanative/runtime/RiftAllocator.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirDefinitions.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirGenExpr.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirPrimitives.scala`

This is compiler integration for allocation. It is not yet capture checking.

## 8. Static Safety Strategy

The safe API target is a capture-checked region discipline:

- Region handles are capabilities.
- Values allocated in a region capture that region capability.
- A value cannot outlive any region capability in its capture set.
- Closing or resetting a region is legal only when no accessible value can use
  that region afterward.
- Closures must carry the regions captured by their environment.
- Region-polymorphic functions should allow reusable higher-order code without
  forcing everything into one global region.

The design intentionally avoids:

- Yak-style write barriers for every heap write.
- Reggio-style pervasive capability annotations on every type.
- Fully automatic Tofte-Talpin inference for all Scala code.
- Cheney copying collection inside regions, because Scala Native relies on
  native/non-moving assumptions.

The open hard problem is closure-region interaction. Prior systems either
avoid closures, restrict them heavily, or handle escapes dynamically. Rift must
make this problem explicit in tests and proof obligations.

Minimum Phase 6 evidence:

- Positive cases for for-loops, nested regions, and higher-order functions.
- Negative cases for return escape, closure capture escape, cross-region
  leakage, use-after-reset, and unrooted region-to-GC ownership.
- A report stating exactly what current Scala capture checking can express and
  what requires compiler or API changes.

## 9. Application And Library Design

The application/library story must follow the literature-review lesson:
regions win when the program exposes structured lifetimes.

For Broom-like dataflow:

- Messages or window buckets should be self-contained or explicitly rooted.
- Operator state should live in actor/operator regions or the GC heap depending
  on lifetime.
- Temporary scratch should live in scoped or streaming regions.
- The API must make ownership transfer and region close/reset explicit enough
  for static checking.

For DEBS 2015:

- Benchmark fairness rule: heap and Rift variants should share the same query
  algorithm and data-shape as much as possible. The variable under test should
  be allocation placement and lifetime management: heap uses ordinary `new`;
  Rift uses minimal region annotations, `region.alloc`, and close/reset at the
  same logical lifetime boundary.
- Current Q1 uses a shared bucketed-window implementation. Heap mode allocates
  the same `BucketWindowEntry` class on the GC heap; Rift modes allocate that
  class in per-timestamp regions and close the bucket region at eviction.
- Current Q2 uses a shared bucketed-window implementation for profit and
  empty-taxi window entries. Heap mode allocates the same entry classes on the
  GC heap; Rift modes allocate those entries in per-timestamp regions and close
  each region at window eviction.
- RunBoth parsing now uses a shared byte parser. Heap mode allocates the input
  byte buffer on the GC heap; Rift modes allocate the same run-lifetime input
  buffer in a region. The hot RunBoth path avoids `String.split`, per-row
  `Option`, per-row `Trip`, per-row line strings, and per-row taxi/timestamp
  substrings. Durable taxi IDs remain heap metadata and are interned only when
  first seen.
- Q1 ranking uses heap `HashMap`/`TreeSet` control metadata. In Rift modes,
  `RankedRoute`, `Route`, and `Cell` ranking objects are now allocated in a
  run-lifetime region, and returned top-k arrays are allocated in a resettable
  snapshot region.
- Q2 window queues now hold primitive-key entries in heap or Rift memory, and
  active profit values are stored in those entries rather than duplicated in a
  heap `ArrayBuffer`.
- Q2 median sorting arrays are heap-allocated in heap mode and allocated in a
  resettable scratch region in Rift modes. The current Rift version resets that
  scratch region on every median recomputation, which is correct but likely too
  fine-grained for final performance evidence.
- Q2 still uses heap maps, `ProfitStats` control metadata, `TreeSet`, taxi-id
  metadata, and latency arrays. In Rift modes, `ProfitableArea` ranking objects
  are region-allocated, returned top-k arrays are allocated in a resettable
  snapshot region, and output rows are written directly through shared
  writer-based formatting rather than per-row `StringBuilder.toString`.

Therefore current DEBS exercises more of the region-heavy application design,
but it is still not Phase 5 success. The newest ranking/result experiment
reduces GC time and can reduce RSS at 1M, but it makes region reset/bookkeeping
visible and slows elapsed time. The remaining pressure is in heap collection
metadata, latency arrays, broader collection state, and the need for a
lower-overhead region-backed top-k/result view rather than resetting a 32 KB
snapshot slab on every processed event.

For parallel collections:

- The current raw-array pipeline surrogate is not comparable to amordo
  `ZoneParVector`.
- A fair comparison requires a `RiftParVector` or equivalent region-backed API
  with the same workload shape as the parallel-collections benchmark.

## 10. Current Benchmark Summary

Validated local runtime medians:

| Benchmark | Immix heap | Current SafeZone | Improved SafeZone | Rift HPZone |
|---|---:|---:|---:|---:|
| GCBench, 5 runs | 203.514 ms | 684.517 ms | 223.770 ms | 161.641 ms |
| ListOfLists linked, 5 runs | 15085.511 ms | 138171.998 ms | 10132.854 ms | 6951.331 ms |

Layout study:

| Mode | Linked ms | Chunked ms | Flat ms |
|---|---:|---:|---:|
| Immix heap | 15260.875 | 2636.480 | 1766.295 |
| current SafeZone | 136016.922 | 4835.904 | 1735.453 |
| improved SafeZone | 9824.936 | 2369.108 | 1743.161 |
| Rift HPZone | 7278.150 | 2463.674 | 1515.091 |

Report-scale topology:

| Mode | Topology | Median ms |
|---|---|---:|
| Immix heap | full heap graph | 14755.580 |
| current SafeZone | one-region | 136016.922 |
| current SafeZone | nested | 10289.097 |
| improved SafeZone | one-region | 9767.048 |
| improved SafeZone | nested | 9929.699 |
| Rift HPZone | one-region | 7256.426 |
| Rift HPZone | nested | 7314.201 |
| Rift HPZone | mixed rooted heap-values | 11695.748 |

Pipeline status:

| Benchmark | Result |
|---|---|
| Cleaned raw-array surrogate, 2M records | Heap, SafeZone, and Rift are near parity; Rift is not a clear win. |
| amordo `ZoneParVector`, 100k records | Region-per-stage median 30.36 ms, on-heap `ParVector` median 30.75 ms. |
| Rift raw-array surrogate, 100k records | Heap raw arrays 1.845 ms, Rift HPZone 2.502 ms; not apples-to-apples with `ZoneParVector`. |

DEBS status:

| Run | Heap | Rift HPZone | Rift Streaming | Evidence level |
|---|---:|---:|---:|---|
| 1M RunBoth, elapsed | 21658.215 ms | 22314.989 ms | 22184.467 ms | Single-run, provisional |
| 1M instrumented, elapsed | 22827.882 ms | 22366.285 ms | 22810.687 ms | Single-run, provisional |
| 1M instrumented, GC time | 1051.752 ms | 1003.171 ms | 1012.489 ms | Single-run, provisional |

Interpretation:

- Rift has credible same-layout runtime wins on GCBench and linked ListOfLists.
- Improved SafeZone is a serious baseline.
- Layout changes can dominate allocator changes and must be separated.
- Current DEBS does not yet reduce GC time enough because most application data
  operations remain heap-based.
- DEBS changes that merely hand-optimize the heap query implementation are not
  evidence. A change is Phase-5 relevant only if it preserves the same logical
  benchmark for heap and Rift while moving structured-lifetime data into region
  memory or making that lifetime boundary explicit.
- The current Q2 direction follows that boundary: timestamp-bucket window
  entries and median scratch buffers are region-backed in Rift modes, while
  long-lived maps/trees remain heap metadata. Primitive packed keys are used in
  the shared logic to avoid accidental heap objects in the hot path.

## 11. Formal Model

The proof target should be a small calculus, not the whole Scala compiler:

- Terms for allocation, region open, region close, reset, function values,
  capture sets, and GC fallback.
- A two-space store with `H_GC` and `H_R`.
- Liveness of region capabilities.
- A typing judgment that records capture sets.
- An operational semantics that permits closing/resetting only when no live
  value can still access the region.

Required theorems:

- Progress.
- Preservation.
- Containment: every reachable value is in `H_GC` or in a live region allowed
  by its capture set.
- Safe close/reset: closing or resetting a region cannot leave a reachable
  dangling pointer.

The MLKit lineage shows why this cannot stay pen-and-paper only: later work
found soundness problems in higher-order polymorphic cases. Rift's proof must
pay special attention to higher-order functions and closures.

## 12. Claim Boundaries

Claims that are currently supported:

- In-fork Rift HPZone can beat heap and improved SafeZone on specific
  allocation-heavy same-layout workloads.
- Improved SafeZone changes the baseline materially.
- Topology and layout effects are large enough to require separate reporting.
- Region memory is not GC-scanned, so unrooted region-to-GC references are
  unsafe in the current runtime.

Claims that are not yet supported:

- Rift has a strong DEBS application-level speedup.
- Rift has a capture-checked safe API.
- Rift beats Broom, StreamFlex, Yak, Stancu et al., or Reggio on their strongest
  axes.
- Rift has lower p99/p99.9 latency than low-pause JVM collectors.
- Rift has a mechanized proof.

The project should narrow claims honestly until those gaps are closed.
