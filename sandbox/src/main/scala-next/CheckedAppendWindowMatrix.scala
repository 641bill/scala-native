import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object CheckedAppendWindowConfig {
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

  val events: Int = envInt("CHECKED_APPEND_EVENTS", 1000000)
  val eventsPerBucket: Int =
    envInt("CHECKED_APPEND_EVENTS_PER_BUCKET", 25000)
  val windowBuckets: Int = envInt("CHECKED_APPEND_WINDOW_BUCKETS", 8)
  val keySpace: Int = envInt("CHECKED_APPEND_KEY_SPACE", 65536)
  val sampleEvery: Int = envInt("CHECKED_APPEND_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("CHECKED_APPEND_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("CHECKED_APPEND_BENCHMARK_RUNS", 3)
  val apiDiagnostics: Boolean =
    sys.env.get("CHECKED_APPEND_API_DIAG").exists(value =>
      value.nonEmpty && value != "0"
    )
}

object CheckedAppendWindowMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class HeapRecord(
      val key: Int,
      var value: Int,
      var total: Long,
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
      var total: Long,
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

  private def fold(
      checksum: Long,
      key: Int,
      value: Int,
      total: Long,
      bucketStartSeconds: Long
  ): Long =
    (((checksum ^ key.toLong) * 1099511628211L) ^
      value.toLong ^
      total ^
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
    val cfg = CheckedAppendWindowConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartSeconds: Long): Long = {
    val cfg = CheckedAppendWindowConfig
    currentStartSeconds -
      (cfg.windowBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  def runHeap(): Long = {
    val cfg = CheckedAppendWindowConfig
    val totals = new Array[Long](cfg.keySpace)
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
          val nextTotal =
            (totals(record.key) + record.total) & 0xffffffffL
          totals(record.key) = nextTotal
          checksum =
            fold(checksum, record.key, record.value, nextTotal, bucket.startSeconds)
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
      val seed = mix(i * 1103515245 + 12345)
      val key = seed % cfg.keySpace
      val value = (mix(seed + 17) & 0xffff) + 1
      val startSeconds = bucketStart(i)
      val bucket = bucketFor(startSeconds)
      val record = new HeapRecord(key, value, value.toLong, null)
      record.value += seed & 3
      record.total += record.value.toLong
      if (bucket.head == null) {
        bucket.head = record
        bucket.tail = record
      } else {
        bucket.tail.next = record
        bucket.tail = record
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(
          checksum,
          record.key,
          record.value,
          record.total,
          bucket.startSeconds
        )
      i += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    checksum
  }

  def runRiftChecked(): Long = {
    val cfg = CheckedAppendWindowConfig
    val totals = new Array[Long](cfg.keySpace)
    val checksum = RiftRegion.streaming { stream ?=>
      final class Bucket(
          val child: RiftRegion.ChildBucket^{stream},
          val startSeconds: Long,
          var next: Bucket^{stream}
      ) {
        final class Record(
            val key: Int,
            var value: Int,
            var total: Long,
            var next: Record^{child.region}
        )
        var head: Record^{child.region} = null
        var tail: Record^{child.region} = null
      }

      var first: Bucket^{stream} = null
      var last: Bucket^{stream} = null
      var current: Bucket^{stream} = null
      var running = 0L

      def closeExpired(cutoffSeconds: Long): Unit =
        while (
          first != null &&
          first.startSeconds + cfg.eventsPerBucket.toLong <= cutoffSeconds
        ) {
          val bucket = first
          RiftRegion.closeChildBucket(stream, bucket.child) {
            var record = bucket.head
            while (record != null) {
              val nextTotal =
                (totals(record.key) + record.total) & 0xffffffffL
              totals(record.key) = nextTotal
              running = fold(
                running,
                record.key,
                record.value,
                nextTotal,
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
        }

      def bucketFor(startSeconds: Long): Bucket^{stream} =
        if (current != null && current.startSeconds == startSeconds) current
        else {
          closeExpired(closeCutoff(startSeconds))
          val child = RiftRegion.childBucket
          val bucket: Bucket^{stream} =
            new Bucket(child, startSeconds, null)
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
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        val bucket = bucketFor(startSeconds)
        val bucketRegion = bucket.child.region
        val record: bucket.Record^{bucketRegion} =
          RiftRegion.alloc(
            new bucket.Record(key, value, value.toLong, null)
          )(using bucketRegion)
        record.value += seed & 3
        record.total += record.value.toLong
        if (bucket.head == null) {
          bucket.head = record
          bucket.tail = record
        } else {
          bucket.tail.next = record
          bucket.tail = record
        }
        if (i % cfg.sampleEvery == 0)
          running = fold(
            running,
            record.key,
            record.value,
            record.total,
            bucket.startSeconds
          )
        i += 1
      }

      closeExpired(Long.MaxValue)
      running
    }
    checksumSink = checksum
    checksum
  }

  private def runRiftCheckedApiFast(): Long = {
    val cfg = CheckedAppendWindowConfig
    val totals = new Array[Long](cfg.keySpace)
    val checksum = RiftRegion.streaming { stream ?=>
      final class Record(
          val key: Int,
          var value: Int,
          var total: Long
      ) extends RiftRegion.StreamAppendNode
      val window =
        RiftRegion.streamAppendWindow[Record](cfg.eventsPerBucket.toLong)
      var running = 0L

      def closeExpired(cutoffSeconds: Long): Unit =
        RiftRegion.closeAppendWindowBucketsBefore(
          stream,
          window,
          cutoffSeconds
        ) { (bucket, record) =>
          val nextTotal =
            (totals(record.key) + record.total) & 0xffffffffL
          totals(record.key) = nextTotal
          running = fold(
            running,
            record.key,
            record.value,
            nextTotal,
            bucket.startSeconds
          )
        }

      var currentStartSeconds = Long.MinValue
      var i = 0
      while (i < cfg.events) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        if (startSeconds != currentStartSeconds) {
          closeExpired(closeCutoff(startSeconds))
          currentStartSeconds = startSeconds
        }
        val bucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, startSeconds)
        val bucketRegion = RiftRegion.streamBucketRegion(stream, bucket)
        val record: Record^{stream} =
          RiftRegion.alloc(new Record(key, value, value.toLong))(
            using bucketRegion
          )
        record.value += seed & 3
        record.total += record.value.toLong
        RiftRegion.appendWindow(stream, window, bucket, record)
        if (i % cfg.sampleEvery == 0)
          running = fold(
            running,
            record.key,
            record.value,
            record.total,
            bucket.startSeconds
          )
        i += 1
      }

      RiftRegion.closeAllAppendWindowBuckets(stream, window) {
        (bucket, record) =>
          val nextTotal =
            (totals(record.key) + record.total) & 0xffffffffL
          totals(record.key) = nextTotal
          running = fold(
            running,
            record.key,
            record.value,
            nextTotal,
            bucket.startSeconds
          )
      }
      running
    }
    checksumSink = checksum
    checksum
  }

  private def runRiftCheckedApiDiagnostic(): Long = {
    val cfg = CheckedAppendWindowConfig
    val totals = new Array[Long](cfg.keySpace)
    val checksum = RiftRegion.streaming { stream ?=>
      final class Record(
          val key: Int,
          var value: Int,
          var total: Long
      ) extends RiftRegion.StreamAppendNode
      val window =
        RiftRegion.streamAppendWindow[Record](cfg.eventsPerBucket.toLong)
      var running = 0L
      var bucketLookups = 0L
      var currentBucketHits = 0L
      var bucketOpens = 0L
      var appends = 0L
      var closeBuckets = 0L
      var closeEntries = 0L
      var lastClosedStartSeconds = Long.MinValue

      def recordClosedEntry(
          bucket: RiftRegion.StreamBucket^{stream},
          record: Record^{stream}
      ): Unit = {
        if (bucket.startSeconds != lastClosedStartSeconds) {
          closeBuckets += 1L
          lastClosedStartSeconds = bucket.startSeconds
        }
        closeEntries += 1L
        val nextTotal =
          (totals(record.key) + record.total) & 0xffffffffL
        totals(record.key) = nextTotal
        running = fold(
          running,
          record.key,
          record.value,
          nextTotal,
          bucket.startSeconds
        )
      }

      def closeExpired(cutoffSeconds: Long): Unit =
        RiftRegion.closeAppendWindowBucketsBefore(
          stream,
          window,
          cutoffSeconds
        ) { (bucket, record) =>
          recordClosedEntry(bucket, record)
        }

      var currentStartSeconds = Long.MinValue
      var i = 0
      while (i < cfg.events) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        if (startSeconds != currentStartSeconds) {
          closeExpired(closeCutoff(startSeconds))
          currentStartSeconds = startSeconds
          bucketOpens += 1L
        } else {
          currentBucketHits += 1L
        }
        bucketLookups += 1L
        val bucket =
          RiftRegion.streamAppendWindowBucketFor(stream, window, startSeconds)
        val bucketRegion = RiftRegion.streamBucketRegion(stream, bucket)
        val record: Record^{stream} =
          RiftRegion.alloc(new Record(key, value, value.toLong))(
            using bucketRegion
          )
        record.value += seed & 3
        record.total += record.value.toLong
        RiftRegion.appendWindow(stream, window, bucket, record)
        appends += 1L
        if (i % cfg.sampleEvery == 0)
          running = fold(
            running,
            record.key,
            record.value,
            record.total,
            bucket.startSeconds
          )
        i += 1
      }

      RiftRegion.closeAllAppendWindowBuckets(stream, window) {
        (bucket, record) =>
          recordClosedEntry(bucket, record)
      }

      val finalLiveLength = RiftRegion.appendWindowLength(stream, window)
      println(
        s"APPEND_API_DIAG mode=rift-checked-api " +
          s"bucket_lookups=$bucketLookups " +
          s"current_bucket_hits=$currentBucketHits " +
          s"bucket_opens=$bucketOpens " +
          s"appends=$appends " +
          s"close_buckets=$closeBuckets " +
          s"close_entries=$closeEntries " +
          s"final_live_length=$finalLiveLength"
      )
      running
    }
    checksumSink = checksum
    checksum
  }

  def runRiftCheckedApi(): Long =
    if (CheckedAppendWindowConfig.apiDiagnostics)
      runRiftCheckedApiDiagnostic()
    else runRiftCheckedApiFast()

  def runRiftTrusted(kind: Int): Long = {
    val cfg = CheckedAppendWindowConfig
    val totals = new Array[Long](cfg.keySpace)
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L

    def closeBucket(bucket: TrustedBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        val nextTotal =
          (totals(record.key) + record.total) & 0xffffffffL
        totals(record.key) = nextTotal
        checksum =
          fold(checksum, record.key, record.value, nextTotal, bucket.startSeconds)
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
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        val bucket = bucketFor(startSeconds)
        val record =
          bucket.region.alloc(new TrustedRecord(key, value, value.toLong, null))
        record.value += seed & 3
        record.total += record.value.toLong
        if (bucket.head == null) {
          bucket.head = record
          bucket.tail = record
        } else {
          bucket.tail.next = record
          bucket.tail = record
        }
        if (i % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            record.key,
            record.value,
            record.total,
            bucket.startSeconds
          )
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
      case "rift-checked-api"       => runRiftCheckedApi()
      case "rift-trusted-hp"        => runRiftTrusted(RiftRegion.HPZone)
      case "rift-trusted-streaming" => runRiftTrusted(RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-append-window mode '$other'"
        )
    }

  def validateMode(mode: String): Unit =
    runModeName(mode)

  private def runModeName(mode: String): String =
    mode match {
      case "heap" | "rift-checked" | "rift-trusted-hp" |
          "rift-trusted-streaming" | "rift-checked-api" =>
        mode
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-append-window mode '$other'"
        )
    }

  def runBenchmark(mode: String): Unit = {
    val cfg = CheckedAppendWindowConfig
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
      s"Running checked-append-window-$mode for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=checked-append-window-$mode " +
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
    val cfg = CheckedAppendWindowConfig
    println(
      s"CONFIG mode=$mode runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} window_buckets=${cfg.windowBuckets} key_space=${cfg.keySpace} sample_every=${cfg.sampleEvery}"
    )
  }
}

@main def CheckedAppendWindowMatrix(mode: String = "heap"): Unit = {
  CheckedAppendWindowMatrixHelpers.validateMode(mode)
  CheckedAppendWindowMatrixHelpers.printConfig(mode)

  val usesRift = mode != "heap"
  if (usesRift) RiftRegion.init(0)
  try {
    CheckedAppendWindowMatrixHelpers.runBenchmark(mode)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
