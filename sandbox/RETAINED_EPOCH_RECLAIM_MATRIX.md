# Retained Epoch Reclaim Matrix

Date: 2026-05-09
Last updated: 2026-05-10 17:37 CEST

Status: clean committed rerun completed after child commit `918c7d4c1` and
parent commit `ab570b1`. These rows separate summary-only topology wins from
actual retained-object memory-management/reclaim wins.

Raw clean-run summaries:

- focused smoke:
  `/Users/siyaoliu/rift/cache/retained-epoch-clean-2026-05-09/smoke`
- focused 1M:
  `/Users/siyaoliu/rift/cache/retained-epoch-clean-2026-05-09/1m`
- DSPBench 1M:
  `/Users/siyaoliu/rift/cache/dspbench-retained-clean-2026-05-09/1m/summary.tsv`
- GH Archive-shaped 1M:
  `/Users/siyaoliu/rift/cache/github-archive-retained-clean-2026-05-09/1m/summary.tsv`
- GH Archive-shaped L1 final-clean:
  `/tmp/rift-l1-gharchive-retained-q2-1m-x20-36bbfa9cd-r{1,2,3}/summary.tsv`
- LogHub 1M:
  `/Users/siyaoliu/rift/cache/loghub-retained-clean-2026-05-09/1m/summary.tsv`

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
`11688`. Treat this row as correctness smoke only; headline evidence below
uses the clean 1M 3-run medians.

## Focused 1M Retained Epoch

All rows matched checksum `-829278451938965381` and output count `163644`.

| Mode | Topology | Median ms | Min ms | Max ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `11.211` | `11.001` | `11.276` | `0.000` | `0.000` | `0/3` | `3817472` |
| `heap-epoch-retained-no-traverse` | retained epoch | `36.233` | `34.850` | `38.248` | `10.109` | `13.131` | `3/3` | `21348352` |
| `checked-epoch-retained-no-traverse` | retained epoch | `27.703` | `27.604` | `27.727` | `0.000` | `0.000` | `0/3` | `4833280` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `24.274` | `24.260` | `24.379` | `0.000` | `0.000` | `0/3` | `4882432` |

Interpretation:

- The `heap-direct-summary-only` row is not memory-management evidence; it is
  a lower-bound topology/operator row where records do not survive until close.
- The fair memory-management comparison is retained heap vs retained checked
  region. Checked scoped retained epoch is `33.0%` faster than retained heap
  and uses about `77%` less RSS while removing `10.109 ms` median timed GC.
- Checked Rift streaming retained epoch is `23.5%` faster than retained heap
  and also removes timed GC. Rift region bookkeeping is small here:
  `0.083 ms` median region op time for 1M retained objects and 40 epoch resets.

## DSPBench Fraud q2 1M Retained Controls

Input: generated/indexable DSPBench Fraud-shaped stream. All rows matched
checksum `-5765375221524988491` and output count `613295`.

| Mode | Topology | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `286.318` | `0.000` | `0.000` | `0/3` | `575651840` |
| `heap-epoch-retained-no-traverse` | retained epoch | `392.743` | `35.631` | `38.214` | `2/3` | `575963136` |
| `checked-epoch-retained-no-traverse` | retained epoch | `383.628` | `0.000` | `0.000` | `0/3` | `582713344` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `370.746` | `0.000` | `0.000` | `0/3` | `582746112` |

Interpretation:

- Summary-only is again topology/operator evidence, not a placement claim.
- Retained checked scoped epoch beats retained heap by `5.6%` and removes
  `35.631 ms` median timed GC. This is a real memory-management win, although
  RSS is slightly higher than heap in this application-shaped row.
- Retained checked Rift streaming also beats retained heap by `2.3%`, with
  `0.563 ms` median region op time while allocating `4,873,323` retained
  region objects.
- Compared with the focused matrix, Fraud q2 still has significant
  query/predictor/hash CPU, so eliminating GC does not translate one-for-one
  into elapsed speedup.

## GH Archive-Shaped q2 1M Retained Controls

Input: generated/preloaded GH Archive-shaped stream. All rows matched checksum
`7294087528134281006` and output count `163487`.

| Mode | Topology | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `75.424` | `0.000` | `0.000` | `0/3` | `6029312` |
| `heap-epoch-retained-no-traverse` | retained epoch | `257.377` | `77.208` | `78.345` | `3/3` | `147259392` |
| `checked-epoch-retained-no-traverse` | retained epoch | `194.827` | `0.000` | `0.000` | `0/3` | `15089664` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `186.868` | `0.000` | `0.000` | `0/3` | `15204352` |

Interpretation: this is a strong retained memory-management row for the
GH-shaped generated/preloaded workload. Checked scoped retained epoch is
`27.4%` faster than retained heap, removes `77.208 ms` median timed GC, and
uses about `90%` less RSS. The summary-only row remains topology evidence.

### L1 Final-Clean GH Archive-Shaped q2

This row reruns the same generated/preloaded retained q2 shape with
`RIFT_FINAL_CLEAN=1`. Each external process runs 20 identical 1M-event q2
iterations. L1 external timing/RSS comes from `/usr/bin/time -l`; the L2 row
above remains the GC interpretation source.

All rows matched checksum `7294087528134281006` and output count `163487`.

| Mode | Topology | External real median | External user median | Max RSS median |
|---|---|---:|---:|---:|
| `heap-direct-summary-only` | summary-only | `1.28 s` | `1.27 s` | `6832128` |
| `heap-epoch-retained-no-traverse` | retained epoch | `4.62 s` | `4.60 s` | `147341312` |
| `checked-epoch-retained-no-traverse` | retained epoch | `3.65 s` | `3.64 s` | `15990784` |
| `checked-scoped-epoch-retained-no-traverse` | retained epoch | `3.44 s` | `3.42 s` | `16056320` |

Interpretation: L1 confirms this retained-object memory-management win with
measurement-clean external timing. Checked scoped retained epoch is `25.5%`
faster than retained heap and uses about `89%` less RSS. Summary-only remains
the topology/operator lower bound.

## LogHub q2/q3 1M Retained Controls

Input: generated LogHub-shaped stream.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q2-window-counts` | `heap-direct-summary-only` | `218.976` | `0.000` | `0.000` | `0/3` | `678739968` | `163487` |
| `q2-window-counts` | `heap-epoch-retained-no-traverse` | `469.079` | `64.060` | `68.094` | `2/3` | `812761088` | `163487` |
| `q2-window-counts` | `checked-epoch-retained-no-traverse` | `420.734` | `0.000` | `0.000` | `0/3` | `693944320` | `163487` |
| `q2-window-counts` | `checked-scoped-epoch-retained-no-traverse` | `402.821` | `0.000` | `0.000` | `0/3` | `693944320` | `163487` |
| `q3-template-session` | `heap-direct-summary-only` | `1877.508` | `0.000` | `115.107` | `1/3` | `2291040256` | `312151` |
| `q3-template-session` | `heap-epoch-retained-no-traverse` | `2244.266` | `143.539` | `148.752` | `2/3` | `2291138560` | `312151` |
| `q3-template-session` | `checked-epoch-retained-no-traverse` | `2169.804` | `0.000` | `117.128` | `1/3` | `2312208384` | `312151` |
| `q3-template-session` | `checked-scoped-epoch-retained-no-traverse` | `2106.541` | `0.000` | `124.770` | `1/3` | `2312323072` | `312151` |

Interpretation:

- LogHub q2 is a retained throughput/GC win. Checked scoped retained epoch
  is `14.1%` faster than retained heap and removes median timed GC.
- LogHub q3 is mixed but still useful: checked scoped retained is `5.2%`
  faster than retained heap and removes median timed GC, but RSS is not lower;
  checked stream retained is `3.3%` faster than retained heap while removing
  median timed GC, but RSS is also not lower in this clean run.
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

- Keep these retained rows as the report-ready memory-management evidence for
  append-time summary plus epoch close.
- Add retained controls for rank/top-k/median/join only after each operator has
  a concrete same-shape heap design that retains ordinary objects until the
  intended close boundary.
- Do not use `heap-direct-summary-only` as a pure Rift placement baseline; use
  it only as a topology/operator lower bound.
