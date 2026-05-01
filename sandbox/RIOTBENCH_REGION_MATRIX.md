# RIoTBench Region Matrix

Date: 2026-05-01

Status: new RIoTBench-style local memory-management probe. This is not an
exact RIoTBench distributed stream-processing artifact. It is a generated or
preloaded local Scala Native matrix for IoT ETL/statistics shapes where
external brokers and services do not hide allocator/GC behavior.

## Workload

Implementation:

- `sandbox/src/main/scala-next/RiotBenchRegionMatrix.scala`
- `sandbox/run_riotbench_region_matrix.sh`

Queries:

- `q0-parse`: one ordinary sensor-reading object per input event.
- `q1-clean-annotate`: raw reading plus cleaned/annotated output object for
  valid readings.
- `q2-window-stats`: cleaned readings retained in sensor windows while durable
  per-sensor sums/counts stay in primitive heap arrays.

Modes:

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone buckets with
  `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone buckets with
  `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: trusted HPZone per event bucket.
- `rift-streaming`: trusted Streaming region per event bucket.

No checked mode is included yet. Add checked mode only if the matching checked
append/window or fold operator has already cleared a focused gate.

## Commands

Compile:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```bash
RIOTBENCH_EVENTS=20000 \
RIOTBENCH_BENCHMARK_RUNS=1 \
RIOTBENCH_WARMUPS=0 \
RIOTBENCH_OUTPUT_DIR=/tmp/riotbench-smoke \
zsh sandbox/run_riotbench_region_matrix.sh
```

100k medians:

```bash
RIOTBENCH_EVENTS=100000 \
RIOTBENCH_BENCHMARK_RUNS=3 \
RIOTBENCH_WARMUPS=1 \
RIOTBENCH_OUTPUT_DIR=/tmp/riotbench-100k \
RIOTBENCH_BUILD=0 \
zsh sandbox/run_riotbench_region_matrix.sh
```

## Current Results

### 20k Smoke

The 20k smoke matched checksum/output count across all modes and all three
queries. It is a plumbing row, not headline evidence. Q1 and Q2 triggered one
heap collection in the single timed run, so the matrix was advanced to 100k.

### 100k Medians

Command:

```bash
RIOTBENCH_EVENTS=100000 \
RIOTBENCH_BENCHMARK_RUNS=3 \
RIOTBENCH_WARMUPS=1 \
RIOTBENCH_OUTPUT_DIR=/tmp/riotbench-100k \
RIOTBENCH_BUILD=0 \
zsh sandbox/run_riotbench_region_matrix.sh
```

Checksums and output counts matched across all modes.

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-parse | heap | 10.624 | 0.000 | 4.667 | 1 | 0.000 | 0 | 39731200 | 100000 |
| q0-parse | safezone-improved | 10.939 | 0.000 | 1.785 | 1 | 0.000 | 0 | 27590656 | 100000 |
| q0-parse | rift-hp | 11.403 | 0.000 | 0.943 | 1 | 0.010 | 100000 | 27443200 | 100000 |
| q0-parse | rift-streaming | 11.654 | 0.000 | 0.976 | 1 | 0.010 | 100000 | 27508736 | 100000 |
| q1-clean-annotate | heap | 16.643 | 4.508 | 11.150 | 2 | 0.000 | 0 | 39698432 | 186520 |
| q1-clean-annotate | safezone-improved | 13.980 | 0.000 | 0.000 | 0 | 0.000 | 0 | 32456704 | 186520 |
| q1-clean-annotate | rift-hp | 14.516 | 0.000 | 0.000 | 0 | 0.016 | 186520 | 32309248 | 186520 |
| q1-clean-annotate | rift-streaming | 14.445 | 0.000 | 0.000 | 0 | 0.022 | 186520 | 32374784 | 186520 |
| q2-window-stats | heap | 16.621 | 0.000 | 4.477 | 1 | 0.000 | 0 | 39714816 | 86520 |
| q2-window-stats | safezone-improved | 16.702 | 0.000 | 2.422 | 1 | 0.000 | 0 | 26836992 | 86520 |
| q2-window-stats | rift-hp | 17.164 | 0.000 | 0.963 | 1 | 0.009 | 86520 | 26787840 | 86520 |
| q2-window-stats | rift-streaming | 17.367 | 0.000 | 0.953 | 1 | 0.009 | 86520 | 26755072 | 86520 |

Interpretation:

- `q1-clean-annotate` is the only useful early signal: improved SafeZone is
  fastest, with trusted Rift close behind and lower RSS than heap. Rift does
  not beat improved SafeZone.
- `q0-parse` is heap-fast and low-pressure.
- `q2-window-stats` reduces RSS and max-GC in region modes, but heap and
  improved SafeZone are faster. Do not tune this shape before a 1M scale check
  or a cheaper checked aggregate operator.

## Interpretation Contract

- Treat this as a RIoTBench-style methodology probe, not exact RIoTBench.
- Keep heap/Rift logical behavior aligned; only allocation placement and
  lifetime policy should differ.
- Durable sensor aggregate metadata stays heap/primitive control state.
- Stream reading, cleaned reading, and window-entry objects are the region
  candidates.
- Do not add a checked mode until a focused checked operator gate passes.
