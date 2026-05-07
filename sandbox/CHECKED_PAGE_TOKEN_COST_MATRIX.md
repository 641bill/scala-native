# Checked Page-Token Cost Matrix

Date: 2026-05-07
Last updated: 2026-05-07 16:20 CEST

Status: focused cost-decomposition matrix added and validated. This matrix is
not a replacement for `CHECKED_PAGE_TOKEN_APPEND_MATRIX.md`; it splits the
page-token workload into fair same-shape costs so we can see which part is
memory-management overhead and which part is query/traversal CPU.

## Workloads

| Workload | Meaning |
|---|---|
| `append-only` | Allocate/link records, update checksum during append, close buckets without record traversal. |
| `append-drain` | Allocate/link records, then drain records through cursor/list traversal at bucket close. |
| `append-aggregate` | Allocate/link records, maintain per-bucket aggregate metadata during append, and close by reading metadata only. |

All modes use the same logical bucketed program shape. The heap control is
`heap-same-shape`, not a direct array oracle.

## Modes

- `heap-same-shape`
- `safezone-improved-32k`
- `rift-trusted-streaming`
- `rift-checked-page-token`
- `rift-checked-safezone-page-token`

## Baseline 1M Rows Before No-Drain API

Command:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-baseline \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Workload | Heap same-shape | SafeZone improved 32K | Trusted Streaming | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|---:|---:|
| `append-only` | `76.696` ms, GC `11.240` | `91.355` | `90.843` | `76.831` | `74.787` |
| `append-drain` | `83.176` ms, GC `11.147` | `98.887` | `96.492` | `84.563` | `82.569` |
| `append-aggregate` | `86.338` ms, GC `11.523` | `101.775` | `99.536` | `87.106` | `83.484` |

Interpretation:

- Checked scoped page-token is competitive even in same-shape rows.
- Heap is still strong when it can allocate cheaply and only pays about
  `11-12 ms` timed GC at 1M.
- SafeZone/trusted streaming are slower in this focused same-shape matrix,
  which suggests the operator-owned checked path is doing useful work beyond
  merely replacing the allocator.

## No-Drain Close Follow-Up

Change:

- Added `closePageTokenAppendBucketsBeforeNoDrain(...)`.
- Added `closeAllPageTokenAppendBucketsNoDrain(...)`.
- `append-only` and `append-aggregate` now use the no-drain close path in the
  cost matrix.
- Generic public append-window close remains defensive.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `51/51`.
- 20k smoke passed across all workloads/modes.

1M command:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-safe-fast-path \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Workload | Heap same-shape | SafeZone improved 32K | Trusted Streaming | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|---:|---:|
| `append-only` | `78.130` ms, GC `11.508` | `91.752` | `92.081` | `77.185` | `74.743` |
| `append-drain` | `83.447` ms, GC `11.351` | `101.303` | `98.882` | `84.698` | `81.628` |
| `append-aggregate` | `85.500` ms, GC `11.465` | `102.528` | `99.061` | `84.928` | `82.623` |

Conclusion:

- No-drain close is safe and useful as an operator-owned API option, but it did
  not materially improve the 1M focused matrix. It should not be presented as
  the main speedup. The final rerun also tightens the same-bucket fast path so
  it bypasses expiry checks only when the current bucket is the only live
  bucket and cannot expire at the current cutoff.
- The remaining same-shape cost is mostly allocation/object construction plus
  query/checksum/aggregate work, not close traversal. Close/open counts are
  already small at this scale (`40-41` buckets at 1M).
- The strongest existing headline path remains the focused append matrix:
  checked scoped page-token `27.240 ms` vs heap `36.722 ms` after the no-drain
  API checkpoint.

## Diagnostic-Only Split

Command:

```sh
CHECKED_PAGE_TOKEN_COST_DIAG=1 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=1 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=0 \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-diag \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

Do not use diagnostic elapsed times as headline rows. The instrumentation
adds per-loop `nanoTime` overhead.

Useful direction from the diagnostic pass:

- `allocation_append_ms` is lower in checked page-token rows than heap in the
  diagnostic pass, consistent with the idea that allocation+append is not the
  remaining blocker.
- `cursor_traversal_ms` is only a few milliseconds at 1M in `append-drain`.
- close/open counts are low, so further close bookkeeping changes are unlikely
  to produce large wins unless an application calls the region lookup on every
  record instead of once per bucket.

## Files

- Child matrix: `/Users/siyaoliu/rift/scala-native-rift/sandbox/src/main/scala-next/CheckedPageTokenCostMatrix.scala`
- Runner: `/Users/siyaoliu/rift/scala-native-rift/sandbox/run_checked_page_token_cost_matrix.sh`
- Raw summaries:
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-baseline/summary.tsv`
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-safe-fast-path/summary.tsv`
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-diag/summary.tsv`
