# Direct Epoch Topology Matrix

Date: 2026-05-08
Last updated: 2026-05-08 22:00 CEST

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
