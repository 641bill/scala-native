# StreamFlex Design Matrix

Last updated: 2026-05-12 17:22 CEST

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
| `checked-epoch-stream` | Checked `RiftRegion.epoch` API over the Rift streaming backend. |

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
