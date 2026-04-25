# Stancu Region Matrix

Status: Stancu-style annotation/accounting probe with validated smoke, default
median, and pressure median runs.

The harness is implemented in
`sandbox/src/main/scala-next/StancuRegionMatrix.scala` and run with
`sandbox/run_stancu_region_instrumented_matrix.sh`.

## Benchmark Intent

This is not an exact reproduction of Stancu et al.'s ISMM 2015 system or its
SPECjbb2005 experiment. It is a local probe for the axes that paper makes
important:

- coarse transaction lifetime boundaries;
- ordinary transaction object graphs;
- durable heap state as fallback/control metadata;
- explicit accounting for how much of the logical object population is region
  candidate data;
- annotation/API-boundary count.

The current workload is a small warehouse transaction shape:

- durable heap state: product stock, warehouse revenue, customer checksum state;
- region candidate data: one order object and `items_per_tx` line-item objects
  per transaction;
- no intentional region object escapes.

Heap mode allocates transaction objects with `new`; SafeZone mode allocates
them in Scala Native SafeZone; Rift modes allocate them with `region.alloc`.

## Default Configuration

| Environment variable | Default |
|---|---:|
| `STANCU_TRANSACTIONS` | `200000` |
| `STANCU_ITEMS_PER_TX` | `8` |
| `STANCU_TX_PER_REGION` | `64` |
| `STANCU_WAREHOUSES` | `64` |
| `STANCU_PRODUCTS` | `4096` |
| `STANCU_WARMUPS` | `1` |
| `STANCU_BENCHMARK_RUNS` | `3` |

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STANCU_TRANSACTIONS=2000 STANCU_TX_PER_REGION=64 \
STANCU_BENCHMARK_RUNS=1 STANCU_WARMUPS=0 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STANCU_BUILD=0 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_TX_PER_REGION=64 \
STANCU_OUTPUT_DIR=/tmp/stancu-region-instrumented \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

## Result Status

Compile, smoke, default local medians, and one transaction-pressure run have
been recorded. These are local Stancu-style accounting numbers, not an exact
Stancu et al. implementation or SPECjbb2005 reproduction.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

STANCU_TRANSACTIONS=2000 STANCU_BENCHMARK_RUNS=1 STANCU_WARMUPS=0 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh

STANCU_BUILD=0 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_OUTPUT_DIR=/tmp/stancu-region-instrumented \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh

STANCU_BUILD=0 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_TRANSACTIONS=1000000 STANCU_TX_PER_REGION=64 \
STANCU_OUTPUT_DIR=/tmp/stancu-region-pressure \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. All
heap/SafeZone/Rift checksums matched.

## Native-Only Local 3-Run Median, Batched Boundary And Fixed Counters

Configuration:

- `STANCU_TRANSACTIONS=200000`
- `STANCU_ITEMS_PER_TX=8`
- `STANCU_TX_PER_REGION=64`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical region objects | Durable control slots | Region-candidate objects | Explicit boundaries | Escaped objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 43.871 | 4.483 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 7962624 |
| current SafeZone | 37.313 | 0.000 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 8011776 |
| improved SafeZone | 34.412 | 0.000 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 8011776 |
| Rift HPZone | 40.308 | 0.000 | 0.186 | 1800000 | 4224 | 99.76% | 1 | 0 | 8011776 |
| Rift Streaming | 40.126 | 0.000 | 0.063 | 1800000 | 4224 | 99.76% | 1 | 0 | 7979008 |

Interpretation:

- Coarsening the boundary to 64 transactions per region fixes the default
  Stancu result for Rift: HPZone and Streaming now beat heap and remove
  measured GC.
- The previous per-transaction boundary was too fine-grained. It paid
  `200000` opens/closes or resets on this default run.
- Improved SafeZone remains the fastest mode at this size.

## Transaction-Pressure 3-Run Median, Batched Boundary And Fixed Counters

Configuration:

- `STANCU_TRANSACTIONS=1000000`
- `STANCU_ITEMS_PER_TX=8`
- `STANCU_TX_PER_REGION=64`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical region objects | Durable control slots | Region-candidate objects | Explicit boundaries | Escaped objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 223.611 | 23.924 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 7946240 |
| current SafeZone | 173.976 | 0.307 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 8028160 |
| improved SafeZone | 174.620 | 0.315 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 8011776 |
| Rift HPZone | 201.447 | 0.000 | 0.772 | 9000000 | 4224 | 99.95% | 1 | 0 | 7979008 |
| Rift Streaming | 199.438 | 0.000 | 0.317 | 9000000 | 4224 | 99.95% | 1 | 0 | 7979008 |

Interpretation:

- At one million transactions, Rift now shows the expected direction:
  `199.438 ms` Streaming and `201.447 ms` HPZone versus heap `223.611 ms`.
- Removing per-object atomic allocation-counter increments from the Rift
  runtime was necessary for this small-object workload; the earlier
  instrumentation distorted elapsed time.
- Improved/current SafeZone remain stronger on this transaction-shaped local
  probe.

## Heavier Object Mix, Batched Boundary And Fixed Counters

Configuration:

- `STANCU_TRANSACTIONS=500000`
- `STANCU_ITEMS_PER_TX=32`
- `STANCU_TX_PER_REGION=64`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical region objects | Region-candidate objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 387.420 | 40.579 | 0.000 | 16500000 | 99.97% | 7962624 |
| current SafeZone | 333.532 | 0.000 | 0.000 | 16500000 | 99.97% | 8077312 |
| improved SafeZone | 328.298 | 0.000 | 0.000 | 16500000 | 99.97% | 8060928 |
| Rift HPZone | 364.288 | 0.000 | 1.077 | 16500000 | 99.97% | 8093696 |
| Rift Streaming | 341.879 | 0.000 | 0.588 | 16500000 | 99.97% | 8060928 |

Interpretation:

- The heavier mix confirms the fix: Rift Streaming is `11.8%` faster than heap
  while removing measured GC.
- Improved SafeZone is still faster than Rift, so this is a Rift-vs-heap win,
  not a Rift-vs-improved-SafeZone win.

Caveats:

- This is not SPECjbb2005 and not the Stancu et al. static analysis. It is a
  small transaction-shaped local accounting probe.
- `explicit_region_boundaries=1` means one logical allocation-placement site in
  the benchmark, not a real source annotation count produced by the compiler.
- The current harness uses manual accounting. It does not yet provide
  compiler-produced annotation counts, region-freed byte accounting, or
  capture-check rejection cases.
