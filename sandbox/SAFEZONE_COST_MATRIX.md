# SafeZone Cost Matrix

Status: implemented measurement scaffold; full headline sweep not yet run.

Date: 2026-05-01

## Purpose

`unsafezone-hp` shows that SafeZone-family allocator/pool mechanics are often
competitive with, and sometimes faster than, the current Rift HPZone backend
when GC root registration is removed. This matrix decomposes that result before
we optimize or build a checked backend on top of it.

The matrix is backend evidence only. `unsafezone-hp` is unsafe and
benchmark-only.

## Labels

| Label | `SAFEZONE_ROOTS_MODE` | `SAFEZONE_PAGE_SIZE` | Meaning |
|---|---:|---:|---|
| `current-default` | 0 | default | Current SafeZone per-page root add/remove. |
| `improved-default` | 1 | default | Improved/coalesced root removal baseline. |
| `chunk-default` | 2 | default | Chunk-root mode. |
| `unsafe-hp-32k` | 3 | 32768 | Rootless benchmark-only UnsafeZone-HP. |
| `improved-32k` | 1 | 32768 | Improved SafeZone with the UnsafeZone page size. |

## Command

```sh
cd /Users/siyaoliu/rift/scala-native-rift
SAFEZONE_COST_RUNS=3 \
SAFEZONE_COST_BENCHES="gcbench listoflists-linked listoflists-flat dataflow common-crawl-q1" \
zsh sandbox/run_safezone_cost_matrix.sh
```

For a smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
SAFEZONE_COST_RUNS=1 \
SAFEZONE_COST_BENCHES="gcbench common-crawl-q1" \
SAFEZONE_COST_CONFIGS="improved-default:1: unsafe-hp-32k:3:32768" \
SAFEZONE_COST_COMMON_CRAWL_PAGES=20000 \
zsh sandbox/run_safezone_cost_matrix.sh
```

## Recorded Columns

The TSV summary records:

- benchmark result fields: `median_ms`, `avg_ms`, `min_ms`, `max_ms`,
  optional GC fields, checksum/output count, RSS.
- SafeZone pool counters from `SAFEZONE_TRACE=1`: claim/reclaim calls,
  reclaimed pages, root add/remove calls, chunk/page allocations, root timing,
  reclaim sort/bookkeeping timing, chunk/page allocation timing.

## Interpretation Rules

- Root mode `3` wins are not safety wins.
- If `unsafe-hp-32k` wins mostly because `root_add_time_ms` and
  `root_remove_time_ms` disappear, the next safe design target is static
  proof that a checked region can avoid root registration.
- If `improved-32k` closes much of the gap, page size is a baseline/config
  issue, not a Rift-specific speedup.
- If `chunk-default` wins or ties, chunk roots may be a safer intermediate
  substrate than fully rootless regions.
- If reclaim sort/bookkeeping dominates, optimize SafeZone reclaim before
  touching checked operators.

## Smoke Result

The runner exists at `sandbox/run_safezone_cost_matrix.sh`. It builds selected
native benchmark mains, runs SafeZone-family configurations with
`SAFEZONE_TRACE=1`, and writes `summary.tsv` under
`SAFEZONE_COST_OUTPUT_DIR` or `/tmp/safezone-cost-matrix`.

A 2k-page Common Crawl q1 smoke validated the format:

```sh
SAFEZONE_COST_BUILD=0 \
SAFEZONE_COST_RUNS=1 \
SAFEZONE_COST_BENCHES="common-crawl-q1" \
SAFEZONE_COST_CONFIGS="improved-default:1: unsafe-hp-32k:3:32768" \
SAFEZONE_COST_COMMON_CRAWL_PAGES=2000 \
SAFEZONE_COST_OUTPUT_DIR=/tmp/safezone-cost-smoke \
zsh sandbox/run_safezone_cost_matrix.sh
```

| Config | Median ms | Claim calls | Root add/remove calls | Reclaimed pages | RSS bytes |
|---|---:|---:|---:|---:|---:|
| improved-default | 16.895 | 3224 | 3224 / 4 | 3224 | 32899072 |
| unsafe-hp-32k | 17.135 | 804 | 0 / 0 | 804 | 32718848 |

This is a smoke row only. It validates that the trace counters and TSV are
usable; it does not replace headline medians.
