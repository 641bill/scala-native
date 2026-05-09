# Retained Epoch Reclaim Matrix

Date: 2026-05-09
Last updated: 2026-05-09 19:57 CEST

Status: implemented focused retained-epoch controls plus generated/indexable
application rows for DSPBench Fraud q2, GH Archive-shaped q2, and LogHub q2/q3.
These rows are intended to separate summary-only topology wins from actual
memory-management/reclaim wins.

## Purpose

Earlier `heap-direct-epoch` rows updated primitive summaries on append and did
not keep record objects alive until the epoch closed. Those rows are useful
operator/topology evidence, but not a pure memory-management comparison.

The retained controls use the same append-time summaries but keep ordinary
Scala record objects alive until epoch close:

- `heap-direct-summary-only`: alias for the old summary-only direct epoch.
- `heap-epoch-retained-no-traverse`: heap objects are linked and retained
  until epoch close; close drops the bucket anchor without traversing records.
- `checked-epoch-retained-no-traverse`: same retained shape in
  `RiftRegion.epoch` over the Rift streaming backend.
- `checked-scoped-epoch-retained-no-traverse`: same retained shape over the
  SafeZone-backed checked scoped backend.

Close-time record traversal is intentionally absent. The retained rows touch
only `head`/`tail` anchors and the retained count before dropping the heap
anchor or closing the region. Primitive summary arrays remain heap/control
metadata in every mode.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RETAINED_EPOCH_RECORDS=20000 \
RETAINED_EPOCH_RECORDS_PER_EPOCH=5000 \
RETAINED_EPOCH_WARMUPS=0 \
RETAINED_EPOCH_BENCHMARK_RUNS=1 \
zsh sandbox/run_retained_epoch_reclaim_matrix.sh
```

1M focused row:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RETAINED_EPOCH_BUILD=0 \
RETAINED_EPOCH_RECORDS=1000000 \
RETAINED_EPOCH_RECORDS_PER_EPOCH=25000 \
RETAINED_EPOCH_WARMUPS=1 \
RETAINED_EPOCH_BENCHMARK_RUNS=3 \
zsh sandbox/run_retained_epoch_reclaim_matrix.sh
```

DSPBench Fraud q2 retained controls:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_EVENTS_PER_BUCKET=25000 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_INPUT_MODE=generated \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-direct-summary-only heap-epoch-retained-no-traverse checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
zsh sandbox/run_dspbench_region_matrix.sh
```

GH Archive-shaped q2 retained controls:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
GITHUB_ARCHIVE_BUILD=0 \
GITHUB_ARCHIVE_EVENTS=1000000 \
GITHUB_ARCHIVE_EVENTS_PER_BUCKET=25000 \
GITHUB_ARCHIVE_WARMUPS=1 \
GITHUB_ARCHIVE_BENCHMARK_RUNS=3 \
GITHUB_ARCHIVE_INPUT_MODE=preloaded \
GITHUB_ARCHIVE_QUERIES="q2-repo-window" \
GITHUB_ARCHIVE_MODES="heap-direct-summary-only heap-epoch-retained-no-traverse checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
zsh sandbox/run_github_archive_region_matrix.sh
```

LogHub q2/q3 retained controls:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_BUILD=0 \
LOGHUB_LINES=1000000 \
LOGHUB_LINES_PER_BUCKET=25000 \
LOGHUB_WARMUPS=1 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_INPUT_MODE=generated \
LOGHUB_QUERIES="q2-window-counts q3-template-session" \
LOGHUB_MODES="heap-direct-summary-only heap-epoch-retained-no-traverse checked-epoch-retained-no-traverse checked-scoped-epoch-retained-no-traverse" \
zsh sandbox/run_loghub_region_matrix.sh
```

## Focused 20k Smoke

All four modes matched checksum `-7433014701216206459` and output count
`11688`.

| Mode | Topology | Median ms | GC ms | RSS bytes |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `0.562` | `0.000` | `3784704` |
| `heap-epoch-retained-no-traverse` | retained epoch | `0.802` | `0.000` | `4685824` |
| `checked-epoch-retained-no-traverse` | retained epoch | `0.761` | `0.000` | `4030464` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `0.740` | `0.000` | `4014080` |

## Focused 1M Retained Epoch

All rows matched checksum `-829278451938965381` and output count `163644`.

| Mode | Topology | Median ms | Min ms | Max ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `11.041` | `11.025` | `11.643` | `0.000` | `0.000` | `0/3` | `3801088` |
| `heap-epoch-retained-no-traverse` | retained epoch | `34.405` | `34.373` | `34.473` | `9.646` | `9.779` | `3/3` | `21331968` |
| `checked-epoch-retained-no-traverse` | retained epoch | `25.366` | `25.208` | `25.472` | `0.000` | `0.000` | `0/3` | `4816896` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `23.999` | `23.891` | `24.008` | `0.000` | `0.000` | `0/3` | `4866048` |

Interpretation:

- The `heap-direct-summary-only` row is not memory-management evidence; it is
  a lower-bound topology/operator row where records do not survive until close.
- The fair memory-management comparison is retained heap vs retained checked
  region. Checked scoped retained epoch is `30.2%` faster than retained heap
  and uses about `77%` less RSS while removing `9.646 ms` median timed GC.
- Checked Rift streaming retained epoch is `26.3%` faster than retained heap
  and also removes timed GC. Rift region bookkeeping is small here:
  `0.060 ms` median region op time for 1M retained objects and 40 epoch resets.

## DSPBench Fraud q2 1M Retained Controls

Input: generated/indexable DSPBench Fraud-shaped stream. All rows matched
checksum `-5765375221524988491` and output count `613295`.

| Mode | Topology | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `279.103` | `0.000` | `0.000` | `0/3` | `575668224` |
| `heap-epoch-retained-no-traverse` | retained epoch | `387.943` | `35.797` | `38.527` | `2/3` | `575979520` |
| `checked-epoch-retained-no-traverse` | retained epoch | `373.837` | `0.000` | `0.000` | `0/3` | `582729728` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `364.535` | `0.000` | `0.000` | `0/3` | `582746112` |

Interpretation:

- Summary-only is again topology/operator evidence, not a placement claim.
- Retained checked scoped epoch beats retained heap by `6.0%` and removes
  `35.797 ms` median timed GC. This is a real memory-management win, although
  RSS is slightly higher than heap in this application-shaped row.
- Retained checked Rift streaming also beats retained heap by `3.6%`, with
  `0.503 ms` median region op time while allocating `4,873,323` retained
  region objects.
- Compared with the focused matrix, Fraud q2 still has significant
  query/predictor/hash CPU, so eliminating GC does not translate one-for-one
  into elapsed speedup.

## GH Archive-Shaped q2 1M Retained Controls

Input: generated/preloaded GH Archive-shaped stream. All rows matched checksum
`7294087528134281006` and output count `163487`.

| Mode | Topology | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `70.416` | `0.000` | `0.000` | `0/3` | `6078464` |
| `heap-epoch-retained-no-traverse` | retained epoch | `244.988` | `69.552` | `76.134` | `3/3` | `147275776` |
| `checked-epoch-retained-no-traverse` | retained epoch | `191.064` | `0.000` | `0.000` | `0/3` | `15138816` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `181.345` | `0.000` | `0.000` | `0/3` | `15187968` |

Interpretation: this is a strong retained memory-management row for the
GH-shaped generated/preloaded workload. Checked scoped retained epoch is
`26.0%` faster than retained heap, removes `69.552 ms` median timed GC, and
uses about `90%` less RSS. The summary-only row remains topology evidence.

## LogHub q2/q3 1M Retained Controls

Input: generated LogHub-shaped stream.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q2-window-counts` | `heap-direct-summary-only` | `216.636` | `0.000` | `0.000` | `0/3` | `678739968` | `163487` |
| `q2-window-counts` | `heap-epoch-retained-no-traverse` | `464.389` | `64.349` | `70.366` | `2/3` | `812761088` | `163487` |
| `q2-window-counts` | `checked-epoch-retained-no-traverse` | `413.203` | `0.000` | `0.000` | `0/3` | `305184768` | `163487` |
| `q2-window-counts` | `checked-scoped-epoch-retained-no-traverse` | `402.611` | `0.000` | `0.000` | `0/3` | `693960704` | `163487` |
| `q3-template-session` | `heap-direct-summary-only` | `1812.573` | `0.000` | `115.524` | `1/3` | `2291040256` | `312151` |
| `q3-template-session` | `heap-epoch-retained-no-traverse` | `2217.322` | `146.514` | `154.948` | `2/3` | `2291138560` | `312151` |
| `q3-template-session` | `checked-epoch-retained-no-traverse` | `2213.846` | `43.315` | `81.758` | `3/3` | `834338816` | `312151` |
| `q3-template-session` | `checked-scoped-epoch-retained-no-traverse` | `2101.599` | `0.000` | `130.038` | `1/3` | `2312257536` | `312151` |

Interpretation:

- LogHub q2 is a retained throughput/GC/RSS win. Checked scoped retained epoch
  is `13.3%` faster than retained heap and removes median timed GC. Checked
  stream retained is also faster than retained heap and has much lower RSS.
- LogHub q3 is mixed but still useful: checked scoped retained is `5.2%`
  faster than retained heap and removes median timed GC, but RSS is not lower;
  checked stream retained nearly ties elapsed while cutting RSS by about `64%`.
  The q3 parser/template/session CPU is large enough that reclaim wins are
  partially hidden.

## Classification

Use these labels in reports:

- `heap-direct-summary-only`: algorithm/topology evidence only.
- retained heap vs retained checked epoch: Rift memory-management evidence.
- retained checked wins with similar or higher RSS: throughput/GC evidence, not
  RSS evidence.
- retained checked ties but lowers GC/RSS tails: memory/tail evidence.

## Next Steps

- Add the retained modes to GH Archive-shaped q2 and LogHub q2/q3, using the
  same retained no-traverse rule.
- Run Yak LiveJournal retained/epoch rows only where the topology differs from
  the already retained edge-update implementation.
- Keep rank/top-k/median/join out of this path until each has a focused
  retained-control design with fair heap same-shape controls.
