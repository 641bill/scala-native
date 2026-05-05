# Checked Overhead Removal Matrix

Date: 2026-05-03
Last updated: 2026-05-05 13:43:52 CEST

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
| Object construction and checked allocation lowering | Regions remove tracing/reclaim, but every object still needs header/RTTI setup, constructor work, and field stores. Checked allocation currently goes through an `allocImpl` method path until it is made more intrinsic. |
| SafeZone close/reclaim bookkeeping | Required by SafeZone lifecycle unless a new reset-capable backend is designed. |

## Current Bottlenecks

| Area | Evidence | Next target |
|---|---|---|
| Checked append/window application overhead | The new page/token operator clears the generated Common Crawl-shaped q1/q2 gate: at 1M, q1 `rift-checked-page-token` is `3956.366 ms` vs current checked `4855.133 ms`; q2 is `4039.855 ms` vs `4820.611 ms`. | Promote this shape cautiously and test real WET/WAT/NDJSON inputs; keep generic `StreamAppendWindow` as the defensive control. |
| Allocation lowering / object construction | `ObjectAllocationLoweringMatrix` now removes stream-window/query traversal from the comparison and retains records so allocations cannot be optimized away. | The first retained-buffer rows mixed in generic checked `RegionBuffer` overhead. The refined retained-region-array rows show checked region allocation itself is fast: at 10M, checked SafeZone-backed is `143.319 ms`, checked Rift is `165.774 ms`, trusted HP is `199.627 ms`, and heap is `271.121 ms` with `105.807 ms` median GC. Next work should target generic checked buffers/operators, not raw allocation first. |
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
4. Use `ObjectAllocationLoweringMatrix` to distinguish allocation lowering
   from operator/query CPU before changing more application benchmarks.
5. Prioritize real WET/WAT/NDJSON rows that stress heap GC before optimizing
   more application-specific code.
