import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object YahooAdRegionConfig {
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

  val events: Int = envInt("YAHOO_AD_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("YAHOO_AD_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("YAHOO_AD_LIVE_BUCKETS", 4)
  val campaignSpace: Int = envInt("YAHOO_AD_CAMPAIGN_SPACE", 100)
  val adsPerCampaign: Int = envInt("YAHOO_ADS_PER_CAMPAIGN", 10)
  val sampleEvery: Int = envInt("YAHOO_AD_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("YAHOO_AD_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("YAHOO_AD_BENCHMARK_RUNS", 3)
  val inputPath: String = BenchmarkInputSupport.envString("YAHOO_AD_INPUT")

  val adSpace: Int = math.max(1, campaignSpace * adsPerCampaign)
}

object YahooAdRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapEvent(
      val kind: Int,
      val timestamp: Long,
      val adId: Int,
      val campaignId: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: HeapEvent
  )

  private final class HeapBucket(val startEvent: Long, var next: HeapBucket) {
    var head: HeapEvent = null
    var tail: HeapEvent = null
  }

  private final class SafeEvent(
      val kind: Int,
      val timestamp: Long,
      val adId: Int,
      val campaignId: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: SafeEvent
  )

  private final class SafeBucket(val zone: SafeZone, val startEvent: Long, var next: SafeBucket) {
    var head: SafeEvent = null
    var tail: SafeEvent = null
  }

  private final class TrustedEvent(
      val kind: Int,
      val timestamp: Long,
      val adId: Int,
      val campaignId: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: TrustedEvent
  )

  private final class TrustedBucket(
      val region: RiftRegion,
      val startEvent: Long,
      var next: TrustedBucket
  ) {
    var head: TrustedEvent = null
    var tail: TrustedEvent = null
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

  private final class InputData(
      val label: String,
      val events: Int,
      val adIds: Array[Int],
      val eventTypes: Array[Int],
      val values: Array[Int],
      val hashes: Array[Long]
  ) {
    def adAt(index: Int): Int =
      if (adIds == null) adFor(index) else adIds(index)

    def campaignAt(adId: Int): Int =
      if (YahooAdRegionConfig.campaignSpace <= 1) 0
      else (adId / YahooAdRegionConfig.adsPerCampaign) %
        YahooAdRegionConfig.campaignSpace

    def eventTypeAt(index: Int): Int =
      if (eventTypes == null) eventTypeFor(index) else eventTypes(index)

    def valueAt(index: Int): Int =
      if (values == null) valueFor(index) else values(index)

    def hashAt(index: Int, adId: Int, eventType: Int): Long =
      if (hashes == null) eventHash(index, adId, eventType)
      else hashes(index) ^ (eventType.toLong * 1099511628211L)
  }

  private lazy val inputData: InputData = loadInput()

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

  private def adFor(index: Int): Int =
    mix(index * 1103515245 + 12345) % YahooAdRegionConfig.adSpace

  private def eventTypeFor(index: Int): Int =
    mix(index * 1664525 + 1013904223) % 4

  private def valueFor(index: Int): Int =
    1 + (mix(index * 8191 + 17) % 64)

  private def eventHash(index: Int, adId: Int, eventType: Int): Long =
    mix(index * 1000003 + adId * 8191 + eventType * 131).toLong

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = YahooAdRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartEvent: Long): Long = {
    val cfg = YahooAdRegionConfig
    currentStartEvent -
      (cfg.liveBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def loadInput(): InputData = {
    val cfg = YahooAdRegionConfig
    if (cfg.inputPath.isEmpty)
      return new InputData(
        "generated-yahoo-ad-shaped",
        cfg.events,
        null,
        null,
        null,
        null
      )

    val ads = scala.collection.mutable.ArrayBuffer.empty[Int]
    val eventTypes = scala.collection.mutable.ArrayBuffer.empty[Int]
    val values = scala.collection.mutable.ArrayBuffer.empty[Int]
    val hashes = scala.collection.mutable.ArrayBuffer.empty[Long]
    val reader = BenchmarkInputSupport.openText(cfg.inputPath)

    def field(line: String, key: String): String = {
      val marker = "\"" + key + "\""
      val start = line.indexOf(marker)
      if (start < 0) ""
      else {
        val colon = line.indexOf(':', start + marker.length)
        if (colon < 0) ""
        else {
          var i = colon + 1
          while (i < line.length && (line.charAt(i) == ' ' || line.charAt(i) == '"'))
            i += 1
          val begin = i
          while (
            i < line.length &&
            line.charAt(i) != ',' &&
            line.charAt(i) != '}' &&
            line.charAt(i) != '"'
          ) i += 1
          line.substring(begin, i)
        }
      }
    }

    try {
      var line = reader.readLine()
      while (line != null && ads.length < cfg.events) {
        if (line.nonEmpty) {
          val adText = {
            val parsed = field(line, "ad_id")
            if (parsed.nonEmpty) parsed else line
          }
          val eventText = {
            val parsed = field(line, "event_type")
            if (parsed.nonEmpty) parsed else "view"
          }
          val valueText = field(line, "value")
          val ad =
            BenchmarkInputSupport.positiveModulo(
              BenchmarkInputSupport.stableHash(adText),
              cfg.adSpace
            )
          val eventType =
            if (eventText == "view" || eventText == "0") 0
            else BenchmarkInputSupport.positiveModulo(
              BenchmarkInputSupport.stableHash(eventText),
              4
            )
          val value = BenchmarkInputSupport.parseInt(valueText, 1)
          ads += ad
          eventTypes += eventType
          values += (if (value > 0) value else 1)
          hashes += BenchmarkInputSupport.stableHash(line).toLong
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }

    if (ads.isEmpty)
      throw new IllegalArgumentException(
        s"Yahoo ad input '${cfg.inputPath}' did not contain usable rows"
      )

    new InputData(
      "real-yahoo-ad-preloaded",
      ads.length,
      ads.toArray,
      eventTypes.toArray,
      values.toArray,
      hashes.toArray
    )
  }

  private def fold(
      checksum: Long,
      kind: Int,
      timestamp: Long,
      adId: Int,
      campaignId: Int,
      eventType: Int,
      value: Int,
      hash: Long,
      bucketStartEvent: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ timestamp
    h = (h * 1099511628211L) ^ adId.toLong
    h = (h * 1099511628211L) ^ campaignId.toLong
    h = (h * 1099511628211L) ^ eventType.toLong
    h = (h * 1099511628211L) ^ value.toLong
    h ^ hash ^ bucketStartEvent
  }

  private def appendEvent(bucket: HeapBucket, event: HeapEvent): Unit =
    if (bucket.head == null) {
      bucket.head = event
      bucket.tail = event
    } else {
      bucket.tail.next = event
      bucket.tail = event
    }

  private def appendEvent(bucket: SafeBucket, event: SafeEvent): Unit =
    if (bucket.head == null) {
      bucket.head = event
      bucket.tail = event
    } else {
      bucket.tail.next = event
      bucket.tail = event
    }

  private def appendEvent(bucket: TrustedBucket, event: TrustedEvent): Unit =
    if (bucket.head == null) {
      bucket.head = event
      bucket.tail = event
    } else {
      bucket.tail.next = event
      bucket.tail = event
    }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown Yahoo ad mode '$other'")
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-filter" | "q2-campaign-window" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown Yahoo ad query '$other'")
    }

  private def runHeap(query: String): RunOutcome = {
    val cfg = YahooAdRegionConfig
    val input = inputData
    val counts =
      if (query == "q2-campaign-window") new Array[Int](cfg.campaignSpace)
      else null
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: HeapBucket, event: HeapEvent): Unit =
      if (query == "q2-campaign-window" && event.kind == 22) {
        counts(event.campaignId) -= 1
        checksum = fold(
          checksum,
          event.kind + 40,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          counts(event.campaignId),
          event.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          event.kind,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          event.value,
          event.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }

    def closeExpired(cutoffEvent: Long): Unit =
      while (
        first != null &&
        first.startEvent + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val bucket = first
        var event = bucket.head
        while (event != null) {
          consume(bucket, event)
          event = event.next
        }
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        bucket.head = null
        bucket.tail = null
        bucket.next = null
      }

    def bucketFor(startEvent: Long): HeapBucket =
      if (current != null && current.startEvent == startEvent) current
      else {
        closeExpired(closeCutoff(startEvent))
        val bucket = new HeapBucket(startEvent, null)
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
    while (i < input.events) {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val ad = input.adAt(i)
      val campaign = input.campaignAt(ad)
      val eventType = input.eventTypeAt(i)
      val value = input.valueAt(i)
      val hash = input.hashAt(i, ad, eventType)

      query match {
        case "q0-parse" =>
          appendEvent(bucket, new HeapEvent(10, i.toLong, ad, campaign, eventType, value, hash, null))
        case "q1-filter" =>
          appendEvent(bucket, new HeapEvent(10, i.toLong, ad, campaign, eventType, value, hash, null))
          if (eventType == 0)
            appendEvent(bucket, new HeapEvent(21, i.toLong, ad, campaign, eventType, value, hash, null))
        case "q2-campaign-window" =>
          if (eventType == 0) {
            counts(campaign) += 1
            appendEvent(bucket, new HeapEvent(22, i.toLong, ad, campaign, eventType, value, hash, null))
            checksum = fold(checksum, 32, i.toLong, ad, campaign, eventType, counts(campaign), hash, start)
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

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = YahooAdRegionConfig
    val input = inputData
    val counts =
      if (query == "q2-campaign-window") new Array[Int](cfg.campaignSpace)
      else null
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: SafeBucket, event: SafeEvent): Unit =
      if (query == "q2-campaign-window" && event.kind == 22) {
        counts(event.campaignId) -= 1
        checksum = fold(
          checksum,
          event.kind + 40,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          counts(event.campaignId),
          event.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          event.kind,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          event.value,
          event.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }

    def closeBucket(bucket: SafeBucket): Unit = {
      var event = bucket.head
      while (event != null) {
        consume(bucket, event)
        event = event.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      SafeZone.close(bucket.zone)
    }

    def closeExpired(cutoffEvent: Long): Unit =
      while (
        first != null &&
        first.startEvent + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startEvent: Long): SafeBucket =
      if (current != null && current.startEvent == startEvent) current
      else {
        closeExpired(closeCutoff(startEvent))
        val bucket = new SafeBucket(SafeZone.open(), startEvent, null)
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
      while (i < input.events) {
        val start = bucketStart(i)
        val bucket = bucketFor(start)
        val zone = bucket.zone
        val ad = input.adAt(i)
        val campaign = input.campaignAt(ad)
        val eventType = input.eventTypeAt(i)
        val value = input.valueAt(i)
        val hash = input.hashAt(i, ad, eventType)

        query match {
          case "q0-parse" =>
            appendEvent(
              bucket,
              SafeZoneAllocator
                .allocate(zone, new SafeEvent(10, i.toLong, ad, campaign, eventType, value, hash, null))
                .asInstanceOf[SafeEvent]
            )
          case "q1-filter" =>
            appendEvent(
              bucket,
              SafeZoneAllocator
                .allocate(zone, new SafeEvent(10, i.toLong, ad, campaign, eventType, value, hash, null))
                .asInstanceOf[SafeEvent]
            )
            if (eventType == 0)
              appendEvent(
                bucket,
                SafeZoneAllocator
                  .allocate(zone, new SafeEvent(21, i.toLong, ad, campaign, eventType, value, hash, null))
                  .asInstanceOf[SafeEvent]
              )
          case "q2-campaign-window" =>
            if (eventType == 0) {
              counts(campaign) += 1
              appendEvent(
                bucket,
                SafeZoneAllocator
                  .allocate(zone, new SafeEvent(22, i.toLong, ad, campaign, eventType, value, hash, null))
                  .asInstanceOf[SafeEvent]
              )
              checksum = fold(checksum, 32, i.toLong, ad, campaign, eventType, counts(campaign), hash, start)
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
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftTrusted(query: String, kind: Int): RunOutcome = {
    val cfg = YahooAdRegionConfig
    val input = inputData
    val counts =
      if (query == "q2-campaign-window") new Array[Int](cfg.campaignSpace)
      else null
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: TrustedBucket, event: TrustedEvent): Unit =
      if (query == "q2-campaign-window" && event.kind == 22) {
        counts(event.campaignId) -= 1
        checksum = fold(
          checksum,
          event.kind + 40,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          counts(event.campaignId),
          event.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          event.kind,
          event.timestamp,
          event.adId,
          event.campaignId,
          event.eventType,
          event.value,
          event.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }

    def closeBucket(bucket: TrustedBucket): Unit = {
      var event = bucket.head
      while (event != null) {
        consume(bucket, event)
        event = event.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      bucket.region.close()
    }

    def closeExpired(cutoffEvent: Long): Unit =
      while (
        first != null &&
        first.startEvent + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startEvent: Long): TrustedBucket =
      if (current != null && current.startEvent == startEvent) current
      else {
        closeExpired(closeCutoff(startEvent))
        val bucket = new TrustedBucket(RiftRegion.open(kind), startEvent, null)
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
      while (i < input.events) {
        val start = bucketStart(i)
        val bucket = bucketFor(start)
        val region = bucket.region
        val ad = input.adAt(i)
        val campaign = input.campaignAt(ad)
        val eventType = input.eventTypeAt(i)
        val value = input.valueAt(i)
        val hash = input.hashAt(i, ad, eventType)

        query match {
          case "q0-parse" =>
            appendEvent(bucket, region.alloc(new TrustedEvent(10, i.toLong, ad, campaign, eventType, value, hash, null)))
          case "q1-filter" =>
            appendEvent(bucket, region.alloc(new TrustedEvent(10, i.toLong, ad, campaign, eventType, value, hash, null)))
            if (eventType == 0)
              appendEvent(bucket, region.alloc(new TrustedEvent(21, i.toLong, ad, campaign, eventType, value, hash, null)))
          case "q2-campaign-window" =>
            if (eventType == 0) {
              counts(campaign) += 1
              appendEvent(bucket, region.alloc(new TrustedEvent(22, i.toLong, ad, campaign, eventType, value, hash, null)))
              checksum = fold(checksum, 32, i.toLong, ad, campaign, eventType, counts(campaign), hash, start)
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
        throw new IllegalArgumentException(s"unknown Yahoo ad mode '$other'")
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = YahooAdRegionConfig
    val input = inputData
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
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(s"Running yahoo-ad-$query-$mode for ${cfg.benchmarkRuns} timed runs")

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
    val medianOpens = medianLong(riftOpens)
    val medianCloses = medianLong(riftCloses)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=yahoo-ad-$query-$mode " +
        f"query=$query mode=$mode input=${input.label} " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"max_gc_ms=${maxGc / 1000000.0}%.3f " +
        f"runs_with_gc=$runsWithGc%d " +
        f"max_gc_collections=$maxGcCollections%d " +
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
    val cfg = YahooAdRegionConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode query=$query events=${input.events} configured_events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} campaign_space=${cfg.campaignSpace} ads_per_campaign=${cfg.adsPerCampaign} sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=${input.label} input_path=${cfg.inputPath}"
    )
  }
}

@main def YahooAdRegionMatrix(
    mode: String = "heap",
    query: String = "q2-campaign-window"
): Unit = {
  YahooAdRegionMatrixHelpers.validateMode(mode)
  YahooAdRegionMatrixHelpers.validateQuery(query)
  YahooAdRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    YahooAdRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
