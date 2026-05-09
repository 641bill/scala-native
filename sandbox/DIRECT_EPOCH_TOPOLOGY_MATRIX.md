# Direct Epoch Topology Matrix

Date: 2026-05-08
Last updated: 2026-05-09 19:45 CEST

Status: clean post-checkpoint sweep for reusable `RiftRegion.epoch { ... }`
topology. Parent repo commit: `b462db8`. Child repo commit: `c71e56ae0`.

Raw logs are under:

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

10M replayed real edges:

| Mode | Topology | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---|---:|---:|---:|---:|
| gc-heap | heap | 315.164 | 51.113 | 0.000 | 605536256 |
| region-scoped-rooted | scoped epoch | 247.730 | 0.000 | 0.000 | 328646656 |
| region-stream-rootless | streaming epoch | 256.790 | 0.000 | 0.532 | 328531968 |
| checked-whole-run-scoped | whole run | 215.099 | 0.000 | 0.000 | 617201664 |
| checked-epoch-scoped | direct epoch | 214.587 | 0.000 | 0.000 | 328646656 |
| checked-page-token-scoped | page-token | 273.415 | 0.000 | 0.000 | 328646656 |
| checked-epoch-buffer-scoped | EpochBuffer | 278.581 | 0.000 | 0.000 | 328613888 |
| checked-epoch-stream | direct epoch | 213.781 | 0.000 | 0.588 | 328548352 |

50M replayed real edges:

| Mode | Topology | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---|---:|---:|---:|---:|
| gc-heap | heap | 1612.834 | 274.756 | 0.000 | 2761310208 |
| region-scoped-rooted | scoped epoch | 1199.260 | 0.000 | 0.000 | 1534984192 |
| region-stream-rootless | streaming epoch | 1315.363 | 0.000 | 23.181 | 1534738432 |
| checked-whole-run-scoped | whole run | 1028.817 | 0.000 | 0.000 | 2977824768 |
| checked-epoch-scoped | direct epoch | 1030.943 | 0.000 | 0.000 | 1535016960 |
| checked-page-token-scoped | page-token | 1333.360 | 0.000 | 0.000 | 1535033344 |
| checked-epoch-buffer-scoped | EpochBuffer | 1349.152 | 0.000 | 0.000 | 1535049728 |
| checked-epoch-stream | direct epoch | 1083.050 | 0.000 | 23.113 | 1534754816 |

Interpretation: whole-run checked scoped is slightly faster, but it keeps all
records live and uses about `2.98 GB` RSS. Direct epoch preserves the low-RSS
shape at about `1.53 GB` and still beats heap by about 36%. Page-token and
EpochBuffer beat heap but are the wrong topology for this graph replay.

### Dataflow SELECT / AGGREGATE / JOIN

Configuration: 10 epochs x 100k documents.

| Operator | Heap ms / GC ms | Region scoped rooted ms | Checked epoch scoped ms | Checked epoch stream ms | Control |
|---|---:|---:|---:|---:|---|
| SELECT | 26.686 / 6.300 | 22.161 | 18.483 | 20.963 | scoped page-token SELECT 17.946 |
| AGGREGATE | 49.611 / 12.438 | 39.028 | 33.896 | 36.162 | EpochFold remains gated |
| JOIN | 29.622 / 9.512 | 22.605 | 19.581 | 20.402 | direct epoch covers full operator |

Interpretation: direct checked scoped epoch is the reusable full-family win.
Page-token remains slightly faster for SELECT only, but it does not cover
AGGREGATE or JOIN.

### StreamFlex

1M throughput:

| Mode | Median ms | GC ms | Rift op ms | RSS bytes |
|---|---:|---:|---:|---:|
| heap | 215.097 | 46.023 | 0.000 | 8028160 |
| improved SafeZone | 203.733 | 0.000 | 0.000 | 8142848 |
| Rift Streaming | 178.028 | 0.000 | 0.592 | 8126464 |
| checked direct epoch | 168.915 | 0.000 | 0.591 | 8126464 |
| scoped checked direct epoch | 159.767 | 0.000 | 0.000 | 8159232 |
| scoped checked TransactionRegion | 202.604 | 0.000 | 0.000 | 8175616 |

10k latency:

| Mode | Median ms | p99 ns | max ns | deadline misses |
|---|---:|---:|---:|---:|
| heap | 9.114 | 792 | 281791 | 4 |
| improved SafeZone | 11.786 | 1209 | 254750 | 1 |
| Rift Streaming | 9.057 | 875 | 2917 | 0 |
| checked direct epoch | 9.679 | 917 | 1208 | 0 |
| scoped checked direct epoch | 9.042 | 875 | 6084 | 0 |
| scoped checked TransactionRegion | 12.640 | 1209 | 6584 | 0 |

Interpretation: direct checked epoch supersedes `TransactionRegion` for this
pipeline shape. `TransactionRegion` remains a useful multi-list control, but
the simpler direct epoch is faster when all temporary stage objects share the
same batch lifetime.

### Stancu-Style Transactions

Configuration: 1M transactions, 8 items/transaction, 64 transactions/region.

| Mode | Median ms | GC ms | Rift op ms | Logical region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|
| heap | 222.415 | 23.573 | 0.000 | 9000000 | 7929856 |
| improved SafeZone | 180.912 | 0.000 | 0.000 | 9000000 | 7995392 |
| Rift Streaming | 214.052 | 0.000 | 0.991 | 9000000 | 7946240 |
| checked direct epoch | 168.617 | 0.000 | 1.032 | 9000000 | 7946240 |
| scoped checked direct epoch | 155.992 | 0.311 | 0.000 | 9000000 | 8028160 |

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

Focused 1M retained epoch:

| Mode | Evidence class | Median ms | Median GC ms | RSS bytes |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | topology lower bound | `11.041` | `0.000` | `3801088` |
| `heap-epoch-retained-no-traverse` | retained heap control | `34.405` | `9.646` | `21331968` |
| `checked-epoch-retained-no-traverse` | retained checked region | `25.366` | `0.000` | `4816896` |
| `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `23.999` | `0.000` | `4866048` |

DSPBench Fraud q2 1M retained controls:

| Mode | Evidence class | Median ms | Median GC ms | RSS bytes |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | topology lower bound | `279.103` | `0.000` | `575668224` |
| `heap-epoch-retained-no-traverse` | retained heap control | `387.943` | `35.797` | `575979520` |
| `checked-epoch-retained-no-traverse` | retained checked region | `373.837` | `0.000` | `582729728` |
| `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `364.535` | `0.000` | `582746112` |

Interpretation: summary-only direct epoch remains topology evidence. Retained
heap versus retained checked epoch is now the fair memory-management evidence:
checked scoped retained epoch is `30.2%` faster than retained heap in the
focused matrix, and `6.0%` faster on DSPBench Fraud q2 while removing heap GC.
Fraud q2 keeps similar/slightly higher RSS in checked retained rows, so that
row is a throughput/GC win rather than an RSS win.

Additional 1M retained application rows:

| Benchmark/query | Retained heap ms / GC ms / RSS | Checked retained stream ms / GC ms / RSS | Checked retained scoped ms / GC ms / RSS | Interpretation |
|---|---:|---:|---:|---|
| GH Archive-shaped q2 | 244.988 / 69.552 / 147 MB | 191.064 / 0.000 / 15 MB | 181.345 / 0.000 / 15 MB | strong retained memory/RSS win |
| LogHub-shaped q2 | 464.389 / 64.349 / 813 MB | 413.203 / 0.000 / 305 MB | 402.611 / 0.000 / 694 MB | retained throughput/GC win; stream backend has better RSS |
| LogHub-shaped q3 | 2217.322 / 146.514 / 2291 MB | 2213.846 / 43.315 / 834 MB | 2101.599 / 0.000 / 2312 MB | mixed; scoped improves elapsed, stream improves RSS |

## Conclusions

- Direct checked epoch is now a reusable positive operator/topology, not a
  benchmark-specific trick.
- The strongest clean row is real-input Yak LiveJournal: checked epoch removes
  timed heap GC and cuts RSS roughly from `2.76 GB` to `1.53 GB` at 50M replayed
  edges.
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
