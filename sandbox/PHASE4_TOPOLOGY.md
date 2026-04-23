# Phase 4 Topology Sweep

Date: 2026-04-23

Branch: `feature/rift`  
Head commit: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

Worktree note:

- These results use local, uncommitted Rift runtime changes in
  `nativelib/src/main/resources/scala-native/rift/RiftRuntime.c`.
- The topology harness is
  `sandbox/src/main/scala-next/ListOfListsTopologyMatrix.scala`.
- The runner is `sandbox/run_listoflists_topology_matrix.sh`.

This note covers the Phase 4 topology deliverable from `ROADMAP.md`: compare
one full-graph region, nested row-local regions, and a mixed GC-plus-region
setup.

## Topologies

All modes preserve the same logical checksum:

- `n * n` logical inner values per structure
- `structures` independent structures per timed run
- expected checksum: `structures * n * n * (n - 1)`

Topologies:

- `one-region`: allocate the full linked list-of-lists graph in one region per
  structure, then traverse it.
- `nested`: keep an outer region containing one outer node per row, but allocate
  and close an inner row-local region for each inner list. The outer node stores
  the row checksum instead of an inner pointer, so closed inner regions cannot
  escape.
- `mixed rooted heap-values`: allocate value objects on the GC heap and keep them
  explicitly rooted in a heap array while region or SafeZone nodes point to them.

The explicit root table in the mixed mode is required for Rift. A first
unrooted version let Rift region nodes point at heap objects without any GC root.
At `n=1000`, `structures=10`, that produced:

```text
CHECKSUM_MISMATCH name=rift-mixed actual=13763975568 expected=9990000000
```

This is an important design constraint: Rift regions are not scanned by the GC,
so region objects must not be the only references keeping GC-heap objects alive.
Safe mixed setups need either explicit heap roots, GC-scanned region metadata, or
the opposite reference direction where heap objects reference region objects that
are known to remain open.

## Commands

Reduced smoke test:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_N=80 LISTBENCH_STRUCTURES=1 LISTBENCH_BENCHMARK_RUNS=1 \
  zsh sandbox/run_listoflists_topology_matrix.sh
```

Medium directional sweep:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_N=1000 LISTBENCH_STRUCTURES=10 LISTBENCH_BENCHMARK_RUNS=3 \
  zsh sandbox/run_listoflists_topology_matrix.sh
```

The medium sweep initially failed on unrooted `rift-mixed`; the three mixed
rows below were rerun after adding the explicit heap root table.

Report-scale subset:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_BENCHMARK_RUNS=3 \
  zsh sandbox/run_listoflists_topology_report_subset.sh
```

The report-scale subset skips only current SafeZone one-region by default,
because that already measured as `136016.922 ms` in the full linked baseline
and costs several minutes to rerun. Set
`LISTBENCH_INCLUDE_CURRENT_SAFEZONE_ONE=1` to include it.

## Medium Results

Configuration:

- `LISTBENCH_N=1000`
- `LISTBENCH_STRUCTURES=10`
- `LISTBENCH_BENCHMARK_RUNS=3`

| Mode | Topology | Median ms | Samples ms |
|---|---|---:|---|
| Immix heap | full heap graph | 439.726 | `435.604, 439.726, 587.636` |
| current SafeZone | one-region | 680.573 | `677.231, 680.573, 682.756` |
| current SafeZone | nested | 274.131 | `273.128, 274.131, 277.934` |
| current SafeZone | mixed rooted heap-values | 430.501 | `422.986, 430.501, 459.668` |
| improved SafeZone | one-region | 267.049 | `266.148, 267.049, 274.443` |
| improved SafeZone | nested | 273.335 | `273.195, 273.335, 274.984` |
| improved SafeZone | mixed rooted heap-values | 274.278 | `273.265, 274.278, 298.503` |
| Rift HPZone | one-region | 199.429 | `198.963, 199.429, 208.259` |
| Rift HPZone | nested | 206.730 | `206.014, 206.730, 211.459` |
| Rift HPZone | mixed rooted heap-values | 337.418 | `306.932, 337.418, 407.252` |

## Interpretation

- On this medium shape, Rift HPZone is the fastest one-region path:
  `199.429 ms`, or `1.339x` faster than improved SafeZone one-region and
  `2.205x` faster than heap.
- Nested row-local regions fix most of current SafeZone's root-bookkeeping cost:
  current SafeZone improves from `680.573 ms` to `274.131 ms`.
- Improved SafeZone one-region and nested are effectively tied at this scale:
  `267.049 ms` vs `273.335 ms`. That supports the revised design claim that the
  roots-mode fix changes the baseline substantially.
- Rift nested is close to Rift one-region (`206.730 ms` vs `199.429 ms`), so
  row-local open/close overhead is not the limiting factor at this scale.
- The rooted mixed topology is not a Rift win: Rift is `337.418 ms`, while
  improved SafeZone is `274.278 ms`. The heap root table and GC value allocation
  dominate enough that region-allocating the nodes is not sufficient.
- The failed unrooted mixed attempt is evidence for the safety story, not just a
  benchmark artifact. It shows why the capture/safe API must prevent unrooted
  region-to-GC references or make them visible to the GC.

## Report-Scale Results

Configuration:

- `LISTBENCH_N=3000`
- `LISTBENCH_STRUCTURES=40`
- `LISTBENCH_BENCHMARK_RUNS=3`

| Mode | Topology | Median ms | Samples ms |
|---|---|---:|---|
| Immix heap | full heap graph | 14755.580 | `14704.328, 14755.580, 16224.723` |
| current SafeZone | one-region | 136016.922 | from linked 5-run baseline; skipped here |
| current SafeZone | nested | 10289.097 | `10275.936, 10289.097, 10305.335` |
| current SafeZone | mixed rooted heap-values | 55554.702 | `55108.631, 55554.702, 55558.918` |
| improved SafeZone | one-region | 9767.048 | `9743.167, 9767.048, 9787.080` |
| improved SafeZone | nested | 9929.699 | `9912.121, 9929.699, 9933.331` |
| improved SafeZone | mixed rooted heap-values | 9877.914 | `9870.536, 9877.914, 10611.134` |
| Rift HPZone | one-region | 7256.426 | `7251.278, 7256.426, 7300.764` |
| Rift HPZone | nested | 7314.201 | `7295.217, 7314.201, 7372.917` |
| Rift HPZone | mixed rooted heap-values | 11695.748 | `10716.691, 11695.748, 11829.713` |

Report-scale interpretation:

- Rift HPZone remains the fastest one-region path in this topology harness:
  `7256.426 ms`, or `1.346x` faster than improved SafeZone one-region and
  `2.033x` faster than heap.
- Rift nested is effectively tied with Rift one-region (`7314.201 ms` vs
  `7256.426 ms`). Nested row-local regions do not create a new Rift win; they
  mainly prove open/close overhead is not disastrous.
- Current SafeZone nested is `13.219x` faster than the current SafeZone
  one-region baseline (`10289.097 ms` vs `136016.922 ms`). This confirms that
  topology can hide the old root-bookkeeping pathology even without roots mode.
- Improved SafeZone one-region, nested, and mixed are all in the same band:
  `9767.048-9929.699 ms`. Once roots mode is enabled, topology matters much less
  for this linked workload.
- Rooted mixed is not a good Rift topology here. Rift mixed is `11695.748 ms`,
  slower than improved SafeZone mixed (`9877.914 ms`) and slower than Rift
  one-region/nested. The explicit heap root table prevents corruption but
  changes the cost profile enough to erase the region-node win.

## Phase 4 Status

Completed:

- Linked runtime-only baseline from Phase 3.
- Chunked and flat layout variants in `PHASE4_LAYOUT.md`.
- Medium topology sweep covering one-region, nested, and mixed GC-plus-region
  setups.
- Report-scale topology subset covering heap, current SafeZone nested/mixed,
  improved SafeZone one/nested/mixed, and Rift one/nested/mixed.
- Phase 4 exit summary in `PHASE4_EXIT.md`.

Phase 4 can now move to Phase 5.
