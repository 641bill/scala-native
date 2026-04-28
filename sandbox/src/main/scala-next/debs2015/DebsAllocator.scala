package debs2015

import scala.language.experimental.captureChecking
import scala.scalanative.memory.{RiftRegion, SafeZone}

private[debs2015] final class DebsAllocator private (
    val kind: Int,
    val riftRegion: RiftRegion,
    val safeZone: SafeZone
) {
  def close(): Unit =
    kind match {
      case DebsAllocator.Rift         => riftRegion.close()
      case DebsAllocator.SafeZoneKind => SafeZone.close(safeZone)
      case _                          => ()
    }
}

private[debs2015] object DebsAllocator {
  final val Heap = 0
  final val Rift = 1
  final val SafeZoneKind = 2

  // SafeZone mode is a trusted benchmark control. Allocation call sites keep a
  // direct `new` expression for Scala Native lowering, then erase the SafeZone
  // capture so the same Q1/Q2 engine interfaces can compare heap/Rift/SafeZone.

  def open(kind: Int, riftKind: Int): DebsAllocator =
    kind match {
      case Heap         => heap()
      case Rift         => rift(riftKind)
      case SafeZoneKind => safeZone()
      case other =>
        throw new IllegalArgumentException(s"unknown DEBS allocator kind: $other")
    }

  def heap(): DebsAllocator =
    new DebsAllocator(Heap, null, null)

  def rift(kind: Int): DebsAllocator =
    new DebsAllocator(Rift, RiftRegion.open(kind), null)

  def safeZone(): DebsAllocator =
    new DebsAllocator(SafeZoneKind, null, SafeZone.open())
}
