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
| NEXMark-lite Q1/Q2/Q8 | Modest checked/trusted wins or near-ties, with lower GC/RSS. Beam-default Q1/Q8 remain promising; Q2 is near-tie. | Keep as methodology evidence and focused operator regression coverage, not exact Beam runner evidence. |
| NEXMark-lite Q5 / fold | Checked fold lowers RSS/GC but loses elapsed time. | Do not build Q5 claims until fold/table overhead improves. |
| Common Crawl WET-shaped tokenization | Generated rows stress heap GC, but improved SafeZone beats trusted Rift; real preloaded WET rows are heap-fastest with median-zero timed GC. | Keep as memory-pressure detector; do not make it the next case study without a new shard or cheaper checked page/token API. |
| Wikimedia pageview/clickstream | Generated Q2 is promising at 1M, but the 10M generated scale check is a near-tie and real enwiki clickstream is heap-fastest. Median GC is zero on real input, with one heap 1M collection outlier. | Keep as ladder evidence; do not make it a headline case study from current real TSV rows. |
| Linear Road position/toll stream | Generated q1/q2 remove measured GC in Rift, but heap is fastest; official preloaded input has median-zero GC and heap wins all 1M rows, with one heap collection outlier per 1M query. | Record as a ceiling result; do not tune it into a benchmark-specific claim. |
| DEBS | Real data and correctness-heavy, but current wins are modest/near-ties. | Keep as downstream validation, not the next search space. |

## Candidate Ranking

| Rank | Candidate | Why it might help Rift | First benchmark shape | Gate to continue |
|---:|---|---|---|---|
| 1 | Cheaper checked operators | The real-input ladder did not expose a stronger app-level case; the controllable remaining variable is checked API/container overhead. | Focus on append/fold/join-window primitives with fair heap controls before another app integration. | Continue only when focused 1M gates beat heap or give material RSS/GC wins with <=5% elapsed overhead. |
| 2 | NEXMark Beam-default expansion | Keeps stream operators comparable to a known generated benchmark family and produced the only new positive profile rows. | Q1/Q8 are the current promising rows; add only targeted missing shapes and fair heap controls. | Must remain clearly local Beam-default profile evidence unless the Beam runner/generator pipeline is reproduced. |
| 3 | Real Common Crawl WET/WAT | Parser/token streams can create heavy short-lived object pressure, but the first real WET shard was not GC-bound after preloading. | Try another larger WET shard or WAT metadata/link extraction only after a cheap checked page/token operator exists. | Continue only if real input shows timed GC/allocation pressure or a checked operator passes its focused gate. |
| 4 | Wikimedia / Linear Road real inputs | Real-data controls are now wired and negative/ceiling under current probes. | Keep them as regression controls for future operators. | Revisit only after a reusable operator changes allocation cost, or when adding file-backed/parser timing as a separate non-memory claim. |

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

The real-input ladder is now wired and measured. Real enwiki clickstream,
Common Crawl WET, and official Linear Road preloaded rows do not clear the
case-study gate; heap is fastest and median timed GC is zero in the reported
rows. This is not the same as "no GC ever": Wikimedia and Linear Road heap 1M
logs include sporadic collection outliers.
NEXMark Beam-default Q1/Q8 remain useful positive profile rows, but not exact
Beam runner evidence. The next implementation target should be focused
checked-operator overhead reduction, with NEXMark Beam-default Q1/Q8 kept as
regression/application-profile checks.
