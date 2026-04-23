# Phase 0 Baselines

Date: 2026-04-22

Branch: `feature/rift`  
Commit: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

This note records the currently trusted Phase 0 reruns performed inside the fork.

## Common environment

- `ENABLE_EXPERIMENTAL_COMPILER=1`
- `JAVA_HOME="$(cs java-home --jvm temurin:17)"`
- `PATH="$JAVA_HOME/bin:$PATH"`
- Scala Native sandbox project: `sandbox3_next`

## Checked-in commands

GCBench:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
GCBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_gcbench_runtime_matrix.sh
```

BenchmarkListOfLists:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_listoflists_runtime_matrix.sh
```

GCBench topology rerun:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_gcbench_topology_matrix.sh
```

Cleaned pipeline rerun:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_pipeline_runtime_matrix.sh
```

## GCBench

Config:

- stretch depth `18`
- long-lived depth `16`
- array size `500000`
- min depth `4`
- max depth `16`
- depth step `2`
- batch size `1`
- runs `5`

| Mode | Key env | Median ms |
|---|---|---:|
| Immix heap | `-` | 203.514 |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0` | 684.517 |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1` | 223.770 |
| Rift HPZone | `-` | 161.641 |

## BenchmarkListOfLists

Workload:

- `n=3000`
- `structures=40`
- each structure is `n` outer nodes, each outer node holds a linked list of `n` inner nodes
- each inner node points at a separate `BasicObject`
- runs `5`

The current fork-local harness was ported from the earlier benchmark branch `codex/broom-bench-region-friendly` to preserve the original workload shape while using the current in-tree SafeZone and Rift paths.

| Mode | Key env | Median ms |
|---|---|---:|
| Immix heap | `-` | 15085.511 |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0` | 138171.998 |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1` | 10132.854 |
| Rift HPZone | `-` | 6951.331 |

## GCBench topology rerun

Config:

- stretch depth `18`
- long-lived depth `16`
- array size `500000`
- min depth `4`
- max depth `16`
- depth step `2`
- runs `5`
- `SAFEZONE_PAGE_SIZE=4096`
- `SAFEZONE_BATCH_SIZE=1`

Topology definitions:

- topology A: long-lived tree on the GC heap, short-lived trees in inner SafeZones
- topology B: long-lived tree in an outer SafeZone, short-lived trees in inner SafeZones

| Mode | Key env | Median ms |
|---|---|---:|
| heap baseline | `SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 213.082 |
| current SafeZone topology A | `SAFEZONE_ROOTS_MODE=0 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 236.542 |
| current SafeZone topology B | `SAFEZONE_ROOTS_MODE=0 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 635.247 |
| improved SafeZone topology A | `SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 211.319 |
| improved SafeZone topology B | `SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=4096 SAFEZONE_BATCH_SIZE=1` | 436.749 |

## Cleaned pipeline rerun

Config:

- `PIPELINE_SIZE=2000000`
- `PIPELINE_WORKERS=4`
- `PIPELINE_WARMUPS=1`
- runs `5`

Provenance:

- No tracked pipeline benchmark survived in the fork history.
- This rerun uses a fork-local cleaned surrogate derived from the surviving local `TestSafeZoneParCollections.scala` scaffold from the other worktree.
- The cleaned version keeps a single-pass allocation-and-consume shape with per-worker regions and avoids the earlier checksum-capture issue by keeping per-worker primitive sums local and publishing one result per worker.

| Mode | Key env | Median ms |
|---|---|---:|
| heap baseline | `PIPELINE_SIZE=2000000 PIPELINE_WORKERS=4` | 4.924 |
| current SafeZone pipeline | `SAFEZONE_ROOTS_MODE=0 PIPELINE_SIZE=2000000 PIPELINE_WORKERS=4` | 5.344 |
| improved SafeZone pipeline | `SAFEZONE_ROOTS_MODE=1 PIPELINE_SIZE=2000000 PIPELINE_WORKERS=4` | 5.055 |
| Rift HPZone pipeline | `PIPELINE_SIZE=2000000 PIPELINE_WORKERS=4` | 5.089 |

## Notes

- The improved SafeZone baseline materially changes the comparison set on both workloads.
- On `BenchmarkListOfLists`, `SAFEZONE_ROOTS_MODE=1` cuts median wall time from `138.172 s` to `10.133 s`.
- The topology rerun in the current fork confirms that topology still matters: at the same `4096`/batch-1 setting, topology A is near heap parity while topology B remains much slower.
- On the cleaned pipeline surrogate, all four modes are near-parity; this benchmark does not currently show a large allocator separation once the checksum-capture artifact is removed.
- The `BenchmarkListOfLists` harness avoids the earlier closure-capture boxing pitfall by computing a per-structure checksum inside each region block and returning the scalar checksum to the outer loop.
- The first attempt at GCBench `SAFEZONE_ROOTS_MODE=1` crashed until `GC_Roots_RemoveByRange` was fixed to handle fully covered root ranges correctly in `gc/immix_commix/GCRoots.c`.
