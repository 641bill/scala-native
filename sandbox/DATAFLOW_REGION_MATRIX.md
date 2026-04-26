# Dataflow Region Matrix

Status: Broom-style methodology reproduction harness with trusted
heap/SafeZone/Rift medians and a SELECT-only checked RegionBuffer mode.

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
- `safezone`: the same logical object graph allocated in Scala Native
  `SafeZone`; run as current SafeZone with `SAFEZONE_ROOTS_MODE=0` and improved
  SafeZone with `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: the same classes allocated with `region.alloc`, using one region
  per epoch.
- `rift-streaming`: the same classes allocated with `region.alloc`, using a
  resettable streaming region across epochs.
- `rift-checked`: SELECT-only checked safe-API variant using
  `RiftRegion.streaming/reset` plus growable `RegionBuffer` for selected
  records. Aggregate and join are not yet ported to checked mode.

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

Native-only instrumented median run with peak RSS:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-100k \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh

DATAFLOW_BUILD=0 DATAFLOW_OPERATOR=select \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-select-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Checked SELECT run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_OPERATOR=select \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-select-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Broom-scale methodology run, subject to local runtime feasibility:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

## Result Status

Smoke, local medians, native-only instrumented RSS runs, and a SELECT-only
checked RegionBuffer median have been recorded. These are local methodology
reproduction numbers, not exact Broom/Naiad numbers.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BUILD=0 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-100k \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

The first smoke run rebuilt under the script's Java 17 setup and emitted a JVM
code-cache warning during sbt compilation. The native binaries still linked and
ran to completion. The local median run reused the built binary and completed
normally.

## Native-Only Local 3-Run Median, 10 Epochs x 100k Documents

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_KEY_SPACE=65536`
- `DATAFLOW_AUTHOR_KEY_SPACE=256`
- `DATAFLOW_SELECT_MODULO=8`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 26.760 | 6.554 | 0.000 | 0 | 40026112 |
| SELECT | current SafeZone | 26.599 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | improved SafeZone | 24.035 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | Rift HPZone | 22.934 | 0.000 | 0.052 | 1124990 | 46891008 |
| SELECT | Rift Streaming | 22.958 | 0.000 | 0.044 | 1124990 | 46858240 |
| AGGREGATE | heap | 57.705 | 19.240 | 0.000 | 0 | 40026112 |
| AGGREGATE | current SafeZone | 49.599 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | improved SafeZone | 41.426 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | Rift HPZone | 43.046 | 0.000 | 0.198 | 1627152 | 46891008 |
| AGGREGATE | Rift Streaming | 42.802 | 0.000 | 0.232 | 1627152 | 46858240 |
| JOIN | heap | 28.189 | 7.084 | 0.000 | 0 | 40026112 |
| JOIN | current SafeZone | 26.705 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | improved SafeZone | 22.789 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | Rift HPZone | 22.554 | 0.000 | 0.036 | 1078279 | 46891008 |
| JOIN | Rift Streaming | 23.025 | 0.000 | 0.038 | 1078279 | 46858240 |

Interpretation:

- This is the first current benchmark where ordinary Scala dataflow objects are
  placed in regions and measured against the same heap program.
- The local controlled workload shows material GC reduction because the data
  objects are intentionally epoch-local and allocation-heavy.
- Improved SafeZone is now a strong baseline: it is faster than heap on all
  three operators and roughly tied with Rift on this local scale.
- Rift HPZone and Streaming are close at this scale. Streaming trades ten
  open/close pairs for one open/close plus nine resets.
- Rift operation time is small in this benchmark: below 0.25 ms median for each
  operator at the 1M-document local scale.

Caveats:

- This is a Broom-style methodology reproduction, not exact Naiad/Broom.
- The default local size is smaller than the Broom paper's reported
  40-epoch, 500k-600k document-per-epoch vertex experiment.
- The benchmark uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future capture-checked safe API.

## Post Allocation-Counter Fix Verification, 10 Epochs x 100k Documents

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-fixed \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

This rerun was taken after moving Rift allocation counters out of the
per-object atomic hot path and flushing counts at region reset/close.

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 28.398 | 7.131 | 0.000 | 0 | 40042496 |
| SELECT | current SafeZone | 26.413 | 0.000 | 0.000 | 0 | 47038464 |
| SELECT | improved SafeZone | 23.600 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | Rift HPZone | 21.614 | 0.000 | 0.051 | 1124990 | 46891008 |
| SELECT | Rift Streaming | 24.445 | 0.000 | 0.046 | 1124990 | 46874624 |
| AGGREGATE | heap | 59.906 | 19.908 | 0.000 | 0 | 40042496 |
| AGGREGATE | current SafeZone | 51.568 | 0.000 | 0.000 | 0 | 47038464 |
| AGGREGATE | improved SafeZone | 43.216 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | Rift HPZone | 41.870 | 0.000 | 0.273 | 1627152 | 46891008 |
| AGGREGATE | Rift Streaming | 48.003 | 0.000 | 0.320 | 1627152 | 46874624 |
| JOIN | heap | 28.347 | 7.415 | 0.000 | 0 | 40042496 |
| JOIN | current SafeZone | 27.032 | 0.000 | 0.000 | 0 | 47038464 |
| JOIN | improved SafeZone | 24.079 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | Rift HPZone | 21.481 | 0.000 | 0.075 | 1078279 | 46891008 |
| JOIN | Rift Streaming | 36.120 | 0.000 | 0.046 | 1078279 | 46874624 |

Interpretation:

- HPZone now beats heap and improved SafeZone on all three local Broom-style
  operators in this rerun.
- Streaming is not consistently good on this run; keep HPZone and Streaming
  claims separate.

## Checked RegionBuffer SELECT Median

Date: 2026-04-26

This run adds a `rift-checked` SELECT-only mode to the existing Broom-style
matrix. The checked path uses `RiftRegion.streaming/reset` and
`RiftRegion.RegionBuffer` for selected output records. Heap, SafeZone, trusted
Rift, and checked Rift all produce the same SELECT checksum.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_OPERATOR=select \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-select-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 27.671 | 6.761 | 0.000 | 0 | 0 / 0 / 0 | 39288832 |
| current SafeZone | 26.304 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 30392320 |
| improved SafeZone | 23.403 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 30392320 |
| Rift HPZone | 20.186 | 0.000 | 0.039 | 1124990 | 10 / 10 / 0 | 30375936 |
| Rift Streaming | 20.760 | 0.000 | 0.038 | 1124990 | 1 / 1 / 9 | 30343168 |
| Rift checked RegionBuffer | 18.472 | 0.000 | 0.133 | 1125100 | 1 / 1 / 10 | 30490624 |

Interpretation:

- This is the first existing literature-shaped operator path using the checked
  safe API rather than trusted `RiftRegion.open`.
- The checked SELECT variant is SELECT-only. Aggregate and join still need
  checked table/container provenance before they can be included.
- `rift-checked` is faster than heap, improved SafeZone, trusted HPZone, and
  trusted Streaming in this local SELECT run.
- Region operation time remains small (`0.133 ms` median), though higher than
  trusted Streaming because checked reset runs every epoch and the selected
  output uses growable backing arrays.

## Provisional Broom-Scale Single Run, 40 Epochs x 500k Documents

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BUILD=0 DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 \
DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-broom-scale \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
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

| Operator | Mode | Elapsed ms | GC ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 623.761 | 207.058 | 0.000 | 0 | 290504704 |
| SELECT | current SafeZone | 766.753 | 0.000 | 0.000 | 0 | 226590720 |
| SELECT | improved SafeZone | 463.303 | 0.000 | 0.000 | 0 | 226607104 |
| SELECT | Rift HPZone | 451.041 | 0.000 | 2.109 | 22500066 | 226361344 |
| SELECT | Rift Streaming | 452.422 | 0.000 | 1.848 | 22500066 | 226344960 |
| AGGREGATE | heap | 859.726 | 269.652 | 0.000 | 0 | 290504704 |
| AGGREGATE | current SafeZone | 924.995 | 0.000 | 0.000 | 0 | 226590720 |
| AGGREGATE | improved SafeZone | 618.133 | 0.000 | 0.000 | 0 | 226607104 |
| AGGREGATE | Rift HPZone | 620.342 | 0.000 | 2.048 | 22621480 | 226361344 |
| AGGREGATE | Rift Streaming | 630.151 | 0.000 | 2.112 | 22621480 | 226344960 |
| JOIN | heap | 602.540 | 184.929 | 0.000 | 0 | 290504704 |
| JOIN | current SafeZone | 748.152 | 0.000 | 0.000 | 0 | 226590720 |
| JOIN | improved SafeZone | 470.770 | 0.000 | 0.000 | 0 | 226607104 |
| JOIN | Rift HPZone | 447.803 | 0.000 | 0.968 | 21563289 | 226361344 |
| JOIN | Rift Streaming | 448.956 | 0.000 | 0.867 | 21563289 | 226344960 |

Interpretation:

- This is the closest current run to the Broom paper's reported vertex scale:
  40 epochs with 500k documents per epoch.
- It is still a methodology run, not exact Naiad/Broom.
- It is single-run evidence. Use it as a stress/provenance checkpoint, not as a
  headline median.
- The result suggests this harness is sensitive to GC when the object volume is
  raised to the paper-scale input shape.
- Improved SafeZone is again a strong baseline; Rift is slightly faster on
  SELECT and JOIN in this single run, while improved SafeZone is slightly faster
  on AGGREGATE.
