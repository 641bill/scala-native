# Checked StreamWindowIndexedRank Matrix

Status: focused checked-API methodology harness. Results should be treated as
framework/API evidence, not DEBS evidence.

The harness is implemented in
`sandbox/src/main/scala-next/CheckedStreamWindowRankMatrix.scala` and run with
`sandbox/run_checked_stream_window_rank_matrix.sh`.

## Intent

This harness exercises the checked stream-window ranking abstraction added
after `RegionIndexedPriorityQueue`.

Heap and Rift use the same logical program:

- process a monotonic stream of keyed events;
- group events into fixed time buckets and retain a fixed number of live
  buckets;
- create an ordinary Scala record object the first time a key appears in a
  bucket;
- mutate that record and update its priority while the key stays in the same
  bucket;
- replace the key's ranked value when it moves into a newer bucket;
- remove all parent-visible references for records whose bucket is closing;
- sample the current highest-ranked record during the stream and fold a final
  top-k snapshot into a checksum.

Heap mode allocates records and control arrays on the GC heap. `rift-checked`
allocates records in checked child bucket regions and uses
`StreamWindowIndexedRank` for parent-owned dense-key ranking state.
The current `rift-checked` path uses `putWindowRankInBucket`, which records
the child bucket that owns each dense key. Bucket close automatically removes
those keys from parent-owned rank state before closing the child region.

The purpose is to validate a reusable framework shape for stream operators:
ordinary Scala objects live in structured bucket regions, parent control
metadata is explicit, and the rank API removes bucket-local references before
the child region closes.

## Configuration

Defaults:

| Environment variable | Default |
|---|---:|
| `CHECKED_SWR_EVENTS` | `1000000` |
| `CHECKED_SWR_EVENTS_PER_BUCKET` | `25000` |
| `CHECKED_SWR_KEY_CAPACITY` | `65536` |
| `CHECKED_SWR_LONG_KEY_SPACE` | `65536` |
| `CHECKED_SWR_BUCKET_SECONDS` | `60` |
| `CHECKED_SWR_WINDOW_BUCKETS` | `8` |
| `CHECKED_SWR_INITIAL_RANK_CAPACITY` | `1024` |
| `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY` | `131072` |
| `CHECKED_SWR_TOP_K` | `128` |
| `CHECKED_SWR_SAMPLE_EVERY` | `4096` |
| `CHECKED_SWR_WARMUPS` | `1` |
| `CHECKED_SWR_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `rift-checked`
- `heap-long`
- `rift-checked-long`

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_EVENTS=20000 \
CHECKED_SWR_EVENTS_PER_BUCKET=1000 \
CHECKED_SWR_KEY_CAPACITY=4096 \
CHECKED_SWR_LONG_KEY_SPACE=4096 \
CHECKED_SWR_WINDOW_BUCKETS=4 \
CHECKED_SWR_BENCHMARK_RUNS=1 \
CHECKED_SWR_WARMUPS=0 \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-rank-smoke \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-rank \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Long-key 100k median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_EVENTS=100000 \
CHECKED_SWR_EVENTS_PER_BUCKET=25000 \
CHECKED_SWR_KEY_CAPACITY=65536 \
CHECKED_SWR_LONG_KEY_SPACE=65536 \
CHECKED_SWR_WINDOW_BUCKETS=8 \
CHECKED_SWR_BENCHMARK_RUNS=3 \
CHECKED_SWR_WARMUPS=1 \
CHECKED_SWR_MODES="heap-long rift-checked-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-long-rank-100000-rss \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

Long-key default local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
CHECKED_SWR_BUILD=0 \
CHECKED_SWR_MODES="heap-long rift-checked-long" \
CHECKED_SWR_OUTPUT_DIR=/tmp/checked-stream-window-long-rank-1000000 \
  zsh sandbox/run_checked_stream_window_rank_matrix.sh
```

## Validation

Validation run on 2026-04-28 and updated on 2026-04-29:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `72/72`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `23/23`.
- The smoke matrix native-linked `CheckedStreamWindowRankMatrix`.
- The small heap and `rift-checked` smoke runs matched checksum
  `-3490531581377742567`.
- After the auto-cleanup update, a 100k smoke matrix matched checksum
  `-476315670107920613`.
- The post-auto-cleanup default local matrix matched checksum
  `6881312641757835670`.
- After the entry-cleanup update, a 100k 3-run smoke matched checksum
  `-476315670107920613`.
- The post-entry-cleanup default local matrix matched checksum
  `6881312641757835670`.
- After the remove-with-value queue primitive update, a 100k 3-run smoke
  matched checksum `-476315670107920613`.
- The post-remove-with-value default local matrix matched checksum
  `6881312641757835670`.
- After the lexicographic priority API update, a 100k 3-run smoke matched
  checksum `-476315670107920613`.
- The post-lexicographic priority default local matrix matched checksum
  `6881312641757835670`.
- After the long-key matrix update, `sandbox3_next/compile` passed and the
  long-key 20k smoke, 100k 3-run matrix, and default 1M 3-run matrix matched
  heap-long and `rift-checked-long` checksums.

## Small Smoke Result

Configuration:

- `CHECKED_SWR_EVENTS=20000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=1000`
- `CHECKED_SWR_KEY_CAPACITY=4096`
- `CHECKED_SWR_WINDOW_BUCKETS=4`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 5.755 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 6864896 | -3490531581377742567 |
| rift-checked | 7.415 | 0.390 | 0.055 | 18676 | 21 | 21 | 0 | 8486912 | -3490531581377742567 |

## Auto-Cleanup Smoke Result

This run uses the newer `putWindowRankInBucket` path, where the rank
collection owns the key-to-bucket cleanup list.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 25.043 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 20578304 | -476315670107920613 |
| rift-checked | 33.499 | 0.909 | 0.296 | 82548 | 5 | 5 | 0 | 30556160 | -476315670107920613 |

## Entry-Cleanup Smoke Result

This run uses `closeWindowRankBucketsBeforeWithEntries`, which reports the
rank entries being unlinked so the checked operator can clean its side table
without maintaining a second bucket-local key list.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22.271 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35176448 | -476315670107920613 |
| rift-checked | 33.840 | 0.901 | 0.117 | 82545 | 5 | 5 | 0 | 29999104 | -476315670107920613 |

## Remove-With-Value Close Probe Result

This run adds an internal queue primitive that removes a key and returns its
ranked value in one trusted operation. Bucket close uses that primitive instead
of `contains` + `get` + `remove`. A runtime regression test covers the
already-popped-key case, where a bucket-owned key may no longer be present in
the rank heap when the bucket closes.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 25.360 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35225600 | -476315670107920613 |
| rift-checked | 38.823 | 1.021 | 0.162 | 82545 | 5 | 5 | 0 | 30015488 | -476315670107920613 |

## Lexicographic Priority API Control Result

This checkpoint adds `regionIndexedPriorityQueueLexicographic` and
`streamWindowIndexedRankLexicographic`. The existing matrix still uses the
single-`Long` priority path; this control checks that the shared queue changes
do not break the current stream-window rank harness. Separate compiler/runtime
tests exercise the new four-component lexicographic path directly.

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 22.211 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 35209216 | -476315670107920613 |
| rift-checked | 37.404 | 0.908 | 0.110 | 82545 | 5 | 5 | 0 | 30031872 | -476315670107920613 |

## Long-Key Smoke Result

This run uses `heap-long` and `rift-checked-long`, where keys are arbitrary
`Long` values generated from the same event stream. Heap mode uses an
open-addressed heap long-key rank queue; checked Rift uses
`StreamWindowLongIndexedRank`. The checked mode uses the no-entry close helper
because the rank collection itself owns lookup state in this long-key shape.

Configuration:

- `CHECKED_SWR_EVENTS=20000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=1000`
- `CHECKED_SWR_KEY_CAPACITY=4096`
- `CHECKED_SWR_LONG_KEY_SPACE=4096`
- `CHECKED_SWR_WINDOW_BUCKETS=4`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_BENCHMARK_RUNS=1`
- `CHECKED_SWR_WARMUPS=0`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 6.007 | 0.149 | 0.000 | 0 | 0 | 0 | 0 | 9879552 | -715513143181030887 |
| rift-checked-long | 9.440 | 0.310 | 0.128 | 18679 | 21 | 21 | 0 | 13254656 | -715513143181030887 |

## Long-Key 100k Median

Configuration:

- `CHECKED_SWR_EVENTS=100000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_LONG_KEY_SPACE=65536`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY=131072`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 37.045 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 38830080 | -2863780563714953957 |
| rift-checked-long | 49.450 | 0.000 | 0.178 | 82547 | 5 | 5 | 0 | 33882112 | -2863780563714953957 |

## Default Local Median

Configuration:

- `CHECKED_SWR_EVENTS=1000000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_BUCKET_SECONDS=60`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 205.849 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 145801216 | 6881312641757835670 |
| rift-checked | 329.761 | 7.523 | 0.352 | 826643 | 41 | 41 | 0 | 87457792 | 6881312641757835670 |

## Long-Key Default Local Median

Configuration:

- `CHECKED_SWR_EVENTS=1000000`
- `CHECKED_SWR_EVENTS_PER_BUCKET=25000`
- `CHECKED_SWR_KEY_CAPACITY=65536`
- `CHECKED_SWR_LONG_KEY_SPACE=65536`
- `CHECKED_SWR_BUCKET_SECONDS=60`
- `CHECKED_SWR_WINDOW_BUCKETS=8`
- `CHECKED_SWR_INITIAL_RANK_CAPACITY=1024`
- `CHECKED_SWR_INITIAL_LONG_TABLE_CAPACITY=131072`
- `CHECKED_SWR_TOP_K=128`
- `CHECKED_SWR_SAMPLE_EVERY=4096`
- `CHECKED_SWR_BENCHMARK_RUNS=3`
- `CHECKED_SWR_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens | Closes | Resets | Peak RSS bytes | Checksum |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| heap-long | 358.988 | 6.973 | 0.000 | 0 | 0 | 0 | 0 | 111394816 | 4222832129898301078 |
| rift-checked-long | 503.906 | 5.470 | 0.491 | 826645 | 41 | 41 | 0 | 128286720 | 4222832129898301078 |

## Interpretation

- This is a general stream-window rank benchmark, not a DEBS-specific
  optimization.
- It tests the design goal that structured-lifetime stream data can be ordinary
  Scala objects in regions while control metadata stays explicit.
- The first version used dense integer keys and one `Long` priority. The
  lexicographic priority API now covers Q1-style count/time/sequence/key
  tie-breakers while keeping dense keys and the same close discipline.
  Long-key modes now exercise the hash-keyed stream-window rank path with
  arbitrary `Long` keys, so packed route-style keys no longer require a
  benchmark-specific dense remapping layer.
- The auto-cleanup path strengthens close discipline: the rank collection now
  tracks bucket-owned keys and removes them before closing child regions. This
  is a framework-level improvement, not a DEBS-specific cleanup convention.
- A first auto-cleanup implementation with linear unlinking was rejected during
  this checkpoint: the 100k checked smoke took `1745.677 ms`. The committed
  version uses per-key previous/next links and reduces that smoke to
  `33.499 ms`.
- A direct heap bucket-reference owner table was also tested and rejected: the
  default checked median regressed to `341.705 ms`. The current entry-cleanup
  path keeps the primitive owner tables and removes duplicate operator-side
  bucket nodes in checked mode.
- The default local median is still not a speed win: checked Rift is slower
  (`329.761 ms` vs heap `205.849 ms`) even though measured Rift runtime
  operation cost is low (`0.352 ms`) and peak RSS is lower. The entry-cleanup
  API improved the previous auto-cleanup default (`313.572 ms` checked) and RSS
  (`94732288` bytes) by avoiding a second checked-side key list, but remained
  slower than the previous manual-cleanup median (`254.050 ms` checked). The
  remove-with-value primitive is a simpler close path and validates the
  already-popped-key behavior, but it did not produce a measured speedup. The
  lexicographic API is a functionality step toward Q1-style ordering, not a
  speed claim.
- The long-key mode uses the no-entry close helper because there is no
  operator-side lookup table to clean. That improves the 100k checked median
  compared with the first close-with-entry run (`49.450 ms` vs `54.574 ms`),
  but it does not close the main gap.
- The long-key default median is also not a speed win: `rift-checked-long` is
  slower than `heap-long` (`503.906 ms` vs `358.988 ms`) and has higher RSS at
  1M events, although the 100k long-key row has lower checked RSS. This means
  the long-key stream-window API is functionally validated but should not be
  treated as ready to improve DEBS throughput without another container-CPU or
  memory-layout pass. The 1M absolute times moved between adjacent reruns, so
  use the same-run ratio and checksum result rather than over-interpreting the
  exact millisecond values.
- The result is still useful because it validates the general pattern that
  bucket-lifetime records can be ordinary Scala objects, stored in checked
  region-backed ranking state, sampled during the stream, and automatically
  unlinked from parent rank state before bucket close with heap-equivalent
  output.

## Caveats

- This is not an exact Broom, Yak, StreamFlex, or Stancu artifact
  reproduction.
- It does not include SafeZone, improved SafeZone, or Commix controls.
- It is intended to decide whether the reusable checked stream-window rank API
  is worth integrating into DEBS or needs another API iteration first.
- Heap mode still uses primitive bucket-list nodes for per-key record-table
  cleanup. Checked mode now uses `closeWindowRankBucketsBeforeWithEntries` for
  that side-table cleanup and no longer maintains its own duplicate key list.
