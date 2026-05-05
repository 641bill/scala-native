# Realistic Stream GC Matrix

Date: 2026-05-03

Status: benchmark ladder for realistic and real-input GC-heavy stream
evidence. This file distinguishes generated stressors, methodology generators,
real preloaded inputs, and real file-backed inputs. It is also the decision log
for why some real datasets are parked instead of tuned.

## Current Classification

| Workload | Input type | Current signal | Decision |
|---|---|---|---|
| Common Crawl WET-shaped q1/q2 | generated WET-shaped pages/lines/tokens | Strong GC pressure; `heap-immix` spends about `1.55-1.59 s` in GC at 1M; trusted Rift wins elapsed. | Keep as memory-pressure detector, not real-data proof. |
| Common Crawl real WET/WAT q1/q2/q4/q5 | real preloaded WET/WAT shards | Page-token modes match output. SafeZone-backed page-token is fastest on the current WET shard and on real WAT q4/q5, but heap median/max timed GC remains zero on the measured shards. | Park current shards as ceiling/control evidence; try larger/multiple shards or a different real log/NDJSON workload. |
| NEXMark Q3/Q8/Q9/Q11 | official-style generated auction profile | Best recognized stream-methodology checked rows are Q3/Q8, but margins are modest. | Keep as methodology/regression evidence. |
| DSPBench | external benchmark family | Not ported yet; broad stream applications with memory-occupation characterization. | Next new benchmark family to triage. |
| RIoTBench | generated local probe so far; real input desired | Current generated rows are not strong enough. | Revisit only with provenance-clean real IoT-style input. |
| GH Archive NDJSON/log-event stream | real preloaded hourly GitHub events | q1 showed a 100k real-input win when heap collected; the cleaner 8-hour 1M oracle run has heap winning median elapsed with one `135 ms` GC outlier and about `1.7 GiB` RSS. A 1G heap-budget q1 diagnostic makes checked SafeZone-backed page-token faster than heap. | Keep as memory-budget/tail-latency candidate; not yet an unconstrained throughput case study. |
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

Next attempts:

1. GH Archive file-backed and latency/tail rows:
   - include parse/string allocation inside the timed loop;
   - record per-run elapsed and GC so max-GC tails are visible;
   - explicitly label heap cap/RSS when using memory-budget controls.
2. Larger/multiple Common Crawl WET/WAT shards:
   - load enough pages/tokens to approach generated q1/q2 object counts;
   - record actual pages/tokens and shard provenance.
3. More NDJSON/log-event streams:
   - prefer public web/server/security logs with JSON or key-value records;
   - implement parse/project/window-count and token/field extraction variants.
4. DSPBench local-kernel subset:
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
| 5 | NEXMark Q3/Q8/Q9/Q11 | Beam-default generated profile | keep 1M/5-run methodology controls. |
| 6 | RIoTBench real/provenance-clean input | real or clearly documented generator | only continue if heap GC is material at 1M. |

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
