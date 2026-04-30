package debs2015

import java.io.BufferedWriter
import java.io.FileWriter
import java.io.Writer

import scala.collection.mutable
import scala.io.Source
import scala.language.experimental.captureChecking
import scala.scalanative.memory.RiftRegion

object Debs2015Q2CheckedProcessingRunner {
  import Q2Support._

  private val CellPartBits = 10

  final case class Metrics(
      events: Long,
      parsed: Long,
      outliersOrInvalid: Long,
      outputs: Long,
      elapsedNanos: Long,
      latencyMillis: Array[Long]
  ) {
    def elapsedMillis: Double = elapsedNanos.toDouble / 1000000.0

    def throughputEventsPerSecond: Double =
      if (elapsedNanos <= 0L) 0.0
      else events.toDouble * 1000000000.0 / elapsedNanos.toDouble
  }

  private[debs2015] trait CheckedQ2Processor {
    def process(trip: Trip): Int
    def changed(previous: Q2Output.Snapshot, size: Int): Boolean
    def snapshot(size: Int): Q2Output.Snapshot
    def writeRow(
        writer: Writer,
        trip: Trip,
        size: Int,
        delayMillis: Long
    ): Unit
    def writeRow(
        writer: OutputSupport.ByteRowWriter,
        trip: Trip,
        size: Int,
        delayMillis: Long
    ): Unit
  }

  def run(inputPath: String, outputPath: String, mode: String): Metrics =
    mode match {
      case "heap" =>
        fromQ2Metrics(Debs2015Q2Runner.run(inputPath, outputPath, "heap"))
      case "checked-processing" =>
        RiftRegion.init(0)
        try
          RiftRegion.streaming { stream ?=>
            runCheckedProcessing(inputPath, outputPath)
          }
        finally RiftRegion.shutdown()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Q2 checked-processing mode '$other'; expected heap or checked-processing"
        )
    }

  private def fromQ2Metrics(metrics: Debs2015Q2Runner.Metrics): Metrics =
    Metrics(
      events = metrics.events,
      parsed = metrics.parsed,
      outliersOrInvalid = metrics.outliersOrInvalid,
      outputs = metrics.outputs,
      elapsedNanos = metrics.elapsedNanos,
      latencyMillis = metrics.latencyMillis
    )

  private[debs2015] def withCheckedProcessor[A](using
      stream: RiftRegion.StreamingRegion^
  )(
      body: CheckedQ2Processor^{stream} => A
  ): A = {
    final class CheckedProfitableArea(
        val cellKey: Int,
        var emptyTaxis: Int,
        var medianProfit: Double,
        var profitability: Double,
        var latestSeq: Long
    )

    final class ProfitEntry(
        val cellKey: Int,
        val profit: Double
    ) extends RiftRegion.StreamAppendNode {
      var medianHeap: Int = NoMedianHeap
      var medianIndex: Int = -1
    }

    final class EmptyEntry(
        val seq: Long,
        val taxiKey: Int,
        val cellKey: Int
    ) extends RiftRegion.StreamAppendNode

    final class TaxiIdEntry(
        val hash: Int,
        val taxiId: Array[Byte]^{stream},
        val id: Int,
        var next: TaxiIdEntry^{stream}
    )

    def allocateIntArray(size: Int): Array[Int]^{stream} =
      RiftRegion.alloc(new Array[Int](size))

    def allocateLongArray(size: Int): Array[Long]^{stream} =
      RiftRegion.alloc(new Array[Long](size))

    def allocateAreaArray(
        size: Int
    ): Array[CheckedProfitableArea^{stream}]^{stream} =
      RiftRegion.alloc(new Array[CheckedProfitableArea^{stream}](size))

    def allocateProfitEntryArray(
        size: Int
    ): Array[ProfitEntry^{stream}]^{stream} =
      RiftRegion.alloc(new Array[ProfitEntry^{stream}](size))

    def allocateEmptyEntryArray(
        size: Int
    ): Array[EmptyEntry^{stream}]^{stream} =
      RiftRegion.alloc(new Array[EmptyEntry^{stream}](size))

    def allocateTaxiEntryArray(
        size: Int
    ): Array[TaxiIdEntry^{stream}]^{stream} =
      RiftRegion.alloc(new Array[TaxiIdEntry^{stream}](size))

    final class TaxiIds {
      private var buckets = allocateTaxiEntryArray(InitialTaxiIdTableCapacity)
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
        val taxiId: Array[Byte]^{stream} =
          RiftRegion.alloc(new Array[Byte](trip.taxiIdLength))
        trip.copyTaxiIdTo(taxiId)
        val created: TaxiIdEntry^{stream} =
          RiftRegion.alloc(new TaxiIdEntry(hash, taxiId, id, null))
        created.next = buckets(bucket)
        buckets(bucket) = created
        id
      }

      def clear(): Unit = {
        var i = 0
        while (i < buckets.length) {
          buckets(i) = null
          i += 1
        }
      }

      private def grow(): Unit = {
        val oldBuckets = buckets
        buckets = allocateTaxiEntryArray(oldBuckets.length << 1)

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

    }

    final class ProfitStats {
      private var lower = allocateProfitEntryArray(InitialMedianHeapCapacity)
      private var upper = allocateProfitEntryArray(InitialMedianHeapCapacity)
      private var lowerSize = 0
      private var upperSize = 0
      private var count = 0

      def nonEmpty: Boolean = count > 0

      def add(entry: ProfitEntry^{stream}): Unit = {
        count += 1
        if (lowerSize == 0 || compareProfit(entry, lower(0)) <= 0)
          insertLower(entry)
        else insertUpper(entry)
        rebalance()
        Debs2015Counters.recordQ2MedianHeapAdd()
      }

      def remove(entry: ProfitEntry^{stream}): Unit = {
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

      private def insertLower(entry: ProfitEntry^{stream}): Unit = {
        ensureLowerCapacity(lowerSize + 1)
        lower(lowerSize) = entry
        entry.medianHeap = LowerMedianHeap
        entry.medianIndex = lowerSize
        lowerSize += 1
        siftLowerUp(entry.medianIndex)
      }

      private def insertUpper(entry: ProfitEntry^{stream}): Unit = {
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

      private def removeLowerAt(index: Int): ProfitEntry^{stream} = {
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

      private def removeUpperAt(index: Int): ProfitEntry^{stream} = {
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
          val expanded =
            allocateProfitEntryArray(nextCapacity(required, lower.length))
          Array.copy(lower, 0, expanded, 0, lowerSize)
          lower = expanded
        }

      private def ensureUpperCapacity(required: Int): Unit =
        if (required > upper.length) {
          val expanded =
            allocateProfitEntryArray(nextCapacity(required, upper.length))
          Array.copy(upper, 0, expanded, 0, upperSize)
          upper = expanded
        }

      private def nextCapacity(required: Int, current: Int): Int = {
        var capacity = current
        while (required > capacity)
          capacity *= 2
        capacity
      }

      private def compareProfit(
          left: ProfitEntry^{stream},
          right: ProfitEntry^{stream}
      ): Int =
        java.lang.Double.compare(left.profit, right.profit)
    }

    final class CheckedQ2 {
      private val taxiIds = new TaxiIds
      private val profitStatsByCell =
        new Array[ProfitStats^{stream}](CellKeyCapacity)
      private val emptyCounts = allocateIntArray(CellKeyCapacity)
      private val latestByCell = allocateLongArray(CellKeyCapacity)
      private val rankByCell = allocateAreaArray(CellKeyCapacity)
      private val heapIndexByCell = allocateIntArray(CellKeyCapacity)
      private val topIndexByCell = allocateIntArray(CellKeyCapacity)
      private var heapAreas = allocateAreaArray(InitialAreaRankCapacity)
      private var heapCellKeys = allocateIntArray(InitialAreaRankCapacity)
      private val topCandidateHeap = allocateIntArray(TopCandidateCapacity)
      private var latestEmptyByTaxi =
        allocateEmptyEntryArray(InitialTaxiTableCapacity)
      private val result = allocateAreaArray(10)
      private val profitWindow: RiftRegion.StreamAppendWindow[ProfitEntry]^{stream} =
        RiftRegion.streamAppendWindow[ProfitEntry](1L)
      private val emptyWindow: RiftRegion.StreamAppendWindow[EmptyEntry]^{stream} =
        RiftRegion.streamAppendWindow[EmptyEntry](1L)
      private var currentProfitStartSeconds = Long.MinValue
      private var currentProfitBucket: RiftRegion.StreamBucket^{stream} = null
      private var currentProfitRegion: RiftRegion.StreamingRegion^{stream} = null
      private var currentEmptyStartSeconds = Long.MinValue
      private var currentEmptyBucket: RiftRegion.StreamBucket^{stream} = null
      private var currentEmptyRegion: RiftRegion.StreamingRegion^{stream} = null
      private var nextSeq = 0L
      private var heapSize = 0
      private var resultSize = 0
      private var topCacheDirty = true

      def process(trip: Trip): Int = {
        val q2CpuDiagnostics = Debs2015Q2CpuDiagnostics.enabled
        var q2CpuStarted = 0L

        if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
        evictProfitBefore(trip.dropoffSeconds - ProfitWindowSeconds)
        if (q2CpuDiagnostics)
          Debs2015Q2CpuDiagnostics.recordEvictProfit(
            System.nanoTime() - q2CpuStarted
          )

        if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
        evictEmptyBefore(trip.dropoffSeconds - EmptyWindowSeconds)
        if (q2CpuDiagnostics)
          Debs2015Q2CpuDiagnostics.recordEvictEmpty(
            System.nanoTime() - q2CpuStarted
          )

        val seq = nextSeq
        nextSeq += 1L
        if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
        val taxiKey = taxiIds.idFor(trip)
        if (q2CpuDiagnostics)
          Debs2015Q2CpuDiagnostics.recordTaxiLookup(
            System.nanoTime() - q2CpuStarted
          )

        if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
        val previousEmpty = removeLatestEmpty(taxiKey)
        if (previousEmpty != null) removeEmpty(previousEmpty)
        if (q2CpuDiagnostics)
          Debs2015Q2CpuDiagnostics.recordPreviousEmpty(
            System.nanoTime() - q2CpuStarted
          )

        if (trip.hasValidProfit) {
          if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
          val pickupKey =
            Grid.Q2.cellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude)
          if (pickupKey != 0) {
            val profit = trip.profit
            val bucket = profitBucketFor(trip.dropoffSeconds)
            val bucketRegion = currentProfitRegion
            val entry: ProfitEntry^{stream} =
              RiftRegion.alloc(new ProfitEntry(pickupKey, profit))(using
                bucketRegion
              )
            Debs2015ProcessDiagnostics.recordQ2ProfitEntry()
            RiftRegion.appendWindow(stream, profitWindow, bucket, entry)
            profitStatsOrCreate(pickupKey).add(entry)
            updateLatest(pickupKey, seq)
            if (q2CpuDiagnostics)
              Debs2015Q2CpuDiagnostics.recordProfitPath(
                System.nanoTime() - q2CpuStarted
              )
            if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
            updateRank(pickupKey)
            if (q2CpuDiagnostics)
              Debs2015Q2CpuDiagnostics.recordProfitRank(
                System.nanoTime() - q2CpuStarted
              )
          } else if (q2CpuDiagnostics) {
            Debs2015Q2CpuDiagnostics.recordProfitPath(
              System.nanoTime() - q2CpuStarted
            )
          }
        }

        if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
        val dropoffKey =
          Grid.Q2.cellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude)
        if (dropoffKey != 0) {
          val bucket = emptyBucketFor(trip.dropoffSeconds)
          val bucketRegion = currentEmptyRegion
          val entry: EmptyEntry^{stream} =
            RiftRegion.alloc(new EmptyEntry(seq, taxiKey, dropoffKey))(using
              bucketRegion
            )
          Debs2015ProcessDiagnostics.recordQ2EmptyEntry()
          RiftRegion.appendWindow(stream, emptyWindow, bucket, entry)
          updateLatestEmpty(taxiKey, entry)
          incrementEmpty(dropoffKey)
          updateLatest(dropoffKey, seq)
          if (q2CpuDiagnostics)
            Debs2015Q2CpuDiagnostics.recordEmptyPath(
              System.nanoTime() - q2CpuStarted
            )
          if (q2CpuDiagnostics) q2CpuStarted = System.nanoTime()
          updateRank(dropoffKey)
          if (q2CpuDiagnostics)
            Debs2015Q2CpuDiagnostics.recordEmptyRank(
              System.nanoTime() - q2CpuStarted
            )
        } else if (q2CpuDiagnostics) {
          Debs2015Q2CpuDiagnostics.recordEmptyPath(
            System.nanoTime() - q2CpuStarted
          )
        }

        if (q2CpuDiagnostics) {
          q2CpuStarted = System.nanoTime()
          val size = top10()
          Debs2015Q2CpuDiagnostics.recordTop10(
            System.nanoTime() - q2CpuStarted
          )
          size
        } else top10()
      }

      def resultAt(index: Int): CheckedProfitableArea^{stream} =
        result(index)

      def size: Int = resultSize

      def close(): Unit = {
        taxiIds.clear()
        clearLatestEmpty()
        clearRankIndex()
        clearCellTables()
        RiftRegion.closeAllAppendWindowBucketsWithCursor(
          stream,
          profitWindow
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ2ProfitBucketClose()
          while (cursor.hasNext) cursor.next()
        }
        RiftRegion.closeAllAppendWindowBucketsWithCursor(
          stream,
          emptyWindow
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ2EmptyBucketClose()
          while (cursor.hasNext) cursor.next()
        }
        currentProfitStartSeconds = Long.MinValue
        currentProfitBucket = null
        currentProfitRegion = null
        currentEmptyStartSeconds = Long.MinValue
        currentEmptyBucket = null
        currentEmptyRegion = null
      }

      private def evictProfitBefore(cutoffSeconds: Long): Unit = {
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(
          stream,
          profitWindow,
          cutoffSeconds
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ2ProfitBucketClose()
          while (cursor.hasNext) {
            val expired = cursor.next()
            val stats = profitStats(expired.cellKey)
            if (stats != null) {
              stats.remove(expired)
              updateRank(expired.cellKey)
            }
          }
        }
        if (
          currentProfitBucket != null &&
          currentProfitStartSeconds < cutoffSeconds
        ) {
          currentProfitStartSeconds = Long.MinValue
          currentProfitBucket = null
          currentProfitRegion = null
        }
      }

      private def evictEmptyBefore(cutoffSeconds: Long): Unit = {
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(
          stream,
          emptyWindow,
          cutoffSeconds
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ2EmptyBucketClose()
          while (cursor.hasNext) {
            val expired = cursor.next()
            val latest = latestEmpty(expired.taxiKey)
            if (latest != null && latest.seq == expired.seq) {
              clearLatestEmpty(expired.taxiKey)
              removeEmpty(expired)
            }
          }
        }
        if (
          currentEmptyBucket != null &&
          currentEmptyStartSeconds < cutoffSeconds
        ) {
          currentEmptyStartSeconds = Long.MinValue
          currentEmptyBucket = null
          currentEmptyRegion = null
        }
      }

      private def profitBucketFor(
          dropoffSeconds: Long
      ): RiftRegion.StreamBucket^{stream} = {
        val startSeconds = dropoffSeconds
        if (
          currentProfitBucket != null &&
          currentProfitStartSeconds == startSeconds &&
          currentProfitBucket.isOpen
        )
          currentProfitBucket
        else {
          currentProfitStartSeconds = startSeconds
          currentProfitBucket =
            RiftRegion.streamAppendWindowBucketFor(
              stream,
              profitWindow,
              dropoffSeconds
            ) { bucket =>
              Debs2015ProcessDiagnostics.recordQ2ProfitBucketOpen()
              RiftRegion.setDiagnosticFamily(
                stream,
                bucket,
                DebsRegionFamilies.Q2ProfitWindow
              )
            }
          currentProfitRegion =
            RiftRegion.streamBucketRegion(stream, currentProfitBucket)
          currentProfitBucket
        }
      }

      private def emptyBucketFor(
          dropoffSeconds: Long
      ): RiftRegion.StreamBucket^{stream} = {
        val startSeconds = dropoffSeconds
        if (
          currentEmptyBucket != null &&
          currentEmptyStartSeconds == startSeconds &&
          currentEmptyBucket.isOpen
        )
          currentEmptyBucket
        else {
          currentEmptyStartSeconds = startSeconds
          currentEmptyBucket =
            RiftRegion.streamAppendWindowBucketFor(
              stream,
              emptyWindow,
              dropoffSeconds
            ) { bucket =>
              Debs2015ProcessDiagnostics.recordQ2EmptyBucketOpen()
              RiftRegion.setDiagnosticFamily(
                stream,
                bucket,
                DebsRegionFamilies.Q2EmptyWindow
              )
            }
          currentEmptyRegion =
            RiftRegion.streamBucketRegion(stream, currentEmptyBucket)
          currentEmptyBucket
        }
      }

      private def allocateProfitableArea(
          cellKey: Int,
          emptyTaxis: Int,
          medianProfit: Double,
          profitability: Double,
          latestSeq: Long
      ): CheckedProfitableArea^{stream} = {
        val area: CheckedProfitableArea^{stream} =
          RiftRegion.alloc(
            new CheckedProfitableArea(
              cellKey,
              emptyTaxis,
              medianProfit,
              profitability,
              latestSeq
            )
          )
        Debs2015Counters.recordQ2RankCreated()
        area
      }

      private def allocateProfitStats(): ProfitStats^{stream} =
        new ProfitStats

      private def profitStats(cellKey: Int): ProfitStats^{stream} =
        profitStatsByCell(cellKey)

      private def profitStatsOrCreate(cellKey: Int): ProfitStats^{stream} = {
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

      private def rank(cellKey: Int): CheckedProfitableArea^{stream} =
        rankByCell(cellKey)

      private def updateRankEntry(
          cellKey: Int,
          area: CheckedProfitableArea^{stream}
      ): Unit =
        rankByCell(cellKey) = area

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

      private def top10(): Int = {
        Debs2015Counters.recordQ2Top10()
        if (!topCacheDirty && resultSize == math.min(10, heapSize))
          return resultSize

        val size = math.min(10, heapSize)
        clearTopCacheIndex()
        resultSize = size
        topCacheDirty = false
        Debs2015Counters.recordQ2Top10Recompute()
        var clear = size
        while (clear < result.length) {
          result(clear) = null
          clear += 1
        }
        if (size == 0) return size

        var candidateCount = 1
        topCandidateHeap(0) = 0
        var i = 0
        while (i < size) {
          val candidateSlot = bestCandidate(candidateCount)
          val heapPosition = topCandidateHeap(candidateSlot)
          candidateCount -= 1
          topCandidateHeap(candidateSlot) = topCandidateHeap(candidateCount)

          result(i) = heapAreas(heapPosition)
          topIndexByCell(result(i).cellKey) = i + 1

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
        size
      }

      private def removeEmpty(entry: EmptyEntry^{stream}): Unit = {
        val previous = emptyCount(entry.cellKey)
        if (previous <= 1) clearEmpty(entry.cellKey)
        else updateEmpty(entry.cellKey, previous - 1)
        updateRank(entry.cellKey)
      }

      private def clearRank(cellKey: Int, wasTop: Boolean): Unit = {
        removeRankHeap(cellKey)
        rankByCell(cellKey) = null
        if (wasTop || resultSize < 10)
          topCacheDirty = true
      }

      private def latestEmpty(taxiKey: Int): EmptyEntry^{stream} =
        if (taxiKey < latestEmptyByTaxi.length) latestEmptyByTaxi(taxiKey)
        else null

      private def updateLatestEmpty(
          taxiKey: Int,
          entry: EmptyEntry^{stream}
      ): Unit = {
        ensureTaxiCapacity(taxiKey)
        latestEmptyByTaxi(taxiKey) = entry
      }

      private def removeLatestEmpty(taxiKey: Int): EmptyEntry^{stream} =
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

      private def addRankHeap(
          cellKey: Int,
          area: CheckedProfitableArea^{stream}
      ): Unit = {
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

      private def fixRankHeapAt(index: Int): Unit =
        if (index > 0 && betterHeapIndex(index, (index - 1) >>> 1))
          siftRankUp(index)
        else siftRankDown(index)

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

      private def compareAreas(
          left: CheckedProfitableArea^{stream},
          right: CheckedProfitableArea^{stream}
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
        var r = 0
        while (r < result.length) {
          result(r) = null
          r += 1
        }
        heapSize = 0
        resultSize = 0
        topCacheDirty = true
      }

      private def isCachedTop(cellKey: Int): Boolean =
        topIndexByCell(cellKey) > 0

      private def markTopCacheAfterUpdate(
          area: CheckedProfitableArea^{stream},
          wasTop: Boolean
      ): Unit =
        if (topCacheDirty || wasTop || resultSize < 10 || canEnterTop(area))
          topCacheDirty = true

      private def canEnterTop(area: CheckedProfitableArea^{stream}): Boolean =
        resultSize == 0 ||
          compareAreas(area, result(resultSize - 1)) < 0

      private def clearTopCacheIndex(): Unit = {
        var i = 0
        while (i < resultSize) {
          val area = result(i)
          if (area != null)
            topIndexByCell(area.cellKey) = 0
          i += 1
        }
      }
    }

    val q2 = new CheckedQ2

    def hasChanged(previous: Q2Output.Snapshot, size: Int): Boolean = {
      Debs2015Counters.recordQ2ChangedCall()
      if (previous.cellKeys.length != size) true
      else {
        var i = 0
        var same = true
        while (i < size && same) {
          Debs2015Counters.recordQ2ChangedElementCheck()
          val area = q2.resultAt(i)
          same =
            previous.cellKeys(i) == area.cellKey &&
              previous.emptyTaxis(i) == area.emptyTaxis &&
              previous.medianProfits(i) == area.medianProfit &&
              previous.profitabilities(i) == area.profitability
          i += 1
        }
        !same
      }
    }

    def snapshotAreas(size: Int): Q2Output.Snapshot = {
      Debs2015Counters.recordQ2Snapshot(size)
      val cellKeys = new Array[Int](size)
      val emptyTaxis = new Array[Int](size)
      val medianProfits = new Array[Double](size)
      val profitabilities = new Array[Double](size)
      var i = 0
      while (i < size) {
        val area = q2.resultAt(i)
        cellKeys(i) = area.cellKey
        emptyTaxis(i) = area.emptyTaxis
        medianProfits(i) = area.medianProfit
        profitabilities(i) = area.profitability
        i += 1
      }
      new Q2Output.Snapshot(cellKeys, emptyTaxis, medianProfits, profitabilities)
    }

    def writeTextRow(
        writer: Writer,
        trip: Trip,
        size: Int,
        delayMillis: Long
    ): Unit = {
      trip.writePickupTimestamp(writer)
      OutputSupport.writeComma(writer)
      trip.writeDropoffTimestamp(writer)

      var i = 0
      while (i < 10) {
        OutputSupport.writeComma(writer)
        if (i < size) {
          val area = q2.resultAt(i)
          Q2Support.writeCellId(writer, area.cellKey)
          OutputSupport.writeComma(writer)
          OutputSupport.writeInt(writer, area.emptyTaxis)
          OutputSupport.writeComma(writer)
          OutputSupport.writeFixed(writer, area.medianProfit, 2)
          OutputSupport.writeComma(writer)
          OutputSupport.writeFixed(writer, area.profitability, 6)
        } else {
          writer.write("NULL,NULL,NULL,NULL")
        }
        i += 1
      }

      OutputSupport.writeComma(writer)
      OutputSupport.writeLong(writer, delayMillis)
    }

    def writeByteRow(
        writer: OutputSupport.ByteRowWriter,
        trip: Trip,
        size: Int,
        delayMillis: Long
    ): Unit = {
      trip.writePickupTimestamp(writer)
      OutputSupport.writeComma(writer)
      trip.writeDropoffTimestamp(writer)

      var i = 0
      while (i < 10) {
        OutputSupport.writeComma(writer)
        if (i < size) {
          val area = q2.resultAt(i)
          Q2Support.writeCellId(writer, area.cellKey)
          OutputSupport.writeComma(writer)
          OutputSupport.writeInt(writer, area.emptyTaxis)
          OutputSupport.writeComma(writer)
          OutputSupport.writeFixed(writer, area.medianProfit, 2)
          OutputSupport.writeComma(writer)
          OutputSupport.writeFixed(writer, area.profitability, 6)
        } else {
          writer.writeAscii("NULL,NULL,NULL,NULL")
        }
        i += 1
      }

      OutputSupport.writeComma(writer)
      OutputSupport.writeLong(writer, delayMillis)
    }

    val processor: CheckedQ2Processor^{stream} =
      new CheckedQ2Processor {
        def process(trip: Trip): Int =
          q2.process(trip)

        def changed(previous: Q2Output.Snapshot, size: Int): Boolean =
          hasChanged(previous, size)

        def snapshot(size: Int): Q2Output.Snapshot =
          snapshotAreas(size)

        def writeRow(
            writer: Writer,
            trip: Trip,
            size: Int,
            delayMillis: Long
        ): Unit =
          writeTextRow(writer, trip, size, delayMillis)

        def writeRow(
            writer: OutputSupport.ByteRowWriter,
            trip: Trip,
            size: Int,
            delayMillis: Long
        ): Unit =
          writeByteRow(writer, trip, size, delayMillis)
      }

    try body(processor)
    finally q2.close()
  }

  private def runCheckedProcessing(
      inputPath: String,
      outputPath: String
  )(using stream: RiftRegion.StreamingRegion^): Metrics =
    withCheckedProcessor { q2 =>
    val source = Source.fromFile(inputPath)
    val writer = new BufferedWriter(new FileWriter(outputPath))
    val latencies = new mutable.ArrayBuffer[Long](1024)
    val trip = Trip.empty

    var previous = Q2Output.EmptySnapshot
    var events = 0L
    var parsed = 0L
    var outliersOrInvalid = 0L
    var outputs = 0L
    val started = System.nanoTime()

    try {
      val lines = source.getLines()
      while (lines.hasNext) {
        val readAt = System.nanoTime()
        val line = lines.next()
        events += 1L

        if (Trip.parseInto(line, trip)) {
          parsed += 1L
          val size = q2.process(trip)
          if (size == 0) {
            outliersOrInvalid += 1L
          } else if (q2.changed(previous, size)) {
            val writeAt = System.nanoTime()
            val delayMillis = (writeAt - readAt) / 1000000L
            q2.writeRow(writer, trip, size, delayMillis)
            writer.newLine()
            latencies += delayMillis
            outputs += 1L
            previous = q2.snapshot(size)
          }
        } else {
          outliersOrInvalid += 1L
        }
      }
    } finally {
      writer.close()
      source.close()
    }

    val elapsedNanos = System.nanoTime() - started
    Metrics(
      events = events,
      parsed = parsed,
      outliersOrInvalid = outliersOrInvalid,
      outputs = outputs,
      elapsedNanos = elapsedNanos,
      latencyMillis = latencies.toArray
    )
  }

  def printMetrics(metrics: Metrics): Unit =
    Debs2015Q2Runner.printMetrics(
      Debs2015Q2Runner.Metrics(
        events = metrics.events,
        parsed = metrics.parsed,
        outliersOrInvalid = metrics.outliersOrInvalid,
        outputs = metrics.outputs,
        elapsedNanos = metrics.elapsedNanos,
        latencyMillis = metrics.latencyMillis
      )
    )

  private def compareCellKeysById(left: Int, right: Int): Int = {
    val east = compareDecimalLex(left >>> CellPartBits, right >>> CellPartBits)
    if (east != 0) east
    else {
      val mask = (1 << CellPartBits) - 1
      compareDecimalLex(left & mask, right & mask)
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
}

@main def Debs2015Q2CheckedProcessingRun(
    inputPath: String,
    outputPath: String,
    mode: String = "checked-processing"
): Unit = {
  val metrics =
    Debs2015Q2CheckedProcessingRunner.run(inputPath, outputPath, mode)
  Debs2015Q2CheckedProcessingRunner.printMetrics(metrics)
}
