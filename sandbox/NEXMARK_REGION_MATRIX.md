# NEXMark Region Matrix

Date: 2026-04-30

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
- `q5`: hot-auction sliding-window shape; bid objects are bucket-owned while
  durable window counts/sums stay in primitive heap arrays.
- `q8`: new-user/new-auction window join; person, auction, and join-output
  objects are bucket-owned while durable join counts stay in primitive heap
  arrays.

Modes:

- `heap`: ordinary Scala heap objects.
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
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed.
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
  `27.148 ms` to `7.413 ms`. The new specialized `StreamJoinWindow` API is
  much faster than the generic runner and lower-RSS than a specialized heap
  join, but still slower than that fair specialized heap control.
- Rift operation time stays below `0.4 ms` in all 1M rows. The remaining gaps
  are query/operator CPU and live-window footprint, not region open/close
  overhead.

## Next Steps

- Keep `q1` and checked `q2` as NEXMark-lite positive candidates.
- Keep generic `q8` as a positive checked NEXMark-lite row, but treat
  `StreamJoinWindow` as framework evidence until it beats the specialized heap
  join control.
- Do not claim `q5` as a window-aggregate win. The next Q5 work should reduce
  checked per-entry/window-container CPU or change the aggregate-maintenance
  shape, with the heap version kept logically aligned.
- Continue building reusable checked operators, but always add fair heap
  operator controls when the API specializes the query loop.
- Do not move back to DEBS ranking/TableRank from this result; TableRank
  remains gated out by its focused 1M profile.
