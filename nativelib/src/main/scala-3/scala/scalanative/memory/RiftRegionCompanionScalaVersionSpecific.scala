package scala.scalanative.memory

import scala.scalanative.runtime.RiftAllocator

trait RiftRegionCompanionScalaVersionSpecific { self: RiftRegion.type =>
  extension (region: RiftRegion)
    inline def alloc[T <: AnyRef](inline obj: T): T =
      RiftAllocator.allocate(region, obj)
}
