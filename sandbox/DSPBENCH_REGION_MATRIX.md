# DSPBench Region Matrix

Date: 2026-05-07
Last updated: 2026-05-07 17:51 CEST

Status: implemented first two local DSPBench-family real-input candidates:
Spike Detection and Fraud Detection. This is not an exact DSPBench artifact
reproduction: the benchmark uses public DSPBench sample inputs, but runs local
single-process Scala Native kernels so Storm/Spark/Flink/thread-engine overhead
does not hide memory-management effects.

## Spike Input And Queries

Input source:
`/Users/siyaoliu/rift/cache/benchmark-data/dspbench/source/dspbench-threads/data/sensors.dat`

Provenance:
`GMAP/DSPBench` clone at commit
`00c20da828faf2b960fdb697c61d34cb25461875`.

The file has `79999` usable sensor lines after header/parse filtering. Runs
larger than that replay the same real sample and report `input_replays`.

| Query | Shape | Region lifetime |
|---|---|---|
| `q0-parse` | Parse each sensor line and allocate one ordinary sensor record. | Event buckets. |
| `q1-moving-average` | Allocate sensor and moving-average records; update durable per-device primitive moving-average state. | Event buckets; device state on heap/primitive arrays. |
| `q2-spike-window` | Allocate sensor, average, and spike-candidate records; summarize spike candidates by bucket/window. | Event/window buckets; per-device state on heap/primitive arrays. |

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_EVENTS=20000 \
DSPBENCH_BENCHMARK_RUNS=1 \
DSPBENCH_WARMUPS=0 \
DSPBENCH_QUERIES="q0-parse q1-moving-average q2-spike-window" \
DSPBENCH_MODES="heap-immix rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-spike-smoke-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

100k medians:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=100000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="q0-parse q1-moving-average q2-spike-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-spike-100k-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

1M medians:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="q0-parse q1-moving-average q2-spike-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-spike-1m-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

## 20k Smoke

All three queries matched checksums/output counts between `heap-immix` and
`rift-checked-safezone-page-token`.

| Query | Heap ms | Checked scoped page-token ms | Heap GC ms | Output count |
|---|---:|---:|---:|---:|
| `q0-parse` | `20.923` | `20.766` | `0.000` | `20000` |
| `q1-moving-average` | `22.836` | `23.617` | `0.000` | `40000` |
| `q2-spike-window` | `24.316` | `24.849` | `0.000` | `54` |

## 100k Median Rows

Input replays: `2`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q0-parse` | `heap-immix` | `106.271` | `0.000` | `5.395` | `1/3` | `39632896` | `100000` |
| `q0-parse` | `safezone-improved-32k` | `106.719` | `0.000` | `0.000` | `0/3` | `44023808` | `100000` |
| `q0-parse` | `rift-trusted-streaming` | `107.070` | `0.000` | `0.000` | `0/3` | `44089344` | `100000` |
| `q0-parse` | `rift-checked-safezone-page-token` | `106.630` | `0.000` | `0.000` | `0/3` | `43270144` | `100000` |
| `q1-moving-average` | `heap-immix` | `119.069` | `0.000` | `4.762` | `1/3` | `66797568` | `200000` |
| `q1-moving-average` | `safezone-improved-32k` | `117.489` | `0.000` | `1.640` | `1/3` | `50937856` | `200000` |
| `q1-moving-average` | `rift-trusted-streaming` | `117.783` | `0.000` | `1.256` | `1/3` | `51052544` | `200000` |
| `q1-moving-average` | `rift-checked-safezone-page-token` | `117.364` | `0.000` | `1.654` | `1/3` | `49381376` | `200000` |
| `q2-spike-window` | `heap-immix` | `124.554` | `0.000` | `14.632` | `1/3` | `81166336` | `216` |
| `q2-spike-window` | `safezone-improved-32k` | `126.106` | `0.000` | `0.000` | `0/3` | `76939264` | `216` |
| `q2-spike-window` | `rift-trusted-streaming` | `125.461` | `0.000` | `0.000` | `0/3` | `76906496` | `216` |
| `q2-spike-window` | `rift-checked-safezone-page-token` | `126.254` | `0.000` | `0.000` | `0/3` | `74547200` | `216` |

## 1M Median Rows

Input replays: `13`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q0-parse` | `heap-immix` | `1068.850` | `10.880` | `10.928` | `3/3` | `147030016` | `1000000` |
| `q0-parse` | `safezone-improved-32k` | `1076.718` | `4.061` | `4.965` | `3/3` | `81231872` | `1000000` |
| `q0-parse` | `rift-trusted-streaming` | `1059.202` | `3.371` | `3.627` | `3/3` | `81313792` | `1000000` |
| `q0-parse` | `rift-checked-safezone-page-token` | `1058.429` | `4.269` | `4.471` | `3/3` | `80461824` | `1000000` |
| `q1-moving-average` | `heap-immix` | `1187.525` | `21.421` | `21.882` | `3/3` | `147111936` | `2000000` |
| `q1-moving-average` | `safezone-improved-32k` | `1169.897` | `8.282` | `8.598` | `2/3` | `158351360` | `2000000` |
| `q1-moving-average` | `rift-trusted-streaming` | `1180.927` | `6.127` | `7.372` | `2/3` | `158466048` | `2000000` |
| `q1-moving-average` | `rift-checked-safezone-page-token` | `1163.045` | `8.174` | `8.373` | `2/3` | `156827648` | `2000000` |
| `q2-spike-window` | `heap-immix` | `1271.677` | `32.793` | `33.328` | `3/3` | `206438400` | `2160` |
| `q2-spike-window` | `safezone-improved-32k` | `1260.040` | `12.225` | `14.693` | `2/3` | `223215616` | `2160` |
| `q2-spike-window` | `rift-trusted-streaming` | `1258.164` | `8.900` | `11.391` | `2/3` | `223117312` | `2160` |
| `q2-spike-window` | `rift-checked-safezone-page-token` | `1277.947` | `11.900` | `12.648` | `2/3` | `220741632` | `2160` |

## Interpretation

DSPBench Spike is a useful real-input methodology row, but not the missing
GC-heavy flagship:

- It naturally materializes ordinary stream objects and has a clear
  bucket/window lifetime, so it is a good shape test for page-token style
  regions.
- At 1M, `heap-immix` does collect in every run, but the GC share is small:
  about `1.0%` of q0 elapsed, `1.8%` of q1 elapsed, and `2.6%` of q2 elapsed.
- Region rows cut timed GC substantially. For q2, trusted Streaming reduces
  median GC from `32.793 ms` to `8.900 ms`, but elapsed improves only from
  `1271.677 ms` to `1258.164 ms`.
- The best checked row is q1: checked scoped page-token is `1163.045 ms` versus
  heap `1187.525 ms`, about a `2.1%` modest throughput win. q2 checked scoped
  page-token is slightly slower than heap despite lower GC.
- RSS is mixed: q0 region rows cut RSS sharply, while q1/q2 region rows use
  slightly more RSS than heap at 1M because the live bucket/object topology
  keeps a larger region footprint while durable moving-average state remains
  heap/primitive.

Decision: keep DSPBench Spike as real-input modest/control evidence. Do not
tune it into a case study. The next real-input search target is DSPBench Fraud
Detection over `credit-card.dat`, because the transaction/prediction/alert
shape may force more intermediate object materialization than Spike.

## Fraud Detection Input And Queries

Input source:
`/Users/siyaoliu/rift/cache/benchmark-data/dspbench/source/dspbench-threads/data/credit-card.dat`

The file has `185000` comma-separated transaction rows. Runs larger than that
replay the same real sample and report `input_replays`.

The local port follows the DSPBench Fraud Detection shape at the operator
level:

| Query | Shape | Region lifetime |
|---|---|---|
| `fraud-q0-parse` | Parse each transaction and allocate one ordinary transaction record. | Event buckets. |
| `fraud-q1-predict` | Allocate transaction, prediction, and state-token records; update durable per-entity primitive predictor state. | Event buckets; per-entity predictor state on heap/primitive arrays. |
| `fraud-q2-alert-window` | Allocate transaction, prediction, state-token, and alert records; summarize alerts by bucket/window. | Event/window buckets; per-entity predictor state on heap/primitive arrays. |

The predictor is a deterministic local Markov-style proxy over the DSPBench
state labels in `credit-card.dat`, not the original Java Markov predictor
artifact. Treat these rows as local methodology evidence.

## Fraud Commands

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_EVENTS=20000 \
DSPBENCH_BENCHMARK_RUNS=1 \
DSPBENCH_WARMUPS=0 \
DSPBENCH_QUERIES="fraud-q0-parse fraud-q1-predict fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-smoke-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

100k medians:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=100000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q0-parse fraud-q1-predict fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-100k-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

1M medians:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q0-parse fraud-q1-predict fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-1m-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

## Fraud 20k Smoke

All three queries matched checksums/output counts between `heap-immix` and
`rift-checked-safezone-page-token`.

| Query | Heap ms | Checked scoped page-token ms | Heap GC ms | Output count |
|---|---:|---:|---:|---:|
| `fraud-q0-parse` | `8.628` | `8.858` | `0.000` | `20000` |
| `fraud-q1-predict` | `12.399` | `13.601` | `0.000` | `80000` |
| `fraud-q2-alert-window` | `13.210` | `14.585` | `0.000` | `98` |

## Fraud 100k Median Rows

Input replays: `1`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `fraud-q0-parse` | `heap-immix` | `42.818` | `0.000` | `3.175` | `1/3` | `41943040` | `100000` |
| `fraud-q0-parse` | `safezone-improved-32k` | `43.140` | `0.000` | `0.863` | `1/3` | `36782080` | `100000` |
| `fraud-q0-parse` | `rift-trusted-streaming` | `42.817` | `0.000` | `0.934` | `1/3` | `36847616` | `100000` |
| `fraud-q0-parse` | `rift-checked-safezone-page-token` | `42.949` | `0.000` | `0.808` | `1/3` | `36012032` | `100000` |
| `fraud-q1-predict` | `heap-immix` | `64.488` | `0.000` | `9.926` | `1/3` | `107020288` | `400000` |
| `fraud-q1-predict` | `safezone-improved-32k` | `65.456` | `0.000` | `0.000` | `0/3` | `88866816` | `400000` |
| `fraud-q1-predict` | `rift-trusted-streaming` | `63.934` | `0.000` | `0.000` | `0/3` | `88850432` | `400000` |
| `fraud-q1-predict` | `rift-checked-safezone-page-token` | `66.302` | `0.000` | `0.000` | `0/3` | `85770240` | `400000` |
| `fraud-q2-alert-window` | `heap-immix` | `70.866` | `0.000` | `7.217` | `1/3` | `120979456` | `14360` |
| `fraud-q2-alert-window` | `safezone-improved-32k` | `71.893` | `0.000` | `4.271` | `1/3` | `90177536` | `14360` |
| `fraud-q2-alert-window` | `rift-trusted-streaming` | `70.474` | `0.000` | `2.444` | `1/3` | `90161152` | `14360` |
| `fraud-q2-alert-window` | `rift-checked-safezone-page-token` | `72.088` | `0.000` | `3.886` | `1/3` | `86867968` | `14360` |

## Fraud 1M Median Rows

Input replays: `6`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `fraud-q0-parse` | `heap-immix` | `433.606` | `9.997` | `10.508` | `3/3` | `129204224` | `1000000` |
| `fraud-q0-parse` | `safezone-improved-32k` | `431.301` | `0.000` | `6.373` | `1/3` | `134709248` | `1000000` |
| `fraud-q0-parse` | `rift-trusted-streaming` | `433.833` | `0.000` | `7.644` | `1/3` | `134758400` | `1000000` |
| `fraud-q0-parse` | `rift-checked-safezone-page-token` | `435.159` | `0.000` | `7.193` | `1/3` | `133955584` | `1000000` |
| `fraud-q1-predict` | `heap-immix` | `673.635` | `43.801` | `46.235` | `3/3` | `254427136` | `4000000` |
| `fraud-q1-predict` | `safezone-improved-32k` | `663.673` | `0.000` | `15.562` | `1/3` | `276758528` | `4000000` |
| `fraud-q1-predict` | `rift-trusted-streaming` | `658.144` | `0.000` | `11.576` | `1/3` | `276725760` | `4000000` |
| `fraud-q1-predict` | `rift-checked-safezone-page-token` | `694.019` | `0.000` | `15.622` | `1/3` | `273580032` | `4000000` |
| `fraud-q2-alert-window` | `heap-immix` | `801.790` | `69.686` | `78.665` | `3/3` | `358252544` | `594182` |
| `fraud-q2-alert-window` | `safezone-improved-32k` | `795.836` | `15.179` | `16.006` | `2/3` | `282476544` | `594182` |
| `fraud-q2-alert-window` | `rift-trusted-streaming` | `763.819` | `12.492` | `12.910` | `2/3` | `282460160` | `594182` |
| `fraud-q2-alert-window` | `rift-checked-safezone-page-token` | `822.846` | `15.554` | `16.804` | `2/3` | `278593536` | `594182` |

### Fraud q2 heap-cap follow-up

Command:

```bash
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
DSPBENCH_HEAP_CAPS="uncapped 512M 384M 256M" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-heapcaps-rss-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

This rerun was executed outside the sandbox so macOS `/usr/bin/time -l` could
report RSS. All rows matched checksum `2645894572926148009` and output count
`594182`.

| Mode | Heap cap | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `heap-immix` | `uncapped` | `787.174` | `72.511` | `81.353` | `3/3` | `358236160` |
| `heap-immix` | `512M` | `794.389` | `70.367` | `79.946` | `3/3` | `358268928` |
| `heap-immix` | `384M` | `788.579` | `68.305` | `79.079` | `3/3` | `358268928` |
| `heap-immix` | `256M` | `791.781` | `72.691` | `101.267` | `3/3` | `272449536` |
| `safezone-improved-32k` | `uncapped` | `779.068` | `14.755` | `16.016` | `2/3` | `282460160` |
| `rift-trusted-streaming` | `uncapped` | `756.303` | `0.000` | `16.601` | `1/3` | `385761280` |
| `rift-checked-safezone-page-token` | `uncapped` | `794.566` | `15.857` | `16.406` | `2/3` | `278528000` |

Interpretation: heap caps at `512M` and `384M` do not materially change heap
behavior for this 1M row. The `256M` heap cap lowers RSS but increases the GC
tail to `101.267 ms`, still without creating a fixed-memory throughput win for
checked page-token. `safezone-improved-32k` and checked scoped page-token keep
RSS near `278-282 MB` while cutting timed GC, but checked page-token is still
CPU-overhead limited. The trusted Streaming row remains the fastest elapsed
row, but its RSS is higher in this rerun than in the earlier full Fraud matrix,
so do not use Streaming RSS as a stable claim without another repeated control.

### Fraud q2 checked diagnostic

Command:

```bash
DSPBENCH_BUILD=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=1 \
DSPBENCH_WARMUPS=0 \
DSPBENCH_DIAG=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-diag-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

Diagnostic row from
`/Users/siyaoliu/rift/cache/dspbench-fraud-q2-diag-2026-05-07/run-fraud-q2-alert-window-rift-checked-safezone-page-token-uncapped.log`:

| Mode | Median ms | Median GC ms | RSS bytes | Appended records | Bucket switches | Append ms | Predictor ms | Close cursor ms | Bucket switch ms | Final close ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `rift-checked-safezone-page-token` | `943.068` | `0.000` | `278462464` | `4851373` | `40` | `139.828` | `84.186` | `63.408` | `57.394` | `6.806` |

This is diagnostic-only because per-record timers perturb elapsed time. The
useful signal is qualitative: checked Fraud q2 is not losing because of GC.
Visible cost is spread across allocation+append, durable predictor CPU, bucket
switch/open-close work, and cursor traversal. The next optimization should not
be another heap-cap run; it should reduce checked append/cursor overhead or
compare against a heap implementation with the same cursor traversal shape.

### Fraud q2 checked backend comparison

Command:

```bash
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-checked-backends-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

All rows matched checksum `2645894572926148009` and output count `594182`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Region op ms | Region objects |
|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `792.438` | `72.875` | `76.286` | `3/3` | `358301696` | `0.000` | `0` |
| `safezone-improved-32k` | `779.882` | `14.757` | `15.274` | `2/3` | `282509312` | `0.000` | `0` |
| `rift-trusted-streaming` | `754.721` | `0.000` | `17.554` | `1/3` | `385810432` | `0.453` | `4851373` |
| `rift-checked-page-token` | `810.721` | `10.971` | `12.054` | `2/3` | `278478848` | `0.363` | `4851373` |
| `rift-checked-safezone-page-token` | `807.095` | `14.445` | `16.987` | `2/3` | `278609920` | `0.000` | `0` |

Interpretation: the checked slowdown is not specific to the SafeZone-backed
checked backend. Both checked page-token backends cut RSS and timed GC versus
heap, but both lose elapsed. `safezone-improved-32k` is the fastest rooted/safe
non-checked row. `rift-trusted-streaming` is the fastest elapsed row, but its
RSS is higher in this same-run comparison, so it should be treated as a
trusted-throughput row rather than a stable RSS win. The next checked work
should target the common checked page-token/operator layer before backend
selection.

### Fraud q2 memory-cost diagnostic across modes

Command:

```bash
DSPBENCH_BUILD=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=1 \
DSPBENCH_WARMUPS=0 \
DSPBENCH_DIAG=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-diag2-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

Diagnostic timing is non-headline: per-record timers perturb elapsed time, and
each process prints a heap oracle diagnostic before the measured mode. The
table below uses the mode-specific diagnostic line for each mode; for
`heap-immix`, it uses the second heap diagnostic line from the heap process.

The latest diagnostic print includes `estimated_bucket_open_ms`, because
`bucket_switch_ms` includes expired-bucket close work in page-token modes.

| Mode | Diagnostic elapsed ms | RSS bytes | Append/alloc ms | Predictor ms | Close/traverse ms | Estimated bucket open ms | Final close ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `963.747` | `254459904` | `186.048` | `84.626` | `53.764` | `0.012` | `6.241` |
| `rift-trusted-streaming` | `930.667` | `282296320` | `131.493` | `86.917` | `56.087` | `0.188` | `6.155` |
| `rift-checked-page-token` | `963.104` | `278331392` | `150.361` | `89.099` | `64.032` | `0.299` | `6.876` |
| `rift-checked-safezone-page-token` | `959.978` | `278413312` | `144.353` | `91.120` | `63.456` | `0.990` | `6.994` |

Interpretation:

- `allocation + append` is memory-management/operator overhead, not reclaim.
  It includes object construction, region/heap allocation, and linking the
  record into the bucket/page-token structure.
- In this diagnostic, region allocation+append is not slower than heap:
  trusted Streaming is `131.493 ms`, checked scoped page-token is
  `144.353 ms`, and heap is `186.048 ms`.
- Estimated bucket open/switch is below `1 ms`; it is not a remaining
  optimization target on Fraud q2. Older raw `bucket_switch_ms` values were
  misleading because they included expired-bucket close traversal.
- Checked page-token still loses or only modestly wins clean elapsed because
  it pays extra common operator overhead: close/cursor traversal is roughly
  `7-10 ms` higher than heap/trusted same-shape traversal, and the total
  program still includes parser/replay, predictor, checksum, and traversal CPU
  outside the measured buckets.
- The “faster = higher RSS” pattern is real in some clean rows. The fastest
  path can keep more memory resident because it avoids or delays work that
  trims/reclaims pages aggressively. Do not treat lower GC time as equivalent
  to lower RSS; report elapsed, GC, and RSS separately.

### Fraud q2 page-token fast-path follow-up

This follow-up ran after optimizing the operator-owned page-token close path:

- `StreamAppendCursor.nextOrNull()` replaces close-callback `hasNext` + `next`
  loops in the focused page-token callbacks.
- Page-token-owned close no longer performs a second defensive drain only to
  clear leftover links; generic public append-window close still does.
- `pageTokenAppendRegionFor` now skips general close/open work for monotonic
  same-bucket timestamps when no bucket can expire.

Command:

```bash
DSPBENCH_BUILD=0 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR="/tmp/dspbench-fraud-q2-page-token-fast-1m" \
zsh sandbox/run_dspbench_region_matrix.sh
```

All rows matched checksum `2645894572926148009` and output count `594182`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Region op ms | Region objects |
|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `862.834` | `79.393` | `88.869` | `3/3` | `358268928` | `0.000` | `0` |
| `safezone-improved-32k` | `873.859` | `17.176` | `17.685` | `2/3` | `282509312` | `0.000` | `0` |
| `rift-trusted-streaming` | `834.447` | `11.632` | `12.390` | `2/3` | `282476544` | `0.664` | `4851373` |
| `rift-checked-page-token` | `851.488` | `11.244` | `11.265` | `2/3` | `278413312` | `0.522` | `4851373` |
| `rift-checked-safezone-page-token` | `818.574` | `17.265` | `19.309` | `2/3` | `278544384` | `0.000` | `0` |

Result: the checked SafeZone-backed page-token path is the fastest row in this
dirty fast-path direction check. It is about `5.1%` faster than heap, about
`6.3%` faster than improved SafeZone, and about `1.9%` faster than trusted
Streaming, while cutting RSS from about `358 MB` to about `279 MB`. Because
the worktree was not yet checkpointed, do not use this as final headline
evidence. It is still valuable because it showed that q2 could become a modest
checked real-input win after operator-overhead reduction.

Diagnostic-only rerun:

```bash
DSPBENCH_BUILD=0 \
DSPBENCH_DIAG=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=1 \
DSPBENCH_WARMUPS=0 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR="/tmp/dspbench-fraud-q2-page-token-fast-diag" \
zsh sandbox/run_dspbench_region_matrix.sh
```

Diagnostic timing remains non-headline. Mode-specific diagnostic lines:

| Mode | Append/alloc ms | Predictor ms | Close/traverse ms | Bucket switch/open ms | Final close ms |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `185.740` | `84.752` | `52.269` | `46.463` | `5.817` |
| `rift-trusted-streaming` | `130.294` | `86.660` | `54.779` | `49.058` | `5.899` |
| `rift-checked-page-token` | `150.160` | `94.899` | `62.859` | `56.211` | `6.860` |
| `rift-checked-safezone-page-token` | `143.351` | `83.222` | `62.026` | `55.959` | `6.779` |

Interpretation: allocation+append remains faster in region modes than heap,
and the fast path was enough to flip the clean checked SafeZone-backed row.
The remaining common checked gap is still visible in close/traverse and bucket
switch/open timing, roughly `6-8 ms` over trusted/heap same-shape traversal at
1M. Further optimization should target this common operator layer only after a
profile confirms it is still material on the next application row.

## Fraud Interpretation

Fraud Detection is more promising than Spike for trusted region runtime
evidence, and the 2026-05-07 page-token fast-path work makes q2 the best
checked real-input regression row:

- At 1M, heap GC is visible and grows with richer query shape:
  q0 `9.997 ms`, q1 `43.801 ms`, q2 `69.686 ms`.
- Trusted Streaming wins q1 and q2 in the full Fraud matrix. The best original
  trusted row is q2:
  `763.819 ms` versus heap `801.790 ms`, about `4.7%` faster, while reducing
  RSS from `358252544` to `282460160` bytes and median GC from `69.686 ms` to
  `12.492 ms`.
- Improved SafeZone also modestly beats heap on q1/q2 and cuts RSS on q2.
- The dirty page-token fast-path row made checked scoped page-token fastest:
  `818.574 ms` versus heap `862.834 ms`, improved SafeZone `873.859 ms`, and
  trusted Streaming `834.447 ms`.
- The committed-code rerun is more conservative: trusted Streaming is fastest
  at `788.040 ms`, checked scoped page-token is `810.770 ms`, and heap is
  `820.945 ms`, with checked RSS about `279 MB` versus heap `358 MB`.

Decision: keep Fraud q2 as the strongest current DSPBench real-input candidate
and a modest checked/RSS win over heap after operator-overhead reduction. Do
not overclaim it as the missing GC-heavy flagship: the clean elapsed win is
modest, trusted Streaming is fastest, the input is a local single-process
methodology port, and heap GC is material but not dominant.

### Fraud q2 committed-code safe-fast-path rerun

After adding the page-token no-drain close API and refining
`pageTokenAppendRegionFor` to return the cached child region on same-bucket
timestamps only when the current bucket is the only live bucket, q2 was rerun
against the committed implementation:

```bash
DSPBENCH_BUILD=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR="/Users/siyaoliu/rift/cache/perf-eval/2026-05-07-dspbench-fraud-q2-safe-fast-path" \
zsh sandbox/run_dspbench_region_matrix.sh
```

All rows matched checksum/output count.

| Mode | Median ms | Median GC ms | Max GC ms | RSS bytes |
|---|---:|---:|---:|---:|
| `heap-immix` | `820.945` | `75.928` | `100.789` | `358154240` |
| `safezone-improved-32k` | `817.402` | `17.407` | `17.500` | `282427392` |
| `rift-trusted-streaming` | `788.040` | `11.547` | `12.573` | `282443776` |
| `rift-checked-page-token` | `814.763` | `11.224` | `11.779` | `278396928` |
| `rift-checked-safezone-page-token` | `810.770` | `15.105` | `16.853` | `278593536` |

Interpretation: trusted Streaming is fastest in this rerun. Checked scoped
page-token remains a modest real-input win over heap and cuts RSS by about
`80 MB`, but it is no longer the fastest row. This reinforces the next
engineering target: the safe checked path needs lower common operator/query
CPU to match the trusted lower bound consistently.

### Fraud q2 page-token live-length bookkeeping rerun

After removing page-token-owned generic live-length bookkeeping from the
checked append helper, q2 was rerun as a focused application control:

```bash
DSPBENCH_BUILD=1 \
DSPBENCH_EVENTS=1000000 \
DSPBENCH_BENCHMARK_RUNS=3 \
DSPBENCH_WARMUPS=1 \
DSPBENCH_QUERIES="fraud-q2-alert-window" \
DSPBENCH_MODES="heap-immix rift-trusted-streaming rift-checked-page-token rift-checked-safezone-page-token" \
DSPBENCH_OUTPUT_DIR=/Users/siyaoliu/rift/cache/dspbench-fraud-q2-page-token-no-length-1m-2026-05-07 \
zsh sandbox/run_dspbench_region_matrix.sh
```

All rows matched checksum `2645894572926148009` and output count `594182`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `842.739` | `72.387` | `77.539` | `3/3` | `358252544` |
| `rift-trusted-streaming` | `832.012` | `11.175` | `11.705` | `2/3` | `282443776` |
| `rift-checked-page-token` | `854.207` | `12.948` | `13.054` | `2/3` | `278396928` |
| `rift-checked-safezone-page-token` | `843.380` | `15.245` | `16.612` | `2/3` | `278593536` |

Interpretation: this removes one justified hot-path counter, but it does not
produce a reliable application throughput improvement. Checked scoped
page-token is essentially tied with heap while cutting RSS by about `22%` and
timed GC by about `57 ms`; trusted Streaming remains fastest. Use this as a
negative/no-large-speedup data point for page-token bookkeeping. The next
checked optimization target should be generated allocation lowering
(`allocImpl`/`checkOpen` under statically proven operator ownership), not more
bucket-open/close bookkeeping.
