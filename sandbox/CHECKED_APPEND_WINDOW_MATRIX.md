# Checked Append Window Matrix

Last updated: 2026-05-13 17:45 CEST

Status: focused cheap checked-operator benchmark. This is framework evidence,
not DEBS application evidence.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedAppendWindowMatrix.scala` and run with
`sandbox/run_checked_append_window_matrix.sh`.

## All-Optimizations Page-Token Gate

Date/time: 2026-05-13 17:38 CEST.

This focused 1M append-window gate reruns the heap, legacy checked page-token,
promoted checked page-token, and checked SafeZone-backed page-token rows after
the handle-backed allocation path became the default checked Rift page-token
implementation. All rows matched checksum `-2507118467295660905`.

Source:
`/Users/siyaoliu/rift/cache/checked-append-allopts-20260513`.

Command shape:

```sh
RIFT_FINAL_CLEAN=1 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=5 \
CHECKED_APPEND_WARMUPS=0 \
CHECKED_APPEND_MODES="heap-immix rift-checked-page-token-legacy rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-append-allopts-20260513 \
  zsh sandbox/run_checked_append_window_matrix.sh
```

| Mode | Median ms | Median GC ms | Rift op ms | Opens/Closes/Resets | RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | 38.352 | 12.512 | 0.000 | 0/0/0 | 75104256 | -2507118467295660905 |
| `rift-checked-page-token-legacy` | 24.687 | 0.000 | 0.066 | 41/41/0 | 47562752 | -2507118467295660905 |
| `rift-checked-page-token` | 23.059 | 0.000 | 0.067 | 41/41/0 | 47562752 | -2507118467295660905 |
| `rift-checked-safezone-page-token` | 25.805 | 0.000 | 0.000 | 0/0/0 | 47464448 | -2507118467295660905 |

Interpretation: the promoted default `rift-checked-page-token` is `6.6%`
faster than the legacy checked page-token control, `10.6%` faster than the
checked SafeZone-backed page-token row, and `39.9%` faster than heap in this
focused shape while preserving lower RSS than heap. This is focused framework
evidence that handle-backed allocation is the right default for Rift-backed
operator-owned page/window paths.

## Latest Final-Clean Page-Token Gate

Date/time: 2026-05-12 17:02 CEST.

After the Rift runtime cached allocation-stats mode on each region at open, the
operator-owned page-token rows were rerun at 1M records with five measured
runs. All rows matched the existing checksum.

```sh
RIFT_FINAL_CLEAN=1 \
CHECKED_APPEND_RECORDS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=5 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="rift-checked-page-token rift-checked-safezone-page-token" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-append-region-cached-stats-20260512 \
zsh sandbox/run_checked_append_window_matrix.sh
```

| Mode | Median ms | Median GC ms | RSS bytes | Checksum |
|---|---:|---:|---:|---:|
| `rift-checked-page-token` | 24.618 | 0.000 | 47595520 | -2507118467295660905 |
| `rift-checked-safezone-page-token` | 25.255 | 0.000 | 47480832 | -2507118467295660905 |

Interpretation: this is a modest focused page-token improvement over the clean
open-region gate (`25.007 ms` checked Rift, `25.591 ms` checked SafeZone-backed).
It supports the allocation-stats profile cleanup but does not change the larger
conclusion: remaining cost is allocation/object construction, append/linking,
and query/traversal work.

## Current-Slab Zeroed-Cache Follow-Up

Date/time: 2026-05-13 00:31 CEST.

After the Rift runtime cached current-slab zeroed state on each region, the
same 1M page-token gate was rerun with five measured runs. All rows matched the
existing checksum.

Source:
`/Users/siyaoliu/rift/cache/checked-append-current-slab-zeroed-cache-20260513`.

| Mode | Median ms | Median GC ms | RSS bytes | Checksum |
|---|---:|---:|---:|---:|
| `rift-checked-page-token` | 23.930 | 0.000 | 47595520 | -2507118467295660905 |
| `rift-checked-safezone-page-token` | 24.823 | 0.000 | 47464448 | -2507118467295660905 |

Interpretation: this is a focused page-token improvement over both the clean
open-region gate (`25.007 ms`) and the region-cached stats gate (`24.618 ms`)
for the Rift-backed checked row. The SafeZone-backed row changes only within
normal noise because the code change targets the Rift allocator.

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
- `rift-checked-api-cursor`: the same reusable API, but expired buckets are
  drained through a close-time cursor so close callback dispatch is once per
  bucket rather than once per entry;
- `heap-prepend`: heap baseline for order-insensitive/head-insert buckets;
- `heap-epoch`: heap baseline for epoch/batch append-drain;
- `heap-immix-chunk`: heap baseline for fixed object-array chunks;
- `rift-checked-api-prepend-cursor`: checked `StreamAppendWindow` with
  head-insert `prependWindow` and cursor close;
- `rift-checked-epoch-buffer`: checked `EpochBuffer`, an operator-owned
  append/drain epoch path with one active child region per epoch;
- `rift-checked-safezone-epoch-buffer`: the same epoch path over the
  SafeZone-backed checked backend;
- `rift-checked-chunk-token`: checked `StreamChunkAppendWindow`, an
  operator-owned fixed object-array chunk path;
- `rift-checked-safezone-chunk-token`: the same chunk path over the
  SafeZone-backed checked backend;
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
| `CHECKED_APPEND_CHUNK_SIZE` | `64` |
| `CHECKED_APPEND_WARMUPS` | `1` |
| `CHECKED_APPEND_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `heap-prepend`
- `heap-immix-chunk`
- `rift-checked`
- `rift-checked-api`
- `rift-checked-api-cursor`
- `rift-checked-api-prepend-cursor`
- `rift-checked-chunk-token`
- `rift-checked-safezone-chunk-token`
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
- `RiftRegionCheckedCompilerTest` passed `89/89` after adding the
  `StreamAppendWindow` compiler probes.
- `RiftRegionCheckedTest` passed `35/35` after adding the
  `StreamAppendWindow` runtime probe.
- The 20k smoke, 100k median, and 1M median matched checksums across all four
  original modes.
- The follow-up reusable API runs matched checksums across all five modes.
- The no-callback `streamBucketFor`/`streamAppendWindowBucketFor` follow-up
  also matched checksums across all five modes.
- The cached bucket/region follow-up and cursor close follow-up matched
  checksums across all six modes.
- The 2026-05-05 `EpochBuffer` follow-up matched checksums for `heap-epoch`,
  `rift-checked-epoch-buffer`, and `rift-checked-safezone-epoch-buffer` at
  20k, 100k, and 1M.
- `CHECKED_APPEND_API_DIAG=1` was added after the no-callback gate still
  failed. Diagnostic elapsed times are not headline numbers.
- The RSS-backed 100k and 1M runs were rerun outside the sandbox because macOS
  `/usr/bin/time -l` could not read RSS counters inside the sandbox.

## 2026-05-05 EpochBuffer Follow-Up

`EpochBuffer[T]` is the new reusable checked operator for batch/epoch lifetime
shapes. It is not a sliding-window replacement: the heap control is
`heap-epoch`, which appends records into one epoch bucket, drains the epoch in
bulk, and then moves to the next epoch. The checked modes allocate ordinary
Scala record objects in the active child epoch region and drain with
`closeEpochBufferWithCursor`.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap-epoch rift-checked-epoch-buffer rift-checked-safezone-epoch-buffer" \
CHECKED_APPEND_OUTPUT_DIR=/tmp/checked-append-epoch-1m \
  zsh sandbox/run_checked_append_window_matrix.sh
```

Validation before the focused rows:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `RiftRegionCheckedCompilerTest` passed `108/108`.
- `RiftRegionCheckedTest` passed `48/48`.

| Scale | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Peak RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 20k | `heap-epoch` | 1.410 | 0.000 | 0.000 | 0 | 0 | 0 | 7127040 | -1919398397124784300 |
| 20k | `rift-checked-epoch-buffer` | 0.932 | 0.000 | 0.043 | 20000 | 2 | 2 | 6930432 | -1919398397124784300 |
| 20k | `rift-checked-safezone-epoch-buffer` | 0.783 | 0.000 | 0.000 | 0 | 0 | 0 | 6914048 | -1919398397124784300 |
| 100k | `heap-epoch` | 2.463 | 0.000 | 0.000 | 0 | 0 | 0 | 21364736 | 5190533824430307482 |
| 100k | `rift-checked-epoch-buffer` | 2.703 | 0.000 | 0.007 | 100000 | 5 | 5 | 10731520 | 5190533824430307482 |
| 100k | `rift-checked-safezone-epoch-buffer` | 2.615 | 0.000 | 0.000 | 0 | 0 | 0 | 10747904 | 5190533824430307482 |
| 1M | `heap-epoch` | 27.164 | 5.707 | 0.000 | 0 | 0 | 0 | 21446656 | 6798353749814785397 |
| 1M | `rift-checked-epoch-buffer` | 26.673 | 0.000 | 0.065 | 1000000 | 41 | 41 | 22446080 | 6798353749814785397 |
| 1M | `rift-checked-safezone-epoch-buffer` | 25.448 | 0.000 | 0.000 | 0 | 0 | 0 | 22462464 | 6798353749814785397 |

Interpretation: `EpochBuffer` is a focused positive reusable-operator row at
1M. It is a small elapsed win over the fair heap epoch control by removing a
`5.707 ms` heap GC component, with SafeZone-backed checked allocation fastest.
At 100k, heap is still slightly faster because there is no GC pressure; the
checked rows mainly reduce RSS. This is framework evidence for Yak/StreamFlex
style append/drain epochs, not yet application evidence.

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

No-callback bucket-lookup follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 0.614 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6160384 | 2522262741738122908 |
| rift-checked | 0.783 | 0.000 | 0.041 | 20000 | 5 | 5 | 0 | 6012928 | 2522262741738122908 |
| rift-checked-api | 1.506 | 0.000 | 0.040 | 20000 | 5 | 5 | 0 | 6012928 | 2522262741738122908 |
| rift-trusted-hp | 0.979 | 0.000 | 0.036 | 20000 | 4 | 4 | 0 | 5980160 | 2522262741738122908 |
| rift-trusted-streaming | 1.755 | 0.000 | 0.099 | 20000 | 4 | 4 | 0 | 5980160 | 2522262741738122908 |

Cached bucket/region plus cursor close follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 1.224 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6209536 | 2522262741738122908 |
| rift-checked | 1.115 | 0.000 | 0.044 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-checked-api | 1.314 | 0.000 | 0.040 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-checked-api-cursor | 1.093 | 0.000 | 0.039 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-trusted-hp | 0.945 | 0.000 | 0.042 | 20000 | 4 | 4 | 0 | 6045696 | 2522262741738122908 |
| rift-trusted-streaming | 0.932 | 0.000 | 0.035 | 20000 | 4 | 4 | 0 | 6045696 | 2522262741738122908 |

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

No-callback bucket-lookup follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2.872 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21331968 | 4594055666086494054 |
| rift-checked | 3.363 | 0.000 | 0.009 | 100000 | 5 | 5 | 0 | 15761408 | 4594055666086494054 |
| rift-checked-api | 6.841 | 0.000 | 0.009 | 100000 | 5 | 5 | 0 | 15761408 | 4594055666086494054 |
| rift-trusted-hp | 4.202 | 0.000 | 0.007 | 100000 | 4 | 4 | 0 | 15679488 | 4594055666086494054 |
| rift-trusted-streaming | 4.319 | 0.000 | 0.009 | 100000 | 4 | 4 | 0 | 15745024 | 4594055666086494054 |

Cached bucket/region plus cursor close follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 2.666 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21315584 | 4594055666086494054 |
| rift-checked | 3.367 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-checked-api | 4.602 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-checked-api-cursor | 4.137 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-trusted-hp | 4.181 | 0.000 | 0.007 | 100000 | 4 | 4 | 0 | 15679488 | 4594055666086494054 |
| rift-trusted-streaming | 4.124 | 0.000 | 0.007 | 100000 | 4 | 4 | 0 | 15745024 | 4594055666086494054 |

Interpretation: 100k is still below the useful allocation-pressure threshold.
Heap is fastest, GC is not measurable, and Rift's main benefit is lower RSS.
The reusable `StreamAppendWindow` API is correctness-valid but still not a
100k speed win. The cached-bucket path improves `rift-checked-api` materially
versus the original `7.473 ms`, and cursor close improves it further, but both
remain slower than heap and manual checked at this scale.

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

No-callback bucket-lookup follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 37.455 | 11.906 | 0.000 | 0 | 0 | 0 | 0 | 75038720 | -2507118467295660905 |
| rift-checked | 33.157 | 0.000 | 0.084 | 1000000 | 41 | 41 | 0 | 47513600 | -2507118467295660905 |
| rift-checked-api | 66.023 | 0.000 | 0.082 | 1000000 | 41 | 41 | 0 | 47513600 | -2507118467295660905 |
| rift-trusted-hp | 42.558 | 0.000 | 0.091 | 1000000 | 40 | 40 | 0 | 47382528 | -2507118467295660905 |
| rift-trusted-streaming | 42.142 | 0.000 | 0.077 | 1000000 | 40 | 40 | 0 | 47497216 | -2507118467295660905 |

Cached bucket/region follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 38.559 | 11.581 | 0.000 | 0 | 0 | 0 | 0 | 74989568 | -2507118467295660905 |
| rift-checked | 33.172 | 0.000 | 0.083 | 1000000 | 41 | 41 | 0 | 47497216 | -2507118467295660905 |
| rift-checked-api | 39.372 | 0.000 | 0.084 | 1000000 | 41 | 41 | 0 | 47480832 | -2507118467295660905 |
| rift-trusted-hp | 41.805 | 0.000 | 0.076 | 1000000 | 40 | 40 | 0 | 47349760 | -2507118467295660905 |
| rift-trusted-streaming | 43.017 | 0.000 | 0.091 | 1000000 | 40 | 40 | 0 | 47480832 | -2507118467295660905 |

Cached bucket/region plus cursor close follow-up:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 35.705 | 11.095 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | -2507118467295660905 |
| rift-checked | 32.367 | 0.000 | 0.077 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| rift-checked-api | 37.705 | 0.000 | 0.073 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| rift-checked-api-cursor | 34.708 | 0.000 | 0.074 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| rift-trusted-hp | 40.429 | 0.000 | 0.070 | 1000000 | 40 | 40 | 0 | 47366144 | -2507118467295660905 |
| rift-trusted-streaming | 40.105 | 0.000 | 0.070 | 1000000 | 40 | 40 | 0 | 47497216 | -2507118467295660905 |

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
- The per-entry reusable `StreamAppendWindow` close API did not pass the
  performance gate: no-callback `rift-checked-api` improved from `76.057 ms`
  to `66.023 ms`, and cached bucket/region use improved it to `39.372 ms`, but
  it remained slower than same-run heap and outside the 1.15x manual-checked
  gate.
- The cursor close API passes the focused 1M gate. `rift-checked-api-cursor`
  is faster than same-run heap (`34.708 ms` versus `35.705 ms`), within 1.15x
  of same-run manual checked (`32.367 ms`), keeps RSS far below heap and level
  with manual checked, and keeps Rift op time below `1 ms`.
- This supports `StreamAppendWindow` as a reusable checked operator primitive,
  but DEBS should still wait for a deliberate integration step. The passing
  shape is cursor close, not the older per-entry close callback.

## Cursor-Reuse Follow-Up, 2026-04-30

Implementation change:

- `StreamAppendWindow` now owns one reusable `StreamAppendCursor` object.
- `closeAppendWindowBucketsBeforeWithCursor` and
  `closeAllAppendWindowBucketsWithCursor` reset that cursor for each closed
  bucket instead of allocating a fresh cursor object per bucket.
- Public API signatures did not change.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `89/89`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `35/35`.
- 20k, 100k, and 1M focused matrix runs matched checksums across modes.

20k smoke:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 0.563 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6209536 | 2522262741738122908 |
| rift-checked | 0.788 | 0.000 | 0.038 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-checked-api | 1.091 | 0.000 | 0.038 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-checked-api-cursor | 1.011 | 0.000 | 0.034 | 20000 | 5 | 5 | 0 | 6078464 | 2522262741738122908 |
| rift-trusted-hp | 1.202 | 0.000 | 0.062 | 20000 | 4 | 4 | 0 | 6045696 | 2522262741738122908 |
| rift-trusted-streaming | 1.007 | 0.000 | 0.046 | 20000 | 4 | 4 | 0 | 6045696 | 2522262741738122908 |

100k 3-run median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 3.327 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21315584 | 4594055666086494054 |
| rift-checked | 3.420 | 0.000 | 0.011 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-checked-api | 4.070 | 0.000 | 0.009 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-checked-api-cursor | 3.627 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15777792 | 4594055666086494054 |
| rift-trusted-hp | 4.341 | 0.000 | 0.007 | 100000 | 4 | 4 | 0 | 15679488 | 4594055666086494054 |
| rift-trusted-streaming | 4.264 | 0.000 | 0.009 | 100000 | 4 | 4 | 0 | 15745024 | 4594055666086494054 |

1M all-mode 3-run median:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 38.670 | 11.893 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | -2507118467295660905 |
| rift-checked | 34.605 | 0.000 | 0.086 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| rift-checked-api | 42.432 | 0.000 | 0.088 | 1000000 | 41 | 41 | 0 | 47546368 | -2507118467295660905 |
| rift-checked-api-cursor | 44.344 | 0.000 | 0.107 | 1000000 | 41 | 41 | 0 | 47546368 | -2507118467295660905 |
| rift-trusted-hp | 43.577 | 0.000 | 0.097 | 1000000 | 40 | 40 | 0 | 47366144 | -2507118467295660905 |
| rift-trusted-streaming | 43.546 | 0.000 | 0.089 | 1000000 | 40 | 40 | 0 | 47513600 | -2507118467295660905 |

1M confirmation subset:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 37.494 | 11.576 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | -2507118467295660905 |
| rift-checked | 33.286 | 0.000 | 0.087 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| rift-checked-api-cursor | 35.527 | 0.000 | 0.093 | 1000000 | 41 | 41 | 0 | 47546368 | -2507118467295660905 |

Interpretation:

- Reusing the cursor object is a small framework cleanup, not a headline
  performance result.
- The focused confirmation subset still clears the cursor API gate, but the
  all-mode 1M run had a noisy/non-winning cursor row. Use the earlier
  cached-bucket plus cursor result and this confirmation as correctness and
  gate evidence, not as evidence that cursor-object reuse itself is faster.
- RSS is effectively unchanged. Cursor object allocation was not the memory
  driver in the focused append-window matrix.

## Prepend-Cursor Follow-Up, 2026-04-30

Implementation change:

- Added experimental `RiftRegion.prependWindow`.
- The compiler guard treats `prependWindow` like `appendWindow`: direct
  unrooted heap values are rejected.
- Added `heap-prepend` and `rift-checked-api-prepend-cursor` focused modes so
  the comparison uses the same head-insert logical order on both sides.
- DEBS was not changed.

Validation:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `91/91`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `36/36`.
- 20k, 100k, and 1M focused prepend runs matched checksums.

Prepend-only focused rows:

| Input | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20k | heap-prepend | 1.428 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6209536 | 3441449352371702976 |
| 20k | rift-checked-api-prepend-cursor | 1.541 | 0.000 | 0.050 | 20000 | 5 | 5 | 0 | 6078464 | 3441449352371702976 |
| 100k | heap-prepend | 2.870 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 21315584 | 5398035291417411760 |
| 100k | rift-checked-api-prepend-cursor | 3.610 | 0.000 | 0.008 | 100000 | 5 | 5 | 0 | 15777792 | 5398035291417411760 |
| 1M | heap-prepend | 36.700 | 11.147 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | 7790559636484650435 |
| 1M | rift-checked-api-prepend-cursor | 34.943 | 0.000 | 0.076 | 1000000 | 41 | 41 | 0 | 47529984 | 7790559636484650435 |

Same-binary append-vs-prepend 1M comparison:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 36.746 | 11.394 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | -2507118467295660905 |
| rift-checked-api-cursor | 34.597 | 0.000 | 0.075 | 1000000 | 41 | 41 | 0 | 47529984 | -2507118467295660905 |
| heap-prepend | 36.836 | 11.367 | 0.000 | 0 | 0 | 0 | 0 | 75022336 | 7790559636484650435 |
| rift-checked-api-prepend-cursor | 34.662 | 0.000 | 0.076 | 1000000 | 41 | 41 | 0 | 47529984 | 7790559636484650435 |

Interpretation:

- `prependWindow` is a valid order-insensitive checked append-window operation
  and clears the same 1M threshold as cursor close: lower elapsed than the
  matching heap-prepend baseline, no measured GC, and lower RSS.
- It does not improve on the existing append-cursor shape in the focused
  matrix. The same-binary 1M rows are effectively tied.
- Therefore this is framework/control evidence only. Do not integrate
  `prependWindow` into DEBS unless a DEBS path specifically needs
  order-insensitive head insertion for code clarity or a new focused result
  shows a real advantage.

## Opt-In API Diagnostics

Command shape:

```sh
CHECKED_APPEND_API_DIAG=1 \
CHECKED_APPEND_MODES="rift-checked-api" \
CHECKED_APPEND_BENCHMARK_RUNS=1 \
CHECKED_APPEND_WARMUPS=0 \
  zsh sandbox/run_checked_append_window_matrix.sh
```

Diagnostic rows are single-run and perturbing. They are for counter
interpretation only.

| Input | bucket lookups | current-bucket hits | bucket opens | appends | close buckets | close entries | final live length |
|---|---:|---:|---:|---:|---:|---:|---:|
| 100k | 4 | 99996 | 4 | 100000 | 4 | 100000 | 0 |
| 1M | 40 | 999960 | 40 | 1000000 | 40 | 1000000 | 0 |

Interpretation: after benchmark-side bucket/region caching, actual API bucket
lookups happen once per bucket, not once per event. The remaining per-entry API
gap is close callback dispatch and intrusive-link traversal shape. Cursor close
addresses that path by dispatching once per bucket and draining entries in a
while loop owned by the callback.

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
