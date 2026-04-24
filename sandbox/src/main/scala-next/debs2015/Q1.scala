package debs2015

import java.util.Comparator
import java.util.TreeSet

import scala.language.experimental.captureChecking

import scala.collection.mutable
import scala.scalanative.memory.RiftRegion

final case class Route(start: Cell, end: Cell) {
  def id: String = s"${start.id}->${end.id}"
}

final case class RankedRoute(route: Route, count: Int, latestSeconds: Long, latestSeq: Long)

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
    private val counts = mutable.HashMap.empty[Long, RouteState]
    private val rankedRoutes = new TreeSet[RankedRoute](RankedRouteOrdering)
    private val rankByKey = mutable.HashMap.empty[Long, RankedRoute]
    private val rankRegion =
      if (useRegions) RiftRegion.open(regionKind) else null
    private val snapshotRegion =
      if (useRegions) RiftRegion.open(regionKind) else null

    def increment(key: Long, latestSeconds: Long, latestSeq: Long): Unit = {
      val previous = counts.getOrElse(key, RouteState(0, 0L, -1L))
      val next =
        RouteState(
          count = previous.count + 1,
          latestSeconds = latestSeconds,
          latestSeq = latestSeq
        )
      counts.update(key, next)
      updateRank(key, next)
    }

    def decrement(key: Long): Unit = {
      counts.get(key).foreach { previous =>
        if (previous.count <= 1) {
          counts.remove(key)
          removeRank(key)
        } else {
          val next = previous.copy(count = previous.count - 1)
          counts.update(key, next)
          updateRank(key, next)
        }
      }
    }

    def top10(): Array[RankedRoute] = {
      if (useRegions) snapshotRegion.reset()
      val size = math.min(10, rankedRoutes.size())
      val result = allocateResultArray(size)
      val it = rankedRoutes.iterator()
      var i = 0
      while (i < size && it.hasNext) {
        result(i) = it.next()
        i += 1
      }
      result
    }

    def close(): Unit = {
      counts.clear()
      rankedRoutes.clear()
      rankByKey.clear()
      if (useRegions) {
        snapshotRegion.close()
        rankRegion.close()
      }
    }

    private def updateRank(key: Long, state: RouteState): Unit = {
      removeRank(key)
      val ranked =
        allocateRankedRoute(
          key,
          state.count,
          state.latestSeconds,
          state.latestSeq
        )
      rankedRoutes.add(ranked)
      rankByKey.update(key, ranked)
    }

    private def removeRank(key: Long): Unit =
      rankByKey.remove(key).foreach(rankedRoutes.remove)

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

    private def allocateResultArray(size: Int): Array[RankedRoute] =
      if (useRegions) snapshotRegion.alloc(new Array[RankedRoute](size))
      else new Array[RankedRoute](size)
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
