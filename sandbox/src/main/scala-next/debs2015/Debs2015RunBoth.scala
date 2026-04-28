package debs2015

import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object Debs2015RunBothRunner {
  final case class PhaseMetrics(
      readNanos: Long,
      parseNanos: Long,
      q1ProcessNanos: Long,
      q1ChangeNanos: Long,
      q1OutputNanos: Long,
      q1SnapshotNanos: Long,
      q2ProcessNanos: Long,
      q2ChangeNanos: Long,
      q2OutputNanos: Long,
      q2SnapshotNanos: Long,
      closeNanos: Long
  ) {
    def trackedNanos: Long =
      readNanos +
        parseNanos +
        q1ProcessNanos +
        q1ChangeNanos +
        q1OutputNanos +
        q2ProcessNanos +
        q2ChangeNanos +
        q2OutputNanos +
        closeNanos
  }

  private object PhaseIndex {
    final val Read = 0
    final val Parse = 1
    final val Q1Process = 2
    final val Q1Change = 3
    final val Q1Output = 4
    final val Q1Snapshot = 5
    final val Q2Process = 6
    final val Q2Change = 7
    final val Q2Output = 8
    final val Q2Snapshot = 9
    final val Close = 10
    final val Count = 11
  }

  final case class PhaseGcAllocMetrics(
      totals: Array[Long],
      bytes: Array[Long],
      nanos: Array[Long]
  ) {
    def total(index: Int): Long = totals(index)
    def byteCount(index: Int): Long = bytes(index)
    def timeNanos(index: Int): Long = nanos(index)
  }

  private object PhaseGcAllocMetrics {
    val zero: PhaseGcAllocMetrics =
      PhaseGcAllocMetrics(
        new Array[Long](PhaseIndex.Count),
        new Array[Long](PhaseIndex.Count),
        new Array[Long](PhaseIndex.Count)
      )
  }

  private final class PhaseGcAllocAccumulator(enabled: Boolean) {
    def enter(index: Int): Unit =
      if (enabled) GC.enterStatsAllocationPhase(index)

    def clear(): Unit =
      if (enabled) GC.enterStatsAllocationPhase(-1)

    def result(): PhaseGcAllocMetrics =
      if (enabled) {
        val totals = new Array[Long](PhaseIndex.Count)
        val bytes = new Array[Long](PhaseIndex.Count)
        val nanos = new Array[Long](PhaseIndex.Count)
        var i = 0
        while (i < PhaseIndex.Count) {
          totals(i) = GC.getStatsAllocationPhaseTotal(i).toLong
          bytes(i) = GC.getStatsAllocationPhaseBytesTotal(i).toLong
          nanos(i) = GC.getStatsAllocationPhaseDurationTotal(i).toLong
          i += 1
        }
        PhaseGcAllocMetrics(totals, bytes, nanos)
      } else PhaseGcAllocMetrics.zero
  }

  final case class RuntimeMetrics(
      gcCollections: Long,
      gcNanos: Long,
      gcAllocTotal: Long,
      gcAllocBytesTotal: Long,
      gcAllocNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocRawTotal: Long,
      riftAllocRawBytesTotal: Long,
      riftAllocObjectTotal: Long,
      riftAllocSlowTotal: Long,
      riftMmapSlabTotal: Long,
      riftMmapBytesTotal: Long,
      riftMmapSlabCurrent: Long,
      riftMmapSlabPeak: Long,
      riftMmapBytesCurrent: Long,
      riftMmapBytesPeak: Long,
      riftActiveSlabCurrent: Long,
      riftActiveSlabPeak: Long,
      riftActiveBytesCurrent: Long,
      riftActiveBytesPeak: Long,
      riftActiveAllocBytesCurrent: Long,
      riftActiveAllocBytesPeak: Long,
      riftTlsReuseTotal: Long,
      riftPoolReuseTotal: Long,
      riftRegionOpNanos: Long,
      riftOpenNanos: Long,
      riftCloseNanos: Long,
      riftResetNanos: Long,
      riftSlowAllocNanos: Long,
      riftFamilyAllocRawBytesTotal: Array[Long],
      riftFamilyActiveBytesPeak: Array[Long],
      riftFamilyActiveAllocBytesPeak: Array[Long],
      riftPoolSlabs: Long,
      riftPoolBytes: Long
  )

  private object RuntimeMetrics {
    val zero: RuntimeMetrics =
      RuntimeMetrics(
        gcCollections = 0L,
        gcNanos = 0L,
        gcAllocTotal = 0L,
        gcAllocBytesTotal = 0L,
        gcAllocNanos = 0L,
        riftRegionOpenTotal = 0L,
        riftRegionCloseTotal = 0L,
        riftRegionResetTotal = 0L,
        riftAllocRawTotal = 0L,
        riftAllocRawBytesTotal = 0L,
        riftAllocObjectTotal = 0L,
        riftAllocSlowTotal = 0L,
        riftMmapSlabTotal = 0L,
        riftMmapBytesTotal = 0L,
        riftMmapSlabCurrent = 0L,
        riftMmapSlabPeak = 0L,
        riftMmapBytesCurrent = 0L,
        riftMmapBytesPeak = 0L,
        riftActiveSlabCurrent = 0L,
        riftActiveSlabPeak = 0L,
        riftActiveBytesCurrent = 0L,
        riftActiveBytesPeak = 0L,
        riftActiveAllocBytesCurrent = 0L,
        riftActiveAllocBytesPeak = 0L,
        riftTlsReuseTotal = 0L,
        riftPoolReuseTotal = 0L,
        riftRegionOpNanos = 0L,
        riftOpenNanos = 0L,
        riftCloseNanos = 0L,
        riftResetNanos = 0L,
        riftSlowAllocNanos = 0L,
        riftFamilyAllocRawBytesTotal = DebsRegionFamilies.emptyLongs(),
        riftFamilyActiveBytesPeak = DebsRegionFamilies.emptyLongs(),
        riftFamilyActiveAllocBytesPeak = DebsRegionFamilies.emptyLongs(),
        riftPoolSlabs = 0L,
        riftPoolBytes = 0L
      )

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    private def captureFamilyAllocRawBytesTotal(): Array[Long] = {
      val result = DebsRegionFamilies.emptyLongs()
      var family = 1
      while (family < DebsRegionFamilies.Count) {
        result(family) =
          rawSizeToLong(
            RiftAllocator.Impl.statsFamilyAllocRawBytesTotal(family)
          )
        family += 1
      }
      result
    }

    private def captureFamilyActiveBytesPeak(): Array[Long] = {
      val result = DebsRegionFamilies.emptyLongs()
      var family = 1
      while (family < DebsRegionFamilies.Count) {
        result(family) =
          rawSizeToLong(RiftAllocator.Impl.statsFamilyActiveBytesPeak(family))
        family += 1
      }
      result
    }

    private def captureFamilyActiveAllocBytesPeak(): Array[Long] = {
      val result = DebsRegionFamilies.emptyLongs()
      var family = 1
      while (family < DebsRegionFamilies.Count) {
        result(family) =
          rawSizeToLong(
            RiftAllocator.Impl.statsFamilyActiveAllocBytesPeak(family)
          )
        family += 1
      }
      result
    }

    private def deltaFamily(end: Array[Long], start: Array[Long]): Array[Long] = {
      val result = DebsRegionFamilies.emptyLongs()
      var family = 1
      while (family < DebsRegionFamilies.Count) {
        result(family) = delta(end(family), start(family))
        family += 1
      }
      result
    }

    def capture(includeRift: Boolean): RuntimeMetrics = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)
      val gcAllocTotal = nonNegative(GC.getStatsAllocationTotal().toLong)
      val gcAllocBytesTotal =
        nonNegative(GC.getStatsAllocationBytesTotal().toLong)
      val gcAllocNanos =
        nonNegative(GC.getStatsAllocationDurationTotal().toLong)

      if (!includeRift) {
        zero.copy(
          gcCollections = gcCollections,
          gcNanos = gcNanos,
          gcAllocTotal = gcAllocTotal,
          gcAllocBytesTotal = gcAllocBytesTotal,
          gcAllocNanos = gcAllocNanos
        )
      } else {
        RuntimeMetrics(
          gcCollections = gcCollections,
          gcNanos = gcNanos,
          gcAllocTotal = gcAllocTotal,
          gcAllocBytesTotal = gcAllocBytesTotal,
          gcAllocNanos = gcAllocNanos,
          riftRegionOpenTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpenTotal()),
          riftRegionCloseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionCloseTotal()),
          riftRegionResetTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal()),
          riftAllocRawTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocRawTotal()),
          riftAllocRawBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocRawBytesTotal()),
          riftAllocObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          riftAllocSlowTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocSlowTotal()),
          riftMmapSlabTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabTotal()),
          riftMmapBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesTotal()),
          riftMmapSlabCurrent =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabCurrent()),
          riftMmapSlabPeak =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabPeak()),
          riftMmapBytesCurrent =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesCurrent()),
          riftMmapBytesPeak =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesPeak()),
          riftActiveSlabCurrent =
            rawSizeToLong(RiftAllocator.Impl.statsActiveSlabCurrent()),
          riftActiveSlabPeak =
            rawSizeToLong(RiftAllocator.Impl.statsActiveSlabPeak()),
          riftActiveBytesCurrent =
            rawSizeToLong(RiftAllocator.Impl.statsActiveBytesCurrent()),
          riftActiveBytesPeak =
            rawSizeToLong(RiftAllocator.Impl.statsActiveBytesPeak()),
          riftActiveAllocBytesCurrent =
            rawSizeToLong(RiftAllocator.Impl.statsActiveAllocBytesCurrent()),
          riftActiveAllocBytesPeak =
            rawSizeToLong(RiftAllocator.Impl.statsActiveAllocBytesPeak()),
          riftTlsReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsTlsReuseTotal()),
          riftPoolReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsPoolReuseTotal()),
          riftRegionOpNanos =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftOpenNanos = rawSizeToLong(RiftAllocator.Impl.statsOpenNanos()),
          riftCloseNanos = rawSizeToLong(RiftAllocator.Impl.statsCloseNanos()),
          riftResetNanos = rawSizeToLong(RiftAllocator.Impl.statsResetNanos()),
          riftSlowAllocNanos =
            rawSizeToLong(RiftAllocator.Impl.statsSlowAllocNanos()),
          riftFamilyAllocRawBytesTotal =
            captureFamilyAllocRawBytesTotal(),
          riftFamilyActiveBytesPeak =
            captureFamilyActiveBytesPeak(),
          riftFamilyActiveAllocBytesPeak =
            captureFamilyActiveAllocBytesPeak(),
          riftPoolSlabs = rawSizeToLong(RiftAllocator.Impl.poolSlabCount()),
          riftPoolBytes = rawSizeToLong(RiftAllocator.Impl.poolResidentBytes())
        )
      }
    }

    def since(start: RuntimeMetrics, end: RuntimeMetrics): RuntimeMetrics =
      RuntimeMetrics(
        gcCollections = delta(end.gcCollections, start.gcCollections),
        gcNanos = delta(end.gcNanos, start.gcNanos),
        gcAllocTotal = delta(end.gcAllocTotal, start.gcAllocTotal),
        gcAllocBytesTotal =
          delta(end.gcAllocBytesTotal, start.gcAllocBytesTotal),
        gcAllocNanos = delta(end.gcAllocNanos, start.gcAllocNanos),
        riftRegionOpenTotal =
          delta(end.riftRegionOpenTotal, start.riftRegionOpenTotal),
        riftRegionCloseTotal =
          delta(end.riftRegionCloseTotal, start.riftRegionCloseTotal),
        riftRegionResetTotal =
          delta(end.riftRegionResetTotal, start.riftRegionResetTotal),
        riftAllocRawTotal =
          delta(end.riftAllocRawTotal, start.riftAllocRawTotal),
        riftAllocRawBytesTotal =
          delta(end.riftAllocRawBytesTotal, start.riftAllocRawBytesTotal),
        riftAllocObjectTotal =
          delta(end.riftAllocObjectTotal, start.riftAllocObjectTotal),
        riftAllocSlowTotal =
          delta(end.riftAllocSlowTotal, start.riftAllocSlowTotal),
        riftMmapSlabTotal =
          delta(end.riftMmapSlabTotal, start.riftMmapSlabTotal),
        riftMmapBytesTotal =
          delta(end.riftMmapBytesTotal, start.riftMmapBytesTotal),
        riftMmapSlabCurrent = end.riftMmapSlabCurrent,
        riftMmapSlabPeak = end.riftMmapSlabPeak,
        riftMmapBytesCurrent = end.riftMmapBytesCurrent,
        riftMmapBytesPeak = end.riftMmapBytesPeak,
        riftActiveSlabCurrent = end.riftActiveSlabCurrent,
        riftActiveSlabPeak = end.riftActiveSlabPeak,
        riftActiveBytesCurrent = end.riftActiveBytesCurrent,
        riftActiveBytesPeak = end.riftActiveBytesPeak,
        riftActiveAllocBytesCurrent = end.riftActiveAllocBytesCurrent,
        riftActiveAllocBytesPeak = end.riftActiveAllocBytesPeak,
        riftTlsReuseTotal =
          delta(end.riftTlsReuseTotal, start.riftTlsReuseTotal),
        riftPoolReuseTotal =
          delta(end.riftPoolReuseTotal, start.riftPoolReuseTotal),
        riftRegionOpNanos =
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftOpenNanos = delta(end.riftOpenNanos, start.riftOpenNanos),
        riftCloseNanos = delta(end.riftCloseNanos, start.riftCloseNanos),
        riftResetNanos = delta(end.riftResetNanos, start.riftResetNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos),
        riftFamilyAllocRawBytesTotal =
          deltaFamily(
            end.riftFamilyAllocRawBytesTotal,
            start.riftFamilyAllocRawBytesTotal
          ),
        riftFamilyActiveBytesPeak = end.riftFamilyActiveBytesPeak,
        riftFamilyActiveAllocBytesPeak = end.riftFamilyActiveAllocBytesPeak,
        riftPoolSlabs = end.riftPoolSlabs,
        riftPoolBytes = end.riftPoolBytes
      )
  }

  final case class Metrics(
      events: Long,
      parsed: Long,
      invalid: Long,
      q1Outputs: Long,
      q2Outputs: Long,
      elapsedNanos: Long,
      q1LatencyMillis: Array[Long],
      q2LatencyMillis: Array[Long],
      phases: PhaseMetrics,
      phaseGcAlloc: PhaseGcAllocMetrics,
      runtime: RuntimeMetrics,
      counters: Debs2015Counters.Snapshot,
      processDiagnostics: Debs2015ProcessDiagnostics.Snapshot,
      q2CpuDiagnostics: Debs2015Q2CpuDiagnostics.Snapshot
  ) {
    def elapsedMillis: Double = elapsedNanos.toDouble / 1000000.0

    def throughputEventsPerSecond: Double =
      if (elapsedNanos <= 0L) 0.0
      else events.toDouble * 1000000000.0 / elapsedNanos.toDouble
  }

  private final class LongSampleBuffer(
      useRegions: Boolean,
      region: RiftRegion,
      initialCapacity: Int
  ) {
    private var values = allocate(initialCapacity)
    private var used = 0

    def +=(value: Long): Unit = {
      ensureCapacity(used + 1)
      values(used) = value
      used += 1
    }

    def toArray: Array[Long] = {
      val result = new Array[Long](used)
      Array.copy(values, 0, result, 0, used)
      result
    }

    private def ensureCapacity(required: Int): Unit =
      if (required > values.length) {
        var next = values.length
        while (required > next)
          next *= 2
        val expanded = allocate(next)
        Array.copy(values, 0, expanded, 0, used)
        values = expanded
      }

    private def allocate(size: Int): Array[Long] =
      if (useRegions) region.alloc(new Array[Long](size))
      else new Array[Long](size)
  }

  def run(
      inputPath: String,
      q1OutputPath: String,
      q2OutputPath: String,
      q1Mode: String
  ): Metrics = {
    if (q1Mode == "rift-checked")
      return runChecked(inputPath, q1OutputPath, q2OutputPath, q1Mode)

    val usesRift = q1Mode.startsWith("rift-")
    if (usesRift) {
      RiftRegion.init(0)
      RiftAllocator.Impl.statsReset()
    }
    Debs2015Counters.reset()
    Debs2015ProcessDiagnostics.reset()
    Debs2015Q2CpuDiagnostics.reset()
    val counterStart = Debs2015Counters.snapshot()
    val processDiagnosticsStart = Debs2015ProcessDiagnostics.snapshot()
    val q2CpuDiagnosticsStart = Debs2015Q2CpuDiagnostics.snapshot()
    val runtimeStart = RuntimeMetrics.capture(usesRift)
    var runtimeEnd = runtimeStart
    val phaseGcAlloc =
      new PhaseGcAllocAccumulator(gcAllocAttributionEnabled())

    val q1 = Debs2015Q1Runner.createEngine(q1Mode)
    val q2 = Debs2015Q2Runner.createEngine(q1Mode)
    val source = new CsvLineReader(inputPath, q1Mode)
    val snapshotRegion =
      if (usesRift) RiftRegion.open(regionKindForMode(q1Mode)) else null
    val q1Writer = OutputSupport.ByteRowWriter.open(q1OutputPath, snapshotRegion)
    val q2Writer = OutputSupport.ByteRowWriter.open(q2OutputPath, snapshotRegion)
    val q1Latencies = new LongSampleBuffer(usesRift, snapshotRegion, 1024)
    val q2Latencies = new LongSampleBuffer(usesRift, snapshotRegion, 1024)
    val trip = Trip.empty

    var previousQ1 = Q1Output.EmptySnapshot
    var previousQ2 = Q2Output.EmptySnapshot
    var events = 0L
    var parsed = 0L
    var invalid = 0L
    var q1Outputs = 0L
    var q2Outputs = 0L
    var readNanos = 0L
    var parseNanos = 0L
    var q1ProcessNanos = 0L
    var q1ChangeNanos = 0L
    var q1OutputNanos = 0L
    var q1SnapshotNanos = 0L
    var q2ProcessNanos = 0L
    var q2ChangeNanos = 0L
    var q2OutputNanos = 0L
    var q2SnapshotNanos = 0L
    var closeNanos = 0L
    var q1LatencyMillis = Array.emptyLongArray
    var q2LatencyMillis = Array.emptyLongArray
    val started = System.nanoTime()

    try {
      while ({
        val readStarted = System.nanoTime()
        phaseGcAlloc.enter(PhaseIndex.Read)
        val hasNext = source.nextLine()
        phaseGcAlloc.clear()
        readNanos += System.nanoTime() - readStarted
        hasNext
      }) {
        val readAt = System.nanoTime()
        events += 1L

        val parseStarted = System.nanoTime()
        phaseGcAlloc.enter(PhaseIndex.Parse)
        val parsedTrip =
          Trip.parseInto(source.bytes, source.lineStart, source.lineEnd, trip)
        phaseGcAlloc.clear()
        val parseFinished = System.nanoTime()
        parseNanos += parseFinished - parseStarted

        if (parsedTrip) {
            parsed += 1L

            val q1Started = System.nanoTime()
            phaseGcAlloc.enter(PhaseIndex.Q1Process)
            val q1Current = q1.process(trip)
            phaseGcAlloc.clear()
            val q1Finished = System.nanoTime()
            q1ProcessNanos += q1Finished - q1Started
            val q1ChangeStarted = System.nanoTime()
            phaseGcAlloc.enter(PhaseIndex.Q1Change)
            val q1Changed =
              q1Current.nonEmpty && Q1Output.changed(previousQ1, q1Current)
            phaseGcAlloc.clear()
            q1ChangeNanos += System.nanoTime() - q1ChangeStarted
            if (q1Changed) {
              val q1OutputStarted = System.nanoTime()
              phaseGcAlloc.enter(PhaseIndex.Q1Output)
              val writeAt = q1OutputStarted
              val delayMillis = (writeAt - readAt) / 1000000L
              Q1Output.writeRow(q1Writer, trip, q1Current, delayMillis)
              q1Writer.newLine()
              q1Latencies += delayMillis
              Debs2015Counters.recordQ1LatencyAppend()
              q1Outputs += 1L
              val q1SnapshotStarted = System.nanoTime()
              phaseGcAlloc.enter(PhaseIndex.Q1Snapshot)
              previousQ1 = Q1Output.snapshot(q1Current, snapshotRegion)
              phaseGcAlloc.clear()
              q1SnapshotNanos += System.nanoTime() - q1SnapshotStarted
              q1OutputNanos += System.nanoTime() - q1OutputStarted
            }

            val q2Started = System.nanoTime()
            phaseGcAlloc.enter(PhaseIndex.Q2Process)
            val q2Current = q2.process(trip)
            phaseGcAlloc.clear()
            val q2Finished = System.nanoTime()
            q2ProcessNanos += q2Finished - q2Started
            val q2ChangeStarted = System.nanoTime()
            phaseGcAlloc.enter(PhaseIndex.Q2Change)
            val q2Changed =
              q2Current.nonEmpty && Q2Output.changed(previousQ2, q2Current)
            phaseGcAlloc.clear()
            q2ChangeNanos += System.nanoTime() - q2ChangeStarted
            if (q2Changed) {
              val q2OutputStarted = System.nanoTime()
              phaseGcAlloc.enter(PhaseIndex.Q2Output)
              val writeAt = q2OutputStarted
              val delayMillis = (writeAt - readAt) / 1000000L
              Q2Output.writeRow(q2Writer, trip, q2Current, delayMillis)
              q2Writer.newLine()
              q2Latencies += delayMillis
              Debs2015Counters.recordQ2LatencyAppend()
              q2Outputs += 1L
              val q2SnapshotStarted = System.nanoTime()
              phaseGcAlloc.enter(PhaseIndex.Q2Snapshot)
              previousQ2 = Q2Output.snapshot(q2Current, snapshotRegion)
              phaseGcAlloc.clear()
              q2SnapshotNanos += System.nanoTime() - q2SnapshotStarted
              q2OutputNanos += System.nanoTime() - q2OutputStarted
            }
        } else {
          invalid += 1L
        }
      }
    } finally {
      val closeStarted = System.nanoTime()
      phaseGcAlloc.enter(PhaseIndex.Close)
      q2Writer.close()
      q1Writer.close()
      q1LatencyMillis = q1Latencies.toArray
      q2LatencyMillis = q2Latencies.toArray
      source.close()
      q2.close()
      q1.close()
      if (snapshotRegion != null) snapshotRegion.close()
      closeNanos = System.nanoTime() - closeStarted
      phaseGcAlloc.clear()
      runtimeEnd = RuntimeMetrics.capture(usesRift)
      if (usesRift) RiftRegion.shutdown()
    }

    Metrics(
      events = events,
      parsed = parsed,
      invalid = invalid,
      q1Outputs = q1Outputs,
      q2Outputs = q2Outputs,
      elapsedNanos = System.nanoTime() - started,
      q1LatencyMillis = q1LatencyMillis,
      q2LatencyMillis = q2LatencyMillis,
      phases = PhaseMetrics(
        readNanos = readNanos,
        parseNanos = parseNanos,
        q1ProcessNanos = q1ProcessNanos,
        q1ChangeNanos = q1ChangeNanos,
        q1OutputNanos = q1OutputNanos,
        q1SnapshotNanos = q1SnapshotNanos,
        q2ProcessNanos = q2ProcessNanos,
        q2ChangeNanos = q2ChangeNanos,
        q2OutputNanos = q2OutputNanos,
        q2SnapshotNanos = q2SnapshotNanos,
        closeNanos = closeNanos
      ),
      phaseGcAlloc = phaseGcAlloc.result(),
      runtime = RuntimeMetrics.since(runtimeStart, runtimeEnd),
      counters = Debs2015Counters.snapshot().since(counterStart),
      processDiagnostics =
        Debs2015ProcessDiagnostics.snapshot().since(processDiagnosticsStart),
      q2CpuDiagnostics =
        Debs2015Q2CpuDiagnostics.snapshot().since(q2CpuDiagnosticsStart)
    )
  }

  private def runChecked(
      inputPath: String,
      q1OutputPath: String,
      q2OutputPath: String,
      q1Mode: String
  ): Metrics = {
    RiftRegion.init(0)
    RiftAllocator.Impl.statsReset()
    Debs2015Counters.reset()
    Debs2015ProcessDiagnostics.reset()
    Debs2015Q2CpuDiagnostics.reset()
    val counterStart = Debs2015Counters.snapshot()
    val processDiagnosticsStart = Debs2015ProcessDiagnostics.snapshot()
    val q2CpuDiagnosticsStart = Debs2015Q2CpuDiagnostics.snapshot()
    val runtimeStart = RuntimeMetrics.capture(includeRift = true)
    var runtimeEnd = runtimeStart
    val phaseGcAlloc =
      new PhaseGcAllocAccumulator(gcAllocAttributionEnabled())

    var events = 0L
    var parsed = 0L
    var invalid = 0L
    var q1Outputs = 0L
    var q2Outputs = 0L
    var readNanos = 0L
    var parseNanos = 0L
    var q1ProcessNanos = 0L
    var q1ChangeNanos = 0L
    var q1OutputNanos = 0L
    var q1SnapshotNanos = 0L
    var q2ProcessNanos = 0L
    var q2ChangeNanos = 0L
    var q2OutputNanos = 0L
    var q2SnapshotNanos = 0L
    var closeNanos = 0L
    var q1LatencyMillis = Array.emptyLongArray
    var q2LatencyMillis = Array.emptyLongArray
    var snapshotRegion: RiftRegion = null
    val started = System.nanoTime()

    try {
      snapshotRegion = RiftRegion.open(RiftRegion.Streaming)
      DebsRegionFamilies.set(snapshotRegion, DebsRegionFamilies.Snapshot)
      RiftRegion.streaming { stream ?=>
        DebsRegionFamilies.set(stream, DebsRegionFamilies.CheckedParent)
        Debs2015Q1CheckedProcessingRunner.withCheckedProcessor { q1 =>
          Debs2015Q2CheckedProcessingRunner.withCheckedProcessor { q2 =>
            val source = new CsvLineReader(inputPath, "rift-streaming")
            val q1Writer =
              OutputSupport.ByteRowWriter.open(q1OutputPath, snapshotRegion)
            val q2Writer =
              OutputSupport.ByteRowWriter.open(q2OutputPath, snapshotRegion)
            val q1Latencies =
              new LongSampleBuffer(useRegions = true, snapshotRegion, 1024)
            val q2Latencies =
              new LongSampleBuffer(useRegions = true, snapshotRegion, 1024)
            val trip = Trip.empty

            var previousQ1 = Q1Output.EmptySnapshot
            var previousQ2 = Q2Output.EmptySnapshot

            try {
              while ({
                val readStarted = System.nanoTime()
                phaseGcAlloc.enter(PhaseIndex.Read)
                val hasNext = source.nextLine()
                phaseGcAlloc.clear()
                readNanos += System.nanoTime() - readStarted
                hasNext
              }) {
                val readAt = System.nanoTime()
                events += 1L

                val parseStarted = System.nanoTime()
                phaseGcAlloc.enter(PhaseIndex.Parse)
                val parsedTrip =
                  Trip.parseInto(
                    source.bytes,
                    source.lineStart,
                    source.lineEnd,
                    trip
                  )
                phaseGcAlloc.clear()
                parseNanos += System.nanoTime() - parseStarted

                if (parsedTrip) {
                  parsed += 1L

                  val q1Started = System.nanoTime()
                  phaseGcAlloc.enter(PhaseIndex.Q1Process)
                  val q1Size = q1.process(trip)
                  phaseGcAlloc.clear()
                  q1ProcessNanos += System.nanoTime() - q1Started

                  val q1ChangeStarted = System.nanoTime()
                  phaseGcAlloc.enter(PhaseIndex.Q1Change)
                  val q1Changed =
                    q1Size != 0 && q1.changed(previousQ1, q1Size)
                  phaseGcAlloc.clear()
                  q1ChangeNanos += System.nanoTime() - q1ChangeStarted
                  if (q1Changed) {
                    val q1OutputStarted = System.nanoTime()
                    phaseGcAlloc.enter(PhaseIndex.Q1Output)
                    val delayMillis = (q1OutputStarted - readAt) / 1000000L
                    q1.writeRow(q1Writer, trip, q1Size, delayMillis)
                    q1Writer.newLine()
                    q1Latencies += delayMillis
                    Debs2015Counters.recordQ1LatencyAppend()
                    q1Outputs += 1L
                    val q1SnapshotStarted = System.nanoTime()
                    phaseGcAlloc.enter(PhaseIndex.Q1Snapshot)
                    previousQ1 = q1.snapshot(q1Size)
                    phaseGcAlloc.clear()
                    q1SnapshotNanos += System.nanoTime() - q1SnapshotStarted
                    q1OutputNanos += System.nanoTime() - q1OutputStarted
                  }

                  val q2Started = System.nanoTime()
                  phaseGcAlloc.enter(PhaseIndex.Q2Process)
                  val q2Size = q2.process(trip)
                  phaseGcAlloc.clear()
                  q2ProcessNanos += System.nanoTime() - q2Started

                  val q2ChangeStarted = System.nanoTime()
                  phaseGcAlloc.enter(PhaseIndex.Q2Change)
                  val q2Changed =
                    q2Size != 0 && q2.changed(previousQ2, q2Size)
                  phaseGcAlloc.clear()
                  q2ChangeNanos += System.nanoTime() - q2ChangeStarted
                  if (q2Changed) {
                    val q2OutputStarted = System.nanoTime()
                    phaseGcAlloc.enter(PhaseIndex.Q2Output)
                    val delayMillis = (q2OutputStarted - readAt) / 1000000L
                    q2.writeRow(q2Writer, trip, q2Size, delayMillis)
                    q2Writer.newLine()
                    q2Latencies += delayMillis
                    Debs2015Counters.recordQ2LatencyAppend()
                    q2Outputs += 1L
                    val q2SnapshotStarted = System.nanoTime()
                    phaseGcAlloc.enter(PhaseIndex.Q2Snapshot)
                    previousQ2 = q2.snapshot(q2Size)
                    phaseGcAlloc.clear()
                    q2SnapshotNanos += System.nanoTime() - q2SnapshotStarted
                    q2OutputNanos += System.nanoTime() - q2OutputStarted
                  }
                } else {
                  invalid += 1L
                }
              }
            } finally {
              val closeStarted = System.nanoTime()
              phaseGcAlloc.enter(PhaseIndex.Close)
              q2Writer.close()
              q1Writer.close()
              q1LatencyMillis = q1Latencies.toArray
              q2LatencyMillis = q2Latencies.toArray
              source.close()
              closeNanos = System.nanoTime() - closeStarted
              phaseGcAlloc.clear()
            }
          }
        }
      }
      val snapshotCloseStarted = System.nanoTime()
      phaseGcAlloc.enter(PhaseIndex.Close)
      snapshotRegion.close()
      snapshotRegion = null
      closeNanos += System.nanoTime() - snapshotCloseStarted
      phaseGcAlloc.clear()
      runtimeEnd = RuntimeMetrics.capture(includeRift = true)
    } finally {
      if (snapshotRegion != null)
        snapshotRegion.close()
      RiftRegion.shutdown()
    }

    Metrics(
      events = events,
      parsed = parsed,
      invalid = invalid,
      q1Outputs = q1Outputs,
      q2Outputs = q2Outputs,
      elapsedNanos = System.nanoTime() - started,
      q1LatencyMillis = q1LatencyMillis,
      q2LatencyMillis = q2LatencyMillis,
      phases = PhaseMetrics(
        readNanos = readNanos,
        parseNanos = parseNanos,
        q1ProcessNanos = q1ProcessNanos,
        q1ChangeNanos = q1ChangeNanos,
        q1OutputNanos = q1OutputNanos,
        q1SnapshotNanos = q1SnapshotNanos,
        q2ProcessNanos = q2ProcessNanos,
        q2ChangeNanos = q2ChangeNanos,
        q2OutputNanos = q2OutputNanos,
        q2SnapshotNanos = q2SnapshotNanos,
        closeNanos = closeNanos
      ),
      phaseGcAlloc = phaseGcAlloc.result(),
      runtime = RuntimeMetrics.since(runtimeStart, runtimeEnd),
      counters = Debs2015Counters.snapshot().since(counterStart),
      processDiagnostics =
        Debs2015ProcessDiagnostics.snapshot().since(processDiagnosticsStart),
      q2CpuDiagnostics =
        Debs2015Q2CpuDiagnostics.snapshot().since(q2CpuDiagnosticsStart)
    )
  }

  private def gcAllocAttributionEnabled(): Boolean = {
    val env = System.getenv("SCALANATIVE_GC_ALLOC_STATS")
    env != null && env.nonEmpty && env != "0"
  }

  private def regionKindForMode(mode: String): Int =
    mode match {
      case "rift-streaming" => RiftRegion.Streaming
      case _                => RiftRegion.HPZone
    }

  private def percentile(sorted: Array[Long], fraction: Double): Long = {
    if (sorted.isEmpty) 0L
    else {
      val idx = math.ceil(fraction * sorted.length.toDouble).toInt - 1
      sorted(math.max(0, math.min(sorted.length - 1, idx)))
    }
  }

  def printMetrics(metrics: Metrics, q1Mode: String): Unit = {
    val q1Sorted = metrics.q1LatencyMillis.clone()
    val q2Sorted = metrics.q2LatencyMillis.clone()
    val runtime = metrics.runtime
    val phases = metrics.phases
    val phaseGcAlloc = metrics.phaseGcAlloc
    val counters = metrics.counters
    val processDiagnostics = metrics.processDiagnostics
    val q2CpuDiagnostics = metrics.q2CpuDiagnostics
    val trackedNanos = phases.trackedNanos
    val untrackedNanos =
      if (metrics.elapsedNanos > trackedNanos) metrics.elapsedNanos - trackedNanos
      else 0L
    scala.util.Sorting.quickSort(q1Sorted)
    scala.util.Sorting.quickSort(q2Sorted)

    println(
      f"DEBS2015_RUNBOTH_RESULT q1_mode=$q1Mode events=${metrics.events}%d " +
        f"parsed=${metrics.parsed}%d invalid=${metrics.invalid}%d " +
        f"q1_outputs=${metrics.q1Outputs}%d q2_outputs=${metrics.q2Outputs}%d " +
        f"elapsed_ms=${metrics.elapsedMillis}%.3f throughput_eps=${metrics.throughputEventsPerSecond}%.3f " +
        f"phase_read_ns=${phases.readNanos}%d " +
        f"phase_parse_ns=${phases.parseNanos}%d " +
        f"phase_q1_process_ns=${phases.q1ProcessNanos}%d " +
        f"phase_q1_change_ns=${phases.q1ChangeNanos}%d " +
        f"phase_q1_output_ns=${phases.q1OutputNanos}%d " +
        f"phase_q1_snapshot_ns=${phases.q1SnapshotNanos}%d " +
        f"phase_q2_process_ns=${phases.q2ProcessNanos}%d " +
        f"phase_q2_change_ns=${phases.q2ChangeNanos}%d " +
        f"phase_q2_output_ns=${phases.q2OutputNanos}%d " +
        f"phase_q2_snapshot_ns=${phases.q2SnapshotNanos}%d " +
        f"phase_close_ns=${phases.closeNanos}%d " +
        f"phase_tracked_ns=${trackedNanos}%d " +
        f"phase_untracked_ns=${untrackedNanos}%d " +
        phaseGcAllocFields("read", PhaseIndex.Read, phaseGcAlloc) +
        phaseGcAllocFields("parse", PhaseIndex.Parse, phaseGcAlloc) +
        phaseGcAllocFields("q1_process", PhaseIndex.Q1Process, phaseGcAlloc) +
        phaseGcAllocFields("q1_change", PhaseIndex.Q1Change, phaseGcAlloc) +
        phaseGcAllocFields("q1_output", PhaseIndex.Q1Output, phaseGcAlloc) +
        phaseGcAllocFields("q1_snapshot", PhaseIndex.Q1Snapshot, phaseGcAlloc) +
        phaseGcAllocFields("q2_process", PhaseIndex.Q2Process, phaseGcAlloc) +
        phaseGcAllocFields("q2_change", PhaseIndex.Q2Change, phaseGcAlloc) +
        phaseGcAllocFields("q2_output", PhaseIndex.Q2Output, phaseGcAlloc) +
        phaseGcAllocFields("q2_snapshot", PhaseIndex.Q2Snapshot, phaseGcAlloc) +
        phaseGcAllocFields("close", PhaseIndex.Close, phaseGcAlloc) +
        f"q1_p50_ms=${percentile(q1Sorted, 0.50)}%d " +
        f"q1_p99_ms=${percentile(q1Sorted, 0.99)}%d " +
        f"q1_p999_ms=${percentile(q1Sorted, 0.999)}%d " +
        f"q1_max_ms=${if (q1Sorted.isEmpty) 0L else q1Sorted.last}%d " +
        f"q2_p50_ms=${percentile(q2Sorted, 0.50)}%d " +
        f"q2_p99_ms=${percentile(q2Sorted, 0.99)}%d " +
        f"q2_p999_ms=${percentile(q2Sorted, 0.999)}%d " +
        f"q2_max_ms=${if (q2Sorted.isEmpty) 0L else q2Sorted.last}%d " +
        f"gc_collections=${runtime.gcCollections}%d " +
        f"gc_time_ns=${runtime.gcNanos}%d " +
        f"gc_alloc_total=${runtime.gcAllocTotal}%d " +
        f"gc_alloc_bytes_total=${runtime.gcAllocBytesTotal}%d " +
        f"gc_alloc_time_ns=${runtime.gcAllocNanos}%d " +
        f"rift_region_op_ns=${runtime.riftRegionOpNanos}%d " +
        f"rift_open_ns=${runtime.riftOpenNanos}%d " +
        f"rift_close_ns=${runtime.riftCloseNanos}%d " +
        f"rift_reset_ns=${runtime.riftResetNanos}%d " +
        f"rift_slow_alloc_ns=${runtime.riftSlowAllocNanos}%d " +
        f"rift_open_total=${runtime.riftRegionOpenTotal}%d " +
        f"rift_close_total=${runtime.riftRegionCloseTotal}%d " +
        f"rift_reset_total=${runtime.riftRegionResetTotal}%d " +
        f"rift_alloc_raw_total=${runtime.riftAllocRawTotal}%d " +
        f"rift_alloc_raw_bytes_total=${runtime.riftAllocRawBytesTotal}%d " +
        f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d " +
        f"rift_alloc_slow_total=${runtime.riftAllocSlowTotal}%d " +
        f"rift_mmap_slab_total=${runtime.riftMmapSlabTotal}%d " +
        f"rift_mmap_bytes_total=${runtime.riftMmapBytesTotal}%d " +
        f"rift_mmap_slab_current=${runtime.riftMmapSlabCurrent}%d " +
        f"rift_mmap_slab_peak=${runtime.riftMmapSlabPeak}%d " +
        f"rift_mmap_bytes_current=${runtime.riftMmapBytesCurrent}%d " +
        f"rift_mmap_bytes_peak=${runtime.riftMmapBytesPeak}%d " +
        f"rift_active_slab_current=${runtime.riftActiveSlabCurrent}%d " +
        f"rift_active_slab_peak=${runtime.riftActiveSlabPeak}%d " +
        f"rift_active_bytes_current=${runtime.riftActiveBytesCurrent}%d " +
        f"rift_active_bytes_peak=${runtime.riftActiveBytesPeak}%d " +
        f"rift_active_alloc_bytes_current=${runtime.riftActiveAllocBytesCurrent}%d " +
        f"rift_active_alloc_bytes_peak=${runtime.riftActiveAllocBytesPeak}%d " +
        f"rift_tls_reuse_total=${runtime.riftTlsReuseTotal}%d " +
        f"rift_pool_reuse_total=${runtime.riftPoolReuseTotal}%d " +
        f"rift_pool_slabs=${runtime.riftPoolSlabs}%d " +
        f"rift_pool_bytes=${runtime.riftPoolBytes}%d " +
        familyMetricFields("input", DebsRegionFamilies.Input, runtime) +
        familyMetricFields("snapshot", DebsRegionFamilies.Snapshot, runtime) +
        familyMetricFields("checked_parent", DebsRegionFamilies.CheckedParent, runtime) +
        familyMetricFields("q1_window", DebsRegionFamilies.Q1Window, runtime) +
        familyMetricFields(
          "q2_profit_window",
          DebsRegionFamilies.Q2ProfitWindow,
          runtime
        ) +
        familyMetricFields(
          "q2_empty_window",
          DebsRegionFamilies.Q2EmptyWindow,
          runtime
        ) +
        f"diag_grid_q1_calls=${counters.gridQ1Calls}%d " +
        f"diag_grid_q1_hits=${counters.gridQ1Hits}%d " +
        f"diag_grid_q2_calls=${counters.gridQ2Calls}%d " +
        f"diag_grid_q2_hits=${counters.gridQ2Hits}%d " +
        f"diag_q1_rank_adds=${counters.q1RankAdds}%d " +
        f"diag_q1_rank_removes=${counters.q1RankRemoves}%d " +
        f"diag_q1_rank_created=${counters.q1RankCreated}%d " +
        f"diag_q1_top10_calls=${counters.q1Top10Calls}%d " +
        f"diag_q1_result_array_allocs=${counters.q1ResultArrayAllocs}%d " +
        f"diag_q1_result_array_slots=${counters.q1ResultArraySlots}%d " +
        f"diag_q1_window_entries_created=${processDiagnostics.q1WindowEntriesCreated}%d " +
        f"diag_q1_bucket_opens=${processDiagnostics.q1BucketOpens}%d " +
        f"diag_q1_bucket_closes=${processDiagnostics.q1BucketCloses}%d " +
        f"diag_q1_route_table_probe_steps=${processDiagnostics.q1RouteTableProbeSteps}%d " +
        f"diag_q1_route_table_rehashes=${processDiagnostics.q1RouteTableRehashes}%d " +
        f"diag_q1_route_table_rehash_slots=${processDiagnostics.q1RouteTableRehashSlots}%d " +
        f"diag_q1_rank_refreshes=${processDiagnostics.q1RankRefreshes}%d " +
        f"diag_q1_rank_heap_compares=${processDiagnostics.q1RankHeapCompares}%d " +
        f"diag_q1_rank_heap_swaps=${processDiagnostics.q1RankHeapSwaps}%d " +
        f"diag_q1_top_candidate_compares=${processDiagnostics.q1TopCandidateCompares}%d " +
        f"diag_q2_rank_adds=${counters.q2RankAdds}%d " +
        f"diag_q2_rank_removes=${counters.q2RankRemoves}%d " +
        f"diag_q2_rank_fixes=${counters.q2RankFixes}%d " +
        f"diag_q2_rank_created=${counters.q2RankCreated}%d " +
        f"diag_q2_rank_heap_compares=${counters.q2RankHeapCompares}%d " +
        f"diag_q2_rank_heap_swaps=${counters.q2RankHeapSwaps}%d " +
        f"diag_q2_top_candidate_compares=${counters.q2TopCandidateCompares}%d " +
        f"diag_q2_top10_calls=${counters.q2Top10Calls}%d " +
        f"diag_q2_top10_recomputes=${counters.q2Top10Recomputes}%d " +
        f"diag_q2_result_array_allocs=${counters.q2ResultArrayAllocs}%d " +
        f"diag_q2_result_array_slots=${counters.q2ResultArraySlots}%d " +
        f"diag_q2_profit_entries_created=${processDiagnostics.q2ProfitEntriesCreated}%d " +
        f"diag_q2_empty_entries_created=${processDiagnostics.q2EmptyEntriesCreated}%d " +
        f"diag_q2_profit_bucket_opens=${processDiagnostics.q2ProfitBucketOpens}%d " +
        f"diag_q2_profit_bucket_closes=${processDiagnostics.q2ProfitBucketCloses}%d " +
        f"diag_q2_empty_bucket_opens=${processDiagnostics.q2EmptyBucketOpens}%d " +
        f"diag_q2_empty_bucket_closes=${processDiagnostics.q2EmptyBucketCloses}%d " +
        f"diag_q2_cpu_evict_profit_ns=${q2CpuDiagnostics.evictProfitNanos}%d " +
        f"diag_q2_cpu_evict_empty_ns=${q2CpuDiagnostics.evictEmptyNanos}%d " +
        f"diag_q2_cpu_taxi_lookup_ns=${q2CpuDiagnostics.taxiLookupNanos}%d " +
        f"diag_q2_cpu_previous_empty_ns=${q2CpuDiagnostics.previousEmptyNanos}%d " +
        f"diag_q2_cpu_profit_path_ns=${q2CpuDiagnostics.profitPathNanos}%d " +
        f"diag_q2_cpu_profit_rank_ns=${q2CpuDiagnostics.profitRankNanos}%d " +
        f"diag_q2_cpu_empty_path_ns=${q2CpuDiagnostics.emptyPathNanos}%d " +
        f"diag_q2_cpu_empty_rank_ns=${q2CpuDiagnostics.emptyRankNanos}%d " +
        f"diag_q2_cpu_top10_ns=${q2CpuDiagnostics.top10Nanos}%d " +
        f"diag_q2_cpu_recorded_ns=${q2CpuDiagnostics.recordedNanos}%d " +
        f"diag_q2_median_computes=${counters.q2MedianComputes}%d " +
        f"diag_q2_median_values_sorted=${counters.q2MedianValuesSorted}%d " +
        f"diag_q2_median_reads=${counters.q2MedianReads}%d " +
        f"diag_q2_median_heap_adds=${counters.q2MedianHeapAdds}%d " +
        f"diag_q2_median_heap_removes=${counters.q2MedianHeapRemoves}%d " +
        f"diag_q2_median_rebalances=${counters.q2MedianRebalances}%d " +
        f"diag_q1_snapshot_allocs=${counters.q1SnapshotAllocs}%d " +
        f"diag_q1_snapshot_slots=${counters.q1SnapshotSlots}%d " +
        f"diag_q2_snapshot_allocs=${counters.q2SnapshotAllocs}%d " +
        f"diag_q2_snapshot_array_allocs=${counters.q2SnapshotArrayAllocs}%d " +
        f"diag_q2_snapshot_slots=${counters.q2SnapshotSlots}%d " +
        f"diag_q1_latency_appends=${counters.q1LatencyAppends}%d " +
        f"diag_q2_latency_appends=${counters.q2LatencyAppends}%d " +
        f"diag_q2_changed_calls=${counters.q2ChangedCalls}%d " +
        f"diag_q2_changed_element_checks=${counters.q2ChangedElementChecks}%d " +
        f"diag_taxi_lookups=${counters.taxiLookups}%d " +
        f"diag_taxi_hits=${counters.taxiHits}%d " +
        f"diag_taxi_misses=${counters.taxiMisses}%d " +
        f"diag_taxi_entries_scanned=${counters.taxiEntriesScanned}%d " +
        f"diag_taxi_entries_created=${counters.taxiEntriesCreated}%d"
    )
  }

  private def phaseGcAllocFields(
      name: String,
      index: Int,
      metrics: PhaseGcAllocMetrics
  ): String =
    f"phase_${name}_gc_alloc_total=${metrics.total(index)}%d " +
      f"phase_${name}_gc_alloc_bytes=${metrics.byteCount(index)}%d " +
      f"phase_${name}_gc_alloc_time_ns=${metrics.timeNanos(index)}%d "

  private def familyMetricFields(
      name: String,
      family: Int,
      metrics: RuntimeMetrics
  ): String =
    s"rift_family_${name}_alloc_raw_bytes_total=${metrics.riftFamilyAllocRawBytesTotal(family)} " +
      s"rift_family_${name}_active_bytes_peak=${metrics.riftFamilyActiveBytesPeak(family)} " +
      s"rift_family_${name}_active_alloc_bytes_peak=${metrics.riftFamilyActiveAllocBytesPeak(family)} "
}

@main def Debs2015RunBoth(
    inputPath: String,
    q1OutputPath: String,
    q2OutputPath: String,
    q1Mode: String = "heap"
): Unit = {
  val metrics =
    Debs2015RunBothRunner.run(inputPath, q1OutputPath, q2OutputPath, q1Mode)
  Debs2015RunBothRunner.printMetrics(metrics, q1Mode)
}
