# Theodolite Power Region Matrix

Date: 2026-05-11
Last updated: 2026-05-21 12:07 CEST

Status: implemented a local single-process Theodolite UC2/UC4-style real-input
kernel over the UCI Household Electric Power Consumption trace. This is not an
exact Theodolite artifact reproduction: Theodolite's public implementation is
Kafka/Flink/Kubernetes-oriented and its documented active-power load path is
generated. This matrix instead pairs the same downsampling/hierarchical
aggregation shape with a public real power-meter trace so external framework
overhead does not hide memory-management behavior. The matrix now also has an
explicit `THEODOLITE_POWER_INPUT_MODE=streaming-file` path: real records are
parsed inside each benchmark run, no parsed full-input arrays are retained, and
active memory is bounded by the current epoch plus durable aggregate metadata.
The streaming path now uses the shared byte-line reader and a reusable
semicolon-delimited byte-field cursor instead of `String.split`, so the parser
does not manufacture a `String[]` per record. The checked stream epoch path now
also has a handle-backed allocation lowering; the old generic checked path is
retained as `checked-epoch-stream-legacy`.

The current retained-object real-streaming row is `q3-retained-uc4`: it keeps
the UCI power trace compressed on disk, streams the archive member during each
run, and allocates one measurement plus twelve hierarchy contribution objects
per usable record. This models the Theodolite UC4 hierarchical aggregation
shape more directly than `q2-hierarchical`, which retains only three
contribution objects per record.

## Input

Real input:
`/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt`

Source archive:
`/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.zip`

Archive-member input spec:
`zip:/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.zip!household_power_consumption.txt`

SHA-256:
`9f84b46ade8a2d8e1286ec4b2b6c2987a45a755c59f263be3b3b3d10dfbda3ff`

The extracted file has `2075260` lines including the header. The current parser
skips malformed/missing-value records, so the full q2 row loaded `2049280`
usable measurements.

## Queries

| Query | Shape | Region lifetime |
|---|---|---|
| `q1-downsample` | Allocate one ordinary measurement record per real input line; aggregate by group/window. | Epoch/window batch; durable aggregate arrays stay heap/primitive metadata. |
| `q2-hierarchical` | Allocate one measurement plus three hierarchy contribution records per real input line; aggregate by group/window and hierarchy level. | Epoch/window batch; hierarchy/group state stays heap/primitive metadata. |
| `q3-retained-uc4` | Allocate one measurement plus twelve UC4-style hierarchy contribution records per real input line; aggregate circuit, room, floor, building, and site-level windows. | Epoch/window batch; retained measurement/contribution objects close in bulk while hierarchy/group arrays stay heap/primitive metadata. |

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=20000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=5000 \
THEODOLITE_POWER_BENCHMARK_RUNS=1 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q1-downsample q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-smoke-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

1M L2/interpretable rows:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
THEODOLITE_POWER_QUERIES="q1-downsample q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-1m-2026-05-11-rss \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

Full local q2:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=2075259 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-full-q2-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

Full local q2 L1 final-clean:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=2075259 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted region-stream-rootless checked-epoch-stream checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-power-full-q2-l1-2026-05-11 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

1M q2 streaming-file L1/L2 with byte-field parser:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT_MODE=streaming-file \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/tmp/rift-theodolite-bytecursor-1m-20260513 \
zsh sandbox/run_theodolite_power_region_matrix.sh

RIFT_FINAL_CLEAN=1 \
THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT_MODE=streaming-file \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_MODES="heap-immix region-scoped-rooted checked-epoch-scoped" \
THEODOLITE_POWER_OUTPUT_DIR=/tmp/rift-theodolite-bytecursor-1m-l1-20260513 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

1M q2 streaming-file handle-backed checked epoch gate:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
THEODOLITE_POWER_INPUT_MODE=streaming-file \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=0 \
THEODOLITE_POWER_QUERIES=q2-hierarchical \
THEODOLITE_POWER_MODES="heap-immix checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle checked-epoch-scoped region-scoped-rooted" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-handle-promotion-20260514 \
zsh sandbox/run_theodolite_power_region_matrix.sh

THEODOLITE_POWER_BUILD=0 \
THEODOLITE_POWER_INPUT_MODE=streaming-file \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_RECORDS_PER_EPOCH=25000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
THEODOLITE_POWER_QUERIES=q2-hierarchical \
THEODOLITE_POWER_MODES="heap-immix checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle checked-epoch-scoped region-scoped-rooted" \
THEODOLITE_POWER_OUTPUT_DIR=/Users/siyaoliu/rift/cache/theodolite-handle-promotion-l2-20260514 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

## 20k Smoke

All modes matched checksums/output counts.

## Retained UC4 Hierarchy Windows

Date/time: 2026-05-17 17:45 CEST.

This row is the next investigation-driven real-streaming candidate. It uses
the compressed UCI household-power archive directly:

`THEODOLITE_POWER_INPUT_MODE=streaming-file`

`THEODOLITE_POWER_INPUT=zip:/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.zip!household_power_consumption.txt`

Query: `q3-retained-uc4`.

Shape: one retained measurement plus twelve retained hierarchy contribution
objects per usable record. Durable aggregate arrays remain heap/control
metadata. No parsed full-input array is retained in the timed path.

Smoke source:
`/private/tmp/rift-theodolite-uc4-smoke-20260517`.

1M L1 source:
`/private/tmp/rift-theodolite-uc4-1m-l1-20260517`.

1M L2 source:
`/private/tmp/rift-theodolite-uc4-1m-l2-20260517`.

Full local L1 source:
`/private/tmp/rift-theodolite-uc4-full-l1-20260517`.

Full local L2 source:
`/private/tmp/rift-theodolite-uc4-full-l2-20260517`.

Heap-cap probes:
`/private/tmp/rift-theodolite-uc4-full-l1-heapcap128m-20260517`,
`/private/tmp/rift-theodolite-uc4-full-l1-heapcap64m-20260517`,
and `/private/tmp/rift-theodolite-uc4-full-l1-checked-cap64m-20260517`.

20k smoke matched checksum `-2895454912458695581` and output count `6176`
for heap, checked stream, checked scoped, and rooted scoped.

### 1M Streaming-File Gate

All rows matched checksum `5496025699187626461` and output count `61760`.

| Mode | L1 real s | L1 RSS bytes | L2 median ms | L2 median GC ms | L2 max GC ms | L2 runs with GC | L2 Rift op ms | L2 region objects | Claim |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | 9.42 | 183,566,336 | 2712.028 | 376.791 | 393.169 | 3/3 | 0.000 | 0 | Natural heap/GC baseline. |
| `checked-epoch-stream` | 7.77 | 31,064,064 | 2143.809 | 38.516 | 41.907 | 3/3 | 1.420 | 13,000,000 | Checked Rift stream epoch. |
| `checked-epoch-scoped` | 7.99 | 31,113,216 | 2189.232 | 43.837 | 48.301 | 3/3 | 0.000 | 0 | Checked SafeZone-backed scoped comparison. |
| `region-scoped-rooted` | 8.21 | 31,129,600 | 2299.931 | 44.424 | 49.323 | 3/3 | 0.000 | 0 | Rooted scoped backend baseline. |

### Full Local Streaming-File Gate

The full local row requested `2075259` records and processed `2049280` usable
measurements after skipping missing/malformed rows. All rows matched checksum
`6053646443718331766` and output count `126608`.

| Mode | L1 real s | L1 RSS bytes | L2 median ms | L2 median GC ms | L2 max GC ms | L2 runs with GC | L2 Rift op ms | L2 region objects | Claim |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | 16.85 | 207,077,376 | 4504.438 | 335.309 | 482.380 | 3/3 | 0.000 | 0 | Natural heap/GC baseline. |
| `checked-epoch-stream` | 14.06 | 26,607,616 | 3769.962 | 31.570 | 33.884 | 3/3 | 3.175 | 26,640,640 | Checked Rift stream epoch. |
| `checked-epoch-scoped` | 14.88 | 26,689,536 | 3865.198 | 39.062 | 54.579 | 3/3 | 0.000 | 0 | Checked SafeZone-backed scoped comparison. |
| `region-scoped-rooted` | 15.54 | 26,640,384 | 4104.244 | 37.961 | 37.991 | 3/3 | 0.000 | 0 | Rooted scoped backend baseline. |

Heap fixed-memory probes:

| Mode | Heap cap | L1 status | L1 real s | L1 RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-immix` | uncapped | pass | 16.85 | 207,077,376 | Baseline. |
| `heap-immix` | 128M | pass | 17.90 | 138,821,632 | Slower under cap. |
| `heap-immix` | 64M | fail | 9.87 | 72,204,288 | Out of heap before result row. |
| `checked-epoch-stream` | 64M | pass | 14.27 | 26,591,232 | Same checksum/output as uncapped checked row. |

Interpretation: `q3-retained-uc4` is the strongest Theodolite real-streaming
row so far. Unlike the older q2 row, heap GC is material at 1M and full local
scale, and the checked Rift row improves both L1 real time and clean RSS. It
also gives a fixed-memory signal: the natural heap row fails at `64M`, while
checked Rift completes under the same cap. This is still a local
Theodolite-style kernel paired with a real UCI power trace, not an exact
Theodolite artifact reproduction.

### 2026-05-20 Checked Loop-Shape Follow-Up

The mutator-parity audit found that the checked streaming callback for
`q3-retained-uc4` captured mutable outer loop state. The sampled checked Rift
top frame contained `scala.runtime.IntRef`/`LongRef`, so the row still paid
heap allocation/runtime-helper cost in the checked path. The fix mirrors the
Wikimedia clickstream cleanup: the checked epoch body now uses local primitive
accumulators and returns a small primitive-only `StreamingEpochOutcome` after
close. Query semantics and allocation placement of retained measurement and
contribution objects are unchanged.

Validation:

- Compile: `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- Smoke:
  `/Users/siyaoliu/rift/cache/theodolite-loopshape-smoke-20260520`.
  20k `q3-retained-uc4` matched checksum `-2895454912458695581` and
  output count `12352` across `heap-immix`, `checked-epoch-stream`, and
  `checked-epoch-scoped`.
- 1M follow-up:
  `/Users/siyaoliu/rift/cache/theodolite-loopshape-1m-20260520`.
  All rows matched checksum `5496025699187626461` and output count `61760`.
- L4 follow-up:
  `/Users/siyaoliu/rift/cache/profile-sweep-20260520-theodolite-loopshape`.
  `rg "LongRef|IntRef|ObjectRef|BooleanRef"` finds no matches in the patched
  checked Rift profile.

1M L2 follow-up. Records: `1000000`. Epoch size: `25000`. Runs: `3`,
warmups: `1`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Rift op ms | Region objects | Checksum/output |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | `2464.646` | `282.408` | `369.951` | `3/3` | `257802240` | `0.000` | `0` | match |
| `checked-epoch-stream` | `2049.682` | `41.666` | `44.700` | `3/3` | `196739072` | `1.305` | `13000000` | match |
| `checked-epoch-scoped` | `2061.617` | `40.000` | `40.060` | `3/3` | `196788224` | `0.000` | `0` | match |

Compared with the earlier 1M retained-UC4 L2 row, checked Rift improves from
`2143.809 ms` to `2049.682 ms` while preserving output. The L4 bucket summary
for the patched checked Rift row is now parser/input/hash dominated
(`168.60 samples/sec`) with small region allocation/init (`8.60 samples/sec`).
Residual heap allocation/init (`23.20 samples/sec`) is mostly stream
source/archive/runtime allocation, not retained hierarchy objects. This is a
validated mutator-parity cleanup, not a new region-inference capability.

## Retained UC4 Byte-Cursor Parser Follow-Up

Date/time: 2026-05-20 19:44 CEST.

The retained-UC4 parser follow-up replaces the reusable `DelimitedByteFields`
cursor in `parseCurrentLine` with a direct semicolon byte cursor for the fields
needed by the query: active power (`2`), voltage (`4`), and the three submeter
fields (`6`, `7`, `8`). The accepted version still calls the existing
`BenchmarkInputSupport.parseMilliDecimal` and `parseIntDecimal` helpers after
locating each field. This is a shared parser/input cleanup for heap and checked
rows, not a checked-region allocation optimization. A fully fused numeric parser
variant was tried first and rejected because it made the 1M gate slower.

Validation:

- Compile: `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile`
  passed.
- 20k smoke:
  `/Users/siyaoliu/rift/cache/theodolite-bytecursor-parser-smoke-20260520`.
  `heap-immix`, `checked-epoch-stream`, and `checked-epoch-scoped` matched
  checksum `-2895454912458695581` and output count `6176`.
- 1M L2:
  `/Users/siyaoliu/rift/cache/theodolite-bytecursor-parser-1m-l2-20260520`.
  All rows matched checksum `5496025699187626461` and output count `61760`.
- L4:
  `/Users/siyaoliu/rift/cache/profile-sweep-20260520-theodolite-bytecursor-parser`.
  All profiled rows matched checksum/output.

1M L2 follow-up. Records: `1000000`. Epoch size: `25000`. Runs: `3`,
warmups: `1`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Rift op ms | Region objects | Checksum/output |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | `2325.524` | `197.187` | `236.490` | `3/3` | `257818624` | `0.000` | `0` | match |
| `checked-epoch-stream` | `1965.787` | `35.653` | `36.434` | `3/3` | `196755456` | `1.069` | `13000000` | match |
| `checked-epoch-scoped` | `1999.749` | `40.604` | `41.713` | `3/3` | `196820992` | `0.000` | `0` | match |

L4 active-work buckets, samples/sec:

| Mode | Parser/input/hash | Query mutator | Safepoint poll | GC mark/sweep metadata | Heap alloc/init | Region alloc/init | Zeroing/memset | Other |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `133.80` | `49.80` | `51.40` | `43.40` | `25.60` | `0.00` | `12.60` | `55.60` |
| `checked-epoch-stream` | `147.00` | `23.40` | `48.40` | `20.40` | `19.00` | `6.20` | `9.20` | `31.20` |
| `checked-epoch-scoped` | `148.20` | `24.40` | `50.60` | `24.00` | `19.00` | `8.80` | `13.40` | `34.20` |

Interpretation: the byte-cursor parser reduces the checked parser/input bucket
from the post-loop-shape `168.60 samples/sec` to `147.00 samples/sec`, and the
1M L2 gate improves all three modes versus the loop-shape checkpoint. The
remaining retained-UC4 CPU is still mostly shared source/archive/parser/runtime
floor. Region allocation/init is visible but small, so this row should not drive
region lifecycle tuning.

## Retained UC4 Active-Handle Source-Placement Follow-Up

Date/time: 2026-05-21 12:07 CEST.

The retained-UC4 checked Rift handle paths now use ordinary `new` under
`RiftRegion.resetOpenHandle` for `CheckedMeasurement` and
`CheckedContribution` records. This replaces
`RiftAllocator.allocateOpenHandle(region, new ...)` in both the streaming-file
and preloaded handle paths. The scoped/open-region paths and legacy explicit
allocation controls remain unchanged, so this is source-use evidence for the
existing active-handle inference path rather than a query rewrite.

Focused real-input smoke:
`/tmp/theodolite-inferred-openhandle-smoke-20260521`. Input was the compressed
UCI archive member, query `q3-retained-uc4`, records `20000`, warmups `0`,
runs `1`.

| Mode | Median ms | Median GC ms | Rift op ms | Region objects | RSS bytes | Checksum/output |
|---|---:|---:|---:|---:|---:|---|
| `heap-immix` | `678.118` | `219.360` | `0.000` | `0` | `94044160` | match |
| `checked-epoch-stream` | `489.551` | `46.542` | `0.556` | `260000` | `59899904` | match |
| `checked-epoch-scoped` | `492.509` | `46.431` | `0.000` | `0` | `59916288` | match |

All rows matched checksum `-2895454912458695581` and output count `1544`.
This proves the real retained-UC4 handle source now exercises ordinary
active-handle `new` placement. It does not add an L1/L2 elapsed claim or a new
L4 bucket claim.

## Handle-Backed Checked Epoch Promotion

Date/time: 2026-05-14 13:17 CEST.

This gate promotes the real streaming q2 checked epoch path from generic
`RiftRegion.allocOpen` to backend-known `RiftAllocator.allocateOpenHandle`.
The public benchmark label `checked-epoch-stream` is the optimized default;
`checked-epoch-stream-legacy` keeps the old generic lowering, and
`checked-epoch-stream-open-handle` is an explicit alias/provenance row.

L1 source:
`/Users/siyaoliu/rift/cache/theodolite-handle-promotion-20260514`.

L2 source:
`/Users/siyaoliu/rift/cache/theodolite-handle-promotion-l2-20260514`.

Input mode: `THEODOLITE_POWER_INPUT_MODE=streaming-file`. The benchmark parses
the UCI file inside each run and does not retain a parsed full-input array.
Records: `1000000`. Epoch size: `25000`. Query: `q2-hierarchical`.
All rows matched checksum `7683095093045065342` and output count `40960`.

| Mode | L1 real s | L1 RSS bytes | L2 median ms | L2 median GC ms | L2 max GC ms | L2 Rift op ms | L2 region objects | Claim |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | 4.33 | 75,300,864 | 1054.432 | 19.906 | 20.980 | 0.000 | 0 | Natural heap baseline. |
| `checked-epoch-stream-legacy` | 3.83 | 15,908,864 | 1006.176 | 0.000 | 0.000 | 0.387 | 4,000,000 | Legacy generic checked stream epoch. |
| `checked-epoch-stream` | 3.80 | 15,892,480 | 993.191 | 0.000 | 0.000 | 0.406 | 4,000,000 | Optimized checked stream epoch default. |
| `checked-epoch-stream-open-handle` | 3.80 | 15,892,480 | 1002.330 | 0.000 | 0.000 | 0.376 | 4,000,000 | Explicit alias/provenance row. |
| `checked-epoch-scoped` | 3.83 | 15,974,400 | 995.167 | 0.000 | 0.000 | 0.000 | 0 | Checked SafeZone-backed scoped control. |
| `region-scoped-rooted` | 3.90 | 15,925,248 | 1009.541 | 0.000 | 0.000 | 0.000 | 0 | Rooted scoped baseline. |

Interpretation: backend-known checked allocation gives a modest win on this
real streaming row: optimized checked stream is about `0.8%` faster than the
legacy checked stream in L1 and about `1.3%` faster in L2, while retaining the
large RSS reduction versus heap. The result is smaller than SPECjbb because
the byte parser and aggregate/update CPU dominate; still, it confirms that the
handle-backed lowering is safe and directionally positive outside generated
stressors.

## Experimental Reusable-Slab Bulk-Zero Gate

Date/time: 2026-05-14 14:39 CEST.

This real streaming-input gate tests `RIFT_ZERO_REUSED_SLABS=1` on the
optimized `checked-epoch-stream` q2 path. The policy bulk-zeros dead non-huge
Rift slabs at close/reset before cache reuse. It preserves zero-initialization
semantics but can touch more pages at epoch boundaries.

| Policy | L1 real s | L1 user s | L1 RSS bytes | L2 median ms | L2 GC ms | L2 Rift op ms | L2 region objects | Checksum | Output count |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| default | 3.94 | 3.64 | 15,908,864 | 927.612 | 0.000 | 0.215 | 4,000,000 | 7683095093045065342 | 40,960 |
| `RIFT_ZERO_REUSED_SLABS=1` | 3.60 | 3.57 | 24,870,912 | 922.823 | 0.000 | 1.362 | 4,000,000 | 7683095093045065342 | 40,960 |

Interpretation: this is a real streaming-input throughput-positive but
RSS-negative result. L1 improves by about `8.6%`, and L2 median improves
slightly, but RSS rises because bulk zeroing touches reusable pages that the
default policy leaves dirty and less resident. Therefore reusable-slab
bulk-zeroing should stay experimental and workload-selective rather than
becoming a default after only generated/focused wins.

## Reference-Field No-Zero Transfer Gate

Date/time: 2026-05-15 13:38 CEST.

This L2 transfer gate reruns the real streaming-file q2 row after the
definite-initialization proof was broadened from primitive-only records to
reference-field records. The benchmark parses the UCI file inside each run and
does not retain a parsed full-input array. It is a real-streaming-input
interpretation row, not an L1 final-clean headline row.

Command:

```sh
THEODOLITE_POWER_OUTPUT_DIR=/private/tmp/rift-ref-nozero-theodolite-q2-20260515 \
THEODOLITE_POWER_INPUT_MODE=streaming-file \
THEODOLITE_POWER_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/theodolite/real-power/household_power_consumption.txt \
THEODOLITE_POWER_MODES="heap-immix checked-epoch-stream checked-epoch-stream-legacy checked-epoch-scoped region-scoped-rooted" \
THEODOLITE_POWER_QUERIES="q2-hierarchical" \
THEODOLITE_POWER_RECORDS=1000000 \
THEODOLITE_POWER_BENCHMARK_RUNS=3 \
THEODOLITE_POWER_WARMUPS=1 \
zsh sandbox/run_theodolite_power_region_matrix.sh
```

Records: `1000000`. Query: `q2-hierarchical`. All rows matched checksum
`7683095093045065342` and output count `40960`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Region op ms | Region objects | RSS bytes | Claim |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `heap-immix` | 988.976 | 19.292 | 19.629 | 3/3 | 0.000 | 0 | 75350016 | Natural heap baseline. |
| `checked-epoch-stream-legacy` | 963.018 | 0.000 | 0.000 | 0/3 | 0.386 | 4000000 | 78495744 | Legacy generic checked stream epoch. |
| `checked-epoch-stream` | 952.664 | 0.000 | 0.000 | 0/3 | 0.354 | 4000000 | 78512128 | Optimized checked stream epoch default. |
| `checked-epoch-scoped` | 954.529 | 0.000 | 0.000 | 0/3 | 0.000 | 0 | 78512128 | Checked SafeZone-backed scoped control. |
| `region-scoped-rooted` | 965.048 | 0.000 | 0.000 | 0/3 | 0.000 | 0 | 78528512 | Rooted scoped baseline. |

Interpretation: the real streaming q2 row remains modest but positive for the
optimized checked stream epoch path. It beats heap by about `3.7%`, removes
about `19.3 ms` of median timed heap GC, and beats the legacy checked stream
path by about `1.1%`. The reference-field proof does not produce a large new
speedup here because source scanning, byte parsing, and hierarchy-query CPU are
now the dominant costs. The latest L2 RSS values are close across region modes
and slightly above heap, so this specific transfer gate should be reported as
throughput/GC evidence rather than an RSS win; older L1 final-clean rows remain
the source for headline elapsed/RSS.

| Query | Mode | Median ms | Median GC ms | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|
| `q1-downsample` | `heap-immix` | `0.559` | `0.000` | `17104896` | `1024` |
| `q1-downsample` | `region-scoped-rooted` | `0.628` | `0.000` | `17039360` | `1024` |
| `q1-downsample` | `region-stream-rootless` | `0.616` | `0.000` | `17088512` | `1024` |
| `q1-downsample` | `checked-epoch-scoped` | `0.569` | `0.000` | `17072128` | `1024` |
| `q2-hierarchical` | `heap-immix` | `2.481` | `0.000` | `19775488` | `4096` |
| `q2-hierarchical` | `region-scoped-rooted` | `3.124` | `0.000` | `20332544` | `4096` |
| `q2-hierarchical` | `region-stream-rootless` | `3.357` | `0.000` | `20348928` | `4096` |
| `q2-hierarchical` | `checked-epoch-scoped` | `2.881` | `0.000` | `20348928` | `4096` |

## 1M Rows

Loaded records: `1000000`. Epoch size: `25000`. Runs: `3`, warmups: `1`.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|
| `q1-downsample` | `heap-immix` | `26.481` | `0.000` | `0.000` | `0/3` | `145670144` | `10240` |
| `q1-downsample` | `region-scoped-rooted` | `27.722` | `0.000` | `0.000` | `0/3` | `146391040` | `10240` |
| `q1-downsample` | `region-stream-rootless` | `28.082` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q1-downsample` | `checked-epoch-stream` | `25.598` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q1-downsample` | `checked-epoch-scoped` | `24.362` | `0.000` | `0.000` | `0/3` | `146341888` | `10240` |
| `q2-hierarchical` | `heap-immix` | `133.198` | `12.399` | `12.872` | `3/3` | `145735680` | `40960` |
| `q2-hierarchical` | `region-scoped-rooted` | `145.026` | `0.000` | `0.000` | `0/3` | `149012480` | `40960` |
| `q2-hierarchical` | `region-stream-rootless` | `163.998` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |
| `q2-hierarchical` | `checked-epoch-stream` | `145.491` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |
| `q2-hierarchical` | `checked-epoch-scoped` | `129.511` | `0.000` | `0.000` | `0/3` | `148963328` | `40960` |

## Full Local q2

Requested records: `2075259`. Loaded records: `2049280`. Epoch size: `25000`.
Runs: `3`, warmups: `1`.

| Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `287.296` | `20.354` | `20.450` | `3/3` | `306806784` | `83968` |
| `region-scoped-rooted` | `319.224` | `0.000` | `0.000` | `0/3` | `310116352` | `83968` |
| `region-stream-rootless` | `336.759` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |
| `checked-epoch-stream` | `301.347` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |
| `checked-epoch-scoped` | `288.495` | `0.000` | `0.000` | `0/3` | `310067200` | `83968` |

## Full Local q2 L1 Final-Clean

Requested records: `2075259`. Loaded records: `2049280`. Runs per external
process: `3`. No internal GC/region counter reads in the timed query loop.

| Mode | External real s | External user s | External sys s | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `5.46` | `5.28` | `0.14` | `306872320` | `83968` |
| `region-scoped-rooted` | `5.50` | `5.35` | `0.13` | `304349184` | `83968` |
| `region-stream-rootless` | `5.61` | `5.44` | `0.13` | `304283648` | `83968` |
| `checked-epoch-stream` | `5.49` | `5.33` | `0.13` | `304332800` | `83968` |
| `checked-epoch-scoped` | `5.45` | `5.29` | `0.13` | `304398336` | `83968` |

## Streaming-File q2

Input mode: `THEODOLITE_POWER_INPUT_MODE=streaming-file`. The benchmark parses
the real power trace inside each run instead of preloading parsed primitive
arrays before timing. The current row uses the byte-line/byte-field parser.

1M L2 standard-stats row. Records: `1000000`. Epoch size: `25000`. Runs: `3`,
warmups: `0`.

| Mode | External s | Median ms | Median GC ms | Max GC ms | Runs with GC | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|---:|---:|
| `heap-immix` | `4.90` | `1007.030` | `19.932` | `20.834` | `3/3` | `75284480` | `40960` |
| `region-scoped-rooted` | `4.79` | `977.056` | `0.000` | `0.000` | `0/3` | `78544896` | `40960` |
| `checked-epoch-scoped` | `4.76` | `967.144` | `0.000` | `0.000` | `0/3` | `78528512` | `40960` |

1M L1 final-clean row. Runs per external process: `3`. No internal
GC/region counter reads in the timed query loop.

| Mode | External real s | External user s | External sys s | RSS bytes | Output count |
|---|---:|---:|---:|---:|---:|
| `heap-immix` | `3.87` | `3.80` | `0.05` | `75300864` | `40960` |
| `region-scoped-rooted` | `3.88` | `3.81` | `0.04` | `15925248` | `40960` |
| `checked-epoch-scoped` | `3.77` | `3.70` | `0.04` | `24854528` | `40960` |

## Interpretation

Theodolite real power q2 is a useful real-input control, but not the missing
GC-heavy flagship:

- The q1 downsampling query is zero-GC at 1M and should be treated as a
  ceiling/control row.
- The q2 hierarchical query does create ordinary intermediate objects and heap
  GC is visible: `12.399 ms` median timed GC at 1M and `20.354 ms` on the full
  local trace.
- `checked-epoch-scoped` removes timed heap GC and is the best checked/safe
  q2 mode. At 1M it is a small kernel throughput win over heap
  (`129.511 ms` vs `133.198 ms`), but at full local input it is an elapsed tie
  in L2 (`288.495 ms` vs `287.296 ms`).
- The strict L1 final-clean row includes input parsing/preload and confirms
  the same end-to-end story: `checked-epoch-scoped` is effectively tied/slightly
  faster (`5.45 s` versus heap `5.46 s`) with slightly lower RSS (`304 MB`
  versus heap `307 MB`). Parser/file CPU dominates the end-to-end workload.

The streaming-file q2 row is stronger than the older preloaded/full-local
control because it keeps the input source true to stream-processing semantics.
The first streaming-file row used `String.split`; the byte-field parser is the
cleaner current result. It cuts heap L2 from `2479.703 ms` and `143.088 ms`
GC to `1007.030 ms` and `19.932 ms` GC, showing that parser allocation was
inflating the first streaming row. With parser allocation controlled, checked
scoped epoch remains an L1 throughput/RSS/fixed-memory win (`3.77 s`,
`24.9 MB` RSS) versus heap (`3.87 s`, `75.3 MB` RSS), and removes timed heap
GC in L2 (`967.144 ms`, `0 ms` GC).

Decision: keep Theodolite streaming-file q2 as real-streaming-input
throughput/RSS/fixed-memory evidence. It is still not the missing flagship
"huge GC" stream case; after parser allocation is controlled, heap GC is about
`2%` of L2 elapsed, and the remaining work is source scanning, query CPU, and
ordinary retained measurement/contribution objects.
