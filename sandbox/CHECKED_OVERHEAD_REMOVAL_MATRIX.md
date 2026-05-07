# Checked Overhead Removal Matrix

Date: 2026-05-03
Last updated: 2026-05-07 18:53 CEST

Status: overhead-removal contract plus implementation checkpoint. This matrix
separates three states:

- already removed or avoided in code;
- safe to remove only after explicit compiler/runtime probes;
- still required because it is real operator/container work, not memory-safety
  bookkeeping.

## Static Safety To Runtime Work Mapping

| Static property | Runtime work that should be absent from checked headline paths | Current state |
|---|---|---|
| Region values cannot escape their lifetime. | No runtime escape table, no Yak-style promotion barrier. | Satisfied by compiler probes for current checked API slice. |
| Heap objects cannot retain region values past close. | No dynamic heap-to-region retention tracker. | Satisfied by negative compiler probes for direct retention patterns. |
| Region-to-heap references require immutable/static metadata or explicit `HeapRoot`. | No blanket per-page root registration for checked Rift regions. | Partially satisfied; root-free SafeZone-backed lowering is not a safety claim yet. |
| Outer-to-inner region references are rejected unless lifetimes are equal/proven. | No close-time scan to discover illegal outer references. | Partially satisfied by owner-token APIs and negative probes. |
| Escaping closures cannot capture region values. | No runtime closure-capture tracker. | Satisfied by current negative compiler probes. |
| Use-after-close/reset is rejected by API/lifetime shape. | Hot paths should not rely on dynamic use-after-close checks for correctness. | Public low-level APIs remain defensive; the new operator-owned page/token append path removes selected checks after targeted stale-bucket probes. |
| Diagnostics are opt-in. | No per-allocation global atomics for byte counters or trace stats. | Already improved for Rift allocation stats; SafeZone trace remains diagnostic-only. |

## Already Removed Or Avoided

| Overhead | Change / evidence | Result |
|---|---|---|
| Rift per-allocation global allocated-byte atomics | Default fast path now accumulates total raw bytes locally and flushes at close/reset. | Common Crawl-like q1/q2 ordering changed in favor of trusted Rift. This is the clearest completed overhead-removal item. |
| SafeZone per-page root-removal cliff | Improved roots mode coalesces root removal. | `safezone-improved` is now the real SafeZone baseline. |
| Per-entry close callback in append-window API | `StreamAppendCursor` closes buckets with one bucket callback and cursor traversal. | Focused 1M append-window cursor gate passed. |
| Per-record bucket open check and child-region lookup in the page/token append path | `StreamPageTokenAppendWindow` owns bucket lookup, caches the child region once per bucket, and appends through an owned path that skips per-record `bucket.child.checkOpen()` and stale-current `isOpen` checks. | Focused 1M `rift-checked-page-token` is `27.141 ms` vs current `rift-checked-rift` `30.819 ms`; generated Common Crawl-shaped q1/q2 checked rows improve by `16.2-18.5%` versus current checked. |
| Page-token no-drain close for append-only/aggregate-on-append shapes | `closePageTokenAppendBucketsBeforeNoDrain` and `closeAllPageTokenAppendBucketsNoDrain` clear parent bucket refs and close child regions without cursor traversal when the operator has already completed query work on append. | Safety/runtime probes pass, but the 1M cost matrix did not materially improve. Keep as an operator-owned option, not a headline speedup. |
| Generic checked `allocImpl/checkOpen` in operator-owned page-token child buckets | `OpenStreamingRegion` plus `allocOpen(...)` lets lowering call `RiftRegion.allocUncheckedImpl(...)` only when the page-token operator owns bucket open/close order. | Validation passes. Focused 1M checked scoped page-token is `73.632/83.177/82.198 ms` on append-only/drain/aggregate versus heap `76.295/85.873/84.354 ms`; DSPBench Fraud q2 improves slightly to `797.782 ms` checked scoped versus heap `806.697 ms`. |
| SafeZone root tracking in unsafe lower-bound mode | `safezone-rootless-32k` disables root add/remove. | Useful lower bound only; not a safety result. |

## Safe But Not Yet Removed

These are not code changes yet. They require a debug/headline policy and
matching compiler probes so the optimized path is not relying on undefined
behavior.

| Candidate overhead | Why it looks removable | Required proof/test before removal |
|---|---|---|
| `bucket.child.checkOpen()` in generic checked append/fold/join hot paths | Checked bucket owner tokens should prevent appending to a closed child bucket. | Removed only in the new page/token operator-owned path. Generic low-level APIs and fold/join/rank paths still need their own stale-token probes before removal. |
| `current.isOpen` check in generic current-bucket fast paths | A closed current bucket should be impossible after structured close updates arena state. | Removed only in the new page/token operator-owned path. Other windows still need stale-current probes. |
| SafeZone roots for root-free checked rows | If region objects contain only region references or statically safe immutable/rooted heap references, the GC should not need region pages as roots. | Root-free eligibility analysis plus negative tests for unrooted region-to-heap, heap-retains-region, and outer-retains-inner. |
| HeapRoot list maintenance for root-free regions | Root-free rows should not retain dynamic heap handles. | Separate root-free backend mode that rejects `HeapRoot` use or proves all handles are static/immutable. |

## Costs That Are Not Memory-Safety Overhead

These should not be removed by safety claims alone:

| Cost | Reason |
|---|---|
| Hash table probes in rank/fold/table operators | They are container algorithm work. Optimize layout/probing only if the operator shape is required by a winning benchmark. |
| Heap maintenance in rank/TableRank | This is ranking semantics, not region safety. |
| Object field access, token parsing, string/decimal formatting | CPU/application work. Regions only help if allocation/reclaim is material. |
| Object construction and remaining allocation lowering | Regions remove tracing/reclaim, but every object still needs header/RTTI setup, constructor work, and field stores. The page-token `allocOpen` path removes one checked branch, but it does not remove object construction or append/linking work. |
| SafeZone close/reclaim bookkeeping | Required by SafeZone lifecycle unless a new reset-capable backend is designed. |

## Current Bottlenecks

| Area | Evidence | Next target |
|---|---|---|
| Checked append/window application overhead | The page-token operator clears the generated Common Crawl-shaped q1/q2 gate after open allocation: q1 checked scoped `3707.214 ms` vs heap `5577.965 ms`; q2 checked scoped `3902.795 ms` vs heap `5183.074 ms`. | Promote this shape cautiously and keep searching real WET/WAT/NDJSON/DSPBench-style inputs; generated rows prove the memory regime, not real-input prevalence. |
| Allocation lowering / object construction | `ObjectAllocationLoweringMatrix` removes stream-window/query traversal from the comparison; page-token `allocOpen` removes the generic checked `checkOpen` branch for operator-owned buckets. | The focused and application reruns show only modest extra speedup. Remaining allocation cost is object construction, zero/init, append/linking, and query traversal. Do not spend more time on `checkOpen`; profile object construction and operator traversal next. |
| Generic checked buffer overhead | `CheckedRegionBufferMatrix` now has exact-array and fixed-capacity `ObjectBuffer` controls. At 10 x 100k records, `rift-checked-array` is `18.574 ms`, fixed `ObjectBuffer` is `25.788 ms`, and growable `RegionBuffer` is `29.968 ms`; pre-sizing `RegionBuffer` lands near `ObjectBuffer` (`25.980 ms` vs `25.408 ms`) but still trails the exact array. A follow-up fixed-chunk append operator was correct but slower than linked page-token at 1M. | For known-size operators, prefer exact region-owned arrays when capacity is known. For append/drain stream workloads, keep linked page-token as the current fast path; do not assume chunking is faster unless a workload needs random access or substantially larger per-bucket payloads. |
| Checked rank/table overhead | TableRank and long-key rank fail focused 1M gates. | Keep out of application claims; profile only after append/page/token path is fixed. |
| Checked fold/aggregate overhead | `StreamWindowFold` lowers RSS/GC but loses elapsed at 1M. | Do not integrate into Common Crawl/NEXMark until focused gate passes. |
| SafeZone trace overhead | `SAFEZONE_TRACE=1` creates a severe `safezone-rootless-32k` Common Crawl q1 pathology not reproduced without tracing. | Never use trace elapsed as headline; use only counters. |

## Latest Measurement

Detailed source: `evidence/OBJECT_ALLOCATION_LOWERING_MATRIX.md`.

The retained-region-array object-allocation matrix now has validated rows at 100k,
1M, and 10M objects. The result splits allocation/object-construction cost
from stream operator cost:

| Scale | Heap | Trusted Rift HP | Checked Rift | Checked SafeZone-backed | Interpretation |
|---|---:|---:|---:|---:|---|
| 100k | `1.691 ms`, GC median `0.000 ms` | `1.932 ms` | `1.576 ms` | `1.395 ms` | Checked region-array rows win once generic `RegionBuffer` overhead is removed. |
| 1M | `20.429 ms`, GC median `5.745 ms` | `19.392 ms` | `16.100 ms` | `14.347 ms` | Checked region-array rows beat heap/trusted and lower RSS. |
| 10M | `271.121 ms`, GC median `105.807 ms`, RSS `971 MB` | `199.627 ms`, RSS `404 MB` | `165.774 ms`, RSS `404 MB` | `143.319 ms`, RSS `404 MB` | Region allocation wins when heap becomes GC/RSS-bound; checked allocation is not the bottleneck in this clean shape. |

This changes the next engineering target: the region allocator itself is not
the dominant cost at 10M (`~11-12 ms` measured Rift allocator-op time), and
checked allocation is not slower in the clean retained-array shape. The earlier
checked gap came mostly from generic `RegionBuffer` retention overhead, so
checked buffers/operators need the next focused optimization pass.

Detailed source: `evidence/CHECKED_PAGE_TOKEN_COST_MATRIX.md`.

The new page-token cost-decomposition matrix splits the remaining page-token
work into `append-only`, `append-drain`, and `append-aggregate` same-shape
rows. Baseline 1M rows before the no-drain API were:

| Workload | Heap same-shape | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|
| `append-only` | `76.696 ms`, GC `11.240` | `76.831` | `74.787` |
| `append-drain` | `83.176 ms`, GC `11.147` | `84.563` | `82.569` |
| `append-aggregate` | `86.338 ms`, GC `11.523` | `87.106` | `83.484` |

After adding no-drain close and tightening the same-bucket fast path to skip
expiry checks only when the current bucket is the only live bucket, the same
1M rows were:

| Workload | Heap same-shape | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|
| `append-only` | `78.130 ms`, GC `11.508` | `77.185` | `74.743` |
| `append-drain` | `83.447 ms`, GC `11.351` | `84.698` | `81.628` |
| `append-aggregate` | `85.500 ms`, GC `11.465` | `84.928` | `82.623` |

Conclusion: no-drain close is safe, but close/traversal/open bookkeeping is
already small for this focused matrix. The remaining performance work should
focus on allocation lowering, object construction, query CPU, and application
paths that accidentally call bucket/region lookup more often than once per
bucket.

Follow-up diagnostic source:
`docs/CPU_PROFILE_REPORT.md`, `COMMON_CRAWL_WET_DIAG=1`, and
`DSPBENCH_DIAG=1`.

The 2026-05-07 diagnostic rerun clarified one misleading cost bucket:
`pageTokenAppendRegionFor` performs expired-bucket close synchronously, so raw
`bucket_switch_ms` includes most close traversal. After subtracting expired
close, estimated bucket open/switch is small:

| Row | Estimated bucket open | Close cursor | Allocation+append | Interpretation |
|---|---:|---:|---:|---|
| Common Crawl-shaped q1 checked Rift, 1M | `2.672 ms` | `757.595 ms` | `3444.176 ms` | Open/switch is not the bottleneck; allocation+append and per-record close traversal dominate. |
| Common Crawl-shaped q1 checked scoped, 1M | `9.829 ms` | `760.700 ms` | `3177.917 ms` | SafeZone-backed checked mostly wins by cheaper append/allocation; traversal is similar. |
| DSPBench Fraud q2 trusted Streaming, 1M | `0.188 ms` | `56.087 ms` | `131.493 ms` | Real-input row has negligible open cost and faster region append than heap. |
| DSPBench Fraud q2 checked scoped, 1M | `0.990 ms` | `63.456 ms` | `144.353 ms` | The checked tax is close traversal plus common query/replay CPU, not bucket open. |

Conclusion: the next useful optimization is not another bucket-open patch.
Either avoid per-record close traversal for workloads whose query work can be
completed on append, or reduce cursor/node traversal overhead itself. For real
Fraud q2, allocator work is already below heap in the diagnostic row.

Detailed source: `evidence/CHECKED_PAGE_TOKEN_APPEND_MATRIX.md`.

`StreamPageTokenAppendWindow` adds the operator-owned checked path requested by
the overhead-removal contract. It passed:

- `sandbox3_next` compile;
- checked compiler suite: `98/98`;
- checked runtime suite: `43/43`;
- 20k smoke, 100k 3-run, and 1M 3-run focused gates;
- generated Common Crawl-shaped q1/q2 100k and 1M application gates.

Focused 1M rows:

| Mode | Median ms | GC ms | RSS bytes |
|---|---:|---:|---:|
| `heap-immix` | 35.652 | 10.548 | 75087872 |
| `rift-checked-rift` | 30.819 | 0.000 | 47579136 |
| `rift-checked-page-token` | 27.141 | 0.000 | 47579136 |
| `rift-checked-safezone-improved-32k` | 29.276 | 0.000 | 47448064 |
| `rift-checked-safezone-page-token` | 26.191 | 0.000 | 47480832 |

Generated Common Crawl-shaped 1M q1/q2 rows:

| Query | Current checked | Page-token checked | SafeZone-backed page-token |
|---|---:|---:|---:|
| q1-tokenize | `4855.133 ms` | `3956.366 ms` | `3728.286 ms` |
| q2-domain-window | `4820.611 ms` | `4039.855 ms` | `3816.247 ms` |

All rows matched checksum/output count. This is generated stressor evidence,
not real Common Crawl proof.

Fixed-chunk append follow-up:

| Mode | Median ms | GC ms | RSS bytes | Interpretation |
|---|---:|---:|---:|---|
| `heap-immix` | 37.748 | 11.359 | 75071488 | Baseline linked-list shape. |
| `heap-immix-chunk` | 45.363 | 11.952 | 75104256 | Chunk traversal is slower even on heap. |
| `rift-checked-page-token` | 28.452 | 0.000 | 47579136 | Current best checked Rift append path. |
| `rift-checked-chunk-token` | 34.273 | 0.000 | 47579136 | Correct but slower due to extra chunk allocation/control overhead. |
| `rift-checked-safezone-page-token` | 27.214 | 0.000 | 47464448 | Current best checked SafeZone-backed append path. |
| `rift-checked-safezone-chunk-token` | 33.108 | 0.000 | 47579136 | Correct but slower than SafeZone-backed page-token. |

Conclusion: the chunk path is useful negative evidence. It shows that
"array/chunk" is not automatically faster than linked page-token. The
operator-owned lifetime idea is still right, but the next patch should target
the existing page-token path or exact known-size arrays rather than moving
sequential append/drain workloads to chunks.

## Next Code Patch Criteria

The next overhead-removal patch should not broaden no-check paths blindly:

1. Keep public low-level APIs defensive.
2. Add equivalent stale-token probes before applying the same owned-path idea
   to fold/join/rank operators.
3. For rootless checked SafeZone rows, add root-free eligibility checks before
   claiming safety.
4. Use focused cost matrices and profiles to distinguish object construction,
   append/linking, cursor traversal, and query CPU before changing more
   application benchmarks.
5. Prioritize real WET/WAT/NDJSON rows that stress heap GC before optimizing
   more application-specific code.
