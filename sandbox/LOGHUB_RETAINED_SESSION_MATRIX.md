# LogHub Retained Session Matrix

Last updated: 2026-05-21 07:56 CEST

Status: real streaming-input retained session/join triage plus the first named
Wikimedia clickstream retained-session workload. This matrix was added after
the active-window LogHub q3 row showed heap-cap pressure but not a clean
throughput/RSS win. The goal is to test whether a more naturally retained
session/join shape over real LogHub HDFS/Spark/Windows lines or Wikimedia
clickstream rows creates material heap GC or fixed-memory/RSS pressure.

This is not an exact LogHub paper benchmark. It is a local single-process
streaming replay over the public HDFS log.

## Benchmark Shape

Source:

`/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log`

Queries:

- `session`: stream HDFS log lines, derive a severity/template/session key,
  allocate ordinary session events, retain them in per-epoch dictionaries, and
  close/drop timestamp-local state after the active-epoch boundary.
- `join`: stream the same lines, route records into left/right retained
  per-key tables by record parity, emit join matches, and close/drop
  timestamp-local state after the active-epoch boundary.
- `wikimedia-clickstream-session`: stream Wikimedia clickstream TSV rows,
  derive session keys from source article, target article, and link kind,
  retain ordinary session events plus per-key aggregate entries, and close/drop
  epoch-local state at the active-epoch boundary. This is a named retained
  clickstream workload, not a generic line-session stress row.

Headline modes:

| Mode | Meaning |
|---|---|
| `heap-gc` | Natural heap/GC retained object implementation. |
| `checked-rift` | Checked Rift direct streaming/epoch region implementation. |
| `checked-region-scoped` | Checked Rift API over the SafeZone-backed scoped backend. |

## Commands

20k smoke:

```sh
LOGHUB_SESSION_BUILD=1 \
LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_SESSION_RECORDS=20000 \
LOGHUB_SESSION_RECORDS_PER_EPOCH=2500 \
LOGHUB_SESSION_ACTIVE_EPOCHS=4 \
LOGHUB_SESSION_KEY_SPACE=4096 \
LOGHUB_SESSION_BENCHMARK_RUNS=1 \
LOGHUB_SESSION_WARMUPS=0 \
LOGHUB_SESSION_WORKLOADS="session join" \
LOGHUB_SESSION_MODES="heap-gc checked-rift checked-region-scoped" \
LOGHUB_SESSION_OUTPUT_DIR=/private/tmp/loghub-retained-session-smoke-20260516 \
zsh sandbox/run_loghub_retained_session_matrix.sh
```

1M active-16 triage:

```sh
LOGHUB_SESSION_BUILD=0 \
LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1/HDFS.log \
LOGHUB_SESSION_RECORDS=1000000 \
LOGHUB_SESSION_RECORDS_PER_EPOCH=25000 \
LOGHUB_SESSION_ACTIVE_EPOCHS=16 \
LOGHUB_SESSION_KEY_SPACE=65536 \
LOGHUB_SESSION_BENCHMARK_RUNS=3 \
LOGHUB_SESSION_WARMUPS=1 \
LOGHUB_SESSION_WORKLOADS="session join" \
LOGHUB_SESSION_MODES="heap-gc checked-rift checked-region-scoped" \
LOGHUB_SESSION_OUTPUT_DIR=/private/tmp/loghub-retained-session-hdfs-1m-active16-l2-20260516 \
zsh sandbox/run_loghub_retained_session_matrix.sh
```

## 20k Smoke

The 20k smoke matched checksum/output across all modes.

| Workload | Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---|---:|---:|---:|---:|---:|
| session | `heap-gc` | `155.874` | `0.527` | `8470528` | `642625343242307127` | `15043` |
| session | `checked-rift` | `134.191` | `0.000` | `8388608` | `642625343242307127` | `15043` |
| session | `checked-region-scoped` | `131.387` | `0.000` | `8552448` | `642625343242307127` | `15043` |
| join | `heap-gc` | `141.055` | `0.462` | `8421376` | `-3039645399054221914` | `0` |
| join | `checked-rift` | `136.381` | `0.000` | `8273920` | `-3039645399054221914` | `0` |
| join | `checked-region-scoped` | `132.541` | `0.000` | `8421376` | `-3039645399054221914` | `0` |

## 1M Active-16 Triage

Raw summary:

`/private/tmp/loghub-retained-session-hdfs-1m-active16-l2-20260516/summary.tsv`

This run was executed inside the sandbox, so `/usr/bin/time -l` could not read
maximum RSS for the 1M rows. Use the L2 timing/GC fields for triage only; rerun
outside the sandbox if a row becomes presentation-worthy.

| Workload | Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Checksum | Output | Retained object proxy | Max live proxy |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| session | `heap-gc` | `6595.172` | `82.341` | `90.781` | `3/3` | `0.000` | `0` | `-4251312673673367471` | `831923` | `1831923` | `732847` |
| session | `checked-rift` | `6578.258` | `5.572` | `5.858` | `3/3` | `0.885` | `1831932` | `-4251312673673367471` | `831923` | `1831923` | `732850` |
| session | `checked-region-scoped` | `6997.852` | `63.979` | `67.797` | `3/3` | `0.000` | `0` | `-4251312673673367471` | `831923` | `1831923` | `732850` |
| join | `heap-gc` | `6837.810` | `9.474` | `13.881` | `3/3` | `0.000` | `0` | `4282190220497908364` | `0` | `1000000` | `400000` |
| join | `checked-rift` | `6927.376` | `6.731` | `7.516` | `3/3` | `1.624` | `1000006` | `4282190220497908364` | `0` | `1000000` | `400002` |
| join | `checked-region-scoped` | `6681.557` | `77.349` | `77.610` | `3/3` | `0.000` | `0` | `4282190220497908364` | `0` | `1000000` | `400002` |

## Decision

Park this as real-streaming-input retained-object control evidence.

- It is a true streaming-file replay and it retains ordinary objects until
  epoch/session boundaries.
- It does not become the GC-heavy real-input flagship: session heap GC is only
  about `1.2%` of median L2 elapsed, and join heap GC is about `0.1%`.
- The session checked Rift row is effectively tied with heap and removes most
  timed GC, but the parser/template/hash/query work dominates. The join row is
  mixed: checked scoped is fastest, but heap GC is too small for a strong
  memory-management claim.
- The next stronger row remains the Broom/Naiad-style retained dataflow ladder,
  which spends a much larger share of elapsed time in heap GC at 5M/20M
  active-16.

## Windows Archive-Member Follow-Up

Date/time: 2026-05-17 22:20 CEST.

The next high-cardinality LogHub check used the larger local Windows archive
without extracting it:

`LOGHUB_SESSION_INPUT=tar.gz:/Users/siyaoliu/rift/cache/benchmark-data/loghub/Windows.tar.gz!Windows.log`

The first smoke had to be rerun after relinking `LogHubRetainedSessionMatrix`;
the stale binary did not yet include archive-member input support.

20k smoke:

| Workload | Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---|---:|---:|---:|---:|---:|
| session | `heap-gc` | `228.033` | `0.223` | `21118976` | `7807814513177854722` | `13871` |
| session | `checked-rift` | `217.695` | `0.332` | `19726336` | `7807814513177854722` | `13871` |
| session | `checked-region-scoped` | `225.719` | `2.119` | `19857408` | `7807814513177854722` | `13871` |
| join | `heap-gc` | `218.939` | `2.421` | `28049408` | `-9158567952398434248` | `0` |
| join | `checked-rift` | `224.693` | `0.350` | `27295744` | `-9158567952398434248` | `0` |
| join | `checked-region-scoped` | `230.593` | `3.474` | `27426816` | `-9158567952398434248` | `0` |

The join workload is not useful on this input because it emits zero matches.
The 1M follow-up therefore scaled only `session`.

1M Windows session source:
`/private/tmp/loghub-retained-session-windows-1m-session-l2-20260517`.

| Mode | External real s | RSS bytes | Median ms | Median GC ms | Max GC ms | Runs with GC | Retained proxy | Max live proxy | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `51.90` | `318111744` | `17059.969` | `125.783` | `153.059` | `3/3` | `1443591` | `589737` | `443861` |
| `checked-rift` | `54.92` | `63389696` | `17808.808` | `14.753` | `15.408` | `3/3` | `1443591` | `589740` | `443861` |
| `checked-region-scoped` | `51.58` | `63881216` | `17187.744` | `169.069` | `171.963` | `3/3` | `1443591` | `589740` | `443861` |

Decision: park as real-streaming-input RSS/control evidence. The larger
Windows stream confirms a large RSS reduction for region rows, but heap GC is
only about `0.7%` of L2 elapsed and the checked Rift row loses throughput.
The checked scoped row is an external-time near-tie, not a clear win. Parser,
archive, and hash/session CPU dominate.

## Spark Archive-Wide Streaming Follow-Up

Date/time: 2026-05-17 23:36 CEST.

The Spark archive contains thousands of small log files. A first attempt using
`tar.gzdir:/archive!prefix` was functionally correct but not useful: each
member was opened with a separate `tar -xOzf archive member`, so the 20k heap
smoke spent about `55 s` mostly rescanning the compressed tarball. The useful
Spark row therefore uses archive-wide streaming:

`LOGHUB_SESSION_INPUT=tar.gzcat:/Users/siyaoliu/rift/cache/benchmark-data/loghub/Spark.tar.gz`

This keeps the source compressed and streams the concatenated archive contents
without extracting the archive. The archive-wide stream reports `input_files=1`
because it is one streaming source, even though the original tar contains many
members.

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-retained-session-spark-cat-20260517/smoke-20k/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-retained-session-spark-cat-20260517/l1-1m/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-retained-session-spark-cat-20260517/l2-1m/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-retained-session-spark-cat-20260517/heapcaps-1m/summary.tsv`

20k smoke:

| Workload | Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---|---:|---:|---:|---:|---:|
| session | `heap-gc` | `206.572` | `4.140` | `21544960` | `-435595809917860422` | `17689` |
| session | `checked-rift` | `219.635` | `0.397` | `14843904` | `-435595809917860422` | `17689` |
| session | `checked-region-scoped` | `204.379` | `1.276` | `14974976` | `-435595809917860422` | `17689` |
| join | `heap-gc` | `190.694` | `5.544` | `28131328` | `8799025668600574012` | `0` |
| join | `checked-rift` | `220.861` | `0.458` | `18464768` | `8799025668600574012` | `0` |
| join | `checked-region-scoped` | `215.222` | `4.526` | `18563072` | `8799025668600574012` | `0` |

The Spark join emits zero matches under the current parity/key query, so only
`session` was scaled.

1M L1 final-clean session:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `27.62` | `21.94` | `0.28` | `390561792` | `-1938898183938054371` | `770310` | `1770310` | `714833` |
| `checked-rift` | `20.30` | `19.76` | `0.10` | `72908800` | `-1938898183938054371` | `770310` | `1770310` | `714836` |
| `checked-region-scoped` | `19.85` | `19.86` | `0.06` | `73023488` | `-1938898183938054371` | `770310` | `1770310` | `714836` |

1M L2 session:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `6642.276` | `6550.577` | `6643.644` | `140.639` | `169.764` | `3/3` | `0.000` | `0` | `508149760` |
| `checked-rift` | `6396.869` | `6385.812` | `6434.709` | `14.407` | `14.889` | `3/3` | `1.121` | `1770319` | `72941568` |
| `checked-region-scoped` | `6615.206` | `6575.994` | `6670.608` | `196.520` | `199.531` | `3/3` | `0.000` | `0` | `73138176` |

Heap-cap probes:

| Heap cap | Status | External real s | RSS bytes | Notes |
|---|---|---:|---:|---|
| `384M` | pass | `19.91` | `344850432` | checksum/output matched |
| `256M` | pass | `19.85` | `277676032` | checksum/output matched |
| `128M` | fail | `3.05` | `143409152` | out of heap space |
| `96M` | fail | `2.16` | `107020288` | out of heap space |
| `64M` | fail | `1.23` | `72761344` | out of heap space |

Decision: keep Spark session as the strongest LogHub real-streaming
fixed-memory/RSS row so far. It is not a pure GC-time flagship: heap median GC
is about `2.1%` of L2 elapsed. It is, however, a real compressed-stream
retained-object row where checked modes cut RSS by about `81%`, checked Rift
is modestly faster in L2, and heap fails below `128M` while checked rows
complete around `73 MB` RSS. Use it as real-input retained-state evidence, not
as an exact LogHub paper benchmark.

## Wikimedia Enwiki Clickstream Retained Line-Session Triage

Date/time: 2026-05-18 00:44 CEST.

This row reuses `LogHubRetainedSessionMatrix` as a generic retained
line-session benchmark over a different real compressed stream. It is not a
LogHub row and not a final named Wikimedia operator. The purpose is to test the
investigation hypothesis that a large real stream with retained ordinary
per-line/session objects is stronger than primitive/preloaded clickstream
counting.

Input:

`LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-enwiki-2026-03.tsv.gz`

Configuration:

`records=1000000`, `records_per_epoch=25000`, `active_epochs=16`,
`key_space=262144`, workload `session`.

Raw summaries:

- `/private/tmp/wikimedia-retained-line-session-smoke-20260518/summary.tsv`
- `/private/tmp/wikimedia-retained-line-session-1m-l1-20260518/summary.tsv`
- `/private/tmp/wikimedia-retained-line-session-1m-l2-20260518/summary.tsv`
- `/private/tmp/wikimedia-retained-line-session-1m-heapcaps-20260518/summary.tsv`
- `/private/tmp/wikimedia-retained-line-session-1m-region-cap-128m-20260518/summary.tsv`
- `/private/tmp/wikimedia-retained-line-session-1m-region-cap-64m-20260518/summary.tsv`

20k smoke:

| Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|
| `heap-gc` | `117.290` | `0.254` | `21807104` | `-6891630890320843875` | `19623` |
| `checked-rift` | `110.601` | `0.266` | `19824640` | `-6891630890320843875` | `19623` |
| `checked-region-scoped` | `98.366` | `2.348` | `20021248` | `-6891630890320843875` | `19623` |

1M L1 final-clean:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `15.54` | `15.02` | `0.14` | `864436224` | `-6192260257488813902` | `953730` | `1953730` | `781552` |
| `checked-rift` | `14.15` | `13.99` | `0.13` | `136462336` | `-6192260257488813902` | `953730` | `1953730` | `781555` |
| `checked-region-scoped` | `14.98` | `14.74` | `0.08` | `136642560` | `-6192260257488813902` | `953730` | `1953730` | `781555` |

1M L2 standard stats:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `4826.234` | `4797.992` | `4890.285` | `166.100` | `217.036` | `2/3` | `0.000` | `0` | `870252544` |
| `checked-rift` | `5058.376` | `4949.777` | `5218.312` | `11.537` | `21.435` | `3/3` | `3.121` | `1953739` | `136511488` |
| `checked-region-scoped` | `5216.981` | `5195.984` | `5261.575` | `197.466` | `215.615` | `3/3` | `0.000` | `0` | `138821632` |

Heap-cap probes:

| Mode | Cap | Status | External real s | RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-gc` | `512M` | pass | `5.21` | `437026816` | checksum/output matched |
| `heap-gc` | `256M` | fail | `2.22` | `201654272` | OOM in heap array allocation |
| `heap-gc` | `128M` | fail | `0.00` | `5619712` | OOM at startup/allocation |
| `checked-rift` | `128M` | pass | `5.04` | `136331264` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `128M` | pass | `5.22` | `136626176` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-rift` | `64M` | pass | `4.96` | `136331264` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `64M` | pass | `5.64` | `136511488` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |

Decision: keep as a useful real-streaming retained-state RSS/fixed-memory row.

- The input remains compressed and is consumed as a stream; no preloaded
  clickstream row array is used.
- L1 checked Rift is `14.15 s` versus heap `15.54 s`, with RSS reduced from
  `864 MB` to `136 MB`.
- Heap fails at `256M` and `128M`, while checked rows complete under `128M`
  and `64M` caps.
- This is still not a GC-time flagship: heap L2 GC is `166.100 ms` inside
  `4826.234 ms`, about `3.4%`. The L2 checked row is slower than heap because
  parser/hash/session work and standard-stat measurement dominate; use L1 for
  headline elapsed and L2 only for interpretation.

## Wikimedia Enwiki Clickstream Retained Session, Named Workload

Date/time: 2026-05-18 03:49 CEST.

This row promotes the generic clickstream triage into a named retained
workload: `wikimedia-clickstream-session`. The input is still the compressed
real Wikimedia clickstream TSV file, but keys and values now come from TSV
fields instead of the generic log-token extractor:

- session key: source article, target article, and link kind;
- value: click count;
- retained objects: one clickstream session event per row plus per-key
  aggregate entries, retained until epoch close.

Input:

`LOGHUB_SESSION_INPUT=/Users/siyaoliu/rift/cache/benchmark-data/wikimedia/clickstream-enwiki-2026-03.tsv.gz`

Configuration:

`records=1000000`, `records_per_epoch=25000`, `active_epochs=16`,
`key_space=262144`, workload `wikimedia-clickstream-session`.

Raw summaries:

- `/private/tmp/wikimedia-named-clickstream-smoke-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-1m-l1-seq-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-1m-l2-seq-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-1m-heapcaps-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-1m-regioncap-128m-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-1m-regioncap-64m-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-5m-l1-rss-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-5m-l2-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-5m-heapcaps-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-5m-regioncap-64m-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-l1-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-l2-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-l1-3run-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-l2-3run-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-heapcaps-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-regioncap-64m-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-full-l1-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-full-l2-20260518/summary.tsv`

20k smoke:

| Mode | Median ms | GC ms | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|
| `heap-gc` | `55.070` | `0.141` | `21413888` | `5515862501352978002` | `18983` |
| `checked-rift` | `42.434` | `0.227` | `19759104` | `5515862501352978002` | `18983` |
| `checked-region-scoped` | `44.434` | `2.572` | `19972096` | `5515862501352978002` | `18983` |

1M L1 final-clean, sequential:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `6.50` | `6.36` | `0.12` | `783663104` | `250002331971566003` | `922453` | `1922453` | `771045` |
| `checked-rift` | `5.81` | `5.71` | `0.08` | `137871360` | `250002331971566003` | `922453` | `1922453` | `771048` |
| `checked-region-scoped` | `6.47` | `6.38` | `0.07` | `138018816` | `250002331971566003` | `922453` | `1922453` | `771048` |

1M L2 standard stats, sequential:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `2038.262` | `1889.303` | `2071.888` | `150.157` | `178.978` | `3/3` | `0.000` | `0` | `785203200` |
| `checked-rift` | `1952.438` | `1946.964` | `1953.228` | `9.237` | `9.805` | `3/3` | `1.871` | `1922462` | `137887744` |
| `checked-region-scoped` | `2109.767` | `2102.079` | `2111.042` | `146.274` | `160.154` | `3/3` | `0.000` | `0` | `138149888` |

Heap-cap probes:

| Mode | Cap | Status | External real s | RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-gc` | `512M` | fail | `2.34` | `437813248` | OOM in heap array allocation |
| `heap-gc` | `256M` | fail | `0.97` | `193314816` | OOM in heap array allocation |
| `heap-gc` | `128M` | fail | `0.00` | `5570560` | OOM at startup/allocation |
| `heap-gc` | `64M` | fail | `0.00` | `5570560` | OOM at startup/allocation |
| `checked-rift` | `128M` | pass | `5.87` | `137871360` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `128M` | pass | `6.41` | `138035200` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-rift` | `64M` | pass | `5.81` | `137854976` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `64M` | pass | `6.55` | `138067968` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |

5M scale-up, L1 final-clean, sequential:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `28.77` | `28.52` | `0.22` | `1539489792` | `-5539074761685310486` | `4649530` | `9649530` | `778586` |
| `checked-rift` | `27.86` | `27.61` | `0.24` | `138412032` | `-5539074761685310486` | `4649530` | `9649530` | `778589` |
| `checked-region-scoped` | `30.23` | `30.10` | `0.12` | `140886016` | `-5539074761685310486` | `4649530` | `9649530` | `778589` |

5M scale-up, L2 standard stats, sequential:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `9459.416` | `9293.328` | `10215.546` | `334.176` | `1066.487` | `3/3` | `0.000` | `0` | `1953153024` |
| `checked-rift` | `9486.647` | `9451.145` | `9534.568` | `46.492` | `46.991` | `3/3` | `10.699` | `9649569` | `138428416` |
| `checked-region-scoped` | `10156.911` | `10145.822` | `10162.828` | `733.741` | `740.655` | `3/3` | `0.000` | `0` | `138838016` |

5M heap-cap and checked-cap probes:

| Mode | Cap | Status | External real s | RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-gc` | `1G` | fail | `7.63` | `831127552` | OOM in heap array allocation |
| `heap-gc` | `768M` | fail | `3.48` | `527319040` | OOM in heap array allocation |
| `heap-gc` | `512M` | fail | `1.83` | `374325248` | OOM in heap array allocation |
| `checked-rift` | `64M` | pass | `9.37` | `136101888` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `64M` | pass | `10.49` | `136298496` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |

10M initial feasibility scale-up, one-run L1 final-clean:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `24.23` | `22.84` | `0.56` | `1155743744` | `8006060730683441349` | `9323019` | `19323019` | `778835` |
| `checked-rift` | `19.96` | `19.37` | `0.43` | `136118272` | `8006060730683441349` | `9323019` | `19323019` | `778838` |
| `checked-region-scoped` | `21.43` | `21.02` | `0.29` | `136396800` | `8006060730683441349` | `9323019` | `19323019` | `778838` |

10M initial feasibility scale-up, one-run L2 standard stats:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Records/sec |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `22877.820` | `4058.998` | `4058.998` | `1/1` | `0.000` | `0` | `1138147328` | `437104.591` |
| `checked-rift` | `19716.840` | `99.708` | `99.708` | `1/1` | `31.049` | `19323094` | `136151040` | `507180.666` |
| `checked-region-scoped` | `21079.678` | `1703.489` | `1703.489` | `1/1` | `0.000` | `0` | `136511488` | `474390.556` |

10M scale-up, 3-run L1 final-clean:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `62.52` | `60.07` | `1.32` | `1796128768` | `8006060730683441349` | `9323019` | `19323019` | `778835` |
| `checked-rift` | `59.37` | `58.10` | `1.08` | `138412032` | `8006060730683441349` | `9323019` | `19323019` | `778838` |
| `checked-region-scoped` | `62.33` | `61.35` | `0.78` | `138706944` | `8006060730683441349` | `9323019` | `19323019` | `778838` |

10M scale-up, 3-run L2 standard stats:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `20103.863` | `19813.540` | `20848.839` | `851.023` | `885.434` | `3/3` | `0.000` | `0` | `1943896064` |
| `checked-rift` | `19635.995` | `19557.128` | `19753.105` | `96.069` | `96.128` | `3/3` | `24.329` | `19323094` | `140689408` |
| `checked-region-scoped` | `21396.025` | `21376.874` | `21566.522` | `1490.661` | `1525.790` | `3/3` | `0.000` | `0` | `138854400` |

10M heap-cap and checked-cap probes:

| Mode | Cap | Status | External real s | RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-gc` | `1G` | fail | `7.97` | `831127552` | OOM in heap array allocation |
| `heap-gc` | `768M` | fail | `3.69` | `527335424` | OOM in heap array allocation |
| `heap-gc` | `512M` | fail | `1.92` | `374358016` | OOM in heap array allocation |
| `checked-rift` | `64M` | pass | `19.39` | `136151040` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `64M` | pass | `21.08` | `136364032` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |

Full local file feasibility, one-run L1 final-clean (`35862259` rows):

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `74.23` | `72.73` | `1.24` | `2298707968` | `3903090754337931261` | `33529413` | `69391672` | `780731` |
| `checked-rift` | `69.87` | `68.48` | `1.17` | `136265728` | `3903090754337931261` | `33529413` | `69391672` | `780734` |
| `checked-region-scoped` | `76.83` | `75.65` | `0.88` | `136593408` | `3903090754337931261` | `33529413` | `69391672` | `780734` |

Full local file feasibility, one-run L2 standard stats:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Records/sec |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `72750.014` | `7122.811` | `7122.811` | `1/1` | `0.000` | `0` | `2571665408` | `492951.922` |
| `checked-rift` | `71149.579` | `318.165` | `318.165` | `1/1` | `79.786` | `69391942` | `136282112` | `504040.352` |
| `checked-region-scoped` | `76156.741` | `5582.325` | `5582.325` | `1/1` | `0.000` | `0` | `136691712` | `470900.650` |

Decision: promote this named workload above the earlier generic line-session
triage row and keep the 5M/10M scale-ups as stronger RSS/fixed-memory
evidence.

- L1 checked Rift is `5.81 s` versus heap `6.50 s`, about `10.6%` faster, and
  RSS drops from `784 MB` to `138 MB`.
- L2 checked Rift is also faster (`1952.438 ms` versus heap `2038.262 ms`)
  while reducing median timed GC from `150.157 ms` to `9.237 ms`.
- Heap fails even at a `512M` cap for this active-state setting; checked rows
  complete under `128M` and `64M` external heap caps because the retained
  clickstream objects are region-managed.
- Heap GC is `7.4%` of L2 elapsed, so this now passes the material-GC gate.
  Use it as real-streaming retained clickstream throughput/RSS/GC/fixed-memory
  evidence, with the caveat that it is a local named workload over public
  Wikimedia data, not an official Wikimedia benchmark artifact.
- At 5M, checked Rift remains L1 faster (`27.86 s` versus heap `28.77 s`) and
  cuts RSS from `1.54 GB` to `138 MB`, but the L2 timed loop is essentially
  tied (`9486.647 ms` versus heap `9459.416 ms`). Treat the 5M row primarily
  as scale-up RSS/fixed-memory and GC-tail evidence: heap max GC reaches
  `1066.487 ms` and heap fails at `1G`, `768M`, and `512M`, while checked Rift
  completes under a `64M` GC heap cap.
- At 10M, the 3-run L1 row keeps checked Rift ahead (`59.37 s` versus heap
  `62.52 s`) and cuts RSS from about `1.80 GB` to `138 MB`. The 3-run L2 row
  reports checked Rift `19635.995 ms` with `96.069 ms` GC and `24.329 ms`
  region-op, versus heap `20103.863 ms` with `851.023 ms` timed GC. Heap
  fails at `1G`, `768M`, and `512M`; checked Rift and checked scoped complete
  under a `64M` GC heap cap. Promote 10M from feasibility to report-grade
  scale-up evidence; attempt the full 35.8M-row file only if the machine has
  enough time and memory headroom.
- The one-run full-file feasibility row over all `35862259` rows also
  completes: checked Rift is L1 `69.87 s`, RSS `136 MB`, versus heap
  `74.23 s`, RSS `2.30 GB`; L2 checked Rift is `71149.579 ms` with
  `318.165 ms` GC and `79.786 ms` region-op, versus heap `72750.014 ms` with
  `7122.811 ms` timed GC. This is stronger full-input feasibility evidence,
  but keep the 10M x3 row as the report-grade median until the full-file row is
  repeated.
- 2026-05-19 inference follow-up, source
  `/private/tmp/wikimedia-full-l2-inference-20260519/summary.tsv`: the
  full-file L2 row was rerun after the owner-token/generic inference work and
  adds `checked-rift-inferred`. Heap is `71819.469 ms`, GC `5671.534 ms`,
  RSS `2.81 GB`; explicit checked Rift is `70451.516 ms`, GC `0.672 ms`,
  region-op `71.044 ms`, RSS `130.5 MB`; inferred checked Rift is
  `70022.499 ms`, GC `0.696 ms`, region-op `79.254 ms`, RSS `130.5 MB`.
  Checksums/output and region object counts match explicit checked Rift
  (`69391942` region objects). The inferred row is `429.017 ms` faster than
  explicit checked Rift in this one-run L2 pass, but the row remains
  full-file one-run evidence, not a report-grade median.

### 2026-05-20 Checked Loop-Shape Audit

Source audit found that the checked Wikimedia session loop still captured
outer mutable `checksum`, `processed`, `group`, and `done` state through the
region callback. The pre-fix L4 profile exposed this as
`scala.runtime.LongRef`, `BooleanRef`, and `IntRef` parameters in the checked
`anonfun` top frame. The fix keeps the logical query unchanged but makes the
group body use immutable base values, a local EOF flag, and a reusable parsed
field scratch object; it also places the per-group `counts` array in the
checked region for checked session modes.

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-wikimedia-loopshape-smoke-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-wikimedia-loopshape-1m-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-wikimedia-loopshape-priv/summary.tsv`

20k smoke matched checksum/output across `heap-gc`, `checked-rift`,
`checked-rift-inferred`, and `checked-region-scoped`:
checksum `4440636879622788340`, output `18167`.

1M L2 standard stats after the loop-shape cleanup:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `2012.161` | `2005.311` | `3026.927` | `148.355` | `159.938` | `3/3` | `0.000` | `0` | `587350016` | `250002331971566003` | `922453` |
| `checked-rift` | `1990.483` | `1949.478` | `2032.769` | `0.169` | `0.281` | `3/3` | `2.511` | `1922465` | `123650048` | `250002331971566003` | `922453` |
| `checked-rift-inferred` | `1939.183` | `1930.530` | `1970.763` | `0.262` | `0.331` | `3/3` | `3.667` | `1922465` | `124141568` | `250002331971566003` | `922453` |
| `checked-region-scoped` | `1928.016` | `1916.475` | `2008.246` | `26.233` | `27.908` | `3/3` | `0.000` | `0` | `125124608` | `250002331971566003` | `922453` |

Post-fix L4 profile interpretation:

- The boxed `LongRef` / `BooleanRef` / `IntRef` closure parameters disappear
  from checked top frames, so the source-shape cleanup did remove that
  accidental checked-loop overhead.
- Region allocation remains small in the sampled profile: about `3.8`
  samples/sec for checked Rift and `3.6` samples/sec for inferred checked Rift
  in the 5 s sample.
- The remaining checked overhead is not region close/open. It is mostly TSV
  field hashing/int parsing, byte-line reading, stable hashing, and the
  checked session-loop closure itself. The corrected profile classifier now
  separates `LogHubRetainedSessionMatrixHelpers.*anonfun` session-loop frames
  from parser/input frames, because the mangled closure signature includes
  `StreamingByteLineSource` even when the top-frame work is query/session-loop
  code.

The loop-shape cleanup is therefore accepted as a mutator-parity/source-shape
fix, not as a new headline speed claim. It makes the checked rows cleaner for
future profiling, but the main remaining candidate is a compiler/API-level
solution for inlineable checked region-body callbacks; a direct `inline`
`resetOpenHandle` experiment was rejected by capture checking because the
owner token became `{any}` in many benchmark bodies.

### 2026-05-20 Inferred Session Inline-Reset Split

The internal `resetOpenHandleInline` probes showed that simple/non-inline
open-handle bodies can inline and preserve region allocation, but enclosing
`inline def` wrappers lose the owner capture. `checked-rift-inferred` session
mode was therefore split out of the generic `runCheckedSessionImpl` wrapper and
uses a sandbox-only bridge to the internal inline reset helper. The logical
query is unchanged: same parser, same session tables, same per-record/event
objects, and same checksum/output. The explicit `checked-rift` mode remains on
the non-inline helper path.

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-wikimedia-inline-reset-smoke-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-wikimedia-inline-reset-1m-20260520/summary.tsv`

20k smoke matched checksum/output across `heap-gc`, `checked-rift`, and
`checked-rift-inferred`: checksum `-8642330901600858181`, output `19102`.

1M L2 standard stats after the inferred-session split:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `2044.334` | `2042.497` | `2051.658` | `158.237` | `168.740` | `3/3` | `0.000` | `0` | `590102528` | `250002331971566003` | `922453` |
| `checked-rift` | `1955.329` | `1950.891` | `1956.729` | `0.135` | `0.330` | `3/3` | `2.276` | `1922465` | `123617280` | `250002331971566003` | `922453` |
| `checked-rift-inferred` | `1867.761` | `1857.456` | `1874.685` | `0.153` | `0.268` | `3/3` | `2.554` | `1922465` | `126730240` | `250002331971566003` | `922453` |
| `checked-region-scoped` | `1972.133` | `1961.303` | `1974.453` | `25.737` | `28.637` | `3/3` | `0.000` | `0` | `125026304` | `250002331971566003` | `922453` |

Interpretation:

- The split inferred path is now the fastest safe row in this 1M L2 pass:
  `1867.761 ms`, about `4.5%` faster than explicit checked Rift and about
  `8.6%` faster than heap, with matching checksum/output.
- RSS remains in the same low range as other checked rows (`~124-127 MB`),
  versus heap at `590 MB`.
- This is accepted as a general checked-framework/source-shape optimization:
  it removes an enclosing inline-wrapper shape that capture checking cannot
  preserve today, while keeping the benchmark semantics and public Rift APIs
  unchanged. It is still not a full solution for inlineable region-body
  callbacks; the join path and broader compiler ownership/effect-summary work
  remain open.

### 2026-05-20 State-Local Inferred Session Follow-Up

The inferred session path was then tightened so the mutable loop counters stay
inside the open-handle callback instead of being captured through outer boxed
state. This keeps the same query semantics and public API shape.

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-wikimedia-inline-reset-state-smoke-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-wikimedia-inline-reset-state-1m-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-wikimedia-inline-reset-state-escalated/summary.tsv`

20k smoke matched checksum/output across `heap-gc`, `checked-rift`, and
`checked-rift-inferred`: checksum `-8642330901600858181`, output `19102`.

1M L2 standard stats after the state-local cleanup:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `2082.274` | `2080.107` | `2117.115` | `175.642` | `183.149` | `3/3` | `0.000` | `0` | `590102528` | `250002331971566003` | `922453` |
| `checked-rift` | `2015.182` | `2009.286` | `2043.809` | `0.157` | `0.344` | `3/3` | `3.011` | `1922465` | `123633664` | `250002331971566003` | `922453` |
| `checked-rift-inferred` | `1927.010` | `1893.473` | `1942.639` | `0.161` | `0.281` | `3/3` | `3.125` | `1922465` | `126779392` | `250002331971566003` | `922453` |
| `checked-region-scoped` | `1968.287` | `1966.403` | `2025.894` | `23.875` | `26.017` | `3/3` | `0.000` | `0` | `124993536` | `250002331971566003` | `922453` |

Fresh L4 for `checked-rift-inferred` reports parser/input/hash `519.00`
samples/sec, query/session-loop `56.00`, safepoint poll `241.20`, region
alloc/init `3.80`, and zeroing `1.20`. No sampled
`scala.runtime.IntRef`/`LongRef`/`BooleanRef` top-frame matches remain. The
state-local cleanup is therefore accepted, but the remaining row is still
dominated by TSV parsing/hashing and residual runtime safepoint/metadata work,
not region allocation.

### 2026-05-20 Wikimedia Fused Clickstream Parser

The next Wikimedia follow-up addressed the shared parser/hash floor instead of
region allocation. The old clickstream parser walked each byte line repeatedly:
three `tsvFieldHash` calls, one `tsvFieldInt`, and a whole-line
`stableHash`. `readClickstreamFieldsInto` now computes source/target/link-kind
FNV hashes, field-3 count, and the whole-line FNV hash in one byte pass. This
is backend-neutral and applies to heap and every checked row.

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-wikimedia-fused-parser-1m-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-wikimedia-fused-parser/summary.tsv`

20k compressed smoke matched checksum/output across `heap-gc`,
`checked-rift`, `checked-rift-inferred`, and `checked-region-scoped`: checksum
`-5707858218641866390`, output `18036`.

1M x3 L2 standard stats after the fused parser:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `1219.250` | `1218.549` | `1222.755` | `103.101` | `108.270` | `3/3` | `0.000` | `0` | `250002331971566003` | `922453` |
| `checked-rift` | `1203.342` | `1203.006` | `1206.332` | `0.232` | `0.243` | `3/3` | `1.739` | `1922465` | `250002331971566003` | `922453` |
| `checked-rift-inferred` | `1124.139` | `1121.720` | `1126.134` | `0.232` | `0.246` | `3/3` | `1.809` | `1922465` | `250002331971566003` | `922453` |
| `checked-region-scoped` | `1216.653` | `1202.141` | `1220.408` | `30.300` | `32.169` | `3/3` | `0.000` | `0` | `250002331971566003` | `922453` |

Focused L4 confirms the mechanical effect: the old `tsvFieldHash`,
`tsvFieldInt`, and standalone `stableHash` top frames disappear, replaced by
one fused `readClickstreamFieldsInto` frame. Coarse buckets remain parser-heavy
because the workload is still real compressed TSV replay: parser/input/hash is
heap `418.20`, explicit checked `496.60`, inferred checked `490.20`, and scoped
`512.40` samples/sec. Region allocation remains tiny (`2.60-6.20` samples/sec).
This result is accepted as shared parser/hash fairness cleanup, not as a
Rift-specific memory-management win.

### 2026-05-21 Chunked ByteLineReader Follow-Up

The next shared input cleanup removed the per-byte helper shape in
`BenchmarkInputSupport.ByteLineReader`. The old `readLine` path called
`nextByte` and `append` for every byte. The new path scans each filled input
buffer for newline bytes and copies contiguous spans into the reusable line
buffer with `System.arraycopy`, preserving CR trimming and EOF final-line
semantics.

Raw summaries:

- `/tmp/loghub-bytereader-smoke-wikimedia/summary.tsv`
- `/tmp/loghub-bytereader-smoke-join/summary.tsv`
- `/tmp/theodolite-bytereader-smoke/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bytereader-wikimedia-1m-l2-20260521/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-bytereader-join-1m-l2-20260521/summary.tsv`
- `/Users/siyaoliu/rift/cache/profile-sweep-20260521-bytereader-loghub/summary.tsv`

Validation:

- `sandbox3_next` compile passed.
- 20k compressed Wikimedia clickstream-session smoke matched checksum/output
  across heap, explicit checked, inferred checked, and scoped: checksum
  `-7932171983828413881`, output `17518`.
- 20k HDFS archive-member join smoke matched the same four modes: checksum
  `-1607483374812565358`, output `0`. The tar reader still reports a nonzero
  external exit status when closed after the record limit, but every row wrote
  a valid `RESULT`.
- 20k zip-backed Theodolite retained-UC4 smoke matched heap, checked stream,
  and checked scoped: checksum `-2895454912458695581`, output `6176`.

1M x3 L2 standard stats after the chunked reader:

| Workload | Mode | Median ms | Min ms | Max ms | GC median ms | Region op ms | Region objects | Checksum | Output |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Wikimedia clickstream-session | `heap-gc` | `1061.907` | `1059.806` | `1066.727` | `105.736` | `0.000` | `0` | `250002331971566003` | `922453` |
| Wikimedia clickstream-session | `checked-rift` | `1041.414` | `1039.591` | `1045.862` | `0.234` | `1.761` | `1922465` | `250002331971566003` | `922453` |
| Wikimedia clickstream-session | `checked-rift-inferred` | `960.242` | `958.452` | `960.464` | `0.131` | `1.735` | `1922465` | `250002331971566003` | `922453` |
| Wikimedia clickstream-session | `checked-region-scoped` | `1047.725` | `1034.973` | `1048.164` | `24.033` | `0.000` | `0` | `250002331971566003` | `922453` |
| HDFS join | `heap-gc` | `7134.073` | `7086.826` | `7159.943` | `27.343` | `0.000` | `0` | `4282190220497908364` | `0` |
| HDFS join | `checked-rift` | `7138.545` | `7100.550` | `7160.117` | `0.295` | `1.253` | `1000006` | `4282190220497908364` | `0` |
| HDFS join | `checked-rift-inferred` | `7097.635` | `7060.752` | `7138.951` | `0.294` | `0.990` | `1000006` | `4282190220497908364` | `0` |
| HDFS join | `checked-region-scoped` | `7140.815` | `7130.046` | `7150.360` | `6.153` | `0.000` | `0` | `4282190220497908364` | `0` |

Inferred session-array source audit: after the array-placement evidence was
recorded, a source audit found the inferred Wikimedia session path still used
explicit `RiftAllocator.allocateOpenHandle(region, new Array(...))` for
`entries`, `heads`, `tails`, and `counts`. Those remaining per-group arrays
now use ordinary `new Array` under the validated reset-open-handle owner. A
fresh sandbox compile and native link passed, and a fresh 20k compressed
Wikimedia smoke under `/tmp/loghub-inferred-session-array-smoke` matched
checksum `4260216346575211415` and output `18980` across heap, explicit
checked, inferred checked, and scoped.

Fresh 1M x3 L2 standard stats after completing the inferred session array
source shape:

| Mode | Median ms | Min ms | Max ms | GC median ms | Region op ms | Region objects | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `1069.051` | `1066.926` | `1075.975` | `106.308` | `0.000` | `0` | `250002331971566003` | `922453` |
| `checked-rift` | `1052.706` | `1047.754` | `1062.249` | `0.241` | `1.784` | `1922465` | `250002331971566003` | `922453` |
| `checked-rift-inferred` | `965.723` | `961.293` | `981.874` | `0.229` | `1.870` | `1922465` | `250002331971566003` | `922453` |
| `checked-region-scoped` | `1053.033` | `1041.968` | `1069.362` | `27.650` | `0.000` | `0` | `250002331971566003` | `922453` |

This confirms the intended source-plumbing cleanup with identical explicit and
inferred region-object counts; it is not a new region-object-count reduction.

Focused L4 confirms the mechanical source effect: the old
`ByteLineReaderD8nextByte` and `ByteLineReaderD6append` top frames are absent.
Samples now land in the chunked `ByteLineReader.readLine` body plus the
benchmark parser/hash work. Coarse parser/input/hash samples/sec are mixed
rather than uniformly lower in the five-second Wikimedia sample
(`385.80/478.80/510.40/484.60` for heap/explicit/inferred/scoped), so the
accepted claim is shared throughput improvement and removal of per-byte helper
frames, not a uniform L4 bucket reduction.

### 2026-05-20 Inferred Join Inline-Reset Split

After branch/match-final local inference closed the mixed inferred/explicit
allocation gap, `checked-rift-inferred` join was split out of the enclosing
inline wrapper and moved to the same sandbox-only internal inline reset bridge
used by the session path. The explicit `checked-rift` join remains on the
older non-inline helper path for comparison.

Input uses the compressed archive member, not the removed decompressed HDFS
file:

`tar.gz:/Users/siyaoliu/rift/cache/benchmark-data/loghub/HDFS_1.tar.gz!HDFS.log`

Raw summaries:

- `/Users/siyaoliu/rift/cache/loghub-join-inline-inferred-smoke-20260520-archive-priv/summary.tsv`
- `/Users/siyaoliu/rift/cache/loghub-join-inline-inferred-1m-20260520/summary.tsv`
- `/Users/siyaoliu/rift/cache/profile-sweep-20260520-loghub-join-inline-inferred/summary.tsv`

20k smoke matched checksum/output across `heap-gc`, `checked-rift`,
`checked-rift-inferred`, and `checked-region-scoped`: checksum
`-3039645399054221914`, output `0`.

1M L2 standard stats:

| Mode | Median ms | Min ms | Max ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Checksum | Output |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `8268.847` | `8199.232` | `8272.734` | `42.256` | `63.717` | `3/3` | `0.000` | `0` | `273530880` | `4282190220497908364` | `0` |
| `checked-rift` | `10102.728` | `8505.642` | `12569.110` | `0.353` | `0.625` | `3/3` | `2.534` | `1000006` | `67977216` | `4282190220497908364` | `0` |
| `checked-rift-inferred` | `8350.524` | `8251.936` | `10683.908` | `0.342` | `1.296` | `3/3` | `2.681` | `1000006` | `66600960` | `4282190220497908364` | `0` |
| `checked-region-scoped` | `8389.309` | `8384.063` | `8395.176` | `6.589` | `7.172` | `3/3` | `0.000` | `0` | `67026944` | `4282190220497908364` | `0` |

Interpretation:

- The inferred join split removes the rejected enclosing inline-wrapper shape
  from the inferred join path and brings it close to heap and checked scoped in
  this archive-backed control.
- The row is not a GC-heavy real-input win: heap GC is only `42.256 ms`, about
  `0.5%` of the L2 median. Use it as source-shape and RSS/control evidence,
  not as a headline memory-management result.
- L4 follow-up confirms the row is not allocator-bound. Parser/input/hash
  samples/sec are heap `598.80`, explicit checked Rift `629.40`, inferred
  checked Rift `622.60`, and checked scoped `612.20`; residual
  safepoint-poll samples are also near `190` samples/sec across modes, while
  actual GC mark/sweep metadata is `0.00` in the checked rows.
  Checked callback-ref-shape samples are explicit checked `17.00`, inferred
  checked `12.60`, and scoped `31.20`, versus heap `0.00`. This marks
  generated closure bodies whose signatures carry `scala.runtime.*Ref`; it is
  a source-shape marker, not direct allocation time. The next general compiler
  target is callback ownership/effect-summary preservation rather than another
  region allocation fast path for this row.
