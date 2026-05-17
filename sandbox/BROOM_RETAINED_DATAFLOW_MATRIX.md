# Broom Retained Dataflow Matrix

Last updated: 2026-05-17 12:03 CEST

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
- `q17` / `tpch-q17` / `q17-retained`: allocate deterministic `Part` and
  `LineItem`-like records, retain lineitems in per-timestamp per-part
  dictionaries, retain per-part aggregate entries, then compute a
  TPC-H-Q17-style below-average quantity/revenue filter at timestamp close.
  The same workload can also consume DBGEN-style `part.tbl` and
  `lineitem.tbl` via `BROOM_Q17_INPUT_MODE=tpch-file`.
- high-cardinality/active-timestamp variants keep multiple timestamp states
  live to increase heap traversal and RSS pressure.

Headline modes:

| Mode | Meaning |
|---|---|
| `heap-gc` | Natural heap/GC implementation using ordinary Scala objects and normal heap retention until timestamp close. |
| `checked-rift` | Checked Rift implementation using timestamp/epoch regions for transient timestamp-local objects; durable control metadata remains on heap/primitive state. |
| `checked-region-scoped` | Checked Rift API over the SafeZone-backed scoped backend. This is the best-safe-region/backend comparison row, not a separate user-facing system. |

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
BROOM_MODES="heap-gc checked-rift checked-region-scoped" \
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

DBGEN/TPC-H file-backed q17 mode:

```sh
BROOM_Q17_INPUT_MODE=tpch-file \
BROOM_TPCH_PART_INPUT=/path/to/part.tbl \
BROOM_TPCH_LINEITEM_INPUT=/path/to/lineitem.tbl \
BROOM_TPCH_BRAND='Brand#23' \
BROOM_TPCH_CONTAINER='MED BOX' \
BROOM_WORKLOADS="q17" \
BROOM_MODES="heap-gc checked-rift checked-region-scoped" \
zsh sandbox/run_broom_retained_dataflow_matrix.sh
```

This mode reads `part.tbl` and `lineitem.tbl` incrementally and uses
`BROOM_RECORDS` as the maximum number of lineitem rows to consume. It retains
all lineitems in the active timestamp/part dictionaries, then applies the Q17
selected-part and below-average filters at timestamp close. It is a
DBGEN/TPC-H workload input mode, not an audited official TPC-H result and not
real-world production input. SF0.1 rows below were generated locally with the
public `electrum/tpch-dbgen` mirror; SF1/full TPC-H rows are still pending.

## Correctness Smoke

20k L1 smoke matched checksum/output for heap, checked Rift, and the checked
scoped backend:

| Workload | Mode | Checksum | Output count | RSS bytes |
|---|---|---:|---:|---:|
| aggregate | `heap-gc` | `2757946740166219268` | `15219` | `6225920` |
| aggregate | `checked-rift` | `2757946740166219268` | `15219` | `5832704` |
| aggregate | `checked-region-scoped` | `2757946740166219268` | `15219` | `6062080` |
| join | `heap-gc` | `-4534341871053622537` | `12934` | `5996544` |
| join | `checked-rift` | `-4534341871053622537` | `12934` | `5767168` |
| join | `checked-region-scoped` | `-4534341871053622537` | `12934` | `5996544` |

Tiny DBGEN-shape file-backed q17 smoke, 2026-05-17 03:20 CEST:

Command used a hand-sized `/private/tmp` `part.tbl`/`lineitem.tbl` fixture
with `BROOM_Q17_INPUT_MODE=tpch-file`, `BROOM_RECORDS=8`, and the three
headline modes. The goal was parser/integration correctness, not performance.

| Workload | Mode | Measurement level | Checksum | Output count | Retained objects | Region-freed proxy | RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|
| q17 | `heap-gc` | L1 | `-3582489220934111213` | `1` | `14` | `0` | `8536064` |
| q17 | `checked-rift` | L1 | `-3582489220934111213` | `1` | `14` | `15` | `8585216` |
| q17 | `checked-region-scoped` | L1 | `-3582489220934111213` | `1` | `14` | `15` | `8585216` |

DBGEN SF0.1 file-backed q17 smoke/scale, 2026-05-17 12:03 CEST:

Provenance:

- DBGEN source: `/Users/siyaoliu/rift/cache/tpch-dbgen`, cloned from
  `https://github.com/electrum/tpch-dbgen`.
- Generation command shape:
  `DSS_PATH=/Users/siyaoliu/rift/cache/tpch-sf0.1 ./dbgen -f -s 0.1 -T P`
  and the same command with `-T L`.
- Generated input: `part.tbl` has `20000` rows and `lineitem.tbl` has
  `600572` rows (`71 MB` lineitem file).
- Q17 parameters: QGEN in this checkout produced `Brand#13` / `SM PKG`; those
  parameters match `26` part rows and `782` lineitems, but the retained
  dataflow implementation keeps all lineitems until timestamp close.

L1 final-clean, SF0.1, 600572 lineitems, 3 repeats:

| Workload | Mode | L1 real s | RSS bytes | Checksum | Output count | Retained objects | Region-freed proxy | Claim |
|---|---|---:|---:|---:|---:|---:|---:|---|
| q17 | `heap-gc` | `5.57` | `257474560` | `-5130892219889863805` | `25` | `1287360` | `0` | Natural heap baseline over DBGEN-generated input. |
| q17 | `checked-rift` | `5.15` | `50921472` | `-5130892219889863805` | `25` | `1287360` | `1287367` | Checked Rift is about `7.5%` faster and about `80%` lower RSS. |
| q17 | `checked-region-scoped` | `5.22` | `51085312` | `-5130892219889863805` | `25` | `1287360` | `1287367` | Checked scoped is about `6.3%` faster than heap and close to checked Rift. |

L2 standard stats, SF0.1, 600572 lineitems, 3 repeats:

| Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Region resets | RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q17 | `heap-gc` | `1731.492` | `59.210` | `118.781` | `3/3` | `0.000` | `0` | `0` | `257572864` |
| q17 | `checked-rift` | `1733.100` | `38.075` | `39.236` | `3/3` | `0.468` | `1287367` | `7` | `50954240` |
| q17 | `checked-region-scoped` | `1717.691` | `69.013` | `70.224` | `3/3` | `0.000` | `0` | `0` | `51150848` |

Heap-cap follow-up, SF0.1:

| Mode | Heap cap | Status | L2 median ms | GC median ms | RSS bytes | Checksum | Output count |
|---|---:|---|---:|---:|---:|---:|---:|
| `heap-gc` | `256M` | completed | `1722.054` | `61.629` | `257572864` | `-5130892219889863805` | `25` |
| `heap-gc` | `128M` | completed | `1753.752` | `61.859` | `138739712` | `-5130892219889863805` | `25` |
| `heap-gc` | `64M` | failed/OOM | n/a | n/a | `71909376` | n/a | n/a |
| `checked-rift` | uncapped | completed | `1697.607` | `36.855` | `50954240` | `-5130892219889863805` | `25` |

Interpretation: SF0.1 is now a standardized DBGEN-generated input row, not a
hand-written generator row. It is still not real-world input and still smaller
than SF1, but it reproduces the retained-object shape over DBGEN tables. The
headline signal is mostly RSS/fixed-memory plus modest L1 throughput: checked
Rift lowers RSS from about `257 MB` to about `51 MB`, and heap fails at `64M`.
The L2 elapsed rows are near-ties because file parsing and line scanning still
dominate at SF0.1.

## L1 Final-Clean Rows

L1 rows use external process timing/RSS. No diagnostics, tracing, profiling, or
allocation attribution are enabled.

| Records | Active timestamps | Workload | Mode | L1 real s | RSS bytes | Checksum | Output count | Claim |
|---:|---:|---|---|---:|---:|---:|---:|---|
| 1M | 4 | aggregate | `heap-gc` | `0.59` | `75710464` | `2843352872537677199` | `708604` | Natural heap baseline. |
| 1M | 4 | aggregate | `checked-rift` | `0.18` | `13500416` | `2843352872537677199` | `708604` | Checked Rift is about `69.5%` faster and `82%` lower RSS. |
| 1M | 4 | aggregate | `checked-region-scoped` | `0.23` | `13615104` | `2843352872537677199` | `708604` | Checked scoped backend is about `61.0%` faster than heap and about `27.8%` slower than checked Rift, with comparable RSS. |
| 1M | 4 | join | `heap-gc` | `0.27` | `74743808` | `-5733395378394929899` | `681426` | Natural heap baseline. |
| 1M | 4 | join | `checked-rift` | `0.22` | `12763136` | `-5733395378394929899` | `681426` | Checked Rift is about `18.5%` faster and `83%` lower RSS. |
| 1M | 4 | join | `checked-region-scoped` | `0.23` | `12877824` | `-5733395378394929899` | `681426` | Checked scoped backend is about `14.8%` faster than heap and about `4.5%` slower than checked Rift, with comparable RSS. |
| 5M | 4 | aggregate | `heap-gc` | `1.60` | `75808768` | `1129059544353065479` | `3546626` | Natural heap baseline. |
| 5M | 4 | aggregate | `checked-rift` | `1.18` | `13533184` | `1129059544353065479` | `3546626` | Checked Rift is about `26.3%` faster and `82%` lower RSS. |
| 5M | 4 | aggregate | `checked-region-scoped` | `1.31` | `13697024` | `1129059544353065479` | `3546626` | Checked scoped backend is about `18.1%` faster than heap and about `11.0%` slower than checked Rift, with comparable RSS. |
| 5M | 4 | join | `heap-gc` | `1.46` | `74940416` | `8970609240165110799` | `3404170` | Natural heap baseline. |
| 5M | 4 | join | `checked-rift` | `1.21` | `12779520` | `8970609240165110799` | `3404170` | Checked Rift is about `17.1%` faster and `83%` lower RSS. |
| 5M | 4 | join | `checked-region-scoped` | `1.18` | `12943360` | `8970609240165110799` | `3404170` | Checked scoped backend is about `19.2%` faster than heap and slightly faster than checked Rift in this L1 rerun, with comparable RSS. |
| 20M | 4 | aggregate | `heap-gc` | `5.27` | `75792384` | `-6213795708380666256` | `14180644` | Natural heap baseline. |
| 20M | 4 | aggregate | `checked-rift` | `3.59` | `13565952` | `-6213795708380666256` | `14180644` | Checked Rift is about `31.9%` faster and `82%` lower RSS. |
| 20M | 4 | aggregate | `checked-region-scoped` | `4.55` | `13729792` | `-6213795708380666256` | `14180644` | Checked scoped backend is about `13.7%` faster than heap and about `26.7%` slower than checked Rift, with comparable RSS. |
| 20M | 4 | join | `heap-gc` | `5.00` | `74661888` | `2961953091326998353` | `13612832` | Natural heap baseline. |
| 20M | 4 | join | `checked-rift` | `4.26` | `12812288` | `2961953091326998353` | `13612832` | Checked Rift is about `14.8%` faster and `83%` lower RSS. |
| 20M | 4 | join | `checked-region-scoped` | `4.52` | `12992512` | `2961953091326998353` | `13612832` | Checked scoped backend is about `9.6%` faster than heap and about `6.1%` slower than checked Rift, with comparable RSS. |
| 1M | 16 | aggregate | `heap-gc` | `0.67` | `232341504` | `8854638383809110735` | `839789` | High-live-state heap baseline. |
| 1M | 16 | aggregate | `checked-rift` | `0.51` | `53149696` | `8854638383809110735` | `839789` | Checked Rift is about `23.9%` faster and `77%` lower RSS. |
| 1M | 16 | join | `heap-gc` | `0.63` | `239403008` | `3791171928160505090` | `591580` | High-live-state heap baseline. |
| 1M | 16 | join | `checked-rift` | `0.42` | `56492032` | `3791171928160505090` | `591580` | Checked Rift is about `33.3%` faster and `76%` lower RSS. |

## L2 Interpretation Rows

L2 rows are standard-stat runs. Use them to explain GC and region behavior, not
as final-clean headline elapsed timing.

| Records | Active timestamps | Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Region op ms | Region objects | Region resets |
|---:|---:|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1M | 4 | aggregate | `heap-gc` | `106.309` | `27.412` | `27.824` | `3/3` | `75808768` | `0.000` | `0` | `0` |
| 1M | 4 | aggregate | `checked-rift` | `65.975` | `0.000` | `0.000` | `0/3` | `13631488` | `0.339` | `1708634` | `10` |
| 1M | 4 | aggregate | `checked-region-scoped` | `79.657` | `0.000` | `0.000` | `0/3` | `13893632` | `0.000` | `0` | `0` |
| 1M | 4 | join | `heap-gc` | `91.569` | `11.680` | `15.825` | `3/3` | `75448320` | `0.000` | `0` | `0` |
| 1M | 4 | join | `checked-rift` | `76.459` | `0.000` | `0.000` | `0/3` | `12894208` | `0.402` | `1000020` | `10` |
| 1M | 4 | join | `checked-region-scoped` | `76.802` | `0.000` | `0.000` | `0/3` | `13139968` | `0.000` | `0` | `0` |
| 5M | 4 | aggregate | `heap-gc` | `439.733` | `104.789` | `107.161` | `3/3` | `75923456` | `0.000` | `0` | `0` |
| 5M | 4 | aggregate | `checked-rift` | `316.369` | `0.000` | `0.000` | `0/3` | `13664256` | `1.320` | `8546776` | `50` |
| 5M | 4 | aggregate | `checked-region-scoped` | `380.395` | `0.000` | `0.000` | `0/3` | `13860864` | `0.000` | `0` | `0` |
| 5M | 4 | join | `heap-gc` | `413.254` | `54.116` | `64.931` | `3/3` | `75431936` | `0.000` | `0` | `0` |
| 5M | 4 | join | `checked-rift` | `370.018` | `0.000` | `0.000` | `0/3` | `12910592` | `1.945` | `5000100` | `50` |
| 5M | 4 | join | `checked-region-scoped` | `377.139` | `0.000` | `0.000` | `0/3` | `13139968` | `0.000` | `0` | `0` |
| 20M | 4 | aggregate | `heap-gc` | `1728.037` | `410.002` | `414.132` | `3/3` | `75907072` | `0.000` | `0` | `0` |
| 20M | 4 | aggregate | `checked-rift` | `1248.788` | `0.000` | `0.000` | `0/3` | `13729792` | `5.475` | `34181244` | `200` |
| 20M | 4 | aggregate | `checked-region-scoped` | `1496.342` | `0.000` | `0.000` | `0/3` | `14041088` | `0.000` | `0` | `0` |
| 20M | 4 | join | `heap-gc` | `1686.550` | `267.636` | `279.596` | `3/3` | `75644928` | `0.000` | `0` | `0` |
| 20M | 4 | join | `checked-rift` | `1465.638` | `0.000` | `0.000` | `0/3` | `12959744` | `7.469` | `20000400` | `200` |
| 20M | 4 | join | `checked-region-scoped` | `1497.109` | `0.000` | `0.000` | `0/3` | `13254656` | `0.000` | `0` | `0` |
| 1M | 16 | aggregate | `heap-gc` | `222.413` | `53.115` | `89.073` | `3/3` | `235077632` | `0.000` | `0` | `0` |
| 1M | 16 | aggregate | `checked-rift` | `157.008` | `0.000` | `0.000` | `0/3` | `53280768` | `0.783` | `1839798` | `3` |
| 1M | 16 | join | `heap-gc` | `166.912` | `28.080` | `28.779` | `3/3` | `163004416` | `0.000` | `0` | `0` |
| 1M | 16 | join | `checked-rift` | `143.453` | `0.000` | `0.000` | `0/3` | `56623104` | `0.883` | `1000006` | `3` |

## Active-16 Scale Follow-Up

Date/time: 2026-05-16 17:55 CEST.

This follow-up extends the high-live-state row from 1M to 5M records while
keeping `BROOM_ACTIVE_TIMESTAMPS=16`, `BROOM_RECORDS_PER_TIMESTAMP=25000`,
and `BROOM_KEY_SPACE=65536`. It is the next promising benchmark after the
LogHub q3 active-window triage because it spends a much larger share of the
work loop in heap GC while retaining ordinary timestamp-local objects.

L1 final-clean, 5M records x3:

| Workload | Mode | L1 real s | RSS bytes | Checksum | Output count | Claim |
|---|---|---:|---:|---:|---:|---|
| aggregate | `heap-gc` | `2.84` | `235880448` | `-5905754216353393596` | `4203692` | Natural heap baseline. |
| aggregate | `checked-rift` | `2.06` | `53248000` | `-5905754216353393596` | `4203692` | Checked Rift is about `27.5%` faster and about `77%` lower RSS. |
| aggregate | `checked-region-scoped` | `2.91` | `53641216` | `-5905754216353393596` | `4203692` | Safe scoped backend removes GC/RSS pressure but is slightly slower than heap on elapsed in this high-active aggregate row. |
| join | `heap-gc` | `2.72` | `362315776` | `-7727222760792553569` | `2955614` | Natural heap baseline. |
| join | `checked-rift` | `2.04` | `56557568` | `-7727222760792553569` | `2955614` | Checked Rift is about `25.0%` faster and about `84%` lower RSS. |
| join | `checked-region-scoped` | `2.24` | `56819712` | `-7727222760792553569` | `2955614` | Safe scoped backend is about `17.6%` faster than heap and about `84%` lower RSS. |

L2 standard stats, 5M records x3:

| Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Region resets |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| aggregate | `heap-gc` | `869.384` | `176.734` | `203.724` | `3/3` | `0.000` | `0` | `0` |
| aggregate | `checked-rift` | `715.297` | `0.000` | `0.000` | `0/3` | `4.524` | `9203731` | `13` |
| aggregate | `checked-region-scoped` | `923.846` | `0.000` | `0.000` | `0/3` | `0.000` | `0` | `0` |
| join | `heap-gc` | `791.787` | `147.124` | `155.291` | `3/3` | `0.000` | `0` | `0` |
| join | `checked-rift` | `690.092` | `0.000` | `0.000` | `0/3` | `5.578` | `5000026` | `13` |
| join | `checked-region-scoped` | `753.323` | `0.000` | `0.000` | `0/3` | `0.000` | `0` | `0` |

Interpretation: the 5M active-16 row strengthens the Broom-style case study.
Heap spends about `20.3%` of aggregate L2 time and `18.6%` of join L2 time in
timed GC. Checked Rift removes that GC, stays low-RSS, and has small region-op
time relative to elapsed. This is a stronger GC-heavy data-processing row than
the LogHub q3 active-window triage.

### 20M Active-16 Follow-Up

Date/time: 2026-05-16 18:20 CEST.

This follow-up scales the same high-live-state configuration to 20M records.
Raw summaries:

- `/Users/siyaoliu/rift/cache/broom-retained-20m-active16-20260516/summary.tsv`
- `/Users/siyaoliu/rift/cache/broom-retained-20m-active16-heapcaps-20260516/summary.tsv`

L1 final-clean and L2 standard-stat rows, 20M records x3:

| Workload | Mode | L1 real s | RSS bytes | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Region resets | Checksum | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| aggregate | `heap-gc` | `10.78` | `236240896` | `4235.563` | `953.153` | `2241.709` | `3/3` | `0.000` | `0` | `0` | `-1247762236770718130` | `16814775` |
| aggregate | `checked-rift` | `8.48` | `53264384` | `2904.092` | `0.000` | `0.000` | `0/3` | `15.371` | `36814925` | `50` | `-1247762236770718130` | `16814775` |
| aggregate | `checked-region-scoped` | `11.51` | `53657600` | `3905.089` | `0.000` | `0.000` | `0/3` | `0.000` | `0` | `0` | `-1247762236770718130` | `16814775` |
| join | `heap-gc` | `9.88` | `438140928` | `3133.932` | `486.649` | `490.029` | `3/3` | `0.000` | `0` | `0` | `8550799944693742972` | `11813690` |
| join | `checked-rift` | `8.09` | `56557568` | `2864.439` | `0.000` | `0.000` | `0/3` | `20.990` | `20000100` | `50` | `8550799944693742972` | `11813690` |
| join | `checked-region-scoped` | `9.15` | `56901632` | `3141.022` | `0.000` | `0.000` | `0/3` | `0.000` | `0` | `0` | `8550799944693742972` | `11813690` |

Heap-cap rows, 20M records x3:

| Workload | Mode | Heap cap | Status | L1 real s | RSS bytes | Median ms | GC median ms | GC max ms | GC collections | Checksum | Output count |
|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| aggregate | `heap-gc` | `512M` | completed | `15.85` | `398475264` | `3802.005` | `912.593` | `1574.844` | `32` | `-1247762236770718130` | `16814775` |
| aggregate | `heap-gc` | `384M` | OOM after two timed runs | `15.41` | `314228736` | n/a | n/a | n/a | n/a |  |  |
| aggregate | `heap-gc` | `256M` | OOM after two timed runs | `13.29` | `258031616` | n/a | n/a | n/a | n/a |  |  |
| join | `heap-gc` | `512M` | completed | `14.30` | `473513984` | `3249.380` | `455.743` | `494.576` | `14` | `8550799944693742972` | `11813690` |
| join | `heap-gc` | `384M` | OOM before timed output | `1.29` | `273088512` | n/a | n/a | n/a | n/a |  |  |
| join | `heap-gc` | `256M` | OOM before timed output | `0.93` | `222494720` | n/a | n/a | n/a | n/a |  |  |

Interpretation: this is the strongest Broom-style retained-object row so far.
At 20M active-16, heap spends about `22.5%` of aggregate median L2 time and
`15.5%` of join median L2 time in timed GC. Checked Rift removes that GC,
improves L1 elapsed by about `21.3%` on aggregate and `18.1%` on join, and
cuts RSS by about `77.5%` and `87.1%`, respectively. The heap-cap rows add a
fixed-memory signal: heap completes at `512M` but fails at `384M`/`256M`,
while the checked Rift rows complete uncapped at about `53-57 MB` RSS.

## Interpretation

- This matrix finally gives a Broom-like retained-object row where heap GC is
  material in Scala Native: aggregate heap GC is about `21-24%` of L2 elapsed
  at 1M through 20M, and join heap GC is about `11-16%`.
- Checked Rift removes timed heap GC and bulk-closes timestamp regions with
  low region-op time: about `5-8 ms` region op for 20M records and 200 resets
  in the latest scoped-comparison rerun.
- The checked scoped backend now has Broom comparison rows at 1M, 5M, and 20M.
  It matches checksums and removes timed heap GC throughout. It is faster than
  heap and usually slower than checked Rift on aggregate; join is closer, with
  checked scoped essentially tied with checked Rift at 1M/5M and slightly
  behind at 20M. It should be reported as a backend/topology comparison under
  the unified Rift story.
- The high-active-timestamp variant confirms the expected RSS behavior:
  keeping more timestamp states live raises heap RSS to hundreds of MB
  (`236-438 MB` at 20M active-16), while checked Rift stays near `53-57 MB`.
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

## Scoped Backend Completion Follow-Up

The 1M/5M checked scoped completion rows were rerun on 2026-05-16 after the
optimization-closure checkpoint:

- L1 1M: `/private/tmp/broom-retained-scoped-1m-20260516/summary.tsv`
- L2 1M: `/private/tmp/broom-retained-scoped-1m-l2-20260516/summary.tsv`
- L1 5M: `/private/tmp/broom-retained-scoped-5m-20260516/summary.tsv`
- L2 5M: `/private/tmp/broom-retained-scoped-5m-l2-20260516/summary.tsv`

These rows fill the safe-backend scale curve; no full Broom rerun is required
unless the benchmark implementation changes.

## TPC-H Q17-Style Retained Follow-Up

Date/time: 2026-05-17 02:54 CEST.

This follow-up adds a third Broom/Naiad-style workload, `q17`, with aliases
`tpch-q17` and `q17-retained`. It is deterministic generated methodology
evidence, not an exact TPC-H artifact reproduction. The workload keeps the
prior-work headline comparison style: natural heap/GC versus checked Rift, with
`checked-region-scoped` as the best-safe-backend comparison row.

Query shape:

- generate deterministic `Part` metadata and `LineItem`-like records;
- retain ordinary lineitem objects per timestamp and part key;
- retain per-part aggregate entries with quantity count and sum;
- at timestamp notify/close, select rows whose quantity is below one fifth of
  that part's average quantity and accumulate revenue/checksum;
- keep durable constants and primitive counters as heap/control metadata.

20k smoke:

```sh
RIFT_FINAL_CLEAN=1 \
BROOM_OUTPUT_DIR=/private/tmp/broom-q17-smoke-20260517 \
BROOM_BUILD=1 \
BROOM_RECORDS=20000 \
BROOM_RECORDS_PER_TIMESTAMP=2500 \
BROOM_ACTIVE_TIMESTAMPS=4 \
BROOM_KEY_SPACE=4096 \
BROOM_BENCHMARK_RUNS=1 \
BROOM_WARMUPS=0 \
BROOM_WORKLOADS="q17" \
BROOM_MODES="heap-gc checked-rift checked-region-scoped" \
zsh sandbox/run_broom_retained_dataflow_matrix.sh
```

The smoke matched checksum/output across all modes:

| Mode | Checksum | Output count | Retained object proxy | Region-freed proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|
| `heap-gc` | `-4880530136591270732` | `44` | `29354` | `0` | `14678` |
| `checked-rift` | `-4880530136591270732` | `44` | `29354` | `29356` | `14679` |
| `checked-region-scoped` | `-4880530136591270732` | `44` | `29354` | `29356` | `14679` |

### Q17 Active-4 Scale

Active-4 rows use `BROOM_RECORDS_PER_TIMESTAMP=25000`,
`BROOM_ACTIVE_TIMESTAMPS=4`, and `BROOM_KEY_SPACE=32768`.

L1 final-clean rows:

| Records | Mode | L1 real s | RSS bytes | Checksum | Output count | Claim |
|---:|---|---:|---:|---:|---:|---|
| 1M | `heap-gc` | `0.46` | `39354368` | `-4578452102221460627` | `2203` | Natural heap baseline. |
| 1M | `checked-rift` | `0.39` | `13139968` | `-4578452102221460627` | `2203` | Checked Rift is about `15.2%` faster and about `67%` lower RSS. |
| 1M | `checked-region-scoped` | `0.46` | `13303808` | `-4578452102221460627` | `2203` | Safe scoped backend removes timed GC in L2 and lowers RSS, but is an elapsed tie at 1M L1. |
| 5M | `heap-gc` | `1.90` | `39354368` | `-7339711446398030577` | `11380` | Natural heap baseline. |
| 5M | `checked-rift` | `1.52` | `13156352` | `-7339711446398030577` | `11380` | Checked Rift is about `20.0%` faster and about `67%` lower RSS. |
| 5M | `checked-region-scoped` | `1.73` | `13369344` | `-7339711446398030577` | `11380` | Safe scoped backend is about `8.9%` faster than heap and lower RSS. |
| 20M | `heap-gc` | `5.89` | `39354368` | `2928417581136374388` | `45638` | Natural heap baseline. |
| 20M | `checked-rift` | `4.76` | `13221888` | `2928417581136374388` | `45638` | Checked Rift is about `19.2%` faster and about `66%` lower RSS. |
| 20M | `checked-region-scoped` | `5.91` | `13434880` | `2928417581136374388` | `45638` | Safe scoped backend removes timed GC and lowers RSS, but is an elapsed tie at 20M L1. |

L2 standard-stat rows:

| Records | Mode | Median ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Region op ms | Region objects | Region resets |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1M | `heap-gc` | `128.172` | `6.405` | `8.543` | `3/3` | `39469056` | `0.000` | `0` | `0` |
| 1M | `checked-rift` | `100.773` | `0.000` | `0.000` | `0/3` | `13271040` | `0.561` | `1457534` | `10` |
| 1M | `checked-region-scoped` | `106.511` | `0.000` | `0.000` | `0/3` | `13500416` | `0.000` | `0` | `0` |
| 5M | `heap-gc` | `659.428` | `36.395` | `37.794` | `3/3` | `39469056` | `0.000` | `0` | `0` |
| 5M | `checked-rift` | `575.189` | `0.000` | `0.000` | `0/3` | `13287424` | `3.162` | `7287508` | `50` |
| 5M | `checked-region-scoped` | `602.648` | `0.000` | `0.000` | `0/3` | `13615104` | `0.000` | `0` | `0` |
| 20M | `heap-gc` | `2840.837` | `213.555` | `215.215` | `3/3` | `75317248` | `0.000` | `0` | `0` |
| 20M | `checked-rift` | `1892.916` | `0.000` | `0.000` | `0/3` | `13369344` | `10.774` | `29150830` | `200` |
| 20M | `checked-region-scoped` | `2200.976` | `0.000` | `0.000` | `0/3` | `13713408` | `0.000` | `0` | `0` |

Interpretation: active-4 `q17` becomes material at 20M. Heap spends about
`7.5%` of L2 elapsed in timed GC, while checked Rift is about `19.2%` faster
in L1, about `33.4%` faster in L2, and much lower RSS.

### Q17 Active-16 Scale

Active-16 rows use `BROOM_RECORDS_PER_TIMESTAMP=25000`,
`BROOM_ACTIVE_TIMESTAMPS=16`, and `BROOM_KEY_SPACE=65536`.

L1 final-clean rows:

| Records | Mode | L1 real s | RSS bytes | Checksum | Output count | Claim |
|---:|---|---:|---:|---:|---:|---|
| 5M | `heap-gc` | `3.17` | `231342080` | `-7464756659937277476` | `11211` | High-live-state heap baseline. |
| 5M | `checked-rift` | `2.25` | `49905664` | `-7464756659937277476` | `11211` | Checked Rift is about `29.0%` faster and about `78%` lower RSS. |
| 5M | `checked-region-scoped` | `3.09` | `50249728` | `-7464756659937277476` | `11211` | Safe scoped backend removes timed GC and lowers RSS, but is near heap elapsed at 5M. |
| 20M | `heap-gc` | `14.45` | `231686144` | `-4910137671593411349` | `44550` | High-live-state heap baseline. |
| 20M | `checked-rift` | `9.67` | `49905664` | `-4910137671593411349` | `44550` | Checked Rift is about `33.1%` faster and about `78%` lower RSS. |
| 20M | `checked-region-scoped` | `13.02` | `50282496` | `-4910137671593411349` | `44550` | Safe scoped backend is about `9.9%` faster than heap and about `78%` lower RSS. |

L2 standard-stat rows:

| Records | Mode | Median ms | GC median ms | GC max ms | Runs with GC | RSS bytes | Region op ms | Region objects | Region resets |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 5M | `heap-gc` | `1095.721` | `264.054` | `292.139` | `3/3` | `231555072` | `0.000` | `0` | `0` |
| 5M | `checked-rift` | `776.136` | `0.000` | `0.000` | `0/3` | `50036736` | `4.615` | `7390099` | `13` |
| 5M | `checked-region-scoped` | `1031.661` | `0.000` | `0.000` | `0/3` | `50462720` | `0.000` | `0` | `0` |
| 20M | `heap-gc` | `4781.079` | `1370.380` | `1519.456` | `3/3` | `231800832` | `0.000` | `0` | `0` |
| 20M | `checked-rift` | `3349.128` | `0.000` | `0.000` | `0/3` | `50036736` | `17.687` | `29559430` | `50` |
| 20M | `checked-region-scoped` | `4269.248` | `0.000` | `0.000` | `0/3` | `50528256` | `0.000` | `0` | `0` |

Heap-cap rows for 20M active-16:

| Mode | Heap cap | Status | L1 real s | RSS bytes | Checksum | Output count |
|---|---:|---|---:|---:|---:|---:|
| `heap-gc` | `512M` | completed | `14.74` | `231686144` | `-4910137671593411349` | `44550` |
| `heap-gc` | `384M` | completed | `14.66` | `231669760` | `-4910137671593411349` | `44550` |
| `heap-gc` | `256M` | completed | `14.72` | `231669760` | `-4910137671593411349` | `44550` |

Interpretation: active-16 `q17` is the strongest q17 case. At 20M, heap
spends about `28.7%` of L2 elapsed in timed GC; checked Rift removes that GC,
is about `33.1%` faster in L1, about `29.9%` faster in L2, and cuts RSS by
about `78%`. Heap caps down to `256M` complete, so this row is not a
fixed-memory failure case; the evidence is throughput, GC, and RSS.

## Next Work

- Use aggregate/join and q17 as the current retained-object GC-heavy dataflow
  case studies while continuing the real-input search for sessions, joins,
  timestamp dictionaries, transaction-local objects, graph epochs, and
  text/top-k candidates.
- If another Broom/Naiad-style shape is needed, implement shopper
  JOIN-SELECT-JOIN as a separate slice rather than mixing it into this q17
  checkpoint.
