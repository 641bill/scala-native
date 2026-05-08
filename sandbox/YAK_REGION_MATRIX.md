# Yak Region Matrix

Date: 2026-04-25
Last updated: 2026-05-08 19:35 CEST

Status: Yak-style methodology reproduction harness with validated smoke,
default median, pressure median, external-sort-shaped median, top-word/filter
median, GraphChi-style subinterval median, runtime-safety proxy, and
promotion/escape proxy runs. A first real-input graph replay row is now
implemented with SNAP Twitter ego-network and LiveJournal edges. The latest
LiveJournal follow-up separates checked topology from checked backend:
whole-run regions, per-epoch regions, and page-token regions are all measured
over scoped and streaming backends. Direct checked epoch now also covers the
grouped-sort array topology. These rows are local Yak-shaped probes, not exact
Yak/GraphChi reproduction.

The harness is implemented in
`sandbox/src/main/scala-next/YakRegionMatrix.scala` and run with
`sandbox/run_yak_region_instrumented_matrix.sh`.

Reporting note: raw commands and raw logs still use script labels such as
`heap`, `improved-safezone`, `rift-hp`, and `rift-streaming`. Summary rows
should use the canonical names from `docs/MEMORY_MODE_TAXONOMY.md`:
`gc-heap`, `region-scoped-rooted`, `region-hp-rootless`,
`region-stream-rootless`, `checked-region-stream`, `checked-region-scoped`,
and `checked-page-token`. Older historical tables in this source pack may still
quote legacy labels as provenance; new rollups should not.

## Benchmark Intent

This is not an exact Yak artifact reproduction. Yak evaluated distributed
Hyracks, Hadoop, and GraphChi workloads on the JVM. This local Scala Native
harness reproduces the key comparison shape:

- durable control metadata stays on the GC heap;
- high-volume data-path objects are allocated per epoch;
- epoch-local data dies together at the epoch boundary;
- rare data objects may escape to durable control state through the trusted
  `RiftRegion.RuntimeEpoch` escape/promotion API;
- heap, SafeZone, and Rift variants run the same logical program.

The current local workloads are:

- `wordcount`: durable heap dictionary counters, epoch-local token objects.
- `graphstep`: durable heap vertex state, epoch-local graph update messages.
- `sort`: external-sort-shaped grouped sort. Durable heap group totals remain
  live across epochs; each epoch allocates ordinary record objects, sorts them
  by key, and folds grouped sums into the durable totals.
- `topword`: Hadoop-like map/filter/in-map-combine/top-word shape. Durable
  heap counters and reusable combiner arrays remain control state; each epoch
  allocates ordinary word records, filters them, combines counts, and records
  the top word for the task.
- `graphchi`: GraphChi-like subinterval update shape. Durable vertex values
  stay on the heap; each subinterval allocates ordinary edge-update objects,
  applies them to the current vertex interval, and releases the subinterval
  region.
- `graphreal`: real graph edge replay. The input edge list is loaded into
  primitive heap/control arrays, then real source/destination pairs are
  replayed as epoch-local `EdgeUpdate` objects. This keeps the Yak
  data/control split but does not implement GraphChi scheduling.
- `promotion`: durable heap counters plus rare retained data objects. Region
  escape handling is routed through `RiftRegion.RuntimeEpoch`, which owns
  barrier checks, remembered-reference counts, and promoted-object counts.

The stream/data objects are ordinary Scala objects. Heap mode allocates them
with `new`; SafeZone mode allocates them in Scala Native SafeZone; Rift modes
allocate them with `region.alloc`.

The `yak-runtime` mode is a local runtime-safety proxy. It uses a reusable Rift
streaming region behind a dynamic epoch object with lifecycle checks. This
models the performance envelope of a Yak-like runtime-managed epoch discipline
on Scala Native. The `promotion` workload adds a local proxy for Yak's
write-barrier/escaping-object mechanism through the Rift memory API, but it
still does not implement real JVM-style object movement, remote stack scanning,
or a distributed runtime.

## Default Configuration

| Environment variable | Default |
|---|---:|
| `YAK_EPOCHS` | `20` |
| `YAK_RECORDS_PER_EPOCH` | `100000` |
| `YAK_KEY_SPACE` | `65536` |
| `YAK_VERTICES` | `100000` |
| `YAK_MESSAGES_PER_EPOCH` | `100000` |
| `YAK_SORT_RECORDS_PER_EPOCH` | `20000` |
| `YAK_GRAPHCHI_SUBINTERVALS` | `16` |
| `YAK_GRAPHCHI_EDGES_PER_SUBINTERVAL` | `5000` |
| `YAK_GRAPH_INPUT` | empty |
| `YAK_GRAPH_INPUT_EDGES` | `1000000` |
| `YAK_GRAPH_INPUT_VERTICES` | `YAK_VERTICES` |
| `YAK_GRAPH_INPUT_EDGES_PER_EPOCH` | `YAK_MESSAGES_PER_EPOCH` |
| `YAK_ESCAPE_MODULO` | `1000` |
| `YAK_SCRATCH_SLOTS` | `128` |
| `YAK_WARMUPS` | `1` |
| `YAK_BENCHMARK_RUNS` | `3` |

Use `YAK_WORKLOAD=wordcount`, `graphstep`, `sort`, `topword`, `graphchi`,
`graphreal`, `promotion`, or `all`.

## Commands

Compile:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

Smoke:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Local median:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

## Result Status

Compile, smoke, default local medians, epoch-pressure runs, grouped-sort,
top-word/filter, GraphChi-style pressure runs, runtime-proxy runs,
promotion/escape proxy pressure runs, real-input graph replay rows, checked
page-token LiveJournal `graphreal` rows, and explicit checked topology
LiveJournal rows have been recorded. These are local Yak-style methodology
numbers, not an exact Yak artifact reproduction.

## SNAP Twitter Ego `graphreal` Row

Date: 2026-05-08

Input:

`/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/twitter_combined.txt.gz`

This is the SNAP Twitter ego-network combined edge list. It is much smaller
than Yak's original Twitter-2010 / SampleTwitter graph inputs, so this row is
best treated as a real-input smoke/regression path for the Yak-style graph
operator. It proves that the harness can consume real edge files; it is not yet
a flagship GC-heavy row.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="heap improved-safezone rift-hp rift-streaming yak-runtime" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/twitter_combined.txt.gz \
YAK_GRAPH_INPUT_EDGES=1000000 \
YAK_GRAPH_INPUT_VERTICES=100000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=100000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-graphreal-twitter-1m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | `31.202` | `5.267` | `0.000` | `1000000` | `0 / 0 / 0` | `74792960` |
| `region-scoped-rooted` | `23.919` | `0.000` | `0.000` | `1000000` | `0 / 0 / 0` | `42254336` |
| `region-hp-rootless` | `24.383` | `0.000` | `0.047` | `1000000` | `10 / 10 / 0` | `42205184` |
| `region-stream-rootless` | `24.315` | `0.000` | `0.046` | `1000000` | `1 / 1 / 9` | `42188800` |
| `yak-runtime-proxy` internal control | `27.566` | `0.000` | `0.044` | `1000000` | `1 / 1 / 10` | `42188800` |

Interpretation:

- This is the first real-input Yak-shaped row in the repo. It uses real graph
  edge pairs but keeps the existing local single-process Yak methodology.
- Region rows beat heap by about 22%-23% and cut RSS by about 32 MB at 1M
  replayed edge-update objects. Heap reports `5.267 ms` median timed GC.
- `region-scoped-rooted` is fastest, with `region-hp-rootless` and
  `region-stream-rootless` close. This supports the project direction of
  checked stream regions over the best backend rather than "Rift-native
  backend only."
- The row is not Yak-scale. Next real-input steps are larger SNAP LiveJournal,
  full SNAP Twitter-2010 if disk/time allow, StackOverflow text for top-word,
  or a larger web/text corpus for external sort/wordcount.

## SNAP Twitter Ego `graphreal` 2M Follow-Up

Date: 2026-05-08

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="heap improved-safezone rift-hp rift-streaming yak-runtime" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/twitter_combined.txt.gz \
YAK_GRAPH_INPUT_EDGES=2000000 \
YAK_GRAPH_INPUT_VERTICES=100000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=200000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-graphreal-twitter-2m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | `63.454` | `0.000` | `0.000` | `2000000` | `0 / 0 / 0` | `172523520` |
| `region-scoped-rooted` | `47.967` | `0.000` | `0.000` | `2000000` | `0 / 0 / 0` | `65421312` |
| `region-hp-rootless` | `48.474` | `0.000` | `0.102` | `2000000` | `10 / 10 / 0` | `65339392` |
| `region-stream-rootless` | `49.557` | `0.000` | `0.101` | `2000000` | `1 / 1 / 9` | `65323008` |
| `yak-runtime-proxy` internal control | `54.897` | `0.000` | `0.100` | `2000000` | `1 / 1 / 10` | `65323008` |

Interpretation:

- The 2M follow-up keeps the elapsed/RSS direction: `region-scoped-rooted`,
  `region-hp-rootless`, and `region-stream-rootless` beat `gc-heap` by about
  22%-24%, and region rows use about `65 MB` RSS versus `gc-heap` `173 MB`.
- Heap median GC is zero at 2M because only one of the three timed heap runs
  collected (`16.857 ms`). Report this as a throughput/RSS/tail row, not a
  median-GC-heavy row.
- The 1M row remains the clearer timed-GC-share checkpoint; the 2M row
  strengthens the scale/RSS direction and exposes a heap GC tail.

## SNAP LiveJournal `graphreal` Rows

Date: 2026-05-08

Input:

`/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz`

This is the SNAP LiveJournal directed social graph: `68993777` edge-list lines,
`259619239` compressed bytes, SHA-256
`d7bcd5a87b88c896c35fdb9611e804c3f4033c39b58c4c9ea3ba53c680d516d8`.
It is much closer to Yak/GraphChi-scale than the Twitter ego graph, although
the local harness still replays a bounded prefix as single-process epoch-local
`EdgeUpdate` objects rather than running exact GraphChi/Yak.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="gc-heap region-scoped-rooted region-hp-rootless region-stream-rootless checked-region-stream checked-region-scoped" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz \
YAK_GRAPH_INPUT_EDGES=5000000 \
YAK_GRAPH_INPUT_VERTICES=1000000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=500000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-checked-livejournal-5m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="gc-heap region-scoped-rooted region-hp-rootless region-stream-rootless checked-region-stream checked-region-scoped" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz \
YAK_GRAPH_INPUT_EDGES=10000000 \
YAK_GRAPH_INPUT_VERTICES=1000000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=1000000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-checked-livejournal-10m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="gc-heap region-scoped-rooted region-stream-rootless checked-region-scoped checked-region-stream" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz \
YAK_GRAPH_INPUT_EDGES=50000000 \
YAK_GRAPH_INPUT_VERTICES=1000000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=5000000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-checked-livejournal-50m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

5M rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | `173.832` | `33.456` | `0.000` | `5000000` | `0 / 0 / 0` | `322699264` |
| `region-scoped-rooted` | `139.264` | `0.000` | `0.000` | `5000000` | `0 / 0 / 0` | `190578688` |
| `region-hp-rootless` | `145.163` | `0.000` | `0.318` | `5000000` | `10 / 10 / 0` | `190545920` |
| `region-stream-rootless` | `130.716` | `0.000` | `0.248` | `5000000` | `1 / 1 / 9` | `190513152` |
| `checked-region-stream` | `150.667` | `0.000` | `0.268` | `5000000` | `11 / 11 / 0` | `190562304` |
| `checked-region-scoped` | `146.845` | `0.000` | `0.000` | `5000000` | `0 / 0 / 0` | `190545920` |

10M rows:

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | `319.560` | `51.987` | `0.000` | `10000000` | `0 / 0 / 0` | `605569024` |
| `region-scoped-rooted` | `249.543` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328597504` |
| `region-hp-rootless` | `260.027` | `0.000` | `0.569` | `10000000` | `10 / 10 / 0` | `328564736` |
| `region-stream-rootless` | `257.299` | `0.000` | `0.538` | `10000000` | `1 / 1 / 9` | `328531968` |
| `checked-region-stream` | `286.336` | `0.000` | `0.562` | `10000000` | `11 / 11 / 0` | `328581120` |
| `checked-region-scoped` | `272.072` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328597504` |

50M rows:

| Mode | Operator/path | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | heap list | `1743.673` | `307.992` | `0.000` | `50000000` | `0 / 0 / 0` | `2759245824` |
| `region-scoped-rooted` | SafeZone scoped allocator | `1262.347` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534967808` |
| `region-stream-rootless` | trusted streaming reset | `1401.406` | `0.000` | `25.217` | `50000000` | `1 / 1 / 9` | `1534738432` |
| `checked-region-scoped` | checked page-token over scoped backend | `1436.514` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534967808` |
| `checked-region-stream` | checked page-token over streaming backend | `1575.633` | `0.000` | `28.075` | `50000000` | `11 / 11 / 0` | `1534803968` |

## SNAP LiveJournal `graphreal` Checked Topology Follow-Up

Date: 2026-05-08

This follow-up answers a design question raised after the first checked
LiveJournal rows: checked regions should not mean "always use page-token."
The programmer-visible topology can be whole-run, epoch, window, or page; Rift
should check the topology and lower it to the best backend/operator available.
For Yak-style graph replay, the natural lifetime is one epoch of edge-update
objects. Page-token is safe but over-general for this workload.

10M command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="gc-heap region-scoped-rooted region-stream-rootless checked-whole-run-scoped checked-epoch-scoped checked-page-token-scoped checked-whole-run-stream checked-epoch-stream checked-page-token-stream" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz \
YAK_GRAPH_INPUT_EDGES=10000000 \
YAK_GRAPH_INPUT_VERTICES=1000000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=1000000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-topology-livejournal-10m \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

50M command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 \
YAK_WORKLOAD=graphreal \
YAK_MODES="gc-heap region-scoped-rooted region-stream-rootless checked-whole-run-scoped checked-epoch-scoped checked-page-token-scoped checked-whole-run-stream checked-epoch-stream checked-page-token-stream" \
YAK_GRAPH_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/yak/snap/soc-LiveJournal1.txt.gz \
YAK_GRAPH_INPUT_EDGES=50000000 \
YAK_GRAPH_INPUT_VERTICES=1000000 \
YAK_GRAPH_INPUT_EDGES_PER_EPOCH=5000000 \
YAK_EPOCHS=10 \
YAK_BENCHMARK_RUNS=3 \
YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-topology-livejournal-50m \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Raw summaries were copied to:

- `/Users/siyaoliu/rift/cache/yak-topology-livejournal-10m-2026-05-08/summary.tsv`
- `/Users/siyaoliu/rift/cache/yak-topology-livejournal-50m-2026-05-08/summary.tsv`
- `/Users/siyaoliu/rift/cache/yak-epoch-buffer-livejournal-10m-2026-05-08/summary.tsv`
- `/Users/siyaoliu/rift/cache/yak-epoch-buffer-livejournal-50m-2026-05-08/summary.tsv`
- `/Users/siyaoliu/rift/cache/yak-epoch-api-livejournal-10m-2026-05-08/summary.tsv`

10M topology rows:

| Mode | Backend | Topology/operator | Reclaim boundary | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | GC | `342.083` | `53.743` | `0.000` | `10000000` | `0 / 0 / 0` | `605487104` |
| `region-scoped-rooted` | SafeZone improved 32K | per-epoch scoped list | epoch close | `265.013` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328564736` |
| `region-stream-rootless` | Rift streaming | per-epoch reset list | epoch reset | `271.871` | `0.000` | `0.647` | `10000000` | `1 / 1 / 9` | `328466432` |
| `checked-whole-run-scoped` | checked scoped | whole-run checked list | final close only | `230.896` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `617185280` |
| `checked-epoch-scoped` | checked scoped | per-epoch checked list | epoch close | `235.370` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328564736` |
| `checked-page-token-scoped` | checked scoped | page-token append/window | bucket close | `293.112` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328564736` |
| `checked-whole-run-stream` | checked stream | whole-run checked list | final close only | `252.535` | `0.000` | `15.217` | `10000000` | `1 / 1 / 0` | `616775680` |
| `checked-epoch-stream` | checked stream | per-epoch checked list | epoch reset | `227.108` | `0.000` | `0.673` | `10000000` | `1 / 1 / 10` | `328499200` |
| `checked-page-token-stream` | checked stream | page-token append/window | bucket close | `306.171` | `0.000` | `0.732` | `10000000` | `11 / 11 / 0` | `328531968` |

50M topology rows:

| Mode | Backend | Topology/operator | Reclaim boundary | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | GC | `1618.105` | `273.410` | `0.000` | `50000000` | `0 / 0 / 0` | `2761261056` |
| `region-scoped-rooted` | SafeZone improved 32K | per-epoch scoped list | epoch close | `1256.538` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534902272` |
| `region-stream-rootless` | Rift streaming | per-epoch reset list | epoch reset | `1345.479` | `0.000` | `24.384` | `50000000` | `1 / 1 / 9` | `1534705664` |
| `checked-whole-run-scoped` | checked scoped | whole-run checked list | final close only | `1048.751` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `2977742848` |
| `checked-epoch-scoped` | checked scoped | per-epoch checked list | epoch close | `1069.241` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534918656` |
| `checked-page-token-scoped` | checked scoped | page-token append/window | bucket close | `1403.878` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534902272` |
| `checked-whole-run-stream` | checked stream | whole-run checked list | final close only | `1288.801` | `0.000` | `117.637` | `50000000` | `1 / 1 / 0` | `2976104448` |
| `checked-epoch-stream` | checked stream | per-epoch checked list | epoch reset | `1113.261` | `0.000` | `25.341` | `50000000` | `1 / 1 / 10` | `1534705664` |
| `checked-page-token-stream` | checked stream | page-token append/window | bucket close | `1457.811` | `0.000` | `22.642` | `50000000` | `11 / 11 / 0` | `1534771200` |

Reusable `EpochBuffer` follow-up:

After the topology result, the benchmark was wired to the existing reusable
`EpochBuffer` operator through two new modes:

- `checked-epoch-buffer-scoped`
- `checked-epoch-buffer-stream`

`EpochBuffer` is the current general append/drain epoch operator:
ordinary records extend `StreamAppendNode`, allocation uses
`epochBufferOpenRegionFor` + `allocOpen`, appends use `appendEpochBuffer`, and
close drains with `closeEpochBufferWithCursor`.

10M reusable epoch rows:

| Mode | Backend | Topology/operator | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | `316.537` | `50.849` | `0.000` | `10000000` | `0 / 0 / 0` | `605487104` |
| `checked-epoch-scoped` | checked scoped | benchmark-local linked epoch | `204.196` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328564736` |
| `checked-epoch-buffer-scoped` | checked scoped | reusable `EpochBuffer` | `274.105` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328564736` |
| `checked-epoch-stream` | checked stream | benchmark-local linked epoch | `210.622` | `0.000` | `0.502` | `10000000` | `1 / 1 / 10` | `328482816` |
| `checked-epoch-buffer-stream` | checked stream | reusable `EpochBuffer` | `289.345` | `0.000` | `0.570` | `10000000` | `11 / 11 / 0` | `328499200` |

50M reusable epoch rows:

| Mode | Backend | Topology/operator | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | `1514.313` | `279.226` | `0.000` | `50000000` | `0 / 0 / 0` | `2761244672` |
| `checked-epoch-scoped` | checked scoped | benchmark-local linked epoch | `1022.643` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534918656` |
| `checked-epoch-buffer-scoped` | checked scoped | reusable `EpochBuffer` | `1343.071` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534902272` |
| `checked-epoch-stream` | checked stream | benchmark-local linked epoch | `1218.898` | `0.000` | `27.426` | `50000000` | `1 / 1 / 10` | `1534197760` |
| `checked-epoch-buffer-stream` | checked stream | reusable `EpochBuffer` | `1462.975` | `0.000` | `24.741` | `50000000` | `11 / 11 / 0` | `1534738432` |

10M API-backed checked epoch rerun:

| Mode | Backend | Topology/operator | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | `317.779` | `48.532` | `0.000` | `10000000` | `0 / 0 / 0` | `605519872` |
| `checked-epoch-scoped` | checked scoped | `RiftRegion.epoch` linked epoch | `212.691` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328581120` |
| `checked-epoch-stream` | checked stream | `RiftRegion.epoch` linked epoch | `229.532` | `0.000` | `0.659` | `10000000` | `1 / 1 / 10` | `328515584` |
| `checked-epoch-buffer-scoped` | checked scoped | reusable `EpochBuffer` | `285.605` | `0.000` | `0.000` | `10000000` | `0 / 0 / 0` | `328630272` |
| `checked-epoch-buffer-stream` | checked stream | reusable `EpochBuffer` | `287.201` | `0.000` | `0.628` | `10000000` | `11 / 11 / 0` | `328548352` |

50M API-backed checked epoch rerun, same topology as the 50M topology table
(`YAK_EPOCHS=10`, `YAK_GRAPH_INPUT_EDGES_PER_EPOCH=5000000`):

Raw summary:
`/Users/siyaoliu/rift/cache/yak-epoch-api-livejournal-50m-10x5m-2026-05-08/summary.tsv`.

| Mode | Backend | Topology/operator | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | Immix | heap linked list | `1604.811` | `288.801` | `0.000` | `50000000` | `0 / 0 / 0` | `2759835648` |
| `checked-epoch-scoped` | checked scoped | `RiftRegion.epoch` linked epoch | `1055.958` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1535016960` |
| `checked-epoch-stream` | checked stream | `RiftRegion.epoch` linked epoch | `1101.001` | `0.000` | `24.094` | `50000000` | `1 / 1 / 10` | `1534263296` |
| `checked-epoch-buffer-scoped` | checked scoped | reusable `EpochBuffer` | `1361.752` | `0.000` | `0.000` | `50000000` | `0 / 0 / 0` | `1534984192` |
| `checked-epoch-buffer-stream` | checked stream | reusable `EpochBuffer` | `1437.983` | `0.000` | `23.850` | `50000000` | `11 / 11 / 0` | `1534787584` |

Non-comparable 50M topology-sensitivity row:
`/Users/siyaoliu/rift/cache/yak-epoch-api-livejournal-50m-2026-05-08/summary.tsv`
uses `YAK_EPOCHS=50` and `YAK_GRAPH_INPUT_EDGES_PER_EPOCH=1000000`.
It is useful as an epoch-granularity sensitivity row, but it should not be
compared directly with the 10 x 5M topology table above. Its checksum differs
because the workload uses the epoch index in the update formula.

Reusable checked epoch breadth rerun, 10M logical objects:

Raw summaries:
`/Users/siyaoliu/rift/cache/yak-reusable-epoch-wordcount-10m-2026-05-08/summary.tsv`,
`/Users/siyaoliu/rift/cache/yak-reusable-epoch-graphstep-10m-2026-05-08/summary.tsv`,
`/Users/siyaoliu/rift/cache/yak-reusable-epoch-topword-10m-2026-05-08/summary.tsv`,
and
`/Users/siyaoliu/rift/cache/yak-reusable-epoch-graphchi-10m-2026-05-08/summary.tsv`.

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical objects | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|
| `wordcount` | `gc-heap` | `270.952` | `51.582` | `0.000` | `10000000` | `75350016` |
| `wordcount` | `region-scoped-rooted` | `190.181` | `0.000` | `0.000` | `10000000` | `81281024` |
| `wordcount` | `region-stream-rootless` | `221.372` | `0.000` | `0.425` | `10000000` | `81231872` |
| `wordcount` | `checked-epoch-scoped` | `164.825` | `0.000` | `0.000` | `10000000` | `81281024` |
| `wordcount` | `checked-epoch-stream` | `176.695` | `0.000` | `0.411` | `10000000` | `81231872` |
| `graphstep` | `gc-heap` | `278.067` | `55.792` | `0.000` | `10000000` | `75333632` |
| `graphstep` | `region-scoped-rooted` | `207.784` | `0.000` | `0.000` | `10000000` | `83329024` |
| `graphstep` | `region-stream-rootless` | `236.936` | `0.000` | `0.590` | `10000000` | `83230720` |
| `graphstep` | `checked-epoch-scoped` | `181.341` | `0.000` | `0.000` | `10000000` | `83296256` |
| `graphstep` | `checked-epoch-stream` | `198.122` | `0.000` | `0.559` | `10000000` | `83230720` |
| `topword` | `gc-heap` | `314.482` | `46.220` | `0.000` | `10000000` | `75317248` |
| `topword` | `region-scoped-rooted` | `257.855` | `0.000` | `0.000` | `10000000` | `83279872` |
| `topword` | `region-stream-rootless` | `279.046` | `0.000` | `0.476` | `10000000` | `83214336` |
| `topword` | `checked-epoch-scoped` | `239.507` | `0.000` | `0.000` | `10000000` | `83279872` |
| `topword` | `checked-epoch-stream` | `248.299` | `0.000` | `0.484` | `10000000` | `83230720` |
| `graphchi` | `gc-heap` | `291.930` | `52.363` | `0.000` | `10000000` | `12713984` |
| `graphchi` | `region-scoped-rooted` | `221.187` | `0.000` | `0.000` | `10000000` | `13221888` |
| `graphchi` | `region-stream-rootless` | `256.171` | `0.000` | `0.496` | `10000000` | `13172736` |
| `graphchi` | `checked-epoch-scoped` | `195.816` | `0.000` | `0.000` | `10000000` | `13221888` |
| `graphchi` | `checked-epoch-stream` | `208.460` | `0.000` | `0.507` | `10000000` | `13172736` |

Interpretation:

- LiveJournal is now the best real-input Yak-style evidence in the repo. It
  has real graph provenance, millions of ordinary epoch-local `EdgeUpdate`
  objects, and material `gc-heap` GC/RSS pressure.
- At 5M, every region row beats `gc-heap` and cuts RSS by about `132 MB`.
  `checked-region-scoped` is `146.845 ms` versus `gc-heap` `173.832 ms`;
  `checked-region-stream` is `150.667 ms`.
- At 10M, `region-scoped-rooted` is fastest (`249.543 ms`), followed by
  `region-stream-rootless` (`257.299 ms`). The checked rows remain wins over
  heap: `checked-region-scoped` is `272.072 ms`, and
  `checked-region-stream` is `286.336 ms`, versus `gc-heap` `319.560 ms`.
- RSS is about `328.5 MB` for region rows at 10M versus `605.6 MB` for
  `gc-heap`. `gc-heap` median timed GC is `51.987 ms`.
- The first 50M page-token follow-up made the timing less short-run dominated.
  `gc-heap` grows
  to `1743.673 ms`, spends `307.992 ms` in timed GC, and reaches `2.76 GB`
  RSS. `region-scoped-rooted` is `1262.347 ms`, and the strongest checked row
  (`checked-region-scoped`) is `1436.514 ms`, with about `1.53 GB` RSS and no
  timed GC.
- The checked rows use the reusable page-token checked operator, so they are
  safe/operator-surface evidence, not just trusted/rootless lower-bound
  evidence. They still trail the fastest region rows because page-token
  append/cursor bookkeeping remains visible.
- The topology follow-up changes the checked story. The best safe checked
  topology for Yak is not page-token: `checked-epoch-scoped` is `1069.241 ms`
  at 50M with the low `1.53 GB` region RSS, while `checked-page-token-scoped`
  is `1403.878 ms`. `checked-whole-run-scoped` is fastest at `1048.751 ms`,
  but it reaches `2.98 GB` RSS because it does not reclaim until the end of
  the whole run. This is safe but not the intended epochal memory-management
  topology.
- This supports the user-facing design direction: expose `Rift.stream {
  epoch { ... }; window(...) { ... }; page { ... } }`, let programmers choose
  a logical lifetime topology, and let Rift check/lower it. Reporting should
  separate backend (`checked scoped` vs `checked stream`) from topology
  (`whole-run`, `epoch`, `page-token`).
- The reusable `EpochBuffer` rows are positive but not final: at 50M,
  `checked-epoch-buffer-scoped` still beats heap (`1343.071 ms` vs
  `1514.313 ms`) and keeps the low `1.53 GB` RSS, but it trails the
  benchmark-local linked epoch path (`1022.643 ms`). That means the checked
  epoch API shape is right, but the reusable implementation should get a
  lower-overhead linked-epoch lowering rather than relying on the current
  generic append-window cursor machinery.
- Follow-up API implementation: `RiftRegion.epoch { ... }` now exposes the
  direct checked linked-epoch topology through an `OpenStreamingRegion`. Yak
  `checked-epoch-*` modes route through this reusable API rather than a
  benchmark-only epoch loop. The 10M and apples-to-apples 50M API-backed
  LiveJournal reruns
  confirm the direction:
  `checked-epoch-scoped` is `212.691 ms` and `checked-epoch-stream` is
  `229.532 ms`, versus `gc-heap` `317.779 ms` and reusable
  `EpochBuffer` around `286 ms` at 10M; at 50M, API-backed
  `checked-epoch-scoped` is `1055.958 ms` with `1.53 GB` RSS versus
  `gc-heap` `1604.811 ms`, `288.801 ms` timed GC, and `2.76 GB` RSS.
  Validation after adding `epoch`,
  `epochBufferOpenRegionFor`, and the Yak `checked-epoch-buffer-*` modes:
  `sandbox3_next/compile` passed, `RiftRegionCheckedCompilerTest` passed
  `123/123`, `RiftRegionCheckedTest` passed `55/55`, and a 2-epoch SNAP
  Twitter `graphreal` smoke matched checksums across heap, checked epoch, and
  `EpochBuffer` modes.
- The same reusable direct epoch topology now covers local Yak-shaped
  `wordcount`, `graphstep`, `sort`, `topword`, and `graphchi` workloads.
  For `sort`, the checked row uses a region-owned array of region-owned
  `SortRecord` objects; it does not silently fall back to heap arrays.
  The 1M grouped-sort rerun is a modest CPU-bound win:
  `checked-epoch-stream` `230.000 ms`, `checked-epoch-scoped` `230.799 ms`,
  heap `235.554 ms`, and improved SafeZone `233.068 ms`.
- This is still not exact Yak/GraphChi artifact reproduction: there is no
  distributed runtime, no GraphChi scheduler, and no Yak JVM promotion/barrier
  machinery. It is a strong local prior-work-shaped real-input row.

Validation commands run on 2026-04-25 and 2026-04-26:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile

YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_OUTPUT_DIR=/tmp/yak-region-instrumented \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-region-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=sort YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=10 YAK_SORT_RECORDS_PER_EPOCH=100000 \
YAK_OUTPUT_DIR=/tmp/yak-sort-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=topword YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-topword-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=graphchi YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_GRAPHCHI_SUBINTERVALS=16 \
YAK_GRAPHCHI_EDGES_PER_SUBINTERVAL=15625 \
YAK_OUTPUT_DIR=/tmp/yak-graphchi-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_WORKLOAD=promotion YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_ESCAPE_MODULO=1000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-promotion-api-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

The smoke run rebuilt and native-linked the benchmark successfully. During the
sbt native-link setup the JVM emitted a code-cache warning, but the native
binary linked and all heap/SafeZone/Rift checksums matched.

## Native-Only Local 3-Run Median, Default Configuration

Configuration:

- `YAK_EPOCHS=20`
- `YAK_RECORDS_PER_EPOCH=100000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=100000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 51.961 | 12.062 | 0.000 | 2000000 | 65536 | 39272448 |
| wordcount | current SafeZone | 41.143 | 0.000 | 0.000 | 2000000 | 65536 | 42237952 |
| wordcount | improved SafeZone | 40.005 | 0.000 | 0.000 | 2000000 | 65536 | 42287104 |
| wordcount | Rift HPZone | 42.075 | 0.000 | 0.053 | 2000000 | 65536 | 42188800 |
| wordcount | Rift Streaming | 41.873 | 0.000 | 0.053 | 2000000 | 65536 | 42172416 |
| graphstep | heap | 56.235 | 9.283 | 0.000 | 2000000 | 100000 | 39272448 |
| graphstep | current SafeZone | 47.417 | 0.000 | 0.000 | 2000000 | 100000 | 42237952 |
| graphstep | improved SafeZone | 46.859 | 0.000 | 0.000 | 2000000 | 100000 | 42287104 |
| graphstep | Rift HPZone | 48.126 | 0.000 | 0.077 | 2000000 | 100000 | 42188800 |
| graphstep | Rift Streaming | 46.909 | 0.000 | 0.072 | 2000000 | 100000 | 42172416 |

Interpretation:

- This table predates adding the `sort`, `topword`, and `graphchi` workloads
  to `all`; it covers `wordcount` and `graphstep`.
- This reproduces the Yak control/data split locally: durable counters or vertex
  state remain on heap, while two million epoch-local data objects are allocated
  per workload.
- Heap pays measurable GC time. SafeZone and Rift remove the measured heap GC
  path for the epoch-local data objects.
- Rift beats heap on both workloads, but improved SafeZone is the fastest or
  effectively tied at this default scale.
- Rift operation time is small: below `0.1 ms` median per workload.

## External-Sort-Shaped Grouped Sort Median

Date: 2026-04-26

Configuration:

- `YAK_EPOCHS=10`
- `YAK_SORT_RECORDS_PER_EPOCH=100000`
- `YAK_KEY_SPACE=65536`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=sort YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=10 YAK_SORT_RECORDS_PER_EPOCH=100000 \
YAK_OUTPUT_DIR=/tmp/yak-sort-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 237.354 | 3.463 | 0.000 | 0 | 0 / 0 / 0 | 22446080 |
| current SafeZone | 234.644 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24674304 |
| improved SafeZone | 236.047 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24674304 |
| Rift HPZone | 227.393 | 0.283 | 0.047 | 1000000 | 10 / 10 / 0 | 24002560 |
| Rift Streaming | 232.658 | 0.307 | 0.030 | 1000000 | 1 / 1 / 9 | 24002560 |
| Yak-runtime proxy | 242.517 | 0.306 | 0.032 | 1000000 | 1 / 1 / 10 | 24002560 |

Interpretation:

- This is closer to Yak's external-sort/dataflow evaluation shape than the
  earlier wordcount-only path: high-volume record objects are epoch-local, but
  the durable grouped totals remain heap control state.
- Sorting dominates elapsed time, so heap GC is only `3.463 ms`. This is not a
  large-GC-pressure case.
- Rift HPZone is still fastest locally (`227.393 ms` vs heap `237.354 ms`),
  with small region-op overhead. The result is a modest same-program
  allocation-placement win, not a dramatic Yak-style GC win.
- `yak-runtime` is slower than raw Rift and heap here, which again shows that
  dynamic runtime discipline has visible cost when escape handling is not
  needed.

## Checked Direct Epoch Grouped Sort Rerun

Date: 2026-05-08

This rerun closes the earlier checked-sort gap. The checked sort path allocates
both the per-epoch array and its `SortRecord` objects in the epoch region:
`Array[SortRecord^{epoch}]^{epoch}`. It sorts and consumes the array before the
epoch closes, while durable grouped totals remain heap control state.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_EPOCHS=10 YAK_SORT_RECORDS_PER_EPOCH=100000 \
YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 YAK_WORKLOAD=sort \
YAK_MODES="gc-heap region-scoped-rooted region-stream-rootless checked-epoch-scoped checked-epoch-stream" \
YAK_OUTPUT_DIR=/Users/siyaoliu/rift/cache/yak-sort-epoch-api-1m-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Smoke:

```sh
YAK_EPOCHS=2 YAK_SORT_RECORDS_PER_EPOCH=1000 YAK_BENCHMARK_RUNS=1 \
YAK_WARMUPS=0 YAK_WORKLOAD=sort \
YAK_MODES="gc-heap checked-epoch-scoped checked-epoch-stream" \
YAK_OUTPUT_DIR=/tmp/yak-sort-checked-smoke-2026-05-08 \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Topology/API | Median elapsed ms | Median GC ms | Median Rift op ms | Region objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| `gc-heap` | heap array + heap records | 235.554 | 3.333 | 0.000 | 0 | 0 / 0 / 0 | 22708224 |
| `region-scoped-rooted` | SafeZone epoch array + records | 233.068 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24969216 |
| `region-stream-rootless` | trusted streaming epoch | 231.458 | 0.312 | 0.047 | 1000000 | 1 / 1 / 9 | 24313856 |
| `checked-epoch-scoped` | checked `RiftRegion.epoch` array + records | 230.799 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 24969216 |
| `checked-epoch-stream` | checked `RiftRegion.epoch` array + records | 230.000 | 0.000 | 0.247 | 1000010 | 1 / 1 / 10 | 24936448 |

Interpretation:

- Checked direct epoch now covers the grouped-sort array topology, so the Yak
  local methodology suite has checked epoch rows for `wordcount`, `graphstep`,
  `sort`, `topword`, `graphchi`, and `graphreal`.
- This is not a flagship GC result. Sort CPU dominates, and heap median timed
  GC is only `3.333 ms`.
- It is still useful coverage: checked direct epoch is correct, removes timed
  GC, and is slightly faster than heap and improved SafeZone in this rerun.
- The row validates a region-captured array shape that is likely useful beyond
  Yak: epoch-local arrays of region-owned objects sorted/processed before
  epoch close.

## Hadoop-Style Top-Word/Filter Median

Date: 2026-04-26

Configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_KEY_SPACE=65536`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=topword YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-topword-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 311.527 | 44.949 | 0.000 | 0 | 0 / 0 / 0 | 75038720 |
| current SafeZone | 326.680 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83116032 |
| improved SafeZone | 271.273 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83116032 |
| Rift HPZone | 266.478 | 0.000 | 0.416 | 10000000 | 40 / 40 / 0 | 82968576 |
| Rift Streaming | 262.980 | 0.000 | 0.401 | 10000000 | 1 / 1 / 39 | 82935808 |
| Yak-runtime proxy | 292.179 | 0.000 | 0.418 | 10000000 | 1 / 1 / 40 | 82935808 |

Interpretation:

- This is the closest current local model of Yak's Hadoop top-word/filter
  family: durable global counts and reusable combiner arrays remain heap
  control state; high-volume per-record stream objects are epoch-local.
- Rift Streaming beats heap by about `15.6%` and improved SafeZone by about
  `3.1%` in this local median, while removing `44.949 ms` of measured heap GC.
- Rift operation time stays low at about `0.4 ms` for ten million region
  objects. `yak-runtime` still costs more than raw Rift Streaming.

## GraphChi-Style Subinterval Median

Date: 2026-04-26

Configuration:

- `YAK_EPOCHS=40`
- `YAK_GRAPHCHI_SUBINTERVALS=16`
- `YAK_GRAPHCHI_EDGES_PER_SUBINTERVAL=15625`
- `YAK_VERTICES=100000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=graphchi YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_GRAPHCHI_SUBINTERVALS=16 \
YAK_GRAPHCHI_EDGES_PER_SUBINTERVAL=15625 \
YAK_OUTPUT_DIR=/tmp/yak-graphchi-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|
| heap | 302.599 | 58.498 | 0.000 | 0 | 0 / 0 / 0 | 12468224 |
| current SafeZone | 232.621 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 13008896 |
| improved SafeZone | 228.252 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 12959744 |
| Rift HPZone | 237.091 | 0.000 | 0.430 | 10000000 | 640 / 640 / 0 | 12943360 |
| Rift Streaming | 236.388 | 0.000 | 0.400 | 10000000 | 1 / 1 / 639 | 12910592 |
| Yak-runtime proxy | 270.159 | 0.000 | 0.411 | 10000000 | 1 / 1 / 640 | 12910592 |

Interpretation:

- This is the closest current local model of Yak's GraphChi family: durable
  vertex state stays on heap, while per-subinterval edge-update objects are
  region-local.
- Rift Streaming beats heap by about `21.9%` and removes `58.498 ms` of
  measured heap GC, but improved SafeZone is still faster (`228.252 ms`).
- The workload opens/closes or resets 640 subinterval regions. Region operation
  time remains about `0.4 ms`, so the remaining gap to improved SafeZone is not
  explained by close/reset accounting alone.
- This is still not GraphChi/Yak artifact evidence; it is a local methodology
  probe.

## Epoch-Pressure 3-Run Median

Configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_KEY_SPACE=65536`
- `YAK_VERTICES=100000`
- `YAK_MESSAGES_PER_EPOCH=250000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 240.541 | 35.225 | 0.000 | 10000000 | 65536 | 75071488 |
| wordcount | current SafeZone | 237.253 | 0.000 | 0.000 | 10000000 | 65536 | 83181568 |
| wordcount | improved SafeZone | 197.373 | 0.000 | 0.000 | 10000000 | 65536 | 83197952 |
| wordcount | Rift HPZone | 214.210 | 0.000 | 0.314 | 10000000 | 65536 | 83099648 |
| wordcount | Rift Streaming | 209.944 | 0.000 | 0.275 | 10000000 | 65536 | 83066880 |
| graphstep | heap | 239.001 | 29.774 | 0.000 | 10000000 | 100000 | 75071488 |
| graphstep | current SafeZone | 275.357 | 0.000 | 0.000 | 10000000 | 100000 | 83181568 |
| graphstep | improved SafeZone | 206.425 | 0.000 | 0.000 | 10000000 | 100000 | 83197952 |
| graphstep | Rift HPZone | 229.265 | 0.000 | 0.406 | 10000000 | 100000 | 83099648 |
| graphstep | Rift Streaming | 224.885 | 0.000 | 0.365 | 10000000 | 100000 | 83066880 |

Interpretation:

- The pressure run scales the epoch-local data path to ten million ordinary
  Scala data objects per workload.
- Rift again removes measured heap GC and beats heap on elapsed time.
- Improved SafeZone is a stronger baseline than Rift in both pressure
  workloads, so this is not a Rift-over-SafeZone win.
- Current SafeZone is workload-sensitive: it is close to heap on `wordcount`
  but slower than heap on `graphstep`.

Caveats:

- This is not an exact Yak reproduction. It does not run Hyracks, Hadoop, or
  GraphChi. The wordcount/graphstep workloads are no-escape epoch workloads;
  the separate promotion workload below exercises Rift's trusted runtime epoch
  escape API, but still lacks generic object movement and stack scanning.
- The benchmark's purpose is to isolate the control/data split and epoch-local
  lifetime shape in Scala Native. Do not compare the absolute speedups directly
  to Yak's distributed JVM results.
- The harness uses trusted `HPZone`/`Streaming` APIs. It does not prove the
  future safe capture-checked API.

## Post Allocation-Counter Fix Pressure Verification

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-region-pressure-fixed \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

This rerun was taken after moving Rift allocation counters out of the
per-object atomic hot path and flushing counts at region reset/close.

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Logical data objects | Control slots | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 243.522 | 32.850 | 0.000 | 10000000 | 65536 | 75071488 |
| wordcount | current SafeZone | 228.260 | 0.000 | 0.000 | 10000000 | 65536 | 83197952 |
| wordcount | improved SafeZone | 194.933 | 0.000 | 0.000 | 10000000 | 65536 | 83214336 |
| wordcount | Rift HPZone | 224.415 | 0.000 | 0.346 | 10000000 | 65536 | 83083264 |
| wordcount | Rift Streaming | 199.156 | 0.000 | 0.258 | 10000000 | 65536 | 83066880 |
| graphstep | heap | 240.890 | 38.498 | 0.000 | 10000000 | 100000 | 75071488 |
| graphstep | current SafeZone | 273.608 | 0.000 | 0.000 | 10000000 | 100000 | 83197952 |
| graphstep | improved SafeZone | 203.729 | 0.000 | 0.000 | 10000000 | 100000 | 83214336 |
| graphstep | Rift HPZone | 213.454 | 0.000 | 0.409 | 10000000 | 100000 | 83083264 |
| graphstep | Rift Streaming | 209.528 | 0.000 | 0.408 | 10000000 | 100000 | 83066880 |

Interpretation:

- Streaming is now close to improved SafeZone on Yak-style pressure: `199.156 ms`
  vs `194.933 ms` for wordcount and `209.528 ms` vs `203.729 ms` for graphstep.
- Rift still removes measured heap GC and beats heap on both workloads.
- Improved SafeZone remains the strongest baseline, so this is good
  Rift-vs-heap evidence and near-parity with improved SafeZone, not a clean
  Rift-over-SafeZone result.

## Yak-Style Runtime-Safety Proxy

Date: 2026-04-26

Implementation:

- Added mode `yak-runtime`.
- `yak-runtime` keeps durable control state on the heap, as in other modes.
- Epoch-local data objects are allocated in a reusable Rift streaming region.
- Allocation goes through a dynamic epoch object that checks lifecycle state and
  resets the region at epoch boundaries. This represents a runtime-managed
  Yak-style epoch discipline, not the checked Rift API.
- The no-escape `wordcount`/`graphstep` path does not exercise Yak's full
  dynamic promotion or write-barrier mechanism, so those measurements isolate
  the runtime epoch path. The separate `promotion` workload below routes rare
  escaping data objects through `RiftRegion.RuntimeEpoch`.

Commands:

```sh
cd /Users/siyaoliu/rift/scala-native-rift

YAK_EPOCHS=2 YAK_RECORDS_PER_EPOCH=1000 YAK_MESSAGES_PER_EPOCH=1000 \
YAK_BENCHMARK_RUNS=1 YAK_WARMUPS=0 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-smoke \
  zsh sandbox/run_yak_region_instrumented_matrix.sh

YAK_BUILD=0 YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 \
YAK_MESSAGES_PER_EPOCH=250000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Validation:

- The smoke run rebuilt and native-linked `YakRegionMatrix`.
- The pressure run completed.
- All heap/SafeZone/Rift/Yak-runtime checksums matched.

Pressure configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_MESSAGES_PER_EPOCH=250000`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Workload | Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Opens/closes/resets | Peak RSS bytes |
|---|---|---:|---:|---:|---:|---:|---:|
| wordcount | heap | 255.335 | 51.043 | 0.000 | 0 | 0 / 0 / 0 | 75071488 |
| wordcount | current SafeZone | 233.729 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83165184 |
| wordcount | improved SafeZone | 196.523 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83181568 |
| wordcount | Rift HPZone | 201.921 | 0.000 | 0.414 | 10000000 | 40 / 40 / 0 | 83099648 |
| wordcount | Rift Streaming | 206.291 | 0.000 | 0.330 | 10000000 | 1 / 1 / 39 | 83066880 |
| wordcount | Yak-runtime proxy | 211.585 | 0.000 | 0.300 | 10000000 | 1 / 1 / 40 | 83066880 |
| graphstep | heap | 321.660 | 55.830 | 0.000 | 0 | 0 / 0 / 0 | 75071488 |
| graphstep | current SafeZone | 553.408 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83165184 |
| graphstep | improved SafeZone | 212.354 | 0.000 | 0.000 | 0 | 0 / 0 / 0 | 83181568 |
| graphstep | Rift HPZone | 217.911 | 0.000 | 0.536 | 10000000 | 40 / 40 / 0 | 83099648 |
| graphstep | Rift Streaming | 214.947 | 0.000 | 0.404 | 10000000 | 1 / 1 / 39 | 83066880 |
| graphstep | Yak-runtime proxy | 234.914 | 0.000 | 0.497 | 10000000 | 1 / 1 / 40 | 83066880 |

Interpretation:

- A pure runtime epoch mechanism on Scala Native is viable for Yak-shaped
  workloads: it removes measured heap GC and beats heap on both pressure
  workloads.
- The runtime proxy is slower than raw Rift Streaming in this run, especially
  on `graphstep` (`234.914 ms` vs `214.947 ms`). That is the expected cost of
  adding dynamic lifecycle checks around the region path.
- Improved SafeZone remains the strongest baseline for `wordcount`, and is
  close to raw Rift modes on `graphstep`. The result is therefore not a
  Rift-over-SafeZone claim.
- This gives the project a useful axis: pure runtime safety can be measured,
  and the checked Rift path should eventually be compared against both raw Rift
  and this runtime proxy.

## Yak-Style Runtime Promotion/Escape Proxy

Date: 2026-04-26

Implementation:

- Added workload `promotion`.
- Each logical record allocates an ordinary Scala `PromoToken` and nested
  `PromoChild`. Heap mode uses `new`; `yak-runtime` places both objects in a
  runtime-managed epoch region.
- Every record performs a heap/control write through the runtime epoch API.
  `RiftRegion.RuntimeEpoch` counts one barrier check per write.
- Every `YAK_ESCAPE_MODULO` records, the token is retained by durable heap
  control state. The runtime epoch counts one remembered reference and two
  promoted objects, then uses a `RuntimePromoter` hook to copy the escaping
  token/child pair to heap.
- This is now a memory-API-level proxy rather than benchmark-inline promotion
  accounting. It is still not a generic object-moving collector, stack scanner,
  or cross-thread remember-set.

Command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
YAK_BUILD=0 YAK_WORKLOAD=promotion YAK_BENCHMARK_RUNS=3 YAK_WARMUPS=1 \
YAK_EPOCHS=40 YAK_RECORDS_PER_EPOCH=250000 YAK_ESCAPE_MODULO=1000 \
YAK_OUTPUT_DIR=/tmp/yak-runtime-promotion-api-pressure \
  zsh sandbox/run_yak_region_instrumented_matrix.sh
```

Pressure configuration:

- `YAK_EPOCHS=40`
- `YAK_RECORDS_PER_EPOCH=250000`
- `YAK_ESCAPE_MODULO=1000`
- `YAK_SCRATCH_SLOTS=128`
- `YAK_BENCHMARK_RUNS=3`
- `YAK_WARMUPS=1`

| Mode | Median elapsed ms | Median GC ms | Median Rift op ms | Rift objects | Barrier checks | Remembered refs | Promoted objects | Peak RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| heap | 424.768 | 43.745 | 0.000 | 0 | 0 | 0 | 0 | 576045056 |
| Yak-runtime proxy | 513.465 | 0.000 | 0.736 | 20000000 | 10000000 | 10000 | 20000 | 220692480 |

Interpretation:

- This is the closest current local Yak model: it keeps the control/data split,
  includes rare escaping data objects, and moves barrier/remember/promotion
  accounting into Rift's trusted runtime epoch API.
- The Yak-runtime path removes measured heap GC and reduces peak RSS versus
  heap while preserving the same logical program and checksum.
- The corrected memory-API-level runtime path is slower than heap in elapsed
  time (`513.465 ms` vs `424.768 ms`). That is an important negative result:
  dynamic promotion/barrier discipline is not free on Scala Native, and the
  static checked Rift path still needs to avoid paying this cost on every
  heap/control write.
- The result is still not exact Yak. It does not include Hyracks/Hadoop/GraphChi
  workloads, distributed execution, compiler-inserted write barriers on
  arbitrary object fields, generic object movement, stack scanning, or STW
  epoch-end coordination.
