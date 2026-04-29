# Checked Append Window Matrix

Status: focused cheap checked-operator benchmark. This is framework evidence,
not DEBS application evidence.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedAppendWindowMatrix.scala` and run with
`sandbox/run_checked_append_window_matrix.sh`.

## Intent

This matrix asks a narrower question than `CheckedStreamWindowRankMatrix`: what
is the Scala Native win envelope for a cheap stream-window operator when the
data path is append-only and bucket-local, without ranking, hash-table, or
top-k maintenance?

Heap and Rift modes use the same logical workload:

- stream records into fixed time buckets;
- allocate one ordinary Scala record object per event;
- mutate that record once after allocation;
- keep records in the current bucket until the bucket expires;
- bulk-close expired buckets and fold their records into durable heap counters;
- sample records during the stream and emit a checksum.

The difference is allocation placement:

- `heap`: ordinary `new` for bucket records and heap bucket metadata;
- `rift-checked`: checked `RiftRegion.streaming` plus child bucket regions;
- `rift-checked-api`: the same checked child-bucket placement through the
  reusable `RiftRegion.StreamAppendWindow` API, whose records extend
  `RiftRegion.StreamAppendNode`;
- `rift-trusted-hp`: trusted `RiftRegion.open(HPZone)` once per bucket;
- `rift-trusted-streaming`: trusted `RiftRegion.open(Streaming)` once per
  bucket.

The checked mode is the main design target: ordinary Scala data objects live in
structured child regions, while durable control counters stay on the heap.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_APPEND_EVENTS` | `1000000` |
| `CHECKED_APPEND_EVENTS_PER_BUCKET` | `25000` |
| `CHECKED_APPEND_WINDOW_BUCKETS` | `8` |
| `CHECKED_APPEND_KEY_SPACE` | `65536` |
| `CHECKED_APPEND_SAMPLE_EVERY` | `4096` |
| `CHECKED_APPEND_WARMUPS` | `1` |
| `CHECKED_APPEND_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `rift-checked`
- `rift-checked-api`
- `rift-trusted-hp`
- `rift-trusted-streaming`

## Commands

Compile and safety checks:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_APPEND_EVENTS=20000 \
CHECKED_APPEND_EVENTS_PER_BUCKET=5000 \
CHECKED_APPEND_WINDOW_BUCKETS=4 \
CHECKED_APPEND_KEY_SPACE=16384 \
CHECKED_APPEND_SAMPLE_EVERY=1024 \
CHECKED_APPEND_BENCHMARK_RUNS=1 \
CHECKED_APPEND_WARMUPS=0 \
CHECKED_APPEND_OUTPUT_DIR=/tmp/checked-append-window-smoke \
  zsh sandbox/run_checked_append_window_matrix.sh
```

100k median with RSS:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=100000 \
CHECKED_APPEND_EVENTS_PER_BUCKET=25000 \
CHECKED_APPEND_WINDOW_BUCKETS=8 \
CHECKED_APPEND_KEY_SPACE=65536 \
CHECKED_APPEND_SAMPLE_EVERY=4096 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_OUTPUT_DIR=/tmp/checked-append-window-100k-rss \
  zsh sandbox/run_checked_append_window_matrix.sh
```

1M median with RSS:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_EVENTS_PER_BUCKET=25000 \
CHECKED_APPEND_WINDOW_BUCKETS=8 \
CHECKED_APPEND_KEY_SPACE=65536 \
CHECKED_APPEND_SAMPLE_EVERY=4096 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_OUTPUT_DIR=/tmp/checked-append-window-1m-rss \
  zsh sandbox/run_checked_append_window_matrix.sh
```

## Validation

Validation run on 2026-04-29:

- `sandbox3_next/compile` passed after adding the harness.
- `RiftRegionCheckedCompilerTest` passed `88/88` after adding the
  `StreamAppendWindow` compiler probes.
- `RiftRegionCheckedTest` passed `34/34` after adding the
  `StreamAppendWindow` runtime probe.
- The 20k smoke, 100k median, and 1M median matched checksums across all four
  original modes.
- The follow-up reusable API runs matched checksums across all five modes.
- The RSS-backed 100k and 1M runs were rerun outside the sandbox because macOS
  `/usr/bin/time -l` could not read RSS counters inside the sandbox.

## 20k Smoke

Configuration:

- `CHECKED_APPEND_EVENTS=20000`
- `CHECKED_APPEND_EVENTS_PER_BUCKET=5000`
- `CHECKED_APPEND_WINDOW_BUCKETS=4`
- `CHECKED_APPEND_KEY_SPACE=16384`
- `CHECKED_APPEND_SAMPLE_EVERY=1024`
- `CHECKED_APPEND_BENCHMARK_RUNS=1`
- `CHECKED_APPEND_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 0.585 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6160384 | 2522262741738122908 |
| rift-checked | 4.019 | 0.000 | 0.053 | 20000 | 5 | 5 | 0 | 5996544 | 2522262741738122908 |
| rift-trusted-hp | 1.156 | 0.000 | 0.042 | 20000 | 4 | 4 | 0 | 5980160 | 2522262741738122908 |
| rift-trusted-streaming | 1.137 | 0.000 | 0.042 | 20000 | 4 | 4 | 0 | 5980160 | 2522262741738122908 |

Reusable node-API smoke after `StreamAppendWindow`:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1.061 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6193152 | 2522262741738122908 |
| rift-checked | 0.758 | 0.000 | 0.036 | 20000 | 5 | 5 | 0 | 6029312 | 2522262741738122908 |
| rift-checked-api | 2.156 | 0.000 | 0.040 | 20000 | 5 | 5 | 0 | 6373376 | 2522262741738122908 |
| rift-trusted-hp | 0.989 | 0.000 | 0.036 | 20000 | 4 | 4 | 0 | 6012928 | 2522262741738122908 |
| rift-trusted-streaming | 1.007 | 0.000 | 0.046 | 20000 | 4 | 4 | 0 | 6012928 | 2522262741738122908 |

Interpretation: the tiny smoke validates correctness only. Heap has no GC
pressure at this size, so Rift overhead dominates elapsed time.

## 100k 3-Run Median

Configuration:

- `CHECKED_APPEND_EVENTS=100000`
- `CHECKED_APPEND_EVENTS_PER_BUCKET=25000`
- `CHECKED_APPEND_WINDOW_BUCKETS=8`
- `CHECKED_APPEND_KEY_SPACE=65536`
- `CHECKED_APPEND_SAMPLE_EVERY=4096`
- `CHECKED_APPEND_BENCHMARK_RUNS=3`
- `CHECKED_APPEND_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2.782 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21282816 | 4594055666086494054 |
| rift-checked | 3.370 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15728640 | 4594055666086494054 |
| rift-trusted-hp | 4.087 | 0.000 | 0.006 | 100000 | 4 | 4 | 0 | 15646720 | 4594055666086494054 |
| rift-trusted-streaming | 4.211 | 0.000 | 0.017 | 100000 | 4 | 4 | 0 | 15712256 | 4594055666086494054 |

Reusable node-API 100k median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2.844 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21364736 | 4594055666086494054 |
| rift-checked | 3.478 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15794176 | 4594055666086494054 |
| rift-checked-api | 7.473 | 0.000 | 0.011 | 100000 | 5 | 5 | 0 | 16547840 | 4594055666086494054 |
| rift-trusted-hp | 4.506 | 0.000 | 0.012 | 100000 | 4 | 4 | 0 | 15712256 | 4594055666086494054 |
| rift-trusted-streaming | 4.315 | 0.000 | 0.008 | 100000 | 4 | 4 | 0 | 15777792 | 4594055666086494054 |

Interpretation: 100k is still below the useful allocation-pressure threshold.
Heap is fastest, GC is not measurable, and Rift's main benefit is lower RSS.
The reusable `StreamAppendWindow` API is correctness-valid but not
performance-ready at this size.

## 1M 3-Run Median

Configuration:

- `CHECKED_APPEND_EVENTS=1000000`
- `CHECKED_APPEND_EVENTS_PER_BUCKET=25000`
- `CHECKED_APPEND_WINDOW_BUCKETS=8`
- `CHECKED_APPEND_KEY_SPACE=65536`
- `CHECKED_APPEND_SAMPLE_EVERY=4096`
- `CHECKED_APPEND_BENCHMARK_RUNS=3`
- `CHECKED_APPEND_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 35.513 | 11.149 | 0.000 | 0 | 0 | 0 | 0 | 74989568 | -2507118467295660905 |
| rift-checked | 32.261 | 0.000 | 0.074 | 1000000 | 41 | 41 | 0 | 47497216 | -2507118467295660905 |
| rift-trusted-hp | 41.641 | 0.000 | 0.093 | 1000000 | 40 | 40 | 0 | 47349760 | -2507118467295660905 |
| rift-trusted-streaming | 41.859 | 0.000 | 0.088 | 1000000 | 40 | 40 | 0 | 47480832 | -2507118467295660905 |

Reusable node-API 1M median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 37.424 | 11.596 | 0.000 | 0 | 0 | 0 | 0 | 75071488 | -2507118467295660905 |
| rift-checked | 34.762 | 0.000 | 0.117 | 1000000 | 41 | 41 | 0 | 47546368 | -2507118467295660905 |
| rift-checked-api | 76.057 | 0.000 | 0.092 | 1000000 | 41 | 41 | 0 | 83247104 | -2507118467295660905 |
| rift-trusted-hp | 42.671 | 0.000 | 0.090 | 1000000 | 40 | 40 | 0 | 47398912 | -2507118467295660905 |
| rift-trusted-streaming | 42.352 | 0.000 | 0.082 | 1000000 | 40 | 40 | 0 | 47529984 | -2507118467295660905 |

Interpretation:

- `rift-checked` is the useful row: it is about `9.2%` faster than heap on
  elapsed time, removes `11.149 ms` of measured GC collection time, and reduces
  peak RSS by about `27.5 MB`.
- Region operation time is tiny (`0.074 ms`) for one million ordinary Scala
  region objects and 41 structured region closes.
- The trusted per-bucket modes are not wins in this harness. This result should
  be reported as a checked child-bucket/operator-shape win, not as a generic
  "trusted HPZone is always faster" claim.
- The result supports the Phase 7/8 direction: cheap checked append/window
  operators can beat Immix when the stream data objects are numerous enough and
  lifetimes are structured.
- The reusable `StreamAppendWindow` API does not pass the performance gate yet:
  `rift-checked-api` is `76.057 ms` at 1M, much slower than heap and the
  manual checked child-bucket shape. Region-op time is still tiny, so the
  overhead is API/container CPU and callback shape, not allocation or close.
  Do not integrate this API into DEBS until the focused matrix is fixed.

## Caveats

- This is not DEBS. It should not be used as application evidence.
- This is not an exact Broom, Yak, StreamFlex, or Stancu artifact reproduction.
- It deliberately avoids ranking, table lookup, and top-k maintenance. Those
  costs are covered by `CheckedStreamWindowRankMatrix` and `TABLERANK_PROFILE`.
- Heap and Rift use the same logical workload, but the checked path has a
  parent streaming region and child bucket handles, while trusted modes use one
  standalone region per bucket. Keep mode comparisons scoped to this harness.
- `StreamAppendWindow` currently requires records to extend
  `RiftRegion.StreamAppendNode`, so it is not yet the final ergonomic story for
  arbitrary ordinary Scala objects. It is a framework probe for low-overhead
  append/window ownership.
