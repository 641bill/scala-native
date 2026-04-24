# Rift Roadmap

Status: revised from the active fork state, benchmark notes, handoff, and the
literature-review comparison contract.

Active worktree: `/Users/siyaoliu/rift/scala-native-rift`

Branch: `feature/rift`

Head at time of this revision: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

## 1. Roadmap Principles

The roadmap separates four questions:

- Can the runtime path itself beat current SafeZone, improved SafeZone, and
  heap on same-layout workloads?
- Which effects come from runtime, topology, and layout?
- Can real stream/dataflow workloads move dominant data operations into regions?
- Can the safe API be justified by static capture checking and a mechanized
  containment proof?

The roadmap also keeps prior-work comparison anchors visible. Rift is not done
when it beats old SafeZone on one benchmark. It must eventually explain its
position relative to Broom, StreamFlex, Yak, Stancu-style static hybrid
analysis, Tofte-Talpin/MLKit, Hallenberg-Elsman typed regions, and
Reggio/Verona capabilities.

## 2. Status Summary

| Phase | Status | Evidence | Main remaining work |
|---|---|---|---|
| Phase 0: baseline and comparison contract | Mostly complete, not sealed | `sandbox/PHASE0_BASELINES.md`; literature review now read as design constraint. | Commit/provenance boundary, Commix gaps, pipeline remains surrogate. |
| Phase 1: standalone allocator bootstrap | Done as provenance | `/Users/siyaoliu/rift/rift-bootstrap/bench/microbench/results.md`. | Do not continue active design there unless validating bootstrap artifacts. |
| Phase 2: in-tree runtime/compiler path | Partially done | `RiftRuntime.c/h`, `RiftRegion`, plugin lowering, `RiftRegionTest`. | Header/API cleanup, broader tests, commit boundary, stats ABI decision. |
| Phase 3: runtime-only evaluation | Done enough for current claim | GCBench and ListOfLists medians show Rift wins over heap and improved SafeZone. | Add Commix where relevant; avoid overclaiming pipeline. |
| Phase 4: topology/layout decomposition | Done enough to move on | `PHASE4_LAYOUT.md`, `PHASE4_TOPOLOGY.md`, `PHASE4_EXIT.md`. | Carry safety finding into Phase 6; chunked layout still not clear Rift win vs improved SafeZone. |
| Phase 5: application evidence | In progress, not complete | DEBS Q1/Q2 scaffold runs and outputs match on bounded real-data samples; RunBoth uses a shared byte parser and region-backed input buffer; Rift modes now region-allocate Q1/Q2 window entries, Q2 median scratch, ranking objects, reusable top-k result arrays, and Q2 bounded cell tables. The current 1M 3-run median after Q2 cell tables has Rift HPZone slightly faster than heap with lower GC/RSS and `11.759 ms` Rift op time. | Q1 maps/trees, Q2 TreeSet/taxi metadata/latency arrays, SafeZone/Commix modes, full-month scale, safe API boundaries. |
| Phase 6: Broom/parallel-collections API evidence | Open | Only raw-array surrogate and amordo comparison note exist. | Build fair Rift-backed collection/operator API. |
| Phase 7: capture-checked safe API | Open | Runtime kind constants exist; safety tests not implemented. | Positive/negative capture tests, safe `Scoped`/`Streaming` API, report gaps. |
| Phase 8: native GC/region integration hardening | Open | Safety bug found for unrooted region-to-GC references. | Decide reject/root/scan strategy; test mixed references. |
| Phase 9: Lean mechanization | Open | Design target only; older proof pack is not active in fork. | Core calculus and proofs without `sorry`. |
| Phase 10: writing | Not started beyond notes | Handoff and result packs exist. | Paper/thesis narrative after evidence stabilizes. |

Phase 0 is not "final." It is complete enough for GCBench and ListOfLists, but
not for a final pipeline or application story.

## 3. Phase 0: Baseline And Comparison Contract

Goal: establish trustworthy local baselines and explicit prior-work comparison
targets before making claims.

Completed:

- GCBench 5-run matrix: Immix, current SafeZone, improved SafeZone, Rift HPZone.
- ListOfLists 5-run matrix: Immix, current SafeZone, improved SafeZone, Rift
  HPZone.
- GCBench topology rerun.
- Cleaned raw-array pipeline surrogate.
- Literature-review comparison contract covering Broom, StreamFlex, Yak,
  Stancu et al., Tofte-Talpin/MLKit, Hallenberg-Elsman-Tofte,
  Reggio/Verona, and Pony/Orca.

Key local baseline medians:

| Benchmark | Immix | Current SafeZone | Improved SafeZone | Rift HPZone |
|---|---:|---:|---:|---:|
| GCBench | 203.514 ms | 684.517 ms | 223.770 ms | 161.641 ms |
| ListOfLists linked | 15085.511 ms | 138171.998 ms | 10132.854 ms | 6951.331 ms |

Exit criteria:

- Baseline commands are checked in.
- Results state run counts and environment variables.
- Surrogates are labeled as surrogates.
- The comparison section in `DESIGN.md` stays visible in future docs.

Remaining:

- Add Commix where it is a meaningful comparison.
- Stabilize current uncommitted work into a commit or patch boundary.
- Do not treat the pipeline surrogate as a tracked-source reproduction.

## 4. Phase 1: Standalone Allocator Bootstrap

Goal: prove the slab allocator core before in-tree integration.

Status: done as historical provenance, not active architecture.

Evidence:

- Old bootstrap tree: `/Users/siyaoliu/rift/rift-bootstrap`.
- Recorded microbench results show Rift HPZone allocation around `2.1 ns`
  p50 and close/free around `3000 ns` p50 for 100000 allocations.

Do not redo:

- Do not restart implementation from the standalone scaffold.
- Use it only for provenance or focused allocator sanity checks.

## 5. Phase 2: In-Tree Runtime And Compiler Path

Goal: make Rift a real Scala Native runtime/compiler path.

Completed or partially completed:

- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`
- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.h`
- `nativelib/src/main/scala/scala/scalanative/memory/RiftRegion.scala`
- `nativelib/src/main/scala/scala/scalanative/runtime/RiftAllocator.scala`
- Scala 2/3 companion surface.
- Compiler primitive and lowering for `region.alloc(new T(...))`.
- `RiftRegionTest` recorded as passing `5/5`.
- SafeZone baseline correction in `MemoryPool.c`, `Zone.c`, and
  `GCRoots.c`.

Exit criteria:

- Full API/header consistency, including stats functions or an explicit choice
  to keep them private.
- Dedicated compiler-plugin regression tests.
- Broader Scala Native test subset beyond `RiftRegionTest`.
- No untracked implementation files for the core runtime/API.

Risks:

- `RiftRuntime.h` does not currently declare all stats functions used by Scala
  externs.

## 6. Phase 3: Runtime-Only Evaluation

Goal: establish whether the runtime claim stands before relying on layout or
library redesign.

Completed enough:

- GCBench: Rift HPZone `161.641 ms` vs heap `203.514 ms` and improved SafeZone
  `223.770 ms`.
- ListOfLists linked: Rift HPZone `6951.331 ms` vs heap `15085.511 ms` and
  improved SafeZone `10132.854 ms`.

Interpretation:

- Same-layout runtime wins exist.
- Old SafeZone pathologies reproduce in the current fork for linked one-region
  shapes.
- Improved SafeZone is much stronger and must remain a baseline.
- The cleaned pipeline does not currently show Rift separation.

Remaining:

- Add Commix where supported.
- Keep the runtime claim scoped to workloads where measurements support it.
- Do not use layout-specialized results to prove same-layout runtime speed.

## 7. Phase 4: Topology And Layout Decomposition

Goal: separate allocator wins from topology wins and data-layout wins.

Completed enough:

- Linked, chunked, and flat ListOfLists layout study.
- One-region, nested, and mixed rooted topology study.
- Exit summary tying runtime, layout, topology, and safety findings together.

Key results:

| Mode | Linked ms | Chunked ms | Flat ms |
|---|---:|---:|---:|
| Immix heap | 15260.875 | 2636.480 | 1766.295 |
| current SafeZone | 136016.922 | 4835.904 | 1735.453 |
| improved SafeZone | 9824.936 | 2369.108 | 1743.161 |
| Rift HPZone | 7278.150 | 2463.674 | 1515.091 |

Key findings:

- Layout is first-order for every runtime.
- Rift still has a runtime win on linked allocation-heavy structures.
- The flat Rift cliff was a runtime bug and was fixed.
- The first chunked layout does not clearly beat improved SafeZone.
- Mixed rooted heap-values is not a Rift win.
- Unrooted region-to-GC references are unsafe because the GC does not scan Rift
  regions.

Exit criteria:

- Phase 4 is complete enough to move to Phase 5.
- The safety finding must be tested and designed around in Phase 7/8.

## 8. Phase 5: Application Evidence And DEBS 2015

Goal: produce real application-level evidence that region memory reduces the
dominant allocation and GC costs in a streaming workload.

Current status:

- `bench/debs2015` scaffold exists.
- Q1 and Q2 run simultaneously on sorted bounded real NYC taxi samples.
- Heap, Rift HPZone, and Rift Streaming outputs match after stripping only the
  measured latency column.
- Instrumented runs report GC counters, RSS, and Rift counters.
- The RunBoth hot path now uses a shared byte parser. Heap mode uses a heap
  input byte buffer; Rift modes allocate the same run-lifetime input buffer in
  a region. The parser avoids `String.split`, per-row `Option`, per-row `Trip`,
  per-row line strings, and per-row taxi/timestamp substrings in the RunBoth
  path.
- Q1 now uses a shared bucketed-window implementation for heap and Rift. Heap
  allocates the same bucket-entry class with `new`; Rift allocates it with
  `region.alloc` and closes the per-timestamp region at bucket eviction.
- Q2 now uses the same shared-backend shape for profit-window and empty-taxi
  window entries. Heap allocates the same entry classes with `new`; Rift
  allocates them with `region.alloc` and closes each per-timestamp region at
  window eviction.

Current limitation:

- Current Rift DEBS region-allocates Q1 and Q2 window entries using the same
  bucketed algorithms as heap.
- Q1 ranking control metadata still uses heap maps/trees, but Rift modes now
  allocate Q1 `RankedRoute`/`Route`/`Cell` objects in run-lifetime regions.
- Q2 replaced boxed per-cell `HashMap` tables for active profits, empty counts,
  latest sequence, and current ranked area with fixed arrays over the bounded
  grid key space. Heap allocates those arrays and `ProfitStats` with `new`;
  Rift modes allocate them in the run-lifetime ranking/control region.
- Q2 still uses heap `TreeSet`, `latestEmptyByTaxi`, taxi-id metadata, and
  latency arrays.
- Returned top-k arrays are cached by exact size and reused. In Rift modes the
  cached arrays are region-allocated; runners keep only heap primitive snapshots
  between outputs so region-backed arrays do not escape across `process` calls.
- Output formatting now writes directly to `Writer` through shared code and
  avoids hot per-row `StringBuilder.toString` and Q2 `f""` formatting.
- Q2 median scratch arrays are region-backed in Rift modes and reused with
  capacity growth. Q2 ranking uses packed primitive cell keys internally.
- Therefore current DEBS does not yet establish that Rift is faster at the
  application level.

Current provisional evidence:

| Run | Heap | Rift HPZone | Rift Streaming |
|---|---:|---:|---:|
| 1M RunBoth elapsed | 21658.215 ms | 22314.989 ms | 22184.467 ms |
| 1M instrumented elapsed | 22827.882 ms | 22366.285 ms | 22810.687 ms |
| 1M instrumented GC time | 1051.752 ms | 1003.171 ms | 1012.489 ms |
| 100k after Q2 windows, elapsed | 1837.501 ms | 1794.929 ms | 1843.627 ms |
| 100k after Q2 windows, region objects | 0 | 294284 | 294284 |
| 100k after Q2 profit values, elapsed | 1816.773 ms | 1791.195 ms | 1802.942 ms |
| 100k after Q2 median scratch, elapsed | 1881.304 ms | 1920.875 ms | 1937.911 ms |
| 100k after Q2 median scratch, region resets | 0 | 171197 | 171197 |
| 100k after per-trip scratch reset, elapsed | 1748.744 ms | 1775.350 ms | 1768.716 ms |
| 100k after per-trip scratch reset, region resets | 0 | 87438 | 87438 |
| 100k after primitive Q2 ranking keys, elapsed | 1813.074 ms | 1863.806 ms | 1832.249 ms |
| 100k phase share after primitive Q2 ranking keys, Q2 process | 48.6% | 48.8% | 48.9% |
| 100k after region-backed input buffer, elapsed | 1557.171 ms | 1703.600 ms | 1591.372 ms |
| 100k after region-backed input buffer, read+parse share | 9.6% | 8.8% | 9.4% |
| 1M after region-backed input buffer, elapsed | 18692.484 ms | 17789.410 ms | 17280.431 ms |
| 1M after region-backed input buffer, GC time | 731.171 ms | 644.394 ms | 643.015 ms |
| 1M median after region-backed input buffer, elapsed | 17047.611 ms | 17119.396 ms | 17210.458 ms |
| 1M median after region-backed input buffer, GC time | 684.338 ms | 640.062 ms | 628.808 ms |
| 1M single after region ranking/result snapshots, elapsed | 16363.012 ms | 17243.387 ms | 17301.238 ms |
| 1M single after region ranking/result snapshots, GC time | 601.615 ms | 503.198 ms | 529.115 ms |
| 1M single after region ranking/result snapshots, Rift op time | 0.000 ms | 933.640 ms | 936.641 ms |
| 1M single after region ranking/result snapshots, peak RSS | 1148436480 | 1088684032 | 1088618496 |
| 100k 3-run median after reusable ranking arrays, elapsed | 1286.990 ms | 1262.091 ms | 1265.775 ms |
| 100k 3-run median after reusable ranking arrays, GC time | 61.087 ms | 35.098 ms | 35.855 ms |
| 100k 3-run median after reusable ranking arrays, Rift op time | 0.000 ms | 1.430 ms | 1.420 ms |
| 1M 3-run median after reusable ranking arrays, elapsed | 14633.019 ms | 14234.470 ms | 14392.097 ms |
| 1M 3-run median after reusable ranking arrays, GC time | 513.199 ms | 457.726 ms | 478.912 ms |
| 1M 3-run median after reusable ranking arrays, Rift op time | 0.000 ms | 13.003 ms | 14.166 ms |
| 1M 3-run median after reusable ranking arrays, peak RSS | 813105152 | 876101632 | 876101632 |
| 100k 3-run median after Q2 cell tables, elapsed | 1037.748 ms | 997.997 ms | 983.928 ms |
| 100k 3-run median after Q2 cell tables, GC time | 45.193 ms | 33.453 ms | 32.947 ms |
| 100k 3-run median after Q2 cell tables, Rift op time | 0.000 ms | 1.745 ms | 1.349 ms |
| 100k 3-run median after Q2 cell tables, peak RSS | 217137152 | 172113920 | 172097536 |
| 1M 3-run median after Q2 cell tables, elapsed | 11471.085 ms | 11442.637 ms | 11477.431 ms |
| 1M 3-run median after Q2 cell tables, GC time | 478.636 ms | 419.658 ms | 428.339 ms |
| 1M 3-run median after Q2 cell tables, Rift op time | 0.000 ms | 11.759 ms | 11.421 ms |
| 1M 3-run median after Q2 cell tables, peak RSS | 609730560 | 490766336 | 490799104 |

Immediate next step:

- Preserve benchmark fairness before adding more region code. Heap and Rift
  variants should be the same logical program with allocation/lifetime policy
  as the variable, not separate hand-specialized algorithms.
- The input-buffer median rerun is complete. The single-run Rift win after the
  byte-reader change did not hold as a median: Rift reduced GC time, but region
  operation time offset it.
- The first ranking/result-region experiment showed that resettable top-k
  snapshot regions are the wrong lifetime shape.
- The follow-up reusable ranking-array backend removed that reset churn and made
  Rift region-operation time small again. The current 1M 3-run median has Rift
  HPZone faster than heap, but this remains bounded-sample evidence with an RSS
  tradeoff and missing SafeZone/Commix modes.
- The Q2 cell-table step replaced boxed per-cell maps with a shared bounded
  array layout. It improves both heap and Rift, and Rift modes place those
  arrays and `ProfitStats` in a region. The 1M HPZone median is only slightly
  faster than heap, but it reduces median GC by about `59 ms` and peak RSS by
  about `119 MB` with low Rift operation time.
- Next, continue moving dominant heap control/collection state only where the
  heap and Rift paths remain the same logical program and the lifetime boundary
  is explicit.

Implementation substeps:

- Treat input/parser cleanup as shared noise reduction unless the only
  allocation-placement difference is explicit, as with the current heap input
  buffer vs Rift region input buffer.
- Keep Q1 heap and Rift algorithms aligned. Any future Q1 optimization must be
  shared by both modes unless the only difference is allocation placement.
- Q2 profit and empty-taxi windows now have heap and Rift backends over the
  same algorithm.
- Q2 active profit values now live in window entries instead of a heap
  `ArrayBuffer`; Q2 median scratch arrays are region-backed in Rift modes and
  reused rather than reset per processed trip.
- Q1/Q2 ranking objects now have a heap/Rift allocation-placement split, but
  Q1 heap maps/trees and Q2 heap `TreeSet` remain control metadata. This is
  trusted HPZone benchmark code, not the final safe mixed-reference story.
- Q2 bounded cell tables now have a heap/Rift allocation-placement split over
  the same fixed-array layout. This is acceptable Phase 5 evidence because Q2's
  grid key space is bounded by the benchmark and both modes use the same
  logical structure.
- Primitive keys and packed cell/route IDs remain acceptable only as shared
  noise cleanup or as temporary boundaries where mixed-reference safety is not
  implemented.
- RunBoth input bytes now have a heap/Rift allocation-placement split over the
  same byte parser. Single-query Q1/Q2 runners still use the older file input
  path and are not the source of Phase 5 input-buffer evidence.
- Do not add more reset-per-event region scratch paths; the superseded
  snapshot-result experiment showed they can replace GC as the bottleneck.
- Only replace remaining Q2 ranking/control data structures further if the
  change keeps heap and Rift on the same logical algorithm and uses a region
  lifetime that can be reclaimed without excessive reset churn.
- Use primitive keys and packed cell/route IDs only when the change is shared
  across modes or needed to avoid unsafe region-to-GC references.
- Keep heap roots explicit for any heap object referenced from region memory.
- Rerun 100k and 1M instrumented matrices with medians after each change that
  looks promising in a single run. The superseded resettable snapshot-result
  numbers are diagnostic only; the current reusable-array backend has 100k and
  1M medians.
- Add Commix and improved SafeZone modes where meaningful.
- Scale to full-month joined/sorted data only after the bounded samples have a
  stable region-heavy implementation.

Exit criteria:

- Both queries run simultaneously and correctly.
- Results include medians for throughput, p50/p99/p99.9 latency, GC time, RSS,
  and Rift operation time.
- At least one Rift mode reduces GC time or tail latency for a reason traceable
  to region-backed application data structures.
- The writeup separates runtime-only, topology/layout, and application effects.

Do not claim Phase 5 success until these criteria are met.

## 9. Phase 6: Broom And Parallel-Collections API Evidence

Goal: compare Rift against dataflow/parallel-collection systems at the API
level, not only as raw arrays.

Current status:

- `sandbox/PIPELINE_PARCOLL_COMPARISON.md` records the amordo
  `ZoneParVector` benchmark and the Rift raw-array surrogate.
- The comparison is explicitly not apples-to-apples.

Required work:

- Build a `RiftParVector`, region-backed stage buffer, or equivalent
  collection/operator API.
- Reproduce the amordo pipeline shape using the Rift API, not raw arrays.
- Compare heap `ParVector`, amordo `ZoneParVector`, SafeZone, and Rift under the
  same logical API.
- Track annotation burden and release/reset ergonomics.

Exit criteria:

- Rift has a fair Broom/parallel-collections comparison.
- Results state whether wins come from allocator runtime, region release, or
  lower-level array loops.

## 10. Phase 7: Capture-Checked Safe API

Goal: make `Scoped` and `Streaming` regions safe by construction rather than by
convention.

Required work:

- Define user-facing safe APIs for scoped and streaming regions.
- Add positive compile/run tests for for-loops, nested regions, and
  higher-order code.
- Add negative compile tests for return escape, closure capture escape,
  cross-region leakage, use-after-reset, GC-to-region fields, and unrooted
  region-to-GC ownership.
- Create or update `REPORT_CAPTURE_CHECK.md` with exact checker behavior.
- Decide whether current Scala 3 capture checking is enough or whether the fork
  needs a small compiler extension.

Exit criteria:

- Positive cases compile and run.
- Negative cases fail for capture/safety reasons, not incidental type errors.
- Any checker limitation is documented and reflected in the design.

Comparison obligation:

- Show why the API is less restrictive than StreamFlex and Reggio but safer
  than Broom and HPZone.
- Count annotations and compare qualitatively to Stancu-style `@RegionScope`
  and Reggio capability annotations.

## 11. Phase 8: Native GC/Region Integration Hardening

Goal: resolve mixed GC-region safety and native-runtime compatibility.

Required decisions:

- Should safe regions forbid owning region-to-GC references?
- Should Rift expose explicit GC-root handles for heap values stored in regions?
- Should region metadata be scanned by the GC?
- Should typed-region metadata eventually support BIBOP-style layout or
  tag-free scanning?

Required tests:

- Region-to-GC reference retained only by region memory must fail statically or
  be kept alive by explicit root/scan machinery.
- GC-to-region references through heap fields must fail for safe regions.
- HPZone versions of these cases must be labeled unsafe/trusted.

Exit criteria:

- The mixed reference story is explicit in design, tests, and benchmarks.
- Native/non-moving assumptions remain valid.
- No copying collector is introduced into Scala Native's C interop path.

## 12. Phase 9: Lean Mechanization

Goal: mechanize the core safety story.

Required model:

- Syntax for functions, closures, allocation, region open, close, reset, and
  capture sets.
- Two-space store: GC heap plus region heaps.
- Typing judgment with capture sets.
- Operational semantics for allocation and close/reset.
- A model of GC fallback sufficient to state containment.

Required theorems:

- Progress.
- Preservation.
- Containment.
- Safe close/reset.

Exit criteria:

- Core theorems are proved without `sorry`.
- The model covers at least one higher-order closure case, because that is where
  the literature review identifies prior proof fragility.

## 13. Phase 10: Writing

Goal: turn the artifact into a defensible research narrative.

Expected structure:

- Literature gap and comparison contract.
- Baseline correction and why old SafeZone numbers were incomplete.
- In-tree runtime/compiler implementation.
- Runtime-only evidence.
- Topology/layout decomposition.
- Application evidence from region-heavy DEBS and collection operators.
- Capture-checking safety.
- Lean proof.
- Limitations and failure cases.

Do not write the conclusion before Phase 5, Phase 7, and Phase 9 have real
evidence.

## 14. Immediate Safe Next Action

After these docs, the safest technical next action is:

1. Create a clean commit or patch boundary for the current dirty worktree after
   user approval.
2. Run a read-only allocation-pressure diagnosis for DEBS.
3. Continue the fair-backend DEBS conversion: same logical query algorithm,
   heap backend with `new`, Rift backend with `region.alloc` and region
   close/reset at the same lifetime boundary.
4. Rerun 100k and 1M instrumented medians.

Do not move straight to new runtime micro-optimizations unless the DEBS
diagnosis points there.

## 15. Unsafe Assumptions To Avoid

- Rift already has a DEBS application win.
- SafeZone is solved or irrelevant.
- Layout wins prove allocator wins.
- The raw-array pipeline surrogate is a Broom or `ZoneParVector` replacement.
- Region-to-GC references are safe just because region memory is live.
- Full automatic region inference is realistic for all Scala code.
- Reggio-style capabilities can be copied without paying annotation or topology
  costs.
- The active `origin` remote is the intended `641bill` fork.
