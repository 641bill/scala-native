# StreamIt Kernel Matrix

Last updated: 2026-05-12 00:22 CEST

Status: initial StreamIt-style BeamFormer/FilterBank throughput and latency
ports. These are methodology controls for StreamFlex-style axes, not exact
StreamFlex/Ovm artifact reproduction and not expected GC-heavy Rift wins.

## Source Provenance

The first port is based on the public MIT StreamIt benchmark sources from
`bthies/streamit`:

- `streams/apps/benchmarks/filterbank/streamit/FilterBankNew.str`
- `streams/apps/benchmarks/filterbank/c/filterbank.c`
- `streams/apps/benchmarks/beamformer/iter-streamit/BeamFormer.str`

The port keeps the core pipeline shapes:

- `filterbank`: generated float source, eight filter-bank branches, FIR/downsample/upsample/FIR, and combine.
- `beamformer`: channel input generation, coarse/fine FIR stages, beam formation, matched FIR, magnitude output.

The current implementation uses deterministic generated inputs and primitive
arrays/state, matching the classic StreamIt throughput-control shape. It does
not use the original StreamIt compiler, Ovm, StreamFlex runtime, StreamIt
scheduler, or exact output files. Treat results as `StreamIt-style`, not
`exact StreamFlex`.

## Benchmark Intent

This matrix exists because StreamFlex reports both throughput and latency/tail
predictability. It lets Rift report the same evaluation axes on familiar
StreamIt benchmark names even when the benchmark itself is not object-GC-heavy.

Required axes:

- throughput elapsed and records/outputs;
- latency p50/p95/p99/p999/max and deadline misses;
- RSS from external process measurement;
- GC time/count and Rift region counters in L2 interpretation rows.

## Modes

| Mode | Meaning |
|---|---|
| `heap` | Primitive StreamIt-style kernel on the Scala Native Immix heap. |
| `checked-epoch-scoped` | Same primitive kernel wrapped in checked scoped epoch topology. This is a control row; it should not be interpreted as region memory-management evidence because the kernel state is primitive arrays, not retained region objects. |
| `checked-epoch-stream` | Same primitive kernel wrapped in checked streaming epoch topology. |

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMIT_FILTERBANK_ITERATIONS=2 \
STREAMIT_BEAMFORMER_FRAMES=1 \
STREAMIT_LATENCY_ITERATIONS=4 \
STREAMIT_BENCHMARK_RUNS=1 \
STREAMIT_WARMUPS=0 \
STREAMIT_KERNEL_MODES="heap checked-epoch-scoped" \
STREAMIT_KERNEL_OUTPUT_DIR=/tmp/streamit-kernel-smoke \
  zsh sandbox/run_streamit_kernel_matrix.sh
```

Candidate throughput/latency row:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
STREAMIT_KERNEL_BUILD=0 \
STREAMIT_FILTERBANK_ITERATIONS=64 \
STREAMIT_BEAMFORMER_FRAMES=16 \
STREAMIT_LATENCY_ITERATIONS=128 \
STREAMIT_BENCHMARK_RUNS=3 \
STREAMIT_WARMUPS=1 \
STREAMIT_KERNEL_MODES="heap checked-epoch-scoped checked-epoch-stream" \
STREAMIT_KERNEL_OUTPUT_DIR=/tmp/streamit-kernel-matrix \
  zsh sandbox/run_streamit_kernel_matrix.sh
```

## Initial Smoke

Date/time: 2026-05-12 00:22 CEST.

Command:

```sh
STREAMIT_KERNEL_BUILD=0 \
STREAMIT_FILTERBANK_ITERATIONS=2 \
STREAMIT_BEAMFORMER_FRAMES=1 \
STREAMIT_LATENCY_ITERATIONS=4 \
STREAMIT_BENCHMARK_RUNS=1 \
STREAMIT_WARMUPS=0 \
STREAMIT_KERNEL_MODES="heap checked-epoch-scoped" \
STREAMIT_KERNEL_OUTPUT_DIR=/tmp/streamit-kernel-smoke-2026-05-12c \
  zsh sandbox/run_streamit_kernel_matrix.sh
```

The smoke passed checksum/output matching across heap and checked scoped epoch.
External `/usr/bin/time -l` RSS was unavailable in the sandbox because `time`
returned `sysctl kern.clockrate: Operation not permitted`; L2 in-process rows
are still valid for smoke correctness.

| Benchmark | Workload | Mode | Median ms | Median GC ms | p95 ns | Max ns | Deadline misses | Checksum | Output count |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| filterbank | throughput | heap | 14.479 | 0.000 |  |  |  | `-8134889661192982908` | 4096 |
| filterbank | throughput | checked-epoch-scoped | 13.610 | 0.000 |  |  |  | `-8134889661192982908` | 4096 |
| filterbank | latency | heap | 27.431 | 0.000 | 7184541 | 7184541 | 4 | `-9216380429369783879` | 8192 |
| filterbank | latency | checked-epoch-scoped | 27.210 | 0.000 | 7171583 | 7171583 | 4 | `-9216380429369783879` | 8192 |
| beamformer | throughput | heap | 26.873 | 0.296 |  |  |  | `5414983289155120464` | 2048 |
| beamformer | throughput | checked-epoch-scoped | 28.784 | 0.375 |  |  |  | `5414983289155120464` | 2048 |
| beamformer | latency | heap | 111.437 | 0.717 | 29725042 | 29725042 | 4 | `-4446879170294965056` | 8192 |
| beamformer | latency | checked-epoch-scoped | 116.992 | 0.710 | 33481458 | 33481458 | 4 | `-4446879170294965056` | 8192 |

Strict L1 smoke with external RSS:

```sh
RIFT_FINAL_CLEAN=1 \
STREAMIT_KERNEL_BUILD=0 \
STREAMIT_FILTERBANK_ITERATIONS=2 \
STREAMIT_BEAMFORMER_FRAMES=1 \
STREAMIT_LATENCY_ITERATIONS=4 \
STREAMIT_BENCHMARK_RUNS=1 \
STREAMIT_WARMUPS=0 \
STREAMIT_KERNEL_MODES="heap checked-epoch-scoped" \
STREAMIT_KERNEL_OUTPUT_DIR=/tmp/streamit-kernel-l1-smoke-2026-05-12 \
  zsh sandbox/run_streamit_kernel_matrix.sh
```

| Mode | External real s | Max RSS bytes | Checksum status |
|---|---:|---:|---|
| heap | 0.17 | 7929856 | all four FilterBank/BeamFormer throughput/latency checksums matched |
| checked-epoch-scoped | 0.17 | 7946240 | all four FilterBank/BeamFormer throughput/latency checksums matched |

## First 3-Run L2 Candidate

Date/time: 2026-05-12 00:22 CEST.

Command:

```sh
STREAMIT_KERNEL_BUILD=0 \
STREAMIT_FILTERBANK_ITERATIONS=64 \
STREAMIT_BEAMFORMER_FRAMES=16 \
STREAMIT_LATENCY_ITERATIONS=128 \
STREAMIT_BENCHMARK_RUNS=3 \
STREAMIT_WARMUPS=1 \
STREAMIT_KERNEL_MODES="heap checked-epoch-scoped checked-epoch-stream" \
STREAMIT_KERNEL_OUTPUT_DIR=/tmp/streamit-kernel-l2-2026-05-12 \
  zsh sandbox/run_streamit_kernel_matrix.sh
```

| Benchmark | Workload | Mode | Median ms | Median GC ms | p95 ns | Max ns | Deadline misses | External real s | RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| filterbank | throughput | heap | 406.173 | 0.000 |  |  |  | 24.60 | 8011776 |
| filterbank | throughput | checked-epoch-scoped | 406.661 | 0.000 |  |  |  | 24.96 | 8044544 |
| filterbank | throughput | checked-epoch-stream | 406.844 | 0.000 |  |  |  | 24.54 | 8060928 |
| filterbank | latency | heap | 815.160 | 0.319 | 6412375 | 6541125 | 128 | 24.60 | 8011776 |
| filterbank | latency | checked-epoch-scoped | 817.068 | 0.362 | 6441875 | 6681166 | 128 | 24.96 | 8044544 |
| filterbank | latency | checked-epoch-stream | 829.230 | 0.360 | 6430209 | 11937792 | 128 | 24.54 | 8060928 |
| beamformer | throughput | heap | 408.436 | 2.562 |  |  |  | 24.60 | 8011776 |
| beamformer | throughput | checked-epoch-scoped | 423.161 | 2.686 |  |  |  | 24.96 | 8044544 |
| beamformer | throughput | checked-epoch-stream | 410.086 | 2.615 |  |  |  | 24.54 | 8060928 |
| beamformer | latency | heap | 3284.119 | 22.236 | 25845708 | 30216625 | 128 | 24.60 | 8011776 |
| beamformer | latency | checked-epoch-scoped | 3288.088 | 22.078 | 25921084 | 30335584 | 128 | 24.96 | 8044544 |
| beamformer | latency | checked-epoch-stream | 3246.326 | 21.479 | 25763417 | 30330458 | 128 | 24.54 | 8060928 |

## Interpretation

- This is a StreamFlex/StreamIt comparison-axis control, not a Rift win yet.
- FilterBank has essentially zero GC in the smoke; a faithful primitive DSP
  port is not expected to be a GC-heavy region benchmark.
- BeamFormer has small GC from kernel/state allocation in the short smoke, but
  compute dominates.
- Checked epoch wrappers are useful to show the cost of applying Rift topology
  to primitive StreamIt kernels. They are not memory-management evidence unless
  a future object-state variant retains ordinary stream objects until a period
  or epoch boundary.
- The next useful step is a clean L1/L2 3-run matrix with external RSS, then an
  optional object-state StreamIt-inspired variant only if we need a GC-pressure
  stressor distinct from the faithful primitive controls.
