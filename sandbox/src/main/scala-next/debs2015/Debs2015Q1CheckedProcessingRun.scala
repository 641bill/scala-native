package debs2015

import java.io.BufferedWriter
import java.io.FileWriter
import java.io.Writer

import scala.collection.mutable
import scala.io.Source
import scala.language.experimental.captureChecking
import scala.scalanative.memory.RiftRegion

object Debs2015Q1CheckedProcessingRunner {
  private val RoutePartBits = 10
  private val RoutePartMask = (1L << RoutePartBits) - 1L
  private val EmptyRouteKey = 0L
  private val DeletedRouteKey = -1L
  private val InitialRouteTableCapacity = 1024
  private val InitialRouteRankCapacity = 1024
  private val TopCandidateCapacity = 24
  // Rank arenas close only after every event in the same Q1 window interval has
  // expired, so active route slots either point to a newer arena or have been removed.
  private val RankBucketSeconds = Q1Support.WindowSeconds

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

  private[debs2015] trait CheckedQ1Processor {
    def process(trip: Trip): Int
    def changed(previous: Array[Long], size: Int): Boolean
    def snapshot(size: Int): Array[Long]
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
        fromQ1Metrics(Debs2015Q1Runner.run(inputPath, outputPath, "heap"))
      case "checked-processing" =>
        RiftRegion.init(0)
        try
          RiftRegion.streaming { stream ?=>
            runCheckedProcessing(inputPath, outputPath)
          }
        finally RiftRegion.shutdown()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Q1 checked-processing mode '$other'; expected heap or checked-processing"
        )
    }

  private def fromQ1Metrics(metrics: Debs2015Q1Runner.Metrics): Metrics =
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
      body: CheckedQ1Processor^{stream} => A
  ): A = {
    final class CheckedCell(val east: Int, val south: Int)
    final class CheckedRoute(
        val start: CheckedCell^{stream},
        val end: CheckedCell^{stream}
    )
    final class CheckedRankedRoute(
        val key: Long,
        val route: CheckedRoute^{stream},
        var count: Int,
        var latestSeconds: Long,
        var latestSeq: Long,
        val rankBucketStartSeconds: Long
    )
    final class RouteEvent(val routeKey: Long)
        extends RiftRegion.StreamAppendNode

    def allocateLongArray(size: Int): Array[Long]^{stream} =
      RiftRegion.alloc(new Array[Long](size))

    def allocateIntArray(size: Int): Array[Int]^{stream} =
      RiftRegion.alloc(new Array[Int](size))

    def allocateRankArray(
        size: Int
    ): Array[CheckedRankedRoute^{stream}]^{stream} =
      RiftRegion.alloc(new Array[CheckedRankedRoute^{stream}](size))

    final class RouteCounter {
      private var keys = allocateLongArray(InitialRouteTableCapacity)
      private var counts = allocateIntArray(InitialRouteTableCapacity)
      private var latestSecondsBySlot = allocateLongArray(InitialRouteTableCapacity)
      private var latestSeqBySlot = allocateLongArray(InitialRouteTableCapacity)
      private var rankBySlot = allocateRankArray(InitialRouteTableCapacity)
      private var rankIndexBySlot = allocateIntArray(InitialRouteTableCapacity)
      private var heapRanks = allocateRankArray(InitialRouteRankCapacity)
      private var heapSlots = allocateIntArray(InitialRouteRankCapacity)
      private val topCandidateHeap = allocateIntArray(TopCandidateCapacity)
      private val result = allocateRankArray(10)
      private var activeSize = 0
      private var usedSize = 0
      private var heapSize = 0
      private var resultSize = 0

      def increment(
          key: Long,
          latestSeconds: Long,
          latestSeq: Long,
          rankRegion: RiftRegion.StreamingRegion^{stream},
          rankBucketStartSeconds: Long
      ): Unit = {
        val slot = insertSlot(key)
        if (keys(slot) == key) {
          counts(slot) += 1
          latestSecondsBySlot(slot) = latestSeconds
          latestSeqBySlot(slot) = latestSeq
          refreshRank(slot, rankRegion, rankBucketStartSeconds)
        } else {
          if (keys(slot) == EmptyRouteKey) usedSize += 1
          keys(slot) = key
          counts(slot) = 1
          latestSecondsBySlot(slot) = latestSeconds
          latestSeqBySlot(slot) = latestSeq
          activeSize += 1
          refreshRank(slot, rankRegion, rankBucketStartSeconds)
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

      def top10(): Int = {
        Debs2015Counters.recordQ1Top10()
        val size = math.min(10, heapSize)
        resultSize = size
        if (size == 0) return size

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
        size
      }

      def resultAt(index: Int): CheckedRankedRoute^{stream} =
        result(index)

      def size: Int = resultSize

      def clearResult(): Unit = {
        var i = 0
        while (i < resultSize) {
          result(i) = null
          i += 1
        }
        resultSize = 0
      }

      private def refreshRank(
          slot: Int,
          rankRegion: RiftRegion.StreamingRegion^{stream},
          rankBucketStartSeconds: Long
      ): Unit = {
        Debs2015ProcessDiagnostics.recordQ1RankRefresh()
        val existing = rankBySlot(slot)
        if (
          existing != null &&
          existing.rankBucketStartSeconds == rankBucketStartSeconds
        ) {
          existing.count = counts(slot)
          existing.latestSeconds = latestSecondsBySlot(slot)
          existing.latestSeq = latestSeqBySlot(slot)
          fixRankHeap(slot)
        } else {
          val ranked =
            allocateRankedRoute(
              keys(slot),
              counts(slot),
              latestSecondsBySlot(slot),
              latestSeqBySlot(slot),
              rankRegion,
              rankBucketStartSeconds
            )
          val index = rankHeapIndex(slot)
          rankBySlot(slot) = ranked
          if (index >= 0) {
            heapRanks(index) = ranked
            fixRankHeap(slot)
          } else {
            addRankHeap(slot, ranked)
          }
        }
      }

      private def allocateRankedRoute(
          key: Long,
          count: Int,
          latestSeconds: Long,
          latestSeq: Long,
          rankRegion: RiftRegion.StreamingRegion^{stream},
          rankBucketStartSeconds: Long
      ): CheckedRankedRoute^{stream} = {
        val startEast = ((key >>> 30) & RoutePartMask).toInt
        val startSouth = ((key >>> 20) & RoutePartMask).toInt
        val endEast = ((key >>> 10) & RoutePartMask).toInt
        val endSouth = (key & RoutePartMask).toInt

        val start: CheckedCell^{stream} =
          RiftRegion.alloc(new CheckedCell(startEast, startSouth))(using
            rankRegion
          )
        val end: CheckedCell^{stream} =
          RiftRegion.alloc(new CheckedCell(endEast, endSouth))(using
            rankRegion
          )
        val route: CheckedRoute^{stream} =
          RiftRegion.alloc(new CheckedRoute(start, end))(using rankRegion)
        val ranked: CheckedRankedRoute^{stream} =
          RiftRegion.alloc(
            new CheckedRankedRoute(
              key,
              route,
              count,
              latestSeconds,
              latestSeq,
              rankBucketStartSeconds
            )
          )(using rankRegion)
        Debs2015Counters.recordQ1RankCreated()
        ranked
      }

      private def addRankHeap(
          slot: Int,
          ranked: CheckedRankedRoute^{stream}
      ): Unit = {
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
        Debs2015ProcessDiagnostics.recordQ1RankHeapSwap()
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
          Debs2015ProcessDiagnostics.recordQ1TopCandidateCompare()
          if (betterHeapIndex(topCandidateHeap(i), topCandidateHeap(best)))
            best = i
          i += 1
        }
        best
      }

      private def betterHeapIndex(leftIndex: Int, rightIndex: Int): Boolean =
        compareHeapEntries(leftIndex, rightIndex) < 0

      private def compareHeapEntries(leftIndex: Int, rightIndex: Int): Int = {
        Debs2015ProcessDiagnostics.recordQ1RankHeapCompare()
        val left = heapRanks(leftIndex)
        val right = heapRanks(rightIndex)
        if (left eq right) 0
        else if (left.count != right.count)
          java.lang.Integer.compare(right.count, left.count)
        else if (left.latestSeconds != right.latestSeconds)
          java.lang.Long.compare(right.latestSeconds, left.latestSeconds)
        else if (left.latestSeq != right.latestSeq)
          java.lang.Long.compare(right.latestSeq, left.latestSeq)
        else compareRouteKeysById(left.key, right.key)
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
          Debs2015ProcessDiagnostics.recordQ1RouteTableProbe()
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
          Debs2015ProcessDiagnostics.recordQ1RouteTableProbe()
          val current = keys(slot)
          if (current == key) return slot
          if (current == EmptyRouteKey) return -1
          slot = (slot + 1) & mask
        }
        -1
      }

      private def rehash(newCapacity: Int): Unit = {
        Debs2015ProcessDiagnostics.recordQ1RouteTableRehash(newCapacity)
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
        while (keys(slot) != EmptyRouteKey) {
          Debs2015ProcessDiagnostics.recordQ1RouteTableProbe()
          slot = (slot + 1) & mask
        }
        Debs2015ProcessDiagnostics.recordQ1RouteTableProbe()
        slot
      }
    }

    final class CheckedQ1 {
      private val routes = new RouteCounter
      private val eventWindow: RiftRegion.StreamAppendWindow[RouteEvent]^{stream} =
        RiftRegion.streamAppendWindow[RouteEvent](1L)
      private var currentEventStartSeconds = Long.MinValue
      private var currentEventBucket: RiftRegion.StreamBucket^{stream} = null
      private var currentEventRegion: RiftRegion.StreamingRegion^{stream} = null
      private val rankBuckets: RiftRegion.StreamBucketArena^{stream} =
        RiftRegion.streamBucketArena(RankBucketSeconds)
      private var nextSeq = 0L

      def process(trip: Trip): Int = {
        evictBefore(trip.dropoffSeconds - Q1Support.WindowSeconds)

        val startKey =
          Grid.Q1.cellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude)
        if (startKey != 0) {
          val endKey =
            Grid.Q1.cellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude)
          if (endKey != 0) {
            val key = routeKey(startKey, endKey)
            val seq = nextSeq
            nextSeq += 1L
            val bucket = eventBucketFor(trip.dropoffSeconds)
            val bucketRegion = currentEventRegion
            val rankBucket = rankBucketFor(trip.dropoffSeconds)
            val rankRegion =
              RiftRegion.streamBucketRegion(stream, rankBucket)
            val event: RouteEvent^{stream} =
              RiftRegion.alloc(new RouteEvent(key))(using bucketRegion)
            Debs2015ProcessDiagnostics.recordQ1WindowEntry()
            RiftRegion.appendWindow(stream, eventWindow, bucket, event)
            routes.increment(
              key,
              trip.dropoffSeconds,
              seq,
              rankRegion,
              rankBucket.startSeconds
            )
          }
        }

        routes.top10()
      }

      def resultAt(index: Int): CheckedRankedRoute^{stream} =
        routes.resultAt(index)

      def resultSize: Int =
        routes.size

      def close(): Unit = {
        routes.clearResult()
        RiftRegion.closeAllAppendWindowBucketsWithCursor(
          stream,
          eventWindow
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ1BucketClose()
          while (cursor.hasNext) cursor.next()
        }
        currentEventStartSeconds = Long.MinValue
        currentEventBucket = null
        currentEventRegion = null
        RiftRegion.closeAllStreamBuckets(stream, rankBuckets) { _ => () }
      }

      private def eventBucketFor(
          dropoffSeconds: Long
      ): RiftRegion.StreamBucket^{stream} = {
        val startSeconds = dropoffSeconds
        if (
          currentEventBucket != null &&
          currentEventStartSeconds == startSeconds &&
          currentEventBucket.isOpen
        )
          currentEventBucket
        else {
          currentEventStartSeconds = startSeconds
          currentEventBucket =
            RiftRegion.streamAppendWindowBucketFor(
              stream,
              eventWindow,
              dropoffSeconds
            ) { bucket =>
              Debs2015ProcessDiagnostics.recordQ1BucketOpen()
              RiftRegion.setDiagnosticFamily(
                stream,
                bucket,
                DebsRegionFamilies.Q1Window
              )
            }
          currentEventRegion =
            RiftRegion.streamBucketRegion(stream, currentEventBucket)
          currentEventBucket
        }
      }

      private def rankBucketFor(
          dropoffSeconds: Long
      ): RiftRegion.StreamBucket^{stream} =
        RiftRegion.streamBucketFor(stream, rankBuckets, dropoffSeconds) {
          bucket =>
            RiftRegion.setDiagnosticFamily(
              stream,
              bucket,
              DebsRegionFamilies.Q1Window
            )
        }

      private def evictBefore(cutoffSeconds: Long): Unit = {
        val routeCounters = routes
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(
          stream,
          eventWindow,
          cutoffSeconds
        ) { (_, cursor) =>
          Debs2015ProcessDiagnostics.recordQ1BucketClose()
          while (cursor.hasNext) {
            val event = cursor.next()
            routeCounters.decrement(event.routeKey)
          }
        }
        if (
          currentEventBucket != null &&
          currentEventStartSeconds < cutoffSeconds
        ) {
          currentEventStartSeconds = Long.MinValue
          currentEventBucket = null
          currentEventRegion = null
        }
        closeRankBucketsBefore(cutoffSeconds)
      }

      private def closeRankBucketsBefore(cutoffSeconds: Long): Unit = {
        if (
          !RiftRegion.hasStreamBucketsBefore(
            stream,
            rankBuckets,
            cutoffSeconds
          )
        )
          return

        routes.clearResult()
        RiftRegion.closeStreamBucketsBefore(
            stream,
            rankBuckets,
            cutoffSeconds
        )(_ => ())
      }
    }

    val q1 = new CheckedQ1

    def hasChanged(previous: Array[Long], size: Int): Boolean = {
      if (previous.length != size) true
      else {
        var i = 0
        var same = true
        while (i < size && same) {
          same = previous(i) == q1.resultAt(i).key
          i += 1
        }
        !same
      }
    }

    def snapshotKeys(size: Int): Array[Long] = {
      Debs2015Counters.recordQ1Snapshot(size)
      val result = new Array[Long](size)
      var i = 0
      while (i < size) {
        result(i) = q1.resultAt(i).key
        i += 1
      }
      result
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
          val route = q1.resultAt(i).route
          OutputSupport.writeCellId(writer, route.start.east, route.start.south)
          OutputSupport.writeComma(writer)
          OutputSupport.writeCellId(writer, route.end.east, route.end.south)
        } else {
          writer.write("NULL,NULL")
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
          val route = q1.resultAt(i).route
          OutputSupport.writeCellId(writer, route.start.east, route.start.south)
          OutputSupport.writeComma(writer)
          OutputSupport.writeCellId(writer, route.end.east, route.end.south)
        } else {
          writer.writeAscii("NULL,NULL")
        }
        i += 1
      }

      OutputSupport.writeComma(writer)
      OutputSupport.writeLong(writer, delayMillis)
    }

    val processor: CheckedQ1Processor^{stream} =
      new CheckedQ1Processor {
        def process(trip: Trip): Int =
          q1.process(trip)

        def changed(previous: Array[Long], size: Int): Boolean =
          hasChanged(previous, size)

        def snapshot(size: Int): Array[Long] =
          snapshotKeys(size)

        def writeRow(
            writer: Writer,
            trip: Trip,
            size: Int,
            delayMillis: Long
        ): Unit =
          writeTextRow(
            writer,
            trip,
            size,
            delayMillis
          )

        def writeRow(
            writer: OutputSupport.ByteRowWriter,
            trip: Trip,
            size: Int,
            delayMillis: Long
        ): Unit =
          writeByteRow(
            writer,
            trip,
            size,
            delayMillis
          )
      }

    try body(processor)
    finally q1.close()
  }

  private def runCheckedProcessing(
      inputPath: String,
      outputPath: String
  )(using stream: RiftRegion.StreamingRegion^): Metrics =
    withCheckedProcessor { q1 =>
    val source = Source.fromFile(inputPath)
    val writer = new BufferedWriter(new FileWriter(outputPath))
    val latencies = new mutable.ArrayBuffer[Long](1024)
    val trip = Trip.empty

    var previous = Q1Output.EmptySnapshot
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
          val size = q1.process(trip)
          if (size == 0) {
            outliersOrInvalid += 1L
          } else if (q1.changed(previous, size)) {
            val writeAt = System.nanoTime()
            val delayMillis = (writeAt - readAt) / 1000000L
            q1.writeRow(writer, trip, size, delayMillis)
            writer.newLine()
            previous = q1.snapshot(size)
            latencies += delayMillis
            outputs += 1L
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

  private def routeKey(startKey: Int, endKey: Int): Long =
    (startKey.toLong << (RoutePartBits * 2)) | endKey.toLong

  private def hash(key: Long): Int = {
    var x = key
    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    x *= 0xc4ceb9fe1a85ec53L
    x ^= x >>> 33
    x.toInt
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

  def printMetrics(metrics: Metrics): Unit =
    Debs2015Q1Runner.printMetrics(
      Debs2015Q1Runner.Metrics(
        events = metrics.events,
        parsed = metrics.parsed,
        outliersOrInvalid = metrics.outliersOrInvalid,
        outputs = metrics.outputs,
        elapsedNanos = metrics.elapsedNanos,
        latencyMillis = metrics.latencyMillis
      )
    )
}

@main def Debs2015Q1CheckedProcessingRun(
    inputPath: String,
    outputPath: String,
    mode: String = "checked-processing"
): Unit = {
  val metrics =
    Debs2015Q1CheckedProcessingRunner.run(inputPath, outputPath, mode)
  Debs2015Q1CheckedProcessingRunner.printMetrics(metrics)
}
