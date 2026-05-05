# Cheap Operator Family Matrix

Date: 2026-05-05
Last updated: 2026-05-05 23:47:54 CEST

Status: focused checkpoint. The first staged smoke rows have been followed by
targeted 1M-shape 3-run medians for Dataflow SELECT, Dataflow AGGREGATE, and
ListOfLists linked builder. `PageTokenMapFilter` and `RegionList` are now real
reusable APIs with compiler/runtime probes. `EpochFold` also exists as a real
API, but its first Dataflow AGGREGATE row fails the speed gate and is recorded
as a negative/gated operator result. `EpochBuffer` has now been added as a
reusable batch/epoch append-drain API and passes its first focused 1M gate. This
has now been followed by `TransactionRegion`, a multi-list transaction/batch
API for pipelines that would otherwise stack several epoch buffers. This is
still not the full comprehensive headline sweep.

## Purpose

This matrix records the first implementation of the "cheap checked operator
families" direction:

- `checked-page-token` for SELECT/filter/project-style rows;
- `checked-region-scoped` page-token over the SafeZone-backed checked backend;
- `checked-epoch-fold` for additive aggregate-shaped rows;
- `checked-listoflists-builder` for linked ListOfLists topology evidence.
- `checked-epoch-buffer` for batch/epoch append-drain rows without ranking or
  hash-table maintenance.
- `checked-transaction-region` for multi-stage batch pipelines with several
  internal lists but one child region per batch.

Reusable API status:

| API | Status | Evidence |
|---|---|---|
| `PageTokenMapFilter[T]` | Implemented and validated | Thin reusable API over the fast page-token child-bucket path. Dataflow SELECT now uses this named operator. |
| `EpochFold[T]` | Implemented but gated/negative | Correct and tested, but the first real aggregate row is `91.938 ms`, far slower than the older checked aggregate path. Do not use as headline evidence yet. |
| `RegionList[T]` | Implemented and validated | ListOfLists checked builder now uses the reusable region-list API and improves to `5927.385 ms` median. |
| `EpochBuffer[T]` | Implemented and focused-positive | Batch/epoch append-drain API. At 1M, `rift-checked-epoch-buffer` is `26.673 ms` and SafeZone-backed checked is `25.448 ms` versus `heap-epoch` `27.164 ms` with `5.707 ms` GC. |
| `TransactionRegion` + `TransactionList[T]` | Implemented and partially validated | Multi-list batch API. In StreamFlex 200k throughput, scoped checked `TransactionRegion` is `41.375 ms`, slightly faster than heap `41.995 ms` and improved SafeZone `41.871 ms`; Rift-native checked transaction improves over stacked EpochBuffer but remains slower than heap. |

The implementation goal is not to rename public APIs. It is to report the
system in terms of descriptive memory modes while keeping raw benchmark labels
as aliases.

## Validation

Compile and safety suites passed after the implementation slice:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Latest safety results after the reusable API slice:

- compiler checked suite: `108/108` after adding `EpochBuffer`;
- runtime checked suite: `48/48` after adding `EpochBuffer`;
- compiler checked suite: `110/110` after adding `TransactionRegion`;
- runtime checked suite: `49/49` after adding `TransactionRegion`;
- Dataflow SELECT page-token smoke matched checksum;
- Dataflow AGGREGATE `EpochFold` row matched checksum but failed the speed gate;
- ListOfLists checked builder smoke completed.
- `EpochBuffer` compiler/runtime probes passed; focused 20k, 100k, and 1M
  rows matched checksums.
- `TransactionRegion` compiler/runtime probes passed; StreamFlex 20k smoke,
  200k throughput, and 10k latency rows matched checksums.

The first attempt to run the staged performance script stopped at
`checked-region-buffer` because its zsh default mode-list parsing treated the
whole mode list as one value. The script was fixed and the checked/stream
staged smoke leg was rerun successfully.

## EpochBuffer Focused Follow-Up

Date/time: 2026-05-05 20:52:18 CEST.

`EpochBuffer[T]` is the reusable API for a whole-epoch/batch lifetime: user
records are ordinary Scala objects allocated in one active child region, and
`closeEpochBufferWithCursor` drains the epoch before closing that child region.
The fair heap control is `heap-epoch`, not the sliding-window heap row.

| Scale | Mode | Median elapsed ms | Median GC ms | RSS bytes | Interpretation |
|---|---|---:|---:|---:|---|
| 20k | `heap-epoch` | 1.410 | 0.000 | 7127040 | smoke baseline |
| 20k | `rift-checked-epoch-buffer` | 0.932 | 0.000 | 6930432 | checksum matched |
| 20k | `rift-checked-safezone-epoch-buffer` | 0.783 | 0.000 | 6914048 | fastest smoke row |
| 100k | `heap-epoch` | 2.463 | 0.000 | 21364736 | heap still slightly faster at no-GC scale |
| 100k | `rift-checked-epoch-buffer` | 2.703 | 0.000 | 10731520 | lower RSS, slight elapsed overhead |
| 100k | `rift-checked-safezone-epoch-buffer` | 2.615 | 0.000 | 10747904 | lower RSS, closer to heap |
| 1M | `heap-epoch` | 27.164 | 5.707 | 21446656 | heap now pays material GC |
| 1M | `rift-checked-epoch-buffer` | 26.673 | 0.000 | 22446080 | focused checked win over heap |
| 1M | `rift-checked-safezone-epoch-buffer` | 25.448 | 0.000 | 22462464 | fastest focused epoch row |

Interpretation: this is positive framework evidence for the next cheap
operator family. It should be tried on Yak/StreamFlex-style epoch buffers
before any rank/hash-heavy application path. The win is modest, but it is
exactly the desired mechanism: structured-lifetime records leave the heap,
heap GC goes to zero, and the checked API remains close enough that SafeZone
backend mechanics beat the heap epoch control at 1M.

## TransactionRegion StreamFlex Follow-Up

Date/time: 2026-05-05 23:47:54 CEST.

`TransactionRegion` generalizes the multi-stage stream-pipeline shape:
one transaction owns one active child region and several typed
`TransactionList[T]` append lists. This lets packets, decoded records,
classified records, and alerts share one batch lifetime in StreamFlex-style
code instead of opening one `EpochBuffer` child region per stage.

The first implementation used indexed arrays for list heads/tails/lengths and
was slower than `EpochBuffer` despite fewer region opens. The current
implementation moves hot list state into each typed list handle. That keeps
the safety/lifetime structure the same while removing array indexing from the
append/drain hot path.

Validation:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results: compile passed; checked compiler suite passed `110/110`; checked
native runtime suite passed `49/49`.

200k StreamFlex throughput:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Interpretation |
|---|---:|---:|---:|---:|---|---|
| heap | 41.995 | 8.135 | 0.000 | 0 | 0 / 0 / 0 | baseline |
| improved SafeZone | 41.871 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | fair scoped baseline |
| Rift Streaming | 36.365 | 0.000 | 0.127 | 2499843 | 1 / 1 / 781 | best trusted row |
| checked EpochBuffer | 48.052 | 0.000 | 0.403 | 2499843 | 3129 / 3129 / 0 | negative stacked-buffer control |
| scoped checked EpochBuffer | 44.987 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | still slower than heap |
| checked TransactionRegion | 44.881 | 0.000 | 0.189 | 2499843 | 783 / 783 / 0 | improves EpochBuffer, still speed-gated |
| scoped checked TransactionRegion | 41.375 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | best checked StreamFlex-shaped row |

10k StreamFlex latency:

| Mode | Median elapsed ms | p50 ns | p99 ns | p999 ns | Max ns | Deadline misses |
|---|---:|---:|---:|---:|---:|---:|
| heap | 10.033 | 708 | 917 | 1333 | 296250 | 4 |
| improved SafeZone | 11.046 | 917 | 1208 | 2709 | 25041 | 0 |
| Rift Streaming | 9.804 | 792 | 1042 | 1541 | 17417 | 0 |
| checked EpochBuffer | 27.454 | 2250 | 2916 | 8834 | 399167 | 4 |
| scoped checked EpochBuffer | 23.072 | 1958 | 2541 | 7250 | 323583 | 4 |
| checked TransactionRegion | 15.493 | 1292 | 1667 | 2000 | 39625 | 0 |
| scoped checked TransactionRegion | 13.580 | 1125 | 1458 | 2375 | 20000 | 0 |

Interpretation: `TransactionRegion` is the right general operator shape for
multi-stage epoch pipelines, but only the SafeZone-backed checked row clears a
small throughput win at this scale. Rift-native checked transaction improves
region lifecycle cost but still has too much checked/operator CPU to beat heap.
This is a partial win and a useful design signal: multi-list operators should
use one child lifetime, but their hot list metadata must be field-based and
operator-owned.

## Staged Dataflow Probe

Source command:

```sh
RIFT_EVAL_RUN_ID=2026-05-05-cheap-operators-smoke \
RIFT_EVAL_ALLOW_DIRTY=1 \
RIFT_EVAL_SCALE=smoke \
RIFT_EVAL_RUNS=1 \
RIFT_EVAL_SUITES="preflight core prior checked streams" \
bash scripts/run-performance-evaluation.sh
```

Caveat: despite the `smoke` scale, the Dataflow runner uses its own default
`10 * 100k` shape in this path. Treat these as single-run directional rows,
not headline medians.

| Operator | Reporting mode | Raw mode | Elapsed ms | GC ms | Checksum |
|---|---|---|---:|---:|---:|
| SELECT | `gc-heap` | `heap` | 33.489 | 12.218 | matched |
| SELECT | `region-scoped-rooted` | improved SafeZone | 22.904 | 0.000 | matched |
| SELECT | `region-scoped-rootless` | rootless SafeZone | 22.152 | 0.000 | matched |
| SELECT | `region-hp-rootless` | `rift-hp` | 23.020 | 0.000 | matched |
| SELECT | `region-stream-rootless` | `rift-streaming` | 22.843 | 0.000 | matched |
| SELECT | `checked-region-stream` | `rift-checked` | 20.541 | 0.000 | matched |
| SELECT | `checked-page-token` | `rift-checked-page-token` | 19.697 | 0.000 | matched |
| SELECT | `checked-page-token` over scoped backend | `rift-checked-safezone-page-token` | 18.371 | 0.000 | matched |
| AGGREGATE | `gc-heap` | `heap` | 49.316 | 11.020 | matched |
| AGGREGATE | `region-scoped-rooted` | improved SafeZone | 39.885 | 0.000 | matched |
| AGGREGATE | `checked-region-stream` | `rift-checked` | 38.581 | 0.000 | matched |
| AGGREGATE | `checked-epoch-fold` | exact-array checked aggregate | 39.213 | 0.000 | matched |
| JOIN | `gc-heap` | `heap` | 31.337 | 10.691 | matched |
| JOIN | `region-scoped-rooted` | improved SafeZone | 22.225 | 0.000 | matched |
| JOIN | `checked-region-stream` | `rift-checked` | 21.146 | 0.000 | matched |

Interpretation:

- The new page-token SELECT path is the best Dataflow SELECT row in this
  staged probe.
- The SafeZone-backed page-token row is faster than current checked Rift on
  SELECT, supporting SafeZone-family scoped allocation as a serious checked
  backend candidate.
- `checked-epoch-fold` is currently not a new generic fold implementation; it
  uses the existing exact-array checked aggregate path under the new reporting
  label. It beats heap but is slightly slower than current checked aggregate
  in this single-run probe.
- JOIN is unchanged and should remain existing-mode evidence until a real
  `WindowHashJoin` cheap operator exists.

## Staged ListOfLists Probe

Same staged run, single-run default ListOfLists shape.

| Reporting mode | Raw mode | Elapsed ms | Interpretation |
|---|---|---:|---|
| `gc-heap` | `heap` | 16615.319 | Baseline. |
| `region-scoped-rooted` | improved SafeZone | 9831.508 | SafeZone-family wins strongly over heap. |
| `region-scoped-rootless` | rootless SafeZone | 9707.455 | Unsafe lower bound, slightly faster than rooted scoped. |
| `region-hp-rootless` | `rift-hp` | 9341.644 | Trusted Rift HP beats rooted/rootless scoped in this staged row. |
| `checked-listoflists-builder` | checked scoped builder | 9109.045 | Fastest single-run row; promising topology operator evidence. |

Interpretation:

- This supports adding a reusable topology builder for linked region-friendly
  structures, but it still needs clean 3-run medians before it becomes headline
  evidence.
- The old current SafeZone root-mode-0 cliff remains pathological and is not a
  fair baseline for new claims.

## Checked And Stream Staged Smoke Continuation

After fixing the checked-buffer mode-list parser:

```sh
RIFT_EVAL_RUN_ID=2026-05-05-cheap-operators-checked-streams-smoke \
RIFT_EVAL_ALLOW_DIRTY=1 \
RIFT_EVAL_SCALE=smoke \
RIFT_EVAL_RUNS=1 \
RIFT_EVAL_SUITES="checked streams" \
bash scripts/run-performance-evaluation.sh
```

The checked and stream smoke leg completed. Selected rows:

| Benchmark | Reporting mode | Elapsed ms | Interpretation |
|---|---|---:|---|
| Checked append, 20k | `gc-heap` | 2.109 | Heap baseline, GC `0.917 ms`. |
| Checked append, 20k | `checked-region-stream` cursor | 0.649 | Cursor path still cheap. |
| Checked append, 20k | `checked-page-token` | 0.587 | Page-token remains faster than generic cursor. |
| Checked append, 20k | `checked-page-token` over scoped backend | 0.564 | Fastest checked append smoke row. |
| Common Crawl-shaped q1, 20k | `gc-heap` | 80.459 | Heap GC is zero at this scale. |
| Common Crawl-shaped q1, 20k | `checked-page-token` over scoped backend | 77.120 | Fastest q1 smoke row, but not GC-heavy at 20k. |
| Common Crawl-shaped q2, 20k | `gc-heap` | 78.029 | Heap GC is zero at this scale. |
| Common Crawl-shaped q2, 20k | `checked-page-token` over scoped backend | 77.974 | Near heap/fastest, still smoke only. |

Interpretation:

- The staged smoke confirms the new reporting-mode wiring does not break the
  checked/stream runner set.
- It does not replace existing 1M page-token evidence. The current headline
  page-token rows remain the 1M medians in
  `CHECKED_PAGE_TOKEN_APPEND_MATRIX.md` and `COMMON_CRAWL_LIKE_MATRIX.md`.

## Focused 1M-Shape 3-Run Follow-Up

These rows use the default Dataflow shape:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

Commands were run with:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1
JAVA_HOME="$(cs java-home --jvm temurin:17)"
PATH="$JAVA_HOME/bin:$PATH"
```

### Reusable API Follow-Up

After the initial focused rows, the benchmark code was changed to use the real
reusable APIs instead of benchmark-local wrappers:

- Dataflow SELECT now uses `RiftRegion.pageTokenMapFilter`,
  `pageTokenMapFilterRegionFor`, `emitPageTokenMapFilter`, and cursor close.
- Dataflow AGGREGATE now has a true `RiftRegion.epochFold` mode. This is
  correct but currently too slow.
- ListOfLists checked builder now uses `RiftRegion.regionList`,
  `prependRegionList`, `regionListHead`, and `regionListNext`.

Validation commands:

```sh
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results: compile passed; compiler probes passed `106/106`; runtime checked
tests passed `47/47`.

### Dataflow SELECT

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | Region/Rift op ms | Checksum |
|---|---|---:|---:|---:|---:|
| `gc-heap` | `heap` | 28.176 | 6.698 | 0.000 | 131080080920 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 22.982 | 0.000 | 0.000 | 131080080920 |
| `checked-region-stream` | `rift-checked` | 21.033 | 0.000 | 0.209 | 131080080920 |
| `checked-page-token` | `checked-page-token` | 19.856 | 0.000 | 0.068 | 131080080920 |
| `checked-page-token` over scoped backend | `checked-page-token-scoped`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 19.050 | 0.000 | 0.000 | 131080080920 |

Reusable `PageTokenMapFilter` rerun:

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | Region/Rift op ms | Checksum |
|---|---|---:|---:|---:|---:|
| `gc-heap` | `heap` | 27.872 | 6.603 | 0.000 | 131080080920 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1` | 23.025 | 0.000 | 0.000 | 131080080920 |
| `region-scoped-rootless` | `safezone`, `SAFEZONE_ROOTS_MODE=3`, `SAFEZONE_PAGE_SIZE=32768` | 22.788 | 0.000 | 0.000 | 131080080920 |
| `region-hp-rootless` | `rift-hp` | 23.022 | 0.000 | 0.065 | 131080080920 |
| `region-stream-rootless` | `rift-streaming` | 23.418 | 0.000 | 0.060 | 131080080920 |
| `checked-region-stream` | `rift-checked` | 20.844 | 0.000 | 0.203 | 131080080920 |
| `checked-page-token` / `PageTokenMapFilter` | `checked-page-token` | 19.881 | 0.000 | 0.071 | 131080080920 |
| scoped `checked-page-token` / `PageTokenMapFilter` | `checked-page-token-scoped`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 18.214 | 0.000 | 0.000 | 131080080920 |

Interpretation:

- The SELECT page-token gate passes: both page-token rows beat heap,
  improved SafeZone/scoped rooted, and current checked.
- The scoped SafeZone-backed page-token row is fastest in this focused
  comparison.
- This is the first cheap `PageTokenMapFilter` family result worth carrying
  into the headline sweep.

### Dataflow AGGREGATE

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | Region/Rift op ms | Checksum |
|---|---|---:|---:|---:|---:|
| `gc-heap` | `heap` | 62.008 | 22.583 | 0.000 | 163835709480 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 42.234 | 0.000 | 0.000 | 163835709480 |
| `checked-region-stream` | `rift-checked` | 40.024 | 0.000 | 0.338 | 163835709480 |
| `checked-epoch-fold` | existing exact-array checked aggregate path | 38.991 | 0.000 | 0.248 | 163835709480 |

Reusable `EpochFold` rerun:

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | Region/Rift op ms | Checksum |
|---|---|---:|---:|---:|---:|
| `checked-epoch-fold` / true `EpochFold` | `checked-epoch-fold` | 91.938 | 1.929 | 0.132 | 163835709480 |

Interpretation:

- The old aggregate reporting row beats heap, scoped rooted, and current
  checked, but it was the exact-array checked aggregate path.
- The true reusable `EpochFold` implementation is correct but too slow in this
  workload. It should stay as a gated negative/control row until table layout,
  entry iteration, or update-path overhead is improved.

### ListOfLists Linked Builder

Rows use:

- `LISTBENCH_N=3000`
- `LISTBENCH_STRUCTURES=40`
- `LISTBENCH_BENCHMARK_RUNS=3`

| Reporting mode | Raw mode/env | Median elapsed ms | Samples ms |
|---|---|---:|---|
| `gc-heap` | `heap` | 15390.798 | 15184.947, 15390.798, 16797.692 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1` | 10002.658 | 9960.765, 10002.658, 10040.237 |
| `region-hp-rootless` | `rift-hp` | 9594.689 | 9500.827, 9594.689, 9750.959 |
| checked ListOfLists builder | `checked-region-listoflists-builder` | 9407.102 | 9328.961, 9407.102, 9578.718 |

Reusable `RegionList` rerun:

| Reporting mode | Raw mode/env | Median elapsed ms | Samples ms |
|---|---|---:|---|
| checked ListOfLists builder / `RegionList` | `checked-region-listoflists-builder` | 5927.385 | 5908.064, 5927.385, 5970.701 |

Interpretation:

- The checked builder gate passes against heap, improved SafeZone/scoped
  rooted, and trusted HP in this focused comparison.
- The reusable `RegionList` row is much faster than the earlier benchmark-local
  checked builder row in this run and remains far faster than heap.
- This is promising topology-operator evidence for ordinary linked object
  graphs with a shared region lifetime.
- It is still not evidence for arbitrary linked graphs with unpredictable
  lifetimes.

## Next Required Runs

## Focused RSS Rerun

The rows below wrap the native benchmark binary directly with `/usr/bin/time -l`
so the recorded RSS is the benchmark process, not SBT. Logs are under:

```text
/Users/siyaoliu/rift/cache/cheap-operator-rss-2026-05-05/
```

### Dataflow SELECT With RSS

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|
| `gc-heap` | `heap` | 27.932 | 6.616 | 39288832 | 131080080920 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 22.466 | 0.000 | 30375936 | 131080080920 |
| `checked-region-stream` | `rift-checked` | 20.245 | 0.000 | 30490624 | 131080080920 |
| `checked-page-token` | `checked-page-token` | 19.673 | 0.000 | 30392320 | 131080080920 |
| `checked-page-token` over scoped backend | `checked-page-token-scoped`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 17.980 | 0.000 | 30375936 | 131080080920 |

Interpretation:

- The SELECT page-token result remains positive with RSS captured.
- The scoped-backend page-token row is fastest and cuts RSS by about `8.9 MB`
  versus heap in this run.
- Current checked, page-token, and scoped page-token all have roughly the same
  RSS; the elapsed win is from cheaper operator/backend mechanics, not a large
  RSS change among region modes.

### Dataflow AGGREGATE With RSS

| Reporting mode | Raw mode/env | Median elapsed ms | Median GC ms | RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|
| `gc-heap` | `heap` | 61.585 | 22.297 | 40091648 | 163835709480 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1`, `SAFEZONE_PAGE_SIZE=32768` | 40.103 | 0.000 | 46907392 | 163835709480 |
| `checked-region-stream` | `rift-checked` | 38.644 | 0.000 | 46825472 | 163835709480 |
| `checked-epoch-fold` | existing exact-array checked aggregate path | 38.399 | 0.000 | 46825472 | 163835709480 |

Interpretation:

- The aggregate row remains positive on elapsed and GC, but RSS is higher than
  heap by about `6.7 MB`.
- This reinforces the classification: `EpochFold` is a promising direction for
  elapsed/GC, but the current row is not yet a polished low-RSS reusable fold
  operator.

### ListOfLists Linked Builder With RSS

| Reporting mode | Raw mode/env | Median elapsed ms | RSS bytes | Samples ms |
|---|---|---:|---:|---|
| `gc-heap` | `heap` | 14822.115 | 575930368 | 14800.468, 14822.115, 16296.972 |
| `region-scoped-rooted` | `safezone`, `SAFEZONE_ROOTS_MODE=1` | 9812.764 | 367280128 | 9782.016, 9812.764, 9914.740 |
| `region-hp-rootless` | `rift-hp` | 9547.748 | 364101632 | 9446.748, 9547.748, 9740.864 |
| checked ListOfLists builder | `checked-region-listoflists-builder` | 9228.561 | 364150784 | 9061.164, 9228.561, 9796.303 |

Interpretation:

- The checked builder remains fastest and has nearly the same RSS as trusted
  HP, while cutting RSS by about `212 MB` versus heap.
- This is the strongest first result for the `RegionListOfListsBuilder`
  direction: linked object graphs can be region-friendly when the whole linked
  structure has one lifetime.

## Next Required Runs

Before using these new operator-family rows in the final comprehensive report:

1. Rerun the same focused rows from a committed/clean checkpoint.
2. Integrate the RSS wrapper into a reusable script instead of relying on
   ad hoc direct-binary commands.
3. Optimize the comprehensive run harness to reuse built binaries. A dirty
   smoke attempt (`2026-05-05-reusable-operators-smoke`) was stopped during
   GCBench because repeated sbt invocations under Java 17 were too slow and hit
   JVM code-cache warnings; the partial run is not evidence.
4. Only then run the broader staged headline suite for rows that clear those
   focused gates.
