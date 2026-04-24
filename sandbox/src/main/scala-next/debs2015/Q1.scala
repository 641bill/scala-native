package debs2015

import java.util.Comparator
import java.util.TreeSet

import scala.language.experimental.captureChecking

import scala.collection.mutable
import scala.scalanative.memory.RiftRegion

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

final class Q1Heap extends Q1BucketedWindow(useRegions = false, RiftRegion.HPZone)

final class Q1RiftBuckets(kind: Int) extends Q1BucketedWindow(useRegions = true, kind)

private abstract class Q1BucketedWindow(
    useRegions: Boolean,
    regionKind: Int
) extends Q1Engine {
  import Q1Support._

  private val buckets = mutable.Queue.empty[Bucket]
  private val routes = new RouteCounter(useRegions, regionKind)
  private var currentBucket: Bucket = null
  private var nextSeq = 0L

  override def process(trip: Trip): Array[RankedRoute] = {
    evictBefore(trip.dropoffSeconds - WindowSeconds)

    val maybeRouteKey =
      for {
        start <- Grid.Q1.cell(trip.pickupLongitude, trip.pickupLatitude)
        end <- Grid.Q1.cell(trip.dropoffLongitude, trip.dropoffLatitude)
      } yield routeKey(start, end)

    maybeRouteKey.foreach { key =>
      val seq = nextSeq
      nextSeq += 1L
      val bucket = bucketFor(trip.dropoffSeconds)
      bucket.head = allocateEntry(bucket, key)
      routes.increment(key, trip.dropoffSeconds, seq)
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
      val region = if (useRegions) RiftRegion.open(regionKind) else null
      val bucket = new Bucket(startSeconds, region, null)
      buckets.enqueue(bucket)
      currentBucket = bucket
      bucket
    }
  }

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
    if (useRegions) bucket.region.alloc(new BucketWindowEntry(routeKey, bucket.head))
    else new BucketWindowEntry(routeKey, bucket.head)

  private def closeBucket(bucket: Bucket): Unit =
    if (useRegions) bucket.region.close()

  private final class Bucket(
      val startSeconds: Long,
      val region: RiftRegion,
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

  private val RankedRouteOrdering: Comparator[RankedRoute] =
    new Comparator[RankedRoute] {
      override def compare(left: RankedRoute, right: RankedRoute): Int = {
        if (left eq right) 0
        else if (left.count != right.count)
          java.lang.Integer.compare(right.count, left.count)
        else if (left.latestSeconds != right.latestSeconds)
          java.lang.Long.compare(right.latestSeconds, left.latestSeconds)
        else if (left.latestSeq != right.latestSeq)
          java.lang.Long.compare(right.latestSeq, left.latestSeq)
        else left.route.id.compareTo(right.route.id)
      }
    }

  private[debs2015] final class RouteCounter(
      useRegions: Boolean,
      regionKind: Int
  ) {
    private val rankedRoutes = new TreeSet[RankedRoute](RankedRouteOrdering)
    private val rankRegion =
      if (useRegions) RiftRegion.open(regionKind) else null
    private var keys = allocateLongArray(InitialRouteTableCapacity)
    private var counts = allocateIntArray(InitialRouteTableCapacity)
    private var latestSecondsBySlot = allocateLongArray(InitialRouteTableCapacity)
    private var latestSeqBySlot = allocateLongArray(InitialRouteTableCapacity)
    private var rankBySlot = allocateRankArray(InitialRouteTableCapacity)
    private val resultArrays = new Array[Array[RankedRoute]](11)
    private var activeSize = 0
    private var usedSize = 0

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
        if (ranked != null) rankedRoutes.remove(ranked)

        val nextCount = counts(slot) - 1
        if (nextCount <= 0) {
          deleteSlot(slot)
        } else {
          counts(slot) = nextCount
          if (ranked != null) {
            ranked.count = nextCount
            rankedRoutes.add(ranked)
          }
        }
      }
    }

    def top10(): Array[RankedRoute] = {
      val size = math.min(10, rankedRoutes.size())
      val result = resultArray(size)
      val it = rankedRoutes.iterator()
      var i = 0
      while (i < size && it.hasNext) {
        result(i) = it.next()
        i += 1
      }
      result
    }

    def close(): Unit = {
      clearTables()
      rankedRoutes.clear()
      var i = 0
      while (i < resultArrays.length) {
        resultArrays(i) = null
        i += 1
      }
      if (useRegions) rankRegion.close()
    }

    private def updateRank(slot: Int): Unit = {
      val existing = rankBySlot(slot)
      if (existing != null) rankedRoutes.remove(existing)

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
          created
        }
      rankedRoutes.add(ranked)
    }

    private def allocateRankedRoute(
        key: Long,
        count: Int,
        latestSeconds: Long,
        latestSeq: Long
    ): RankedRoute = {
      val route = allocateRoute(key)
      if (useRegions)
        rankRegion.alloc(new RankedRoute(route, count, latestSeconds, latestSeq))
      else new RankedRoute(route, count, latestSeconds, latestSeq)
    }

    private def allocateRoute(key: Long): Route = {
      val startEast = ((key >>> 30) & RoutePartMask).toInt
      val startSouth = ((key >>> 20) & RoutePartMask).toInt
      val endEast = ((key >>> 10) & RoutePartMask).toInt
      val endSouth = (key & RoutePartMask).toInt

      if (useRegions) {
        val start = rankRegion.alloc(new Cell(startEast, startSouth))
        val end = rankRegion.alloc(new Cell(endEast, endSouth))
        rankRegion.alloc(new Route(start, end))
      } else Route(Cell(startEast, startSouth), Cell(endEast, endSouth))
    }

    private def resultArray(size: Int): Array[RankedRoute] = {
      if (size == 0) Array.empty[RankedRoute]
      else {
        var result = resultArrays(size)
        if (result == null) {
          result =
            if (useRegions) rankRegion.alloc(new Array[RankedRoute](size))
            else new Array[RankedRoute](size)
          resultArrays(size) = result
        }
        result
      }
    }

    private def allocateLongArray(size: Int): Array[Long] =
      if (useRegions) rankRegion.alloc(new Array[Long](size))
      else new Array[Long](size)

    private def allocateIntArray(size: Int): Array[Int] =
      if (useRegions) rankRegion.alloc(new Array[Int](size))
      else new Array[Int](size)

    private def allocateRankArray(size: Int): Array[RankedRoute] =
      if (useRegions) rankRegion.alloc(new Array[RankedRoute](size))
      else new Array[RankedRoute](size)

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

      keys = allocateLongArray(newCapacity)
      counts = allocateIntArray(newCapacity)
      latestSecondsBySlot = allocateLongArray(newCapacity)
      latestSeqBySlot = allocateLongArray(newCapacity)
      rankBySlot = allocateRankArray(newCapacity)
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
        i += 1
      }
      activeSize = 0
      usedSize = 0
    }
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
