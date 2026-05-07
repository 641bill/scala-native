# Realistic Stream GC Matrix

Date: 2026-05-03
Last updated: 2026-05-07 13:51 CEST

Status: benchmark ladder for realistic and real-input GC-heavy stream
evidence. This file distinguishes generated stressors, methodology generators,
real preloaded inputs, and real file-backed inputs. It is also the decision log
for why some real datasets are parked instead of tuned.

## Current Classification

| Workload | Input type | Current signal | Decision |
|---|---|---|---|
| Common Crawl WET-shaped q1/q2 | generated WET-shaped pages/lines/tokens | Strong GC pressure; latest post-fast-path selected 1M sweep has heap spending `1655.357 ms` GC on q1 and `1599.698 ms` GC on q2. Checked page-token rows win elapsed: checked scoped page-token is fastest on q1 (`3840.668 ms` vs heap `5618.631 ms`) and q2 (`3839.158 ms` vs heap `5303.179 ms`), while checked Rift page-token also beats trusted Streaming. | Keep as memory-pressure detector, not real-data proof. |
| Common Crawl real WET/WAT q1/q2/q4/q5 | real preloaded WET/WAT shards | Page-token modes match output. SafeZone-backed page-token is fastest on the current WET shard and on real WAT q4/q5, but heap median/max timed GC remains zero on the measured shards. | Park current shards as ceiling/control evidence; try larger/multiple shards or a different real log/NDJSON workload. |
| NEXMark Q3/Q8/Q9/Q11 | official-style generated auction profile | Latest post-fast-path selected Beam-default sweep has checked Rift fastest on q3/q8/q9/q11, with q9 the strongest selected row (`724.479 ms` vs heap `798.672 ms`). Margins remain mostly modest. | Keep as methodology/regression evidence. |
| DSPBench Spike Detection | public DSPBench source and real bundled sensor sample, local single-process methodology port | `DSPBenchRegionMatrix` now runs q0/q1/q2 over `79999` usable `sensors.dat` rows, replayed with explicit counts. At 1M, heap GC is material but small (`10.880-32.793 ms` inside `1068.850-1271.677 ms`). Checked scoped page-token q1 is a modest win (`1163.045 ms` vs heap `1187.525 ms`); q2 trusted Streaming is a modest win, but checked scoped q2 is slightly slower. | Keep as real-input modest/control evidence; Fraud is now the stronger DSPBench follow-up row. |
| DSPBench Fraud Detection | public DSPBench source and real bundled `credit-card.dat`, local single-process methodology port | Implemented q0/q1/q2. Original full Fraud q2 made trusted Streaming the strongest row (`763.819 ms` vs heap `801.790 ms`) while checked scoped page-token lost elapsed. After the 2026-05-07 page-token batch-close fast path, same-run q2 has checked scoped page-token fastest: `818.574 ms` vs heap `862.834 ms`, improved SafeZone `873.859 ms`, and trusted Streaming `834.447 ms`, with RSS about `279 MB` vs heap `358 MB`. Diagnostic-only timing still shows checked close/traverse and bucket switching about `6-8 ms` above trusted/heap same-shape traversal. | Promote to modest checked real-input win and keep as checked-operator overhead regression target. Not a flagship GC-heavy case: heap GC is material but not dominant. |
| RIoTBench | generated local probe so far; real input desired | Current generated rows are not strong enough. | Revisit only with provenance-clean real IoT-style input. |
| LogHub / LogPAI BGL | real file-backed system log | New matrix loads the real BGL log (`4747963` lines). At 1M, heap GC is material enough to appear in every run (`99-157 ms`) but still a small share of roughly `5.6 s` elapsed. Region rows remove timed GC and produce modest throughput/RSS/fixed-memory wins. Full-file q2 single-run heap spends `595.599 ms` in GC inside `32.161 s`; checked scoped page-token is faster and lower-RSS than heap, but the win is not large enough for a flagship GC-heavy case. | Keep as real-input modest-win/control evidence. Do not overclaim as the missing GC-heavy stream case; move to DSPBench/real RIoTBench/other logs next. |
| GH Archive NDJSON/log-event stream | generated/real-shaped local GitHub-event rows plus real file-backed hourly events | The legacy string-parser file-backed rows showed timed heap GC in every run and large RSS reductions from regions, but parser/string allocation dominated elapsed. The new byte-slice parser-scratch path cuts 100k elapsed/RSS sharply and makes the two-hour 200k q1/q2 rows modest real-input throughput/RSS/tail wins: heap uses about `290 MB` RSS and collects in 2/3 runs, while region rows use about `211 MB` RSS and report zero timed GC. Heap GC is still only about `58-62 ms` inside `3.8 s` total elapsed, so this is not the missing GC-heavy case study. | Keep as the strongest current real-input modest-win candidate, not GC-heavy proof. Next target is scaling byte-slice file-backed rows and generalizing byte-slice NDJSON/log extraction beyond GH-specific code. |
| Other NDJSON/log-event streams | real public logs desired | GH Archive is implemented; other public logs not yet tried. | Still high-priority if they produce larger object churn or force steadier GC without multi-GB heap growth. |
| Wikimedia / Linear Road real inputs | real preloaded inputs | Mostly heap-fastest or median-GC-zero. | Regression/ceiling controls. |
| Yahoo-style ad stream | generated/preloaded local probe | Near-tie; cuts GC but no decisive elapsed win. | Regression/control unless real input or new operator changes allocation shape. |
| DEBS | real data | Correctness-heavy, modest/near-tie current wins. | Downstream validation, not next search target. |

## What Counts As Realistic Evidence

| Label | Meaning | Claim strength |
|---|---|---|
| generated stressor | Synthetic input shaped after a real format, e.g. WET-shaped pages/tokens. | Good for memory-regime proof, not real-data proof. |
| generated methodology | Official or literature-recognized generator, e.g. NEXMark. | Good methodology evidence, not real deployment evidence. |
| real preloaded input | Real data loaded before timing, parser/I/O mostly excluded. | Good memory-management evidence if object count and GC pressure are material. |
| real file-backed input | Real data parsed during timing. | Good end-to-end evidence if parser/I/O does not hide memory effects. |

## Why Current Real Rows Are Not GC Heavy

The current real-input probes do not contradict the generated GC-heavy result.
They mostly fail to create the same allocation regime:

| Row | Likely reason median GC is low or heap wins |
|---|---|
| Real WET q1/q2 shard | The loaded page/token count is much smaller than generated 1M WET-shaped q1/q2; timing is dominated by parser/string work and small working-set effects. The new page-token operator can win modestly, but not because it removed material timed GC on this shard. |
| Real WAT q4/q5 shard | Link extraction now materializes about `1.0M` link/page records at 50k requested pages, but heap still reports zero timed GC; the current shard is a high-RSS real-input control rather than a GC-heavy case study. |
| GH Archive q1/q2 8-hour oracle | At 1M real events, q1/q2 allocate `13M` ordinary event/field records. Heap wins median elapsed when uncapped, but one run per query collects for about `135 ms` and RSS is around `1.7 GiB`. Under a 1G heap cap, q1 heap slows to `395.295 ms` with `92.347 ms` median GC, making the checked SafeZone-backed q1 row (`348.817 ms`, zero timed GC) faster. |
| GH Archive q1/q2 file-backed, legacy string parser | At 100k-200k real events, parser/decompression is included and all successful modes report timed GC. Regions cut RSS by about `45-70%` depending on query/mode and modestly improve trusted Streaming elapsed, but checked scoped page-token is near-tied because parser/string allocation remains heap-managed. |
| GH Archive q1/q2 file-backed, byte-slice parser | Byte-slice parsing reuses line buffers and extracts JSON fields from raw UTF-8 bytes. At 100k it turns the row into a near tie with lower RSS and removed GC outliers; at 200k/two-hour scale it gives modest region throughput wins and zero timed GC in region rows. It is still not GC-heavy because heap GC is a small share of elapsed time. |
| LogHub BGL q1/q2 file-backed | The real BGL log creates many line/token objects and does trigger heap GC more steadily than GH Archive byte-slice rows. At 1M lines, heap GC is `99.271 ms` for q1 and `157.198 ms` for q2, but elapsed is still around `5.6 s`, so parser/line/token CPU dominates. Full-file q2 raises heap GC to `595.599 ms`, still under 2% of elapsed. |
| Wikimedia TSV/clickstream | TSV parsing and primitive/group-count state dominate; event objects do not survive long enough or accumulate enough allocation pressure. |
| Linear Road official slice | Durable vehicle/segment metadata is mostly primitive/heap control state; generated position reports are not enough to make GC the bottleneck. |
| Yahoo/RIoT local probes | Generated records are too regular and the heap collector handles them cheaply; elapsed time is mostly query CPU. |

These rows are still useful because they prevent overclaiming. They show that
"stream processing" is not automatically a region win; the workload must have
large object churn plus clear epochal death.

## Next Real-Input Attempts

Latest real WET page-token control:

| Query | Heap | Best checked row | Heap GC | Output count | Decision |
|---|---:|---:|---:|---:|---|
| q1-tokenize | `32.215 ms` | SafeZone-backed page-token `30.474 ms` | `0.000 ms`, 0 runs with GC | `752797` | Correctness/control row; not GC-heavy. |
| q2-domain-window | `31.227 ms` | SafeZone-backed page-token `30.783 ms` | `0.000 ms`, 0 runs with GC | `18560` | Correctness/control row; not GC-heavy. |

Latest real WAT link-metadata control:

Input:
`/Users/siyaoliu/rift/cache/benchmark-data/common-crawl/CC-MAIN-2026-17/CC-MAIN-20260410081153-20260410111153-00000.warc.wat`

| Query | Heap | Best checked row | Heap GC | Output count | Decision |
|---|---:|---:|---:|---:|---|
| q4-wat-links, 50k requested pages | `33.646 ms` | SafeZone-backed page-token `31.792 ms` | `0.000 ms`, 0 runs with GC | `1006742` | Real link-object path works and wins modestly; not GC-heavy. |
| q5-wat-link-domain-window, 50k requested pages | `35.066 ms` | SafeZone-backed page-token `33.937 ms` | `0.000 ms`, 0 runs with GC | `293020` | Real link/window path works and wins modestly; not GC-heavy. |
| q4-wat-links, 100k requested one-run probe | `43.274 ms` | SafeZone-backed page-token `42.226 ms` | `0.000 ms`, 0 runs with GC | `1339183` | Scaling this shard still does not trigger material heap GC. |

Next attempts:

Latest LogHub BGL control:

| Query | Scale | Heap | Best region row | Heap GC | Output count | Decision |
|---|---:|---:|---:|---:|---:|---|
| q1-tokens | 100k real BGL lines / 1.25M line+token records | `642.431 ms`, RSS `148111360` | checked scoped page-token `620.531 ms`, RSS `135823360` | median `17.627 ms`, max `45.285 ms`, 2/3 runs | `1251309` | Real-input modest throughput/RSS win; not headline GC-heavy evidence. |
| q2-window-counts | 100k real BGL lines / 1.25M line+token records | `647.981 ms`, RSS `148127744` | Streaming `616.252 ms`, RSS `135626752` | median `17.470 ms`, max `50.490 ms`, 2/3 runs | `13` | Real-input modest throughput/RSS win. |
| q1-tokens | 1M real BGL lines / 13.4M line+token records | `5568.252 ms`, RSS `408420352` | Streaming `5491.033 ms`, RSS `357679104`; checked scoped page-token `5552.988 ms` | median `99.271 ms`, max `103.832 ms`, 3/3 runs | `13445386` | Region rows remove GC; trusted Streaming is modestly faster/lower-RSS, checked scoped page-token is near-tied but higher-RSS in this row. |
| q2-window-counts | 1M real BGL lines / 13.4M line+token records | `5646.824 ms`, RSS `408879104` | improved SafeZone-32k `5509.481 ms`; checked scoped page-token `5636.357 ms` | median `157.198 ms`, max `164.560 ms`, 3/3 runs | `87` | Modest safe/scoped runtime win; checked row is near-tied. |
| q1/q2 heap caps | 1M real BGL lines, heap-only | q1 `256M` cap `5807.256 ms`, q2 `256M` cap `5768.222 ms` | compare to uncapped region rows | q1/q2 median GC about `195-200 ms`, max `220-265 ms` | matching checksums | Fixed-memory/tail sensitivity exists, but not enough for flagship claim. |
| q2-window-counts | full BGL file, `4747963` real lines / `66868883` line+token records | heap `32161.391 ms`, RSS `576012288` | Streaming `30899.595 ms`, checked scoped page-token `31165.087 ms`, improved SafeZone-32k `31459.104 ms` | `595.599 ms`, 7 collections | `562` | Full-file single-run scale probe: region rows are faster and remove GC, but heap GC remains under 2% of elapsed. |

Latest GH Archive control:

| Query | Scale | Heap | Best checked row | Heap GC | Output count | Decision |
|---|---:|---:|---:|---:|---:|---|
| q1-fields | 100k real events / 1.3M records, original heap-expected harness | `46.309 ms` | SafeZone-backed page-token `33.656 ms` | median `15.777 ms`, max `58.617 ms`, 2/3 runs | `1300000` | Promising first signal, but superseded for RSS/GC hygiene by oracle rows. |
| q1-fields | 1M real events / 13M records, 8-hour oracle | `293.204 ms` | Streaming rerun `340.820 ms`; SafeZone-backed page-token `348.817 ms` | median `0.000 ms`, max `135.368 ms`, 1/3 runs | `13000000` | Heap wins uncapped median by growing to ~1.7 GiB; region removes GC tail but loses throughput. |
| q1-fields | 1M real events / 13M records, heap cap `1G` | heap `395.295 ms` | compare to uncapped SafeZone-backed page-token `348.817 ms` | median `92.347 ms`, max `101.174 ms`, 2/3 runs | `13000000` | Memory-budget diagnostic: checked region path wins when heap cannot grow freely. |
| q2-repo-window | 1M real events / 13M records, 8-hour oracle | `271.880 ms` | Streaming `325.665 ms`; SafeZone-backed page-token `347.033 ms` | median `0.000 ms`, max `136.353 ms`, 1/3 runs | `159411` | Heap wins median; repo aggregation CPU dominates. |
| q1-fields | 100k real file-backed events / 1.3M records | heap `3999.933 ms`, RSS `1218805760` | Streaming `3908.972 ms`, RSS `495943680`; SafeZone-backed page-token `3937.394 ms`, RSS `674791424` | heap median `158.149 ms`, Streaming `73.055 ms`, checked SafeZone-backed `106.248 ms`; all modes 3/3 runs with GC | `1300000` | First real file-backed row: regions modestly improve elapsed/RSS, but parser/string allocation still causes GC in region rows. |
| q2-repo-window | 100k real file-backed events / 1.3M records | heap `3995.632 ms`, RSS `1218428928`; heap `1G` cap fails with signal 11 at `1077067776` bytes RSS | Streaming `3906.291 ms`, RSS `673644544`; SafeZone-backed page-token `3921.127 ms`, RSS `673824768` | heap median `158.277 ms`, Streaming `81.402 ms`, checked SafeZone-backed `106.352 ms`; all successful rows 3/3 runs with GC | `15877` | File-backed q2 is modest region/RSS/fixed-memory evidence, not just an aggregation-CPU ceiling row. Parser/string allocation still causes GC. |
| q1-fields | 100k real file-backed events / 1.3M records, heap caps | heap uncapped `4014.909 ms`, `1G` cap fails with signal 11 at `1076805632` bytes RSS | Streaming `3995.238 ms`, RSS `673611776`; SafeZone-backed page-token `4023.883 ms`, RSS `674742272` | heap uncapped median `157.495 ms`, heap `1400M` max `201.304 ms`, Streaming `82.368 ms`, checked SafeZone-backed `113.334 ms` | `1300000` | Mostly RSS/fixed-memory evidence: checked scoped page-token is a near tie/slight elapsed loss but cuts RSS by about 45%; trusted Streaming is slightly faster. |
| q1-fields | 200k real file-backed events / 2.6M records, 2 hourly files | heap `7549.355 ms`, RSS `2432679936` | Streaming `7448.838 ms`, RSS `925466624`; SafeZone-backed page-token `7489.923 ms`, RSS `925614080` | heap median `198.535 ms`, Streaming `154.497 ms`, checked SafeZone-backed `193.910 ms`; all modes 3/3 runs with GC | `2600000` | Multi-hour file-backed row strengthens RSS evidence; throughput gains remain modest because parser/string/decompression dominates. |
| q2-repo-window | 200k real file-backed events / 2.6M records, 2 hourly files | heap `7641.540 ms`, RSS `2431680512` | Streaming `7442.005 ms`, RSS `724779008`; SafeZone-backed page-token `7498.263 ms`, RSS `925630464` | heap median `199.876 ms`, Streaming `138.692 ms`, checked SafeZone-backed `197.692 ms`; all modes 3/3 runs with GC | `31794` | Region rows cut RSS sharply and modestly improve elapsed; checked still pays parser/string heap allocation. |
| q1-fields | 100k real file-backed events / 1.3M records, byte-slice parser | heap `1957.637 ms`, RSS `152911872` | Streaming `1964.748 ms`, RSS `147243008`; SafeZone-backed page-token `1957.640 ms`, RSS `147406848` | heap median `0.000 ms`, max `53.889 ms`, 1/3 runs; region rows `0.000 ms` | `1300000` | Byte-slice parser removes most string-parser overhead; 100k becomes a near-tie/ceiling row with lower RSS and no region GC. |
| q2-repo-window | 100k real file-backed events / 1.3M records, byte-slice parser | heap `1957.715 ms`, RSS `152911872` | Streaming `1968.853 ms`, RSS `147292160`; SafeZone-backed page-token `2005.000 ms`, RSS `147406848` | heap median `0.000 ms`, max `52.762 ms`, 1/3 runs; region rows `0.000 ms` | `15877` | q2 byte-slice 100k remains CPU/parser dominated; useful control before scaling. |
| q1-fields | 200k real file-backed events / 2.6M records, 2 hourly files, byte-slice parser | heap `3806.120 ms`, RSS `290177024` | Streaming `3626.219 ms`, RSS `211075072`; SafeZone-backed page-token `3629.193 ms`, RSS `211238912` | heap median `57.685 ms`, max `73.922 ms`, 2/3 runs; region rows `0.000 ms` | `2600000` | Best current real GH q1 row: modest throughput win, clear RSS win, and GC-tail removal after parser scratch; not GC-heavy because heap GC is about 1.5% of elapsed. |
| q2-repo-window | 200k real file-backed events / 2.6M records, 2 hourly files, byte-slice parser | heap `3756.950 ms`, RSS `290193408` | Streaming `3645.458 ms`, RSS `211075072`; SafeZone-backed page-token `3626.107 ms`, RSS `211222528` | heap median `61.625 ms`, max `67.231 ms`, 2/3 runs; region rows `0.000 ms` | `31794` | Byte-slice q2 is now a modest checked scoped page-token win, not just a repo-aggregation ceiling row; still not a GC-heavy row. |

Next attempts:

1. DSPBench local-kernel subset:
   - Spike Detection q0/q1/q2 is implemented and measured in
     `evidence/DSPBENCH_REGION_MATRIX.md`;
   - keep it as modest/control evidence because heap GC stays below 3% of
     elapsed at 1M;
   - Fraud Detection q0/q1/q2 is implemented and q2 is the strongest DSPBench
     real-input row so far;
   - after the page-token fast path, checked scoped page-token q2 is a modest
     same-run win over heap, improved SafeZone, and trusted Streaming;
   - keep q2 as a regression row for checked close/traverse overhead, but do
     not treat it as the final GC-heavy flagship;
   - remove distributed runtime dependencies from headline memory rows;
   - replay real sample lines only with explicit actual/replayed-count labels.
2. Real RIoTBench-style input:
   - find provenance-clean CITY/FIT/sensor traces or official-style inputs;
   - run parse/filter/window statistics before adding new operators.
3. Another real log/NDJSON workload:
   - use LogHub as the baseline log control;
   - try HDFS, Thunderbird, Spark, or GDELT-like event files only if they
     materialize more per-record objects or force steadier heap GC than BGL.
4. GH Archive larger byte-slice file-backed q1/q2:
   - test whether multi-hour/day file-backed inputs amplify the fixed-memory result;
   - record per-run elapsed and GC so max-GC tails are visible;
   - explicitly label heap cap/RSS when using memory-budget controls.
5. General NDJSON/log parser-scratch API:
   - factor the byte-slice JSON field extraction shape out of GH Archive;
   - apply it to another public NDJSON/log stream before claiming broad
     parser-scratch generality.
6. Larger/multiple Common Crawl WET/WAT shards:
   - load enough pages/tokens to approach generated q1/q2 object counts;
   - record actual pages/tokens and shard provenance.

## Concrete Benchmark Ladder

Run the ladder in this order. Stop early when a row fails the material heap-GC
or allocation-pressure gate.

| Step | Benchmark | Input | Required rows before scaling |
|---:|---|---|---|
| 1 | DSPBench triage | source workloads, local kernel only | Spike and Fraud q0/q1/q2 implemented and measured; Fraud q2 is now a modest checked win after page-token fast-close optimization. |
| 2 | Real RIoTBench-style traces | real or provenance-clean sensor traces | parse/filter/window-stat rows with material heap GC gate. |
| 3 | LogHub BGL q1/q2 | real file-backed system log | completed first 100k/1M/full-file controls; keep as modest-win baseline. |
| 4 | More NDJSON/log stream q0/q1/q2 | real public logs | parsed records, fields/tokens, window-count output. |
| 5 | GDELT/log-event real-data matrix | real event/log files | file-backed parse/project/window-count rows with actual loaded record count and heap max-GC. |
| 6 | Common Crawl WET/WAT larger or multi-shard q1/q2/q4/q5 | real preloaded, then file-backed | actual pages/tokens/links; heap median/max GC; output checksum. |
| 7 | GH Archive file-backed q1/q2 | real file-backed NDJSON | parser/string allocation included; per-run latency/tail GC. |
| 8 | NEXMark Q3/Q8/Q9/Q11 | Beam-default generated profile | keep 1M/5-run methodology controls. |

For every row record:

- canonical memory mode;
- input provenance: generated stressor, generated methodology, real-preloaded,
  or real-file-backed;
- requested count and actual loaded count;
- elapsed median/min/max;
- GC median/max and runs-with-GC;
- RSS;
- checksum/output count;
- Rift op time and region open/close/reset/object counts for Rift rows.

## Gates

- Continue a real-input candidate only if actual loaded records are large
  enough and `heap-immix` shows material median/max GC or allocation-call cost.
- A case-study row must beat `heap-immix` and `safezone-improved-32k` by about
  `10%`, or materially reduce GC/RSS with no more than `5%` elapsed overhead.
- Checked rows require the focused checked operator gate first.
- Median-zero-GC real rows are useful ceiling controls, not tuning targets.

## Presentation Rule

Do not present generated WET-shaped q1/q2 as "real Common Crawl performance."
Present it as "Common Crawl-shaped memory-pressure detector." A real-data
Common Crawl claim requires larger WET/WAT rows with real input provenance and
material heap GC.
