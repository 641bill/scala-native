import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftOpenStreamingHandle, RiftRegion, SafeZone}
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

  private def envFlag(name: String): Boolean =
    sys.env.get(name).exists { value =>
      value == "1" ||
      value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")
    }

  val pages: Int = envInt("COMMON_CRAWL_WET_PAGES", 100000)
  val pagesPerBucket: Int = envInt("COMMON_CRAWL_WET_PAGES_PER_BUCKET", 2500)
  val liveBuckets: Int = envInt("COMMON_CRAWL_WET_LIVE_BUCKETS", 4)
  val domainSpace: Int = envInt("COMMON_CRAWL_WET_DOMAIN_SPACE", 16384)
  val linesPerPage: Int = envInt("COMMON_CRAWL_WET_LINES_PER_PAGE", 8)
  val tokensPerLine: Int = envInt("COMMON_CRAWL_WET_TOKENS_PER_LINE", 16)
  val watLinksPerPage: Int = envInt("COMMON_CRAWL_WAT_LINKS_PER_PAGE", 64)
  val sampleEvery: Int = envInt("COMMON_CRAWL_WET_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("COMMON_CRAWL_WET_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("COMMON_CRAWL_WET_BENCHMARK_RUNS", 3)
  val inputPath: String =
    BenchmarkInputSupport.envString("COMMON_CRAWL_WET_INPUT")
  val diagnostics: Boolean = envFlag("COMMON_CRAWL_WET_DIAG")
  val finalClean: Boolean =
    envFlag("RIFT_FINAL_CLEAN") ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
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
      riftAllocRawBytesTotal: Long,
      riftAllocSlowTotal: Long,
      riftMmapSlabTotal: Long,
      riftMmapBytesTotal: Long,
      riftTlsReuseTotal: Long,
      riftPoolReuseTotal: Long,
      riftRegionOpNanos: Long,
      riftSlowAllocNanos: Long
  )

  private final class InputData(
      val label: String,
      val pages: Int,
      val domains: Array[Int],
      val lineOffsets: Array[Int],
      val lineHashes: Array[Long],
      val tokenCounts: Array[Int],
      val tokenHashSeeds: Array[Long],
      val linkOffsets: Array[Int],
      val linkHashes: Array[Long],
      val linkDomains: Array[Int]
  ) {
    def domainAt(page: Int): Int =
      if (domains == null) domainFor(page) else domains(page)

    def lineCountAt(page: Int): Int =
      if (lineOffsets == null) CommonCrawlWetConfig.linesPerPage
      else lineOffsets(page + 1) - lineOffsets(page)

    def lineHashAt(page: Int, line: Int): Long =
      if (lineHashes == null) lineHash(page, line)
      else lineHashes(lineOffsets(page) + line)

    def tokenCountAt(page: Int, line: Int): Int =
      if (tokenCounts == null) CommonCrawlWetConfig.tokensPerLine
      else tokenCounts(lineOffsets(page) + line)

    def tokenHashAt(page: Int, line: Int, token: Int): Long =
      if (tokenHashSeeds == null) tokenHash(page, line, token)
      else tokenHashSeeds(lineOffsets(page) + line) ^
        (token.toLong * 1099511628211L)

    def linkCountAt(page: Int): Int =
      if (linkOffsets == null) 0 else linkOffsets(page + 1) - linkOffsets(page)

    def linkHashAt(page: Int, link: Int): Long =
      if (linkHashes == null) 0L else linkHashes(linkOffsets(page) + link)

    def linkDomainAt(page: Int, link: Int): Int =
      if (linkDomains == null) domainAt(page)
      else linkDomains(linkOffsets(page) + link)
  }

  private lazy val inputData: InputData = loadInput()

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)

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
          riftAllocRawBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocRawBytesTotal()),
          riftAllocSlowTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocSlowTotal()),
          riftMmapSlabTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabTotal()),
          riftMmapBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesTotal()),
          riftTlsReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsTlsReuseTotal()),
          riftPoolReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsPoolReuseTotal()),
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
        riftAllocRawBytesTotal =
          delta(end.riftAllocRawBytesTotal, start.riftAllocRawBytesTotal),
        riftAllocSlowTotal =
          delta(end.riftAllocSlowTotal, start.riftAllocSlowTotal),
        riftMmapSlabTotal =
          delta(end.riftMmapSlabTotal, start.riftMmapSlabTotal),
        riftMmapBytesTotal =
          delta(end.riftMmapBytesTotal, start.riftMmapBytesTotal),
        riftTlsReuseTotal =
          delta(end.riftTlsReuseTotal, start.riftTlsReuseTotal),
        riftPoolReuseTotal =
          delta(end.riftPoolReuseTotal, start.riftPoolReuseTotal),
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

  private def maxLong(values: Array[Long]): Long = {
    var max = 0L
    var i = 0
    while (i < values.length) {
      if (values(i) > max) max = values(i)
      i += 1
    }
    max
  }

  private def countPositive(values: Array[Long]): Long = {
    var count = 0L
    var i = 0
    while (i < values.length) {
      if (values(i) > 0L) count += 1L
      i += 1
    }
    count
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

  private def loadInput(): InputData = {
    val cfg = CommonCrawlWetConfig
    if (cfg.inputPath.isEmpty)
      return new InputData(
        "generated-wet-shaped",
        cfg.pages,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      )

    val isWatInput =
      cfg.inputPath.endsWith(".wat") || cfg.inputPath.contains(".wat.")
    val domains = scala.collection.mutable.ArrayBuffer.empty[Int]
    val lineOffsets = scala.collection.mutable.ArrayBuffer.empty[Int]
    val lineHashes = scala.collection.mutable.ArrayBuffer.empty[Long]
    val tokenCounts = scala.collection.mutable.ArrayBuffer.empty[Int]
    val tokenHashSeeds = scala.collection.mutable.ArrayBuffer.empty[Long]
    val linkOffsets = scala.collection.mutable.ArrayBuffer.empty[Int]
    val linkHashes = scala.collection.mutable.ArrayBuffer.empty[Long]
    val linkDomains = scala.collection.mutable.ArrayBuffer.empty[Int]
    lineOffsets += 0
    linkOffsets += 0

    var currentDomain = 0
    var sawTarget = false
    var inContent = false
    var currentLines = 0
    var currentLinks = 0

    def flushPage(): Unit =
      if (
        sawTarget && (currentLines > 0 || currentLinks > 0) &&
        domains.length < cfg.pages
      ) {
        domains += currentDomain
        lineOffsets += lineHashes.length
        linkOffsets += linkHashes.length
      }

    def resetRecord(): Unit = {
      sawTarget = false
      inContent = false
      currentLines = 0
      currentLinks = 0
    }

    def appendWatLinks(line: String): Unit = {
      val marker = "\"url\":\""
      var from = 0
      while (currentLinks < cfg.watLinksPerPage) {
        val markerIndex = line.indexOf(marker, from)
        if (markerIndex < 0) return
        val start = markerIndex + marker.length
        val end = line.indexOf('"', start)
        if (end < 0) return
        val url = line.substring(start, end)
        val hash = BenchmarkInputSupport.stableHash(url)
        linkHashes += hash.toLong
        linkDomains +=
          BenchmarkInputSupport.positiveModulo(hash, cfg.domainSpace)
        currentLinks += 1
        from = end + 1
      }
    }

    val reader = BenchmarkInputSupport.openText(cfg.inputPath)
    try {
      var line = reader.readLine()
      while (line != null && domains.length < cfg.pages) {
        if (line == "WARC/1.0") {
          flushPage()
          resetRecord()
        } else if (!inContent) {
          if (line.startsWith("WARC-Target-URI:")) {
            val uri = line.substring("WARC-Target-URI:".length).trim
            currentDomain =
              BenchmarkInputSupport.positiveModulo(
                BenchmarkInputSupport.stableHash(uri),
                cfg.domainSpace
              )
            sawTarget = true
          } else if (line.isEmpty && sawTarget) {
            inContent = true
          }
        } else if (currentLines < cfg.linesPerPage && line.nonEmpty) {
          if (isWatInput)
            appendWatLinks(line)
          lineHashes += BenchmarkInputSupport.stableHash(line).toLong
          tokenCounts += BenchmarkInputSupport.tokenCount(
            line,
            cfg.tokensPerLine
          )
          tokenHashSeeds +=
            (BenchmarkInputSupport.stableHash(line.reverse).toLong ^
              currentLines.toLong)
          currentLines += 1
        }
        line = reader.readLine()
      }
      flushPage()
    } finally {
      reader.close()
    }

    if (domains.isEmpty)
      throw new IllegalArgumentException(
        s"Common Crawl WET input '${cfg.inputPath}' did not contain any usable conversion records"
      )

    val inputLabel =
      if (isWatInput) "real-wat-preloaded" else "real-wet-preloaded"

    new InputData(
      inputLabel,
      domains.length,
      domains.toArray,
      lineOffsets.toArray,
      lineHashes.toArray,
      tokenCounts.toArray,
      tokenHashSeeds.toArray,
      linkOffsets.toArray,
      linkHashes.toArray,
      linkDomains.toArray
    )
  }

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

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap-immix" => "heap"
      case "safezone-current" | "safezone-improved" |
          "safezone-improved-32k" | "safezone-chunk-roots" |
          "safezone-chunk" | "safezone-rootless-32k" |
          "unsafezone-hp" =>
        "safezone"
      case "rift-trusted-hp"                 => "rift-hp"
      case "rift-trusted-streaming"          => "rift-streaming"
      case "rift-checked-rift"               => "rift-checked"
      case "rift-checked-page-token-open-region" |
          "rift-checked-page-token-legacy" =>
        "rift-checked-page-token-legacy"
      case "rift-checked-safezone-improved-32k" =>
        "rift-checked-safezone-32k"
      case "rift-checked-safezone-rootless-32k" =>
        "rift-checked-rootfree-safezone-hp"
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-hp" | "rift-streaming" | "rift-checked" |
          "rift-checked-page-token" | "rift-checked-page-token-legacy" |
          "rift-checked-page-token-open-handle" | "rift-checked-count-by-key" =>
        true
      case _ => false
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked" | "rift-checked-page-token" |
          "rift-checked-page-token-legacy" |
          "rift-checked-page-token-open-handle" |
          "rift-checked-count-by-key" |
          "rift-checked-safezone-32k" |
          "rift-checked-safezone-page-token" |
          "rift-checked-safezone-count-by-key" |
          "rift-checked-rootfree-safezone-hp" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-tokenize" | "q2-domain-window" |
          "q3-parser-scratch" | "q4-wat-links" |
          "q5-wat-link-domain-window" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET query '$other'"
        )
    }

  private def tokenQuery(query: String): Boolean =
    query == "q1-tokenize" || query == "q2-domain-window" ||
      query == "q3-parser-scratch"

  private def linkQuery(query: String): Boolean =
    query == "q4-wat-links" || query == "q5-wat-link-domain-window"

  private def domainWindowQuery(query: String): Boolean =
    query == "q2-domain-window" || query == "q5-wat-link-domain-window"

  private def scratchQuery(query: String): Boolean =
    query == "q3-parser-scratch"

  def runHeap(query: String): RunOutcome = {
    val cfg = CommonCrawlWetConfig
    val input = inputData
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

    def consumeDomainSummary(
        bucket: HeapBucket,
        domain: Int,
        count: Int
    ): Unit = {
      checksum = fold(
        checksum,
        4,
        bucket.startPage.toInt,
        domain,
        count,
        (domain.toLong << 32) ^ count.toLong,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeRecords(bucket: HeapBucket): Unit =
      if (domainWindowQuery(query)) {
        val counts = new Array[Int](cfg.domainSpace)
        var record = bucket.head
        while (record != null) {
          counts(record.domain) += 1
          record = record.next
        }
        var domain = 0
        while (domain < counts.length) {
          val count = counts(domain)
          if (count != 0)
            consumeDomainSummary(bucket, domain, count)
          domain += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          consume(bucket, record)
          record = record.next
        }
      }

    def emit(bucket: HeapBucket, record: HeapRecord): Unit =
      if (scratchQuery(query)) consume(bucket, record)
      else appendRecord(bucket, record)

    def closeExpired(cutoffPage: Long): Unit =
      while (
        first != null &&
        first.startPage + cfg.pagesPerBucket.toLong <= cutoffPage
      ) {
        val bucket = first
        closeRecords(bucket)
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
    while (page < input.pages) {
      val domain = input.domainAt(page)
      val bucket = bucketFor(bucketStart(page))
      emit(bucket, new HeapRecord(1, page, domain, 0, input.lineHashAt(page, 0), null))
      var observed = 0
      if (linkQuery(query)) {
        val links = input.linkCountAt(page)
        while (observed < links) {
          emit(
            bucket,
            new HeapRecord(
              5,
              page,
              input.linkDomainAt(page, observed),
              observed,
              input.linkHashAt(page, observed),
              null
            )
          )
          observed += 1
        }
      } else {
        val lines = input.lineCountAt(page)
        while (observed < lines) {
          val lh = input.lineHashAt(page, observed)
          emit(bucket, new HeapRecord(2, page, domain, observed, lh, null))
          if (tokenQuery(query)) {
            var token = 0
            val tokens = input.tokenCountAt(page, observed)
            while (token < tokens) {
              emit(
                bucket,
                new HeapRecord(
                  3,
                  page,
                  domain,
                  token,
                  input.tokenHashAt(page, observed, token),
                  null
                )
              )
              token += 1
            }
          }
          observed += 1
        }
      }
      if (page % cfg.sampleEvery == 0)
        checksum = fold(
          checksum,
          9,
          page,
          domain,
          observed,
          if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
          else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
          bucket.startPage
        )
      page += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runSafeZone(query: String): RunOutcome = {
    val cfg = CommonCrawlWetConfig
    val input = inputData
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

    def consumeDomainSummary(
        bucket: SafeBucket,
        domain: Int,
        count: Int
    ): Unit = {
      checksum = fold(
        checksum,
        4,
        bucket.startPage.toInt,
        domain,
        count,
        (domain.toLong << 32) ^ count.toLong,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeRecords(bucket: SafeBucket): Unit =
      if (domainWindowQuery(query)) {
        val counts = new Array[Int](cfg.domainSpace)
        var record = bucket.head
        while (record != null) {
          counts(record.domain) += 1
          record = record.next
        }
        var domain = 0
        while (domain < counts.length) {
          val count = counts(domain)
          if (count != 0)
            consumeDomainSummary(bucket, domain, count)
          domain += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          consume(bucket, record)
          record = record.next
        }
      }

    def emit(bucket: SafeBucket, record: SafeRecord): Unit =
      if (scratchQuery(query)) consume(bucket, record)
      else appendRecord(bucket, record)

    def closeBucket(bucket: SafeBucket): Unit = {
      closeRecords(bucket)
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
      while (page < input.pages) {
        val domain = input.domainAt(page)
        val bucket = bucketFor(bucketStart(page))
        val zone = bucket.zone
        emit(
          bucket,
          SafeZoneAllocator
            .allocate(zone, new SafeRecord(1, page, domain, 0, input.lineHashAt(page, 0), null))
            .asInstanceOf[SafeRecord]
        )
        var observed = 0
        if (linkQuery(query)) {
          val links = input.linkCountAt(page)
          while (observed < links) {
            emit(
              bucket,
              SafeZoneAllocator
                .allocate(
                  zone,
                  new SafeRecord(
                    5,
                    page,
                    input.linkDomainAt(page, observed),
                    observed,
                    input.linkHashAt(page, observed),
                    null
                  )
                )
                .asInstanceOf[SafeRecord]
            )
            observed += 1
          }
        } else {
          val lines = input.lineCountAt(page)
          while (observed < lines) {
            val lh = input.lineHashAt(page, observed)
            emit(
              bucket,
              SafeZoneAllocator
                .allocate(zone, new SafeRecord(2, page, domain, observed, lh, null))
                .asInstanceOf[SafeRecord]
            )
            if (tokenQuery(query)) {
              var token = 0
              val tokens = input.tokenCountAt(page, observed)
              while (token < tokens) {
                emit(
                  bucket,
                  SafeZoneAllocator
                    .allocate(
                      zone,
                      new SafeRecord(
                        3,
                        page,
                        domain,
                        token,
                        input.tokenHashAt(page, observed, token),
                        null
                      )
                    )
                    .asInstanceOf[SafeRecord]
                )
                token += 1
              }
            }
            observed += 1
          }
        }
        if (page % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            9,
            page,
            domain,
            observed,
            if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
            else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
            bucket.startPage
          )
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
    val input = inputData
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

    def consumeDomainSummary(
        bucket: TrustedBucket,
        domain: Int,
        count: Int
    ): Unit = {
      checksum = fold(
        checksum,
        4,
        bucket.startPage.toInt,
        domain,
        count,
        (domain.toLong << 32) ^ count.toLong,
        bucket.startPage
      )
      outputCount += 1L
    }

    def closeRecords(bucket: TrustedBucket): Unit =
      if (domainWindowQuery(query)) {
        val counts = new Array[Int](cfg.domainSpace)
        var record = bucket.head
        while (record != null) {
          counts(record.domain) += 1
          record = record.next
        }
        var domain = 0
        while (domain < counts.length) {
          val count = counts(domain)
          if (count != 0)
            consumeDomainSummary(bucket, domain, count)
          domain += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          consume(bucket, record)
          record = record.next
        }
      }

    def emit(bucket: TrustedBucket, record: TrustedRecord): Unit =
      if (scratchQuery(query)) consume(bucket, record)
      else appendRecord(bucket, record)

    def closeBucket(bucket: TrustedBucket): Unit = {
      closeRecords(bucket)
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
      while (page < input.pages) {
        val domain = input.domainAt(page)
        val bucket = bucketFor(bucketStart(page))
        val region = bucket.region
        emit(
          bucket,
          region.alloc(new TrustedRecord(1, page, domain, 0, input.lineHashAt(page, 0), null))
        )
        var observed = 0
        if (linkQuery(query)) {
          val links = input.linkCountAt(page)
          while (observed < links) {
            emit(
              bucket,
              region.alloc(
                new TrustedRecord(
                  5,
                  page,
                  input.linkDomainAt(page, observed),
                  observed,
                  input.linkHashAt(page, observed),
                  null
                )
              )
            )
            observed += 1
          }
        } else {
          val lines = input.lineCountAt(page)
          while (observed < lines) {
            val lh = input.lineHashAt(page, observed)
            emit(
              bucket,
              region.alloc(new TrustedRecord(2, page, domain, observed, lh, null))
            )
            if (tokenQuery(query)) {
              var token = 0
              val tokens = input.tokenCountAt(page, observed)
              while (token < tokens) {
                emit(
                  bucket,
                  region.alloc(
                    new TrustedRecord(
                      3,
                      page,
                      domain,
                      token,
                      input.tokenHashAt(page, observed, token),
                      null
                    )
                  )
                )
                token += 1
              }
            }
            observed += 1
          }
        }
        if (page % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            9,
            page,
            domain,
            observed,
            if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
            else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
            bucket.startPage
          )
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

  private def runRiftCheckedBody(query: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
      val cfg = CommonCrawlWetConfig
      val input = inputData

      final class CheckedRecord(
          val kind: Int,
          val pageId: Int,
          val domain: Int,
          val value: Int,
          val hash: Long
      ) extends RiftRegion.StreamAppendNode

      val window =
        RiftRegion.streamAppendWindow[CheckedRecord](
          cfg.pagesPerBucket.toLong
        )
      var checksum = 0L
      var outputCount = 0L

      def consume(
          bucket: RiftRegion.StreamBucket^{stream},
          record: CheckedRecord^{stream}
      ): Unit = {
        checksum = fold(
          checksum,
          record.kind,
          record.pageId,
          record.domain,
          record.value,
          record.hash,
          bucket.startSeconds
        )
        outputCount += 1L
      }

      def consumeDomainSummary(
          bucket: RiftRegion.StreamBucket^{stream},
          domain: Int,
          count: Int
      ): Unit = {
        checksum = fold(
          checksum,
          4,
          bucket.startSeconds.toInt,
          domain,
          count,
          (domain.toLong << 32) ^ count.toLong,
          bucket.startSeconds
        )
        outputCount += 1L
      }

      def closeRecords(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[CheckedRecord]^{stream}
      ): Unit =
        if (domainWindowQuery(query)) {
          val counts = new Array[Int](cfg.domainSpace)
          while (cursor.hasNext) {
            val record: CheckedRecord^{stream} = cursor.next()
            counts(record.domain) += 1
          }
          var domain = 0
          while (domain < counts.length) {
            val count = counts(domain)
            if (count != 0)
              consumeDomainSummary(bucket, domain, count)
            domain += 1
          }
        } else {
          while (cursor.hasNext) {
            val record: CheckedRecord^{stream} = cursor.next()
            consume(bucket, record)
          }
        }

      def closeExpired(cutoffPage: Long): Unit =
        RiftRegion.closeAppendWindowBucketsBeforeWithCursor(
          stream,
          window,
          cutoffPage
        ) { (bucket, cursor) =>
          closeRecords(bucket, cursor)
        }

      var currentStartPage = Long.MinValue
      var currentBucket: RiftRegion.StreamBucket^{stream} = null
      var currentBucketRegion: RiftRegion.StreamingRegion^{stream} = null
      var page = 0
      while (page < input.pages) {
        val domain = input.domainAt(page)
        val startPage = bucketStart(page)
        if (startPage != currentStartPage) {
          closeExpired(closeCutoff(startPage))
          currentStartPage = startPage
          currentBucket =
            RiftRegion.streamAppendWindowBucketFor(stream, window, startPage)
          currentBucketRegion =
            RiftRegion.streamBucketRegion(stream, currentBucket)
        }
        val bucket = currentBucket
        val bucketRegion = currentBucketRegion

        val pageRecord: CheckedRecord^{stream} =
          RiftRegion.alloc(
            new CheckedRecord(1, page, domain, 0, input.lineHashAt(page, 0))
          )(using bucketRegion)
        if (scratchQuery(query)) consume(bucket, pageRecord)
        else RiftRegion.appendWindow(stream, window, bucket, pageRecord)

        var observed = 0
        if (linkQuery(query)) {
          val links = input.linkCountAt(page)
          while (observed < links) {
            val linkRecord: CheckedRecord^{stream} =
              RiftRegion.alloc(
                new CheckedRecord(
                  5,
                  page,
                  input.linkDomainAt(page, observed),
                  observed,
                  input.linkHashAt(page, observed)
                )
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, linkRecord)
            observed += 1
          }
        } else {
          val lines = input.lineCountAt(page)
          while (observed < lines) {
            val lh = input.lineHashAt(page, observed)
            val lineRecord: CheckedRecord^{stream} =
              RiftRegion.alloc(new CheckedRecord(2, page, domain, observed, lh))(
                using bucketRegion
              )
            if (scratchQuery(query)) consume(bucket, lineRecord)
            else RiftRegion.appendWindow(stream, window, bucket, lineRecord)
            if (tokenQuery(query)) {
              var token = 0
              val tokens = input.tokenCountAt(page, observed)
              while (token < tokens) {
                val tokenRecord: CheckedRecord^{stream} =
                  RiftRegion.alloc(
                    new CheckedRecord(
                      3,
                      page,
                      domain,
                      token,
                      input.tokenHashAt(page, observed, token)
                    )
                  )(using bucketRegion)
                if (scratchQuery(query)) consume(bucket, tokenRecord)
                else RiftRegion.appendWindow(stream, window, bucket, tokenRecord)
                token += 1
              }
            }
            observed += 1
          }
        }
        if (page % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            9,
            page,
            domain,
            observed,
            if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
            else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
            bucket.startSeconds
          )
        page += 1
      }

      RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
        (bucket, cursor) =>
          closeRecords(bucket, cursor)
      }

      checksumSink = checksum
      outputSink = outputCount
      RunOutcome(checksum, outputCount)
    }

  def runRiftChecked(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedBody(query)
    }

  def runRiftCheckedSafeZone(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedBody(query)
    }

  private def runRiftCheckedPageTokenBody(query: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
      if (scratchQuery(query))
        throw new IllegalArgumentException(
          s"checked page-token mode does not support scratch query '$query'"
        )

      val cfg = CommonCrawlWetConfig
      val input = inputData
      val diagnostics = cfg.diagnostics
      var bucketSwitchNanos = 0L
      var appendNanos = 0L
      var closeCursorNanos = 0L
      var domainAggregateNanos = 0L
      var sampleChecksumNanos = 0L
      var finalCloseNanos = 0L
      var bucketSwitches = 0L
      var appendedRecords = 0L
      var closedRecords = 0L
      var closeBuckets = 0L
      var domainSummaries = 0L

      final class CheckedRecord(
          val kind: Int,
          val pageId: Int,
          val domain: Int,
          val value: Int,
          val hash: Long
      ) extends RiftRegion.StreamAppendNode

      val window =
        RiftRegion.streamPageTokenAppendWindow[CheckedRecord](
          cfg.pagesPerBucket.toLong
        )
      var checksum = 0L
      var outputCount = 0L

      def consumeDomainSummary(
          bucket: RiftRegion.StreamBucket^{stream},
          domain: Int,
          count: Int
      ): Unit = {
        checksum = fold(
          checksum,
          4,
          bucket.startSeconds.toInt,
          domain,
          count,
          (domain.toLong << 32) ^ count.toLong,
          bucket.startSeconds
        )
        outputCount += 1L
      }

      def closeRecords(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[CheckedRecord]^{stream}
      ): Unit = {
        val closeStarted = if (diagnostics) System.nanoTime() else 0L
        if (domainWindowQuery(query)) {
          val counts = new Array[Int](cfg.domainSpace)
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val record: CheckedRecord^{stream} =
              current.asInstanceOf[CheckedRecord^{stream}]
            if (diagnostics) closedRecords += 1L
            counts(record.domain) += 1
            current = cursor.nextOwnedOrNull()
          }
          val aggregateStarted = if (diagnostics) System.nanoTime() else 0L
          var domain = 0
          while (domain < counts.length) {
            val count = counts(domain)
            if (count != 0) {
              consumeDomainSummary(bucket, domain, count)
              if (diagnostics) domainSummaries += 1L
            }
            domain += 1
          }
          if (diagnostics)
            domainAggregateNanos += System.nanoTime() - aggregateStarted
        } else {
          var current = cursor.nextOwnedOrNull()
          while (current != null) {
            val record: CheckedRecord^{stream} =
              current.asInstanceOf[CheckedRecord^{stream}]
            if (diagnostics) closedRecords += 1L
            checksum = fold(
              checksum,
              record.kind,
              record.pageId,
              record.domain,
              record.value,
              record.hash,
              bucket.startSeconds
            )
            outputCount += 1L
            current = cursor.nextOwnedOrNull()
          }
        }
        if (diagnostics) {
          closeCursorNanos += System.nanoTime() - closeStarted
          closeBuckets += 1L
        }
      }

      var currentStartPage = Long.MinValue
      var currentRegion: RiftRegion.OpenStreamingRegion^{stream} = null
      var page = 0
      while (page < input.pages) {
        val domain = input.domainAt(page)
        val startPage = bucketStart(page)
        if (startPage != currentStartPage) {
          val bucketStarted = if (diagnostics) System.nanoTime() else 0L
          currentStartPage = startPage
          currentRegion =
            RiftRegion.pageTokenAppendOpenRegionFor(
              stream,
              window,
              startPage,
              closeCutoff(startPage)
            )(closeRecords)
          if (diagnostics) {
            bucketSwitchNanos += System.nanoTime() - bucketStarted
            bucketSwitches += 1L
          }
        }

        val appendStarted = if (diagnostics) System.nanoTime() else 0L
        val pageRecord: CheckedRecord^{stream} =
          RiftRegion.allocOpen(
            new CheckedRecord(1, page, domain, 0, input.lineHashAt(page, 0))
          )(using currentRegion)
        RiftRegion.appendPageToken(stream, window, pageRecord)
        if (diagnostics) appendedRecords += 1L

        var observed = 0
        if (linkQuery(query)) {
          val links = input.linkCountAt(page)
          while (observed < links) {
            val linkRecord: CheckedRecord^{stream} =
              RiftRegion.allocOpen(
                new CheckedRecord(
                  5,
                  page,
                  input.linkDomainAt(page, observed),
                  observed,
                  input.linkHashAt(page, observed)
                )
              )(using currentRegion)
            RiftRegion.appendPageToken(stream, window, linkRecord)
            if (diagnostics) appendedRecords += 1L
            observed += 1
          }
        } else {
          val lines = input.lineCountAt(page)
          while (observed < lines) {
            val lh = input.lineHashAt(page, observed)
            val lineRecord: CheckedRecord^{stream} =
              RiftRegion.allocOpen(new CheckedRecord(2, page, domain, observed, lh))(
                using currentRegion
              )
            RiftRegion.appendPageToken(stream, window, lineRecord)
            if (diagnostics) appendedRecords += 1L
            if (tokenQuery(query)) {
              var token = 0
              val tokens = input.tokenCountAt(page, observed)
              while (token < tokens) {
                val tokenRecord: CheckedRecord^{stream} =
                  RiftRegion.allocOpen(
                    new CheckedRecord(
                      3,
                      page,
                      domain,
                      token,
                      input.tokenHashAt(page, observed, token)
                    )
                  )(using currentRegion)
                RiftRegion.appendPageToken(stream, window, tokenRecord)
                if (diagnostics) appendedRecords += 1L
                token += 1
              }
            }
            observed += 1
          }
        }
        if (diagnostics)
          appendNanos += System.nanoTime() - appendStarted
        if (page % cfg.sampleEvery == 0) {
          val checksumStarted = if (diagnostics) System.nanoTime() else 0L
          checksum = fold(
            checksum,
            9,
            page,
            domain,
            observed,
            if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
            else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
            currentStartPage
          )
          if (diagnostics)
            sampleChecksumNanos += System.nanoTime() - checksumStarted
        }
        page += 1
      }

      val finalCloseStarted = if (diagnostics) System.nanoTime() else 0L
      RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
        closeRecords
      )
      if (diagnostics)
        finalCloseNanos += System.nanoTime() - finalCloseStarted

      if (diagnostics) {
        def ms(nanos: Long): Double = nanos / 1000000.0
        val estimatedExpiredCloseNanos =
          math.max(0L, closeCursorNanos - finalCloseNanos)
        val estimatedBucketOpenNanos =
          math.max(0L, bucketSwitchNanos - estimatedExpiredCloseNanos)
        println(
          f"COMMON_CRAWL_WET_DIAG query=$query " +
            s"bucket_switches=$bucketSwitches appended_records=$appendedRecords " +
            s"closed_records=$closedRecords close_buckets=$closeBuckets " +
            s"domain_summaries=$domainSummaries " +
            f"bucket_switch_ms=${ms(bucketSwitchNanos)}%.3f " +
            f"estimated_bucket_open_ms=${ms(estimatedBucketOpenNanos)}%.3f " +
            f"append_ms=${ms(appendNanos)}%.3f " +
            f"close_cursor_ms=${ms(closeCursorNanos)}%.3f " +
            f"domain_aggregate_ms=${ms(domainAggregateNanos)}%.3f " +
            f"sample_checksum_ms=${ms(sampleChecksumNanos)}%.3f " +
            f"final_close_ms=${ms(finalCloseNanos)}%.3f"
        )
      }

      checksumSink = checksum
      outputSink = outputCount
      RunOutcome(checksum, outputCount)
    }

  def runRiftCheckedPageTokenLegacy(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(query)
    }

  def runRiftCheckedSafeZonePageToken(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedPageTokenBody(query)
    }

  private def runRiftCheckedPageTokenOpenHandleBody(query: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    if (scratchQuery(query))
      throw new IllegalArgumentException(
        s"checked page-token open-handle mode does not support scratch query '$query'"
      )

    val cfg = CommonCrawlWetConfig
    val input = inputData

    final class CheckedRecord(
        val kind: Int,
        val pageId: Int,
        val domain: Int,
        val value: Int,
        val hash: Long
    ) extends RiftRegion.StreamAppendNode

    val window =
      RiftRegion.streamPageTokenAppendWindow[CheckedRecord](
        cfg.pagesPerBucket.toLong
      )
    var checksum = 0L
    var outputCount = 0L

    def consumeDomainSummary(
        bucket: RiftRegion.StreamBucket^{stream},
        domain: Int,
        count: Int
    ): Unit = {
      checksum = fold(
        checksum,
        4,
        bucket.startSeconds.toInt,
        domain,
        count,
        (domain.toLong << 32) ^ count.toLong,
        bucket.startSeconds
      )
      outputCount += 1L
    }

    def closeRecords(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedRecord]^{stream}
    ): Unit =
      if (domainWindowQuery(query)) {
        val counts = new Array[Int](cfg.domainSpace)
        var current = cursor.nextOwnedOrNull()
        while (current != null) {
          val record: CheckedRecord^{stream} =
            current.asInstanceOf[CheckedRecord^{stream}]
          counts(record.domain) += 1
          current = cursor.nextOwnedOrNull()
        }
        var domain = 0
        while (domain < counts.length) {
          val count = counts(domain)
          if (count != 0)
            consumeDomainSummary(bucket, domain, count)
          domain += 1
        }
      } else {
        var current = cursor.nextOwnedOrNull()
        while (current != null) {
          val record: CheckedRecord^{stream} =
            current.asInstanceOf[CheckedRecord^{stream}]
          checksum = fold(
            checksum,
            record.kind,
            record.pageId,
            record.domain,
            record.value,
            record.hash,
            bucket.startSeconds
          )
          outputCount += 1L
          current = cursor.nextOwnedOrNull()
        }
      }

    var currentStartPage = Long.MinValue
    var currentHandle: RiftOpenStreamingHandle^{stream} = null
    var page = 0
    while (page < input.pages) {
      val domain = input.domainAt(page)
      val startPage = bucketStart(page)
      if (startPage != currentStartPage) {
        currentStartPage = startPage
        currentHandle =
          RiftRegion.pageTokenAppendRiftOpenHandleFor(
            stream,
            window,
            startPage,
            closeCutoff(startPage)
          )(closeRecords)
      }

      val pageRecord: CheckedRecord^{stream} =
        RiftAllocator.allocateOpenHandle(
          currentHandle,
          new CheckedRecord(1, page, domain, 0, input.lineHashAt(page, 0))
        )
      RiftRegion.appendPageToken(stream, window, pageRecord)

      var observed = 0
      if (linkQuery(query)) {
        val links = input.linkCountAt(page)
        while (observed < links) {
          val linkRecord: CheckedRecord^{stream} =
            RiftAllocator.allocateOpenHandle(
              currentHandle,
              new CheckedRecord(
                5,
                page,
                input.linkDomainAt(page, observed),
                observed,
                input.linkHashAt(page, observed)
              )
            )
          RiftRegion.appendPageToken(stream, window, linkRecord)
          observed += 1
        }
      } else {
        val lines = input.lineCountAt(page)
        while (observed < lines) {
          val lh = input.lineHashAt(page, observed)
          val lineRecord: CheckedRecord^{stream} =
            RiftAllocator.allocateOpenHandle(
              currentHandle,
              new CheckedRecord(2, page, domain, observed, lh)
            )
          RiftRegion.appendPageToken(stream, window, lineRecord)
          if (tokenQuery(query)) {
            var token = 0
            val tokens = input.tokenCountAt(page, observed)
            while (token < tokens) {
              val tokenRecord: CheckedRecord^{stream} =
                RiftAllocator.allocateOpenHandle(
                  currentHandle,
                  new CheckedRecord(
                    3,
                    page,
                    domain,
                    token,
                    input.tokenHashAt(page, observed, token)
                  )
                )
              RiftRegion.appendPageToken(stream, window, tokenRecord)
              token += 1
            }
          }
          observed += 1
        }
      }
      if (page % cfg.sampleEvery == 0)
        checksum = fold(
          checksum,
          9,
          page,
          domain,
          observed,
          if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
          else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
          currentStartPage
        )
      page += 1
    }

    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      closeRecords
    )

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftCheckedPageTokenOpenHandle(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenOpenHandleBody(query)
    }

  def runRiftCheckedPageToken(query: String): RunOutcome =
    runRiftCheckedPageTokenOpenHandle(query)

  private def runRiftCheckedCountByKeyBody(query: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    if (!domainWindowQuery(query))
      throw new IllegalArgumentException(
        s"checked count-by-key mode only supports domain-window queries, got '$query'"
      )

    val cfg = CommonCrawlWetConfig
    val input = inputData

    final class CheckedRecord(
        val kind: Int,
        val pageId: Int,
        val domain: Int,
        val value: Int,
        val hash: Long
    ) extends RiftRegion.StreamAppendNode

    val operator =
      RiftRegion.pageTokenCountByKey[CheckedRecord](
        cfg.pagesPerBucket.toLong,
        cfg.domainSpace,
        cfg.liveBuckets
      )
    var checksum = 0L
    var outputCount = 0L

    def consumeDomainSummary(
        bucket: RiftRegion.StreamBucket^{stream},
        domain: Int,
        count: Int,
        sum: Long
    ): Unit = {
      checksum = fold(
        checksum,
        4,
        bucket.startSeconds.toInt,
        domain,
        count,
        (domain.toLong << 32) ^ count.toLong,
        bucket.startSeconds
      )
      outputCount += 1L
    }

    var currentStartPage = Long.MinValue
    var currentRegion: RiftRegion.OpenStreamingRegion^{stream} = null
    var page = 0
    while (page < input.pages) {
      val domain = input.domainAt(page)
      val startPage = bucketStart(page)
      if (startPage != currentStartPage) {
        currentStartPage = startPage
        currentRegion =
          RiftRegion.pageTokenCountByKeyOpenRegionFor(
            stream,
            operator,
            startPage,
            closeCutoff(startPage)
          )(consumeDomainSummary)
      }

      val pageRecord: CheckedRecord^{stream} =
        RiftRegion.allocOpen(
          new CheckedRecord(1, page, domain, 0, input.lineHashAt(page, 0))
        )(using currentRegion)
      RiftRegion.appendPageTokenCountByKey(
        stream,
        operator,
        pageRecord,
        domain,
        0L
      )

      var observed = 0
      if (linkQuery(query)) {
        val links = input.linkCountAt(page)
        while (observed < links) {
          val linkDomain = input.linkDomainAt(page, observed)
          val linkRecord: CheckedRecord^{stream} =
            RiftRegion.allocOpen(
              new CheckedRecord(
                5,
                page,
                linkDomain,
                observed,
                input.linkHashAt(page, observed)
              )
            )(using currentRegion)
          RiftRegion.appendPageTokenCountByKey(
            stream,
            operator,
            linkRecord,
            linkDomain,
            0L
          )
          observed += 1
        }
      } else {
        val lines = input.lineCountAt(page)
        while (observed < lines) {
          val lh = input.lineHashAt(page, observed)
          val lineRecord: CheckedRecord^{stream} =
            RiftRegion.allocOpen(new CheckedRecord(2, page, domain, observed, lh))(
              using currentRegion
            )
          RiftRegion.appendPageTokenCountByKey(
            stream,
            operator,
            lineRecord,
            domain,
            0L
          )
          if (tokenQuery(query)) {
            var token = 0
            val tokens = input.tokenCountAt(page, observed)
            while (token < tokens) {
              val tokenRecord: CheckedRecord^{stream} =
                RiftRegion.allocOpen(
                  new CheckedRecord(
                    3,
                    page,
                    domain,
                    token,
                    input.tokenHashAt(page, observed, token)
                )
              )(using currentRegion)
              RiftRegion.appendPageTokenCountByKey(
                stream,
                operator,
                tokenRecord,
                domain,
                0L
              )
              token += 1
            }
          }
          observed += 1
        }
      }
      if (page % cfg.sampleEvery == 0)
        checksum = fold(
          checksum,
          9,
          page,
          domain,
          observed,
          if (linkQuery(query) && observed > 0) input.linkHashAt(page, 0)
          else input.lineHashAt(page, if (observed == 0) 0 else observed - 1),
          currentStartPage
        )
      page += 1
    }

    RiftRegion.closeAllPageTokenCountByKeyBuckets(stream, operator)(
      consumeDomainSummary
    )

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftCheckedCountByKey(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedCountByKeyBody(query)
    }

  def runRiftCheckedSafeZoneCountByKey(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedCountByKeyBody(query)
    }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"                  => runHeap(query)
      case "safezone"              => runSafeZone(query)
      case "rift-hp"               => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming"        => runRiftTrusted(query, RiftRegion.Streaming)
      case "rift-checked"          => runRiftChecked(query)
      case "rift-checked-page-token" => runRiftCheckedPageToken(query)
      case "rift-checked-page-token-legacy" =>
        runRiftCheckedPageTokenLegacy(query)
      case "rift-checked-page-token-open-handle" =>
        runRiftCheckedPageTokenOpenHandle(query)
      case "rift-checked-count-by-key" => runRiftCheckedCountByKey(query)
      case "rift-checked-safezone-32k" | "rift-checked-rootfree-safezone-hp" =>
        runRiftCheckedSafeZone(query)
      case "rift-checked-safezone-page-token" =>
        runRiftCheckedSafeZonePageToken(query)
      case "rift-checked-safezone-count-by-key" =>
        runRiftCheckedSafeZoneCountByKey(query)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Common Crawl WET mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = CommonCrawlWetConfig
    val input = inputData
    val usesRift = usesRiftRuntime(mode)
    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      var outputCount = 0L
      while (run < cfg.benchmarkRuns) {
        val outcome = runMode(mode, query)
        if (run == 0) {
          checksum = outcome.checksum
          outputCount = outcome.outputCount
        } else if (
          outcome.checksum != checksum || outcome.outputCount != outputCount
        ) {
          throw new IllegalStateException(
            s"final-clean common-crawl mismatch query=$query mode=$mode first_checksum=$checksum first_output_count=$outputCount actual=$outcome"
          )
        }
        run += 1
      }
      println(
        s"RESULT name=common-crawl-wet-$query-$mode " +
          s"measurement_level=L1 final_clean=1 query=$query mode=$mode " +
          s"backend_mode=${canonicalMode(mode)} input=${input.label} " +
          s"pages=${input.pages} configured_pages=${cfg.pages} " +
          s"runs=${cfg.benchmarkRuns} checksum=$checksum " +
          s"output_count=$outputCount"
      )
      return
    }

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
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftRawBytes = new Array[Long](cfg.benchmarkRuns)
    val riftSlowAllocs = new Array[Long](cfg.benchmarkRuns)
    val riftMmapSlabs = new Array[Long](cfg.benchmarkRuns)
    val riftMmapBytes = new Array[Long](cfg.benchmarkRuns)
    val riftTlsReuse = new Array[Long](cfg.benchmarkRuns)
    val riftPoolReuse = new Array[Long](cfg.benchmarkRuns)
    val riftSlowAllocNanos = new Array[Long](cfg.benchmarkRuns)
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
      gcCollections(run) = runtime.gcCollections
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftRawBytes(run) = runtime.riftAllocRawBytesTotal
      riftSlowAllocs(run) = runtime.riftAllocSlowTotal
      riftMmapSlabs(run) = runtime.riftMmapSlabTotal
      riftMmapBytes(run) = runtime.riftMmapBytesTotal
      riftTlsReuse(run) = runtime.riftTlsReuseTotal
      riftPoolReuse(run) = runtime.riftPoolReuseTotal
      riftSlowAllocNanos(run) = runtime.riftSlowAllocNanos
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
    val maxGc = maxLong(gcNanos)
    val runsWithGc = countPositive(gcCollections)
    val maxGcCollections = maxLong(gcCollections)
    val medianRiftOp = medianLong(riftOpNanos)
    val medianObjects = medianLong(riftObjects)
    val medianRawBytes = medianLong(riftRawBytes)
    val medianSlowAllocs = medianLong(riftSlowAllocs)
    val medianMmapSlabs = medianLong(riftMmapSlabs)
    val medianMmapBytes = medianLong(riftMmapBytes)
    val medianTlsReuse = medianLong(riftTlsReuse)
    val medianPoolReuse = medianLong(riftPoolReuse)
    val medianSlowAllocNanos = medianLong(riftSlowAllocNanos)
    val medianOpens = medianLong(riftOpens)
    val medianCloses = medianLong(riftCloses)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=common-crawl-wet-$query-$mode " +
        f"query=$query mode=$mode input=${input.label} " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"max_gc_ms=${maxGc / 1000000.0}%.3f " +
        f"runs_with_gc=$runsWithGc%d " +
        f"max_gc_collections=$maxGcCollections%d " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_slow_alloc_ms=${medianSlowAllocNanos / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_alloc_raw_bytes_total=$medianRawBytes%d " +
        f"median_rift_alloc_slow_total=$medianSlowAllocs%d " +
        f"median_rift_mmap_slab_total=$medianMmapSlabs%d " +
        f"median_rift_mmap_bytes_total=$medianMmapBytes%d " +
        f"median_rift_tls_reuse_total=$medianTlsReuse%d " +
        f"median_rift_pool_reuse_total=$medianPoolReuse%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=${expected.checksum}%d " +
        f"output_count=${expected.outputCount}%d"
    )
  }

  def printConfig(mode: String, query: String): Unit = {
    val cfg = CommonCrawlWetConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode backend_mode=${canonicalMode(mode)} query=$query pages=${input.pages} configured_pages=${cfg.pages} pages_per_bucket=${cfg.pagesPerBucket} live_buckets=${cfg.liveBuckets} domain_space=${cfg.domainSpace} lines_per_page=${cfg.linesPerPage} tokens_per_line=${cfg.tokensPerLine} sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=${input.label} input_path=${cfg.inputPath}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    usesRiftRuntime(mode)
}

@main def CommonCrawlWetMatrix(
    mode: String = "heap",
    query: String = "q1-tokenize"
): Unit = {
  CommonCrawlWetMatrixHelpers.validateMode(mode)
  CommonCrawlWetMatrixHelpers.validateQuery(query)
  CommonCrawlWetMatrixHelpers.printConfig(mode, query)

  val usesRift = CommonCrawlWetMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    CommonCrawlWetMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
