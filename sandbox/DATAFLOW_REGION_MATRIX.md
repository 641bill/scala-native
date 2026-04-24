# Dataflow Region Matrix

Status: new Broom-style methodology reproduction harness.

The harness is implemented in
`sandbox/src/main/scala-next/DataflowRegionMatrix.scala` and run with
`sandbox/run_dataflow_region_matrix.sh`.

## Benchmark Intent

This is not an exact Naiad/Broom artifact reproduction. The original Naiad/Broom
code is not available in this workspace. The benchmark instead reproduces the
paper's operator methodology: many per-epoch data objects enter SELECT,
AGGREGATE, and JOIN vertices, then die together at the epoch boundary.

Heap and Rift variants use the same logical programs:

- `heap`: ordinary `new` for document, selected-output, aggregate-entry,
  author-entry, join-output, and table-array objects.
- `rift-hp`: the same classes allocated with `region.alloc`, using one region
  per epoch.
- `rift-streaming`: the same classes allocated with `region.alloc`, using a
  resettable streaming region across epochs.

The benchmark intentionally uses ordinary Scala object graphs in regions. It is
not a primitive-key-only layout experiment.

## Default Configuration

The defaults are sized for local iteration, not for the original Broom paper's
500k-600k documents per epoch and 40 epochs:

| Environment variable | Default |
|---|---:|
| `DATAFLOW_EPOCHS` | `10` |
| `DATAFLOW_DOCS_PER_EPOCH` | `100000` |
| `DATAFLOW_AUTHORS_PER_EPOCH` | `20` |
| `DATAFLOW_KEY_SPACE` | `65536` |
| `DATAFLOW_AUTHOR_KEY_SPACE` | `256` |
| `DATAFLOW_SELECT_MODULO` | `8` |
| `DATAFLOW_WARMUPS` | `1` |
| `DATAFLOW_BENCHMARK_RUNS` | `3` |

Use `DATAFLOW_OPERATOR=select`, `aggregate`, `join`, or `all`.

## Commands

Smoke-sized run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Local median run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Broom-scale methodology run, subject to local runtime feasibility:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

## Result Status

Smoke and local medians have been recorded. These are local methodology
reproduction numbers, not exact Broom/Naiad numbers.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

The first smoke run rebuilt under the script's Java 17 setup and emitted a JVM
code-cache warning during sbt compilation. The native binaries still linked and
ran to completion. The local median run reused the built binary and completed
normally.

## Local 3-Run Median, 10 Epochs x 100k Documents

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_KEY_SPACE=65536`
- `DATAFLOW_AUTHOR_KEY_SPACE=256`
- `DATAFLOW_SELECT_MODULO=8`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | Checksum |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 29.100 | 7.134 | 0.000 | 0 | 131080080920 |
| SELECT | Rift HPZone | 23.549 | 0.000 | 0.055 | 1124990 | 131080080920 |
| SELECT | Rift Streaming | 23.466 | 0.000 | 0.043 | 1124990 | 131080080920 |
| AGGREGATE | heap | 61.204 | 20.346 | 0.000 | 0 | 163835709480 |
| AGGREGATE | Rift HPZone | 43.812 | 0.000 | 0.236 | 1627152 | 163835709480 |
| AGGREGATE | Rift Streaming | 43.934 | 0.000 | 0.254 | 1627152 | 163835709480 |
| JOIN | heap | 28.548 | 7.249 | 0.000 | 0 | 193232836790 |
| JOIN | Rift HPZone | 23.043 | 0.000 | 0.043 | 1078279 | 193232836790 |
| JOIN | Rift Streaming | 23.330 | 0.000 | 0.040 | 1078279 | 193232836790 |

Interpretation:

- This is the first current benchmark where ordinary Scala dataflow objects are
  placed in regions and measured against the same heap program.
- The local controlled workload shows material GC reduction because the data
  objects are intentionally epoch-local and allocation-heavy.
- Rift operation time is small in this benchmark: below 0.3 ms median for each
  operator at the 1M-document local scale.
- HPZone and Streaming are close at this scale. Streaming trades ten
  open/close pairs for one open/close plus nine resets.

Caveats:

- This is a Broom-style methodology reproduction, not exact Naiad/Broom.
- SafeZone modes are not implemented in this harness yet.
- The default local size is smaller than the Broom paper's reported
  40-epoch, 500k-600k document-per-epoch vertex experiment.
- The benchmark uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future capture-checked safe API.

## Provisional Broom-Scale Single Run, 40 Epochs x 500k Documents

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Configuration:

- `DATAFLOW_EPOCHS=40`
- `DATAFLOW_DOCS_PER_EPOCH=500000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_KEY_SPACE=65536`
- `DATAFLOW_AUTHOR_KEY_SPACE=256`
- `DATAFLOW_SELECT_MODULO=8`
- `DATAFLOW_BENCHMARK_RUNS=1`
- `DATAFLOW_WARMUPS=0`

| Operator | Mode | Elapsed ms | GC ms | Rift op ms | Region objects | Checksum |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 1193.248 | 440.094 | 0.000 | 0 | 25069572096852 |
| SELECT | Rift HPZone | 471.136 | 0.000 | 2.378 | 22500066 | 25069572096852 |
| SELECT | Rift Streaming | 475.792 | 0.000 | 2.211 | 22500066 | 25069572096852 |
| AGGREGATE | heap | 906.687 | 263.886 | 0.000 | 0 | 2912068017281 |
| AGGREGATE | Rift HPZone | 688.029 | 0.000 | 2.956 | 22621480 | 2912068017281 |
| AGGREGATE | Rift Streaming | 700.530 | 0.000 | 4.881 | 22621480 | 2912068017281 |
| JOIN | heap | 587.596 | 163.944 | 0.000 | 0 | 15930819206750 |
| JOIN | Rift HPZone | 492.409 | 0.000 | 1.105 | 21563289 | 15930819206750 |
| JOIN | Rift Streaming | 455.895 | 0.000 | 1.013 | 21563289 | 15930819206750 |

Interpretation:

- This is the closest current run to the Broom paper's reported vertex scale:
  40 epochs with 500k documents per epoch.
- It is still a methodology run, not exact Naiad/Broom.
- It is single-run evidence. Use it as a stress/provenance checkpoint, not as a
  headline median.
- The result suggests this harness is sensitive to GC when the object volume is
  raised to the paper-scale input shape.
