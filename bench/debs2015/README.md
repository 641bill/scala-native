# DEBS 2015 Phase 5 Scaffold

This directory tracks the Phase 5 application benchmark from `ROADMAP.md`.

The implementation lives in the Scala Native sandbox while it is still
experimental:

- `sandbox/src/main/scala-next/debs2015/Trip.scala`
- `sandbox/src/main/scala-next/debs2015/Grid.scala`
- `sandbox/src/main/scala-next/debs2015/Q1.scala`
- `sandbox/src/main/scala-next/debs2015/Q1Output.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q1Run.scala`
- `sandbox/src/main/scala-next/debs2015/Q2.scala`
- `sandbox/src/main/scala-next/debs2015/Q2Output.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q2Run.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015RunBoth.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Smoke.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q1Smoke.scala`
- `sandbox/src/main/scala-next/debs2015/Debs2015Q2Smoke.scala`
- `bench/debs2015/join_nyc_taxi_sample.sh`
- `bench/debs2015/run_q1_sample_matrix.sh`
- `bench/debs2015/run_both_sample_matrix.sh`
- `bench/debs2015/run_both_instrumented_matrix.sh`
- `bench/debs2015/RESULTS.md`

The authoritative challenge brief is the DEBS 2015 taxi challenge:

- Q1: top-10 most frequent `(pickup-cell, dropoff-cell)` routes in the last
  30 minutes.
- Q2: top-10 most profitable areas, using median `fare + tip` over the last
  15 minutes divided by empty taxis in the last 30 minutes.
- Both queries must run simultaneously and write separate output streams.

Grid constants from the challenge spec:

- First cell center: latitude `41.474937`, longitude `-74.913585`.
- Eastward 500m delta: `0.005986` degrees longitude.
- Southward 500m delta: `0.004491556` degrees latitude.
- Q1 uses `500m x 500m` cells over `300 x 300`.
- Q2 uses `250m x 250m` cells over `600 x 600`.

Initial smoke command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015Smoke\")" \
  run
```

Q1 heap smoke command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Smoke\")" \
  run
```

Q1 file-backed runner:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015Q1Run\")" \
  "run bench/debs2015/sample_q1.csv /tmp/debs2015-q1.out heap"
```

Supported Q1 modes:

- `heap`
- `rift-hp`
- `rift-streaming`

Q1 sample matrix:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh bench/debs2015/run_q1_sample_matrix.sh
```

Q2 heap smoke command:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Smoke\")" \
  run
```

Q2 file-backed heap runner:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015Q2Run\")" \
  "run bench/debs2015/sample_q2.csv /tmp/debs2015-q2.out"
```

Run Q1 and Q2 simultaneously:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
ENABLE_EXPERIMENTAL_COMPILER=1 \
JAVA_HOME="$(cs java-home --jvm temurin:17)" \
PATH="$JAVA_HOME/bin:$PATH" \
sbt "project sandbox3_next" \
  "set Compile / mainClass := Some(\"debs2015.Debs2015RunBoth\")" \
  "run bench/debs2015/sample_both.csv /tmp/debs2015-q1-both.out /tmp/debs2015-q2-both.out heap"
```

RunBoth sample matrix:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
zsh bench/debs2015/run_both_sample_matrix.sh
```

RunBoth instrumented matrix:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_BOTH_INPUT=/tmp/debs2015-month1-1000000.csv \
DEBS2015_BOTH_OUTPUT_DIR=/tmp/debs2015-runboth-instrumented-1000000 \
  zsh bench/debs2015/run_both_instrumented_matrix.sh
```

The instrumented runner links `debs2015.Debs2015RunBoth` once, runs the native
binary directly, captures peak RSS via `/usr/bin/time`, and writes a TSV summary
with throughput, latency, GC counters, and Rift region counters.

Join a bounded real NYC taxi sample:

```sh
cd /Users/siyaoliu/rift/scala-native-rift
DEBS2015_MONTH=1 DEBS2015_LIMIT=100000 \
  zsh bench/debs2015/join_nyc_taxi_sample.sh
```

The downloaded NYC files are split into `trip_data_N.csv` and
`trip_fare_N.csv`. The join script verifies corresponding trip identity fields,
emits the 17-column format consumed by the Scala runner, and sorts the bounded
sample by dropoff timestamp. Sorting is required for meaningful sliding-window
query semantics because the raw monthly files are not monotonic by dropoff time.

Next implementation steps:

- Rerun the instrumented matrix enough times to report medians for RSS,
  throughput, GC time, and Rift operation time.
- Add Commix and improved SafeZone comparison modes where meaningful.
- Scale from the bounded 1M January sample to a full-month joined/sorted stream.
- Replace the simple heap `HashMap` ranking path with a region-shaped
  bucket/index layout if the sample run shows ranking scratch allocation is a
  bottleneck.
