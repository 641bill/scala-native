# Rift Project Handoff

Date: 2026-04-24

Active worktree: `/Users/siyaoliu/rift/scala-native-rift`

Active branch: `feature/rift`

Head commit at this update: the local `feature/rift` head after the Q2
array-backed ranking checkpoint (see `git log -1` after committing this
handoff update)

Status: active research fork. The Phase 5 input-boundary checkpoint, reusable
ranking backend, Q2 bounded cell-table checkpoint, Q1 primitive route-table
checkpoint, Q2 latest-empty taxi-table checkpoint, and Q2 array-backed ranking
checkpoint are committed locally once this update is committed. The fork is
ahead of `origin/feature/rift` unless pushed.

## 1. Project Objective

Rift is now a fork-first hybrid region-GC memory system for Scala Native. The target is not a standalone arena library beside Scala Native. The target is an in-tree Scala Native runtime/compiler experiment that combines:

- A fast region slab allocator and close/reset path inside the fork.
- Scala-facing region APIs, currently `RiftRegion` with `HPZone`, `Scoped`, and `Streaming` kind constants.
- Compiler-plugin support so `region.alloc(new T(...))` lowers into Rift allocation, analogous to SafeZone allocation.
- Safe APIs based on Scala 3 capture checking, planned but not yet implemented as a complete Phase 6 story.
- Region-friendly data layouts and stream operators, because layout/topology effects are first-order.
- A Lean model and proof plan, planned but not integrated in this fork yet.

The framing changed from "replace SafeZone with a standalone faster region runtime" to "build in the Scala Native fork, compare against both current SafeZone and an improved SafeZone baseline, and report runtime, topology, and layout effects separately."

Current evaluation standard:

- Compare against Immix heap, current SafeZone (`SAFEZONE_ROOTS_MODE=0`), improved SafeZone (`SAFEZONE_ROOTS_MODE=1`), and Rift where applicable.
- Use medians for headline claims.
- Mark single-run application measurements as provisional.
- Do not claim an application-level win unless the application path is actually using region memory for the operations that dominate allocation and GC pressure.
- Separate runtime-only wins from topology/layout wins and from DEBS application evidence.

## 2. Current Workspace / Repo State

Use this worktree for active Rift work:

- `/Users/siyaoliu/rift/scala-native-rift`
- branch: `feature/rift`
- current head at this handoff update: local `feature/rift` head after the Q2
  latest-empty taxi-table checkpoint and Q2 array-backed ranking checkpoint
- `origin`: `git@github.com:641bill/scala-native.git`
- `upstream`: `https://github.com/scala-native/scala-native.git`

Other directories exist but should not be used for active implementation unless explicitly requested:

| Path | Purpose / status |
|---|---|
| `/Users/siyaoliu/rift/Claude_output` | Revised design pack: `DESIGN.md`, `ROADMAP.md`, `CODEX.md`, README, proof/capture templates. Read-only framing source. |
| `/Users/siyaoliu/rift/rift-bootstrap` | Old standalone bootstrap runtime and microbench. Useful provenance for Phase 1, not active architecture. |
| `/Users/siyaoliu/rift/scala-native` | Old worktree on `codex/safezone-topology-instrument-4096`; contains prior SafeZone/topology investigation artifacts. Do not continue Rift work there. |
| `/Users/siyaoliu/rift/scala-parallel-collections-amordo` | External/amordo parallel-collections worktree used for the `ZoneParVector` comparison. |
| `/Users/siyaoliu/rift/trip_data` and `/Users/siyaoliu/rift/trip_fare` | Downloaded DEBS/NYC taxi split datasets. |
| `/tmp/debs2015-month1-100000.csv` and `/tmp/debs2015-month1-1000000.csv` | Joined/sorted bounded DEBS inputs generated during Phase 5. |

Repo-layout quirks:

- The worktree is a Git worktree; `.git` is a file pointing at the real metadata directory. `project/Settings.scala` was patched so generated hooks use the actual git metadata directory rather than assuming `.git/` is a directory.
- The active branch contains committed Rift runtime/compiler/benchmark work,
  the Phase 5 input-boundary checkpoint, the reusable ranking backend, the Q2
  bounded cell-table checkpoint, the Q1 primitive route-table checkpoint, and
  the Q2 latest-empty taxi-table checkpoint, and the Q2 array-backed ranking
  checkpoint.
- Always inspect `git status --short --untracked-files=all` and `git diff --stat`
  before continuing.

At this update, `git status --short --untracked-files=all` should be clean in
the implementation worktree. Recheck before continuing; if it is dirty, inspect
the diff rather than assuming it belongs to the previous phase.

## 3. Revised Project Framing

The authoritative revised docs are in `/Users/siyaoliu/rift/Claude_output`:

- `/Users/siyaoliu/rift/Claude_output/DESIGN.md`
- `/Users/siyaoliu/rift/Claude_output/ROADMAP.md`
- `/Users/siyaoliu/rift/Claude_output/CODEX.md`

Main design changes:

- Standalone library story was rejected. The real costs and opportunities are inside Scala Native: SafeZone root bookkeeping, runtime allocation paths, GC/root interactions, and NIR lowering.
- Rift should live in a Scala Native fork and use the fork's runtime/compiler surface.
- Improved SafeZone is part of the baseline, not an irrelevant competitor. `SAFEZONE_ROOTS_MODE=1` materially changes SafeZone performance.
- Runtime work still matters. It is not acceptable to say "regions only win after changing data structures" if the allocator/runtime path is slow.
- Layout and topology matter enough that they must be measured separately.
- Closed-source Naiad/Broom artifacts cannot be reproduced exactly. Use workload methodology, not unavailable artifacts.

Baseline story changed:

- Old SafeZone headlines such as "SafeZone is 8x slower" are incomplete.
- In-fork reruns show current SafeZone can be pathological, but improved SafeZone can be near heap or substantially better than current SafeZone on the same workload.
- Rift has runtime-only wins on linked allocation-heavy structures, but not every cleaned/surrogate benchmark is a Rift win.
- Current DEBS evidence is correctness plus instrumentation, not yet a strong application-level speedup story.

## 4. Work Completed In This Session

### 4.1 Documentation And Framing

Completed and validated by file inspection:

- Revised design pack exists in `/Users/siyaoliu/rift/Claude_output`.
- `sandbox/PHASE0_BASELINES.md` records trusted Phase 0 medians and exact commands.
- `sandbox/PHASE4_LAYOUT.md`, `sandbox/PHASE4_TOPOLOGY.md`, and `sandbox/PHASE4_EXIT.md` record Phase 4 layout/topology findings.
- `sandbox/PIPELINE_PARCOLL_COMPARISON.md` records the amordo `ZoneParVector` comparison and labels the Rift pipeline as a raw-array surrogate.
- `bench/debs2015/README.md` and `bench/debs2015/RESULTS.md` record Phase 5 scaffold, commands, real-data runs, and instrumentation results.

Files touched:

- `sandbox/PHASE0_BASELINES.md`
- `sandbox/PHASE4_LAYOUT.md`
- `sandbox/PHASE4_TOPOLOGY.md`
- `sandbox/PHASE4_EXIT.md`
- `sandbox/PIPELINE_PARCOLL_COMPARISON.md`
- `bench/debs2015/README.md`
- `bench/debs2015/RESULTS.md`
- `docs/HANDOFF.md`
- `AGENTS.md`
- `CLAUDE.md`

Validation:

- Docs were reconstructed from checked-in/untracked notes, command outputs, and generated summary TSVs.

### 4.2 SafeZone Baseline Correction

Completed and partially validated:

- Added configurable SafeZone page size via `SAFEZONE_PAGE_SIZE`.
- Added SafeZone roots mode via `SAFEZONE_ROOTS_MODE`.
- Added SafeZone trace counters via `SAFEZONE_TRACE`.
- Changed root removal/reclaim behavior to support coalesced root removal and alternative root-registration strategies.
- Fixed `GC_Roots_RemoveByRange` so fully covered root ranges are handled correctly.

Files touched:

- `nativelib/src/main/resources/scala-native/gc/immix_commix/GCRoots.c`
- `nativelib/src/main/resources/scala-native/zone/MemoryPool.c`
- `nativelib/src/main/resources/scala-native/zone/MemoryPool.h`
- `nativelib/src/main/resources/scala-native/zone/LargeMemoryPool.c`
- `nativelib/src/main/resources/scala-native/zone/Zone.c`

Why it was done:

- The previous SafeZone investigation showed root bookkeeping was dominating some close paths.
- Phase 0 requires comparing against an improved SafeZone baseline, not only current SafeZone.

Validation:

- `sandbox/PHASE0_BASELINES.md` records 5-run medians.
- Notes state the first `SAFEZONE_ROOTS_MODE=1` GCBench attempt crashed until `GC_Roots_RemoveByRange` was fixed.
- Full Scala Native test suite has not been run.

### 4.3 Rift Runtime Integration

Completed and partially validated:

- Added in-tree Rift runtime under `nativelib/src/main/resources/scala-native/rift`.
- Runtime uses 32 KB slabs, mmap-backed allocation, TLS cache up to 8 slabs, and a global Treiber stack.
- Runtime supports region open/close/reset, raw allocation, managed object allocation, slab pool resident counters, and recently added operation counters/timers.
- Runtime fixes from Phase 4:
  - Fresh mmapped slabs are marked zero-filled.
  - Fresh zero-filled slabs skip redundant per-object `memset`.
  - Huge slabs avoid synchronous `MAP_POPULATE` and `MADV_HUGEPAGE`.
  - Reused regular slabs are zeroed once when acquired from TLS/global pool.
  - `reset` zeroes the retained first slab before reuse.

Files touched:

- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`
- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.h`

Validation:

- `sbt "tests3/testOnly scala.scalanative.memory.RiftRegionTest"` is recorded as passing `5/5` in `sandbox/PHASE4_EXIT.md`.
- `zsh bench/debs2015/run_both_instrumented_matrix.sh` rebuilt and linked the native DEBS runner successfully after adding counters.
- Full C warnings build of this in-tree runtime has not been separately recorded.

Important caveat:

- The newest stats functions are defined in `RiftRuntime.c` and exposed through Scala externs, but they are not declared in `RiftRuntime.h` yet. The Scala Native build links successfully, but the C header is incomplete for external C users.

### 4.4 Scala API Surface

Completed and partially validated:

- Added `RiftRegion` Scala facade.
- Added `RiftAllocator` extern bindings and compiler intrinsic marker.
- Added Scala 3 extension method `region.alloc(new T(...))`.
- Added Scala 2 no-op version-specific companion.
- Extended `SafeZone` trait to expose `isOpen`, `isClosed`, `checkOpen`, `close`, `handle`, and private `allocImpl`, so Rift can share the allocation-zone lowering shape.

Files touched:

- `nativelib/src/main/scala/scala/scalanative/memory/RiftRegion.scala`
- `nativelib/src/main/scala/scala/scalanative/runtime/RiftAllocator.scala`
- `nativelib/src/main/scala-3/scala/scalanative/memory/RiftRegionCompanionScalaVersionSpecific.scala`
- `nativelib/src/main/scala-2/scala/scalanative/memory/RiftRegionCompanionScalaVersionSpecific.scala`
- `nativelib/src/main/scala/scala/scalanative/memory/SafeZone.scala`

Validation:

- `RiftRegionTest` passes according to Phase 4 notes.
- GCBench/ListOfLists/Pipeline/DEBS harnesses exercised `region.alloc`.
- Safe `Scoped`/`Streaming` capture-checked APIs are not implemented beyond runtime kind constants.

### 4.5 Compiler / Plugin Changes

Completed and partially validated:

- Added a `RIFT_ALLOC` primitive.
- Added `RuntimeRiftAllocator_allocate` definitions.
- Generalized `SafeZoneInstance` attachment to `AllocationZoneInstance`.
- Taught `NirGenExpr` to attach the Rift region handle to object/array allocation trees.

Files touched:

- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirDefinitions.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirGenExpr.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirPrimitives.scala`

Validation:

- The benchmark and test harnesses compile/link through Scala Native.
- No dedicated compiler-plugin regression suite has been run.

### 4.6 Build-System Fix

Completed and validated for this worktree:

- `project/Settings.scala` now resolves the real git metadata directory when `.git` is a worktree pointer file.

Why it was done:

- The active worktree uses a `.git` file, and hook-generation code assumed `.git/hooks` existed.

Validation:

- sbt builds now proceed in this worktree.

### 4.7 Benchmark Harness Additions

Completed and validated by recorded benchmark runs:

- Added generic benchmark runner and configs.
- Added GCBench runtime matrix and topology matrix.
- Added ListOfLists runtime, flat, chunked, topology, and report-subset matrices.
- Added cleaned pipeline raw-array runtime matrix.
- Added scripts to run matrices with Immix/current SafeZone/improved SafeZone/Rift modes.
- Added DEBS Q1/Q2 implementation and sample/real-data runners.

Key files:

- `sandbox/src/main/scala-next/BenchmarkRunner.scala`
- `sandbox/src/main/scala-next/GCBenchRuntimeMatrix.scala`
- `sandbox/src/main/scala-next/GCBenchTopologyMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsRuntimeMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsFlatMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsChunkedMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsTopologyMatrix.scala`
- `sandbox/src/main/scala-next/PipelineRuntimeMatrix.scala`
- `sandbox/run_gcbench_runtime_matrix.sh`
- `sandbox/run_gcbench_topology_matrix.sh`
- `sandbox/run_listoflists_runtime_matrix.sh`
- `sandbox/run_listoflists_flat_matrix.sh`
- `sandbox/run_listoflists_chunked_matrix.sh`
- `sandbox/run_listoflists_topology_matrix.sh`
- `sandbox/run_listoflists_topology_report_subset.sh`
- `sandbox/run_pipeline_runtime_matrix.sh`

Validation:

- Results are recorded in `sandbox/PHASE0_BASELINES.md`, `sandbox/PHASE4_LAYOUT.md`, `sandbox/PHASE4_TOPOLOGY.md`, `sandbox/PHASE4_EXIT.md`, and `sandbox/PIPELINE_PARCOLL_COMPARISON.md`.

### 4.8 DEBS 2015 Work

Completed and partially validated:

- Implemented DEBS parser/grid/Q1/Q2 scaffold.
- Added heap and Rift-bucket Q1 modes.
- Added heap and Rift-window Q2 modes over the same bucketed algorithm.
- Added RunBoth runner for simultaneous Q1 and Q2.
- Added sample matrices and real-data join script.
- Added direct-native instrumented matrix with RSS, GC stats, and Rift op counters.

Files:

- `bench/debs2015/*`
- `sandbox/src/main/scala-next/debs2015/*`

Important bug fixes:

- Q1 originally stored heap `Route` objects in Rift region entries. That is unsafe because Rift regions are not GC-scanned; a heap object reachable only from region memory can be collected. Q1 now stores packed `Long` route keys in region entries.
- Q1 and Q2 originally did full rescans/sorts per event. 100k heap run took `246360.864 ms` (`405.909 events/s`). Incremental ranking with `TreeSet` reduced 100k runs to about `2.0-2.1 s`.
- `join_nyc_taxi_sample.sh` was adjusted to handle bounded-read pipeline behavior and sort by dropoff timestamp.

Validation:

- 10k, 100k, and 1M RunBoth matrices matched heap/Rift outputs after stripping only final measured-latency column.
- 100k and 1M instrumented matrices also matched.

Current limitation:

- Q1/Q2 window entries, Q2 active profit values, Q2 median scratch arrays, the
  RunBoth input byte buffer, Q1/Q2 ranking objects, and top-k result arrays now
  have heap/Rift allocation-placement boundaries in the current worktree.
- The resettable ranking/result experiment was not a performance win and was
  replaced by reusable top-k arrays. Do not reintroduce per-event resettable
  snapshot regions.
- Q2 per-cell maps, Q1 route maps, Q2 latest-empty taxi state, and Q2 ranking
  index arrays now have shared heap/Rift structures with region-backed arrays
  in Rift modes.
- Q1 ranking still uses a heap `TreeSet`; durable taxi-id metadata, latency
  arrays, SafeZone/Commix modes, full-month scale, and safe API boundaries
  remain open.
- Therefore Rift DEBS still depends on GC and still does not provide final
  application-level evidence, though the latest bounded-sample medians now show
  Rift faster than heap with lower GC/RSS.

## 5. Benchmark And Validation Summary

Common environment for fork-local runs:

- `ENABLE_EXPERIMENTAL_COMPILER=1`
- `JAVA_HOME="$(cs java-home --jvm temurin:17)"`
- `PATH="$JAVA_HOME/bin:$PATH"`
- Scala Native project: `sandbox3_next`
- Head commit for recorded result packs: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

### Phase 1 Bootstrap Microbench

Source: `/Users/siyaoliu/rift/rift-bootstrap/bench/microbench/results.md`

Command:

```sh
cd /Users/siyaoliu/rift/rift-bootstrap
make -C native clean bench
./bench/microbench/microbench
```

| Metric | malloc/free | Rift HPZone |
|---|---:|---:|
| per-allocation p50 | 11.3 ns/alloc | 2.1 ns/alloc |
| per-allocation p99 | 13.9 ns/alloc | 2.9 ns/alloc |
| close/free p50 for 100000 allocations | 1210000 ns | 3000 ns |
| close/free p99 for 100000 allocations | 1507000 ns | 11000 ns |

Status: completed and validated in the standalone bootstrap tree, not the active fork.

### GCBench Runtime Matrix

Source: `sandbox/PHASE0_BASELINES.md`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
GCBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_gcbench_runtime_matrix.sh
```

Config:

- stretch depth `18`
- long-lived depth `16`
- array size `500000`
- min depth `4`
- max depth `16`
- depth step `2`
- batch size `1`
- runs `5`

| Mode | Key env | Median ms | Status |
|---|---|---:|---|
| Immix heap | `-` | 203.514 | trusted Phase 0 median |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0` | 684.517 | trusted Phase 0 median |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1` | 223.770 | trusted Phase 0 median |
| Rift HPZone | `-` | 161.641 | trusted Phase 0 median |

Interpretation:

- Runtime-only Rift is faster than heap and both SafeZone baselines on this GCBench harness.
- Improved SafeZone closes most of the current SafeZone gap.

### ListOfLists Runtime Matrix

Source: `sandbox/PHASE0_BASELINES.md`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_listoflists_runtime_matrix.sh
```

Workload:

- `n=3000`
- `structures=40`
- linked outer and inner nodes
- each inner node points at a separate `BasicObject`
- runs `5`

| Mode | Key env | Median ms | Status |
|---|---|---:|---|
| Immix heap | `-` | 15085.511 | trusted Phase 0 median |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0` | 138171.998 | trusted Phase 0 median |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1` | 10132.854 | trusted Phase 0 median |
| Rift HPZone | `-` | 6951.331 | trusted Phase 0 median |

Interpretation:

- The linked runtime-only story is strong: Rift beats heap and improved SafeZone.
- Current SafeZone remains pathological on this shape.

### GCBench Topology Rerun

Source: `sandbox/PHASE0_BASELINES.md`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_gcbench_topology_matrix.sh
```

Config:

- `SAFEZONE_PAGE_SIZE=4096`
- `SAFEZONE_BATCH_SIZE=1`
- runs `5`

Definitions:

- topology A: long-lived tree on GC heap, short-lived trees in inner SafeZones.
- topology B: long-lived tree in outer SafeZone, short-lived trees in inner SafeZones.

| Mode | Key env | Median ms |
|---|---|---:|
| heap baseline | `SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 213.082 |
| current SafeZone topology A | `SAFEZONE_ROOTS_MODE=0 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 236.542 |
| current SafeZone topology B | `SAFEZONE_ROOTS_MODE=0 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 635.247 |
| improved SafeZone topology A | `SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 211.319 |
| improved SafeZone topology B | `SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 436.749 |

Interpretation:

- Topology changes conclusions. Topology A can be near heap parity even for current SafeZone, while topology B remains much slower.
- Improved SafeZone topology A is near heap.

### Phase 4 Layout Study

Source: `sandbox/PHASE4_LAYOUT.md`

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_CHUNK_SIZE=32 LISTBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_listoflists_chunked_matrix.sh
LISTBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_listoflists_flat_matrix.sh
```

| Mode | Linked median ms | Chunked median ms | Flat median ms |
|---|---:|---:|---:|
| Immix heap | 15260.875 | 2636.480 | 1766.295 |
| current SafeZone | 136016.922 | 4835.904 | 1735.453 |
| improved SafeZone | 9824.936 | 2369.108 | 1743.161 |
| Rift HPZone | 7278.150 | 2463.674 | 1515.091 |

Follow-up Rift-only checks after regular-slab zeroing:

| Layout | Previous Rift median ms | Follow-up Rift median ms |
|---|---:|---:|
| Linked | 7278.150 | 6638.949 |
| Chunked | 2463.674 | 2390.390 |

Interpretation:

- Layout is a first-order effect.
- Rift remains strong on linked one-region layout.
- Rift flat cliff was a runtime bug and is fixed.
- Rift does not yet clearly beat improved SafeZone on the first chunked layout.

### Phase 4 Topology Sweep

Source: `sandbox/PHASE4_TOPOLOGY.md`

Report-scale command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_BENCHMARK_RUNS=3 zsh sandbox/run_listoflists_topology_report_subset.sh
```

| Mode | Topology | Median ms |
|---|---|---:|
| Immix heap | full heap graph | 14755.580 |
| current SafeZone | one-region | 136016.922 |
| current SafeZone | nested | 10289.097 |
| current SafeZone | mixed rooted heap-values | 55554.702 |
| improved SafeZone | one-region | 9767.048 |
| improved SafeZone | nested | 9929.699 |
| improved SafeZone | mixed rooted heap-values | 9877.914 |
| Rift HPZone | one-region | 7256.426 |
| Rift HPZone | nested | 7314.201 |
| Rift HPZone | mixed rooted heap-values | 11695.748 |

Interpretation:

- Rift one-region/nested are fastest in this linked topology harness.
- Current SafeZone nested mostly fixes the old root-bookkeeping pathology.
- Improved SafeZone makes topology much less important for that runtime.
- Rooted mixed topology is not a Rift win.

### Pipeline Benchmark Status

Source: `sandbox/PHASE0_BASELINES.md` and `sandbox/PIPELINE_PARCOLL_COMPARISON.md`

Pipeline surrogate command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_pipeline_runtime_matrix.sh
```

Config in Phase 0:

- `PIPELINE_SIZE=2000000`
- `PIPELINE_WORKERS=4`
- `PIPELINE_WARMUPS=1`
- runs `5`

| Mode | Median ms |
|---|---:|
| heap baseline | 4.924 |
| current SafeZone pipeline | 5.344 |
| improved SafeZone pipeline | 5.055 |
| Rift HPZone pipeline | 5.089 |

Provenance caveat:

- No tracked original pipeline benchmark survived in the fork history.
- This is a cleaned raw-array surrogate derived from a surviving local scaffold.
- It fixes a checksum-capture artifact by using per-worker primitive sums.
- It is not a parallel-collections API comparison.

Amordo parallel-collections comparison:

```sh
cd /Users/siyaoliu/rift/scala-parallel-collections-amordo
JAVA_HOME="$(cs java-home --jvm temurin:17)" PATH="$JAVA_HOME/bin:$PATH" \
  sbt "junitNative/testOnly scala.collection.parallel.immutable.BroomPipelineBenchmark"
```

| Mode | Median ms |
|---|---:|
| ZoneParVector explicit stage release | 55.07 |
| on-heap ParVector | 30.75 |
| ZoneParVector region per stage | 30.36 |

Rift raw-array 100k comparison:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
PIPELINE_SIZE=100000 PIPELINE_KEYS=64 PIPELINE_BENCHMARK_RUNS=5 PIPELINE_WARMUPS=1 \
  zsh sandbox/run_pipeline_runtime_matrix.sh
```

| Mode | Median ms |
|---|---:|
| Immix heap raw arrays | 1.845 |
| current SafeZone raw arrays | 1.976 |
| improved SafeZone raw arrays | 1.907 |
| Rift HPZone raw arrays | 2.502 |
| Rift Streaming raw arrays | 2.652 |

Interpretation:

- Raw-array Rift is much faster than `ZoneParVector` because it is a lower-level kernel, not because it replaced the collection API.
- Within the raw-array surrogate, Rift is not a win at 100k.
- Fair next comparison requires a Rift-backed collection with the same API shape as `ParVector`/`ZoneParVector`.

### DEBS 2015 Status

Source: `bench/debs2015/RESULTS.md`

Input preparation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_LIMIT=1000000 DEBS2015_JOINED_OUTPUT=/tmp/debs2015-month1-1000000.csv \
  zsh bench/debs2015/join_nyc_taxi_sample.sh
```

RunBoth matrix:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-month1-1000000 \
  zsh bench/debs2015/run_both_sample_matrix.sh
```

1M non-instrumented single-run rows:

| Q1 mode | Events | Elapsed ms | Throughput events/s | Q1 p99.9 ms | Q1 max ms | Q2 p99.9 ms | Q2 max ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 1000000 | 21658.215 | 46171.857 | 0 | 92 | 2 | 69 |
| Rift HPZone | 1000000 | 22314.989 | 44812.928 | 0 | 1 | 2 | 37 |
| Rift Streaming | 1000000 | 22184.467 | 45076.585 | 0 | 45 | 2 | 94 |

Instrumented matrix:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_BUILD=0 \
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-instrumented-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

1M instrumented single-run rows from current binary:

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22827.882 | 43806.080 | 21 | 1051.752 | 0.000 | 0 | 0 / 0 | 0 | 1148780544 |
| Rift HPZone | 22366.285 | 44710.151 | 21 | 1003.171 | 9.350 | 979699 | 10730 / 10730 | 3342336 | 1152122880 |
| Rift Streaming | 22810.687 | 43839.101 | 21 | 1012.489 | 10.390 | 979699 | 10730 / 10730 | 3342336 | 1152122880 |

100k instrumented rows:

| Q1 mode | Elapsed ms | Throughput events/s | GC collections | GC time ms | Rift op ms | Rift alloc objects | Rift opens/closes | Rift mmap bytes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2018.887 | 49532.241 | 8 | 86.696 | 0.000 | 0 | 0 / 0 | 0 | 348684288 |
| Rift HPZone | 2021.861 | 49459.394 | 8 | 83.432 | 1.036 | 98005 | 1513 / 1513 | 1015808 | 343670784 |
| Rift Streaming | 2018.424 | 49543.610 | 8 | 83.035 | 1.004 | 98005 | 1513 / 1513 | 1015808 | 343638016 |

Correctness:

- 10k, 100k, and 1M heap/Rift outputs match after stripping only the final latency column.

Caveats:

- The region-backed input-buffer checkpoint now has three-run medians:
  - 1M heap elapsed `17047.611 ms`, GC `684.338 ms`.
  - 1M Rift HPZone elapsed `17119.396 ms`, GC `640.062 ms`, Rift op `288.815 ms`.
  - 1M Rift Streaming elapsed `17210.458 ms`, GC `628.808 ms`, Rift op `288.920 ms`.
- The first ranking/result-output experiment is single-run diagnostic evidence:
  - 1M heap elapsed `16363.012 ms`, GC `601.615 ms`, RSS `1148436480`.
  - 1M Rift HPZone elapsed `17243.387 ms`, GC `503.198 ms`, Rift op `933.640 ms`, RSS `1088684032`.
  - 1M Rift Streaming elapsed `17301.238 ms`, GC `529.115 ms`, Rift op `936.641 ms`, RSS `1088618496`.
- Interpretation: region ranking/result objects reduce GC and can reduce RSS,
  but the current resettable snapshot-region design makes region operations
  visible enough to lose elapsed time. This is not Phase 5 success.
- The reusable ranking backend fixed the reset shape:
  - 1M heap median elapsed `14633.019 ms`, GC `513.199 ms`, RSS `813105152`.
  - 1M Rift HPZone median elapsed `14234.470 ms`, GC `457.726 ms`, Rift op `13.003 ms`, RSS `876101632`.
  - 1M Rift Streaming median elapsed `14392.097 ms`, GC `478.912 ms`, Rift op `14.166 ms`, RSS `876101632`.
- The Q2 bounded cell-table checkpoint replaced boxed per-cell maps with fixed
  arrays over the bounded Q2 grid key space. Heap and Rift use the same logical
  layout; Rift allocates the arrays and `ProfitStats` in a run-lifetime region:
  - 1M heap median elapsed `11471.085 ms`, GC `478.636 ms`, RSS `609730560`.
  - 1M Rift HPZone median elapsed `11442.637 ms`, GC `419.658 ms`, Rift op `11.759 ms`, RSS `490766336`.
  - 1M Rift Streaming median elapsed `11477.431 ms`, GC `428.339 ms`, Rift op `11.421 ms`, RSS `490799104`.
  - Outputs matched on sample, 100k, and 1M matrices after stripping only the
    measured latency column.
- The Q1 primitive route-table checkpoint replaced boxed Q1 route-count and
  route-rank maps with a shared primitive open-addressed table. Heap allocates
  the backing arrays with `new`; Rift modes allocate them in a run-lifetime
  ranking/control region:
  - 1M heap median elapsed `11957.721 ms`, GC `463.555 ms`, RSS `433209344`.
  - 1M Rift HPZone median elapsed `11659.223 ms`, GC `397.162 ms`, Rift op `15.102 ms`, RSS `381222912`.
  - 1M Rift Streaming median elapsed `11739.195 ms`, GC `398.268 ms`, Rift op `15.164 ms`, RSS `381190144`.
  - Q1 process improves versus the Q2-cell-table checkpoint, but total elapsed
    remains Q2-dominated and is not a clean cross-checkpoint speedup.
- The Q2 latest-empty taxi-table checkpoint replaced `latestEmptyByTaxi` with a
  shared growable dense array over existing taxi IDs. Heap allocates the array
  with `new`; Rift modes allocate it in the run-lifetime ranking/control region:
  - 1M heap median elapsed `11649.547 ms`, GC `487.774 ms`, RSS `609304576`.
  - 1M Rift HPZone median elapsed `11263.680 ms`, GC `381.099 ms`, Rift op `14.536 ms`, RSS `381501440`.
  - 1M Rift Streaming median elapsed `11188.144 ms`, GC `382.734 ms`, Rift op `14.991 ms`, RSS `381501440`.
- The Q2 array-backed ranking checkpoint replaced the heap `TreeSet` ranking
  index with a shared indexed binary heap over ordinary `ProfitableArea`
  objects. Heap allocates the index arrays with `new`; Rift modes allocate them
  in the run-lifetime ranking/control region:
  - 1M heap median elapsed `11202.975 ms`, GC `423.932 ms`, RSS `431718400`.
  - 1M Rift HPZone median elapsed `10808.901 ms`, GC `348.901 ms`, Rift op `14.861 ms`, RSS `383631360`.
  - 1M Rift Streaming median elapsed `10804.756 ms`, GC `344.376 ms`, Rift op `14.684 ms`, RSS `383631360`.
  - This is the strongest bounded-sample DEBS result so far, but it still lacks
    SafeZone/Commix/full-month controls and safe API enforcement.

### Smoke Tests / Unit Tests / Compile Checks

Recorded as run:

- `sbt "tests3/testOnly scala.scalanative.memory.RiftRegionTest"` passes `5/5` in Phase 4 notes.
- `zsh bench/debs2015/run_both_instrumented_matrix.sh` rebuilt and linked the native DEBS runner after the latest counter additions.
- 100k and 1M instrumented DEBS matrices run to completion and outputs match.
- After the byte-reader change, 100k and 1M RunBoth matrices parsed all rows
  and matched outputs across heap, Rift HPZone, and Rift Streaming.
- After the reusable ranking backend and Q2 cell-table checkpoint,
  `Debs2015Q2Smoke` passed, `Debs2015RunBoth` native-linked, and sample/100k/1M
  instrumented matrices matched outputs across heap, Rift HPZone, and Rift
  Streaming.
- After the Q1 primitive route-table checkpoint, `Debs2015Q1Smoke` passed,
  `Debs2015RunBoth` native-linked, and 100k/1M instrumented matrices matched
  outputs across heap, Rift HPZone, and Rift Streaming.
- After the Q2 latest-empty taxi-table checkpoint, `Debs2015Q2Smoke` passed,
  `Debs2015RunBoth` native-linked, and 100k/1M instrumented matrices matched
  outputs across heap, Rift HPZone, and Rift Streaming.
- After the Q2 array-backed ranking checkpoint, `Debs2015Q2Smoke` passed,
  `Debs2015RunBoth` native-linked, and sample/100k/1M instrumented matrices
  matched outputs across heap, Rift HPZone, and Rift Streaming.

Not yet run or not recorded:

- Full Scala Native test suite.
- Dedicated compiler-plugin regression tests.
- Commix DEBS matrix.
- Improved SafeZone DEBS matrix where meaningful.

## 6. Key Findings So Far

SafeZone and baselines:

- Current SafeZone can still be very slow on linked one-region shapes.
- Improved SafeZone (`SAFEZONE_ROOTS_MODE=1`) substantially changes the baseline. On ListOfLists runtime matrix it reduces median wall time from `138171.998 ms` to `10132.854 ms`.
- SafeZone can be near heap on some flat/contiguous shapes, but that is workload-specific, not a universal conclusion.

Rift runtime:

- Rift has a real runtime-only win on linked allocation-heavy workloads:
  - GCBench: `161.641 ms` vs heap `203.514 ms` and improved SafeZone `223.770 ms`.
  - ListOfLists linked: `6951.331 ms` vs heap `15085.511 ms` and improved SafeZone `10132.854 ms`.
- The flat-layout Rift cliff was a runtime bug in the huge-allocation path; after fixing it, flat Rift is best in the recorded layout table.
- Regular slab zeroing mattered for linked and chunked follow-ups.

Topology and safety:

- Topology can change results by an order of magnitude.
- Unrooted Rift mixed topology produced a checksum mismatch because region memory is not GC-scanned. Region-to-GC references are allowed only if heap referents are otherwise rooted or visible to the GC.
- The Q1 DEBS crash/failure mode reinforced the same safety rule: storing heap objects in Rift region entries is unsafe if the region is the only owner.

Pipeline:

- The cleaned raw-array pipeline does not currently show a Rift win.
- The amordo `ZoneParVector` comparison is not apples-to-apples; Rift raw arrays are lower-level than parallel collection APIs.

DEBS:

- Q1/Q2 correctness is established for bounded sorted real-data samples up to 1M rows.
- The first 100k heap attempt was dominated by rescans/sorts (`246360.864 ms`), then improved to about `2 s` after incremental ranking.
- Current DEBS Rift modes region-allocate Q1 bucket entries and Q2 profit/empty-taxi window entries using the same bucketed algorithms as heap.
- A 100k phase breakdown before the byte reader showed Q2 processing at roughly
  `48-50%` of elapsed time and read+parse around `22%`. After the byte reader,
  read+parse is roughly `9-10%`, while Q2 processing is still the dominant
  phase at roughly `57-64%` in the latest 100k/1M single runs.
- Batching Q2 median scratch reset per processed trip reduced Rift resets from
  `171197` to `87438` and Rift region-op time from about `51.5 ms` to about
  `28 ms` on the 100k sample.
- The 1M byte-reader median rerun corrected the earlier single-run optimism:
  heap `17047.611 ms`, Rift HPZone `17119.396 ms`, Rift Streaming
  `17210.458 ms`. Rift reduced GC time by about `44-56 ms`, but Rift operation
  time was about `289 ms`.
- The first ranking/result-region experiment moved ordinary Scala
  `RankedRoute`/`Route`/`Cell` and `ProfitableArea` objects into regions and
  allocated top-k arrays in resettable snapshot regions. On a 1M single run it
  reduced HPZone GC time from heap `601.615 ms` to `503.198 ms` and RSS from
  `1148436480` to `1088684032`, but elapsed time worsened because Rift op time
  reached `933.640 ms`.
- The reusable ranking-array backend fixed that lifetime shape: on the 1M
  3-run median HPZone elapsed `14234.470 ms` vs heap `14633.019 ms`, with
  HPZone Rift op `13.003 ms`.
- The Q2 cell-table checkpoint moved bounded Q2 per-cell control tables out of
  boxed `HashMap`s and into fixed arrays. In Rift modes those arrays and
  `ProfitStats` are region-allocated. On the 1M 3-run median HPZone elapsed
  `11442.637 ms` vs heap `11471.085 ms`, GC time fell from `478.636 ms` to
  `419.658 ms`, and peak RSS fell from `609730560` to `490766336`.
- The Q1 route-table checkpoint moved Q1 route-count and route-rank maps into a
  shared primitive table. In Rift modes the table arrays are region-allocated.
  On the 1M 3-run median HPZone elapsed `11659.223 ms` vs heap `11957.721 ms`,
  GC time fell from `463.555 ms` to `397.162 ms`, and peak RSS fell from
  `433209344` to `381222912`. This is stronger evidence for placement, but not
  a total-elapsed breakthrough because Q2 processing remains dominant.
- The Q2 latest-empty taxi-table checkpoint moved latest empty-taxi state from a
  heap `HashMap` into a shared dense array. In Rift modes that array is
  region-allocated. On the 1M 3-run median HPZone elapsed `11263.680 ms` vs
  heap `11649.547 ms`, GC time fell from `487.774 ms` to `381.099 ms`, and peak
  RSS fell from `609304576` to `381501440`.
- The Q2 array-backed ranking checkpoint moved Q2 ranking index state from a
  heap `TreeSet` into a shared indexed heap. In Rift modes that index is
  region-allocated and the ranked values remain ordinary region-allocated
  `ProfitableArea` Scala objects. On the 1M 3-run median HPZone elapsed
  `10808.901 ms` vs heap `11202.975 ms`, GC time fell from `423.932 ms` to
  `348.901 ms`, and peak RSS fell from `431718400` to `383631360`.
- Remaining app control state is still substantial, especially Q1 `TreeSet`,
  durable taxi-id metadata, latency arrays, and any broader collection API
  work. The current Rift elapsed win is still bounded-sample evidence, so this
  is stronger evidence but still not final Phase 5 success.

Why Rift DEBS still uses so much GC:

- The RunBoth path no longer uses `Source.getLines()` and no longer allocates a
  heap line `String` per row. Single-query Q1/Q2 runners still use the older
  file input path.
- Q2 window entries, active profit values, median scratch arrays, ranking
  `ProfitableArea` objects, top-k result arrays, and bounded per-cell tables
  are now region-backed in Rift modes. Q2 latest-empty taxi state is a
  region-backed dense array in Rift modes. Q2 ranking-index arrays are also
  region-backed in Rift modes. Durable taxi-id metadata and latency arrays
  remain heap-based.
- Q1 window entries, route-table arrays, ranking `RankedRoute`/`Route`/`Cell`
  objects, and top-k result arrays are region-backed in Rift modes. Q1 still
  uses a heap `TreeSet` for ranking order.
- Output formatting now writes directly to `Writer` through shared code; this
  removed much of the Q2 output allocation for heap and Rift alike.
- Rift currently removes Q1/Q2 window-entry allocation, Q2 active profit-value
  storage, Q2 median scratch arrays, RunBoth input bytes, ranking objects,
  top-k result arrays, Q2 bounded cell tables, Q1 route-table arrays, and Q2
  latest-empty taxi arrays from the GC heap. It also removes Q2 ranking-index
  tree nodes by replacing the `TreeSet` with a shared indexed heap whose arrays
  are region-backed in Rift modes. The remaining heap pressure is Q1 tree
  metadata, durable taxi-id metadata, latency arrays, and broader collections.
  Fine-grained
  result-snapshot reset overhead was fixed by the reusable ranking backend and
  should not be reintroduced.
- Q2 primitive cell keys remove accidental `Cell`/cell-id string allocation from the shared hot path, but this is a boundary/noise cleanup, not a Rift-specific win.

## 7. Roadmap Status

Roadmap source: `/Users/siyaoliu/rift/Claude_output/ROADMAP.md`

| Phase | Status | Evidence | Remaining work |
|---|---|---|---|
| Phase 0 baseline correction | Mostly complete, but still caveated | `sandbox/PHASE0_BASELINES.md` has 5-run medians and commands for GCBench, ListOfLists, GCBench topology, and pipeline surrogate. | Confirm whether all Phase 0 scripts/results should be committed as the trusted baseline pack. Be careful: pipeline is a surrogate. |
| Phase 1 bootstrap allocator | Done in old bootstrap tree | `/Users/siyaoliu/rift/rift-bootstrap/bench/microbench/results.md`; clean `-Wall -Werror` microbench notes. | Not active architecture; no need to redo unless validating standalone package. |
| Phase 2 in-tree runtime | Partially done | In-tree `RiftRuntime.c/h`, Scala facade, compiler lowering, `RiftRegionTest`, benchmark use. | Make API/header complete, run broader tests, decide stats ABI, clean up untracked state. |
| Phase 3 runtime-only benchmarks | Done enough for current story | GCBench and ListOfLists runtime medians recorded; pipeline surrogate recorded. | Commix is not included. Pipeline provenance remains surrogate. |
| Phase 4 topology/layout | Done enough to move on | `PHASE4_LAYOUT.md`, `PHASE4_TOPOLOGY.md`, `PHASE4_EXIT.md`. | Chunked layout still not a Rift win vs improved SafeZone. Mixed GC/region safety story needs Phase 6 tests. |
| Phase 5 streaming operators and DEBS | In progress | DEBS Q1/Q2 run simultaneously on real data; outputs match; instrumentation added; Q1 and Q2 window entries have shared heap/Rift backends; Q2 active profit values live in window entries; Q2 median scratch arrays are region-backed and reused; Q2 ranking uses primitive cell keys internally; RunBoth input bytes use a heap/Rift allocation-placement split; the reusable ranking backend region-allocates Q1/Q2 ranking objects and top-k result arrays; Q2 bounded per-cell tables, Q1 route-table arrays, Q2 latest-empty taxi arrays, and Q2 ranking-index arrays are region-backed in Rift modes. | Current 1M HPZone and Streaming medians are faster than heap with lower GC/RSS, but Q2 processing still dominates and the bounded-sample result is not final application evidence. Need Commix, SafeZone comparison, full-month input, remaining control/collection work, and safe API boundaries. |
| Phase 6 capture checking | Open | Only design templates and early Rift API surface exist. | Implement positive/negative capture tests and fill `REPORT_CAPTURE_CHECK.md`. |
| Phase 7 Lean mechanization | Open | Design pack has Lean stubs/templates. | Port or start proof work; prove without `sorry`. |
| Phase 8 writing | Not started beyond notes | Result packs and this handoff exist. | Thesis/paper narrative after evidence stabilizes. |

Is Phase 0 actually complete?

- For GCBench and ListOfLists, yes enough to proceed.
- For pipeline, only as a provisional surrogate, not as a final reproduction of the original claimed pipeline result.
- Phase 0 should be described as "mostly complete with caveats," not "finalized beyond dispute."

## 8. Files Of Highest Importance

Docs / roadmap / design:

- `/Users/siyaoliu/rift/Claude_output/DESIGN.md`
- `/Users/siyaoliu/rift/Claude_output/ROADMAP.md`
- `/Users/siyaoliu/rift/Claude_output/CODEX.md`
- `docs/HANDOFF.md`
- `AGENTS.md`
- `CLAUDE.md`

Runtime implementation:

- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`
- `nativelib/src/main/resources/scala-native/rift/RiftRuntime.h`
- `nativelib/src/main/resources/scala-native/zone/MemoryPool.c`
- `nativelib/src/main/resources/scala-native/zone/MemoryPool.h`
- `nativelib/src/main/resources/scala-native/zone/LargeMemoryPool.c`
- `nativelib/src/main/resources/scala-native/zone/Zone.c`
- `nativelib/src/main/resources/scala-native/gc/immix_commix/GCRoots.c`

Scala API surface:

- `nativelib/src/main/scala/scala/scalanative/memory/RiftRegion.scala`
- `nativelib/src/main/scala/scala/scalanative/runtime/RiftAllocator.scala`
- `nativelib/src/main/scala-3/scala/scalanative/memory/RiftRegionCompanionScalaVersionSpecific.scala`
- `nativelib/src/main/scala-2/scala/scalanative/memory/RiftRegionCompanionScalaVersionSpecific.scala`
- `nativelib/src/main/scala/scala/scalanative/memory/SafeZone.scala`

Compiler/plugin integration:

- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirDefinitions.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirGenExpr.scala`
- `nscplugin/src/main/scala-3/scala/scalanative/nscplugin/NirPrimitives.scala`

Benchmark harnesses:

- `sandbox/src/main/scala-next/GCBenchRuntimeMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsRuntimeMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsFlatMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsChunkedMatrix.scala`
- `sandbox/src/main/scala-next/ListOfListsTopologyMatrix.scala`
- `sandbox/src/main/scala-next/PipelineRuntimeMatrix.scala`
- `sandbox/src/main/scala-next/debs2015/*`
- `bench/debs2015/*`

Notes / result packs:

- `sandbox/PHASE0_BASELINES.md`
- `sandbox/PHASE4_LAYOUT.md`
- `sandbox/PHASE4_TOPOLOGY.md`
- `sandbox/PHASE4_EXIT.md`
- `sandbox/PIPELINE_PARCOLL_COMPARISON.md`
- `bench/debs2015/RESULTS.md`

Scripts:

- `sandbox/run_gcbench_runtime_matrix.sh`
- `sandbox/run_gcbench_topology_matrix.sh`
- `sandbox/run_listoflists_runtime_matrix.sh`
- `sandbox/run_listoflists_flat_matrix.sh`
- `sandbox/run_listoflists_chunked_matrix.sh`
- `sandbox/run_listoflists_topology_matrix.sh`
- `sandbox/run_listoflists_topology_report_subset.sh`
- `sandbox/run_pipeline_runtime_matrix.sh`
- `bench/debs2015/join_nyc_taxi_sample.sh`
- `bench/debs2015/run_both_sample_matrix.sh`
- `bench/debs2015/run_both_instrumented_matrix.sh`

## 9. Open Questions / Uncertainties / Risks

Technical uncertainties:

- Can the safe `ScopedRegion`/`StreamingRegion` APIs be expressed cleanly with current Scala 3 capture checking?
- How should region-to-GC references be restricted or made visible to the GC in safe APIs?
- Should Rift stats functions become public C API in `RiftRuntime.h`, remain private, or be gated behind a compile flag?
- The current `mach_timebase_info` timing path in `RiftRuntime.c` calls `mach_timebase_info` for every timed operation on macOS. This is acceptable for instrumentation but probably not final low-overhead runtime design.
- Q2 currently recomputes medians by sorting arrays when dirty. That still creates heap pressure and may not be a realistic final streaming operator design.

Benchmarking uncertainties:

- Current DEBS checkpoint rows have 100k and 1M three-run medians, but older
  DEBS rows in the same result history are still single-run diagnostics.
- DEBS currently uses bounded sorted January samples, not full-month or full-challenge scale.
- Commix is missing from most comparison tables.
- Improved SafeZone comparison is not yet present for DEBS because DEBS currently compares heap vs Q1 Rift modes, not SafeZone modes.
- Pipeline is a surrogate. Do not present it as a reproduction of the old Broom/Naiad-style result.
- Older result packs were generated from then-uncommitted code. The current
  input-boundary, ranking/result, Q2 cell-table, Q1 route-table, Q2 taxi-table,
  and Q2 array-ranking experiments now have local commit boundaries once this
  update is committed, but public claims still need pushed provenance and the
  missing SafeZone/Commix/full-scale controls.

Provenance risks:

- The active branch has local commit boundaries through the Q2 array-backed
  ranking checkpoint once this update is committed, but this branch is ahead of
  `origin/feature/rift` until pushed.
- Several old worktrees are dirty and contain useful but obsolete artifacts;
  avoid mixing their outputs into active results without labeling provenance.

Docs needing possible revision:

- `/Users/siyaoliu/rift/Claude_output/DESIGN.md` is now provenance, not the
  active design. The active `DESIGN.md` and `ROADMAP.md` have been revised
  through the Q2 array-backed ranking checkpoint.
- `RiftRuntime.h` does not include the newest stats API used by Scala externs.

## 10. Exact Next Recommended Steps

Immediate next step:

1. Treat the Q2 array-backed ranking checkpoint as the strongest bounded
   Phase 5 evidence so far, but still incomplete. It moves more Q2 control/data
   state into region-backed storage in Rift modes and improves elapsed/GC/RSS,
   but Q2 still dominates elapsed time and SafeZone/Commix/full-month/safety
   comparisons remain open.

Next technical milestone:

1. Continue the DEBS "region-heavy" path with measurement first. The goal should be to reduce GC pressure in the actual dominant data operations, not just window entries.
2. Start with a narrow measurement-driven plan:
   - Add allocation counters or coarse heap allocation attribution around
     remaining Q1 tree metadata, durable taxi-id metadata, latency arrays,
     and output formatting.
   - Treat the RunBoth byte parser and region-backed input buffer as
     implemented. It avoids per-row line strings and only interns durable taxi
     IDs as heap metadata.
   - Treat the shared Q2 window/profit-value/backend, region-backed median
     scratch, primitive Q2 cell keys, RunBoth input buffer, region ranking
     objects, reusable top-k arrays, Q2 bounded cell tables, Q1 primitive route
     table, Q2 latest-empty taxi table, Q2 array-backed ranking, and direct
     output writing as implemented.
   - Do not reintroduce reset-per-event result snapshots; the reusable top-k
     arrays are the lower-overhead replacement.
   - Replace remaining heap `HashMap`/`TreeSet` control metadata only where the
     change preserves the same logical query for heap and Rift, or where the
     only difference is allocation placement.
   - Do not repeat the uncommitted Q1 indexed-heap ranking design unchanged; it
     preserved correctness but regressed Q1 processing.
3. Rerun 100k and 1M instrumented matrices with medians after each change.

What should not be done yet:

- Do not claim Rift has strong DEBS application-level evidence.
- Do not move to Phase 6/7 as if Phase 5 is complete.
- Do not optimize random runtime code before confirming whether the cost is
  allocator/runtime overhead or an avoidable API/lifetime shape.
- Do not compare Rift raw-array pipeline directly against `ZoneParVector` as if the APIs are equivalent.
- Do not treat local Phase 5 commits as shared provenance until they are pushed.

What needs remeasurement:

- DEBS instrumented medians for heap, Rift HPZone, Rift Streaming.
- DEBS medians for the current region-backed input-buffer state.
- DEBS after any further Q1/ranking/output region-heavy changes.
- Commix comparisons where supported.
- SafeZone or improved SafeZone DEBS modes if meaningful.
- Pipeline if a real Rift-backed collection API is added.

What is stable enough:

- Improved SafeZone changes the baseline materially.
- Rift has runtime-only wins on GCBench and linked ListOfLists in the current harness.
- Layout/topology effects are large and must be reported separately.
- Region memory is not GC-scanned, so unrooted region-to-GC references can corrupt correctness.
- Current DEBS GC time persists because Q1 ranking, durable taxi-id metadata,
  latency arrays, and remaining output/result/control metadata are still
  heap-based even after the RunBoth input buffer and Q2 ranking index moved to
  regions in Rift modes.

## 11. Do-Not-Redo Notes

- Do not restart from `/Users/siyaoliu/rift/rift-bootstrap`; it is Phase 1 provenance only.
- Do not use `/Users/siyaoliu/rift/scala-native` as the active Rift branch; it is an older SafeZone/topology investigation worktree.
- Do not treat old SafeZone pathologies as the only baseline. Always include improved SafeZone if SafeZone is in scope.
- Do not rerun raw DEBS monthly files without sorting by dropoff timestamp; raw files are not monotonic by dropoff time.
- Do not store heap objects inside Rift region structures unless they are otherwise rooted or the GC can see the reference.
- Do not use the original full-rescan Q1/Q2 ranking approach; it made the 100k heap run take `246360.864 ms`.
- Do not repeat the uncommitted Q1 indexed-heap ranking replacement unchanged;
  it was correct on smoke/sample/100k/1M output checks, but it materially
  regressed Q1 processing and was backed out.
- Do not present the pipeline surrogate as a tracked-source reproduction of the old pipeline benchmark.
- Do not treat single-run DEBS ordering as stable. A later current-binary single run showed Rift HPZone slightly faster than heap, while earlier single runs showed Rift slower.
- Do not ignore macOS sandboxing. `sbt` needs access to `~/.sbt`, and `/usr/bin/time -l` needed escalation to read RSS correctly.
- Do not assume `.git` is a directory in this worktree.

## Read These First

1. `docs/HANDOFF.md`
2. `DESIGN.md`
3. `ROADMAP.md`
4. `/Users/siyaoliu/rift/docs/Rift Literature Review.md`
5. `/Users/siyaoliu/rift/Claude_output/CODEX.md`
6. `sandbox/PHASE0_BASELINES.md`
7. `sandbox/PHASE4_EXIT.md`
8. `bench/debs2015/RESULTS.md`

## Safe Next Action

The implementation worktree should now be clean and ahead of
`origin/feature/rift`. The safest next technical action is to design and test a
fair treatment for the remaining tree/taxi/latency metadata, or add allocation
attribution around those structures before changing them. Do not reintroduce
reset-per-event top-k snapshots; reusable top-k arrays are the accepted lower
overhead shape.

## Unsafe Assumptions To Avoid

- "Rift already has a DEBS application win." It does not yet.
- "GC time should disappear because Q1/Q2 windows and input bytes use Rift."
  Tree/taxi/latency metadata and broader collection state are still heap-heavy.
  The current measurements show Q2 processing dominates total elapsed time more
  than GC or Rift bookkeeping.
- "SafeZone is solved." Improved SafeZone is much better on some workloads, but current SafeZone pathologies and workload sensitivity still matter.
- "Layout wins prove allocator wins." They are separate effects.
- "The current bounded-sample medians prove a final DEBS win." They do not;
  SafeZone/Commix/full-month controls and safe API boundaries are still missing.
