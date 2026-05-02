# SafeZone Cost Matrix

Status: headline diagnostic sweep completed.

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

## Headline Diagnostic Run

Run id: `2026-05-01-safezone-cost`

Implementation commit:
`cec5c0e31a25fce91946f6d37e9bf59d789fb3c8`

Parent commit:
`b80cde3ea1d7d63b8b27f159fbb157c9895c89ee`

Command:

```sh
cd /Users/siyaoliu/rift
RIFT_EVAL_RUN_ID=2026-05-01-safezone-cost \
RIFT_EVAL_SCALE=headline \
RIFT_EVAL_SUITES="preflight safezone-cost" \
bash scripts/run-performance-evaluation.sh
```

Raw logs:
`/Users/siyaoliu/rift/cache/perf-eval/2026-05-01-safezone-cost/`

Summary TSV:
`/Users/siyaoliu/rift/cache/perf-eval/2026-05-01-safezone-cost/summaries/safezone-cost/summary.tsv`

Environment:

- Darwin 25.4.0, Apple M4 Pro, 24 GiB memory.
- Java Temurin 17.0.18.
- `SAFEZONE_TRACE=1` enabled for cost counters.

Important caveat: these are trace-instrumented diagnostic rows. Use them to
attribute SafeZone-family costs and choose follow-up experiments. Do not use
their elapsed times as normal non-instrumented benchmark headline rows.

### Runtime/Topology Rows

| Benchmark | Config | Median ms | Claim calls | Root add/remove calls | Root add/remove ms | Reclaim ms | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| GCBench | current-default | 1111.263 | 215240 | 215240 / 215240 | 5.810 / 2168.263 | 2180.901 | 125796352 |
| GCBench | improved-default | 671.204 | 215240 | 215240 / 70 | 5.841 / 2.841 | 9.608 | 125763584 |
| GCBench | chunk-default | 691.392 | 215240 | 19 / 0 | 0.000 / 0.000 | 8.165 | 129679360 |
| GCBench | unsafe-hp-32k | 665.224 | 53790 | 0 / 0 | 0.000 / 0.000 | 2.063 | 125370368 |
| GCBench | improved-32k | 662.399 | 53790 | 53790 / 70 | 1.576 / 0.719 | 2.159 | 125435904 |
| ListOfLists linked | current-default | 158348.051 | 8803600 | 8803600 / 8803600 | 276.112 / 627735.229 | 628324.690 | 367296512 |
| ListOfLists linked | improved-default | 32686.980 | 8803600 | 8803600 / 600 | 268.215 / 116.420 | 461.067 | 367329280 |
| ListOfLists linked | chunk-default | 33449.201 | 8803600 | 92 / 0 | 0.013 / 0.000 | 327.390 | 365903872 |
| ListOfLists linked | unsafe-hp-32k | 32970.802 | 2198200 | 0 / 0 | 0.000 / 0.000 | 82.049 | 364412928 |
| ListOfLists linked | improved-32k | 32080.248 | 2198200 | 2198200 / 400 | 67.098 / 27.968 | 99.384 | 364806144 |
| ListOfLists flat | current-default | 1703.666 | 0 | 0 / 0 | 0.000 / 0.000 | 0.007 | 39862272 |
| ListOfLists flat | improved-default | 1714.297 | 0 | 0 / 0 | 0.000 / 0.000 | 0.008 | 39895040 |
| ListOfLists flat | chunk-default | 1710.662 | 0 | 0 / 0 | 0.000 / 0.000 | 0.007 | 39895040 |
| ListOfLists flat | unsafe-hp-32k | 1702.746 | 0 | 0 / 0 | 0.000 / 0.000 | 0.006 | 39878656 |
| ListOfLists flat | improved-32k | 1692.936 | 0 | 0 / 0 | 0.000 / 0.000 | 0.009 | 39862272 |

### Dataflow Rows

The Dataflow runner reports one SafeZone trace aggregate per binary run, shared
by SELECT/AGGREGATE/JOIN rows for a given config.

| Query | current-default | improved-default | chunk-default | unsafe-hp-32k | improved-32k |
|---|---:|---:|---:|---:|---:|
| SELECT median ms | 59.766 | 56.541 | 56.331 | 55.972 | 55.893 |
| AGGREGATE median ms | 96.635 | 88.645 | 87.495 | 87.080 | 87.773 |
| JOIN median ms | 57.961 | 55.188 | 54.572 | 54.506 | 54.864 |

Shared trace counters by config:

| Config | Claim calls | Root add/remove calls | Root add/remove ms | Reclaim ms | RSS bytes |
|---|---:|---:|---:|---:|---:|
| current-default | 93624 | 93624 / 93624 | 2.492 / 83.366 | 88.852 | 47087616 |
| improved-default | 93624 | 93624 / 420 | 2.459 / 1.223 | 3.647 | 47104000 |
| chunk-default | 93624 | 8 / 0 | 0.002 / 0.000 | 3.603 | 49561600 |
| unsafe-hp-32k | 23442 | 0 / 0 | 0.000 / 0.000 | 0.907 | 47022080 |
| improved-32k | 23442 | 23442 / 420 | 0.664 / 0.355 | 0.915 | 47022080 |

### Common Crawl WET-Shaped Q1

Generated 1M pages, `137000000` token records.

| Config | Median ms | Median GC ms | Max GC ms | Claim calls | Root add/remove calls | Root add/remove ms | Reclaim ms | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| current-default | 27790.600 | 31.328 | 45.366 | 4836000 | 4836000 / 4836000 | 133.118 / 1326310.264 | 1326591.838 | 475152384 |
| improved-default | 8478.823 | 32.891 | 35.175 | 4836000 | 4836000 / 3000 | 140.949 / 183.121 | 332.548 | 475119616 |
| chunk-default | 8626.992 | 34.644 | 45.748 | 4836000 | 22 / 0 | 0.004 / 0.000 | 190.519 | 475906048 |
| unsafe-hp-32k | 227556.451 | 18.259 | 20.282 | 1207200 | 0 / 0 | 0.000 / 0.000 | 47.542 | 474513408 |
| improved-32k | 8079.502 | 30.878 | 33.687 | 1207200 | 1207200 / 3000 | 47.067 / 46.695 | 77.725 | 474644480 |

The `unsafe-hp-32k` Common Crawl q1 row is a severe negative/pathology under
trace. It matched checksum/output count, and its root/reclaim counters are low,
so the slowdown is not explained by root registration. Do not build a checked
SafeZone-HP backend on the assumption that rootless 32 KiB SafeZone is always
the fastest substrate. This row needs a targeted non-trace rerun and allocator
inspection before using `unsafe-hp-32k` for parser/token workloads.

## Headline Interpretation

- Current SafeZone's major cliff is still per-page root removal. It dominates
  GCBench, linked ListOfLists, Dataflow, and generated Common Crawl q1.
- Improved SafeZone root coalescing removes nearly all of that root-removal
  cost. Root mode `1` is therefore the real SafeZone baseline.
- `SAFEZONE_PAGE_SIZE=32768` explains much of the UnsafeZone-HP improvement:
  it cuts claim counts by about 4x in GCBench/ListOfLists/Dataflow/Common
  Crawl. `improved-32k` matches or beats `unsafe-hp-32k` on GCBench,
  ListOfLists linked, ListOfLists flat, and Common Crawl q1.
- Chunk-root mode is competitive on Dataflow and Common Crawl q1, and may be a
  safer intermediate substrate than fully rootless regions.
- `unsafezone-hp` remains useful as an unsafe lower-bound/control, but the cost
  matrix does not justify making it the first checked backend target. The next
  safer target is improved SafeZone with explicit page-size/chunk-root
  configuration, plus a separate investigation of the Common Crawl
  `unsafe-hp-32k` pathology.

## Resulting Next Steps

1. Add non-trace focused reruns for `improved-32k`, `chunk-default`, and
   `unsafe-hp-32k` on Common Crawl-like q1/q2/q3 before using
   `unsafe-hp-32k` as a backend substrate.
2. Treat `improved-32k` as the leading SafeZone-family configuration to study
   for a checked backend, with chunk roots as the safer fallback candidate.
3. Do not implement `rift-checked-safezone-hp` until unsupported mixed
   references and the root-free lowering policy are clear and the unsafe q1
   pathology is explained.
