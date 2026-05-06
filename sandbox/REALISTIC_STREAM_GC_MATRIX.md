# Realistic Stream GC Matrix

Date: 2026-05-03
Last updated: 2026-05-07 00:16 CEST

Status: benchmark ladder for realistic and real-input GC-heavy stream
evidence. This file distinguishes generated stressors, methodology generators,
real preloaded inputs, and real file-backed inputs. It is also the decision log
for why some real datasets are parked instead of tuned.

## Current Classification

| Workload | Input type | Current signal | Decision |
|---|---|---|---|
| Common Crawl WET-shaped q1/q2 | generated WET-shaped pages/lines/tokens | Strong GC pressure; latest heap spends `1517.640 ms` GC on q1 and `1526.751 ms` GC on q2 at 1M; checked page-token rows now win elapsed. | Keep as memory-pressure detector, not real-data proof. |
| Common Crawl real WET/WAT q1/q2/q4/q5 | real preloaded WET/WAT shards | Page-token modes match output. SafeZone-backed page-token is fastest on the current WET shard and on real WAT q4/q5, but heap median/max timed GC remains zero on the measured shards. | Park current shards as ceiling/control evidence; try larger/multiple shards or a different real log/NDJSON workload. |
| NEXMark Q3/Q8/Q9/Q11 | official-style generated auction profile | Latest Beam-default sweep has checked Rift fastest on q3/q8/q9/q11, with q9 the strongest row (`708.391 ms` vs heap `779.032 ms`). Margins remain mostly modest. | Keep as methodology/regression evidence. |
| DSPBench | external benchmark family | Not ported yet; broad stream applications with memory-occupation characterization. | Next new benchmark family to triage. |
| RIoTBench | generated local probe so far; real input desired | Current generated rows are not strong enough. | Revisit only with provenance-clean real IoT-style input. |
| GH Archive NDJSON/log-event stream | generated/real-shaped local GitHub-event rows plus real file-backed hourly events | The legacy string-parser file-backed rows showed material heap GC in every run and large RSS reductions from regions, but parser/string allocation dominated elapsed. The new byte-slice parser-scratch path cuts 100k elapsed/RSS sharply and makes the two-hour 200k q1/q2 rows modest real-input throughput/RSS/tail wins: heap uses about `290 MB` RSS and collects in 2/3 runs, while region rows use about `211 MB` RSS and report zero timed GC. | Keep as the strongest current real-input candidate. Next target is scaling byte-slice file-backed rows and generalizing byte-slice NDJSON/log extraction beyond GH-specific code. |
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
| GH Archive q1/q2 file-backed, byte-slice parser | Byte-slice parsing reuses line buffers and extracts JSON fields from raw UTF-8 bytes. At 100k it turns the row into a near tie with lower RSS and removed GC outliers; at 200k/two-hour scale it gives modest region throughput wins and zero timed GC in region rows. |
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
| q1-fields | 200k real file-backed events / 2.6M records, 2 hourly files, byte-slice parser | heap `3806.120 ms`, RSS `290177024` | Streaming `3626.219 ms`, RSS `211075072`; SafeZone-backed page-token `3629.193 ms`, RSS `211238912` | heap median `57.685 ms`, max `73.922 ms`, 2/3 runs; region rows `0.000 ms` | `2600000` | Best current real GH q1 row: modest throughput win, clear RSS win, and GC-tail removal after parser scratch. |
| q2-repo-window | 200k real file-backed events / 2.6M records, 2 hourly files, byte-slice parser | heap `3756.950 ms`, RSS `290193408` | Streaming `3645.458 ms`, RSS `211075072`; SafeZone-backed page-token `3626.107 ms`, RSS `211222528` | heap median `61.625 ms`, max `67.231 ms`, 2/3 runs; region rows `0.000 ms` | `31794` | Byte-slice q2 is now a modest checked scoped page-token win, not just a repo-aggregation ceiling row. |

Next attempts:

1. GH Archive larger byte-slice file-backed q1/q2:
   - test whether multi-hour/day file-backed inputs amplify the fixed-memory result;
   - record per-run elapsed and GC so max-GC tails are visible;
   - explicitly label heap cap/RSS when using memory-budget controls.
2. General NDJSON/log parser-scratch API:
   - factor the byte-slice JSON field extraction shape out of GH Archive;
   - apply it to another public NDJSON/log stream before claiming broad
     parser-scratch generality.
3. Larger/multiple Common Crawl WET/WAT shards:
   - load enough pages/tokens to approach generated q1/q2 object counts;
   - record actual pages/tokens and shard provenance.
4. More NDJSON/log-event streams:
   - prefer public web/server/security logs with JSON or key-value records;
   - first concrete candidates are GDELT event files, Apache/NASA-style web
     logs, security/event JSON lines, and Stack Exchange-style dumps converted
     to event streams;
   - implement parse/project/window-count and token/field extraction variants.
5. DSPBench local-kernel subset:
   - choose 2-3 kernels with high object churn and clear windows/epochs;
   - remove distributed runtime dependencies from headline memory rows.

## Concrete Benchmark Ladder

Run the ladder in this order. Stop early when a row fails the material heap-GC
or allocation-pressure gate.

| Step | Benchmark | Input | Required rows before scaling |
|---:|---|---|---|
| 1 | Common Crawl WET/WAT larger or multi-shard q1/q2/q4/q5 | real preloaded, then file-backed | actual pages/tokens/links; heap median/max GC; output checksum. |
| 2 | GH Archive file-backed q1/q2 | real file-backed NDJSON | parser/string allocation included; per-run latency/tail GC. |
| 3 | More NDJSON/log stream q0/q1/q2 | real public logs | parsed records, fields/tokens, window-count output. |
| 4 | DSPBench triage | source workloads, local kernel only | select 2-3 kernels with object churn and epoch/window lifetimes. |
| 5 | GDELT/log-event real-data matrix | real event/log files | file-backed parse/project/window-count rows with actual loaded record count and heap max-GC. |
| 6 | NEXMark Q3/Q8/Q9/Q11 | Beam-default generated profile | keep 1M/5-run methodology controls. |
| 7 | RIoTBench real/provenance-clean input | real or clearly documented generator | only continue if heap GC is material at 1M. |

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
