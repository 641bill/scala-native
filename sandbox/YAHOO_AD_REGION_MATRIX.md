# Yahoo Ad Region Matrix

Date: 2026-05-01

Status: new Yahoo Streaming Benchmark-style local memory-management probe.
This is not an exact Yahoo/Flink/Kafka/Redis reproduction. The first version is
intentionally local and preloaded/generated so external systems do not hide GC
and allocation costs.

## Workload

Implementation:

- `sandbox/src/main/scala-next/YahooAdRegionMatrix.scala`
- `sandbox/run_yahoo_ad_region_matrix.sh`

Queries:

- `q0-parse`: one ordinary ad-event object per input event.
- `q1-filter`: raw ad-event object plus projected view object for view events.
- `q2-campaign-window`: view objects retained in campaign windows while
  durable campaign counters stay in primitive heap arrays.

Modes:

- `heap`: ordinary Scala heap objects.
- `safezone-current`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=0`.
- `safezone-improved`: closeable SafeZone buckets with `SAFEZONE_ROOTS_MODE=1`.
- `rift-hp`: trusted HPZone per event bucket.
- `rift-streaming`: trusted Streaming region per event bucket.

No checked mode is included yet. Add checked mode only if the matching checked
append/window or fold operator has already cleared a focused gate.

## Commands

Compile:

```bash
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
```

20k smoke:

```bash
YAHOO_AD_EVENTS=20000 \
YAHOO_AD_BENCHMARK_RUNS=1 \
YAHOO_AD_WARMUPS=0 \
YAHOO_AD_OUTPUT_DIR=/tmp/yahoo-ad-smoke \
zsh sandbox/run_yahoo_ad_region_matrix.sh
```

100k medians:

```bash
YAHOO_AD_EVENTS=100000 \
YAHOO_AD_BENCHMARK_RUNS=3 \
YAHOO_AD_WARMUPS=1 \
YAHOO_AD_OUTPUT_DIR=/tmp/yahoo-ad-100k \
YAHOO_AD_BUILD=0 \
zsh sandbox/run_yahoo_ad_region_matrix.sh
```

1M medians:

```bash
YAHOO_AD_EVENTS=1000000 \
YAHOO_AD_BENCHMARK_RUNS=3 \
YAHOO_AD_WARMUPS=1 \
YAHOO_AD_OUTPUT_DIR=/tmp/yahoo-ad-1m \
YAHOO_AD_BUILD=0 \
zsh sandbox/run_yahoo_ad_region_matrix.sh
```

## Validation

- `ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile` passed
  after adding the matrix.
- 20k smoke matched checksum/output count across all modes and all three
  queries. Q2 was rerun after fixing the output path and now reports nonzero
  campaign-window output counts.
- 100k 3-run medians matched checksum/output count across all modes and all
  three queries.
- 1M 3-run Q2 medians matched checksum/output count across all modes.
- The runner summary includes median GC, max GC, runs-with-GC, max collection
  count, RSS, Rift op time, region objects, and open/close/reset counts.

## 20k Smoke

This is plumbing evidence only; do not use it as a headline result.

| Query | Best heap/SafeZone/Rift signal | Interpretation |
|---|---|---|
| `q0-parse` | heap collected once (`max_gc_ms=1.587`); HPZone was fastest in the single smoke row. | The shape can trigger heap collection even at 20k, but this is not a median. |
| `q1-filter` | heap collected once (`max_gc_ms=1.123`); improved SafeZone and Streaming were close. | Promising enough for 100k/1M medians. |
| `q2-campaign-window` | fixed rerun has `5003` outputs at 20k and matching checksums. | Validated as a real output-producing window probe. |

## 100k Medians

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q0-parse | heap | 10.101 | 0.000 | 3.978 | 1 | 0.000 | 0 | 39485440 | 100000 |
| q0-parse | safezone-improved | 10.776 | 0.000 | 1.243 | 1 | 0.000 | 0 | 27344896 | 100000 |
| q0-parse | rift-hp | 11.051 | 0.000 | 0.964 | 1 | 0.010 | 100000 | 27213824 | 100000 |
| q0-parse | rift-streaming | 11.114 | 0.000 | 0.968 | 1 | 0.010 | 100000 | 27279360 | 100000 |
| q1-filter | heap | 10.867 | 0.000 | 6.981 | 1 | 0.000 | 0 | 39485440 | 125002 |
| q1-filter | safezone-improved | 12.085 | 0.000 | 2.110 | 1 | 0.000 | 0 | 28753920 | 125002 |
| q1-filter | rift-streaming | 12.056 | 0.000 | 0.995 | 1 | 0.019 | 125002 | 28721152 | 125002 |
| q2-campaign-window | heap | 12.748 | 1.596 | 1.617 | 3 | 0.000 | 0 | 12664832 | 25002 |
| q2-campaign-window | safezone-improved | 10.385 | 0.000 | 0.813 | 1 | 0.000 | 0 | 14123008 | 25002 |
| q2-campaign-window | rift-hp | 10.428 | 0.000 | 0.564 | 1 | 0.005 | 25002 | 14106624 | 25002 |
| q2-campaign-window | rift-streaming | 10.773 | 0.000 | 0.771 | 1 | 0.006 | 25002 | 14172160 | 25002 |

## 1M Q2 Medians

| Query | Mode | Median ms | Median GC ms | Max GC ms | Runs with GC | Rift op ms | Region objects | RSS bytes | Outputs |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| q2-campaign-window | heap | 104.512 | 6.253 | 6.381 | 3 | 0.000 | 0 | 39501824 | 250003 |
| q2-campaign-window | safezone-improved | 108.173 | 2.652 | 4.314 | 3 | 0.000 | 0 | 40943616 | 250003 |
| q2-campaign-window | rift-hp | 105.216 | 2.404 | 3.744 | 3 | 0.033 | 250003 | 40960000 | 250003 |
| q2-campaign-window | rift-streaming | 105.961 | 2.111 | 3.760 | 3 | 0.037 | 250003 | 41025536 | 250003 |

## Interpretation

- Q0/Q1 show lower RSS and lower max-GC in region modes, but heap remains
  fastest at 100k. Do not use them as Rift wins.
- Q2 looked promising at 100k, but the 1M rerun does not clear the case-study
  gate. Rift cuts median GC from `6.253 ms` to about `2.1-2.4 ms`, but heap is
  still slightly fastest.
- The Yahoo-style ad stream should stay as a useful GC-pressure/control
  candidate, not a headline case study yet.

## Next Step

Do not tune Yahoo-specific code next. If this family is revisited, add a
checked append/window version only after a focused checked operator passes its
own gate, or move to the RIoTBench-style candidate for a different
stream-processing shape.
