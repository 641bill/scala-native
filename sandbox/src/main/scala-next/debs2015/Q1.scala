package debs2015

import scala.language.experimental.captureChecking

import scala.collection.mutable
import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.SafeZoneAllocator

final case class Route(start: Cell, end: Cell) {
  def id: String = s"${start.id}->${end.id}"
}

final case class RankedRoute(
    route: Route,
    var count: Int,
    var latestSeconds: Long,
    var latestSeq: Long
)

trait Q1Engine {
  def process(trip: Trip): Array[RankedRoute]
  def close(): Unit = ()
}

final class Q1Heap
    extends Q1BucketedWindow(DebsAllocator.Heap, RiftRegion.HPZone)

final class Q1RiftBuckets(kind: Int)
    extends Q1BucketedWindow(DebsAllocator.Rift, kind)

final class Q1SafeZone
    extends Q1BucketedWindow(DebsAllocator.SafeZoneKind, RiftRegion.HPZone)

private abstract class Q1BucketedWindow(
    allocatorKind: Int,
    riftKind: Int
) extends Q1Engine {
  import Q1Support._

  private val buckets = mutable.Queue.empty[Bucket]
  private val routes = new RouteCounter(openAllocator())
  private var currentBucket: Bucket = null
  private var nextSeq = 0L

  override def process(trip: Trip): Array[RankedRoute] = {
    evictBefore(trip.dropoffSeconds - WindowSeconds)

    val startKey =
      Grid.Q1.cellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude)
    if (startKey != 0) {
      val endKey =
        Grid.Q1.cellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude)
      if (endKey != 0) {
        val key = routeKey(startKey, endKey)
        val seq = nextSeq
        nextSeq += 1L
        val bucket = bucketFor(trip.dropoffSeconds)
        bucket.head = allocateEntry(bucket, key)
        routes.increment(key, trip.dropoffSeconds, seq)
      }
    }

    routes.top10()
  }

  override def close(): Unit = {
    while (buckets.nonEmpty) {
      val bucket = buckets.dequeue()
      closeBucket(bucket)
    }
    routes.close()
    currentBucket = null
  }

  private def bucketFor(dropoffSeconds: Long): Bucket = {
    val startSeconds = dropoffSeconds
    if (currentBucket != null && currentBucket.startSeconds == startSeconds)
      currentBucket
    else {
      val allocator = openAllocator()
      val bucket = new Bucket(startSeconds, allocator, null)
      buckets.enqueue(bucket)
      currentBucket = bucket
      bucket
    }
  }

  private def openAllocator(): DebsAllocator =
    DebsAllocator.open(allocatorKind, riftKind)

  private def evictBefore(cutoffSeconds: Long): Unit = {
    while (buckets.nonEmpty && buckets.front.startSeconds < cutoffSeconds) {
      val bucket = buckets.dequeue()
      var entry = bucket.head
      while (entry != null) {
        routes.decrement(entry.routeKey)
        entry = entry.next
      }
      if (currentBucket eq bucket) currentBucket = null
      closeBucket(bucket)
    }
  }

  private def allocateEntry(bucket: Bucket, routeKey: Long): BucketWindowEntry =
    bucket.allocator.kind match {
      case DebsAllocator.Rift =>
        bucket.allocator.riftRegion.alloc(
          new BucketWindowEntry(routeKey, bucket.head)
        )
      case DebsAllocator.SafeZoneKind =>
        SafeZoneAllocator
          .allocate(bucket.allocator.safeZone,
                    new BucketWindowEntry(routeKey, bucket.head))
          .asInstanceOf[BucketWindowEntry]
      case _ =>
        new BucketWindowEntry(routeKey, bucket.head)
    }

  private def closeBucket(bucket: Bucket): Unit =
    bucket.allocator.close()

  private final class Bucket(
      val startSeconds: Long,
      val allocator: DebsAllocator,
      var head: BucketWindowEntry
  )

  private final class BucketWindowEntry(
      val routeKey: Long,
      val next: BucketWindowEntry
  )
}

object Q1Support {
  private[debs2015] val WindowSeconds = 30L * 60L
  private val RoutePartBits = 10
  private val RoutePartMask = (1L << RoutePartBits) - 1L
  private val EmptyRouteKey = 0L
  private val DeletedRouteKey = -1L
  private val InitialRouteTableCapacity = 1024
  private val InitialRouteRankCapacity = 1024
  private val TopCandidateCapacity = 24

  private[debs2015] final case class RouteState(
      count: Int,
      latestSeconds: Long,
      latestSeq: Long
  )

  private[debs2015] def routeKey(start: Cell, end: Cell): Long =
    (start.east.toLong << 30) |
      (start.south.toLong << 20) |
      (end.east.toLong << 10) |
      end.south.toLong

  private[debs2015] def routeKey(startKey: Int, endKey: Int): Long =
    (startKey.toLong << (RoutePartBits * 2)) | endKey.toLong

  private[debs2015] def routeFromKey(key: Long): Route =
    Route(
      Cell(
        ((key >>> 30) & RoutePartMask).toInt,
        ((key >>> 20) & RoutePartMask).toInt
      ),
      Cell(
        ((key >>> 10) & RoutePartMask).toInt,
        (key & RoutePartMask).toInt
      )
    )

  private def hash(key: Long): Int = {
    var x = key
    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    x *= 0xc4ceb9fe1a85ec53L
    x ^= x >>> 33
    x.toInt
  }

  private[debs2015] final class RouteCounter(
      rankAllocator: DebsAllocator
  ) {
    private var keys = allocateLongArray(InitialRouteTableCapacity)
    private var counts = allocateIntArray(InitialRouteTableCapacity)
    private var latestSecondsBySlot = allocateLongArray(InitialRouteTableCapacity)
    private var latestSeqBySlot = allocateLongArray(InitialRouteTableCapacity)
    private var rankBySlot = allocateRankArray(InitialRouteTableCapacity)
    private var rankIndexBySlot = allocateIntArray(InitialRouteTableCapacity)
    private var heapRanks = allocateRankArray(InitialRouteRankCapacity)
    private var heapSlots = allocateIntArray(InitialRouteRankCapacity)
    private val topCandidateHeap = allocateIntArray(TopCandidateCapacity)
    private val resultArrays = new Array[Array[RankedRoute]](11)
    private var activeSize = 0
    private var usedSize = 0
    private var heapSize = 0

    def increment(key: Long, latestSeconds: Long, latestSeq: Long): Unit = {
      val slot = insertSlot(key)
      if (keys(slot) == key) {
        counts(slot) += 1
        latestSecondsBySlot(slot) = latestSeconds
        latestSeqBySlot(slot) = latestSeq
        updateRank(slot)
      } else {
        if (keys(slot) == EmptyRouteKey) usedSize += 1
        keys(slot) = key
        counts(slot) = 1
        latestSecondsBySlot(slot) = latestSeconds
        latestSeqBySlot(slot) = latestSeq
        activeSize += 1
        updateRank(slot)
      }
    }

    def decrement(key: Long): Unit = {
      val slot = existingSlot(key)
      if (slot >= 0) {
        val ranked = rankBySlot(slot)
        val nextCount = counts(slot) - 1
        if (nextCount <= 0) {
          if (ranked != null)
            removeRankHeap(slot)
          deleteSlot(slot)
        } else {
          counts(slot) = nextCount
          if (ranked != null) {
            ranked.count = nextCount
            fixRankHeap(slot)
          }
        }
      }
    }

    def top10(): Array[RankedRoute] = {
      Debs2015Counters.recordQ1Top10()
      val size = math.min(10, heapSize)
      val result = resultArray(size)
      if (size == 0) return result

      var candidateCount = 1
      topCandidateHeap(0) = 0
      var i = 0
      while (i < size) {
        val candidateSlot = bestCandidate(candidateCount)
        val heapPosition = topCandidateHeap(candidateSlot)
        candidateCount -= 1
        topCandidateHeap(candidateSlot) = topCandidateHeap(candidateCount)

        result(i) = heapRanks(heapPosition)

        val left = (heapPosition << 1) + 1
        if (left < heapSize) {
          topCandidateHeap(candidateCount) = left
          candidateCount += 1
        }
        val right = left + 1
        if (right < heapSize) {
          topCandidateHeap(candidateCount) = right
          candidateCount += 1
        }
        i += 1
      }
      result
    }

    def close(): Unit = {
      clearRankIndex()
      clearTables()
      var i = 0
      while (i < resultArrays.length) {
        resultArrays(i) = null
        i += 1
      }
      rankAllocator.close()
    }

    private def updateRank(slot: Int): Unit = {
      val existing = rankBySlot(slot)

      val ranked =
        if (existing != null) {
          existing.count = counts(slot)
          existing.latestSeconds = latestSecondsBySlot(slot)
          existing.latestSeq = latestSeqBySlot(slot)
          existing
        } else {
          val created =
            allocateRankedRoute(
              keys(slot),
              counts(slot),
              latestSecondsBySlot(slot),
              latestSeqBySlot(slot)
            )
          rankBySlot(slot) = created
          addRankHeap(slot, created)
          created
        }
      if (existing != null)
        fixRankHeap(slot)
    }

    private def allocateRankedRoute(
        key: Long,
        count: Int,
        latestSeconds: Long,
        latestSeq: Long
    ): RankedRoute = {
      val route = allocateRoute(key)
      val ranked =
        rankAllocator.kind match {
          case DebsAllocator.Rift =>
            rankAllocator.riftRegion.alloc(
              new RankedRoute(route, count, latestSeconds, latestSeq)
            )
          case DebsAllocator.SafeZoneKind =>
            SafeZoneAllocator
              .allocate(
                rankAllocator.safeZone,
                new RankedRoute(route, count, latestSeconds, latestSeq)
              )
              .asInstanceOf[RankedRoute]
          case _ =>
            new RankedRoute(route, count, latestSeconds, latestSeq)
        }
      Debs2015Counters.recordQ1RankCreated()
      ranked
    }

    private def allocateRoute(key: Long): Route = {
      val startEast = ((key >>> 30) & RoutePartMask).toInt
      val startSouth = ((key >>> 20) & RoutePartMask).toInt
      val endEast = ((key >>> 10) & RoutePartMask).toInt
      val endSouth = (key & RoutePartMask).toInt

      rankAllocator.kind match {
        case DebsAllocator.Rift =>
          val start =
            rankAllocator.riftRegion.alloc(new Cell(startEast, startSouth))
          val end =
            rankAllocator.riftRegion.alloc(new Cell(endEast, endSouth))
          rankAllocator.riftRegion.alloc(new Route(start, end))
        case DebsAllocator.SafeZoneKind =>
          val start =
            SafeZoneAllocator
              .allocate(rankAllocator.safeZone,
                        new Cell(startEast, startSouth))
              .asInstanceOf[Cell]
          val end =
            SafeZoneAllocator
              .allocate(rankAllocator.safeZone, new Cell(endEast, endSouth))
              .asInstanceOf[Cell]
          SafeZoneAllocator
            .allocate(rankAllocator.safeZone, new Route(start, end))
            .asInstanceOf[Route]
        case _ =>
          Route(Cell(startEast, startSouth), Cell(endEast, endSouth))
      }
    }

    private def resultArray(size: Int): Array[RankedRoute] = {
      if (size == 0) Array.empty[RankedRoute]
      else {
        var result = resultArrays(size)
        if (result == null) {
          result = allocateRankResultArray(size)
          resultArrays(size) = result
          Debs2015Counters.recordQ1ResultArrayAlloc(size)
        }
        result
      }
    }

    private def allocateLongArray(size: Int): Array[Long] =
      rankAllocator.kind match {
        case DebsAllocator.Rift =>
          rankAllocator.riftRegion.alloc(new Array[Long](size))
        case DebsAllocator.SafeZoneKind =>
          SafeZoneAllocator
            .allocate(rankAllocator.safeZone, new Array[Long](size))
            .asInstanceOf[Array[Long]]
        case _ =>
          new Array[Long](size)
      }

    private def allocateIntArray(size: Int): Array[Int] =
      rankAllocator.kind match {
        case DebsAllocator.Rift =>
          rankAllocator.riftRegion.alloc(new Array[Int](size))
        case DebsAllocator.SafeZoneKind =>
          SafeZoneAllocator
            .allocate(rankAllocator.safeZone, new Array[Int](size))
            .asInstanceOf[Array[Int]]
        case _ =>
          new Array[Int](size)
      }

    private def allocateRankArray(size: Int): Array[RankedRoute] =
      rankAllocator.kind match {
        case DebsAllocator.Rift =>
          rankAllocator.riftRegion.alloc(new Array[RankedRoute](size))
        case DebsAllocator.SafeZoneKind =>
          SafeZoneAllocator
            .allocate(rankAllocator.safeZone, new Array[RankedRoute](size))
            .asInstanceOf[Array[RankedRoute]]
        case _ =>
          new Array[RankedRoute](size)
      }

    private def allocateRankResultArray(size: Int): Array[RankedRoute] =
      allocateRankArray(size)

    private def addRankHeap(slot: Int, ranked: RankedRoute): Unit = {
      ensureRankCapacity(heapSize + 1)
      val index = heapSize
      heapSize += 1
      heapRanks(index) = ranked
      heapSlots(index) = slot
      rankIndexBySlot(slot) = index + 1
      siftRankUp(index)
      Debs2015Counters.recordQ1RankAdd()
    }

    private def removeRankHeap(slot: Int): Unit = {
      val index = rankHeapIndex(slot)
      if (index < 0) return

      val last = heapSize - 1
      rankIndexBySlot(slot) = 0
      if (index != last) {
        heapRanks(index) = heapRanks(last)
        heapSlots(index) = heapSlots(last)
        rankIndexBySlot(heapSlots(index)) = index + 1
      }
      heapRanks(last) = null
      heapSlots(last) = 0
      heapSize = last
      if (index < heapSize)
        fixRankHeapAt(index)
      Debs2015Counters.recordQ1RankRemove()
    }

    private def fixRankHeap(slot: Int): Unit = {
      val index = rankHeapIndex(slot)
      if (index >= 0)
        fixRankHeapAt(index)
    }

    private def fixRankHeapAt(index: Int): Unit = {
      val moved = siftRankUp(index)
      siftRankDown(moved)
    }

    private def siftRankUp(start: Int): Int = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (!betterHeapIndex(child, parent)) return child
        swapRankHeap(child, parent)
        child = parent
      }
      child
    }

    private def siftRankDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= heapSize) return
        val right = left + 1
        var best = left
        if (right < heapSize && betterHeapIndex(right, left))
          best = right
        if (!betterHeapIndex(best, parent)) return
        swapRankHeap(parent, best)
        parent = best
      }
    }

    private def swapRankHeap(left: Int, right: Int): Unit = {
      val leftRank = heapRanks(left)
      val leftSlot = heapSlots(left)
      heapRanks(left) = heapRanks(right)
      heapSlots(left) = heapSlots(right)
      heapRanks(right) = leftRank
      heapSlots(right) = leftSlot
      rankIndexBySlot(heapSlots(left)) = left + 1
      rankIndexBySlot(heapSlots(right)) = right + 1
    }

    private def bestCandidate(candidateCount: Int): Int = {
      var best = 0
      var i = 1
      while (i < candidateCount) {
        if (betterHeapIndex(topCandidateHeap(i), topCandidateHeap(best)))
          best = i
        i += 1
      }
      best
    }

    private def betterHeapIndex(leftIndex: Int, rightIndex: Int): Boolean =
      compareHeapEntries(leftIndex, rightIndex) < 0

    private def compareHeapEntries(leftIndex: Int, rightIndex: Int): Int = {
      val left = heapRanks(leftIndex)
      val right = heapRanks(rightIndex)
      if (left eq right) 0
      else if (left.count != right.count)
        java.lang.Integer.compare(right.count, left.count)
      else if (left.latestSeconds != right.latestSeconds)
        java.lang.Long.compare(right.latestSeconds, left.latestSeconds)
      else if (left.latestSeq != right.latestSeq)
        java.lang.Long.compare(right.latestSeq, left.latestSeq)
      else compareRouteKeysById(keys(heapSlots(leftIndex)), keys(heapSlots(rightIndex)))
    }

    private def rankHeapIndex(slot: Int): Int =
      rankIndexBySlot(slot) - 1

    private def ensureRankCapacity(required: Int): Unit =
      if (required > heapRanks.length) {
        var capacity = heapRanks.length
        while (required > capacity)
          capacity *= 2

        val expandedRanks = allocateRankArray(capacity)
        val expandedSlots = allocateIntArray(capacity)
        Array.copy(heapRanks, 0, expandedRanks, 0, heapSize)
        Array.copy(heapSlots, 0, expandedSlots, 0, heapSize)
        heapRanks = expandedRanks
        heapSlots = expandedSlots
      }

    private def clearRankIndex(): Unit = {
      var i = 0
      while (i < heapSize) {
        rankIndexBySlot(heapSlots(i)) = 0
        heapRanks(i) = null
        heapSlots(i) = 0
        i += 1
      }
      heapSize = 0
    }

    private def insertSlot(key: Long): Int = {
      if ((usedSize + 1) * 4 >= keys.length * 3) {
        val compactOnly = activeSize * 2 < usedSize
        if (compactOnly) rehash(keys.length)
        else rehash(keys.length << 1)
      }

      val mask = keys.length - 1
      var slot = hash(key) & mask
      var firstDeleted = -1
      while (true) {
        val current = keys(slot)
        if (current == key) return slot
        if (current == EmptyRouteKey)
          return if (firstDeleted >= 0) firstDeleted else slot
        if (current == DeletedRouteKey && firstDeleted < 0)
          firstDeleted = slot
        slot = (slot + 1) & mask
      }
      slot
    }

    private def existingSlot(key: Long): Int = {
      val mask = keys.length - 1
      var slot = hash(key) & mask
      while (true) {
        val current = keys(slot)
        if (current == key) return slot
        if (current == EmptyRouteKey) return -1
        slot = (slot + 1) & mask
      }
      -1
    }

    private def rehash(newCapacity: Int): Unit = {
      val oldKeys = keys
      val oldCounts = counts
      val oldLatestSeconds = latestSecondsBySlot
      val oldLatestSeq = latestSeqBySlot
      val oldRanks = rankBySlot
      val oldRankIndexes = rankIndexBySlot

      keys = allocateLongArray(newCapacity)
      counts = allocateIntArray(newCapacity)
      latestSecondsBySlot = allocateLongArray(newCapacity)
      latestSeqBySlot = allocateLongArray(newCapacity)
      rankBySlot = allocateRankArray(newCapacity)
      rankIndexBySlot = allocateIntArray(newCapacity)
      activeSize = 0
      usedSize = 0

      var i = 0
      while (i < oldKeys.length) {
        val key = oldKeys(i)
        if (key != EmptyRouteKey && key != DeletedRouteKey) {
          val slot = insertSlotWithoutRehash(key)
          keys(slot) = key
          counts(slot) = oldCounts(i)
          latestSecondsBySlot(slot) = oldLatestSeconds(i)
          latestSeqBySlot(slot) = oldLatestSeq(i)
          rankBySlot(slot) = oldRanks(i)
          val rankIndex = oldRankIndexes(i)
          if (rankIndex != 0) {
            rankIndexBySlot(slot) = rankIndex
            heapSlots(rankIndex - 1) = slot
          }
          activeSize += 1
          usedSize += 1
        }
        i += 1
      }
    }

    private def deleteSlot(slot: Int): Unit = {
      keys(slot) = DeletedRouteKey
      counts(slot) = 0
      latestSecondsBySlot(slot) = 0L
      latestSeqBySlot(slot) = 0L
      rankBySlot(slot) = null
      rankIndexBySlot(slot) = 0
      activeSize -= 1
    }

    private def insertSlotWithoutRehash(key: Long): Int = {
      val mask = keys.length - 1
      var slot = hash(key) & mask
      while (keys(slot) != EmptyRouteKey)
        slot = (slot + 1) & mask
      slot
    }

    private def clearTables(): Unit = {
      var i = 0
      while (i < keys.length) {
        keys(i) = EmptyRouteKey
        counts(i) = 0
        latestSecondsBySlot(i) = 0L
        latestSeqBySlot(i) = 0L
        rankBySlot(i) = null
        rankIndexBySlot(i) = 0
        i += 1
      }
      activeSize = 0
      usedSize = 0
    }
  }

  private def compareRouteKeysById(left: Long, right: Long): Int = {
    val startEast = compareDecimalLex(
      ((left >>> 30) & RoutePartMask).toInt,
      ((right >>> 30) & RoutePartMask).toInt
    )
    if (startEast != 0) startEast
    else {
      val startSouth = compareDecimalLex(
        ((left >>> 20) & RoutePartMask).toInt,
        ((right >>> 20) & RoutePartMask).toInt
      )
      if (startSouth != 0) startSouth
      else {
        val endEast = compareDecimalLex(
          ((left >>> 10) & RoutePartMask).toInt,
          ((right >>> 10) & RoutePartMask).toInt
        )
        if (endEast != 0) endEast
        else compareDecimalLex(
          (left & RoutePartMask).toInt,
          (right & RoutePartMask).toInt
        )
      }
    }
  }

  private def compareDecimalLex(left: Int, right: Int): Int = {
    if (left == right) 0
    else {
      var leftDivisor = highestPowerOf10(left)
      var rightDivisor = highestPowerOf10(right)
      while (leftDivisor > 0 && rightDivisor > 0) {
        val leftDigit = (left / leftDivisor) % 10
        val rightDigit = (right / rightDivisor) % 10
        if (leftDigit != rightDigit)
          return java.lang.Integer.compare(leftDigit, rightDigit)
        leftDivisor /= 10
        rightDivisor /= 10
      }
      if (leftDivisor == 0 && rightDivisor == 0) 0
      else if (leftDivisor == 0) -1
      else 1
    }
  }

  private def highestPowerOf10(value: Int): Int = {
    var divisor = 1
    while (value / divisor >= 10)
      divisor *= 10
    divisor
  }

  private[debs2015] def top10(
      counts: mutable.HashMap[Route, RouteState]
  ): Array[RankedRoute] = {
    val ranked = new mutable.ArrayBuffer[RankedRoute](counts.size)
    counts.foreach { case (route, state) =>
      ranked += RankedRoute(route, state.count, state.latestSeconds, state.latestSeq)
    }

    val sorted = ranked.sortWith { (left, right) =>
      if (left.count != right.count) left.count > right.count
      else if (left.latestSeconds != right.latestSeconds)
        left.latestSeconds > right.latestSeconds
      else if (left.latestSeq != right.latestSeq) left.latestSeq > right.latestSeq
      else left.route.id < right.route.id
    }

    sorted.take(10).toArray
  }

  private[debs2015] def top10Keys(
      counts: mutable.HashMap[Long, RouteState]
  ): Array[RankedRoute] = {
    val ranked = new mutable.ArrayBuffer[RankedRoute](counts.size)
    counts.foreach { case (key, state) =>
      ranked += RankedRoute(routeFromKey(key), state.count, state.latestSeconds, state.latestSeq)
    }

    val sorted = ranked.sortWith { (left, right) =>
      if (left.count != right.count) left.count > right.count
      else if (left.latestSeconds != right.latestSeconds)
        left.latestSeconds > right.latestSeconds
      else if (left.latestSeq != right.latestSeq) left.latestSeq > right.latestSeq
      else left.route.id < right.route.id
    }

    sorted.take(10).toArray
  }
}
