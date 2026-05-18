# Portable Rift Experimental Module

Last updated: 2026-05-17 03:09 CEST

Status: isolated prototype area for the portable-backend fork. This directory
is intentionally separate from Scala Native runtime/compiler implementation
files.

## Ownership Boundary

This directory is owned by the portable-backend experiment track.

Portable-backend work may edit:

- `experimental/portable-rift/**`
- parent project docs that describe the portable track;
- future portable-specific evidence files.

Portable-backend work should not edit without coordination:

- `nativelib/**`
- `nscplugin/**`
- `unit-tests/native/**`
- Native benchmark matrices in `sandbox/**`
- Native runtime allocator files.

Native-backend work should not edit this directory unless it is intentionally
changing the shared Scala-level API contract.

## Purpose

The goal is to separate Rift's Scala-level checked lifetime topology model from
Scala Native-specific allocation lowering.

This prototype models:

- a shared `epoch` scope;
- active/closed scope checks;
- heap fallback allocation;
- JVM/Scala.js-style explicit object pools;
- a Wasm-style linear-memory arena;
- analysis-only lifetime checking.

It does **not** claim that JVM, Scala.js, or Wasm can currently allocate
arbitrary Scala objects in native Rift regions. Those backends are planned
experiments.

## Current Source

- `src/main/scala/rift/portable/Rift.scala` defines the portable API contract in code.
- `src/main/scala/rift/portable/Backends.scala` implements heap, analysis, JVM pool, Scala.js pool, Wasm arena, and Native model backends.
- `src/main/scala/rift/portable/PortableRiftSmoke.scala` is the compatibility smoke runner.
- `src/main/scala/rift/portable/PortableRiftMicrobench.scala` is the tiny JVM evidence harness.
- `src/test/scala/rift/portable/PortableRiftSuite.scala` covers portable runtime discipline.

The source is ordinary Scala by design. The module is deliberately run with
Scala CLI first instead of being wired into the main Scala Native sbt build.

## Commands

Compile:

```sh
scala-cli compile --server=false experimental/portable-rift
```

Run tests:

```sh
scala-cli test --server=false experimental/portable-rift
```

Run smoke:

```sh
scala-cli run --server=false experimental/portable-rift \
  --main-class rift.portable.PortableRiftSmoke -- \
  --records 20000 --epoch-size 10000
```

Run the tiny JVM harness:

```sh
scala-cli run --server=false experimental/portable-rift \
  --main-class rift.portable.PortableRiftMicrobench -- \
  --workload broom-aggregate --records 100000 \
  --epoch-size 10000 --active-timestamps 4 --runs 2
```

Run the JVM retained-object gate with external time/RSS and GC logs:

```sh
PORTABLE_RIFT_RECORDS=500000 PORTABLE_RIFT_RUNS=2 \
  experimental/portable-rift/scripts/run_jvm_gate.sh
```

The gate is evidence infrastructure, not a final performance claim. It runs
each mode in a separate JVM and writes row TSVs, `/usr/bin/time -l` output, and
JVM GC logs under `/private/tmp` by default.
