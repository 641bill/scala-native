# Scala Native Win Envelope

Date: 2026-04-29

Status: Phase 6/7 evidence synthesis. This note classifies where Rift currently
wins against Scala Native Immix, where it only reduces memory pressure, and
where checked container overhead still dominates.

## Purpose

The next useful Scala Native question is not "can one more TableRank patch make
DEBS faster?" It is:

- which workloads are actually allocation/GC-bound under Scala Native Immix;
- which region-backed operator shapes are cheap enough to win;
- which workloads are CPU/I/O/ranking-bound and have little GC ceiling;
- where improved SafeZone remains the stronger baseline;
- where checked safety machinery costs more than it saves.

This document summarizes existing evidence and the new
`CheckedAppendWindowMatrix` result. It keeps TableRank boxed off from DEBS Q1:
TableRank is framework progress, but its 1M focused gate still fails. It also
keeps the first reusable `StreamAppendWindow` API boxed off from DEBS: the
manual checked child-bucket shape wins at 1M, while the reusable API currently
adds too much container/callback overhead.

## Evidence Labels

| Label | Meaning |
|---|---|
| Runtime allocator win | Same-layout or same-logical-shape allocation is expensive enough that Rift beats Immix. |
| Region-friendly operator win | Stream/dataflow operator data has structured lifetime and region placement wins. |
| Layout/topology win | Performance depends primarily on object topology or layout, not just allocator choice. |
| CPU-bound/no GC ceiling | Heap GC is too small to explain elapsed time; region memory cannot create a large win by itself. |
| Checked-container overhead | The checked abstraction is functionally useful but container CPU/RSS overhead dominates. |

## Summary Table

| Benchmark / result pack | Main scale | Best current Rift row | Heap / Immix row | Classification | Evidence status |
|---|---:|---:|---:|---|---|
| GCBench | 5-run median | HPZone `161.641 ms` | heap `203.514 ms` | Runtime allocator win | Validated local baseline |
| ListOfLists linked | 5-run median | HPZone `6951.331 ms` | heap `15085.511 ms` | Runtime + topology win | Validated local baseline |
| ListOfLists flat | 3-run layout median | HPZone `1515.091 ms` | heap `1766.295 ms` | Layout/topology win | Validated enough for Phase 4 |
| Pipeline surrogate | 5-run median | HPZone `5.089 ms` | heap `4.924 ms` | CPU-bound/no GC ceiling | Surrogate, not tracked-source evidence |
| Broom-style Dataflow checked | 10 x 100k docs | checked SELECT `18.865 ms`, AGGREGATE `36.003 ms`, JOIN `18.736 ms` | heap SELECT `36.868 ms`, AGGREGATE `51.474 ms`, JOIN `32.170 ms` | Region-friendly checked operator win | Local methodology reproduction |
| Broom-scale Dataflow | 40 x 500k docs, single run | HPZone SELECT `451.041 ms`, JOIN `447.803 ms` | heap SELECT `623.761 ms`, JOIN `602.540 ms` | Region-friendly operator win | Provisional single run |
| StreamFlex pressure | throughput/latency pressure | Streaming throughput `329.896 ms`; latency `157.792 ms`, 0 misses | heap throughput `634.472 ms`; latency `169.331 ms`, 89 misses | Region-friendly latency/throughput win | Local methodology reproduction |
| Yak top-word/filter | 40 x 250k records | Streaming `262.980 ms` | heap `311.527 ms` | Region-friendly operator win | Local methodology reproduction |
| Yak GraphChi-style | 40 x 16 x 15625 edges | Streaming `236.388 ms` | heap `302.599 ms` | Region-friendly vs heap, not improved SafeZone | Local methodology reproduction |
| Yak grouped sort | 10 x 100k records | HPZone `227.393 ms` | heap `237.354 ms` | CPU/sort-bound, modest allocator win | Local methodology reproduction |
| Stancu-style tx boundary | 200k tx, 64/region | Streaming `38.844 ms` | heap `43.189 ms` | Boundary-sensitive allocator win | Local methodology reproduction |
| Checked RegionBuffer | 1M records | checked `28.654 ms` | heap `33.825 ms` | Cheap checked container win | Focused checked API evidence |
| Checked RegionPriorityQueue | 500k records | checked `28.621 ms` | heap `27.369 ms` | Checked-container overhead | Focused checked API evidence |
| Checked IndexedPriorityQueue | 1M events | checked `103.052 ms` | heap `100.254 ms` | Checked-container overhead | Focused checked API evidence |
| Checked StreamWindowRank long-key | 1M events | checked-long `503.906 ms` | heap-long `358.988 ms` | Checked-container overhead | Focused checked API evidence |
| StreamWindowTableRank profile | 1M events | table-long `568.572 ms` | heap-long `437.702 ms` | Checked-container overhead | Profiled; gated out of DEBS |
| Checked AppendWindow manual child-bucket | 1M events | checked `32.261 ms` | heap `35.513 ms` | Cheap checked operator win | New focused matrix |
| Reusable `StreamAppendWindow` API | 1M events | checked-api `76.057 ms` | heap `37.424 ms` | Checked-container/API overhead | Correctness-valid, gated out of DEBS |
| DEBS RunBoth checked, bounded 1M | 3-run median | checked `5043.240 ms` | heap `5363.257 ms` | Application partial win, CPU-limited | Bounded real-data evidence |
| DEBS RunBoth checked, full month | 3-run median | checked `66.804 s` | heap `67.122 s` | Near-tie throughput, memory validation | Full-month evidence, not large speedup |

## What The New Append Window Matrix Adds

`CheckedAppendWindowMatrix` is intentionally simpler than Q1/Q2 ranking. It
uses ordinary Scala data records in child bucket regions and keeps durable
counter arrays on the heap. It avoids hash tables, rank heaps, top-k snapshots,
formatting, and DEBS parsing.

The useful row is the 1M checked result:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 35.513 | 11.149 | 0.000 | 0 | 0 / 0 | 74989568 |
| rift-checked | 32.261 | 0.000 | 0.074 | 1000000 | 41 / 41 | 47497216 |
| rift-trusted-hp | 41.641 | 0.000 | 0.093 | 1000000 | 40 / 40 | 47349760 |
| rift-trusted-streaming | 41.859 | 0.000 | 0.088 | 1000000 | 40 / 40 | 47480832 |

At 100k, heap is still fastest (`2.782 ms` vs checked `3.370 ms`) because
there is no measured GC ceiling. This gives a useful threshold result: cheap
checked append/window regions become attractive when object volume is large
enough, but they are not free on tiny streams.

The first reusable API version is not performance-ready:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 37.424 | 11.596 | 0.000 | 0 | 0 / 0 | 75071488 |
| rift-checked | 34.762 | 0.000 | 0.117 | 1000000 | 41 / 41 | 47546368 |
| rift-checked-api | 76.057 | 0.000 | 0.092 | 1000000 | 41 / 41 | 83247104 |

`rift-checked-api` uses `RiftRegion.StreamAppendWindow` with records extending
`RiftRegion.StreamAppendNode`. It matches checksums and passes compiler/runtime
probes, but it is much slower and higher-RSS than both heap and the manual
checked shape. Region operation time remains tiny, so the gap is API/container
CPU and representation overhead, not allocation or close. Do not move this API
into DEBS until a focused rerun clears the 1M gate.

The trusted modes losing here is also useful. It means the current win is not a
generic "HPZone always beats heap" statement. It is a checked operator-shape
win where parent streaming plus structured child buckets is a better match
than standalone per-bucket trusted regions.

## What Rift Currently Wins

Rift has reliable wins when all of these hold:

- many ordinary data-path Scala objects are allocated;
- those objects share a clear epoch, batch, bucket, or subinterval lifetime;
- close/reset happens at a coarse enough boundary;
- durable control state can remain on the heap or in primitive arrays;
- the operator is append/fold/filter/join-like rather than maintenance-heavy
  ranking or table-update-heavy.

The strongest local categories are:

- linked allocation-heavy runtime baselines: GCBench and ListOfLists;
- Broom-style dataflow operators where documents and outputs are epoch-local;
- StreamFlex-style throughput/latency pressure where per-event objects cause
  heap GC and latency misses;
- Yak top-word/filter and GraphChi-style subintervals where high-volume data
  objects are epoch/subinterval-local;
- checked RegionBuffer and the manual checked AppendWindow child-bucket shape,
  which show the safe API direction can win on simple collection/operator
  shapes when abstraction overhead stays low.

## Where Immix Or SafeZone Remain Hard To Beat

- If GC collection time is tiny, elapsed wins are limited. The pipeline
  surrogate and 100k AppendWindow row are examples.
- If CPU work dominates, region memory can only remove allocation/GC overhead.
  Yak grouped sort is a modest win because sorting dominates.
- Improved SafeZone remains a strong baseline on some Yak/Stancu-style shapes.
  Rift beating heap is not enough for final claims.
- Checked ranking/indexing containers are still too expensive at 1M:
  `StreamWindowRank`, `StreamWindowLongIndexedRank`, and `StreamWindowTableRank`
  are functionally important, but not ready as throughput evidence.
- The first reusable `StreamAppendWindow` API is also too expensive at 1M
  despite matching the winning child-bucket lifetime shape. This is a warning
  that framework APIs must be benchmarked separately from handwritten
  benchmark logic before application integration.
- DEBS full-month is currently a near-tie in elapsed time with much better
  memory/lifetime evidence than earlier checkpoints, not a large application
  speedup.

## DEBS Position After This Plan

Do not reintegrate TableRank into DEBS Q1 yet. The current DEBS path should use
the existing checked rank-arena implementation until a focused checked rank
primitive clears its 1M gate.

The next DEBS candidate should match the proven cheap shape:

- Q1/Q2 append-only bucket event/window entries;
- output or snapshot scratch whose lifetime is an event, bucket, or run;
- fold/filter buffers where records are appended and bulk-closed;
- not Q1 ranking until the checked rank primitive is cheaper;
- not the current `StreamAppendWindow` API until its focused 1M gate passes.

The standard remains: heap and Rift should share the same logical program, with
only allocation placement and lifetime policy changing.

## Practical Rule For Next Work

Before moving an operator into DEBS or another application benchmark, run it in
a focused matrix and classify it:

1.  If checked Rift beats heap at 1M with small region-op time, it is a
    candidate for application integration only if the same API form, not just
    handwritten benchmark logic, passes.
2.  If checked Rift only reduces RSS/GC but loses elapsed by under 5%, it may be
    useful for memory/latency evidence but should not become a speed claim.
3.  If checked Rift loses by more than 5%, keep it as API evidence and profile
    container CPU before application integration.
4.  If heap GC is near zero, do not expect region memory to create a large
    throughput win unless allocation-call cost or RSS is the measured problem.

## Files

Primary evidence:

- `sandbox/CHECKED_APPEND_WINDOW_MATRIX.md`
- `sandbox/CHECKED_REGION_BUFFER_MATRIX.md`
- `sandbox/CHECKED_STREAM_WINDOW_RANK_MATRIX.md`
- `sandbox/TABLERANK_PROFILE.md`
- `sandbox/DATAFLOW_REGION_MATRIX.md`
- `sandbox/STREAMFLEX_REGION_MATRIX.md`
- `sandbox/YAK_REGION_MATRIX.md`
- `sandbox/STANCU_REGION_MATRIX.md`
- `bench/debs2015/RESULTS.md`

Parent synced copies live under `/Users/siyaoliu/rift/evidence/`.
