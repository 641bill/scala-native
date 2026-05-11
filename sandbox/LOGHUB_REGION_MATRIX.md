# LogHub Region Matrix

Date: 2026-05-07
Last updated: 2026-05-11 20:42 CEST

Status: implemented real-input candidate. The matrix has a generated fallback
for compile/smoke validation and a file-backed byte-line path for extracted
real LogHub files. The first real BGL rows, the richer BGL q3
template/session extension, and the HDFS v1 follow-up are correctness-valid
and show modest throughput/RSS/fixed-memory value, but not a flagship
GC-heavy case.

## Goal

GH Archive byte-slice rows are useful parser-scratch evidence but not
GC-heavy. LogHub is the next candidate because system logs are naturally
record-oriented and can create many short-lived line/token/template objects
with line, block, session, or window lifetimes.

This is the intended first real-input shape:

- q0: one ordinary `LogRecord` object per line;
- q1: one line object plus many token/template objects per line;
- q2: same object stream plus per-window/component counts at bucket close.
- q3: same object stream plus parsed template/session candidate records and
  per-window/session counts.

The heap and region programs share the same logical record/token/window
program. Region rows only change allocation placement and bucket lifetime.

## Inputs

Use real extracted LogHub files once downloaded:

```sh
LOGHUB_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS/HDFS.log
LOGHUB_INPUT_MODE=file-backed
```

Current local real input:

| Dataset | Local file | Lines | Size |
|---|---|---:|---:|
| LogHub BGL | `/Users/siyaoliu/rift/cache/benchmark-data/loghub/BGL/BGL.log` | `4747963` | `743185031` bytes |
| LogHub HDFS v1 | `/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log` | `11175629` | `1576383671` bytes |
| LogHub Spark application subset | `/Users/siyaoliu/rift/cache/benchmark-data/loghub/Spark/application_1485248649253_0132/*.log` | `4242303` | about `416 MiB` directory payload |

The matrix also supports generated smoke input:

```sh
LOGHUB_INPUT_MODE=generated
LOGHUB_LINES=20000
```

## Queries

| Query | Meaning | Region candidate |
|---|---|---|
| `q0-lines` | Allocate one parsed log-line record per line. | Line records with bucket lifetime. |
| `q1-tokens` | Allocate one line record plus token/template records per line. | Line and token records with bucket lifetime. |
| `q2-window-counts` | Allocate line/token records, then count by component bucket on close. | Bucket-owned records plus close-time summary state. |
| `q3-template-session` | Parse BGL message suffixes into template buckets, derive session buckets from node/template fields, allocate template-token and session-candidate records, then count sessions by window. | Richer log/template/session records with bucket lifetime. |

## Modes

The runner accepts:

- `heap-immix`
- `safezone-improved-32k`
- `safezone-rootless-32k`
- `rift-trusted-hp`
- `rift-trusted-streaming`
- `rift-checked-page-token`
- `rift-checked-safezone-page-token`
- `heap-direct-epoch`
- `rift-checked-direct-epoch`
- `rift-checked-safezone-direct-epoch`

Default runs include heap, improved SafeZone-32k, and checked scoped
page-token. Set `RIFT_BENCH_INCLUDE_CONTROLS=1` or pass `LOGHUB_MODES=...` to
include rootless/trusted controls.

The direct-epoch modes currently support generated/indexable q2/q3 rows only.
Real file-backed LogHub rows remain page-token rows until a preloaded real-log
path or streaming bucket iterator is added.

## Commands

Generated 20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_BUILD=1 \
LOGHUB_INPUT_MODE=generated \
LOGHUB_LINES=20000 \
LOGHUB_BENCHMARK_RUNS=1 \
LOGHUB_WARMUPS=0 \
LOGHUB_QUERIES="q0-lines q1-tokens q2-window-counts" \
LOGHUB_MODES="heap-immix rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-generated-smoke-2026-05-07 \
zsh sandbox/run_loghub_region_matrix.sh
```

Real BGL first pass:

```sh
LOGHUB_BUILD=0 \
LOGHUB_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/BGL/BGL.log \
LOGHUB_INPUT_MODE=file-backed \
LOGHUB_LINES=100000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=1 \
LOGHUB_QUERIES="q1-tokens q2-window-counts" \
LOGHUB_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-bgl-100k-2026-05-07 \
zsh sandbox/run_loghub_region_matrix.sh
```

Real BGL richer template/session q3:

```sh
LOGHUB_BUILD=0 \
LOGHUB_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/BGL/BGL.log \
LOGHUB_INPUT_MODE=file-backed \
LOGHUB_LINES=1000000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=1 \
LOGHUB_QUERIES="q3-template-session" \
LOGHUB_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-bgl-q3-2026-05-08 \
zsh sandbox/run_loghub_region_matrix.sh
```

## Classification Gate

LogHub becomes a serious real-input GC-heavy candidate only if:

- actual loaded lines are large enough;
- heap timed GC is a material share of elapsed time, not just a small tail;
- region rows match checksums/output counts;
- a checked or trusted region row beats heap and improved SafeZone by a
  meaningful margin, or materially cuts RSS/GC with no more than about 5%
  elapsed overhead.

If heap GC remains a tiny fraction of elapsed after scaling real HDFS/BGL, park
LogHub as another modest parser/record control and move to DSPBench local
kernels.

## Generated Smoke

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_BUILD=1 \
LOGHUB_INPUT_MODE=generated \
LOGHUB_LINES=20000 \
LOGHUB_BENCHMARK_RUNS=1 \
LOGHUB_WARMUPS=0 \
LOGHUB_QUERIES="q0-lines q1-tokens q2-window-counts" \
LOGHUB_MODES="heap-immix rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-generated-smoke-2026-05-07 \
zsh sandbox/run_loghub_region_matrix.sh
```

The generated smoke compiled and ran successfully. Heap and checked
SafeZone-backed page-token rows matched checksums and output counts for q0/q1/q2.
At 20k generated rows, heap timed GC was zero and elapsed differences are not
headline evidence.

## Real BGL Results

Source summaries:

- `/Users/siyaoliu/rift/cache/loghub-bgl-100k-2026-05-07/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bgl-1m-2026-05-07/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bgl-1m-heapcaps-2026-05-07/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bgl-full-q2-probe-2026-05-07/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bgl-q3-100k-2026-05-08/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bgl-q3-2026-05-08/summary.tsv`

### 100k, 3-run medians

| Query | Mode | Elapsed ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Output |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokens | heap-immix | `642.431` | `17.627` | `45.285` | `2/3` | `148111360` | `1251309` |
| q1-tokens | safezone-improved-32k | `624.527` | `0.000` | `0.000` | `0/3` | `135790592` | `1251309` |
| q1-tokens | rift-trusted-streaming | `622.292` | `0.000` | `0.000` | `0/3` | `135610368` | `1251309` |
| q1-tokens | rift-checked-safezone-page-token | `620.531` | `0.000` | `0.000` | `0/3` | `135823360` | `1251309` |
| q2-window-counts | heap-immix | `647.981` | `17.470` | `50.490` | `2/3` | `148127744` | `13` |
| q2-window-counts | safezone-improved-32k | `621.998` | `0.000` | `0.000` | `0/3` | `135790592` | `13` |
| q2-window-counts | rift-trusted-streaming | `616.252` | `0.000` | `0.000` | `0/3` | `135626752` | `13` |
| q2-window-counts | rift-checked-safezone-page-token | `624.104` | `0.000` | `0.000` | `0/3` | `135823360` | `13` |

### 1M, 3-run medians

| Query | Mode | Elapsed ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Output |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokens | heap-immix | `5568.252` | `99.271` | `103.832` | `3/3` | `408420352` | `13445386` |
| q1-tokens | safezone-improved-32k | `5589.860` | `0.000` | `0.000` | `0/3` | `357842944` | `13445386` |
| q1-tokens | rift-trusted-streaming | `5491.033` | `0.000` | `0.000` | `0/3` | `357679104` | `13445386` |
| q1-tokens | rift-checked-safezone-page-token | `5552.988` | `0.000` | `0.000` | `0/3` | `475856896` | `13445386` |
| q2-window-counts | heap-immix | `5646.824` | `157.198` | `164.560` | `3/3` | `408879104` | `87` |
| q2-window-counts | safezone-improved-32k | `5509.481` | `0.000` | `0.000` | `0/3` | `475856896` | `87` |
| q2-window-counts | rift-trusted-streaming | `5605.787` | `0.000` | `0.000` | `0/3` | `475693056` | `87` |
| q2-window-counts | rift-checked-safezone-page-token | `5636.357` | `0.000` | `0.000` | `0/3` | `475873280` | `87` |

### Richer q3 template/session, 100k and 1M

The q3 row was added on 2026-05-08 to test whether richer real BGL parsing
would naturally materialize enough template/session objects to make heap GC a
larger share of elapsed time. The query keeps the same logical program across
modes: each line produces a base record, template-token records, and a
session-candidate record; window close counts session buckets.

| Scale | Mode | Elapsed ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Output |
|---:|---|---:|---:|---:|---:|---:|---:|
| 100k | heap-immix | `954.149` | `12.132` | `21.654` | `2/3` | `146898944` | `26515` |
| 100k | safezone-improved-32k | `954.043` | `0.000` | `9.194` | `1/3` | `98156544` | `26515` |
| 100k | rift-trusted-streaming | `948.697` | `0.000` | `6.162` | `1/3` | `98009088` | `26515` |
| 100k | rift-checked-safezone-page-token | `948.715` | `0.000` | `8.923` | `1/3` | `98123776` | `26515` |
| 1M | heap-immix | `8683.558` | `84.166` | `117.946` | `3/3` | `290242560` | `243309` |
| 1M | safezone-improved-32k | `8635.167` | `34.787` | `38.138` | `3/3` | `236945408` | `243309` |
| 1M | rift-trusted-streaming | `8615.627` | `21.841` | `22.533` | `3/3` | `236814336` | `243309` |
| 1M | rift-checked-safezone-page-token | `8722.008` | `34.865` | `35.456` | `3/3` | `236961792` | `243309` |

### 1M heap caps, 3-run medians

| Query | Heap cap | Elapsed ms | GC median ms | GC max ms | Max GC collections | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| q1-tokens | `512M` | `5653.886` | `100.719` | `101.371` | `2` | `408862720` |
| q1-tokens | `384M` | `5723.089` | `102.567` | `183.740` | `3` | `406667264` |
| q1-tokens | `256M` | `5807.256` | `194.609` | `220.065` | `4` | `272449536` |
| q2-window-counts | `512M` | `5716.596` | `136.025` | `154.252` | `2` | `408420352` |
| q2-window-counts | `384M` | `5724.792` | `145.934` | `152.610` | `2` | `406667264` |
| q2-window-counts | `256M` | `5768.222` | `199.503` | `265.396` | `4` | `272465920` |

### HDFS v1, 1M, 3-run medians

HDFS v1 was added on 2026-05-08 as a larger real machine-log follow-up after
BGL q3 and RIoTBench/MHEALTH did not expose flagship GC pressure. The 20k
smoke matched checksums/output counts for q1/q2/q3 across heap, improved
SafeZone-32k, trusted Streaming, and checked scoped page-token. The 1M run used
the extracted LogHub HDFS v1 file:

`/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log`

Command:

```sh
LOGHUB_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_LINES=1000000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=1 \
LOGHUB_QUERIES="q1-tokens q2-window-counts q3-template-session" \
LOGHUB_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-hdfs-1m-2026-05-08 \
LOGHUB_BUILD=0 \
zsh sandbox/run_loghub_region_matrix.sh
```

| Query | Mode | Elapsed ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Output |
|---|---|---:|---:|---:|---:|---:|---:|
| q1-tokens | heap-immix | `8059.155` | `139.906` | `191.623` | `3/3` | `408551424` | `13495462` |
| q1-tokens | safezone-improved-32k | `7919.352` | `0.000` | `0.000` | `0/3` | `356286464` | `13495462` |
| q1-tokens | rift-trusted-streaming | `7938.369` | `0.000` | `0.000` | `0/3` | `356171776` | `13495462` |
| q1-tokens | rift-checked-safezone-page-token | `7923.079` | `0.000` | `0.000` | `0/3` | `356319232` | `13495462` |
| q2-window-counts | heap-immix | `8227.369` | `92.659` | `104.790` | `3/3` | `408666112` | `41` |
| q2-window-counts | safezone-improved-32k | `7924.213` | `0.000` | `0.000` | `0/3` | `394887168` | `41` |
| q2-window-counts | rift-trusted-streaming | `7871.713` | `0.000` | `0.000` | `0/3` | `356073472` | `41` |
| q2-window-counts | rift-checked-safezone-page-token | `7871.856` | `0.000` | `0.000` | `0/3` | `394969088` | `41` |
| q3-template-session | heap-immix | `8460.928` | `81.910` | `88.603` | `3/3` | `408666112` | `283857` |
| q3-template-session | safezone-improved-32k | `8360.487` | `44.009` | `46.158` | `3/3` | `319864832` | `283857` |
| q3-template-session | rift-trusted-streaming | `8439.205` | `35.908` | `37.188` | `3/3` | `319143936` | `283857` |
| q3-template-session | rift-checked-safezone-page-token | `8506.708` | `52.221` | `55.345` | `3/3` | `319864832` | `283857` |

Interpretation:

- HDFS q1/q2 are modest real-input wins for the SafeZone/Streaming/page-token
  family, with q2 the strongest checked row: checked scoped page-token
  `7871.856 ms` versus heap `8227.369 ms`, while removing heap's `92.659 ms`
  median timed GC and lowering RSS.
- HDFS q3 is useful RSS/tail evidence but not an elapsed win for checked
  page-token. Improved SafeZone wins elapsed versus heap, and trusted
  Streaming nearly ties heap while cutting RSS/GC.
- The result is stronger than the MHEALTH ceiling row but still not the
  missing "huge GC" real-data case: heap GC remains about 1-2% of elapsed,
  not the dominant bottleneck.
- Timed GC does not explain the full q2 elapsed gap. Heap spends `92.659 ms`
  median in timed GC, while checked scoped page-token is about `355 ms` faster
  than heap. The remaining difference should be reported as broader
  memory-management/mutator-side cost: heap allocation path work, heap
  growth/metadata effects, RSS/cache locality, and region-owned append/close
  bookkeeping. For LogHub rows, read elapsed, RSS, timed GC, and diagnostic
  allocation/append/close buckets together rather than treating timed GC as
  the only memory cost.

### HDFS v1 q2 final-clean L1 row

Final-clean support was added on 2026-05-10. In L1 mode,
`LogHubRegionMatrix` skips warmups, heap-expected replay, internal timing,
GC-stat reads, and Rift-stat reads. The runner records only external
`/usr/bin/time -l` real/user/sys time and max RSS. This row is the clean
headline timing counterpart to the L2 HDFS q2 standard-stats row above.

Command shape:

```sh
RIFT_FINAL_CLEAN=1 \
LOGHUB_BUILD=0 \
LOGHUB_INPUT_MODE=file-backed \
LOGHUB_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_LINES=1000000 \
LOGHUB_LINES_PER_BUCKET=25000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=0 \
LOGHUB_QUERIES="q2-window-counts" \
LOGHUB_MODES="heap-immix safezone-improved-32k rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/tmp/rift-l1-loghub-hdfs-q2-1m-x3-fe8f0d853-r1 \
zsh sandbox/run_loghub_region_matrix.sh
```

Three external repeats were collected from clean child commit `fe8f0d853`.
Each process loads 1M real HDFS log lines and runs q2 three times. The first
attempt used 20 q2 iterations per process; it was stopped after the heap row
because one mode took `165.25 s`, so the report row uses 3 inner iterations.

| Mode | Median external real s | Min/Max external real s | Median user s | Median sys s | Median RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `25.60` | `25.58 / 25.75` | `25.21` | `0.35` | `408649728` | `-4515648042024502814` | `41` |
| `safezone-improved-32k` | `25.35` | `25.33 / 25.39` | `24.96` | `0.31` | `79003648` | `-4515648042024502814` | `41` |
| `rift-checked-safezone-page-token` | `25.56` | `25.56 / 25.58` | `25.16` | `0.31` | `79036416` | `-4515648042024502814` | `41` |

Interpretation: this is an L1 real-input RSS win, not a throughput headline.
Checked scoped page-token essentially ties heap on total file-backed elapsed
(`25.56 s` versus `25.60 s`) while cutting max RSS from about `409 MB` to
about `79 MB`. The L2 row remains the source for GC interpretation: heap q2
spends `92.659 ms` median in timed GC while checked scoped page-token reports
zero timed GC.

### Spark q3 template/session, 1M control row

Spark q3 was added on 2026-05-11 as the richer local LogHub session/template
follow-up after Spark and Windows top-template rows confirmed the reusable
top-k shape but stayed file/parser dominated at L1. The run uses one large
Spark application directory with enough real log lines for a bounded 1M row:

`/Users/siyaoliu/rift/cache/benchmark-data/loghub/Spark/application_1485248649253_0132/*.log`

Command shape:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_BUILD=0 \
LOGHUB_INPUTS="$(printf "%s," /Users/siyaoliu/rift/cache/benchmark-data/loghub/Spark/application_1485248649253_0132/*.log)" \
LOGHUB_INPUT_MODE=file-backed \
LOGHUB_LINES=1000000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=1 \
LOGHUB_QUERIES="q3-template-session" \
LOGHUB_MODES="heap-immix safezone-improved-32k rift-trusted-streaming rift-checked-safezone-page-token" \
LOGHUB_OUTPUT_DIR=/Users/siyaoliu/rift/cache/loghub-spark-q3-1m-2026-05-11 \
zsh sandbox/run_loghub_region_matrix.sh
```

Source summaries:

- `/Users/siyaoliu/rift/cache/loghub-spark-q3-smoke-2026-05-11/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-spark-q3-1m-2026-05-11/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-spark-q3-1m-l1-2026-05-11/summary.tsv`

The 20k smoke matched checksums/output counts. The 1M L2 row loaded 61 Spark
log files and matched output count `287798` across all modes:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Output |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `7602.328` | `140.934` | `168.854` | `3/3` | `408354816` L1 RSS | `287798` |
| `safezone-improved-32k` | `7794.877` | `29.986` | `64.175` | `3/3` | `58310656` L1 RSS | `287798` |
| `rift-trusted-streaming` | `7861.160` | `22.620` | `44.248` | `3/3` | `55934976` L1 RSS | `287798` |
| `rift-checked-safezone-page-token` | `7534.013` | `31.147` | `58.136` | `3/3` | `56098816` L1 RSS | `287798` |

L1 was also run with `RIFT_FINAL_CLEAN=1` to collect max RSS and checksum
status. The RSS numbers above come from that L1 run. Its external elapsed
field is not promoted because two Darwin `/usr/bin/time -l` real-time values
in that run were inconsistent with the user/sys times and observed process
duration; keep L2 elapsed as the interpretation source for this control row.

Interpretation: Spark q3 is another real-input modest/control result. Checked
scoped page-token is only `0.9%` faster than heap in the L2 loop
(`7534.013 ms` versus `7602.328 ms`) while cutting RSS sharply and reducing
timed GC. Heap timed GC is still only about `1.9%` of elapsed, so the row does
not become the missing GC-heavy real-data case. It reinforces the Windows q3
conclusion: richer log template/session materialization alone is still
parser/query dominated unless the workload retains enough ordinary objects for
bulk region close to dominate.

### Full BGL q2, single-run scale probe

| Mode | Loaded lines | Elapsed ms | GC ms | GC collections | RSS bytes | Output |
|---|---:|---:|---:|---:|---:|---:|
| heap-immix | `4747963` | `32161.391` | `595.599` | `7` | `576012288` | `562` |
| safezone-improved-32k | `4747963` | `31459.104` | `0.000` | `0` | `490258432` | `562` |
| rift-trusted-streaming | `4747963` | `30899.595` | `0.000` | `0` | `658178048` | `562` |
| rift-checked-safezone-page-token | `4747963` | `31165.087` | `0.000` | `0` | `490946560` | `562` |

### Generated Direct-Epoch q2/q3 Follow-Up

Direct-epoch modes were added for generated/indexable window rows:

```bash
cd /Users/siyaoliu/rift/scala-native-rift
LOGHUB_INPUT= \
LOGHUB_INPUTS= \
LOGHUB_INPUT_MODE=generated \
LOGHUB_LINES=1000000 \
LOGHUB_BENCHMARK_RUNS=3 \
LOGHUB_WARMUPS=1 \
LOGHUB_QUERIES="q2-window-counts q3-template-session" \
LOGHUB_MODES="gc-heap heap-direct-epoch checked-epoch-stream checked-epoch-scoped" \
LOGHUB_BUILD=0 \
zsh sandbox/run_loghub_region_matrix.sh
```

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output |
|---|---|---:|---:|---:|---:|---:|---:|
| q2-window-counts | gc-heap | `526.803` | `124.142` | `145.882` | `2/3` | `812711936` | `163487` |
| q2-window-counts | heap-direct-epoch | `191.601` | `0.000` | `0.000` | `0/3` | `678690816` | `163487` |
| q2-window-counts | checked-epoch-stream | `193.441` | `0.000` | `0.000` | `0/3` | `678739968` | `163487` |
| q2-window-counts | checked-epoch-scoped | `193.938` | `0.000` | `0.000` | `0/3` | `678707200` | `163487` |
| q3-template-session | gc-heap | `2225.364` | `175.674` | `183.261` | `2/3` | `2291122176` | `312151` |
| q3-template-session | heap-direct-epoch | `1779.064` | `0.000` | `118.088` | `1/3` | `2290974720` | `312151` |
| q3-template-session | checked-epoch-stream | `1784.048` | `0.000` | `127.952` | `1/3` | `2291040256` | `312151` |
| q3-template-session | checked-epoch-scoped | `1792.397` | `0.000` | `124.048` | `1/3` | `2291040256` | `312151` |

Interpretation: generated LogHub q2/q3 strongly favor direct epoch because the
operator closes a bucket epoch after producing primitive summary metadata,
instead of preserving linked records for generic page-token close traversal.
The `heap-direct-epoch` control shows that this is mainly an operator-topology
win. Checked direct epoch remains close to same-shape heap while preserving the
checked lifetime boundary. Real file-backed LogHub q2/q3 still use page-token
until an indexable/preloaded real-input path exists.

Final-clean L1 symmetric direct-summary follow-up, 2026-05-10:

```bash
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
LOGHUB_BUILD=0 \
LOGHUB_LINES=1000000 \
LOGHUB_LINES_PER_BUCKET=25000 \
LOGHUB_BENCHMARK_RUNS=20 \
LOGHUB_WARMUPS=0 \
LOGHUB_INPUT_MODE=generated \
LOGHUB_QUERIES="q2-window-counts q3-template-session" \
LOGHUB_MODES="heap-direct-summary-only checked-epoch-stream checked-epoch-scoped" \
LOGHUB_OUTPUT_DIR=/tmp/rift-l1-loghub-direct-summary-q2q3-1m-x20-eea5894d5-r1 \
zsh sandbox/run_loghub_region_matrix.sh
```

The command was repeated with `r2` and `r3` output directories. Each row is one
external process with 20 in-process 1M-line iterations; the table reports the
median external real time across three processes.

| Query | Mode | Median external real s | Min / max s | Per-iteration ms | Max RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|
| `q2-window-counts` | `heap-direct-summary-only` | `4.23` | `4.19 / 4.24` | `211.5` | `6504448` | `163487` |
| `q2-window-counts` | `checked-epoch-stream` | `4.39` | `4.33 / 4.41` | `219.5` | `6668288` | `163487` |
| `q2-window-counts` | `checked-epoch-scoped` | `4.38` | `4.33 / 4.38` | `219.0` | `6701056` | `163487` |
| `q3-template-session` | `heap-direct-summary-only` | `37.01` | `36.83 / 37.16` | `1850.5` | `8372224` | `312151` |
| `q3-template-session` | `checked-epoch-stream` | `37.65` | `37.15 / 37.71` | `1882.5` | `8437760` | `312151` |
| `q3-template-session` | `checked-epoch-scoped` | `38.00` | `37.36 / 38.33` | `1900.0` | `8388608` | `312151` |

Interpretation: L1 confirms that generated LogHub direct-summary is a symmetric
topology lower bound. Checked direct epoch is within about `4%` of heap
direct-summary for q2 and about `2-3%` for q3. The q3 row remains dominated by
template/session query CPU, so this is not a memory-management win by itself.

### Generated Retained-Epoch q2/q3 Reclaim Control

Retained no-traverse modes were added on 2026-05-09. They keep ordinary
Scala line/token/template/session records linked and alive until the epoch
closes, update primitive summaries on append, and close without traversing
records.

Command:

```bash
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

| Query | Mode | Evidence class | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|
| `q2-window-counts` | `heap-direct-summary-only` | topology lower bound | `216.636` | `0.000` | `0.000` | `0/3` | `678739968` |
| `q2-window-counts` | `heap-epoch-retained-no-traverse` | retained heap control | `464.389` | `64.349` | `70.366` | `2/3` | `812761088` |
| `q2-window-counts` | `checked-epoch-retained-no-traverse` | retained checked region | `413.203` | `0.000` | `0.000` | `0/3` | `305184768` |
| `q2-window-counts` | `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `402.611` | `0.000` | `0.000` | `0/3` | `693960704` |
| `q3-template-session` | `heap-direct-summary-only` | topology lower bound | `1812.573` | `0.000` | `115.524` | `1/3` | `2291040256` |
| `q3-template-session` | `heap-epoch-retained-no-traverse` | retained heap control | `2217.322` | `146.514` | `154.948` | `2/3` | `2291138560` |
| `q3-template-session` | `checked-epoch-retained-no-traverse` | retained checked region | `2213.846` | `43.315` | `81.758` | `3/3` | `834338816` |
| `q3-template-session` | `checked-scoped-epoch-retained-no-traverse` | retained checked scoped region | `2101.599` | `0.000` | `130.038` | `1/3` | `2312257536` |

Interpretation: q2 is a retained throughput/GC/RSS win; checked scoped retained
epoch is `13.3%` faster than retained heap and removes median timed GC. q3 is
mixed but useful: checked scoped retained is `5.2%` faster than retained heap
and removes median timed GC, while checked stream retained cuts RSS sharply but
nearly ties elapsed. The summary-only rows remain topology/operator lower
bounds.

## Interpretation

LogHub BGL is a useful real-input stream/log benchmark, but the first rows do
not make it the missing GC-heavy flagship. At 1M lines, heap allocates about
`13.4M` line/token records and reports GC in every run, but GC is still only
about `99-157 ms` inside roughly `5.6 s` elapsed. At full-file q2 scale, heap
loads all `4747963` lines and spends `595.599 ms` in GC inside `32.161 s`.

The region rows are still valuable:

- they remove timed GC in all 100k/1M/full-file rows;
- they give modest throughput wins in several rows;
- `safezone-improved-32k` and checked SafeZone-backed page-token reduce RSS on
  the full-file q2 probe versus heap;
- heap caps at 256M roughly double 1M heap GC and slow q1/q2, so LogHub can be
  a fixed-memory/tail control.

The q3 template/session extension confirms the same conclusion. It naturally
allocates richer real-input intermediate objects and raises heap GC to
`84.166 ms` median at 1M lines, but that is still under 1% of `8683.558 ms`
elapsed. Region/scoped rows reduce RSS by about `53 MB` and cut the GC tail,
while trusted Streaming is only about `0.8%` faster than heap and checked
scoped page-token is slightly slower. The current conclusion is therefore
"real-input modest throughput/RSS/tail evidence," not "GC-heavy real-data case
study." The HDFS v1 follow-up strengthens the modest real-log story: q2 gives
a clean checked scoped page-token throughput/RSS/GC win over heap, but heap GC
is still only about 1-2% of elapsed. Spark q3 confirms the same pattern on a
larger multi-file Spark log subset: checked scoped page-token slightly wins
the L2 loop and cuts RSS, but heap timed GC remains below 2% of elapsed. Next
benchmark search should continue to Thunderbird if fetched, Theodolite-style
real industrial-energy traces, larger StackOverflow/StackExchange text, or
another provenance-clean NDJSON/security workload with heavier natural object
retention.
