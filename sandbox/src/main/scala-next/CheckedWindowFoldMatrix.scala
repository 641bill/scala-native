import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedWindowFoldConfig {
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

  val events: Int = envInt("CHECKED_FOLD_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("CHECKED_FOLD_EVENTS_PER_BUCKET", 25000)
  val windowBuckets: Int = envInt("CHECKED_FOLD_WINDOW_BUCKETS", 8)
  val keySpace: Int = envInt("CHECKED_FOLD_KEY_SPACE", 65536)
  val sampleEvery: Int = envInt("CHECKED_FOLD_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("CHECKED_FOLD_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_FOLD_BENCHMARK_RUNS", 3)
}

object CheckedWindowFoldMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(
      val key: Int,
      var value: Int,
      var delta: Long,
      var next: HeapRecord
  )

  private final class HeapBucket(
      val startSeconds: Long,
      var next: HeapBucket
  ) {
    var head: HeapRecord = null
    var tail: HeapRecord = null
  }

  private final class TrustedRecord(
      val key: Int,
      var value: Int,
      var delta: Long,
      var next: TrustedRecord
  )

  private final class TrustedBucket(
      val region: RiftRegion,
      val startSeconds: Long,
      var next: TrustedBucket
  ) {
    var head: TrustedRecord = null
    var tail: TrustedRecord = null
  }

  private final class HeapFoldTable(initialCapacity: Int) {
    private final val Empty: Byte = 0
    private final val Used: Byte = 1
    private final val Deleted: Byte = 2

    private var keys = new Array[Int](nextPowerOfTwo(initialCapacity * 2))
    private var sums = new Array[Long](keys.length)
    private var counts = new Array[Int](keys.length)
    private var states = new Array[Byte](keys.length)
    private var size = 0
    private var deleted = 0

    private def findSlot(key: Int): Int = {
      val mask = states.length - 1
      var slot = hash(key) & mask
      while (states(slot) != Empty) {
        if (states(slot) == Used && keys(slot) == key) return slot
        slot = (slot + 1) & mask
      }
      -1
    }

    private def findInsertSlot(key: Int): Int = {
      val mask = states.length - 1
      var slot = hash(key) & mask
      var firstDeleted = -1
      while (states(slot) != Empty) {
        val state = states(slot)
        if (state == Used && keys(slot) == key) return slot
        if (state == Deleted && firstDeleted < 0) firstDeleted = slot
        slot = (slot + 1) & mask
      }
      if (firstDeleted >= 0) firstDeleted else slot
    }

    private def rehash(nextCapacity: Int): Unit = {
      val oldKeys = keys
      val oldSums = sums
      val oldCounts = counts
      val oldStates = states
      keys = new Array[Int](nextCapacity)
      sums = new Array[Long](nextCapacity)
      counts = new Array[Int](nextCapacity)
      states = new Array[Byte](nextCapacity)
      size = 0
      deleted = 0

      var index = 0
      while (index < oldStates.length) {
        if (oldStates(index) == Used) {
          val slot = findInsertSlot(oldKeys(index))
          keys(slot) = oldKeys(index)
          sums(slot) = oldSums(index)
          counts(slot) = oldCounts(index)
          states(slot) = Used
          size += 1
        }
        index += 1
      }
    }

    private def ensureCapacity(): Unit =
      if ((size + deleted + 1) * 4 >= states.length * 3) {
        val nextCapacity =
          if (deleted > size / 2) states.length else states.length << 1
        rehash(nextCapacity)
      }

    def add(key: Int, delta: Long): Long = {
      ensureCapacity()
      val slot = findInsertSlot(key)
      if (states(slot) == Used) {
        val next = sums(slot) + delta
        sums(slot) = next
        counts(slot) += 1
        next
      } else {
        if (states(slot) == Deleted) deleted -= 1
        keys(slot) = key
        sums(slot) = delta
        counts(slot) = 1
        states(slot) = Used
        size += 1
        delta
      }
    }

    def remove(key: Int, delta: Long): Long = {
      val slot = findSlot(key)
      if (slot < 0 || counts(slot) <= 0)
        throw new IllegalStateException("heap fold count underflow")
      val nextCount = counts(slot) - 1
      val next = sums(slot) - delta
      if (nextCount == 0) {
        states(slot) = Deleted
        sums(slot) = 0L
        counts(slot) = 0
        size -= 1
        deleted += 1
        0L
      } else {
        counts(slot) = nextCount
        sums(slot) = next
        next
      }
    }

    def value(key: Int): Long = {
      val slot = findSlot(key)
      if (slot >= 0) sums(slot) else 0L
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

  private def nextPowerOfTwo(value: Int): Int = {
    var n = 1
    val target = if (value <= 1) 1 else value
    while (n > 0 && n < target) n <<= 1
    if (n > 0) n else 1 << 30
  }

  private def hash(key: Int): Int = {
    var x = key
    x ^= x >>> 16
    x *= 0x7feb352d
    x ^= x >>> 15
    x *= 0x846ca68b
    x ^ (x >>> 16)
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def foldChecksum(
      checksum: Long,
      key: Int,
      value: Int,
      aggregate: Long,
      bucketStartSeconds: Long
  ): Long =
    (((checksum ^ key.toLong) * 1099511628211L) ^
      value.toLong ^
      aggregate ^
      bucketStartSeconds)

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

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = CheckedWindowFoldConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartSeconds: Long): Long = {
    val cfg = CheckedWindowFoldConfig
    currentStartSeconds -
      (cfg.windowBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def eventSeed(index: Int): Int =
    mix(index * 1103515245 + 12345)

  private def eventKey(seed: Int): Int =
    seed % CheckedWindowFoldConfig.keySpace

  private def eventValue(seed: Int): Int =
    (mix(seed + 17) & 0xffff) + 1

  private def eventDelta(seed: Int, value: Int): Long =
    value.toLong + (seed & 3).toLong

  def runHeap(): Long = {
    val cfg = CheckedWindowFoldConfig
    val table = new HeapFoldTable(cfg.keySpace)
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L

    def closeExpired(cutoffSeconds: Long): Unit =
      while (
        first != null &&
        first.startSeconds + cfg.eventsPerBucket.toLong <= cutoffSeconds
      ) {
        val bucket = first
        var record = bucket.head
        while (record != null) {
          val nextAggregate = table.remove(record.key, record.delta)
          checksum = foldChecksum(
            checksum,
            record.key,
            record.value,
            nextAggregate,
            bucket.startSeconds
          )
          record = record.next
        }
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        bucket.head = null
        bucket.tail = null
        bucket.next = null
      }

    def bucketFor(startSeconds: Long): HeapBucket =
      if (current != null && current.startSeconds == startSeconds) current
      else {
        closeExpired(closeCutoff(startSeconds))
        val bucket = new HeapBucket(startSeconds, null)
        if (first == null) {
          first = bucket
          last = bucket
        } else {
          last.next = bucket
          last = bucket
        }
        current = bucket
        bucket
      }

    var i = 0
    while (i < cfg.events) {
      val seed = eventSeed(i)
      val key = eventKey(seed)
      var value = eventValue(seed)
      value += seed & 3
      val delta = eventDelta(seed, value)
      val startSeconds = bucketStart(i)
      val bucket = bucketFor(startSeconds)
      val record = new HeapRecord(key, value, delta, null)
      val aggregate = table.add(key, delta)
      if (bucket.head == null) {
        bucket.head = record
        bucket.tail = record
      } else {
        bucket.tail.next = record
        bucket.tail = record
      }
      if (i % cfg.sampleEvery == 0)
        checksum = foldChecksum(
          checksum,
          record.key,
          record.value,
          table.value(record.key),
          bucket.startSeconds
        )
      else checksum ^= aggregate & 1L
      i += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    val cfg = CheckedWindowFoldConfig
    val checksum = RiftRegion.streaming { stream ?=>
      final class Record(
          val key: Int,
          var value: Int,
          val delta: Long
      ) extends RiftRegion.StreamAppendNode

      val fold = RiftRegion.streamWindowFold[Record](
        cfg.eventsPerBucket.toLong,
        cfg.keySpace
      )
      var running = 0L

      def consume(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[Record]^{stream}
      ): Unit =
        while (cursor.hasNext) {
          val record: Record^{stream} = cursor.next()
          val nextAggregate =
            RiftRegion.removeFoldContribution(
              stream,
              fold,
              record.key,
              record.delta
            )
          running = foldChecksum(
            running,
            record.key,
            record.value,
            nextAggregate,
            bucket.startSeconds
          )
        }

      def closeExpired(cutoffSeconds: Long): Unit =
        RiftRegion.closeFoldBucketsBeforeWithCursor(stream, fold, cutoffSeconds) {
          (bucket, cursor) =>
            consume(bucket, cursor)
        }

      var currentStartSeconds = Long.MinValue
      var currentBucket: RiftRegion.StreamBucket^{stream} = null
      var currentBucketRegion: RiftRegion.StreamingRegion^{stream} = null
      var i = 0
      while (i < cfg.events) {
        val seed = eventSeed(i)
        val key = eventKey(seed)
        var value = eventValue(seed)
        value += seed & 3
        val delta = eventDelta(seed, value)
        val startSeconds = bucketStart(i)
        if (startSeconds != currentStartSeconds) {
          closeExpired(closeCutoff(startSeconds))
          currentStartSeconds = startSeconds
          currentBucket =
            RiftRegion.streamWindowFoldBucketFor(stream, fold, startSeconds)
          currentBucketRegion =
            RiftRegion.streamBucketRegion(stream, currentBucket)
        }
        val bucket = currentBucket
        val bucketRegion = currentBucketRegion
        val record: Record^{stream} =
          RiftRegion.alloc(new Record(key, value, delta))(using bucketRegion)
        val aggregate =
          RiftRegion.putFoldInBucket(stream, fold, bucket, key, delta, record)
        if (i % cfg.sampleEvery == 0)
          running = foldChecksum(
            running,
            record.key,
            record.value,
            RiftRegion.foldValue(stream, fold, record.key),
            bucket.startSeconds
          )
        else running ^= aggregate & 1L
        i += 1
      }

      RiftRegion.closeAllFoldBucketsWithCursor(stream, fold) { (bucket, cursor) =>
        consume(bucket, cursor)
      }
      running
    }
    checksumSink = checksum
    checksum
  }

  def runRiftTrusted(kind: Int): Long = {
    val cfg = CheckedWindowFoldConfig
    val table = new HeapFoldTable(cfg.keySpace)
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L

    def closeBucket(bucket: TrustedBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        val nextAggregate = table.remove(record.key, record.delta)
        checksum = foldChecksum(
          checksum,
          record.key,
          record.value,
          nextAggregate,
          bucket.startSeconds
        )
        record = record.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      bucket.region.close()
    }

    def closeExpired(cutoffSeconds: Long): Unit =
      while (
        first != null &&
        first.startSeconds + cfg.eventsPerBucket.toLong <= cutoffSeconds
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startSeconds: Long): TrustedBucket =
      if (current != null && current.startSeconds == startSeconds) current
      else {
        closeExpired(closeCutoff(startSeconds))
        val bucket = new TrustedBucket(RiftRegion.open(kind), startSeconds, null)
        if (first == null) {
          first = bucket
          last = bucket
        } else {
          last.next = bucket
          last = bucket
        }
        current = bucket
        bucket
      }

    var i = 0
    try {
      while (i < cfg.events) {
        val seed = eventSeed(i)
        val key = eventKey(seed)
        var value = eventValue(seed)
        value += seed & 3
        val delta = eventDelta(seed, value)
        val startSeconds = bucketStart(i)
        val bucket = bucketFor(startSeconds)
        val record =
          bucket.region.alloc(new TrustedRecord(key, value, delta, null))
        val aggregate = table.add(key, delta)
        if (bucket.head == null) {
          bucket.head = record
          bucket.tail = record
        } else {
          bucket.tail.next = record
          bucket.tail = record
        }
        if (i % cfg.sampleEvery == 0)
          checksum = foldChecksum(
            checksum,
            record.key,
            record.value,
            table.value(record.key),
            bucket.startSeconds
          )
        else checksum ^= aggregate & 1L
        i += 1
      }

      closeExpired(Long.MaxValue)
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket)
      }
      last = null
      current = null
    }

    checksumSink = checksum
    checksum
  }

  private def runMode(mode: String): Long =
    mode match {
      case "heap"                   => runHeap()
      case "rift-checked"           => runRiftChecked()
      case "rift-trusted-hp"        => runRiftTrusted(RiftRegion.HPZone)
      case "rift-trusted-streaming" => runRiftTrusted(RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-window-fold mode '$other'"
        )
    }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "rift-checked" | "rift-trusted-hp" |
          "rift-trusted-streaming" =>
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-window-fold mode '$other'"
        )
    }

  def runBenchmark(mode: String): Unit = {
    val cfg = CheckedWindowFoldConfig
    val usesRift = mode != "heap"
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
      s"Running checked-window-fold-$mode for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=checked-window-fold-$mode " +
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
    val cfg = CheckedWindowFoldConfig
    println(
      s"CHECKED_WINDOW_FOLD_CONFIG mode=$mode " +
        s"events=${cfg.events} " +
        s"events_per_bucket=${cfg.eventsPerBucket} " +
        s"window_buckets=${cfg.windowBuckets} " +
        s"key_space=${cfg.keySpace} " +
        s"sample_every=${cfg.sampleEvery} " +
        s"warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns}"
    )
  }
}

@main def CheckedWindowFoldMatrix(mode: String = "heap"): Unit = {
  CheckedWindowFoldMatrixHelpers.validateMode(mode)
  CheckedWindowFoldMatrixHelpers.printConfig(mode)
  RiftRegion.init(1)
  try CheckedWindowFoldMatrixHelpers.runBenchmark(mode)
  finally RiftRegion.shutdown()
}
