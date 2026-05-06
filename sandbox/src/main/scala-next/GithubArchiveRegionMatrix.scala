import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object GithubArchiveRegionConfig {
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

  val events: Int = envInt("GITHUB_ARCHIVE_EVENTS", 100000)
  val eventsPerBucket: Int =
    envInt("GITHUB_ARCHIVE_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("GITHUB_ARCHIVE_LIVE_BUCKETS", 4)
  val repoBuckets: Int = envInt("GITHUB_ARCHIVE_REPO_BUCKETS", 4096)
  val fieldLimit: Int = envInt("GITHUB_ARCHIVE_FIELD_LIMIT", 12)
  val sampleEvery: Int = envInt("GITHUB_ARCHIVE_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("GITHUB_ARCHIVE_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("GITHUB_ARCHIVE_BENCHMARK_RUNS", 3)
  private val inputPathsRaw: String = {
    val multiple = BenchmarkInputSupport.envString("GITHUB_ARCHIVE_INPUTS")
    if (multiple.nonEmpty) multiple
    else BenchmarkInputSupport.envString("GITHUB_ARCHIVE_INPUT")
  }
  val inputPaths: Array[String] =
    if (inputPathsRaw.isEmpty) Array.empty
    else inputPathsRaw.split(",").map(_.trim).filter(_.nonEmpty)
  val inputPath: String = inputPaths.mkString(",")
  val inputMode: String = {
    val raw = BenchmarkInputSupport.envString("GITHUB_ARCHIVE_INPUT_MODE")
    if (raw.isEmpty) "preloaded" else raw
  }
  val fileBackedInput: Boolean =
    inputMode match {
      case "preloaded"   => false
      case "file-backed" => true
      case other =>
        throw new IllegalArgumentException(
          s"unknown GITHUB_ARCHIVE_INPUT_MODE '$other'; expected preloaded or file-backed"
        )
    }
}

object GithubArchiveRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapRecord(
      val kind: Int,
      val eventIndex: Int,
      val repoBucket: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: HeapRecord
  )

  private final class HeapBucket(val startEvent: Long, var next: HeapBucket) {
    var head: HeapRecord = null
    var tail: HeapRecord = null
  }

  private final class SafeRecord(
      val kind: Int,
      val eventIndex: Int,
      val repoBucket: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: SafeRecord
  )

  private final class SafeBucket(
      val zone: SafeZone,
      val startEvent: Long,
      var next: SafeBucket
  ) {
    var head: SafeRecord = null
    var tail: SafeRecord = null
  }

  private final class TrustedRecord(
      val kind: Int,
      val eventIndex: Int,
      val repoBucket: Int,
      val eventType: Int,
      val value: Int,
      val hash: Long,
      var next: TrustedRecord
  )

  private final class TrustedBucket(
      val region: RiftRegion,
      val startEvent: Long,
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

  private final class InputData(
      val label: String,
      val events: Int,
      val inputFiles: Int,
      val repoBuckets: Array[Int],
      val eventTypes: Array[Int],
      val fieldCounts: Array[Int],
      val hashes: Array[Long]
  ) {
    def repoAt(index: Int): Int =
      if (repoBuckets == null) repoFor(index) else repoBuckets(index)

    def eventTypeAt(index: Int): Int =
      if (eventTypes == null) eventTypeFor(index) else eventTypes(index)

    def fieldCountAt(index: Int): Int =
      if (fieldCounts == null) fieldCountFor(index) else fieldCounts(index)

    def hashAt(index: Int, eventType: Int): Long =
      if (hashes == null) eventHash(index, eventType)
      else hashes(index) ^ (eventType.toLong * 1099511628211L)
  }

  private lazy val inputData: InputData = loadInput()

  private abstract class EventConsumer {
    def apply(
        eventIndex: Int,
        repoBucket: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit
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
    x ^= x >>> 16
    x *= 0x7feb352d
    x ^= x >>> 15
    x *= 0x846ca68b
    x ^= x >>> 16
    x & 0x7fffffff
  }

  private def medianDouble(values: Array[Double]): Double = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
  }

  private def medianLong(values: Array[Long]): Long = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
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

  private def repoFor(index: Int): Int =
    mix(index * 1103515245 + 12345) % GithubArchiveRegionConfig.repoBuckets

  private def eventTypeFor(index: Int): Int =
    mix(index * 1664525 + 1013904223) % 12

  private def fieldCountFor(index: Int): Int =
    1 + (mix(index * 8191 + 17) % GithubArchiveRegionConfig.fieldLimit)

  private def eventHash(index: Int, eventType: Int): Long =
    mix(index * 1000003 + eventType * 131 + 53).toLong

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = GithubArchiveRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartEvent: Long): Long = {
    val cfg = GithubArchiveRegionConfig
    currentStartEvent -
      (cfg.liveBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def stringFieldFrom(line: String, key: String, from: Int): String = {
    val marker = "\"" + key + "\""
    val start = line.indexOf(marker, math.max(0, from))
    if (start < 0) ""
    else {
      val colon = line.indexOf(':', start + marker.length)
      if (colon < 0) ""
      else {
        var i = colon + 1
        while (i < line.length && line.charAt(i) == ' ') i += 1
        if (i >= line.length || line.charAt(i) != '"') ""
        else {
          i += 1
          val begin = i
          var escaped = false
          while (i < line.length) {
            val c = line.charAt(i)
            if (escaped) escaped = false
            else if (c == '\\') escaped = true
            else if (c == '"') return line.substring(begin, i)
            i += 1
          }
          ""
        }
      }
    }
  }

  private def eventTypeId(eventType: String): Int =
    eventType match {
      case "PushEvent"         => 0
      case "PullRequestEvent"  => 1
      case "IssuesEvent"       => 2
      case "IssueCommentEvent" => 3
      case "WatchEvent"        => 4
      case "ForkEvent"         => 5
      case "CreateEvent"       => 6
      case "DeleteEvent"       => 7
      case "ReleaseEvent"      => 8
      case "PublicEvent"       => 9
      case other =>
        BenchmarkInputSupport.positiveModulo(
          BenchmarkInputSupport.stableHash(other),
          16
        )
    }

  private def countJsonFields(line: String, limit: Int): Int = {
    var count = 0
    var i = 0
    while (i + 2 < line.length && count < limit) {
      if (line.charAt(i) == '"' && line.charAt(i + 1) != ':') {
        var j = i + 1
        var escaped = false
        var done = false
        while (j < line.length && !done) {
          val c = line.charAt(j)
          if (escaped) escaped = false
          else if (c == '\\') escaped = true
          else if (c == '"') done = true
          j += 1
        }
        var k = j
        while (k < line.length && line.charAt(k) == ' ') k += 1
        if (k < line.length && line.charAt(k) == ':') count += 1
        i = k
      }
      i += 1
    }
    if (count == 0) 1 else count
  }

  private def countFileBackedInputRows(): Int = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.inputPath.isEmpty)
      throw new IllegalArgumentException(
        "GITHUB_ARCHIVE_INPUT_MODE=file-backed requires GITHUB_ARCHIVE_INPUT or GITHUB_ARCHIVE_INPUTS"
      )

    var count = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && count < cfg.events) {
      val reader = BenchmarkInputSupport.openText(cfg.inputPaths(pathIndex))
      try {
        var line = reader.readLine()
        while (line != null && count < cfg.events) {
          if (line.nonEmpty) count += 1
          line = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }
    count
  }

  private def foreachFileBackedEvent(consumer: EventConsumer^): Int = {
    val cfg = GithubArchiveRegionConfig
    var index = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && index < cfg.events) {
      val reader = BenchmarkInputSupport.openText(cfg.inputPaths(pathIndex))
      try {
        var line = reader.readLine()
        while (line != null && index < cfg.events) {
          if (line.nonEmpty) {
            val eventTypeText = stringFieldFrom(line, "type", 0)
            val repoIndex = line.indexOf("\"repo\"")
            val actorIndex = line.indexOf("\"actor\"")
            val repoText = stringFieldFrom(line, "name", repoIndex)
            val actorText = stringFieldFrom(line, "login", actorIndex)
            val repoHash =
              if (repoText.nonEmpty) BenchmarkInputSupport.stableHash(repoText)
              else BenchmarkInputSupport.stableHash(line)
            val actorHash =
              if (actorText.nonEmpty) BenchmarkInputSupport.stableHash(actorText)
              else 0
            val repoBucket =
              BenchmarkInputSupport.positiveModulo(repoHash, cfg.repoBuckets)
            val eventType = eventTypeId(eventTypeText)
            val fields = countJsonFields(line, cfg.fieldLimit)
            val hash =
              BenchmarkInputSupport.stableHash(line).toLong ^
                (actorHash.toLong << 17) ^
                (eventType.toLong * 1099511628211L)
            consumer(index, repoBucket, eventType, fields, hash)
            index += 1
          }
          line = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }
    index
  }

  private def loadInput(): InputData = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.fileBackedInput) {
      val rows = countFileBackedInputRows()
      if (rows == 0)
        throw new IllegalArgumentException(
          s"GH Archive input '${cfg.inputPath}' did not contain usable rows"
        )
      return new InputData(
        if (cfg.inputPaths.length == 1) "real-gharchive-file-backed"
        else s"real-gharchive-file-backed-${cfg.inputPaths.length}files",
        rows,
        cfg.inputPaths.length,
        null,
        null,
        null,
        null
      )
    }

    if (cfg.inputPath.isEmpty)
      return new InputData(
        "generated-gharchive-shaped",
        cfg.events,
        0,
        null,
        null,
        null,
        null
      )

    val repos = scala.collection.mutable.ArrayBuffer.empty[Int]
    val eventTypes = scala.collection.mutable.ArrayBuffer.empty[Int]
    val fieldCounts = scala.collection.mutable.ArrayBuffer.empty[Int]
    val hashes = scala.collection.mutable.ArrayBuffer.empty[Long]

    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && repos.length < cfg.events) {
      val path = cfg.inputPaths(pathIndex)
      val reader = BenchmarkInputSupport.openText(path)

      try {
        var line = reader.readLine()
        while (line != null && repos.length < cfg.events) {
          if (line.nonEmpty) {
            val eventTypeText = stringFieldFrom(line, "type", 0)
            val repoIndex = line.indexOf("\"repo\"")
            val actorIndex = line.indexOf("\"actor\"")
            val repoText = stringFieldFrom(line, "name", repoIndex)
            val actorText = stringFieldFrom(line, "login", actorIndex)
            val repoHash =
              if (repoText.nonEmpty) BenchmarkInputSupport.stableHash(repoText)
              else BenchmarkInputSupport.stableHash(line)
            val actorHash =
              if (actorText.nonEmpty) BenchmarkInputSupport.stableHash(actorText)
              else 0
            repos += BenchmarkInputSupport.positiveModulo(
              repoHash,
              cfg.repoBuckets
            )
            eventTypes += eventTypeId(eventTypeText)
            fieldCounts += countJsonFields(line, cfg.fieldLimit)
            hashes +=
              (BenchmarkInputSupport.stableHash(line).toLong ^
                (actorHash.toLong << 17))
          }
          line = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }

    if (repos.isEmpty)
      throw new IllegalArgumentException(
        s"GH Archive input '${cfg.inputPath}' did not contain usable rows"
      )

    new InputData(
      if (cfg.inputPaths.length == 1) "real-gharchive-preloaded"
      else s"real-gharchive-preloaded-${cfg.inputPaths.length}files",
      repos.length,
      cfg.inputPaths.length,
      repos.toArray,
      eventTypes.toArray,
      fieldCounts.toArray,
      hashes.toArray
    )
  }

  private def fieldQuery(query: String): Boolean =
    query == "q1-fields" || query == "q2-repo-window"

  private def windowQuery(query: String): Boolean =
    query == "q2-repo-window"

  private def fold(
      checksum: Long,
      kind: Int,
      eventIndex: Int,
      repoBucket: Int,
      eventType: Int,
      value: Int,
      hash: Long,
      bucketStartEvent: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ eventIndex.toLong
    h = (h * 1099511628211L) ^ repoBucket.toLong
    h = (h * 1099511628211L) ^ eventType.toLong
    h = (h * 1099511628211L) ^ value.toLong
    h ^ hash ^ bucketStartEvent
  }

  private def appendRecord(bucket: HeapBucket, record: HeapRecord): Unit =
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

  private def appendRecord(bucket: TrustedBucket, record: TrustedRecord): Unit =
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked-page-token" | "rift-checked-safezone-page-token" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown GH Archive mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-events" | "q1-fields" | "q2-repo-window" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown GH Archive query '$other'"
        )
    }

  private def runHeap(query: String): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: HeapBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.repoBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.repoBucket) += 1
          record = record.next
        }
        var repo = 0
        while (repo < counts.length) {
          val count = counts(repo)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startEvent.toInt,
              repo,
              0,
              count,
              (repo.toLong << 32) ^ count.toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          repo += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.repoBucket,
            record.eventType,
            record.value,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }

    def closeExpired(cutoffEvent: Long): Unit =
      while (
        first != null &&
        first.startEvent + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val bucket = first
        consumeBucket(bucket)
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

    def processEvent(
        i: Int,
        repo: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendRecord(bucket, new HeapRecord(10, i, repo, eventType, fields, hash, null))
      if (fieldQuery(query)) {
        var field = 0
        while (field < fields) {
          appendRecord(
            bucket,
            new HeapRecord(
              20 + (field & 3),
              i,
              repo,
              eventType,
              field,
              hash ^ (field.toLong * 1315423911L),
              null
            )
          )
          field += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
    }

    if (cfg.fileBackedInput) {
      foreachFileBackedEvent(new EventConsumer {
        def apply(
            i: Int,
            repo: Int,
            eventType: Int,
            fields: Int,
            hash: Long
        ): Unit =
          processEvent(i, repo, eventType, fields, hash)
      })
    } else {
      var i = 0
      while (i < input.events) {
        val repo = input.repoAt(i)
        val eventType = input.eventTypeAt(i)
        val fields = input.fieldCountAt(i)
        val hash = input.hashAt(i, eventType)
        processEvent(i, repo, eventType, fields, hash)
        i += 1
      }
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runExpected(query: String): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.fileBackedInput)
      return runHeap(query)

    val input = inputData
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(startEvent: Long): Unit = {
      val begin = startEvent.toInt
      val end = math.min(input.events, begin + cfg.eventsPerBucket)
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.repoBuckets)
        var i = begin
        while (i < end) {
          counts(input.repoAt(i)) += 1 + input.fieldCountAt(i)
          i += 1
        }
        var repo = 0
        while (repo < counts.length) {
          val count = counts(repo)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              startEvent.toInt,
              repo,
              0,
              count,
              (repo.toLong << 32) ^ count.toLong,
              startEvent
            )
            outputCount += 1L
          }
          repo += 1
        }
      } else {
        var i = begin
        while (i < end) {
          val repo = input.repoAt(i)
          val eventType = input.eventTypeAt(i)
          val fields = input.fieldCountAt(i)
          val hash = input.hashAt(i, eventType)
          checksum = fold(
            checksum,
            10,
            i,
            repo,
            eventType,
            fields,
            hash,
            startEvent
          )
          outputCount += 1L
          if (fieldQuery(query)) {
            var field = 0
            while (field < fields) {
              checksum = fold(
                checksum,
                20 + (field & 3),
                i,
                repo,
                eventType,
                field,
                hash ^ (field.toLong * 1315423911L),
                startEvent
              )
              outputCount += 1L
              field += 1
            }
          }
          i += 1
        }
      }
    }

    var nextCloseStart = 0L
    var currentStart = Long.MinValue
    var i = 0
    while (i < input.events) {
      val start = bucketStart(i)
      if (start != currentStart) {
        currentStart = start
        val cutoff = closeCutoff(start)
        while (
          nextCloseStart < input.events &&
          nextCloseStart + cfg.eventsPerBucket.toLong <= cutoff
        ) {
          consumeBucket(nextCloseStart)
          nextCloseStart += cfg.eventsPerBucket.toLong
        }
      }
      val repo = input.repoAt(i)
      val eventType = input.eventTypeAt(i)
      val fields = input.fieldCountAt(i)
      val hash = input.hashAt(i, eventType)
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
      i += 1
    }

    while (nextCloseStart < input.events) {
      consumeBucket(nextCloseStart)
      nextCloseStart += cfg.eventsPerBucket.toLong
    }

    RunOutcome(checksum, outputCount)
  }

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: SafeBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.repoBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.repoBucket) += 1
          record = record.next
        }
        var repo = 0
        while (repo < counts.length) {
          val count = counts(repo)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startEvent.toInt,
              repo,
              0,
              count,
              (repo.toLong << 32) ^ count.toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          repo += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.repoBucket,
            record.eventType,
            record.value,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }

    def closeBucket(bucket: SafeBucket): Unit = {
      consumeBucket(bucket)
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

    def processEvent(
        i: Int,
        repo: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendRecord(
        bucket,
        SafeZoneAllocator
          .allocate(bucket.zone, new SafeRecord(10, i, repo, eventType, fields, hash, null))
          .asInstanceOf[SafeRecord]
      )
      if (fieldQuery(query)) {
        var field = 0
        while (field < fields) {
          appendRecord(
            bucket,
            SafeZoneAllocator
              .allocate(
                bucket.zone,
                new SafeRecord(
                  20 + (field & 3),
                  i,
                  repo,
                  eventType,
                  field,
                  hash ^ (field.toLong * 1315423911L),
                  null
                )
              )
              .asInstanceOf[SafeRecord]
          )
          field += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
    }

    try {
      if (cfg.fileBackedInput) {
        foreachFileBackedEvent(new EventConsumer {
          def apply(
              i: Int,
              repo: Int,
              eventType: Int,
              fields: Int,
              hash: Long
          ): Unit =
            processEvent(i, repo, eventType, fields, hash)
        })
      } else {
        var i = 0
        while (i < input.events) {
          val repo = input.repoAt(i)
          val eventType = input.eventTypeAt(i)
          val fields = input.fieldCountAt(i)
          val hash = input.hashAt(i, eventType)
          processEvent(i, repo, eventType, fields, hash)
          i += 1
          }
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
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: TrustedBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.repoBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.repoBucket) += 1
          record = record.next
        }
        var repo = 0
        while (repo < counts.length) {
          val count = counts(repo)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startEvent.toInt,
              repo,
              0,
              count,
              (repo.toLong << 32) ^ count.toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          repo += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.repoBucket,
            record.eventType,
            record.value,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }

    def closeBucket(bucket: TrustedBucket): Unit = {
      consumeBucket(bucket)
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

    def processEvent(
        i: Int,
        repo: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val region = bucket.region
      appendRecord(
        bucket,
        region.alloc(new TrustedRecord(10, i, repo, eventType, fields, hash, null))
      )
      if (fieldQuery(query)) {
        var field = 0
        while (field < fields) {
          appendRecord(
            bucket,
            region.alloc(
              new TrustedRecord(
                20 + (field & 3),
                i,
                repo,
                eventType,
                field,
                hash ^ (field.toLong * 1315423911L),
                null
              )
            )
          )
          field += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
    }

    try {
      if (cfg.fileBackedInput) {
        foreachFileBackedEvent(new EventConsumer {
          def apply(
              i: Int,
              repo: Int,
              eventType: Int,
              fields: Int,
              hash: Long
          ): Unit =
            processEvent(i, repo, eventType, fields, hash)
        })
      } else {
        var i = 0
        while (i < input.events) {
          val repo = input.repoAt(i)
          val eventType = input.eventTypeAt(i)
          val fields = input.fieldCountAt(i)
          val hash = input.hashAt(i, eventType)
          processEvent(i, repo, eventType, fields, hash)
          i += 1
          }
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

  private def runRiftCheckedPageTokenBody(query: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData

    final class CheckedRecord(
        val kind: Int,
        val eventIndex: Int,
        val repoBucket: Int,
        val eventType: Int,
        val value: Int,
        val hash: Long
    ) extends RiftRegion.StreamAppendNode

    val window =
      RiftRegion.streamPageTokenAppendWindow[CheckedRecord](
        cfg.eventsPerBucket.toLong
      )
    var checksum = 0L
    var outputCount = 0L

    def closeRecords(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedRecord]^{stream}
    ): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.repoBuckets)
        while (cursor.hasNext) {
          val record: CheckedRecord^{stream} = cursor.next()
          counts(record.repoBucket) += 1
        }
        var repo = 0
        while (repo < counts.length) {
          val count = counts(repo)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startSeconds.toInt,
              repo,
              0,
              count,
              (repo.toLong << 32) ^ count.toLong,
              bucket.startSeconds
            )
            outputCount += 1L
          }
          repo += 1
        }
      } else {
        while (cursor.hasNext) {
          val record: CheckedRecord^{stream} = cursor.next()
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.repoBucket,
            record.eventType,
            record.value,
            record.hash,
            bucket.startSeconds
          )
          outputCount += 1L
        }
      }

    var currentStartEvent = Long.MinValue
    var currentRegion: RiftRegion.StreamingRegion^{stream} = null

    def processEvent(
        i: Int,
        repo: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      if (start != currentStartEvent) {
        currentStartEvent = start
        currentRegion =
          RiftRegion.pageTokenAppendRegionFor(
            stream,
            window,
            start,
            closeCutoff(start)
          )(closeRecords)
      }
      val eventRecord: CheckedRecord^{stream} =
        RiftRegion.alloc(
          new CheckedRecord(10, i, repo, eventType, fields, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, eventRecord)
      if (fieldQuery(query)) {
        var field = 0
        while (field < fields) {
          val fieldRecord: CheckedRecord^{stream} =
            RiftRegion.alloc(
              new CheckedRecord(
                20 + (field & 3),
                i,
                repo,
                eventType,
                field,
                hash ^ (field.toLong * 1315423911L)
              )
            )(using currentRegion)
          RiftRegion.appendPageToken(stream, window, fieldRecord)
          field += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
    }

    if (cfg.fileBackedInput) {
      foreachFileBackedEvent(new EventConsumer {
        def apply(
            i: Int,
            repo: Int,
            eventType: Int,
            fields: Int,
            hash: Long
        ): Unit =
          processEvent(i, repo, eventType, fields, hash)
      })
    } else {
      var i = 0
      while (i < input.events) {
        val repo = input.repoAt(i)
        val eventType = input.eventTypeAt(i)
        val fields = input.fieldCountAt(i)
        val hash = input.hashAt(i, eventType)
        processEvent(i, repo, eventType, fields, hash)
        i += 1
      }
    }

    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      closeRecords
    )

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedPageToken(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(query)
    }

  private def runRiftCheckedSafeZonePageToken(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedPageTokenBody(query)
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap-immix" => "heap"
      case "safezone-current" | "safezone-improved" |
          "safezone-improved-32k" | "safezone-rootless-32k" |
          "unsafezone-hp" =>
        "safezone"
      case "rift-trusted-hp"        => "rift-hp"
      case "rift-trusted-streaming" => "rift-streaming"
      case other                    => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-hp" | "rift-streaming" | "rift-checked-page-token" => true
      case _                                                        => false
    }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"                        => runHeap(query)
      case "safezone"                    => runSafeZone(query)
      case "rift-hp"                     => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming"              => runRiftTrusted(query, RiftRegion.Streaming)
      case "rift-checked-page-token"     => runRiftCheckedPageToken(query)
      case "rift-checked-safezone-page-token" =>
        runRiftCheckedSafeZonePageToken(query)
      case other =>
        throw new IllegalArgumentException(
          s"unknown GH Archive mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    val canonical = canonicalMode(mode)
    val usesRift = usesRiftRuntime(mode)
    val expected = runExpected(query)
    System.gc()

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode, query)
      if (outcome != expected)
        throw new IllegalStateException(
          s"warmup mismatch query=$query mode=$mode expected=$expected actual=$outcome"
        )
      System.gc()
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
      s"Running github-archive-$query-$canonical for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=github-archive-$query-$canonical " +
        f"query=$query mode=$canonical input=${input.label} " +
        f"input_mode=${cfg.inputMode} " +
        f"loaded_events=${input.events}%d " +
        f"input_files=${input.inputFiles}%d " +
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
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode canonical_mode=${canonicalMode(mode)} query=$query " +
        s"events=${input.events} configured_events=${cfg.events} " +
        s"events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} " +
        s"repo_buckets=${cfg.repoBuckets} field_limit=${cfg.fieldLimit} " +
        s"sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns} input=${input.label} input_mode=${cfg.inputMode} " +
        s"input_path=${cfg.inputPath}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    usesRiftRuntime(mode)
}

@main def GithubArchiveRegionMatrix(
    mode: String = "heap",
    query: String = "q2-repo-window"
): Unit = {
  GithubArchiveRegionMatrixHelpers.validateMode(mode)
  GithubArchiveRegionMatrixHelpers.validateQuery(query)
  GithubArchiveRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = GithubArchiveRegionMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    GithubArchiveRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
