import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object LogHubRegionConfig {
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

  val lines: Int = envInt("LOGHUB_LINES", 100000)
  val linesPerBucket: Int = envInt("LOGHUB_LINES_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("LOGHUB_LIVE_BUCKETS", 4)
  val componentBuckets: Int = envInt("LOGHUB_COMPONENT_BUCKETS", 4096)
  val tokenLimit: Int = envInt("LOGHUB_TOKEN_LIMIT", 16)
  val sampleEvery: Int = envInt("LOGHUB_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("LOGHUB_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("LOGHUB_BENCHMARK_RUNS", 3)

  private val inputPathsRaw: String = {
    val multiple = BenchmarkInputSupport.envString("LOGHUB_INPUTS")
    if (multiple.nonEmpty) multiple
    else BenchmarkInputSupport.envString("LOGHUB_INPUT")
  }

  val inputPaths: Array[String] =
    if (inputPathsRaw.isEmpty) Array.empty
    else inputPathsRaw.split(",").map(_.trim).filter(_.nonEmpty)

  val inputPath: String = inputPaths.mkString(",")

  val inputMode: String = {
    val raw = BenchmarkInputSupport.envString("LOGHUB_INPUT_MODE")
    if (raw.isEmpty) {
      if (inputPaths.isEmpty) "generated" else "file-backed"
    } else raw
  }

  val fileBackedInput: Boolean =
    inputMode match {
      case "generated"   => false
      case "file-backed" => true
      case other =>
        throw new IllegalArgumentException(
          s"unknown LOGHUB_INPUT_MODE '$other'; expected generated or file-backed"
        )
    }
}

object LogHubRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapRecord(
      val kind: Int,
      val lineIndex: Int,
      val component: Int,
      val severity: Int,
      val value: Int,
      val hash: Long,
      var next: HeapRecord
  )

  private final class HeapBucket(val startLine: Long, var next: HeapBucket) {
    var head: HeapRecord = null
    var tail: HeapRecord = null
  }

  private final class SafeRecord(
      val kind: Int,
      val lineIndex: Int,
      val component: Int,
      val severity: Int,
      val value: Int,
      val hash: Long,
      var next: SafeRecord
  )

  private final class SafeBucket(
      val zone: SafeZone,
      val startLine: Long,
      var next: SafeBucket
  ) {
    var head: SafeRecord = null
    var tail: SafeRecord = null
  }

  private final class TrustedRecord(
      val kind: Int,
      val lineIndex: Int,
      val component: Int,
      val severity: Int,
      val value: Int,
      val hash: Long,
      var next: TrustedRecord
  )

  private final class TrustedBucket(
      val region: RiftRegion,
      val startLine: Long,
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
      val lines: Int,
      val inputFiles: Int
  )

  private abstract class LineConsumer {
    def apply(
        lineIndex: Int,
        component: Int,
        severity: Int,
        tokens: Int,
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

  private lazy val inputData: InputData = loadInput()

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

  private def bucketStart(lineIndex: Int): Long = {
    val cfg = LogHubRegionConfig
    (lineIndex / cfg.linesPerBucket).toLong * cfg.linesPerBucket.toLong
  }

  private def closeCutoff(startLine: Long): Long = {
    val cfg = LogHubRegionConfig
    startLine - cfg.linesPerBucket.toLong * (cfg.liveBuckets - 1).toLong
  }

  private def generatedComponent(index: Int): Int =
    mix(index * 1103515245 + 12345) % LogHubRegionConfig.componentBuckets

  private def generatedSeverity(index: Int): Int =
    mix(index * 1664525 + 1013904223) % 5

  private def generatedTokens(index: Int): Int =
    4 + (mix(index * 8191 + 17) % LogHubRegionConfig.tokenLimit)

  private def generatedHash(index: Int, severity: Int): Long =
    mix(index * 1000003 + severity * 131 + 53).toLong

  private def fold(
      acc: Long,
      kind: Int,
      lineIndex: Int,
      component: Int,
      severity: Int,
      value: Int,
      hash: Long,
      bucket: Long
  ): Long = {
    var x = acc ^ hash
    x = x * 1099511628211L + kind.toLong
    x = x ^ (lineIndex.toLong << 17)
    x = x + (component.toLong << 7) + severity.toLong
    x = x ^ (value.toLong * 1315423911L)
    x ^ bucket
  }

  private def tokenQuery(query: String): Boolean =
    query == "q1-tokens" || query == "q2-window-counts"

  private def windowQuery(query: String): Boolean =
    query == "q2-window-counts"

  private def loadInput(): InputData = {
    val cfg = LogHubRegionConfig
    if (cfg.fileBackedInput) {
      if (cfg.inputPaths.isEmpty)
        throw new IllegalArgumentException(
          "LOGHUB_INPUT_MODE=file-backed requires LOGHUB_INPUT or LOGHUB_INPUTS"
        )
      val rows = countFileBackedRows()
      if (rows <= 0)
        throw new IllegalArgumentException(
          s"LogHub input '${cfg.inputPath}' did not contain usable rows"
        )
      new InputData(
        if (cfg.inputPaths.length == 1) "real-loghub-file-backed"
        else s"real-loghub-file-backed-${cfg.inputPaths.length}files",
        rows,
        cfg.inputPaths.length
      )
    } else {
      new InputData("generated-loghub-shaped", cfg.lines, 0)
    }
  }

  private def countFileBackedRows(): Int = {
    val cfg = LogHubRegionConfig
    var count = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && count < cfg.lines) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0 && count < cfg.lines) {
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

  private def containsAscii(
      bytes: Array[Byte],
      length: Int,
      text: String
  ): Boolean = {
    var i = 0
    val last = length - text.length
    while (i <= last) {
      var j = 0
      var ok = true
      while (j < text.length && ok) {
        if ((bytes(i + j) & 0xff) != text.charAt(j).toInt) ok = false
        j += 1
      }
      if (ok) return true
      i += 1
    }
    false
  }

  private def severityFor(bytes: Array[Byte], length: Int): Int =
    if (containsAscii(bytes, length, "ERROR")) 4
    else if (containsAscii(bytes, length, "FATAL")) 5
    else if (containsAscii(bytes, length, "WARN")) 3
    else if (containsAscii(bytes, length, "INFO")) 2
    else if (containsAscii(bytes, length, "DEBUG")) 1
    else 0

  private def countTokens(bytes: Array[Byte], length: Int): Int = {
    val limit = LogHubRegionConfig.tokenLimit
    var count = 0
    var inToken = false
    var i = 0
    while (i < length && count < limit) {
      val b = bytes(i) & 0xff
      val sep = b <= 32 || b == ','.toInt || b == ';'.toInt || b == '|'.toInt
      if (sep) inToken = false
      else if (!inToken) {
        inToken = true
        count += 1
      }
      i += 1
    }
    if (count == 0) 1 else count
  }

  private def componentFor(bytes: Array[Byte], length: Int): Int = {
    var start = 0
    while (start < length && (bytes(start) & 0xff) <= 32) start += 1
    var end = start
    while (end < length && (bytes(end) & 0xff) > 32 && end - start < 96)
      end += 1
    val hash =
      if (end > start) BenchmarkInputSupport.stableHash(bytes, start, end - start)
      else BenchmarkInputSupport.stableHash(bytes, 0, length)
    BenchmarkInputSupport.positiveModulo(
      hash,
      LogHubRegionConfig.componentBuckets
    )
  }

  private def foreachGeneratedLine(consumer: LineConsumer^): Int = {
    val input = inputData
    var i = 0
    while (i < input.lines) {
      val severity = generatedSeverity(i)
      consumer(
        i,
        generatedComponent(i),
        severity,
        generatedTokens(i),
        generatedHash(i, severity)
      )
      i += 1
    }
    input.lines
  }

  private def foreachFileBackedLine(consumer: LineConsumer^): Int = {
    val cfg = LogHubRegionConfig
    var index = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && index < cfg.lines) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0 && index < cfg.lines) {
          if (length > 0) {
            val line = reader.bytes
            val severity = severityFor(line, length)
            consumer(
              index,
              componentFor(line, length),
              severity,
              countTokens(line, length),
              BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                (severity.toLong * 1099511628211L)
            )
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

  private def foreachLine(consumer: LineConsumer^): Int =
    if (LogHubRegionConfig.fileBackedInput) foreachFileBackedLine(consumer)
    else foreachGeneratedLine(consumer)

  private def appendRecord(bucket: HeapBucket, record: HeapRecord): Unit = {
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }
  }

  private def appendRecord(bucket: SafeBucket, record: SafeRecord): Unit = {
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }
  }

  private def appendRecord(bucket: TrustedBucket, record: TrustedRecord): Unit = {
    if (bucket.head == null) {
      bucket.head = record
      bucket.tail = record
    } else {
      bucket.tail.next = record
      bucket.tail = record
    }
  }

  private def runHeap(query: String): RunOutcome = {
    val cfg = LogHubRegionConfig
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: HeapBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.componentBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.component) += 1
          record = record.next
        }
        var component = 0
        while (component < counts.length) {
          val count = counts(component)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startLine.toInt,
              component,
              0,
              count,
              (component.toLong << 32) ^ count.toLong,
              bucket.startLine
            )
            outputCount += 1L
          }
          component += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.lineIndex,
            record.component,
            record.severity,
            record.value,
            record.hash,
            bucket.startLine
          )
          outputCount += 1L
          record = record.next
        }
      }

    def closeBucket(bucket: HeapBucket): Unit =
      consumeBucket(bucket)

    def closeExpired(cutoffLine: Long): Unit =
      while (
        first != null &&
        first.startLine + cfg.linesPerBucket.toLong <= cutoffLine
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startLine: Long): HeapBucket =
      if (current != null && current.startLine == startLine) current
      else {
        closeExpired(closeCutoff(startLine))
        val bucket = new HeapBucket(startLine, null)
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

    def processLine(
        i: Int,
        component: Int,
        severity: Int,
        tokens: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendRecord(
        bucket,
        new HeapRecord(10, i, component, severity, tokens, hash, null)
      )
      if (tokenQuery(query)) {
        var token = 0
        while (token < tokens) {
          appendRecord(
            bucket,
            new HeapRecord(
              20 + (token & 3),
              i,
              component,
              severity,
              token,
              hash ^ (token.toLong * 1315423911L),
              null
            )
          )
          token += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, component, severity, tokens, hash, start)
    }

    foreachLine(new LineConsumer {
      def apply(
          i: Int,
          component: Int,
          severity: Int,
          tokens: Int,
          hash: Long
      ): Unit =
        processLine(i, component, severity, tokens, hash)
    })
    closeExpired(Long.MaxValue)

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = LogHubRegionConfig
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: SafeBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.componentBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.component) += 1
          record = record.next
        }
        var component = 0
        while (component < counts.length) {
          val count = counts(component)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startLine.toInt,
              component,
              0,
              count,
              (component.toLong << 32) ^ count.toLong,
              bucket.startLine
            )
            outputCount += 1L
          }
          component += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.lineIndex,
            record.component,
            record.severity,
            record.value,
            record.hash,
            bucket.startLine
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

    def closeExpired(cutoffLine: Long): Unit =
      while (
        first != null &&
        first.startLine + cfg.linesPerBucket.toLong <= cutoffLine
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startLine: Long): SafeBucket =
      if (current != null && current.startLine == startLine) current
      else {
        closeExpired(closeCutoff(startLine))
        val bucket = new SafeBucket(SafeZone.open(), startLine, null)
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

    def processLine(
        i: Int,
        component: Int,
        severity: Int,
        tokens: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendRecord(
        bucket,
        SafeZoneAllocator
          .allocate(
            bucket.zone,
            new SafeRecord(10, i, component, severity, tokens, hash, null)
          )
          .asInstanceOf[SafeRecord]
      )
      if (tokenQuery(query)) {
        var token = 0
        while (token < tokens) {
          appendRecord(
            bucket,
            SafeZoneAllocator
              .allocate(
                bucket.zone,
                new SafeRecord(
                  20 + (token & 3),
                  i,
                  component,
                  severity,
                  token,
                  hash ^ (token.toLong * 1315423911L),
                  null
                )
              )
              .asInstanceOf[SafeRecord]
          )
          token += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, component, severity, tokens, hash, start)
    }

    try {
      foreachLine(new LineConsumer {
        def apply(
            i: Int,
            component: Int,
            severity: Int,
            tokens: Int,
            hash: Long
        ): Unit =
          processLine(i, component, severity, tokens, hash)
      })
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
    val cfg = LogHubRegionConfig
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: TrustedBucket): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.componentBuckets)
        var record = bucket.head
        while (record != null) {
          counts(record.component) += 1
          record = record.next
        }
        var component = 0
        while (component < counts.length) {
          val count = counts(component)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startLine.toInt,
              component,
              0,
              count,
              (component.toLong << 32) ^ count.toLong,
              bucket.startLine
            )
            outputCount += 1L
          }
          component += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          checksum = fold(
            checksum,
            record.kind,
            record.lineIndex,
            record.component,
            record.severity,
            record.value,
            record.hash,
            bucket.startLine
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

    def closeExpired(cutoffLine: Long): Unit =
      while (
        first != null &&
        first.startLine + cfg.linesPerBucket.toLong <= cutoffLine
      ) {
        val bucket = first
        first = bucket.next
        if (first == null) last = null
        if (current eq bucket) current = null
        closeBucket(bucket)
      }

    def bucketFor(startLine: Long): TrustedBucket =
      if (current != null && current.startLine == startLine) current
      else {
        closeExpired(closeCutoff(startLine))
        val bucket = new TrustedBucket(RiftRegion.open(kind), startLine, null)
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

    def processLine(
        i: Int,
        component: Int,
        severity: Int,
        tokens: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val region = bucket.region
      appendRecord(
        bucket,
        region.alloc(
          new TrustedRecord(10, i, component, severity, tokens, hash, null)
        )
      )
      if (tokenQuery(query)) {
        var token = 0
        while (token < tokens) {
          appendRecord(
            bucket,
            region.alloc(
              new TrustedRecord(
                20 + (token & 3),
                i,
                component,
                severity,
                token,
                hash ^ (token.toLong * 1315423911L),
                null
              )
            )
          )
          token += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, component, severity, tokens, hash, start)
    }

    try {
      foreachLine(new LineConsumer {
        def apply(
            i: Int,
            component: Int,
            severity: Int,
            tokens: Int,
            hash: Long
        ): Unit =
          processLine(i, component, severity, tokens, hash)
      })
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
    val cfg = LogHubRegionConfig

    final class CheckedRecord(
        val kind: Int,
        val lineIndex: Int,
        val component: Int,
        val severity: Int,
        val value: Int,
        val hash: Long
    ) extends RiftRegion.StreamAppendNode

    val window =
      RiftRegion.streamPageTokenAppendWindow[CheckedRecord](
        cfg.linesPerBucket.toLong
      )
    var checksum = 0L
    var outputCount = 0L

    def closeRecords(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedRecord]^{stream}
    ): Unit =
      if (windowQuery(query)) {
        val counts = new Array[Int](cfg.componentBuckets)
        while (cursor.hasNext) {
          val record: CheckedRecord^{stream} = cursor.next()
          counts(record.component) += 1
        }
        var component = 0
        while (component < counts.length) {
          val count = counts(component)
          if (count != 0) {
            checksum = fold(
              checksum,
              44,
              bucket.startSeconds.toInt,
              component,
              0,
              count,
              (component.toLong << 32) ^ count.toLong,
              bucket.startSeconds
            )
            outputCount += 1L
          }
          component += 1
        }
      } else {
        while (cursor.hasNext) {
          val record: CheckedRecord^{stream} = cursor.next()
          checksum = fold(
            checksum,
            record.kind,
            record.lineIndex,
            record.component,
            record.severity,
            record.value,
            record.hash,
            bucket.startSeconds
          )
          outputCount += 1L
        }
      }

    var currentStartLine = Long.MinValue
    var currentRegion: RiftRegion.StreamingRegion^{stream} = null

    def processLine(
        i: Int,
        component: Int,
        severity: Int,
        tokens: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      if (start != currentStartLine) {
        currentStartLine = start
        currentRegion =
          RiftRegion.pageTokenAppendRegionFor(
            stream,
            window,
            start,
            closeCutoff(start)
          )(closeRecords)
      }
      val lineRecord: CheckedRecord^{stream} =
        RiftRegion.alloc(
          new CheckedRecord(10, i, component, severity, tokens, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, lineRecord)
      if (tokenQuery(query)) {
        var token = 0
        while (token < tokens) {
          val tokenRecord: CheckedRecord^{stream} =
            RiftRegion.alloc(
              new CheckedRecord(
                20 + (token & 3),
                i,
                component,
                severity,
                token,
                hash ^ (token.toLong * 1315423911L)
              )
            )(using currentRegion)
          RiftRegion.appendPageToken(stream, window, tokenRecord)
          token += 1
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, component, severity, tokens, hash, start)
    }

    foreachLine(new LineConsumer {
      def apply(
          i: Int,
          component: Int,
          severity: Int,
          tokens: Int,
          hash: Long
      ): Unit =
        processLine(i, component, severity, tokens, hash)
    })

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
      case "rift-checked-page-token" |
          "rift-checked-safezone-page-token" =>
        mode
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-hp" | "rift-streaming" | "rift-checked-page-token" => true
      case _                                                        => false
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked-page-token" | "rift-checked-safezone-page-token" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-lines" | "q1-tokens" | "q2-window-counts" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub query '$other'"
        )
    }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"           => runHeap(query)
      case "safezone"       => runSafeZone(query)
      case "rift-hp"        => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case "rift-checked-page-token" => runRiftCheckedPageToken(query)
      case "rift-checked-safezone-page-token" =>
        runRiftCheckedSafeZonePageToken(query)
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = LogHubRegionConfig
    val input = inputData
    val canonical = canonicalMode(mode)
    val usesRift = usesRiftRuntime(mode)
    val expected = runHeap(query)
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
      s"Running loghub-$query-$canonical for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=loghub-$query-$canonical " +
        f"query=$query mode=$canonical input=${input.label} " +
        f"input_mode=${cfg.inputMode} " +
        f"loaded_events=${input.lines}%d " +
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
    val cfg = LogHubRegionConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode canonical_mode=${canonicalMode(mode)} query=$query " +
        s"lines=${input.lines} configured_lines=${cfg.lines} " +
        s"lines_per_bucket=${cfg.linesPerBucket} live_buckets=${cfg.liveBuckets} " +
        s"component_buckets=${cfg.componentBuckets} token_limit=${cfg.tokenLimit} " +
        s"sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns} input=${input.label} input_mode=${cfg.inputMode} " +
        s"input_path=${cfg.inputPath}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    usesRiftRuntime(mode)
}

@main def LogHubRegionMatrix(
    mode: String = "heap",
    query: String = "q1-tokens"
): Unit = {
  LogHubRegionMatrixHelpers.validateMode(mode)
  LogHubRegionMatrixHelpers.validateQuery(query)
  LogHubRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = LogHubRegionMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    LogHubRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
