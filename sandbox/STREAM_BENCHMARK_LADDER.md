# Stream Benchmark Ladder

Date: 2026-05-01

Status: selection guide for the next non-DEBS stream/dataflow case studies.
This is a planning/evidence note, not a benchmark result pack.

## Purpose

Rift should not depend on one application or one generated workload. New stream
benchmarks should test the same design question:

- heap and region modes run the same logical program;
- ordinary Scala data objects with structured lifetimes move into regions;
- durable/control metadata can remain on the heap or in primitive arrays;
- current SafeZone and improved SafeZone are both explicit controls.

## Current Evidence Filter

| Workload | Current signal | Decision |
|---|---|---|
| NEXMark-lite Q1/Q2/Q8 and Beam-default Q0/Q1/Q3/Q8/Q11 | Modest checked/trusted wins or near-ties, with lower GC/RSS. Beam-default Q3 is the strongest new checked row; Q0/Q11 are trusted-runtime wins; Q2/Q4/Q9 are ceiling or improved-SafeZone-favored controls. | Keep as methodology evidence and focused operator regression coverage, not exact Beam runner evidence. |
| NEXMark-lite Q5 / fold | Checked fold lowers RSS/GC but loses elapsed time. | Do not build Q5 claims until fold/table overhead improves. |
| Yahoo-style ad stream | Local generated/preloaded parse/filter/campaign-window matrix now has 20k smoke, 100k medians, and a 1M Q2 scale check. Q2 cuts GC but does not beat heap elapsed at 1M; Q0/Q1 are heap-fast. | Keep as a GC-pressure/control candidate, not a headline case study. |
| RIoTBench-style IoT ETL/statistics | Local generated matrix now has 20k smoke and 100k medians. Q1 clean/annotate stresses heap GC and region modes cut RSS, but improved SafeZone is fastest at 100k. | Keep as a fresh stream-shape control; do not tune before a 1M scale check or checked-operator improvement. |
| Common Crawl WET-shaped tokenization | Generated rows stress heap GC, but improved SafeZone beats trusted Rift; real preloaded WET rows are heap-fastest with median-zero timed GC. | Keep as memory-pressure detector; do not make it the next case study without a new shard or cheaper checked page/token API. |
| Wikimedia pageview/clickstream | Generated Q2 is promising at 1M, but the 10M generated scale check is a near-tie and real enwiki clickstream is heap-fastest. Median GC is zero on real input, with one heap 1M collection outlier. | Keep as ladder evidence; do not make it a headline case study from current real TSV rows. |
| Linear Road position/toll stream | Generated q1/q2 remove measured GC in Rift, but heap is fastest; official preloaded input has median-zero GC and heap wins all 1M rows, with one heap collection outlier per 1M query. | Record as a ceiling result; do not tune it into a benchmark-specific claim. |
| DEBS | Real data and correctness-heavy, but current wins are modest/near-ties. | Keep as downstream validation, not the next search space. |
| DaCapo / Renaissance / SPECjbb2015 | Potentially GC-heavy JVM suites, but not stream-topology-first workloads. | Deprioritize for primary Rift evidence; preserve SPECjbb2005 only as the Stancu comparison anchor. |

## Candidate Ranking

| Rank | Candidate | Why it might help Rift | First benchmark shape | Gate to continue |
|---:|---|---|---|---|
| 1 | Cheaper checked operators | The real-input ladder did not expose a stronger app-level case; the controllable remaining variable is checked API/container overhead. | Focus on append/fold/join-window primitives with fair heap controls before another app integration. | Continue only when focused 1M gates beat heap or give material RSS/GC wins with <=5% elapsed overhead. |
| 2 | NEXMark Beam-default expansion | Keeps stream operators comparable to a known generated benchmark family and produced the best new positive profile rows. | Q0/Q1/Q3/Q8/Q11 are current profile/regression rows; Q3 is the best checked candidate. | Must remain clearly local Beam-default profile evidence unless the Beam runner/generator pipeline is reproduced. |
| 3 | RIoTBench-style IoT ETL/statistics | Sensor ETL has parse/clean/annotate/window/anomaly objects with clear event/window lifetimes and a different shape from auctions/ad views. | `RiotBenchRegionMatrix` now has 20k smoke and 100k medians. | Continue to 1M only as a scale check; current 100k q1 favors improved SafeZone over Rift. |
| 4 | Yahoo-style ad stream | Parse/filter/campaign-window pipelines are closer to ad-serving stream systems than generic JVM suites, and have clear bucket lifetimes. | Keep `YahooAdRegionMatrix` Q0/Q1/Q2 as a regression/control matrix. | Revisit only with a checked operator that changes allocation cost or with real ad-event input. |
| 5 | Real Common Crawl WET/WAT | Parser/token streams can create heavy short-lived object pressure, but the first real WET shard was not GC-bound after preloading. | Try another larger WET shard or WAT metadata/link extraction only after a cheap checked page/token operator exists. | Continue only if real input shows timed GC/allocation pressure or a checked operator passes its focused gate. |
| 6 | Wikimedia / Linear Road real inputs | Real-data controls are now wired and negative/ceiling under current probes. | Keep them as regression controls for future operators. | Revisit only after a reusable operator changes allocation cost, or when adding file-backed/parser timing as a separate non-memory claim. |

## Measurement Requirements

Every ladder benchmark must record:

- command and environment, including `SAFEZONE_ROOTS_MODE`;
- current SafeZone and improved SafeZone rows;
- elapsed median, GC ms, Rift op ms, region object counts, opens/closes/resets;
- RSS from `/usr/bin/time`;
- checksum/output count equality;
- whether input is generated, preloaded real data, or file-backed real data;
- whether the Rift path is checked safe API or trusted runtime API.

## Immediate Next Step

The real-input ladder is wired and measured enough to park current Wikimedia,
Common Crawl WET, and Linear Road rows as ceiling/regression controls. The
expanded NEXMark Beam-default rows, Yahoo-style ad medians, and first
RIoTBench-style 100k medians are now recorded. The next benchmark search
should stay in GC-heavy stream processing, not generic JVM suites, but the
strongest remaining direction is checked operator overhead reduction. Use
NEXMark Q3/Q8, Yahoo Q2, and RIoTBench q1 as regression/profile controls.
