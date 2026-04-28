import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedRegionIndexedPriorityQueueConfig {
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

  val epochs: Int = envInt("CHECKED_IPQ_EPOCHS", 8)
  val eventsPerEpoch: Int = envInt("CHECKED_IPQ_EVENTS_PER_EPOCH", 125000)
  val keyCapacity: Int = envInt("CHECKED_IPQ_KEY_CAPACITY", 65536)
  val initialCapacity: Int = envInt("CHECKED_IPQ_INITIAL_CAPACITY", 1024)
  val topK: Int = envInt("CHECKED_IPQ_TOP_K", 128)
  val warmupRuns: Int = envInt("CHECKED_IPQ_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_IPQ_BENCHMARK_RUNS", 3)
}

object CheckedRegionIndexedPriorityQueueMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(val key: Int) {
    var count: Int = 0
    var total: Long = 0L
    var lastValue: Int = 0
  }

  private final class HeapIndexedPriorityQueue[T <: Object](
      keyCapacity: Int,
      initialCapacity: Int
  ) {
    private var items =
      new Array[Object](if (initialCapacity <= 0) 1 else initialCapacity)
    private var priorities = new Array[Long](items.length)
    private var keys = new Array[Int](items.length)
    private val heapIndexByKey = new Array[Int](keyCapacity)
    private var used = 0

    def length: Int = used

    def contains(key: Int): Boolean = {
      checkKey(key)
      heapIndexByKey(key) != 0
    }

    def get(key: Int): T = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0)
        throw new NoSuchElementException("key is absent")
      items(slot - 1).asInstanceOf[T]
    }

    def put(key: Int, value: T, priority: Long): Unit = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot != 0) {
        val index = slot - 1
        items(index) = value
        priorities(index) = priority
        fixAt(index)
      } else {
        if (used >= items.length) grow()
        val index = used
        used += 1
        items(index) = value
        priorities(index) = priority
        keys(index) = key
        heapIndexByKey(key) = index + 1
        siftUp(index)
      }
    }

    def updatePriority(key: Int, priority: Long): Boolean = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0) false
      else {
        val index = slot - 1
        priorities(index) = priority
        fixAt(index)
        true
      }
    }

    def pop(): T = {
      if (used == 0)
        throw new NoSuchElementException("HeapIndexedPriorityQueue is empty")
      val result = items(0)
      removeAt(0)
      result.asInstanceOf[T]
    }

    private def checkKey(key: Int): Unit =
      if (key < 0 || key >= heapIndexByKey.length)
        throw new IndexOutOfBoundsException(key.toString)

    private def grow(): Unit = {
      val nextItems = new Array[Object](items.length * 2)
      val nextPriorities = new Array[Long](priorities.length * 2)
      val nextKeys = new Array[Int](keys.length * 2)
      var i = 0
      while (i < used) {
        nextItems(i) = items(i)
        nextPriorities(i) = priorities(i)
        nextKeys(i) = keys(i)
        i += 1
      }
      items = nextItems
      priorities = nextPriorities
      keys = nextKeys
    }

    private def removeAt(index: Int): Unit = {
      val removedKey = keys(index)
      heapIndexByKey(removedKey) = 0
      val last = used - 1
      used = last
      if (index != last) {
        items(index) = items(last)
        priorities(index) = priorities(last)
        keys(index) = keys(last)
        heapIndexByKey(keys(index)) = index + 1
        items(last) = null
        priorities(last) = 0L
        keys(last) = 0
        fixAt(index)
      } else {
        items(index) = null
        priorities(index) = 0L
        keys(index) = 0
      }
    }

    private def fixAt(index: Int): Unit = {
      val beforeKey = keys(index)
      siftUp(index)
      val afterSlot = heapIndexByKey(beforeKey)
      if (afterSlot != 0) siftDown(afterSlot - 1)
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
      val leftKey = keys(left)
      items(left) = items(right)
      priorities(left) = priorities(right)
      keys(left) = keys(right)
      heapIndexByKey(keys(left)) = left + 1
      items(right) = leftItem
      priorities(right) = leftPriority
      keys(right) = leftKey
      heapIndexByKey(keys(right)) = right + 1
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
    private val zeroRaw = 0L

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= start) end - start else zeroRaw

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

  private def priority(count: Int, total: Long, lastValue: Int): Long =
    (count.toLong << 42) ^ ((total & 0x1ffffffffffL) << 1) ^ lastValue.toLong

  private def updateRecord(record: HeapRecord, value: Int): Long = {
    record.count += 1
    record.total += value.toLong
    record.lastValue = value
    priority(record.count, record.total, record.lastValue)
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
    val cfg = CheckedRegionIndexedPriorityQueueConfig
    var checksum = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      val queue =
        new HeapIndexedPriorityQueue[HeapRecord](
          cfg.keyCapacity,
          cfg.initialCapacity
        )
      var i = 0
      while (i < cfg.eventsPerEpoch) {
        val seed = mix(epoch * 1000003 + i * 131)
        val key = seed % cfg.keyCapacity
        val value = (mix(seed + 19) & 0xffff) + 1
        if (queue.contains(key)) {
          val record = queue.get(key)
          queue.updatePriority(key, updateRecord(record, value))
        } else {
          val record = new HeapRecord(key)
          queue.put(key, record, updateRecord(record, value))
        }
        i += 1
      }

      var remaining = math.min(cfg.topK, queue.length)
      while (remaining > 0) {
        val record = queue.pop()
        checksum =
          (checksum * 1099511628211L) ^
            record.key.toLong ^
            record.count.toLong ^
            record.total
        remaining -= 1
      }
      epoch += 1
    }
    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    val cfg = CheckedRegionIndexedPriorityQueueConfig
    val checksum = RiftRegion.streaming { stream ?=>
      var running = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        RiftRegion.reset { region ?=>
          final class Record(val key: Int) {
            var count: Int = 0
            var total: Long = 0L
            var lastValue: Int = 0
          }

          def recordPriority(record: Record^{region}): Long =
            priority(record.count, record.total, record.lastValue)

          val queue =
            RiftRegion.regionIndexedPriorityQueue[Record](
              cfg.keyCapacity,
              cfg.initialCapacity
            )
          var i = 0
          while (i < cfg.eventsPerEpoch) {
            val seed = mix(epoch * 1000003 + i * 131)
            val key = seed % cfg.keyCapacity
            val value = (mix(seed + 19) & 0xffff) + 1
            if (region.contains(queue, key)) {
              val record = region.get(queue, key)
              record.count += 1
              record.total += value.toLong
              record.lastValue = value
              region.updatePriority(queue, key, recordPriority(record))
            } else {
              val record: Record^{region} =
                RiftRegion.alloc(new Record(key))
              record.count = 1
              record.total = value.toLong
              record.lastValue = value
              region.put(queue, key, record, recordPriority(record))
            }
            i += 1
          }

          var remaining = math.min(cfg.topK, region.length(queue))
          while (remaining > 0) {
            val record = region.pop(queue)
            running =
              (running * 1099511628211L) ^
                record.key.toLong ^
                record.count.toLong ^
                record.total
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
          s"unknown checked-indexed-priority-queue mode '$other'; expected heap or rift-checked"
        )
    }

  def runBenchmark(mode: String): Unit = {
    val cfg = CheckedRegionIndexedPriorityQueueConfig
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
      f"RESULT name=checked-indexed-priority-queue-$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )
  }

  def printConfig(mode: String): Unit = {
    val cfg = CheckedRegionIndexedPriorityQueueConfig
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} events_per_epoch=${cfg.eventsPerEpoch} key_capacity=${cfg.keyCapacity} top_k=${cfg.topK} initial_capacity=${cfg.initialCapacity}"
    )
  }
}

@main def CheckedRegionIndexedPriorityQueueMatrix(mode: String = "heap"): Unit = {
  if (mode != "heap" && mode != "rift-checked")
    throw new IllegalArgumentException(
      s"unknown checked-indexed-priority-queue mode '$mode'; expected heap or rift-checked"
    )

  CheckedRegionIndexedPriorityQueueMatrixHelpers.printConfig(mode)
  val usesRift = mode == "rift-checked"
  if (usesRift) RiftRegion.init(0)
  try CheckedRegionIndexedPriorityQueueMatrixHelpers.runBenchmark(mode)
  finally if (usesRift) RiftRegion.shutdown()
}
