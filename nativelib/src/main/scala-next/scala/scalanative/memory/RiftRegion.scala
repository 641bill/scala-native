package scala.scalanative.memory

import scala.annotation.{implicitNotFound, targetName}
import scala.compiletime.{erasedValue, error}

import scala.scalanative.runtime.{
  RawPtr,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator,
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

/** Experimental allocation-lowering token for focused Rift backend tests.
 *
 *  This carries only a raw Rift handle and is not a replacement for the public
 *  checked region APIs. Keep user-facing checked code on `epoch`,
 *  page/window operators, and `OpenStreamingRegion` until this path is folded
 *  back into compiler-owned lowering.
 */
final class RiftOpenStreamingHandle private[scalanative] (
    private[scalanative] override val handle: RawPtr
) extends SafeZone {
  // This handle is only exposed inside operator-owned lifetimes. The owner
  // controls close/reset, so allocation through the handle remains unchecked.
  override def isOpen: Boolean = true

  override def isClosed: Boolean = false

  private[scalanative] override def close(): Unit =
    throw new UnsupportedOperationException(
      "Rift open streaming handles are closed by their owner"
    )

  private[scalanative] override def allocImpl(
      cls: RawPtr,
      size: RawSize
  ): RawPtr =
    RiftAllocator.Impl.alloc(handle, cls, size)
}

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

  private[scalanative] def allocUncheckedImpl(
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

  sealed trait ScopedRegion extends RiftRegion {
    private[scalanative] override def allocImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr
  }

  sealed trait StreamingRegion extends RiftRegion {
    private[scalanative] override def allocImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr
  }

  /** Operator-owned open streaming allocation token.
   *
   *  This marker is only returned by APIs that control the bucket open/close
   *  order themselves. Allocations through it use an unchecked allocation
   *  lowering that skips the per-object open check; generic region allocation
   *  remains defensive.
   */
  sealed trait OpenStreamingRegion extends StreamingRegion

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
    private[memory] var appendHead: Object = null
    private[memory] var appendTail: Object = null
    private[memory] var appendLength: Int = 0
    private[memory] var chunkHead: Object = null
    private[memory] var chunkTail: Object = null
    private[memory] var chunkLength: Int = 0
    private[memory] var ownedRankKeyHeadPlusOne: Int = 0
    private[memory] var ownedLongRankSlotHeadPlusOne: Int = 0
    private[memory] var ownedTableRankSlotHeadPlusOne: Int = 0

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

  /** Base node for checked append-window streams.
   *
   *  The append-window primitive deliberately does not allocate a wrapper node
   *  per event. User records extend this base class, and the framework owns
   *  the hidden next pointer used to link records inside a child bucket.
   */
  abstract class StreamAppendNode {
    private[memory] var appendNext: StreamAppendNode = null
  }

  /** Base node for checked region-owned linked lists. */
  abstract class RegionListNode {
    private[memory] var regionListNext: Object = null
  }

  /** Checked region-owned singly linked list.
   *
   *  The list header and nodes live in the same region. This is a reusable
   *  topology primitive for linked object graphs whose whole structure dies at
   *  a scoped region boundary, such as ListOfLists-style workloads.
   */
  final class RegionList[T <: RegionListNode] private[memory] (
      private[memory] var headNode: Object,
      private[memory] var length0: Int
  )

  /** Checked append/fold stream-window primitive.
   *
   *  This is the reusable version of the cheap child-bucket append pattern:
   *  ordinary Scala records live in child bucket regions, while parent-owned
   *  heap metadata keeps bucket heads/tails until structured close. Close
   *  helpers clear those parent references and consume records before closing
   *  each child region.
   */
  final class StreamAppendWindow[T <: StreamAppendNode] private[memory] (
      private[memory] val buckets: StreamBucketArena
  ) {
    private[memory] var totalLength: Int = 0
    private[memory] val cursor: StreamAppendCursor[T] =
      new StreamAppendCursor[T](null)
  }

  /** Checked page/token append-window primitive.
   *
   *  This is the lower-overhead sibling of `StreamAppendWindow` for parser and
   *  tokenization workloads. The operator owns the current bucket cache and
   *  child-region lookup, so hot-path append does not need to re-check a bucket
   *  token that user code can no longer pass around directly.
   */
  final class StreamPageTokenAppendWindow[T <: StreamAppendNode] private[memory] (
      private[memory] val append: StreamAppendWindow[T]
  ) {
    private[memory] var currentBucket: StreamBucket = null
  }

  /** Checked map/filter page-token operator.
   *
   *  This is the public reusable name for the fastest checked SELECT-shaped
   *  path: user records and projected outputs live in page/bucket child
   *  regions, while the operator owns current-bucket caching and bulk cursor
   *  close. It is intentionally a thin wrapper over `StreamPageTokenAppendWindow`
   *  so benchmark code does not depend on the lower-level append primitive.
   */
  final class PageTokenMapFilter[T <: StreamAppendNode] private[memory] (
      private[memory] val pageToken: StreamPageTokenAppendWindow[T]
  )

  /** Checked page-token count/sum-by-key operator.
   *
   *  This is the reusable no-drain window-count shape: ordinary records still
   *  live in child bucket regions, while parent-owned primitive metadata keeps
   *  per-key counts/sums as records are appended. Closing a bucket can emit the
   *  aggregate summary and close the child region without walking every record
   *  again.
   */
  final class PageTokenCountByKey[T <: StreamAppendNode] private[memory] (
      private[memory] val pageToken: StreamPageTokenAppendWindow[T],
      private[memory] val keySpace: Int,
      private[memory] val slotCount: Int,
      private[memory] val bucketStarts: Array[Long],
      private[memory] val counts: Array[Int],
      private[memory] val sums: Array[Long]
  ) {
    private[memory] var currentSlot: Int = -1
  }

  /** Checked epoch append/drain operator.
   *
   *  This is the reusable primitive for workloads whose natural lifetime is a
   *  whole epoch or batch rather than a timestamped sliding window. Ordinary
   *  Scala records live in one child region for the active epoch; parent
   *  metadata keeps only the linked-list head/tail/count until bulk cursor
   *  close drains the epoch and closes the child region.
   */
  final class EpochBuffer[T <: StreamAppendNode] private[memory] (
      private[memory] val append: StreamAppendWindow[T]
  ) {
    private[memory] var currentBucket: StreamBucket = null
  }

  /** Checked multi-list transaction/batch region.
   *
   *  A transaction owns one active child region and several typed append lists.
   *  This is the reusable shape for stream pipelines with multiple temporary
   *  stages inside the same batch: open one child region, append/drain several
   *  lists, then close the child region once at transaction end.
   */
  final class TransactionRegion private[memory] (
      private[memory] val lists: Array[Object]
  ) {
    private[memory] var child: ChildBucket = null
    private[memory] val cursor: StreamAppendCursor[StreamAppendNode] =
      new StreamAppendCursor[StreamAppendNode](null)
  }

  /** Typed append list inside a checked transaction region. */
  final class TransactionList[T <: StreamAppendNode] private[memory] (
      private[memory] val tx: TransactionRegion,
      private[memory] val index: Int
  ) {
    private[memory] var head: Object = null
    private[memory] var tail: Object = null
    private[memory] var length0: Int = 0
  }

  /** One region-owned fixed chunk for checked chunk append windows. */
  final class StreamChunk private[memory] (
      private[memory] val items: Array[Object]
  ) {
    private[memory] var used: Int = 0
    private[memory] var next: StreamChunk = null
  }

  /** Close-time cursor over fixed chunks in one stream bucket. */
  final class StreamChunkCursor[T <: Object] private[memory] (
      private[memory] var currentChunk: StreamChunk,
      private[memory] var currentIndex: Int
  ) {
    def hasNext: Boolean =
      currentChunk != null

    def next(): T = {
      if (currentChunk == null)
        throw new NoSuchElementException("empty StreamChunkCursor")
      val chunk = currentChunk
      val value = chunk.items(currentIndex).asInstanceOf[T]
      chunk.items(currentIndex) = null
      currentIndex += 1
      if (currentIndex >= chunk.used) {
        currentChunk = chunk.next
        chunk.next = null
        currentIndex = 0
      }
      value
    }
  }

  /** Checked fixed-chunk append-window primitive.
   *
   *  This is the array/chunk sibling of `StreamPageTokenAppendWindow`. It is
   *  intended for page, batch, and window workloads where the operator owns the
   *  lifetime boundary and can append records into region-owned object-array
   *  chunks instead of linking every record through an `appendNext` field.
   */
  final class StreamChunkAppendWindow[T <: Object] private[memory] (
      private[memory] val buckets: StreamBucketArena,
      private[memory] val chunkSize: Int
  ) {
    private[memory] var totalLength: Int = 0
    private[memory] var currentBucket: StreamBucket = null
    private[memory] val cursor: StreamChunkCursor[T] =
      new StreamChunkCursor[T](null, 0)
  }

  /** Checked two-sided append/join stream-window primitive.
   *
   *  This factors the NEXMark Q8-shaped pattern out of benchmark code: records
   *  still live in child bucket regions and are drained through the
   *  append-window close cursor, while parent-owned primitive metadata tracks
   *  how many left/right records are currently live per key.
   */
  final class StreamJoinWindow[T <: StreamAppendNode] private[memory] (
      private[memory] val append: StreamAppendWindow[T],
      private[memory] val leftCounts: Array[Int],
      private[memory] val rightCounts: Array[Int]
  )

  /** Checked additive fold stream-window primitive.
   *
   *  Records live in child bucket regions through the underlying
   *  `StreamAppendWindow`. Parent-owned primitive arrays keep aggregate
   *  metadata so simple count/sum windows do not need a heap map or ranking
   *  container on the hot path.
   */
  final class StreamWindowFold[T <: StreamAppendNode] private[memory] (
      private[memory] val append: StreamAppendWindow[T],
      private[memory] var keys: Array[Int],
      private[memory] var sums: Array[Long],
      private[memory] var counts: Array[Int],
      private[memory] var states: Array[Byte],
      private[memory] var size: Int,
      private[memory] var deleted: Int
  )

  /** Checked epoch-local fold/count/sum operator.
   *
   *  Event records live in the current child bucket, while aggregate metadata
   *  stays in parent-owned primitive arrays. Closing an epoch drains child
   *  records and clears the whole aggregate table, avoiding per-entry
   *  contribution removal when the static lifetime is one complete epoch.
   */
  final class EpochFold[T <: StreamAppendNode] private[memory] (
      private[memory] val fold: StreamWindowFold[T]
  ) {
    private[memory] var currentBucket: StreamBucket = null
  }

  /** Checked epoch-local top-k-by-key operator.
   *
   *  Ordinary records live in direct `epoch` child regions. Parent-owned
   *  primitive metadata counts keys during append and computes top-k output at
   *  the epoch boundary without traversing the retained record graph. This is
   *  the reusable shape for LogHub template-ranking and Yak-style epochal
   *  top-word workloads where records naturally die at the batch boundary.
   */
  final class EpochTopKByKey private[memory] (
      private[memory] val keySpace: Int,
      private[memory] val topK: Int,
      private[memory] val counts: Array[Int],
      private[memory] val topKeys: Array[Int],
      private[memory] val topCounts: Array[Int]
  ) {
    private[memory] var topLength: Int = 0
  }

  /** Close-time cursor over records linked in one append-window bucket.
   *
   *  Cursor close drains amortize close callback dispatch to once per bucket.
   *  `next()` also clears the hidden append link so parent metadata cannot keep
   *  a chain of child-bucket records reachable after close cleanup completes.
   */
  final class StreamAppendCursor[T <: StreamAppendNode] private[memory] (
      private[memory] var current: StreamAppendNode
  ) {
    def hasNext: Boolean =
      current != null

    /** Returns the next record, or null when the cursor is exhausted.
     *
     *  This keeps close-time bucket drains on the single-call fast path while
     *  preserving the defensive link clearing used by public cursor consumers.
     */
    def nextOrNull(): T | Null = {
      val value0 = current
      if (value0 == null) null
      else {
        val value = value0.asInstanceOf[T]
        current = value.appendNext
        value.appendNext = null
        value
      }
    }

    /** Returns the next record without clearing its link field.
     *
     *  This is for operator-owned page-token close callbacks where parent
     *  bucket references have already been cleared and the child region closes
     *  immediately after the callback. Use `next`/`nextOrNull` for generic
     *  public cursor drains that need defensive link clearing.
     */
    def nextOwnedOrNull(): T | Null = {
      val value0 = current
      if (value0 == null) null
      else {
        val value = value0.asInstanceOf[T]
        current = value.appendNext
        value
      }
    }

    def next(): T = {
      if (current == null)
        throw new NoSuchElementException("empty StreamAppendCursor")
      val value = current.asInstanceOf[T]
      current = value.appendNext
      value.appendNext = null
      value
    }
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
    ): Unit =
      removeOwnedKeysForBucket(bucket, null)

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket,
        cleanup: (Int, Object) => Unit
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
          val value = queue.removeWithValueTrusted(key)
          ownerPresentByKey(key) = false
          ownerStartByKey(key) = 0L
          if (value != null && cleanup != null) cleanup(key, value)
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

  /** Long-key indexed rank storage tied to stream-window child buckets.
   *
   *  This is the hash-keyed sibling of `StreamWindowIndexedRank`. It keeps the
   *  rank queue and bucket-owner table in region-owned arrays so stream
   *  operators can use meaningful packed `Long` keys without adding a dense
   *  remapping layer. Bucket close unlinks parent-visible rank references
   *  before the child region is closed.
   */
  final class StreamWindowLongIndexedRank[T <: Object] private[memory] (
      private[memory] val buckets: StreamBucketArena,
      private[memory] val queue: RegionLongIndexedPriorityQueue[T],
      private var ownerKeys: Array[Long],
      private var ownerStates: Array[Byte],
      private var ownerStartBySlot: Array[Long],
      private var nextOwnedSlotPlusOneBySlot: Array[Int],
      private var previousOwnedSlotPlusOneBySlot: Array[Int]
  ) {
    private final val Empty: Byte = 0
    private final val Used: Byte = 1
    private final val Deleted: Byte = 2

    private var ownerActive = 0
    private var ownerUsed = 0

    private[memory] def linkOwnedKey(
        owner: RiftRegion^,
        key: Long,
        bucket: StreamBucket
    ): Unit = {
      val existing = findExistingOwnerSlot(key)
      if (existing >= 0) {
        unlinkOwnerSlotFromBucket(existing)
        ownerStartBySlot(existing) = bucket.startSeconds
        linkOwnerSlotToBucket(existing, bucket)
      } else {
        ensureOwnerCapacity(owner)
        val slot = findInsertOwnerSlot(key)
        insertOwnerSlot(slot, key)
        ownerStartBySlot(slot) = bucket.startSeconds
        linkOwnerSlotToBucket(slot, bucket)
      }
    }

    private[memory] def unlinkOwnedKey(key: Long): Unit = {
      val slot = findExistingOwnerSlot(key)
      if (slot >= 0) {
        unlinkOwnerSlotFromBucket(slot)
        markOwnerSlotDeleted(slot)
      }
    }

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket
    ): Unit =
      removeOwnedKeysForBucket(bucket, null)

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket,
        cleanup: (Long, Object) => Unit
    ): Unit = {
      var current = bucket.ownedLongRankSlotHeadPlusOne
      bucket.ownedLongRankSlotHeadPlusOne = 0
      while (current != 0) {
        val slot = current - 1
        val next = nextOwnedSlotPlusOneBySlot(slot)
        if (
          ownerStates(slot) == Used &&
          ownerStartBySlot(slot) == bucket.startSeconds
        ) {
          val key = ownerKeys(slot)
          val value = queue.removeWithValueTrusted(key)
          markOwnerSlotDeleted(slot)
          if (value != null && cleanup != null) cleanup(key, value)
        }
        nextOwnedSlotPlusOneBySlot(slot) = 0
        previousOwnedSlotPlusOneBySlot(slot) = 0
        current = next
      }
    }

    private def ensureOwnerCapacity(owner: RiftRegion^): Unit =
      if ((ownerUsed + 1) * 4 >= ownerKeys.length * 3) {
        val compactOnly = ownerActive * 2 < ownerUsed
        val nextCapacity =
          if (compactOnly) ownerKeys.length else ownerKeys.length << 1
        rehashOwnerTable(owner, nextCapacity)
      }

    private def rehashOwnerTable(
        owner: RiftRegion^,
        nextCapacity: Int
    ): Unit = {
      val oldKeys = ownerKeys
      val oldStates = ownerStates
      val oldStartBySlot = ownerStartBySlot

      ownerKeys = owner.alloc(new Array[Long](nextCapacity))
      ownerStates = owner.alloc(new Array[Byte](nextCapacity))
      ownerStartBySlot = owner.alloc(new Array[Long](nextCapacity))
      nextOwnedSlotPlusOneBySlot = owner.alloc(new Array[Int](nextCapacity))
      previousOwnedSlotPlusOneBySlot =
        owner.alloc(new Array[Int](nextCapacity))
      ownerActive = 0
      ownerUsed = 0

      var bucket = buckets.first
      while (bucket != null) {
        bucket.ownedLongRankSlotHeadPlusOne = 0
        bucket = bucket.next
      }

      var index = 0
      while (index < oldKeys.length) {
        if (oldStates(index) == Used) {
          val key = oldKeys(index)
          val slot = findInsertOwnerSlot(key)
          insertOwnerSlot(slot, key)
          ownerStartBySlot(slot) = oldStartBySlot(index)
          val ownerBucket = findBucket(ownerStartBySlot(slot))
          if (ownerBucket != null) linkOwnerSlotToBucket(slot, ownerBucket)
        }
        index += 1
      }
    }

    private def linkOwnerSlotToBucket(
        slot: Int,
        bucket: StreamBucket
    ): Unit = {
      val slotPlusOne = slot + 1
      val oldHead = bucket.ownedLongRankSlotHeadPlusOne
      nextOwnedSlotPlusOneBySlot(slot) = oldHead
      previousOwnedSlotPlusOneBySlot(slot) = 0
      if (oldHead != 0)
        previousOwnedSlotPlusOneBySlot(oldHead - 1) = slotPlusOne
      bucket.ownedLongRankSlotHeadPlusOne = slotPlusOne
    }

    private def unlinkOwnerSlotFromBucket(slot: Int): Unit = {
      val bucket = findBucket(ownerStartBySlot(slot))
      if (bucket != null) {
        val previous = previousOwnedSlotPlusOneBySlot(slot)
        val next = nextOwnedSlotPlusOneBySlot(slot)
        if (previous == 0) bucket.ownedLongRankSlotHeadPlusOne = next
        else nextOwnedSlotPlusOneBySlot(previous - 1) = next
        if (next != 0) previousOwnedSlotPlusOneBySlot(next - 1) = previous
      }
      nextOwnedSlotPlusOneBySlot(slot) = 0
      previousOwnedSlotPlusOneBySlot(slot) = 0
    }

    private def markOwnerSlotDeleted(slot: Int): Unit = {
      ownerStates(slot) = Deleted
      ownerStartBySlot(slot) = 0L
      nextOwnedSlotPlusOneBySlot(slot) = 0
      previousOwnedSlotPlusOneBySlot(slot) = 0
      ownerActive -= 1
    }

    private def findBucket(startSeconds: Long): StreamBucket = {
      var bucket = buckets.first
      while (bucket != null && bucket.startSeconds != startSeconds)
        bucket = bucket.next
      bucket
    }

    private def findExistingOwnerSlot(key: Long): Int = {
      val mask = ownerKeys.length - 1
      var slot = hashKey(key) & mask
      var probes = 0
      while (probes < ownerKeys.length) {
        val state = ownerStates(slot)
        if (state == Empty) return -1
        if (state == Used && ownerKeys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      -1
    }

    private def findInsertOwnerSlot(key: Long): Int = {
      val mask = ownerKeys.length - 1
      var slot = hashKey(key) & mask
      var firstDeleted = -1
      var probes = 0
      while (probes < ownerKeys.length) {
        val state = ownerStates(slot)
        if (state == Empty)
          return if (firstDeleted >= 0) firstDeleted else slot
        if (state == Deleted && firstDeleted < 0) firstDeleted = slot
        if (state == Used && ownerKeys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      if (firstDeleted >= 0) firstDeleted
      else throw new IllegalStateException("Rift long rank owner table is full")
    }

    private def insertOwnerSlot(slot: Int, key: Long): Unit = {
      if (ownerStates(slot) == Empty) ownerUsed += 1
      if (ownerStates(slot) != Used) ownerActive += 1
      ownerStates(slot) = Used
      ownerKeys(slot) = key
    }

    private def hashKey(key: Long): Int = {
      var x = key
      x ^= x >>> 33
      x *= 0xff51afd7ed558ccdL
      x ^= x >>> 33
      x *= 0xc4ceb9fe1a85ec53L
      x ^= x >>> 33
      x.toInt
    }
  }

  /** Fused long-key stream-window rank storage.
   *
   *  This experimental checked primitive removes the separate long-key rank
   *  queue plus owner table used by `StreamWindowLongIndexedRank`. One
   *  open-addressed table owns lookup, values, priorities, heap positions, and
   *  bucket-owner links. The binary heap stores table slots, so bucket close can
   *  unlink parent-visible state by slot before the child bucket closes.
   */
  final class StreamWindowTableRank[T <: Object] private[memory] (
      private[memory] val buckets: StreamBucketArena,
      private var keys: Array[Long],
      private var states: Array[Byte],
      private var items: Array[Object],
      private var priorities: Array[Long],
      private var priority2s: Array[Long],
      private var priority3s: Array[Long],
      private var priority4s: Array[Long],
      private var heapSlots: Array[Int],
      private var heapIndexPlusOneBySlot: Array[Int],
      private var bucketStartBySlot: Array[Long],
      private var nextOwnedSlotPlusOneBySlot: Array[Int],
      private var previousOwnedSlotPlusOneBySlot: Array[Int]
  ) {
    private final val Empty: Byte = 0
    private final val Used: Byte = 1
    private final val Deleted: Byte = 2

    private var heapUsed = 0
    private var tableActive = 0
    private var tableUsed = 0

    private var diagnosticsEnabled = false
    private var diagnosticLookups = 0L
    private var diagnosticProbes = 0L
    private var diagnosticInserts = 0L
    private var diagnosticReplacements = 0L
    private var diagnosticPriorityUpdates = 0L
    private var diagnosticHeapSiftSteps = 0L
    private var diagnosticHeapSwaps = 0L
    private var diagnosticBucketMoves = 0L
    private var diagnosticBucketCloseRemovals = 0L
    private var diagnosticRehashes = 0L
    private var diagnosticTopKCandidateCompares = 0L

    def length: Int = heapUsed

    def capacity: Int = heapSlots.length

    def tableCapacity: Int = keys.length

    private[memory] def setDiagnosticsEnabled(enabled: Boolean): Unit =
      diagnosticsEnabled = enabled

    private[memory] def resetDiagnostics(): Unit = {
      diagnosticLookups = 0L
      diagnosticProbes = 0L
      diagnosticInserts = 0L
      diagnosticReplacements = 0L
      diagnosticPriorityUpdates = 0L
      diagnosticHeapSiftSteps = 0L
      diagnosticHeapSwaps = 0L
      diagnosticBucketMoves = 0L
      diagnosticBucketCloseRemovals = 0L
      diagnosticRehashes = 0L
      diagnosticTopKCandidateCompares = 0L
    }

    private[memory] def diagnosticSummaryTrusted(): String =
      "lookups=" + diagnosticLookups +
        " probes=" + diagnosticProbes +
        " inserts=" + diagnosticInserts +
        " replacements=" + diagnosticReplacements +
        " priority_updates=" + diagnosticPriorityUpdates +
        " heap_sift_steps=" + diagnosticHeapSiftSteps +
        " heap_swaps=" + diagnosticHeapSwaps +
        " bucket_moves=" + diagnosticBucketMoves +
        " bucket_close_removals=" + diagnosticBucketCloseRemovals +
        " rehashes=" + diagnosticRehashes +
        " topk_candidate_compares=" + diagnosticTopKCandidateCompares +
        " table_active=" + tableActive +
        " table_used=" + tableUsed +
        " table_deleted=" + (tableUsed - tableActive) +
        " table_capacity=" + keys.length +
        " heap_used=" + heapUsed +
        " heap_capacity=" + heapSlots.length

    private[memory] def putTrusted(
        owner: RiftRegion^,
        bucket: StreamBucket,
        key: Long,
        value: Object,
        priority: Long
    ): Unit = {
      val existing = findExistingSlot(key)
      if (existing >= 0)
        replaceSlot(existing, bucket, value, priority)
      else {
        ensureInsertCapacity(owner)
        val slot = findInsertSlot(key)
        insertSlot(owner, slot, bucket, key, value)
        setPriority(slot, priority)
        addHeapSlot(owner, slot)
      }
    }

    private[memory] def putTrusted(
        owner: RiftRegion^,
        bucket: StreamBucket,
        key: Long,
        value: Object,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      checkLexicographicPriorities()
      val existing = findExistingSlot(key)
      if (existing >= 0)
        replaceSlot(existing, bucket, value, priority1, priority2, priority3, priority4)
      else {
        ensureInsertCapacity(owner)
        val slot = findInsertSlot(key)
        insertSlot(owner, slot, bucket, key, value)
        setPriorities(slot, priority1, priority2, priority3, priority4)
        addHeapSlot(owner, slot)
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Long,
        priority: Long
    ): Boolean = {
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        if (diagnosticsEnabled) diagnosticPriorityUpdates += 1L
        val oldPriority = priorities(slot)
        setPriority(slot, priority)
        fixAfterPriorityChange(
          heapIndexPlusOneBySlot(slot) - 1,
          priority > oldPriority,
          priority < oldPriority
        )
        true
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Long,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Boolean = {
      checkLexicographicPriorities()
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        if (diagnosticsEnabled) diagnosticPriorityUpdates += 1L
        val improves =
          priorityTupleBetter(
            priority1,
            priority2,
            priority3,
            priority4,
            priorities(slot),
            priority2s(slot),
            priority3s(slot),
            priority4s(slot)
          )
        val worsens =
          priorityTupleBetter(
            priorities(slot),
            priority2s(slot),
            priority3s(slot),
            priority4s(slot),
            priority1,
            priority2,
            priority3,
            priority4
          )
        setPriorities(slot, priority1, priority2, priority3, priority4)
        fixAfterPriorityChange(heapIndexPlusOneBySlot(slot) - 1, improves, worsens)
        true
      }
    }

    private[memory] def removeTrusted(key: Long): Boolean = {
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        removeSlot(slot)
        true
      }
    }

    private[memory] inline def removeWithValueTrusted(key: Long): Object = {
      val slot = findExistingSlot(key)
      if (slot < 0) null
      else {
        val result = items(slot)
        removeSlot(slot)
        result
      }
    }

    private[memory] def containsTrusted(key: Long): Boolean =
      findExistingSlot(key) >= 0

    private[memory] def getTrusted(key: Long): Object = {
      val slot = findExistingSlot(key)
      if (slot < 0)
        throw new NoSuchElementException(
          "Rift StreamWindowTableRank key is absent"
        )
      items(slot)
    }

    private[memory] def peekTrusted(): Object = {
      if (heapUsed == 0)
        throw new NoSuchElementException(
          "Rift StreamWindowTableRank is empty"
        )
      items(heapSlots(0))
    }

    private[memory] def peekKeyTrusted(): Long = {
      if (heapUsed == 0)
        throw new NoSuchElementException(
          "Rift StreamWindowTableRank is empty"
        )
      keys(heapSlots(0))
    }

    private[memory] def peekPriorityTrusted(): Long = {
      if (heapUsed == 0)
        throw new NoSuchElementException(
          "Rift StreamWindowTableRank is empty"
        )
      priorities(heapSlots(0))
    }

    private[memory] def popTrusted(): Object = {
      if (heapUsed == 0)
        throw new NoSuchElementException(
          "Rift StreamWindowTableRank is empty"
        )
      val slot = heapSlots(0)
      val result = items(slot)
      removeSlot(slot)
      result
    }

    private[memory] def copyTopKTrusted(
        result: Array[Object],
        candidateHeap: Array[Int],
        max: Int
    ): Int = {
      val limit = math.min(math.min(max, result.length), heapUsed)
      if (limit <= 0) 0
      else {
        if (candidateHeap.length < limit)
          throw new IllegalArgumentException(
            "Rift StreamWindowTableRank top-k candidate heap is too small"
          )

        var candidateCount = 1
        candidateHeap(0) = 0
        var i = 0
        while (i < limit) {
          val candidateSlot = bestCopyCandidate(candidateHeap, candidateCount)
          val heapPosition = candidateHeap(candidateSlot)
          candidateCount -= 1
          candidateHeap(candidateSlot) = candidateHeap(candidateCount)

          result(i) = items(heapSlots(heapPosition))

          if (i + 1 < limit) {
            val left = (heapPosition << 1) + 1
            if (left < heapUsed) {
              candidateHeap(candidateCount) = left
              candidateCount += 1
            }
            val right = left + 1
            if (right < heapUsed) {
              candidateHeap(candidateCount) = right
              candidateCount += 1
            }
          }
          i += 1
        }
        limit
      }
    }

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket
    ): Unit =
      removeOwnedKeysForBucket(bucket, null)

    private[memory] def removeOwnedKeysForBucket(
        bucket: StreamBucket,
        cleanup: (Long, Object) => Unit
    ): Unit = {
      var current = bucket.ownedTableRankSlotHeadPlusOne
      bucket.ownedTableRankSlotHeadPlusOne = 0
      while (current != 0) {
        val slot = current - 1
        val next = nextOwnedSlotPlusOneBySlot(slot)
        if (states(slot) == Used && bucketStartBySlot(slot) == bucket.startSeconds) {
          val key = keys(slot)
          val value = items(slot)
          if (diagnosticsEnabled) diagnosticBucketCloseRemovals += 1L
          removeBucketOwnedSlot(slot)
          if (value != null && cleanup != null) cleanup(key, value)
        } else {
          nextOwnedSlotPlusOneBySlot(slot) = 0
          previousOwnedSlotPlusOneBySlot(slot) = 0
        }
        current = next
      }
    }

    private def replaceSlot(
        slot: Int,
        bucket: StreamBucket,
        value: Object,
        priority: Long
    ): Unit = {
      if (diagnosticsEnabled) diagnosticReplacements += 1L
      items(slot) = value
      val oldPriority = priorities(slot)
      setPriority(slot, priority)
      moveSlotToBucket(slot, bucket)
      fixAfterPriorityChange(
        heapIndexPlusOneBySlot(slot) - 1,
        priority > oldPriority,
        priority < oldPriority
      )
    }

    private def replaceSlot(
        slot: Int,
        bucket: StreamBucket,
        value: Object,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      if (diagnosticsEnabled) diagnosticReplacements += 1L
      items(slot) = value
      val improves =
        priorityTupleBetter(
          priority1,
          priority2,
          priority3,
          priority4,
          priorities(slot),
          priority2s(slot),
          priority3s(slot),
          priority4s(slot)
        )
      val worsens =
        priorityTupleBetter(
          priorities(slot),
          priority2s(slot),
          priority3s(slot),
          priority4s(slot),
          priority1,
          priority2,
          priority3,
          priority4
        )
      setPriorities(slot, priority1, priority2, priority3, priority4)
      moveSlotToBucket(slot, bucket)
      fixAfterPriorityChange(heapIndexPlusOneBySlot(slot) - 1, improves, worsens)
    }

    private def insertSlot(
        owner: RiftRegion^,
        slot: Int,
        bucket: StreamBucket,
        key: Long,
        value: Object
    ): Unit = {
      if (diagnosticsEnabled) diagnosticInserts += 1L
      if (states(slot) == Empty) tableUsed += 1
      if (states(slot) != Used) tableActive += 1
      states(slot) = Used
      keys(slot) = key
      items(slot) = value
      bucketStartBySlot(slot) = bucket.startSeconds
      linkSlotToBucket(slot, bucket)
    }

    private def moveSlotToBucket(slot: Int, bucket: StreamBucket): Unit =
      if (bucketStartBySlot(slot) != bucket.startSeconds) {
        if (diagnosticsEnabled) diagnosticBucketMoves += 1L
        unlinkSlotFromBucket(slot)
        bucketStartBySlot(slot) = bucket.startSeconds
        linkSlotToBucket(slot, bucket)
      }

    private def removeSlot(slot: Int): Unit = {
      val heapIndex = heapIndexPlusOneBySlot(slot) - 1
      if (heapIndex >= 0) removeHeapAt(heapIndex)
      unlinkSlotFromBucket(slot)
      states(slot) = Deleted
      items(slot) = null
      clearPriorities(slot)
      bucketStartBySlot(slot) = 0L
      nextOwnedSlotPlusOneBySlot(slot) = 0
      previousOwnedSlotPlusOneBySlot(slot) = 0
      tableActive -= 1
    }

    private def removeBucketOwnedSlot(slot: Int): Unit = {
      val heapIndex = heapIndexPlusOneBySlot(slot) - 1
      if (heapIndex >= 0) removeHeapAt(heapIndex)
      states(slot) = Deleted
      items(slot) = null
      clearPriorities(slot)
      bucketStartBySlot(slot) = 0L
      nextOwnedSlotPlusOneBySlot(slot) = 0
      previousOwnedSlotPlusOneBySlot(slot) = 0
      tableActive -= 1
    }

    private def ensureInsertCapacity(owner: RiftRegion^): Unit = {
      val deleted = tableUsed - tableActive
      if (deleted * 4 > keys.length)
        rehashTable(owner, keys.length)
      else if ((tableUsed + 1) * 4 >= keys.length * 3)
        rehashTable(owner, keys.length << 1)
    }

    private def addHeapSlot(owner: RiftRegion^, slot: Int): Unit = {
      if (heapUsed >= heapSlots.length) growHeap(owner)
      val index = heapUsed
      heapUsed += 1
      heapSlots(index) = slot
      heapIndexPlusOneBySlot(slot) = index + 1
      siftUp(index)
    }

    private def growHeap(owner: RiftRegion^): Unit = {
      val oldHeapSlots = heapSlots
      val nextCapacity =
        if (oldHeapSlots.length == 0) 1 else oldHeapSlots.length * 2
      val nextHeapSlots = owner.alloc(new Array[Int](nextCapacity))
      var i = 0
      while (i < heapUsed) {
        nextHeapSlots(i) = oldHeapSlots(i)
        i += 1
      }
      heapSlots = nextHeapSlots
    }

    private def rehashTable(owner: RiftRegion^, requestedCapacity: Int): Unit = {
      if (diagnosticsEnabled) diagnosticRehashes += 1L
      val oldKeys = keys
      val oldStates = states
      val oldItems = items
      val oldPriorities = priorities
      val oldPriority2s = priority2s
      val oldPriority3s = priority3s
      val oldPriority4s = priority4s
      val oldBucketStartBySlot = bucketStartBySlot

      keys = owner.alloc(new Array[Long](requestedCapacity))
      states = owner.alloc(new Array[Byte](requestedCapacity))
      items = owner.alloc(new Array[Object](requestedCapacity)).asInstanceOf[Array[Object]]
      priorities = owner.alloc(new Array[Long](requestedCapacity))
      priority2s =
        if (oldPriority2s == null) null
        else owner.alloc(new Array[Long](requestedCapacity))
      priority3s =
        if (oldPriority3s == null) null
        else owner.alloc(new Array[Long](requestedCapacity))
      priority4s =
        if (oldPriority4s == null) null
        else owner.alloc(new Array[Long](requestedCapacity))
      heapIndexPlusOneBySlot = owner.alloc(new Array[Int](requestedCapacity))
      bucketStartBySlot = owner.alloc(new Array[Long](requestedCapacity))
      nextOwnedSlotPlusOneBySlot = owner.alloc(new Array[Int](requestedCapacity))
      previousOwnedSlotPlusOneBySlot =
        owner.alloc(new Array[Int](requestedCapacity))

      tableActive = 0
      tableUsed = 0
      heapUsed = 0

      var bucket = buckets.first
      while (bucket != null) {
        bucket.ownedTableRankSlotHeadPlusOne = 0
        bucket = bucket.next
      }

      var i = 0
      while (i < oldKeys.length) {
        if (oldStates(i) == Used) {
          val slot = findInsertSlot(oldKeys(i))
          if (states(slot) == Empty) tableUsed += 1
          tableActive += 1
          states(slot) = Used
          keys(slot) = oldKeys(i)
          items(slot) = oldItems(i)
          priorities(slot) = oldPriorities(i)
          if (priority2s != null) priority2s(slot) = oldPriority2s(i)
          if (priority3s != null) priority3s(slot) = oldPriority3s(i)
          if (priority4s != null) priority4s(slot) = oldPriority4s(i)
          bucketStartBySlot(slot) = oldBucketStartBySlot(i)
          val ownerBucket = findBucket(bucketStartBySlot(slot))
          if (ownerBucket != null) linkSlotToBucket(slot, ownerBucket)
          heapSlots(heapUsed) = slot
          heapIndexPlusOneBySlot(slot) = heapUsed + 1
          heapUsed += 1
        }
        i += 1
      }
      heapify()
    }

    private def heapify(): Unit = {
      var index = (heapUsed >>> 1) - 1
      while (index >= 0) {
        siftDown(index)
        index -= 1
      }
    }

    private def removeHeapAt(index: Int): Unit = {
      val removedSlot = heapSlots(index)
      heapIndexPlusOneBySlot(removedSlot) = 0
      val last = heapUsed - 1
      heapUsed = last
      if (index != last) {
        val movedSlot = heapSlots(last)
        heapSlots(index) = movedSlot
        heapIndexPlusOneBySlot(movedSlot) = index + 1
        heapSlots(last) = 0
        fixMovedHeapAt(index)
      } else {
        heapSlots(index) = 0
      }
    }

    private def fixMovedHeapAt(index: Int): Unit =
      if (index >= 0 && index < heapUsed) {
        val parent = (index - 1) >>> 1
        if (index > 0 && betterHeap(index, parent)) siftUp(index)
        else siftDown(index)
      }

    private def fixAt(index: Int): Unit =
      if (index >= 0 && index < heapUsed) {
        val afterUp = siftUp(index)
        siftDown(afterUp)
      }

    private def fixAfterPriorityChange(
        index: Int,
        improves: Boolean,
        worsens: Boolean
    ): Unit =
      if (index >= 0 && index < heapUsed) {
        if (improves) siftUp(index)
        else if (worsens) siftDown(index)
      }

    private def siftUp(start: Int): Int = {
      var child = start
      while (child > 0) {
        if (diagnosticsEnabled) diagnosticHeapSiftSteps += 1L
        val parent = (child - 1) >>> 1
        if (!betterHeap(child, parent)) return child
        swapHeap(parent, child)
        child = parent
      }
      child
    }

    private def siftDown(start: Int): Int = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= heapUsed) return parent
        if (diagnosticsEnabled) diagnosticHeapSiftSteps += 1L
        val right = left + 1
        var best = left
        if (right < heapUsed && betterHeap(right, left))
          best = right
        if (!betterHeap(best, parent)) return parent
        swapHeap(parent, best)
        parent = best
      }
      parent
    }

    private def betterHeap(leftIndex: Int, rightIndex: Int): Boolean =
      betterSlot(heapSlots(leftIndex), heapSlots(rightIndex))

    private def bestCopyCandidate(
        candidateHeap: Array[Int],
        candidateCount: Int
    ): Int = {
      var best = 0
      var i = 1
      while (i < candidateCount) {
        if (diagnosticsEnabled) diagnosticTopKCandidateCompares += 1L
        if (betterHeap(candidateHeap(i), candidateHeap(best)))
          best = i
        i += 1
      }
      best
    }

    private def betterSlot(left: Int, right: Int): Boolean =
      if (priorities(left) != priorities(right))
        priorities(left) > priorities(right)
      else if (priority2s == null) false
      else if (priority2s(left) != priority2s(right))
        priority2s(left) > priority2s(right)
      else if (priority3s(left) != priority3s(right))
        priority3s(left) > priority3s(right)
      else if (priority4s(left) != priority4s(right))
        priority4s(left) > priority4s(right)
      else false

    private def priorityTupleBetter(
        left1: Long,
        left2: Long,
        left3: Long,
        left4: Long,
        right1: Long,
        right2: Long,
        right3: Long,
        right4: Long
    ): Boolean =
      if (left1 != right1) left1 > right1
      else if (left2 != right2) left2 > right2
      else if (left3 != right3) left3 > right3
      else if (left4 != right4) left4 > right4
      else false

    private def swapHeap(left: Int, right: Int): Unit = {
      if (diagnosticsEnabled) diagnosticHeapSwaps += 1L
      val leftSlot = heapSlots(left)
      heapSlots(left) = heapSlots(right)
      heapIndexPlusOneBySlot(heapSlots(left)) = left + 1
      heapSlots(right) = leftSlot
      heapIndexPlusOneBySlot(leftSlot) = right + 1
    }

    private def linkSlotToBucket(slot: Int, bucket: StreamBucket): Unit = {
      val slotPlusOne = slot + 1
      val oldHead = bucket.ownedTableRankSlotHeadPlusOne
      nextOwnedSlotPlusOneBySlot(slot) = oldHead
      previousOwnedSlotPlusOneBySlot(slot) = 0
      if (oldHead != 0)
        previousOwnedSlotPlusOneBySlot(oldHead - 1) = slotPlusOne
      bucket.ownedTableRankSlotHeadPlusOne = slotPlusOne
    }

    private def unlinkSlotFromBucket(slot: Int): Unit = {
      val bucket = findBucket(bucketStartBySlot(slot))
      if (bucket != null) {
        val previous = previousOwnedSlotPlusOneBySlot(slot)
        val next = nextOwnedSlotPlusOneBySlot(slot)
        if (previous == 0) bucket.ownedTableRankSlotHeadPlusOne = next
        else nextOwnedSlotPlusOneBySlot(previous - 1) = next
        if (next != 0) previousOwnedSlotPlusOneBySlot(next - 1) = previous
      }
      nextOwnedSlotPlusOneBySlot(slot) = 0
      previousOwnedSlotPlusOneBySlot(slot) = 0
    }

    private def findBucket(startSeconds: Long): StreamBucket = {
      var bucket = buckets.first
      while (bucket != null && bucket.startSeconds != startSeconds)
        bucket = bucket.next
      bucket
    }

    private def findExistingSlot(key: Long): Int = {
      if (diagnosticsEnabled) diagnosticLookups += 1L
      val mask = keys.length - 1
      var slot = hashKey(key) & mask
      var probes = 0
      while (probes < keys.length) {
        if (diagnosticsEnabled) diagnosticProbes += 1L
        val state = states(slot)
        if (state == Empty) return -1
        if (state == Used && keys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      -1
    }

    private def findInsertSlot(key: Long): Int = {
      if (diagnosticsEnabled) diagnosticLookups += 1L
      val mask = keys.length - 1
      var slot = hashKey(key) & mask
      var firstDeleted = -1
      var probes = 0
      while (probes < keys.length) {
        if (diagnosticsEnabled) diagnosticProbes += 1L
        val state = states(slot)
        if (state == Empty)
          return if (firstDeleted >= 0) firstDeleted else slot
        if (state == Deleted && firstDeleted < 0) firstDeleted = slot
        if (state == Used && keys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      if (firstDeleted >= 0) firstDeleted
      else throw new IllegalStateException("Rift table rank is full")
    }

    private def hashKey(key: Long): Int = {
      var x = key
      x ^= x >>> 33
      x *= 0xff51afd7ed558ccdL
      x ^= x >>> 33
      x *= 0xc4ceb9fe1a85ec53L
      x ^= x >>> 33
      x.toInt
    }

    private def checkLexicographicPriorities(): Unit =
      if (priority2s == null || priority3s == null || priority4s == null)
        throw new IllegalStateException(
          "Rift StreamWindowTableRank was not allocated for lexicographic priorities"
        )

    private def setPriority(slot: Int, priority: Long): Unit = {
      priorities(slot) = priority
      if (priority2s != null) priority2s(slot) = 0L
      if (priority3s != null) priority3s(slot) = 0L
      if (priority4s != null) priority4s(slot) = 0L
    }

    private def setPriorities(
        slot: Int,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      priorities(slot) = priority1
      priority2s(slot) = priority2
      priority3s(slot) = priority3
      priority4s(slot) = priority4
    }

    private def clearPriorities(slot: Int): Unit = {
      priorities(slot) = 0L
      if (priority2s != null) priority2s(slot) = 0L
      if (priority3s != null) priority3s(slot) = 0L
      if (priority4s != null) priority4s(slot) = 0L
    }
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
      private var priority2s: Array[Long],
      private var priority3s: Array[Long],
      private var priority4s: Array[Long],
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
        setPriority(index, priority)
        fixAt(index)
      } else {
        if (used >= items.length) growTrusted(owner)
        val index = used
        used += 1
        items(index) = value
        setPriority(index, priority)
        keys(index) = key
        heapIndexByKey(key) = index + 1
        siftUp(index)
      }
    }

    private[memory] def putTrusted(
        owner: RiftRegion^,
        key: Int,
        value: Object,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      checkKey(key)
      checkLexicographicPriorities()
      val slot = heapIndexByKey(key)
      if (slot != 0) {
        val index = slot - 1
        items(index) = value
        setPriorities(index, priority1, priority2, priority3, priority4)
        fixAt(index)
      } else {
        if (used >= items.length) growTrusted(owner)
        val index = used
        used += 1
        items(index) = value
        setPriorities(index, priority1, priority2, priority3, priority4)
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
        setPriority(index, priority)
        fixAt(index)
        true
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Int,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Boolean = {
      checkKey(key)
      checkLexicographicPriorities()
      val slot = heapIndexByKey(key)
      if (slot == 0) false
      else {
        val index = slot - 1
        setPriorities(index, priority1, priority2, priority3, priority4)
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

    private[memory] inline def removeWithValueTrusted(key: Int): Object = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0) null
      else {
        val index = slot - 1
        val result = items(index)
        removeAt(index)
        result
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
      val oldPriority2s = priority2s
      val oldPriority3s = priority3s
      val oldPriority4s = priority4s
      val oldKeys = keys
      val nextCapacity =
        if (oldItems.length == 0) 1 else oldItems.length * 2
      val nextItems =
        owner.alloc(new Array[Object](nextCapacity)).asInstanceOf[Array[Object]]
      val nextPriorities = owner.alloc(new Array[Long](nextCapacity))
      val nextPriority2s =
        if (oldPriority2s == null) null else owner.alloc(new Array[Long](nextCapacity))
      val nextPriority3s =
        if (oldPriority3s == null) null else owner.alloc(new Array[Long](nextCapacity))
      val nextPriority4s =
        if (oldPriority4s == null) null else owner.alloc(new Array[Long](nextCapacity))
      val nextKeys = owner.alloc(new Array[Int](nextCapacity))

      var i = 0
      while (i < used) {
        nextItems(i) = oldItems(i)
        nextPriorities(i) = oldPriorities(i)
        if (nextPriority2s != null) nextPriority2s(i) = oldPriority2s(i)
        if (nextPriority3s != null) nextPriority3s(i) = oldPriority3s(i)
        if (nextPriority4s != null) nextPriority4s(i) = oldPriority4s(i)
        nextKeys(i) = oldKeys(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
      priority2s = nextPriority2s
      priority3s = nextPriority3s
      priority4s = nextPriority4s
      keys = nextKeys
    }

    private def removeAt(index: Int): Unit = {
      val removedKey = keys(index)
      heapIndexByKey(removedKey) = 0
      val last = used - 1
      used = last
      if (index != last) {
        items(index) = items(last)
        copyPriorities(index, last)
        keys(index) = keys(last)
        heapIndexByKey(keys(index)) = index + 1
        items(last) = null
        clearPriorities(last)
        keys(last) = 0
        fixAt(index)
      } else {
        items(index) = null
        clearPriorities(index)
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
        if (!better(child, parent)) return
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
        if (right < used && better(right, left))
          best = right
        if (!better(best, parent)) return
        swap(parent, best)
        parent = best
      }
    }

    private def better(left: Int, right: Int): Boolean =
      if (priorities(left) != priorities(right))
        priorities(left) > priorities(right)
      else if (priority2s == null) false
      else if (priority2s(left) != priority2s(right))
        priority2s(left) > priority2s(right)
      else if (priority3s(left) != priority3s(right))
        priority3s(left) > priority3s(right)
      else if (priority4s(left) != priority4s(right))
        priority4s(left) > priority4s(right)
      else false

    private def swap(left: Int, right: Int): Unit = {
      val leftItem = items(left)
      val leftPriority = priorities(left)
      val leftPriority2 = if (priority2s == null) 0L else priority2s(left)
      val leftPriority3 = if (priority3s == null) 0L else priority3s(left)
      val leftPriority4 = if (priority4s == null) 0L else priority4s(left)
      val leftKey = keys(left)
      items(left) = items(right)
      copyPriorities(left, right)
      keys(left) = keys(right)
      heapIndexByKey(keys(left)) = left + 1
      items(right) = leftItem
      priorities(right) = leftPriority
      if (priority2s != null) priority2s(right) = leftPriority2
      if (priority3s != null) priority3s(right) = leftPriority3
      if (priority4s != null) priority4s(right) = leftPriority4
      keys(right) = leftKey
      heapIndexByKey(keys(right)) = right + 1
    }

    private def checkLexicographicPriorities(): Unit =
      if (priority2s == null || priority3s == null || priority4s == null)
        throw new IllegalStateException(
          "Rift RegionIndexedPriorityQueue was not allocated for lexicographic priorities"
        )

    private def setPriority(index: Int, priority: Long): Unit = {
      priorities(index) = priority
      if (priority2s != null) priority2s(index) = 0L
      if (priority3s != null) priority3s(index) = 0L
      if (priority4s != null) priority4s(index) = 0L
    }

    private def setPriorities(
        index: Int,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      priorities(index) = priority1
      priority2s(index) = priority2
      priority3s(index) = priority3
      priority4s(index) = priority4
    }

    private def copyPriorities(to: Int, from: Int): Unit = {
      priorities(to) = priorities(from)
      if (priority2s != null) priority2s(to) = priority2s(from)
      if (priority3s != null) priority3s(to) = priority3s(from)
      if (priority4s != null) priority4s(to) = priority4s(from)
    }

    private def clearPriorities(index: Int): Unit = {
      priorities(index) = 0L
      if (priority2s != null) priority2s(index) = 0L
      if (priority3s != null) priority3s(index) = 0L
      if (priority4s != null) priority4s(index) = 0L
    }
  }

  /** Long-key indexed max-priority queue backed by region-owned arrays.
   *
   *  This is the hash-keyed counterpart to `RegionIndexedPriorityQueue`: it
   *  keeps the ranked values in a binary heap and maps arbitrary `Long` keys to
   *  heap positions through an open-addressed region-owned table.
   */
  final class RegionLongIndexedPriorityQueue[T <: Object] private[memory] (
      private var items: Array[Object],
      private var priorities: Array[Long],
      private var priority2s: Array[Long],
      private var priority3s: Array[Long],
      private var priority4s: Array[Long],
      private var heapKeys: Array[Long],
      private var tableKeys: Array[Long],
      private var tableStates: Array[Byte],
      private var heapIndexPlusOneBySlot: Array[Int]
  ) {
    private final val Empty: Byte = 0
    private final val Used: Byte = 1
    private final val Deleted: Byte = 2

    private var used = 0
    private var tableActive = 0
    private var tableUsed = 0

    def length: Int = used

    def capacity: Int = items.length

    def tableCapacity: Int = tableKeys.length

    private[memory] def putTrusted(
        owner: RiftRegion^,
        key: Long,
        value: Object,
        priority: Long
    ): Unit = {
      val slot = findExistingSlot(key)
      if (slot >= 0) {
        val index = heapIndexPlusOneBySlot(slot) - 1
        items(index) = value
        setPriority(index, priority)
        fixAt(index)
      } else {
        ensureTableCapacity(owner)
        val insertSlot = findInsertSlot(key)
        insertTableSlot(insertSlot, key)
        if (used >= items.length) growHeapTrusted(owner)
        val index = used
        used += 1
        items(index) = value
        setPriority(index, priority)
        heapKeys(index) = key
        heapIndexPlusOneBySlot(insertSlot) = index + 1
        siftUp(index)
      }
    }

    private[memory] def putTrusted(
        owner: RiftRegion^,
        key: Long,
        value: Object,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      checkLexicographicPriorities()
      val slot = findExistingSlot(key)
      if (slot >= 0) {
        val index = heapIndexPlusOneBySlot(slot) - 1
        items(index) = value
        setPriorities(index, priority1, priority2, priority3, priority4)
        fixAt(index)
      } else {
        ensureTableCapacity(owner)
        val insertSlot = findInsertSlot(key)
        insertTableSlot(insertSlot, key)
        if (used >= items.length) growHeapTrusted(owner)
        val index = used
        used += 1
        items(index) = value
        setPriorities(index, priority1, priority2, priority3, priority4)
        heapKeys(index) = key
        heapIndexPlusOneBySlot(insertSlot) = index + 1
        siftUp(index)
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Long,
        priority: Long
    ): Boolean = {
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        val index = heapIndexPlusOneBySlot(slot) - 1
        setPriority(index, priority)
        fixAt(index)
        true
      }
    }

    private[memory] def updatePriorityTrusted(
        key: Long,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Boolean = {
      checkLexicographicPriorities()
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        val index = heapIndexPlusOneBySlot(slot) - 1
        setPriorities(index, priority1, priority2, priority3, priority4)
        fixAt(index)
        true
      }
    }

    private[memory] def removeTrusted(key: Long): Boolean = {
      val slot = findExistingSlot(key)
      if (slot < 0) false
      else {
        removeAt(heapIndexPlusOneBySlot(slot) - 1)
        true
      }
    }

    private[memory] inline def removeWithValueTrusted(key: Long): Object = {
      val slot = findExistingSlot(key)
      if (slot < 0) null
      else {
        val index = heapIndexPlusOneBySlot(slot) - 1
        val result = items(index)
        removeAt(index)
        result
      }
    }

    private[memory] def containsTrusted(key: Long): Boolean =
      findExistingSlot(key) >= 0

    private[memory] def getTrusted(key: Long): Object = {
      val slot = findExistingSlot(key)
      if (slot < 0)
        throw new NoSuchElementException(
          "Rift RegionLongIndexedPriorityQueue key is absent"
        )
      items(heapIndexPlusOneBySlot(slot) - 1)
    }

    private[memory] def peekTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionLongIndexedPriorityQueue is empty"
        )
      items(0)
    }

    private[memory] def peekKeyTrusted(): Long = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionLongIndexedPriorityQueue is empty"
        )
      heapKeys(0)
    }

    private[memory] def peekPriorityTrusted(): Long = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionLongIndexedPriorityQueue is empty"
        )
      priorities(0)
    }

    private[memory] def popTrusted(): Object = {
      if (used == 0)
        throw new NoSuchElementException(
          "Rift RegionLongIndexedPriorityQueue is empty"
        )
      val result = items(0)
      removeAt(0)
      result
    }

    private def ensureTableCapacity(owner: RiftRegion^): Unit =
      if ((tableUsed + 1) * 4 >= tableKeys.length * 3) {
        val compactOnly = tableActive * 2 < tableUsed
        val nextCapacity =
          if (compactOnly) tableKeys.length else tableKeys.length << 1
        rehashTableTrusted(owner, nextCapacity)
      }

    private def growHeapTrusted(owner: RiftRegion^): Unit = {
      val oldItems = items
      val oldPriorities = priorities
      val oldPriority2s = priority2s
      val oldPriority3s = priority3s
      val oldPriority4s = priority4s
      val oldHeapKeys = heapKeys
      val nextCapacity =
        if (oldItems.length == 0) 1 else oldItems.length * 2
      val nextItems =
        owner.alloc(new Array[Object](nextCapacity)).asInstanceOf[Array[Object]]
      val nextPriorities = owner.alloc(new Array[Long](nextCapacity))
      val nextPriority2s =
        if (oldPriority2s == null) null
        else owner.alloc(new Array[Long](nextCapacity))
      val nextPriority3s =
        if (oldPriority3s == null) null
        else owner.alloc(new Array[Long](nextCapacity))
      val nextPriority4s =
        if (oldPriority4s == null) null
        else owner.alloc(new Array[Long](nextCapacity))
      val nextHeapKeys = owner.alloc(new Array[Long](nextCapacity))

      var i = 0
      while (i < used) {
        nextItems(i) = oldItems(i)
        nextPriorities(i) = oldPriorities(i)
        if (nextPriority2s != null) nextPriority2s(i) = oldPriority2s(i)
        if (nextPriority3s != null) nextPriority3s(i) = oldPriority3s(i)
        if (nextPriority4s != null) nextPriority4s(i) = oldPriority4s(i)
        nextHeapKeys(i) = oldHeapKeys(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
      priority2s = nextPriority2s
      priority3s = nextPriority3s
      priority4s = nextPriority4s
      heapKeys = nextHeapKeys
    }

    private def rehashTableTrusted(
        owner: RiftRegion^,
        nextCapacity: Int
    ): Unit = {
      val nextKeys = owner.alloc(new Array[Long](nextCapacity))
      val nextStates = owner.alloc(new Array[Byte](nextCapacity))
      val nextIndexes = owner.alloc(new Array[Int](nextCapacity))
      tableKeys = nextKeys
      tableStates = nextStates
      heapIndexPlusOneBySlot = nextIndexes
      tableActive = 0
      tableUsed = 0

      var index = 0
      while (index < used) {
        val slot = findInsertSlot(heapKeys(index))
        insertTableSlot(slot, heapKeys(index))
        heapIndexPlusOneBySlot(slot) = index + 1
        index += 1
      }
    }

    private def removeAt(index: Int): Unit = {
      val removedKey = heapKeys(index)
      removeTableKey(removedKey)
      val last = used - 1
      used = last
      if (index != last) {
        items(index) = items(last)
        copyPriorities(index, last)
        heapKeys(index) = heapKeys(last)
        updateTableHeapIndex(heapKeys(index), index)
        items(last) = null
        clearPriorities(last)
        heapKeys(last) = 0L
        fixAt(index)
      } else {
        items(index) = null
        clearPriorities(index)
        heapKeys(index) = 0L
      }
    }

    private def fixAt(index: Int): Unit = {
      val beforeKey = heapKeys(index)
      siftUp(index)
      val slot = findExistingSlot(beforeKey)
      if (slot >= 0) siftDown(heapIndexPlusOneBySlot(slot) - 1)
    }

    private def siftUp(start: Int): Unit = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (!better(child, parent)) return
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
        if (right < used && better(right, left))
          best = right
        if (!better(best, parent)) return
        swap(parent, best)
        parent = best
      }
    }

    private def better(left: Int, right: Int): Boolean =
      if (priorities(left) != priorities(right))
        priorities(left) > priorities(right)
      else if (priority2s == null) false
      else if (priority2s(left) != priority2s(right))
        priority2s(left) > priority2s(right)
      else if (priority3s(left) != priority3s(right))
        priority3s(left) > priority3s(right)
      else if (priority4s(left) != priority4s(right))
        priority4s(left) > priority4s(right)
      else false

    private def swap(left: Int, right: Int): Unit = {
      val leftItem = items(left)
      val leftPriority = priorities(left)
      val leftPriority2 = if (priority2s == null) 0L else priority2s(left)
      val leftPriority3 = if (priority3s == null) 0L else priority3s(left)
      val leftPriority4 = if (priority4s == null) 0L else priority4s(left)
      val leftKey = heapKeys(left)
      items(left) = items(right)
      copyPriorities(left, right)
      heapKeys(left) = heapKeys(right)
      updateTableHeapIndex(heapKeys(left), left)
      items(right) = leftItem
      priorities(right) = leftPriority
      if (priority2s != null) priority2s(right) = leftPriority2
      if (priority3s != null) priority3s(right) = leftPriority3
      if (priority4s != null) priority4s(right) = leftPriority4
      heapKeys(right) = leftKey
      updateTableHeapIndex(heapKeys(right), right)
    }

    private def findExistingSlot(key: Long): Int = {
      val mask = tableKeys.length - 1
      var slot = hashKey(key) & mask
      var probes = 0
      while (probes < tableKeys.length) {
        val state = tableStates(slot)
        if (state == Empty) return -1
        if (state == Used && tableKeys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      -1
    }

    private def findInsertSlot(key: Long): Int = {
      val mask = tableKeys.length - 1
      var slot = hashKey(key) & mask
      var firstDeleted = -1
      var probes = 0
      while (probes < tableKeys.length) {
        val state = tableStates(slot)
        if (state == Empty)
          return if (firstDeleted >= 0) firstDeleted else slot
        if (state == Deleted && firstDeleted < 0) firstDeleted = slot
        if (state == Used && tableKeys(slot) == key) return slot
        slot = (slot + 1) & mask
        probes += 1
      }
      if (firstDeleted >= 0) firstDeleted
      else throw new IllegalStateException("Rift long indexed table is full")
    }

    private def insertTableSlot(slot: Int, key: Long): Unit = {
      if (tableStates(slot) == Empty) tableUsed += 1
      if (tableStates(slot) != Used) tableActive += 1
      tableStates(slot) = Used
      tableKeys(slot) = key
    }

    private def removeTableKey(key: Long): Unit = {
      val slot = findExistingSlot(key)
      if (slot >= 0) {
        tableStates(slot) = Deleted
        heapIndexPlusOneBySlot(slot) = 0
        tableActive -= 1
      }
    }

    private def updateTableHeapIndex(key: Long, index: Int): Unit = {
      val slot = findExistingSlot(key)
      if (slot >= 0) heapIndexPlusOneBySlot(slot) = index + 1
    }

    private def hashKey(key: Long): Int = {
      var x = key
      x ^= x >>> 33
      x *= 0xff51afd7ed558ccdL
      x ^= x >>> 33
      x *= 0xc4ceb9fe1a85ec53L
      x ^= x >>> 33
      x.toInt
    }

    private def checkLexicographicPriorities(): Unit =
      if (priority2s == null || priority3s == null || priority4s == null)
        throw new IllegalStateException(
          "Rift RegionLongIndexedPriorityQueue was not allocated for lexicographic priorities"
        )

    private def setPriority(index: Int, priority: Long): Unit = {
      priorities(index) = priority
      if (priority2s != null) priority2s(index) = 0L
      if (priority3s != null) priority3s(index) = 0L
      if (priority4s != null) priority4s(index) = 0L
    }

    private def setPriorities(
        index: Int,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit = {
      priorities(index) = priority1
      priority2s(index) = priority2
      priority3s(index) = priority3
      priority4s(index) = priority4
    }

    private def copyPriorities(to: Int, from: Int): Unit = {
      priorities(to) = priorities(from)
      if (priority2s != null) priority2s(to) = priority2s(from)
      if (priority3s != null) priority3s(to) = priority3s(from)
      if (priority4s != null) priority4s(to) = priority4s(from)
    }

    private def clearPriorities(index: Int): Unit = {
      priorities(index) = 0L
      if (priority2s != null) priority2s(index) = 0L
      if (priority3s != null) priority3s(index) = 0L
      if (priority4s != null) priority4s(index) = 0L
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

  /** Runs `body` with a checked streaming region backed by SafeZone internals.
   *
   *  This is an experimental benchmark-only backend probe. It preserves the
   *  checked Rift source-level API while delegating object allocation and close
   *  to SafeZone. Raw byte allocation and reset are intentionally unsupported
   *  in this v1 backend.
   */
  final def streamingSafeZone[T](body: (StreamingRegion^) ?=> T)(using
      canReturn: CanReturnFromRegion[T]
  ): T = {
    val region: StreamingRegion^ =
      openSafeZoneImpl(Streaming).asInstanceOf[StreamingRegion]
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

  /** Runs one checked stream epoch with an operator-owned open region.
   *
   *  This is the direct API for batch/epoch lifetimes. It is intentionally
   *  simpler than `EpochBuffer`: callers can build the epoch-local object
   *  graph directly, consume it before the block returns, and then the runtime
   *  closes/resets the epoch in bulk. The result type may not retain epoch
   *  values.
   */
  final def epoch[T](
      body: (OpenStreamingRegion^) ?=> T
  )(using parent: StreamingRegion^, canReturn: CanReturnFromRegion[T]): T =
    parent match {
      case _: SafeZoneBackedRiftRegion =>
        val child: OpenStreamingRegion^ =
          openSafeZoneImpl(Streaming).asInstanceOf[OpenStreamingRegion]
        try body(using child)
        finally child.close()
      case _ =>
        reset { region ?=>
          body(using region.asInstanceOf[OpenStreamingRegion])
        }
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
    parent match {
      case _: SafeZoneBackedRiftRegion =>
        openSafeZoneImpl(Streaming).asInstanceOf[StreamingRegion]
      case _ =>
        openImpl(Streaming).asInstanceOf[StreamingRegion]
    }

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

  /** Opens a parent-captured append-window primitive. */
  def streamAppendWindow[T <: StreamAppendNode](bucketSeconds: Long)(using
      parent: StreamingRegion^
  ): StreamAppendWindow[T]^{parent} =
    new StreamAppendWindow[T](
      streamBucketArena(bucketSeconds).asInstanceOf[StreamBucketArena]
    ).asInstanceOf[StreamAppendWindow[T]^{parent}]

  /** Opens a checked page/token append window.
   *
   *  Call `pageTokenAppendRegionFor` when entering a page/bucket, allocate
   *  records in the returned child region, then call `appendPageToken`.
   */
  def streamPageTokenAppendWindow[T <: StreamAppendNode](
      bucketSeconds: Long
  )(using parent: StreamingRegion^): StreamPageTokenAppendWindow[T]^{parent} =
    new StreamPageTokenAppendWindow[T](
      streamAppendWindow[T](bucketSeconds)
        .asInstanceOf[StreamAppendWindow[T]]
    ).asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}]

  /** Allocates in an operator-owned open stream region.
   *
   *  This is intentionally narrower than `RiftRegion.alloc`: callers only get
   *  an `OpenStreamingRegion` from operator-owned helpers such as
   *  `pageTokenAppendOpenRegionFor`, and the low-level allocation path skips
   *  the hot `checkOpen` call. Use ordinary `alloc` for generic or user-held
   *  region handles.
   */
  inline def allocOpen[T <: AnyRef](inline obj: T)(using
      region: OpenStreamingRegion^
  ): T^{region} =
    RiftAllocator.allocateOpen(region, obj)

  final def epochOpenHandle[T](
      body: (RiftOpenStreamingHandle^) ?=> T
  )(using canReturn: CanReturnFromRegion[T]): T = {
    val raw = RiftAllocator.Impl.open(Streaming)
    val region: RiftOpenStreamingHandle^ = new RiftOpenStreamingHandle(raw)
    if (region.handle == null)
      throw new IllegalStateException("Rift open handle is null")
    try body(using region)
    finally RiftAllocator.Impl.close(raw)
  }

  final def streamingOpenHandle[T](
      body: (RiftOpenStreamingHandle^) ?=> T
  )(using canReturn: CanReturnFromRegion[T]): T =
    epochOpenHandle(body)

  final def resetOpenHandle[T](
      body: (RiftOpenStreamingHandle^) ?=> T
  )(using region: RiftOpenStreamingHandle^, canReturn: CanReturnFromRegion[T]): T =
    try body(using region)
    finally RiftAllocator.Impl.reset(region.handle)

  /** Opens a checked page-token map/filter operator.
   *
   *  This is the reusable operator-owned API for SELECT/filter/project-style
   *  stream rows. It keeps the low-level page-token append window available as
   *  a control, but new application code should prefer this named operator when
   *  the lifetime shape is "records in a page/window, outputs drained at close".
   */
  def pageTokenMapFilter[T <: StreamAppendNode](
      bucketSeconds: Long
  )(using parent: StreamingRegion^): PageTokenMapFilter[T]^{parent} =
    new PageTokenMapFilter[T](
      streamPageTokenAppendWindow[T](bucketSeconds)
        .asInstanceOf[StreamPageTokenAppendWindow[T]]
    ).asInstanceOf[PageTokenMapFilter[T]^{parent}]

  /** Opens a checked page-token count/sum-by-key operator.
   *
   *  This is the reusable no-drain aggregate API for window-count and
   *  window-sum rows. Ordinary records still live in child bucket regions, but
   *  per-key aggregate metadata is updated during append and stored in
   *  parent-owned primitive arrays.
   */
  def pageTokenCountByKey[T <: StreamAppendNode](
      bucketSeconds: Long,
      keySpace: Int,
      liveBuckets: Int
  )(using parent: StreamingRegion^): PageTokenCountByKey[T]^{parent} = {
    if (keySpace <= 0)
      throw new IllegalArgumentException("keySpace must be positive")
    if (liveBuckets <= 0)
      throw new IllegalArgumentException("liveBuckets must be positive")
    val slotCount = liveBuckets + 1
    val starts = new Array[Long](slotCount)
    java.util.Arrays.fill(starts, Long.MinValue)
    new PageTokenCountByKey[T](
      streamPageTokenAppendWindow[T](bucketSeconds)
        .asInstanceOf[StreamPageTokenAppendWindow[T]],
      keySpace,
      slotCount,
      starts,
      new Array[Int](slotCount * keySpace),
      new Array[Long](slotCount * keySpace)
    ).asInstanceOf[PageTokenCountByKey[T]^{parent}]
  }

  /** Opens a checked epoch append/drain operator.
   *
   *  Call `epochBufferRegionFor` once per epoch, allocate records in the
   *  returned child region, append them with `appendEpochBuffer`, then close the
   *  epoch with `closeEpochBufferWithCursor`.
   */
  def epochBuffer[T <: StreamAppendNode]()(using
      parent: StreamingRegion^
  ): EpochBuffer[T]^{parent} =
    new EpochBuffer[T](
      streamAppendWindow[T](Long.MaxValue)
        .asInstanceOf[StreamAppendWindow[T]]
    ).asInstanceOf[EpochBuffer[T]^{parent}]

  /** Opens a checked transaction region with `listCount` internal lists. */
  def transactionRegion(listCount: Int)(using
      parent: StreamingRegion^
  ): TransactionRegion^{parent} = {
    if (listCount <= 0)
      throw new IllegalArgumentException("listCount must be positive")
    new TransactionRegion(new Array[Object](listCount))
      .asInstanceOf[TransactionRegion^{parent}]
  }

  /** Returns a typed list handle for a transaction-internal list slot. */
  def transactionList[T <: StreamAppendNode](
      parent: StreamingRegion^,
      tx: TransactionRegion^{parent},
      index: Int
  ): TransactionList[T]^{parent} = {
    if (index < 0 || index >= tx.lists.length)
      throw new IndexOutOfBoundsException("transaction list index out of range")
    val raw = tx.asInstanceOf[TransactionRegion]
    val existing = raw.lists(index)
    if (existing != null)
      existing.asInstanceOf[TransactionList[T]^{parent}]
    else {
      val created = new TransactionList[T](raw, index)
      raw.lists(index) = created.asInstanceOf[Object]
      created.asInstanceOf[TransactionList[T]^{parent}]
    }
  }

  /** Opens a checked fixed-chunk append window.
   *
   *  Call `chunkAppendRegionFor` when entering a bucket, allocate records in
   *  the returned child region, then append them with `appendChunkToken`.
   */
  def streamChunkAppendWindow[T <: Object](
      bucketSeconds: Long,
      chunkSize: Int
  )(using parent: StreamingRegion^): StreamChunkAppendWindow[T]^{parent} = {
    if (chunkSize <= 0)
      throw new IllegalArgumentException("chunkSize must be positive")
    new StreamChunkAppendWindow[T](
      streamBucketArena(bucketSeconds).asInstanceOf[StreamBucketArena],
      chunkSize
    ).asInstanceOf[StreamChunkAppendWindow[T]^{parent}]
  }

  /** Opens a parent-captured two-sided append/join window primitive. */
  def streamJoinWindow[T <: StreamAppendNode](
      bucketSeconds: Long,
      keyCapacity: Int
  )(using parent: StreamingRegion^): StreamJoinWindow[T]^{parent} = {
    if (keyCapacity <= 0)
      throw new IllegalArgumentException("keyCapacity must be positive")
    val append =
      streamAppendWindow[T](bucketSeconds).asInstanceOf[StreamAppendWindow[T]]
    val leftCounts = alloc(new Array[Int](keyCapacity))
    val rightCounts = alloc(new Array[Int](keyCapacity))
    new StreamJoinWindow[T](
      append,
      leftCounts,
      rightCounts
    ).asInstanceOf[StreamJoinWindow[T]^{parent}]
  }

  private def nextPowerOfTwo(value: Int): Int = {
    var n = 1
    val target = if (value <= 1) 1 else value
    while (n > 0 && n < target) n <<= 1
    if (n > 0) n else 1 << 30
  }

  /** Opens a parent-captured additive fold window primitive. */
  def streamWindowFold[T <: StreamAppendNode](
      bucketSeconds: Long,
      initialKeyCapacity: Int
  )(using parent: StreamingRegion^): StreamWindowFold[T]^{parent} = {
    if (initialKeyCapacity <= 0)
      throw new IllegalArgumentException("initialKeyCapacity must be positive")
    val capacity = nextPowerOfTwo(initialKeyCapacity * 2)
    val append =
      streamAppendWindow[T](bucketSeconds).asInstanceOf[StreamAppendWindow[T]]
    val keys = alloc(new Array[Int](capacity))
    val sums = alloc(new Array[Long](capacity))
    val counts = alloc(new Array[Int](capacity))
    val states = alloc(new Array[Byte](capacity))
    new StreamWindowFold[T](
      append,
      keys,
      sums,
      counts,
      states,
      0,
      0
    ).asInstanceOf[StreamWindowFold[T]^{parent}]
  }

  /** Opens an epoch-local fold/count/sum operator. */
  def epochFold[T <: StreamAppendNode](
      bucketSeconds: Long,
      initialKeyCapacity: Int
  )(using parent: StreamingRegion^): EpochFold[T]^{parent} =
    new EpochFold[T](
      streamWindowFold[T](bucketSeconds, initialKeyCapacity)
        .asInstanceOf[StreamWindowFold[T]]
    ).asInstanceOf[EpochFold[T]^{parent}]

  /** Opens an epoch-local top-k-by-key operator.
   *
   *  `keySpace` is the dense key domain `[0, keySpace)`. The operator is
   *  parent-owned metadata; epoch-local records are still allocated with
   *  `RiftRegion.epoch` and can be bulk-reclaimed independently of this table.
   */
  def epochTopKByKey(
      keySpace: Int,
      topK: Int
  )(using parent: StreamingRegion^): EpochTopKByKey^{parent} = {
    if (keySpace <= 0)
      throw new IllegalArgumentException("keySpace must be positive")
    if (topK <= 0)
      throw new IllegalArgumentException("topK must be positive")
    new EpochTopKByKey(
      keySpace,
      topK,
      new Array[Int](keySpace),
      Array.fill[Int](topK)(-1),
      new Array[Int](topK)
    ).asInstanceOf[EpochTopKByKey^{parent}]
  }

  /** Allocates an empty checked linked list in `region`. */
  def regionList[T <: RegionListNode]()(using
      region: RiftRegion^
  ): RegionList[T]^{region} =
    alloc(new RegionList[T](null, 0))

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
  ): StreamBucket^{parent} = {
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
      arenaBucket.asInstanceOf[StreamBucket^{parent}]
    }
  }

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

  private def streamBucketForOwnedAppend(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} = {
    val startSeconds =
      Math.floorDiv(timestampSeconds, arena.bucketSeconds) * arena.bucketSeconds
    streamBucketForOwnedAppendStart(parent, arena, startSeconds)
  }

  private def streamBucketForOwnedAppendStart(
      parent: StreamingRegion^,
      arena: StreamBucketArena^{parent},
      startSeconds: Long
  ): StreamBucket^{parent} = {
    val current = arena.current
    if (current != null && current.startSeconds == startSeconds)
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
      arenaBucket.asInstanceOf[StreamBucket^{parent}]
    }
  }

  private def streamBucketRegionTrusted(
      parent: StreamingRegion^,
      bucket: StreamBucket^{parent}
  ): StreamingRegion^{parent} =
    bucket.child.region.asInstanceOf[StreamingRegion]

  private def streamBucketRiftOpenHandleTrusted(
      parent: StreamingRegion^,
      bucket: StreamBucket^{parent}
  ): RiftOpenStreamingHandle^{parent} =
    bucket.child.region match {
      case region: MemoryRiftRegion =>
        new RiftOpenStreamingHandle(region.handle)
          .asInstanceOf[RiftOpenStreamingHandle^{parent}]
      case _ =>
        throw new UnsupportedOperationException(
          "Rift open-handle allocation is only available for Rift-backed streaming regions"
        )
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

  /** Finds or opens the append-window bucket containing `timestampSeconds`. */
  def streamAppendWindowBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamBucketFor(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )

  /** Finds or opens the append-window bucket containing `timestampSeconds`. */
  def streamAppendWindowBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamBucketFor(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )(onOpen)

  /** Finds or opens the join-window bucket containing `timestampSeconds`. */
  def streamJoinWindowBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamAppendWindowBucketFor(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      timestampSeconds
    )

  /** Finds or opens the join-window bucket containing `timestampSeconds`. */
  def streamJoinWindowBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamAppendWindowBucketFor(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      timestampSeconds
    )(onOpen)

  /** Finds or opens the fold-window bucket containing `timestampSeconds`. */
  def streamWindowFoldBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamAppendWindowBucketFor(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      timestampSeconds
    )

  /** Finds or opens the fold-window bucket containing `timestampSeconds`. */
  def streamWindowFoldBucketFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamAppendWindowBucketFor(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      timestampSeconds
    )(onOpen)

  /** Returns the child region for the epoch containing `timestampSeconds`. */
  def epochFoldRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent},
      timestampSeconds: Long
  ): StreamingRegion^{parent} = {
    val rawFold = fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}]
    val bucket = streamWindowFoldBucketFor(parent, rawFold, timestampSeconds)
    fold.currentBucket = bucket.asInstanceOf[StreamBucket]
    streamBucketRegionTrusted(parent, bucket)
  }

  private def checkJoinWindowKey[T <: StreamAppendNode](
      join: StreamJoinWindow[T],
      key: Int
  ): Unit =
    if (key < 0 || key >= join.leftCounts.length)
      throw new IndexOutOfBoundsException("Rift StreamJoinWindow key is absent")

  private def packJoinCounts(left: Int, right: Int): Long =
    (left.toLong << 32) | (right.toLong & 0xffffffffL)

  private final val FoldEmpty: Byte = 0
  private final val FoldUsed: Byte = 1
  private final val FoldDeleted: Byte = 2

  private def foldHash(key: Int): Int = {
    var x = key
    x ^= x >>> 16
    x *= 0x7feb352d
    x ^= x >>> 15
    x *= 0x846ca68b
    x ^ (x >>> 16)
  }

  private def findFoldSlot[T <: StreamAppendNode](
      fold: StreamWindowFold[T],
      key: Int
  ): Int = {
    val states = fold.states
    val keys = fold.keys
    val mask = states.length - 1
    var slot = foldHash(key) & mask
    while (states(slot) != FoldEmpty) {
      if (states(slot) == FoldUsed && keys(slot) == key) return slot
      slot = (slot + 1) & mask
    }
    -1
  }

  private def findFoldInsertSlot[T <: StreamAppendNode](
      fold: StreamWindowFold[T],
      key: Int
  ): Int = {
    val states = fold.states
    val keys = fold.keys
    val mask = states.length - 1
    var slot = foldHash(key) & mask
    var firstDeleted = -1
    while (states(slot) != FoldEmpty) {
      val state = states(slot)
      if (state == FoldUsed && keys(slot) == key) return slot
      if (state == FoldDeleted && firstDeleted < 0) firstDeleted = slot
      slot = (slot + 1) & mask
    }
    if (firstDeleted >= 0) firstDeleted else slot
  }

  private def rehashFoldTable[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T],
      nextCapacity: Int
  ): Unit = {
    val oldKeys = fold.keys
    val oldSums = fold.sums
    val oldCounts = fold.counts
    val oldStates = fold.states
    fold.keys = parent.alloc(new Array[Int](nextCapacity))
    fold.sums = parent.alloc(new Array[Long](nextCapacity))
    fold.counts = parent.alloc(new Array[Int](nextCapacity))
    fold.states = parent.alloc(new Array[Byte](nextCapacity))
    fold.size = 0
    fold.deleted = 0

    var index = 0
    while (index < oldStates.length) {
      if (oldStates(index) == FoldUsed) {
        val key = oldKeys(index)
        val slot = findFoldInsertSlot(fold, key)
        fold.keys(slot) = key
        fold.sums(slot) = oldSums(index)
        fold.counts(slot) = oldCounts(index)
        fold.states(slot) = FoldUsed
        fold.size += 1
      }
      index += 1
    }
  }

  private def clearFoldTable[T <: StreamAppendNode](
      fold: StreamWindowFold[T]
  ): Unit = {
    val states = fold.states
    val sums = fold.sums
    val counts = fold.counts
    var index = 0
    while (index < states.length) {
      if (states(index) != FoldEmpty) {
        states(index) = FoldEmpty
        sums(index) = 0L
        counts(index) = 0
      }
      index += 1
    }
    fold.size = 0
    fold.deleted = 0
  }

  private def ensureFoldCapacity[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]
  ): Unit =
    if ((fold.size + fold.deleted + 1) * 4 >= fold.states.length * 3) {
      val nextCapacity =
        if (fold.deleted > fold.size / 2) fold.states.length
        else fold.states.length << 1
      rehashFoldTable(parent, fold, nextCapacity)
    }

  private def addFoldContribution[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T],
      key: Int,
      delta: Long
  ): Long = {
    ensureFoldCapacity(parent, fold)
    val slot = findFoldInsertSlot(fold, key)
    if (fold.states(slot) == FoldUsed) {
      val next = fold.sums(slot) + delta
      fold.sums(slot) = next
      fold.counts(slot) += 1
      next
    } else {
      if (fold.states(slot) == FoldDeleted) fold.deleted -= 1
      fold.keys(slot) = key
      fold.sums(slot) = delta
      fold.counts(slot) = 1
      fold.states(slot) = FoldUsed
      fold.size += 1
      delta
    }
  }

  private def appendWindowUnchecked[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit = {
    bucket.child.checkOpen()
    value.appendNext = null
    if (bucket.appendHead == null) {
      bucket.appendHead = value.asInstanceOf[Object]
      bucket.appendTail = value.asInstanceOf[Object]
    } else {
      bucket.appendTail
        .asInstanceOf[StreamAppendNode]
        .appendNext = value.asInstanceOf[StreamAppendNode]
      bucket.appendTail = value.asInstanceOf[Object]
    }
    bucket.appendLength += 1
    window.totalLength += 1
  }

  private def appendWindowOwnedOpen[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit = {
    value.appendNext = null
    if (bucket.appendHead == null) {
      bucket.appendHead = value.asInstanceOf[Object]
      bucket.appendTail = value.asInstanceOf[Object]
    } else {
      bucket.appendTail
        .asInstanceOf[StreamAppendNode]
        .appendNext = value.asInstanceOf[StreamAppendNode]
      bucket.appendTail = value.asInstanceOf[Object]
    }
    bucket.appendLength += 1
    window.totalLength += 1
  }

  private def appendPageTokenOwnedOpen[T <: StreamAppendNode](
      parent: StreamingRegion^,
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit = {
    value.appendNext = null
    if (bucket.appendHead == null) {
      bucket.appendHead = value.asInstanceOf[Object]
      bucket.appendTail = value.asInstanceOf[Object]
    } else {
      bucket.appendTail
        .asInstanceOf[StreamAppendNode]
        .appendNext = value.asInstanceOf[StreamAppendNode]
      bucket.appendTail = value.asInstanceOf[Object]
    }
    bucket.appendLength += 1
  }

  /** Appends `value` to the linked list owned by `bucket`.
   *
   *  The value should be allocated in `bucket`'s child region and then widened
   *  with the parent owner token. The compiler guard rejects direct heap values
   *  passed here unless they are explicit `HeapRoot` handles.
   */
  def appendWindow[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit = {
    appendWindowUnchecked(
      parent,
      window,
      bucket,
      value
    )
  }

  /** Returns the child region for the bucket containing `timestampSeconds`.
   *
   *  This operator-owned path first closes expired buckets, then caches the
   *  active bucket internally. Callers allocate one or more records in the
   *  returned region and append them with `appendPageToken`.
   */
  def pageTokenAppendRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): StreamingRegion^{parent} = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    val arena = append.buckets.asInstanceOf[StreamBucketArena^{parent}]
    val startSeconds =
      Math.floorDiv(timestampSeconds, arena.bucketSeconds) * arena.bucketSeconds
    val current = window.currentBucket
    val currentIsOnlyLiveBucket =
      current != null &&
        (window.append.buckets.first.asInstanceOf[AnyRef] eq current
          .asInstanceOf[AnyRef])
    val currentCanStayOpen =
      currentIsOnlyLiveBucket &&
        current.startSeconds + arena.bucketSeconds > cutoffSeconds
    if (
      current != null &&
      current.startSeconds == startSeconds &&
      currentCanStayOpen
    )
      streamBucketRegionTrusted(
        parent,
        current.asInstanceOf[StreamBucket^{parent}]
      )
    else {
      val needsClose = hasStreamBucketsBefore(parent, arena, cutoffSeconds)
      if (needsClose)
        closePageTokenAppendBucketsBeforeWithCursor(
          parent,
          window,
          cutoffSeconds
        )(onBucket)

      val bucket = streamBucketForOwnedAppendStart(parent, arena, startSeconds)
      window.currentBucket = bucket.asInstanceOf[StreamBucket]
      streamBucketRegionTrusted(parent, bucket)
    }
  }

  /** Returns an open child region for operator-owned page/token allocation.
   *
   *  This has the same bucket-selection semantics as `pageTokenAppendRegionFor`
   *  but returns the narrower marker required by `allocOpen`. It should only be
   *  used in the page-token append loop that immediately appends records to the
   *  selected bucket.
   */
  def pageTokenAppendOpenRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): OpenStreamingRegion^{parent} =
    pageTokenAppendRegionFor(
      parent,
      window,
      timestampSeconds,
      cutoffSeconds
    )(onBucket).asInstanceOf[OpenStreamingRegion^{parent}]

  /** Returns a Rift backend handle for operator-owned page/token allocation.
   *
   *  This is an experimental lowering gate for the Rift-backed page-token path.
   *  It has the same bucket-selection semantics as `pageTokenAppendOpenRegionFor`
   *  but deliberately rejects SafeZone-backed checked regions because the handle
   *  lowers directly to `scalanative_rift_region_alloc`.
   */
  def pageTokenAppendRiftOpenHandleFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): RiftOpenStreamingHandle^{parent} = {
    pageTokenAppendRegionFor(
      parent,
      window,
      timestampSeconds,
      cutoffSeconds
    )(onBucket)
    streamBucketRiftOpenHandleTrusted(
      parent,
      window.currentBucket.asInstanceOf[StreamBucket^{parent}]
    )
  }

  /** Returns the child region for the current map/filter page bucket. */
  def pageTokenMapFilterRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenMapFilter[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): StreamingRegion^{parent} =
    pageTokenAppendRegionFor(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}],
      timestampSeconds,
      cutoffSeconds
    )(onBucket)

  /** Returns an open child region for the current map/filter page bucket. */
  def pageTokenMapFilterOpenRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenMapFilter[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): OpenStreamingRegion^{parent} =
    pageTokenAppendOpenRegionFor(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}],
      timestampSeconds,
      cutoffSeconds
    )(onBucket)

  private def pageTokenCountByKeyStart[T <: StreamAppendNode](
      operator: PageTokenCountByKey[T],
      timestampSeconds: Long
  ): Long = {
    val pageToken = operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]]
    val append = pageToken.append.asInstanceOf[StreamAppendWindow[T]]
    val arena = append.buckets
    Math.floorDiv(timestampSeconds, arena.bucketSeconds) * arena.bucketSeconds
  }

  private def pageTokenCountByKeySlot[T <: StreamAppendNode](
      operator: PageTokenCountByKey[T],
      startSeconds: Long
  ): Int = {
    val pageToken = operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]]
    val append = pageToken.append.asInstanceOf[StreamAppendWindow[T]]
    val arena = append.buckets
    val slot =
      Math.floorMod(
        Math.floorDiv(startSeconds, arena.bucketSeconds),
        operator.slotCount.toLong
      ).toInt
    val previous = operator.bucketStarts(slot)
    if (previous == startSeconds)
      slot
    else if (previous == Long.MinValue) {
      operator.bucketStarts(slot) = startSeconds
      slot
    } else
      throw new IllegalStateException(
        "PageTokenCountByKey slot collision; increase liveBuckets or close buckets sooner"
      )
  }

  private def pageTokenCountByKeyExistingSlot[T <: StreamAppendNode](
      operator: PageTokenCountByKey[T],
      startSeconds: Long
  ): Int = {
    var i = 0
    while (i < operator.slotCount) {
      if (operator.bucketStarts(i) == startSeconds) return i
      i += 1
    }
    -1
  }

  private def clearPageTokenCountByKeySlot[T <: StreamAppendNode](
      operator: PageTokenCountByKey[T],
      slot: Int
  ): Unit = {
    val base = slot * operator.keySpace
    java.util.Arrays.fill(operator.counts, base, base + operator.keySpace, 0)
    java.util.Arrays.fill(operator.sums, base, base + operator.keySpace, 0L)
    operator.bucketStarts(slot) = Long.MinValue
    if (operator.currentSlot == slot) operator.currentSlot = -1
  }

  private def emitPageTokenCountByKeyBucket[T <: StreamAppendNode](
      operator: PageTokenCountByKey[T],
      bucket: StreamBucket,
      onSummary: (StreamBucket, Int, Int, Long) => Unit
  ): Unit = {
    val slot = pageTokenCountByKeyExistingSlot(operator, bucket.startSeconds)
    if (slot >= 0) {
      val base = slot * operator.keySpace
      var key = 0
      while (key < operator.keySpace) {
        val count = operator.counts(base + key)
        if (count != 0)
          onSummary(bucket, key, count, operator.sums(base + key))
        key += 1
      }
      clearPageTokenCountByKeySlot(operator, slot)
    }
  }

  /** Returns the child region for the current page-token count/sum bucket. */
  def pageTokenCountByKeyRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenCountByKey[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onSummary: (StreamBucket^{parent}, Int, Int, Long) => Unit)
      : StreamingRegion^{parent} = {
    closePageTokenCountByKeyBucketsBefore(
      parent,
      operator,
      cutoffSeconds
    )(onSummary)
    val startSeconds =
      pageTokenCountByKeyStart(operator.asInstanceOf[PageTokenCountByKey[T]], timestampSeconds)
    val slot =
      pageTokenCountByKeySlot(operator.asInstanceOf[PageTokenCountByKey[T]], startSeconds)
    operator.currentSlot = slot
    pageTokenAppendRegionFor(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}],
      timestampSeconds,
      Long.MinValue
    ) { (_, _) => () }
  }

  /** Returns an open child region for a page-token count/sum bucket. */
  def pageTokenCountByKeyOpenRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenCountByKey[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onSummary: (StreamBucket^{parent}, Int, Int, Long) => Unit)
      : OpenStreamingRegion^{parent} =
    pageTokenCountByKeyRegionFor(
      parent,
      operator,
      timestampSeconds,
      cutoffSeconds
    )(onSummary).asInstanceOf[OpenStreamingRegion^{parent}]

  /** Returns the child region for the active epoch buffer.
   *
   *  The operator owns the bucket token, so callers cannot accidentally append
   *  to an expired bucket. Closing the epoch clears this cached bucket before
   *  the child region is closed.
   */
  def epochBufferRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent}
  ): StreamingRegion^{parent} = {
    val current = buffer.currentBucket
    val bucket =
      if (current != null)
        current.asInstanceOf[StreamBucket^{parent}]
      else {
        val append = buffer.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
        val opened = streamBucketForOwnedAppend(
          parent,
          append.buckets.asInstanceOf[StreamBucketArena^{parent}],
          0L
        )
        buffer.currentBucket = opened.asInstanceOf[StreamBucket]
        opened
      }
    streamBucketRegionTrusted(parent, bucket)
  }

  /** Returns the active child region for an epoch buffer on the checked
   *  operator-owned allocation fast path.
   *
   *  This is the epoch sibling of `pageTokenAppendOpenRegionFor`: the operator
   *  owns the bucket token, so checked benchmark/library code can allocate
   *  records with `allocOpen` without paying the generic per-allocation
   *  `checkOpen` branch. Public low-level region APIs remain defensive.
   */
  def epochBufferOpenRegionFor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent}
  ): OpenStreamingRegion^{parent} =
    epochBufferRegionFor(parent, buffer)
      .asInstanceOf[OpenStreamingRegion^{parent}]

  /** Returns the active child region for a checked transaction.
   *
   *  The same region is reused by all transaction-internal lists until
   *  `closeTransactionRegion` closes the transaction.
   */
  def transactionRegionFor(
      parent: StreamingRegion^,
      tx: TransactionRegion^{parent}
  ): StreamingRegion^{parent} = {
    val current = tx.child
    val child =
      if (current != null)
        current.asInstanceOf[ChildBucket^{parent}]
      else {
        val opened = childBucket(using parent)
        tx.child = opened.asInstanceOf[ChildBucket]
        opened
      }
    child.region.asInstanceOf[StreamingRegion]
  }

  /** Appends a record to the page/token window's current bucket.
   *
   *  The current bucket is selected by `pageTokenAppendRegionFor`. This hot path
   *  deliberately skips the defensive child-open check used by public
   *  bucket-token APIs.
   */
  def appendPageToken[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      value: T^{parent}
  ): Unit = {
    val bucket = window.currentBucket.asInstanceOf[StreamBucket^{parent}]
    appendPageTokenOwnedOpen(parent, bucket, value)
  }

  /** Appends a record and updates the current bucket's count/sum metadata. */
  def appendPageTokenCountByKey[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenCountByKey[T]^{parent},
      value: T^{parent},
      key: Int,
      amount: Long
  ): Unit = {
    if (key < 0 || key >= operator.keySpace)
      throw new IndexOutOfBoundsException(
        s"key $key outside PageTokenCountByKey keySpace ${operator.keySpace}"
      )
    val slot = operator.currentSlot
    if (slot < 0)
      throw new IllegalStateException(
        "PageTokenCountByKey has no active bucket; call pageTokenCountByKeyRegionFor first"
      )
    val pageToken =
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}]
    val bucket = pageToken.currentBucket.asInstanceOf[StreamBucket^{parent}]
    appendPageTokenOwnedOpen(parent, bucket, value)
    val index = slot * operator.keySpace + key
    operator.counts(index) += 1
    operator.sums(index) += amount
  }

  /** Appends one record to the active epoch buffer. */
  def appendEpochBuffer[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent},
      value: T^{parent}
  ): Unit = {
    val append = buffer.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    val bucket = buffer.currentBucket.asInstanceOf[StreamBucket^{parent}]
    appendWindowOwnedOpen(parent, append, bucket, value)
  }

  /** Appends one record to a transaction-internal list. */
  def appendTransactionList[T <: StreamAppendNode](
      parent: StreamingRegion^,
      list: TransactionList[T]^{parent},
      value: T^{parent}
  ): Unit = {
    val tx = list.tx
    if (tx.child == null)
      throw new IllegalStateException("Rift TransactionRegion is not open")
    value.appendNext = null
    if (list.head == null) {
      list.head = value.asInstanceOf[Object]
      list.tail = value.asInstanceOf[Object]
    } else {
      list.tail
        .asInstanceOf[StreamAppendNode]
        .appendNext = value.asInstanceOf[StreamAppendNode]
      list.tail = value.asInstanceOf[Object]
    }
    list.length0 += 1
  }

  /** Emits one projected record into the current map/filter page bucket. */
  def emitPageTokenMapFilter[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenMapFilter[T]^{parent},
      value: T^{parent}
  ): Unit = {
    val window =
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}]
    val bucket = window.currentBucket.asInstanceOf[StreamBucket^{parent}]
    appendPageTokenOwnedOpen(parent, bucket, value)
  }

  /** Returns the child region for a fixed-chunk append bucket.
   *
   *  This has the same operator-owned lifetime shape as page/token append, but
   *  close drains region-owned object-array chunks instead of per-record links.
   */
  def chunkAppendRegionFor[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent},
      timestampSeconds: Long,
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamChunkCursor[T]^{parent}
  ) => Unit): StreamingRegion^{parent} = {
    closeChunkAppendBucketsBeforeWithCursor(
      parent,
      window,
      cutoffSeconds
    )(onBucket)
    val bucket = streamBucketForOwnedAppend(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )
    window.currentBucket = bucket.asInstanceOf[StreamBucket]
    streamBucketRegionTrusted(parent, bucket)
  }

  private def newStreamChunk(
      region: StreamingRegion^,
      chunkSize: Int
  ): StreamChunk^{region} = {
    val items =
      RiftRegion.alloc(new Array[Object](chunkSize))(using region)
        .asInstanceOf[Array[Object]]
    RiftRegion.alloc(new StreamChunk(items))(using region)
  }

  /** Appends a record to the current fixed-chunk bucket.
   *
   *  `chunkAppendRegionFor` selects and proves the current bucket. This hot
   *  path avoids per-record bucket-open checks and per-record linked-list
   *  pointer maintenance.
   */
  def appendChunkToken[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent},
      value: T^{parent}
  ): Unit = {
    val bucket = window.currentBucket.asInstanceOf[StreamBucket^{parent}]
    var tail = bucket.chunkTail.asInstanceOf[StreamChunk]
    if (tail == null || tail.used >= window.chunkSize) {
      val region = streamBucketRegionTrusted(parent, bucket)
      val chunk =
        newStreamChunk(region, window.chunkSize).asInstanceOf[StreamChunk]
      if (bucket.chunkHead == null) {
        bucket.chunkHead = chunk
        bucket.chunkTail = chunk
      } else {
        tail.next = chunk
        bucket.chunkTail = chunk
      }
      tail = chunk
    }
    tail.items(tail.used) = value.asInstanceOf[Object]
    tail.used += 1
    bucket.chunkLength += 1
    window.totalLength += 1
  }

  /** Appends a fold record and adds `delta` to the live aggregate for `key`. */
  def putFoldInBucket[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      delta: Long,
      value: T^{parent}
  ): Long = {
    bucket.child.checkOpen()
    val next = addFoldContribution(
      parent,
      fold.asInstanceOf[StreamWindowFold[T]],
      key,
      delta
    )
    appendWindowUnchecked(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    next
  }

  /** Adds one record to the current epoch and updates its aggregate. */
  def putEpochFold[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent},
      key: Int,
      delta: Long,
      value: T^{parent}
  ): Long = {
    val rawFold = fold.fold.asInstanceOf[StreamWindowFold[T]]
    val next = addFoldContribution(parent, rawFold, key, delta)
    val typedFold = fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}]
    val bucket = fold.currentBucket.asInstanceOf[StreamBucket^{parent}]
    appendWindowOwnedOpen(
      parent,
      typedFold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    next
  }

  /** Removes one fold contribution for `key` and returns the new aggregate. */
  def removeFoldContribution[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      key: Int,
      delta: Long
  ): Long = {
    val rawFold = fold.asInstanceOf[StreamWindowFold[T]]
    val slot = findFoldSlot(rawFold, key)
    if (slot < 0 || rawFold.counts(slot) <= 0)
      throw new IllegalStateException("Rift StreamWindowFold count underflow")
    val nextCount = rawFold.counts(slot) - 1
    val nextSum = rawFold.sums(slot) - delta
    if (nextCount == 0) {
      rawFold.states(slot) = FoldDeleted
      rawFold.sums(slot) = 0L
      rawFold.counts(slot) = 0
      rawFold.size -= 1
      rawFold.deleted += 1
      0L
    } else {
      rawFold.sums(slot) = nextSum
      rawFold.counts(slot) = nextCount
      nextSum
    }
  }

  /** Returns true if the fold currently has a live aggregate for `key`. */
  def containsFoldKey[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      key: Int
  ): Boolean =
    findFoldSlot(fold.asInstanceOf[StreamWindowFold[T]], key) >= 0

  /** Returns the live aggregate sum for `key`, or zero if absent. */
  def foldValue[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      key: Int
  ): Long = {
    val rawFold = fold.asInstanceOf[StreamWindowFold[T]]
    val slot = findFoldSlot(rawFold, key)
    if (slot >= 0) rawFold.sums(slot) else 0L
  }

  /** Returns the live contribution count for `key`, or zero if absent. */
  def foldCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      key: Int
  ): Int = {
    val rawFold = fold.asInstanceOf[StreamWindowFold[T]]
    val slot = findFoldSlot(rawFold, key)
    if (slot >= 0) rawFold.counts(slot) else 0
  }

  /** Returns the number of keys with at least one live contribution. */
  def foldKeyCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent}
  ): Int =
    fold.size

  /** Returns true if the epoch fold currently has a live aggregate for `key`. */
  def containsEpochFoldKey[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent},
      key: Int
  ): Boolean =
    containsFoldKey(
      parent,
      fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}],
      key
    )

  /** Returns the live epoch aggregate sum for `key`, or zero if absent. */
  def epochFoldValue[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent},
      key: Int
  ): Long =
    foldValue(
      parent,
      fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}],
      key
    )

  /** Returns the live epoch contribution count for `key`, or zero if absent. */
  def epochFoldCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent},
      key: Int
  ): Int =
    foldCount(
      parent,
      fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}],
      key
    )

  /** Returns the number of keys with at least one live epoch contribution. */
  def epochFoldKeyCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent}
  ): Int =
    foldKeyCount(
      parent,
      fold.fold.asInstanceOf[StreamWindowFold[T]^{parent}]
    )

  /** Iterates live epoch aggregate entries without per-key lookup. */
  def foreachEpochFoldEntry[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent}
  )(onEntry: (Int, Long, Int) => Unit): Unit = {
    val rawFold = fold.fold.asInstanceOf[StreamWindowFold[T]]
    val states = rawFold.states
    val keys = rawFold.keys
    val sums = rawFold.sums
    val counts = rawFold.counts
    var index = 0
    while (index < states.length) {
      if (states(index) == FoldUsed)
        onEntry(keys(index), sums(index), counts(index))
      index += 1
    }
  }

  /** Clears all per-key counts before starting a new top-k epoch. */
  def beginEpochTopKByKey(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent}
  ): Unit = {
    java.util.Arrays.fill(topK.counts, 0)
    topK.topLength = 0
  }

  /** Adds `amount` to the key count for the active top-k epoch. */
  def addEpochTopKByKey(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent},
      key: Int,
      amount: Int
  ): Unit = {
    topK.counts(key) += amount
  }

  /** Increments the key count for the active top-k epoch. */
  def incrementEpochTopKByKey(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent},
      key: Int
  ): Unit =
    topK.counts(key) += 1

  private def betterEpochTopK(
      count: Int,
      key: Int,
      otherCount: Int,
      otherKey: Int
  ): Boolean =
    count > otherCount || (count == otherCount && (otherKey < 0 || key < otherKey))

  /** Computes top-k entries from the current epoch counts.
   *
   *  The retained epoch record graph is not traversed here: callers update the
   *  primitive counts while appending records, close/reset the epoch region,
   *  and then call this method to scan only the parent-owned key table.
   */
  def finishEpochTopKByKey(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent}
  ): Int = {
    java.util.Arrays.fill(topK.topKeys, -1)
    java.util.Arrays.fill(topK.topCounts, 0)
    topK.topLength = 0

    var key = 0
    while (key < topK.keySpace) {
      val count = topK.counts(key)
      if (
        count > 0 &&
        (topK.topLength < topK.topK ||
          betterEpochTopK(
            count,
            key,
            topK.topCounts(topK.topK - 1),
            topK.topKeys(topK.topK - 1)
          ))
      ) {
        var pos =
          if (topK.topLength < topK.topK) topK.topLength
          else topK.topK - 1
        if (topK.topLength < topK.topK)
          topK.topLength += 1
        while (
          pos > 0 &&
          betterEpochTopK(
            count,
            key,
            topK.topCounts(pos - 1),
            topK.topKeys(pos - 1)
          )
        ) {
          topK.topCounts(pos) = topK.topCounts(pos - 1)
          topK.topKeys(pos) = topK.topKeys(pos - 1)
          pos -= 1
        }
        topK.topCounts(pos) = count
        topK.topKeys(pos) = key
      }
      key += 1
    }
    topK.topLength
  }

  /** Returns the key at `rank` after `finishEpochTopKByKey`. */
  def epochTopKKey(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent},
      rank: Int
  ): Int = {
    if (rank < 0 || rank >= topK.topLength)
      throw new IndexOutOfBoundsException("EpochTopKByKey rank out of range")
    topK.topKeys(rank)
  }

  /** Returns the count at `rank` after `finishEpochTopKByKey`. */
  def epochTopKCount(
      parent: StreamingRegion^,
      topK: EpochTopKByKey^{parent},
      rank: Int
  ): Int = {
    if (rank < 0 || rank >= topK.topLength)
      throw new IndexOutOfBoundsException("EpochTopKByKey rank out of range")
    topK.topCounts(rank)
  }

  /** Appends a left-side join record and returns the new live left count. */
  def putJoinLeftInBucket[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent}
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    val counts = join.leftCounts
    val next = counts(key) + 1
    counts(key) = next
    appendWindowUnchecked(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    next
  }

  /** Appends a left-side join record and returns packed live counts.
   *
   *  The high 32 bits contain the new left count and the low 32 bits contain
   *  the current right count. This avoids a second checked count lookup in
   *  high-volume stream joins.
   */
  def putJoinLeftInBucketAndCounts[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent}
  ): Long = {
    val leftCounts = join.leftCounts
    val left = leftCounts(key) + 1
    leftCounts(key) = left
    appendWindowUnchecked(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    packJoinCounts(left, join.rightCounts(key))
  }

  /** Appends a right-side join record and returns the new live right count. */
  def putJoinRightInBucket[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent}
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    val counts = join.rightCounts
    val next = counts(key) + 1
    counts(key) = next
    appendWindowUnchecked(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    next
  }

  /** Appends a right-side join record and returns packed live counts.
   *
   *  The high 32 bits contain the current left count and the low 32 bits
   *  contain the new right count.
   */
  def putJoinRightInBucketAndCounts[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent}
  ): Long = {
    val rightCounts = join.rightCounts
    val right = rightCounts(key) + 1
    rightCounts(key) = right
    appendWindowUnchecked(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )
    packJoinCounts(join.leftCounts(key), right)
  }

  /** Appends a join output/scratch record without changing left/right counts. */
  def putJoinOutputInBucket[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit =
    appendWindowUnchecked(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      bucket,
      value
    )

  /** Returns the live left-side count for `key`. */
  def leftJoinWindowCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    join.leftCounts(key)
  }

  /** Returns the live right-side count for `key`. */
  def rightJoinWindowCount[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    join.rightCounts(key)
  }

  /** Removes one left-side join record and returns the new live left count. */
  def removeJoinLeft[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    val counts = join.leftCounts
    val current = counts(key)
    if (current <= 0)
      throw new IllegalStateException("Rift StreamJoinWindow left count underflow")
    val next = current - 1
    counts(key) = next
    next
  }

  /** Removes one left-side join record and returns packed live counts. */
  def removeJoinLeftAndCounts[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Long = {
    val leftCounts = join.leftCounts
    val current = leftCounts(key)
    if (current <= 0)
      throw new IllegalStateException("Rift StreamJoinWindow left count underflow")
    val left = current - 1
    leftCounts(key) = left
    packJoinCounts(left, join.rightCounts(key))
  }

  /** Removes one right-side join record and returns the new live right count. */
  def removeJoinRight[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Int = {
    checkJoinWindowKey(join.asInstanceOf[StreamJoinWindow[T]], key)
    val counts = join.rightCounts
    val current = counts(key)
    if (current <= 0)
      throw new IllegalStateException("Rift StreamJoinWindow right count underflow")
    val next = current - 1
    counts(key) = next
    next
  }

  /** Removes one right-side join record and returns packed live counts. */
  def removeJoinRightAndCounts[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      key: Int
  ): Long = {
    val rightCounts = join.rightCounts
    val current = rightCounts(key)
    if (current <= 0)
      throw new IllegalStateException("Rift StreamJoinWindow right count underflow")
    val right = current - 1
    rightCounts(key) = right
    packJoinCounts(join.leftCounts(key), right)
  }

  /** Prepends `value` to the linked list owned by `bucket`.
   *
   *  This is the unordered/head-insert sibling of `appendWindow`. It is useful
   *  for stream windows whose close-time fold does not depend on insertion
   *  order, and avoids the per-entry tail update on the hot path.
   */
  def prependWindow[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      value: T^{parent}
  ): Unit = {
    bucket.child.checkOpen()
    val head = bucket.appendHead
    value.appendNext = head.asInstanceOf[StreamAppendNode]
    bucket.appendHead = value.asInstanceOf[Object]
    if (head == null)
      bucket.appendTail = value.asInstanceOf[Object]
    bucket.appendLength += 1
    window.totalLength += 1
  }

  /** Returns the total number of live append-window records. */
  def appendWindowLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent}
  ): Int =
    window.totalLength

  /** Returns the total number of live records in an epoch buffer. */
  def epochBufferLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent}
  ): Int =
    buffer.append.totalLength

  /** Returns the number of live records in a transaction-internal list. */
  def transactionListLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      list: TransactionList[T]^{parent}
  ): Int =
    list.length0

  /** Prepends `value` to a checked region-owned linked list. */
  def prependRegionList[T <: RegionListNode](
      region: RiftRegion^,
      list: RegionList[T]^{region},
      value: T^{region}
  ): Unit = {
    value.regionListNext = list.headNode
    list.headNode = value.asInstanceOf[Object]
    list.length0 += 1
  }

  /** Returns the current head of a checked region-owned linked list. */
  def regionListHead[T <: RegionListNode](
      region: RiftRegion^,
      list: RegionList[T]^{region}
  ): T^{region} =
    list.headNode.asInstanceOf[T^{region}]

  /** Returns the next node in a checked region-owned linked list. */
  def regionListNext[T <: RegionListNode](
      region: RiftRegion^,
      value: T^{region}
  ): T^{region} =
    value.regionListNext.asInstanceOf[T^{region}]

  /** Returns the number of nodes in a checked region-owned linked list. */
  def regionListLength[T <: RegionListNode](
      region: RiftRegion^,
      list: RegionList[T]^{region}
  ): Int =
    list.length0

  /** Returns the total number of live records in a join window. */
  def joinWindowLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent}
  ): Int =
    appendWindowLength(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    )

  /** Returns the total number of live records in a fold window. */
  def foldWindowLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent}
  ): Int =
    appendWindowLength(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    )

  /** Returns the number of records currently linked to `bucket`. */
  def appendWindowBucketLength[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent}
  ): Int =
    bucket.appendLength

  /** Returns the total number of live records in a fixed-chunk append window. */
  def chunkAppendWindowLength[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent}
  ): Int =
    window.totalLength

  /** Returns the number of chunk-appended records currently in `bucket`. */
  def chunkAppendWindowBucketLength[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent}
  ): Int =
    bucket.chunkLength

  private def consumeAppendWindowBucket[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      onEntry: Function2[StreamBucket^{parent}, T^{parent}, Unit]
  ): Unit = {
    var current = bucket.appendHead
    val removed = bucket.appendLength
    bucket.appendHead = null
    bucket.appendTail = null
    bucket.appendLength = 0
    window.totalLength -= removed

    while (current != null) {
      val value = current.asInstanceOf[T^{parent}]
      val next = value.appendNext.asInstanceOf[Object]
      onEntry(bucket, value)
      value.appendNext = null
      current = next
    }
  }

  private def consumeAppendWindowBucketWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      onBucket: (
        StreamBucket^{parent},
        StreamAppendCursor[T]^{parent}
      ) => Unit
  ): Unit = {
    val head = bucket.appendHead.asInstanceOf[StreamAppendNode]
    val removed = bucket.appendLength
    bucket.appendHead = null
    bucket.appendTail = null
    bucket.appendLength = 0
    window.totalLength -= removed

    val cursor = window.cursor.asInstanceOf[StreamAppendCursor[T]^{parent}]
    cursor.current = head
    onBucket(bucket, cursor)
    while (cursor.hasNext) cursor.next()
    cursor.current = null
  }

  private def consumePageTokenAppendBucketWithFastCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      onBucket: (
        StreamBucket^{parent},
        StreamAppendCursor[T]^{parent}
      ) => Unit
  ): Unit = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    val head = bucket.appendHead.asInstanceOf[StreamAppendNode]
    bucket.appendHead = null
    bucket.appendTail = null
    bucket.appendLength = 0

    val cursor = append.cursor.asInstanceOf[StreamAppendCursor[T]^{parent}]
    cursor.current = head
    onBucket(bucket, cursor)
    // Page-token buckets are operator-owned: parent refs are cleared above,
    // callbacks cannot retain bucket-local records, and the child region closes
    // immediately after cleanup. Generic append-window APIs keep the defensive
    // leftover drain; this fast path avoids close-only link traversal.
    cursor.current = null
  }

  private def consumePageTokenAppendBucketNoDrain[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      onBucket: StreamBucket^{parent} => Unit
  ): Unit = {
    bucket.appendHead = null
    bucket.appendTail = null
    bucket.appendLength = 0
    onBucket(bucket)
  }

  private def consumeChunkAppendBucketWithCursor[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent},
      bucket: StreamBucket^{parent},
      onBucket: (
        StreamBucket^{parent},
        StreamChunkCursor[T]^{parent}
      ) => Unit
  ): Unit = {
    val head = bucket.chunkHead.asInstanceOf[StreamChunk]
    val removed = bucket.chunkLength
    bucket.chunkHead = null
    bucket.chunkTail = null
    bucket.chunkLength = 0
    window.totalLength -= removed

    val cursor = window.cursor.asInstanceOf[StreamChunkCursor[T]^{parent}]
    cursor.currentChunk = head
    cursor.currentIndex = 0
    onBucket(bucket, cursor)
    while (cursor.hasNext) cursor.next()
    cursor.currentChunk = null
    cursor.currentIndex = 0
  }

  /** Returns true if closing before `cutoffSeconds` would close a bucket. */
  def hasAppendWindowBucketsBefore[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      cutoffSeconds: Long
  ): Boolean =
    hasStreamBucketsBefore(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    )

  /** Closes append-window buckets fully before `cutoffSeconds`.
   *
   *  `onEntry` runs once per linked record after the parent bucket head/tail
   *  references have been cleared and before the child region closes.
   */
  def closeAppendWindowBucketsBefore[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      cutoffSeconds: Long
  )(onEntry: Function2[StreamBucket^{parent}, T^{parent}, Unit]): Unit =
    closeStreamBucketsBefore(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      consumeAppendWindowBucket(parent, window, bucket, onEntry)
    }

  /** Closes append-window buckets and drains each bucket through a cursor.
   *
   *  `onBucket` runs once per closed bucket after parent head/tail references
   *  have been cleared. The cursor exposes the bucket's records before the
   *  child region closes.
   */
  def closeAppendWindowBucketsBeforeWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      consumeAppendWindowBucketWithCursor(parent, window, bucket, onBucket)
    }

  /** Closes page/token append buckets fully before `cutoffSeconds`. */
  def closePageTokenAppendBucketsBeforeWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    closeStreamBucketsBefore(
      parent,
      append.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      if (window.currentBucket.asInstanceOf[AnyRef] eq bucket.asInstanceOf[AnyRef])
        window.currentBucket = null
      consumePageTokenAppendBucketWithFastCursor(parent, window, bucket, onBucket)
    }
  }

  /** Closes page/token append buckets before `cutoffSeconds` without draining
   *  records.
   *
   *  This is an operator-owned fast path for append-only or aggregate-on-append
   *  workloads. Parent head/tail references are cleared before `onBucket` runs,
   *  and the child bucket closes immediately after the callback. Generic
   *  append-window APIs keep cursor/entry drains for defensive cleanup.
   */
  def closePageTokenAppendBucketsBeforeNoDrain[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: StreamBucket^{parent} => Unit): Unit = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    closeStreamBucketsBefore(
      parent,
      append.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      if (window.currentBucket.asInstanceOf[AnyRef] eq bucket.asInstanceOf[AnyRef])
        window.currentBucket = null
      consumePageTokenAppendBucketNoDrain(parent, window, bucket, onBucket)
    }
  }

  /** Closes map/filter page buckets fully before `cutoffSeconds`. */
  def closePageTokenMapFilterBucketsBeforeWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenMapFilter[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closePageTokenAppendBucketsBeforeWithCursor(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}],
      cutoffSeconds
    )(onBucket)

  /** Closes count/sum buckets before `cutoffSeconds` without record drain. */
  def closePageTokenCountByKeyBucketsBefore[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenCountByKey[T]^{parent},
      cutoffSeconds: Long
  )(onSummary: (StreamBucket^{parent}, Int, Int, Long) => Unit): Unit =
    closePageTokenAppendBucketsBeforeNoDrain(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}],
      cutoffSeconds
    ) { bucket =>
      emitPageTokenCountByKeyBucket(
        operator.asInstanceOf[PageTokenCountByKey[T]],
        bucket.asInstanceOf[StreamBucket],
        onSummary.asInstanceOf[(StreamBucket, Int, Int, Long) => Unit]
      )
    }

  /** Closes join-window buckets fully before `cutoffSeconds`. */
  def closeJoinWindowBucketsBeforeWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAppendWindowBucketsBeforeWithCursor(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      cutoffSeconds
    )(onBucket)

  /** Closes fold-window buckets fully before `cutoffSeconds`. */
  def closeFoldBucketsBeforeWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAppendWindowBucketsBeforeWithCursor(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
      cutoffSeconds
    )(onBucket)

  /** Closes every append-window bucket. */
  def closeAllAppendWindowBuckets[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent}
  )(onEntry: Function2[StreamBucket^{parent}, T^{parent}, Unit]): Unit =
    closeAllStreamBuckets(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      consumeAppendWindowBucket(parent, window, bucket, onEntry)
    }

  /** Closes every append-window bucket and drains each bucket through a cursor. */
  def closeAllAppendWindowBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamAppendWindow[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      consumeAppendWindowBucketWithCursor(parent, window, bucket, onBucket)
    }

  /** Closes every page/token append bucket. */
  def closeAllPageTokenAppendBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    window.currentBucket = null
    closeAllStreamBuckets(
      parent,
      append.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      consumePageTokenAppendBucketWithFastCursor(parent, window, bucket, onBucket)
    }
  }

  /** Closes every page/token append bucket without draining records. */
  def closeAllPageTokenAppendBucketsNoDrain[T <: StreamAppendNode](
      parent: StreamingRegion^,
      window: StreamPageTokenAppendWindow[T]^{parent}
  )(onBucket: StreamBucket^{parent} => Unit): Unit = {
    val append = window.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    window.currentBucket = null
    closeAllStreamBuckets(
      parent,
      append.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      consumePageTokenAppendBucketNoDrain(parent, window, bucket, onBucket)
    }
  }

  /** Closes every map/filter page bucket. */
  def closeAllPageTokenMapFilterBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenMapFilter[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAllPageTokenAppendBucketsWithCursor(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}]
    )(onBucket)

  /** Closes every count/sum bucket without record drain. */
  def closeAllPageTokenCountByKeyBuckets[T <: StreamAppendNode](
      parent: StreamingRegion^,
      operator: PageTokenCountByKey[T]^{parent}
  )(onSummary: (StreamBucket^{parent}, Int, Int, Long) => Unit): Unit =
    closeAllPageTokenAppendBucketsNoDrain(
      parent,
      operator.pageToken.asInstanceOf[StreamPageTokenAppendWindow[T]^{parent}]
    ) { bucket =>
      emitPageTokenCountByKeyBucket(
        operator.asInstanceOf[PageTokenCountByKey[T]],
        bucket.asInstanceOf[StreamBucket],
        onSummary.asInstanceOf[(StreamBucket, Int, Int, Long) => Unit]
      )
    }

  /** Closes the active epoch buffer and drains records through a cursor. */
  def closeEpochBufferWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit = {
    val append = buffer.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    buffer.currentBucket = null
    closeAllAppendWindowBucketsWithCursor(parent, append)(onBucket)
  }

  /** Closes every epoch buffer bucket and drains records through a cursor. */
  def closeAllEpochBufferBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      buffer: EpochBuffer[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeEpochBufferWithCursor(parent, buffer)(onBucket)

  /** Drains one transaction-internal list without closing the transaction. */
  def drainTransactionListWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      list: TransactionList[T]^{parent}
  )(onCursor: StreamAppendCursor[T]^{parent} => Unit): Unit = {
    val tx = list.tx
    val cursor = tx.cursor.asInstanceOf[StreamAppendCursor[T]^{parent}]
    cursor.current = list.head.asInstanceOf[StreamAppendNode]
    list.head = null
    list.tail = null
    list.length0 = 0
    try onCursor(cursor)
    finally {
      while (cursor.hasNext) cursor.next()
      cursor.current = null
    }
  }

  /** Closes the active transaction child region after all list metadata is cleared. */
  def closeTransactionRegion(
      parent: StreamingRegion^,
      tx: TransactionRegion^{parent}
  ): Unit = {
    val child = tx.child
    if (child != null) {
      tx.child = null
      var i = 0
      while (i < tx.lists.length) {
        val list = tx.lists(i).asInstanceOf[TransactionList[StreamAppendNode]]
        if (list != null) {
          list.head = null
          list.tail = null
          list.length0 = 0
        }
        i += 1
      }
      tx.cursor.current = null
      closeChildBucket(parent, child.asInstanceOf[ChildBucket^{parent}]) {
        ()
      }
    }
  }

  /** Closes fixed-chunk append buckets fully before `cutoffSeconds`. */
  def closeChunkAppendBucketsBeforeWithCursor[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent},
      cutoffSeconds: Long
  )(onBucket: (
      StreamBucket^{parent},
      StreamChunkCursor[T]^{parent}
  ) => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      if (window.currentBucket.asInstanceOf[AnyRef] eq bucket.asInstanceOf[AnyRef])
        window.currentBucket = null
      consumeChunkAppendBucketWithCursor(parent, window, bucket, onBucket)
    }

  /** Closes every fixed-chunk append bucket. */
  def closeAllChunkAppendBucketsWithCursor[T <: Object](
      parent: StreamingRegion^,
      window: StreamChunkAppendWindow[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamChunkCursor[T]^{parent}
  ) => Unit): Unit = {
    window.currentBucket = null
    closeAllStreamBuckets(
      parent,
      window.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      consumeChunkAppendBucketWithCursor(parent, window, bucket, onBucket)
    }
  }

  /** Closes every join-window bucket and drains each bucket through a cursor. */
  def closeAllJoinWindowBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      join: StreamJoinWindow[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAllAppendWindowBucketsWithCursor(
      parent,
      join.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    )(onBucket)

  /** Closes every fold-window bucket and drains each bucket through a cursor. */
  def closeAllFoldBucketsWithCursor[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: StreamWindowFold[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit =
    closeAllAppendWindowBucketsWithCursor(
      parent,
      fold.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    )(onBucket)

  /** Closes the current epoch bucket and clears all fold metadata. */
  def closeEpochFoldCurrentBucketAndClear[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit = {
    val rawFold = fold.fold.asInstanceOf[StreamWindowFold[T]]
    val bucket = fold.currentBucket
    if (bucket != null) {
      fold.currentBucket = null
      clearFoldTable(rawFold)
      closeStreamBucketsBefore(
        parent,
        rawFold.append.buckets.asInstanceOf[StreamBucketArena^{parent}],
        bucket.startSeconds + rawFold.append.buckets.bucketSeconds
      ) { closed =>
        consumeAppendWindowBucketWithCursor(
          parent,
          rawFold.append.asInstanceOf[StreamAppendWindow[T]^{parent}],
          closed,
          onBucket
        )
      }
    }
  }

  /** Closes every epoch bucket and clears all fold metadata. */
  def closeAllEpochFoldBucketsAndClear[T <: StreamAppendNode](
      parent: StreamingRegion^,
      fold: EpochFold[T]^{parent}
  )(onBucket: (
      StreamBucket^{parent},
      StreamAppendCursor[T]^{parent}
  ) => Unit): Unit = {
    val rawFold = fold.fold.asInstanceOf[StreamWindowFold[T]]
    fold.currentBucket = null
    clearFoldTable(rawFold)
    closeAllAppendWindowBucketsWithCursor(
      parent,
      rawFold.append.asInstanceOf[StreamAppendWindow[T]^{parent}]
    )(onBucket)
  }

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
      null,
      null,
      null,
      keys,
      heapIndexByKey
    )
  }

  /** Allocates a checked dense-key indexed queue with four lexicographic
   *  priority components. Larger components rank first at each level.
   */
  def regionIndexedPriorityQueueLexicographic[T <: Object](
      keyCapacity: Int,
      initialCapacity: Int = 4
  )(using region: RiftRegion^): RegionIndexedPriorityQueue[T]^{region} = {
    if (keyCapacity <= 0)
      throw new IllegalArgumentException("keyCapacity must be positive")
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    val priorities: Array[Long] = alloc(new Array[Long](capacity))
    val priority2s: Array[Long] = alloc(new Array[Long](capacity))
    val priority3s: Array[Long] = alloc(new Array[Long](capacity))
    val priority4s: Array[Long] = alloc(new Array[Long](capacity))
    val keys: Array[Int] = alloc(new Array[Int](capacity))
    val heapIndexByKey: Array[Int] = alloc(new Array[Int](keyCapacity))
    new RegionIndexedPriorityQueue[T](
      items,
      priorities,
      priority2s,
      priority3s,
      priority4s,
      keys,
      heapIndexByKey
    )
  }

  private def longIndexedTableCapacity(
      initialCapacity: Int,
      initialTableCapacity: Int
  ): Int = {
    var requested = if (initialTableCapacity <= 0) 4 else initialTableCapacity
    val heapBased =
      if (initialCapacity > Int.MaxValue / 2) Int.MaxValue
      else initialCapacity * 2
    if (requested < heapBased) requested = heapBased
    if (requested < 4) requested = 4
    if (requested > (1 << 30))
      throw new IllegalArgumentException("initialTableCapacity is too large")

    var capacity = 4
    while (capacity < requested) capacity <<= 1
    capacity
  }

  /** Allocates a checked long-key indexed max-priority queue.
   *
   *  Use this when keys are already meaningful stream identifiers, such as a
   *  packed route id, and forcing them through a dense side table would add
   *  benchmark-specific plumbing.
   */
  def regionLongIndexedPriorityQueue[T <: Object](
      initialCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using region: RiftRegion^): RegionLongIndexedPriorityQueue[T]^{region} = {
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val tableCapacity =
      longIndexedTableCapacity(capacity, initialTableCapacity)
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    val priorities: Array[Long] = alloc(new Array[Long](capacity))
    val heapKeys: Array[Long] = alloc(new Array[Long](capacity))
    val tableKeys: Array[Long] = alloc(new Array[Long](tableCapacity))
    val tableStates: Array[Byte] = alloc(new Array[Byte](tableCapacity))
    val heapIndexPlusOneBySlot: Array[Int] =
      alloc(new Array[Int](tableCapacity))
    new RegionLongIndexedPriorityQueue[T](
      items,
      priorities,
      null,
      null,
      null,
      heapKeys,
      tableKeys,
      tableStates,
      heapIndexPlusOneBySlot
    )
  }

  /** Allocates a checked long-key indexed queue with four lexicographic
   *  priority components. Larger components rank first at each level.
   */
  def regionLongIndexedPriorityQueueLexicographic[T <: Object](
      initialCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using region: RiftRegion^): RegionLongIndexedPriorityQueue[T]^{region} = {
    val capacity = if (initialCapacity <= 0) 1 else initialCapacity
    val tableCapacity =
      longIndexedTableCapacity(capacity, initialTableCapacity)
    val items: Array[Object] =
      alloc(new Array[Object](capacity)).asInstanceOf[Array[Object]]
    val priorities: Array[Long] = alloc(new Array[Long](capacity))
    val priority2s: Array[Long] = alloc(new Array[Long](capacity))
    val priority3s: Array[Long] = alloc(new Array[Long](capacity))
    val priority4s: Array[Long] = alloc(new Array[Long](capacity))
    val heapKeys: Array[Long] = alloc(new Array[Long](capacity))
    val tableKeys: Array[Long] = alloc(new Array[Long](tableCapacity))
    val tableStates: Array[Byte] = alloc(new Array[Byte](tableCapacity))
    val heapIndexPlusOneBySlot: Array[Int] =
      alloc(new Array[Int](tableCapacity))
    new RegionLongIndexedPriorityQueue[T](
      items,
      priorities,
      priority2s,
      priority3s,
      priority4s,
      heapKeys,
      tableKeys,
      tableStates,
      heapIndexPlusOneBySlot
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

  /** Allocates a checked stream-window rank whose dense-key queue uses four
   *  lexicographic priority components.
   */
  def streamWindowIndexedRankLexicographic[T <: Object](
      bucketSeconds: Long,
      keyCapacity: Int,
      initialCapacity: Int = 4
  )(using parent: StreamingRegion^): StreamWindowIndexedRank[T]^{parent} = {
    val buckets = streamBucketArena(bucketSeconds)
    val queue =
      regionIndexedPriorityQueueLexicographic[T](keyCapacity, initialCapacity)
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

  /** Allocates a checked stream-window rank for arbitrary long keys. */
  def streamWindowLongIndexedRank[T <: Object](
      bucketSeconds: Long,
      initialCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using parent: StreamingRegion^): StreamWindowLongIndexedRank[T]^{parent} = {
    val buckets = streamBucketArena(bucketSeconds)
    val queue =
      regionLongIndexedPriorityQueue[T](initialCapacity, initialTableCapacity)
    val tableCapacity =
      longIndexedTableCapacity(
        if (initialCapacity <= 0) 1 else initialCapacity,
        initialTableCapacity
      )
    val ownerKeys = alloc(new Array[Long](tableCapacity))
    val ownerStates = alloc(new Array[Byte](tableCapacity))
    val ownerStartBySlot = alloc(new Array[Long](tableCapacity))
    val nextOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val previousOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    new StreamWindowLongIndexedRank[T](
      buckets.asInstanceOf[StreamBucketArena],
      queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]],
      ownerKeys,
      ownerStates,
      ownerStartBySlot,
      nextOwnedSlotPlusOneBySlot,
      previousOwnedSlotPlusOneBySlot
    ).asInstanceOf[StreamWindowLongIndexedRank[T]^{parent}]
  }

  /** Allocates a checked long-key stream-window rank with four lexicographic
   *  priority components.
   */
  def streamWindowLongIndexedRankLexicographic[T <: Object](
      bucketSeconds: Long,
      initialCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using parent: StreamingRegion^): StreamWindowLongIndexedRank[T]^{parent} = {
    val buckets = streamBucketArena(bucketSeconds)
    val queue =
      regionLongIndexedPriorityQueueLexicographic[T](
        initialCapacity,
        initialTableCapacity
      )
    val tableCapacity =
      longIndexedTableCapacity(
        if (initialCapacity <= 0) 1 else initialCapacity,
        initialTableCapacity
      )
    val ownerKeys = alloc(new Array[Long](tableCapacity))
    val ownerStates = alloc(new Array[Byte](tableCapacity))
    val ownerStartBySlot = alloc(new Array[Long](tableCapacity))
    val nextOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val previousOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    new StreamWindowLongIndexedRank[T](
      buckets.asInstanceOf[StreamBucketArena],
      queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]],
      ownerKeys,
      ownerStates,
      ownerStartBySlot,
      nextOwnedSlotPlusOneBySlot,
      previousOwnedSlotPlusOneBySlot
    ).asInstanceOf[StreamWindowLongIndexedRank[T]^{parent}]
  }

  /** Allocates an experimental fused stream-window rank for arbitrary long
   *  keys. The table owns lookup, rank heap positions, values, priorities, and
   *  bucket cleanup links.
   */
  def streamWindowTableRank[T <: Object](
      bucketSeconds: Long,
      initialRankCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using parent: StreamingRegion^): StreamWindowTableRank[T]^{parent} = {
    val heapCapacity =
      if (initialRankCapacity <= 0) 1 else initialRankCapacity
    val tableCapacity =
      longIndexedTableCapacity(heapCapacity, initialTableCapacity)
    val buckets = streamBucketArena(bucketSeconds)
    val keys = alloc(new Array[Long](tableCapacity))
    val states = alloc(new Array[Byte](tableCapacity))
    val items =
      alloc(new Array[Object](tableCapacity)).asInstanceOf[Array[Object]]
    val priorities = alloc(new Array[Long](tableCapacity))
    val heapSlots = alloc(new Array[Int](heapCapacity))
    val heapIndexPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val bucketStartBySlot = alloc(new Array[Long](tableCapacity))
    val nextOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val previousOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    new StreamWindowTableRank[T](
      buckets.asInstanceOf[StreamBucketArena],
      keys,
      states,
      items,
      priorities,
      null,
      null,
      null,
      heapSlots,
      heapIndexPlusOneBySlot,
      bucketStartBySlot,
      nextOwnedSlotPlusOneBySlot,
      previousOwnedSlotPlusOneBySlot
    ).asInstanceOf[StreamWindowTableRank[T]^{parent}]
  }

  /** Allocates an experimental fused stream-window rank whose table stores
   *  four lexicographic priority components.
   */
  def streamWindowTableRankLexicographic[T <: Object](
      bucketSeconds: Long,
      initialRankCapacity: Int = 4,
      initialTableCapacity: Int = 16
  )(using parent: StreamingRegion^): StreamWindowTableRank[T]^{parent} = {
    val heapCapacity =
      if (initialRankCapacity <= 0) 1 else initialRankCapacity
    val tableCapacity =
      longIndexedTableCapacity(heapCapacity, initialTableCapacity)
    val buckets = streamBucketArena(bucketSeconds)
    val keys = alloc(new Array[Long](tableCapacity))
    val states = alloc(new Array[Byte](tableCapacity))
    val items =
      alloc(new Array[Object](tableCapacity)).asInstanceOf[Array[Object]]
    val priorities = alloc(new Array[Long](tableCapacity))
    val priority2s = alloc(new Array[Long](tableCapacity))
    val priority3s = alloc(new Array[Long](tableCapacity))
    val priority4s = alloc(new Array[Long](tableCapacity))
    val heapSlots = alloc(new Array[Int](heapCapacity))
    val heapIndexPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val bucketStartBySlot = alloc(new Array[Long](tableCapacity))
    val nextOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    val previousOwnedSlotPlusOneBySlot = alloc(new Array[Int](tableCapacity))
    new StreamWindowTableRank[T](
      buckets.asInstanceOf[StreamBucketArena],
      keys,
      states,
      items,
      priorities,
      priority2s,
      priority3s,
      priority4s,
      heapSlots,
      heapIndexPlusOneBySlot,
      bucketStartBySlot,
      nextOwnedSlotPlusOneBySlot,
      previousOwnedSlotPlusOneBySlot
    ).asInstanceOf[StreamWindowTableRank[T]^{parent}]
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

  /** Inserts or replaces `value` with four lexicographic priority components.
   *  Larger components rank first at each tie-break level.
   */
  def put[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int,
      value: T^{owner},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit =
    queue.putTrusted(
      owner,
      key,
      value.asInstanceOf[Object],
      priority1,
      priority2,
      priority3,
      priority4
    )

  /** Updates `key`'s priority if it is present. */
  def updatePriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int,
      priority: Long
  ): Boolean =
    queue.updatePriorityTrusted(key, priority)

  /** Updates `key`'s lexicographic priority if it is present. */
  def updatePriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionIndexedPriorityQueue[T]^{owner},
      key: Int,
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Boolean =
    queue.updatePriorityTrusted(
      key,
      priority1,
      priority2,
      priority3,
      priority4
    )

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

  /** Inserts or replaces `value` for a long key in an indexed queue. */
  def put[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long,
      value: T^{owner},
      priority: Long
  ): Unit =
    queue.putTrusted(owner, key, value.asInstanceOf[Object], priority)

  /** Inserts or replaces `value` with four lexicographic priority components.
   *  Larger components rank first at each tie-break level.
   */
  def put[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long,
      value: T^{owner},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit =
    queue.putTrusted(
      owner,
      key,
      value.asInstanceOf[Object],
      priority1,
      priority2,
      priority3,
      priority4
    )

  /** Updates `key`'s priority if it is present. */
  def updatePriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long,
      priority: Long
  ): Boolean =
    queue.updatePriorityTrusted(key, priority)

  /** Updates `key`'s lexicographic priority if it is present. */
  def updatePriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long,
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Boolean =
    queue.updatePriorityTrusted(
      key,
      priority1,
      priority2,
      priority3,
      priority4
    )

  /** Removes `key` if it is present. */
  def remove[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long
  ): Boolean =
    queue.removeTrusted(key)

  /** Returns true when `key` is present. */
  def contains[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long
  ): Boolean =
    queue.containsTrusted(key)

  /** Reads the value for `key`. */
  def get[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner},
      key: Long
  ): T^{owner} =
    queue.getTrusted(key).asInstanceOf[T^{owner}]

  /** Reads the highest-priority long-key indexed value without removing it. */
  def peek[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.peekTrusted().asInstanceOf[T^{owner}]

  /** Reads the long key of the highest-priority indexed value. */
  def peekKey[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): Long =
    queue.peekKeyTrusted()

  /** Reads the highest indexed priority without removing its value. */
  def peekPriority[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): Long =
    queue.peekPriorityTrusted()

  /** Removes and returns the highest-priority indexed value. */
  def pop[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): T^{owner} =
    queue.popTrusted().asInstanceOf[T^{owner}]

  /** Returns the number of elements in a long-key indexed priority queue. */
  def length[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.length

  /** Returns the current heap backing capacity of a long-key indexed queue. */
  def capacity[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.capacity

  /** Returns the hash-table capacity of a long-key indexed queue. */
  def tableCapacity[T <: Object](
      owner: RiftRegion^,
      queue: RegionLongIndexedPriorityQueue[T]^{owner}
  ): Int =
    queue.tableCapacity

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

  /** Inserts or replaces a ranked value using four lexicographic priorities. */
  def putWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int,
      value: T^{parent},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit =
    rank.queue
      .asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
      .putTrusted(
        parent,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )

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

  /** Inserts or replaces a ranked value owned by `bucket` using four
   *  lexicographic priority components.
   */
  def putWindowRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Int,
      value: T^{parent},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.queue
      .asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}]
      .putTrusted(
        parent,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )
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

  /** Updates `key`'s lexicographic priority if it is present. */
  def updateWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      key: Int,
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Boolean =
    updatePriority(
      parent,
      rank.queue.asInstanceOf[RegionIndexedPriorityQueue[T]^{parent}],
      key,
      priority1,
      priority2,
      priority3,
      priority4
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

  /** Closes window-rank buckets and reports each removed ranked entry.
   *
   *  This lets stream operators clean parent-side indexes while the framework
   *  unlinks bucket-owned rank entries, avoiding a second per-bucket key list.
   *  `cleanupEntry` runs after the key is removed from parent-owned rank state
   *  but before the child bucket closes.
   */
  def closeWindowRankBucketsBeforeWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent},
      cutoffSeconds: Long
  )(
      cleanupEntry: (StreamBucket^{parent}, Int, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
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

  /** Closes every window-rank bucket and reports each removed ranked entry. */
  def closeAllWindowRankBucketsWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowIndexedRank[T]^{parent}
  )(
      cleanupEntry: (StreamBucket^{parent}, Int, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
    }

  /** Finds or opens the long-key window-rank bucket containing timestamp. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamWindowBucketFor(parent, rank, timestampSeconds)(_ => ())

  /** Finds or opens the long-key window-rank bucket containing timestamp. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamBucketFor(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )(onOpen)

  /** Inserts or replaces a long-key ranked value for `key`. */
  def putWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long,
      value: T^{parent},
      priority: Long
  ): Unit =
    rank.queue
      .asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
      .putTrusted(parent, key, value.asInstanceOf[Object], priority)

  /** Inserts or replaces a long-key ranked value using lexicographic
   *  priorities.
   */
  def putWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long,
      value: T^{parent},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit =
    rank.queue
      .asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
      .putTrusted(
        parent,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )

  /** Inserts or replaces a long-key ranked value owned by `bucket`. */
  def putWindowRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Long,
      value: T^{parent},
      priority: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.queue
      .asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
      .putTrusted(parent, key, value.asInstanceOf[Object], priority)
    rank.linkOwnedKey(parent, key, bucket.asInstanceOf[StreamBucket])
  }

  /** Inserts or replaces a long-key ranked value owned by `bucket` using
   *  lexicographic priorities.
   */
  def putWindowRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Long,
      value: T^{parent},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.queue
      .asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
      .putTrusted(
        parent,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )
    rank.linkOwnedKey(parent, key, bucket.asInstanceOf[StreamBucket])
  }

  /** Updates a long-key rank priority if the key is present. */
  def updateWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long,
      priority: Long
  ): Boolean =
    updatePriority(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}],
      key,
      priority
    )

  /** Updates a long-key lexicographic rank priority if the key is present. */
  def updateWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long,
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Boolean =
    updatePriority(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}],
      key,
      priority1,
      priority2,
      priority3,
      priority4
    )

  /** Removes a long key if it is present. */
  def removeWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long
  ): Boolean = {
    val removed = remove(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}],
      key
    )
    if (removed) rank.unlinkOwnedKey(key)
    removed
  }

  /** Returns true when a long key is present. */
  def containsWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long
  ): Boolean =
    contains(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}],
      key
    )

  /** Reads the ranked value for a long key. */
  def getWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      key: Long
  ): T^{parent} =
    get(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}],
      key
    )

  /** Reads the highest-priority long-key ranked value without removing it. */
  def peekWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  ): T^{parent} =
    peek(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
    )

  /** Reads the long key of the highest-priority ranked value. */
  def peekWindowRankKey[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  ): Long =
    peekKey(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
    )

  /** Reads the highest priority without removing the ranked value. */
  def peekWindowRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  ): Long =
    peekPriority(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
    )

  /** Removes and returns the highest-priority long-key ranked value. */
  def popWindowRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  ): T^{parent} =
    pop(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
    )

  /** Returns the number of long-key ranked values. */
  def windowRankLength[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  ): Int =
    length(
      parent,
      rank.queue.asInstanceOf[RegionLongIndexedPriorityQueue[T]^{parent}]
    )

  /** Returns true if closing before `cutoffSeconds` would close a bucket. */
  def hasWindowRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      cutoffSeconds: Long
  ): Boolean =
    hasStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    )

  /** Closes long-key window-rank buckets fully before `cutoffSeconds`. */
  def closeWindowRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
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

  /** Closes long-key window-rank buckets and reports removed entries. */
  def closeWindowRankBucketsBeforeWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent},
      cutoffSeconds: Long
  )(
      cleanupEntry: (StreamBucket^{parent}, Long, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
    }

  /** Closes every long-key window-rank bucket. */
  def closeAllWindowRankBuckets[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket])
      cleanup(bucket)
    }

  /** Closes every long-key window-rank bucket and reports removed entries. */
  def closeAllWindowRankBucketsWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowLongIndexedRank[T]^{parent}
  )(
      cleanupEntry: (StreamBucket^{parent}, Long, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
    }

  /** Finds or opens the fused table-rank bucket containing timestamp. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      timestampSeconds: Long
  ): StreamBucket^{parent} =
    streamWindowBucketFor(parent, rank, timestampSeconds)(_ => ())

  /** Finds or opens the fused table-rank bucket containing timestamp. */
  def streamWindowBucketFor[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      timestampSeconds: Long
  )(onOpen: StreamBucket^{parent} => Unit): StreamBucket^{parent} =
    streamBucketFor(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      timestampSeconds
    )(onOpen)

  /** Inserts or replaces a fused table-rank value owned by `bucket`. */
  def putTableRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Long,
      value: T^{parent},
      priority: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.putTrusted(
      parent,
      bucket.asInstanceOf[StreamBucket],
      key,
      value.asInstanceOf[Object],
      priority
    )
  }

  /** Inserts or replaces a fused table-rank value using lexicographic
   *  priorities.
   */
  def putTableRankInBucket[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      bucket: StreamBucket^{parent},
      key: Long,
      value: T^{parent},
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Unit = {
    bucket.child.checkOpen()
    rank.putTrusted(
      parent,
      bucket.asInstanceOf[StreamBucket],
      key,
      value.asInstanceOf[Object],
      priority1,
      priority2,
      priority3,
      priority4
    )
  }

  /** Updates a fused table-rank priority if the key is present. */
  def updateTableRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      key: Long,
      priority: Long
  ): Boolean =
    rank.updatePriorityTrusted(key, priority)

  /** Updates a fused table-rank lexicographic priority if the key is present. */
  def updateTableRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      key: Long,
      priority1: Long,
      priority2: Long,
      priority3: Long,
      priority4: Long
  ): Boolean =
    rank.updatePriorityTrusted(key, priority1, priority2, priority3, priority4)

  /** Removes a fused table-rank key if it is present. */
  def removeTableRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      key: Long
  ): Boolean =
    rank.removeTrusted(key)

  /** Returns true when a table-rank key is present. */
  def containsTableRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      key: Long
  ): Boolean =
    rank.containsTrusted(key)

  /** Reads the ranked value for a table-rank key. */
  def getTableRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      key: Long
  ): T^{parent} =
    rank.getTrusted(key).asInstanceOf[T^{parent}]

  /** Reads the highest-priority table-ranked value without removing it. */
  def peekTableRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): T^{parent} =
    rank.peekTrusted().asInstanceOf[T^{parent}]

  /** Reads the key of the highest-priority table-ranked value. */
  def peekTableRankKey[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): Long =
    rank.peekKeyTrusted()

  /** Reads the highest table-rank priority without removing the value. */
  def peekTableRankPriority[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): Long =
    rank.peekPriorityTrusted()

  /** Removes and returns the highest-priority table-ranked value. */
  def popTableRank[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): T^{parent} =
    rank.popTrusted().asInstanceOf[T^{parent}]

  /** Copies the best table-ranked values into `result` without mutating rank. */
  def copyTableRankTopK[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      result: Array[T^{parent}]^{parent},
      candidateHeap: Array[Int]^{parent},
      max: Int
  ): Int =
    rank.copyTopKTrusted(
      result.asInstanceOf[Array[Object]],
      candidateHeap.asInstanceOf[Array[Int]],
      max
    )

  /** Enables or disables opt-in diagnostics for a fused table-rank. */
  def setTableRankDiagnosticsEnabled[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      enabled: Boolean
  ): Unit =
    rank.setDiagnosticsEnabled(enabled)

  /** Clears opt-in diagnostics counters for a fused table-rank. */
  def resetTableRankDiagnostics[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): Unit =
    rank.resetDiagnostics()

  /** Returns a compact diagnostics summary for a fused table-rank. */
  def tableRankDiagnostics[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): String =
    rank.diagnosticSummaryTrusted()

  /** Returns the number of table-ranked values. */
  def tableRankLength[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  ): Int =
    rank.length

  /** Returns true if closing before `cutoffSeconds` would close a bucket. */
  def hasTableRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      cutoffSeconds: Long
  ): Boolean =
    hasStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    )

  /** Closes fused table-rank buckets fully before `cutoffSeconds`. */
  def closeTableRankBucketsBefore[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
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

  /** Closes fused table-rank buckets and reports removed entries. */
  def closeTableRankBucketsBeforeWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent},
      cutoffSeconds: Long
  )(
      cleanupEntry: (StreamBucket^{parent}, Long, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeStreamBucketsBefore(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}],
      cutoffSeconds
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
    }

  /** Closes every fused table-rank bucket. */
  def closeAllTableRankBuckets[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  )(cleanup: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket])
      cleanup(bucket)
    }

  /** Closes every fused table-rank bucket and reports removed entries. */
  def closeAllTableRankBucketsWithEntries[T <: Object](
      parent: StreamingRegion^,
      rank: StreamWindowTableRank[T]^{parent}
  )(
      cleanupEntry: (StreamBucket^{parent}, Long, T^{parent}) => Unit
  )(cleanupBucket: StreamBucket^{parent} => Unit): Unit =
    closeAllStreamBuckets(
      parent,
      rank.buckets.asInstanceOf[StreamBucketArena^{parent}]
    ) { bucket =>
      rank.removeOwnedKeysForBucket(bucket.asInstanceOf[StreamBucket], {
        (key, value) =>
          cleanupEntry(bucket, key, value.asInstanceOf[T^{parent}])
      })
      cleanupBucket(bucket)
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

    @targetName("putLexicographicToRegionIndexedPriorityQueue")
    def put[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int,
        value: T^{owner},
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit =
      queue.putTrusted(
        owner,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )

    @targetName("updateRegionIndexedPriorityQueuePriority")
    def updatePriority[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int,
        priority: Long
    ): Boolean =
      RiftRegion.updatePriority(owner, queue, key, priority)

    @targetName("updateRegionIndexedPriorityQueueLexicographicPriority")
    def updatePriority[T <: Object](
        queue: RegionIndexedPriorityQueue[T]^{owner},
        key: Int,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Boolean =
      RiftRegion.updatePriority(
        owner,
        queue,
        key,
        priority1,
        priority2,
        priority3,
        priority4
      )

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

    @targetName("putToRegionLongIndexedPriorityQueue")
    def put[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long,
        value: T^{owner},
        priority: Long
    ): Unit =
      queue.putTrusted(owner, key, value.asInstanceOf[Object], priority)

    @targetName("putLexicographicToRegionLongIndexedPriorityQueue")
    def put[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long,
        value: T^{owner},
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Unit =
      queue.putTrusted(
        owner,
        key,
        value.asInstanceOf[Object],
        priority1,
        priority2,
        priority3,
        priority4
      )

    @targetName("updateRegionLongIndexedPriorityQueuePriority")
    def updatePriority[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long,
        priority: Long
    ): Boolean =
      RiftRegion.updatePriority(owner, queue, key, priority)

    @targetName("updateRegionLongIndexedPriorityQueueLexicographicPriority")
    def updatePriority[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long,
        priority1: Long,
        priority2: Long,
        priority3: Long,
        priority4: Long
    ): Boolean =
      RiftRegion.updatePriority(
        owner,
        queue,
        key,
        priority1,
        priority2,
        priority3,
        priority4
      )

    @targetName("removeFromRegionLongIndexedPriorityQueue")
    def remove[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long
    ): Boolean =
      RiftRegion.remove(owner, queue, key)

    @targetName("containsInRegionLongIndexedPriorityQueue")
    def contains[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long
    ): Boolean =
      RiftRegion.contains(owner, queue, key)

    @targetName("getFromRegionLongIndexedPriorityQueue")
    def get[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner},
        key: Long
    ): T^{owner} =
      RiftRegion.get(owner, queue, key)

    @targetName("peekFromRegionLongIndexedPriorityQueue")
    def peek[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.peek(owner, queue)

    @targetName("peekKeyFromRegionLongIndexedPriorityQueue")
    def peekKey[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): Long =
      RiftRegion.peekKey(owner, queue)

    @targetName("peekPriorityFromRegionLongIndexedPriorityQueue")
    def peekPriority[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): Long =
      RiftRegion.peekPriority(owner, queue)

    @targetName("popFromRegionLongIndexedPriorityQueue")
    def pop[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): T^{owner} =
      RiftRegion.pop(owner, queue)

    @targetName("regionLongIndexedPriorityQueueLength")
    def length[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.length(owner, queue)

    @targetName("regionLongIndexedPriorityQueueCapacity")
    def capacity[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.capacity(owner, queue)

    @targetName("regionLongIndexedPriorityQueueTableCapacity")
    def tableCapacity[T <: Object](
        queue: RegionLongIndexedPriorityQueue[T]^{owner}
    ): Int =
      RiftRegion.tableCapacity(owner, queue)

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

  private def openSafeZoneImpl(kind: Int): RiftRegion = {
    val handle = SafeZoneAllocator.Impl.open()
    if (handle == null)
      throw new OutOfMemoryError("failed to open SafeZone-backed Rift region")
    kind match {
      case Streaming => new MemorySafeZoneBackedStreamingRiftRegion(handle)
      case _         => new MemorySafeZoneBackedRiftRegion(handle)
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

    private[scalanative] override def allocImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr = {
      checkOpen()
      RiftAllocator.Impl.alloc(handle, cls, size)
    }

    private[scalanative] override def allocUncheckedImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr =
      RiftAllocator.Impl.alloc(handle, cls, size)

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
      with OpenStreamingRegion {
    private[scalanative] override def allocUncheckedImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr =
      RiftAllocator.Impl.alloc(handle, cls, size)
  }

  private class MemorySafeZoneBackedRiftRegion(
      private[scalanative] override val handle: RawPtr)
      extends RiftRegion
      with SafeZoneBackedRiftRegion {
    private var flagIsOpen = true
    private var heapRoots: List[RiftRegion.HeapRoot[AnyRef]] = Nil

    override def isOpen: Boolean = flagIsOpen

    override def checkOpen(): Unit =
      if (!flagIsOpen)
        throw new IllegalStateException(
          "SafeZone-backed Rift region is already closed."
        )

    override def alloc(size: CSize, align: CSize): Ptr[Byte] = {
      checkOpen()
      throw new UnsupportedOperationException(
        "SafeZone-backed checked Rift regions do not support raw allocation"
      )
    }

    private[scalanative] override def allocImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr = {
      checkOpen()
      SafeZoneAllocator.Impl.alloc(handle, cls, size)
    }

    private[scalanative] override def allocUncheckedImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr =
      SafeZoneAllocator.Impl.alloc(handle, cls, size)

    private[memory] override def retainHeapRoot[T <: AnyRef](
        value: T
    ): RiftRegion.HeapRoot[T] = {
      checkOpen()
      val root = new RiftRegion.HeapRoot(value)
      heapRoots = root.asInstanceOf[RiftRegion.HeapRoot[AnyRef]] :: heapRoots
      root
    }

    private[memory] override def setDiagnosticFamily(family: Int): Unit =
      checkOpen()

    override def reset(): Unit = {
      checkOpen()
      throw new UnsupportedOperationException(
        "SafeZone-backed checked Rift regions do not support reset"
      )
    }

    override def close(): Unit = {
      checkOpen()
      flagIsOpen = false
      heapRoots = Nil
      SafeZoneAllocator.Impl.close(handle)
    }
  }

  private final class MemorySafeZoneBackedStreamingRiftRegion(handle: RawPtr)
      extends MemorySafeZoneBackedRiftRegion(handle)
      with StreamingRegion
      with OpenStreamingRegion {
    private[scalanative] override def allocUncheckedImpl(
        cls: RawPtr,
        size: RawSize
    ): RawPtr =
      SafeZoneAllocator.Impl.alloc(handle, cls, size)
  }

  private sealed trait SafeZoneBackedRiftRegion extends RiftRegion
}
