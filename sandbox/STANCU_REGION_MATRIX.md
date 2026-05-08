# Stancu Region Matrix

Last updated: 2026-05-08 21:07 CEST

Status: Stancu-style annotation/accounting probe with validated smoke, default
median, pressure median, heavier-object median, boundary-sensitivity runs, and
direct checked epoch rows.

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
Checked direct epoch modes allocate transaction line/order objects inside one
`RiftRegion.epoch { ... }` per transaction batch while durable stock/revenue
metadata stays on the heap.

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

Additional boundary-sensitivity validation run on 2026-04-26 from the Codex
worktree clone at
`/Users/siyaoliu/.codex/worktrees/ba3c/rift/scala-native-rift`:

```sh
cd /Users/siyaoliu/.codex/worktrees/ba3c/rift/scala-native-rift

STANCU_TRANSACTIONS=2000 STANCU_TX_PER_REGION=64 \
STANCU_BENCHMARK_RUNS=1 STANCU_WARMUPS=0 \
STANCU_OUTPUT_DIR=/tmp/ba3c-stancu-smoke \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh

STANCU_BUILD=0 STANCU_TRANSACTIONS=200000 STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=1 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_OUTPUT_DIR=/tmp/ba3c-stancu-boundary-1 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh

STANCU_BUILD=0 STANCU_TRANSACTIONS=200000 STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=64 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_OUTPUT_DIR=/tmp/ba3c-stancu-boundary-64 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh

STANCU_BUILD=0 STANCU_TRANSACTIONS=200000 STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=512 STANCU_BENCHMARK_RUNS=3 STANCU_WARMUPS=1 \
STANCU_OUTPUT_DIR=/tmp/ba3c-stancu-boundary-512 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

The smoke/link run and all boundary runs completed with matching
heap/SafeZone/Rift checksums. `sbt` and `/usr/bin/time -l` needed unsandboxed
access to the existing user cache/RSS counters; the benchmark source and
outputs were kept inside the new worktree clone and `/tmp`.

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

## Boundary-Sensitivity 3-Run Median

Configuration:

- `STANCU_TRANSACTIONS=200000`
- `STANCU_ITEMS_PER_TX=8`
- `STANCU_WAREHOUSES=64`
- `STANCU_PRODUCTS=4096`
- `STANCU_BENCHMARK_RUNS=3`
- `STANCU_WARMUPS=1`

### `STANCU_TX_PER_REGION=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region opens | Region closes | Region resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 44.592 | 4.862 | 0.000 | 0 | 0 | 0 | 7962624 |
| current SafeZone | 57.568 | 1.412 | 0.000 | 0 | 0 | 0 | 7995392 |
| improved SafeZone | 62.564 | 1.678 | 0.000 | 0 | 0 | 0 | 8011776 |
| Rift HPZone | 63.257 | 0.576 | 9.318 | 200000 | 200000 | 0 | 7979008 |
| Rift Streaming | 50.487 | 0.000 | 3.922 | 1 | 1 | 199999 | 7979008 |

### `STANCU_TX_PER_REGION=64`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region opens | Region closes | Region resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 43.189 | 4.880 | 0.000 | 0 | 0 | 0 | 7962624 |
| current SafeZone | 36.691 | 0.000 | 0.000 | 0 | 0 | 0 | 7979008 |
| improved SafeZone | 37.057 | 0.000 | 0.000 | 0 | 0 | 0 | 7995392 |
| Rift HPZone | 39.522 | 0.000 | 0.188 | 3125 | 3125 | 0 | 8011776 |
| Rift Streaming | 38.844 | 0.000 | 0.060 | 1 | 1 | 3124 | 7995392 |

### `STANCU_TX_PER_REGION=512`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region opens | Region closes | Region resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|
| heap | 45.315 | 4.740 | 0.000 | 0 | 0 | 0 | 7962624 |
| current SafeZone | 37.701 | 0.000 | 0.000 | 0 | 0 | 0 | 8126464 |
| improved SafeZone | 38.436 | 0.000 | 0.000 | 0 | 0 | 0 | 8110080 |
| Rift HPZone | 40.025 | 0.000 | 0.101 | 391 | 391 | 0 | 8159232 |
| Rift Streaming | 39.717 | 0.000 | 0.065 | 1 | 1 | 390 | 8126464 |

Boundary interpretation:

- The Stancu-style result is now complete enough to label the original weak
  result as a boundary-granularity issue, not a checksum or placement bug.
- Per-transaction regions are too fine-grained for this workload:
  HPZone pays `9.318 ms` in measured region operations and loses to heap;
  Streaming reduces the open/close cost but still does not beat heap.
- Batching `64` transactions per region is the best measured point in this
  local sweep: Streaming is `38.844 ms` versus heap `43.189 ms`, with measured
  GC removed and only `0.060 ms` of Rift region-operation time.
- Increasing to `512` transactions per region reduces measured Rift operation
  time slightly, but elapsed time is not better than the `64`-transaction
  boundary on this local run.
- SafeZone remains faster than Rift at the coarse boundaries, so this supports
  a Rift-vs-heap Stancu-style accounting story, not a Rift-vs-improved-SafeZone
  claim.

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

## Direct Checked Epoch Follow-Up

Date/time: 2026-05-08 21:07 CEST.

This follow-up applies the reusable `RiftRegion.epoch { ... }` topology to the
Stancu-style transaction boundary. The checked path uses one epoch block for
each `STANCU_TX_PER_REGION` batch. Ordinary transaction line and order objects
live in the epoch region; durable product stock, warehouse revenue, and
customer checksum state remain heap arrays.

Smoke command:

```sh
STANCU_TRANSACTIONS=20000 \
STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=64 \
STANCU_BENCHMARK_RUNS=1 \
STANCU_WARMUPS=0 \
STANCU_MODES="heap rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch" \
STANCU_OUTPUT_DIR=/tmp/stancu-direct-epoch-smoke-2026-05-08 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

200k command:

```sh
STANCU_BUILD=0 \
STANCU_TRANSACTIONS=200000 \
STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=64 \
STANCU_BENCHMARK_RUNS=3 \
STANCU_WARMUPS=1 \
STANCU_MODES="heap improved-safezone rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch" \
STANCU_OUTPUT_DIR=/Users/siyaoliu/rift/cache/stancu-direct-epoch-200k-2026-05-08 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

1M command:

```sh
STANCU_BUILD=0 \
STANCU_TRANSACTIONS=1000000 \
STANCU_ITEMS_PER_TX=8 \
STANCU_TX_PER_REGION=64 \
STANCU_BENCHMARK_RUNS=3 \
STANCU_WARMUPS=1 \
STANCU_MODES="heap improved-safezone rift-streaming rift-checked-direct-epoch rift-checked-safezone-direct-epoch" \
STANCU_OUTPUT_DIR=/Users/siyaoliu/rift/cache/stancu-direct-epoch-1m-2026-05-08 \
  zsh sandbox/run_stancu_region_instrumented_matrix.sh
```

20k smoke rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Region opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---|---:|---:|
| heap | 4.754 | 0.731 | 0.000 | 0 | 0 / 0 / 0 | 7929856 | 4921059990652481282 |
| Rift Streaming | 4.403 | 0.000 | 0.031 | 180000 | 1 / 1 / 312 | 7946240 | 4921059990652481282 |
| checked direct epoch | 3.528 | 0.000 | 0.030 | 160000 | 1 / 1 / 313 | 7946240 | 4921059990652481282 |
| scoped checked direct epoch | 3.304 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 7962624 | 4921059990652481282 |

200k rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Region opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---|---:|---:|
| heap | 45.211 | 4.595 | 0.000 | 0 | 0 / 0 / 0 | 7929856 | -1953196317317355226 |
| improved SafeZone | 37.468 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 7995392 | -1953196317317355226 |
| Rift Streaming | 44.880 | 0.000 | 0.223 | 1800000 | 1 / 1 / 3124 | 7946240 | -1953196317317355226 |
| checked direct epoch | 35.196 | 0.000 | 0.213 | 1600000 | 1 / 1 / 3125 | 7946240 | -1953196317317355226 |
| scoped checked direct epoch | 31.638 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 7995392 | -1953196317317355226 |

1M rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Region opens/closes/resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---|---:|---:|
| heap | 225.798 | 23.901 | 0.000 | 0 | 0 / 0 / 0 | 7929856 | -3971418759171958816 |
| improved SafeZone | 186.122 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 8044544 | -3971418759171958816 |
| Rift Streaming | 219.668 | 0.000 | 1.019 | 9000000 | 1 / 1 / 15624 | 7946240 | -3971418759171958816 |
| checked direct epoch | 174.137 | 0.000 | 1.040 | 8000000 | 1 / 1 / 15625 | 7946240 | -3971418759171958816 |
| scoped checked direct epoch | 160.198 | 0.299 | 0.000 | 0 | 0 / 0 / 0 | 7995392 | -3971418759171958816 |

Interpretation:

- Direct checked epoch changes the Stancu-style result from a trusted
  Rift-vs-heap story into a checked-region win. At 1M, scoped checked direct
  epoch is `160.198 ms` versus heap `225.798 ms`, improved SafeZone
  `186.122 ms`, and trusted Streaming `219.668 ms`.
- The Rift streaming checked epoch is also faster than improved SafeZone at
  200k and 1M in this same-run follow-up, though the SafeZone-backed scoped
  checked backend is the fastest row.
- The result matches the design: transaction line/order objects have a coarse
  batch lifetime; durable accounting arrays remain heap control metadata; the
  epoch closes in bulk after the batch.
- The lower `median_rift_alloc_object_total` in checked direct epoch is a
  runtime-counter artifact of the open allocation path and should not be used
  as the logical object count. Use `logical_region_objects` for the intended
  candidate population.

Caveats:

- This is not SPECjbb2005 and not the Stancu et al. static analysis. It is a
  small transaction-shaped local accounting probe.
- `explicit_region_boundaries=1` means one logical allocation-placement site in
  the benchmark, not a real source annotation count produced by the compiler.
- The current harness uses manual accounting. It does not yet provide
  compiler-produced annotation counts, region-freed byte accounting, or
  capture-check rejection cases.
