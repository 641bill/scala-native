import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedStreamWindowRankConfig {
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

  private def envLong(name: String, default: Long): Long =
    sys.env
      .get(name)
      .flatMap(value =>
        try {
          val parsed = value.toLong
          if (parsed > 0L) Some(parsed) else None
        } catch {
          case _: NumberFormatException => None
        }
      )
      .getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env
      .get(name)
      .flatMap(value =>
        try {
          val parsed = value.toInt
          if (parsed >= 0) Some(parsed) else None
        } catch {
          case _: NumberFormatException => None
        }
      )
      .getOrElse(default)

  val events: Int = envInt("CHECKED_SWR_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("CHECKED_SWR_EVENTS_PER_BUCKET", 25000)
  val keyCapacity: Int = envInt("CHECKED_SWR_KEY_CAPACITY", 65536)
  val bucketSeconds: Long = envLong("CHECKED_SWR_BUCKET_SECONDS", 60L)
  val windowBuckets: Int = envInt("CHECKED_SWR_WINDOW_BUCKETS", 8)
  val initialRankCapacity: Int =
    envInt("CHECKED_SWR_INITIAL_RANK_CAPACITY", 1024)
  val topK: Int = envInt("CHECKED_SWR_TOP_K", 128)
  val sampleEvery: Int = envInt("CHECKED_SWR_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("CHECKED_SWR_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_SWR_BENCHMARK_RUNS", 3)
}

object CheckedStreamWindowRankMatrixHelpers {
  private final val EmptyBucketStart = Long.MinValue

  @volatile private var checksumSink = 0L

  private final class HeapRecord(val key: Int, val bucketStart: Long) {
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

    def remove(key: Int): Boolean = {
      checkKey(key)
      val slot = heapIndexByKey(key)
      if (slot == 0) false
      else {
        removeAt(slot - 1)
        true
      }
    }

    def peek(): T = {
      if (used == 0)
        throw new NoSuchElementException("HeapIndexedPriorityQueue is empty")
      items(0).asInstanceOf[T]
    }

    def peekKey(): Int = {
      if (used == 0)
        throw new NoSuchElementException("HeapIndexedPriorityQueue is empty")
      keys(0)
    }

    def peekPriority(): Long = {
      if (used == 0)
        throw new NoSuchElementException("HeapIndexedPriorityQueue is empty")
      priorities(0)
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

  private def priority(
      key: Int,
      count: Int,
      total: Long,
      lastValue: Int
  ): Long =
    (count.toLong << 48) ^
      ((total & 0xffffffffL) << 16) ^
      ((lastValue & 0xff).toLong << 8) ^
      (key & 0xff).toLong

  private def foldRecord(
      checksum: Long,
      key: Int,
      bucketStart: Long,
      count: Int,
      total: Long,
      lastValue: Int,
      rankPriority: Long
  ): Long =
    (checksum * 1099511628211L) ^
      key.toLong ^
      bucketStart ^
      count.toLong ^
      total ^
      lastValue.toLong ^
      rankPriority

  private def bucketStartFor(eventIndex: Int): Long = {
    val cfg = CheckedStreamWindowRankConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.bucketSeconds
  }

  private def timestampFor(eventIndex: Int): Long =
    bucketStartFor(eventIndex) +
      (eventIndex % CheckedStreamWindowRankConfig.bucketSeconds.toInt).toLong

  private def cutoffFor(bucketStart: Long): Long = {
    val cfg = CheckedStreamWindowRankConfig
    bucketStart - ((cfg.windowBuckets - 1).toLong * cfg.bucketSeconds)
  }

  private def bucketSlot(bucketStart: Long, slotCount: Int): Int =
    ((bucketStart / CheckedStreamWindowRankConfig.bucketSeconds) %
      slotCount.toLong).toInt

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

  private def validateConfig(): Unit = {
    val cfg = CheckedStreamWindowRankConfig
    if (cfg.bucketSeconds <= 0L || cfg.bucketSeconds > Int.MaxValue)
      throw new IllegalArgumentException(
        "CHECKED_SWR_BUCKET_SECONDS must be positive and fit in Int"
      )
    if (cfg.windowBuckets <= 0)
      throw new IllegalArgumentException(
        "CHECKED_SWR_WINDOW_BUCKETS must be positive"
      )
    if (cfg.eventsPerBucket <= 0)
      throw new IllegalArgumentException(
        "CHECKED_SWR_EVENTS_PER_BUCKET must be positive"
      )
    if (cfg.keyCapacity <= 0)
      throw new IllegalArgumentException(
        "CHECKED_SWR_KEY_CAPACITY must be positive"
      )
  }

  def runHeap(): Long = {
    validateConfig()
    val cfg = CheckedStreamWindowRankConfig
    val recordByKey = new Array[HeapRecord](cfg.keyCapacity)
    val nodeKeys = new Array[Int](cfg.events)
    val nodeNext = new Array[Int](cfg.events)
    val slotCount = cfg.windowBuckets + 2
    val bucketStarts = new Array[Long](slotCount)
    val bucketHeads = new Array[Int](slotCount)
    val rank =
      new HeapIndexedPriorityQueue[HeapRecord](
        cfg.keyCapacity,
        cfg.initialRankCapacity
      )

    var i = 0
    while (i < nodeNext.length) {
      nodeNext(i) = -1
      i += 1
    }
    i = 0
    while (i < slotCount) {
      bucketStarts(i) = EmptyBucketStart
      bucketHeads(i) = -1
      i += 1
    }

    var nodeUsed = 0

    def addBucketNode(slot: Int, key: Int): Unit = {
      if (nodeUsed >= nodeKeys.length)
        throw new IllegalStateException("bucket node capacity exhausted")
      nodeKeys(nodeUsed) = key
      nodeNext(nodeUsed) = bucketHeads(slot)
      bucketHeads(slot) = nodeUsed
      nodeUsed += 1
    }

    def clearBucket(bucketStart: Long): Unit = {
      val slot = bucketSlot(bucketStart, slotCount)
      if (bucketStarts(slot) == bucketStart) {
        var node = bucketHeads(slot)
        while (node >= 0) {
          val next = nodeNext(node)
          val key = nodeKeys(node)
          val record = recordByKey(key)
          if (record != null && record.bucketStart == bucketStart) {
            rank.remove(key)
            recordByKey(key) = null
          }
          nodeKeys(node) = 0
          nodeNext(node) = -1
          node = next
        }
        bucketStarts(slot) = EmptyBucketStart
        bucketHeads(slot) = -1
      }
    }

    def closeBefore(cutoffSeconds: Long): Unit = {
      var slot = 0
      while (slot < slotCount) {
        val start = bucketStarts(slot)
        if (
          start != EmptyBucketStart &&
          start + cfg.bucketSeconds <= cutoffSeconds
        )
          clearBucket(start)
        slot += 1
      }
    }

    def ensureBucket(bucketStart: Long): Int = {
      val slot = bucketSlot(bucketStart, slotCount)
      if (bucketStarts(slot) != bucketStart) {
        if (bucketStarts(slot) != EmptyBucketStart)
          throw new IllegalStateException(
            s"bucket slot reused before close old=${bucketStarts(slot)} new=$bucketStart"
          )
        bucketStarts(slot) = bucketStart
        bucketHeads(slot) = -1
      }
      slot
    }

    var checksum = 0L
    i = 0
    while (i < cfg.events) {
      val bucketStart = bucketStartFor(i)
      closeBefore(cutoffFor(bucketStart))
      val slot = ensureBucket(bucketStart)
      val seed = mix(i * 131 + (bucketStart / cfg.bucketSeconds).toInt)
      val key = seed % cfg.keyCapacity
      val value = (mix(seed + 19) & 0xffff) + 1
      val existing = recordByKey(key)
      if (existing != null && existing.bucketStart == bucketStart) {
        existing.count += 1
        existing.total += value.toLong
        existing.lastValue = value
        rank.updatePriority(
          key,
          priority(
            existing.key,
            existing.count,
            existing.total,
            existing.lastValue
          )
        )
      } else {
        val record = new HeapRecord(key, bucketStart)
        record.count = 1
        record.total = value.toLong
        record.lastValue = value
        recordByKey(key) = record
        addBucketNode(slot, key)
        rank.put(
          key,
          record,
          priority(record.key, record.count, record.total, value)
        )
      }

      if (rank.length > 0 && (i % cfg.sampleEvery) == 0) {
        val record = rank.peek()
        checksum =
          foldRecord(
            checksum,
            rank.peekKey(),
            record.bucketStart,
            record.count,
            record.total,
            record.lastValue,
            rank.peekPriority()
          )
      }
      i += 1
    }

    var remaining = math.min(cfg.topK, rank.length)
    while (remaining > 0) {
      val record = rank.pop()
      val rankPriority =
        priority(record.key, record.count, record.total, record.lastValue)
      checksum =
        foldRecord(
          checksum,
          record.key,
          record.bucketStart,
          record.count,
          record.total,
          record.lastValue,
          rankPriority
        )
      recordByKey(record.key) = null
      remaining -= 1
    }

    i = 0
    while (i < slotCount) {
      val start = bucketStarts(i)
      if (start != EmptyBucketStart) clearBucket(start)
      i += 1
    }

    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    validateConfig()
    val cfg = CheckedStreamWindowRankConfig
    val checksum = RiftRegion.streaming { stream ?=>
      final class Record(val key: Int, val bucketStart: Long) {
        var count: Int = 0
        var total: Long = 0L
        var lastValue: Int = 0
      }

      def recordPriority(record: Record^{stream}): Long =
        priority(record.key, record.count, record.total, record.lastValue)

      val rank =
        RiftRegion.streamWindowIndexedRank[Record](
          cfg.bucketSeconds,
          cfg.keyCapacity,
          cfg.initialRankCapacity
        )
      val recordByKey: Array[Record^{stream}]^{stream} =
        RiftRegion.alloc(new Array[Record^{stream}](cfg.keyCapacity))
      val nodeKeys: Array[Int]^{stream} =
        RiftRegion.alloc(new Array[Int](cfg.events))
      val nodeNext: Array[Int]^{stream} =
        RiftRegion.alloc(new Array[Int](cfg.events))
      val slotCount = cfg.windowBuckets + 2
      val bucketStarts: Array[Long]^{stream} =
        RiftRegion.alloc(new Array[Long](slotCount))
      val bucketHeads: Array[Int]^{stream} =
        RiftRegion.alloc(new Array[Int](slotCount))

      var i = 0
      while (i < nodeNext.length) {
        nodeNext(i) = -1
        i += 1
      }
      i = 0
      while (i < slotCount) {
        bucketStarts(i) = EmptyBucketStart
        bucketHeads(i) = -1
        i += 1
      }

      var nodeUsed = 0

      def addBucketNode(slot: Int, key: Int): Unit = {
        if (nodeUsed >= nodeKeys.length)
          throw new IllegalStateException("bucket node capacity exhausted")
        nodeKeys(nodeUsed) = key
        nodeNext(nodeUsed) = bucketHeads(slot)
        bucketHeads(slot) = nodeUsed
        nodeUsed += 1
      }

      def clearBucket(bucketStart: Long): Unit = {
        val slot = bucketSlot(bucketStart, slotCount)
        if (bucketStarts(slot) == bucketStart) {
          var node = bucketHeads(slot)
          while (node >= 0) {
            val next = nodeNext(node)
            val key = nodeKeys(node)
            val record = recordByKey(key)
            if (record != null && record.bucketStart == bucketStart) {
              RiftRegion.removeWindowRank(stream, rank, key)
              recordByKey(key) = null
            }
            nodeKeys(node) = 0
            nodeNext(node) = -1
            node = next
          }
          bucketStarts(slot) = EmptyBucketStart
          bucketHeads(slot) = -1
        }
      }

      def ensureBucketSlot(bucketStart: Long): Int = {
        val slot = bucketSlot(bucketStart, slotCount)
        if (bucketStarts(slot) != bucketStart) {
          if (bucketStarts(slot) != EmptyBucketStart)
            throw new IllegalStateException(
              s"bucket slot reused before close old=${bucketStarts(slot)} new=$bucketStart"
            )
          bucketStarts(slot) = bucketStart
          bucketHeads(slot) = -1
        }
        slot
      }

      var checksum = 0L
      i = 0
      while (i < cfg.events) {
        val bucketStart = bucketStartFor(i)
        RiftRegion.closeWindowRankBucketsBefore(
          stream,
          rank,
          cutoffFor(bucketStart)
        ) { bucket =>
          clearBucket(bucket.startSeconds)
        }
        val bucket =
          RiftRegion.streamWindowBucketFor(
            stream,
            rank,
            timestampFor(i)
          ) { opened =>
            ensureBucketSlot(opened.startSeconds)
          }
        val slot = ensureBucketSlot(bucket.startSeconds)
        val seed = mix(i * 131 + (bucketStart / cfg.bucketSeconds).toInt)
        val key = seed % cfg.keyCapacity
        val value = (mix(seed + 19) & 0xffff) + 1
        val existing = recordByKey(key)
        if (existing != null && existing.bucketStart == bucket.startSeconds) {
          existing.count += 1
          existing.total += value.toLong
          existing.lastValue = value
          RiftRegion.updateWindowRankPriority(
            stream,
            rank,
            key,
            recordPriority(existing)
          )
        } else {
          val child = RiftRegion.streamBucketRegion(stream, bucket)
          val record: Record^{stream} =
            RiftRegion.alloc(new Record(key, bucket.startSeconds))(using child)
          record.count = 1
          record.total = value.toLong
          record.lastValue = value
          recordByKey(key) = record
          addBucketNode(slot, key)
          RiftRegion.putWindowRank(
            stream,
            rank,
            key,
            record,
            recordPriority(record)
          )
        }

        if (
          RiftRegion.windowRankLength(stream, rank) > 0 &&
          (i % cfg.sampleEvery) == 0
        ) {
          val record = RiftRegion.peekWindowRank(stream, rank)
          checksum =
            foldRecord(
              checksum,
              RiftRegion.peekWindowRankKey(stream, rank),
              record.bucketStart,
              record.count,
              record.total,
              record.lastValue,
              RiftRegion.peekWindowRankPriority(stream, rank)
            )
        }
        i += 1
      }

      var remaining =
        math.min(cfg.topK, RiftRegion.windowRankLength(stream, rank))
      while (remaining > 0) {
        val record = RiftRegion.popWindowRank(stream, rank)
        checksum =
          foldRecord(
            checksum,
            record.key,
            record.bucketStart,
            record.count,
            record.total,
            record.lastValue,
            recordPriority(record)
          )
        recordByKey(record.key) = null
        remaining -= 1
      }

      RiftRegion.closeAllWindowRankBuckets(stream, rank) { bucket =>
        clearBucket(bucket.startSeconds)
      }

      checksum
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
          s"unknown checked-stream-window-rank mode '$other'; expected heap or rift-checked"
        )
    }

  def runBenchmark(mode: String): Unit = {
    validateConfig()
    val cfg = CheckedStreamWindowRankConfig
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
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d " +
          f"rift_open_total=${runtime.riftRegionOpenTotal}%d " +
          f"rift_close_total=${runtime.riftRegionCloseTotal}%d " +
          f"rift_reset_total=${runtime.riftRegionResetTotal}%d"
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
      f"RESULT name=checked-stream-window-rank-$mode " +
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
    val cfg = CheckedStreamWindowRankConfig
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} key_capacity=${cfg.keyCapacity} bucket_seconds=${cfg.bucketSeconds} window_buckets=${cfg.windowBuckets} top_k=${cfg.topK} sample_every=${cfg.sampleEvery} initial_rank_capacity=${cfg.initialRankCapacity}"
    )
  }
}

@main def CheckedStreamWindowRankMatrix(mode: String = "heap"): Unit = {
  if (mode != "heap" && mode != "rift-checked")
    throw new IllegalArgumentException(
      s"unknown checked-stream-window-rank mode '$mode'; expected heap or rift-checked"
    )

  CheckedStreamWindowRankMatrixHelpers.printConfig(mode)
  val usesRift = mode == "rift-checked"
  if (usesRift) RiftRegion.init(0)
  try CheckedStreamWindowRankMatrixHelpers.runBenchmark(mode)
  finally if (usesRift) RiftRegion.shutdown()
}
