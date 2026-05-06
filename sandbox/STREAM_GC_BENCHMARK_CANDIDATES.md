# Stream GC Benchmark Candidates

Date: 2026-05-01
Last updated: 2026-05-07 01:23 CEST

Status: benchmark-selection note for Phase 6. This is not a result pack.

## Decision

Rift should not use DaCapo, Renaissance, or SPECjbb2015 as primary
performance evidence. They can be useful background for "managed runtimes can
be GC-heavy," but their lifetimes are not naturally stream/dataflow shaped:
framework caches, global indexes, thread pools, long-lived graphs, and
cross-request state make region topology annotations intrusive and likely weak.

The primary search space is GC-heavy stream processing:

- many ordinary event/data objects;
- clear page, batch, window, epoch, transaction, or operator close boundary;
- durable control state separated into heap or primitive metadata;
- heap and Rift versions share the same logical program;
- region modes change only allocation placement and lifetime policy.

SPECjbb remains visible only as the Stancu/SPECjbb2005 literature anchor.
Do not start a Scala Native port of SPECjbb2015 unless a separate question
requires it and licensing/provenance are explicit.

## Sources Checked In This Pass

| Source | What it contributes | Rift relevance |
|---|---|---|
| Apache Beam NEXMark | Deterministic synthetic auction streams and many query shapes over `Person`/`Auction`/`Bid`. | Already active as generated-profile evidence; Q3/Q8 remain the best checked profile rows. |
| Yahoo Streaming Benchmark | Synthetic ad-event pipeline: JSON parse, filter/project, ad-to-campaign join, 10-second campaign windows, Redis sink in the original system. | Current local version is a useful GC-pressure control; not a 1M case-study win. |
| RIoTBench | IoT tasks/applications coupled to real smart-city/fitness style observation streams. | Current local generated probe is useful, but the next provenance step should use real RIoTBench-style input. |
| DSPBench | 15 DSPS applications from Finance, Telecommunications, Sensor Networks, Social Networks, and other domains, with workload characterization including memory occupation. | Best new candidate family because it offers varied stream topologies without being only one relational query. |
| Theodolite | Four Industrial IoT stream-processing benchmarks with load generators and implementations for Kafka Streams, Flink, Hazelcast Jet, and Beam. | Good methodology source, but distributed/Kubernetes services can hide allocator effects; port local kernels first. |
| HiBench Streaming | Spark/Flink/Storm/Gearpump streaming workloads: identity, repartition, stateful wordcount, fixed window. | Easy controls, but too small/simple for a primary Rift case study. |
| BigDataBench | Broad big-data/AI suite with streaming among several workload types and real-world datasets. | Useful workload catalogue; likely too broad/heavy for the immediate next implementation. |
| ShuffleBench / SH-Bench | Large-scale stream shuffling/routing to state-local aggregations, inspired by observability workloads. | Interesting if we want routing/shuffle object pressure; less directly region-lifetime-friendly than append/window ETL. |
| RiverBench RDF streaming | Real-life RDF stream datasets and streaming tasks including serialization/deserialization throughput. | Potentially object-heavy parser/record workload, but RDF/network serialization may dominate memory-management effects. |
| LogHub / LogPAI logs | Real HDFS/BGL/Spark/Thunderbird-style system logs with multi-million-line datasets. | Implemented first as `LogHubRegionMatrix` over real BGL; useful modest real-input row, but not yet the missing GC-heavy flagship. |
| DaCapo / Renaissance / SPECjbb2015 | General JVM/managed-runtime benchmark context. | Deprioritized for primary Rift evidence because lifetimes are not stream-structured. |

Sources:

- Apache Beam NEXMark: https://beam.apache.org/documentation/sdks/java/testing/nexmark/
- Yahoo Streaming Benchmark writeup: https://www.tumblr.com/yahooeng/135321837876/benchmarking-streaming-computation-engines-at
- RIoTBench paper/resource: https://www.canr.msu.edu/resources/Riotbench-an-iot-benchmark-for-distributed-stream-processing-systems
- DSPBench paper/resource: https://arpi.unipi.it/handle/11568/1066388
- DSPBench Zenodo record: https://zenodo.org/record/4671407
- Theodolite benchmarks: https://www.theodolite.rocks/theodolite-benchmarks/
- HiBench repository: https://github.com/Intel-bigdata/HiBench
- BigDataBench: https://www.benchcouncil.org/BigDataBench/index.html
- ShuffleBench replication package: https://zenodo.org/records/10667717/latest
- RiverBench: https://riverbench.github.io/v/latest/
- LogHub: https://github.com/logpai/loghub
- LogHub dataset table: https://github.com/logpai/loghub/blob/master/docs/datasets.md

## Candidate Ranking

| Rank | Candidate | Why it fits Rift | First action |
|---:|---|---|---|
| 1 | DSPBench local-kernel subset | Broadest stream-processing benchmark family found in this pass: finance, telecom, sensor, social, and other applications; paper explicitly characterizes workload and memory occupation. | Inspect `GMAP/DSPBench` or the paper app list, choose 2-3 object-heavy kernels, and port local heap/SafeZone/Rift kernels without Storm/Spark runtime. |
| 2 | Real RIoTBench-style input | Actual RIoTBench uses real IoT observation streams, unlike our current generated probe. | Find/download a public CITY/FIT-style dataset, wire `RIOTBENCH_INPUT`, and rerun q1/q2 before changing operators. |
| 3 | More LogHub / real log variants | Real BGL has now been tried and gives modest throughput/RSS/tail evidence, not a huge-GC case. Other LogHub datasets may still differ if they force richer parsing/template/session objects. | Keep BGL as baseline; only try HDFS/Thunderbird/Spark if the query materializes more objects than the current line/token/window path. |
| 4 | NEXMark Beam-default expansion | Auction streams have generated event objects, joins, windows, and output objects with explicit window/session lifetimes. Q3 is the best new checked row; Q0/Q11 are trusted wins; Q1/Q8 remain useful profile rows. | Keep as active profile/regression evidence; do not claim exact Beam runner evidence. |
| 5 | Theodolite local UC subset | Industrial IoT microservice benchmarks with load generators and UC1-UC4 stream shapes. | Port one local UC kernel only if DSPBench/real RIoTBench do not produce a stronger target; avoid Kubernetes/Kafka in headline memory runs. |
| 6 | Yahoo-style ad stream | Ad-view pipelines match filter/project/campaign-lookup/window-count structure, but our 1M Q2 row does not beat heap. | Keep `YahooAdRegionMatrix` as a control; improve provenance/schema later, not as the next speed target. |
| 7 | ShuffleBench / SH-Bench | Routing/shuffle to state-local aggregation may allocate many route/envelope objects. | Use only if we want a routing/object-envelope stress test; not a first region-lifetime case study. |
| 8 | HiBench Streaming | Identity/repartition/stateful wordcount/fixed-window are easy to port and compare. | Use as simple controls; too small/simple for primary evidence. |
| 9 | BigDataBench streaming / RiverBench RDF streaming | BigDataBench offers broad datasets; RiverBench offers real RDF stream datasets that may be parser/object heavy. | Keep as later workload catalogues; likely higher setup/parser/serialization noise. |
| 10 | DaCapo / Renaissance / SPECjbb2015 | GC-heavy in places, but not stream-topology-first. | Deprioritized as primary Rift performance evidence. |

## Gates

A candidate becomes a serious Rift case study only if:

- heap shows material memory pressure by median/max GC time or allocation
  attribution;
- Rift beats heap and improved SafeZone by about `>=10%`, or materially reduces
  GC/RSS with `<=5%` elapsed overhead;
- the heap and Rift programs remain logically aligned;
- checked mode is added only after the relevant checked operator passes a
  focused gate.

## Immediate Use

Treat Wikimedia real pageviews/clickstream, Common Crawl real WET/WAT, official
Linear Road input, GH Archive byte-slice rows, and LogHub BGL as
ceiling/modest-win controls unless a future operator changes their allocation
shape. Keep NEXMark Q3/Q8, Yahoo Q2, and RIoTBench q1 as stream-GC
profile/regression controls, but make checked operator overhead reduction and
DSPBench / real RIoTBench triage the next engineering focus. The next
benchmark-search action should be DSPBench local-kernel triage, not another
generic JVM suite.

## LogHub BGL Update

`LogHubRegionMatrix` now runs against the real BGL log:
`/Users/siyaoliu/rift/cache/benchmark-data/loghub/BGL/BGL.log`
(`4747963` lines, `743185031` bytes).

The first 100k/1M medians and a full-file q2 scale probe show that LogHub BGL
is a real-input modest-win/control benchmark, not the missing GC-heavy flagship:

- at 1M q1/q2, heap GC is `99.271 ms` / `157.198 ms` inside roughly `5.6 s`;
- region rows remove timed GC and modestly improve selected rows;
- at full-file q2, heap spends `595.599 ms` in GC inside `32161.391 ms`;
- checked scoped page-token full-file q2 is faster and lower-RSS than heap,
  but the GC share is still under 2% of elapsed.

This is enough to keep real log streams in the benchmark ladder, but not enough
to stop searching for a stronger real GC-heavy data-stream workload.
