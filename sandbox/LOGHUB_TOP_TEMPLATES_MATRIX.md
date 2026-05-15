# LogHub Top Templates Matrix

Date: 2026-05-09
Last updated: 2026-05-15 22:14 CEST

Status: focused top-k/rank candidate matrix with retained-object controls and
the first reusable `EpochTopKByKey` checked API gate. L1 final-clean support
is now implemented and the real HDFS reusable top-k row has an external
timing/RSS representative result. This is not a full LogPAI/Drain
reproduction. It is a local single-process benchmark over generated
LogHub-shaped data and real HDFS log lines, designed to decide whether a
top-k/template-ranking operator is worth promoting under the comparison-class
rules.

## Intent

This matrix is the first concrete follow-up to the operator gate for top-k and
rank-like workloads. The workload materializes ordinary Scala template-token
records, groups them into epoch-sized buckets, computes the top template
buckets per epoch, and then closes/drops the epoch.

The key comparison is retained heap versus retained checked epoch:

- `heap-natural`: heap retains token records and traverses them at close to
  build counts and top-k output.
- `heap-summary-only`: heap updates primitive counts on append and does not
  retain token records. This is a topology/operator lower bound, not a memory
  management claim.
- `heap-retained-drop-anchor`: heap retains token records, updates primitive
  counts on append, and drops the retained bucket anchor at close without
  traversing records. This is the fair retained heap control.
- `checked-epoch-retained-no-traverse`: same retained shape using the Rift
  streaming backend. This is now lowered through
  `RiftRegion.streamingOpenHandle`/`resetOpenHandle` and
  `RiftAllocator.allocateOpenHandle` for Rift-backed checked epochs.
- `checked-epoch-retained-no-traverse-legacy`: old generic checked retained
  stream path using `RiftRegion.streaming` plus `RiftRegion.epoch`/`allocOpen`.
- `checked-scoped-epoch-retained-no-traverse`: same retained shape using
  `RiftRegion.epoch` over the SafeZone-backed scoped checked backend.
- `checked-epoch-topk-retained-no-traverse`: same retained shape using the
  reusable `RiftRegion.EpochTopKByKey` API over the Rift streaming backend.
- `checked-scoped-epoch-topk-retained-no-traverse`: same retained shape using
  `EpochTopKByKey` over the SafeZone-backed scoped checked backend.

For `file-backed` rows, the input is preloaded into primitive arrays before the
timed section. Treat those rows as preloaded real-input memory-management
probes: parsing/file I/O is not the measured work, but the original data
provenance is real HDFS.

`streaming-file` is the true streaming-input mode. It reopens the configured
real files for each benchmark run, reads line records through
`BenchmarkInputSupport.StreamingByteLineSource`, and does not retain parsed
arrays proportional to the full input. It reports `bytes_read` and
`parse_errors` and is classified as `real-streaming-input`.

## Implementation

- Matrix: `sandbox/src/main/scala-next/LogHubTopTemplatesMatrix.scala`
- Runner: `sandbox/run_loghub_top_templates_matrix.sh`
- Reusable checked API:
  - `RiftRegion.epochTopKByKey(keySpace, topK)`
  - `beginEpochTopKByKey(parent, op)`
  - `incrementEpochTopKByKey(parent, op, key)` /
    `addEpochTopKByKey(parent, op, key, amount)`
  - `finishEpochTopKByKey(parent, op)`
  - `epochTopKKey(parent, op, rank)` / `epochTopKCount(parent, op, rank)`

`EpochTopKByKey` keeps only parent-owned primitive counts and top-k scratch
arrays. Ordinary token/template records still live in the checked epoch
region, so close/reclaim does not traverse retained records. The top-k scan is
over the primitive key table, not the retained record graph.

Default configuration:

| Variable | Default |
|---|---:|
| `LOGHUB_TOP_LINES` | `100000` |
| `LOGHUB_TOP_LINES_PER_EPOCH` | `25000` |
| `LOGHUB_TOP_TEMPLATE_BUCKETS` | `8192` |
| `LOGHUB_TOP_TEMPLATE_TOKEN_LIMIT` | `24` |
| `LOGHUB_TOP_K` | `32` |
| `LOGHUB_TOP_WARMUPS` | `1` |
| `LOGHUB_TOP_BENCHMARK_RUNS` | `3` |

## Validation

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Native link:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
  sbt "project sandbox3_next" \
      "set Compile / mainClass := Some(\"LogHubTopTemplatesMatrix\")" \
      nativeLink
```

Generated 20k smoke:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=20000 \
LOGHUB_TOP_LINES_PER_EPOCH=5000 \
LOGHUB_TOP_BENCHMARK_RUNS=1 \
LOGHUB_TOP_WARMUPS=0 \
LOGHUB_TOP_MODES="heap-natural heap-summary-only heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-smoke-rss \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All smoke modes matched checksum `2679852081608533090` and output count `128`.

Real HDFS 20k smoke:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=file-backed \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=20000 \
LOGHUB_TOP_LINES_PER_EPOCH=5000 \
LOGHUB_TOP_BENCHMARK_RUNS=1 \
LOGHUB_TOP_WARMUPS=0 \
LOGHUB_TOP_MODES="heap-natural heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-real-smoke \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All real-smoke modes matched checksum `8243024414237720723` and output count
`128`.

Real HDFS streaming-file 20k smoke:

```sh
LOGHUB_TOP_BUILD=1 \
LOGHUB_TOP_INPUT_MODE=streaming-file \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=20000 \
LOGHUB_TOP_LINES_PER_EPOCH=5000 \
LOGHUB_TOP_BENCHMARK_RUNS=1 \
LOGHUB_TOP_WARMUPS=0 \
LOGHUB_TOP_MODES="heap-natural heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-loghub-streaming-smoke \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All streaming smoke modes matched checksum `8243024414237720723`, output count
`128`, loaded `20000` real HDFS log lines, and read `3145728` buffered source
bytes from one input file. The checked scoped top-k row used about `10.4 MB`
max RSS versus heap retained at about `11.7 MB`; this is validation only, not
a headline row.

Reusable top-k generated 20k smoke:

```sh
LOGHUB_TOP_BUILD=1 \
LOGHUB_TOP_LINES=20000 \
LOGHUB_TOP_LINES_PER_EPOCH=5000 \
LOGHUB_TOP_BENCHMARK_RUNS=1 \
LOGHUB_TOP_WARMUPS=0 \
LOGHUB_TOP_MODES="heap-natural heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse checked-epoch-topk-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-topk-smoke \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All reusable top-k smoke modes matched checksum `2679852081608533090` and
output count `128`.

## Generated 100k Gate

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=100000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-natural heap-summary-only heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-100k \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `heap-natural` | natural heap baseline | `29.952` | `0.000` | `24.799` | `1/3` | `146817024` | `128` |
| `heap-summary-only` | summary-only topology | `1.066` | `0.000` | `0.000` | `0/3` | `75366400` | `128` |
| `heap-retained-drop-anchor` | retained heap control | `31.139` | `0.000` | `23.549` | `1/3` | `146817024` | `128` |
| `checked-epoch-retained-no-traverse` | checked retained stream | `30.161` | `0.000` | `0.000` | `0/3` | `90980352` | `128` |
| `checked-scoped-epoch-retained-no-traverse` | checked retained scoped | `29.065` | `0.000` | `0.000` | `0/3` | `91013120` | `128` |

Interpretation: the 100k row has the desired retained-control shape, but it is
too short for a headline. Checked scoped is slightly faster than retained heap
and much lower RSS; heap's GC appears only as an outlier.

## 2026-05-15 Retained Stream Handle-Backed Allocation Gate

This gate tests the remaining generic allocation-path audit for the
benchmark-local retained epoch stream path. It does not change the reusable
`EpochTopKByKey` scoped rows, which remain the presentation-facing LogHub
top-template API result.

Generated/preloaded 1M command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="checked-epoch-retained-no-traverse-legacy checked-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/private/tmp/loghub-retained-handle-1m \
zsh sandbox/run_loghub_top_templates_matrix.sh
```

| Input | Mode | Median ms | Median GC ms | Rift op ms | Objects | Opens/Closes/Resets | Checksum | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| generated/preloaded 1M | `checked-epoch-retained-no-traverse-legacy` | 291.519 | 0.000 | 1.371 | 15509744 | 1/1/40 | -761408849270161430 | 1280 |
| generated/preloaded 1M | `checked-epoch-retained-no-traverse` | 269.309 | 0.000 | 1.323 | 15509744 | 1/1/40 | -761408849270161430 | 1280 |

Interpretation: handle-backed allocation improves the generated retained
stream row by `7.6%` over the legacy generic checked allocation path, with the
same checksum, object count, and open/close/reset shape. This is a positive
allocation-lowering transfer gate, but it is still benchmark-local retained
epoch evidence rather than the reusable top-k API result.

Real HDFS streaming-file 100k sanity command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=streaming-file \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=100000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="checked-epoch-retained-no-traverse-legacy checked-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/private/tmp/loghub-retained-handle-hdfs-stream-100k \
zsh sandbox/run_loghub_top_templates_matrix.sh
```

| Input | Mode | Median ms | Median GC ms | Rift op ms | Objects | Opens/Closes/Resets | Checksum | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| real HDFS streaming-file 100k | `checked-epoch-retained-no-traverse-legacy` | 273.834 | 0.000 | 0.056 | 407067 | 1/1/4 | -8128686789696942939 | 128 |
| real HDFS streaming-file 100k | `checked-epoch-retained-no-traverse` | 275.054 | 0.000 | 0.049 | 407067 | 1/1/4 | -8128686789696942939 | 128 |

Interpretation: the same allocation-lowering change is neutral on the small
real HDFS streaming-file sanity row because source parsing and template hashing
dominate at this scale. Keep the existing scoped/top-k HDFS streaming rows as
the real-input evidence; use this row only as an allocation-path transfer
sanity check.

## Generated 1M Gate

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-natural heap-summary-only heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-1m \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `-761408849270161430` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-natural` | natural heap baseline | `417.541` | `126.052` | `172.380` | `3/3` | `289112064` |
| `heap-summary-only` | summary-only topology | `10.563` | `0.000` | `0.000` | `0/3` | `288849920` |
| `heap-retained-drop-anchor` | retained heap control | `424.443` | `126.371` | `152.546` | `3/3` | `407633920` |
| `checked-epoch-retained-no-traverse` | checked retained stream | `300.826` | `0.000` | `0.000` | `0/3` | `304480256` |
| `checked-scoped-epoch-retained-no-traverse` | checked retained scoped | `290.610` | `0.000` | `0.000` | `0/3` | `304480256` |

Interpretation:

- This is a retained-object memory-management win for the generated
  top-template stressor. Checked scoped retained epoch is `31.5%` faster than
  retained heap and cuts RSS by about `25%`.
- The `heap-summary-only` row is the topology/operator lower bound and must
  not be used as a memory-management claim.
- This is a good candidate shape for a reusable top-k/rank operator, but the
  current implementation is still a focused matrix, not a public operator API.

## Reusable EpochTopKByKey 1M Gate

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse checked-epoch-topk-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-topk-1m-rss \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `-761408849270161430` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-retained-drop-anchor` | retained heap control | `463.578` | `138.050` | `207.089` | `3/3` | `407683072` |
| `checked-epoch-retained-no-traverse` | benchmark-local checked retained stream | `320.091` | `0.000` | `0.000` | `0/3` | `304545792` |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `300.984` | `0.000` | `0.000` | `0/3` | `304594944` |
| `checked-epoch-topk-retained-no-traverse` | reusable checked top-k stream | `353.049` | `0.000` | `0.000` | `0/3` | `304545792` |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `341.905` | `0.000` | `0.000` | `0/3` | `304611328` |

Interpretation:

- `EpochTopKByKey` passes the retained-object API gate on the generated
  stressor: checked scoped top-k is `26.3%` faster than retained heap, removes
  `138.050 ms` median timed GC, and cuts RSS by about `25%`.
- The benchmark-local manual count-array path is still faster
  (`300.984 ms` scoped versus `341.905 ms` reusable scoped). Treat this as a
  passed reusable API with measurable abstraction overhead, not as the final
  top-k lower bound.
- The retained-object memory-management claim remains valid because both
  reusable top-k rows retain ordinary records until epoch close and do not
  traverse them at close.

Hot-path follow-up, 2026-05-10:

`incrementEpochTopKByKey` now updates the parent-owned primitive count array
directly instead of delegating through `addEpochTopKByKey`; `add` still relies
on the array bounds check for invalid keys. This removes a duplicate method hop
and duplicate hot-path validation from the common increment case.

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_INPUT_MODE=generated \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-epoch-topk-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-loghub-topk-hotpath-1m-bc9fd5979 \
zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `-761408849270161430` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-retained-drop-anchor` | retained heap control | `397.788` | `126.601` | `179.193` | `3/3` | `407683072` |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `260.130` | `0.000` | `0.000` | `0/3` | `304611328` |
| `checked-epoch-topk-retained-no-traverse` | reusable checked top-k stream | `288.601` | `0.000` | `0.000` | `0/3` | `304545792` |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `274.914` | `0.000` | `0.000` | `0/3` | `304578560` |

Interpretation: the reusable scoped top-k API remains faster than retained
heap and now trails the benchmark-local checked scoped lower bound by about
`5.7%` in this same-run generated gate. This is an overhead-reduction result,
not a new benchmark-local algorithm.

## Real HDFS 1M Gate

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=file-backed \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-natural heap-summary-only heap-retained-drop-anchor checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-hdfs-1m \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `4142347521733569598` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-natural` | natural heap baseline | `107.677` | `25.918` | `26.580` | `3/3` | `145981440` |
| `heap-summary-only` | summary-only topology | `11.132` | `0.000` | `0.000` | `0/3` | `145768448` |
| `heap-retained-drop-anchor` | retained heap control | `116.138` | `31.161` | `31.508` | `3/3` | `145932288` |
| `checked-epoch-retained-no-traverse` | checked retained stream | `85.357` | `0.000` | `0.000` | `0/3` | `149962752` |
| `checked-scoped-epoch-retained-no-traverse` | checked retained scoped | `81.174` | `0.000` | `0.000` | `0/3` | `150011904` |

Interpretation:

- This is a real-input retained top-k win under the preloaded-input protocol.
  Checked scoped retained epoch is `30.1%` faster than retained heap and
  removes `31.161 ms` median timed GC.
- RSS is slightly higher for checked retained rows, so the claim is
  throughput/GC, not RSS.
- Parser/file I/O is excluded from the timed section. This is intentional for
  a memory-management/operator probe, but the report should label it
  `real-preloaded`, not `real-file-backed` headline evidence.

Reusable top-k real HDFS 1M gate:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=file-backed \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/loghub-top-templates-hdfs-topk-1m \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `4142347521733569598` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-retained-drop-anchor` | retained heap control | `123.024` | `33.966` | `36.715` | `3/3` | `146046976` |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `83.697` | `0.000` | `0.000` | `0/3` | `150028288` |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `95.267` | `0.000` | `0.000` | `0/3` | `150061056` |

Interpretation:

- `EpochTopKByKey` also passes on real-preloaded HDFS: the reusable checked
  scoped row is `22.6%` faster than retained heap and removes `33.966 ms`
  median timed GC.
- RSS is slightly higher than retained heap, so this is throughput/GC evidence,
  not an RSS win.
- The API overhead caveat remains: the benchmark-local scoped path is
  `83.697 ms`, while the reusable top-k scoped path is `95.267 ms`.

Reusable top-k real HDFS hot-path follow-up:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=file-backed \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-loghub-topk-hotpath-hdfs-1m-bc9fd5979 \
zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `4142347521733569598` and output count `1280`.

| Mode | Class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-retained-drop-anchor` | retained heap control | `111.704` | `30.542` | `32.272` | `3/3` | `146079744` |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `76.818` | `0.000` | `0.000` | `0/3` | `150077440` |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `82.170` | `0.000` | `0.000` | `0/3` | `150077440` |

Interpretation: on real-preloaded HDFS, the reusable scoped top-k row is now
`26.4%` faster than retained heap and removes `30.542 ms` median timed GC. It
trails the benchmark-local checked scoped lower bound by about `7.0%`, down
from the previous recorded `13.8%` caveat.

## Real HDFS L1 Final-Clean Top-K Row

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
for i in 1 2 3; do
  RIFT_FINAL_CLEAN=1 \
  LOGHUB_TOP_BUILD=0 \
  LOGHUB_TOP_INPUT_MODE=file-backed \
  LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
  LOGHUB_TOP_LINES=1000000 \
  LOGHUB_TOP_LINES_PER_EPOCH=25000 \
  LOGHUB_TOP_BENCHMARK_RUNS=20 \
  LOGHUB_TOP_WARMUPS=0 \
  LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
  LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-l1-loghub-top-hdfs-1m-x20-3598efe29-r${i} \
  zsh sandbox/run_loghub_top_templates_matrix.sh
done
```

Configuration:

- child commit: `3598efe29`;
- measurement level: L1 final-clean, external `/usr/bin/time -l`;
- input: real HDFS log file, loaded into primitive arrays before the 20 replay
  iterations;
- loaded events: 1,000,000;
- epoch size: 25,000 lines;
- top-k: 32;
- checksum: `4142347521733569598`;
- output count: `1280`.

| Mode | Class | Median real s | Min real s | Max real s | Median RSS bytes | Claim |
|---|---|---:|---:|---:|---:|---|
| `heap-retained-drop-anchor` | retained heap control | `5.46` | `5.43` | `5.47` | `205406208` | L1 retained heap/drop-anchor control. |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `4.84` | `4.82` | `4.88` | `28262400` | L1 checked retained win over heap retained; benchmark-local lower bound. |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `5.05` | `5.05` | `5.08` | `28114944` | L1 reusable checked top-k win over heap retained with much lower RSS. |

Interpretation:

- The reusable `EpochTopKByKey` row is `7.5%` faster than retained heap on L1
  total process time and cuts RSS by about `86%`.
- The benchmark-local checked retained path is still faster (`4.84 s`), so the
  operator API retains a measurable abstraction cost.
- This is a real-input, preloaded/replayed memory-management row: the external
  process includes one HDFS input load plus 20 replay iterations. Use the L2
  rows above for GC interpretation.

Hot-path L1 follow-up, same input and protocol:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
for i in 1 2 3; do
  RIFT_FINAL_CLEAN=1 \
  LOGHUB_TOP_BUILD=0 \
  LOGHUB_TOP_INPUT_MODE=file-backed \
  LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
  LOGHUB_TOP_LINES=1000000 \
  LOGHUB_TOP_LINES_PER_EPOCH=25000 \
  LOGHUB_TOP_BENCHMARK_RUNS=20 \
  LOGHUB_TOP_WARMUPS=0 \
  LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
  LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-l1-loghub-topk-hotpath-hdfs-1m-x20-bc9fd5979-r${i} \
  zsh sandbox/run_loghub_top_templates_matrix.sh
done
```

| Mode | Class | Median real s | Min real s | Max real s | Median RSS bytes | Claim |
|---|---|---:|---:|---:|---:|---|
| `heap-retained-drop-anchor` | retained heap control | `5.52` | `5.50` | `5.52` | `205406208` | L1 retained heap/drop-anchor control. |
| `checked-scoped-epoch-retained-no-traverse` | benchmark-local checked retained scoped | `4.80` | `4.73` | `4.81` | `28262400` | L1 checked retained lower bound over heap retained. |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped | `4.88` | `4.87` | `4.94` | `28098560` | L1 reusable checked top-k win over heap retained with much lower RSS. |

Interpretation: after the hot-path change, the reusable `EpochTopKByKey` L1
row is `11.6%` faster than retained heap and cuts RSS by about `86%`. The
report-facing API overhead versus the benchmark-local checked retained lower
bound is now about `1.7%` (`4.88 s` versus `4.80 s`), so the previous overhead
caveat is materially reduced.

## Real HDFS Streaming-File 1M Gate

Command:

```sh
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=streaming-file \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=1 \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-loghub-streaming-hdfs-1m-l2 \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

L1 command:

```sh
RIFT_FINAL_CLEAN=1 \
LOGHUB_TOP_BUILD=0 \
LOGHUB_TOP_INPUT_MODE=streaming-file \
LOGHUB_TOP_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_TOP_LINES=1000000 \
LOGHUB_TOP_LINES_PER_EPOCH=25000 \
LOGHUB_TOP_BENCHMARK_RUNS=3 \
LOGHUB_TOP_WARMUPS=0 \
LOGHUB_TOP_MODES="heap-retained-drop-anchor checked-scoped-epoch-retained-no-traverse checked-scoped-epoch-topk-retained-no-traverse" \
LOGHUB_TOP_OUTPUT_DIR=/tmp/rift-loghub-streaming-hdfs-1m-l1 \
  zsh sandbox/run_loghub_top_templates_matrix.sh
```

All modes matched checksum `4142347521733569598`, output count `1280`,
loaded `1000000` real HDFS log lines, and read `141557760` buffered source
bytes from one input file.

| Mode | Class | L1 external s | L1 RSS bytes | L2 median ms | Median GC ms | Runs with GC |
|---|---|---:|---:|---:|---:|---:|
| `heap-retained-drop-anchor` | retained heap streaming control | `8.10` | `75595776` | `2765.068` | `32.681` | `3/3` |
| `checked-scoped-epoch-retained-no-traverse` | checked retained scoped streaming | `8.02` | `12189696` | `2672.825` | `0.000` | `0/3` |
| `checked-scoped-epoch-topk-retained-no-traverse` | reusable checked top-k scoped streaming | `8.06` | `12173312` | `2697.653` | `0.000` | `0/3` |

Interpretation:

- This is the first `real-streaming-input` LogHub row: the HDFS file is read
  inside each benchmark run through the streaming source and is not preloaded
  into parsed arrays.
- The reusable checked scoped top-k row is a near-tie/slight L1 throughput win
  over retained heap and cuts RSS sharply while removing timed heap GC.
- The benchmark-local checked scoped retained row is slightly faster than the
  reusable top-k row, so the existing API-overhead caveat still applies in the
  full streaming loop.
- Heap GC is still modest relative to the full streaming parse/query loop, so
  this is retained-object/RSS streaming evidence rather than the missing
  flagship GC-heavy streaming result.

## Decision

LogHub top templates advances from "candidate" to a passed reusable checked
top-k API gate with an overhead caveat. `EpochTopKByKey` beats retained heap on
generated and real-preloaded HDFS rows while preserving the retained-object
comparison. After the increment hot-path follow-up, the benchmark-local manual
path remains slightly faster, but the L1 API overhead on real HDFS is down to
about `1.7%`. The new `streaming-file` row moves the same retained/top-k shape
into true streaming-input evidence. DEBS ranking and generic mutable rank
remain gated.
