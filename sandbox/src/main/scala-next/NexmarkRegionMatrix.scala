import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object NexmarkRegionConfig {
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

  val events: Int = envInt("NEXMARK_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("NEXMARK_EVENTS_PER_BUCKET", 25000)
  val windowBuckets: Int = envInt("NEXMARK_WINDOW_BUCKETS", 8)
  val auctionSpace: Int = envInt("NEXMARK_AUCTION_SPACE", 65536)
  val personSpace: Int = envInt("NEXMARK_PERSON_SPACE", 65536)
  val categorySpace: Int = envInt("NEXMARK_CATEGORY_SPACE", 64)
  val q2SelectModulo: Int = envInt("NEXMARK_Q2_SELECT_MODULO", 128)
  val sampleEvery: Int = envInt("NEXMARK_SAMPLE_EVERY", 8192)
  val warmupRuns: Int = envNonNegativeInt("NEXMARK_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("NEXMARK_BENCHMARK_RUNS", 3)
}

object NexmarkRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapRecord(
      val kind: Int,
      val id: Int,
      val key: Int,
      var value: Int,
      var price: Long,
      val timestamp: Long,
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
      val kind: Int,
      val id: Int,
      val key: Int,
      var value: Int,
      var price: Long,
      val timestamp: Long,
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

  final case class RunOutcome(checksum: Long, outputCount: Long)

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

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = NexmarkRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartSeconds: Long): Long = {
    val cfg = NexmarkRegionConfig
    currentStartSeconds -
      (cfg.windowBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def eventKind(index: Int): Int = {
    val mod = index % 10
    if (mod == 0) 0
    else if (mod == 1) 1
    else 2
  }

  private def auctionId(index: Int): Int =
    mix(index * 1103515245 + 12345) % NexmarkRegionConfig.auctionSpace

  private def bidderId(index: Int): Int =
    mix(index * 1664525 + 1013904223) % NexmarkRegionConfig.personSpace

  private def price(index: Int): Long =
    ((mix(index * 8191 + 17) & 0xffff) + 100).toLong

  private def category(index: Int): Int =
    mix(index * 131 + 53) % NexmarkRegionConfig.categorySpace

  private def fold(
      checksum: Long,
      kind: Int,
      id: Int,
      key: Int,
      value: Int,
      price: Long,
      timestamp: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ id.toLong
    h = (h * 1099511628211L) ^ key.toLong
    h = (h * 1099511628211L) ^ (value.toLong << 13)
    h ^ price ^ timestamp
  }

  private def topAuction(counts: Array[Int], sums: Array[Long]): Int = {
    var best = 0
    var bestCount = counts(0)
    var bestSum = sums(0)
    var i = 1
    while (i < counts.length) {
      val count = counts(i)
      val sum = sums(i)
      if (
        count > bestCount ||
        (count == bestCount && sum > bestSum) ||
        (count == bestCount && sum == bestSum && i < best)
      ) {
        best = i
        bestCount = count
        bestSum = sum
      }
      i += 1
    }
    best
  }

  private def appendRecord(bucket: HeapBucket, record: HeapRecord): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  private def appendRecord(bucket: TrustedBucket, record: TrustedRecord): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  def runHeap(query: String): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(bucket: HeapBucket, record: HeapRecord): Unit =
      if (query == "q5") {
        counts(record.key) -= 1
        sums(record.key) -= record.price
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          counts(record.key),
          sums(record.key),
          bucket.startSeconds
        )
      } else if (query == "q0" || record.kind != 2) {
        checksum = fold(
          checksum,
          record.kind,
          record.id,
          record.key,
          record.value,
          record.price,
          record.timestamp
        )
        outputCount += 1L
      } else {
        checksum = fold(
          checksum,
          record.kind + 60,
          record.id,
          record.key,
          record.value,
          record.price,
          record.timestamp
        )
      }

    def closeExpired(cutoffSeconds: Long): Unit =
      while (
        first != null &&
        first.startSeconds + cfg.eventsPerBucket.toLong <= cutoffSeconds
      ) {
        val bucket = first
        var record = bucket.head
        while (record != null) {
          consumeRecord(bucket, record)
          record = record.next
        }
        first = bucket.next
        if (first == null) last = null
        if (current.eq(bucket)) current = null
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
      val startSeconds = bucketStart(i)
      val bucket = bucketFor(startSeconds)
      query match {
        case "q0" =>
          val kind = eventKind(i)
          val key = if (kind == 2) auctionId(i) else bidderId(i)
          val record =
            new HeapRecord(kind, i, key, category(i), price(i), i.toLong, null)
          appendRecord(bucket, record)

        case "q1" =>
          val bid =
            new HeapRecord(2, i, auctionId(i), bidderId(i), price(i), i.toLong, null)
          appendRecord(bucket, bid)
          val convertedPrice = (bid.price * 89L) / 100L
          val out =
            new HeapRecord(11, bid.id, bid.key, bid.value, convertedPrice, bid.timestamp, null)
          out.value += (convertedPrice & 7L).toInt
          appendRecord(bucket, out)

        case "q2" =>
          val bid =
            new HeapRecord(2, i, auctionId(i), bidderId(i), price(i), i.toLong, null)
          appendRecord(bucket, bid)
          if ((bid.key % cfg.q2SelectModulo) == 0) {
            val out =
              new HeapRecord(12, bid.id, bid.key, bid.value, bid.price, bid.timestamp, null)
            appendRecord(bucket, out)
          }

        case "q5" =>
          val auction = auctionId(i)
          val bidPrice = price(i)
          val bid =
            new HeapRecord(15, i, auction, bidderId(i), bidPrice, i.toLong, null)
          counts(auction) += 1
          sums(auction) += bidPrice
          appendRecord(bucket, bid)
          if (i % cfg.sampleEvery == 0) {
            val hot = topAuction(counts, sums)
            checksum = fold(
              checksum,
              55,
              i,
              hot,
              counts(hot),
              sums(hot),
              startSeconds
            )
            outputCount += 1L
          }
      }
      i += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftTrusted(query: String, kind: Int): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(bucket: TrustedBucket, record: TrustedRecord): Unit =
      if (query == "q5") {
        counts(record.key) -= 1
        sums(record.key) -= record.price
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          counts(record.key),
          sums(record.key),
          bucket.startSeconds
        )
      } else if (query == "q0" || record.kind != 2) {
        checksum = fold(
          checksum,
          record.kind,
          record.id,
          record.key,
          record.value,
          record.price,
          record.timestamp
        )
        outputCount += 1L
      } else {
        checksum = fold(
          checksum,
          record.kind + 60,
          record.id,
          record.key,
          record.value,
          record.price,
          record.timestamp
        )
      }

    def closeBucket(bucket: TrustedBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        consumeRecord(bucket, record)
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
        if (current.eq(bucket)) current = null
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
        val startSeconds = bucketStart(i)
        val bucket = bucketFor(startSeconds)
        val region = bucket.region
        query match {
          case "q0" =>
            val kind = eventKind(i)
            val key = if (kind == 2) auctionId(i) else bidderId(i)
            val record =
              region.alloc(
                new TrustedRecord(kind, i, key, category(i), price(i), i.toLong, null)
              )
            appendRecord(bucket, record)

          case "q1" =>
            val bid =
              region.alloc(
                new TrustedRecord(2, i, auctionId(i), bidderId(i), price(i), i.toLong, null)
              )
            appendRecord(bucket, bid)
            val convertedPrice = (bid.price * 89L) / 100L
            val out =
              region.alloc(
                new TrustedRecord(
                  11,
                  bid.id,
                  bid.key,
                  bid.value,
                  convertedPrice,
                  bid.timestamp,
                  null
                )
              )
            out.value += (convertedPrice & 7L).toInt
            appendRecord(bucket, out)

          case "q2" =>
            val bid =
              region.alloc(
                new TrustedRecord(2, i, auctionId(i), bidderId(i), price(i), i.toLong, null)
              )
            appendRecord(bucket, bid)
            if ((bid.key % cfg.q2SelectModulo) == 0) {
              val out =
                region.alloc(
                  new TrustedRecord(12, bid.id, bid.key, bid.value, bid.price, bid.timestamp, null)
                )
              appendRecord(bucket, out)
            }

          case "q5" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid =
              region.alloc(
                new TrustedRecord(15, i, auction, bidderId(i), bidPrice, i.toLong, null)
              )
            counts(auction) += 1
            sums(auction) += bidPrice
            appendRecord(bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              val hot = topAuction(counts, sums)
              checksum = fold(
                checksum,
                55,
                i,
                hot,
                counts(hot),
                sums(hot),
                startSeconds
              )
              outputCount += 1L
            }
        }
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
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftChecked(query: String): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    var outputCount = 0L
    val checksum = RiftRegion.streaming { stream ?=>
      final class Record(
          val kind: Int,
          val id: Int,
          val key: Int,
          var value: Int,
          var price: Long,
          val timestamp: Long
      ) extends RiftRegion.StreamAppendNode

      val window =
        RiftRegion.streamAppendWindow[Record](cfg.eventsPerBucket.toLong)
      var running = 0L
      var outputs = 0L

      def consume(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[Record]^{stream}
      ): Unit =
        while (cursor.hasNext) {
          val record: Record^{stream} = cursor.next()
          if (query == "q5") {
            counts(record.key) -= 1
            sums(record.key) -= record.price
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              counts(record.key),
              sums(record.key),
              bucket.startSeconds
            )
          } else if (query == "q0" || record.kind != 2) {
            running = fold(
              running,
              record.kind,
              record.id,
              record.key,
              record.value,
              record.price,
              record.timestamp
            )
            outputs += 1L
          } else {
            running = fold(
              running,
              record.kind + 60,
              record.id,
              record.key,
              record.value,
              record.price,
              record.timestamp
            )
          }
        }

      def closeExpired(cutoffSeconds: Long): Unit =
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(
          stream,
          window,
          cutoffSeconds
        ) { (bucket, cursor) =>
          consume(bucket, cursor)
        }

      var currentStartSeconds = Long.MinValue
      var currentBucket: RiftRegion.StreamBucket^{stream} = null
      var currentBucketRegion: RiftRegion.StreamingRegion^{stream} = null
      var i = 0
      while (i < cfg.events) {
        val startSeconds = bucketStart(i)
        if (startSeconds != currentStartSeconds) {
          closeExpired(closeCutoff(startSeconds))
          currentStartSeconds = startSeconds
          currentBucket =
            RiftRegion.streamAppendWindowBucketFor(stream, window, startSeconds)
          currentBucketRegion =
            RiftRegion.streamBucketRegion(stream, currentBucket)
        }
        val bucket = currentBucket
        val bucketRegion = currentBucketRegion

        query match {
          case "q0" =>
            val kind = eventKind(i)
            val key = if (kind == 2) auctionId(i) else bidderId(i)
            val record: Record^{stream} =
              RiftRegion.alloc(
                new Record(kind, i, key, category(i), price(i), i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, record)

          case "q1" =>
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(2, i, auctionId(i), bidderId(i), price(i), i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, bid)
            val convertedPrice = (bid.price * 89L) / 100L
            val out: Record^{stream} =
              RiftRegion.alloc(
                new Record(
                  11,
                  bid.id,
                  bid.key,
                  bid.value,
                  convertedPrice,
                  bid.timestamp
                )
              )(using bucketRegion)
            out.value += (convertedPrice & 7L).toInt
            RiftRegion.appendWindow(stream, window, bucket, out)

          case "q2" =>
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(2, i, auctionId(i), bidderId(i), price(i), i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if ((bid.key % cfg.q2SelectModulo) == 0) {
              val out: Record^{stream} =
                RiftRegion.alloc(
                  new Record(12, bid.id, bid.key, bid.value, bid.price, bid.timestamp)
                )(using bucketRegion)
              RiftRegion.appendWindow(stream, window, bucket, out)
            }

          case "q5" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(15, i, auction, bidderId(i), bidPrice, i.toLong)
              )(using bucketRegion)
            counts(auction) += 1
            sums(auction) += bidPrice
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              val hot = topAuction(counts, sums)
              running = fold(
                running,
                55,
                i,
                hot,
                counts(hot),
                sums(hot),
                startSeconds
              )
              outputs += 1L
            }
        }
        i += 1
      }

      RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
        (bucket, cursor) =>
          consume(bucket, cursor)
      }
      outputCount = outputs
      running
    }
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runMode(mode: String, query: String): RunOutcome =
    mode match {
      case "heap"           => runHeap(query)
      case "rift-checked"   => runRiftChecked(query)
      case "rift-hp"        => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark mode '$other'")
    }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "rift-checked" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark mode '$other'")
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0" | "q1" | "q2" | "q5" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark query '$other'")
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = NexmarkRegionConfig
    val usesRift = mode != "heap"
    val expected = runHeap(query)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode, query)
      if (outcome != expected)
        throw new IllegalStateException(
          s"warmup mismatch query=$query mode=$mode expected=$expected actual=$outcome"
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
      s"Running nexmark-$query-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val outcome = runMode(mode, query)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (outcome != expected)
        throw new IllegalStateException(
          s"checksum mismatch query=$query mode=$mode expected=$expected actual=$outcome"
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
      f"RESULT name=nexmark-$query-$mode " +
        f"query=$query mode=$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=${expected.checksum}%d " +
        f"output_count=${expected.outputCount}%d"
    )
  }

  def printConfig(mode: String, query: String): Unit = {
    val cfg = NexmarkRegionConfig
    println(
      s"CONFIG mode=$mode query=$query runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} window_buckets=${cfg.windowBuckets} auction_space=${cfg.auctionSpace} person_space=${cfg.personSpace} category_space=${cfg.categorySpace} q2_select_modulo=${cfg.q2SelectModulo} sample_every=${cfg.sampleEvery}"
    )
  }
}

@main def NexmarkRegionMatrix(
    mode: String = "heap",
    query: String = "q1"
): Unit = {
  NexmarkRegionMatrixHelpers.validateMode(mode)
  NexmarkRegionMatrixHelpers.validateQuery(query)
  NexmarkRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = mode != "heap"
  if (usesRift) RiftRegion.init(0)
  try {
    NexmarkRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
