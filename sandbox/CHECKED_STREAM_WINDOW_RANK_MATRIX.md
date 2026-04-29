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
| `CHECKED_SWR_BUCKET_SECONDS` | `60` |
| `CHECKED_SWR_WINDOW_BUCKETS` | `8` |
| `CHECKED_SWR_INITIAL_RANK_CAPACITY` | `1024` |
| `CHECKED_SWR_TOP_K` | `128` |
| `CHECKED_SWR_SAMPLE_EVERY` | `4096` |
| `CHECKED_SWR_WARMUPS` | `1` |
| `CHECKED_SWR_BENCHMARK_RUNS` | `3` |

Modes:

- `heap`
- `rift-checked`

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

## Validation

Validation run on 2026-04-28 and updated on 2026-04-29:

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"`
  passed `70/70`.
- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"`
  passed `21/21`.
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
| heap | 200.304 | 0.000 | 0.000 | 0 | 0 | 0 | 0 | 145768448 | 6881312641757835670 |
| rift-checked | 302.001 | 7.447 | 0.289 | 826643 | 41 | 41 | 0 | 87441408 | 6881312641757835670 |

## Interpretation

- This is a general stream-window rank benchmark, not a DEBS-specific
  optimization.
- It tests the design goal that structured-lifetime stream data can be ordinary
  Scala objects in regions while control metadata stays explicit.
- The first version uses dense integer keys and one `Long` priority, matching
  the current `StreamWindowIndexedRank` API. Richer Q1/Q2 integration may need
  comparator/tie-breaker support and hash-keyed indexing.
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
  (`302.001 ms` vs heap `200.304 ms`) even though measured Rift runtime
  operation cost is low (`0.289 ms`) and peak RSS is lower. The entry-cleanup
  API improves the previous auto-cleanup default (`313.572 ms` checked) and RSS
  (`94732288` bytes) by avoiding a second checked-side key list, but remains
  slower than the previous manual-cleanup median (`254.050 ms` checked).
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
