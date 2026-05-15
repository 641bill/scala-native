import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftOpenStreamingHandle, RiftRegion, SafeZone}
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

  private def envFlag(name: String): Boolean =
    sys.env.get(name).exists { value =>
      value == "1" || value.equalsIgnoreCase("true") ||
        value.equalsIgnoreCase("yes")
    }

  val events: Int = envInt("GITHUB_ARCHIVE_EVENTS", 100000)
  val eventsPerBucket: Int =
    envInt("GITHUB_ARCHIVE_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("GITHUB_ARCHIVE_LIVE_BUCKETS", 4)
  val repoBuckets: Int = envInt("GITHUB_ARCHIVE_REPO_BUCKETS", 4096)
  val fieldLimit: Int = envInt("GITHUB_ARCHIVE_FIELD_LIMIT", 12)
  val sampleEvery: Int = envInt("GITHUB_ARCHIVE_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("GITHUB_ARCHIVE_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("GITHUB_ARCHIVE_BENCHMARK_RUNS", 3)
  val finalClean: Boolean =
    envFlag("RIFT_FINAL_CLEAN") ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
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
  val fileParser: String = {
    val raw = BenchmarkInputSupport.envString("GITHUB_ARCHIVE_FILE_PARSER")
    if (raw.isEmpty) "byte-slice"
    else
      raw match {
        case "byte-slice" | "string" => raw
        case other =>
          throw new IllegalArgumentException(
            s"unknown GITHUB_ARCHIVE_FILE_PARSER '$other'; expected byte-slice or string"
          )
      }
  }
  val fileBackedInput: Boolean =
    inputMode match {
      case "preloaded"                 => false
      case "file-backed" | "streaming-file" => true
      case other =>
        throw new IllegalArgumentException(
          s"unknown GITHUB_ARCHIVE_INPUT_MODE '$other'; expected preloaded, file-backed, or streaming-file"
        )
    }
}

object GithubArchiveRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var retainedAnchorSink = 0L

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

  private val TypeMarker = "\"type\"".getBytes("US-ASCII")
  private val RepoMarker = "\"repo\"".getBytes("US-ASCII")
  private val ActorMarker = "\"actor\"".getBytes("US-ASCII")
  private val NameMarker = "\"name\"".getBytes("US-ASCII")
  private val LoginMarker = "\"login\"".getBytes("US-ASCII")

  private def matches(bytes: Array[Byte], offset: Int, marker: Array[Byte]): Boolean = {
    var i = 0
    while (i < marker.length) {
      if (bytes(offset + i) != marker(i)) return false
      i += 1
    }
    true
  }

  private def findBytes(
      bytes: Array[Byte],
      length: Int,
      marker: Array[Byte],
      from: Int
  ): Int = {
    var i = math.max(0, from)
    val last = length - marker.length
    while (i <= last) {
      if (bytes(i) == marker(0) && matches(bytes, i, marker)) return i
      i += 1
    }
    -1
  }

  private def encodeBounds(start: Int, length: Int): Long =
    (start.toLong << 32) | (length.toLong & 0xffffffffL)

  private def boundsStart(bounds: Long): Int =
    (bounds >>> 32).toInt

  private def boundsLength(bounds: Long): Int =
    bounds.toInt

  private def jsonStringValueBounds(
      bytes: Array[Byte],
      length: Int,
      marker: Array[Byte],
      from: Int
  ): Long = {
    val start = findBytes(bytes, length, marker, from)
    if (start < 0) -1L
    else {
      var colon = start + marker.length
      while (colon < length && bytes(colon) != ':'.toByte) colon += 1
      if (colon >= length) -1L
      else {
        var i = colon + 1
        while (i < length && bytes(i) == ' '.toByte) i += 1
        if (i >= length || bytes(i) != '"'.toByte) -1L
        else {
          i += 1
          val begin = i
          var escaped = false
          while (i < length) {
            val b = bytes(i)
            if (escaped) escaped = false
            else if (b == '\\'.toByte) escaped = true
            else if (b == '"'.toByte) return encodeBounds(begin, i - begin)
            i += 1
          }
          -1L
        }
      }
    }
  }

  private def equalsAscii(
      bytes: Array[Byte],
      start: Int,
      length: Int,
      text: String
  ): Boolean = {
    if (length != text.length) return false
    var i = 0
    while (i < length) {
      if ((bytes(start + i) & 0xff) != text.charAt(i).toInt) return false
      i += 1
    }
    true
  }

  private def eventTypeId(bytes: Array[Byte], start: Int, length: Int): Int =
    if (start < 0 || length <= 0) 0
    else if (equalsAscii(bytes, start, length, "PushEvent")) 0
    else if (equalsAscii(bytes, start, length, "PullRequestEvent")) 1
    else if (equalsAscii(bytes, start, length, "IssuesEvent")) 2
    else if (equalsAscii(bytes, start, length, "IssueCommentEvent")) 3
    else if (equalsAscii(bytes, start, length, "WatchEvent")) 4
    else if (equalsAscii(bytes, start, length, "ForkEvent")) 5
    else if (equalsAscii(bytes, start, length, "CreateEvent")) 6
    else if (equalsAscii(bytes, start, length, "DeleteEvent")) 7
    else if (equalsAscii(bytes, start, length, "ReleaseEvent")) 8
    else if (equalsAscii(bytes, start, length, "PublicEvent")) 9
    else
      BenchmarkInputSupport.positiveModulo(
        BenchmarkInputSupport.stableHash(bytes, start, length),
        16
      )

  private def countJsonFields(bytes: Array[Byte], length: Int, limit: Int): Int = {
    var count = 0
    var i = 0
    while (i + 2 < length && count < limit) {
      if (bytes(i) == '"'.toByte && bytes(i + 1) != ':'.toByte) {
        var j = i + 1
        var escaped = false
        var done = false
        while (j < length && !done) {
          val b = bytes(j)
          if (escaped) escaped = false
          else if (b == '\\'.toByte) escaped = true
          else if (b == '"'.toByte) done = true
          j += 1
        }
        var k = j
        while (k < length && bytes(k) == ' '.toByte) k += 1
        if (k < length && bytes(k) == ':'.toByte) count += 1
        i = k
      }
      i += 1
    }
    if (count == 0) 1 else count
  }

  private def countFileBackedInputRowsString(): Int = {
    val cfg = GithubArchiveRegionConfig
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

  private def countFileBackedInputRowsBytes(): Int = {
    val cfg = GithubArchiveRegionConfig
    var count = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && count < cfg.events) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0 && count < cfg.events) {
          if (length > 0) count += 1
          length = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }
    count
  }

  private def countFileBackedInputRows(): Int = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.inputPath.isEmpty)
      throw new IllegalArgumentException(
        "GITHUB_ARCHIVE_INPUT_MODE=file-backed/streaming-file requires GITHUB_ARCHIVE_INPUT or GITHUB_ARCHIVE_INPUTS"
      )

    if (cfg.fileParser == "string") countFileBackedInputRowsString()
    else countFileBackedInputRowsBytes()
  }

  private def foreachFileBackedEventString(consumer: EventConsumer^): Int = {
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

  private def foreachFileBackedEventBytes(consumer: EventConsumer^): Int = {
    val cfg = GithubArchiveRegionConfig
    var index = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && index < cfg.events) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0 && index < cfg.events) {
          if (length > 0) {
            val line = reader.bytes
            val eventBounds =
              jsonStringValueBounds(line, length, TypeMarker, 0)
            val repoIndex = findBytes(line, length, RepoMarker, 0)
            val actorIndex = findBytes(line, length, ActorMarker, 0)
            val repoBounds =
              jsonStringValueBounds(line, length, NameMarker, repoIndex)
            val actorBounds =
              jsonStringValueBounds(line, length, LoginMarker, actorIndex)

            val lineHash = BenchmarkInputSupport.stableHash(line, 0, length)
            val repoHash =
              if (repoBounds >= 0L)
                BenchmarkInputSupport.stableHash(
                  line,
                  boundsStart(repoBounds),
                  boundsLength(repoBounds)
                )
              else lineHash
            val actorHash =
              if (actorBounds >= 0L)
                BenchmarkInputSupport.stableHash(
                  line,
                  boundsStart(actorBounds),
                  boundsLength(actorBounds)
                )
              else 0
            val repoBucket =
              BenchmarkInputSupport.positiveModulo(repoHash, cfg.repoBuckets)
            val eventType =
              if (eventBounds >= 0L)
                eventTypeId(
                  line,
                  boundsStart(eventBounds),
                  boundsLength(eventBounds)
                )
              else 0
            val fields = countJsonFields(line, length, cfg.fieldLimit)
            val hash =
              lineHash.toLong ^
                (actorHash.toLong << 17) ^
                (eventType.toLong * 1099511628211L)
            consumer(index, repoBucket, eventType, fields, hash)
            index += 1
          }
          length = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }
    index
  }

  private def foreachFileBackedEvent(consumer: EventConsumer^): Int = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.fileParser == "string") foreachFileBackedEventString(consumer)
    else foreachFileBackedEventBytes(consumer)
  }

  private def loadInput(): InputData = {
    val cfg = GithubArchiveRegionConfig
    if (cfg.fileBackedInput) {
      val rows = countFileBackedInputRows()
      if (rows == 0)
        throw new IllegalArgumentException(
          s"GH Archive input '${cfg.inputPath}' did not contain usable rows"
        )
      val parserLabel =
        if (cfg.fileParser == "byte-slice") "byte" else "string"
      val inputKind =
        if (cfg.inputMode == "streaming-file") "streaming-file"
        else "file-backed"
      return new InputData(
        if (cfg.inputPaths.length == 1)
          s"real-gharchive-${parserLabel}-${inputKind}"
        else s"real-gharchive-${parserLabel}-${inputKind}-${cfg.inputPaths.length}files",
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
      case "heap" | "heap-direct-epoch" | "safezone" | "rift-hp" |
          "rift-streaming" | "rift-checked-page-token" |
          "rift-checked-page-token-legacy" |
          "rift-checked-safezone-page-token" | "rift-checked-direct-epoch" |
          "rift-checked-safezone-direct-epoch" |
          "heap-epoch-retained-no-traverse" |
          "checked-epoch-retained-no-traverse" |
          "checked-scoped-epoch-retained-no-traverse" =>
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

  private def runRiftCheckedPageTokenBody(
      query: String,
      useRiftHandle: Boolean
  )(using stream: RiftRegion.StreamingRegion^): RunOutcome = {
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
    var currentRegion: RiftRegion.OpenStreamingRegion^{stream} = null
    var currentHandle: RiftOpenStreamingHandle^{stream} = null

    def selectBucket(start: Long): Unit =
      if (start != currentStartEvent) {
        currentStartEvent = start
        if (useRiftHandle) {
          currentHandle =
            RiftRegion.pageTokenAppendRiftOpenHandleFor(
              stream,
              window,
              start,
              closeCutoff(start)
            )(closeRecords)
          currentRegion = null
        } else {
          currentRegion =
            RiftRegion.pageTokenAppendOpenRegionFor(
              stream,
              window,
              start,
              closeCutoff(start)
            )(closeRecords)
          currentHandle = null
        }
      }

    def appendCheckedLegacy(
        kind: Int,
        i: Int,
        repo: Int,
        eventType: Int,
        value: Int,
        hash: Long
    ): Unit = {
      val record: CheckedRecord^{stream} =
        RiftRegion.allocOpen(
          new CheckedRecord(kind, i, repo, eventType, value, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, record)
    }

    def appendCheckedHandle(
        kind: Int,
        i: Int,
        repo: Int,
        eventType: Int,
        value: Int,
        hash: Long
    ): Unit = {
      val record: CheckedRecord^{stream} =
        RiftAllocator.allocateOpenHandle(
          currentHandle,
          new CheckedRecord(kind, i, repo, eventType, value, hash)
        )
      RiftRegion.appendPageToken(stream, window, record)
    }

    def appendChecked(
        kind: Int,
        i: Int,
        repo: Int,
        eventType: Int,
        value: Int,
        hash: Long
    ): Unit =
      if (useRiftHandle)
        appendCheckedHandle(kind, i, repo, eventType, value, hash)
      else
        appendCheckedLegacy(kind, i, repo, eventType, value, hash)

    def processEvent(
        i: Int,
        repo: Int,
        eventType: Int,
        fields: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      selectBucket(start)
      appendChecked(10, i, repo, eventType, fields, hash)
      if (fieldQuery(query)) {
        var field = 0
        while (field < fields) {
          appendChecked(
            20 + (field & 3),
            i,
            repo,
            eventType,
            field,
            hash ^ (field.toLong * 1315423911L)
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

    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      closeRecords
    )

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedPageToken(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(query, useRiftHandle = true)
    }

  private def runRiftCheckedPageTokenLegacy(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(query, useRiftHandle = false)
    }

  private def runRiftCheckedSafeZonePageToken(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedPageTokenBody(query, useRiftHandle = false)
    }

  private def runHeapDirectEpochAggregate(
      query: String,
      retainRecords: Boolean = false
  ): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    if (cfg.fileBackedInput)
      throw new IllegalArgumentException(
        "GH Archive heap direct-epoch requires generated or preloaded input; use page-token for file-backed rows"
      )
    if (!windowQuery(query))
      throw new IllegalArgumentException(
        s"GH Archive heap direct-epoch supports q2/window queries, not '$query'"
      )

    final class HeapDirectRecord(
        val kind: Int,
        val eventIndex: Int,
        val repoBucket: Int,
        val eventType: Int,
        val value: Int,
        val hash: Long
    )

    final class HeapRetainedRecord(
        val kind: Int,
        val eventIndex: Int,
        val repoBucket: Int,
        val eventType: Int,
        val value: Int,
        val hash: Long,
        val next: HeapRetainedRecord
    )

    val slots = cfg.liveBuckets + 1
    val starts = Array.fill[Long](slots)(Long.MinValue)
    val counts = new Array[Int](slots * cfg.repoBuckets)
    var nextCloseStart = 0L
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L

    def slotFor(start: Long): Int =
      ((start / cfg.eventsPerBucket.toLong) % slots.toLong).toInt

    def clearSlot(slot: Int): Unit = {
      val base = slot * cfg.repoBuckets
      var repo = 0
      while (repo < cfg.repoBuckets) {
        counts(base + repo) = 0
        repo += 1
      }
    }

    def closeSummaries(cutoffEvent: Long): Unit =
      while (
        nextCloseStart < input.events.toLong &&
        nextCloseStart + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val slot = slotFor(nextCloseStart)
        if (starts(slot) == nextCloseStart) {
          val base = slot * cfg.repoBuckets
          var repo = 0
          while (repo < cfg.repoBuckets) {
            val count = counts(base + repo)
            if (count != 0) {
              checksum = fold(
                checksum,
                44,
                nextCloseStart.toInt,
                repo,
                0,
                count,
                (repo.toLong << 32) ^ count.toLong,
                nextCloseStart
              )
              outputCount += 1L
            }
            repo += 1
          }
          starts(slot) = Long.MinValue
        }
        nextCloseStart += cfg.eventsPerBucket.toLong
      }

    def runBucket(startEvent: Int, endEvent: Int): Unit = {
      val start = startEvent.toLong
      closeSummaries(closeCutoff(start))
      val slot = slotFor(start)
      clearSlot(slot)
      starts(slot) = start
      val base = slot * cfg.repoBuckets
      var head: HeapRetainedRecord = null
      var tail: HeapRetainedRecord = null
      var retainedCount = 0

      def appendHeapDirect(
          kind: Int,
          i: Int,
          repo: Int,
          eventType: Int,
          value: Int,
          hash: Long
      ): Unit = {
        if (retainRecords) {
          val record =
            new HeapRetainedRecord(kind, i, repo, eventType, value, hash, head)
          if (head == null) tail = record
          head = record
          retainedCount += 1
          counts(base + record.repoBucket) += 1
        } else {
          val record =
            new HeapDirectRecord(kind, i, repo, eventType, value, hash)
          counts(base + record.repoBucket) += 1
        }
      }

      var i = startEvent
      while (i < endEvent) {
        val repo = input.repoAt(i)
        val eventType = input.eventTypeAt(i)
        val fields = input.fieldCountAt(i)
        val hash = input.hashAt(i, eventType)
        appendHeapDirect(10, i, repo, eventType, fields, hash)
        var field = 0
        while (field < fields) {
          appendHeapDirect(
            20 + (field & 3),
            i,
            repo,
            eventType,
            field,
            hash ^ (field.toLong * 1315423911L)
          )
          field += 1
        }
        if (i % cfg.sampleEvery == 0)
          checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
        i += 1
      }
      if (retainRecords && head != null && tail != null)
        retainedAnchor =
          (retainedAnchor * 1099511628211L) ^
            head.hash ^
            (tail.hash << 1) ^
            retainedCount.toLong ^
            start
    }

    var bucketStartEvent = 0
    while (bucketStartEvent < input.events) {
      val bucketEnd =
        math.min(input.events, bucketStartEvent + cfg.eventsPerBucket)
      runBucket(bucketStartEvent, bucketEnd)
      bucketStartEvent = bucketEnd
    }
    closeSummaries(Long.MaxValue)

    checksumSink = checksum
    outputSink = outputCount
    if (retainRecords) retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedDirectEpochBody(
      query: String,
      retainRecords: Boolean = false
  )(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = GithubArchiveRegionConfig
    val input = inputData
    if (cfg.fileBackedInput)
      throw new IllegalArgumentException(
        "GH Archive checked direct-epoch requires generated or preloaded input; use page-token for file-backed rows"
      )
    if (!windowQuery(query))
      throw new IllegalArgumentException(
        s"GH Archive checked direct-epoch supports q2/window queries, not '$query'"
      )

    val slots = cfg.liveBuckets + 1
    val starts = Array.fill[Long](slots)(Long.MinValue)
    val counts = new Array[Int](slots * cfg.repoBuckets)
    var nextCloseStart = 0L
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L

    def slotFor(start: Long): Int =
      ((start / cfg.eventsPerBucket.toLong) % slots.toLong).toInt

    def clearSlot(slot: Int): Unit = {
      val base = slot * cfg.repoBuckets
      var repo = 0
      while (repo < cfg.repoBuckets) {
        counts(base + repo) = 0
        repo += 1
      }
    }

    def closeSummaries(cutoffEvent: Long): Unit =
      while (
        nextCloseStart < input.events.toLong &&
        nextCloseStart + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val slot = slotFor(nextCloseStart)
        if (starts(slot) == nextCloseStart) {
          val base = slot * cfg.repoBuckets
          var repo = 0
          while (repo < cfg.repoBuckets) {
            val count = counts(base + repo)
            if (count != 0) {
              checksum = fold(
                checksum,
                44,
                nextCloseStart.toInt,
                repo,
                0,
                count,
                (repo.toLong << 32) ^ count.toLong,
                nextCloseStart
              )
              outputCount += 1L
            }
            repo += 1
          }
          starts(slot) = Long.MinValue
        }
        nextCloseStart += cfg.eventsPerBucket.toLong
      }

    def runBucket(startEvent: Int, endEvent: Int): Unit = {
      val start = startEvent.toLong
      closeSummaries(closeCutoff(start))
      val slot = slotFor(start)
      clearSlot(slot)
      starts(slot) = start
      val base = slot * cfg.repoBuckets

      RiftRegion.epoch { region ?=>
        final class CheckedRecord(
            val kind: Int,
            val eventIndex: Int,
            val repoBucket: Int,
            val eventType: Int,
            val value: Int,
            val hash: Long
        )

        final class CheckedRetainedRecord(
            val kind: Int,
            val eventIndex: Int,
            val repoBucket: Int,
            val eventType: Int,
            val value: Int,
            val hash: Long
        ) {
          var next: CheckedRetainedRecord^{region} = null
        }

        var head: CheckedRetainedRecord^{region} = null
        var tail: CheckedRetainedRecord^{region} = null
        var retainedCount = 0

        def appendChecked(
            kind: Int,
            i: Int,
            repo: Int,
            eventType: Int,
            value: Int,
            hash: Long
        ): Unit = {
          if (retainRecords) {
            val record: CheckedRetainedRecord^{region} =
              RiftRegion.allocOpen(
                new CheckedRetainedRecord(kind, i, repo, eventType, value, hash)
              )
            record.next = head
            if (head == null) tail = record
            head = record
            retainedCount += 1
            counts(base + record.repoBucket) += 1
          } else {
            val record =
              RiftRegion.allocOpen(
                new CheckedRecord(kind, i, repo, eventType, value, hash)
              )
            counts(base + record.repoBucket) += 1
          }
        }

        var i = startEvent
        while (i < endEvent) {
          val repo = input.repoAt(i)
          val eventType = input.eventTypeAt(i)
          val fields = input.fieldCountAt(i)
          val hash = input.hashAt(i, eventType)
          appendChecked(10, i, repo, eventType, fields, hash)
          var field = 0
          while (field < fields) {
            appendChecked(
              20 + (field & 3),
              i,
              repo,
              eventType,
              field,
              hash ^ (field.toLong * 1315423911L)
            )
            field += 1
          }
          if (i % cfg.sampleEvery == 0)
            checksum = fold(checksum, 99, i, repo, eventType, fields, hash, start)
          i += 1
        }
        if (retainRecords && head != null && tail != null)
          retainedAnchor =
            (retainedAnchor * 1099511628211L) ^
              head.hash ^
              (tail.hash << 1) ^
              retainedCount.toLong ^
              start
      }
    }

    var bucketStartEvent = 0
    while (bucketStartEvent < input.events) {
      val bucketEnd =
        math.min(input.events, bucketStartEvent + cfg.eventsPerBucket)
      runBucket(bucketStartEvent, bucketEnd)
      bucketStartEvent = bucketEnd
    }
    closeSummaries(Long.MaxValue)

    checksumSink = checksum
    outputSink = outputCount
    if (retainRecords) retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedDirectEpoch(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedDirectEpochBody(query)
    }

  private def runRiftCheckedSafeZoneDirectEpoch(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedDirectEpochBody(query)
    }

  private def runHeapRetainedEpochNoTraverse(query: String): RunOutcome =
    runHeapDirectEpochAggregate(query, retainRecords = true)

  private def runRiftCheckedRetainedEpochNoTraverse(
      query: String
  ): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedDirectEpochBody(query, retainRecords = true)
    }

  private def runRiftCheckedSafeZoneRetainedEpochNoTraverse(
      query: String
  ): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedDirectEpochBody(query, retainRecords = true)
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" | "heap-immix" => "heap"
      case "heap-direct-summary-only" =>
        "heap-direct-epoch"
      case "heap-same-shape-direct-epoch" | "heap-direct-aggregate" |
          "heap-direct-epoch" =>
        "heap-direct-epoch"
      case "heap-epoch-retained-no-traverse" =>
        "heap-epoch-retained-no-traverse"
      case "safezone-current" | "safezone-improved" |
          "safezone-improved-32k" | "safezone-rootless-32k" |
          "unsafezone-hp" =>
        "safezone"
      case "rift-trusted-hp"        => "rift-hp"
      case "rift-trusted-streaming" => "rift-streaming"
      case "checked-epoch-stream" | "checked-region-stream-epoch" |
          "rift-checked-direct-epoch" =>
        "rift-checked-direct-epoch"
      case "checked-epoch-scoped" | "checked-region-scoped-epoch" |
          "rift-checked-safezone-direct-epoch" =>
        "rift-checked-safezone-direct-epoch"
      case "checked-epoch-retained-no-traverse" |
          "checked-region-stream-retained-epoch" =>
        "checked-epoch-retained-no-traverse"
      case "checked-scoped-epoch-retained-no-traverse" |
          "checked-region-scoped-retained-epoch" =>
        "checked-scoped-epoch-retained-no-traverse"
      case other                    => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-hp" | "rift-streaming" | "rift-checked-page-token" |
          "rift-checked-page-token-legacy" |
          "rift-checked-direct-epoch" |
          "checked-epoch-retained-no-traverse" =>
        true
      case _                                                        => false
    }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"                        => runHeap(query)
      case "heap-direct-epoch"           => runHeapDirectEpochAggregate(query)
      case "heap-epoch-retained-no-traverse" =>
        runHeapRetainedEpochNoTraverse(query)
      case "safezone"                    => runSafeZone(query)
      case "rift-hp"                     => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming"              => runRiftTrusted(query, RiftRegion.Streaming)
      case "rift-checked-page-token"     => runRiftCheckedPageToken(query)
      case "rift-checked-page-token-legacy" =>
        runRiftCheckedPageTokenLegacy(query)
      case "rift-checked-safezone-page-token" =>
        runRiftCheckedSafeZonePageToken(query)
      case "rift-checked-direct-epoch" => runRiftCheckedDirectEpoch(query)
      case "rift-checked-safezone-direct-epoch" =>
        runRiftCheckedSafeZoneDirectEpoch(query)
      case "checked-epoch-retained-no-traverse" =>
        runRiftCheckedRetainedEpochNoTraverse(query)
      case "checked-scoped-epoch-retained-no-traverse" =>
        runRiftCheckedSafeZoneRetainedEpochNoTraverse(query)
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

    if (cfg.finalClean) {
      var run = 0
      var expected: RunOutcome = null
      while (run < cfg.benchmarkRuns) {
        val outcome = runMode(mode, query)
        if (run == 0) expected = outcome
        else if (outcome != expected)
          throw new IllegalStateException(
            s"final-clean mismatch query=$query mode=$mode expected=$expected actual=$outcome"
          )
        run += 1
      }

      println(
        f"RESULT name=github-archive-$query-$canonical " +
          f"measurement_level=L1 final_clean=1 " +
          f"query=$query mode=$canonical input=${input.label} " +
          f"input_mode=${cfg.inputMode} " +
          f"input_parser=${if (cfg.fileBackedInput) cfg.fileParser else "preloaded"} " +
          f"loaded_events=${input.events}%d " +
          f"input_files=${input.inputFiles}%d " +
          f"runs=${cfg.benchmarkRuns}%d " +
          f"checksum=${expected.checksum}%d " +
          f"output_count=${expected.outputCount}%d"
      )
      return
    }

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
        f"input_parser=${if (cfg.fileBackedInput) cfg.fileParser else "preloaded"} " +
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
        s"runs=${cfg.benchmarkRuns} final_clean=${cfg.finalClean} input=${input.label} input_mode=${cfg.inputMode} " +
        s"input_parser=${if (cfg.fileBackedInput) cfg.fileParser else "preloaded"} " +
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
