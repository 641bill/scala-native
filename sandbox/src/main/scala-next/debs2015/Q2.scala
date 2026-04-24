package debs2015

import java.io.Writer
import java.util.Comparator
import java.util.TreeSet

import scala.language.experimental.captureChecking

import scala.collection.mutable
import scala.scalanative.memory.RiftRegion

final case class ProfitableArea(
    cellKey: Int,
    var emptyTaxis: Int,
    var medianProfit: Double,
    var profitability: Double,
    var latestSeq: Long
) {
  def cell: Cell = Q2Support.cellFromKey(cellKey)
}

trait Q2Engine {
  def process(trip: Trip): Array[ProfitableArea]
  def close(): Unit = ()
}

final class Q2Heap extends Q2BucketedWindow(useRegions = false, RiftRegion.HPZone)

final class Q2RiftWindows(kind: Int)
    extends Q2BucketedWindow(useRegions = true, kind)

private abstract class Q2BucketedWindow(
    useRegions: Boolean,
    regionKind: Int
) extends Q2Engine {
  import Q2Support._

  private val profitBuckets = mutable.Queue.empty[ProfitBucket]
  private val emptyBuckets = mutable.Queue.empty[EmptyBucket]
  private val profitsByCell = mutable.HashMap.empty[Int, ProfitStats]
  private val emptyCounts = mutable.HashMap.empty[Int, Int]
  private val latestByCell = mutable.HashMap.empty[Int, Long]
  private val latestEmptyByTaxi = mutable.HashMap.empty[Int, EmptyEntry]
  private val rankedAreas = new TreeSet[ProfitableArea](AreaOrdering)
  private val rankByCell = mutable.HashMap.empty[Int, ProfitableArea]
  private val taxiIds = new TaxiIds
  private val rankRegion =
    if (useRegions) RiftRegion.open(regionKind) else null
  private val medianScratchRegion =
    if (useRegions) RiftRegion.open(regionKind) else null
  private val resultArrays = new Array[Array[ProfitableArea]](11)
  private var medianScratch: Array[Double] = null
  private var currentProfitBucket: ProfitBucket = null
  private var currentEmptyBucket: EmptyBucket = null
  private var nextSeq = 0L

  override def process(trip: Trip): Array[ProfitableArea] = {
    evictProfitBefore(trip.dropoffSeconds - ProfitWindowSeconds)
    evictEmptyBefore(trip.dropoffSeconds - EmptyWindowSeconds)

    val seq = nextSeq
    nextSeq += 1L
    val taxiKey = taxiIds.idFor(trip)

    // The current pickup means this taxi is no longer empty at its previous dropoff.
    latestEmptyByTaxi.remove(taxiKey).foreach(removeEmpty)

    if (trip.hasValidProfit) {
      Grid.Q2.cell(trip.pickupLongitude, trip.pickupLatitude).foreach { pickupCell =>
        val profit = trip.profit
        val pickupKey = cellKey(pickupCell)
        val bucket = profitBucketFor(trip.dropoffSeconds)
        val entry = allocateProfitEntry(bucket, pickupKey, profit)
        bucket.head = entry
        profitsByCell
          .getOrElseUpdate(pickupKey, new ProfitStats)
          .add(entry)
        latestByCell.update(pickupKey, seq)
        updateRank(pickupKey)
      }
    }

    Grid.Q2.cell(trip.dropoffLongitude, trip.dropoffLatitude).foreach { dropoffCell =>
      val dropoffKey = cellKey(dropoffCell)
      val bucket = emptyBucketFor(trip.dropoffSeconds)
      val entry = allocateEmptyEntry(bucket, seq, taxiKey, dropoffKey)
      bucket.head = entry
      latestEmptyByTaxi.update(taxiKey, entry)
      emptyCounts.update(dropoffKey, emptyCounts.getOrElse(dropoffKey, 0) + 1)
      latestByCell.update(dropoffKey, seq)
      updateRank(dropoffKey)
    }

    top10()
  }

  override def close(): Unit = {
    profitsByCell.clear()
    emptyCounts.clear()
    latestByCell.clear()
    latestEmptyByTaxi.clear()
    rankedAreas.clear()
    rankByCell.clear()

    while (profitBuckets.nonEmpty)
      closeProfitBucket(profitBuckets.dequeue())
    while (emptyBuckets.nonEmpty)
      closeEmptyBucket(emptyBuckets.dequeue())

    if (useRegions) {
      medianScratchRegion.close()
      rankRegion.close()
    }
    currentProfitBucket = null
    currentEmptyBucket = null
  }

  private def evictProfitBefore(cutoffSeconds: Long): Unit = {
    while (profitBuckets.nonEmpty && profitBuckets.front.startSeconds < cutoffSeconds) {
      val bucket = profitBuckets.dequeue()
      var expired = bucket.head
      while (expired != null) {
        val next = expired.bucketNext
        profitsByCell.get(expired.cellKey).foreach { stats =>
          stats.remove(expired)
          if (stats.isEmpty) profitsByCell.remove(expired.cellKey)
          updateRank(expired.cellKey)
        }
        expired = next
      }
      if (currentProfitBucket eq bucket) currentProfitBucket = null
      closeProfitBucket(bucket)
    }
  }

  private def evictEmptyBefore(cutoffSeconds: Long): Unit = {
    while (emptyBuckets.nonEmpty && emptyBuckets.front.startSeconds < cutoffSeconds) {
      val bucket = emptyBuckets.dequeue()
      var expired = bucket.head
      while (expired != null) {
        latestEmptyByTaxi.get(expired.taxiKey).foreach { latest =>
          if (latest.seq == expired.seq) {
            latestEmptyByTaxi.remove(expired.taxiKey)
            removeEmpty(expired)
          }
        }
        expired = expired.next
      }
      if (currentEmptyBucket eq bucket) currentEmptyBucket = null
      closeEmptyBucket(bucket)
    }
  }

  private def removeEmpty(entry: EmptyEntry): Unit = {
    val previous = emptyCounts.getOrElse(entry.cellKey, 0)
    if (previous <= 1) emptyCounts.remove(entry.cellKey)
    else emptyCounts.update(entry.cellKey, previous - 1)
    updateRank(entry.cellKey)
  }

  private def updateRank(cellKey: Int): Unit = {
    val existing = rankByCell.get(cellKey)
    existing.foreach(rankedAreas.remove)

    profitsByCell.get(cellKey) match {
      case Some(profits) =>
        val empty = emptyCounts.getOrElse(cellKey, 0)
        if (empty > 0 && profits.nonEmpty) {
          val median =
            if (profits.needsMedianScratch)
              profits.medianProfitWithScratch(
                ensureMedianScratch(profits.medianScratchSize)
              )
            else profits.cachedMedianProfit
          val area =
            existing match {
              case Some(current) =>
                current.emptyTaxis = empty
                current.medianProfit = median
                current.profitability = median / empty.toDouble
                current.latestSeq = latestByCell.getOrElse(cellKey, 0L)
                current
              case None =>
                val created =
                  allocateProfitableArea(
                    cellKey,
                    empty,
                    median,
                    median / empty.toDouble,
                    latestByCell.getOrElse(cellKey, 0L)
                  )
                rankByCell.update(cellKey, created)
                created
            }
          rankedAreas.add(area)
        } else {
          rankByCell.remove(cellKey)
        }
      case None =>
        rankByCell.remove(cellKey)
    }
  }

  private def top10(): Array[ProfitableArea] = {
    val size = math.min(10, rankedAreas.size())
    val ranked = resultArray(size)
    val it = rankedAreas.iterator()
    var i = 0
    while (i < size && it.hasNext) {
      ranked(i) = it.next()
      i += 1
    }
    ranked
  }

  private def profitBucketFor(dropoffSeconds: Long): ProfitBucket = {
    if (currentProfitBucket != null && currentProfitBucket.startSeconds == dropoffSeconds)
      currentProfitBucket
    else {
      val region = if (useRegions) RiftRegion.open(regionKind) else null
      val bucket = new ProfitBucket(dropoffSeconds, region, null)
      profitBuckets.enqueue(bucket)
      currentProfitBucket = bucket
      bucket
    }
  }

  private def emptyBucketFor(dropoffSeconds: Long): EmptyBucket = {
    if (currentEmptyBucket != null && currentEmptyBucket.startSeconds == dropoffSeconds)
      currentEmptyBucket
    else {
      val region = if (useRegions) RiftRegion.open(regionKind) else null
      val bucket = new EmptyBucket(dropoffSeconds, region, null)
      emptyBuckets.enqueue(bucket)
      currentEmptyBucket = bucket
      bucket
    }
  }

  private def allocateProfitEntry(
      bucket: ProfitBucket,
      cellKey: Int,
      profit: Double
  ): ProfitEntry =
    if (useRegions)
      bucket.region.alloc(new ProfitEntry(cellKey, profit, bucket.head))
    else new ProfitEntry(cellKey, profit, bucket.head)

  private def allocateEmptyEntry(
      bucket: EmptyBucket,
      seq: Long,
      taxiKey: Int,
      cellKey: Int
  ): EmptyEntry =
    if (useRegions)
      bucket.region.alloc(new EmptyEntry(seq, taxiKey, cellKey, bucket.head))
    else new EmptyEntry(seq, taxiKey, cellKey, bucket.head)

  private def ensureMedianScratch(count: Int): Array[Double] = {
    if (medianScratch == null || medianScratch.length < count) {
      val capacity = nextScratchCapacity(count)
      medianScratch =
        if (useRegions) medianScratchRegion.alloc(new Array[Double](capacity))
        else new Array[Double](capacity)
    }
    medianScratch
  }

  private def nextScratchCapacity(count: Int): Int = {
    var capacity = 16
    while (capacity < count)
      capacity *= 2
    capacity
  }

  private def allocateProfitableArea(
      cellKey: Int,
      emptyTaxis: Int,
      medianProfit: Double,
      profitability: Double,
      latestSeq: Long
  ): ProfitableArea =
    if (useRegions)
      rankRegion.alloc(
        new ProfitableArea(
          cellKey,
          emptyTaxis,
          medianProfit,
          profitability,
          latestSeq
        )
      )
    else
      new ProfitableArea(
        cellKey,
        emptyTaxis,
        medianProfit,
        profitability,
        latestSeq
      )

  private def resultArray(size: Int): Array[ProfitableArea] = {
    if (size == 0) Array.empty[ProfitableArea]
    else {
      var result = resultArrays(size)
      if (result == null) {
        result =
          if (useRegions) rankRegion.alloc(new Array[ProfitableArea](size))
          else new Array[ProfitableArea](size)
        resultArrays(size) = result
      }
      result
    }
  }

  private def closeProfitBucket(bucket: ProfitBucket): Unit = {
    bucket.head = null
    if (useRegions) bucket.region.close()
  }

  private def closeEmptyBucket(bucket: EmptyBucket): Unit = {
    bucket.head = null
    if (useRegions) bucket.region.close()
  }

  private final class ProfitBucket(
      val startSeconds: Long,
      val region: RiftRegion,
      var head: ProfitEntry
  )

  private final class EmptyBucket(
      val startSeconds: Long,
      val region: RiftRegion,
      var head: EmptyEntry
  )

  private final class ProfitEntry(
      val cellKey: Int,
      val profit: Double,
      val bucketNext: ProfitEntry
  ) {
    var nextInCell: ProfitEntry = null
    var previousInCell: ProfitEntry = null
  }

  private final class EmptyEntry(
      val seq: Long,
      val taxiKey: Int,
      val cellKey: Int,
      val next: EmptyEntry
  )

  private final class ProfitStats {
    private var head: ProfitEntry = null
    private var count = 0
    private var dirty = true
    private var cachedMedian = 0.0

    def nonEmpty: Boolean = count > 0
    def isEmpty: Boolean = count == 0

    def add(entry: ProfitEntry): Unit = {
      entry.nextInCell = head
      entry.previousInCell = null
      if (head != null) head.previousInCell = entry
      head = entry
      count += 1
      dirty = true
    }

    def remove(entry: ProfitEntry): Unit = {
      val previous = entry.previousInCell
      val next = entry.nextInCell
      if (previous == null) head = next
      else previous.nextInCell = next
      if (next != null) next.previousInCell = previous
      entry.previousInCell = null
      entry.nextInCell = null
      count -= 1
      dirty = true
    }

    def needsMedianScratch: Boolean = dirty
    def medianScratchSize: Int = count

    def cachedMedianProfit: Double = cachedMedian

    def medianProfitWithScratch(sorted: Array[Double]): Double = {
      if (dirty) computeMedian(sorted)
      cachedMedian
    }

    private def computeMedian(sorted: Array[Double]): Unit = {
      var entry = head
      var i = 0
      while (entry != null) {
        sorted(i) = entry.profit
        entry = entry.nextInCell
        i += 1
      }
      java.util.Arrays.sort(sorted, 0, count)
      cachedMedian =
        if (count == 0) 0.0
        else if ((count & 1) == 1) sorted(count / 2)
        else (sorted(count / 2 - 1) + sorted(count / 2)) / 2.0
      dirty = false
    }
  }
}

object Q2Support {
  private[debs2015] val ProfitWindowSeconds = 15L * 60L
  private[debs2015] val EmptyWindowSeconds = 30L * 60L
  private val CellPartBits = 10
  private val CellPartMask = (1 << CellPartBits) - 1

  private[debs2015] val AreaOrdering: Comparator[ProfitableArea] =
    new Comparator[ProfitableArea] {
      override def compare(left: ProfitableArea, right: ProfitableArea): Int = {
        if (left eq right) 0
        else {
          val byProfitability =
            java.lang.Double.compare(right.profitability, left.profitability)
          if (byProfitability != 0) byProfitability
          else if (left.latestSeq != right.latestSeq)
            java.lang.Long.compare(right.latestSeq, left.latestSeq)
          else compareCellKeysById(left.cellKey, right.cellKey)
        }
      }
    }

  private[debs2015] def cellKey(cell: Cell): Int =
    (cell.east << CellPartBits) | cell.south

  private[debs2015] def cellFromKey(key: Int): Cell =
    Cell(key >>> CellPartBits, key & CellPartMask)

  private[debs2015] def appendCellId(builder: StringBuilder, key: Int): Unit = {
    builder.append(key >>> CellPartBits)
    builder.append('.')
    builder.append(key & CellPartMask)
  }

  private[debs2015] def writeCellId(writer: Writer, key: Int): Unit =
    OutputSupport.writeCellId(
      writer,
      key >>> CellPartBits,
      key & CellPartMask
    )

  private def compareCellKeysById(left: Int, right: Int): Int = {
    val east = compareDecimalLex(left >>> CellPartBits, right >>> CellPartBits)
    if (east != 0) east
    else compareDecimalLex(left & CellPartMask, right & CellPartMask)
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

  private[debs2015] final class TaxiIds {
    private val ids = mutable.HashMap.empty[Int, TaxiIdEntry]
    private var nextId = 0

    def idFor(trip: Trip): Int = {
      val hash = trip.taxiIdHash
      var entry = ids.getOrElse(hash, null)
      while (entry != null) {
        if (trip.taxiIdEquals(entry.taxiId))
          return entry.id
        entry = entry.next
      }

      val id = nextId
      nextId += 1
      ids.update(hash, new TaxiIdEntry(trip.taxiId, id, ids.getOrElse(hash, null)))
      id
    }
  }

  private final class TaxiIdEntry(
      val taxiId: String,
      val id: Int,
      val next: TaxiIdEntry
  )

}
