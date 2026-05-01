import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

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

  private def envFlag(name: String): Boolean =
    sys.env.get(name).exists { value =>
      value == "1" || value.equalsIgnoreCase("true") ||
        value.equalsIgnoreCase("yes")
    }

  val beamDefaults: Boolean = envFlag("NEXMARK_BEAM_DEFAULTS")
  private def defaultInt(local: Int, beam: Int): Int =
    if (beamDefaults) beam else local

  val events: Int = envInt("NEXMARK_EVENTS", defaultInt(1000000, 100000))
  val eventsPerBucket: Int =
    envInt("NEXMARK_EVENTS_PER_BUCKET", defaultInt(25000, 10000))
  val windowBuckets: Int = envInt("NEXMARK_WINDOW_BUCKETS", defaultInt(8, 10))
  val auctionSpace: Int = envInt("NEXMARK_AUCTION_SPACE", defaultInt(65536, 100))
  val personSpace: Int = envInt("NEXMARK_PERSON_SPACE", defaultInt(65536, 1000))
  val categorySpace: Int = envInt("NEXMARK_CATEGORY_SPACE", defaultInt(64, 5))
  val q2SelectModulo: Int = envInt("NEXMARK_Q2_SELECT_MODULO", 128)
  val sampleEvery: Int = envInt("NEXMARK_SAMPLE_EVERY", 8192)
  val warmupRuns: Int = envNonNegativeInt("NEXMARK_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("NEXMARK_BENCHMARK_RUNS", 3)
  val q5Diagnostics: Boolean = envFlag("NEXMARK_Q5_DIAG")
  val beamSourcePath: String =
    BenchmarkInputSupport.envString("NEXMARK_BEAM_SOURCE")
  val inputLabel: String =
    if (beamDefaults) "beam-defaults-generated" else "generated-local"
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

  private final class SafeZoneRecord(
      val kind: Int,
      val id: Int,
      val key: Int,
      var value: Int,
      var price: Long,
      val timestamp: Long,
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

  private final class Q5Diag(val enabled: Boolean, val mode: String) {
    var windowAdds = 0L
    var windowRemoves = 0L
    var closedBuckets = 0L
    var samples = 0L
    var topScans = 0L
    var topScanEntries = 0L
    var topScanNanos = 0L

    def recordAdd(): Unit =
      if (enabled) windowAdds += 1L

    def recordRemove(): Unit =
      if (enabled) windowRemoves += 1L

    def recordClosedBucket(): Unit =
      if (enabled) closedBuckets += 1L

    def recordSample(): Unit =
      if (enabled) samples += 1L

    def recordTopScan(entries: Int, nanos: Long): Unit =
      if (enabled) {
        topScans += 1L
        topScanEntries += entries.toLong
        topScanNanos += nanos
      }

    def print(query: String, events: Int): Unit =
      if (enabled) {
        val live = windowAdds - windowRemoves
        val scansPerEvent =
          if (events == 0) 0.0 else topScans.toDouble / events.toDouble
        val scanEntriesPerEvent =
          if (events == 0) 0.0 else topScanEntries.toDouble / events.toDouble
        println(
          f"NEXMARK_Q5_DIAG query=$query mode=$mode " +
            f"window_adds=$windowAdds%d " +
            f"window_removes=$windowRemoves%d " +
            f"closed_buckets=$closedBuckets%d " +
            f"samples=$samples%d " +
            f"top_scans=$topScans%d " +
            f"top_scan_entries=$topScanEntries%d " +
            f"top_scan_ms=${topScanNanos / 1000000.0}%.3f " +
            f"top_scans_per_event=$scansPerEvent%.6f " +
            f"top_scan_entries_per_event=$scanEntriesPerEvent%.3f " +
            f"final_live_records=$live%d"
        )
      }
  }

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

  private def personId(index: Int): Int =
    (index / 10) % NexmarkRegionConfig.personSpace

  private def auctionSeller(index: Int): Int =
    ((index - 1) / 10) % NexmarkRegionConfig.personSpace

  private def bidderId(index: Int): Int =
    mix(index * 1664525 + 1013904223) % NexmarkRegionConfig.personSpace

  private def price(index: Int): Long =
    ((mix(index * 8191 + 17) & 0xffff) + 100).toLong

  private def category(index: Int): Int =
    mix(index * 131 + 53) % NexmarkRegionConfig.categorySpace

  private def allowedSeller(person: Int, category: Int): Int =
    if ((person % 5) == 0 || category == 0 || category == 2) 1 else 0

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

  private def topAuctionProfiled(
      counts: Array[Int],
      sums: Array[Long],
      diag: Q5Diag
  ): Int =
    if (!diag.enabled) topAuction(counts, sums)
    else {
      val start = System.nanoTime()
      val result = topAuction(counts, sums)
      diag.recordTopScan(counts.length, System.nanoTime() - start)
      result
    }

  private def q5Diag(
      query: String,
      mode: String,
      emitDiagnostics: Boolean
  ): Q5Diag =
    new Q5Diag(
      query == "q5" && emitDiagnostics && NexmarkRegionConfig.q5Diagnostics,
      mode
    )

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

  private def appendRecord(
      bucket: SafeZoneBucket,
      record: SafeZoneRecord
  ): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  def runHeap(query: String, emitDiagnostics: Boolean = true): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val diag = q5Diag(query, "heap", emitDiagnostics)
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    val q8Persons =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q8Auctions =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q3Allowed =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q3Auctions =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q4Counts =
      if (query == "q4") new Array[Int](cfg.categorySpace) else null
    val q4Sums =
      if (query == "q4") new Array[Long](cfg.categorySpace) else null
    val q9Max =
      if (query == "q9") new Array[Long](cfg.auctionSpace) else null
    val q11Counts =
      if (query == "q11") new Array[Int](cfg.personSpace) else null
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(bucket: HeapBucket, record: HeapRecord): Unit =
      if (query == "q5") {
        diag.recordRemove()
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
      } else if (query == "q8" && record.kind == 18) {
        q8Persons(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q8" && record.kind == 19) {
        q8Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 13) {
        q3Allowed(record.key) -= record.value
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Allowed(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 14) {
        q3Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Auctions(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q4" && record.kind == 24) {
        q4Counts(record.key) -= 1
        q4Sums(record.key) -= record.price
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q4Counts(record.key),
          q4Sums(record.key),
          bucket.startSeconds
        )
      } else if (query == "q9" && record.kind == 39) {
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          record.value,
          q9Max(record.key),
          bucket.startSeconds
        )
      } else if (query == "q11" && record.kind == 31) {
        q11Counts(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q11Counts(record.key),
          record.price,
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
        if (query == "q5") diag.recordClosedBucket()
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

        case "q3" =>
          eventKind(i) match {
            case 0 =>
              val id = personId(i)
              val allowed = allowedSeller(id, category(i))
              q3Allowed(id) += allowed
              appendRecord(
                bucket,
                new HeapRecord(13, i, id, allowed, price(i), i.toLong, null)
              )
            case 1 =>
              val seller = auctionSeller(i)
              q3Auctions(seller) += 1
              appendRecord(
                bucket,
                new HeapRecord(14, i, seller, auctionId(i), price(i), i.toLong, null)
              )
              if (q3Allowed(seller) > 0) {
                val out =
                  new HeapRecord(
                    23,
                    i,
                    seller,
                    q3Allowed(seller),
                    q3Auctions(seller).toLong,
                    i.toLong,
                    null
                  )
                appendRecord(bucket, out)
              }
            case _ => ()
          }

        case "q4" =>
          val cat = category(i)
          val bidPrice = price(i)
          q4Counts(cat) += 1
          q4Sums(cat) += bidPrice
          appendRecord(
            bucket,
            new HeapRecord(24, i, cat, q4Counts(cat), bidPrice, i.toLong, null)
          )
          if (i % cfg.sampleEvery == 0) {
            val avg = q4Sums(cat) / q4Counts(cat).toLong
            checksum = fold(checksum, 44, i, cat, q4Counts(cat), avg, startSeconds)
            outputCount += 1L
          }

        case "q5" =>
          val auction = auctionId(i)
          val bidPrice = price(i)
          val bid =
            new HeapRecord(15, i, auction, bidderId(i), bidPrice, i.toLong, null)
          counts(auction) += 1
          sums(auction) += bidPrice
          diag.recordAdd()
          appendRecord(bucket, bid)
          if (i % cfg.sampleEvery == 0) {
            diag.recordSample()
            val hot = topAuctionProfiled(counts, sums, diag)
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

        case "q8" =>
          eventKind(i) match {
            case 0 =>
              val id = personId(i)
              val person =
                new HeapRecord(18, i, id, category(i), price(i), i.toLong, null)
              q8Persons(id) += 1
              appendRecord(bucket, person)
              if (q8Auctions(id) > 0) {
                val out =
                  new HeapRecord(
                    28,
                    i,
                    id,
                    q8Persons(id),
                    q8Auctions(id).toLong,
                    i.toLong,
                    null
                  )
                appendRecord(bucket, out)
              }
            case 1 =>
              val seller = auctionSeller(i)
              val auction =
                new HeapRecord(19, i, seller, auctionId(i), price(i), i.toLong, null)
              q8Auctions(seller) += 1
              appendRecord(bucket, auction)
              if (q8Persons(seller) > 0) {
                val out =
                  new HeapRecord(
                    28,
                    i,
                    seller,
                    q8Persons(seller),
                    q8Auctions(seller).toLong,
                    i.toLong,
                    null
                  )
                appendRecord(bucket, out)
              }
            case _ =>
              ()
          }

        case "q9" =>
          val auction = auctionId(i)
          val bidPrice = price(i)
          val bid = new HeapRecord(39, i, auction, bidderId(i), bidPrice, i.toLong, null)
          appendRecord(bucket, bid)
          if (bidPrice >= q9Max(auction)) {
            q9Max(auction) = bidPrice
            appendRecord(
              bucket,
              new HeapRecord(49, i, auction, bid.value, bidPrice, i.toLong, null)
            )
          }

        case "q11" =>
          val bidder = bidderId(i)
          q11Counts(bidder) += 1
          appendRecord(
            bucket,
            new HeapRecord(31, i, bidder, q11Counts(bidder), price(i), i.toLong, null)
          )
          if ((q11Counts(bidder) & 3) == 1) {
            appendRecord(
              bucket,
              new HeapRecord(41, i, bidder, q11Counts(bidder), price(i), i.toLong, null)
            )
          }
      }
      i += 1
    }

    closeExpired(Long.MaxValue)
    diag.print(query, cfg.events)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runHeapJoinApi(query: String): RunOutcome = {
    if (query != "q8")
      throw new IllegalArgumentException(
        "heap-join-api currently supports only NEXMark q8"
      )

    val cfg = NexmarkRegionConfig
    val leftCounts = new Array[Int](cfg.personSpace)
    val rightCounts = new Array[Int](cfg.personSpace)
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(bucket: HeapBucket, record: HeapRecord): Unit =
      if (record.kind == 18) {
        leftCounts(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          leftCounts(record.key),
          rightCounts(record.key).toLong,
          bucket.startSeconds
        )
      } else if (record.kind == 19) {
        rightCounts(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          leftCounts(record.key),
          rightCounts(record.key).toLong,
          bucket.startSeconds
        )
      } else {
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
      eventKind(i) match {
        case 0 =>
          val id = personId(i)
          val person =
            new HeapRecord(18, i, id, category(i), price(i), i.toLong, null)
          leftCounts(id) += 1
          appendRecord(bucket, person)
          val right = rightCounts(id)
          if (right > 0) {
            val out =
              new HeapRecord(
                28,
                i,
                id,
                leftCounts(id),
                right.toLong,
                i.toLong,
                null
              )
            appendRecord(bucket, out)
          }
        case 1 =>
          val seller = auctionSeller(i)
          val auction =
            new HeapRecord(
              19,
              i,
              seller,
              auctionId(i),
              price(i),
              i.toLong,
              null
            )
          rightCounts(seller) += 1
          appendRecord(bucket, auction)
          val left = leftCounts(seller)
          if (left > 0) {
            val out =
              new HeapRecord(
                28,
                i,
                seller,
                left,
                rightCounts(seller).toLong,
                i.toLong,
                null
              )
            appendRecord(bucket, out)
          }
        case _ =>
          ()
      }
      i += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftTrusted(
      query: String,
      kind: Int,
      mode: String,
      emitDiagnostics: Boolean = true
  ): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val diag = q5Diag(query, mode, emitDiagnostics)
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    val q8Persons =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q8Auctions =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q3Allowed =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q3Auctions =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q4Counts =
      if (query == "q4") new Array[Int](cfg.categorySpace) else null
    val q4Sums =
      if (query == "q4") new Array[Long](cfg.categorySpace) else null
    val q9Max =
      if (query == "q9") new Array[Long](cfg.auctionSpace) else null
    val q11Counts =
      if (query == "q11") new Array[Int](cfg.personSpace) else null
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(bucket: TrustedBucket, record: TrustedRecord): Unit =
      if (query == "q5") {
        diag.recordRemove()
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
      } else if (query == "q8" && record.kind == 18) {
        q8Persons(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q8" && record.kind == 19) {
        q8Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 13) {
        q3Allowed(record.key) -= record.value
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Allowed(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 14) {
        q3Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Auctions(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q4" && record.kind == 24) {
        q4Counts(record.key) -= 1
        q4Sums(record.key) -= record.price
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q4Counts(record.key),
          q4Sums(record.key),
          bucket.startSeconds
        )
      } else if (query == "q9" && record.kind == 39) {
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          record.value,
          q9Max(record.key),
          bucket.startSeconds
        )
      } else if (query == "q11" && record.kind == 31) {
        q11Counts(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q11Counts(record.key),
          record.price,
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
      if (query == "q5") diag.recordClosedBucket()
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

          case "q3" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val allowed = allowedSeller(id, category(i))
                q3Allowed(id) += allowed
                appendRecord(
                  bucket,
                  region.alloc(
                    new TrustedRecord(13, i, id, allowed, price(i), i.toLong, null)
                  )
                )
              case 1 =>
                val seller = auctionSeller(i)
                q3Auctions(seller) += 1
                appendRecord(
                  bucket,
                  region.alloc(
                    new TrustedRecord(
                      14,
                      i,
                      seller,
                      auctionId(i),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                )
                if (q3Allowed(seller) > 0) {
                  appendRecord(
                    bucket,
                    region.alloc(
                      new TrustedRecord(
                        23,
                        i,
                        seller,
                        q3Allowed(seller),
                        q3Auctions(seller).toLong,
                        i.toLong,
                        null
                      )
                    )
                  )
                }
              case _ => ()
            }

          case "q4" =>
            val cat = category(i)
            val bidPrice = price(i)
            q4Counts(cat) += 1
            q4Sums(cat) += bidPrice
            appendRecord(
              bucket,
              region.alloc(
                new TrustedRecord(24, i, cat, q4Counts(cat), bidPrice, i.toLong, null)
              )
            )
            if (i % cfg.sampleEvery == 0) {
              val avg = q4Sums(cat) / q4Counts(cat).toLong
              checksum = fold(checksum, 44, i, cat, q4Counts(cat), avg, startSeconds)
              outputCount += 1L
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
            diag.recordAdd()
            appendRecord(bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              diag.recordSample()
              val hot = topAuctionProfiled(counts, sums, diag)
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

          case "q8" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val person =
                  region.alloc(
                    new TrustedRecord(
                      18,
                      i,
                      id,
                      category(i),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                q8Persons(id) += 1
                appendRecord(bucket, person)
                if (q8Auctions(id) > 0) {
                  val out =
                    region.alloc(
                      new TrustedRecord(
                        28,
                        i,
                        id,
                        q8Persons(id),
                        q8Auctions(id).toLong,
                        i.toLong,
                        null
                      )
                    )
                  appendRecord(bucket, out)
                }
              case 1 =>
                val seller = auctionSeller(i)
                val auction =
                  region.alloc(
                    new TrustedRecord(
                      19,
                      i,
                      seller,
                      auctionId(i),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                q8Auctions(seller) += 1
                appendRecord(bucket, auction)
                if (q8Persons(seller) > 0) {
                  val out =
                    region.alloc(
                      new TrustedRecord(
                        28,
                        i,
                        seller,
                        q8Persons(seller),
                        q8Auctions(seller).toLong,
                        i.toLong,
                        null
                      )
                    )
                  appendRecord(bucket, out)
                }
              case _ =>
                ()
            }

          case "q9" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid =
              region.alloc(
                new TrustedRecord(39, i, auction, bidderId(i), bidPrice, i.toLong, null)
              )
            appendRecord(bucket, bid)
            if (bidPrice >= q9Max(auction)) {
              q9Max(auction) = bidPrice
              appendRecord(
                bucket,
                region.alloc(
                  new TrustedRecord(49, i, auction, bid.value, bidPrice, i.toLong, null)
                )
              )
            }

          case "q11" =>
            val bidder = bidderId(i)
            q11Counts(bidder) += 1
            appendRecord(
              bucket,
              region.alloc(
                new TrustedRecord(
                  31,
                  i,
                  bidder,
                  q11Counts(bidder),
                  price(i),
                  i.toLong,
                  null
                )
              )
            )
            if ((q11Counts(bidder) & 3) == 1) {
              appendRecord(
                bucket,
                region.alloc(
                  new TrustedRecord(
                    41,
                    i,
                    bidder,
                    q11Counts(bidder),
                    price(i),
                    i.toLong,
                    null
                  )
                )
              )
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

    diag.print(query, cfg.events)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runSafeZone(
      query: String,
      emitDiagnostics: Boolean = true
  ): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val diag = q5Diag(query, "safezone", emitDiagnostics)
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    val q8Persons =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q8Auctions =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q3Allowed =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q3Auctions =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q4Counts =
      if (query == "q4") new Array[Int](cfg.categorySpace) else null
    val q4Sums =
      if (query == "q4") new Array[Long](cfg.categorySpace) else null
    val q9Max =
      if (query == "q9") new Array[Long](cfg.auctionSpace) else null
    val q11Counts =
      if (query == "q11") new Array[Int](cfg.personSpace) else null
    var first: SafeZoneBucket = null
    var last: SafeZoneBucket = null
    var current: SafeZoneBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeRecord(
        bucket: SafeZoneBucket,
        record: SafeZoneRecord
    ): Unit =
      if (query == "q5") {
        diag.recordRemove()
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
      } else if (query == "q8" && record.kind == 18) {
        q8Persons(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q8" && record.kind == 19) {
        q8Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q8Persons(record.key),
          q8Auctions(record.key).toLong,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 13) {
        q3Allowed(record.key) -= record.value
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Allowed(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q3" && record.kind == 14) {
        q3Auctions(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q3Auctions(record.key),
          record.price,
          bucket.startSeconds
        )
      } else if (query == "q4" && record.kind == 24) {
        q4Counts(record.key) -= 1
        q4Sums(record.key) -= record.price
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q4Counts(record.key),
          q4Sums(record.key),
          bucket.startSeconds
        )
      } else if (query == "q9" && record.kind == 39) {
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          record.value,
          q9Max(record.key),
          bucket.startSeconds
        )
      } else if (query == "q11" && record.kind == 31) {
        q11Counts(record.key) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.id,
          record.key,
          q11Counts(record.key),
          record.price,
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

    def closeBucket(bucket: SafeZoneBucket): Unit = {
      if (query == "q5") diag.recordClosedBucket()
      var record = bucket.head
      while (record != null) {
        consumeRecord(bucket, record)
        record = record.next
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
        closeBucket(bucket)
      }

    def bucketFor(startSeconds: Long): SafeZoneBucket =
      if (current != null && current.startSeconds == startSeconds) current
      else {
        closeExpired(closeCutoff(startSeconds))
        val bucket = new SafeZoneBucket(SafeZone.open(), startSeconds, null)
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
        val zone = bucket.zone
        query match {
          case "q0" =>
            val kind = eventKind(i)
            val key = if (kind == 2) auctionId(i) else bidderId(i)
            val record =
              SafeZoneAllocator
                .allocate(
                zone,
                new SafeZoneRecord(
                  kind,
                  i,
                  key,
                  category(i),
                  price(i),
                  i.toLong,
                  null
                )
              )
                .asInstanceOf[SafeZoneRecord]
            appendRecord(bucket, record)

          case "q1" =>
            val bid =
              SafeZoneAllocator
                .allocate(
                zone,
                new SafeZoneRecord(
                  2,
                  i,
                  auctionId(i),
                  bidderId(i),
                  price(i),
                  i.toLong,
                  null
                )
              )
                .asInstanceOf[SafeZoneRecord]
            appendRecord(bucket, bid)
            val convertedPrice = (bid.price * 89L) / 100L
            val out =
              SafeZoneAllocator
                .allocate(
                zone,
                new SafeZoneRecord(
                  11,
                  bid.id,
                  bid.key,
                  bid.value,
                  convertedPrice,
                  bid.timestamp,
                  null
                )
              )
                .asInstanceOf[SafeZoneRecord]
            out.value += (convertedPrice & 7L).toInt
            appendRecord(bucket, out)

          case "q2" =>
            val bid =
              SafeZoneAllocator
                .allocate(
                zone,
                new SafeZoneRecord(
                  2,
                  i,
                  auctionId(i),
                  bidderId(i),
                  price(i),
                  i.toLong,
                  null
                )
              )
                .asInstanceOf[SafeZoneRecord]
            appendRecord(bucket, bid)
            if ((bid.key % cfg.q2SelectModulo) == 0) {
              val out =
                SafeZoneAllocator
                  .allocate(
                  zone,
                  new SafeZoneRecord(
                    12,
                    bid.id,
                    bid.key,
                    bid.value,
                    bid.price,
                    bid.timestamp,
                    null
                  )
                )
                  .asInstanceOf[SafeZoneRecord]
              appendRecord(bucket, out)
            }

          case "q3" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val allowed = allowedSeller(id, category(i))
                q3Allowed(id) += allowed
                appendRecord(
                  bucket,
                  SafeZoneAllocator
                    .allocate(
                      zone,
                      new SafeZoneRecord(13, i, id, allowed, price(i), i.toLong, null)
                    )
                    .asInstanceOf[SafeZoneRecord]
                )
              case 1 =>
                val seller = auctionSeller(i)
                q3Auctions(seller) += 1
                appendRecord(
                  bucket,
                  SafeZoneAllocator
                    .allocate(
                      zone,
                      new SafeZoneRecord(
                        14,
                        i,
                        seller,
                        auctionId(i),
                        price(i),
                        i.toLong,
                        null
                      )
                    )
                    .asInstanceOf[SafeZoneRecord]
                )
                if (q3Allowed(seller) > 0) {
                  appendRecord(
                    bucket,
                    SafeZoneAllocator
                      .allocate(
                        zone,
                        new SafeZoneRecord(
                          23,
                          i,
                          seller,
                          q3Allowed(seller),
                          q3Auctions(seller).toLong,
                          i.toLong,
                          null
                        )
                      )
                      .asInstanceOf[SafeZoneRecord]
                  )
                }
              case _ => ()
            }

          case "q4" =>
            val cat = category(i)
            val bidPrice = price(i)
            q4Counts(cat) += 1
            q4Sums(cat) += bidPrice
            appendRecord(
              bucket,
              SafeZoneAllocator
                .allocate(
                  zone,
                  new SafeZoneRecord(24, i, cat, q4Counts(cat), bidPrice, i.toLong, null)
                )
                .asInstanceOf[SafeZoneRecord]
            )
            if (i % cfg.sampleEvery == 0) {
              val avg = q4Sums(cat) / q4Counts(cat).toLong
              checksum = fold(checksum, 44, i, cat, q4Counts(cat), avg, startSeconds)
              outputCount += 1L
            }

          case "q5" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid =
              SafeZoneAllocator
                .allocate(
                zone,
                new SafeZoneRecord(
                  15,
                  i,
                  auction,
                  bidderId(i),
                  bidPrice,
                  i.toLong,
                  null
                )
              )
                .asInstanceOf[SafeZoneRecord]
            counts(auction) += 1
            sums(auction) += bidPrice
            diag.recordAdd()
            appendRecord(bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              diag.recordSample()
              val hot = topAuctionProfiled(counts, sums, diag)
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

          case "q8" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val person =
                  SafeZoneAllocator
                    .allocate(
                    zone,
                    new SafeZoneRecord(
                      18,
                      i,
                      id,
                      category(i),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                    .asInstanceOf[SafeZoneRecord]
                q8Persons(id) += 1
                appendRecord(bucket, person)
                if (q8Auctions(id) > 0) {
                  val out =
                    SafeZoneAllocator
                      .allocate(
                      zone,
                      new SafeZoneRecord(
                        28,
                        i,
                        id,
                        q8Persons(id),
                        q8Auctions(id).toLong,
                        i.toLong,
                        null
                      )
                    )
                      .asInstanceOf[SafeZoneRecord]
                  appendRecord(bucket, out)
                }
              case 1 =>
                val seller = auctionSeller(i)
                val auction =
                  SafeZoneAllocator
                    .allocate(
                    zone,
                    new SafeZoneRecord(
                      19,
                      i,
                      seller,
                      auctionId(i),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                    .asInstanceOf[SafeZoneRecord]
                q8Auctions(seller) += 1
                appendRecord(bucket, auction)
                if (q8Persons(seller) > 0) {
                  val out =
                    SafeZoneAllocator
                      .allocate(
                      zone,
                      new SafeZoneRecord(
                        28,
                        i,
                        seller,
                        q8Persons(seller),
                        q8Auctions(seller).toLong,
                        i.toLong,
                        null
                      )
                    )
                      .asInstanceOf[SafeZoneRecord]
                  appendRecord(bucket, out)
                }
              case _ =>
                ()
            }

          case "q9" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid =
              SafeZoneAllocator
                .allocate(
                  zone,
                  new SafeZoneRecord(39, i, auction, bidderId(i), bidPrice, i.toLong, null)
                )
                .asInstanceOf[SafeZoneRecord]
            appendRecord(bucket, bid)
            if (bidPrice >= q9Max(auction)) {
              q9Max(auction) = bidPrice
              appendRecord(
                bucket,
                SafeZoneAllocator
                  .allocate(
                    zone,
                    new SafeZoneRecord(49, i, auction, bid.value, bidPrice, i.toLong, null)
                  )
                  .asInstanceOf[SafeZoneRecord]
              )
            }

          case "q11" =>
            val bidder = bidderId(i)
            q11Counts(bidder) += 1
            appendRecord(
              bucket,
              SafeZoneAllocator
                .allocate(
                  zone,
                  new SafeZoneRecord(
                    31,
                    i,
                    bidder,
                    q11Counts(bidder),
                    price(i),
                    i.toLong,
                    null
                  )
                )
                .asInstanceOf[SafeZoneRecord]
            )
            if ((q11Counts(bidder) & 3) == 1) {
              appendRecord(
                bucket,
                SafeZoneAllocator
                  .allocate(
                    zone,
                    new SafeZoneRecord(
                      41,
                      i,
                      bidder,
                      q11Counts(bidder),
                      price(i),
                      i.toLong,
                      null
                    )
                  )
                  .asInstanceOf[SafeZoneRecord]
              )
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

    diag.print(query, cfg.events)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftChecked(
      query: String,
      emitDiagnostics: Boolean = true
  ): RunOutcome = {
    val cfg = NexmarkRegionConfig
    val diag = q5Diag(query, "rift-checked", emitDiagnostics)
    val counts = if (query == "q5") new Array[Int](cfg.auctionSpace) else null
    val sums = if (query == "q5") new Array[Long](cfg.auctionSpace) else null
    val q8Persons =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q8Auctions =
      if (query == "q8") new Array[Int](cfg.personSpace) else null
    val q3Allowed =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q3Auctions =
      if (query == "q3") new Array[Int](cfg.personSpace) else null
    val q4Counts =
      if (query == "q4") new Array[Int](cfg.categorySpace) else null
    val q4Sums =
      if (query == "q4") new Array[Long](cfg.categorySpace) else null
    val q9Max =
      if (query == "q9") new Array[Long](cfg.auctionSpace) else null
    val q11Counts =
      if (query == "q11") new Array[Int](cfg.personSpace) else null
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
            diag.recordRemove()
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
          } else if (query == "q8" && record.kind == 18) {
            q8Persons(record.key) -= 1
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q8Persons(record.key),
              q8Auctions(record.key).toLong,
              bucket.startSeconds
            )
          } else if (query == "q8" && record.kind == 19) {
            q8Auctions(record.key) -= 1
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q8Persons(record.key),
              q8Auctions(record.key).toLong,
              bucket.startSeconds
            )
          } else if (query == "q3" && record.kind == 13) {
            q3Allowed(record.key) -= record.value
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q3Allowed(record.key),
              record.price,
              bucket.startSeconds
            )
          } else if (query == "q3" && record.kind == 14) {
            q3Auctions(record.key) -= 1
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q3Auctions(record.key),
              record.price,
              bucket.startSeconds
            )
          } else if (query == "q4" && record.kind == 24) {
            q4Counts(record.key) -= 1
            q4Sums(record.key) -= record.price
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q4Counts(record.key),
              q4Sums(record.key),
              bucket.startSeconds
            )
          } else if (query == "q9" && record.kind == 39) {
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              record.value,
              q9Max(record.key),
              bucket.startSeconds
            )
          } else if (query == "q11" && record.kind == 31) {
            q11Counts(record.key) -= 1
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              q11Counts(record.key),
              record.price,
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
          if (query == "q5") diag.recordClosedBucket()
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

          case "q3" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val allowed = allowedSeller(id, category(i))
                q3Allowed(id) += allowed
                val person: Record^{stream} =
                  RiftRegion.alloc(
                    new Record(13, i, id, allowed, price(i), i.toLong)
                  )(using bucketRegion)
                RiftRegion.appendWindow(stream, window, bucket, person)
              case 1 =>
                val seller = auctionSeller(i)
                q3Auctions(seller) += 1
                val auction: Record^{stream} =
                  RiftRegion.alloc(
                    new Record(14, i, seller, auctionId(i), price(i), i.toLong)
                  )(using bucketRegion)
                RiftRegion.appendWindow(stream, window, bucket, auction)
                if (q3Allowed(seller) > 0) {
                  val out: Record^{stream} =
                    RiftRegion.alloc(
                      new Record(
                        23,
                        i,
                        seller,
                        q3Allowed(seller),
                        q3Auctions(seller).toLong,
                        i.toLong
                      )
                    )(using bucketRegion)
                  RiftRegion.appendWindow(stream, window, bucket, out)
                }
              case _ => ()
            }

          case "q4" =>
            val cat = category(i)
            val bidPrice = price(i)
            q4Counts(cat) += 1
            q4Sums(cat) += bidPrice
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(24, i, cat, q4Counts(cat), bidPrice, i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              val avg = q4Sums(cat) / q4Counts(cat).toLong
              running = fold(running, 44, i, cat, q4Counts(cat), avg, startSeconds)
              outputs += 1L
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
            diag.recordAdd()
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if (i % cfg.sampleEvery == 0) {
              diag.recordSample()
              val hot = topAuctionProfiled(counts, sums, diag)
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

          case "q8" =>
            eventKind(i) match {
              case 0 =>
                val id = personId(i)
                val person: Record^{stream} =
                  RiftRegion.alloc(
                    new Record(18, i, id, category(i), price(i), i.toLong)
                  )(using bucketRegion)
                q8Persons(id) += 1
                RiftRegion.appendWindow(stream, window, bucket, person)
                if (q8Auctions(id) > 0) {
                  val out: Record^{stream} =
                    RiftRegion.alloc(
                      new Record(
                        28,
                        i,
                        id,
                        q8Persons(id),
                        q8Auctions(id).toLong,
                        i.toLong
                      )
                    )(using bucketRegion)
                  RiftRegion.appendWindow(stream, window, bucket, out)
                }
              case 1 =>
                val seller = auctionSeller(i)
                val auction: Record^{stream} =
                  RiftRegion.alloc(
                    new Record(
                      19,
                      i,
                      seller,
                      auctionId(i),
                      price(i),
                      i.toLong
                    )
                  )(using bucketRegion)
                q8Auctions(seller) += 1
                RiftRegion.appendWindow(stream, window, bucket, auction)
                if (q8Persons(seller) > 0) {
                  val out: Record^{stream} =
                    RiftRegion.alloc(
                      new Record(
                        28,
                        i,
                        seller,
                        q8Persons(seller),
                        q8Auctions(seller).toLong,
                        i.toLong
                      )
                    )(using bucketRegion)
                  RiftRegion.appendWindow(stream, window, bucket, out)
                }
              case _ =>
                ()
            }

          case "q9" =>
            val auction = auctionId(i)
            val bidPrice = price(i)
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(39, i, auction, bidderId(i), bidPrice, i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if (bidPrice >= q9Max(auction)) {
              q9Max(auction) = bidPrice
              val out: Record^{stream} =
                RiftRegion.alloc(
                  new Record(49, i, auction, bid.value, bidPrice, i.toLong)
                )(using bucketRegion)
              RiftRegion.appendWindow(stream, window, bucket, out)
            }

          case "q11" =>
            val bidder = bidderId(i)
            q11Counts(bidder) += 1
            val bid: Record^{stream} =
              RiftRegion.alloc(
                new Record(31, i, bidder, q11Counts(bidder), price(i), i.toLong)
              )(using bucketRegion)
            RiftRegion.appendWindow(stream, window, bucket, bid)
            if ((q11Counts(bidder) & 3) == 1) {
              val out: Record^{stream} =
                RiftRegion.alloc(
                  new Record(41, i, bidder, q11Counts(bidder), price(i), i.toLong)
                )(using bucketRegion)
              RiftRegion.appendWindow(stream, window, bucket, out)
            }
        }
        i += 1
      }

      RiftRegion.closeAllAppendWindowBucketsWithCursor(stream, window) {
        (bucket, cursor) =>
          if (query == "q5") diag.recordClosedBucket()
          consume(bucket, cursor)
      }
      outputCount = outputs
      running
    }
    diag.print(query, cfg.events)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  def runRiftCheckedJoinApi(query: String): RunOutcome = {
    if (query != "q8")
      throw new IllegalArgumentException(
        "rift-checked-join-api currently supports only NEXMark q8"
      )

    val cfg = NexmarkRegionConfig
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

      val join =
        RiftRegion.streamJoinWindow[Record](
          cfg.eventsPerBucket.toLong,
          cfg.personSpace
        )
      var running = 0L
      var outputs = 0L

      def consume(
          bucket: RiftRegion.StreamBucket^{stream},
          cursor: RiftRegion.StreamAppendCursor[Record]^{stream}
      ): Unit =
        while (cursor.hasNext) {
          val record: Record^{stream} = cursor.next()
          if (record.kind == 18) {
            val counts =
              RiftRegion.removeJoinLeftAndCounts(stream, join, record.key)
            val left = (counts >>> 32).toInt
            val right = counts.toInt
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              left,
              right.toLong,
              bucket.startSeconds
            )
          } else if (record.kind == 19) {
            val counts =
              RiftRegion.removeJoinRightAndCounts(stream, join, record.key)
            val left = (counts >>> 32).toInt
            val right = counts.toInt
            running = fold(
              running,
              record.kind + 40,
              record.id,
              record.key,
              left,
              right.toLong,
              bucket.startSeconds
            )
          } else {
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
          }
        }

      def closeExpired(cutoffSeconds: Long): Unit =
        RiftRegion.closeJoinWindowBucketsBeforeWithCursor(
          stream,
          join,
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
            RiftRegion.streamJoinWindowBucketFor(stream, join, startSeconds)
          currentBucketRegion =
            RiftRegion.streamBucketRegion(stream, currentBucket)
        }
        val bucket = currentBucket
        val bucketRegion = currentBucketRegion

        eventKind(i) match {
          case 0 =>
            val id = personId(i)
            val person: Record^{stream} =
              RiftRegion.alloc(
                new Record(18, i, id, category(i), price(i), i.toLong)
              )(using bucketRegion)
            val counts =
              RiftRegion.putJoinLeftInBucketAndCounts(
                stream,
                join,
                bucket,
                id,
                person
              )
            val left = (counts >>> 32).toInt
            val right = counts.toInt
            if (right > 0) {
              val out: Record^{stream} =
                RiftRegion.alloc(
                  new Record(28, i, id, left, right.toLong, i.toLong)
                )(using bucketRegion)
              RiftRegion.putJoinOutputInBucket(stream, join, bucket, out)
            }
          case 1 =>
            val seller = auctionSeller(i)
            val auction: Record^{stream} =
              RiftRegion.alloc(
                new Record(
                  19,
                  i,
                  seller,
                  auctionId(i),
                  price(i),
                  i.toLong
                )
              )(using bucketRegion)
            val counts =
              RiftRegion.putJoinRightInBucketAndCounts(
                stream,
                join,
                bucket,
                seller,
                auction
              )
            val left = (counts >>> 32).toInt
            val right = counts.toInt
            if (left > 0) {
              val out: Record^{stream} =
                RiftRegion.alloc(
                  new Record(28, i, seller, left, right.toLong, i.toLong)
                )(using bucketRegion)
              RiftRegion.putJoinOutputInBucket(stream, join, bucket, out)
            }
          case _ =>
            ()
        }
        i += 1
      }

      RiftRegion.closeAllJoinWindowBucketsWithCursor(stream, join) {
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

  private def runMode(
      mode: String,
      query: String,
      emitDiagnostics: Boolean = true
  ): RunOutcome =
    mode match {
      case "heap" => runHeap(query, emitDiagnostics)
      case "safezone" =>
        runSafeZone(query, emitDiagnostics)
      case "heap-join-api" =>
        runHeapJoinApi(query)
      case "rift-checked" =>
        runRiftChecked(query, emitDiagnostics)
      case "rift-checked-join-api" =>
        runRiftCheckedJoinApi(query)
      case "rift-hp" =>
        runRiftTrusted(query, RiftRegion.HPZone, mode, emitDiagnostics)
      case "rift-streaming" =>
        runRiftTrusted(query, RiftRegion.Streaming, mode, emitDiagnostics)
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark mode '$other'")
    }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "heap-join-api" | "rift-checked" |
          "rift-checked-join-api" | "rift-hp" | "rift-streaming" =>
        ()
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark mode '$other'")
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0" | "q1" | "q2" | "q3" | "q4" | "q5" | "q8" | "q9" |
          "q11" =>
        ()
      case other =>
        throw new IllegalArgumentException(s"unknown NEXMark query '$other'")
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = NexmarkRegionConfig
    val usesRift =
      mode != "heap" && mode != "safezone" && mode != "heap-join-api"
    val expected = runHeap(query, emitDiagnostics = false)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode, query, emitDiagnostics = false)
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

    println(
      s"Running nexmark-$query-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val outcome = runMode(mode, query, emitDiagnostics = true)
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
      f"RESULT name=nexmark-$query-$mode " +
        f"query=$query mode=$mode input=${cfg.inputLabel} " +
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
    val cfg = NexmarkRegionConfig
    println(
      s"CONFIG mode=$mode query=$query runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} window_buckets=${cfg.windowBuckets} auction_space=${cfg.auctionSpace} person_space=${cfg.personSpace} category_space=${cfg.categorySpace} q2_select_modulo=${cfg.q2SelectModulo} sample_every=${cfg.sampleEvery} q5_diag=${cfg.q5Diagnostics} input=${cfg.inputLabel} beam_defaults=${cfg.beamDefaults} beam_source=${cfg.beamSourcePath}"
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

  val usesRift =
    mode != "heap" && mode != "safezone" && mode != "heap-join-api"
  if (usesRift) RiftRegion.init(0)
  try {
    NexmarkRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
