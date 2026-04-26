package scala.scalanative.memory

import scala.annotation.{implicitNotFound, targetName}
import scala.compiletime.{erasedValue, error}

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

  private[memory] def retainHeapRoot[T <: AnyRef](
      value: T
  ): RiftRegion.HeapRoot[T]
}

object RiftRegion extends RiftRegionCompanionScalaVersionSpecific {
  final val HPZone: Int = RiftAllocator.HPZone
  final val Scoped: Int = RiftAllocator.Scoped
  final val Streaming: Int = RiftAllocator.Streaming

  sealed trait ScopedRegion extends RiftRegion
  sealed trait StreamingRegion extends RiftRegion

  /** A heap object explicitly retained by a live Rift region.
   *
   *  Rift slabs are not scanned by Scala Native's GC. If a region object needs
   *  to point at a heap object, the heap object must be reachable through some
   *  ordinary GC path. `HeapRoot` is the v1 explicit-root handle: the handle is
   *  stored in heap memory owned by the live region object, so the referent is
   *  visible to the GC even if region memory also points at the handle.
   */
  final class HeapRoot[+T <: AnyRef] private[memory] (
      private val referent: T
  ) {
    def value: T = referent
  }

  /** Small checked append-only buffer backed by a region-owned array.
   *
   *  The buffer object itself is ordinary heap control metadata whose capture
   *  set prevents it from escaping the owning region; the data array is
   *  region-owned. Operations take the owner token explicitly so the capture
   *  checker can reject cross-region values. Direct heap values are rejected by
   *  the checked compiler path unless wrapped in a `HeapRoot`.
   */
  final class ObjectBuffer[T <: Object] private[memory] (
      private val items: Array[Object]
  ) {
    private var used = 0

    def length: Int = used

    def capacity: Int = items.length

    private[memory] def appendTrusted(value: Object): Unit = {
      if (used >= items.length)
        throw new IndexOutOfBoundsException("Rift ObjectBuffer is full")
      items(used) = value
      used += 1
    }

    private[memory] def applyTrusted(index: Int): Object = {
      if (index < 0 || index >= used)
        throw new IndexOutOfBoundsException(index.toString)
      items(index)
    }
  }

  /** Snapshot of the trusted runtime-epoch escape path.
   *
   *  This is the dynamic Yak-style side of Rift's comparison story, not the
   *  intended checked API. The checked API should make these counters
   *  unnecessary by statically rejecting or rooting unsafe cross-boundary flows.
   */
  final case class RuntimeEpochStats(
      barrierChecks: Long,
      rememberedRefs: Long,
      promotedObjects: Long
  )

  /** Object-specific promotion hook for the trusted runtime-epoch path.
   *
   *  True Yak promotion copies arbitrary escaping object graphs using runtime
   *  object-layout information and rewrites references. Rift does not have that
   *  generic object copier yet, so this hook isolates the object-copying
   *  boundary while the region runtime owns the barrier, remember-set, and
   *  promotion accounting.
   */
  trait RuntimePromoter[-T <: AnyRef, +U <: AnyRef] {
    def promote(value: T): U
    def promotedObjectCount(value: T): Int = 1
  }

  /** Trusted runtime-managed epoch.
   *
   *  This is a dynamic escape/promotion mechanism for comparing against Yak.
   *  It deliberately sits outside `scoped`/`streaming` checked capture
   *  boundaries. Benchmarks and experiments that use it are measuring a runtime
   *  memory-management policy, not the future statically checked Rift API.
   */
  final class RuntimeEpoch private[memory] (kind: Int) {
    private var region: RiftRegion = RiftRegion.open(kind)
    private var closed = false
    private var barrierChecksValue = 0L
    private var rememberedRefsValue = 0L
    private var promotedObjectsValue = 0L

    private def checkOpen(): Unit =
      if (closed)
        throw new IllegalStateException("Rift runtime epoch is closed")

    def begin(): RiftRegion = {
      checkOpen()
      region.reset()
      region
    }

    def end(): Unit =
      checkOpen()

    inline def alloc[T <: AnyRef](inline obj: T): T = {
      checkOpen()
      region.alloc(obj).asInstanceOf[T]
    }

    def controlWriteOrNull[T <: AnyRef, U <: AnyRef](
        value: T,
        retain: Boolean
    )(using promoter: RuntimePromoter[T, U]): U = {
      checkOpen()
      barrierChecksValue += 1L
      if (retain) {
        rememberedRefsValue += 1L
        promotedObjectsValue += promoter.promotedObjectCount(value).toLong
        promoter.promote(value)
      } else null.asInstanceOf[U]
    }

    def statsSnapshot(): RuntimeEpochStats =
      RuntimeEpochStats(
        barrierChecksValue,
        rememberedRefsValue,
        promotedObjectsValue
      )

    def close(): Unit =
      if (!closed) {
        closed = true
        region.close()
        region = null
      }
  }

  /** Evidence that a checked region body may return `T`.
   *
   *  Scala-next capture checking currently misses one important closure case:
   *  a function value returned from a region body can capture a region-local
   *  object without the region capability appearing in the result type. Until
   *  that gap is closed, checked region boundaries reject direct function
   *  results. Trusted benchmark code can still use `open`/`trustedOpen`.
   */
  @implicitNotFound(
    "Rift checked regions cannot return this result type safely."
  )
  sealed trait CanReturnFromRegion[-T]

  object CanReturnFromRegion {
    private object AnyResult extends CanReturnFromRegion[Any]

    private inline def rejectFunctionResult(): Nothing =
      error(
        "Rift checked regions cannot return function values yet; returned closures may hide region-local captures."
      )

    inline given allowResult[T]: CanReturnFromRegion[T] =
      inline erasedValue[T] match {
        case _: Function0[?]       => rejectFunctionResult()
        case _: Function1[?, ?]    => rejectFunctionResult()
        case _: Function2[?, ?, ?] => rejectFunctionResult()
        case _: Function3[?, ?, ?, ?] => rejectFunctionResult()
        case _: Function4[?, ?, ?, ?, ?] => rejectFunctionResult()
        case _: Function5[?, ?, ?, ?, ?, ?] => rejectFunctionResult()
        case _: Function6[?, ?, ?, ?, ?, ?, ?] => rejectFunctionResult()
        case _: Function7[?, ?, ?, ?, ?, ?, ?, ?] => rejectFunctionResult()
        case _: Function8[?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function9[?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?] =>
          rejectFunctionResult()
        case _: Function16[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function17[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function18[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function19[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function20[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function21[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _: Function22[
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?,
              ?
            ] =>
          rejectFunctionResult()
        case _ => AnyResult.asInstanceOf[CanReturnFromRegion[T]]
      }
  }

  private[memory] val defaultAlignment: CSize =
    unsignedOf(castIntToRawSizeUnsigned(16))

  def init(initialSlabs: CSize): Unit =
    RiftAllocator.Impl.init(toRawSize(initialSlabs))

  def init(initialSlabs: Int): Unit =
    init(unsignedOf(castIntToRawSizeUnsigned(initialSlabs)))

  def shutdown(): Unit =
    RiftAllocator.Impl.shutdown()

  /** Opens a trusted runtime-managed epoch used to measure Yak-style dynamic
   *  escape handling. Prefer `scoped`/`streaming` for checked Rift code.
   */
  def runtimeEpoch(kind: Int = Streaming): RuntimeEpoch =
    new RuntimeEpoch(kind)

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
  final def scoped[T](body: (ScopedRegion^) ?=> T)(using
      canReturn: CanReturnFromRegion[T]
  ): T = {
    val region: ScopedRegion^ = openImpl(Scoped).asInstanceOf[ScopedRegion]
    try body(using region)
    finally region.close()
  }

  /** Runs `body` with one resettable streaming region, closed at block exit. */
  final def streaming[T](body: (StreamingRegion^) ?=> T)(using
      canReturn: CanReturnFromRegion[T]
  ): T = {
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
  )(using region: StreamingRegion^, canReturn: CanReturnFromRegion[T]): T = {
    try body(using region)
    finally region.reset()
  }

  /** Retains `value` through the live region's GC-visible root list.
   *
   *  Use this when a checked region object must refer to heap metadata. Direct
   *  region-to-heap ownership is unsafe in Rift because the GC does not scan
   *  region slabs.
   */
  def root[T <: AnyRef](value: T)(using
      region: RiftRegion^
  ): HeapRoot[T]^{region} =
    region.retainHeapRoot(value)

  /** Allocates a fixed-capacity checked object buffer in the implicit region. */
  def objectBuffer[T <: Object](capacity: Int)(using
      region: RiftRegion^
  ): ObjectBuffer[T]^{region} = {
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    new ObjectBuffer[T](items)
  }

  /** Appends `value` to a checked object buffer owned by `owner`. */
  def append[T <: Object](
      owner: RiftRegion^,
      buffer: ObjectBuffer[T]^{owner},
      value: T^{owner}
  ): Unit =
    buffer.appendTrusted(value.asInstanceOf[Object])

  /** Reads an element from a checked object buffer owned by `owner`. */
  def get[T <: Object](
      owner: RiftRegion^,
      buffer: ObjectBuffer[T]^{owner},
      index: Int
  ): T^{owner} =
    buffer.applyTrusted(index).asInstanceOf[T^{owner}]

  /** Returns the number of elements appended to a checked object buffer. */
  def length[T <: Object](
      owner: RiftRegion^,
      buffer: ObjectBuffer[T]^{owner}
  ): Int =
    buffer.length

  /** Owner-token method syntax for checked object buffers.
   *
   *  These methods keep the same explicit owner in the type signature as the
   *  companion functions above, but let checked code use `region.append(...)`
   *  and `region.get(...)` at the allocation boundary.
   */
  extension (owner: RiftRegion^)
    @targetName("appendToObjectBuffer")
    def append[T <: Object](
        buffer: ObjectBuffer[T]^{owner},
        value: T^{owner}
    ): Unit =
      buffer.appendTrusted(value.asInstanceOf[Object])

    @targetName("getFromObjectBuffer")
    def get[T <: Object](
        buffer: ObjectBuffer[T]^{owner},
        index: Int
    ): T^{owner} =
      RiftRegion.get(owner, buffer, index)

    @targetName("objectBufferLength")
    def length[T <: Object](buffer: ObjectBuffer[T]^{owner}): Int =
      RiftRegion.length(owner, buffer)

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
    private var heapRoots: List[RiftRegion.HeapRoot[AnyRef]] = Nil

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

    private[memory] override def retainHeapRoot[T <: AnyRef](
        value: T
    ): RiftRegion.HeapRoot[T] = {
      checkOpen()
      val root = new RiftRegion.HeapRoot(value)
      heapRoots = root.asInstanceOf[RiftRegion.HeapRoot[AnyRef]] :: heapRoots
      root
    }

    override def reset(): Unit = {
      checkOpen()
      heapRoots = Nil
      RiftAllocator.Impl.reset(handle)
    }

    override def close(): Unit = {
      checkOpen()
      flagIsOpen = false
      heapRoots = Nil
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
