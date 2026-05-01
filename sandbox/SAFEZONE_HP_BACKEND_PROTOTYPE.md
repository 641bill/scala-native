# SafeZone-HP Checked Backend Prototype

Status: design target and prototype checklist; no backend code yet.

Date: 2026-05-01

## Purpose

The SafeZone-family measurements suggest a possible backend direction:
combine SafeZone's allocator/pool mechanics with Rift's static safety story.
The prototype label will be `rift-checked-safezone-hp`.

This must not become a silent unsafe mode. The rootless backend is allowed only
when the checked compiler can prove the relevant region graph does not require
GC root registration or region scanning.

## V1 Policy

| Reference direction | V1 rule |
|---|---|
| GC heap to region | Allowed only when capture/provenance proves the heap value cannot outlive the region. |
| Region to GC heap | Allowed only for static/immutable metadata or explicit rooted handles. |
| Outer region to inner region | Rejected unless the lifetimes are proven equal. |
| Inner region to outer region | Allowed when the outer region outlives the inner region. |
| Region scanning by GC | Not supported in v1. |
| Unsupported case | Reject in `rift-checked-safezone-hp`; do not silently fall back. |

## Prototype Shape

- Keep the public user API unchanged initially.
- Add an internal benchmark mode label, `rift-checked-safezone-hp`.
- Reuse existing checked Rift APIs and compiler tests where possible.
- Lower checked allocations to a SafeZone-HP backend only after root-free
  safety checks succeed.
- Keep `unsafezone-hp` as the lower-bound unsafe substrate control.

## Performance Gate

The prototype is useful only if it:

- matches checksums/output counts;
- beats or matches current `rift-checked` on focused checked operators;
- is competitive with `unsafezone-hp` on root-free workloads;
- preserves rejection of heap-retains-region, unrooted region-retains-heap,
  outer-retains-inner, closure escape, and use-after-close/reset cases.

## Current Status

No code has been added for this backend yet. The immediate prerequisite is the
SafeZone cost matrix, because the prototype should target measured costs rather
than assume root registration is the only issue.
