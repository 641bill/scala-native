# SafeZone-HP Checked Backend Prototype

Status: v1 checked SafeZone-backed backend implemented and measured in focused
append-window and Common Crawl-like q1/q2 follow-up rows.

Date: 2026-05-03

## Purpose

The SafeZone-family measurements suggest a possible backend direction:
combine SafeZone's allocator/pool mechanics with Rift's static safety story.
The first prototype label is `rift-checked-safezone-32k`.

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

## Implemented V1 Shape

- `RiftRegion.streamingSafeZone(...)` is a benchmark-only entrypoint that
  returns a checked `StreamingRegion`.
- Checked object allocation delegates to `SafeZoneAllocator`.
- Child buckets opened from a SafeZone-backed checked parent use the same
  SafeZone-backed backend.
- Normal `RiftRegion.streaming(...)` is unchanged.
- Raw byte allocation and reset are explicitly unsupported in v1.
- `rift-checked-safezone-32k` uses `SAFEZONE_ROOTS_MODE=1` and
  `SAFEZONE_PAGE_SIZE=32768`.
- `rift-checked-rootfree-safezone-hp` is wired as a lower-bound rootless label,
  but remains benchmark-only and not a safety claim.
- `unsafezone-hp` remains the lower-bound unsafe substrate control.

## Performance Gate

The prototype is useful only if it:

- matches checksums/output counts;
- beats or matches current `rift-checked` on focused checked operators;
- is competitive with `unsafezone-hp` on root-free workloads;
- preserves rejection of heap-retains-region, unrooted region-retains-heap,
  outer-retains-inner, closure escape, and use-after-close/reset cases.

## Current Status

The focused checked append-window backend gate passed. At 1M events,
`rift-checked-safezone-32k` is `29.444 ms` versus current
`rift-checked-api-cursor` at `30.922 ms`, with matching checksum and no RSS
regression.

The Common Crawl-like q1/q2 application follow-up improved checked mode but did
not clear the application gate:

- q1 1M: `rift-checked-safezone-32k` is `4512.743 ms` versus current
  `rift-checked` at `4744.872 ms` and trusted HPZone at `4278.440 ms`.
- q2 1M: `rift-checked-safezone-32k` is `4431.865 ms` versus current
  `rift-checked` at `4698.903 ms` and trusted HPZone at `4075.431 ms`.

Interpretation: SafeZone-family allocator mechanics can reduce checked
overhead, but the checked application path still has material
`StreamAppendWindow` container/API overhead. Do not treat this as a final
checked application-speed result.

Detailed evidence: `evidence/CHECKED_SAFEZONE_BACKEND_MATRIX.md`.
