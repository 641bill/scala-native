# LogHub Top Templates Matrix

Date: 2026-05-09
Last updated: 2026-05-09 22:15 CEST

Status: focused top-k/rank candidate matrix with retained-object controls.
This is not a full LogPAI/Drain reproduction. It is a local single-process
benchmark over generated LogHub-shaped data and real HDFS log lines, designed
to decide whether a top-k/template-ranking operator is worth promoting under
the comparison-class rules.

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
- `checked-epoch-retained-no-traverse`: same retained shape using
  `RiftRegion.epoch` over the Rift streaming backend.
- `checked-scoped-epoch-retained-no-traverse`: same retained shape using
  `RiftRegion.epoch` over the SafeZone-backed scoped checked backend.

For file-backed rows, the input is preloaded into primitive arrays before the
timed section. Treat those rows as preloaded real-input memory-management
probes: parsing/file I/O is not the measured work, but the original data
provenance is real HDFS.

## Implementation

- Matrix: `sandbox/src/main/scala-next/LogHubTopTemplatesMatrix.scala`
- Runner: `sandbox/run_loghub_top_templates_matrix.sh`

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

## Decision

LogHub top templates advances from "candidate" to "promising retained top-k
operator shape." The next implementation step should not be DEBS ranking.
Instead, design a reusable checked top-k/template-ranking API around this
retained epoch shape, then rerun the same `heap-retained-drop-anchor` controls.
