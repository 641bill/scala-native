# SPECjbb2005 Workload Port Matrix

Last updated: 2026-05-13 17:45 CEST

Status: clean initial Scala Native workload-port rows plus an L1 final-clean
8-warehouse representative row from child commit `678a6eb41`, plus a
fresh all-optimizations 4-warehouse gate after the handle-backed allocation
promotion.

This is **not** an official SPECjbb2005 result. It is a deterministic
single-process Scala Native workload port that preserves the memory-management
shape used in the Stancu/SPECjbb2005 comparison: durable warehouse/customer/
stock/accounting state stays on heap/primitive arrays, while ordinary
transaction request, line-item, stock-probe, and receipt objects are allocated
on the heap or in transaction/batch epochs.

Raw logs:

- `/tmp/specjbb2005-port-smoke/`
- `/tmp/specjbb2005-port-4w-2026-05-08/`
- `/tmp/specjbb2005-port-5w-2026-05-08/`
- `/tmp/specjbb2005-port-6w-2026-05-08/`
- `/tmp/specjbb2005-port-7w-2026-05-08/`
- `/tmp/specjbb2005-port-8w-2026-05-08/`
- `/tmp/rift-l1-specjbb-8w-x20-678a6eb41-r1/`
- `/tmp/rift-l1-specjbb-8w-x20-678a6eb41-r2/`
- `/tmp/rift-l1-specjbb-8w-x20-678a6eb41-r3/`
- `/Users/siyaoliu/rift/cache/specjbb-allopts-20260513/`

## Workload

Configuration for the scale rows:

- warehouses: 4 through 8;
- iterations: 100,000 transactions per warehouse;
- items per order or stock-level probe: 8;
- transaction epoch: 64 transactions per region;
- runs: 3 timed runs after 1 warmup;
- checksum: matched across all modes at each warehouse count.

The transaction mix is deterministic and includes new-order, payment,
order-status, delivery, and stock-level-style cases. The port records Stancu-like
axes: elapsed, GC time/count, RSS, transaction-local object proxy, region-freed
object/byte proxy, max live region payload proxy, and API-boundary count.

## L1 Final-Clean 8-Warehouse Row

Configuration:

- child commit: `678a6eb41`;
- measurement level: L1 final-clean, external `/usr/bin/time -l`;
- warehouses: 8;
- iterations: 100,000 transactions per warehouse;
- total transactions: 800,000 per inner iteration;
- process runs: 3 external processes, each with 20 identical workload
  iterations inside the optimized native binary;
- transaction epoch: 64 transactions per region;
- checksum: `-9186304385429183494` across all modes.

| Mode | Median real s | Min real s | Max real s | Median RSS bytes | User s | Sys s | Claim |
|---|---:|---:|---:|---:|---:|---:|---|
| `gc-heap` | 2.64 | 2.63 | 2.66 | 12,369,920 | 2.64 | 0.00 | L1 natural heap baseline. |
| `region-scoped-rooted` | 2.48 | 2.46 | 2.48 | 7,962,624 | 2.47 | 0.00 | L1 rooted scoped-region baseline. |
| `checked-epoch-scoped` | 2.21 | 2.21 | 2.22 | 7,995,392 | 2.20 | 0.00-0.01 | L1 checked transaction/epoch win over heap and rooted baseline. |

Interpretation: this L1 row preserves the L2 direction without internal
timers. `checked-epoch-scoped` is about 16.3% faster than `gc-heap` and about
10.9% faster than `region-scoped-rooted`, while RSS is about 35% lower than
heap and near the rooted scoped baseline.

## All-Optimizations 4-Warehouse Gate

Date/time: 2026-05-13 17:55 CEST.

This row reruns the representative transaction workload after the current
handle-backed allocation promotion. It uses L1 final-clean mode, so the table
contains external process timing/RSS and correctness metadata only. L2 rows
above remain the GC/region interpretation source.

Source:
`/Users/siyaoliu/rift/cache/specjbb-allopts-20260513`.

Configuration:

- warehouses: 4;
- iterations: 100,000 transactions per warehouse;
- total transactions: 400,000 per inner iteration;
- runs: 3;
- transaction epoch: 64 transactions per region;
- checksum: `-6492448434046782774` across all modes.

| Mode | Real s | User s | Sys s | RSS bytes | Region-freed object proxy | Checksum | Claim |
|---|---:|---:|---:|---:|---:|---:|---|
| `gc-heap` | 0.51 | 0.24 | 0.00 | 7,929,856 | 2,080,320 | -6492448434046782774 | Natural heap baseline. |
| `region-scoped-rooted` | 0.21 | 0.21 | 0.00 | 6,307,840 | 2,080,320 | -6492448434046782774 | Rooted scoped baseline. |
| `checked-epoch-stream` | 0.18 | 0.18 | 0.00 | 5,980,160 | 2,080,320 | -6492448434046782774 | Checked epoch win over heap and rooted baseline. |
| `checked-epoch-scoped` | 0.19 | 0.19 | 0.00 | 6,307,840 | 2,080,320 | -6492448434046782774 | Checked scoped epoch near checked stream. |

Interpretation: this shorter all-optimizations gate preserves the same
transaction-local lifetime story as the 8-warehouse row. It is not a new
official SPEC claim; it is a quick L1 check that the current optimized default
still wins on the Stancu/SPECjbb-shaped transaction topology.

## Scale Results

These rows are L2 standard-stats rows from the initial port. Use them for
GC-count/time interpretation, not as final-clean headline elapsed timing.

| Warehouses | Mode | Median ms | GC ms | GC count | RSS bytes | Region-freed object proxy | Candidate object bp |
|---:|---|---:|---:|---:|---:|---:|---:|
| 4 | `gc-heap` | 64.717 | 7.389 | 25 | 7,979,008 | 2,080,320 | 9825 |
| 4 | `region-scoped-rooted` | 60.874 | 0.000 | 0 | 8,060,928 | 2,080,320 | 9825 |
| 4 | `region-stream-rootless` | 61.354 | 0.000 | 0 | 7,995,392 | 2,080,320 | 9825 |
| 4 | `checked-epoch-stream` | 57.779 | 0.000 | 0 | 7,995,392 | 2,080,320 | 9825 |
| 4 | `checked-epoch-scoped` | 53.953 | 0.000 | 0 | 8,060,928 | 2,080,320 | 9825 |
| 5 | `gc-heap` | 80.617 | 9.245 | 31 | 7,979,008 | 2,600,944 | 9829 |
| 5 | `region-scoped-rooted` | 75.825 | 0.000 | 0 | 8,060,928 | 2,600,944 | 9829 |
| 5 | `region-stream-rootless` | 79.361 | 0.000 | 0 | 7,995,392 | 2,600,944 | 9829 |
| 5 | `checked-epoch-stream` | 71.588 | 0.000 | 0 | 7,995,392 | 2,600,944 | 9829 |
| 5 | `checked-epoch-scoped` | 67.630 | 0.000 | 0 | 8,060,928 | 2,600,944 | 9829 |
| 6 | `gc-heap` | 96.729 | 10.880 | 38 | 7,979,008 | 3,123,056 | 9832 |
| 6 | `region-scoped-rooted` | 91.316 | 0.000 | 0 | 8,060,928 | 3,123,056 | 9832 |
| 6 | `region-stream-rootless` | 91.671 | 0.000 | 0 | 7,995,392 | 3,123,056 | 9832 |
| 6 | `checked-epoch-stream` | 86.330 | 0.000 | 0 | 7,995,392 | 3,123,056 | 9832 |
| 6 | `checked-epoch-scoped` | 80.594 | 0.000 | 0 | 8,060,928 | 3,123,056 | 9832 |
| 7 | `gc-heap` | 113.241 | 13.242 | 45 | 7,962,624 | 3,643,016 | 9833 |
| 7 | `region-scoped-rooted` | 106.329 | 0.000 | 0 | 8,060,928 | 3,643,016 | 9833 |
| 7 | `region-stream-rootless` | 107.148 | 0.000 | 0 | 7,995,392 | 3,643,016 | 9833 |
| 7 | `checked-epoch-stream` | 100.705 | 0.000 | 0 | 7,995,392 | 3,643,016 | 9833 |
| 7 | `checked-epoch-scoped` | 95.169 | 0.000 | 0 | 8,060,928 | 3,643,016 | 9833 |
| 8 | `gc-heap` | 129.674 | 15.125 | 52 | 7,979,008 | 4,163,936 | 9835 |
| 8 | `region-scoped-rooted` | 122.022 | 0.000 | 0 | 8,060,928 | 4,163,936 | 9835 |
| 8 | `region-stream-rootless` | 123.475 | 0.000 | 0 | 7,995,392 | 4,163,936 | 9835 |
| 8 | `checked-epoch-stream` | 114.651 | 0.000 | 0 | 7,995,392 | 4,163,936 | 9835 |
| 8 | `checked-epoch-scoped` | 108.649 | 0.000 | 0 | 8,077,312 | 4,163,936 | 9835 |

## Interpretation

This first port supports the Stancu comparison direction under the canonical
Rift taxonomy:

- `checked-epoch-scoped` is fastest at every warehouse count, beating
  `gc-heap` by about 16%-19% and `region-scoped-rooted` by about 11%-12%.
- `checked-epoch-stream` also beats `gc-heap` consistently, but trails scoped
  checked epoch on this workload.
- Heap timed GC grows with warehouses, from `7.389 ms` and 25 collections at
  4 warehouses to `15.125 ms` and 52 collections at 8 warehouses.
- Region rows remove timed GC from the transaction-local object path. RSS is
  similar at this scale because max live transaction payload is intentionally
  small, matching the Stancu-style coarse transaction-region setup.
- The candidate temporary-object fraction is very high by object count
  (`9825`-`9835` basis points), because durable warehouse/product/customer
  metadata is primitive heap state while transaction requests, lines, probes,
  and receipts are epoch-local.

Do not compare these wall-clock numbers directly to Stancu or official
SPECjbb2005. The valid claim is narrower: the Scala Native port reproduces the
transaction lifetime shape and reports the same axes for local Rift modes.
