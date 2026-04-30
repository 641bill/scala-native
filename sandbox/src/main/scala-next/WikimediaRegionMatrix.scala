import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object WikimediaRegionConfig {
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

  val events: Int = envInt("WIKIMEDIA_EVENTS", 100000)
  val eventsPerBucket: Int = envInt("WIKIMEDIA_EVENTS_PER_BUCKET", 2500)
  val liveBuckets: Int = envInt("WIKIMEDIA_LIVE_BUCKETS", 4)
  val projectSpace: Int = envInt("WIKIMEDIA_PROJECT_SPACE", 64)
  val articleSpace: Int = envInt("WIKIMEDIA_ARTICLE_SPACE", 65536)
  val sampleEvery: Int = envInt("WIKIMEDIA_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("WIKIMEDIA_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("WIKIMEDIA_BENCHMARK_RUNS", 3)
}

object WikimediaRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapEvent(
      val kind: Int,
      val timestamp: Long,
      val project: Int,
      val article: Int,
      val peer: Int,
      val value: Int,
      val bytes: Long,
      val hash: Long,
      var next: HeapEvent
  )

  private final class HeapBucket(
      val startEvent: Long,
      var next: HeapBucket
  ) {
    var head: HeapEvent = null
    var tail: HeapEvent = null
  }

  private final class SafeEvent(
      val kind: Int,
      val timestamp: Long,
      val project: Int,
      val article: Int,
      val peer: Int,
      val value: Int,
      val bytes: Long,
      val hash: Long,
      var next: SafeEvent
  )

  private final class SafeBucket(
      val zone: SafeZone,
      val startEvent: Long,
      var next: SafeBucket
  ) {
    var head: SafeEvent = null
    var tail: SafeEvent = null
  }

  private final class TrustedEvent(
      val kind: Int,
      val timestamp: Long,
      val project: Int,
      val article: Int,
      val peer: Int,
      val value: Int,
      val bytes: Long,
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
    val cfg = WikimediaRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartEvent: Long): Long = {
    val cfg = WikimediaRegionConfig
    currentStartEvent -
      (cfg.liveBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def projectFor(eventIndex: Int): Int =
    mix(eventIndex * 1103515245 + 12345) % WikimediaRegionConfig.projectSpace

  private def articleFor(eventIndex: Int): Int =
    mix(eventIndex * 1000003 + 8191) % WikimediaRegionConfig.articleSpace

  private def peerFor(eventIndex: Int): Int =
    mix(eventIndex * 9176 + 131071) % WikimediaRegionConfig.articleSpace

  private def viewsFor(eventIndex: Int): Int =
    1 + (mix(eventIndex * 3571 + 53) % 64)

  private def bytesFor(eventIndex: Int): Long =
    128L + (mix(eventIndex * 104729 + 17) % 8192).toLong

  private def eventHash(
      kind: Int,
      eventIndex: Int,
      project: Int,
      article: Int,
      peer: Int
  ): Long =
    mix(eventIndex * 1000003 + kind * 8191 + project * 131 + article + peer)
      .toLong

  private def fold(
      checksum: Long,
      kind: Int,
      timestamp: Long,
      project: Int,
      article: Int,
      peer: Int,
      value: Int,
      bytes: Long,
      hash: Long,
      bucketStartEvent: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ timestamp
    h = (h * 1099511628211L) ^ project.toLong
    h = (h * 1099511628211L) ^ article.toLong
    h = (h * 1099511628211L) ^ peer.toLong
    h = (h * 1099511628211L) ^ value.toLong
    h = (h * 1099511628211L) ^ bytes
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
        throw new IllegalArgumentException(
          s"unknown Wikimedia mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-pageviews" | "q1-counts" | "q2-clickstream" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Wikimedia query '$other'"
        )
    }

  private def extraRecords(query: String): Int =
    query match {
      case "q0-pageviews"   => 0
      case "q1-counts"      => 1
      case "q2-clickstream" => 1
    }

  private def runHeap(query: String): RunOutcome = {
    val cfg = WikimediaRegionConfig
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: HeapBucket, event: HeapEvent): Unit = {
      checksum = fold(
        checksum,
        event.kind,
        event.timestamp,
        event.project,
        event.article,
        event.peer,
        event.value,
        event.bytes,
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

    var eventIndex = 0
    while (eventIndex < cfg.events) {
      val project = projectFor(eventIndex)
      val article = articleFor(eventIndex)
      val peer = peerFor(eventIndex)
      val views = viewsFor(eventIndex)
      val bytes = bytesFor(eventIndex)
      val start = bucketStart(eventIndex)
      val bucket = bucketFor(start)
      val timestamp = eventIndex.toLong

      appendEvent(
        bucket,
        new HeapEvent(
          1,
          timestamp,
          project,
          article,
          0,
          views,
          bytes,
          eventHash(1, eventIndex, project, article, 0),
          null
        )
      )

      if (query == "q1-counts") {
        appendEvent(
          bucket,
          new HeapEvent(
            2,
            timestamp,
            project,
            article,
            0,
            views,
            bytes,
            eventHash(2, eventIndex, project, article, 0),
            null
          )
        )
      } else if (query == "q2-clickstream") {
        appendEvent(
          bucket,
          new HeapEvent(
            3,
            timestamp,
            project,
            article,
            peer,
            1,
            bytes,
            eventHash(3, eventIndex, project, article, peer),
            null
          )
        )
      }

      if (eventIndex % cfg.sampleEvery == 0)
        checksum = fold(
          checksum,
          9,
          timestamp,
          project,
          article,
          peer,
          extraRecords(query),
          bytes,
          eventHash(9, eventIndex, project, article, peer),
          bucket.startEvent
        )

      eventIndex += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = WikimediaRegionConfig
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: SafeBucket, event: SafeEvent): Unit = {
      checksum = fold(
        checksum,
        event.kind,
        event.timestamp,
        event.project,
        event.article,
        event.peer,
        event.value,
        event.bytes,
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

    var eventIndex = 0
    try {
      while (eventIndex < cfg.events) {
        val project = projectFor(eventIndex)
        val article = articleFor(eventIndex)
        val peer = peerFor(eventIndex)
        val views = viewsFor(eventIndex)
        val bytes = bytesFor(eventIndex)
        val start = bucketStart(eventIndex)
        val bucket = bucketFor(start)
        val zone = bucket.zone
        val timestamp = eventIndex.toLong

        appendEvent(
          bucket,
          SafeZoneAllocator
            .allocate(
              zone,
              new SafeEvent(
                1,
                timestamp,
                project,
                article,
                0,
                views,
                bytes,
                eventHash(1, eventIndex, project, article, 0),
                null
              )
            )
            .asInstanceOf[SafeEvent]
        )

        if (query == "q1-counts") {
          appendEvent(
            bucket,
            SafeZoneAllocator
              .allocate(
                zone,
                new SafeEvent(
                  2,
                  timestamp,
                  project,
                  article,
                  0,
                  views,
                  bytes,
                  eventHash(2, eventIndex, project, article, 0),
                  null
                )
              )
              .asInstanceOf[SafeEvent]
          )
        } else if (query == "q2-clickstream") {
          appendEvent(
            bucket,
            SafeZoneAllocator
              .allocate(
                zone,
                new SafeEvent(
                  3,
                  timestamp,
                  project,
                  article,
                  peer,
                  1,
                  bytes,
                  eventHash(3, eventIndex, project, article, peer),
                  null
                )
              )
              .asInstanceOf[SafeEvent]
          )
        }

        if (eventIndex % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            9,
            timestamp,
            project,
            article,
            peer,
            extraRecords(query),
            bytes,
            eventHash(9, eventIndex, project, article, peer),
            bucket.startEvent
          )

        eventIndex += 1
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
    val cfg = WikimediaRegionConfig
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: TrustedBucket, event: TrustedEvent): Unit = {
      checksum = fold(
        checksum,
        event.kind,
        event.timestamp,
        event.project,
        event.article,
        event.peer,
        event.value,
        event.bytes,
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

    var eventIndex = 0
    try {
      while (eventIndex < cfg.events) {
        val project = projectFor(eventIndex)
        val article = articleFor(eventIndex)
        val peer = peerFor(eventIndex)
        val views = viewsFor(eventIndex)
        val bytes = bytesFor(eventIndex)
        val start = bucketStart(eventIndex)
        val bucket = bucketFor(start)
        val region = bucket.region
        val timestamp = eventIndex.toLong

        appendEvent(
          bucket,
          region.alloc(
            new TrustedEvent(
              1,
              timestamp,
              project,
              article,
              0,
              views,
              bytes,
              eventHash(1, eventIndex, project, article, 0),
              null
            )
          )
        )

        if (query == "q1-counts") {
          appendEvent(
            bucket,
            region.alloc(
              new TrustedEvent(
                2,
                timestamp,
                project,
                article,
                0,
                views,
                bytes,
                eventHash(2, eventIndex, project, article, 0),
                null
              )
            )
          )
        } else if (query == "q2-clickstream") {
          appendEvent(
            bucket,
            region.alloc(
              new TrustedEvent(
                3,
                timestamp,
                project,
                article,
                peer,
                1,
                bytes,
                eventHash(3, eventIndex, project, article, peer),
                null
              )
            )
          )
        }

        if (eventIndex % cfg.sampleEvery == 0)
          checksum = fold(
            checksum,
            9,
            timestamp,
            project,
            article,
            peer,
            extraRecords(query),
            bytes,
            eventHash(9, eventIndex, project, article, peer),
            bucket.startEvent
          )

        eventIndex += 1
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
      case "heap"           => runHeap(query)
      case "safezone"       => runSafeZone(query)
      case "rift-hp"        => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Wikimedia mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = WikimediaRegionConfig
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
      s"Running wikimedia-$query-$mode for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=wikimedia-$query-$mode " +
        f"query=$query mode=$mode input=generated-tsv-shaped " +
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
    val cfg = WikimediaRegionConfig
    println(
      s"CONFIG mode=$mode query=$query events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} project_space=${cfg.projectSpace} article_space=${cfg.articleSpace} sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=generated-tsv-shaped"
    )
  }
}

@main def WikimediaRegionMatrix(
    mode: String = "heap",
    query: String = "q1-counts"
): Unit = {
  WikimediaRegionMatrixHelpers.validateMode(mode)
  WikimediaRegionMatrixHelpers.validateQuery(query)
  WikimediaRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    WikimediaRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
