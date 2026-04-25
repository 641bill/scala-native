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
STANCU_TRANSACTIONS=2000 STANCU_BENCHMARK_RUNS=1 STANCU_WARMUPS=0 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STANCU_BUILD=0 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
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
STANCU_TRANSACTIONS=1000000 \
STANCU_OUTPUT_DIR=/tmp/stancu-region-pressure \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. All
heap/SafeZone/Rift checksums matched.

## Native-Only Local 3-Run Median, Default Configuration

Configuration:

- `STANCU_TRANSACTIONS=200000`
- `STANCU_ITEMS_PER_TX=8`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical region objects | Durable control slots | Region-candidate objects | Explicit boundaries | Escaped objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 43.180 | 4.785 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 7946240 |
| current SafeZone | 57.125 | 1.495 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 7979008 |
| improved SafeZone | 57.782 | 1.481 | 0.000 | 1800000 | 4224 | 99.76% | 1 | 0 | 7979008 |
| Rift HPZone | 65.729 | 0.545 | 10.413 | 1800000 | 4224 | 99.76% | 1 | 0 | 7979008 |
| Rift Streaming | 51.769 | 0.000 | 3.243 | 1800000 | 4224 | 99.76% | 1 | 0 | 7962624 |

Interpretation:

- The accounting story is strong but the performance story is not: `99.76%` of
  logical objects are region candidates, yet heap is fastest.
- Per-transaction regions are too fine-grained for HPZone here:
  `200000` opens/closes produce `10.413 ms` median region-op time.
- Streaming reduces region-op time by using one open/close and `199999` resets,
  but still does not beat heap at this size.

## Transaction-Pressure 3-Run Median

Configuration:

- `STANCU_TRANSACTIONS=1000000`
- `STANCU_ITEMS_PER_TX=8`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical region objects | Durable control slots | Region-candidate objects | Explicit boundaries | Escaped objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 217.335 | 23.743 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 7946240 |
| current SafeZone | 281.234 | 8.013 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 7995392 |
| improved SafeZone | 286.893 | 7.261 | 0.000 | 9000000 | 4224 | 99.95% | 1 | 0 | 7995392 |
| Rift HPZone | 332.947 | 2.483 | 53.415 | 9000000 | 4224 | 99.95% | 1 | 0 | 7979008 |
| Rift Streaming | 262.075 | 0.000 | 16.300 | 9000000 | 4224 | 99.95% | 1 | 0 | 7962624 |

Interpretation:

- At one million transactions, almost every logical object is a region
  candidate and Rift removes nearly all measured heap GC.
- Heap is still fastest. The GC savings are smaller than the cost of the
  current per-transaction region/reset boundary.
- This is an important negative result: "high region-candidate fraction" is not
  sufficient evidence. The lifetime boundary and reset/open frequency must also
  be appropriate.

Caveats:

- This is not SPECjbb2005 and not the Stancu et al. static analysis. It is a
  small transaction-shaped local accounting probe.
- `explicit_region_boundaries=1` means one logical allocation-placement site in
  the benchmark, not a real source annotation count produced by the compiler.
- The current harness does not yet test coarser transaction batches, compiler
  inference, or capture-check rejection cases.
