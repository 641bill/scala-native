# SPECjbb2005 Workload Port Matrix

Last updated: 2026-05-08 22:16 CEST

Status: clean initial Scala Native workload-port rows.

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

## Scale Results

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
