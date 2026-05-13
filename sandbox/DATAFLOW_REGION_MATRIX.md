# Dataflow Region Matrix

Last updated: 2026-05-13 12:49 CEST

Status: Broom-style methodology reproduction harness with trusted
heap/SafeZone/Rift medians, checked RegionBuffer modes, and reusable checked
direct-epoch modes for SELECT, AGGREGATE, and JOIN.

The harness is implemented in
`sandbox/src/main/scala-next/DataflowRegionMatrix.scala` and run with
`sandbox/run_dataflow_region_matrix.sh`.

## Benchmark Intent

This is not an exact Naiad/Broom artifact reproduction. The original Naiad/Broom
code is not available in this workspace. The benchmark instead reproduces the
paper's operator methodology: many per-epoch data objects enter SELECT,
AGGREGATE, and JOIN vertices, then die together at the epoch boundary.

Heap and Rift variants use the same logical programs:

- `heap`: ordinary `new` for document, selected-output, aggregate-entry,
  author-entry, join-output, and table-array objects.
- `safezone`: the same logical object graph allocated in Scala Native
  `SafeZone`; run as current SafeZone with `SAFEZONE_ROOTS_MODE=0` and improved
  SafeZone with `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: the same classes allocated with `region.alloc`, using one region
  per epoch.
- `rift-streaming`: the same classes allocated with `region.alloc`, using a
  resettable streaming region across epochs.
- `rift-checked`: checked safe-API variant using
  `RiftRegion.streaming/reset`. SELECT and JOIN use growable `RegionBuffer`
  for output records. AGGREGATE uses a region-owned table array with linked
  region-owned aggregate entries.
- `checked-epoch-stream`: checked safe-API variant using
  `RiftRegion.epoch { ... }` over the Rift streaming backend. It exposes the
  direct per-epoch topology as a reusable API rather than benchmark-local
  reset plumbing. As of the 2026-05-13 handle-backed allocation gate, this
  mode uses the open-handle fast path for SELECT, AGGREGATE, and JOIN over the
  Rift streaming backend. Region-owned arrays are covered by making the
  internal open handle satisfy the `SafeZone` allocation contract used by
  Scala Native's array allocation helpers.
- `checked-epoch-scoped`: checked safe-API variant using the same
  `RiftRegion.epoch { ... }` source shape over the SafeZone-backed scoped
  backend.
- `checked-page-token-scoped`: operator-owned checked page-token path. This is
  valid for SELECT/filter/project-style rows only, not AGGREGATE or JOIN.
- `checked-epoch-fold`: older generic fold API for AGGREGATE. It remains a
  negative/speed-gated control.

The benchmark intentionally uses ordinary Scala object graphs in regions. It is
not a primitive-key-only layout experiment.

## Default Configuration

The defaults are sized for local iteration, not for the original Broom paper's
500k-600k documents per epoch and 40 epochs:

| Environment variable | Default |
|---|---:|
| `DATAFLOW_EPOCHS` | `10` |
| `DATAFLOW_DOCS_PER_EPOCH` | `100000` |
| `DATAFLOW_AUTHORS_PER_EPOCH` | `20` |
| `DATAFLOW_KEY_SPACE` | `65536` |
| `DATAFLOW_AUTHOR_KEY_SPACE` | `256` |
| `DATAFLOW_SELECT_MODULO` | `8` |
| `DATAFLOW_WARMUPS` | `1` |
| `DATAFLOW_BENCHMARK_RUNS` | `3` |

Use `DATAFLOW_OPERATOR=select`, `aggregate`, `join`, or `all`.

## Commands

Smoke-sized run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Local median run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Native-only instrumented median run with peak RSS:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-100k \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh

DATAFLOW_BUILD=0 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-all-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Checked all-operator run:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-all-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Broom-scale methodology run, subject to local runtime feasibility:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh
```

## Result Status

Smoke, local medians, native-only instrumented RSS runs, checked
RegionBuffer/table medians, and reusable checked direct-epoch medians have
been recorded. These are local methodology reproduction numbers, not exact
Broom/Naiad numbers.

Validation commands run on 2026-04-25:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BUILD=0 DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-100k \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

The first smoke run rebuilt under the script's Java 17 setup and emitted a JVM
code-cache warning during sbt compilation. The native binaries still linked and
ran to completion. The local median run reused the built binary and completed
normally.

## Native-Only Local 3-Run Median, 10 Epochs x 100k Documents

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_KEY_SPACE=65536`
- `DATAFLOW_AUTHOR_KEY_SPACE=256`
- `DATAFLOW_SELECT_MODULO=8`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 26.760 | 6.554 | 0.000 | 0 | 40026112 |
| SELECT | current SafeZone | 26.599 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | improved SafeZone | 24.035 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | Rift HPZone | 22.934 | 0.000 | 0.052 | 1124990 | 46891008 |
| SELECT | Rift Streaming | 22.958 | 0.000 | 0.044 | 1124990 | 46858240 |
| AGGREGATE | heap | 57.705 | 19.240 | 0.000 | 0 | 40026112 |
| AGGREGATE | current SafeZone | 49.599 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | improved SafeZone | 41.426 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | Rift HPZone | 43.046 | 0.000 | 0.198 | 1627152 | 46891008 |
| AGGREGATE | Rift Streaming | 42.802 | 0.000 | 0.232 | 1627152 | 46858240 |
| JOIN | heap | 28.189 | 7.084 | 0.000 | 0 | 40026112 |
| JOIN | current SafeZone | 26.705 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | improved SafeZone | 22.789 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | Rift HPZone | 22.554 | 0.000 | 0.036 | 1078279 | 46891008 |
| JOIN | Rift Streaming | 23.025 | 0.000 | 0.038 | 1078279 | 46858240 |

Interpretation:

- This is the first current benchmark where ordinary Scala dataflow objects are
  placed in regions and measured against the same heap program.
- The local controlled workload shows material GC reduction because the data
  objects are intentionally epoch-local and allocation-heavy.
- Improved SafeZone is now a strong baseline: it is faster than heap on all
  three operators and roughly tied with Rift on this local scale.
- Rift HPZone and Streaming are close at this scale. Streaming trades ten
  open/close pairs for one open/close plus nine resets.
- Rift operation time is small in this benchmark: below 0.25 ms median for each
  operator at the 1M-document local scale.

Caveats:

- This is a Broom-style methodology reproduction, not exact Naiad/Broom.
- The default local size is smaller than the Broom paper's reported
  40-epoch, 500k-600k document-per-epoch vertex experiment.
- The benchmark uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future capture-checked safe API.

## Post Allocation-Counter Fix Verification, 10 Epochs x 100k Documents

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=1 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-fixed \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

This rerun was taken after moving Rift allocation counters out of the
per-object atomic hot path and flushing counts at region reset/close.

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Median region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 28.398 | 7.131 | 0.000 | 0 | 40042496 |
| SELECT | current SafeZone | 26.413 | 0.000 | 0.000 | 0 | 47038464 |
| SELECT | improved SafeZone | 23.600 | 0.000 | 0.000 | 0 | 47005696 |
| SELECT | Rift HPZone | 21.614 | 0.000 | 0.051 | 1124990 | 46891008 |
| SELECT | Rift Streaming | 24.445 | 0.000 | 0.046 | 1124990 | 46874624 |
| AGGREGATE | heap | 59.906 | 19.908 | 0.000 | 0 | 40042496 |
| AGGREGATE | current SafeZone | 51.568 | 0.000 | 0.000 | 0 | 47038464 |
| AGGREGATE | improved SafeZone | 43.216 | 0.000 | 0.000 | 0 | 47005696 |
| AGGREGATE | Rift HPZone | 41.870 | 0.000 | 0.273 | 1627152 | 46891008 |
| AGGREGATE | Rift Streaming | 48.003 | 0.000 | 0.320 | 1627152 | 46874624 |
| JOIN | heap | 28.347 | 7.415 | 0.000 | 0 | 40042496 |
| JOIN | current SafeZone | 27.032 | 0.000 | 0.000 | 0 | 47038464 |
| JOIN | improved SafeZone | 24.079 | 0.000 | 0.000 | 0 | 47005696 |
| JOIN | Rift HPZone | 21.481 | 0.000 | 0.075 | 1078279 | 46891008 |
| JOIN | Rift Streaming | 36.120 | 0.000 | 0.046 | 1078279 | 46874624 |

Interpretation:

- HPZone now beats heap and improved SafeZone on all three local Broom-style
  operators in this rerun.
- Streaming is not consistently good on this run; keep HPZone and Streaming
  claims separate.

## Checked RegionBuffer/Table Medians

Date: 2026-04-26

This run extends `rift-checked` across the existing Broom-style matrix. The
checked path uses `RiftRegion.streaming/reset`; SELECT and JOIN use
`RiftRegion.RegionBuffer` for output records, and AGGREGATE uses a
region-owned table array with linked region-owned aggregate entries. Heap,
SafeZone, trusted Rift, and checked Rift all produce matching checksums for all
three operators.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_BUILD=0 DATAFLOW_OUTPUT_DIR=/tmp/dataflow-checked-all-default \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| SELECT | heap | 36.868 | 8.624 | 0.000 | 0 | 0 / 0 / 0 | 75907072 |
| SELECT | current SafeZone | 26.785 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47104000 |
| SELECT | improved SafeZone | 23.454 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47120384 |
| SELECT | Rift HPZone | 20.958 | 0.000 | 0.051 | 1124990 | 10 / 10 / 0 | 47005696 |
| SELECT | Rift Streaming | 21.042 | 0.000 | 0.040 | 1124990 | 1 / 1 / 9 | 46956544 |
| SELECT | Rift checked RegionBuffer | 18.865 | 0.000 | 0.178 | 1125100 | 1 / 1 / 10 | 46972928 |
| AGGREGATE | heap | 51.474 | 11.510 | 0.000 | 0 | 0 / 0 / 0 | 75907072 |
| AGGREGATE | current SafeZone | 50.328 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47104000 |
| AGGREGATE | improved SafeZone | 41.290 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47120384 |
| AGGREGATE | Rift HPZone | 39.118 | 0.000 | 0.218 | 1627152 | 10 / 10 / 0 | 47005696 |
| AGGREGATE | Rift Streaming | 39.482 | 0.000 | 0.160 | 1627152 | 1 / 1 / 9 | 46956544 |
| AGGREGATE | Rift checked table | 36.003 | 0.000 | 0.171 | 1627152 | 1 / 1 / 10 | 46972928 |
| JOIN | heap | 32.170 | 10.966 | 0.000 | 0 | 0 / 0 / 0 | 75907072 |
| JOIN | current SafeZone | 25.536 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47104000 |
| JOIN | improved SafeZone | 22.604 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 47120384 |
| JOIN | Rift HPZone | 20.819 | 0.000 | 0.038 | 1078279 | 10 / 10 / 0 | 47005696 |
| JOIN | Rift Streaming | 21.192 | 0.000 | 0.039 | 1078279 | 1 / 1 / 9 | 46956544 |
| JOIN | Rift checked RegionBuffer | 18.736 | 0.000 | 0.101 | 1078379 | 1 / 1 / 10 | 46972928 |

Interpretation:

- This is the first existing literature-shaped operator set using the checked
  safe API rather than trusted `RiftRegion.open`.
- The checked variants are faster than heap, improved SafeZone, trusted HPZone,
  and trusted Streaming in this local run for SELECT, AGGREGATE, and JOIN.
- Region operation time remains small; the largest checked median here is
  `0.178 ms` for SELECT.
- This is still a local methodology reproduction, not exact Naiad/Broom
  evidence and not a DEBS application result.

## Reusable Checked Epoch Topology Rerun, 10 Epochs x 100k Documents

Date: 2026-05-08

This run applies the reusable `RiftRegion.epoch { ... }` API to the
Broom/Dataflow methodology harness. The goal is to test whether the direct
checked epoch topology that won on Yak/LiveJournal generalizes to another
epoch-shaped benchmark before designing more operators.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=10 \
DATAFLOW_DOCS_PER_EPOCH=100000 \
DATAFLOW_AUTHORS_PER_EPOCH=20 \
DATAFLOW_BENCHMARK_RUNS=3 \
DATAFLOW_WARMUPS=1 \
DATAFLOW_MODES="heap improved-safezone rift-streaming checked-epoch-scoped checked-epoch-stream checked-page-token-scoped checked-epoch-fold" \
DATAFLOW_OPERATOR=all \
  zsh sandbox/run_dataflow_region_matrix.sh
```

Configuration:

- `DATAFLOW_EPOCHS=10`
- `DATAFLOW_DOCS_PER_EPOCH=100000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_BENCHMARK_RUNS=3`
- `DATAFLOW_WARMUPS=1`
- normal `run_dataflow_region_matrix.sh`, so no peak RSS was collected in this
  pass.

| Operator | Mode | Topology/API | Median elapsed ms | Median GC ms | Median region op ms | Region objects | Opens/closes/resets |
|---|---|---|---:|---:|---:|---:|---:|
| SELECT | `gc-heap` | heap | 26.996 | 6.355 | 0.000 | 0 | 0 / 0 / 0 |
| SELECT | `region-scoped-rooted` | improved SafeZone | 23.339 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| SELECT | `region-stream-rootless` | trusted streaming | 22.563 | 0.000 | 0.050 | 1124990 | 1 / 1 / 9 |
| SELECT | `checked-epoch-scoped` | direct epoch, SafeZone-backed | 19.318 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| SELECT | `checked-epoch-stream` | direct epoch, Rift streaming | 21.328 | 0.000 | 0.056 | 1124990 | 1 / 1 / 10 |
| SELECT | `checked-page-token-scoped` | page-token SELECT only | 19.010 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| AGGREGATE | `gc-heap` | heap | 52.754 | 13.209 | 0.000 | 0 | 0 / 0 / 0 |
| AGGREGATE | `region-scoped-rooted` | improved SafeZone | 44.737 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| AGGREGATE | `region-stream-rootless` | trusted streaming | 50.345 | 0.000 | 0.341 | 1627152 | 1 / 1 / 9 |
| AGGREGATE | `checked-epoch-scoped` | direct epoch, SafeZone-backed | 36.016 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| AGGREGATE | `checked-epoch-stream` | direct epoch, Rift streaming | 39.926 | 0.000 | 0.300 | 1627152 | 1 / 1 / 10 |
| AGGREGATE | `checked-epoch-fold` | generic fold control | 93.491 | 2.648 | 0.126 | 1000004 | 11 / 11 / 0 |
| JOIN | `gc-heap` | heap | 31.281 | 9.672 | 0.000 | 0 | 0 / 0 / 0 |
| JOIN | `region-scoped-rooted` | improved SafeZone | 24.136 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| JOIN | `region-stream-rootless` | trusted streaming | 23.467 | 0.000 | 0.053 | 1078279 | 1 / 1 / 9 |
| JOIN | `checked-epoch-scoped` | direct epoch, SafeZone-backed | 20.082 | 0.000 | 0.000 | 0 | 0 / 0 / 0 |
| JOIN | `checked-epoch-stream` | direct epoch, Rift streaming | 21.307 | 0.000 | 0.052 | 1078279 | 1 / 1 / 10 |

Interpretation:

- Reusable direct `RiftRegion.epoch { ... }` now covers Broom/Dataflow-style
  SELECT, AGGREGATE, and JOIN, not only Yak/graph workloads.
- `checked-epoch-scoped` is the fastest full-operator row in this rerun:
  `19.318/36.016/20.082 ms` for SELECT/AGGREGATE/JOIN versus heap
  `26.996/52.754/31.281 ms` and improved SafeZone
  `23.339/44.737/24.136 ms`.
- SELECT still has a slightly faster specialized page-token row
  (`19.010 ms`), but page-token does not cover AGGREGATE/JOIN. The direct
  epoch API is the reusable topology for this whole operator family.
- `checked-epoch-stream` also beats heap and removes timed GC, but the
  SafeZone-backed scoped backend is faster in this matrix.
- `checked-epoch-fold` remains speed-gated. It is correct but much slower than
  direct epoch AGGREGATE, so it should stay out of application/headline claims
  until redesigned.
- This run did not collect peak RSS. Use the earlier native-only rows for RSS,
  or rerun this same matrix with an instrumented/native-only runner before
  using it in final paper tables.

## Handle-Backed Direct Epoch Gate

Date: 2026-05-13

This gate checks whether the `RiftOpenStreamingHandle` allocation-lowering path
can be promoted from focused allocation/PageToken/StreamFlex rows into the
Broom/Dataflow direct-epoch benchmark.

Outcome:

- SELECT/filter/project, AGGREGATE, and JOIN now use the handle-backed
  allocation path and keep the same checksums.
- The earlier AGGREGATE crash was caused by region-owned table arrays:
  `new Array[...]` in a zone is lowered through `Array.alloc(length,
  zone: SafeZone)`, but `RiftOpenStreamingHandle` did not satisfy `SafeZone`.
  The handle now implements the internal allocation-only `SafeZone` contract
  and routes `allocImpl` directly to the Rift allocator. Close/reset remain
  owner-controlled.
- The public reporting mode `checked-epoch-stream` is now handle-backed for the
  full Dataflow direct-epoch operator family. The explicit
  `checked-epoch-stream-open-handle` row remains a provenance/control alias.

Validation:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"

DATAFLOW_EPOCHS=2 DATAFLOW_DOCS_PER_EPOCH=1000 \
DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
DATAFLOW_MODES="checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle" \
DATAFLOW_OPERATOR=all \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BENCHMARK_RUNS=3 DATAFLOW_WARMUPS=0 \
DATAFLOW_MODES="checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle" \
DATAFLOW_OPERATOR=all \
  zsh sandbox/run_dataflow_region_matrix.sh

for op in select aggregate join; do
  for i in 1 2 3; do
    RIFT_FINAL_CLEAN=1 \
    DATAFLOW_BUILD=0 \
    DATAFLOW_EPOCHS=10 \
    DATAFLOW_DOCS_PER_EPOCH=100000 \
    DATAFLOW_BENCHMARK_RUNS=20 \
    DATAFLOW_WARMUPS=0 \
    DATAFLOW_OPERATOR=${op} \
    DATAFLOW_MODES="gc-heap region-scoped-rooted checked-epoch-scoped checked-epoch-stream" \
    DATAFLOW_OUTPUT_DIR=/tmp/rift-l1-dataflow-controls-${op}-20260513-r${i} \
      zsh sandbox/run_dataflow_region_instrumented_matrix.sh
  done
done
```

Results:

- sandbox compile: passed;
- compiler checked-region probes: `141/141`;
- runtime checked-region tests: `65/65`, including direct open-handle array
  allocation;
- parent and child `git diff --check`: passed after the evidence update.

Smoke result, 2 epochs x 1000 documents:

| Operator | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Checksum |
|---|---|---:|---:|---:|---:|---:|
| SELECT | `checked-epoch-stream-legacy` | 0.082 | 0.000 | 0.017 | 2251 | 258537945 |
| AGGREGATE | `checked-epoch-stream-legacy` | 1.303 | 0.000 | 0.039 | 4002 | 327600108 |
| JOIN | `checked-epoch-stream-legacy` | 0.056 | 0.000 | 0.001 | 2183 | 361336122 |
| SELECT | `checked-epoch-stream` | 0.155 | 0.000 | 0.014 | 2251 | 258537945 |
| AGGREGATE | `checked-epoch-stream` | 3.000 | 0.000 | 0.042 | 4002 | 327600108 |
| JOIN | `checked-epoch-stream` | 0.084 | 0.000 | 0.002 | 2183 | 361336122 |
| SELECT | `checked-epoch-stream-open-handle` | 0.045 | 0.000 | 0.005 | 2251 | 258537945 |
| AGGREGATE | `checked-epoch-stream-open-handle` | 1.371 | 0.000 | 0.031 | 4002 | 327600108 |
| JOIN | `checked-epoch-stream-open-handle` | 0.043 | 0.000 | 0.001 | 2183 | 361336122 |

The tiny smoke is only a correctness/routing check. It confirms that
handle-backed region-owned arrays no longer crash AGGREGATE.

10 epochs x 100k documents, 3-run L2 medians:

| Operator | Mode | Allocation path | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Checksum |
|---|---|---|---:|---:|---:|---:|---:|
| SELECT | `checked-epoch-stream-legacy` | old checked epoch allocation | 18.956 | 0.000 | 0.049 | 1124990 | 131080080920 |
| SELECT | `checked-epoch-stream` | promoted handle-backed direct epoch | 17.178 | 0.000 | 0.050 | 1124990 | 131080080920 |
| SELECT | `checked-epoch-stream-open-handle` | explicit handle-backed alias | 17.227 | 0.000 | 0.050 | 1124990 | 131080080920 |
| AGGREGATE | `checked-epoch-stream-legacy` | old checked epoch allocation | 36.888 | 0.000 | 0.300 | 1627152 | 163835709480 |
| AGGREGATE | `checked-epoch-stream` | promoted handle-backed direct epoch | 33.551 | 0.000 | 0.223 | 1627152 | 163835709480 |
| AGGREGATE | `checked-epoch-stream-open-handle` | explicit handle-backed alias | 33.639 | 0.000 | 0.274 | 1627152 | 163835709480 |
| JOIN | `checked-epoch-stream-legacy` | old checked epoch allocation | 20.270 | 0.000 | 0.056 | 1078279 | 193232836790 |
| JOIN | `checked-epoch-stream` | promoted handle-backed direct epoch | 19.133 | 0.000 | 0.052 | 1078279 | 193232836790 |
| JOIN | `checked-epoch-stream-open-handle` | explicit handle-backed alias | 18.409 | 0.000 | 0.053 | 1078279 | 193232836790 |

10 epochs x 100k documents x 20 internal iterations, 3-process L1 medians:

| Operator | Mode | L1 external total s | L1 per-iteration ms | L1 RSS bytes | Checksum |
|---|---|---:|---:|---:|---:|
| SELECT | `gc-heap` | 0.61 | 30.5 | 39288832 | 131080080920 |
| SELECT | `region-scoped-rooted` | 0.44 | 22.0 | 7569408 | 131080080920 |
| SELECT | `checked-epoch-scoped` | 0.35 | 17.5 | 7569408 | 131080080920 |
| SELECT | `checked-epoch-stream` | 0.31 | 15.5 | 7503872 | 131080080920 |
| AGGREGATE | `gc-heap` | 1.18 | 59.0 | 40091648 | 163835709480 |
| AGGREGATE | `region-scoped-rooted` | 0.78 | 39.0 | 10862592 | 163835709480 |
| AGGREGATE | `checked-epoch-scoped` | 0.65 | 32.5 | 10862592 | 163835709480 |
| AGGREGATE | `checked-epoch-stream` | 0.61 | 30.5 | 10813440 | 163835709480 |
| JOIN | `gc-heap` | 0.56 | 28.0 | 75071488 | 193232836790 |
| JOIN | `region-scoped-rooted` | 0.44 | 22.0 | 7421952 | 193232836790 |
| JOIN | `checked-epoch-scoped` | 0.37 | 18.5 | 7405568 | 193232836790 |
| JOIN | `checked-epoch-stream` | 0.31 | 15.5 | 7389184 | 193232836790 |

Legacy/default L1 promotion control, same scale:

| Operator | `checked-epoch-stream-legacy` total s | `checked-epoch-stream` total s | `checked-epoch-stream-open-handle` total s | Checksum |
|---|---:|---:|---:|---:|
| SELECT | 0.34 | 0.31 | 0.30 | 131080080920 |
| AGGREGATE | 0.65 | 0.61 | 0.62 | 163835709480 |
| JOIN | 0.35 | 0.31 | 0.31 | 193232836790 |
| all operators in one process | 1.35 | 1.25 | 1.23 | all three matched |

Interpretation:

- The default promoted row improves all three Dataflow operators versus the
  legacy checked stream path in this same-gate L2 run: SELECT `18.956 ->
  17.178 ms`, AGGREGATE `36.888 -> 33.551 ms`, and JOIN `20.270 ->
  19.133 ms`.
- The final-clean L1 control confirms the same direction against current
  heap/rooted/checked-scoped controls. The promoted `checked-epoch-stream`
  is now the fastest checked direct-epoch Dataflow row in this same binary:
  SELECT `15.5 ms/iter`, AGGREGATE `30.5 ms/iter`, and JOIN `15.5 ms/iter`,
  with matching checksums and no RSS regression.
- This closes the handle-array blocker for the current direct-epoch benchmark
  shape and upgrades the Dataflow handle-backed promotion from L2-only evidence
  to L1 headline timing plus L2 interpretation.

## Provisional Broom-Scale Single Run, 40 Epochs x 500k Documents

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
  zsh sandbox/run_dataflow_region_matrix.sh

DATAFLOW_BUILD=0 DATAFLOW_EPOCHS=40 DATAFLOW_DOCS_PER_EPOCH=500000 \
DATAFLOW_BENCHMARK_RUNS=1 DATAFLOW_WARMUPS=0 \
DATAFLOW_OUTPUT_DIR=/tmp/dataflow-region-instrumented-broom-scale \
  zsh sandbox/run_dataflow_region_instrumented_matrix.sh
```

Configuration:

- `DATAFLOW_EPOCHS=40`
- `DATAFLOW_DOCS_PER_EPOCH=500000`
- `DATAFLOW_AUTHORS_PER_EPOCH=20`
- `DATAFLOW_KEY_SPACE=65536`
- `DATAFLOW_AUTHOR_KEY_SPACE=256`
- `DATAFLOW_SELECT_MODULO=8`
- `DATAFLOW_BENCHMARK_RUNS=1`
- `DATAFLOW_WARMUPS=0`

| Operator | Mode | Elapsed ms | GC ms | Rift op ms | Region objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| SELECT | heap | 623.761 | 207.058 | 0.000 | 0 | 290504704 |
| SELECT | current SafeZone | 766.753 | 0.000 | 0.000 | 0 | 226590720 |
| SELECT | improved SafeZone | 463.303 | 0.000 | 0.000 | 0 | 226607104 |
| SELECT | Rift HPZone | 451.041 | 0.000 | 2.109 | 22500066 | 226361344 |
| SELECT | Rift Streaming | 452.422 | 0.000 | 1.848 | 22500066 | 226344960 |
| AGGREGATE | heap | 859.726 | 269.652 | 0.000 | 0 | 290504704 |
| AGGREGATE | current SafeZone | 924.995 | 0.000 | 0.000 | 0 | 226590720 |
| AGGREGATE | improved SafeZone | 618.133 | 0.000 | 0.000 | 0 | 226607104 |
| AGGREGATE | Rift HPZone | 620.342 | 0.000 | 2.048 | 22621480 | 226361344 |
| AGGREGATE | Rift Streaming | 630.151 | 0.000 | 2.112 | 22621480 | 226344960 |
| JOIN | heap | 602.540 | 184.929 | 0.000 | 0 | 290504704 |
| JOIN | current SafeZone | 748.152 | 0.000 | 0.000 | 0 | 226590720 |
| JOIN | improved SafeZone | 470.770 | 0.000 | 0.000 | 0 | 226607104 |
| JOIN | Rift HPZone | 447.803 | 0.000 | 0.968 | 21563289 | 226361344 |
| JOIN | Rift Streaming | 448.956 | 0.000 | 0.867 | 21563289 | 226344960 |

Interpretation:

- This is the closest current run to the Broom paper's reported vertex scale:
  40 epochs with 500k documents per epoch.
- It is still a methodology run, not exact Naiad/Broom.
- It is single-run evidence. Use it as a stress/provenance checkpoint, not as a
  headline median.
- The result suggests this harness is sensitive to GC when the object volume is
  raised to the paper-scale input shape.
- Improved SafeZone is again a strong baseline; Rift is slightly faster on
  SELECT and JOIN in this single run, while improved SafeZone is slightly faster
  on AGGREGATE.
