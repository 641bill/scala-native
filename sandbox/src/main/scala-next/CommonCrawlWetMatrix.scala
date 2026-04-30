import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object CommonCrawlWetConfig {
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

  val pages: Int = envInt("COMMON_CRAWL_WET_PAGES", 100000)
  val pagesPerBucket: Int = envInt("COMMON_CRAWL_WET_PAGES_PER_BUCKET", 2500)
  val liveBuckets: Int = envInt("COMMON_CRAWL_WET_LIVE_BUCKETS", 4)
  val domainSpace: Int = envInt("COMMON_CRAWL_WET_DOMAIN_SPACE", 16384)
  val linesPerPage: Int = envInt("COMMON_CRAWL_WET_LINES_PER_PAGE", 8)
  val tokensPerLine: Int = envInt("COMMON_CRAWL_WET_TOKENS_PER_LINE", 16)
  val sampleEvery: Int = envInt("COMMON_CRAWL_WET_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("COMMON_CRAWL_WET_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("COMMON_CRAWL_WET_BENCHMARK_RUNS", 3)
}

object CommonCrawlWetMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapRecord(
      val kind: Int,
      val pageId: Int,
      val domain: Int,
      val value: Int,
      val hash: Long,
      var next: HeapRecord
  )

  private final class HeapBucket(
      val startPage: Long,
      var next: HeapBucket
  ) {
    var head: HeapRecord = null
    var tail: HeapRecord = null
  }

  private final class TrustedRecord(
      val kind: Int,
      val pageId: Int,
      val domain: Int,
      val value: Int,
      val hash: Long,
      var next: TrustedRecord
  )

  private final class TrustedBucket(
      val region: RiftRegion,
      val startPage: Long,
      var next: TrustedBucket
  ) {
    var head: TrustedRecord = null
    var tail: TrustedRecord = null
  }

  private final class SafeRecord(
      val kind: Int,
      val pageId: Int,
      val domain: Int,
      val value: Int,
      val hash: Long,
      var next: SafeRecord
  )

  private final class SafeBucket(
      val zone: SafeZone,
      val startPage: Long,
      var next: SafeBucket
  ) {
    var head: SafeRecord = null
    var tail: SafeRecord = null
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

  private def bucketStart(page: Int): Long = {
    val cfg = CommonCrawlWetConfig
    (page / cfg.pagesPerBucket).toLong * cfg.pagesPerBucket.toLong
  }

  private def closeCutoff(currentStartPage: Long): Long = {
    val cfg = CommonCrawlWetConfig
    currentStartPage -
      (cfg.liveBuckets.toLong - 1L) * cfg.pagesPerBucket.toLong
  }

  private def domainFor(page: Int): Int =
    mix(page * 1103515245 + 12345) % CommonCrawlWetConfig.domainSpace

  private def lineHash(page: Int, line: Int): Long =
    mix(page * 1000003 + line * 8191 + 17).toLong

  private def tokenHash(page: Int, line: Int, token: Int): Long =
    mix(page * 1000003 + line * 8191 + token * 131 + 53).toLong

  private def fold(
      checksum: Long,
      kind: Int,
      pageId: Int,
      domain: Int,
      value: Int,
      hash: Long,
      bucketStartPage: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ pageId.toLong
    h = (h * 1099511628211L) ^ domain.toLong
    h = (h * 1099511628211L) ^ value.toLong
    h ^ hash ^ bucketStartPage
  }

  private def appendRecord(bucket: HeapBucket, record: HeapRecord): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  private def appendRecord(
      bucket: TrustedBucket,
      record: TrustedRecord
  ): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  private def appendRecord(bucket: SafeBucket, record: SafeRecord): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-tokenize" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET query '$other'"
        )
    }

  def runHeap(query: String): RunOutcome = {
    val cfg = CommonCrawlWetConfig
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: HeapBucket, record: HeapRecord): Unit = {
      checksum = fold(
        checksum,
        record.kind,
        record.pageId,
        record.domain,
        record.value,
        record.hash,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeExpired(cutoffPage: Long): Unit =
      while (
        first != null &&
        first.startPage + cfg.pagesPerBucket.toLong <= cutoffPage
      ) {
        val bucket = first
        var record = bucket.head
        while (record != null) {
          consume(bucket, record)
          record = record.next
        }
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        bucket.head = null
        bucket.tail = null
        bucket.next = null
      }

    def bucketFor(startPage: Long): HeapBucket =
      if (current != null && current.startPage == startPage) current
      else {
        closeExpired(closeCutoff(startPage))
        val bucket = new HeapBucket(startPage, null)
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

    var page = 0
    while (page < cfg.pages) {
      val domain = domainFor(page)
      val bucket = bucketFor(bucketStart(page))
      appendRecord(bucket, new HeapRecord(1, page, domain, 0, lineHash(page, 0), null))
      var line = 0
      while (line < cfg.linesPerPage) {
        val lh = lineHash(page, line)
        appendRecord(bucket, new HeapRecord(2, page, domain, line, lh, null))
        if (query == "q1-tokenize") {
          var token = 0
          while (token < cfg.tokensPerLine) {
            appendRecord(
              bucket,
              new HeapRecord(
                3,
                page,
                domain,
                token,
                tokenHash(page, line, token),
                null
              )
            )
            token += 1
          }
        }
        line += 1
      }
      if (page % cfg.sampleEvery == 0)
        checksum = fold(checksum, 9, page, domain, line, lineHash(page, 7), bucket.startPage)
      page += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runSafeZone(query: String): RunOutcome = {
    val cfg = CommonCrawlWetConfig
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: SafeBucket, record: SafeRecord): Unit = {
      checksum = fold(
        checksum,
        record.kind,
        record.pageId,
        record.domain,
        record.value,
        record.hash,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeBucket(bucket: SafeBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        consume(bucket, record)
        record = record.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      SafeZone.close(bucket.zone)
    }

    def closeExpired(cutoffPage: Long): Unit =
      while (
        first != null &&
        first.startPage + cfg.pagesPerBucket.toLong <= cutoffPage
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startPage: Long): SafeBucket =
      if (current != null && current.startPage == startPage) current
      else {
        closeExpired(closeCutoff(startPage))
        val bucket = new SafeBucket(SafeZone.open(), startPage, null)
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

    var page = 0
    try {
      while (page < cfg.pages) {
        val domain = domainFor(page)
        val bucket = bucketFor(bucketStart(page))
        val zone = bucket.zone
        appendRecord(
          bucket,
          SafeZoneAllocator
            .allocate(zone, new SafeRecord(1, page, domain, 0, lineHash(page, 0), null))
            .asInstanceOf[SafeRecord]
        )
        var line = 0
        while (line < cfg.linesPerPage) {
          val lh = lineHash(page, line)
          appendRecord(
            bucket,
            SafeZoneAllocator
              .allocate(zone, new SafeRecord(2, page, domain, line, lh, null))
              .asInstanceOf[SafeRecord]
          )
          if (query == "q1-tokenize") {
            var token = 0
            while (token < cfg.tokensPerLine) {
              appendRecord(
                bucket,
                SafeZoneAllocator
                  .allocate(
                    zone,
                    new SafeRecord(
                      3,
                      page,
                      domain,
                      token,
                      tokenHash(page, line, token),
                      null
                    )
                  )
                  .asInstanceOf[SafeRecord]
              )
              token += 1
            }
          }
          line += 1
        }
        if (page % cfg.sampleEvery == 0)
          checksum = fold(checksum, 9, page, domain, line, lineHash(page, 7), bucket.startPage)
        page += 1
      }
      closeExpired(Long.MaxValue)
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket)
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftTrusted(query: String, kind: Int): RunOutcome = {
    val cfg = CommonCrawlWetConfig
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: TrustedBucket, record: TrustedRecord): Unit = {
      checksum = fold(
        checksum,
        record.kind,
        record.pageId,
        record.domain,
        record.value,
        record.hash,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeBucket(bucket: TrustedBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        consume(bucket, record)
        record = record.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      bucket.region.close()
    }

    def closeExpired(cutoffPage: Long): Unit =
      while (
        first != null &&
        first.startPage + cfg.pagesPerBucket.toLong <= cutoffPage
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startPage: Long): TrustedBucket =
      if (current != null && current.startPage == startPage) current
      else {
        closeExpired(closeCutoff(startPage))
        val bucket = new TrustedBucket(RiftRegion.open(kind), startPage, null)
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

    var page = 0
    try {
      while (page < cfg.pages) {
        val domain = domainFor(page)
        val bucket = bucketFor(bucketStart(page))
        val region = bucket.region
        appendRecord(
          bucket,
          region.alloc(new TrustedRecord(1, page, domain, 0, lineHash(page, 0), null))
        )
        var line = 0
        while (line < cfg.linesPerPage) {
          val lh = lineHash(page, line)
          appendRecord(
            bucket,
            region.alloc(new TrustedRecord(2, page, domain, line, lh, null))
          )
          if (query == "q1-tokenize") {
            var token = 0
            while (token < cfg.tokensPerLine) {
              appendRecord(
                bucket,
                region.alloc(
                  new TrustedRecord(
                    3,
                    page,
                    domain,
                    token,
                    tokenHash(page, line, token),
                    null
                  )
                )
              )
              token += 1
            }
          }
          line += 1
        }
        if (page % cfg.sampleEvery == 0)
          checksum = fold(checksum, 9, page, domain, line, lineHash(page, 7), bucket.startPage)
        page += 1
      }
      closeExpired(Long.MaxValue)
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket)
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runMode(mode: String, query: String): RunOutcome =
    mode match {
      case "heap"          => runHeap(query)
      case "safezone"      => runSafeZone(query)
      case "rift-hp"       => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = CommonCrawlWetConfig
    val usesRift = mode == "rift-hp" || mode == "rift-streaming"
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
      s"Running common-crawl-wet-$query-$mode for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=common-crawl-wet-$query-$mode " +
        f"query=$query mode=$mode input=generated-wet-shaped " +
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
    val cfg = CommonCrawlWetConfig
    println(
      s"CONFIG mode=$mode query=$query pages=${cfg.pages} pages_per_bucket=${cfg.pagesPerBucket} live_buckets=${cfg.liveBuckets} domain_space=${cfg.domainSpace} lines_per_page=${cfg.linesPerPage} tokens_per_line=${cfg.tokensPerLine} sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=generated-wet-shaped"
    )
  }
}

@main def CommonCrawlWetMatrix(
    mode: String = "heap",
    query: String = "q1-tokenize"
): Unit = {
  CommonCrawlWetMatrixHelpers.validateMode(mode)
  CommonCrawlWetMatrixHelpers.validateQuery(query)
  CommonCrawlWetMatrixHelpers.printConfig(mode, query)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    CommonCrawlWetMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
