# LogHub Retained Session Matrix

Last updated: 2026-05-16 18:20 CEST

Status: real streaming-input retained session/join triage. This matrix was
added after the active-window LogHub q3 row showed heap-cap pressure but not a
clean throughput/RSS win. The goal is to test whether a more naturally retained
session/join shape over real LogHub HDFS lines creates material heap GC.

This is not an exact LogHub paper benchmark. It is a local single-process
streaming replay over the public HDFS log.

## Benchmark Shape

Source:

`/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log`

Queries:

- `session`: stream HDFS log lines, derive a severity/template/session key,
  allocate ordinary session events, retain them in per-epoch dictionaries, and
  close/drop timestamp-local state after the active-epoch boundary.
- `join`: stream the same lines, route records into left/right retained
  per-key tables by record parity, emit join matches, and close/drop
  timestamp-local state after the active-epoch boundary.

Headline modes:

| Mode | Meaning |
|---|---|
| `heap-gc` | Natural heap/GC retained object implementation. |
| `checked-rift` | Checked Rift direct streaming/epoch region implementation. |
| `checked-region-scoped` | Checked Rift API over the SafeZone-backed scoped backend. |

## Commands

20k smoke:

```sh
LOGHUB_SESSION_BUILD=1 \
LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_SESSION_RECORDS=20000 \
LOGHUB_SESSION_RECORDS_PER_EPOCH=2500 \
LOGHUB_SESSION_ACTIVE_EPOCHS=4 \
LOGHUB_SESSION_KEY_SPACE=4096 \
LOGHUB_SESSION_BENCHMARK_RUNS=1 \
LOGHUB_SESSION_WARMUPS=0 \
LOGHUB_SESSION_WORKLOADS="session join" \
LOGHUB_SESSION_MODES="heap-gc checked-rift checked-region-scoped" \
LOGHUB_SESSION_OUTPUT_DIR=/private/tmp/loghub-retained-session-smoke-20260516 \
zsh sandbox/run_loghub_retained_session_matrix.sh
```

1M active-16 triage:

```sh
LOGHUB_SESSION_BUILD=0 \
LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_SESSION_RECORDS=1000000 \
LOGHUB_SESSION_RECORDS_PER_EPOCH=25000 \
LOGHUB_SESSION_ACTIVE_EPOCHS=16 \
LOGHUB_SESSION_KEY_SPACE=65536 \
LOGHUB_SESSION_BENCHMARK_RUNS=3 \
LOGHUB_SESSION_WARMUPS=1 \
LOGHUB_SESSION_WORKLOADS="session join" \
LOGHUB_SESSION_MODES="heap-gc checked-rift checked-region-scoped" \
LOGHUB_SESSION_OUTPUT_DIR=/private/tmp/loghub-retained-session-hdfs-1m-active16-l2-20260516 \
zsh sandbox/run_loghub_retained_session_matrix.sh
```

## 20k Smoke

The 20k smoke matched checksum/output across all modes.

| Workload | Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---|---:|---:|---:|---:|---:|
| session | `heap-gc` | `155.874` | `0.527` | `8470528` | `642625343242307127` | `15043` |
| session | `checked-rift` | `134.191` | `0.000` | `8388608` | `642625343242307127` | `15043` |
| session | `checked-region-scoped` | `131.387` | `0.000` | `8552448` | `642625343242307127` | `15043` |
| join | `heap-gc` | `141.055` | `0.462` | `8421376` | `-3039645399054221914` | `0` |
| join | `checked-rift` | `136.381` | `0.000` | `8273920` | `-3039645399054221914` | `0` |
| join | `checked-region-scoped` | `132.541` | `0.000` | `8421376` | `-3039645399054221914` | `0` |

## 1M Active-16 Triage

Raw summary:

`/private/tmp/loghub-retained-session-hdfs-1m-active16-l2-20260516/summary.tsv`

This run was executed inside the sandbox, so `/usr/bin/time -l` could not read
maximum RSS for the 1M rows. Use the L2 timing/GC fields for triage only; rerun
outside the sandbox if a row becomes presentation-worthy.

| Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Checksum | Output | Retained object proxy | Max live proxy |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| session | `heap-gc` | `6595.172` | `82.341` | `90.781` | `3/3` | `0.000` | `0` | `-4251312673673367471` | `831923` | `1831923` | `732847` |
| session | `checked-rift` | `6578.258` | `5.572` | `5.858` | `3/3` | `0.885` | `1831932` | `-4251312673673367471` | `831923` | `1831923` | `732850` |
| session | `checked-region-scoped` | `6997.852` | `63.979` | `67.797` | `3/3` | `0.000` | `0` | `-4251312673673367471` | `831923` | `1831923` | `732850` |
| join | `heap-gc` | `6837.810` | `9.474` | `13.881` | `3/3` | `0.000` | `0` | `4282190220497908364` | `0` | `1000000` | `400000` |
| join | `checked-rift` | `6927.376` | `6.731` | `7.516` | `3/3` | `1.624` | `1000006` | `4282190220497908364` | `0` | `1000000` | `400002` |
| join | `checked-region-scoped` | `6681.557` | `77.349` | `77.610` | `3/3` | `0.000` | `0` | `4282190220497908364` | `0` | `1000000` | `400002` |

## Decision

Park this as real-streaming-input retained-object control evidence.

- It is a true streaming-file replay and it retains ordinary objects until
  epoch/session boundaries.
- It does not become the GC-heavy real-input flagship: session heap GC is only
  about `1.2%` of median L2 elapsed, and join heap GC is about `0.1%`.
- The session checked Rift row is effectively tied with heap and removes most
  timed GC, but the parser/template/hash/query work dominates. The join row is
  mixed: checked scoped is fastest, but heap GC is too small for a strong
  memory-management claim.
- The next stronger row remains the Broom/Naiad-style retained dataflow ladder,
  which spends a much larger share of elapsed time in heap GC at 5M/20M
  active-16.
