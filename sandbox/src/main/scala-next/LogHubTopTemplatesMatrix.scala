import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftOpenStreamingHandle, RiftRegion}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator
}

object LogHubTopTemplatesConfig {
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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val lines: Int = envInt("LOGHUB_TOP_LINES", 100000)
  val linesPerEpoch: Int = envInt("LOGHUB_TOP_LINES_PER_EPOCH", 25000)
  val templateBuckets: Int = envInt("LOGHUB_TOP_TEMPLATE_BUCKETS", 8192)
  val templateTokenLimit: Int = envInt("LOGHUB_TOP_TEMPLATE_TOKEN_LIMIT", 24)
  val topK: Int = envInt("LOGHUB_TOP_K", 32)
  val sampleEvery: Int = envInt("LOGHUB_TOP_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("LOGHUB_TOP_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("LOGHUB_TOP_BENCHMARK_RUNS", 3)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env
        .get("RIFT_EVAL_MEASUREMENT_LEVEL")
        .exists(_.equalsIgnoreCase("L1"))

  private val inputPathsRaw: String = {
    val multiple = BenchmarkInputSupport.envString("LOGHUB_TOP_INPUTS")
    if (multiple.nonEmpty) multiple
    else BenchmarkInputSupport.envString("LOGHUB_TOP_INPUT")
  }

  val inputPaths: Array[String] =
    if (inputPathsRaw.isEmpty) Array.empty
    else inputPathsRaw.split(",").map(_.trim).filter(_.nonEmpty)

  val inputPath: String = inputPaths.mkString(",")

  val inputMode: String = {
    val raw = BenchmarkInputSupport.envString("LOGHUB_TOP_INPUT_MODE")
    if (raw.isEmpty) {
      if (inputPaths.isEmpty) "generated" else "file-backed"
    } else raw
  }

  val fileBackedInput: Boolean =
    inputMode match {
      case "generated"      => false
      case "file-backed"    => true
      case "streaming-file" => false
      case other =>
        throw new IllegalArgumentException(
          s"unknown LOGHUB_TOP_INPUT_MODE '$other'; expected generated, file-backed, or streaming-file"
        )
    }

  val streamingFileInput: Boolean =
    inputMode == "streaming-file"
}

object LogHubTopTemplatesMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var retainedAnchorSink = 0L

  private final class HeapRecord(
      val lineIndex: Int,
      val template: Int,
      val token: Int,
      val hash: Long,
      var next: HeapRecord
  )

  private final class InputData(
      val label: String,
      val inputFiles: Int,
      val templates: Array[Int],
      val tokenCounts: Array[Int],
      val hashes: Array[Long]
  ) {
    def lines: Int = templates.length
  }

  final case class RunOutcome(
      checksum: Long,
      outputCount: Long,
      recordsRead: Long = 0L,
      bytesRead: Long = 0L,
      parseErrors: Long = 0L
  )

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

  private def generatedTemplateBucket(index: Int): Int =
    mix(index * 1009 + generatedSeverity(index) * 9176 + 71) %
      LogHubTopTemplatesConfig.templateBuckets

  private def generatedSeverity(index: Int): Int =
    mix(index * 1664525 + 1013904223) % 5

  private def generatedTemplateTokens(index: Int): Int =
    4 + (mix(index * 65537 + 31) %
      LogHubTopTemplatesConfig.templateTokenLimit)

  private def generatedHash(index: Int, template: Int): Long =
    mix(index * 1000003 + template * 131 + 53).toLong

  private def fold(
      acc: Long,
      kind: Int,
      lineIndex: Int,
      template: Int,
      token: Int,
      value: Int,
      hash: Long,
      epoch: Long
  ): Long = {
    var x = acc ^ hash
    x = x * 1099511628211L + kind.toLong
    x = x ^ (lineIndex.toLong << 17)
    x = x + (template.toLong << 7) + token.toLong
    x = x ^ (value.toLong * 1315423911L)
    x ^ epoch
  }

  private def tokenSeparator(byte: Int): Boolean =
    byte <= 32 || byte == ','.toInt || byte == ';'.toInt || byte == '|'.toInt

  private def tokenStartAfter(
      bytes: Array[Byte],
      length: Int,
      tokensToSkip: Int
  ): Int = {
    var tokens = 0
    var inToken = false
    var i = 0
    while (i < length) {
      val sep = tokenSeparator(bytes(i) & 0xff)
      if (sep) inToken = false
      else if (!inToken) {
        if (tokens == tokensToSkip) return i
        inToken = true
        tokens += 1
      }
      i += 1
    }
    length
  }

  private def messageStart(bytes: Array[Byte], length: Int): Int = {
    val bglMessageStart = tokenStartAfter(bytes, length, 9)
    if (bglMessageStart < length) bglMessageStart
    else tokenStartAfter(bytes, length, 4)
  }

  private def countTokensFrom(
      bytes: Array[Byte],
      length: Int,
      start: Int,
      limit: Int
  ): Int = {
    var count = 0
    var inToken = false
    var i = start
    while (i < length && count < limit) {
      val sep = tokenSeparator(bytes(i) & 0xff)
      if (sep) inToken = false
      else if (!inToken) {
        inToken = true
        count += 1
      }
      i += 1
    }
    if (count == 0) 1 else count
  }

  private def templateBucketFor(bytes: Array[Byte], length: Int): Int = {
    val start = messageStart(bytes, length)
    val hash =
      if (start < length) BenchmarkInputSupport.stableHash(bytes, start, length - start)
      else BenchmarkInputSupport.stableHash(bytes, 0, length)
    BenchmarkInputSupport.positiveModulo(
      hash,
      LogHubTopTemplatesConfig.templateBuckets
    )
  }

  private def templateTokensFor(bytes: Array[Byte], length: Int): Int = {
    val start = messageStart(bytes, length)
    countTokensFrom(
      bytes,
      length,
      start,
      LogHubTopTemplatesConfig.templateTokenLimit
    )
  }

  private def countFileBackedRows(): Int = {
    val cfg = LogHubTopTemplatesConfig
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

  private def loadGeneratedInput(): InputData = {
    val cfg = LogHubTopTemplatesConfig
    val templates = new Array[Int](cfg.lines)
    val tokenCounts = new Array[Int](cfg.lines)
    val hashes = new Array[Long](cfg.lines)
    var i = 0
    while (i < cfg.lines) {
      val template = generatedTemplateBucket(i)
      templates(i) = template
      tokenCounts(i) = generatedTemplateTokens(i)
      hashes(i) = generatedHash(i, template)
      i += 1
    }
    new InputData("generated-loghub-top-templates", 0, templates, tokenCounts, hashes)
  }

  private def loadFileBackedInput(): InputData = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.inputPaths.isEmpty)
      throw new IllegalArgumentException(
        "LOGHUB_TOP_INPUT_MODE=file-backed requires LOGHUB_TOP_INPUT or LOGHUB_TOP_INPUTS"
      )
    val rows = countFileBackedRows()
    if (rows <= 0)
      throw new IllegalArgumentException(
        s"LogHub input '${cfg.inputPath}' did not contain usable rows"
      )

    val templates = new Array[Int](rows)
    val tokenCounts = new Array[Int](rows)
    val hashes = new Array[Long](rows)
    var index = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length && index < rows) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0 && index < rows) {
          if (length > 0) {
            val line = reader.bytes
            val template = templateBucketFor(line, length)
            templates(index) = template
            tokenCounts(index) = templateTokensFor(line, length)
            hashes(index) =
              BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                (template.toLong * 1099511628211L)
            index += 1
          }
          length = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }

    new InputData(
      if (cfg.inputPaths.length == 1) "real-loghub-top-templates-file-backed"
      else s"real-loghub-top-templates-${cfg.inputPaths.length}files",
      cfg.inputPaths.length,
      templates,
      tokenCounts,
      hashes
    )
  }

  private def loadInput(): InputData =
    if (LogHubTopTemplatesConfig.fileBackedInput) loadFileBackedInput()
    else loadGeneratedInput()

  private def streamingInputLabel: String = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.inputPaths.length == 1) "real-loghub-top-templates-streaming-file"
    else s"real-loghub-top-templates-streaming-file-${cfg.inputPaths.length}files"
  }

  private def inputLabelFor(outcome: RunOutcome): String = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.streamingFileInput) streamingInputLabel
    else inputData.label
  }

  private def loadedEventsFor(outcome: RunOutcome): Long = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.streamingFileInput) outcome.recordsRead
    else inputData.lines.toLong
  }

  private def inputFilesFor(outcome: RunOutcome): Int = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.streamingFileInput) cfg.inputPaths.length
    else inputData.inputFiles
  }

  private def openStreamingInput(): BenchmarkInputSupport.StreamingByteLineSource = {
    val cfg = LogHubTopTemplatesConfig
    if (cfg.inputPaths.isEmpty)
      throw new IllegalArgumentException(
        "LOGHUB_TOP_INPUT_MODE=streaming-file requires LOGHUB_TOP_INPUT or LOGHUB_TOP_INPUTS"
      )
    BenchmarkInputSupport.openStreamingByteLines(cfg.inputPaths)
  }

  private def readNextUsableLine(
      source: BenchmarkInputSupport.StreamingByteLineSource
  ): Int = {
    var length = source.readLine()
    while (length == 0) length = source.readLine()
    length
  }

  private def clearCounts(counts: Array[Int]): Unit = {
    var i = 0
    while (i < counts.length) {
      counts(i) = 0
      i += 1
    }
  }

  private def emitTopTemplates(
      counts: Array[Int],
      epochStart: Long,
      checksum0: Long,
      output0: Long
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val topKeys = Array.fill[Int](cfg.topK)(-1)
    val topCounts = new Array[Int](cfg.topK)

    def better(count: Int, key: Int, otherCount: Int, otherKey: Int): Boolean =
      count > otherCount || (count == otherCount && (otherKey < 0 || key < otherKey))

    var key = 0
    while (key < counts.length) {
      val count = counts(key)
      if (count > 0 && better(count, key, topCounts(cfg.topK - 1), topKeys(cfg.topK - 1))) {
        var pos = cfg.topK - 1
        while (pos > 0 && better(count, key, topCounts(pos - 1), topKeys(pos - 1))) {
          topCounts(pos) = topCounts(pos - 1)
          topKeys(pos) = topKeys(pos - 1)
          pos -= 1
        }
        topCounts(pos) = count
        topKeys(pos) = key
      }
      key += 1
    }

    var checksum = checksum0
    var outputCount = output0
    var rank = 0
    while (rank < cfg.topK && topKeys(rank) >= 0) {
      val template = topKeys(rank)
      val count = topCounts(rank)
      checksum = fold(
        checksum,
        70,
        epochStart.toInt,
        template,
        rank,
        count,
        (template.toLong << 32) ^ count.toLong ^ rank.toLong,
        epochStart
      )
      outputCount += 1L
      rank += 1
    }
    RunOutcome(checksum, outputCount)
  }

  private def emitTopTemplatesFromOperator(
      stream: RiftRegion.StreamingRegion^,
      topK: RiftRegion.EpochTopKByKey^{stream},
      epochStart: Long,
      checksum0: Long,
      output0: Long
  ): RunOutcome = {
    val length = RiftRegion.finishEpochTopKByKey(stream, topK)
    var checksum = checksum0
    var outputCount = output0
    var rank = 0
    while (rank < length) {
      val template = RiftRegion.epochTopKKey(stream, topK, rank)
      val count = RiftRegion.epochTopKCount(stream, topK, rank)
      checksum = fold(
        checksum,
        70,
        epochStart.toInt,
        template,
        rank,
        count,
        (template.toLong << 32) ^ count.toLong ^ rank.toLong,
        epochStart
      )
      outputCount += 1L
      rank += 1
    }
    RunOutcome(checksum, outputCount)
  }

  private def runHeapSummaryOnly(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      clearCounts(counts)
      var i = epochStart
      while (i < epochEnd) {
        val template = input.templates(i)
        val tokenCount = input.tokenCounts(i)
        counts(template) += tokenCount
        if (i % cfg.sampleEvery == 0)
          checksum =
            fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
        i += 1
      }
      val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runHeapNatural(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      var head: HeapRecord = null
      var tail: HeapRecord = null

      def append(record: HeapRecord): Unit =
        if (head == null) {
          head = record
          tail = record
        } else {
          tail.next = record
          tail = record
        }

      var i = epochStart
      while (i < epochEnd) {
        val template = input.templates(i)
        val tokenCount = input.tokenCounts(i)
        var token = 0
        while (token < tokenCount) {
          append(
            new HeapRecord(
              i,
              template,
              token,
              input.hashes(i) ^ (token.toLong * 1315423911L),
              null
            )
          )
          token += 1
        }
        if (i % cfg.sampleEvery == 0)
          checksum =
            fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
        i += 1
      }

      clearCounts(counts)
      var record = head
      while (record != null) {
        counts(record.template) += 1
        record = record.next
      }
      val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runHeapRetainedDropAnchor(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      clearCounts(counts)
      var head: HeapRecord = null
      var tail: HeapRecord = null
      var retainedCount = 0

      def append(record: HeapRecord): Unit = {
        record.next = head
        if (head == null) tail = record
        head = record
        retainedCount += 1
        counts(record.template) += 1
      }

      var i = epochStart
      while (i < epochEnd) {
        val template = input.templates(i)
        val tokenCount = input.tokenCounts(i)
        var token = 0
        while (token < tokenCount) {
          append(
            new HeapRecord(
              i,
              template,
              token,
              input.hashes(i) ^ (token.toLong * 1315423911L),
              null
            )
          )
          token += 1
        }
        if (i % cfg.sampleEvery == 0)
          checksum =
            fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
        i += 1
      }

      if (head != null && tail != null)
        retainedAnchor =
          (retainedAnchor * 1099511628211L) ^
            head.hash ^
            (tail.hash << 1) ^
            retainedCount.toLong ^
            epochStart.toLong
      val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runCheckedRetainedBody()(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      clearCounts(counts)

      RiftRegion.epoch { region ?=>
        final class CheckedRecord(
            val lineIndex: Int,
            val template: Int,
            val token: Int,
            val hash: Long
        ) {
          var next: CheckedRecord^{region} = null
        }

        var head: CheckedRecord^{region} = null
        var tail: CheckedRecord^{region} = null
        var retainedCount = 0

        def append(record: CheckedRecord^{region}): Unit = {
          record.next = head
          if (head == null) tail = record
          head = record
          retainedCount += 1
          counts(record.template) += 1
        }

        var i = epochStart
        while (i < epochEnd) {
          val template = input.templates(i)
          val tokenCount = input.tokenCounts(i)
          var token = 0
          while (token < tokenCount) {
            val record: CheckedRecord^{region} =
              RiftRegion.allocOpen(
                new CheckedRecord(
                  i,
                  template,
                  token,
                  input.hashes(i) ^ (token.toLong * 1315423911L)
                )
              )
            append(record)
            token += 1
          }
          if (i % cfg.sampleEvery == 0)
            checksum =
              fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
          i += 1
        }

        if (head != null && tail != null)
          retainedAnchor =
            (retainedAnchor * 1099511628211L) ^
              head.hash ^
              (tail.hash << 1) ^
              retainedCount.toLong ^
              epochStart.toLong
      }

      val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runCheckedRetainedStream(): RunOutcome =
    RiftRegion.streamingOpenHandle {
      runCheckedRetainedHandleBody()
    }

  private def runCheckedRetainedStreamLegacy(): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runCheckedRetainedBody()
    }

  private def runCheckedRetainedScoped(): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedRetainedBody()
    }

  private def runCheckedRetainedHandleBody()(using
      stream: RiftOpenStreamingHandle^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      clearCounts(counts)

      RiftRegion.resetOpenHandle { region ?=>
        final class CheckedRecord(
            val lineIndex: Int,
            val template: Int,
            val token: Int,
            val hash: Long
        ) {
          var next: CheckedRecord^{region} = null
        }

        var head: CheckedRecord^{region} = null
        var tail: CheckedRecord^{region} = null
        var retainedCount = 0

        def append(record: CheckedRecord^{region}): Unit = {
          record.next = head
          if (head == null) tail = record
          head = record
          retainedCount += 1
          counts(record.template) += 1
        }

        var i = epochStart
        while (i < epochEnd) {
          val template = input.templates(i)
          val tokenCount = input.tokenCounts(i)
          var token = 0
          while (token < tokenCount) {
            val record: CheckedRecord^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new CheckedRecord(
                  i,
                  template,
                  token,
                  input.hashes(i) ^ (token.toLong * 1315423911L)
                )
              )
            append(record)
            token += 1
          }
          if (i % cfg.sampleEvery == 0)
            checksum =
              fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
          i += 1
        }

        if (head != null && tail != null)
          retainedAnchor =
            (retainedAnchor * 1099511628211L) ^
              head.hash ^
              (tail.hash << 1) ^
              retainedCount.toLong ^
              epochStart.toLong
      }

      val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runCheckedTopKBody()(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val input = inputData
    val topK = RiftRegion.epochTopKByKey(cfg.templateBuckets, cfg.topK)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var epochStart = 0
    while (epochStart < input.lines) {
      val epochEnd = math.min(input.lines, epochStart + cfg.linesPerEpoch)
      RiftRegion.beginEpochTopKByKey(stream, topK)

      RiftRegion.epoch { region ?=>
        final class CheckedRecord(
            val lineIndex: Int,
            val template: Int,
            val token: Int,
            val hash: Long
        ) {
          var next: CheckedRecord^{region} = null
        }

        var head: CheckedRecord^{region} = null
        var tail: CheckedRecord^{region} = null
        var retainedCount = 0

        def append(record: CheckedRecord^{region}): Unit = {
          record.next = head
          if (head == null) tail = record
          head = record
          retainedCount += 1
          RiftRegion.incrementEpochTopKByKey(stream, topK, record.template)
        }

        var i = epochStart
        while (i < epochEnd) {
          val template = input.templates(i)
          val tokenCount = input.tokenCounts(i)
          var token = 0
          while (token < tokenCount) {
            val record: CheckedRecord^{region} =
              RiftRegion.allocOpen(
                new CheckedRecord(
                  i,
                  template,
                  token,
                  input.hashes(i) ^ (token.toLong * 1315423911L)
                )
              )
            append(record)
            token += 1
          }
          if (i % cfg.sampleEvery == 0)
            checksum =
              fold(checksum, 99, i, template, 0, tokenCount, input.hashes(i), epochStart)
          i += 1
        }

        if (head != null && tail != null)
          retainedAnchor =
            (retainedAnchor * 1099511628211L) ^
              head.hash ^
              (tail.hash << 1) ^
              retainedCount.toLong ^
              epochStart.toLong
      }

      val emitted =
        emitTopTemplatesFromOperator(stream, topK, epochStart.toLong, checksum, outputCount)
      checksum = emitted.checksum
      outputCount = emitted.outputCount
      epochStart = epochEnd
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount)
  }

  private def runCheckedTopKStream(): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runCheckedTopKBody()
    }

  private def runCheckedTopKScoped(): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedTopKBody()
    }

  private def runHeapSummaryOnlyStreaming(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        clearCounts(counts)
        while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
          val length = readNextUsableLine(source)
          if (length < 0) done = true
          else {
            val line = source.bytes
            val template = templateBucketFor(line, length)
            val tokenCount = templateTokensFor(line, length)
            val hash =
              BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                (template.toLong * 1099511628211L)
            counts(template) += tokenCount
            if (recordsRead % cfg.sampleEvery == 0)
              checksum =
                fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
            recordsRead += 1
            recordsInEpoch += 1
          }
        }
        if (recordsInEpoch > 0) {
          val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runHeapNaturalStreaming(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        var head: HeapRecord = null
        var tail: HeapRecord = null

        def append(record: HeapRecord): Unit =
          if (head == null) {
            head = record
            tail = record
          } else {
            tail.next = record
            tail = record
          }

        while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
          val length = readNextUsableLine(source)
          if (length < 0) done = true
          else {
            val line = source.bytes
            val template = templateBucketFor(line, length)
            val tokenCount = templateTokensFor(line, length)
            val hash =
              BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                (template.toLong * 1099511628211L)
            var token = 0
            while (token < tokenCount) {
              append(
                new HeapRecord(
                  recordsRead,
                  template,
                  token,
                  hash ^ (token.toLong * 1315423911L),
                  null
                )
              )
              token += 1
            }
            if (recordsRead % cfg.sampleEvery == 0)
              checksum =
                fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
            recordsRead += 1
            recordsInEpoch += 1
          }
        }

        if (recordsInEpoch > 0) {
          clearCounts(counts)
          var record = head
          while (record != null) {
            counts(record.template) += 1
            record = record.next
          }
          val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runHeapRetainedDropAnchorStreaming(): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        var head: HeapRecord = null
        var tail: HeapRecord = null
        var retainedCount = 0
        clearCounts(counts)

        def append(record: HeapRecord): Unit = {
          record.next = head
          if (head == null) tail = record
          head = record
          retainedCount += 1
          counts(record.template) += 1
        }

        while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
          val length = readNextUsableLine(source)
          if (length < 0) done = true
          else {
            val line = source.bytes
            val template = templateBucketFor(line, length)
            val tokenCount = templateTokensFor(line, length)
            val hash =
              BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                (template.toLong * 1099511628211L)
            var token = 0
            while (token < tokenCount) {
              append(
                new HeapRecord(
                  recordsRead,
                  template,
                  token,
                  hash ^ (token.toLong * 1315423911L),
                  null
                )
              )
              token += 1
            }
            if (recordsRead % cfg.sampleEvery == 0)
              checksum =
                fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
            recordsRead += 1
            recordsInEpoch += 1
          }
        }

        if (recordsInEpoch > 0) {
          if (head != null && tail != null)
            retainedAnchor =
              (retainedAnchor * 1099511628211L) ^
                head.hash ^
                (tail.hash << 1) ^
                retainedCount.toLong ^
                epochStart.toLong
          val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runCheckedRetainedStreamingBody()(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        clearCounts(counts)

        RiftRegion.epoch { region ?=>
          final class CheckedRecord(
              val lineIndex: Int,
              val template: Int,
              val token: Int,
              val hash: Long
          ) {
            var next: CheckedRecord^{region} = null
          }

          var head: CheckedRecord^{region} = null
          var tail: CheckedRecord^{region} = null
          var retainedCount = 0

          def append(record: CheckedRecord^{region}): Unit = {
            record.next = head
            if (head == null) tail = record
            head = record
            retainedCount += 1
            counts(record.template) += 1
          }

          while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
            val length = readNextUsableLine(source)
            if (length < 0) done = true
            else {
              val line = source.bytes
              val template = templateBucketFor(line, length)
              val tokenCount = templateTokensFor(line, length)
              val hash =
                BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                  (template.toLong * 1099511628211L)
              var token = 0
              while (token < tokenCount) {
                val record: CheckedRecord^{region} =
                  RiftRegion.allocOpen(
                    new CheckedRecord(
                      recordsRead,
                      template,
                      token,
                      hash ^ (token.toLong * 1315423911L)
                    )
                  )
                append(record)
                token += 1
              }
              if (recordsRead % cfg.sampleEvery == 0)
                checksum =
                  fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
              recordsRead += 1
              recordsInEpoch += 1
            }
          }

          if (head != null && tail != null)
            retainedAnchor =
              (retainedAnchor * 1099511628211L) ^
                head.hash ^
                (tail.hash << 1) ^
                retainedCount.toLong ^
                epochStart.toLong
        }

        if (recordsInEpoch > 0) {
          val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runCheckedRetainedStreamStreaming(): RunOutcome =
    RiftRegion.streamingOpenHandle {
      runCheckedRetainedHandleStreamingBody()
    }

  private def runCheckedRetainedStreamStreamingLegacy(): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runCheckedRetainedStreamingBody()
    }

  private def runCheckedRetainedScopedStreaming(): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedRetainedStreamingBody()
    }

  private def runCheckedRetainedHandleStreamingBody()(using
      stream: RiftOpenStreamingHandle^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val counts = new Array[Int](cfg.templateBuckets)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        clearCounts(counts)

        RiftRegion.resetOpenHandle { region ?=>
          final class CheckedRecord(
              val lineIndex: Int,
              val template: Int,
              val token: Int,
              val hash: Long
          ) {
            var next: CheckedRecord^{region} = null
          }

          var head: CheckedRecord^{region} = null
          var tail: CheckedRecord^{region} = null
          var retainedCount = 0

          def append(record: CheckedRecord^{region}): Unit = {
            record.next = head
            if (head == null) tail = record
            head = record
            retainedCount += 1
            counts(record.template) += 1
          }

          while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
            val length = readNextUsableLine(source)
            if (length < 0) done = true
            else {
              val line = source.bytes
              val template = templateBucketFor(line, length)
              val tokenCount = templateTokensFor(line, length)
              val hash =
                BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                  (template.toLong * 1099511628211L)
              var token = 0
              while (token < tokenCount) {
                val record: CheckedRecord^{region} =
                  RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedRecord(
                      recordsRead,
                      template,
                      token,
                      hash ^ (token.toLong * 1315423911L)
                    )
                  )
                append(record)
                token += 1
              }
              if (recordsRead % cfg.sampleEvery == 0)
                checksum =
                  fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
              recordsRead += 1
              recordsInEpoch += 1
            }
          }

          if (head != null && tail != null)
            retainedAnchor =
              (retainedAnchor * 1099511628211L) ^
                head.hash ^
                (tail.hash << 1) ^
                retainedCount.toLong ^
                epochStart.toLong
        }

        if (recordsInEpoch > 0) {
          val emitted = emitTopTemplates(counts, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runCheckedTopKStreamingBody()(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = LogHubTopTemplatesConfig
    val source = openStreamingInput()
    val topK = RiftRegion.epochTopKByKey(cfg.templateBuckets, cfg.topK)
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L
    var recordsRead = 0
    try {
      var done = false
      while (!done && recordsRead < cfg.lines) {
        val epochStart = recordsRead
        var recordsInEpoch = 0
        RiftRegion.beginEpochTopKByKey(stream, topK)

        RiftRegion.epoch { region ?=>
          final class CheckedRecord(
              val lineIndex: Int,
              val template: Int,
              val token: Int,
              val hash: Long
          ) {
            var next: CheckedRecord^{region} = null
          }

          var head: CheckedRecord^{region} = null
          var tail: CheckedRecord^{region} = null
          var retainedCount = 0

          def append(record: CheckedRecord^{region}): Unit = {
            record.next = head
            if (head == null) tail = record
            head = record
            retainedCount += 1
            RiftRegion.incrementEpochTopKByKey(stream, topK, record.template)
          }

          while (!done && recordsInEpoch < cfg.linesPerEpoch && recordsRead < cfg.lines) {
            val length = readNextUsableLine(source)
            if (length < 0) done = true
            else {
              val line = source.bytes
              val template = templateBucketFor(line, length)
              val tokenCount = templateTokensFor(line, length)
              val hash =
                BenchmarkInputSupport.stableHash(line, 0, length).toLong ^
                  (template.toLong * 1099511628211L)
              var token = 0
              while (token < tokenCount) {
                val record: CheckedRecord^{region} =
                  RiftRegion.allocOpen(
                    new CheckedRecord(
                      recordsRead,
                      template,
                      token,
                      hash ^ (token.toLong * 1315423911L)
                    )
                  )
                append(record)
                token += 1
              }
              if (recordsRead % cfg.sampleEvery == 0)
                checksum =
                  fold(checksum, 99, recordsRead, template, 0, tokenCount, hash, epochStart)
              recordsRead += 1
              recordsInEpoch += 1
            }
          }

          if (head != null && tail != null)
            retainedAnchor =
              (retainedAnchor * 1099511628211L) ^
                head.hash ^
                (tail.hash << 1) ^
                retainedCount.toLong ^
                epochStart.toLong
        }

        if (recordsInEpoch > 0) {
          val emitted =
            emitTopTemplatesFromOperator(stream, topK, epochStart.toLong, checksum, outputCount)
          checksum = emitted.checksum
          outputCount = emitted.outputCount
        }
      }
    } finally {
      source.close()
    }
    checksumSink = checksum
    outputSink = outputCount
    retainedAnchorSink = retainedAnchor
    RunOutcome(checksum, outputCount, recordsRead.toLong, source.bytesRead, 0L)
  }

  private def runCheckedTopKStreamStreaming(): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runCheckedTopKStreamingBody()
    }

  private def runCheckedTopKScopedStreaming(): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedTopKStreamingBody()
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" | "heap-immix" | "heap-natural" => "heap-natural"
      case "heap-direct-summary-only" | "heap-summary-only" =>
        "heap-summary-only"
      case "heap-retained-drop-anchor" |
          "heap-epoch-retained-no-traverse" =>
        "heap-retained-drop-anchor"
      case "checked-epoch-retained-no-traverse" |
          "checked-region-stream-retained-epoch" =>
        "checked-epoch-retained-no-traverse"
      case "checked-epoch-retained-no-traverse-legacy" |
          "checked-region-stream-retained-epoch-legacy" =>
        "checked-epoch-retained-no-traverse-legacy"
      case "checked-scoped-epoch-retained-no-traverse" |
          "checked-region-scoped-retained-epoch" =>
        "checked-scoped-epoch-retained-no-traverse"
      case "checked-epoch-topk-retained-no-traverse" |
          "checked-region-stream-epoch-topk" | "checked-topk-stream" =>
        "checked-epoch-topk-retained-no-traverse"
      case "checked-scoped-epoch-topk-retained-no-traverse" |
          "checked-region-scoped-epoch-topk" | "checked-topk-scoped" =>
        "checked-scoped-epoch-topk-retained-no-traverse"
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "checked-epoch-retained-no-traverse" |
          "checked-epoch-retained-no-traverse-legacy" |
          "checked-epoch-topk-retained-no-traverse" =>
        true
      case _ => false
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap-natural" | "heap-summary-only" |
          "heap-retained-drop-anchor" |
          "checked-epoch-retained-no-traverse" |
          "checked-epoch-retained-no-traverse-legacy" |
          "checked-scoped-epoch-retained-no-traverse" |
          "checked-epoch-topk-retained-no-traverse" |
          "checked-scoped-epoch-topk-retained-no-traverse" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub top-template mode '$other'"
        )
    }

  private def runMode(mode: String): RunOutcome = {
    val streaming = LogHubTopTemplatesConfig.streamingFileInput
    canonicalMode(mode) match {
      case "heap-natural" =>
        if (streaming) runHeapNaturalStreaming() else runHeapNatural()
      case "heap-summary-only" =>
        if (streaming) runHeapSummaryOnlyStreaming() else runHeapSummaryOnly()
      case "heap-retained-drop-anchor" =>
        if (streaming) runHeapRetainedDropAnchorStreaming()
        else runHeapRetainedDropAnchor()
      case "checked-epoch-retained-no-traverse" =>
        if (streaming) runCheckedRetainedStreamStreaming()
        else runCheckedRetainedStream()
      case "checked-epoch-retained-no-traverse-legacy" =>
        if (streaming) runCheckedRetainedStreamStreamingLegacy()
        else runCheckedRetainedStreamLegacy()
      case "checked-scoped-epoch-retained-no-traverse" =>
        if (streaming) runCheckedRetainedScopedStreaming()
        else runCheckedRetainedScoped()
      case "checked-epoch-topk-retained-no-traverse" =>
        if (streaming) runCheckedTopKStreamStreaming()
        else runCheckedTopKStream()
      case "checked-scoped-epoch-topk-retained-no-traverse" =>
        if (streaming) runCheckedTopKScopedStreaming()
        else runCheckedTopKScoped()
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub top-template mode '$other'"
        )
    }
  }

  def runBenchmark(mode: String): Unit = {
    val cfg = LogHubTopTemplatesConfig
    val canonical = canonicalMode(mode)
    val usesRift = usesRiftRuntime(mode)

    if (cfg.finalClean) {
      var run = 0
      var expected: RunOutcome = null
      while (run < cfg.benchmarkRuns) {
        val outcome = runMode(mode)
        if (run == 0) expected = outcome
        else if (outcome != expected)
          throw new IllegalStateException(
            s"final-clean mismatch mode=$mode expected=$expected actual=$outcome"
          )
        run += 1
      }

      println(
        s"RESULT name=loghub-top-templates-$canonical " +
          s"measurement_level=L1 final_clean=1 mode=$canonical " +
          s"input=${inputLabelFor(expected)} input_mode=${cfg.inputMode} " +
          s"loaded_events=${loadedEventsFor(expected)} input_files=${inputFilesFor(expected)} " +
          s"bytes_read=${expected.bytesRead} parse_errors=${expected.parseErrors} " +
          s"lines_per_epoch=${cfg.linesPerEpoch} " +
          s"template_buckets=${cfg.templateBuckets} top_k=${cfg.topK} " +
          s"runs=${cfg.benchmarkRuns} checksum=${expected.checksum} " +
          s"output_count=${expected.outputCount}"
      )
      return
    }

    val expected =
      if (cfg.streamingFileInput) runHeapNaturalStreaming()
      else runHeapNatural()
    System.gc()

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode)
      if (outcome != expected)
        throw new IllegalStateException(
          s"warmup mismatch mode=$mode expected=$expected actual=$outcome"
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
      s"Running loghub-top-templates-$canonical for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val outcome = runMode(mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (outcome != expected)
        throw new IllegalStateException(
          s"checksum mismatch mode=$mode expected=$expected actual=$outcome"
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
      f"RESULT name=loghub-top-templates-$canonical " +
        f"mode=$canonical input=${inputLabelFor(expected)} " +
        f"input_mode=${cfg.inputMode} " +
        f"loaded_events=${loadedEventsFor(expected)}%d " +
        f"input_files=${inputFilesFor(expected)}%d " +
        f"bytes_read=${expected.bytesRead}%d " +
        f"parse_errors=${expected.parseErrors}%d " +
        f"lines_per_epoch=${cfg.linesPerEpoch}%d " +
        f"template_buckets=${cfg.templateBuckets}%d " +
        f"top_k=${cfg.topK}%d " +
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

  def printConfig(mode: String): Unit = {
    val cfg = LogHubTopTemplatesConfig
    val label =
      if (cfg.streamingFileInput) streamingInputLabel
      else inputData.label
    val lines =
      if (cfg.streamingFileInput) -1L
      else inputData.lines.toLong
    println(
      s"CONFIG mode=$mode canonical_mode=${canonicalMode(mode)} " +
        s"lines=$lines configured_lines=${cfg.lines} " +
        s"lines_per_epoch=${cfg.linesPerEpoch} " +
        s"template_buckets=${cfg.templateBuckets} " +
        s"template_token_limit=${cfg.templateTokenLimit} top_k=${cfg.topK} " +
        s"sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns} input=$label " +
        s"input_mode=${cfg.inputMode} input_path=${cfg.inputPath}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "checked-epoch-retained-no-traverse" |
          "checked-epoch-retained-no-traverse-legacy" |
          "checked-epoch-topk-retained-no-traverse" |
          "checked-scoped-epoch-retained-no-traverse" |
          "checked-scoped-epoch-topk-retained-no-traverse" =>
        true
      case _ => false
    }
}

@main def LogHubTopTemplatesMatrix(
    mode: String = "heap-natural"
): Unit = {
  LogHubTopTemplatesMatrixHelpers.validateMode(mode)
  LogHubTopTemplatesMatrixHelpers.printConfig(mode)

  val usesRift = LogHubTopTemplatesMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    LogHubTopTemplatesMatrixHelpers.runBenchmark(mode)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
