import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object CheckedPageTokenCostConfig {
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

  val events: Int = envInt("CHECKED_PAGE_TOKEN_COST_EVENTS", 1000000)
  val eventsPerBucket: Int =
    envInt("CHECKED_PAGE_TOKEN_COST_EVENTS_PER_BUCKET", 25000)
  val windowBuckets: Int =
    envInt("CHECKED_PAGE_TOKEN_COST_WINDOW_BUCKETS", 8)
  val keySpace: Int = envInt("CHECKED_PAGE_TOKEN_COST_KEY_SPACE", 65536)
  val sampleEvery: Int =
    envInt("CHECKED_PAGE_TOKEN_COST_SAMPLE_EVERY", 4096)
  val warmupRuns: Int =
    envNonNegativeInt("CHECKED_PAGE_TOKEN_COST_WARMUPS", 1)
  val benchmarkRuns: Int =
    envInt("CHECKED_PAGE_TOKEN_COST_BENCHMARK_RUNS", 3)
  val diagnostics: Boolean =
    sys.env.get("CHECKED_PAGE_TOKEN_COST_DIAG").exists(value =>
      value.nonEmpty && value != "0"
    )

  val bucketCount: Int =
    (events + eventsPerBucket - 1) / eventsPerBucket + 1
}

object CheckedPageTokenCostMatrixHelpers {
  @volatile private var checksumSink = 0L

  private val AppendOnly = "append-only"
  private val AppendDrain = "append-drain"
  private val AppendAggregate = "append-aggregate"
  private val AppendCountByKey = "append-count-by-key"
  private val CheckedCountByKey = "rift-checked-count-by-key"
  private val CheckedSafeZoneCountByKey =
    "rift-checked-safezone-count-by-key"

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

  private final class SafeZoneRecord(
      val key: Int,
      var value: Int,
      var total: Long,
      var next: SafeZoneRecord
  )

  private final class SafeZoneBucket(
      val zone: SafeZone,
      val startSeconds: Long,
      var next: SafeZoneBucket
  ) {
    var head: SafeZoneRecord = null
    var tail: SafeZoneRecord = null
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
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos()),
          riftSlowAllocNanos =
            rawSizeToLong(RiftAllocator.Impl.statsSlowAllocNanos())
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
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos),
        riftSlowAllocNanos =
          delta(end.riftSlowAllocNanos, start.riftSlowAllocNanos)
      )
  }

  private final class Diagnostics(val enabled: Boolean) {
    var allocationAppendNanos: Long = 0L
    var bucketOpenSwitchNanos: Long = 0L
    var expiredCloseNanos: Long = 0L
    var cursorTraversalNanos: Long = 0L
    var finalCloseNanos: Long = 0L
    var queryChecksumNanos: Long = 0L
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

  private def foldAggregate(
      checksum: Long,
      bucketIndex: Int,
      count: Int,
      sum: Long
  ): Long =
    (((checksum ^ bucketIndex.toLong) * 1099511628211L) ^
      count.toLong ^
      sum)

  private def foldKeyAggregate(
      checksum: Long,
      bucketIndex: Int,
      key: Int,
      count: Int,
      sum: Long
  ): Long =
    (((checksum ^ bucketIndex.toLong) * 1099511628211L) ^
      key.toLong ^
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

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = CheckedPageTokenCostConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def bucketIndex(startSeconds: Long): Int = {
    val cfg = CheckedPageTokenCostConfig
    (startSeconds / cfg.eventsPerBucket.toLong).toInt
  }

  private def closeCutoff(currentStartSeconds: Long): Long = {
    val cfg = CheckedPageTokenCostConfig
    currentStartSeconds -
      (cfg.windowBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def validateWorkload(workload: String): String =
    workload match {
      case AppendOnly | AppendDrain | AppendAggregate | AppendCountByKey =>
        workload
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-page-token cost workload '$other'"
        )
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap" | "heap-immix" | "heap-same-shape" => "heap-same-shape"
      case "checked-region-stream"                  => "rift-checked-page-token"
      case "checked-region-scoped" =>
        "rift-checked-safezone-page-token"
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-trusted-streaming" | "rift-checked-page-token" |
          CheckedCountByKey =>
        true
      case _ => false
    }

  private def updateAppendChecksum(
      checksum: Long,
      index: Int,
      key: Int,
      value: Int,
      total: Long,
      startSeconds: Long
  ): Long = {
    val cfg = CheckedPageTokenCostConfig
    if (index % cfg.sampleEvery == 0)
      fold(checksum, key, value, total, startSeconds)
    else checksum
  }

  private def runHeap(workload0: String, diag: Diagnostics): Long = {
    val workload = validateWorkload(workload0)
    val cfg = CheckedPageTokenCostConfig
    val aggregateCounts = new Array[Int](cfg.bucketCount)
    val aggregateSums = new Array[Long](cfg.bucketCount)
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L

    def closeBucket(bucket: HeapBucket, finalClose: Boolean): Unit = {
      val start = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendDrain =>
          var record = bucket.head
          while (record != null) {
            checksum = fold(
              checksum,
              record.key,
              record.value,
              record.total,
              bucket.startSeconds
            )
            record = record.next
          }
        case AppendAggregate =>
          val index = bucketIndex(bucket.startSeconds)
          checksum =
            foldAggregate(checksum, index, aggregateCounts(index), aggregateSums(index))
          aggregateCounts(index) = 0
          aggregateSums(index) = 0L
        case AppendCountByKey =>
          val counts = new Array[Int](cfg.keySpace)
          val sums = new Array[Long](cfg.keySpace)
          var record = bucket.head
          while (record != null) {
            counts(record.key) += 1
            sums(record.key) +=
              record.key.toLong + record.value.toLong + record.total
            record = record.next
          }
          val index = bucketIndex(bucket.startSeconds)
          var key = 0
          while (key < cfg.keySpace) {
            val count = counts(key)
            if (count != 0)
              checksum = foldKeyAggregate(checksum, index, key, count, sums(key))
            key += 1
          }
        case AppendOnly =>
          ()
      }
      if (diag.enabled) {
        val elapsed = System.nanoTime() - start
        if (finalClose) diag.finalCloseNanos += elapsed
        else diag.expiredCloseNanos += elapsed
        if (workload == AppendDrain) diag.cursorTraversalNanos += elapsed
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
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
        closeBucket(bucket, finalClose = false)
      }

    def openBucket(startSeconds: Long): HeapBucket = {
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
      val value0 = (mix(seed + 17) & 0xffff) + 1
      val startSeconds = bucketStart(i)
      var bucket = current
      if (bucket == null || bucket.startSeconds != startSeconds) {
        val closeStart = if (diag.enabled) System.nanoTime() else 0L
        closeExpired(closeCutoff(startSeconds))
        if (diag.enabled) diag.expiredCloseNanos += System.nanoTime() - closeStart
        val openStart = if (diag.enabled) System.nanoTime() else 0L
        bucket = openBucket(startSeconds)
        if (diag.enabled)
          diag.bucketOpenSwitchNanos += System.nanoTime() - openStart
      }

      val allocStart = if (diag.enabled) System.nanoTime() else 0L
      val record = new HeapRecord(key, value0, value0.toLong, null)
      record.value += seed & 3
      record.total += record.value.toLong
      if (bucket.head == null) {
        bucket.head = record
        bucket.tail = record
      } else {
        bucket.tail.next = record
        bucket.tail = record
      }
      if (diag.enabled)
        diag.allocationAppendNanos += System.nanoTime() - allocStart

      val queryStart = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendOnly =>
          checksum = updateAppendChecksum(
            checksum,
            i,
            record.key,
            record.value,
            record.total,
            startSeconds
          )
        case AppendAggregate =>
          val index = bucketIndex(startSeconds)
          aggregateCounts(index) += 1
          aggregateSums(index) +=
            record.key.toLong + record.value.toLong + record.total
        case AppendCountByKey =>
          ()
        case AppendDrain =>
          ()
      }
      if (diag.enabled)
        diag.queryChecksumNanos += System.nanoTime() - queryStart
      i += 1
    }

    val finalStart = if (diag.enabled) System.nanoTime() else 0L
    while (first != null) {
      val bucket = first
      first = bucket.next
      closeBucket(bucket, finalClose = true)
    }
    if (diag.enabled)
      diag.finalCloseNanos += System.nanoTime() - finalStart
    last = null
    current = null
    checksumSink = checksum
    checksum
  }

  private def runSafeZone(workload0: String, diag: Diagnostics): Long = {
    val workload = validateWorkload(workload0)
    val cfg = CheckedPageTokenCostConfig
    val aggregateCounts = new Array[Int](cfg.bucketCount)
    val aggregateSums = new Array[Long](cfg.bucketCount)
    var first: SafeZoneBucket = null
    var last: SafeZoneBucket = null
    var current: SafeZoneBucket = null
    var checksum = 0L

    def closeBucket(bucket: SafeZoneBucket, finalClose: Boolean): Unit = {
      val start = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendDrain =>
          var record = bucket.head
          while (record != null) {
            checksum = fold(
              checksum,
              record.key,
              record.value,
              record.total,
              bucket.startSeconds
            )
            record = record.next
          }
        case AppendAggregate =>
          val index = bucketIndex(bucket.startSeconds)
          checksum =
            foldAggregate(checksum, index, aggregateCounts(index), aggregateSums(index))
          aggregateCounts(index) = 0
          aggregateSums(index) = 0L
        case AppendCountByKey =>
          val counts = new Array[Int](cfg.keySpace)
          val sums = new Array[Long](cfg.keySpace)
          var record = bucket.head
          while (record != null) {
            counts(record.key) += 1
            sums(record.key) +=
              record.key.toLong + record.value.toLong + record.total
            record = record.next
          }
          val index = bucketIndex(bucket.startSeconds)
          var key = 0
          while (key < cfg.keySpace) {
            val count = counts(key)
            if (count != 0)
              checksum = foldKeyAggregate(checksum, index, key, count, sums(key))
            key += 1
          }
        case AppendOnly =>
          ()
      }
      if (diag.enabled) {
        val elapsed = System.nanoTime() - start
        if (finalClose) diag.finalCloseNanos += elapsed
        else diag.expiredCloseNanos += elapsed
        if (workload == AppendDrain) diag.cursorTraversalNanos += elapsed
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      SafeZone.close(bucket.zone)
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
        closeBucket(bucket, finalClose = false)
      }

    try {
      var i = 0
      while (i < cfg.events) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value0 = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        var bucket = current
        if (bucket == null || bucket.startSeconds != startSeconds) {
          val closeStart = if (diag.enabled) System.nanoTime() else 0L
          closeExpired(closeCutoff(startSeconds))
          if (diag.enabled)
            diag.expiredCloseNanos += System.nanoTime() - closeStart
          val openStart = if (diag.enabled) System.nanoTime() else 0L
          bucket = new SafeZoneBucket(SafeZone.open(), startSeconds, null)
          if (first == null) {
            first = bucket
            last = bucket
          } else {
            last.next = bucket
            last = bucket
          }
          current = bucket
          if (diag.enabled)
            diag.bucketOpenSwitchNanos += System.nanoTime() - openStart
        }

        val allocStart = if (diag.enabled) System.nanoTime() else 0L
        val record =
          SafeZoneAllocator.allocate(
            bucket.zone,
            new SafeZoneRecord(key, value0, value0.toLong, null)
          )
        record.value += seed & 3
        record.total += record.value.toLong
        if (bucket.head == null) {
          bucket.head = record
          bucket.tail = record
        } else {
          bucket.tail.next = record
          bucket.tail = record
        }
        if (diag.enabled)
          diag.allocationAppendNanos += System.nanoTime() - allocStart

        val queryStart = if (diag.enabled) System.nanoTime() else 0L
        workload match {
          case AppendOnly =>
            checksum = updateAppendChecksum(
              checksum,
              i,
              record.key,
              record.value,
              record.total,
              startSeconds
            )
          case AppendAggregate =>
            val index = bucketIndex(startSeconds)
            aggregateCounts(index) += 1
            aggregateSums(index) +=
              record.key.toLong + record.value.toLong + record.total
          case AppendCountByKey =>
            ()
          case AppendDrain =>
            ()
        }
        if (diag.enabled)
          diag.queryChecksumNanos += System.nanoTime() - queryStart
        i += 1
      }

      val finalStart = if (diag.enabled) System.nanoTime() else 0L
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket, finalClose = true)
      }
      if (diag.enabled)
        diag.finalCloseNanos += System.nanoTime() - finalStart
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket, finalClose = true)
      }
      last = null
      current = null
    }
    checksumSink = checksum
    checksum
  }

  private def runTrustedStreaming(workload0: String, diag: Diagnostics): Long = {
    val workload = validateWorkload(workload0)
    val cfg = CheckedPageTokenCostConfig
    val aggregateCounts = new Array[Int](cfg.bucketCount)
    val aggregateSums = new Array[Long](cfg.bucketCount)
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L

    def closeBucket(bucket: TrustedBucket, finalClose: Boolean): Unit = {
      val start = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendDrain =>
          var record = bucket.head
          while (record != null) {
            checksum = fold(
              checksum,
              record.key,
              record.value,
              record.total,
              bucket.startSeconds
            )
            record = record.next
          }
        case AppendAggregate =>
          val index = bucketIndex(bucket.startSeconds)
          checksum =
            foldAggregate(checksum, index, aggregateCounts(index), aggregateSums(index))
          aggregateCounts(index) = 0
          aggregateSums(index) = 0L
        case AppendCountByKey =>
          val counts = new Array[Int](cfg.keySpace)
          val sums = new Array[Long](cfg.keySpace)
          var record = bucket.head
          while (record != null) {
            counts(record.key) += 1
            sums(record.key) +=
              record.key.toLong + record.value.toLong + record.total
            record = record.next
          }
          val index = bucketIndex(bucket.startSeconds)
          var key = 0
          while (key < cfg.keySpace) {
            val count = counts(key)
            if (count != 0)
              checksum = foldKeyAggregate(checksum, index, key, count, sums(key))
            key += 1
          }
        case AppendOnly =>
          ()
      }
      if (diag.enabled) {
        val elapsed = System.nanoTime() - start
        if (finalClose) diag.finalCloseNanos += elapsed
        else diag.expiredCloseNanos += elapsed
        if (workload == AppendDrain) diag.cursorTraversalNanos += elapsed
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
        closeBucket(bucket, finalClose = false)
      }

    try {
      var i = 0
      while (i < cfg.events) {
        val seed = mix(i * 1103515245 + 12345)
        val key = seed % cfg.keySpace
        val value0 = (mix(seed + 17) & 0xffff) + 1
        val startSeconds = bucketStart(i)
        var bucket = current
        if (bucket == null || bucket.startSeconds != startSeconds) {
          val closeStart = if (diag.enabled) System.nanoTime() else 0L
          closeExpired(closeCutoff(startSeconds))
          if (diag.enabled)
            diag.expiredCloseNanos += System.nanoTime() - closeStart
          val openStart = if (diag.enabled) System.nanoTime() else 0L
          bucket =
            new TrustedBucket(RiftRegion.open(RiftRegion.Streaming), startSeconds, null)
          if (first == null) {
            first = bucket
            last = bucket
          } else {
            last.next = bucket
            last = bucket
          }
          current = bucket
          if (diag.enabled)
            diag.bucketOpenSwitchNanos += System.nanoTime() - openStart
        }

        val allocStart = if (diag.enabled) System.nanoTime() else 0L
        val record =
          bucket.region.alloc(new TrustedRecord(key, value0, value0.toLong, null))
        record.value += seed & 3
        record.total += record.value.toLong
        if (bucket.head == null) {
          bucket.head = record
          bucket.tail = record
        } else {
          bucket.tail.next = record
          bucket.tail = record
        }
        if (diag.enabled)
          diag.allocationAppendNanos += System.nanoTime() - allocStart

        val queryStart = if (diag.enabled) System.nanoTime() else 0L
        workload match {
          case AppendOnly =>
            checksum = updateAppendChecksum(
              checksum,
              i,
              record.key,
              record.value,
              record.total,
              startSeconds
            )
          case AppendAggregate =>
            val index = bucketIndex(startSeconds)
            aggregateCounts(index) += 1
            aggregateSums(index) +=
              record.key.toLong + record.value.toLong + record.total
          case AppendCountByKey =>
            ()
          case AppendDrain =>
            ()
        }
        if (diag.enabled)
          diag.queryChecksumNanos += System.nanoTime() - queryStart
        i += 1
      }

      val finalStart = if (diag.enabled) System.nanoTime() else 0L
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket, finalClose = true)
      }
      if (diag.enabled)
        diag.finalCloseNanos += System.nanoTime() - finalStart
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket, finalClose = true)
      }
      last = null
      current = null
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedPageTokenBody(workload0: String, diag: Diagnostics)(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val workload = validateWorkload(workload0)
    val cfg = CheckedPageTokenCostConfig
    val aggregateCounts = new Array[Int](cfg.bucketCount)
    val aggregateSums = new Array[Long](cfg.bucketCount)
    final class Record(
        val key: Int,
        var value: Int,
        var total: Long
    ) extends RiftRegion.StreamAppendNode
    val window =
      RiftRegion.streamPageTokenAppendWindow[Record](
        cfg.eventsPerBucket.toLong
      )
    var checksum = 0L

    def consume(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[Record]^{stream}
    ): Unit = {
      val start = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendDrain =>
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val record = current.asInstanceOf[Record^{stream}]
            checksum = fold(
              checksum,
              record.key,
              record.value,
              record.total,
              bucket.startSeconds
            )
            current = cursor.nextOwnedOrNull()
          }
        case AppendAggregate =>
          val index = bucketIndex(bucket.startSeconds)
          checksum =
            foldAggregate(checksum, index, aggregateCounts(index), aggregateSums(index))
          aggregateCounts(index) = 0
          aggregateSums(index) = 0L
        case AppendCountByKey =>
          val counts = new Array[Int](cfg.keySpace)
          val sums = new Array[Long](cfg.keySpace)
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val record = current.asInstanceOf[Record^{stream}]
            counts(record.key) += 1
            sums(record.key) +=
              record.key.toLong + record.value.toLong + record.total
            current = cursor.nextOwnedOrNull()
          }
          val index = bucketIndex(bucket.startSeconds)
          var key = 0
          while (key < cfg.keySpace) {
            val count = counts(key)
            if (count != 0)
              checksum = foldKeyAggregate(checksum, index, key, count, sums(key))
            key += 1
          }
        case AppendOnly =>
          ()
      }
      if (diag.enabled) {
        val elapsed = System.nanoTime() - start
        if (workload == AppendDrain) diag.cursorTraversalNanos += elapsed
      }
    }

    def consumeNoDrain(bucket: RiftRegion.StreamBucket^{stream}): Unit = {
      val start = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendAggregate =>
          val index = bucketIndex(bucket.startSeconds)
          checksum =
            foldAggregate(checksum, index, aggregateCounts(index), aggregateSums(index))
          aggregateCounts(index) = 0
          aggregateSums(index) = 0L
        case AppendCountByKey =>
          throw new IllegalStateException(
            "append-count-by-key requires PageTokenCountByKey mode"
          )
        case AppendOnly =>
          ()
        case AppendDrain =>
          throw new IllegalStateException(
            "append-drain requires cursor close"
          )
      }
      if (diag.enabled) {
        val elapsed = System.nanoTime() - start
        diag.expiredCloseNanos += elapsed
      }
    }

    var currentStartSeconds = Long.MinValue
    var currentRegion: RiftRegion.StreamingRegion^{stream} = null
    var i = 0
    while (i < cfg.events) {
      val seed = mix(i * 1103515245 + 12345)
      val key = seed % cfg.keySpace
      val value0 = (mix(seed + 17) & 0xffff) + 1
      val startSeconds = bucketStart(i)
      if (startSeconds != currentStartSeconds) {
        val switchStart = if (diag.enabled) System.nanoTime() else 0L
        currentStartSeconds = startSeconds
        if (workload == AppendDrain || workload == AppendCountByKey) {
          currentRegion =
            RiftRegion.pageTokenAppendRegionFor(
              stream,
              window,
              startSeconds,
              closeCutoff(startSeconds)
            )(consume)
        } else {
          RiftRegion.closePageTokenAppendBucketsBeforeNoDrain(
            stream,
            window,
            closeCutoff(startSeconds)
          )(consumeNoDrain)
          currentRegion =
            RiftRegion.pageTokenAppendRegionFor(
              stream,
              window,
              startSeconds,
              Long.MinValue
            ) { (_, _) => () }
        }
        if (diag.enabled)
          diag.bucketOpenSwitchNanos += System.nanoTime() - switchStart
      }

      val allocStart = if (diag.enabled) System.nanoTime() else 0L
      val record: Record^{stream} =
        RiftRegion.alloc(new Record(key, value0, value0.toLong))(
          using currentRegion
        )
      record.value += seed & 3
      record.total += record.value.toLong
      RiftRegion.appendPageToken(stream, window, record)
      if (diag.enabled)
        diag.allocationAppendNanos += System.nanoTime() - allocStart

      val queryStart = if (diag.enabled) System.nanoTime() else 0L
      workload match {
        case AppendOnly =>
          checksum = updateAppendChecksum(
            checksum,
            i,
            record.key,
            record.value,
            record.total,
            startSeconds
          )
        case AppendAggregate =>
          val index = bucketIndex(startSeconds)
          aggregateCounts(index) += 1
          aggregateSums(index) +=
            record.key.toLong + record.value.toLong + record.total
        case AppendCountByKey =>
          ()
        case AppendDrain =>
          ()
      }
      if (diag.enabled)
        diag.queryChecksumNanos += System.nanoTime() - queryStart
      i += 1
    }

    val finalStart = if (diag.enabled) System.nanoTime() else 0L
    if (workload == AppendDrain || workload == AppendCountByKey)
      RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
        consume
      )
    else
      RiftRegion.closeAllPageTokenAppendBucketsNoDrain(stream, window)(
        consumeNoDrain
      )
    if (diag.enabled)
      diag.finalCloseNanos += System.nanoTime() - finalStart
    checksum
  }

  private def runCheckedPageToken(workload: String, diag: Diagnostics): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      runCheckedPageTokenBody(workload, diag)
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedSafeZonePageToken(
      workload: String,
      diag: Diagnostics
  ): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedPageTokenBody(workload, diag)
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedCountByKeyBody(workload0: String, diag: Diagnostics)(
      using stream: RiftRegion.StreamingRegion^
  ): Long = {
    val workload = validateWorkload(workload0)
    if (workload != AppendCountByKey)
      throw new IllegalArgumentException(
        s"$workload requires append-count-by-key for PageTokenCountByKey"
      )
    val cfg = CheckedPageTokenCostConfig
    final class Record(
        val key: Int,
        var value: Int,
        var total: Long
    ) extends RiftRegion.StreamAppendNode
    val operator =
      RiftRegion.pageTokenCountByKey[Record](
        cfg.eventsPerBucket.toLong,
        cfg.keySpace,
        cfg.windowBuckets
      )
    var checksum = 0L

    def consume(
        bucket: RiftRegion.StreamBucket^{stream},
        key: Int,
        count: Int,
        sum: Long
    ): Unit =
      checksum =
        foldKeyAggregate(checksum, bucketIndex(bucket.startSeconds), key, count, sum)

    var currentStartSeconds = Long.MinValue
    var currentRegion: RiftRegion.StreamingRegion^{stream} = null
    var i = 0
    while (i < cfg.events) {
      val seed = mix(i * 1103515245 + 12345)
      val key = seed % cfg.keySpace
      val value0 = (mix(seed + 17) & 0xffff) + 1
      val startSeconds = bucketStart(i)
      if (startSeconds != currentStartSeconds) {
        val switchStart = if (diag.enabled) System.nanoTime() else 0L
        currentStartSeconds = startSeconds
        currentRegion =
          RiftRegion.pageTokenCountByKeyRegionFor(
            stream,
            operator,
            startSeconds,
            closeCutoff(startSeconds)
          )(consume)
        if (diag.enabled)
          diag.bucketOpenSwitchNanos += System.nanoTime() - switchStart
      }

      val allocStart = if (diag.enabled) System.nanoTime() else 0L
      val record: Record^{stream} =
        RiftRegion.alloc(new Record(key, value0, value0.toLong))(
          using currentRegion
        )
      record.value += seed & 3
      record.total += record.value.toLong
      RiftRegion.appendPageTokenCountByKey(
        stream,
        operator,
        record,
        record.key,
        record.key.toLong + record.value.toLong + record.total
      )
      if (diag.enabled)
        diag.allocationAppendNanos += System.nanoTime() - allocStart
      i += 1
    }

    val finalStart = if (diag.enabled) System.nanoTime() else 0L
    RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(consume)
    if (diag.enabled)
      diag.finalCloseNanos += System.nanoTime() - finalStart
    checksum
  }

  private def runCheckedCountByKey(workload: String, diag: Diagnostics): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      runCheckedCountByKeyBody(workload, diag)
    }
    checksumSink = checksum
    checksum
  }

  private def runCheckedSafeZoneCountByKey(
      workload: String,
      diag: Diagnostics
  ): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedCountByKeyBody(workload, diag)
    }
    checksumSink = checksum
    checksum
  }

  private def runMode(mode: String, workload: String, diag: Diagnostics): Long =
    canonicalMode(mode) match {
      case "heap-same-shape"              => runHeap(workload, diag)
      case "safezone-improved-32k"        => runSafeZone(workload, diag)
      case "rift-trusted-streaming"       => runTrustedStreaming(workload, diag)
      case "rift-checked-page-token"      => runCheckedPageToken(workload, diag)
      case "rift-checked-safezone-page-token" =>
        runCheckedSafeZonePageToken(workload, diag)
      case CheckedCountByKey => runCheckedCountByKey(workload, diag)
      case CheckedSafeZoneCountByKey =>
        runCheckedSafeZoneCountByKey(workload, diag)
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-page-token cost mode '$other'"
        )
    }

  def validate(mode: String, workload: String): Unit = {
    validateWorkload(workload)
    runModeName(canonicalMode(mode))
  }

  private def runModeName(mode: String): String =
    mode match {
      case "heap-same-shape" | "safezone-improved-32k" |
          "rift-trusted-streaming" | "rift-checked-page-token" |
          "rift-checked-safezone-page-token" | CheckedCountByKey |
          CheckedSafeZoneCountByKey =>
        mode
      case other =>
        throw new IllegalArgumentException(
          s"unknown checked-page-token cost mode '$other'"
        )
    }

  def runBenchmark(mode0: String, workload0: String): Unit = {
    val cfg = CheckedPageTokenCostConfig
    val mode = canonicalMode(mode0)
    val workload = validateWorkload(workload0)
    val usesRift = usesRiftRuntime(mode)
    val expectedChecksum = runHeap(workload, new Diagnostics(false))

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runMode(mode, workload, new Diagnostics(false))
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"warmup checksum mismatch mode=$mode workload=$workload expected=$expectedChecksum actual=$checksum"
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
    var lastDiag: Diagnostics = null

    println(
      s"Running checked-page-token-cost-$workload-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val diag = new Diagnostics(cfg.diagnostics)
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runMode(mode, workload, diag)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"checksum mismatch mode=$mode workload=$workload expected=$expectedChecksum actual=$checksum"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftRegionOpenTotal
      riftCloses(run) = runtime.riftRegionCloseTotal
      riftResets(run) = runtime.riftRegionResetTotal
      lastDiag = diag

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
      f"RESULT name=checked-page-token-cost-$workload-$mode " +
        f"workload=$workload mode=$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )

    if (cfg.diagnostics && lastDiag != null) {
      println(
        f"PAGE_TOKEN_COST_DIAG workload=$workload mode=$mode " +
          f"allocation_append_ms=${lastDiag.allocationAppendNanos / 1000000.0}%.3f " +
          f"bucket_open_switch_ms=${lastDiag.bucketOpenSwitchNanos / 1000000.0}%.3f " +
          f"expired_close_ms=${lastDiag.expiredCloseNanos / 1000000.0}%.3f " +
          f"cursor_traversal_ms=${lastDiag.cursorTraversalNanos / 1000000.0}%.3f " +
          f"final_close_ms=${lastDiag.finalCloseNanos / 1000000.0}%.3f " +
          f"query_checksum_ms=${lastDiag.queryChecksumNanos / 1000000.0}%.3f"
      )
    }
  }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = CheckedPageTokenCostConfig
    println(
      s"CONFIG mode=$mode backend_mode=${canonicalMode(mode)} workload=$workload runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} window_buckets=${cfg.windowBuckets} key_space=${cfg.keySpace} sample_every=${cfg.sampleEvery} diagnostics=${cfg.diagnostics}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    usesRiftRuntime(mode)
}

@main def CheckedPageTokenCostMatrix(
    mode: String = "heap-same-shape",
    workload: String = "append-drain"
): Unit = {
  CheckedPageTokenCostMatrixHelpers.validate(mode, workload)
  CheckedPageTokenCostMatrixHelpers.printConfig(mode, workload)

  val usesRift = CheckedPageTokenCostMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    CheckedPageTokenCostMatrixHelpers.runBenchmark(mode, workload)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
