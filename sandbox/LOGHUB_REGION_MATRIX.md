# LogHub Region Matrix

Date: 2026-05-07
Last updated: 2026-05-08 09:10 CEST

Status: implemented real-input candidate. The matrix has a generated fallback
for compile/smoke validation and a file-backed byte-line path for extracted
real LogHub files. The first real BGL rows and the richer q3 template/session
extension are correctness-valid and show modest throughput/RSS/fixed-memory
value, but not a flagship GC-heavy case.

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

Default runs include heap, improved SafeZone-32k, and checked scoped
page-token. Set `RIFT_BENCH_INCLUDE_CONTROLS=1` or pass `LOGHUB_MODES=...` to
include rootless/trusted controls.

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

### Full BGL q2, single-run scale probe

| Mode | Loaded lines | Elapsed ms | GC ms | GC collections | RSS bytes | Output |
|---|---:|---:|---:|---:|---:|---:|
| heap-immix | `4747963` | `32161.391` | `595.599` | `7` | `576012288` | `562` |
| safezone-improved-32k | `4747963` | `31459.104` | `0.000` | `0` | `490258432` | `562` |
| rift-trusted-streaming | `4747963` | `30899.595` | `0.000` | `0` | `658178048` | `562` |
| rift-checked-safezone-page-token | `4747963` | `31165.087` | `0.000` | `0` | `490946560` | `562` |

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
study." Next benchmark search should continue to RIoTBench/Theodolite-style
IoT records or another provenance-clean NDJSON/log workload with heavier
natural object materialization.
