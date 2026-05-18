# LogHub Retained Session Matrix

Last updated: 2026-05-18 15:45 CEST

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
- `/private/tmp/wikimedia-named-clickstream-10m-heapcaps-20260518/summary.tsv`
- `/private/tmp/wikimedia-named-clickstream-10m-regioncap-64m-20260518/summary.tsv`

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

10M feasibility scale-up, one-run L1 final-clean:

| Mode | External real s | User s | Sys s | RSS bytes | Checksum | Output | Retained proxy | Max live proxy |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `24.23` | `22.84` | `0.56` | `1155743744` | `8006060730683441349` | `9323019` | `19323019` | `778835` |
| `checked-rift` | `19.96` | `19.37` | `0.43` | `136118272` | `8006060730683441349` | `9323019` | `19323019` | `778838` |
| `checked-region-scoped` | `21.43` | `21.02` | `0.29` | `136396800` | `8006060730683441349` | `9323019` | `19323019` | `778838` |

10M feasibility scale-up, one-run L2 standard stats:

| Mode | Median ms | GC median ms | GC max ms | Runs with GC | Region op ms | Region objects | RSS bytes | Records/sec |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `heap-gc` | `22877.820` | `4058.998` | `4058.998` | `1/1` | `0.000` | `0` | `1138147328` | `437104.591` |
| `checked-rift` | `19716.840` | `99.708` | `99.708` | `1/1` | `31.049` | `19323094` | `136151040` | `507180.666` |
| `checked-region-scoped` | `21079.678` | `1703.489` | `1703.489` | `1/1` | `0.000` | `0` | `136511488` | `474390.556` |

10M heap-cap and checked-cap probes:

| Mode | Cap | Status | External real s | RSS bytes | Notes |
|---|---:|---|---:|---:|---|
| `heap-gc` | `1G` | fail | `7.97` | `831127552` | OOM in heap array allocation |
| `heap-gc` | `768M` | fail | `3.69` | `527335424` | OOM in heap array allocation |
| `heap-gc` | `512M` | fail | `1.92` | `374358016` | OOM in heap array allocation |
| `checked-rift` | `64M` | pass | `19.39` | `136151040` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |
| `checked-region-scoped` | `64M` | pass | `21.08` | `136364032` | cap applied through inherited `GC_MAXIMUM_HEAP_SIZE`; runner labels row `uncapped` |

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
- At 10M, the one-run feasibility probe becomes material-GC evidence again:
  checked Rift is L1 `19.96 s` versus heap `24.23 s` and cuts RSS from about
  `1.16 GB` to `136 MB`. L2 checked Rift is `19716.840 ms` with `99.708 ms`
  GC and `31.049 ms` region-op, versus heap `22877.820 ms` with
  `4058.998 ms` timed GC. Heap fails at `1G`, `768M`, and `512M`; checked
  Rift and checked scoped complete under a `64M` GC heap cap. This is still a
  one-run feasibility row, so use it to justify a future 3-run/full-input
  follow-up rather than as a final median.
