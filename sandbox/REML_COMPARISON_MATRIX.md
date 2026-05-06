# ReML / MLKit Lineage Comparison Matrix

Last updated: 2026-05-06 23:45 CEST

Status: new Phase 6c evidence track. This file separates three evidence
classes:

- **paper-reported ReML/MLKit numbers** from Elsman 2023, not rerun locally;
- **exact artifact reproduction**, still open pending clean source/tool
  provenance;
- **Scala Native ReML-shaped ports**, newly scaffolded in
`sandbox/src/main/scala-next/ReMLRegionMatrix.scala`.

Default local ReML-shaped runs now include only `gc-heap`,
`region-scoped-rooted`, `checked-region-stream`, and
`checked-region-scoped`. Rootless/trusted lower-bound rows require
`RIFT_BENCH_INCLUDE_CONTROLS=1` or explicit `REML_MODES`.

## Exact Artifact Provenance

Current status: **partial source provenance found; exact paper reproduction not
yet run**.

Local inspection, 2026-05-06:

```text
cache path: /Users/siyaoliu/rift/cache/reml/mlkit
repository: https://github.com/melsman/mlkit.git
commit: 8561fe6ad949b84f83e8b78508b720ceccabe902
```

This repository contains many benchmark sources that overlap the paper table:

| Paper program family | Source candidates found in MLKit repo |
|---|---|
| `DLX` | `test/DLXSimulator.sml`, `test/DLXSimulator_smlnj.sml` |
| `b-hut` | `test/barnes-hut.mlb`, `test/barnes-hut/*.sml` |
| `fft` | `test/fft.sml`, `test/barry/fft.sml` |
| `fib37` | `test/kitfib35.sml`, `test/kitfib35_mlton.sml`, `test/barry/fib35.sml` |
| `life` | `test/life.sml`, `test/kitlife35u.sml`, `test/barry/life.sml` |
| `logic` | `test/logic.mlb`, `test/logic/*.sml` |
| `mandel` | `test/kitmandelbrot.sml`, `test/barry/mandelbrot.sml` |
| `mpuz` | `test/mpuz.sml`, `test/mpuz_smlnj.sml` |
| `msort` / `msort-r` | `test/msort.mlb`, `test/msort.sml`, `kitdemo/msort.mlb` |
| `nucleic` | `test/nucleic.mlb`, `test/nucleic/*.sml` |
| `prof` | `test/professor.sml`, `test/professor2.sml` |
| `ratio` | `test/ratio-regions.sml`, `test/ratio-regions_tp.sml` |
| `ray` | `test/ray.mlb`, `test/ray/*.sml` |
| `simple` | `test/kitsimple.sml`, `test/barry/simple.sml` |
| `tak` | `test/tak.sml`, `test/tak_smlnj.sml` |
| `tsp` | `test/tsp.sml`, `test/tsp_tp.sml` |
| `zern` | `test/zern.sml`, `test/zern_smlnj.sml` |

Open provenance gaps:

- The inspected commit is current MLKit HEAD, not yet pinned to the exact PLDI
  2023 ReML paper revision.
- Local `mlkit` and `mlton` executables are not installed, so no local exact
  timing/RSS/GC-count run has been performed yet.
- Docker was checked again at 2026-05-06 23:45 CEST. The Docker CLI exists,
  but the daemon is not running, so the draft containerized MLKit runner could
  not be executed.
- Full Git history/tags were fetched. The most relevant tags found are:

| Tag | Commit | Date | Note |
|---|---|---|---|
| `v4.7.4` | `855248bf` | 2023-10-01 | initial explicit region/effect annotation support per `NEWS.md` |
| `v4.7.5` | `5f0f811d` | 2023-10-10 | "ReML released as part of the distribution" |
| `v4.7.6` | `71c2630e` | 2023-11-16 | later datatype-unboxing release |

- The current public source has MLKit flags for region inference, no-GC region
  mode, tracing GC, and generational GC. It does not yet give us a validated
  mapping from the paper labels `rg`, `rg-`, and `r` to concrete command lines.

Source-inspected draft mapping, not yet paper-confirmed:

| Paper label | Draft command shape | Source evidence |
|---|---|---|
| `rg` | `mlkit <source>` | `KitX64.sml` turns on `garbage_collection`; region inference is enabled by default. |
| `rg-` | `mlkit -disable_spurious_type_variables <source>` | `RegInf.sml` defines this flag and says it matters when GC is enabled. |
| `r` | `mlkit -no_gc <source>` | `test/Makefile` labels `kittester ... -no_gc` as "MLKit with Regions". |
| `MLton` | `mlton <source>` | `src/Tools/Benchmark` supports MLton rows. |

`scripts/reml-mlkit-docker-bench-draft.sh` encodes this mapping for smoke and
provisional rows once an amd64 Docker daemon or host is available.
- The mapping from paper modes `rg`, `rg-`, and `r` to concrete MLKit command
  flags must be verified from the paper-era sources or author artifact before
  reporting exact reproduction.
- Several paper names may need benchmark-specific size/configuration checks
  (`fib37` versus available `fib35` sources, `lexgen`, `mlyacc`, `vliw`,
  `kbc`, and `zebra`).

Next exact-reproduction step:

1. Pin the MLKit revision or release used for the PLDI 2023 paper.
2. Install/build `mlkit` and `mlton`.
3. Run a small source-to-binary smoke for `msort`, `fft`, and `ratio`.
4. Add a tracked runbook with exact commands for `rg`, `rg-`, `r`, and MLton.
5. Only then compare raw wall-clock speed with Rift local rows.

Smoke scaffold:

```sh
cd /Users/siyaoliu/rift
MLKIT_TAG=v4.7.5 bash scripts/reml-mlkit-docker-smoke.sh
```

This uses an amd64 Ubuntu container. It is not yet run because Docker was
installed but the daemon was not running during the 2026-05-06 inspection.

Reference paper: Martin Elsman, "Garbage-Collection Safety for Region-Based
Type-Polymorphic Programs", PLDI 2023.

## Why This Track Exists

The ReML paper is not a stream-processing benchmark suite. It is important for
Rift because it tests the MLKit lineage: region inference, region
polymorphism, higher-order code, and tracing-GC safety when region values can
be hidden inside polymorphic values. That is a different comparison axis from
Broom/Yak/StreamFlex/DEBS/NEXMark.

Rift comparison claims must stay precise:

- do not claim raw cross-language wall-clock wins against ReML unless exact
  MLKit/ReML artifacts are run on the same machine;
- compare relative effects: region-vs-heap speedup, RSS reduction, GC
  reduction, and annotation/safety burden;
- treat Scala Native ports as methodology-shaped comparisons, not exact ReML
  reproduction.

## Port Closeness And Fairness Policy

The Scala Native ports should be as close as practical to the ReML/MLKit
program families, but they are not exact source translations yet. Use this
classification when comparing results:

| Port | Current closeness | Fairness caveat |
|---|---|---|
| `fib37` | Same recursive Fibonacci family, local default `n=37`. | MLKit source candidates include `fib35`; exact paper source/config still needs pinning. |
| `tak` | Same Takeuchi-recursion family. | Local default `(18,12,6)` must be checked against the paper source. |
| `mandel` | Same Mandelbrot numeric family. | Grid/iteration count are local; compare with MLKit and Scala Native Mandelbrot configs before cross-system claims. |
| `msort` / `msort-r` | Same linked-node sort/allocation family. | Local default `REML_LIST_SIZE=100000`; MLKit sources include benchmark-specific sizes. |
| `life` | Same Life/grid-update family. | Local row is array/compute-heavy and currently not a region allocation case study. |
| `fft` | Same complex-number FFT-style update family. | Current size is small; use mainly as RSS/tiny-allocation control until scaled or matched. |
| `ratio` | Same rational-number allocation/reduction family. | Local data generation/checksum is Scala Native-specific; compare relative ratios only. |

Default safe comparison modes are `gc-heap`, `region-scoped-rooted`,
`checked-region-stream`, and `checked-region-scoped`. Rootless/trusted rows are
available as lower-bound controls but are not safe user-facing ReML
comparators.

## Paper-Reported Benchmark Table

The table below transcribes the paper-reported Figure 9 data supplied in the
project discussion. It is tracked here as **paper-reported / not rerun**.

Columns:

- `rg`: ReML/MLKit region inference plus tracing GC with GC-safety refinement.
- `rg-`: variant without the spurious-type-variable refinement.
- `r`: pure region inference; useful lower bound but not the fair GC-safety
  anchor for Rift.
- `MLton`: optimizing Standard ML compiler baseline.

| Program | loc | fcns | inst | delta | rg s | rg- s | r s | MLton s | rg RSS MB | rg- RSS MB | r RSS MB | MLton RSS MB | rg GC # | rg- GC # |
|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| DLX | 2841 | 2/149 | 0/690 | yes | 0.14 | 0.15 | 0.12 | 0.40 | 8 | 8 | 7 | 31 | 3 | 3 |
| b-hut | 1245 | 2/140 | 0/459 | no | 0.67 | 0.70 | 0.63 | 0.14 | 5 | 5 | 169 | 3 | 471 | 471 |
| fft | 71 | 0/9 | 0/34 | no | 0.64 | 0.66 | 0.51 | 0.26 | 69 | 69 | 56 | 125 | 10 | 10 |
| fib37 | 7 | 0/1 | 0/0 | no | 0.98 | 0.98 | 0.21 | 0.85 | 3 | 3 | 3 | 1 | 1 | 1 |
| kbc | 679 | 1/90 | 0/249 | yes | 0.21 | 0.21 | 0.19 | 0.07 | 10 | 10 | 10 | 2 | 10 | 10 |
| lexgen | 1322 | 0/108 | 0/531 | no | 0.74 | 0.81 | 0.62 | 0.43 | 15 | 15 | 67 | 18 | 109 | 109 |
| life | 202 | 0/35 | 0/146 | no | 0.44 | 0.46 | 0.43 | 0.43 | 3 | 3 | 14 | 2 | 58 | 58 |
| logic | 351 | 0/22 | 0/806 | no | 0.63 | 0.64 | 0.43 | 0.13 | 4 | 4 | 251 | 2 | 1843 | 1843 |
| mandel | 62 | 0/5 | 0/0 | no | 0.53 | 0.53 | 0.38 | 0.31 | 3 | 3 | 3 | 1 | 1 | 1 |
| mlyacc | 7385 | 10/966 | 5/3256 | yes | 0.36 | 0.34 | 0.30 | 0.33 | 18 | 15 | 115 | 10 | 29 | 28 |
| mpuz | 124 | 0/13 | 0/44 | no | 0.68 | 0.66 | 0.46 | 0.27 | 3 | 3 | 3 | 1 | 2 | 2 |
| msort-r | 119 | 0/14 | 0/27 | no | 0.69 | 0.67 | 0.47 | 0.93 | 116 | 116 | 97 | 577 | 16 | 16 |
| msort | 113 | 0/13 | 0/22 | no | 0.89 | 0.89 | 0.54 | 0.93 | 131 | 131 | 375 | 421 | 26 | 26 |
| nucleic | 3215 | 1/40 | 475/1273 | no | 0.34 | 0.34 | 0.33 | 0.17 | 5 | 5 | 231 | 3 | 645 | 645 |
| prof | 282 | 0/57 | 0/99 | no | 0.49 | 0.48 | 0.38 | 0.42 | 4 | 4 | 11 | 1 | 263 | 263 |
| ratio | 620 | 0/54 | 0/848 | no | 1.71 | 1.67 | 1.33 | 0.48 | 16 | 16 | 36 | 46 | 14 | 14 |
| ray | 529 | 1/48 | 0/120 | no | 0.69 | 0.67 | 0.64 | 0.25 | 14 | 14 | 14 | 15 | 12 | 12 |
| simple | 1053 | 15/327 | 0/448 | yes | 0.19 | 0.16 | 0.13 | 0.28 | 5 | 5 | 4 | 8 | 4 | 4 |
| tak | 12 | 0/2 | 0/0 | no | 2.09 | 2.04 | 0.55 | 2.12 | 3 | 3 | 3 | 1 | 1 | 1 |
| tsp | 493 | 0/26 | 0/19 | no | 0.14 | 0.14 | 0.11 | 0.14 | 11 | 11 | 6 | 11 | 7 | 7 |
| vliw | 3681 | 5/563 | 4/2133 | yes | 0.78 | 0.73 | 0.56 | 0.30 | 14 | 14 | 44 | 9 | 15 | 15 |
| zebra | 313 | 2/50 | 0/288 | yes | 1.58 | 1.58 | 1.15 | 0.45 | 3 | 3 | 122 | 1 | 1066 | 1504 |
| zern | 605 | 3/103 | 0/34 | yes | 0.80 | 0.80 | 0.52 | 0.30 | 6 | 5 | 5 | 11 | 4503 | 4503 |

## Local Scala Native ReML-Shaped Matrix

New local benchmark entrypoints:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh sandbox/run_reml_region_matrix.sh
```

Default Tier 1 workloads:

| Workload | Port status | Purpose |
|---|---|---|
| `fib37` | implemented | compute-heavy control; regions should not claim a memory win |
| `tak` | implemented | recursive compute-heavy control |
| `mandel` | implemented | numeric compute control |
| `msort` | implemented | linked-node allocation plus shared sort/checksum traversal |
| `msort-r` | implemented | reverse-input linked-node variant |
| `life` | implemented | grid/array update control |
| `fft` | implemented | complex-object array update control |
| `ratio` | implemented | retained rational-object allocation/traversal |

Default modes:

- `gc-heap`
- `region-scoped-rooted`
- `region-scoped-rootless`
- `region-hp-rootless`
- `region-stream-rootless`
- `checked-region-stream`
- `checked-region-scoped`

Smoke validation, 2026-05-06:

```sh
REML_OUTPUT_DIR=/tmp/reml-tier1-smoke \
REML_WORKLOADS="fib37 tak mandel msort msort-r life fft ratio" \
REML_MODES="gc-heap checked-region-stream checked-region-scoped" \
REML_WARMUPS=0 \
REML_BENCHMARK_RUNS=1 \
REML_LIST_SIZE=1000 \
REML_RATIO_COUNT=5000 \
REML_FFT_SIZE=1024 \
REML_MANDEL_SIZE=32 \
REML_LIFE_SIZE=32 \
REML_LIFE_STEPS=4 \
zsh sandbox/run_reml_region_matrix.sh
```

Result: native binary linked and all Tier 1 reduced-size smoke rows matched
checksums across the listed modes. These are smoke rows only; do not use them
as performance claims.

### Tier 1 Headline Rows, 2026-05-06

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
REML_OUTPUT_DIR=/Users/siyaoliu/rift/cache/reml-tier1-headline-2026-05-06 \
REML_BENCHMARK_RUNS=3 \
REML_WARMUPS=1 \
REML_WORKLOADS="fib37 tak mandel msort msort-r life fft ratio" \
REML_MODES="gc-heap region-scoped-rooted checked-region-stream checked-region-scoped" \
zsh sandbox/run_reml_region_matrix.sh
```

All checksums matched. These are **Scala Native port medians**, not exact
MLKit/ReML artifact reruns.

| Workload | Mode | Median ms | GC ms | Max GC ms | Runs with GC | RSS bytes | Rift objects |
|---|---|---:|---:|---:|---:|---:|---:|
| `fib37` | `gc-heap` | `132.735` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `fib37` | `region-scoped-rooted` | `147.083` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `fib37` | `checked-region-stream` | `126.017` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `fib37` | `checked-region-scoped` | `145.884` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `tak` | `gc-heap` | `0.188` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `tak` | `checked-region-stream` | `0.180` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `mandel` | `gc-heap` | `3.462` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `mandel` | `checked-region-stream` | `3.297` | `0.000` | `0.000` | 0 | `3964928` | 0 |
| `msort` | `gc-heap` | `124.983` | `27.180` | `27.238` | 3 | `21381120` | 0 |
| `msort` | `region-scoped-rooted` | `115.653` | `16.399` | `17.093` | 3 | `10403840` | 0 |
| `msort` | `checked-region-stream` | `104.358` | `6.063` | `6.071` | 3 | `10371072` | `100000` |
| `msort` | `checked-region-scoped` | `115.356` | `16.531` | `17.117` | 3 | `10403840` | 0 |
| `msort-r` | `gc-heap` | `126.163` | `27.280` | `27.459` | 3 | `39223296` | 0 |
| `msort-r` | `region-scoped-rooted` | `115.421` | `16.964` | `17.071` | 3 | `10420224` | 0 |
| `msort-r` | `checked-region-stream` | `104.929` | `6.005` | `6.014` | 3 | `10371072` | `100000` |
| `msort-r` | `checked-region-scoped` | `114.716` | `16.021` | `16.799` | 3 | `10420224` | 0 |
| `life` | `gc-heap` | `36.684` | `0.000` | `0.000` | 0 | `4243456` | 0 |
| `life` | `checked-region-scoped` | `35.563` | `0.000` | `0.000` | 0 | `4243456` | 0 |
| `fft` | `gc-heap` | `0.823` | `0.000` | `0.441` | 1 | `7995392` | 0 |
| `fft` | `checked-region-stream` | `0.800` | `0.000` | `0.000` | 0 | `5160960` | `49151` |
| `fft` | `checked-region-scoped` | `0.764` | `0.000` | `0.000` | 0 | `5324800` | 0 |
| `ratio` | `gc-heap` | `51.302` | `3.191` | `12.583` | 2 | `43958272` | 0 |
| `ratio` | `region-scoped-rooted` | `50.729` | `0.073` | `0.075` | 3 | `16056320` | 0 |
| `ratio` | `checked-region-stream` | `49.681` | `0.000` | `0.000` | 0 | `15745024` | `500001` |
| `ratio` | `checked-region-scoped` | `48.929` | `0.000` | `0.000` | 0 | `16023552` | 0 |

Interpretation:

- `msort` and `msort-r` are the clearest Tier 1 local allocation wins:
  checked stream is about `16-17%` faster than heap, reduces timed GC from
  about `27 ms` to about `6 ms`, and cuts RSS by roughly `2-4x`.
- `ratio` is a smaller throughput win but a strong RSS/GC row: checked rows
  remove timed GC and cut RSS from about `44 MB` to about `16 MB`.
- `fft` is too small for a headline timing claim, but it shows lower RSS in
  checked rows.
- `fib37`, `tak`, `mandel`, and `life` are mostly compute/control rows; small
  elapsed differences should not be presented as memory-management evidence.
- These rows strengthen the ReML-lineage comparison axis, but they do not
  support a raw "Rift beats ReML" claim until exact MLKit/ReML artifacts are
  run locally.

## ReML-Inspired Safety Probes

Added compiler probes in `RiftRegionCheckedCompilerTest`:

| Probe | Status | Meaning |
|---|---|---|
| polymorphic local consumer of region value | passes | higher-order local use is allowed when it does not escape |
| polymorphic identity returning region value | rejected | region value cannot leave the scoped region |
| polymorphic region object storing unrooted heap value | rejected | checked region object cannot hold unrooted heap metadata |
| heap generic `Cell[Box^{region}]` retained in durable/static heap state | rejected | current checker preserves the region capture for this ReML-style erased generic escape |
| generic cell widened to `AnyRef` and retained durably | rejected | widening does not erase the tracked region capture at the retention site |
| heap array storing region-captured values and retained durably | rejected | mutable heap containers cannot hide region values in durable/static state |
| escaping closure hiding generic region value | rejected | closures cannot hide generic region values in durable/static state |

The former ignored probe is now active and passing. This is compiler-probe
evidence for durable/static retention, not a full arbitrary heap-alias proof.
Do not make rootless checked safety claims until that broader policy is either
proved or explicitly ruled out by API design.

## Next Steps

1. Pin exact MLKit/ReML artifact provenance and run a small MLKit smoke once
   Docker or host `mlkit`/`mlton` is available.
2. Add Tier 2 ports only after Tier 1 is interpreted:
   `b-hut`, `nucleic`, `ray`, `logic`, `zebra`, `kbc`, `tsp`, `mpuz`.
3. Search for exact MLKit/ReML artifact/source provenance. If found, add
   MLKit `rg`, `rg-`, `r`, and MLton local reruns; if not, keep exact
   reproduction marked unavailable.
4. Match or document default input/config differences for `tak`, `mandel`,
   `msort`, `fft`, and `life` before using those rows in a paper table.
