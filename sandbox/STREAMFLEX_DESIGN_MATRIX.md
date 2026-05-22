# StreamFlex Design Matrix

Last updated: 2026-05-20 14:43 CEST

Status: first Rift-native StreamFlex system-design reproduction. This is a
methodology benchmark for the StreamFlex axes: stable state, transient scoped
objects, bounded capsule transfer, throughput, paced latency, tail latency,
deadline misses, RSS, and GC/region interpretation. It is not an exact
StreamFlex/Ovm artifact reproduction.

## Design Mapping

| StreamFlex concept | Rift benchmark mapping |
|---|---|
| Stable state | Durable heap arrays for thresholds, counters, and score totals. |
| Transient state | Per-period packet, feature, decision, and alert objects allocated on heap, SafeZone, checked scoped epochs, or checked streaming epochs. |
| Capsule / transfer | `AlertCapsule`, a bounded primitive transfer buffer. It exports `(seq, key, score)` values across the transient boundary and never stores transient object references. |
| Periodic filter execution | Each period builds a four-stage object pipeline, exports alerts, updates stable state, then drops heap anchors or closes the epoch/region. |
| Throughput mode | Saturated replay over `STREAMFLEX_DESIGN_EVENTS`. |
| Latency mode | Per-event period replay with p50/p95/p99/p999/max latency and deadline misses versus `STREAMFLEX_DESIGN_PERIOD_NS`. |
| Allocation-pressure latency | Same latency protocol with more transient objects per event. |

## Modes

| Mode | Meaning |
|---|---|
| `gc-heap` | Natural heap implementation with stable heap state and transient heap objects retained until period close. |
| `heap-same-shape` | Same heap topology/control row. In v1 it is intentionally the same object pipeline as `gc-heap`, kept as the same-shape control label. |
| `region-scoped-rooted` | SafeZone-family scoped region per period, with rooted/coalesced metadata mode expected from `SAFEZONE_ROOTS_MODE=1` and `SAFEZONE_PAGE_SIZE=32768`. |
| `checked-epoch-scoped` | Checked `RiftRegion.epoch` API over the SafeZone-backed scoped backend. |
| `checked-epoch-stream` | Checked streaming epoch over the Rift backend. As of 2026-05-13, this benchmark label uses the handle-backed allocation lowering internally. |
| `checked-epoch-stream-legacy` | Previous checked streaming epoch path using `RiftRegion.epoch` plus `allocOpen`; retained as an internal control. |
| `checked-epoch-stream-open-handle` | Explicit alias for the handle-backed path, retained for provenance while the default label is promoted. |

## Runtime Policy Notes

`RIFT_REGION_REUSE_POLICY` applies to Rift-backed checked stream modes such as
`checked-epoch-stream`. The policies are opt-in throughput/RSS tradeoffs:
`default`, `bulk-zero-retained`, `cache-small`, `cache-large`, and
`prezero-large`. The legacy `RIFT_ZERO_REUSED_SLABS=1` setting remains an alias
for `bulk-zero-retained`.

The 2026-05-15 focused allocator gate found `cache-large` fastest on
allocation-only rows, but the first 1M StreamFlexDesign throughput smoke was
neutral after rerun:

| Policy | L1 external real s | L1 RSS bytes | L2 median ms | L2 region op ms | Checksum |
|---|---:|---:|---:|---:|---:|
| `default` | 0.93 | 7913472 | 373.053 | 1.330 | -7120610804659902001 |
| `cache-large` | 0.93 | 7913472 | 372.851 | 1.247 | -7120610804659902001 |

Interpretation: the policy is useful for focused allocation pressure, but this
StreamFlexDesign row is currently dominated by stable-state/query CPU,
capsule add/drain, linked-object traversal, and allocation body cost outside
region pool bookkeeping.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMFLEX_DESIGN_EVENTS=20000 \
STREAMFLEX_DESIGN_LATENCY_EVENTS=1000 \
STREAMFLEX_DESIGN_PRESSURE_LATENCY_EVENTS=1000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=1 \
STREAMFLEX_DESIGN_WARMUPS=0 \
STREAMFLEX_DESIGN_WORKLOAD=all \
STREAMFLEX_DESIGN_OUTPUT_DIR=/tmp/streamflex-design-smoke \
  zsh sandbox/run_streamflex_design_matrix.sh
```

L1 final-clean throughput:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
RIFT_FINAL_CLEAN=1 \
STREAMFLEX_DESIGN_BUILD=0 \
STREAMFLEX_DESIGN_EVENTS=1000000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WORKLOAD=throughput \
STREAMFLEX_DESIGN_OUTPUT_DIR=/tmp/streamflex-design-throughput-1m-l1 \
  zsh sandbox/run_streamflex_design_matrix.sh
```

L2 throughput and latency:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMFLEX_DESIGN_BUILD=0 \
STREAMFLEX_DESIGN_EVENTS=1000000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WARMUPS=1 \
STREAMFLEX_DESIGN_WORKLOAD=throughput \
STREAMFLEX_DESIGN_OUTPUT_DIR=/tmp/streamflex-design-throughput-1m-rss \
  zsh sandbox/run_streamflex_design_matrix.sh

STREAMFLEX_DESIGN_BUILD=0 \
STREAMFLEX_DESIGN_LATENCY_EVENTS=10000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WARMUPS=1 \
STREAMFLEX_DESIGN_WORKLOAD=latency \
STREAMFLEX_DESIGN_OUTPUT_DIR=/tmp/streamflex-design-latency-10k-rss \
  zsh sandbox/run_streamflex_design_matrix.sh

STREAMFLEX_DESIGN_BUILD=0 \
STREAMFLEX_DESIGN_PRESSURE_LATENCY_EVENTS=50000 \
STREAMFLEX_DESIGN_PRESSURE_OBJECTS_PER_EVENT=64 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WARMUPS=1 \
STREAMFLEX_DESIGN_WORKLOAD=pressure-latency \
STREAMFLEX_DESIGN_OUTPUT_DIR=/tmp/streamflex-design-pressure-latency-rss \
  zsh sandbox/run_streamflex_design_matrix.sh
```

## Smoke

Date/time: 2026-05-12 01:00 CEST.

Command: 20k throughput events, 1k latency events, 1k pressure-latency events,
one timed run, all five modes.

All modes matched checksums/output counts:

- throughput checksum `3496158305702065933`, output count `20013`;
- latency checksum `-8617174139091994251`, output count `1979`;
- pressure-latency checksum `-4727308449738168636`, output count `7927`.

Small-smoke interpretation:

- checked scoped was fastest in throughput (`8.964 ms`) and ordinary latency
  (`1.216 ms`);
- checked scoped and checked stream removed the heap deadline miss in ordinary
  latency;
- checked stream removed all deadline misses in pressure latency.

## L1 Final-Clean Throughput, 1M Events x 3 Runs

Date/time: 2026-05-12 01:02 CEST.

L1 rows use external `/usr/bin/time -l`; no GC/region counters are read in the
timed section. Each process executes three full benchmark runs.

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 1.52 | 1.51 | 12402688 | `-7120610804659902001` | 997627 |
| `heap-same-shape` | 1.52 | 1.51 | 12402688 | `-7120610804659902001` | 997627 |
| `region-scoped-rooted` | 1.44 | 1.44 | 7897088 | `-7120610804659902001` | 997627 |
| `checked-epoch-scoped` | 1.27 | 1.27 | 7913472 | `-7120610804659902001` | 997627 |
| `checked-epoch-stream` | 1.33 | 1.32 | 8028160 | `-7120610804659902001` | 997627 |

Interpretation: this is a StreamFlex-design throughput win for the reusable
checked epoch topology. `checked-epoch-scoped` is `16.4%` faster than
`gc-heap`/`heap-same-shape` in L1 and uses about `36%` less RSS.

### L1 Final-Clean Throughput Rerun, 20M Events x 3 Runs

Date/time: 2026-05-12 15:53 CEST.

This rerun follows the open-allocation wrapper cleanup in child `c22c78d57`.
Each process executes three full benchmark runs; use the external process time
and RSS as L1 headline data.

Source: `/Users/siyaoliu/rift/cache/clean-rerun-20260512-streamflex-throughput`.

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 31.26 | 30.24 | 12402688 | `5305809911915216923` | 19999119 |
| `checked-epoch-scoped` | 24.39 | 23.65 | 12615680 | `5305809911915216923` | 19999119 |
| `checked-epoch-stream` | 22.81 | 22.39 | 12566528 | `5305809911915216923` | 19999119 |

Interpretation: at the larger StreamFlex-design throughput scale, checked
stream is the fastest checked row in this L1 rerun (`27.0%` lower external real
time than heap), while checked scoped is `22.0%` lower than heap. RSS is close
across these three rows; this is a throughput/GC-avoidance case rather than an
RSS claim.

### L1 Follow-Up After Region-Cached Allocation Stats

Date/time: 2026-05-12 17:07 CEST.

After the Rift runtime cached allocation-stats mode on the region, the fastest
checked stream row was rerun at the same 20M-event scale.

Source:
`/Users/siyaoliu/rift/cache/streamflex-design-region-cached-stats-20260512`.

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `checked-epoch-stream` | 22.55 | 22.02 | 12550144 | `5305809911915216923` | 19999119 |

Interpretation: this is a modest application-level improvement over the clean
rerun (`22.81 s`). The post-change L4 profile no longer samples
`scalanative_rift_alloc_stats_enabled`; remaining samples are allocation body,
zeroing, stable-state query work, and capsule add/drain.

### L1 Follow-Up After Current-Slab Zeroed Cache

Date/time: 2026-05-13 00:32 CEST.

After the Rift runtime cached current-slab zeroed state on each region, the
fastest checked stream row was rerun at the same 20M-event scale.

Source:
`/Users/siyaoliu/rift/cache/streamflex-design-current-slab-zeroed-cache-20260513`.

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `checked-epoch-stream` | 22.31 | 21.90 | 12550144 | `5305809911915216923` | 19999119 |

Interpretation: this is a small but consistent application-level improvement
over the region-cached stats gate (`22.55 s`) and the clean rerun (`22.81 s`).
The row still measures throughput/GC avoidance rather than RSS: RSS remains
close to the previous checked stream rows.

### L1/L2 Follow-Up After Handle-Backed Default Promotion

Date/time: 2026-05-13 10:26 CEST.

The default `checked-epoch-stream` benchmark label now uses the direct
handle-backed allocation lowering that was previously exposed only as
`checked-epoch-stream-open-handle`. The previous open-region lowering remains
available as `checked-epoch-stream-legacy`.

L1 final-clean command shape:

```sh
RIFT_FINAL_CLEAN=1 \
STREAMFLEX_DESIGN_BUILD=0 \
STREAMFLEX_DESIGN_WORKLOAD=throughput \
STREAMFLEX_DESIGN_EVENTS=20000000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WARMUPS=0 \
STREAMFLEX_DESIGN_MODES="checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle" \
STREAMFLEX_DESIGN_OUTPUT_DIR=/Users/siyaoliu/rift/cache/streamflex-design-handle-promoted-20m-l1-20260513 \
zsh sandbox/run_streamflex_design_matrix.sh
```

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `checked-epoch-stream-legacy` | `22.68` | `22.52` | `12615680` | `5305809911915216923` | `19999119` |
| `checked-epoch-stream` | `21.01` | `20.75` | `12599296` | `5305809911915216923` | `19999119` |
| `checked-epoch-stream-open-handle` | `20.79` | `20.54` | `12599296` | `5305809911915216923` | `19999119` |

L2 interpretation rows:

| Mode | Median ms | Records/sec | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | Opens/Closes/Resets | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `checked-epoch-stream-legacy` | `8776.217` | `2278886.135` | `0.551` | `1.922` | `3/3` | `25.825` | `499999119` | `1/1/78125` | `12369920` |
| `checked-epoch-stream` | `8096.514` | `2470198.928` | `0.500` | `0.725` | `3/3` | `24.768` | `499999119` | `1/1/78125` | `12369920` |
| `checked-epoch-stream-open-handle` | `8090.915` | `2471908.218` | `0.419` | `0.496` | `2/3` | `25.635` | `499999119` | `1/1/78125` | `16646144` |

Interpretation: the promoted default preserves checksum/output, object count,
and epoch reset topology while removing the older open-region allocation path
from the headline label. On the 20M L1 gate, default
`checked-epoch-stream` improves over legacy by about `7.4%` (`22.68 s` to
`21.01 s`) with essentially unchanged RSS. L2 shows the same effect in the
timed region (`8776.217 ms` to `8096.514 ms`) while keeping the same
`499999119` region-object count and `78125` resets. Treat the explicit
`checked-epoch-stream-open-handle` row as a provenance alias; future
StreamFlexDesign reporting should use `checked-epoch-stream` as the promoted
checked streaming backend row.

## All-Optimizations L1 Throughput Gate, 20M Events x 3 Runs

Date/time: 2026-05-13 17:29 CEST.

This final-clean gate checks the promoted handle-backed checked stream default
against heap, the legacy checked stream control, the explicit open-handle
provenance alias, rooted scoped SafeZone, and checked scoped SafeZone. All rows
matched checksum `5305809911915216923` and output count `19999119`.

Source:
`/Users/siyaoliu/rift/cache/streamflex-design-allopts-20260513`.

Command shape:

```sh
RIFT_FINAL_CLEAN=1 \
STREAMFLEX_DESIGN_EVENTS=20000000 \
STREAMFLEX_DESIGN_BENCHMARK_RUNS=3 \
STREAMFLEX_DESIGN_WARMUPS=0 \
STREAMFLEX_DESIGN_WORKLOAD=throughput \
STREAMFLEX_DESIGN_MODES="gc-heap checked-epoch-stream-legacy checked-epoch-stream checked-epoch-stream-open-handle region-scoped-rooted checked-epoch-scoped" \
STREAMFLEX_DESIGN_OUTPUT_DIR=/Users/siyaoliu/rift/cache/streamflex-design-allopts-20260513 \
  zsh sandbox/run_streamflex_design_matrix.sh
```

| Mode | L1 external real s | L1 user s | RSS bytes | Checksum | Outputs |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 30.90 | 30.55 | 12419072 | 5305809911915216923 | 19999119 |
| `checked-epoch-stream-legacy` | 21.32 | 21.27 | 12599296 | 5305809911915216923 | 19999119 |
| `checked-epoch-stream` | 19.25 | 19.21 | 12582912 | 5305809911915216923 | 19999119 |
| `checked-epoch-stream-open-handle` | 19.23 | 19.20 | 12582912 | 5305809911915216923 | 19999119 |
| `region-scoped-rooted` | 28.47 | 28.18 | 12681216 | 5305809911915216923 | 19999119 |
| `checked-epoch-scoped` | 23.41 | 23.27 | 12697600 | 5305809911915216923 | 19999119 |

Interpretation: the optimized default `checked-epoch-stream` is `37.7%`
faster than heap and `9.7%` faster than the legacy checked stream path, with
no RSS regression. The explicit open-handle alias is effectively identical to
the default, confirming that the default label now carries the handle-backed
allocation lowering. This satisfies the application-gate requirement for the
checked stream epoch backend.

### L1/L2 Transfer Gate After Proof-Gated No-Zero Lowering

Date/time: 2026-05-15 01:22 CEST.

This transfer gate reruns the representative StreamFlex-design workload after
normal `RiftOpenStreamingHandle` allocation gained proof-gated no-zero lowering
for definitely initialized primitive-field record shapes. It is not a new
StreamFlex artifact claim; it checks whether the latest allocation lowering
still transfers to the StreamFlex-style stable/transient/capsule benchmark.

L1 final-clean throughput, 20M events x 3 runs:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 29.04 | 28.99 | 12419072 | `5305809911915216923` | 19999119 |
| `checked-epoch-stream` | 19.91 | 19.72 | 12582912 | `5305809911915216923` | 19999119 |
| `checked-epoch-stream-legacy` | 22.19 | 22.03 | 12599296 | `5305809911915216923` | 19999119 |
| `checked-epoch-scoped` | 23.57 | 23.52 | 12664832 | `5305809911915216923` | 19999119 |

L2 throughput, 1M events x 3 runs:

| Mode | Median ms | Records/sec | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 493.207 | 2027545.898 | 97.029 | 98.915 | 3 | 0.000 | 0 | 12517376 |
| `checked-epoch-stream` | 382.353 | 2615384.781 | 0.000 | 0.151 | 1 | 1.216 | 24997627 | 12697600 |
| `checked-epoch-stream-legacy` | 422.225 | 2368407.810 | 0.000 | 0.177 | 1 | 1.208 | 24997627 | 12713984 |
| `checked-epoch-scoped` | 390.927 | 2558025.345 | 0.000 | 0.177 | 1 | 0.000 | 0 | 12763136 |

L2 allocation-pressure latency, 50k events x 3 runs, deadline `80000 ns`:

| Mode | Median ms | Median GC ms | Max GC ms | p50 ns | p95 ns | p99 ns | p999 ns | Max ns | Deadline misses | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 196.985 | 25.658 | 26.301 | 3209 | 3500 | 3750 | 28000 | 667875 | 49 | 12517376 |
| `checked-epoch-stream` | 170.864 | 0.705 | 0.757 | 3167 | 3458 | 3625 | 4417 | 88458 | 1 | 21495808 |
| `checked-epoch-stream-legacy` | 184.954 | 0.782 | 0.847 | 3458 | 3709 | 3875 | 4708 | 30083 | 0 | 21479424 |
| `checked-epoch-scoped` | 170.563 | 0.726 | 0.731 | 3208 | 3458 | 3584 | 4333 | 22084 | 0 | 21528576 |

Interpretation:

- The optimized checked stream default remains clearly faster than the legacy
  checked stream path: `19.91 s` versus `22.19 s` in L1 throughput and
  `382.353 ms` versus `422.225 ms` in the 1M L2 loop.
- Against heap, the checked stream row is `31.4%` lower L1 external time and
  removes heap's `97.029 ms` median timed GC in the L2 throughput row.
- In allocation-pressure latency, checked stream and checked scoped both remove
  nearly all GC pause time and reduce deadline misses from `49` to `0-1`.
  Checked scoped has the best max-latency row in this run; checked stream is
  the direct evidence that the Rift-backed allocation lowering transfers to
  the StreamFlex-style backend.

### L1 Transfer Gate After Stats-Disabled Object Fast Path

Date/time: 2026-05-20 00:05 CEST.

This gate reruns the representative StreamFlex-design workload after the Rift
runtime gained a final-clean no-stats managed-object allocation helper. It is
a transfer check for the allocation-body cleanup, not an exact StreamFlex/Ovm
artifact claim and not profiler timing.

Artifacts:

- `/Users/siyaoliu/rift/cache/streamflex-design-nostats-fastpath-20m-20260520`

L1 final-clean throughput, 20M events x 3 runs:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 32.71 | 32.67 | 12468224 | `5305809911915216923` | 19999119 |
| `checked-epoch-stream` | 19.63 | 19.58 | 12582912 | `5305809911915216923` | 19999119 |
| `checked-epoch-scoped` | 26.04 | 25.99 | 12632064 | `5305809911915216923` | 19999119 |

Follow-up L4 profile:

- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-streamflex-nostats-fastpath`

With the callback-ref classifier enabled, coarse buckets for
`checked-epoch-stream` are callback-ref-shaped checked body `1055` samples,
region allocation/init `1225`, query mutator `1220`, traversal/capsule `172`,
and other `145`. Interpretation: the runtime fast path improves final-clean
elapsed by removing allocator-body constant cost, but the remaining
StreamFlex-design work is still split across allocation/init, stable-state
query work, checked callback source shape, and capsule traversal.

### Callback-Local Source-Shape Probe

Date/time: 2026-05-20 14:35 CEST.

The checked streaming throughput loop was refactored so checksum/output/drop
counters live inside the `streamingOpenHandle` callback, with immutable
per-period values passed into each reset body. This mirrors the accepted
Wikimedia/Theodolite source-shape cleanup and is not a query rewrite.

Artifacts:

- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-streamflex-callback-local`
- `/Users/siyaoliu/rift/cache/streamflex-design-callback-local-20m-20260520`

The 20k smoke matched the previous checksum/output for
`checked-epoch-stream` and `checked-epoch-stream-inferred`:
checksum `3496158305702065933`, output `20013`.

L4 result for `checked-epoch-stream` at 20M events:

| Profile | Callback-ref samples/sec | Traversal/capsule samples/sec | Region alloc/init samples/sec | Query mutator samples/sec |
|---|---:|---:|---:|---:|
| Before callback-local source shape | `211.00` | `34.40` | `245.00` | `244.00` |
| After callback-local source shape | `0.00` | `35.40` | `273.60` | `491.60` |

Interpretation: the source-shape change removes the generated
`scala.runtime.*Ref` callback signature from sampled top frames, but it does
not remove the underlying pipeline work. After adding
`StreamFlexDesignMatrixHelpers.*anonfun` to the query classifier, the exposed
work is ordinary checked query/body work plus region allocation/init. It is not
a capsule/traversal regression; actual capsule/traversal samples remain small
at about `35` samples/sec.

L1 final-clean throughput, 20M events x 3 runs:

| Mode | External real s | External user s | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| `gc-heap` | 34.58 | 31.68 | 12337152 | `5305809911915216923` | 19999119 |
| `checked-epoch-stream` | 19.98 | 19.43 | 12566528 | `5305809911915216923` | 19999119 |

Interpretation: this is accepted as source-shape/profile-clarity cleanup, not
as a new speed claim. The checked stream row remains much faster than heap,
but the callback-local change by itself is timing-neutral to slightly slower
relative to the prior `19.63 s` checkpoint, likely because the removed
callback-ref marker was mostly body attribution rather than direct allocation
time. The next StreamFlex-specific target remains allocation/init and
query/object pipeline work, not more counter-localization or capsule traversal.

## Experimental Reusable-Slab Bulk-Zero Gate

Date/time: 2026-05-14 13:59 CEST.

This gate tests `RIFT_ZERO_REUSED_SLABS=1`, which bulk-zeros dead non-huge Rift
slabs at close/reset before caching them for reuse. It is not the default. The
policy preserves zero-initialization semantics but moves zeroing from each
object allocation to the region boundary.

| Events x runs | Policy | L1 external real s | L1 user s | RSS bytes | Checksum | Outputs |
|---|---|---:|---:|---:|---:|---:|
| 2M x3 | default | 2.31 | 2.01 | 8093696 | -3301579998455784484 | 1998001 |
| 2M x3 | `RIFT_ZERO_REUSED_SLABS=1` | 1.90 | 1.89 | 8093696 | -3301579998455784484 | 1998001 |
| 20M x3 | default | 19.51 | 19.50 | 12599296 | 5305809911915216923 | 19999119 |
| 20M x3 | `RIFT_ZERO_REUSED_SLABS=1` | 18.91 | 18.90 | 12599296 | 5305809911915216923 | 19999119 |

L2 interpretation rows, 20M events x3 with one warmup:

| Policy | Median ms | Records/sec | GC median ms | GC max ms | Runs with GC | Rift op ms | Region objects | Opens/closes/resets | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| default | 7517.881 | 2660323.967 | 0.000 | 0.836 | 1/3 | 23.563 | 499,999,119 | 1/1/78,125 | 21626880 |
| `RIFT_ZERO_REUSED_SLABS=1` | 7330.702 | 2728251.627 | 0.000 | 0.841 | 1/3 | 254.858 | 499,999,119 | 1/1/78,125 | 21626880 |

Interpretation: the focused allocation-row win transfers to this
StreamFlex-design checked epoch workload, but more modestly at 20M: about
`3.1%` external-time improvement and `2.5%` L2 median improvement with
identical checksum/output and RSS. The cost is visible in region op time:
close/reset accounting rises from `23.563 ms` to `254.858 ms`. Keep the policy
experimental until page/window and real-input gates confirm the close/reset
zeroing tradeoff is broadly favorable.

## L2 Throughput, 1M Events x 3 Runs

Date/time: 2026-05-12 01:01 CEST.

| Mode | Median ms | Records/sec | Median GC ms | Max GC ms | Runs with GC | Max GC collections | Rift op ms | Rift objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 509.277 | 1963566.514 | 100.234 | 101.708 | 3 | 118 | 0.000 | 0 | 12484608 |
| `heap-same-shape` | 477.047 | 2096230.246 | 69.221 | 72.278 | 3 | 53 | 0.000 | 0 | 21430272 |
| `region-scoped-rooted` | 482.902 | 2070813.720 | 0.000 | 0.171 | 1 | 1 | 0.000 | 0 | 12681216 |
| `checked-epoch-scoped` | 422.638 | 2366090.840 | 0.000 | 0.166 | 1 | 1 | 0.000 | 0 | 12697600 |
| `checked-epoch-stream` | 443.363 | 2255489.861 | 0.000 | 0.164 | 1 | 1 | 1.167 | 24997627 | 12681216 |

Interpretation: L2 explains the L1 win. Heap rows spend `69-100 ms` in timed
GC across the median run; checked/scoped rows reduce timed GC to zero median
and one tiny startup collection in the max row.

## L2 Paced Latency, 10k Events x 3 Runs

Date/time: 2026-05-12 01:01 CEST. Deadline: `80000 ns`.

| Mode | Median ms | Median GC ms | p50 ns | p95 ns | p99 ns | p999 ns | Max ns | Deadline misses | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 12.031 | 1.394 | 875 | 1000 | 1083 | 1167 | 569542 | 2 | 12484608 |
| `heap-same-shape` | 11.972 | 1.576 | 875 | 1000 | 1083 | 1166 | 912500 | 1 | 21413888 |
| `region-scoped-rooted` | 14.193 | 0.311 | 1167 | 1333 | 1375 | 1500 | 6500 | 0 | 12533760 |
| `checked-epoch-scoped` | 12.733 | 0.403 | 1041 | 1167 | 1250 | 1333 | 12709 | 0 | 12533760 |
| `checked-epoch-stream` | 13.510 | 0.311 | 1125 | 1291 | 1334 | 1459 | 5666 | 0 | 12500992 |

Interpretation: ordinary paced latency is not a throughput win for all region
rows, but it is a tail/deadline win: heap max latency is hundreds of
microseconds with misses, while checked/region rows have zero misses.

## L2 Allocation-Pressure Latency, 50k Events x 3 Runs

Date/time: 2026-05-12 01:02 CEST. Deadline: `80000 ns`.

| Mode | Median ms | Median GC ms | p50 ns | p95 ns | p99 ns | p999 ns | Max ns | Deadline misses | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `gc-heap` | 206.182 | 26.610 | 3333 | 3542 | 3667 | 9542 | 728000 | 48 | 12484608 |
| `heap-same-shape` | 202.148 | 21.606 | 3333 | 3542 | 3667 | 11792 | 1356958 | 22 | 21430272 |
| `region-scoped-rooted` | 219.484 | 0.794 | 4084 | 4334 | 4458 | 4667 | 801958 | 1 | 21479424 |
| `checked-epoch-scoped` | 192.432 | 0.794 | 3542 | 3791 | 3875 | 4334 | 798875 | 1 | 21512192 |
| `checked-epoch-stream` | 208.106 | 1.284 | 3875 | 4125 | 4250 | 5042 | 20875 | 0 | 21430272 |

Interpretation: this is the first StreamFlex-design allocation-pressure row.
`checked-epoch-scoped` is fastest by elapsed and cuts timed GC from `21-27 ms`
to below `1 ms`. `checked-epoch-stream` is not fastest, but it is the strongest
tail/deadline row: max latency falls to `20875 ns` and deadline misses are `0`.

## Classification

- Input type: generated methodology.
- Comparison class: checked framework API win for throughput and
  allocation-pressure latency; tail-latency win for paced latency.
- Allowed claim: local StreamFlex-design reproduction evidence for stable /
  transient / capsule region design. It is not exact StreamFlex/Ovm artifact
  evidence.
- Memory-management caveat: this is object-retained per-period pipeline
  evidence, not summary-only/manual-array evidence. The capsule exports
  primitive values only after transient objects have been materialized.
