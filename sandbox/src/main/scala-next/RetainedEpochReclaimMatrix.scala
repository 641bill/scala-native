import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object RetainedEpochReclaimConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def parseNonNegativeInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed >= 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val records: Int = envNonNegativeInt("RETAINED_EPOCH_RECORDS", 1000000)
  val recordsPerEpoch: Int =
    envInt("RETAINED_EPOCH_RECORDS_PER_EPOCH", 25000)
  val keyBuckets: Int = envInt("RETAINED_EPOCH_KEY_BUCKETS", 4096)
  val sampleEvery: Int = envInt("RETAINED_EPOCH_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("RETAINED_EPOCH_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("RETAINED_EPOCH_BENCHMARK_RUNS", 3)

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
}

object RetainedEpochReclaimMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var anchorSink = 0L

  private final class HeapSummaryRecord(
      val eventIndex: Int,
      val key: Int,
      val value: Int,
      val score: Int,
      val hash: Long
  )

  private final class HeapRetainedRecord(
      val eventIndex: Int,
      val key: Int,
      val value: Int,
      val score: Int,
      val hash: Long,
      val next: HeapRetainedRecord
  )

  final case class Outcome(checksum: Long, outputCount: Long)

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftRegionOpNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample = RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)
      if (!includeRift) zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      else
        RuntimeSample(
          gcCollections = gcCollections,
          gcNanos = gcNanos,
          riftRegionOpenTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpenTotal()),
          riftRegionCloseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionCloseTotal()),
          riftRegionResetTotal =
            rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal()),
          riftAllocObjectTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          riftRegionOpNanos =
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos())
        )
    }

    def since(start: RuntimeSample, end: RuntimeSample): RuntimeSample =
      RuntimeSample(
        gcCollections = delta(end.gcCollections, start.gcCollections),
        gcNanos = delta(end.gcNanos, start.gcNanos),
        riftRegionOpenTotal =
          delta(end.riftRegionOpenTotal, start.riftRegionOpenTotal),
        riftRegionCloseTotal =
          delta(end.riftRegionCloseTotal, start.riftRegionCloseTotal),
        riftRegionResetTotal =
          delta(end.riftRegionResetTotal, start.riftRegionResetTotal),
        riftAllocObjectTotal =
          delta(end.riftAllocObjectTotal, start.riftAllocObjectTotal),
        riftRegionOpNanos =
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos)
      )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def fold(
      checksum: Long,
      kind: Int,
      epochStart: Int,
      key: Int,
      count: Int,
      sum: Long
  ): Long =
    (((checksum ^ kind.toLong) * 1099511628211L) ^
      epochStart.toLong ^
      (key.toLong << 17) ^
      count.toLong ^
      sum)

  private def medianDouble(values: Array[Double]): Double = {
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    if ((sorted.length & 1) == 1) sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2.0
  }

  private def medianLong(values: Array[Long]): Long = {
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    if ((sorted.length & 1) == 1) sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2L
  }

  private def runHeapSummaryOnly(): Outcome = {
    val cfg = RetainedEpochReclaimConfig
    val counts = new Array[Int](cfg.keyBuckets)
    val sums = new Array[Long](cfg.keyBuckets)
    var checksum = 0L
    var outputCount = 0L
    var epochStart = 0
    while (epochStart < cfg.records) {
      java.util.Arrays.fill(counts, 0)
      java.util.Arrays.fill(sums, 0L)
      val epochEnd =
        math.min(cfg.records, epochStart + cfg.recordsPerEpoch)
      var i = epochStart
      while (i < epochEnd) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keyBuckets
        val value = (mix(seed + 17) & 1023) + 1
        val record =
          new HeapSummaryRecord(i, key, value, seed & 255, seed.toLong)
        counts(record.key) += 1
        sums(record.key) += record.value.toLong
        if (i % cfg.sampleEvery == 0)
          checksum = fold(checksum, 99, i, key, 1, record.hash)
        i += 1
      }
      var key = 0
      while (key < cfg.keyBuckets) {
        val count = counts(key)
        if (count != 0) {
          checksum = fold(checksum, 44, epochStart, key, count, sums(key))
          outputCount += 1L
        }
        key += 1
      }
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    Outcome(checksum, outputCount)
  }

  private def runHeapRetainedNoTraverse(): Outcome = {
    val cfg = RetainedEpochReclaimConfig
    val counts = new Array[Int](cfg.keyBuckets)
    val sums = new Array[Long](cfg.keyBuckets)
    var checksum = 0L
    var outputCount = 0L
    var anchor = 0L
    var epochStart = 0
    while (epochStart < cfg.records) {
      java.util.Arrays.fill(counts, 0)
      java.util.Arrays.fill(sums, 0L)
      val epochEnd =
        math.min(cfg.records, epochStart + cfg.recordsPerEpoch)
      var head: HeapRetainedRecord = null
      var tail: HeapRetainedRecord = null
      var retainedCount = 0
      var i = epochStart
      while (i < epochEnd) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keyBuckets
        val value = (mix(seed + 17) & 1023) + 1
        val record =
          new HeapRetainedRecord(i, key, value, seed & 255, seed.toLong, head)
        if (head == null) tail = record
        head = record
        retainedCount += 1
        counts(record.key) += 1
        sums(record.key) += record.value.toLong
        if (i % cfg.sampleEvery == 0)
          checksum = fold(checksum, 99, i, key, 1, record.hash)
        i += 1
      }
      if (head != null && tail != null)
        anchor =
          (anchor * 1099511628211L) ^
            head.hash ^
            (tail.hash << 1) ^
            retainedCount.toLong ^
            epochStart.toLong
      var key = 0
      while (key < cfg.keyBuckets) {
        val count = counts(key)
        if (count != 0) {
          checksum = fold(checksum, 44, epochStart, key, count, sums(key))
          outputCount += 1L
        }
        key += 1
      }
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    anchorSink = anchor
    Outcome(checksum, outputCount)
  }

  private def runCheckedRetainedBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Outcome = {
    val cfg = RetainedEpochReclaimConfig
    val counts = new Array[Int](cfg.keyBuckets)
    val sums = new Array[Long](cfg.keyBuckets)
    var checksum = 0L
    var outputCount = 0L
    var anchor = 0L
    var epochStart = 0
    while (epochStart < cfg.records) {
      java.util.Arrays.fill(counts, 0)
      java.util.Arrays.fill(sums, 0L)
      val epochEnd =
        math.min(cfg.records, epochStart + cfg.recordsPerEpoch)
      val currentEpochStart = epochStart
      RiftRegion.epoch { region ?=>
        final class CheckedRecord(
            val eventIndex: Int,
            val key: Int,
            val value: Int,
            val score: Int,
            val hash: Long
        ) {
          var next: CheckedRecord^{region} = null
        }

        var head: CheckedRecord^{region} = null
        var tail: CheckedRecord^{region} = null
        var retainedCount = 0
        var i = currentEpochStart
        while (i < epochEnd) {
          val seed = mix(i * 1103515245 + 12345)
          val key = seed % cfg.keyBuckets
          val value = (mix(seed + 17) & 1023) + 1
          val record: CheckedRecord^{region} =
            RiftRegion.allocOpen(
              new CheckedRecord(i, key, value, seed & 255, seed.toLong)
            )
          record.next = head
          if (head == null) tail = record
          head = record
          retainedCount += 1
          counts(record.key) += 1
          sums(record.key) += record.value.toLong
          if (i % cfg.sampleEvery == 0)
            checksum = fold(checksum, 99, i, key, 1, record.hash)
          i += 1
        }
        if (head != null && tail != null)
          anchor =
            (anchor * 1099511628211L) ^
              head.hash ^
              (tail.hash << 1) ^
              retainedCount.toLong ^
              currentEpochStart.toLong
      }
      var key = 0
      while (key < cfg.keyBuckets) {
        val count = counts(key)
        if (count != 0) {
          checksum = fold(checksum, 44, epochStart, key, count, sums(key))
          outputCount += 1L
        }
        key += 1
      }
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    anchorSink = anchor
    Outcome(checksum, outputCount)
  }

  private def runCheckedRetainedRift(): Outcome =
    RiftRegion.streaming { stream ?=>
      runCheckedRetainedBody()
    }

  private def runCheckedRetainedSafeZone(): Outcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedRetainedBody()
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap-direct-summary-only" | "heap-direct-epoch" =>
        "heap-direct-summary-only"
      case "heap-epoch-retained-no-traverse" =>
        "heap-epoch-retained-no-traverse"
      case "checked-epoch-retained-no-traverse" |
          "checked-region-stream-retained-epoch" =>
        "checked-epoch-retained-no-traverse"
      case "checked-scoped-epoch-retained-no-traverse" |
          "checked-region-scoped-retained-epoch" =>
        "checked-scoped-epoch-retained-no-traverse"
      case other =>
        throw new IllegalArgumentException(
          s"unknown retained epoch mode '$other'"
        )
    }

  private def usesRiftStats(mode: String): Boolean =
    mode == "checked-epoch-retained-no-traverse"

  private def runMode(mode: String): Outcome =
    mode match {
      case "heap-direct-summary-only" => runHeapSummaryOnly()
      case "heap-epoch-retained-no-traverse" => runHeapRetainedNoTraverse()
      case "checked-epoch-retained-no-traverse" => runCheckedRetainedRift()
      case "checked-scoped-epoch-retained-no-traverse" =>
        runCheckedRetainedSafeZone()
    }

  def run(modeArg: String): Unit = {
    val mode = canonicalMode(modeArg)
    val cfg = RetainedEpochReclaimConfig
    val topology =
      if (mode == "heap-direct-summary-only") "summary-only"
      else "retained-epoch-no-traverse"
    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      var outputCount = 0L
      while (run < cfg.benchmarkRuns) {
        val result = runMode(mode)
        if (run == 0) {
          checksum = result.checksum
          outputCount = result.outputCount
        } else if (
          result.checksum != checksum || result.outputCount != outputCount
        ) {
          throw new IllegalStateException(
            s"final-clean retained epoch mismatch mode=$mode first_checksum=$checksum first_output_count=$outputCount actual=$result"
          )
        }
        run += 1
      }
      println(
        s"RESULT name=retained-epoch-reclaim-$mode " +
          s"measurement_level=L1 final_clean=1 " +
          s"mode=$mode topology=$topology records=${cfg.records} " +
          s"records_per_epoch=${cfg.recordsPerEpoch} " +
          s"key_buckets=${cfg.keyBuckets} runs=${cfg.benchmarkRuns} " +
          s"checksum=$checksum output_count=$outputCount"
      )
      return
    }

    val expected = runHeapSummaryOnly()
    val totalRuns = cfg.warmupRuns + cfg.benchmarkRuns
    val times = new Array[Double](cfg.benchmarkRuns)
    val gcTimes = new Array[Double](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpTimes = new Array[Double](cfg.benchmarkRuns)
    val riftAllocObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpenTotal = new Array[Long](cfg.benchmarkRuns)
    val riftCloseTotal = new Array[Long](cfg.benchmarkRuns)
    val riftResetTotal = new Array[Long](cfg.benchmarkRuns)
    var checksum = 0L
    var outputCount = 0L

    System.gc()
    var run = 0
    while (run < totalRuns) {
      val measured = run >= cfg.warmupRuns
      if (usesRiftStats(mode)) RiftAllocator.Impl.statsReset()
      val before = RuntimeSample.capture(usesRiftStats(mode))
      val start = System.nanoTime()
      val result = runMode(mode)
      val elapsed = System.nanoTime() - start
      val after = RuntimeSample.capture(usesRiftStats(mode))
      val delta = RuntimeSample.since(before, after)
      if (result != expected)
        throw new IllegalStateException(
          s"retained epoch mismatch mode=$mode expected=$expected actual=$result"
        )
      if (measured) {
        val index = run - cfg.warmupRuns
        times(index) = elapsed.toDouble / 1000000.0
        gcTimes(index) = delta.gcNanos.toDouble / 1000000.0
        gcCollections(index) = delta.gcCollections
        riftOpTimes(index) = delta.riftRegionOpNanos.toDouble / 1000000.0
        riftAllocObjects(index) = delta.riftAllocObjectTotal
        riftOpenTotal(index) = delta.riftRegionOpenTotal
        riftCloseTotal(index) = delta.riftRegionCloseTotal
        riftResetTotal(index) = delta.riftRegionResetTotal
        checksum = result.checksum
        outputCount = result.outputCount
      }
      System.gc()
      run += 1
    }

    val medianMs = medianDouble(times)
    val minMs = times.min
    val maxMs = times.max
    val medianGcMs = medianDouble(gcTimes)
    val maxGcMs = gcTimes.max
    val runsWithGc = gcCollections.count(_ > 0L)
    val maxGcCollections = gcCollections.max
    val medianRiftOpMs = medianDouble(riftOpTimes)
    val medianRiftObjects = medianLong(riftAllocObjects)
    val medianOpen = medianLong(riftOpenTotal)
    val medianClose = medianLong(riftCloseTotal)
    val medianReset = medianLong(riftResetTotal)
    println(
      f"RESULT name=retained-epoch-reclaim-$mode " +
        s"mode=$mode topology=$topology records=${cfg.records} " +
        s"records_per_epoch=${cfg.recordsPerEpoch} " +
        s"key_buckets=${cfg.keyBuckets} " +
        f"median_ms=$medianMs%.3f min_ms=$minMs%.3f max_ms=$maxMs%.3f " +
        f"median_gc_ms=$medianGcMs%.3f max_gc_ms=$maxGcMs%.3f " +
        s"runs_with_gc=$runsWithGc max_gc_collections=$maxGcCollections " +
        f"median_rift_op_ms=$medianRiftOpMs%.3f " +
        s"median_rift_alloc_object_total=$medianRiftObjects " +
        s"median_rift_open_total=$medianOpen " +
        s"median_rift_close_total=$medianClose " +
        s"median_rift_reset_total=$medianReset " +
        s"checksum=$checksum output_count=$outputCount"
    )
  }
}

object RetainedEpochReclaimMatrix {
  def main(args: Array[String]): Unit = {
    val mode = if (args.nonEmpty) args(0) else "heap-direct-summary-only"
    RetainedEpochReclaimMatrixHelpers.run(mode)
  }
}
