# Checked Page-Token Cost Matrix

Date: 2026-05-07
Last updated: 2026-05-07 18:53 CEST

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
| `append-count-by-key` | Allocate/link ordinary records, update per-key count/sum metadata during append, and close by emitting aggregate summaries without record traversal. |

All modes use the same logical bucketed program shape. The heap control is
`heap-same-shape`, not a direct array oracle.

## Modes

- `heap-same-shape`
- `safezone-improved-32k`
- `rift-trusted-streaming`
- `rift-checked-page-token`
- `rift-checked-safezone-page-token`
- `rift-checked-count-by-key`
- `rift-checked-safezone-count-by-key`

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

## Append-Time Count-By-Key / No-Drain Operator

Change:

- Added `RiftRegion.PageTokenCountByKey[T]` as a reusable checked
  page-token aggregate operator.
- The operator keeps ordinary record objects in child bucket regions, but
  updates parent-owned primitive per-key `count`/`sum` arrays during append.
- Bucket close emits per-key summaries and uses the page-token no-drain close
  path, so it does not walk every record at close time.
- Added checked compiler probes for:
  - positive child-bucket record storage;
  - negative direct heap record append into the checked operator.
- Fixed the compiler guard for `appendPageTokenCountByKey(...)`; unlike older
  append APIs, the checked value is not the last argument.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"` passed `120/120`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `52/52`.
- 20k smoke matched checksums across all count-by-key modes.

100k command:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=100000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_WORKLOADS="append-count-by-key" \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape rift-checked-page-token rift-checked-count-by-key rift-checked-safezone-page-token rift-checked-safezone-count-by-key" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-count-by-key-100k-2026-05-07 \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

1M command:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_WORKLOADS="append-count-by-key" \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape rift-checked-page-token rift-checked-count-by-key rift-checked-safezone-page-token rift-checked-safezone-count-by-key" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-count-by-key-1m-2026-05-07 \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Scale | Mode | Median elapsed | Median GC | Region op | Checksum |
|---|---|---:|---:|---:|---:|
| 100k | `heap-same-shape` | `8.906` ms | `0.000` | `0.000` | `775575459465894210` |
| 100k | `rift-checked-page-token` | `10.149` ms | `0.000` | `0.006` | `775575459465894210` |
| 100k | `rift-checked-count-by-key` | `10.057` ms | `0.265` | `0.007` | `775575459465894210` |
| 100k | `rift-checked-safezone-page-token` | `10.065` ms | `0.000` | `0.000` | `775575459465894210` |
| 100k | `rift-checked-safezone-count-by-key` | `9.836` ms | `0.217` | `0.000` | `775575459465894210` |
| 1M | `heap-same-shape` | `105.915` ms | `14.026` | `0.000` | `-8381590485523927347` |
| 1M | `rift-checked-page-token` | `103.614` ms | `1.918` | `0.067` | `-8381590485523927347` |
| 1M | `rift-checked-count-by-key` | `98.761` ms | `0.000` | `0.092` | `-8381590485523927347` |
| 1M | `rift-checked-safezone-page-token` | `102.769` ms | `3.481` | `0.000` | `-8381590485523927347` |
| 1M | `rift-checked-safezone-count-by-key` | `97.860` ms | `0.000` | `0.000` | `-8381590485523927347` |

Interpretation:

- The append-time count-by-key operator is a correct reusable no-drain aggregate
  shape, and it removes the 1M heap GC in this focused row.
- It is not a 100k win: heap remains fastest at that scale.
- At 1M it is a modest win: checked SafeZone-backed count-by-key is about
  `7.6%` faster than heap and about `4.8%` faster than checked SafeZone-backed
  page-token; checked Rift count-by-key is about `6.8%` faster than heap.
- This supports the design direction of operator-owned append-time aggregates,
  but it does not justify broad application claims yet. The next application
  step should only use this shape where the query naturally updates aggregate
  metadata during append and can close buckets without per-record traversal.

## Page-Token Live-Length Bookkeeping Removal

Change:

- Page-token, page-token map/filter, and page-token count-by-key appends now
  use a page-token-owned helper that keeps per-bucket `appendLength` for
  close/cursor logic but skips the underlying generic append-window
  `totalLength` counter.
- Page-token close paths no longer decrement that generic live-length counter.
- Generic public `StreamAppendWindow` and `EpochBuffer` APIs still maintain
  `appendWindowLength`/`epochBufferLength`; the optimization applies only to
  operator-owned page-token paths, which expose no public live-length API.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"` passed `120/120`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `52/52`.
- 20k smoke matched checksums for page-token and count-by-key mode groups.
- A source-level `inline` version was tried and rejected by capture checking;
  true allocation/append intrinsic work likely belongs in compiler/runtime
  lowering rather than a public-source inline helper.

1M commands:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_WORKLOADS="append-only append-drain append-aggregate" \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-no-length-1m-2026-05-07/page-token \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_WORKLOADS="append-count-by-key" \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape rift-checked-count-by-key rift-checked-safezone-count-by-key" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-no-length-1m-2026-05-07/count-by-key \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Workload | Heap same-shape | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|
| `append-only` | `81.535` ms, GC `12.314`, RSS `122781696` | `79.586` ms, GC `0.000`, RSS `83361792` | `77.135` ms, GC `0.000`, RSS `83214336` |
| `append-drain` | `90.374` ms, GC `11.944`, RSS `122781696` | `90.249` ms, GC `0.000`, RSS `83345408` | `88.840` ms, GC `0.000`, RSS `83230720` |
| `append-aggregate` | `86.988` ms, GC `11.905`, RSS `75104256` | `88.164` ms, GC `0.000`, RSS `83345408` | `85.362` ms, GC `0.000`, RSS `83263488` |
| `append-count-by-key` | `114.143` ms, GC `15.091`, RSS `146604032` | `104.813` ms, GC `0.000`, RSS `83410944` | `102.504` ms, GC `0.000`, RSS `83279872` |

DSPBench Fraud q2 application control:

```sh
DSPBENCH_BUILD=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-page-token-no-length-1m-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

| Mode | Median elapsed | Median GC | RSS | Output count |
|---|---:|---:|---:|---:|
| `heap-immix` | `842.739` ms | `72.387` | `358252544` | `594182` |
| `rift-trusted-streaming` | `832.012` ms | `11.175` | `282443776` | `594182` |
| `rift-checked-page-token` | `854.207` ms | `12.948` | `278396928` | `594182` |
| `rift-checked-safezone-page-token` | `843.380` ms | `15.245` | `278593536` | `594182` |

Interpretation:

- Removing page-token live-length bookkeeping is semantically justified by the
  operator-owned API and keeps the focused count-by-key row positive: checked
  scoped count-by-key is about `10.2%` faster than heap and cuts RSS by about
  `43%`.
- It is not a large standalone speedup, and it does not make DSPBench Fraud q2
  a representative checked throughput win. In this clean application control,
  checked scoped page-token is essentially tied with heap on elapsed while
  cutting RSS by about `22%` and timed GC by about `57 ms`; trusted Streaming
  remains fastest.
- The next optimization should not keep shaving page-token close/open counters.
  The remaining useful target is generated allocation lowering: remove the
  `allocImpl`/`checkOpen` path from statically proven operator-owned checked
  allocation, or profile a real row that shows a different dominant cost.

## Owned Cursor Link-Clearing Removal

Change:

- Added `StreamAppendCursor.nextOwnedOrNull()`.
- Page-token-owned close callbacks in focused and application page-token rows
  now use `nextOwnedOrNull()` instead of `nextOrNull()`.
- `nextOwnedOrNull()` advances the cursor without clearing each consumed
  record's `appendNext` link. This is only for operator-owned page-token close:
  parent bucket refs are cleared before the callback, callback captures are
  checked, and the child region closes immediately after cleanup.
- Generic `next()` and `nextOrNull()` remain defensive and still clear links.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"` passed `120/120`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `52/52`.
- 20k smoke matched checksums across focused page-token workloads and modes.

1M command:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-cost-nextowned-1m-2026-05-07 \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Workload | Heap same-shape | Trusted Streaming | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|---:|
| `append-only` | `79.786` ms, GC `11.512`, RSS `122699776` | `90.743` ms | `76.808` ms, GC `0.000`, RSS `83361792` | `73.590` ms, GC `0.000`, RSS `83230720` |
| `append-drain` | `87.198` ms, GC `11.807`, RSS `75104256` | `101.074` ms | `86.344` ms, GC `0.000`, RSS `83345408` | `83.997` ms, GC `0.000`, RSS `83247104` |
| `append-aggregate` | `84.703` ms, GC `11.332`, RSS `122699776` | `99.366` ms | `84.421` ms, GC `0.000`, RSS `83345408` | `81.296` ms, GC `0.000`, RSS `83230720` |

Interpretation:

- Avoiding per-record link clearing in the operator-owned cursor is a real but
  modest cleanup. Versus the previous no-length checkpoint, checked scoped
  page-token improved by about `3.5-4.8 ms` across the three focused 1M rows.
- This is the kind of runtime work static epochal safety should remove:
  defensive link cleanup is unnecessary when the parent list is unlinked and
  the whole child region is about to die.
- It does not change the larger conclusion that application rows still have a
  substantial shared traversal/query floor.

## Open Allocation Lowering Probe

Change:

- Added `RiftRegion.OpenStreamingRegion` as an internal marker for
  operator-owned checked child buckets that are known open by construction.
- Added `RiftRegion.allocOpen(...)` and open-region page-token helpers for
  append, map/filter, and count-by-key operators.
- The Scala Native lowering now routes `Classalloc(OpenStreamingRegion)` to
  `RiftRegion.allocUncheckedImpl(...)`, avoiding the generic checked
  `allocImpl/checkOpen` path. Generic public checked allocation remains
  defensive.
- Linker reachability now marks `RiftRegion.allocUncheckedImpl` as reachable
  for open-region class allocation; without that, the lowered dynamic method
  pointer was null.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"` passed `120/120`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `52/52`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed after routing page-token application paths through open allocation.
- 20k focused smoke matched checksums.

Focused 1M commands:

```sh
CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-openalloc-1m-default-2026-05-07 \
zsh sandbox/run_checked_page_token_cost_matrix.sh

CHECKED_PAGE_TOKEN_COST_BUILD=0 \
CHECKED_PAGE_TOKEN_COST_EVENTS=1000000 \
CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS=3 \
CHECKED_PAGE_TOKEN_COST_WARMUPS=1 \
CHECKED_PAGE_TOKEN_COST_WORKLOADS="append-count-by-key" \
CHECKED_PAGE_TOKEN_COST_MODES="heap-same-shape safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token rift-checked-count-by-key rift-checked-safezone-count-by-key" \
CHECKED_PAGE_TOKEN_COST_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-page-token-openalloc-1m-countbykey-2026-05-07 \
zsh sandbox/run_checked_page_token_cost_matrix.sh
```

| Workload | Heap same-shape | Trusted Streaming | Checked page-token | Checked scoped page-token |
|---|---:|---:|---:|---:|
| `append-only` | `76.295` ms, GC `10.917`, RSS `122765312` | `89.424` ms | `76.032` ms, GC `0.000`, RSS `83329024` | `73.632` ms, GC `0.000`, RSS `83230720` |
| `append-drain` | `85.873` ms, GC `11.282`, RSS `75087872` | `98.753` ms | `86.797` ms, GC `0.000`, RSS `83345408` | `83.177` ms, GC `0.000`, RSS `83230720` |
| `append-aggregate` | `84.354` ms, GC `11.789`, RSS `75087872` | `96.409` ms | `84.184` ms, GC `0.000`, RSS `83345408` | `82.198` ms, GC `0.000`, RSS `83247104` |
| `append-count-by-key` | `103.946` ms, GC `14.011`, RSS `146587648` | `109.371` ms | `99.678` ms, GC `1.745`, RSS `83394560` | `100.155` ms, GC `3.604`, RSS `83296256` |
| `append-count-by-key`, count-by-key operator | n/a | n/a | `100.423` ms, GC `0.000`, RSS `83394560` | `95.946` ms, GC `0.000`, RSS `83296256` |

Interpretation:

- Open allocation is correct and removes a real redundant runtime check, but
  it is a modest focused performance change rather than a broad breakthrough.
- The clearest focused improvement is checked scoped count-by-key:
  `95.946 ms` versus the previous `97.860 ms`, and about `7.7%` faster than
  heap while cutting RSS by about `64 MB`.
- Append-only/drain remain modest checked scoped wins over heap; aggregate is
  still within noise of the prior owned-cursor row. This means page-token
  overhead is now dominated more by object construction, append/linking,
  traversal, and query CPU than by `checkOpen` alone.

## Files

- Child matrix: `/Users/siyaoliu/rift/scala-native-rift/sandbox/src/main/scala-next/CheckedPageTokenCostMatrix.scala`
- Runner: `/Users/siyaoliu/rift/scala-native-rift/sandbox/run_checked_page_token_cost_matrix.sh`
- Raw summaries:
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-baseline/summary.tsv`
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-safe-fast-path/summary.tsv`
  - `/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-page-token-cost-1m-diag/summary.tsv`
  - `/Users/siyaoliu/rift/cache/checked-page-token-count-by-key-100k-2026-05-07/summary.tsv`
  - `/Users/siyaoliu/rift/cache/checked-page-token-count-by-key-1m-2026-05-07/summary.tsv`
  - `/Users/siyaoliu/rift/cache/checked-page-token-cost-nextowned-1m-2026-05-07/summary.tsv`
  - `/Users/siyaoliu/rift/cache/checked-page-token-openalloc-1m-default-2026-05-07/summary.tsv`
  - `/Users/siyaoliu/rift/cache/checked-page-token-openalloc-1m-countbykey-2026-05-07/summary.tsv`
