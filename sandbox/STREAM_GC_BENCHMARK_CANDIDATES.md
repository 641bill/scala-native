# Stream GC Benchmark Candidates

Date: 2026-05-01

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

## Candidate Ranking

| Rank | Candidate | Why it fits Rift | First action |
|---:|---|---|---|
| 1 | DSPBench local-kernel subset | Broadest stream-processing benchmark family found in this pass: finance, telecom, sensor, social, and other applications; paper explicitly characterizes workload and memory occupation. | Inspect `GMAP/DSPBench` or the paper app list, choose 2-3 object-heavy kernels, and port local heap/SafeZone/Rift kernels without Storm/Spark runtime. |
| 2 | Real RIoTBench-style input | Actual RIoTBench uses real IoT observation streams, unlike our current generated probe. | Find/download a public CITY/FIT-style dataset, wire `RIOTBENCH_INPUT`, and rerun q1/q2 before changing operators. |
| 3 | NEXMark Beam-default expansion | Auction streams have generated event objects, joins, windows, and output objects with explicit window/session lifetimes. Q3 is the best new checked row; Q0/Q11 are trusted wins; Q1/Q8 remain useful profile rows. | Keep as active profile/regression evidence; do not claim exact Beam runner evidence. |
| 4 | Theodolite local UC subset | Industrial IoT microservice benchmarks with load generators and UC1-UC4 stream shapes. | Port one local UC kernel only if DSPBench/real RIoTBench do not produce a stronger target; avoid Kubernetes/Kafka in headline memory runs. |
| 5 | Yahoo-style ad stream | Ad-view pipelines match filter/project/campaign-lookup/window-count structure, but our 1M Q2 row does not beat heap. | Keep `YahooAdRegionMatrix` as a control; improve provenance/schema later, not as the next speed target. |
| 6 | ShuffleBench / SH-Bench | Routing/shuffle to state-local aggregation may allocate many route/envelope objects. | Use only if we want a routing/object-envelope stress test; not a first region-lifetime case study. |
| 7 | HiBench Streaming | Identity/repartition/stateful wordcount/fixed-window are easy to port and compare. | Use as simple controls; too small/simple for primary evidence. |
| 8 | BigDataBench streaming / RiverBench RDF streaming | BigDataBench offers broad datasets; RiverBench offers real RDF stream datasets that may be parser/object heavy. | Keep as later workload catalogues; likely higher setup/parser/serialization noise. |
| 9 | DaCapo / Renaissance / SPECjbb2015 | GC-heavy in places, but not stream-topology-first. | Deprioritized as primary Rift performance evidence. |

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

Treat Wikimedia real pageviews/clickstream, Common Crawl real WET, and official
Linear Road input as parked ceiling/regression controls unless a future
operator changes their allocation shape. Keep NEXMark Q3/Q8, Yahoo Q2, and
RIoTBench q1 as stream-GC profile/regression controls, but make checked
operator overhead reduction the next engineering focus unless DSPBench or real
RIoTBench input shows stronger heap pressure. The next benchmark-search action
should be DSPBench triage, not another generic JVM suite.
