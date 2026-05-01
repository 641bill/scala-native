# Scala Native Win Envelope

Date: 2026-05-01

Status: Phase 6/7 evidence synthesis. This note classifies where Rift currently
wins against Scala Native Immix, where it only reduces memory pressure, and
where checked container overhead still dominates.

Latest clean-sweep update: `evidence/HEADLINE_STREAMS_2026_05_01.md`,
`evidence/HEADLINE_DEBS_1M_2026_05_01.md`, and
`evidence/HEADLINE_UNSAFEZONE_CORE_PRIOR_2026_05_01.md` supersede older
generated stream, DEBS, core-runtime, and prior-work rows where they overlap.

UnsafeZone-HP checkpoint: `evidence/UNSAFEZONE_HP_BASELINE_MATRIX.md` adds a
benchmark-only SafeZone no-root control (`SAFEZONE_ROOTS_MODE=3`,
`SAFEZONE_PAGE_SIZE=32768`). The first clean core/prior headline medians are
now in `evidence/HEADLINE_UNSAFEZONE_CORE_PRIOR_2026_05_01.md`. Use it to
decide whether SafeZone internals are a better runtime substrate after root
bookkeeping is removed; do not treat it as a safe user-facing mode.

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
TableRank is framework progress, but its 1M focused gate still fails.
`StreamAppendWindow` cursor close now clears the focused append-window gate and
has been integrated into checked DEBS Q1/Q2 window entries. That integration is
framework generalization evidence, not a large DEBS speedup claim; the latest
bounded controls are near-ties or checked-slower because Q1/Q2 process CPU and
live window payload still dominate.

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
| GCBench | 5-run median | HPZone `236.393 ms`; unsafezone-hp `206.636 ms` | heap `213.817 ms`; improved SafeZone `213.239 ms` | Unsafe substrate control; Rift HPZone loses this clean row | Clean UnsafeZone core/prior sweep |
| ListOfLists linked | 5-run median | HPZone `12400.062 ms`; unsafezone-hp `9818.653 ms` | heap `15191.230 ms`; improved SafeZone `9914.397 ms` | Runtime + topology; SafeZone-family win | Clean UnsafeZone core/prior sweep |
| ListOfLists flat | 3-run layout median | HPZone `1515.091 ms` | heap `1766.295 ms` | Layout/topology win | Validated enough for Phase 4 |
| Pipeline surrogate | 5-run median | HPZone `5.089 ms` | heap `4.924 ms` | CPU-bound/no GC ceiling | Surrogate, not tracked-source evidence |
| Broom-style Dataflow checked/UnsafeZone | local docs | checked SELECT `24.413 ms`, AGGREGATE `44.146 ms`, JOIN `24.935 ms`; unsafezone-hp `21.957` / `39.434` / `22.359 ms` | heap SELECT `28.258 ms`, AGGREGATE `48.849 ms`, JOIN `29.122 ms`; improved SafeZone `22.501` / `40.124` / `22.784 ms` | Rift checked beats heap on SELECT/JOIN, but SafeZone-family wins | Clean UnsafeZone core/prior sweep |
| Broom-scale Dataflow | 40 x 500k docs, single run | HPZone SELECT `451.041 ms`, JOIN `447.803 ms` | heap SELECT `623.761 ms`, JOIN `602.540 ms` | Region-friendly operator win | Provisional single run |
| StreamFlex pressure | throughput/latency pressure | Streaming throughput `329.896 ms`; latency `157.792 ms`, 0 misses | heap throughput `634.472 ms`; latency `169.331 ms`, 89 misses | Region-friendly latency/throughput win | Local methodology reproduction |
| Yak top-word/filter | local methodology | Streaming `68.959 ms`; unsafezone-hp `58.686 ms` | heap `70.370 ms`; improved SafeZone `59.286 ms` | SafeZone-family win; Rift only roughly matches heap | Clean UnsafeZone core/prior sweep |
| Yak GraphChi-style | 40 x 16 x 15625 edges | Streaming `236.388 ms` | heap `302.599 ms` | Region-friendly vs heap, not improved SafeZone | Local methodology reproduction |
| Yak grouped sort | 10 x 100k records | HPZone `227.393 ms` | heap `237.354 ms` | CPU/sort-bound, modest allocator win | Local methodology reproduction |
| Stancu-style tx boundary | 200k tx, 64/region | Streaming `51.478 ms`; unsafezone-hp `33.335 ms` | heap `44.141 ms`; improved SafeZone `33.720 ms` | SafeZone-family win; Rift loses elapsed | Clean UnsafeZone core/prior sweep |
| Checked RegionBuffer | 1M records | checked `28.654 ms` | heap `33.825 ms` | Cheap checked container win | Focused checked API evidence |
| Checked RegionPriorityQueue | 500k records | checked `28.621 ms` | heap `27.369 ms` | Checked-container overhead | Focused checked API evidence |
| Checked IndexedPriorityQueue | 1M events | checked `103.052 ms` | heap `100.254 ms` | Checked-container overhead | Focused checked API evidence |
| Checked StreamWindowRank long-key | 1M events | checked-long `503.906 ms` | heap-long `358.988 ms` | Checked-container overhead | Focused checked API evidence |
| StreamWindowTableRank profile | 1M events | table-long `568.572 ms` | heap-long `437.702 ms` | Checked-container overhead | Profiled; gated out of DEBS |
| Checked AppendWindow manual child-bucket | 1M events | checked `32.261 ms` | heap `35.513 ms` | Cheap checked operator win | New focused matrix |
| Reusable `StreamAppendWindow` per-entry API | 1M events | cached checked-api `39.372 ms` | same-run heap `38.559 ms` | Near miss / per-entry callback overhead | Focused matrix; keep as control |
| Reusable `StreamAppendWindow` cursor API | 1M events | cursor `34.708 ms`; cursor-reuse confirmation `35.527 ms` | same-run heap `35.705 ms`; confirmation heap `37.494 ms` | Cheap checked operator win | Focused gate passed; cursor-object reuse is neutral/noisy |
| Reusable `StreamAppendWindow` prepend cursor API | 1M events | prepend cursor `34.662 ms` | same-run heap-prepend `36.836 ms` | Cheap checked operator win, not better than append cursor | Focused control; do not move to DEBS yet |
| Reusable `StreamWindowFold` additive API | 1M events | checked `118.726 ms` | heap `103.244 ms` | Checked aggregate-table overhead | Focused gate failed; lower RSS and zero measured GC, but block application integration |
| NEXMark-lite Q1 conversion | 1M events | checked `374.767 ms`; Streaming `371.404 ms` | heap `384.595 ms` | Region-friendly stream map win | Local methodology benchmark, not exact Beam NEXMark |
| NEXMark-lite Q2 selection | 1M events | checked `287.808 ms` | heap `297.053 ms` | Low-output input-region win | Local methodology benchmark; checked RSS higher than heap |
| NEXMark-lite Q5 hot items | 1M events | checked `355.100 ms` | heap `350.941 ms` | Window aggregate not yet a win | Local methodology benchmark; needs operator/footprint work |
| NEXMark-lite Q8 window join | 1M events | checked `291.832 ms` | heap `322.210 ms` | Region-friendly checked join-window win | Local methodology benchmark, not exact Beam NEXMark |
| NEXMark-lite Q8 `StreamJoinWindow` API | 1M events | packed checked join API `20.987 ms` | heap join API `17.393 ms` | Specialized checked join API is lower-GC/lower-RSS but slower than fair heap control | Focused framework evidence; not a speed claim |
| NEXMark-lite Q5 diagnostic | 1M events | checked `393.415 ms` clean; top scan `46.823 ms` diagnostic | heap `361.882 ms` clean; top scan `45.340 ms` diagnostic | Window aggregate checked overhead | Diagnostic profile plus clean control |
| NEXMark Beam-default Q0 | 1M generated-profile events | Streaming `475.161 ms`, checked `481.436 ms` | heap `521.508 ms`; improved SafeZone `478.500 ms` | Trusted runtime stream-object win; checked beats heap but not improved SafeZone | Clean generated Beam-default profile, not Beam runner evidence |
| NEXMark Beam-default Q1 | 1M generated-profile events | Streaming `919.670 ms`; checked `945.372 ms` | heap `950.341 ms`; improved SafeZone `929.887 ms` | Trusted modest stream-map win; checked does not win | Clean generated Beam-default profile |
| NEXMark Beam-default Q2 | 1M generated-profile events | Streaming `563.282 ms`; checked `572.005 ms` | heap `586.607 ms`; improved SafeZone `576.228 ms` | Modest region-friendly selection row | Clean generated Beam-default profile |
| NEXMark Beam-default Q3 | 1M generated-profile events | checked `295.166 ms`, Streaming `301.975 ms` | heap `315.715 ms`; improved SafeZone `302.668 ms` | Best checked stream row, but below 10% case-study gate | Clean generated Beam-default profile |
| NEXMark Beam-default Q4/Q9 | 1M generated-profile events | Q4 HPZone `576.367 ms`; Q9 Streaming `751.602 ms` | Q4 heap `579.239 ms`; Q9 improved SafeZone `756.972 ms` | Near-tie or improved-SafeZone-adjacent controls | Clean generated Beam-default profile |
| NEXMark Beam-default Q8 | 1M generated-profile events | checked `457.518 ms` | heap `470.798 ms`; improved SafeZone `457.725 ms` | Checked near-tie with improved SafeZone | Clean generated Beam-default profile |
| NEXMark Beam-default Q11 | 1M generated-profile events | HPZone `228.741 ms`, checked `234.401 ms` | heap `218.774 ms`; improved SafeZone `229.557 ms` | Heap wins elapsed; region rows lower GC only | Clean generated Beam-default profile |
| Common Crawl WET-shaped tokenization | 1M generated pages / 137M token records | HPZone `4301.536 ms`, Streaming `4327.405 ms` | heap `4770.503 ms`; improved SafeZone `4066.435 ms` | GC-heavy detector; Rift beats heap but improved SafeZone wins | Clean generated input only; not a Rift case-study win |
| Common Crawl WET small-bucket control | 100k pages / 13.7M records | Streaming `419.779 ms` | heap `386.807 ms`; improved SafeZone `381.109 ms` | Heap/SafeZone recover with tighter lifetimes | Generated input; not a case-study row |
| Common Crawl real WET tokenization | 10k requested pages / 349709 token records | Streaming `15.651 ms` | heap `12.079 ms`; improved SafeZone `16.093 ms` | Real preloaded WET is CPU/live-input-bound, not GC-bound | Real preloaded input; no parser/decompression timing |
| Common Crawl real WET tokenization larger shard row | 50k requested, 21425 actual pages / 752797 token records | HPZone `32.809 ms`, Streaming `33.103 ms` | heap `26.452 ms`; improved SafeZone `30.730 ms` | Heap wins; median timed GC zero | Real preloaded input; actual page count below request |
| Wikimedia generated clickstream | 1M events / 2M records | HPZone `147.163 ms`, Streaming `148.364 ms` | heap `159.746 ms`; improved SafeZone `147.936 ms` | Promising Q2 row but not a 10% win over improved SafeZone | Generated TSV-shaped input only |
| Wikimedia generated clickstream scale check | 10M events / 20M records | HPZone `1462.015 ms`, Streaming `1464.663 ms` | heap `1459.438 ms`; improved SafeZone `1473.088 ms` | Lower GC but elapsed near-tie | Single run only |
| Wikimedia real enwiki clickstream | 1M events / 2M records | Streaming `157.449 ms` | heap `126.800 ms`; improved SafeZone `149.062 ms` | Heap wins; median timed GC zero, with one heap collection outlier | Real preloaded TSV input |
| Linear Road generated tolls | 1M events / 2M records | HPZone `191.896 ms`, Streaming `228.226 ms` | heap `170.464 ms`; improved SafeZone `196.138 ms` | Removes GC but heap wins elapsed | Generated methodology only |
| Linear Road generated accidents | 1M events / 2M records | HPZone `203.793 ms`, Streaming `217.685 ms` | heap `194.520 ms`; improved SafeZone `215.808 ms` | HPZone beats improved SafeZone but not heap | Generated methodology only |
| Linear Road official input reports | 1M events | Streaming `99.769 ms` | heap `88.750 ms`; improved SafeZone `103.086 ms` | Heap wins; region modes lower RSS | Real preloaded official input, not full validator |
| Linear Road official input tolls/accidents | 1M events / 2M outputs | best region `180.277 ms` q1, `198.863 ms` q2 | heap `162.668 ms` q1, `167.811 ms` q2 | Heap wins; median timed GC zero, with one heap collection outlier per 1M query | Real preloaded official input, not full validator |
| Yahoo-style ad stream Q0/Q1 | 1M generated/preloaded events | region modes lower median/max GC | heap Q0 `109.512 ms`, Q1 `121.799 ms` | Heap/improved-SafeZone parse/filter controls | Clean local Yahoo-style memory probe |
| Yahoo-style ad stream Q2 campaign window | 1M generated/preloaded events | Streaming `106.415 ms`, HPZone `109.297 ms` | heap `105.802 ms`; improved SafeZone `106.425 ms` | Near-tie; cuts GC but heap elapsed wins | Clean local Yahoo-style memory probe; not a case-study win |
| RIoTBench-style q1 clean/annotate | 1M generated sensor events | Streaming `148.019 ms`, HPZone `150.369 ms` | heap `135.750 ms`; improved SafeZone `147.638 ms` | Heap wins in clean 1M row; earlier 100k positive weakened | Clean local methodology probe |
| RIoTBench-style q2 window stats | 1M generated sensor events | Streaming `172.802 ms`, HPZone `172.847 ms` | heap `173.334 ms`; improved SafeZone `174.824 ms` | Near-tie; lower GC, tiny elapsed edge | Clean local methodology probe |
| DEBS RunBoth bounded 1M | single run | Streaming `4681.292 ms`; checked `4882.562 ms` | heap `4987.579 ms` | Trusted wins, checked modestly beats heap, bounded correctness/control row | Clean bounded single-run |
| DEBS RunBoth checked, bounded 1M after Q1/Q2 append-window integration | 3-run median | checked `5349.444 ms` | heap `5219.189 ms` | API generalization, CPU-limited | Latest bounded control; not a speedup claim |
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

The first reusable per-entry close API was not performance-ready:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 37.424 | 11.596 | 0.000 | 0 | 0 / 0 | 75071488 |
| rift-checked | 34.762 | 0.000 | 0.117 | 1000000 | 41 / 41 | 47546368 |
| rift-checked-api | 76.057 | 0.000 | 0.092 | 1000000 | 41 / 41 | 83247104 |

The no-callback bucket-lookup follow-up improves the API but does not clear the
gate:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 37.455 | 11.906 | 0.000 | 0 | 0 / 0 | 75038720 |
| rift-checked | 33.157 | 0.000 | 0.084 | 1000000 | 41 / 41 | 47513600 |
| rift-checked-api | 66.023 | 0.000 | 0.082 | 1000000 | 41 / 41 | 47513600 |

The cached bucket/region follow-up removes per-event API bucket lookup and
nearly reaches the gate, but still fails it:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 38.559 | 11.581 | 0.000 | 0 | 0 / 0 | 74989568 |
| rift-checked | 33.172 | 0.000 | 0.083 | 1000000 | 41 / 41 | 47497216 |
| rift-checked-api | 39.372 | 0.000 | 0.084 | 1000000 | 41 / 41 | 47480832 |

The cursor close API clears the focused gate:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 35.705 | 11.095 | 0.000 | 0 | 0 / 0 | 75022336 |
| rift-checked | 32.367 | 0.000 | 0.077 | 1000000 | 41 / 41 | 47529984 |
| rift-checked-api | 37.705 | 0.000 | 0.073 | 1000000 | 41 / 41 | 47529984 |
| rift-checked-api-cursor | 34.708 | 0.000 | 0.074 | 1000000 | 41 / 41 | 47529984 |

`rift-checked-api-cursor` uses `RiftRegion.StreamAppendWindow` with records
extending `RiftRegion.StreamAppendNode`, but closes buckets through
`StreamAppendCursor` so callback dispatch is once per bucket. It is faster
than same-run heap, within 1.15x of same-run manual checked, keeps RSS low, and
keeps Rift op time under `1 ms`. The older per-entry close API remains as a
control and should not be the DEBS integration target.

A follow-up changed `StreamAppendWindow` to reuse one cursor object instead of
allocating a fresh cursor per closed bucket. The 1M focused confirmation subset
still clears the cursor API gate (`35.527 ms` versus same-run heap
`37.494 ms`), but the all-mode rerun had a noisy/non-winning cursor row and RSS
did not change. Treat cursor-object reuse as a small cleanup, not as a new
performance result.

The order-insensitive/head-insert sibling, `prependWindow`, also clears its
matching focused gate: at 1M, `rift-checked-api-prepend-cursor` is `34.662 ms`
versus same-run `heap-prepend` at `36.836 ms`, with the same low RSS as append
cursor. It is not faster than append cursor (`34.597 ms` in the same run), so
it should remain framework/control evidence unless a later operator specifically
needs head insertion.

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

- flat ListOfLists and some older linked allocation-heavy baselines; the clean
  2026-05-01 rerun weakens the GCBench/linked-ListOfLists runtime claim
  against improved SafeZone;
- Broom-style dataflow operators where documents and outputs are epoch-local,
  with the caveat that the clean rerun beats heap but not improved SafeZone on
  SELECT/AGGREGATE and loses JOIN to heap;
- StreamFlex-style latency pressure where region modes remove deadline misses,
  but the clean rerun does not give a throughput win;
- Yak top-word/filter and GraphChi-style subintervals where high-volume data
  objects are epoch/subinterval-local;
- NEXMark-lite Q1 conversion, checked Q2 selection, and checked Q8 window
  joins, where ordinary stream input/output objects are bucket-owned and
  measured GC time drops materially;
- NEXMark Beam-default Q0/Q1/Q2/Q3/Q5/Q8/Q9, which preserve the same local
  logical program under Beam-default generator settings and cut measured GC;
  Q3 is the best checked row, while Q0/Q1/Q2/Q5/Q9 are trusted or near-tie
  rows rather than checked case-study wins;
- checked RegionBuffer and the manual checked AppendWindow child-bucket shape,
  which show the safe API direction can win on simple collection/operator
  shapes when abstraction overhead stays low.
- generated Common Crawl WET-shaped tokenization, where trusted Rift cuts heap
  GC from `1559.601 ms` to about `20.5 ms` and beats heap elapsed. This is not
  a Rift case-study win yet because improved SafeZone is still faster.

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
- The first NEXMark-lite Q5 hot-items row is not a win: region modes reduce GC
  but live-window arrays and top scanning dominate elapsed time and RSS.
- The Q5 diagnostic follow-up shows the top scan itself is about `45 ms` at 1M
  in every mode (`123` scans over `65536` entries), while checked Rift is still
  slower in the clean control. That makes Q5 a checked per-entry/window
  container overhead problem, not a region open/close problem.
- The new `StreamJoinWindow` API is a useful Q8-shaped framework primitive.
  The packed-count fast path removes redundant hot-path count lookups and
  narrows the 1M checked API row from `23.021 ms` to `20.987 ms`, but the fair
  speed gate still fails against the equally specialized heap join API
  (`17.393 ms`). It still cuts measured GC to zero and lowers RSS, so keep it
  as framework/memory evidence.
- The first reusable `StreamAppendWindow` per-entry close API was too
  expensive at 1M despite matching the winning child-bucket lifetime shape.
  Cached bucket/region use almost closed the gap; cursor close is the first
  reusable API form to pass the focused gate. This is a warning that framework
  APIs must be benchmarked separately from handwritten benchmark logic before
  application integration.
- `StreamWindowFold` confirms that warning for aggregate tables. Its 1M
  checked row removes measured GC and cuts RSS (`40402944` bytes versus heap
  `75022336` bytes), but loses elapsed time (`118.726 ms` versus
  `103.244 ms`). Common Crawl WET and NEXMark Q5 fold-backed integration should
  stay blocked until the fold table/API overhead is reduced or a different
  object-heavy shape passes a focused gate.
- The generated Common Crawl WET-shaped detector is mixed after correcting the
  SafeZone baseline. Default token buckets stress heap allocation
  (`149.149 ms` GC at 100k pages), but improved SafeZone is faster than trusted
  Rift (`381.006 ms` vs Streaming `403.935 ms`). Smaller, more natural token
  lifetimes keep improved SafeZone fastest and make heap competitive. Treat
  Common Crawl as a memory-pressure detector, not the next case study. Revisit
  only if a real WET file plus checked page/token API changes the result.
- The generated Wikimedia detector is also mixed. Q2 clickstream is promising
  at 1M (`147.163 ms` HPZone vs `159.746 ms` heap and `147.936 ms` improved
  SafeZone), but the 10M single-run scale check collapses to a near-tie with
  heap. Keep it as ladder evidence; do not make a generated-Wikimedia case
  study without real TSV input or a stronger checked operator.
- The generated Linear Road detector is negative as a case-study candidate.
  HPZone removes measured GC and beats improved SafeZone on q1/q2, but heap is
  still fastest at 1M (`170.464 ms` q1 and `194.520 ms` q2). RSS also does not
  improve for q1/q2 at the default bucket size because the bucket-retained
  output objects dominate the live set.
- The real-input ladder makes this stricter. Real enwiki clickstream, real
  Common Crawl WET, and official Linear Road preloaded rows all report
  `0.000 ms` median timed GC and heap is fastest. Median-zero GC is not
  "no GC": Wikimedia and Linear Road heap 1M logs include one collection
  outlier, but collection was not frequent enough to affect the median. These
  rows are useful ceiling results: preloading real input can make RSS large
  while leaving little median collection-time headroom for regions to recover.
- NEXMark Beam-default Q0/Q1/Q2/Q3/Q5/Q8/Q9 are useful profile rows. Q3 is the
  best checked row (`295.166 ms` versus heap `315.715 ms` and improved
  SafeZone `302.668 ms`); Q8 is a checked near-tie with improved SafeZone; Q11
  is heap-fastest in the clean sweep. These are generated-profile rows, not
  exact Beam runner evidence.
- Yahoo-style ad stream Q2 is not a case-study win at 1M. It cuts heap median
  GC from `6.575 ms` to `1.932 ms` in Streaming, but heap elapsed remains
  slightly faster (`105.802 ms` versus Streaming `106.415 ms`).
- RIoTBench-style rows are useful controls, not wins in the clean 1M sweep. Q1
  clean/annotate now has heap fastest (`135.750 ms`), weakening the earlier
  100k positive row; Q2 is a near-tie where Streaming is only `0.532 ms`
  faster than heap.
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
- `StreamAppendWindow` only through the cursor-close shape, and only in a
  separate DEBS integration step with output equality checks.

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
