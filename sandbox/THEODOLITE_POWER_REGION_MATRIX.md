# Theodolite Power Region Matrix

Date: 2026-05-11
Last updated: 2026-05-11 21:28 CEST

Status: implemented a local single-process Theodolite UC2/UC4-style real-input
kernel over the UCI Household Electric Power Consumption trace. This is not an
exact Theodolite artifact reproduction: Theodolite's public implementation is
Kafka/Flink/Kubernetes-oriented and its documented active-power load path is
generated. This matrix instead pairs the same downsampling/hierarchical
aggregation shape with a public real power-meter trace so external framework
overhead does not hide memory-management behavior.

## Input

Real input:
`/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt`

Source archive:
`/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.zip`

SHA-256:
`9f84b46ade8a2d8e1286ec4b2b6c2987a45a755c59f263be3b3b3d10dfbda3ff`

The extracted file has `2075260` lines including the header. The current parser
skips malformed/missing-value records, so the full q2 row loaded `2049280`
usable measurements.

## Queries

| Query | Shape | Region lifetime |
|---|---|---|
| `q1-downsample` | Allocate one ordinary measurement record per real input line; aggregate by group/window. | Epoch/window batch; durable aggregate arrays stay heap/primitive metadata. |
| `q2-hierarchical` | Allocate one measurement plus three hierarchy contribution records per real input line; aggregate by group/window and hierarchy level. | Epoch/window batch; hierarchy/group state stays heap/primitive metadata. |

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=20000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=5000 \
THEODOLITE_POWER_BENCHMARK_RUNS=1 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q1-downsample q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-smoke-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

1M L2/interpretable rows:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
THEODOLITE_POWER_QUERIES="q1-downsample q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-1m-2026-05-11-rss \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

Full local q2:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=2075259 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-full-q2-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

Full local q2 L1 final-clean:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=2075259 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-full-q2-l1-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

## 20k Smoke

All modes matched checksums/output counts.

| Query | Mode | Median ms | Median GC ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|
| `q1-downsample` | `heap-immix` | `0.559` | `0.000` | `17104896` | `1024` |
| `q1-downsample` | `region-scoped-rooted` | `0.628` | `0.000` | `17039360` | `1024` |
| `q1-downsample` | `region-stream-rootless` | `0.616` | `0.000` | `17088512` | `1024` |
| `q1-downsample` | `checked-epoch-scoped` | `0.569` | `0.000` | `17072128` | `1024` |
| `q2-hierarchical` | `heap-immix` | `2.481` | `0.000` | `19775488` | `4096` |
| `q2-hierarchical` | `region-scoped-rooted` | `3.124` | `0.000` | `20332544` | `4096` |
| `q2-hierarchical` | `region-stream-rootless` | `3.357` | `0.000` | `20348928` | `4096` |
| `q2-hierarchical` | `checked-epoch-scoped` | `2.881` | `0.000` | `20348928` | `4096` |

## 1M Rows

Loaded records: `1000000`. Epoch size: `25000`. Runs: `3`, warmups: `1`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q1-downsample` | `heap-immix` | `26.481` | `0.000` | `0.000` | `0/3` | `145670144` | `10240` |
| `q1-downsample` | `region-scoped-rooted` | `27.722` | `0.000` | `0.000` | `0/3` | `146391040` | `10240` |
| `q1-downsample` | `region-stream-rootless` | `28.082` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q1-downsample` | `checked-epoch-stream` | `25.598` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q1-downsample` | `checked-epoch-scoped` | `24.362` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q2-hierarchical` | `heap-immix` | `133.198` | `12.399` | `12.872` | `3/3` | `145735680` | `40960` |
| `q2-hierarchical` | `region-scoped-rooted` | `145.026` | `0.000` | `0.000` | `0/3` | `149012480` | `40960` |
| `q2-hierarchical` | `region-stream-rootless` | `163.998` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |
| `q2-hierarchical` | `checked-epoch-stream` | `145.491` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |
| `q2-hierarchical` | `checked-epoch-scoped` | `129.511` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |

## Full Local q2

Requested records: `2075259`. Loaded records: `2049280`. Epoch size: `25000`.
Runs: `3`, warmups: `1`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `287.296` | `20.354` | `20.450` | `3/3` | `306806784` | `83968` |
| `region-scoped-rooted` | `319.224` | `0.000` | `0.000` | `0/3` | `310116352` | `83968` |
| `region-stream-rootless` | `336.759` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |
| `checked-epoch-stream` | `301.347` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |
| `checked-epoch-scoped` | `288.495` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |

## Full Local q2 L1 Final-Clean

Requested records: `2075259`. Loaded records: `2049280`. Runs per external
process: `3`. No internal GC/region counter reads in the timed query loop.

| Mode | External real s | External user s | External sys s | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `5.46` | `5.28` | `0.14` | `306872320` | `83968` |
| `region-scoped-rooted` | `5.50` | `5.35` | `0.13` | `304349184` | `83968` |
| `region-stream-rootless` | `5.61` | `5.44` | `0.13` | `304283648` | `83968` |
| `checked-epoch-stream` | `5.49` | `5.33` | `0.13` | `304332800` | `83968` |
| `checked-epoch-scoped` | `5.45` | `5.29` | `0.13` | `304398336` | `83968` |

## Interpretation

Theodolite real power q2 is a useful real-input control, but not the missing
GC-heavy flagship:

- The q1 downsampling query is zero-GC at 1M and should be treated as a
  ceiling/control row.
- The q2 hierarchical query does create ordinary intermediate objects and heap
  GC is visible: `12.399 ms` median timed GC at 1M and `20.354 ms` on the full
  local trace.
- `checked-epoch-scoped` removes timed heap GC and is the best checked/safe
  q2 mode. At 1M it is a small kernel throughput win over heap
  (`129.511 ms` vs `133.198 ms`), but at full local input it is an elapsed tie
  in L2 (`288.495 ms` vs `287.296 ms`).
- The strict L1 final-clean row includes input parsing/preload and confirms
  the same end-to-end story: `checked-epoch-scoped` is effectively tied/slightly
  faster (`5.45 s` versus heap `5.46 s`) with slightly lower RSS (`304 MB`
  versus heap `307 MB`). Parser/file CPU dominates the end-to-end workload.

Decision: keep this as real-input modest/control evidence for the
Theodolite-style time-series aggregation ladder. Do not tune it as a flagship
case unless a larger real power trace or a richer UC4 hierarchy creates
material RSS/tail/fixed-memory pressure.
