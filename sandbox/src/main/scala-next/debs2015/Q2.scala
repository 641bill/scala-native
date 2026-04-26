package debs2015

import java.io.Writer

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
  private val rankRegion =
    if (useRegions) RiftRegion.open(regionKind) else null
  private val taxiIds = new TaxiIds(useRegions, rankRegion)
  private val profitStatsByCell =
    if (useRegions) rankRegion.alloc(new Array[ProfitStats](CellKeyCapacity))
    else new Array[ProfitStats](CellKeyCapacity)
  private val emptyCounts =
    if (useRegions) rankRegion.alloc(new Array[Int](CellKeyCapacity))
    else new Array[Int](CellKeyCapacity)
  private val latestByCell =
    if (useRegions) rankRegion.alloc(new Array[Long](CellKeyCapacity))
    else new Array[Long](CellKeyCapacity)
  private val rankByCell =
    if (useRegions) rankRegion.alloc(new Array[ProfitableArea](CellKeyCapacity))
    else new Array[ProfitableArea](CellKeyCapacity)
  private val heapIndexByCell =
    if (useRegions) rankRegion.alloc(new Array[Int](CellKeyCapacity))
    else new Array[Int](CellKeyCapacity)
  private val topIndexByCell =
    if (useRegions) rankRegion.alloc(new Array[Int](CellKeyCapacity))
    else new Array[Int](CellKeyCapacity)
  private var heapAreas = allocateAreaArray(InitialAreaRankCapacity)
  private var heapCellKeys = allocateIntArray(InitialAreaRankCapacity)
  private val topCandidateHeap = allocateIntArray(TopCandidateCapacity)
  private var latestEmptyByTaxi = allocateEmptyEntryArray(InitialTaxiTableCapacity)
  private val resultArrays = new Array[Array[ProfitableArea]](11)
  private var currentProfitBucket: ProfitBucket = null
  private var currentEmptyBucket: EmptyBucket = null
  private var nextSeq = 0L
  private var heapSize = 0
  private var cachedTopSize = 0
  private var topCacheDirty = true

  override def process(trip: Trip): Array[ProfitableArea] = {
    evictProfitBefore(trip.dropoffSeconds - ProfitWindowSeconds)
    evictEmptyBefore(trip.dropoffSeconds - EmptyWindowSeconds)

    val seq = nextSeq
    nextSeq += 1L
    val taxiKey = taxiIds.idFor(trip)

    // The current pickup means this taxi is no longer empty at its previous dropoff.
    val previousEmpty = removeLatestEmpty(taxiKey)
    if (previousEmpty != null) removeEmpty(previousEmpty)

    if (trip.hasValidProfit) {
      val pickupKey =
        Grid.Q2.cellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude)
      if (pickupKey != 0) {
        val profit = trip.profit
        val bucket = profitBucketFor(trip.dropoffSeconds)
        val entry = allocateProfitEntry(bucket, pickupKey, profit)
        bucket.head = entry
        profitStatsOrCreate(pickupKey).add(entry)
        updateLatest(pickupKey, seq)
        updateRank(pickupKey)
      }
    }

    val dropoffKey =
      Grid.Q2.cellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude)
    if (dropoffKey != 0) {
      val bucket = emptyBucketFor(trip.dropoffSeconds)
      val entry = allocateEmptyEntry(bucket, seq, taxiKey, dropoffKey)
      bucket.head = entry
      updateLatestEmpty(taxiKey, entry)
      incrementEmpty(dropoffKey)
      updateLatest(dropoffKey, seq)
      updateRank(dropoffKey)
    }

    top10()
  }

  override def close(): Unit = {
    taxiIds.clear()
    clearLatestEmpty()
    clearRankIndex()
    clearCellTables()

    while (profitBuckets.nonEmpty)
      closeProfitBucket(profitBuckets.dequeue())
    while (emptyBuckets.nonEmpty)
      closeEmptyBucket(emptyBuckets.dequeue())

    if (useRegions) {
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
        val stats = profitStats(expired.cellKey)
        if (stats != null) {
          stats.remove(expired)
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
        val latest = latestEmpty(expired.taxiKey)
        if (latest != null && latest.seq == expired.seq) {
          clearLatestEmpty(expired.taxiKey)
          removeEmpty(expired)
        }
        expired = expired.next
      }
      if (currentEmptyBucket eq bucket) currentEmptyBucket = null
      closeEmptyBucket(bucket)
    }
  }

  private def removeEmpty(entry: EmptyEntry): Unit = {
    val previous = emptyCount(entry.cellKey)
    if (previous <= 1) clearEmpty(entry.cellKey)
    else updateEmpty(entry.cellKey, previous - 1)
    updateRank(entry.cellKey)
  }

  private def updateRank(cellKey: Int): Unit = {
    val existing = rank(cellKey)
    val wasTop = isCachedTop(cellKey)

    val profits = profitStats(cellKey)
    if (profits != null) {
      val empty = emptyCount(cellKey)
      if (empty > 0 && profits.nonEmpty) {
        val median = profits.medianProfit
        if (existing != null) {
          existing.emptyTaxis = empty
          existing.medianProfit = median
          existing.profitability = median / empty.toDouble
          existing.latestSeq = latest(cellKey)
          fixRankHeap(cellKey)
          markTopCacheAfterUpdate(existing, wasTop)
        } else {
          val created =
            allocateProfitableArea(
              cellKey,
              empty,
              median,
              median / empty.toDouble,
              latest(cellKey)
            )
          updateRankEntry(cellKey, created)
          addRankHeap(cellKey, created)
          markTopCacheAfterUpdate(created, wasTop = false)
        }
      } else {
        clearRank(cellKey, wasTop)
      }
    } else {
      clearRank(cellKey, wasTop)
    }
  }

  private def top10(): Array[ProfitableArea] = {
    Debs2015Counters.recordQ2Top10()
    if (!topCacheDirty && cachedTopSize == math.min(10, heapSize))
      return resultArray(cachedTopSize)

    val size = math.min(10, heapSize)
    val ranked = resultArray(size)
    clearTopCacheIndex()
    cachedTopSize = size
    topCacheDirty = false
    Debs2015Counters.recordQ2Top10Recompute()
    if (size == 0) return ranked

    var candidateCount = 1
    topCandidateHeap(0) = 0
    var i = 0
    while (i < size) {
      val candidateSlot = bestCandidate(candidateCount)
      val heapPosition = topCandidateHeap(candidateSlot)
      candidateCount -= 1
      topCandidateHeap(candidateSlot) = topCandidateHeap(candidateCount)

      ranked(i) = heapAreas(heapPosition)
      topIndexByCell(ranked(i).cellKey) = i + 1

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

  private def allocateProfitableArea(
      cellKey: Int,
      emptyTaxis: Int,
      medianProfit: Double,
      profitability: Double,
      latestSeq: Long
  ): ProfitableArea =
    {
      val area =
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
      Debs2015Counters.recordQ2RankCreated()
      area
    }

  private def allocateProfitStats(): ProfitStats =
    if (useRegions) rankRegion.alloc(new ProfitStats(useRegions, rankRegion))
    else new ProfitStats(useRegions = false, null)

  private def allocateEmptyEntryArray(size: Int): Array[EmptyEntry] =
    if (useRegions) rankRegion.alloc(new Array[EmptyEntry](size))
    else new Array[EmptyEntry](size)

  private def allocateAreaArray(size: Int): Array[ProfitableArea] =
    if (useRegions) rankRegion.alloc(new Array[ProfitableArea](size))
    else new Array[ProfitableArea](size)

  private def allocateIntArray(size: Int): Array[Int] =
    if (useRegions) rankRegion.alloc(new Array[Int](size))
    else new Array[Int](size)

  private def profitStats(cellKey: Int): ProfitStats =
    profitStatsByCell(cellKey)

  private def profitStatsOrCreate(cellKey: Int): ProfitStats = {
    var stats = profitStatsByCell(cellKey)
    if (stats == null) {
      stats = allocateProfitStats()
      profitStatsByCell(cellKey) = stats
    }
    stats
  }

  private def emptyCount(cellKey: Int): Int =
    emptyCounts(cellKey)

  private def incrementEmpty(cellKey: Int): Unit =
    emptyCounts(cellKey) += 1

  private def updateEmpty(cellKey: Int, count: Int): Unit =
    emptyCounts(cellKey) = count

  private def clearEmpty(cellKey: Int): Unit =
    emptyCounts(cellKey) = 0

  private def latest(cellKey: Int): Long =
    latestByCell(cellKey)

  private def updateLatest(cellKey: Int, seq: Long): Unit =
    latestByCell(cellKey) = seq

  private def rank(cellKey: Int): ProfitableArea =
    rankByCell(cellKey)

  private def updateRankEntry(cellKey: Int, area: ProfitableArea): Unit =
    rankByCell(cellKey) = area

  private def clearRank(cellKey: Int, wasTop: Boolean): Unit = {
    removeRankHeap(cellKey)
    rankByCell(cellKey) = null
    if (wasTop || cachedTopSize < 10)
      topCacheDirty = true
  }

  private def latestEmpty(taxiKey: Int): EmptyEntry =
    if (taxiKey < latestEmptyByTaxi.length) latestEmptyByTaxi(taxiKey)
    else null

  private def updateLatestEmpty(taxiKey: Int, entry: EmptyEntry): Unit = {
    ensureTaxiCapacity(taxiKey)
    latestEmptyByTaxi(taxiKey) = entry
  }

  private def removeLatestEmpty(taxiKey: Int): EmptyEntry =
    if (taxiKey < latestEmptyByTaxi.length) {
      val entry = latestEmptyByTaxi(taxiKey)
      latestEmptyByTaxi(taxiKey) = null
      entry
    } else null

  private def clearLatestEmpty(taxiKey: Int): Unit =
    if (taxiKey < latestEmptyByTaxi.length)
      latestEmptyByTaxi(taxiKey) = null

  private def ensureTaxiCapacity(taxiKey: Int): Unit =
    if (taxiKey >= latestEmptyByTaxi.length) {
      var capacity = latestEmptyByTaxi.length
      while (taxiKey >= capacity)
        capacity *= 2

      val expanded = allocateEmptyEntryArray(capacity)
      Array.copy(latestEmptyByTaxi, 0, expanded, 0, latestEmptyByTaxi.length)
      latestEmptyByTaxi = expanded
    }

  private def clearLatestEmpty(): Unit = {
    var i = 0
    while (i < latestEmptyByTaxi.length) {
      latestEmptyByTaxi(i) = null
      i += 1
    }
  }

  private def clearCellTables(): Unit = {
    var i = 0
    while (i < CellKeyCapacity) {
      profitStatsByCell(i) = null
      emptyCounts(i) = 0
      latestByCell(i) = 0L
      rankByCell(i) = null
      i += 1
    }
  }

  private def resultArray(size: Int): Array[ProfitableArea] = {
    if (size == 0) Array.empty[ProfitableArea]
    else {
      var result = resultArrays(size)
      if (result == null) {
        result =
          if (useRegions) rankRegion.alloc(new Array[ProfitableArea](size))
          else new Array[ProfitableArea](size)
        resultArrays(size) = result
        Debs2015Counters.recordQ2ResultArrayAlloc(size)
      }
      result
    }
  }

  private def addRankHeap(cellKey: Int, area: ProfitableArea): Unit = {
    ensureRankCapacity(heapSize + 1)
    val index = heapSize
    heapSize += 1
    heapAreas(index) = area
    heapCellKeys(index) = cellKey
    heapIndexByCell(cellKey) = index + 1
    Debs2015Counters.recordQ2RankAdd()
    siftRankUp(index)
  }

  private def removeRankHeap(cellKey: Int): Unit = {
    val index = rankHeapIndex(cellKey)
    if (index < 0) return

    Debs2015Counters.recordQ2RankRemove()
    val last = heapSize - 1
    heapIndexByCell(cellKey) = 0
    if (index != last) {
      heapAreas(index) = heapAreas(last)
      heapCellKeys(index) = heapCellKeys(last)
      heapIndexByCell(heapCellKeys(index)) = index + 1
    }
    heapAreas(last) = null
    heapCellKeys(last) = 0
    heapSize = last

    if (index < heapSize)
      fixRankHeapAt(index)
  }

  private def fixRankHeap(cellKey: Int): Unit = {
    val index = rankHeapIndex(cellKey)
    if (index >= 0) {
      Debs2015Counters.recordQ2RankFix()
      fixRankHeapAt(index)
    }
  }

  private def fixRankHeapAt(index: Int): Unit = {
    if (index > 0 && betterHeapIndex(index, (index - 1) >>> 1))
      siftRankUp(index)
    else siftRankDown(index)
  }

  private def siftRankUp(start: Int): Unit = {
    var child = start
    while (child > 0) {
      val parent = (child - 1) >>> 1
      if (!betterHeapIndex(child, parent)) return
      swapRankHeap(child, parent)
      child = parent
    }
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
    Debs2015Counters.recordQ2RankHeapSwap()
    val leftArea = heapAreas(left)
    val leftCellKey = heapCellKeys(left)
    heapAreas(left) = heapAreas(right)
    heapCellKeys(left) = heapCellKeys(right)
    heapAreas(right) = leftArea
    heapCellKeys(right) = leftCellKey
    heapIndexByCell(heapCellKeys(left)) = left + 1
    heapIndexByCell(heapCellKeys(right)) = right + 1
  }

  private def bestCandidate(candidateCount: Int): Int = {
    var best = 0
    var i = 1
    while (i < candidateCount) {
      Debs2015Counters.recordQ2TopCandidateCompare()
      if (betterHeapIndex(topCandidateHeap(i), topCandidateHeap(best)))
        best = i
      i += 1
    }
    best
  }

  private def betterHeapIndex(leftIndex: Int, rightIndex: Int): Boolean = {
    Debs2015Counters.recordQ2RankHeapCompare()
    compareAreas(heapAreas(leftIndex), heapAreas(rightIndex)) < 0
  }

  private def rankHeapIndex(cellKey: Int): Int =
    heapIndexByCell(cellKey) - 1

  private def ensureRankCapacity(required: Int): Unit =
    if (required > heapAreas.length) {
      var capacity = heapAreas.length
      while (required > capacity)
        capacity *= 2

      val expandedAreas = allocateAreaArray(capacity)
      val expandedCellKeys = allocateIntArray(capacity)
      Array.copy(heapAreas, 0, expandedAreas, 0, heapSize)
      Array.copy(heapCellKeys, 0, expandedCellKeys, 0, heapSize)
      heapAreas = expandedAreas
      heapCellKeys = expandedCellKeys
    }

  private def clearRankIndex(): Unit = {
    clearTopCacheIndex()
    var i = 0
    while (i < heapSize) {
      heapIndexByCell(heapCellKeys(i)) = 0
      heapAreas(i) = null
      heapCellKeys(i) = 0
      i += 1
    }
    heapSize = 0
    cachedTopSize = 0
    topCacheDirty = true
  }

  private def isCachedTop(cellKey: Int): Boolean =
    topIndexByCell(cellKey) > 0

  private def markTopCacheAfterUpdate(
      area: ProfitableArea,
      wasTop: Boolean
  ): Unit =
    if (topCacheDirty || wasTop || cachedTopSize < 10 || canEnterTop(area))
      topCacheDirty = true

  private def canEnterTop(area: ProfitableArea): Boolean =
    cachedTopSize == 0 ||
      compareAreas(area, resultArray(cachedTopSize)(cachedTopSize - 1)) < 0

  private def clearTopCacheIndex(): Unit = {
    val oldTop = if (cachedTopSize == 0) null else resultArray(cachedTopSize)
    var i = 0
    while (i < cachedTopSize) {
      val area = oldTop(i)
      if (area != null)
        topIndexByCell(area.cellKey) = 0
      i += 1
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
    var medianHeap: Int = NoMedianHeap
    var medianIndex: Int = -1
  }

  private final class EmptyEntry(
      val seq: Long,
      val taxiKey: Int,
      val cellKey: Int,
      val next: EmptyEntry
  )

  // lower is a max-heap and upper is a min-heap; entries carry their heap/index
  // so window eviction can remove them without scanning a cell list.
  private final class ProfitStats(
      useRegions: Boolean,
      region: RiftRegion
  ) {
    private var lower = allocateEntryArray(InitialMedianHeapCapacity)
    private var upper = allocateEntryArray(InitialMedianHeapCapacity)
    private var lowerSize = 0
    private var upperSize = 0
    private var count = 0

    def nonEmpty: Boolean = count > 0

    def add(entry: ProfitEntry): Unit = {
      count += 1
      if (lowerSize == 0 || compareProfit(entry, lower(0)) <= 0)
        insertLower(entry)
      else insertUpper(entry)
      rebalance()
      Debs2015Counters.recordQ2MedianHeapAdd()
    }

    def remove(entry: ProfitEntry): Unit = {
      if (entry.medianHeap == LowerMedianHeap)
        removeLowerAt(entry.medianIndex)
      else if (entry.medianHeap == UpperMedianHeap)
        removeUpperAt(entry.medianIndex)
      count -= 1
      rebalance()
      Debs2015Counters.recordQ2MedianHeapRemove()
    }

    def medianProfit: Double = {
      Debs2015Counters.recordQ2MedianRead()
      if (count == 0) 0.0
      else if ((count & 1) == 1) lower(0).profit
      else (lower(0).profit + upper(0).profit) / 2.0
    }

    private def insertLower(entry: ProfitEntry): Unit = {
      ensureLowerCapacity(lowerSize + 1)
      lower(lowerSize) = entry
      entry.medianHeap = LowerMedianHeap
      entry.medianIndex = lowerSize
      lowerSize += 1
      siftLowerUp(entry.medianIndex)
    }

    private def insertUpper(entry: ProfitEntry): Unit = {
      ensureUpperCapacity(upperSize + 1)
      upper(upperSize) = entry
      entry.medianHeap = UpperMedianHeap
      entry.medianIndex = upperSize
      upperSize += 1
      siftUpperUp(entry.medianIndex)
    }

    private def rebalance(): Unit = {
      while (lowerSize > upperSize + 1) {
        val moved = removeLowerAt(0)
        insertUpper(moved)
        Debs2015Counters.recordQ2MedianRebalance()
      }
      while (upperSize > lowerSize) {
        val moved = removeUpperAt(0)
        insertLower(moved)
        Debs2015Counters.recordQ2MedianRebalance()
      }
    }

    private def removeLowerAt(index: Int): ProfitEntry = {
      val removed = lower(index)
      val last = lowerSize - 1
      lowerSize = last
      if (index != last) {
        val moved = lower(last)
        lower(last) = null
        lower(index) = moved
        moved.medianIndex = index
        fixLowerAt(index)
      } else {
        lower(last) = null
      }
      removed.medianHeap = NoMedianHeap
      removed.medianIndex = -1
      removed
    }

    private def removeUpperAt(index: Int): ProfitEntry = {
      val removed = upper(index)
      val last = upperSize - 1
      upperSize = last
      if (index != last) {
        val moved = upper(last)
        upper(last) = null
        upper(index) = moved
        moved.medianIndex = index
        fixUpperAt(index)
      } else {
        upper(last) = null
      }
      removed.medianHeap = NoMedianHeap
      removed.medianIndex = -1
      removed
    }

    private def fixLowerAt(index: Int): Unit = {
      val moved = siftLowerUp(index)
      siftLowerDown(moved)
    }

    private def fixUpperAt(index: Int): Unit = {
      val moved = siftUpperUp(index)
      siftUpperDown(moved)
    }

    private def siftLowerUp(start: Int): Int = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (compareProfit(lower(child), lower(parent)) <= 0) return child
        swapLower(child, parent)
        child = parent
      }
      child
    }

    private def siftLowerDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= lowerSize) return
        val right = left + 1
        var best = left
        if (right < lowerSize && compareProfit(lower(right), lower(left)) > 0)
          best = right
        if (compareProfit(lower(best), lower(parent)) <= 0) return
        swapLower(parent, best)
        parent = best
      }
    }

    private def siftUpperUp(start: Int): Int = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (compareProfit(upper(child), upper(parent)) >= 0) return child
        swapUpper(child, parent)
        child = parent
      }
      child
    }

    private def siftUpperDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= upperSize) return
        val right = left + 1
        var best = left
        if (right < upperSize && compareProfit(upper(right), upper(left)) < 0)
          best = right
        if (compareProfit(upper(best), upper(parent)) >= 0) return
        swapUpper(parent, best)
        parent = best
      }
    }

    private def swapLower(left: Int, right: Int): Unit = {
      val tmp = lower(left)
      lower(left) = lower(right)
      lower(right) = tmp
      lower(left).medianIndex = left
      lower(right).medianIndex = right
    }

    private def swapUpper(left: Int, right: Int): Unit = {
      val tmp = upper(left)
      upper(left) = upper(right)
      upper(right) = tmp
      upper(left).medianIndex = left
      upper(right).medianIndex = right
    }

    private def ensureLowerCapacity(required: Int): Unit =
      if (required > lower.length) {
        val expanded = allocateEntryArray(nextCapacity(required, lower.length))
        Array.copy(lower, 0, expanded, 0, lowerSize)
        lower = expanded
      }

    private def ensureUpperCapacity(required: Int): Unit =
      if (required > upper.length) {
        val expanded = allocateEntryArray(nextCapacity(required, upper.length))
        Array.copy(upper, 0, expanded, 0, upperSize)
        upper = expanded
      }

    private def nextCapacity(required: Int, current: Int): Int = {
      var capacity = current
      while (required > capacity)
        capacity *= 2
      capacity
    }

    private def allocateEntryArray(size: Int): Array[ProfitEntry] =
      if (useRegions) region.alloc(new Array[ProfitEntry](size))
      else new Array[ProfitEntry](size)

    private def compareProfit(left: ProfitEntry, right: ProfitEntry): Int =
      java.lang.Double.compare(left.profit, right.profit)
  }
}

object Q2Support {
  private[debs2015] val ProfitWindowSeconds = 15L * 60L
  private[debs2015] val EmptyWindowSeconds = 30L * 60L
  private val CellPartBits = 10
  private val CellPartMask = (1 << CellPartBits) - 1
  private[debs2015] val InitialTaxiTableCapacity = 4096
  private[debs2015] val InitialTaxiIdTableCapacity = 4096
  private[debs2015] val InitialAreaRankCapacity = 1024
  private[debs2015] val InitialMedianHeapCapacity = 8
  private[debs2015] val TopCandidateCapacity = 24
  private[debs2015] val CellKeyCapacity = (Grid.Q2.size + 1) << CellPartBits
  private[debs2015] val NoMedianHeap = 0
  private[debs2015] val LowerMedianHeap = 1
  private[debs2015] val UpperMedianHeap = 2

  private[debs2015] def compareAreas(
      left: ProfitableArea,
      right: ProfitableArea
  ): Int =
    if (left eq right) 0
    else {
      val byProfitability =
        java.lang.Double.compare(right.profitability, left.profitability)
      if (byProfitability != 0) byProfitability
      else if (left.latestSeq != right.latestSeq)
        java.lang.Long.compare(right.latestSeq, left.latestSeq)
      else compareCellKeysById(left.cellKey, right.cellKey)
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

  private[debs2015] final class TaxiIds(
      useRegions: Boolean,
      region: RiftRegion
  ) {
    private var buckets = allocateEntryArray(InitialTaxiIdTableCapacity)
    private var nextId = 0

    def idFor(trip: Trip): Int = {
      Debs2015Counters.recordTaxiLookup()
      val hash = trip.taxiIdHash
      var bucket = bucketIndex(hash)
      var entry = buckets(bucket)
      while (entry != null) {
        Debs2015Counters.recordTaxiEntryScan()
        if (entry.hash == hash && trip.taxiIdEquals(entry.taxiId)) {
          Debs2015Counters.recordTaxiHit()
          return entry.id
        }
        entry = entry.next
      }

      if ((nextId + 1) * 4 >= buckets.length * 3) {
        grow()
        bucket = bucketIndex(hash)
      }
      val id = nextId
      nextId += 1
      Debs2015Counters.recordTaxiMiss()
      Debs2015Counters.recordTaxiEntryCreated()
      val taxiId = allocateTaxiId(trip)
      buckets(bucket) = allocateEntry(hash, taxiId, id, buckets(bucket))
      id
    }

    def clear(): Unit = {
      var i = 0
      while (i < buckets.length) {
        buckets(i) = null
        i += 1
      }
      buckets = null
      nextId = 0
    }

    private def grow(): Unit = {
      val oldBuckets = buckets
      buckets = allocateEntryArray(oldBuckets.length << 1)

      var i = 0
      while (i < oldBuckets.length) {
        var entry = oldBuckets(i)
        while (entry != null) {
          val next = entry.next
          val bucket = bucketIndex(entry.hash)
          entry.next = buckets(bucket)
          buckets(bucket) = entry
          entry = next
        }
        oldBuckets(i) = null
        i += 1
      }
    }

    private def bucketIndex(hash: Int): Int =
      hash & (buckets.length - 1)

    private def allocateEntryArray(size: Int): Array[TaxiIdEntry] =
      if (useRegions) region.alloc(new Array[TaxiIdEntry](size))
      else new Array[TaxiIdEntry](size)

    private def allocateTaxiId(trip: Trip): Array[Byte] = {
      val bytes =
        if (useRegions) region.alloc(new Array[Byte](trip.taxiIdLength))
        else new Array[Byte](trip.taxiIdLength)
      trip.copyTaxiIdTo(bytes)
      bytes
    }

    private def allocateEntry(
        hash: Int,
        taxiId: Array[Byte],
        id: Int,
        next: TaxiIdEntry
    ): TaxiIdEntry =
      if (useRegions) region.alloc(new TaxiIdEntry(hash, taxiId, id, next))
      else new TaxiIdEntry(hash, taxiId, id, next)
  }

  private final class TaxiIdEntry(
      val hash: Int,
      val taxiId: Array[Byte],
      val id: Int,
      var next: TaxiIdEntry
  )

}
