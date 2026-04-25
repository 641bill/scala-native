package debs2015

import java.io.BufferedWriter
import java.io.FileWriter

import scala.collection.mutable
import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object Debs2015RunBothRunner {
  final case class PhaseMetrics(
      readNanos: Long,
      parseNanos: Long,
      q1ProcessNanos: Long,
      q1OutputNanos: Long,
      q2ProcessNanos: Long,
      q2OutputNanos: Long,
      closeNanos: Long
  ) {
    def trackedNanos: Long =
      readNanos +
        parseNanos +
        q1ProcessNanos +
        q1OutputNanos +
        q2ProcessNanos +
        q2OutputNanos +
        closeNanos
  }

  final case class RuntimeMetrics(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocRawTotal: Long,
      riftAllocObjectTotal: Long,
      riftAllocSlowTotal: Long,
      riftMmapSlabTotal: Long,
      riftMmapBytesTotal: Long,
      riftTlsReuseTotal: Long,
      riftPoolReuseTotal: Long,
      riftRegionOpNanos: Long,
      riftOpenNanos: Long,
      riftCloseNanos: Long,
      riftResetNanos: Long,
      riftSlowAllocNanos: Long,
      riftPoolSlabs: Long,
      riftPoolBytes: Long
  )

  private object RuntimeMetrics {
    val zero: RuntimeMetrics =
      RuntimeMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
        0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeMetrics = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)

      if (!includeRift) {
        zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      } else {
        RuntimeMetrics(
          gcCollections = gcCollections,
          gcNanos = gcNanos,
          riftRegionOpenTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpenTotal()),
          riftRegionCloseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionCloseTotal()),
          riftRegionResetTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal()),
          riftAllocRawTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocRawTotal()),
          riftAllocObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          riftAllocSlowTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocSlowTotal()),
          riftMmapSlabTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabTotal()),
          riftMmapBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesTotal()),
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
          riftPoolSlabs = rawSizeToLong(RiftAllocator.Impl.poolSlabCount()),
          riftPoolBytes = rawSizeToLong(RiftAllocator.Impl.poolResidentBytes())
        )
      }
    }

    def since(start: RuntimeMetrics, end: RuntimeMetrics): RuntimeMetrics =
      RuntimeMetrics(
        gcCollections = delta(end.gcCollections, start.gcCollections),
        gcNanos = delta(end.gcNanos, start.gcNanos),
        riftRegionOpenTotal =
          delta(end.riftRegionOpenTotal, start.riftRegionOpenTotal),
        riftRegionCloseTotal =
          delta(end.riftRegionCloseTotal, start.riftRegionCloseTotal),
        riftRegionResetTotal =
          delta(end.riftRegionResetTotal, start.riftRegionResetTotal),
        riftAllocRawTotal =
          delta(end.riftAllocRawTotal, start.riftAllocRawTotal),
        riftAllocObjectTotal =
          delta(end.riftAllocObjectTotal, start.riftAllocObjectTotal),
        riftAllocSlowTotal =
          delta(end.riftAllocSlowTotal, start.riftAllocSlowTotal),
        riftMmapSlabTotal =
          delta(end.riftMmapSlabTotal, start.riftMmapSlabTotal),
        riftMmapBytesTotal =
          delta(end.riftMmapBytesTotal, start.riftMmapBytesTotal),
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
      runtime: RuntimeMetrics,
      counters: Debs2015Counters.Snapshot
  ) {
    def elapsedMillis: Double = elapsedNanos.toDouble / 1000000.0

    def throughputEventsPerSecond: Double =
      if (elapsedNanos <= 0L) 0.0
      else events.toDouble * 1000000000.0 / elapsedNanos.toDouble
  }

  def run(
      inputPath: String,
      q1OutputPath: String,
      q2OutputPath: String,
      q1Mode: String
  ): Metrics = {
    val usesRift = q1Mode.startsWith("rift-")
    if (usesRift) {
      RiftRegion.init(0)
      RiftAllocator.Impl.statsReset()
    }
    Debs2015Counters.reset()
    val counterStart = Debs2015Counters.snapshot()
    val runtimeStart = RuntimeMetrics.capture(usesRift)
    var runtimeEnd = runtimeStart

    val q1 = Debs2015Q1Runner.createEngine(q1Mode)
    val q2 = Debs2015Q2Runner.createEngine(q1Mode)
    val source = new CsvLineReader(inputPath, q1Mode)
    val snapshotRegion =
      if (usesRift) RiftRegion.open(regionKindForMode(q1Mode)) else null
    val q1Writer = new BufferedWriter(new FileWriter(q1OutputPath))
    val q2Writer = new BufferedWriter(new FileWriter(q2OutputPath))
    val q1Latencies = new mutable.ArrayBuffer[Long](1024)
    val q2Latencies = new mutable.ArrayBuffer[Long](1024)
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
    var q1OutputNanos = 0L
    var q2ProcessNanos = 0L
    var q2OutputNanos = 0L
    var closeNanos = 0L
    val started = System.nanoTime()

    try {
      while ({
        val readStarted = System.nanoTime()
        val hasNext = source.nextLine()
        readNanos += System.nanoTime() - readStarted
        hasNext
      }) {
        val readAt = System.nanoTime()
        events += 1L

        val parseStarted = System.nanoTime()
        val parsedTrip =
          Trip.parseInto(source.bytes, source.lineStart, source.lineEnd, trip)
        val parseFinished = System.nanoTime()
        parseNanos += parseFinished - parseStarted

        if (parsedTrip) {
            parsed += 1L

            val q1Started = System.nanoTime()
            val q1Current = q1.process(trip)
            val q1Finished = System.nanoTime()
            q1ProcessNanos += q1Finished - q1Started
            if (q1Current.nonEmpty && Q1Output.changed(previousQ1, q1Current)) {
              val q1OutputStarted = System.nanoTime()
              val writeAt = q1OutputStarted
              val delayMillis = (writeAt - readAt) / 1000000L
              Q1Output.writeRow(q1Writer, trip, q1Current, delayMillis)
              q1Writer.newLine()
              q1Latencies += delayMillis
              Debs2015Counters.recordQ1LatencyAppend()
              q1Outputs += 1L
              previousQ1 = Q1Output.snapshot(q1Current, snapshotRegion)
              q1OutputNanos += System.nanoTime() - q1OutputStarted
            }

            val q2Started = System.nanoTime()
            val q2Current = q2.process(trip)
            val q2Finished = System.nanoTime()
            q2ProcessNanos += q2Finished - q2Started
            if (q2Current.nonEmpty && Q2Output.changed(previousQ2, q2Current)) {
              val q2OutputStarted = System.nanoTime()
              val writeAt = q2OutputStarted
              val delayMillis = (writeAt - readAt) / 1000000L
              Q2Output.writeRow(q2Writer, trip, q2Current, delayMillis)
              q2Writer.newLine()
              q2Latencies += delayMillis
              Debs2015Counters.recordQ2LatencyAppend()
              q2Outputs += 1L
              previousQ2 = Q2Output.snapshot(q2Current, snapshotRegion)
              q2OutputNanos += System.nanoTime() - q2OutputStarted
            }
        } else {
          invalid += 1L
        }
      }
    } finally {
      val closeStarted = System.nanoTime()
      q2Writer.close()
      q1Writer.close()
      source.close()
      q2.close()
      q1.close()
      if (snapshotRegion != null) snapshotRegion.close()
      closeNanos = System.nanoTime() - closeStarted
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
      q1LatencyMillis = q1Latencies.toArray,
      q2LatencyMillis = q2Latencies.toArray,
      phases = PhaseMetrics(
        readNanos = readNanos,
        parseNanos = parseNanos,
        q1ProcessNanos = q1ProcessNanos,
        q1OutputNanos = q1OutputNanos,
        q2ProcessNanos = q2ProcessNanos,
        q2OutputNanos = q2OutputNanos,
        closeNanos = closeNanos
      ),
      runtime = RuntimeMetrics.since(runtimeStart, runtimeEnd),
      counters = Debs2015Counters.snapshot().since(counterStart)
    )
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
    val counters = metrics.counters
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
        f"phase_q1_output_ns=${phases.q1OutputNanos}%d " +
        f"phase_q2_process_ns=${phases.q2ProcessNanos}%d " +
        f"phase_q2_output_ns=${phases.q2OutputNanos}%d " +
        f"phase_close_ns=${phases.closeNanos}%d " +
        f"phase_tracked_ns=${trackedNanos}%d " +
        f"phase_untracked_ns=${untrackedNanos}%d " +
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
        f"rift_region_op_ns=${runtime.riftRegionOpNanos}%d " +
        f"rift_open_ns=${runtime.riftOpenNanos}%d " +
        f"rift_close_ns=${runtime.riftCloseNanos}%d " +
        f"rift_reset_ns=${runtime.riftResetNanos}%d " +
        f"rift_slow_alloc_ns=${runtime.riftSlowAllocNanos}%d " +
        f"rift_open_total=${runtime.riftRegionOpenTotal}%d " +
        f"rift_close_total=${runtime.riftRegionCloseTotal}%d " +
        f"rift_reset_total=${runtime.riftRegionResetTotal}%d " +
        f"rift_alloc_raw_total=${runtime.riftAllocRawTotal}%d " +
        f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d " +
        f"rift_alloc_slow_total=${runtime.riftAllocSlowTotal}%d " +
        f"rift_mmap_slab_total=${runtime.riftMmapSlabTotal}%d " +
        f"rift_mmap_bytes_total=${runtime.riftMmapBytesTotal}%d " +
        f"rift_tls_reuse_total=${runtime.riftTlsReuseTotal}%d " +
        f"rift_pool_reuse_total=${runtime.riftPoolReuseTotal}%d " +
        f"rift_pool_slabs=${runtime.riftPoolSlabs}%d " +
        f"rift_pool_bytes=${runtime.riftPoolBytes}%d " +
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
        f"diag_q2_rank_adds=${counters.q2RankAdds}%d " +
        f"diag_q2_rank_removes=${counters.q2RankRemoves}%d " +
        f"diag_q2_rank_fixes=${counters.q2RankFixes}%d " +
        f"diag_q2_rank_created=${counters.q2RankCreated}%d " +
        f"diag_q2_top10_calls=${counters.q2Top10Calls}%d " +
        f"diag_q2_result_array_allocs=${counters.q2ResultArrayAllocs}%d " +
        f"diag_q2_result_array_slots=${counters.q2ResultArraySlots}%d " +
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
        f"diag_taxi_lookups=${counters.taxiLookups}%d " +
        f"diag_taxi_hits=${counters.taxiHits}%d " +
        f"diag_taxi_misses=${counters.taxiMisses}%d " +
        f"diag_taxi_entries_scanned=${counters.taxiEntriesScanned}%d " +
        f"diag_taxi_entries_created=${counters.taxiEntriesCreated}%d"
    )
  }
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
