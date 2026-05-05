# Checked SafeZone-Backed Backend Matrix

Date: 2026-05-03

Status: v1 benchmark-only checked SafeZone-backed backend implemented and
measured. The focused append-window gate passes; the Common Crawl-like
application follow-up improves current checked mode but misses the application
gate.

## What Changed

The experimental backend adds `RiftRegion.streamingSafeZone(...)`, which keeps
the user-level checked Rift programming model but delegates checked object
allocation to SafeZone allocator internals. Child buckets opened from this
parent use the same SafeZone-backed backend.

V1 supports object allocation and close. Raw byte allocation and reset are
explicitly unsupported. Normal `RiftRegion.streaming(...)` is unchanged.

Benchmark labels:

| Label | Meaning |
|---|---|
| `rift-checked-safezone-32k` | Checked Rift API over SafeZone with `SAFEZONE_ROOTS_MODE=1 SAFEZONE_PAGE_SIZE=32768`. |
| `rift-checked-rootfree-safezone-hp` | Lower-bound checked label over rootless SafeZone mode 3/page 32 KiB; benchmark-only. |
| `safezone-improved-32k` | Direct SafeZone baseline with improved root mode and 32 KiB pages. |
| `unsafezone-hp` | Direct rootless SafeZone no-root substrate control; unsafe and benchmark-only. |

Implementation note: SafeZone-backed checked rows do not report Rift allocator
op stats because allocation is delegated through SafeZone internals.

## Validation

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "project sandbox3_next" compile
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "nscplugin3_next/testOnly org.scalanative.RiftRegionCheckedCompilerTest"
ENABLE_EXPERIMENTAL_COMPILER=1 sbt "tests3_next/testOnly scala.scalanative.memory.RiftRegionCheckedTest"
```

Results:

- `sandbox3_next` compile passed.
- checked compiler tests passed: `96/96`.
- checked native runtime tests passed: `40/40`.

New runtime tests cover SafeZone-backed checked streaming object allocation,
child bucket allocation plus cursor close, allocation-after-close rejection,
and raw allocation/reset rejection.

## Focused Append-Window Gate

### Commands

20k smoke:

```sh
CHECKED_APPEND_BUILD=1 \
CHECKED_APPEND_EVENTS=20000 \
CHECKED_APPEND_BENCHMARK_RUNS=1 \
CHECKED_APPEND_WARMUPS=0 \
CHECKED_APPEND_MODES="heap rift-checked-api-cursor rift-checked-safezone-32k safezone-improved-32k unsafezone-hp" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-safezone-append-smoke-2026-05-03 \
zsh sandbox/run_checked_append_window_matrix.sh
```

100k median:

```sh
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=100000 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap rift-checked-api-cursor rift-checked-safezone-32k safezone-improved-32k unsafezone-hp" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-safezone-append-2026-05-03-100k \
zsh sandbox/run_checked_append_window_matrix.sh
```

1M median:

```sh
CHECKED_APPEND_BUILD=0 \
CHECKED_APPEND_EVENTS=1000000 \
CHECKED_APPEND_BENCHMARK_RUNS=3 \
CHECKED_APPEND_WARMUPS=1 \
CHECKED_APPEND_MODES="heap rift-checked-api-cursor rift-checked-safezone-32k safezone-improved-32k unsafezone-hp" \
CHECKED_APPEND_OUTPUT_DIR=/Users/siyaoliu/rift/cache/checked-safezone-append-2026-05-03-1m \
zsh sandbox/run_checked_append_window_matrix.sh
```

All rows matched checksum.

### Focused Results

| Events | Mode | Median ms | GC ms | Rift op ms | Region objects | RSS bytes | Checksum |
|---:|---|---:|---:|---:|---:|---:|---:|
| 20k | heap | 1.745 | 0.000 | 0.000 | 0 | 6995968 | -1919398397124784300 |
| 20k | rift-checked-api-cursor | 1.089 | 0.000 | 0.041 | 20000 | 6832128 | -1919398397124784300 |
| 20k | rift-checked-safezone-32k | 0.883 | 0.000 | 0.000 | 0 | 6815744 | -1919398397124784300 |
| 20k | safezone-improved-32k | 0.983 | 0.000 | 0.000 | 0 | 6815744 | -1919398397124784300 |
| 20k | unsafezone-hp | 0.912 | 0.000 | 0.000 | 0 | 6815744 | -1919398397124784300 |
| 100k | heap | 2.704 | 0.000 | 0.000 | 0 | 21397504 | 4594055666086494054 |
| 100k | rift-checked-api-cursor | 3.136 | 0.000 | 0.007 | 100000 | 15843328 | 4594055666086494054 |
| 100k | rift-checked-safezone-32k | 2.944 | 0.000 | 0.000 | 0 | 15761408 | 4594055666086494054 |
| 100k | safezone-improved-32k | 3.759 | 0.000 | 0.000 | 0 | 15777792 | 4594055666086494054 |
| 100k | unsafezone-hp | 3.752 | 0.000 | 0.000 | 0 | 15777792 | 4594055666086494054 |
| 1M | heap | 35.511 | 10.486 | 0.000 | 0 | 75071488 | -2507118467295660905 |
| 1M | rift-checked-api-cursor | 30.922 | 0.000 | 0.068 | 1000000 | 47579136 | -2507118467295660905 |
| 1M | rift-checked-safezone-32k | 29.444 | 0.000 | 0.000 | 0 | 47464448 | -2507118467295660905 |
| 1M | safezone-improved-32k | 37.295 | 0.000 | 0.000 | 0 | 47464448 | -2507118467295660905 |
| 1M | unsafezone-hp | 36.611 | 0.000 | 0.000 | 0 | 47464448 | -2507118467295660905 |

Focused gate result: pass.

- At 1M, `rift-checked-safezone-32k` is faster than current
  `rift-checked-api-cursor` (`29.444 ms` vs `30.922 ms`).
- RSS does not regress versus current checked cursor mode.
- The result is focused checked-operator/backend evidence, not an application
  case-study claim.

## Common Crawl-Like Follow-Up

These rows were run only after the focused append-window gate passed.

### Commands

20k smoke:

```sh
COMMON_CRAWL_WET_BUILD=1 \
COMMON_CRAWL_WET_PAGES=20000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=1 \
COMMON_CRAWL_WET_WARMUPS=0 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap safezone-improved-32k unsafezone-hp rift-hp rift-streaming rift-checked rift-checked-safezone-32k" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-checked-safezone-smoke-2026-05-03 \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

100k median:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=100000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap safezone-improved-32k unsafezone-hp rift-hp rift-streaming rift-checked rift-checked-safezone-32k" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-checked-safezone-2026-05-03-100k \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

1M median:

```sh
COMMON_CRAWL_WET_BUILD=0 \
COMMON_CRAWL_WET_PAGES=1000000 \
COMMON_CRAWL_WET_BENCHMARK_RUNS=3 \
COMMON_CRAWL_WET_WARMUPS=1 \
COMMON_CRAWL_WET_QUERIES="q1-tokenize q2-domain-window" \
COMMON_CRAWL_WET_MODES="heap safezone-improved-32k unsafezone-hp rift-hp rift-streaming rift-checked rift-checked-safezone-32k" \
COMMON_CRAWL_WET_OUTPUT_DIR=/Users/siyaoliu/rift/cache/common-crawl-checked-safezone-2026-05-03-1m \
zsh sandbox/run_common_crawl_wet_matrix.sh
```

All rows matched checksum/output count within each query.

### 100k Medians

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Region objects | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 542.539 | 153.395 | 165.521 | 0.000 | 0 | 408584192 | 13700000 |
| q1-tokenize | safezone-improved-32k | 448.610 | 0.000 | 0.000 | 0.000 | 0 | 356089856 | 13700000 |
| q1-tokenize | unsafezone-hp | 453.285 | 0.000 | 0.000 | 0.000 | 0 | 356089856 | 13700000 |
| q1-tokenize | rift-hp | 428.715 | 0.000 | 0.000 | 1.049 | 13700000 | 355975168 | 13700000 |
| q1-tokenize | rift-streaming | 424.548 | 0.000 | 0.000 | 1.055 | 13700000 | 355926016 | 13700000 |
| q1-tokenize | rift-checked | 478.626 | 0.000 | 0.000 | 1.027 | 13700000 | 355975168 | 13700000 |
| q1-tokenize | rift-checked-safezone-32k | 449.245 | 0.000 | 0.000 | 0.000 | 0 | 356073472 | 13700000 |
| q2-domain-window | heap | 509.462 | 146.655 | 147.167 | 0.000 | 0 | 408584192 | 92994 |
| q2-domain-window | safezone-improved-32k | 426.988 | 0.000 | 0.000 | 0.000 | 0 | 420380672 | 92994 |
| q2-domain-window | unsafezone-hp | 437.785 | 0.000 | 0.000 | 0.000 | 0 | 420364288 | 92994 |
| q2-domain-window | rift-hp | 413.226 | 0.000 | 0.000 | 1.055 | 13700000 | 420315136 | 92994 |
| q2-domain-window | rift-streaming | 407.032 | 0.000 | 0.000 | 1.036 | 13700000 | 420282368 | 92994 |
| q2-domain-window | rift-checked | 475.838 | 0.000 | 0.000 | 1.051 | 13700000 | 420364288 | 92994 |
| q2-domain-window | rift-checked-safezone-32k | 446.612 | 0.000 | 0.000 | 0.000 | 0 | 420347904 | 92994 |

### 1M Medians

| Query | Mode | Median ms | GC ms | Max GC ms | Rift op ms | Region objects | RSS bytes | Output count |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| q1-tokenize | heap | 5412.668 | 1587.696 | 1635.252 | 0.000 | 0 | 408584192 | 137000000 |
| q1-tokenize | safezone-improved-32k | 4570.772 | 31.764 | 32.248 | 0.000 | 0 | 474742784 | 137000000 |
| q1-tokenize | unsafezone-hp | 4534.978 | 25.358 | 28.078 | 0.000 | 0 | 474644480 | 137000000 |
| q1-tokenize | rift-hp | 4278.440 | 19.923 | 20.854 | 10.166 | 137000000 | 474562560 | 137000000 |
| q1-tokenize | rift-streaming | 4342.267 | 19.602 | 20.515 | 10.268 | 137000000 | 474480640 | 137000000 |
| q1-tokenize | rift-checked | 4744.872 | 19.631 | 20.077 | 10.365 | 137000000 | 474529792 | 137000000 |
| q1-tokenize | rift-checked-safezone-32k | 4512.743 | 29.333 | 35.525 | 0.000 | 0 | 474726400 | 137000000 |
| q2-domain-window | heap | 5190.246 | 1554.398 | 1574.927 | 0.000 | 0 | 408584192 | 929230 |
| q2-domain-window | safezone-improved-32k | 4362.405 | 31.277 | 31.598 | 0.000 | 0 | 474742784 | 929230 |
| q2-domain-window | unsafezone-hp | 4342.620 | 19.233 | 20.570 | 0.000 | 0 | 474644480 | 929230 |
| q2-domain-window | rift-hp | 4075.431 | 18.875 | 19.697 | 10.179 | 137000000 | 474562560 | 929230 |
| q2-domain-window | rift-streaming | 4083.422 | 19.035 | 20.854 | 10.181 | 137000000 | 474497024 | 929230 |
| q2-domain-window | rift-checked | 4698.903 | 19.012 | 19.334 | 10.311 | 137000000 | 474529792 | 929230 |
| q2-domain-window | rift-checked-safezone-32k | 4431.865 | 27.708 | 30.400 | 0.000 | 0 | 474693632 | 929230 |

### Application Follow-Up Interpretation

- The SafeZone-backed checked backend improves current `rift-checked` on this
  workload, but not enough to make a checked application-speed claim.
- At 1M q1, `rift-checked-safezone-32k` is `4512.743 ms` versus current
  `rift-checked` at `4744.872 ms`: about `4.9%` faster.
- At 1M q2, `rift-checked-safezone-32k` is `4431.865 ms` versus current
  `rift-checked` at `4698.903 ms`: about `5.7%` faster.
- The application follow-up gate required at least a `10%` checked-mode
  improvement and a target within `5%` of improved SafeZone-32k or trusted Rift.
  q1 is close to improved SafeZone-32k (`4512.743 ms` vs `4570.772 ms`) but
  remains slower than trusted HPZone (`4278.440 ms`). q2 is slower than both
  improved SafeZone-32k (`4362.405 ms`) and trusted HPZone/Streaming
  (`4075.431` / `4083.422 ms`).
- Heap remains strongly GC-heavy on generated WET-shaped q1/q2: heap spends
  about `1.55-1.59 s` in GC at 1M. Region/SafeZone-family rows cut that to
  about `19-32 ms`, but RSS is higher than heap at 1M.
- The backend is useful evidence that SafeZone-family allocation mechanics can
  reduce checked overhead, but checked container/API overhead is still material
  in application-shaped hot loops.

## Decision

- Keep `rift-checked-safezone-32k` as an experimental backend direction.
- Do not claim checked Common Crawl q1/q2 as a final application win.
- Do not move this backend into DEBS or TableRank next.
- Next engineering target should decompose why trusted Rift remains faster on
  Common Crawl-like q1/q2 while SafeZone-backed checked improves focused
  append-window. The likely split is backend mechanics plus checked
  `StreamAppendWindow` container/API overhead.
