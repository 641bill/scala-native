# Phase 4 Exit Summary

Date: 2026-04-23

Branch: `feature/rift`  
Head commit: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

This file closes the Phase 4 question from `ROADMAP.md`: separate allocator
wins from topology wins and layout wins.

Detailed notes:

- `sandbox/PHASE4_LAYOUT.md`
- `sandbox/PHASE4_TOPOLOGY.md`

## Runtime Patch State

The numbers below use local, uncommitted runtime changes in
`nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`:

- fresh mmapped slabs are marked zero-filled
- fresh zero-filled slabs skip redundant per-object `memset`
- huge slabs avoid synchronous `MAP_POPULATE` and `MADV_HUGEPAGE`
- reused regular slabs are zeroed once when acquired from TLS/global pools
- `reset` zeroes the retained first slab before rewinding the bump pointer

Validation:

- `sbt "tests3/testOnly scala.scalanative.memory.RiftRegionTest"` passes
  `5/5`.
- The reset regression test checks that default object fields are zero after
  `reset`.

## Runtime Effect

The original linked list-of-lists shape still supports a runtime claim:

| Harness | Mode | Median ms |
|---|---|---:|
| runtime matrix, 5-run baseline | Immix heap | 15260.875 |
| runtime matrix, 5-run baseline | current SafeZone | 136016.922 |
| runtime matrix, 5-run baseline | improved SafeZone | 9824.936 |
| runtime matrix, targeted 3-run follow-up | Rift HPZone | 6638.949 |
| topology matrix, report-scale 3-run | Rift HPZone one-region | 7256.426 |

The two Rift entries are different harnesses and should not be collapsed into a
single headline number. Both show the same direction: Rift is materially faster
than heap and improved SafeZone on the linked one-region shape.

## Layout Effect

The layout study shows that data representation is a first-order effect:

| Mode | Linked median ms | Chunked median ms | Flat median ms |
|---|---:|---:|---:|
| Immix heap | 15260.875 | 2636.480 | 1766.295 |
| current SafeZone | 136016.922 | 4835.904 | 1735.453 |
| improved SafeZone | 9824.936 | 2369.108 | 1743.161 |
| Rift HPZone | 7278.150 | 2463.674 | 1515.091 |

Follow-up Rift-only runtime checks after regular-slab zeroing:

| Layout | Previous Rift median ms | Follow-up Rift median ms |
|---|---:|---:|
| Linked | 7278.150 | 6638.949 |
| Chunked | 2463.674 | 2390.390 |

Conclusions:

- The flat Rift cliff was a runtime bug, not a flat-layout limitation:
  `15468.496 ms` before the huge-allocation fix, `1515.091 ms` after.
- Chunking removes most allocation pressure for every runtime, including heap.
- Rift still does not clearly beat improved SafeZone on the first chunked
  layout: the targeted follow-up narrows the gap to about `0.9%`, but does not
  reverse it.

## Topology Effect

Report-scale topology subset:

- `LISTBENCH_N=3000`
- `LISTBENCH_STRUCTURES=40`
- `LISTBENCH_BENCHMARK_RUNS=3`

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

Conclusions:

- Nested row-local topology mostly fixes current SafeZone's old root-bookkeeping
  pathology: `136016.922 ms` to `10289.097 ms`.
- Once roots mode is enabled, SafeZone one-region, nested, and mixed are all in
  the same `9.77-9.93 s` band.
- Rift one-region and nested are also close: `7256.426 ms` vs `7314.201 ms`.
  Open/close overhead is not the bottleneck on this shape.
- Mixed rooted heap-values is not a Rift win. The explicit heap root table makes
  the topology safe, but it also changes the cost profile enough that improved
  SafeZone wins (`9877.914 ms` vs `11695.748 ms`).

## Safety Finding

The first unrooted Rift mixed topology failed:

```text
CHECKSUM_MISMATCH name=rift-mixed actual=13763975568 expected=9990000000
```

Cause: Rift region nodes pointed at GC-heap value objects, but the GC does not
scan Rift regions. The heap objects were not reliably live. This is not just a
benchmark artifact; it is a design rule:

- Safe APIs must reject or specially handle unrooted region-to-GC references.
- Mixed GC-plus-region designs need explicit heap roots, GC-scanned region
  metadata, or the opposite direction where heap objects reference open region
  objects.

This finding should feed directly into Phase 6 capture-checking tests.

## Exit Decision

Phase 4 is sufficiently complete to move to Phase 5, with caveats:

- Runtime-only Rift wins are real on the linked one-region shape.
- Layout wins are large and must be reported separately from allocator wins.
- Improved SafeZone is the right baseline, not old SafeZone.
- Mixed GC-plus-region is safety-sensitive and cannot be treated as a free
  optimization.
- The first chunked layout still leaves a small improved-SafeZone gap.

Next roadmap step: Phase 5, streaming operators and DEBS 2015. Carry forward
the Phase 4 lesson: evaluate runtime-only, topology, and layout effects
separately instead of presenting one blended speedup.
