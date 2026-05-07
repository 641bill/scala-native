# NEXMark Region Matrix

Date: 2026-05-01
Last updated: 2026-05-07 13:51 CEST

Status: first NEXMark-style methodology benchmark for the broader
stream-processing win envelope. This is not an exact Apache Beam NEXMark
reproduction. It is a deterministic local Scala Native auction-stream matrix
that preserves one logical program across heap and Rift modes while changing
allocation placement and lifetime policy.

## Workload

Implementation:

- `sandbox/src/main/scala-next/NexmarkRegionMatrix.scala`
- `sandbox/run_nexmark_region_matrix.sh`

Queries:

- `q0`: mixed `Person`/`Auction`/`Bid` passthrough; all event objects are
  retained until bucket close.
- `q1`: bid currency conversion; each input bid and converted output object is
  bucket-owned.
- `q2`: low-output bid selection; every input bid is bucket-owned, but only
  selected bids produce output.
- `q3`: incremental join/filter shape; person and auction records are
  bucket-owned while primitive arrays track active seller eligibility.
- `q4`: category-average shape; bid records are bucket-owned while category
  count/sum state stays in primitive arrays.
- `q5`: hot-auction sliding-window shape; bid objects are bucket-owned while
  durable window counts/sums stay in primitive heap arrays.
- `q8`: new-user/new-auction window join; person, auction, and join-output
  objects are bucket-owned while durable join counts stay in primitive heap
  arrays.
- `q9`: winning-bid core shape; bid and winning-output records are
  bucket-owned while current max metadata stays in primitive arrays.
- `q11`: session-window shape; bidder session records are bucket-owned while
  active session counts stay in primitive arrays.

Modes:

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone bucket regions with
  `SAFEZONE_ROOTS_MODE=0`. This is a runtime control, not a checked API mode.
- `safezone-improved`: closeable SafeZone bucket regions with
  `SAFEZONE_ROOTS_MODE=1`.
- `heap-join-api`: Q8-only specialized heap join-window control. This exists
  to keep the reusable join API comparison fair; it is not part of the generic
  all-query matrix.
- `rift-checked`: checked `RiftRegion.streaming` plus
  `StreamAppendWindow`/cursor bucket close.
- `rift-checked-join-api`: Q8-only checked `StreamJoinWindow` API control.
- `rift-hp`: trusted low-level HPZone bucket regions.
- `rift-streaming`: trusted low-level Streaming bucket regions.

Default settings:

- `NEXMARK_EVENTS=1000000`
- `NEXMARK_EVENTS_PER_BUCKET=25000`
- `NEXMARK_WINDOW_BUCKETS=8`
- `NEXMARK_AUCTION_SPACE=65536`
- `NEXMARK_PERSON_SPACE=65536`
- `NEXMARK_CATEGORY_SPACE=64`
- `NEXMARK_Q2_SELECT_MODULO=128`
- `NEXMARK_SAMPLE_EVERY=8192`
- `NEXMARK_WARMUPS=1`
- `NEXMARK_BENCHMARK_RUNS=3`

The default runner query set is now
`q0 q1 q2 q3 q4 q5 q8 q9 q11`. Historical tables below cover only
`q0/q1/q2/q5/q8` unless explicitly stated otherwise.

## 2026-05-07 Post-Fast-Path Selected Rows

Source: `evidence/POST_FAST_PATH_SELECTED_SWEEP_2026_05_07.md`.

Command shape:

```sh
NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q3 q8 q9 q11" \
NEXMARK_MODES="heap safezone-improved rift-checked" \
zsh sandbox/run_nexmark_region_matrix.sh
```

All rows matched checksum/output count.

| Query | Heap ms / GC ms | Improved SafeZone ms / GC ms | Checked Rift ms / GC ms | Interpretation |
|---|---:|---:|---:|---|
| q3 | `305.799` / `26.411` | `300.271` / `11.704` | `285.356` / `9.958` | checked generated-methodology win |
| q8 | `466.964` / `33.120` | `452.158` / `16.571` | `443.020` / `15.250` | checked modest win |
| q9 | `798.672` / `84.697` | `744.343` / `33.977` | `724.479` / `29.070` | strongest selected NEXMark row |
| q11 | `215.910` / `17.208` | `227.575` / `5.485` | `209.918` / `3.706` | checked wins elapsed and cuts GC |

Interpretation: the selected Beam-default controls remain positive after the
page-token fast-path checkpoint. These rows are generated methodology
evidence; they are not exact Apache Beam runner evidence.

Apache Beam compatibility source:

- Beam NEXMark is generated, not file-backed. Use
  `NEXMARK_BEAM_DEFAULTS=1` to switch this local generator to the Beam-default
  profile recorded in `../evidence/BENCHMARK_DATA_SOURCES.md`: 100000 events,
  100 concurrent auctions, 1000 concurrent persons, 10 event-rate buckets per
  active window, and 5 categories.
- `NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip`
  can be set to record which Beam source archive/configuration is being used
  as the comparison contract. The benchmark does not read that zip at runtime.

## Commands

Compile/link:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt \
  "project sandbox3_next" \
  "set Compile / mainClass := Some(\"NexmarkRegionMatrix\")" \
  nativeLink
```

20k smoke:

```bash
NEXMARK_EVENTS=20000 \
NEXMARK_BENCHMARK_RUNS=1 \
NEXMARK_WARMUPS=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-smoke \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q0 q5" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-1m-q0-q5 \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q3 q9" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-1m-q3-q9 \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q4 q11" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-1m-q4-q11 \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh
```

Beam-default smoke:

```bash
NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=20000 \
NEXMARK_BENCHMARK_RUNS=1 \
NEXMARK_WARMUPS=0 \
NEXMARK_MODES="heap rift-hp" \
NEXMARK_QUERIES="q1" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-smoke \
zsh sandbox/run_nexmark_region_matrix.sh
```

100k medians:

```bash
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-100k \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh
```

1M medians:

```bash
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-1m \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh
```

## Validation

- 20k smoke matched checksum/output count across all modes and queries.
- 100k and 1M medians matched checksum/output count across all modes and
  queries.
- After adding SafeZone controls, a 20k current-SafeZone smoke matched
  checksum/output count across `heap`, `safezone-current`, `rift-hp`, and
  `rift-streaming` for Q1/Q5/Q8.
- A corrected 100k SafeZone roots-mode follow-up matched checksum/output count
  across `heap`, `safezone-current`, `safezone-improved`, `rift-checked`,
  `rift-hp`, and `rift-streaming` for Q1/Q5/Q8.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
- Beam-default generated smoke passed for Q1 heap/HPZone with matching
  checksum/output count. This is input/config wiring only, not exact Beam
  runner evidence.
- Q3/Q4/Q9/Q11 are implemented and were measured in the Beam-default 1M
  follow-up below. Q3 is the only expanded query that currently gives a
  checked Rift win over both heap and improved SafeZone.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"` passed `91/91`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"` passed `36/36`.
- After adding `StreamJoinWindow`, the targeted suites passed again:
  compiler tests `93/93`, native checked runtime tests `37/37`.

## 100k Results

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0 | heap | 19.016 | 2.250 | 0.000 | 0 | 0 / 0 | 21348352 | 100000 |
| q0 | rift-checked | 19.676 | 0.972 | 0.035 | 100000 | 5 / 5 | 26263552 | 100000 |
| q0 | rift-hp | 19.784 | 0.919 | 0.020 | 100000 | 4 / 4 | 26951680 | 100000 |
| q0 | rift-streaming | 19.673 | 0.982 | 0.015 | 100000 | 4 / 4 | 27017216 | 100000 |
| q1 | heap | 39.511 | 5.747 | 0.000 | 0 | 0 / 0 | 39256064 | 100000 |
| q1 | rift-checked | 39.023 | 1.978 | 0.054 | 200000 | 5 / 5 | 48873472 | 100000 |
| q1 | rift-hp | 38.390 | 1.871 | 0.064 | 200000 | 4 / 4 | 50479104 | 100000 |
| q1 | rift-streaming | 38.574 | 2.037 | 0.061 | 200000 | 4 / 4 | 50544640 | 100000 |
| q2 | heap | 28.939 | 2.733 | 0.000 | 0 | 0 / 0 | 39239680 | 780 |
| q2 | rift-checked | 30.362 | 2.835 | 0.021 | 100780 | 5 / 5 | 44154880 | 780 |
| q2 | rift-hp | 29.839 | 1.912 | 0.021 | 100780 | 4 / 4 | 44990464 | 780 |
| q2 | rift-streaming | 30.287 | 1.877 | 0.029 | 100780 | 4 / 4 | 44924928 | 780 |
| q5 | heap | 35.023 | 0.000 | 0.000 | 0 | 0 / 0 | 74891264 | 13 |
| q5 | rift-checked | 34.521 | 0.000 | 0.018 | 100000 | 5 / 5 | 44171264 | 13 |
| q5 | rift-hp | 35.223 | 0.000 | 0.022 | 100000 | 4 / 4 | 44875776 | 13 |
| q5 | rift-streaming | 34.725 | 0.000 | 0.029 | 100000 | 4 / 4 | 44941312 | 13 |
| q8 | heap | 31.382 | 2.186 | 0.000 | 0 | 0 / 0 | 39223296 | 10000 |
| q8 | rift-checked | 31.441 | 0.928 | 0.012 | 30000 | 5 / 5 | 22921216 | 10000 |
| q8 | rift-hp | 30.860 | 0.954 | 0.010 | 30000 | 4 / 4 | 23085056 | 10000 |
| q8 | rift-streaming | 30.790 | 0.923 | 0.019 | 30000 | 4 / 4 | 23150592 | 10000 |

### SafeZone Roots-Mode 100k Follow-Up

Command:

```bash
NEXMARK_BUILD=0 \
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q1 q5 q8" \
NEXMARK_MODES="heap safezone-current safezone-improved rift-checked rift-hp rift-streaming" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-safezone-roots-100k \
zsh sandbox/run_nexmark_region_matrix.sh
```

`safezone-current` runs the binary `safezone` mode with
`SAFEZONE_ROOTS_MODE=0`. `safezone-improved` runs the same binary mode with
`SAFEZONE_ROOTS_MODE=1`.

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1 | heap | 54.378 | 5.593 | 0.000 | 0 | 0 / 0 | 75137024 | 100000 |
| q1 | safezone-current | 57.561 | 2.958 | 0.000 | 0 | 0 / 0 | 50741248 | 100000 |
| q1 | safezone-improved | 54.777 | 2.964 | 0.000 | 0 | 0 / 0 | 50708480 | 100000 |
| q1 | rift-checked | 55.661 | 1.852 | 0.021 | 200000 | 5 / 5 | 49004544 | 100000 |
| q1 | rift-hp | 53.008 | 1.833 | 0.024 | 200000 | 4 / 4 | 50610176 | 100000 |
| q1 | rift-streaming | 54.026 | 1.849 | 0.024 | 200000 | 4 / 4 | 50675712 | 100000 |
| q5 | heap | 33.043 | 0.000 | 0.000 | 0 | 0 / 0 | 74973184 | 13 |
| q5 | safezone-current | 33.505 | 0.000 | 0.000 | 0 | 0 / 0 | 45088768 | 13 |
| q5 | safezone-improved | 32.435 | 0.000 | 0.000 | 0 | 0 / 0 | 45056000 | 13 |
| q5 | rift-checked | 34.993 | 0.000 | 0.011 | 100000 | 5 / 5 | 44269568 | 13 |
| q5 | rift-hp | 32.865 | 0.000 | 0.011 | 100000 | 4 / 4 | 44957696 | 13 |
| q5 | rift-streaming | 32.899 | 0.000 | 0.011 | 100000 | 4 / 4 | 45023232 | 13 |
| q8 | heap | 29.598 | 1.960 | 0.000 | 0 | 0 / 0 | 39272448 | 10000 |
| q8 | safezone-current | 29.297 | 1.076 | 0.000 | 0 | 0 / 0 | 23216128 | 10000 |
| q8 | safezone-improved | 29.919 | 1.130 | 0.000 | 0 | 0 / 0 | 23216128 | 10000 |
| q8 | rift-checked | 29.146 | 0.878 | 0.004 | 30000 | 5 / 5 | 22986752 | 10000 |
| q8 | rift-hp | 29.911 | 0.874 | 0.004 | 30000 | 4 / 4 | 23134208 | 10000 |
| q8 | rift-streaming | 28.766 | 0.885 | 0.004 | 30000 | 4 / 4 | 23199744 | 10000 |

Interpretation:

- Improved SafeZone is the relevant SafeZone baseline. It closes most of the
  current SafeZone Q1 gap and is the fastest Q5 row in this 100k run.
- Q1 remains a modest trusted Rift row: HPZone is faster than heap and improved
  SafeZone, while checked Rift is slightly slower than both.
- Q5 remains mostly an aggregate/top-scan shape, not a Rift checked win.
- Q8 is a near-tie among heap, SafeZone, and Rift, with Streaming fastest in
  this local 100k run.

### Beam-Default Generated Profile

Command:

```bash
NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-100k \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_BEAM_DEFAULTS=1 \
NEXMARK_BEAM_SOURCE=/Users/siyaoliu/rift/cache/benchmark-data/apache-beam/apache-beam-2.73.0-source-release.zip \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_QUERIES="q1 q2 q8" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-beam-1m-q1-q2-q8 \
NEXMARK_BUILD=0 \
zsh sandbox/run_nexmark_region_matrix.sh
```

This is still generated input. It aligns this local generator with Beam-default
configuration knobs; it is not an Apache Beam runner result. Checksums and
output counts matched across all modes.

100k medians:

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0 | heap | 28.095 | 3.211 | 0.000 | 0 | 0 / 0 | 39354368 | 100000 |
| q0 | safezone-improved | 30.021 | 2.059 | 0.000 | 0 | 0 / 0 | 27230208 | 100000 |
| q0 | rift-checked | 29.596 | 1.004 | 0.014 | 100000 | 11 / 11 | 26591232 | 100000 |
| q0 | rift-hp | 28.968 | 0.995 | 0.014 | 100000 | 10 / 10 | 27377664 | 100000 |
| q1 | heap | 60.097 | 7.093 | 0.000 | 0 | 0 / 0 | 75104256 | 100000 |
| q1 | safezone-improved | 56.151 | 2.904 | 0.000 | 0 | 0 / 0 | 50724864 | 100000 |
| q1 | rift-checked | 58.841 | 1.982 | 0.032 | 200000 | 11 / 11 | 49070080 | 100000 |
| q1 | rift-hp | 56.495 | 2.073 | 0.034 | 200000 | 10 / 10 | 50839552 | 100000 |
| q2 | heap | 35.990 | 0.000 | 0.000 | 0 | 0 / 0 | 75071488 | 958 |
| q2 | safezone-improved | 40.565 | 2.310 | 0.000 | 0 | 0 / 0 | 45236224 | 958 |
| q2 | rift-checked | 43.844 | 1.986 | 0.035 | 100958 | 11 / 11 | 44498944 | 958 |
| q2 | rift-hp | 38.701 | 2.030 | 0.066 | 100958 | 10 / 10 | 45285376 | 958 |
| q5 | heap | 29.654 | 0.000 | 0.000 | 0 | 0 / 0 | 75087872 | 13 |
| q5 | safezone-improved | 29.987 | 0.000 | 0.000 | 0 | 0 / 0 | 45105152 | 13 |
| q5 | rift-checked | 31.204 | 0.000 | 0.015 | 100000 | 11 / 11 | 44498944 | 13 |
| q5 | rift-hp | 29.963 | 0.000 | 0.029 | 100000 | 10 / 10 | 45285376 | 13 |
| q8 | heap | 35.044 | 2.787 | 0.000 | 0 | 0 / 0 | 39337984 | 19000 |
| q8 | safezone-improved | 32.256 | 1.441 | 0.000 | 0 | 0 / 0 | 23740416 | 19000 |
| q8 | rift-checked | 31.468 | 0.992 | 0.012 | 39000 | 11 / 11 | 23592960 | 19000 |
| q8 | rift-hp | 32.794 | 1.017 | 0.013 | 39000 | 10 / 10 | 23756800 | 19000 |

1M medians for the planned Q1/Q2/Q8 subset:

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1 | heap | 579.038 | 93.511 | 0.000 | 0 | 0 / 0 | 75153408 | 1000000 |
| q1 | safezone-improved | 561.787 | 43.793 | 0.000 | 0 | 0 / 0 | 86605824 | 1000000 |
| q1 | rift-checked | 557.251 | 18.468 | 0.202 | 2000000 | 101 / 101 | 84885504 | 1000000 |
| q1 | rift-hp | 538.451 | 18.399 | 0.217 | 2000000 | 100 / 100 | 86654976 | 1000000 |
| q1 | rift-streaming | 541.503 | 18.332 | 0.223 | 2000000 | 100 / 100 | 86491136 | 1000000 |
| q2 | heap | 375.514 | 38.751 | 0.000 | 0 | 0 / 0 | 75153408 | 9988 |
| q2 | safezone-improved | 382.855 | 18.669 | 0.000 | 0 | 0 / 0 | 81035264 | 9988 |
| q2 | rift-checked | 390.105 | 15.129 | 0.239 | 1009988 | 101 / 101 | 80297984 | 9988 |
| q2 | rift-hp | 379.646 | 18.960 | 0.140 | 1009988 | 100 / 100 | 81084416 | 9988 |
| q2 | rift-streaming | 377.816 | 15.056 | 0.187 | 1009988 | 100 / 100 | 80920576 | 9988 |
| q8 | heap | 331.599 | 24.097 | 0.000 | 0 | 0 / 0 | 75137024 | 199000 |
| q8 | safezone-improved | 326.569 | 12.809 | 0.000 | 0 | 0 / 0 | 77496320 | 199000 |
| q8 | rift-checked | 315.545 | 11.895 | 0.058 | 399000 | 101 / 101 | 77348864 | 199000 |
| q8 | rift-hp | 321.910 | 11.274 | 0.111 | 399000 | 100 / 100 | 77479936 | 199000 |
| q8 | rift-streaming | 321.610 | 11.208 | 0.092 | 399000 | 100 / 100 | 77627392 | 199000 |

Additional 1M Beam-default rows after the stream-GC refocus:

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q0 | heap | 548.184 | 81.336 | 86.004 | 3 | 0.000 | 0 | 39387136 | 1000000 |
| q0 | safezone-improved | 482.774 | 27.524 | 28.171 | 3 | 0.000 | 0 | 45187072 | 1000000 |
| q0 | rift-checked | 497.319 | 17.443 | 17.493 | 3 | 0.220 | 1000000 | 44531712 | 1000000 |
| q0 | rift-hp | 467.895 | 17.524 | 17.977 | 3 | 0.124 | 1000000 | 45318144 | 1000000 |
| q0 | rift-streaming | 470.501 | 17.008 | 17.852 | 3 | 0.132 | 1000000 | 45137920 | 1000000 |
| q3 | heap | 304.190 | 27.529 | 28.281 | 3 | 0.000 | 0 | 39403520 | 98266 |
| q3 | safezone-improved | 293.586 | 14.376 | 14.906 | 3 | 0.000 | 0 | 41172992 | 98266 |
| q3 | rift-checked | 287.169 | 11.394 | 11.753 | 3 | 0.059 | 298266 | 40910848 | 98266 |
| q3 | rift-hp | 297.678 | 11.791 | 11.796 | 3 | 0.055 | 298266 | 41385984 | 98266 |
| q3 | rift-streaming | 290.374 | 11.602 | 12.717 | 3 | 0.054 | 298266 | 41205760 | 98266 |
| q4 | heap | 567.739 | 38.535 | 50.975 | 3 | 0.000 | 0 | 146784256 | 123 |
| q4 | safezone-improved | 581.302 | 27.187 | 28.684 | 3 | 0.000 | 0 | 152567808 | 123 |
| q4 | rift-streaming | 579.229 | 22.167 | 33.763 | 3 | 0.180 | 1000000 | 152535040 | 123 |
| q5 | heap | 393.184 | 29.424 | 43.397 | 3 | 0.000 | 0 | 146784256 | 123 |
| q5 | safezone-improved | 391.742 | 17.183 | 18.004 | 3 | 0.000 | 0 | 152535040 | 123 |
| q5 | rift-checked | 395.000 | 14.444 | 14.666 | 3 | 0.132 | 1000000 | 151863296 | 123 |
| q5 | rift-hp | 389.188 | 14.723 | 16.256 | 3 | 0.126 | 1000000 | 152649728 | 123 |
| q5 | rift-streaming | 386.869 | 14.516 | 14.661 | 3 | 0.129 | 1000000 | 152485888 | 123 |
| q9 | heap | 778.606 | 83.179 | 84.030 | 3 | 0.000 | 0 | 146784256 | 922 |
| q9 | safezone-improved | 733.171 | 33.577 | 34.497 | 3 | 0.000 | 0 | 152567808 | 922 |
| q9 | rift-checked | 764.372 | 29.483 | 29.704 | 3 | 0.198 | 1000922 | 151879680 | 922 |
| q9 | rift-hp | 739.200 | 29.443 | 29.575 | 3 | 0.138 | 1000922 | 152698880 | 922 |
| q9 | rift-streaming | 752.774 | 29.380 | 30.185 | 3 | 0.165 | 1000922 | 152567808 | 922 |
| q11 | heap | 255.418 | 19.927 | 37.120 | 3 | 0.000 | 0 | 75202560 | 250197 |
| q11 | safezone-improved | 237.400 | 5.772 | 9.753 | 3 | 0.000 | 0 | 82411520 | 250197 |
| q11 | rift-checked | 240.065 | 3.885 | 10.958 | 3 | 0.210 | 1250197 | 81313792 | 250197 |
| q11 | rift-hp | 226.862 | 3.879 | 7.545 | 3 | 0.142 | 1250197 | 82444288 | 250197 |
| q11 | rift-streaming | 233.387 | 3.916 | 7.411 | 3 | 0.136 | 1250197 | 82264064 | 250197 |

Interpretation:

- Beam-default Q0 is now a strong trusted-runtime stream-object row: HPZone is
  `467.895 ms` versus heap `548.184 ms`, and median GC drops from
  `81.336 ms` to about `17 ms`. Checked Rift also beats heap, but not improved
  SafeZone.
- Beam-default Q1 is now the most promising NEXMark-profile row: HPZone is
  faster than heap and improved SafeZone, and checked Rift is slightly faster
  than improved SafeZone while cutting measured GC sharply.
- Beam-default Q3 is the new best checked expanded-query row: checked Rift is
  faster than heap and improved SafeZone, with low Rift op time. This should be
  the next NEXMark operator-profile candidate.
- Beam-default Q8 is a smaller checked win: checked Rift is faster than heap
  and improved SafeZone, but the margin is below the case-study threshold.
- Beam-default Q2 is a ceiling result. Rift lowers GC, but elapsed time remains
  near heap/improved SafeZone or slower.
- Beam-default Q4/Q9/Q11 are mixed controls. Q11 gives a trusted HPZone win
  but improved SafeZone beats checked; Q9 is improved-SafeZone-favored; Q4
  stays CPU/aggregate dominated.
- These rows are useful win-envelope evidence, not exact Beam NEXMark
  evidence.

### Q8 Join API 100k Follow-Up

Command:

```bash
NEXMARK_QUERIES=q8 \
NEXMARK_MODES="heap heap-join-api rift-checked rift-checked-join-api" \
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q8-join-api-fair-100k \
zsh sandbox/run_nexmark_region_matrix.sh
```

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q8 | heap | 34.137 | 3.023 | 0.000 | 0 | 0 / 0 | 39223296 | 10000 |
| q8 | heap-join-api | 1.732 | 0.000 | 0.000 | 0 | 0 / 0 | 21364736 | 10000 |
| q8 | rift-checked | 31.397 | 0.953 | 0.014 | 30000 | 5 / 5 | 22921216 | 10000 |
| q8 | rift-checked-join-api | 2.293 | 0.000 | 0.015 | 30002 | 5 / 5 | 20348928 | 10000 |

## 1M Results

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q0 | heap | 197.059 | 17.992 | 0.000 | 0 | 0 / 0 | 146636800 | 1000000 |
| q0 | rift-checked | 197.494 | 7.559 | 0.171 | 1000000 | 41 / 41 | 84918272 | 1000000 |
| q0 | rift-hp | 194.113 | 7.270 | 0.123 | 1000000 | 40 / 40 | 86343680 | 1000000 |
| q0 | rift-streaming | 196.232 | 7.385 | 0.174 | 1000000 | 40 / 40 | 86474752 | 1000000 |
| q1 | heap | 384.595 | 54.943 | 0.000 | 0 | 0 / 0 | 205914112 | 1000000 |
| q1 | rift-checked | 374.767 | 7.536 | 0.326 | 2000000 | 41 / 41 | 165937152 | 1000000 |
| q1 | rift-hp | 384.774 | 8.038 | 0.363 | 2000000 | 40 / 40 | 169197568 | 1000000 |
| q1 | rift-streaming | 371.404 | 8.365 | 0.389 | 2000000 | 40 / 40 | 169328640 | 1000000 |
| q2 | heap | 297.053 | 33.602 | 0.000 | 0 | 0 / 0 | 146636800 | 7826 |
| q2 | rift-checked | 287.808 | 11.610 | 0.171 | 1007826 | 41 / 41 | 156499968 | 7826 |
| q2 | rift-hp | 294.806 | 10.254 | 0.212 | 1007826 | 40 / 40 | 158187520 | 7826 |
| q2 | rift-streaming | 305.249 | 12.486 | 0.267 | 1007826 | 40 / 40 | 158023680 | 7826 |
| q5 | heap | 350.941 | 20.104 | 0.000 | 0 | 0 / 0 | 205848576 | 123 |
| q5 | rift-checked | 355.100 | 10.231 | 0.189 | 1000000 | 41 / 41 | 215728128 | 123 |
| q5 | rift-hp | 356.015 | 10.359 | 0.220 | 1000000 | 40 / 40 | 217169920 | 123 |
| q5 | rift-streaming | 356.588 | 10.281 | 0.206 | 1000000 | 40 / 40 | 217300992 | 123 |
| q8 | heap | 322.210 | 27.148 | 0.000 | 0 | 0 / 0 | 146669568 | 100000 |
| q8 | rift-checked | 291.832 | 7.413 | 0.054 | 300000 | 41 / 41 | 149684224 | 100000 |
| q8 | rift-hp | 305.338 | 7.388 | 0.069 | 300000 | 40 / 40 | 150077440 | 100000 |
| q8 | rift-streaming | 305.410 | 7.379 | 0.074 | 300000 | 40 / 40 | 150241280 | 100000 |

### Q8 Join API 1M Follow-Up

Command:

```bash
NEXMARK_QUERIES=q8 \
NEXMARK_MODES="heap heap-join-api rift-checked rift-checked-join-api" \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q8-join-api-fair-1m \
zsh sandbox/run_nexmark_region_matrix.sh
```

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q8 | heap | 327.102 | 27.578 | 0.000 | 0 | 0 / 0 | 146669568 | 100000 |
| q8 | heap-join-api | 17.441 | 0.000 | 0.000 | 0 | 0 / 0 | 146407424 | 100000 |
| q8 | rift-checked | 312.551 | 8.867 | 0.139 | 300000 | 41 / 41 | 149733376 | 100000 |
| q8 | rift-checked-join-api | 23.021 | 0.000 | 0.065 | 300002 | 41 / 41 | 124174336 | 100000 |

Interpretation:

- The original generic Q8 row remains useful as a NEXMark-lite same-run result:
  checked Rift beats the generic heap runner and cuts GC.
- The reusable `StreamJoinWindow` API is a framework improvement because it
  factors the Q8 two-sided count/window shape out of benchmark-local arrays
  and appends. It also eliminates measured GC and lowers RSS versus the
  specialized heap join control at 1M.
- It is not a speed win against the fair specialized heap control:
  `rift-checked-join-api` is `23.021 ms` versus `heap-join-api` at
  `17.441 ms`. Therefore, Q8 join API should remain focused framework
  evidence, not a headline application speed claim.

### Q8 Join API Packed-Count Follow-Up

`StreamJoinWindow` now has packed-count put/remove fast paths:
`putJoinLeftInBucketAndCounts`, `putJoinRightInBucketAndCounts`,
`removeJoinLeftAndCounts`, and `removeJoinRightAndCounts`. These return the
left/right counts as one packed `Long`, removing the separate count lookups in
the Q8 hot path. The final version also avoids a duplicate explicit key check
inside the packed methods while keeping the underlying array bounds checks and
the compiler's checked-region store guard.

Commands:

```bash
NEXMARK_QUERIES=q8 \
NEXMARK_MODES="heap-join-api rift-checked-join-api" \
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q8-join-nocheck-100k \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_QUERIES=q8 \
NEXMARK_MODES="heap-join-api rift-checked-join-api" \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=3 \
NEXMARK_WARMUPS=1 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q8-join-nocheck-1m \
zsh sandbox/run_nexmark_region_matrix.sh
```

| Events | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap-join-api | 2.576 | 0.000 | 0.000 | 0 | 0 / 0 | 21331968 | 10000 |
| 100k | rift-checked-join-api | 2.531 | 0.000 | 0.016 | 30002 | 5 / 5 | 20332544 | 10000 |
| 1M | heap-join-api | 17.393 | 0.000 | 0.000 | 0 | 0 / 0 | 146391040 | 100000 |
| 1M | rift-checked-join-api | 20.987 | 0.000 | 0.062 | 300002 | 41 / 41 | 124157952 | 100000 |

Validation after the packed-count API:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `94/94`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `37/37`.

Interpretation:

- The packed-count path improves the 1M checked join API from `23.021 ms` to
  `20.987 ms`, while preserving zero measured GC and lower RSS than the
  specialized heap control.
- The 100k row is a near tie and slightly favors checked Rift in this run.
- The 1M speed gate still fails: `20.987 ms` checked versus `17.393 ms` heap.
  Keep `StreamJoinWindow` as framework/RSS evidence until the remaining API
  overhead is understood.

## Q5 Diagnostic Follow-Up

Diagnostics are enabled with `NEXMARK_Q5_DIAG=1`. These rows are not headline
timing evidence because the diagnostic path times the top-auction scan.

Commands:

```bash
NEXMARK_Q5_DIAG=1 \
NEXMARK_QUERIES=q5 \
NEXMARK_MODES="heap rift-checked rift-hp rift-streaming" \
NEXMARK_EVENTS=100000 \
NEXMARK_BENCHMARK_RUNS=1 \
NEXMARK_WARMUPS=0 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q5-diag-100k \
zsh sandbox/run_nexmark_region_matrix.sh

NEXMARK_Q5_DIAG=1 \
NEXMARK_QUERIES=q5 \
NEXMARK_MODES="heap rift-checked rift-hp rift-streaming" \
NEXMARK_EVENTS=1000000 \
NEXMARK_BENCHMARK_RUNS=1 \
NEXMARK_WARMUPS=0 \
NEXMARK_BUILD=0 \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-q5-diag-1m \
zsh sandbox/run_nexmark_region_matrix.sh
```

Diagnostic counters:

| Scale | Mode | Adds | Removes | Closed buckets | Samples | Top scans | Scan entries | Top scan ms | Final live |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 100k | heap | 100000 | 100000 | 4 | 13 | 13 | 851968 | 4.791 | 0 |
| 100k | rift-checked | 100000 | 100000 | 4 | 13 | 13 | 851968 | 4.803 | 0 |
| 100k | rift-hp | 100000 | 100000 | 4 | 13 | 13 | 851968 | 4.743 | 0 |
| 100k | rift-streaming | 100000 | 100000 | 4 | 13 | 13 | 851968 | 4.863 | 0 |
| 1M | heap | 1000000 | 1000000 | 40 | 123 | 123 | 8060928 | 45.340 | 0 |
| 1M | rift-checked | 1000000 | 1000000 | 40 | 123 | 123 | 8060928 | 46.823 | 0 |
| 1M | rift-hp | 1000000 | 1000000 | 40 | 123 | 123 | 8060928 | 45.434 | 0 |
| 1M | rift-streaming | 1000000 | 1000000 | 40 | 123 | 123 | 8060928 | 45.093 | 0 |

Clean 1M control after adding diagnostics, with diagnostics off:

| Query | Mode | Median ms | GC ms | Rift op ms | Region objects | Opens/closes | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q5 | heap | 361.882 | 19.496 | 0.000 | 0 | 0 / 0 | 205881344 | 123 |
| q5 | rift-checked | 393.415 | 10.672 | 0.211 | 1000000 | 41 / 41 | 215793664 | 123 |
| q5 | rift-hp | 364.071 | 10.490 | 0.216 | 1000000 | 40 / 40 | 217169920 | 123 |
| q5 | rift-streaming | 369.280 | 10.192 | 0.213 | 1000000 | 40 / 40 | 217333760 | 123 |

Interpretation:

- Q5 is not blocked by region operation cost; Rift op time remains below
  `1 ms` at 1M.
- The aggregate scan cost is real but equal across modes: about `45 ms` for
  `123` full scans of `65536` entries.
- The remaining checked loss is mostly per-entry framework/container CPU plus
  live-window footprint, not GC collection time or top-k scanning alone.

## Interpretation

- This is the first non-DEBS application-style evidence added after the
  append-window work. It uses ordinary Scala objects for stream data, not
  primitive-only records.
- `q1` is the best first win: checked Rift is modestly faster than heap at
  1M, and trusted Streaming is the fastest row. GC time drops from `54.943 ms`
  to about `7-8 ms`; RSS also drops by about `36-40 MB`.
- `q2` shows a low-output selection can still benefit at 1M when input event
  objects are bucket-owned. Checked Rift is faster than heap, but RSS is
  higher than heap, so this is an elapsed/GC result rather than a memory win.
- `q0` is a near tie. Region placement reduces GC and RSS, but passthrough
  work alone does not create a large elapsed win.
- `q5` is currently not a win at 1M. The region modes cut measured GC roughly
  in half, but diagnostics show identical operation counts and about `45 ms`
  of top-auction scans in every mode. The remaining loss is checked
  framework/container CPU and live-window footprint.
- Generic `q8` is the strongest NEXMark-lite checked result: checked Rift is
  `291.832 ms` versus heap `322.210 ms` at 1M, with GC time dropping from
  `27.148 ms` to `7.413 ms`. The packed-count `StreamJoinWindow` API is much
  faster than the generic runner and lower-RSS than a specialized heap join,
  but still slower than that fair specialized heap control at 1M.
- Rift operation time stays below `0.4 ms` in all 1M rows. The remaining gaps
  are query/operator CPU and live-window footprint, not region open/close
  overhead.

## Next Steps

- Keep `q1` and checked `q2` as NEXMark-lite positive candidates.
- Keep generic `q8` as a positive checked NEXMark-lite row, but treat
  `StreamJoinWindow` as framework evidence until it beats the specialized heap
  join control. The packed-count path narrowed the 1M checked API gap from
  `23.021 ms` to `20.987 ms`, but did not clear the gate.
- Do not claim `q5` as a window-aggregate win. The next Q5 work should reduce
  checked per-entry/window-container CPU or change the aggregate-maintenance
  shape, with the heap version kept logically aligned.
- Continue building reusable checked operators, but always add fair heap
  operator controls when the API specializes the query loop.
- Do not move back to DEBS ranking/TableRank from this result; TableRank
  remains gated out by its focused 1M profile.
