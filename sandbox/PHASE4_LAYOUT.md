# Phase 4 Layout Study

Date: 2026-04-22
Updated: 2026-04-23

Branch: `feature/rift`  
Head commit: `ddbba577aecd4c0adc741cbf7085ee93548c46b4`

Worktree note:

- The final tables below were rerun after a local, uncommitted runtime patch in
  [RiftRuntime.c](/Users/siyaoliu/rift/scala-native-rift/nativelib/src/main/resources/scala-native/rift/RiftRuntime.c:141)
  and
  [RiftRuntime.c](/Users/siyaoliu/rift/scala-native-rift/nativelib/src/main/resources/scala-native/rift/RiftRuntime.c:384).
- That patch does two things:
  - fresh mmapped slabs are marked zero-filled so Rift skips redundant
    `memset` on those allocations
  - huge slabs stop using the synchronous `MAP_POPULATE` plus
    `MADV_HUGEPAGE` path that caused the flat-layout cliff
- A later local patch also zeroes reused regular slabs once when acquired from
  the TLS/global pool, and zeroes the retained first slab on `reset`. The
  targeted follow-up results for that patch are listed separately below because
  they are Rift-only 3-run checks, not full 5-run matrix reruns.

This note records the first two Phase 4 layout experiments in the fork. They
keep the same logical `BenchmarkListOfLists` workload and comparison set as the
Phase 0 runtime-only baseline, but change the inner representation from
per-element `BasicObject` plus `InnerNode` pairs to more region-friendly
shapes.

## Compared layouts

Linked baseline from `PHASE0_BASELINES.md`:

- `n=3000`
- `structures=40`
- runs `5`
- outer list is linked
- each outer node owns an inner linked list of length `n`
- each inner element is stored as a separate `BasicObject` plus `InnerNode`
- topology is unchanged from the runtime-only harness

Chunked variant in this note:

- `n=3000`
- `structures=40`
- runs `5`
- `LISTBENCH_CHUNK_SIZE=32`
- outer list is still linked
- each outer node owns a linked list of chunks
- each chunk stores up to `32` `Int` values in one `Array[Int]`
- topology is unchanged from the runtime-only harness

The checksum and total logical element count are unchanged. This is a layout
change, not a topology change.

Flat variant in this note:

- `n=3000`
- `structures=40`
- runs `5`
- one flat `Array[Int]` of length `n * n` per logical structure
- row-major fill/traversal preserves the same logical values and checksum
- topology is unchanged from the runtime-only harness

## Checked-in command

Chunked:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_CHUNK_SIZE=32 LISTBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_listoflists_chunked_matrix.sh
```

Flat:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
LISTBENCH_BENCHMARK_RUNS=5 zsh sandbox/run_listoflists_flat_matrix.sh
```

## Results

| Mode | Linked median ms | Chunked median ms | Flat median ms |
|---|---:|---:|---:|
| Immix heap | 15260.875 | 2636.480 | 1766.295 |
| current SafeZone | 136016.922 | 4835.904 | 1735.453 |
| improved SafeZone | 9824.936 | 2369.108 | 1743.161 |
| Rift HPZone | 7278.150 | 2463.674 | 1515.091 |

Speedup or regression versus the linked baseline:

| Mode | Linked / chunked | Linked / flat |
|---|---:|---:|
| Immix heap | 5.789x | 8.640x |
| current SafeZone | 28.129x | 78.375x |
| improved SafeZone | 4.147x | 5.636x |
| Rift HPZone | 2.954x | 4.804x |

Chunked-layout samples from the checked-in run:

| Mode | Key env | Samples ms | Median ms |
|---|---|---|---:|
| Immix heap | `LISTBENCH_CHUNK_SIZE=32` | `2599.345, 2631.367, 2636.480, 2671.267, 2729.787` | 2636.480 |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0 LISTBENCH_CHUNK_SIZE=32` | `4797.329, 4828.114, 4835.904, 4844.056, 4850.852` | 4835.904 |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1 LISTBENCH_CHUNK_SIZE=32` | `2356.089, 2364.000, 2369.108, 2377.596, 2390.583` | 2369.108 |
| Rift HPZone | `LISTBENCH_CHUNK_SIZE=32` | `2447.758, 2453.996, 2463.674, 2480.406, 2634.740` | 2463.674 |

Flat-layout samples from the checked-in run:

| Mode | Key env | Samples ms | Median ms |
|---|---|---|---:|
| Immix heap | `-` | `1673.328, 1681.196, 1766.295, 1942.462, 2234.381` | 1766.295 |
| current SafeZone | `SAFEZONE_ROOTS_MODE=0` | `1720.359, 1721.386, 1735.453, 1748.697, 2038.046` | 1735.453 |
| improved SafeZone | `SAFEZONE_ROOTS_MODE=1` | `1736.687, 1737.146, 1743.161, 1752.375, 1772.330` | 1743.161 |
| Rift HPZone | `-` | `1508.332, 1511.957, 1515.091, 1528.668, 1549.264` | 1515.091 |

## Initial Diagnosis Before The Runtime Fix

The first local flat-layout run exposed a real Rift runtime problem rather than
a flat-layout limit:

- pre-fix flat Rift median: `15468.496 ms`
- post-fix flat Rift median: `1515.091 ms`
- improvement from the local runtime patch: `10.210x`

The same patch barely changes the chunked result:

- pre-fix chunked Rift median: `2489.530 ms`
- post-fix chunked Rift median: `2463.674 ms`
- improvement from the local runtime patch: `1.010x`

## Targeted Front-Path Follow-Up

After the huge-allocation fix, the remaining open issue was the regular-slab
front path. Reused slabs were being returned to the pool as non-zeroed, causing
later managed-object allocations to pay `memset` per object. The follow-up patch
zeros a reused regular slab once when it is acquired, then treats the remaining
bump range as zero-filled.

Targeted Rift-only reruns:

| Layout | Previous Rift median ms | Follow-up Rift median ms | Change |
|---|---:|---:|---:|
| Linked | 7278.150 | 6638.949 | 1.096x faster |
| Chunked | 2463.674 | 2390.390 | 1.031x faster |

Follow-up samples:

| Layout | Runs | Samples ms | Median ms |
|---|---:|---|---:|
| Linked | 3 | `6594.762, 6638.949, 6748.837` | 6638.949 |
| Chunked | 3 | `2388.869, 2390.390, 2444.058` | 2390.390 |

The same patch required a reset-safety correction: when `reset` keeps the first
slab and rewinds its bump pointer, old object fields must not be exposed as
fresh zero-initialized Scala object storage. The runtime now zeroes that retained
slab on reset. `RiftRegionTest` was extended to cover this case and passes
`5/5`.

## Interpretation

- Layout is a first-order effect on this workload. Even without changing the
  runtime path, the heap version improves by `5.789x` on chunked and `8.640x`
  on flat once the inner representation stops allocating two objects per
  element.
- The runtime story still matters on the linked baseline. In the patched
  worktree, the latest targeted Rift HPZone linked rerun is `2.299x` faster than
  heap and `1.480x` faster than improved SafeZone on the original linked shape.
- The chunked layout remains mixed. Rift HPZone is still much faster than
  current SafeZone, and still slightly faster than heap (`1.070x`), but it does
  not beat the improved SafeZone baseline on this shape. Improved SafeZone is
  about `3.8%` faster than the 5-run Rift HPZone result (`2369.108 ms` vs
  `2463.674 ms`). The targeted regular-slab follow-up narrows that gap to about
  `0.9%` (`2369.108 ms` vs `2390.390 ms`), but does not yet reverse it.
- The flat contiguous shape now tells a different story from the first local
  run. After fixing Rift's huge-allocation path, all three non-Rift modes land
  in a tight `1.735-1.766 s` band and Rift HPZone is best at `1515.091 ms`.
- On this flat variant, the earlier "SafeZone is fine on flat/contiguous
  workloads" hypothesis is supported as a workload-specific claim: both current
  and improved SafeZone are near heap parity after rerun. That does not make it
  a universal statement about SafeZone.
- The earlier flat Rift cliff was a runtime bug. The patched worktree changes
  the huge-slab path at
  [RiftRuntime.c](/Users/siyaoliu/rift/scala-native-rift/nativelib/src/main/resources/scala-native/rift/RiftRuntime.c:141)
  and the object zeroing path at
  [RiftRuntime.c](/Users/siyaoliu/rift/scala-native-rift/nativelib/src/main/resources/scala-native/rift/RiftRuntime.c:384),
  which is consistent with the `10.210x` improvement on the flat layout and the
  near-zero change on chunked. This remains an engineering diagnosis from the
  source and measurements, not a formal proof.

## What this phase learned

- The large linked-layout win previously attributed to regions was not only a
  runtime story. Layout alone removes a large fraction of the total cost.
- The runtime story still matters even after layout work. On the linked shape,
  Rift remains clearly ahead of both SafeZone baselines and heap.
- The first chunked layout is still not a win for Rift against the improved
  SafeZone baseline.
- The flat contiguous regression was not a property of flat layouts. It was a
  bug in Rift's large-allocation path, and fixing that path turns the flat
  layout into a Rift win rather than a Rift failure.
- The next concrete step is no longer "fix huge arrays." That part is fixed.
  The immediate front-path zeroing issue is also partially addressed. The
  topology sweep required by the roadmap is recorded in `PHASE4_TOPOLOGY.md`;
  the remaining Phase 4 work is one exit summary that ties runtime, topology,
  and layout results together.
