# UnsafeZone-HP Baseline Matrix

Date: 2026-05-01

Status: implementation and smoke validation checkpoint. This is not headline
performance evidence yet.

## Definition

`UnsafeZone-HP` is a benchmark-only SafeZone configuration:

- binary/runtime mode: `safezone`
- label in scripts/results: `unsafezone-hp`
- `SAFEZONE_ROOTS_MODE=3`
- `SAFEZONE_PAGE_SIZE=32768`

Root mode `3` disables SafeZone GC root registration/removal for pages and
chunks. It is intentionally unsafe: if objects in no-root SafeZone memory retain
GC heap objects and a collection occurs, the GC cannot discover those
references. This mode exists only to test whether SafeZone's page/pool
mechanics are a better high-throughput substrate once root bookkeeping is
removed.

There is no `unsafezone-streaming` mode in this checkpoint. SafeZone closes and
reclaims zones; it does not implement Rift Streaming's reset lifecycle.

## Implementation Scope

Completed:

- `SAFEZONE_ROOTS_MODE` now accepts `3`.
- Normal and large SafeZone pages skip `GC_add_roots` and `GC_remove_roots` in
  mode `3`.
- Existing root modes `0`, `1`, and `2` remain unchanged.
- Benchmark runners understand `unsafezone-hp` as a label mapped to SafeZone
  mode plus `SAFEZONE_ROOTS_MODE=3 SAFEZONE_PAGE_SIZE=32768`.
- DEBS Q1/Q2/RunBoth accept `safezone-current`, `safezone-improved`, and
  `unsafezone-hp` labels while preserving those labels in output filenames and
  summaries.

Not done:

- No public Scala API.
- No checked safety integration.
- No headline medians yet.

## Validation

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Result: passed.

Shell syntax:

```sh
zsh -n sandbox/run_gcbench_runtime_matrix.sh \
  sandbox/run_gcbench_topology_matrix.sh \
  sandbox/run_listoflists_runtime_matrix.sh \
  sandbox/run_listoflists_flat_matrix.sh \
  sandbox/run_listoflists_chunked_matrix.sh \
  sandbox/run_listoflists_topology_matrix.sh \
  sandbox/run_listoflists_topology_report_subset.sh \
  sandbox/run_pipeline_runtime_matrix.sh \
  sandbox/run_dataflow_region_matrix.sh \
  sandbox/run_dataflow_region_instrumented_matrix.sh \
  sandbox/run_streamflex_region_instrumented_matrix.sh \
  sandbox/run_yak_region_instrumented_matrix.sh \
  sandbox/run_stancu_region_instrumented_matrix.sh \
  sandbox/run_nexmark_region_matrix.sh \
  sandbox/run_common_crawl_wet_matrix.sh \
  sandbox/run_wikimedia_region_matrix.sh \
  sandbox/run_linear_road_region_matrix.sh \
  sandbox/run_yahoo_ad_region_matrix.sh \
  sandbox/run_riotbench_region_matrix.sh \
  bench/debs2015/run_both_sample_matrix.sh \
  bench/debs2015/run_both_instrumented_matrix.sh
```

Result: passed.

## Smoke Rows

### GCBench Runtime

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
GCBENCH_BENCHMARK_RUNS=1 zsh sandbox/run_gcbench_runtime_matrix.sh
```

Single-run smoke results:

| Label | Binary mode | Root mode | Page size | Elapsed ms |
|---|---|---:|---:|---:|
| heap | `heap` | default | default | 267.363 |
| safezone-current | `safezone` | 0 | default | 710.067 |
| safezone-improved | `safezone` | 1 | default | 263.200 |
| unsafezone-hp | `safezone` | 3 | 32768 | 250.579 |
| rift-hp | `rift-hp` | default | default | 291.065 |

Interpretation: root mode `3` was accepted by the native runtime and completed
GCBench. This is a one-run smoke only; do not compare it to previous medians as
headline evidence.

### NEXMark Q3

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
NEXMARK_EVENTS=20000 \
NEXMARK_BENCHMARK_RUNS=1 \
NEXMARK_QUERIES=q3 \
NEXMARK_MODES="heap safezone-current safezone-improved unsafezone-hp rift-hp" \
NEXMARK_OUTPUT_DIR=/tmp/nexmark-unsafezone-smoke \
zsh sandbox/run_nexmark_region_matrix.sh
```

Single-run smoke results:

| Mode | Elapsed ms | GC ms | RSS bytes | Checksum | Output count |
|---|---:|---:|---:|---:|---:|
| heap | 7.206 | 0.753 | 12435456 | 1290373575068669401 | 457 |
| safezone-current | 5.872 | 0.485 | 12746752 | 1290373575068669401 | 457 |
| safezone-improved | 5.906 | 0.464 | 12730368 | 1290373575068669401 | 457 |
| unsafezone-hp | 5.659 | 0.426 | 12713984 | 1290373575068669401 | 457 |
| rift-hp | 5.866 | 0.420 | 12681216 | 1290373575068669401 | 457 |

Interpretation: stream-script mode mapping works, checksums/output counts match,
and the summary preserves the `unsafezone-hp` label. This is a one-run 20k
smoke only.

### DEBS RunBoth Sample

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_MODES="heap safezone-current safezone-improved unsafezone-hp rift-hp" \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-unsafezone-sample \
zsh bench/debs2015/run_both_sample_matrix.sh
```

Result: passed. Q1/Q2 output equality matched heap after stripping latency.
Runtime logs confirmed:

- `safezone-current`: `SAFEZONE_ROOTS_MODE=0`
- `safezone-improved`: `SAFEZONE_ROOTS_MODE=1`
- `unsafezone-hp`: `SAFEZONE_ROOTS_MODE=3`, `SAFEZONE_PAGE_SIZE=32768`

This sample has only two events and is correctness/mode-wiring validation, not
performance evidence.

## Next Headline Runs

Use the comprehensive runner after committing this checkpoint:

```sh
cd /Users/siyaoliu/rift
RIFT_EVAL_RUN_ID=2026-05-01-unsafezone-core-prior \
RIFT_EVAL_SCALE=headline \
RIFT_EVAL_SUITES="preflight core prior" \
bash scripts/run-performance-evaluation.sh

RIFT_EVAL_RUN_ID=2026-05-01-unsafezone-streams \
RIFT_EVAL_SCALE=headline \
RIFT_EVAL_SUITES="preflight streams" \
bash scripts/run-performance-evaluation.sh
```

For DEBS bounded 1M:

```sh
cd /Users/siyaoliu/rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
RIFT_EVAL_RUN_ID=2026-05-01-unsafezone-debs-1m \
RIFT_EVAL_SCALE=headline \
RIFT_EVAL_SUITES="preflight debs" \
bash scripts/run-performance-evaluation.sh
```

Decision rules:

- If `unsafezone-hp` beats improved SafeZone and Rift HPZone on region-friendly
  workloads, inspect SafeZone internals as a possible Rift backend substrate.
- If `unsafezone-hp` wins but crashes or mismatches on mixed-reference rows,
  preserve that as evidence for static safety/capture checking.
- If Rift HPZone beats `unsafezone-hp`, keep Rift's runtime backend as the
  stronger base.
- If improved SafeZone beats both, prioritize a SafeZone-compatible checked
  design over more standalone allocator tuning.
