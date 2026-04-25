package scala.scalanative.memory

import scala.annotation.implicitNotFound

import scala.scalanative.runtime.{
  RawPtr,
  RawSize,
  RiftAllocator,
  fromRawPtr,
  toRawSize
}
import scala.scalanative.runtime.Intrinsics.{
  castIntToRawSizeUnsigned,
  unsignedOf
}
import scala.scalanative.unsafe.{CSize, Ptr}
import scala.scalanative.unsigned._

import language.experimental.captureChecking

@implicitNotFound("Given method requires an implicit Rift region.")
trait RiftRegion extends SafeZone {

  /** In Scala-next, the inherited `SafeZone.alloc(new T(...))` member returns
   *  `T^{this}`. `RiftRegion` overrides `allocImpl`, so that checked member
   *  allocation still uses the Rift allocator.
   */

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

  /** Low-level reset used by trusted HPZone/benchmark code.
   *
   *  Checked streaming code should prefer `RiftRegion.reset { ... }`, which
   *  prevents region-local values created in the reset block from escaping.
   */
  def reset(): Unit

  override def close(): Unit

  override def isOpen: Boolean

  override def isClosed: Boolean = !isOpen
}

object RiftRegion extends RiftRegionCompanionScalaVersionSpecific {
  final val HPZone: Int = RiftAllocator.HPZone
  final val Scoped: Int = RiftAllocator.Scoped
  final val Streaming: Int = RiftAllocator.Streaming

  sealed trait ScopedRegion extends RiftRegion
  sealed trait StreamingRegion extends RiftRegion

  private[memory] val defaultAlignment: CSize =
    unsignedOf(castIntToRawSizeUnsigned(16))

  def init(initialSlabs: CSize): Unit =
    RiftAllocator.Impl.init(toRawSize(initialSlabs))

  def init(initialSlabs: Int): Unit =
    init(unsignedOf(castIntToRawSizeUnsigned(initialSlabs)))

  def shutdown(): Unit =
    RiftAllocator.Impl.shutdown()

  /** Opens a low-level trusted Rift region.
   *
   *  This is the API used by HPZone benchmarks and existing experiments. It
   *  does not enforce non-escape, closure-capture, or mixed GC/region
   *  reference rules. Prefer `scoped` or `streaming` for the checked boundary.
   */
  def open(kind: Int = HPZone): RiftRegion =
    openImpl(kind)

  /** Alias for `open` that makes the trust boundary explicit at call sites. */
  def trustedOpen(kind: Int = HPZone): RiftRegion =
    open(kind)

  /** Runs `body` with a fresh lexically scoped Rift region.
   *
   *  Values allocated with `alloc` in this block capture the scoped region
   *  capability and cannot escape the block under Scala capture checking.
   */
  final def scoped[T](body: (ScopedRegion^) ?=> T): T = {
    val region: ScopedRegion^ = openImpl(Scoped).asInstanceOf[ScopedRegion]
    try body(using region)
    finally region.close()
  }

  /** Runs `body` with one resettable streaming region, closed at block exit. */
  final def streaming[T](body: (StreamingRegion^) ?=> T): T = {
    val region: StreamingRegion^ =
      openImpl(Streaming).asInstanceOf[StreamingRegion]
    try body(using region)
    finally region.close()
  }

  /** Runs one streaming epoch and resets the region after `body`.
   *
   *  The result type may not retain values allocated in the epoch. This is the
   *  checked reset boundary; direct `region.reset()` remains a trusted low-level
   *  operation for benchmark code.
   */
  final def reset[T](
      body: (StreamingRegion^) ?=> T
  )(using region: StreamingRegion^): T = {
    try body(using region)
    finally region.reset()
  }

  /** Allocates an object in the implicit Rift region. */
  inline def alloc[T <: AnyRef](inline obj: T)(using
      region: RiftRegion^
  ): T^{region} =
    RiftAllocator.allocate(region, obj)

  /** Summon the implicit Rift region. */
  transparent inline def region(using region: RiftRegion^): RiftRegion^{region} =
    region

  transparent inline def scopedRegion(using
      region: ScopedRegion^
  ): ScopedRegion^{region} =
    region

  transparent inline def streamingRegion(using
      region: StreamingRegion^
  ): StreamingRegion^{region} =
    region

  private def openImpl(kind: Int): RiftRegion = {
    val handle = RiftAllocator.Impl.open(kind)
    if (handle == null)
      throw new OutOfMemoryError("failed to open Rift region")
    kind match {
      case Scoped    => new MemoryScopedRiftRegion(handle)
      case Streaming => new MemoryStreamingRiftRegion(handle)
      case _         => new MemoryRiftRegion(handle)
    }
  }

  private class MemoryRiftRegion(
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

    @noinline
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

  private final class MemoryScopedRiftRegion(handle: RawPtr)
      extends MemoryRiftRegion(handle)
      with ScopedRegion

  private final class MemoryStreamingRiftRegion(handle: RawPtr)
      extends MemoryRiftRegion(handle)
      with StreamingRegion
}
