# Yak Region Matrix

Status: Yak-style methodology reproduction harness with validated smoke,
default median, pressure median, external-sort-shaped median, top-word/filter
median, runtime-safety proxy, and promotion/escape proxy runs.

The harness is implemented in
`sandbox/src/main/scala-next/YakRegionMatrix.scala` and run with
`sandbox/run_yak_region_instrumented_matrix.sh`.

## Benchmark Intent

This is not an exact Yak artifact reproduction. Yak evaluated distributed
Hyracks, Hadoop, and GraphChi workloads on the JVM. This local Scala Native
harness reproduces the key comparison shape:

- durable control metadata stays on the GC heap;
- high-volume data-path objects are allocated per epoch;
- epoch-local data dies together at the epoch boundary;
- rare data objects may escape to durable control state through the trusted
  `RiftRegion.RuntimeEpoch` escape/promotion API;
- heap, SafeZone, and Rift variants run the same logical program.

The current local workloads are:

- `wordcount`: durable heap dictionary counters, epoch-local token objects.
- `graphstep`: durable heap vertex state, epoch-local graph update messages.
- `sort`: external-sort-shaped grouped sort. Durable heap group totals remain
  live across epochs; each epoch allocates ordinary record objects, sorts them
  by key, and folds grouped sums into the durable totals.
- `topword`: Hadoop-like map/filter/in-map-combine/top-word shape. Durable
  heap counters and reusable combiner arrays remain control state; each epoch
  allocates ordinary word records, filters them, combines counts, and records
  the top word for the task.
- `promotion`: durable heap counters plus rare retained data objects. Region
  escape handling is routed through `RiftRegion.RuntimeEpoch`, which owns
  barrier checks, remembered-reference counts, and promoted-object counts.

The stream/data objects are ordinary Scala objects. Heap mode allocates them
with `new`; SafeZone mode allocates them in Scala Native SafeZone; Rift modes
allocate them with `region.alloc`.

The `yak-runtime` mode is a local runtime-safety proxy. It uses a reusable Rift
streaming region behind a dynamic epoch object with lifecycle checks. This
models the performance envelope of a Yak-like runtime-managed epoch discipline
on Scala Native. The `promotion` workload adds a local proxy for Yak's
write-barrier/escaping-object mechanism through the Rift memory API, but it
still does not implement real JVM-style object movement, remote stack scanning,
or a distributed runtime.

## Default Configuration

| Environment variable | Default |
|---|---:|
| `YAK_EPOCHS` | `20` |
| `YAK_RECORDS_PER_EPOCH` | `100000` |
| `YAK_KEY_SPACE` | `65536` |
| `YAK_VERTICES` | `100000` |
| `YAK_MESSAGES_PER_EPOCH` | `100000` |
| `YAK_SORT_RECORDS_PER_EPOCH` | `20000` |
| `YAK_ESCAPE_MODULO` | `1000` |
| `YAK_SCRATCH_SLOTS` | `128` |
| `YAK_WARMUPS` | `1` |
| `YAK_BENCHMARK_RUNS` | `3` |

Use `YAK_WORKLOAD=wordcount`, `graphstep`, `sort`, `topword`, `promotion`, or
`all`.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

## Result Status

Compile, smoke, default local medians, epoch-pressure runs, grouped-sort and
top-word/filter pressure runs, runtime-proxy runs, and a promotion/escape proxy
pressure run have been recorded. These are local Yak-style methodology numbers,
not an exact Yak artifact reproduction.

Validation commands run on 2026-04-25 and 2026-04-26:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-region-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=sort YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=10 YAK_SORT_RECORDS_PER_EPOCH=100000 \
YAK_OUTPUT_DIR=/tmp/yak-sort-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=topword YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-topword-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=promotion YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_ESCAPE_MODULO=1000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-promotion-api-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. During the
sbt native-link setup the JVM emitted a code-cache warning, but the native
binary linked and all heap/SafeZone/Rift checksums matched.

## Native-Only Local 3-Run Median, Default Configuration

Configuration:

- `YAK_EPOCHS=20`
- `YAK_RECORDS_PER_EPOCH=100000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=100000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 51.961 | 12.062 | 0.000 | 2000000 | 65536 | 39272448 |
| wordcount | current SafeZone | 41.143 | 0.000 | 0.000 | 2000000 | 65536 | 42237952 |
| wordcount | improved SafeZone | 40.005 | 0.000 | 0.000 | 2000000 | 65536 | 42287104 |
| wordcount | Rift HPZone | 42.075 | 0.000 | 0.053 | 2000000 | 65536 | 42188800 |
| wordcount | Rift Streaming | 41.873 | 0.000 | 0.053 | 2000000 | 65536 | 42172416 |
| graphstep | heap | 56.235 | 9.283 | 0.000 | 2000000 | 100000 | 39272448 |
| graphstep | current SafeZone | 47.417 | 0.000 | 0.000 | 2000000 | 100000 | 42237952 |
| graphstep | improved SafeZone | 46.859 | 0.000 | 0.000 | 2000000 | 100000 | 42287104 |
| graphstep | Rift HPZone | 48.126 | 0.000 | 0.077 | 2000000 | 100000 | 42188800 |
| graphstep | Rift Streaming | 46.909 | 0.000 | 0.072 | 2000000 | 100000 | 42172416 |

Interpretation:

- This table predates adding the `sort` and `topword` workloads to `all`; it
  covers `wordcount` and `graphstep`.
- This reproduces the Yak control/data split locally: durable counters or vertex
  state remain on heap, while two million epoch-local data objects are allocated
  per workload.
- Heap pays measurable GC time. SafeZone and Rift remove the measured heap GC
  path for the epoch-local data objects.
- Rift beats heap on both workloads, but improved SafeZone is the fastest or
  effectively tied at this default scale.
- Rift operation time is small: below `0.1 ms` median per workload.

## External-Sort-Shaped Grouped Sort Median

Date: 2026-04-26

Configuration:

- `YAK_EPOCHS=10`
- `YAK_SORT_RECORDS_PER_EPOCH=100000`
- `YAK_KEY_SPACE=65536`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=sort YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=10 YAK_SORT_RECORDS_PER_EPOCH=100000 \
YAK_OUTPUT_DIR=/tmp/yak-sort-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 237.354 | 3.463 | 0.000 | 0 | 0 / 0 / 0 | 22446080 |
| current SafeZone | 234.644 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24674304 |
| improved SafeZone | 236.047 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24674304 |
| Rift HPZone | 227.393 | 0.283 | 0.047 | 1000000 | 10 / 10 / 0 | 24002560 |
| Rift Streaming | 232.658 | 0.307 | 0.030 | 1000000 | 1 / 1 / 9 | 24002560 |
| Yak-runtime proxy | 242.517 | 0.306 | 0.032 | 1000000 | 1 / 1 / 10 | 24002560 |

Interpretation:

- This is closer to Yak's external-sort/dataflow evaluation shape than the
  earlier wordcount-only path: high-volume record objects are epoch-local, but
  the durable grouped totals remain heap control state.
- Sorting dominates elapsed time, so heap GC is only `3.463 ms`. This is not a
  large-GC-pressure case.
- Rift HPZone is still fastest locally (`227.393 ms` vs heap `237.354 ms`),
  with small region-op overhead. The result is a modest same-program
  allocation-placement win, not a dramatic Yak-style GC win.
- `yak-runtime` is slower than raw Rift and heap here, which again shows that
  dynamic runtime discipline has visible cost when escape handling is not
  needed.

## Hadoop-Style Top-Word/Filter Median

Date: 2026-04-26

Configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_KEY_SPACE=65536`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=topword YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-topword-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 311.527 | 44.949 | 0.000 | 0 | 0 / 0 / 0 | 75038720 |
| current SafeZone | 326.680 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83116032 |
| improved SafeZone | 271.273 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83116032 |
| Rift HPZone | 266.478 | 0.000 | 0.416 | 10000000 | 40 / 40 / 0 | 82968576 |
| Rift Streaming | 262.980 | 0.000 | 0.401 | 10000000 | 1 / 1 / 39 | 82935808 |
| Yak-runtime proxy | 292.179 | 0.000 | 0.418 | 10000000 | 1 / 1 / 40 | 82935808 |

Interpretation:

- This is the closest current local model of Yak's Hadoop top-word/filter
  family: durable global counts and reusable combiner arrays remain heap
  control state; high-volume per-record stream objects are epoch-local.
- Rift Streaming beats heap by about `15.6%` and improved SafeZone by about
  `3.1%` in this local median, while removing `44.949 ms` of measured heap GC.
- Rift operation time stays low at about `0.4 ms` for ten million region
  objects. `yak-runtime` still costs more than raw Rift Streaming.

## Epoch-Pressure 3-Run Median

Configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=250000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 240.541 | 35.225 | 0.000 | 10000000 | 65536 | 75071488 |
| wordcount | current SafeZone | 237.253 | 0.000 | 0.000 | 10000000 | 65536 | 83181568 |
| wordcount | improved SafeZone | 197.373 | 0.000 | 0.000 | 10000000 | 65536 | 83197952 |
| wordcount | Rift HPZone | 214.210 | 0.000 | 0.314 | 10000000 | 65536 | 83099648 |
| wordcount | Rift Streaming | 209.944 | 0.000 | 0.275 | 10000000 | 65536 | 83066880 |
| graphstep | heap | 239.001 | 29.774 | 0.000 | 10000000 | 100000 | 75071488 |
| graphstep | current SafeZone | 275.357 | 0.000 | 0.000 | 10000000 | 100000 | 83181568 |
| graphstep | improved SafeZone | 206.425 | 0.000 | 0.000 | 10000000 | 100000 | 83197952 |
| graphstep | Rift HPZone | 229.265 | 0.000 | 0.406 | 10000000 | 100000 | 83099648 |
| graphstep | Rift Streaming | 224.885 | 0.000 | 0.365 | 10000000 | 100000 | 83066880 |

Interpretation:

- The pressure run scales the epoch-local data path to ten million ordinary
  Scala data objects per workload.
- Rift again removes measured heap GC and beats heap on elapsed time.
- Improved SafeZone is a stronger baseline than Rift in both pressure
  workloads, so this is not a Rift-over-SafeZone win.
- Current SafeZone is workload-sensitive: it is close to heap on `wordcount`
  but slower than heap on `graphstep`.

Caveats:

- This is not an exact Yak reproduction. It does not run Hyracks, Hadoop, or
  GraphChi. The wordcount/graphstep workloads are no-escape epoch workloads;
  the separate promotion workload below exercises Rift's trusted runtime epoch
  escape API, but still lacks generic object movement and stack scanning.
- The benchmark's purpose is to isolate the control/data split and epoch-local
  lifetime shape in Scala Native. Do not compare the absolute speedups directly
  to Yak's distributed JVM results.
- The harness uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future safe capture-checked API.

## Post Allocation-Counter Fix Pressure Verification

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-region-pressure-fixed \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

This rerun was taken after moving Rift allocation counters out of the
per-object atomic hot path and flushing counts at region reset/close.

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 243.522 | 32.850 | 0.000 | 10000000 | 65536 | 75071488 |
| wordcount | current SafeZone | 228.260 | 0.000 | 0.000 | 10000000 | 65536 | 83197952 |
| wordcount | improved SafeZone | 194.933 | 0.000 | 0.000 | 10000000 | 65536 | 83214336 |
| wordcount | Rift HPZone | 224.415 | 0.000 | 0.346 | 10000000 | 65536 | 83083264 |
| wordcount | Rift Streaming | 199.156 | 0.000 | 0.258 | 10000000 | 65536 | 83066880 |
| graphstep | heap | 240.890 | 38.498 | 0.000 | 10000000 | 100000 | 75071488 |
| graphstep | current SafeZone | 273.608 | 0.000 | 0.000 | 10000000 | 100000 | 83197952 |
| graphstep | improved SafeZone | 203.729 | 0.000 | 0.000 | 10000000 | 100000 | 83214336 |
| graphstep | Rift HPZone | 213.454 | 0.000 | 0.409 | 10000000 | 100000 | 83083264 |
| graphstep | Rift Streaming | 209.528 | 0.000 | 0.408 | 10000000 | 100000 | 83066880 |

Interpretation:

- Streaming is now close to improved SafeZone on Yak-style pressure: `199.156 ms`
  vs `194.933 ms` for wordcount and `209.528 ms` vs `203.729 ms` for graphstep.
- Rift still removes measured heap GC and beats heap on both workloads.
- Improved SafeZone remains the strongest baseline, so this is good
  Rift-vs-heap evidence and near-parity with improved SafeZone, not a clean
  Rift-over-SafeZone result.

## Yak-Style Runtime-Safety Proxy

Date: 2026-04-26

Implementation:

- Added mode `yak-runtime`.
- `yak-runtime` keeps durable control state on the heap, as in other modes.
- Epoch-local data objects are allocated in a reusable Rift streaming region.
- Allocation goes through a dynamic epoch object that checks lifecycle state and
  resets the region at epoch boundaries. This represents a runtime-managed
  Yak-style epoch discipline, not the checked Rift API.
- The no-escape `wordcount`/`graphstep` path does not exercise Yak's full
  dynamic promotion or write-barrier mechanism, so those measurements isolate
  the runtime epoch path. The separate `promotion` workload below routes rare
  escaping data objects through `RiftRegion.RuntimeEpoch`.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-smoke \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Validation:

- The smoke run rebuilt and native-linked `YakRegionMatrix`.
- The pressure run completed.
- All heap/SafeZone/Rift/Yak-runtime checksums matched.

Pressure configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_MESSAGES_PER_EPOCH=250000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 255.335 | 51.043 | 0.000 | 0 | 0 / 0 / 0 | 75071488 |
| wordcount | current SafeZone | 233.729 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83165184 |
| wordcount | improved SafeZone | 196.523 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83181568 |
| wordcount | Rift HPZone | 201.921 | 0.000 | 0.414 | 10000000 | 40 / 40 / 0 | 83099648 |
| wordcount | Rift Streaming | 206.291 | 0.000 | 0.330 | 10000000 | 1 / 1 / 39 | 83066880 |
| wordcount | Yak-runtime proxy | 211.585 | 0.000 | 0.300 | 10000000 | 1 / 1 / 40 | 83066880 |
| graphstep | heap | 321.660 | 55.830 | 0.000 | 0 | 0 / 0 / 0 | 75071488 |
| graphstep | current SafeZone | 553.408 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83165184 |
| graphstep | improved SafeZone | 212.354 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83181568 |
| graphstep | Rift HPZone | 217.911 | 0.000 | 0.536 | 10000000 | 40 / 40 / 0 | 83099648 |
| graphstep | Rift Streaming | 214.947 | 0.000 | 0.404 | 10000000 | 1 / 1 / 39 | 83066880 |
| graphstep | Yak-runtime proxy | 234.914 | 0.000 | 0.497 | 10000000 | 1 / 1 / 40 | 83066880 |

Interpretation:

- A pure runtime epoch mechanism on Scala Native is viable for Yak-shaped
  workloads: it removes measured heap GC and beats heap on both pressure
  workloads.
- The runtime proxy is slower than raw Rift Streaming in this run, especially
  on `graphstep` (`234.914 ms` vs `214.947 ms`). That is the expected cost of
  adding dynamic lifecycle checks around the region path.
- Improved SafeZone remains the strongest baseline for `wordcount`, and is
  close to raw Rift modes on `graphstep`. The result is therefore not a
  Rift-over-SafeZone claim.
- This gives the project a useful axis: pure runtime safety can be measured,
  and the checked Rift path should eventually be compared against both raw Rift
  and this runtime proxy.

## Yak-Style Runtime Promotion/Escape Proxy

Date: 2026-04-26

Implementation:

- Added workload `promotion`.
- Each logical record allocates an ordinary Scala `PromoToken` and nested
  `PromoChild`. Heap mode uses `new`; `yak-runtime` places both objects in a
  runtime-managed epoch region.
- Every record performs a heap/control write through the runtime epoch API.
  `RiftRegion.RuntimeEpoch` counts one barrier check per write.
- Every `YAK_ESCAPE_MODULO` records, the token is retained by durable heap
  control state. The runtime epoch counts one remembered reference and two
  promoted objects, then uses a `RuntimePromoter` hook to copy the escaping
  token/child pair to heap.
- This is now a memory-API-level proxy rather than benchmark-inline promotion
  accounting. It is still not a generic object-moving collector, stack scanner,
  or cross-thread remember-set.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=promotion YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_ESCAPE_MODULO=1000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-promotion-api-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Pressure configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_ESCAPE_MODULO=1000`
- `YAK_SCRATCH_SLOTS=128`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Barrier checks | Remembered refs | Promoted objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 424.768 | 43.745 | 0.000 | 0 | 0 | 0 | 0 | 576045056 |
| Yak-runtime proxy | 513.465 | 0.000 | 0.736 | 20000000 | 10000000 | 10000 | 20000 | 220692480 |

Interpretation:

- This is the closest current local Yak model: it keeps the control/data split,
  includes rare escaping data objects, and moves barrier/remember/promotion
  accounting into Rift's trusted runtime epoch API.
- The Yak-runtime path removes measured heap GC and reduces peak RSS versus
  heap while preserving the same logical program and checksum.
- The corrected memory-API-level runtime path is slower than heap in elapsed
  time (`513.465 ms` vs `424.768 ms`). That is an important negative result:
  dynamic promotion/barrier discipline is not free on Scala Native, and the
  static checked Rift path still needs to avoid paying this cost on every
  heap/control write.
- The result is still not exact Yak. It does not include Hyracks/Hadoop/GraphChi
  workloads, distributed execution, compiler-inserted write barriers on
  arbitrary object fields, generic object movement, stack scanning, or STW
  epoch-end coordination.
