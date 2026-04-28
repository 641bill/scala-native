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

  private[memory] def setDiagnosticFamily(family: Int): Unit
}

object RiftRegion extends RiftRegionCompanionScalaVersionSpecific {
  final val HPZone: Int = RiftAllocator.HPZone
  final val Scoped: Int = RiftAllocator.Scoped
  final val Streaming: Int = RiftAllocator.Streaming

  sealed trait ScopedRegion extends RiftRegion
  sealed trait StreamingRegion extends RiftRegion

  /** Heap control metadata for a child streaming lifetime.
   *
   *  The child region handle is captured by the parent streaming region that
   *  created this window. Child windows are closed through
   *  `RiftRegion.closeChildWindow`, which gives checked stream code one
   *  structured finalization boundary for unlinking parent metadata before
   *  the child slab lifetime ends.
   */
  final class ChildWindow private[memory] (
      val region: StreamingRegion^
  ) {
    private var closed = false

    def isOpen: Boolean =
      !closed && region.isOpen

    def isClosed: Boolean =
      !isOpen

    private[memory] def checkOpen(): Unit =
      if (!isOpen)
        throw new IllegalStateException("Rift child window is closed")

    private[memory] def close(): Unit =
      if (!closed) {
        closed = true
        region.close()
      }
  }

  /** Checked stream-bucket metadata around a child window.
   *
   *  `ChildBucket` is the reusable shape for stream operators that keep a
   *  child lifetime reachable from parent-owned control metadata until window
   *  eviction. It owns the child region directly to keep the common bucket
   *  path to one heap control object; callers use owner-token helpers to
   *  allocate from and close the bucket.
   */
  final class ChildBucket private[memory] (
      val region: StreamingRegion^
  ) {
    private var closed = false

    def isOpen: Boolean =
      !closed && region.isOpen

    def isClosed: Boolean =
      !isOpen

    private[memory] def checkOpen(): Unit =
      if (!isOpen)
        throw new IllegalStateException("Rift child bucket is closed")

    private[memory] def close(): Unit =
      if (!closed) {
        closed = true
        region.close()
      }
  }

  /** Heap control metadata for one bucket in a parent-owned stream arena.
   *
   *  The bucket owns a checked child streaming region. The bucket object is
   *  parent-captured control metadata; values allocated from its child region
   *  must be unlinked or consumed before the bucket is closed.
   */
  final class StreamBucket private[memory] (
      private[memory] val child: ChildBucket^,
      val startSeconds: Long
  ) {
    private[memory] var next: StreamBucket = null
    private[memory] var ownedRankKeyHeadPlusOne: Int = 0

    def isOpen: Boolean =
      child.isOpen

    def isClosed: Boolean =
      child.isClosed
  }

  /** Reusable checked stream-bucket arena.
   *
   *  This is the general version of the "fine event buckets plus coarser
   *  rank/output arenas" pattern used by streaming operators. It manages a
   *  monotonic linked list of child buckets whose start times are rounded down
   *  to `bucketSeconds`; callers still own the operator-specific cleanup that
   *  unlinks parent-visible references before closing each bucket.
   *
   *  The private list fields are trusted heap metadata. Public operations
   *  reattach the parent owner token before exposing a bucket or child region.
   */
  final class StreamBucketArena private[memory] (
      val bucketSeconds: Long
  ) {
    if (bucketSeconds <= 0L)
      throw new IllegalArgumentException("bucketSeconds must be positive")

    private[memory] var first: StreamBucket = null
    private[memory] var last: StreamBucket = null
    private[memory] var current: StreamBucket = null
  }

  /** Checked indexed rank storage tied to stream-window child buckets.
   *
   *  This combines the `StreamBucketArena` lifetime primitive with the
   *  checked dense-key ranking primitive. Values may be ordinary Scala objects
   *  allocated in a child bucket and deliberately widened to the parent stream
   *  through `streamBucketRegion`. The preferred `putWindowRankInBucket` path
   *  records which child bucket owns each key, so bucket close can unlink
   *  parent-visible rank references before the child region closes.
   */
  final class StreamWindowIndexedRank[T <: Object] private[memory] (
      private[memory] val buckets: StreamBucketArena,
      private[memory] val queue: RegionIndexedPriorityQueue[T],
      private[memory] val ownerPresentByKey: Array[Boolean],
      private[memory] val ownerStartByKey: Array[Long],
      private[memory] val nextOwnedKeyPlusOneByKey: Array[Int],
      private[memory] val previousOwnedKeyPlusOneByKey: Array[Int]
  ) {
    private[memory] def linkOwnedKey(key: Int, bucket: StreamBucket): Unit = {
      checkOwnedKey(key)
      if (ownerPresentByKey(key)) unlinkOwnedKey(key)
      ownerPresentByKey(key) = true
      ownerStartByKey(key) = bucket.startSeconds
      val keyPlusOne = key + 1
      val oldHead = bucket.ownedRankKeyHeadPlusOne
      nextOwnedKeyPlusOneByKey(key) = oldHead
      previousOwnedKeyPlusOneByKey(key) = 0
      if (oldHead != 0)
        previousOwnedKeyPlusOneByKey(oldHead - 1) = keyPlusOne
      bucket.ownedRankKeyHeadPlusOne = keyPlusOne
    }

    private[memory] def unlinkOwnedKey(key: Int): Unit = {
      checkOwnedKey(key)
      if (!ownerPresentByKey(key)) return

      val startSeconds = ownerStartByKey(key)
      var bucket = buckets.first
      while (bucket != null && bucket.startSeconds != startSeconds)
        bucket = bucket.next

      if (bucket != null) {
        val target = key + 1
        val previous = previousOwnedKeyPlusOneByKey(key)
        val next = nextOwnedKeyPlusOneByKey(key)
        if (previous == 0) bucket.ownedRankKeyHeadPlusOne = next
        else nextOwnedKeyPlusOneByKey(previous - 1) = next
        if (next != 0) previousOwnedKeyPlusOneByKey(next - 1) = previous
      }

      ownerPresentByKey(key) = false
      ownerStartByKey(key) = 0L
      nextOwnedKeyPlusOneByKey(key) = 0
      previousOwnedKeyPlusOneByKey(key) = 0
    }

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket
    ): Unit = {
      var current = bucket.ownedRankKeyHeadPlusOne
      bucket.ownedRankKeyHeadPlusOne = 0
      while (current != 0) {
        val key = current - 1
        val next = nextOwnedKeyPlusOneByKey(key)
        if (
          ownerPresentByKey(key) &&
          ownerStartByKey(key) == bucket.startSeconds
        ) {
          queue.removeTrusted(key)
          ownerPresentByKey(key) = false
          ownerStartByKey(key) = 0L
        }
        nextOwnedKeyPlusOneByKey(key) = 0
        previousOwnedKeyPlusOneByKey(key) = 0
        current = next
      }
    }

    private def checkOwnedKey(key: Int): Unit =
      if (key < 0 || key >= ownerPresentByKey.length)
        throw new IndexOutOfBoundsException(key.toString)
  }

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

  /** Growable checked buffer backed by region-owned arrays.
   *
   *  Like `ObjectBuffer`, the buffer object is heap control metadata captured
   *  by the owning region. Appending past capacity allocates a larger backing
   *  array in the same region and leaves the old array to be reclaimed when the
   *  region closes or resets.
   */
  final class RegionBuffer[T <: Object] private[memory] (
      private var items: Array[Object]
  ) {
    private var used = 0

    def length: Int = used

    def capacity: Int = items.length

    private[memory] def appendTrusted(
        owner: RiftRegion^,
        value: Object
    ): Unit = {
      if (used >= items.length) growTrusted(owner)
      items(used) = value
      used += 1
    }

    private[memory] def applyTrusted(index: Int): Object = {
      if (index < 0 || index >= used)
        throw new IndexOutOfBoundsException(index.toString)
      items(index)
    }

    private def growTrusted(owner: RiftRegion^): Unit = {
      val oldItems = items
      val nextCapacity =
        if (oldItems.length == 0) 1 else oldItems.length * 2
      val nextItems =
        owner.alloc(new Array[Object](nextCapacity)).asInstanceOf[Array[Object]]

      var i = 0
      while (i < used) {
        nextItems(i) = oldItems(i)
        i += 1
      }
      items = nextItems
    }
  }

  /** Growable max-priority queue backed by region-owned arrays.
   *
   *  This is the first reusable checked ranking primitive. The queue object is
   *  heap control metadata captured by the owning region; stored values and
   *  backing arrays are region-owned. Priorities are primitive metadata kept in
   *  a parallel region-owned `Long` array, so stream operators can express
   *  top-k/ranking state without each benchmark hand-rolling its own checked
   *  heap arrays.
   */
  final class RegionPriorityQueue[T <: Object] private[memory] (
      private var items: Array[Object],
      private var priorities: Array[Long]
  ) {
    private var used = 0

    def length: Int = used

    def capacity: Int = items.length

    private[memory] def pushTrusted(
        owner: RiftRegion^,
        value: Object,
        priority: Long
    ): Unit = {
      if (used >= items.length) growTrusted(owner)
      val index = used
      used += 1
      items(index) = value
      priorities(index) = priority
      siftUp(index)
    }

    private[memory] def peekTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException("Rift RegionPriorityQueue is empty")
      items(0)
    }

    private[memory] def peekPriorityTrusted(): Long = {
      if (used == 0)
        throw new NoSuchElementException("Rift RegionPriorityQueue is empty")
      priorities(0)
    }

    private[memory] def popTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException("Rift RegionPriorityQueue is empty")

      val result = items(0)
      val last = used - 1
      used = last
      if (last > 0) {
        items(0) = items(last)
        priorities(0) = priorities(last)
        items(last) = null
        priorities(last) = 0L
        siftDown(0)
      } else {
        items(0) = null
        priorities(0) = 0L
      }
      result
    }

    private def growTrusted(owner: RiftRegion^): Unit = {
      val oldItems = items
      val oldPriorities = priorities
      val nextCapacity =
        if (oldItems.length == 0) 1 else oldItems.length * 2
      val nextItems =
        owner.alloc(new Array[Object](nextCapacity)).asInstanceOf[Array[Object]]
      val nextPriorities = owner.alloc(new Array[Long](nextCapacity))

      var i = 0
      while (i < used) {
        nextItems(i) = oldItems(i)
        nextPriorities(i) = oldPriorities(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
    }

    private def siftUp(start: Int): Unit = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (priorities(parent) >= priorities(child)) return
        swap(parent, child)
        child = parent
      }
    }

    private def siftDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= used) return
        val right = left + 1
        var best = left
        if (right < used && priorities(right) > priorities(left))
          best = right
        if (priorities(parent) >= priorities(best)) return
        swap(parent, best)
        parent = best
      }
    }

    private def swap(left: Int, right: Int): Unit = {
      val leftItem = items(left)
      val leftPriority = priorities(left)
      items(left) = items(right)
      priorities(left) = priorities(right)
      items(right) = leftItem
      priorities(right) = leftPriority
    }
  }

  /** Dense-key indexed max-priority queue backed by region-owned arrays.
   *
   *  This is the reusable checked shape for stream operators that keep durable
   *  per-key region objects while updating their rank many times. Dense integer
   *  keys map to heap positions through a region-owned index table; values and
   *  priority/key arrays are region-owned. The queue object remains heap
   *  control metadata captured by the owner region.
   */
  final class RegionIndexedPriorityQueue[T <: Object] private[memory] (
      private var items: Array[Object],
      private var priorities: Array[Long],
      private var keys: Array[Int],
      private val heapIndexByKey: Array[Int]
  ) {
    private var used = 0

    def length: Int = used

    def capacity: Int = items.length

    def keyCapacity: Int = heapIndexByKey.length

    private[memory] def putTrusted(
        owner: RiftRegion^,
        key: Int,
        value: Object,
        priority: Long
    ): Unit = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot != 0) {
        val index = slot - 1
        items(index) = value
        priorities(index) = priority
        fixAt(index)
      } else {
        if (used >= items.length) growTrusted(owner)
        val index = used
        used += 1
        items(index) = value
        priorities(index) = priority
        keys(index) = key
        heapIndexByKey(key) = index + 1
        siftUp(index)
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Int,
        priority: Long
    ): Boolean = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0) false
      else {
        val index = slot - 1
        priorities(index) = priority
        fixAt(index)
        true
      }
    }

    private[memory] def removeTrusted(key: Int): Boolean = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0) false
      else {
        removeAt(slot - 1)
        true
      }
    }

    private[memory] def containsTrusted(key: Int): Boolean = {
      checkKey(key)
      heapIndexByKey(key) != 0
    }

    private[memory] def getTrusted(key: Int): Object = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0)
        throw new NoSuchElementException(
          "Rift RegionIndexedPriorityQueue key is absent"
        )
      items(slot - 1)
    }

    private[memory] def peekTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionIndexedPriorityQueue is empty"
        )
      items(0)
    }

    private[memory] def peekKeyTrusted(): Int = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionIndexedPriorityQueue is empty"
        )
      keys(0)
    }

    private[memory] def peekPriorityTrusted(): Long = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionIndexedPriorityQueue is empty"
        )
      priorities(0)
    }

    private[memory] def popTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionIndexedPriorityQueue is empty"
        )
      val result = items(0)
      removeAt(0)
      result
    }

    private def checkKey(key: Int): Unit =
      if (key < 0 || key >= heapIndexByKey.length)
        throw new IndexOutOfBoundsException(key.toString)

    private def growTrusted(owner: RiftRegion^): Unit = {
      val oldItems = items
      val oldPriorities = priorities
      val oldKeys = keys
      val nextCapacity =
        if (oldItems.length == 0) 1 else oldItems.length * 2
      val nextItems =
        owner.alloc(new Array[Object](nextCapacity)).asInstanceOf[Array[Object]]
      val nextPriorities = owner.alloc(new Array[Long](nextCapacity))
      val nextKeys = owner.alloc(new Array[Int](nextCapacity))

      var i = 0
      while (i < used) {
        nextItems(i) = oldItems(i)
        nextPriorities(i) = oldPriorities(i)
        nextKeys(i) = oldKeys(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
      keys = nextKeys
    }

    private def removeAt(index: Int): Unit = {
      val removedKey = keys(index)
      heapIndexByKey(removedKey) = 0
      val last = used - 1
      used = last
      if (index != last) {
        items(index) = items(last)
        priorities(index) = priorities(last)
        keys(index) = keys(last)
        heapIndexByKey(keys(index)) = index + 1
        items(last) = null
        priorities(last) = 0L
        keys(last) = 0
        fixAt(index)
      } else {
        items(index) = null
        priorities(index) = 0L
        keys(index) = 0
      }
    }

    private def fixAt(index: Int): Unit = {
      val beforeKey = keys(index)
      siftUp(index)
      val afterSlot = heapIndexByKey(beforeKey)
      if (afterSlot != 0) siftDown(afterSlot - 1)
    }

    private def siftUp(start: Int): Unit = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (priorities(parent) >= priorities(child)) return
        swap(parent, child)
        child = parent
      }
    }

    private def siftDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= used) return
        val right = left + 1
        var best = left
        if (right < used && priorities(right) > priorities(left))
          best = right
        if (priorities(parent) >= priorities(best)) return
        swap(parent, best)
        parent = best
      }
    }

    private def swap(left: Int, right: Int): Unit = {
      val leftItem = items(left)
      val leftPriority = priorities(left)
      val leftKey = keys(left)
      items(left) = items(right)
      priorities(left) = priorities(right)
      keys(left) = keys(right)
      heapIndexByKey(keys(left)) = left + 1
      items(right) = leftItem
      priorities(right) = leftPriority
      keys(right) = leftKey
      heapIndexByKey(keys(right)) = right + 1
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

  /** Opens a child streaming region whose handle is captured by `parent`.
   *
   *  This is the first checked multi-region building block for streaming
   *  windows. The child handle cannot escape the parent streaming boundary, but
   *  close ordering is still explicit and not affine-checked: callers must
   *  keep child-owned values in child-specific structures, unlink or consume
   *  them before closing the child region, and avoid widening them into
   *  parent-owned containers.
   */
  def childStreaming(using
      parent: StreamingRegion^
  ): StreamingRegion^{parent} =
    openImpl(Streaming).asInstanceOf[StreamingRegion]

  /** Opens a reusable checked child-window lifetime.
   *
   *  Prefer this over raw `childStreaming` when modeling stream windows,
   *  buckets, or micro-batches. It keeps the child handle in heap control
   *  metadata captured by the parent stream, while the caller keeps
   *  child-owned data in structures tied to `window.region`.
   */
  def childWindow(using parent: StreamingRegion^): ChildWindow^{parent} =
    new ChildWindow(childStreaming)

  /** Opens a reusable checked child bucket owned by the parent stream. */
  def childBucket(using parent: StreamingRegion^): ChildBucket^{parent} =
    new ChildBucket(childStreaming)

  /** Opens a parent-captured stream-bucket arena. */
  def streamBucketArena(bucketSeconds: Long)(using
      parent: StreamingRegion^
  ): StreamBucketArena^{parent} =
    new StreamBucketArena(bucketSeconds).asInstanceOf[StreamBucketArena^{parent}]

  /** Tags a region for opt-in benchmark diagnostics.
   *
   *  This does not change allocation or safety behavior. It only lets runtime
   *  counters attribute active mapped/requested bytes to coarse benchmark
   *  region families.
   */
  def setDiagnosticFamily(region: RiftRegion^, family: Int): Unit =
    region.setDiagnosticFamily(family)

  /** Tags a stream bucket's child region for opt-in diagnostics. */
  def setDiagnosticFamily(
      parent: StreamingRegion^,
      bucket: StreamBucket^{parent},
      family: Int
  ): Unit =
    setDiagnosticFamily(streamBucketRegion(parent, bucket), family)

  /** Returns a child window's region using the parent stream as owner token.
   *
   *  This is intentionally explicit. Some stream operators keep child-window
   *  records reachable from parent-lived control metadata until the window is
   *  evicted. The owner token documents that widening and keeps the lifetime
   *  relation local to checked stream code.
   */
  def childRegion(
      parent: StreamingRegion^,
      window: ChildWindow^{parent}
  ): StreamingRegion^{parent} = {
    window.checkOpen()
    window.region.asInstanceOf[StreamingRegion]
  }

  /** Returns a child bucket's region using the parent stream as owner token. */
  def childBucketRegion(
      parent: StreamingRegion^,
      bucket: ChildBucket^{parent}
  ): StreamingRegion^{parent} = {
    bucket.checkOpen()
    bucket.region.asInstanceOf[StreamingRegion]
  }

  /** Returns a stream bucket's child region using the parent as owner token. */
  def streamBucketRegion(
      parent: StreamingRegion^,
      bucket: StreamBucket^{parent}
  ): StreamingRegion^{parent} =
    childBucketRegion(
      parent,
      bucket.child.asInstanceOf[ChildBucket^{parent}]
    )

  /** Finds or opens the stream bucket containing `timestampSeconds`. */
  def streamBucketFor(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamBucketFor(parent, arena, timestampSeconds)(_ => ())

  /** Finds or opens the stream bucket containing `timestampSeconds`.
   *
   *  `onOpen` runs only when a new child bucket is created, which lets
   *  benchmarks tag diagnostics without adding per-event region metadata work.
   */
  def streamBucketFor(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} = {
    val startSeconds =
      Math.floorDiv(timestampSeconds, arena.bucketSeconds) * arena.bucketSeconds
    val current = arena.current
    if (
      current != null &&
      current.startSeconds == startSeconds &&
      current.isOpen
    )
      current.asInstanceOf[StreamBucket^{parent}]
    else {
      val child = childBucket(using parent)
      val arenaBucket: StreamBucket =
        new StreamBucket(child, startSeconds).asInstanceOf[StreamBucket]
      if (arena.first == null) {
        arena.first = arenaBucket
        arena.last = arenaBucket
      } else {
        arena.last.next = arenaBucket
        arena.last = arenaBucket
      }
      arena.current = arenaBucket
      val bucket = arenaBucket.asInstanceOf[StreamBucket^{parent}]
      onOpen(bucket)
      bucket
    }
  }

  /** Returns true if `closeStreamBucketsBefore` would close at least one bucket. */
  def hasStreamBucketsBefore(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      cutoffSeconds: Long
  ): Boolean =
    arena.first != null &&
      arena.first.startSeconds + arena.bucketSeconds <= cutoffSeconds

  /** Closes stream buckets whose whole interval is before `cutoffSeconds`.
   *
   *  The cleanup callback must remove parent-visible references to bucket-local
   *  values before the bucket's child region closes.
   */
  def closeStreamBucketsBefore(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      cutoffSeconds: Long
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    while (hasStreamBucketsBefore(parent, arena, cutoffSeconds)) {
      val bucket = arena.first.asInstanceOf[StreamBucket^{parent}]
      arena.first = bucket.next
      if (arena.first == null) arena.last = null
      if (arena.current.asInstanceOf[AnyRef] eq bucket.asInstanceOf[AnyRef])
        arena.current = null
      closeChildBucket(
        parent,
        bucket.child.asInstanceOf[ChildBucket^{parent}]
      ) {
        cleanup(bucket)
        bucket.next = null
      }
    }

  /** Closes every bucket in an arena. */
  def closeAllStreamBuckets(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent}
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    while (arena.first != null) {
      val bucket = arena.first.asInstanceOf[StreamBucket^{parent}]
      arena.first = bucket.next
      closeChildBucket(
        parent,
        bucket.child.asInstanceOf[ChildBucket^{parent}]
      ) {
        cleanup(bucket)
        bucket.next = null
      }
    }
    arena.last = null
    arena.current = null

  /** Closes a child window after caller-owned parent metadata is unlinked.
   *
   *  This is the preferred close boundary for checked stream windows and
   *  buckets. The cleanup block is intentionally `Unit`-returning: close-time
   *  code may clear parent fields, remove entries from parent tables, and
   *  consume child-owned values, but it should not produce a value that can be
   *  used after the child region closes.
   */
  def closeChildWindow(
      parent: StreamingRegion^,
      window: ChildWindow^{parent}
  )(cleanup: => Unit): Unit = {
    window.checkOpen()
    try cleanup
    finally window.close()
  }

  /** Closes a checked child bucket after parent metadata cleanup.
   *
   *  Prefer this over `closeChildWindow` for reusable stream buckets. The
   *  cleanup block has the same discipline: unlink parent-visible references
   *  to child-owned values, then the child region closes in `finally`.
   */
  def closeChildBucket(
      parent: StreamingRegion^,
      bucket: ChildBucket^{parent}
  )(cleanup: => Unit): Unit = {
    bucket.checkOpen()
    try cleanup
    finally bucket.close()
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

  /** Allocates a growable checked buffer in the implicit region. */
  def regionBuffer[T <: Object](initialCapacity: Int = 4)(using
      region: RiftRegion^
  ): RegionBuffer[T]^{region} = {
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    new RegionBuffer[T](items)
  }

  /** Allocates a checked max-priority queue in the implicit region. */
  def regionPriorityQueue[T <: Object](initialCapacity: Int = 4)(using
      region: RiftRegion^
  ): RegionPriorityQueue[T]^{region} = {
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    val priorities: Array[Long] = alloc(new Array[Long](capacity))
    new RegionPriorityQueue[T](items, priorities)
  }

  /** Allocates a checked dense-key indexed max-priority queue. */
  def regionIndexedPriorityQueue[T <: Object](
      keyCapacity: Int,
      initialCapacity: Int = 4
  )(using region: RiftRegion^): RegionIndexedPriorityQueue[T]^{region} = {
    if (keyCapacity <= 0)
      throw new IllegalArgumentException("keyCapacity must be positive")
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    val priorities: Array[Long] = alloc(new Array[Long](capacity))
    val keys: Array[Int] = alloc(new Array[Int](capacity))
    val heapIndexByKey: Array[Int] = alloc(new Array[Int](keyCapacity))
    new RegionIndexedPriorityQueue[T](
      items,
      priorities,
      keys,
      heapIndexByKey
    )
  }

  /** Allocates a checked stream-window indexed-rank collection.
   *
   *  The returned collection keeps parent-owned rank storage and a reusable
   *  stream-bucket arena. Use `putWindowRankInBucket` when ranked values live
   *  in a child bucket; close then removes those keys before closing the child
   *  region.
   */
  def streamWindowIndexedRank[T <: Object](
      bucketSeconds: Long,
      keyCapacity: Int,
      initialCapacity: Int = 4
  )(using parent: StreamingRegion^): StreamWindowIndexedRank[T]^{parent} = {
    val buckets = streamBucketArena(bucketSeconds)
    val queue = regionIndexedPriorityQueue[T](keyCapacity, initialCapacity)
    val ownerPresentByKey = alloc(new Array[Boolean](keyCapacity))
    val ownerStartByKey = alloc(new Array[Long](keyCapacity))
    val nextOwnedKeyPlusOneByKey = alloc(new Array[Int](keyCapacity))
    val previousOwnedKeyPlusOneByKey = alloc(new Array[Int](keyCapacity))
    new StreamWindowIndexedRank[T](
      buckets.asInstanceOf[StreamBucketArena],
      queue.asInstanceOf[RegionIndexedPriorityQueue[T]],
      ownerPresentByKey,
      ownerStartByKey,
      nextOwnedKeyPlusOneByKey,
      previousOwnedKeyPlusOneByKey
    ).asInstanceOf[StreamWindowIndexedRank[T]^{parent}]
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

  /** Appends `value` to a growable checked buffer owned by `owner`. */
  def append[T <: Object](
      owner: RiftRegion^,
      buffer: RegionBuffer[T]^{owner},
      value: T^{owner}
  ): Unit =
    buffer.appendTrusted(owner, value.asInstanceOf[Object])

  /** Reads an element from a growable checked buffer owned by `owner`. */
  def get[T <: Object](
      owner: RiftRegion^,
      buffer: RegionBuffer[T]^{owner},
      index: Int
  ): T^{owner} =
    buffer.applyTrusted(index).asInstanceOf[T^{owner}]

  /** Returns the number of elements appended to a growable checked buffer. */
  def length[T <: Object](
      owner: RiftRegion^,
      buffer: RegionBuffer[T]^{owner}
  ): Int =
    buffer.length

  /** Returns the current backing capacity of a growable checked buffer. */
  def capacity[T <: Object](
      owner: RiftRegion^,
      buffer: RegionBuffer[T]^{owner}
  ): Int =
    buffer.capacity

  /** Pushes `value` into a checked max-priority queue owned by `owner`. */
  def push[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner},
      value: T^{owner},
      priority: Long
  ): Unit =
    queue.pushTrusted(owner, value.asInstanceOf[Object], priority)

  /** Reads the highest-priority value without removing it. */
  def peek[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.peekTrusted().asInstanceOf[T^{owner}]

  /** Reads the highest priority without removing its value. */
  def peekPriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner}
  ): Long =
    queue.peekPriorityTrusted()

  /** Removes and returns the highest-priority value. */
  def pop[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.popTrusted().asInstanceOf[T^{owner}]

  /** Returns the number of elements in a checked max-priority queue. */
  def length[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner}
  ): Int =
    queue.length

  /** Returns the current backing capacity of a checked max-priority queue. */
  def capacity[T <: Object](
      owner: RiftRegion^,
      queue: RegionPriorityQueue[T]^{owner}
  ): Int =
    queue.capacity

  /** Inserts or replaces `value` for `key` in an indexed priority queue. */
  def put[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int,
      value: T^{owner},
      priority: Long
  ): Unit =
    queue.putTrusted(owner, key, value.asInstanceOf[Object], priority)

  /** Updates `key`'s priority if it is present. */
  def updatePriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int,
      priority: Long
  ): Boolean =
    queue.updatePriorityTrusted(key, priority)

  /** Removes `key` if it is present. */
  def remove[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int
  ): Boolean =
    queue.removeTrusted(key)

  /** Returns true when `key` is present. */
  def contains[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int
  ): Boolean =
    queue.containsTrusted(key)

  /** Reads the value for `key`. */
  def get[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int
  ): T^{owner} =
    queue.getTrusted(key).asInstanceOf[T^{owner}]

  /** Reads the highest-priority indexed value without removing it. */
  def peek[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.peekTrusted().asInstanceOf[T^{owner}]

  /** Reads the dense key of the highest-priority indexed value. */
  def peekKey[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.peekKeyTrusted()

  /** Reads the highest indexed priority without removing its value. */
  def peekPriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): Long =
    queue.peekPriorityTrusted()

  /** Removes and returns the highest-priority indexed value. */
  def pop[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.popTrusted().asInstanceOf[T^{owner}]

  /** Returns the number of elements in an indexed priority queue. */
  def length[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.length

  /** Returns the current heap backing capacity of an indexed priority queue. */
  def capacity[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.capacity

  /** Returns the dense-key table capacity of an indexed priority queue. */
  def keyCapacity[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.keyCapacity

  /** Finds or opens the window-rank bucket containing `timestampSeconds`. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamWindowBucketFor(parent, rank, timestampSeconds)(_ => ())

  /** Finds or opens the window-rank bucket containing `timestampSeconds`. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamBucketFor(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )(onOpen)

  /** Inserts or replaces a ranked value for `key`. */
  def putWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int,
      value: T^{parent},
      priority: Long
  ): Unit =
    rank.queue
      .asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
      .putTrusted(parent, key, value.asInstanceOf[Object], priority)

  /** Inserts or replaces a ranked value owned by `bucket`.
   *
   *  When the bucket closes, `closeWindowRankBucketsBefore` and
   *  `closeAllWindowRankBuckets` remove the key from parent-owned rank state
   *  before closing the bucket's child region.
   */
  def putWindowRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent},
      priority: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.queue
      .asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
      .putTrusted(parent, key, value.asInstanceOf[Object], priority)
    rank.linkOwnedKey(key, bucket.asInstanceOf[StreamBucket])
  }

  /** Updates `key`'s priority if it is present. */
  def updateWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int,
      priority: Long
  ): Boolean =
    updatePriority(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}],
      key,
      priority
    )

  /** Removes `key` if it is present. */
  def removeWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int
  ): Boolean = {
    val removed = remove(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}],
      key
    )
    if (removed) rank.unlinkOwnedKey(key)
    removed
  }

  /** Returns true when `key` is present. */
  def containsWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int
  ): Boolean =
    contains(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}],
      key
    )

  /** Reads the value for `key`. */
  def getWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int
  ): T^{parent} =
    get(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}],
      key
    )

  /** Reads the highest-priority ranked value without removing it. */
  def peekWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  ): T^{parent} =
    peek(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
    )

  /** Reads the dense key of the highest-priority ranked value. */
  def peekWindowRankKey[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  ): Int =
    peekKey(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
    )

  /** Reads the highest priority without removing the ranked value. */
  def peekWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  ): Long =
    peekPriority(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
    )

  /** Removes and returns the highest-priority ranked value. */
  def popWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  ): T^{parent} =
    pop(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
    )

  /** Returns the number of ranked values. */
  def windowRankLength[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  ): Int =
    length(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
    )

  /** Returns true if closing before `cutoffSeconds` would close a bucket. */
  def hasWindowRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      cutoffSeconds: Long
  ): Boolean =
    hasStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    )

  /** Closes window-rank buckets fully before `cutoffSeconds`. */
  def closeWindowRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      cutoffSeconds: Long
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket])
      cleanup(bucket)
    }

  /** Closes every window-rank bucket. */
  def closeAllWindowRankBuckets[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket])
      cleanup(bucket)
    }

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

    @targetName("appendToRegionBuffer")
    def append[T <: Object](
        buffer: RegionBuffer[T]^{owner},
        value: T^{owner}
    ): Unit =
      buffer.appendTrusted(owner, value.asInstanceOf[Object])

    @targetName("getFromRegionBuffer")
    def get[T <: Object](
        buffer: RegionBuffer[T]^{owner},
        index: Int
    ): T^{owner} =
      RiftRegion.get(owner, buffer, index)

    @targetName("regionBufferLength")
    def length[T <: Object](buffer: RegionBuffer[T]^{owner}): Int =
      RiftRegion.length(owner, buffer)

    @targetName("regionBufferCapacity")
    def capacity[T <: Object](buffer: RegionBuffer[T]^{owner}): Int =
      RiftRegion.capacity(owner, buffer)

    @targetName("pushToRegionPriorityQueue")
    def push[T <: Object](
        queue: RegionPriorityQueue[T]^{owner},
        value: T^{owner},
        priority: Long
    ): Unit =
      queue.pushTrusted(owner, value.asInstanceOf[Object], priority)

    @targetName("peekFromRegionPriorityQueue")
    def peek[T <: Object](
        queue: RegionPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.peek(owner, queue)

    @targetName("peekPriorityFromRegionPriorityQueue")
    def peekPriority[T <: Object](
        queue: RegionPriorityQueue[T]^{owner}
    ): Long =
      RiftRegion.peekPriority(owner, queue)

    @targetName("popFromRegionPriorityQueue")
    def pop[T <: Object](
        queue: RegionPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.pop(owner, queue)

    @targetName("regionPriorityQueueLength")
    def length[T <: Object](queue: RegionPriorityQueue[T]^{owner}): Int =
      RiftRegion.length(owner, queue)

    @targetName("regionPriorityQueueCapacity")
    def capacity[T <: Object](queue: RegionPriorityQueue[T]^{owner}): Int =
      RiftRegion.capacity(owner, queue)

    @targetName("putToRegionIndexedPriorityQueue")
    def put[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int,
        value: T^{owner},
        priority: Long
    ): Unit =
      queue.putTrusted(owner, key, value.asInstanceOf[Object], priority)

    @targetName("updateRegionIndexedPriorityQueuePriority")
    def updatePriority[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int,
        priority: Long
    ): Boolean =
      RiftRegion.updatePriority(owner, queue, key, priority)

    @targetName("removeFromRegionIndexedPriorityQueue")
    def remove[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int
    ): Boolean =
      RiftRegion.remove(owner, queue, key)

    @targetName("containsInRegionIndexedPriorityQueue")
    def contains[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int
    ): Boolean =
      RiftRegion.contains(owner, queue, key)

    @targetName("getFromRegionIndexedPriorityQueue")
    def get[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int
    ): T^{owner} =
      RiftRegion.get(owner, queue, key)

    @targetName("peekFromRegionIndexedPriorityQueue")
    def peek[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.peek(owner, queue)

    @targetName("peekKeyFromRegionIndexedPriorityQueue")
    def peekKey[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.peekKey(owner, queue)

    @targetName("peekPriorityFromRegionIndexedPriorityQueue")
    def peekPriority[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): Long =
      RiftRegion.peekPriority(owner, queue)

    @targetName("popFromRegionIndexedPriorityQueue")
    def pop[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.pop(owner, queue)

    @targetName("regionIndexedPriorityQueueLength")
    def length[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.length(owner, queue)

    @targetName("regionIndexedPriorityQueueCapacity")
    def capacity[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.capacity(owner, queue)

    @targetName("regionIndexedPriorityQueueKeyCapacity")
    def keyCapacity[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.keyCapacity(owner, queue)

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

    private[memory] override def setDiagnosticFamily(family: Int): Unit = {
      checkOpen()
      RiftAllocator.Impl.setFamily(handle, family)
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
