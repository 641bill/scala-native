package scala.scalanative.memory

import scala.annotation.implicitNotFound

import scala.scalanative.runtime.{RawPtr, RawSize, RiftAllocator, fromRawPtr, toRawSize}
import scala.scalanative.runtime.Intrinsics.{
  castIntToRawSizeUnsigned,
  unsignedOf
}
import scala.scalanative.unsafe.{CSize, Ptr}
import scala.scalanative.unsigned._

@implicitNotFound("Given method requires an implicit Rift region.")
trait RiftRegion extends SafeZone {

  def alloc(size: CSize, align: CSize): Ptr[Byte]

  def alloc(size: CSize): Ptr[Byte] =
    alloc(size, RiftRegion.defaultAlignment)

  def alloc(size: Int): Ptr[Byte] =
    alloc(unsignedOf(castIntToRawSizeUnsigned(size)))

  def alloc(size: Int, align: Int): Ptr[Byte] =
    alloc(unsignedOf(castIntToRawSizeUnsigned(size)),
          unsignedOf(castIntToRawSizeUnsigned(align)))

  private[scalanative] override def allocImpl(
      cls: RawPtr,
      size: RawSize
  ): RawPtr

  def reset(): Unit

  override def close(): Unit

  override def isOpen: Boolean

  override def isClosed: Boolean = !isOpen
}

object RiftRegion extends RiftRegionCompanionScalaVersionSpecific {
  final val HPZone: Int = RiftAllocator.HPZone
  final val Scoped: Int = RiftAllocator.Scoped
  final val Streaming: Int = RiftAllocator.Streaming

  private[memory] val defaultAlignment: CSize =
    unsignedOf(castIntToRawSizeUnsigned(16))

  def init(initialSlabs: CSize): Unit =
    RiftAllocator.Impl.init(toRawSize(initialSlabs))

  def init(initialSlabs: Int): Unit =
    init(unsignedOf(castIntToRawSizeUnsigned(initialSlabs)))

  def shutdown(): Unit =
    RiftAllocator.Impl.shutdown()

  def open(kind: Int = HPZone): RiftRegion = {
    val handle = RiftAllocator.Impl.open(kind)
    if (handle == null)
      throw new OutOfMemoryError("failed to open Rift region")
    new MemoryRiftRegion(handle)
  }

  private final class MemoryRiftRegion(
      private[scalanative] override val handle: RawPtr)
      extends RiftRegion {
    private var flagIsOpen = true

    override def isOpen: Boolean = flagIsOpen

    override def checkOpen(): Unit =
      if (!flagIsOpen)
        throw new IllegalStateException("Rift region is already closed.")

    override def alloc(size: CSize, align: CSize): Ptr[Byte] = {
      checkOpen()
      fromRawPtr[Byte](
        RiftAllocator.Impl.allocRaw(handle, toRawSize(size), toRawSize(align))
      )
    }

    private[scalanative] override def allocImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr = {
      checkOpen()
      RiftAllocator.Impl.alloc(handle, cls, size)
    }

    override def reset(): Unit = {
      checkOpen()
      RiftAllocator.Impl.reset(handle)
    }

    override def close(): Unit = {
      checkOpen()
      flagIsOpen = false
      RiftAllocator.Impl.close(handle)
    }
  }
}
