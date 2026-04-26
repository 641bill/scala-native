import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedRegionBufferConfig {
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

  val epochs: Int = envInt("CHECKED_BUFFER_EPOCHS", 10)
  val recordsPerEpoch: Int =
    envInt("CHECKED_BUFFER_RECORDS_PER_EPOCH", 100000)
  val keySpace: Int = envInt("CHECKED_BUFFER_KEY_SPACE", 65536)
  val initialCapacity: Int =
    envInt("CHECKED_BUFFER_INITIAL_CAPACITY", 16)
  val warmupRuns: Int = envNonNegativeInt("CHECKED_BUFFER_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_BUFFER_BENCHMARK_RUNS", 3)
}

object CheckedRegionBufferMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(val key: Int, val value: Int)

  private final class HeapBuffer[T <: Object](initialCapacity: Int) {
    private var items =
      new Array[Object](if (initialCapacity <= 0) 1 else initialCapacity)
    private var used = 0

    def length: Int = used

    def append(value: T): Unit = {
      if (used >= items.length) grow()
      items(used) = value
      used += 1
    }

    def apply(index: Int): T = {
      if (index < 0 || index >= used)
        throw new IndexOutOfBoundsException(index.toString)
      items(index).asInstanceOf[T]
    }

    private def grow(): Unit = {
      val old = items
      val next = new Array[Object](old.length * 2)
      var i = 0
      while (i < used) {
        next(i) = old(i)
        i += 1
      }
      items = next
    }
  }

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftRegionOpNanos: Long,
      riftSlowAllocNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)

      if (!includeRift) {
        zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      } else {
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
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftSlowAllocNanos =
            rawSizeToLong(RiftAllocator.Impl.statsSlowAllocNanos())
        )
      }
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
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos)
      )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

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

  def runHeap(): Long = {
    val cfg = CheckedRegionBufferConfig
    val totals = new Array[Long](cfg.keySpace)
    var checksum = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      val buffer = new HeapBuffer[HeapRecord](cfg.initialCapacity)
      var i = 0
      while (i < cfg.recordsPerEpoch) {
        val seed = mix(epoch * 1000003 + i * 131)
        val key = seed % cfg.keySpace
        val value = (mix(seed + 19) & 0xffff) + 1
        buffer.append(new HeapRecord(key, value))
        i += 1
      }

      i = 0
      while (i < buffer.length) {
        val record = buffer(i)
        val next = (totals(record.key) + record.value.toLong) & 0xffffffffL
        totals(record.key) = next
        checksum =
          (checksum * 1099511628211L) ^ record.key.toLong ^ next
        i += 1
      }
      epoch += 1
    }

    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    val cfg = CheckedRegionBufferConfig
    val totals = new Array[Long](cfg.keySpace)
    val checksum = RiftRegion.streaming { stream ?=>
      var running = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        RiftRegion.reset { region ?=>
          final class Record(val key: Int, val value: Int)

          val buffer =
            RiftRegion.regionBuffer[Record](cfg.initialCapacity)
          var i = 0
          while (i < cfg.recordsPerEpoch) {
            val seed = mix(epoch * 1000003 + i * 131)
            val key = seed % cfg.keySpace
            val value = (mix(seed + 19) & 0xffff) + 1
            val record: Record^{region} =
              RiftRegion.alloc(new Record(key, value))
            region.append(buffer, record)
            i += 1
          }

          var local = 0L
          i = 0
          while (i < region.length(buffer)) {
            val record = region.get(buffer, i)
            val next =
              (totals(record.key) + record.value.toLong) & 0xffffffffL
            totals(record.key) = next
            running =
              (running * 1099511628211L) ^ record.key.toLong ^ next
            i += 1
          }
        }
        epoch += 1
      }
      running
    }

    checksumSink = checksum
    checksum
  }

  private def runMode(mode: String): Long =
    mode match {
      case "heap"         => runHeap()
      case "rift-checked" => runRiftChecked()
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-buffer mode '$other'; expected heap or rift-checked"
        )
    }

  def validateMode(mode: String): Unit =
    runModeName(mode)

  private def runModeName(mode: String): String =
    mode match {
      case "heap" | "rift-checked" => mode
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-buffer mode '$other'; expected heap or rift-checked"
        )
    }

  def runBenchmark(mode: String): Unit = {
    val cfg = CheckedRegionBufferConfig
    val usesRift = mode == "rift-checked"
    val expectedChecksum = runHeap()

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runMode(mode)
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"warmup checksum mismatch mode=$mode expected=$expectedChecksum actual=$checksum"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(
      s"Running checked-buffer-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runMode(mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"checksum mismatch mode=$mode expected=$expectedChecksum actual=$checksum"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftRegionOpenTotal
      riftCloses(run) = runtime.riftRegionCloseTotal
      riftResets(run) = runtime.riftRegionResetTotal

      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"rift_op_ms=${runtime.riftRegionOpNanos / 1000000.0}%.3f " +
          f"rift_slow_alloc_ms=${runtime.riftSlowAllocNanos / 1000000.0}%.3f " +
          f"rift_open_total=${runtime.riftRegionOpenTotal}%d " +
          f"rift_close_total=${runtime.riftRegionCloseTotal}%d " +
          f"rift_reset_total=${runtime.riftRegionResetTotal}%d " +
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d"
      )

      run += 1
    }

    val medianElapsed = medianDouble(elapsedMs)
    val medianGc = medianLong(gcNanos)
    val medianRiftOp = medianLong(riftOpNanos)
    val medianObjects = medianLong(riftObjects)
    val medianOpens = medianLong(riftOpens)
    val medianCloses = medianLong(riftCloses)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=checked-buffer-$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )
  }

  def printConfig(mode: String): Unit = {
    val cfg = CheckedRegionBufferConfig
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} records_per_epoch=${cfg.recordsPerEpoch} key_space=${cfg.keySpace} initial_capacity=${cfg.initialCapacity}"
    )
  }
}

@main def CheckedRegionBufferMatrix(mode: String = "heap"): Unit = {
  CheckedRegionBufferMatrixHelpers.validateMode(mode)
  CheckedRegionBufferMatrixHelpers.printConfig(mode)

  val usesRift = mode == "rift-checked"
  if (usesRift) RiftRegion.init(0)
  try {
    CheckedRegionBufferMatrixHelpers.runBenchmark(mode)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
