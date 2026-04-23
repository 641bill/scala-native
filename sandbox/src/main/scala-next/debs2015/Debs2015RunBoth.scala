package debs2015

import java.io.BufferedWriter
import java.io.FileWriter

import scala.collection.mutable
import scala.io.Source
import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object Debs2015RunBothRunner {
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
      runtime: RuntimeMetrics
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
    val runtimeStart = RuntimeMetrics.capture(usesRift)
    var runtimeEnd = runtimeStart

    val q1 = Debs2015Q1Runner.createEngine(q1Mode)
    val q2 = new Q2Heap
    val source = Source.fromFile(inputPath)
    val q1Writer = new BufferedWriter(new FileWriter(q1OutputPath))
    val q2Writer = new BufferedWriter(new FileWriter(q2OutputPath))
    val q1Latencies = new mutable.ArrayBuffer[Long](1024)
    val q2Latencies = new mutable.ArrayBuffer[Long](1024)
    val trip = Trip.empty

    var previousQ1 = Array.empty[RankedRoute]
    var previousQ2 = Array.empty[ProfitableArea]
    var events = 0L
    var parsed = 0L
    var invalid = 0L
    var q1Outputs = 0L
    var q2Outputs = 0L
    val started = System.nanoTime()

    try {
      val lines = source.getLines()
      while (lines.hasNext) {
        val readAt = System.nanoTime()
        val line = lines.next()
        events += 1L

        if (Trip.parseInto(line, trip)) {
            parsed += 1L

            val q1Current = q1.process(trip)
            if (q1Current.nonEmpty && Q1Output.changed(previousQ1, q1Current)) {
              val writeAt = System.nanoTime()
              val delayMillis = (writeAt - readAt) / 1000000L
              q1Writer.write(Q1Output.formatRow(trip, q1Current, delayMillis))
              q1Writer.newLine()
              q1Latencies += delayMillis
              q1Outputs += 1L
              previousQ1 = q1Current
            }

            val q2Current = q2.process(trip)
            if (q2Current.nonEmpty && Q2Output.changed(previousQ2, q2Current)) {
              val writeAt = System.nanoTime()
              val delayMillis = (writeAt - readAt) / 1000000L
              q2Writer.write(Q2Output.formatRow(trip, q2Current, delayMillis))
              q2Writer.newLine()
              q2Latencies += delayMillis
              q2Outputs += 1L
              previousQ2 = q2Current
            }
        } else {
          invalid += 1L
        }
      }
    } finally {
      q2Writer.close()
      q1Writer.close()
      source.close()
      q2.close()
      q1.close()
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
      runtime = RuntimeMetrics.since(runtimeStart, runtimeEnd)
    )
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
    scala.util.Sorting.quickSort(q1Sorted)
    scala.util.Sorting.quickSort(q2Sorted)

    println(
      f"DEBS2015_RUNBOTH_RESULT q1_mode=$q1Mode events=${metrics.events}%d " +
        f"parsed=${metrics.parsed}%d invalid=${metrics.invalid}%d " +
        f"q1_outputs=${metrics.q1Outputs}%d q2_outputs=${metrics.q2Outputs}%d " +
        f"elapsed_ms=${metrics.elapsedMillis}%.3f throughput_eps=${metrics.throughputEventsPerSecond}%.3f " +
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
        f"rift_pool_bytes=${runtime.riftPoolBytes}%d"
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
