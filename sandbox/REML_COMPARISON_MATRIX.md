# ReML / MLKit Lineage Comparison Matrix

Last updated: 2026-05-06 14:37 CEST

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

1. Run Tier 1 headline rows with 3-run medians and RSS capture.
2. Add Tier 2 ports only after Tier 1 is interpreted:
   `b-hut`, `nucleic`, `ray`, `logic`, `zebra`, `kbc`, `tsp`, `mpuz`.
3. Search for exact MLKit/ReML artifact/source provenance. If found, add
   MLKit `rg`, `rg-`, `r`, and MLton local reruns; if not, keep exact
   reproduction marked unavailable.
4. Fix or formally classify the ignored polymorphic heap-cell escape probe.
