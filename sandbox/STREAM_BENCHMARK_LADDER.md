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
| NEXMark-lite Q1/Q2/Q8 | Modest checked/trusted wins or near-ties, with lower GC/RSS. | Keep as methodology evidence and focused operator regression coverage. |
| NEXMark-lite Q5 / fold | Checked fold lowers RSS/GC but loses elapsed time. | Do not build Q5 claims until fold/table overhead improves. |
| Common Crawl WET-shaped tokenization | Stresses heap GC, but improved SafeZone beats trusted Rift on the generated 100k rows. | Keep as memory-pressure detector; do not make it the next case study yet. |
| Wikimedia generated pageview/clickstream | Q2 is promising at 1M, but the 10M single-run scale check is a near-tie with heap/improved SafeZone. | Keep as ladder evidence; do not make it a headline case study from generated input. |
| Linear Road generated position/toll stream | q1/q2 remove measured GC in Rift, but heap is fastest at 1M and region modes do not improve RSS at the default bucket size. | Record as a ceiling result; do not tune it into a benchmark-specific claim. |
| DEBS | Real data and correctness-heavy, but current wins are modest/near-ties. | Keep as downstream validation, not the next search space. |

## Candidate Ranking

| Rank | Candidate | Why it might help Rift | First benchmark shape | Gate to continue |
|---:|---|---|---|---|
| 1 | Wikimedia pageviews/clickstream | High-volume event records with natural hour/window lifetimes and lower per-event CPU than Common Crawl tokenization. | Generated TSV-shaped pageview/clickstream events are implemented; real TSV input remains open. Queries: pageviews, project/article counts, clickstream edges. | Current generated results do not clear the case-study gate. Revisit only with real TSV input or a stronger checked operator. |
| 2 | Linear Road methodology | Continuous stream windows, toll outputs, latency/deadline metrics, and object-heavy position reports. | Generated position reports, segment windows, accident/congestion state, toll outputs are implemented. | Current generated results do not clear the case-study gate. Revisit only with the original generator/full query set or a stronger checked operator. |
| 3 | Real Common Crawl WET/WAT | Parser/token streams can create heavy short-lived object pressure. | Decompressed WET input plus preloaded control; WAT later for metadata/link extraction. | Only continue if real input differs from generated result or a cheap checked page/token append API changes the outcome. |
| 4 | NEXMark expansion | Keeps stream operators comparable to a known benchmark family. | Add targeted map/filter/output and one fair join/window query. | Must remain clearly NEXMark-lite unless Beam generator/configs are matched. |

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

The corrected SafeZone reruns make Common Crawl generated tokenization a mixed
result. `WikimediaRegionMatrix` and `LinearRoadRegionMatrix` now give generated
stream coverage, but neither clears the case-study gate from generated input.
The next implementation target should be either real Wikimedia TSV input or a
focused checked-operator overhead reduction, not another generated application
probe.
