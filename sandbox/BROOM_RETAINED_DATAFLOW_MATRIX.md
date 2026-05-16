# Broom Retained Dataflow Matrix

Last updated: 2026-05-16 03:16 CEST

Status: new prior-work-style retained-object dataflow benchmark. This matrix
compares the natural heap/GC program against the checked Rift region program,
following the way prior region systems usually report their main result:
ordinary heap allocation versus a region-enabled version with an exposed
lifetime boundary.

This is a local single-process Broom/Naiad-style methodology benchmark, not an
exact Broom or Naiad artifact reproduction.

## Benchmark Shape

`BroomRetainedDataflowMatrix` models timestamped dataflow operators where
records are retained until a notification/epoch boundary:

- `aggregate`: allocate ordinary event/value objects, retain them in
  per-timestamp dictionaries, update per-key aggregates, then notify/close the
  timestamp.
- `join`: allocate left/right ordinary records in per-timestamp per-key
  dictionaries, emit matches while retaining active timestamp state, then
  notify/close the timestamp.
- high-cardinality/active-timestamp variants keep multiple timestamp states
  live to increase heap traversal and RSS pressure.

Headline modes:

| Mode | Meaning |
|---|---|
| `heap-gc` | Natural heap/GC implementation using ordinary Scala objects and normal heap retention until timestamp close. |
| `checked-rift` | Checked Rift implementation using timestamp/epoch regions for transient timestamp-local objects; durable control metadata remains on heap/primitive state. |

Mechanism controls such as retained heap/drop-anchor, legacy checked, unsafe
rootless, and summary-only lower bounds are intentionally not part of this
headline matrix. They remain useful for causality and appendix/debugging, but
the paper-facing comparison here is natural heap/GC versus checked Rift.

## Commands

20k correctness smoke:

```sh
RIFT_FINAL_CLEAN=1 \
BROOM_OUTPUT_DIR=/private/tmp/broom-retained-smoke-20260516c \
BROOM_BUILD=1 \
BROOM_RECORDS=20000 \
BROOM_RECORDS_PER_TIMESTAMP=2500 \
BROOM_ACTIVE_TIMESTAMPS=4 \
BROOM_KEY_SPACE=4096 \
BROOM_BENCHMARK_RUNS=1 \
BROOM_WARMUPS=0 \
BROOM_WORKLOADS="aggregate join" \
BROOM_MODES="heap-gc checked-rift" \
zsh sandbox/run_broom_retained_dataflow_matrix.sh
```

1M, 5M, and 20M L1/L2 rows use the same mode/workload set with
`BROOM_BENCHMARK_RUNS=3`, `BROOM_WARMUPS=1`,
`BROOM_RECORDS_PER_TIMESTAMP=25000`, and `BROOM_KEY_SPACE=32768`. L1 rows set
`RIFT_FINAL_CLEAN=1`; L2 rows omit it.

High-cardinality active-timestamp row:

```sh
RIFT_FINAL_CLEAN=1 \
BROOM_RECORDS=1000000 \
BROOM_RECORDS_PER_TIMESTAMP=25000 \
BROOM_ACTIVE_TIMESTAMPS=16 \
BROOM_KEY_SPACE=65536 \
BROOM_BENCHMARK_RUNS=3 \
BROOM_WARMUPS=1 \
BROOM_WORKLOADS="aggregate join" \
BROOM_MODES="heap-gc checked-rift" \
zsh sandbox/run_broom_retained_dataflow_matrix.sh
```

## Correctness Smoke

20k L1 smoke matched checksum/output for heap and checked Rift:

| Workload | Mode | Checksum | Output count | RSS bytes |
|---|---|---:|---:|---:|
| aggregate | `heap-gc` | `6952075672042057026` | `15062` | `6029312` |
| aggregate | `checked-rift` | `6952075672042057026` | `15062` | `4734976` |
| join | `heap-gc` | `-7268411144049268350` | `12958` | `5849088` |
| join | `checked-rift` | `-7268411144049268350` | `12958` | `4718592` |

## L1 Final-Clean Rows

L1 rows use external process timing/RSS. No diagnostics, tracing, profiling, or
allocation attribution are enabled.

| Records | Active timestamps | Workload | Mode | L1 real s | RSS bytes | Checksum | Output count | Claim |
|---:|---:|---|---|---:|---:|---:|---:|---|
| 1M | 4 | aggregate | `heap-gc` | `0.40` | `75644928` | `2843352872537677199` | `708604` | Natural heap baseline. |
| 1M | 4 | aggregate | `checked-rift` | `0.24` | `13451264` | `2843352872537677199` | `708604` | Checked Rift is about `40.0%` faster and `82%` lower RSS. |
| 1M | 4 | join | `heap-gc` | `0.31` | `74694656` | `-5733395378394929899` | `681426` | Natural heap baseline. |
| 1M | 4 | join | `checked-rift` | `0.28` | `12713984` | `-5733395378394929899` | `681426` | Checked Rift is about `9.7%` faster and `83%` lower RSS. |
| 5M | 4 | aggregate | `heap-gc` | `1.71` | `75759616` | `1129059544353065479` | `3546626` | Natural heap baseline. |
| 5M | 4 | aggregate | `checked-rift` | `0.94` | `13484032` | `1129059544353065479` | `3546626` | Checked Rift is about `45.0%` faster and `82%` lower RSS. |
| 5M | 4 | join | `heap-gc` | `1.35` | `74891264` | `8970609240165110799` | `3404170` | Natural heap baseline. |
| 5M | 4 | join | `checked-rift` | `1.22` | `12730368` | `8970609240165110799` | `3404170` | Checked Rift is about `9.6%` faster and `83%` lower RSS. |
| 20M | 4 | aggregate | `heap-gc` | `6.66` | `75759616` | `-6213795708380666256` | `14180644` | Natural heap baseline. |
| 20M | 4 | aggregate | `checked-rift` | `4.14` | `13549568` | `-6213795708380666256` | `14180644` | Checked Rift is about `37.8%` faster and `82%` lower RSS. |
| 20M | 4 | join | `heap-gc` | `5.72` | `74629120` | `2961953091326998353` | `13612832` | Natural heap baseline. |
| 20M | 4 | join | `checked-rift` | `5.12` | `12795904` | `2961953091326998353` | `13612832` | Checked Rift is about `10.5%` faster and `83%` lower RSS. |
| 1M | 16 | aggregate | `heap-gc` | `0.67` | `232341504` | `8854638383809110735` | `839789` | High-live-state heap baseline. |
| 1M | 16 | aggregate | `checked-rift` | `0.51` | `53149696` | `8854638383809110735` | `839789` | Checked Rift is about `23.9%` faster and `77%` lower RSS. |
| 1M | 16 | join | `heap-gc` | `0.63` | `239403008` | `3791171928160505090` | `591580` | High-live-state heap baseline. |
| 1M | 16 | join | `checked-rift` | `0.42` | `56492032` | `3791171928160505090` | `591580` | Checked Rift is about `33.3%` faster and `76%` lower RSS. |

## L2 Interpretation Rows

L2 rows are standard-stat runs. Use them to explain GC and region behavior, not
as final-clean headline elapsed timing.

| Records | Active timestamps | Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Region op ms | Region objects | Region resets |
|---:|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1M | 4 | aggregate | `heap-gc` | `114.180` | `24.266` | `31.708` | `3/3` | `75644928` | `0.000` | `0` | `0` |
| 1M | 4 | aggregate | `checked-rift` | `83.703` | `0.000` | `0.000` | `0/3` | `13451264` | `0.674` | `1708634` | `10` |
| 1M | 4 | join | `heap-gc` | `106.751` | `13.470` | `17.351` | `3/3` | `74694656` | `0.000` | `0` | `0` |
| 1M | 4 | join | `checked-rift` | `82.532` | `0.000` | `0.000` | `0/3` | `12713984` | `0.586` | `1000020` | `10` |
| 5M | 4 | aggregate | `heap-gc` | `545.521` | `134.111` | `135.310` | `3/3` | `75759616` | `0.000` | `0` | `0` |
| 5M | 4 | aggregate | `checked-rift` | `333.294` | `0.000` | `0.000` | `0/3` | `13484032` | `1.792` | `8546776` | `50` |
| 5M | 4 | join | `heap-gc` | `502.627` | `57.235` | `70.762` | `3/3` | `74891264` | `0.000` | `0` | `0` |
| 5M | 4 | join | `checked-rift` | `426.691` | `0.000` | `0.000` | `0/3` | `12730368` | `3.194` | `5000100` | `50` |
| 20M | 4 | aggregate | `heap-gc` | `2060.553` | `499.423` | `526.671` | `3/3` | `75759616` | `0.000` | `0` | `0` |
| 20M | 4 | aggregate | `checked-rift` | `1593.740` | `0.000` | `0.000` | `0/3` | `13549568` | `11.589` | `34181244` | `200` |
| 20M | 4 | join | `heap-gc` | `1988.478` | `288.525` | `299.673` | `3/3` | `74629120` | `0.000` | `0` | `0` |
| 20M | 4 | join | `checked-rift` | `1743.787` | `0.000` | `0.000` | `0/3` | `12795904` | `12.666` | `20000400` | `200` |
| 1M | 16 | aggregate | `heap-gc` | `222.413` | `53.115` | `89.073` | `3/3` | `235077632` | `0.000` | `0` | `0` |
| 1M | 16 | aggregate | `checked-rift` | `157.008` | `0.000` | `0.000` | `0/3` | `53280768` | `0.783` | `1839798` | `3` |
| 1M | 16 | join | `heap-gc` | `166.912` | `28.080` | `28.779` | `3/3` | `163004416` | `0.000` | `0` | `0` |
| 1M | 16 | join | `checked-rift` | `143.453` | `0.000` | `0.000` | `0/3` | `56623104` | `0.883` | `1000006` | `3` |

## Interpretation

- This matrix finally gives a Broom-like retained-object row where heap GC is
  material in Scala Native: aggregate heap GC is about `21-25%` of L2 elapsed
  at 1M through 20M, and join heap GC is about `11-15%`.
- Checked Rift removes timed heap GC and bulk-closes timestamp regions with
  low region-op time: `11-13 ms` region op for 20M records and 200 resets.
- The high-active-timestamp variant confirms the expected RSS behavior:
  keeping more timestamp states live raises heap RSS to `232-239 MB`, while
  checked Rift stays near `53-56 MB`.
- The headline comparison is natural heap/GC versus checked Rift. Same-shape
  retained heap/drop-anchor controls are still valuable appendix evidence, but
  they are not the main prior-work-style comparison for this benchmark.

## Heap-Cap Follow-Up

1M active-16 L1 final-clean with heap caps:

```sh
RIFT_FINAL_CLEAN=1 \
BROOM_OUTPUT_DIR=/private/tmp/broom-retained-1m-active16-caps-20260516 \
BROOM_BUILD=0 \
BROOM_RECORDS=1000000 \
BROOM_RECORDS_PER_TIMESTAMP=25000 \
BROOM_ACTIVE_TIMESTAMPS=16 \
BROOM_KEY_SPACE=65536 \
BROOM_BENCHMARK_RUNS=3 \
BROOM_WARMUPS=1 \
BROOM_WORKLOADS="aggregate join" \
BROOM_MODES="heap-gc checked-rift" \
BROOM_HEAP_CAPS="256M 128M 64M" \
zsh sandbox/run_broom_retained_dataflow_matrix.sh
```

| Workload | Mode | Heap cap | Status | L1 real s | RSS bytes | Checksum | Output count |
|---|---|---:|---|---:|---:|---:|---:|
| aggregate | `heap-gc` | `256M` | completed | `0.78` | `232390656` | `8854638383809110735` | `839789` |
| aggregate | `heap-gc` | `128M` | OOM | `0.50` | `140410880` |  |  |
| aggregate | `heap-gc` | `64M` | OOM | `0.19` | `63651840` |  |  |
| aggregate | `checked-rift` | uncapped | completed | `0.51` | `53166080` | `8854638383809110735` | `839789` |
| join | `heap-gc` | `256M` | completed | `0.67` | `239255552` | `3791171928160505090` | `591580` |
| join | `heap-gc` | `128M` | OOM | `0.14` | `76742656` |  |  |
| join | `heap-gc` | `64M` | OOM | `0.11` | `59883520` |  |  |
| join | `checked-rift` | uncapped | completed | `0.45` | `56492032` | `3791171928160505090` | `591580` |

Interpretation: the high-active timestamp row now has fixed-memory evidence.
At `256M`, heap completes but is slower and uses about `232-239 MB` RSS. At
`128M` and `64M`, heap fails before producing results. Checked Rift completes
with matching checksum/output and about `53-56 MB` total RSS because the
timestamp-local retained records are region-owned and bulk-closed.

## Next Work

- Add a safe/rooted baseline if a scoped SafeZone-backed timestamp-region mode
  is useful for backend comparison.
- Use this benchmark as the first retained-object GC-heavy dataflow case study
  while continuing the real-input search for sessions, joins, timestamp
  dictionaries, transaction-local objects, graph epochs, and text/top-k
  candidates.
