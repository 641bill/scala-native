import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedRegionPriorityQueueConfig {
  private def envInt(name: String, default: Int): Int =
    sys.env
      .get(name)
      .flatMap(value =>
        try {
          val parsed = value.toInt
          if (parsed > 0) Some(parsed) else None
        } catch {
          case _: NumberFormatException => None
        }
      )
      .getOrElse(default)

  val epochs: Int = envInt("CHECKED_PQ_EPOCHS", 10)
  val recordsPerEpoch: Int = envInt("CHECKED_PQ_RECORDS_PER_EPOCH", 50000)
  val topK: Int = envInt("CHECKED_PQ_TOP_K", 64)
  val initialCapacity: Int = envInt("CHECKED_PQ_INITIAL_CAPACITY", 16)
  val warmupRuns: Int = envInt("CHECKED_PQ_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_PQ_BENCHMARK_RUNS", 3)
}

object CheckedRegionPriorityQueueMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(val key: Int, val value: Int)

  private final class HeapPriorityQueue[T <: Object](initialCapacity: Int) {
    private var items =
      new Array[Object](if (initialCapacity <= 0) 1 else initialCapacity)
    private var priorities = new Array[Long](items.length)
    private var used = 0

    def length: Int = used

    def push(value: T, priority: Long): Unit = {
      if (used >= items.length) grow()
      val index = used
      used += 1
      items(index) = value
      priorities(index) = priority
      siftUp(index)
    }

    def pop(): T = {
      if (used == 0)
        throw new NoSuchElementException("HeapPriorityQueue is empty")
      val result = items(0)
      val last = used - 1
      used = last
      if (last > 0) {
        items(0) = items(last)
        priorities(0) = priorities(last)
        items(last) = null
        priorities(last) = 0L
        siftDown(0)
      } else {
        items(0) = null
        priorities(0) = 0L
      }
      result.asInstanceOf[T]
    }

    private def grow(): Unit = {
      val nextItems = new Array[Object](items.length * 2)
      val nextPriorities = new Array[Long](priorities.length * 2)
      var i = 0
      while (i < used) {
        nextItems(i) = items(i)
        nextPriorities(i) = priorities(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
    }

    private def siftUp(start: Int): Unit = {
      var child = start
      while (child > 0) {
        val parent = (child - 1) >>> 1
        if (priorities(parent) >= priorities(child)) return
        swap(parent, child)
        child = parent
      }
    }

    private def siftDown(start: Int): Unit = {
      var parent = start
      while (true) {
        val left = (parent << 1) + 1
        if (left >= used) return
        val right = left + 1
        var best = left
        if (right < used && priorities(right) > priorities(left))
          best = right
        if (priorities(parent) >= priorities(best)) return
        swap(parent, best)
        parent = best
      }
    }

    private def swap(left: Int, right: Int): Unit = {
      val leftItem = items(left)
      val leftPriority = priorities(left)
      items(left) = items(right)
      priorities(left) = priorities(right)
      items(right) = leftItem
      priorities(right) = leftPriority
    }
  }

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftOpNanos: Long,
      riftObjects: Long,
      riftResets: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample = RuntimeSample(0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)
      if (!includeRift) RuntimeSample(gcCollections, gcNanos, 0L, 0L, 0L)
      else
        RuntimeSample(
          gcCollections,
          gcNanos,
          rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          rawSizeToLong(RiftAllocator.Impl.statsAllocObjectTotal()),
          rawSizeToLong(RiftAllocator.Impl.statsRegionResetTotal())
        )
    }

    def since(start: RuntimeSample, end: RuntimeSample): RuntimeSample =
      RuntimeSample(
        delta(end.gcCollections, start.gcCollections),
        delta(end.gcNanos, start.gcNanos),
        delta(end.riftOpNanos, start.riftOpNanos),
        delta(end.riftObjects, start.riftObjects),
        delta(end.riftResets, start.riftResets)
      )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def priority(seed: Int, index: Int, recordsPerEpoch: Int): Long =
    seed.toLong * recordsPerEpoch.toLong + index.toLong

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
    val cfg = CheckedRegionPriorityQueueConfig
    var checksum = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      val queue =
        new HeapPriorityQueue[HeapRecord](cfg.initialCapacity)
      var i = 0
      while (i < cfg.recordsPerEpoch) {
        val seed = mix(epoch * 1000003 + i * 131)
        val key = seed & 0xffff
        val value = (mix(seed + 19) & 0xffff) + 1
        queue.push(
          new HeapRecord(key, value),
          priority(seed, i, cfg.recordsPerEpoch)
        )
        i += 1
      }

      var remaining = math.min(cfg.topK, queue.length)
      while (remaining > 0) {
        val record = queue.pop()
        checksum =
          (checksum * 1099511628211L) ^ record.key.toLong ^ record.value.toLong
        remaining -= 1
      }
      epoch += 1
    }
    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    val cfg = CheckedRegionPriorityQueueConfig
    val checksum = RiftRegion.streaming { stream ?=>
      var running = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        RiftRegion.reset { region ?=>
          final class Record(val key: Int, val value: Int)

          val queue =
            RiftRegion.regionPriorityQueue[Record](cfg.initialCapacity)
          var i = 0
          while (i < cfg.recordsPerEpoch) {
            val seed = mix(epoch * 1000003 + i * 131)
            val key = seed & 0xffff
            val value = (mix(seed + 19) & 0xffff) + 1
            val record: Record^{region} =
              RiftRegion.alloc(new Record(key, value))
            region.push(
              queue,
              record,
              priority(seed, i, cfg.recordsPerEpoch)
            )
            i += 1
          }

          var remaining = math.min(cfg.topK, region.length(queue))
          while (remaining > 0) {
            val record = region.pop(queue)
            running =
              (running * 1099511628211L) ^ record.key.toLong ^ record.value.toLong
            remaining -= 1
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
          s"unknown checked-priority-queue mode '$other'; expected heap or rift-checked"
        )
    }

  def runBenchmark(mode: String): Unit = {
    val cfg = CheckedRegionPriorityQueueConfig
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
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runMode(mode)
      val end = System.nanoTime()
      val runtime =
        RuntimeSample.since(startRuntime, RuntimeSample.capture(usesRift))
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"checksum mismatch mode=$mode expected=$expectedChecksum actual=$checksum"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftOpNanos
      riftObjects(run) = runtime.riftObjects
      riftResets(run) = runtime.riftResets

      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"rift_op_ms=${runtime.riftOpNanos / 1000000.0}%.3f " +
          f"rift_alloc_object_total=${runtime.riftObjects}%d " +
          f"rift_reset_total=${runtime.riftResets}%d"
      )

      run += 1
    }

    val medianElapsed = medianDouble(elapsedMs)
    val medianGc = medianLong(gcNanos)
    val medianRiftOp = medianLong(riftOpNanos)
    val medianObjects = medianLong(riftObjects)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=checked-priority-queue-$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )
  }

  def printConfig(mode: String): Unit = {
    val cfg = CheckedRegionPriorityQueueConfig
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} records_per_epoch=${cfg.recordsPerEpoch} top_k=${cfg.topK} initial_capacity=${cfg.initialCapacity}"
    )
  }
}

@main def CheckedRegionPriorityQueueMatrix(mode: String = "heap"): Unit = {
  if (mode != "heap" && mode != "rift-checked")
    throw new IllegalArgumentException(
      s"unknown checked-priority-queue mode '$mode'; expected heap or rift-checked"
    )

  CheckedRegionPriorityQueueMatrixHelpers.printConfig(mode)
  val usesRift = mode == "rift-checked"
  if (usesRift) RiftRegion.init(0)
  try CheckedRegionPriorityQueueMatrixHelpers.runBenchmark(mode)
  finally if (usesRift) RiftRegion.shutdown()
}
