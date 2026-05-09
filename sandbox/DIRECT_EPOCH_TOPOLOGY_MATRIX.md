# Direct Epoch Topology Matrix

Date: 2026-05-08
Last updated: 2026-05-09 21:15 CEST

Status: clean committed representative rerun for reusable
`RiftRegion.epoch { ... }` topology. The latest direct-epoch representative
rows were run after child commit `918c7d4c1` and parent commit `ab570b1`.
Earlier local-Yak and generated q2/q3 direct-aggregate rows remain in this file
as clean topology/operator evidence from prior checkpoints.

Latest clean-run summaries:

- Yak LiveJournal:
  `/Users/siyaoliu/rift/cache/yak-livejournal-direct-clean-2026-05-09/`
- Dataflow:
  `/Users/siyaoliu/rift/cache/dataflow-direct-clean-2026-05-09/1m/summary.tsv`
- StreamFlex:
  `/Users/siyaoliu/rift/cache/streamflex-direct-clean-2026-05-09/`
- Stancu:
  `/Users/siyaoliu/rift/cache/stancu-direct-clean-2026-05-09/1m/summary.tsv`

Earlier broad direct-epoch logs:

`/Users/siyaoliu/rift/cache/direct-epoch-sweep-2026-05-08/`

## Purpose

This matrix consolidates the direct checked epoch result across workloads where
the lifetime topology is naturally epoch/batch/transaction scoped. It compares
direct epoch against heap, rooted scoped regions, rootless streaming regions,
and older page-token/buffer controls where those controls explain topology
choice.

Direct epoch is the right shape when ordinary Scala objects are created,
consumed, and discarded as one batch/epoch. Page-token remains the right shape
for page/window streams.

Important evidence split:

- Summary-only direct-epoch rows are topology/operator evidence. They update
  primitive summaries on append and do not retain ordinary records until close.
- Retained no-traverse rows are the fair memory-management control. Heap and
  checked regions both materialize ordinary Scala records and retain them until
  epoch close, but neither traverses records at close. The intended difference
  is heap GC reclaim versus region bulk close.

## Validation

Before the clean sweep:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results:

- sandbox compile: passed;
- compiler tests: `123/123`;
- runtime tests: `57/57`;
- parent and child `git diff --check`: passed before checkpoint commits.

## Key Results

### Yak Local Workloads

Default local Yak rows use 20 epochs and the suite-specific default object
counts.

| Workload | Heap ms / GC ms | Region scoped rooted ms | Checked epoch scoped ms | Checked epoch stream ms | Interpretation |
|---|---:|---:|---:|---:|---|
| wordcount | 46.951 / 7.669 | 37.081 | 31.774 | 34.769 | checked scoped epoch is fastest |
| graphstep | 58.461 / 14.678 | 40.800 | 36.144 | 37.969 | checked scoped epoch is fastest |
| topword | 70.156 / 12.114 | 57.539 | 52.987 | 55.054 | checked scoped epoch is fastest |
| graphchi | 43.841 / 4.866 | 36.058 | 31.387 | 33.343 | checked scoped epoch is fastest |
| sort | 78.274 / 3.159 | 74.769 | 74.784 | 74.570 | modest CPU-bound coverage win |

All checksums matched per workload.

### Yak Real Graph Replay

Input: SNAP LiveJournal
`/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz`.

Clean committed 10M replayed real edges:

| Mode | Topology | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---|---:|---:|---:|---:|
| gc-heap | heap | 599.428 | 94.371 | 0.000 | 605732864 |
| region-scoped-rooted | scoped epoch | 496.827 | 0.000 | 0.000 | 459276288 |
| region-stream-rootless | streaming epoch | 521.498 | 0.000 | 1.636 | 459161600 |
| checked-epoch-scoped | direct epoch | 417.426 | 0.000 | 0.000 | 459292672 |
| checked-epoch-stream | direct epoch | 425.983 | 0.000 | 1.392 | 459161600 |
| checked-page-token-scoped | page-token | 539.450 | 0.000 | 0.000 | 459276288 |
| checked-page-token-stream | page-token | 569.829 | 0.000 | 1.491 | 459243520 |

Clean committed 50M replayed real edges:

| Mode | Topology | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---|---:|---:|---:|---:|
| gc-heap | heap | 2958.659 | 400.484 | 0.000 | 3913564160 |
| region-scoped-rooted | scoped epoch | 2351.582 | 0.000 | 0.000 | 2105999360 |
| region-stream-rootless | streaming epoch | 2499.975 | 0.000 | 40.260 | 2105769984 |
| checked-epoch-scoped | direct epoch | 2008.320 | 0.000 | 0.000 | 2105999360 |
| checked-epoch-stream | direct epoch | 2064.867 | 0.000 | 41.568 | 2105786368 |
| checked-page-token-scoped | page-token | 2586.919 | 0.000 | 0.000 | 2105966592 |
| checked-page-token-stream | page-token | 2746.897 | 0.000 | 43.983 | 2105819136 |

Interpretation: checked direct epoch is the right low-overhead topology for
Yak-style real graph replay. At 50M, checked scoped epoch is `32.1%` faster
than heap, removes `400.484 ms` median timed GC, and cuts RSS from about
`3.91 GB` to about `2.11 GB`. Page-token still beats heap at 50M, but it is
the wrong topology for this workload and leaves too much page/window operator
overhead.

### Dataflow SELECT / AGGREGATE / JOIN

Configuration: 10 epochs x 100k documents.

| Operator | Heap ms / GC ms | Region scoped rooted ms | Checked epoch scoped ms | Checked epoch stream ms | Control |
|---|---:|---:|---:|---:|---|
| SELECT | 27.775 / 6.828 | 22.971 | 19.691 | 20.091 | scoped page-token SELECT remains a SELECT-only control |
| AGGREGATE | 50.837 / 11.564 | 39.665 | 34.676 | 37.328 | EpochFold remains gated |
| JOIN | 30.387 / 9.331 | 22.619 | 19.762 | 20.401 | direct epoch covers full operator |

Interpretation: direct checked scoped epoch is the reusable full-family win.
Page-token remains slightly faster for SELECT only, but it does not cover
AGGREGATE or JOIN.

### StreamFlex

1M throughput:

| Mode | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---:|---:|---:|---:|
| heap | 216.853 | 46.036 | 0.000 | 8028160 |
| improved SafeZone | 208.837 | 0.000 | 0.000 | 8142848 |
| Rift Streaming | 186.029 | 0.000 | 0.600 | 8142848 |
| checked direct epoch | 174.957 | 0.000 | 0.595 | 8142848 |
| scoped checked direct epoch | 157.334 | 0.000 | 0.000 | 8159232 |

200k latency:

| Mode | Median ms | GC ms | p50 ns | p99 ns | p999 ns | max ns | deadline misses |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 200.924 | 20.653 | 708 | 959 | 2167 | 1065125 | 21 |
| improved SafeZone | 226.854 | 1.700 | 917 | 1208 | 2125 | 727959 | 1 |
| Rift Streaming | 198.660 | 0.655 | 833 | 1083 | 1250 | 25666 | 0 |
| checked direct epoch | 195.931 | 1.743 | 792 | 1083 | 1583 | 700250 | 1 |
| scoped checked direct epoch | 192.557 | 1.570 | 750 | 1000 | 2292 | 887875 | 1 |

Interpretation: direct checked epoch supersedes `TransactionRegion` for this
pipeline shape. `TransactionRegion` remains a useful multi-list control, but
the simpler direct epoch is faster when all temporary stage objects share the
same batch lifetime.

### Stancu-Style Transactions

Configuration: 1M transactions, 8 items/transaction, 64 transactions/region.

| Mode | Median ms | GC ms | Rift op ms | Logical region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| heap | 220.951 | 22.819 | 0.000 | 9000000 | 7929856 |
| improved SafeZone | 183.365 | 0.358 | 0.000 | 9000000 | 7979008 |
| Rift Streaming | 218.327 | 0.000 | 1.041 | 9000000 | 7929856 |
| checked direct epoch | 165.116 | 0.000 | 1.152 | 9000000 | 7929856 |
| scoped checked direct epoch | 155.863 | 0.381 | 0.000 | 9000000 | 7979008 |

Interpretation: this turns the local Stancu-shaped transaction row into a
checked direct-epoch win. It remains a local methodology probe; the stronger
SPECjbb2005-workload port is tracked separately.

### Generated Stream q2/q3 Direct-Epoch Follow-Up

After the first direct-epoch sweep, direct-epoch modes were also wired into
generated/indexable stream benchmark rows where bucket summaries are independent
of later sliding-window state:

- DSPBench `fraud-q2-alert-window`;
- DSPBench `log-q2-window`;
- GH Archive-shaped `q2-repo-window`;
- LogHub-shaped `q2-window-counts`;
- LogHub-shaped `q3-template-session`.

These rows use direct epochs to allocate ordinary event/token/alert objects
inside the bucket epoch, then keep only primitive bucket-summary metadata until
the original close point. This preserves checksum ordering while removing
page-token append-window traversal.

On 2026-05-09, a `heap-direct-epoch` same-shape control was added. It uses the
same direct-epoch aggregate algorithm with ordinary heap `new`, so it separates
operator/topology wins from region-allocation wins.

1M generated/indexable rows, 3-run medians:

| Benchmark/query | Heap ms / GC ms | Heap same-shape direct ms / GC ms | Checked direct epoch stream ms / GC ms | Checked direct epoch scoped ms / GC ms | Interpretation |
|---|---:|---:|---:|---:|---|
| DSPBench Fraud q2 | 400.900 / 49.663 | 272.251 / 0.000 | 268.907 / 0.000 | 267.739 / 0.000 | Most of the win is direct-epoch aggregate topology; checked regions still beat same-shape heap slightly. |
| DSPBench Log q2 | 372.174 / 43.983 | 227.036 / 10.019 | 228.960 / 9.841 | 230.374 / 9.995 | Direct topology dominates; checked regions are roughly tied with same-shape heap. |
| GH Archive-shaped q2 | 287.380 / 84.904 | 54.642 / 0.000 | 56.167 / 0.000 | 56.013 / 0.000 | This is almost entirely an aggregate-topology win; same-shape heap is slightly faster than checked regions. |
| LogHub-shaped q2 | 526.803 / 124.142 | 191.601 / 0.000 | 193.441 / 0.000 | 193.938 / 0.000 | Direct topology explains the large win; checked overhead is small but measurable. |
| LogHub-shaped q3 | 2225.364 / 175.674 | 1779.064 / 0.000 | 1784.048 / 0.000 | 1792.397 / 0.000 | Richer generated template/session row still benefits; checked regions are close to same-shape heap. |

Applicability notes:

- The current DSPBench and LogHub direct-epoch modes require generated/indexable
  input. File-backed real rows still use page-token until a preloaded/indexed
  real-input path or streaming bucket iterator is added.
- GH Archive direct epoch works for generated and real-preloaded rows; it
  rejects file-backed rows because file-backed parsing is a sequential reader
  path.
- NEXMark Q3/Q8/Q9/Q11 were intentionally not given direct-epoch rows here:
  their join/window state affects later events, so a bucket cannot be reclaimed
  immediately as an independent epoch without changing semantics. Page-token or
  a checked join/window operator remains the correct topology for those queries.
- These q2/q3 rows are now classified as topology/operator evidence first.
  They show that direct epoch is the right reusable shape, but pure memory
  placement claims should compare checked rows against `heap-direct-epoch`, not
  against the older linked page-token or heap traversal shape.

### Retained-Epoch Reclaim Follow-Up

`evidence/RETAINED_EPOCH_RECLAIM_MATRIX.md` adds the missing retained-object
control. The retained modes keep ordinary Scala records alive until epoch close
and update primitive summaries on append; close touches only the bucket
`head`/`tail` anchors and does not traverse records.

Clean committed focused 1M retained epoch:

| Mode | Evidence class | Median ms | Median GC ms | RSS bytes |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | topology lower bound | `11.211` | `0.000` | `3817472` |
| `heap-epoch-retained-no-traverse` | retained heap control | `36.233` | `10.109` | `21348352` |
| `checked-epoch-retained-no-traverse` | retained checked region | `27.703` | `0.000` | `4833280` |
| `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `24.274` | `0.000` | `4882432` |

DSPBench Fraud q2 1M retained controls:

| Mode | Evidence class | Median ms | Median GC ms | RSS bytes |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | topology lower bound | `286.318` | `0.000` | `575651840` |
| `heap-epoch-retained-no-traverse` | retained heap control | `392.743` | `35.631` | `575963136` |
| `checked-epoch-retained-no-traverse` | retained checked region | `383.628` | `0.000` | `582713344` |
| `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `370.746` | `0.000` | `582746112` |

Interpretation: summary-only direct epoch remains topology evidence. Retained
heap versus retained checked epoch is now the fair memory-management evidence:
checked scoped retained epoch is `33.0%` faster than retained heap in the
focused matrix, and `5.6%` faster on DSPBench Fraud q2 while removing heap GC.
Fraud q2 keeps similar/slightly higher RSS in checked retained rows, so that
row is a throughput/GC win rather than an RSS win.

Additional 1M retained application rows:

| Benchmark/query | Retained heap ms / GC ms / RSS | Checked retained stream ms / GC ms / RSS | Checked retained scoped ms / GC ms / RSS | Interpretation |
|---|---:|---:|---:|---|
| GH Archive-shaped q2 | 257.377 / 77.208 / 147 MB | 194.827 / 0.000 / 15 MB | 186.868 / 0.000 / 15 MB | strong retained memory/RSS win |
| LogHub-shaped q2 | 469.079 / 64.060 / 813 MB | 420.734 / 0.000 / 694 MB | 402.821 / 0.000 / 694 MB | retained throughput/GC win |
| LogHub-shaped q3 | 2244.266 / 143.539 / 2291 MB | 2169.804 / 0.000 / 2312 MB | 2106.541 / 0.000 / 2312 MB | mixed; scoped improves elapsed but not RSS |

## Conclusions

- Direct checked epoch is now a reusable positive operator/topology, not a
  benchmark-specific trick.
- The strongest clean row is real-input Yak LiveJournal: checked epoch removes
  timed heap GC and cuts RSS roughly from `3.91 GB` to `2.11 GB` at 50M
  replayed edges in the latest committed rerun.
- Scoped checked epoch is the best backend for Dataflow, StreamFlex throughput,
  and Stancu-shaped transactions in this sweep.
- Page-token is still the right checked operator for page/window append streams,
  but it is not the right topology for Yak-style graph replay.
- Generic `EpochBuffer` is correct and often beats heap, but direct linked
  epoch is the faster checked lowering for these epoch-shaped workloads.
- Generated stream q2/q3 direct-epoch rows show a promising reusable operator
  family: epoch-local objects plus durable primitive summary metadata. The
  same-shape heap control shows that the large speedups mostly come from the
  topology; checked regions are close to, and sometimes slightly faster than,
  same-shape heap.
- Retained no-traverse controls now show genuine memory-management wins when
  ordinary objects must survive until the epoch boundary: region close removes
  heap GC work without requiring a close-time object traversal.
