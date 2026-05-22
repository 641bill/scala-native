import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftOpenStreamingHandle, RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object DSPBenchRegionConfig {
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

  val events: Int = envInt("DSPBENCH_EVENTS", 100000)
  val eventsPerBucket: Int = envInt("DSPBENCH_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("DSPBENCH_LIVE_BUCKETS", 4)
  val deviceBuckets: Int = envInt("DSPBENCH_DEVICE_BUCKETS", 4096)
  val fraudEntityBuckets: Int = envInt("DSPBENCH_FRAUD_ENTITY_BUCKETS", 32768)
  val logStatusBuckets: Int = envInt("DSPBENCH_LOG_STATUS_BUCKETS", 1024)
  val logMinuteBuckets: Int = envInt("DSPBENCH_LOG_MINUTE_BUCKETS", 1440)
  val movingAverageWindow: Int =
    envInt("DSPBENCH_MOVING_AVERAGE_WINDOW", 128)
  val spikeThresholdPermille: Int =
    envInt("DSPBENCH_SPIKE_THRESHOLD_PERMILLE", 30)
  val fraudStateWindow: Int = envInt("DSPBENCH_FRAUD_STATE_WINDOW", 8)
  val fraudAlertThresholdPermille: Int =
    envInt("DSPBENCH_FRAUD_ALERT_THRESHOLD_PERMILLE", 700)
  val sampleEvery: Int = envInt("DSPBENCH_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("DSPBENCH_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("DSPBENCH_BENCHMARK_RUNS", 3)
  val diagnostics: Boolean = envFlag("DSPBENCH_DIAG")
  val finalClean: Boolean =
    envFlag("RIFT_FINAL_CLEAN") ||
      sys.env
        .get("RIFT_EVAL_MEASUREMENT_LEVEL")
        .exists(_.equalsIgnoreCase("L1"))

  private val inputPathsRaw: String = {
    val multiple = BenchmarkInputSupport.envString("DSPBENCH_INPUTS")
    if (multiple.nonEmpty) multiple
    else BenchmarkInputSupport.envString("DSPBENCH_INPUT")
  }

  val inputPaths: Array[String] =
    if (inputPathsRaw.isEmpty) Array.empty
    else inputPathsRaw.split(",").map(_.trim).filter(_.nonEmpty)

  val inputPath: String = inputPaths.mkString(",")

  val inputMode: String = {
    val raw = BenchmarkInputSupport.envString("DSPBENCH_INPUT_MODE")
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
          s"unknown DSPBENCH_INPUT_MODE '$other'; expected generated or file-backed"
        )
    }
}

object DSPBenchRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var retainedAnchorSink = 0L

  private final class MemoryCostDiagnostics {
    var bucketSwitchNanos = 0L
    var appendNanos = 0L
    var predictNanos = 0L
    var closeCursorNanos = 0L
    var finalCloseNanos = 0L
    var bucketSwitches = 0L
    var appendedRecords = 0L
    var closedRecords = 0L
    var closeBuckets = 0L

    def print(query: String, mode: String): Unit = {
      def ms(nanos: Long): Double = nanos / 1000000.0
      val estimatedExpiredCloseNanos =
        math.max(0L, closeCursorNanos - finalCloseNanos)
      val estimatedBucketOpenNanos =
        math.max(0L, bucketSwitchNanos - estimatedExpiredCloseNanos)
      println(
        f"DSPBENCH_DIAG query=$query mode=$mode " +
          s"bucket_switches=$bucketSwitches appended_records=$appendedRecords " +
          s"closed_records=$closedRecords close_buckets=$closeBuckets " +
          f"bucket_switch_ms=${ms(bucketSwitchNanos)}%.3f " +
          f"estimated_bucket_open_ms=${ms(estimatedBucketOpenNanos)}%.3f " +
          f"append_ms=${ms(appendNanos)}%.3f " +
          f"predict_ms=${ms(predictNanos)}%.3f " +
          f"close_cursor_ms=${ms(closeCursorNanos)}%.3f " +
          f"final_close_ms=${ms(finalCloseNanos)}%.3f"
      )
    }
  }

  private final class HeapRecord(
      val kind: Int,
      val eventIndex: Int,
      val device: Int,
      val valueScaled: Int,
      val avgScaled: Int,
      val spike: Boolean,
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
      val device: Int,
      val valueScaled: Int,
      val avgScaled: Int,
      val spike: Boolean,
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
      val device: Int,
      val valueScaled: Int,
      val avgScaled: Int,
      val spike: Boolean,
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
      val requestedEvents: Int,
      val uniqueInputLines: Int,
      val inputFiles: Int
  ) {
    def replayCount: Int =
      if (uniqueInputLines <= 0) 0
      else (requestedEvents + uniqueInputLines - 1) / uniqueInputLines
  }

  private abstract class SensorConsumer {
    def apply(
        eventIndex: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit
  }

  private final class MovingAverageState {
    private val cfg = DSPBenchRegionConfig
    private val windowSize = cfg.movingAverageWindow
    private val values =
      new Array[Int](cfg.deviceBuckets * cfg.movingAverageWindow)
    private val sums = new Array[Long](cfg.deviceBuckets)
    private val counts = new Array[Int](cfg.deviceBuckets)
    private val positions = new Array[Int](cfg.deviceBuckets)

    def update(device: Int, valueScaled: Int): Int = {
      val count = counts(device)
      val base = device * windowSize
      val pos = positions(device)
      var sum = sums(device)

      if (count >= windowSize) {
        sum -= values(base + pos).toLong
      } else {
        counts(device) = count + 1
      }

      values(base + pos) = valueScaled
      sum += valueScaled.toLong
      sums(device) = sum
      positions(device) = if (pos + 1 == windowSize) 0 else pos + 1
      (sum / counts(device).toLong).toInt
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

  private var cachedInputQuery: String = null
  private var cachedInput: InputData = null

  private def inputDataFor(query: String): InputData = {
    if (cachedInput == null || cachedInputQuery != query) {
      cachedInput = loadInput(query)
      cachedInputQuery = query
    }
    cachedInput
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

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = DSPBenchRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(startEvent: Long): Long = {
    val cfg = DSPBenchRegionConfig
    startEvent - cfg.eventsPerBucket.toLong * (cfg.liveBuckets - 1).toLong
  }

  private def generatedDevice(index: Int): Int =
    mix(index * 1103515245 + 12345) % DSPBenchRegionConfig.deviceBuckets

  private def generatedValue(index: Int): Int =
    18000 + (mix(index * 1664525 + 1013904223) % 9000)

  private def generatedHash(index: Int, device: Int, value: Int): Long =
    mix(index * 1000003 + device * 131 + value).toLong

  private def fold(
      acc: Long,
      kind: Int,
      eventIndex: Int,
      device: Int,
      valueScaled: Int,
      avgScaled: Int,
      spike: Boolean,
      hash: Long,
      bucket: Long
  ): Long = {
    var x = acc ^ hash
    x = x * 1099511628211L + kind.toLong
    x = x ^ (eventIndex.toLong << 17)
    x = x + (device.toLong << 11)
    x = x ^ (valueScaled.toLong * 1315423911L)
    x = x + (avgScaled.toLong << 3)
    if (spike) x ^= 0x9e3779b97f4a7c15L
    x ^ bucket
  }

  private def averageQuery(query: String): Boolean =
    query == "q1-moving-average" || query == "q2-spike-window"

  private def candidateQuery(query: String): Boolean =
    query == "q2-spike-window"

  private def windowQuery(query: String): Boolean =
    query == "q2-spike-window" ||
      query == "fraud-q2-alert-window" ||
      query == "log-q2-window"

  private def fraudQuery(query: String): Boolean =
    query == "fraud-q0-parse" ||
      query == "fraud-q1-predict" ||
      query == "fraud-q2-alert-window"

  private def fraudPredictQuery(query: String): Boolean =
    query == "fraud-q1-predict" || query == "fraud-q2-alert-window"

  private def fraudAlertQuery(query: String): Boolean =
    query == "fraud-q2-alert-window"

  private def logQuery(query: String): Boolean =
    query == "log-q0-parse" ||
      query == "log-q1-status" ||
      query == "log-q2-window"

  private def logStatusQuery(query: String): Boolean =
    query == "log-q1-status" || query == "log-q2-window"

  private def logWindowQuery(query: String): Boolean =
    query == "log-q2-window"

  private def windowRecordKind(query: String): Int =
    if (fraudQuery(query)) 130
    else if (logQuery(query)) 230
    else 30

  private def windowSummaryKind(query: String): Int =
    if (fraudQuery(query)) 144
    else if (logQuery(query)) 244
    else 44

  private def keyBucketCount(query: String): Int =
    if (fraudQuery(query)) DSPBenchRegionConfig.fraudEntityBuckets
    else if (logQuery(query)) DSPBenchRegionConfig.logStatusBuckets
    else DSPBenchRegionConfig.deviceBuckets

  private def isSpike(valueScaled: Int, avgScaled: Int): Boolean = {
    val diff = math.abs(valueScaled - avgScaled)
    val threshold =
      math.max(1, (math.abs(avgScaled) * DSPBenchRegionConfig.spikeThresholdPermille) / 1000)
    diff > threshold
  }

  private def loadInput(query: String): InputData = {
    val cfg = DSPBenchRegionConfig
    if (cfg.fileBackedInput) {
      if (cfg.inputPaths.isEmpty)
        throw new IllegalArgumentException(
          "DSPBENCH_INPUT_MODE=file-backed requires DSPBENCH_INPUT or DSPBENCH_INPUTS"
        )
      val rows = countFileBackedRows(query)
      if (rows <= 0)
        throw new IllegalArgumentException(
          s"DSPBench input '${cfg.inputPath}' did not contain usable rows for query '$query'"
        )
      new InputData(
        if (fraudQuery(query)) {
          if (cfg.inputPaths.length == 1) "real-dspbench-fraud-file-backed"
          else s"real-dspbench-fraud-file-backed-${cfg.inputPaths.length}files"
        } else if (logQuery(query)) {
          if (cfg.inputPaths.length == 1) "real-dspbench-log-file-backed"
          else s"real-dspbench-log-file-backed-${cfg.inputPaths.length}files"
        } else {
          if (cfg.inputPaths.length == 1) "real-dspbench-spike-file-backed"
          else s"real-dspbench-spike-file-backed-${cfg.inputPaths.length}files"
        },
        cfg.events,
        rows,
        cfg.inputPaths.length
      )
    } else {
      new InputData(
        if (fraudQuery(query)) "generated-dspbench-fraud-shaped"
        else if (logQuery(query)) "generated-dspbench-log-shaped"
        else "generated-dspbench-spike-shaped",
        cfg.events,
        cfg.events,
        0
      )
    }
  }

  private def isWhitespace(value: Int): Boolean =
    value == ' '.toInt || value == '\t'.toInt ||
      value == '\r'.toInt || value == '\n'.toInt

  private def skipWhitespace(bytes: Array[Byte], length: Int, index: Int): Int = {
    var i = index
    while (i < length && isWhitespace(bytes(i) & 0xff)) i += 1
    i
  }

  private def skipToken(bytes: Array[Byte], length: Int, index: Int): Int = {
    var i = index
    while (i < length && !isWhitespace(bytes(i) & 0xff)) i += 1
    i
  }

  private def fieldStart(
      bytes: Array[Byte],
      length: Int,
      targetField: Int
  ): Int = {
    var field = 0
    var i = 0
    while (field <= targetField) {
      i = skipWhitespace(bytes, length, i)
      if (i >= length) return -1
      if (field == targetField) return i
      i = skipToken(bytes, length, i)
      field += 1
    }
    -1
  }

  private def parseIntAt(bytes: Array[Byte], length: Int, start: Int): Int = {
    var i = start
    var sign = 1
    if (i < length && bytes(i) == '-'.toByte) {
      sign = -1
      i += 1
    }
    var value = 0
    var seen = false
    while (i < length && !isWhitespace(bytes(i) & 0xff)) {
      val ch = bytes(i) & 0xff
      if (ch >= '0'.toInt && ch <= '9'.toInt) {
        value = value * 10 + (ch - '0'.toInt)
        seen = true
      } else return Int.MinValue
      i += 1
    }
    if (seen) value * sign else Int.MinValue
  }

  private def parseIntUntil(
      bytes: Array[Byte],
      length: Int,
      start: Int,
      stop: Int
  ): Int = {
    var i = start
    var value = 0
    var seen = false
    while (i < length && bytes(i).toInt != stop && !isWhitespace(bytes(i) & 0xff)) {
      val ch = bytes(i) & 0xff
      if (ch >= '0'.toInt && ch <= '9'.toInt) {
        value = value * 10 + (ch - '0'.toInt)
        seen = true
      } else return Int.MinValue
      i += 1
    }
    if (seen) value else Int.MinValue
  }

  private def parseScaledAt(
      bytes: Array[Byte],
      length: Int,
      start: Int
  ): Int = {
    var i = start
    var sign = 1
    if (i < length && bytes(i) == '-'.toByte) {
      sign = -1
      i += 1
    }
    var integer = 0
    var frac = 0
    var fracDigits = 0
    var seen = false
    var dot = false
    while (i < length && !isWhitespace(bytes(i) & 0xff)) {
      val ch = bytes(i) & 0xff
      if (ch == '.'.toInt && !dot) dot = true
      else if (ch >= '0'.toInt && ch <= '9'.toInt) {
        seen = true
        if (dot) {
          if (fracDigits < 3) {
            frac = frac * 10 + (ch - '0'.toInt)
            fracDigits += 1
          }
        } else {
          integer = integer * 10 + (ch - '0'.toInt)
        }
      } else return Int.MinValue
      i += 1
    }
    if (!seen) Int.MinValue
    else {
      while (fracDigits < 3) {
        frac *= 10
        fracDigits += 1
      }
      sign * (integer * 1000 + frac)
    }
  }

  private final class ParsedKeyValue {
    var key: Int = -1
    var value: Int = 0
    var hash: Long = 0L
  }

  private final class ParsedCommonLog {
    var statusBucket: Int = -1
    var minuteBucket: Int = 0
    var byteSize: Int = 0
    var requestHash: Int = 0
    var hash: Long = 0L
  }

  private def invalidate(out: ParsedKeyValue): Unit = {
    out.key = -1
    out.value = 0
    out.hash = 0L
  }

  private def invalidate(out: ParsedCommonLog): Unit = {
    out.statusBucket = -1
    out.minuteBucket = 0
    out.byteSize = 0
    out.requestHash = 0
    out.hash = 0L
  }

  private def parseSensorInto(
      bytes: Array[Byte],
      length: Int,
      out: ParsedKeyValue
  ): Unit = {
    val deviceStart = fieldStart(bytes, length, 3)
    val valueStart = fieldStart(bytes, length, 4)
    if (deviceStart < 0 || valueStart < 0) {
      invalidate(out)
      return
    }
    val rawDevice = parseIntAt(bytes, length, deviceStart)
    val value = parseScaledAt(bytes, length, valueStart)
    if (rawDevice == Int.MinValue || value == Int.MinValue) invalidate(out)
    else {
      val device =
        BenchmarkInputSupport.positiveModulo(rawDevice, DSPBenchRegionConfig.deviceBuckets)
      val hash = BenchmarkInputSupport.stableHash(bytes, 0, length).toLong ^
        (device.toLong * 1099511628211L) ^
        (value.toLong * 1315423911L)
      out.key = device
      out.value = value
      out.hash = hash
    }
  }

  private def csvComma(bytes: Array[Byte], length: Int, start: Int): Int = {
    var i = start
    while (i < length) {
      if (bytes(i) == ','.toByte) return i
      i += 1
    }
    -1
  }

  private def fraudStateChar(value: Int, low: Int, mid: Int, high: Int): Int =
    if (value == low) 0
    else if (value == mid) 1
    else if (value == high) 2
    else -1

  private def parseFraudState(
      bytes: Array[Byte],
      start: Int,
      end: Int
  ): Int = {
    if (end - start < 3) -1
    else {
      val a = fraudStateChar(bytes(start).toInt, 'L'.toInt, 'M'.toInt, 'H'.toInt)
      val b =
        if (bytes(start + 1).toInt == 'N'.toInt) 0
        else if (bytes(start + 1).toInt == 'H'.toInt) 1
        else -1
      val c = fraudStateChar(bytes(start + 2).toInt, 'L'.toInt, 'N'.toInt, 'S'.toInt)
      if (a >= 0 && b >= 0 && c >= 0) a * 6 + b * 3 + c
      else -1
    }
  }

  private def parseFraudInto(
      bytes: Array[Byte],
      length: Int,
      out: ParsedKeyValue
  ): Unit = {
    val firstComma = csvComma(bytes, length, 0)
    if (firstComma <= 0) {
      invalidate(out)
      return
    }
    val secondComma = csvComma(bytes, length, firstComma + 1)
    val entityEnd = firstComma
    val stateStart = if (secondComma > firstComma) secondComma + 1 else firstComma + 1
    val stateEnd = length
    val entityHash =
      BenchmarkInputSupport.stableHash(bytes, 0, entityEnd)
    val rawState = parseFraudState(bytes, stateStart, stateEnd)
    val state =
      if (rawState >= 0) rawState
      else BenchmarkInputSupport.positiveModulo(
        BenchmarkInputSupport.stableHash(bytes, stateStart, stateEnd - stateStart),
        18
      )
    val entity =
      BenchmarkInputSupport.positiveModulo(
        entityHash,
        DSPBenchRegionConfig.fraudEntityBuckets
      )
    val recordHash = BenchmarkInputSupport.stableHash(bytes, 0, length).toLong ^
      (entity.toLong * 1099511628211L) ^
      (state.toLong * 1315423911L)
    out.key = entity
    out.value = state
    out.hash = recordHash
  }

  private def indexOf(
      bytes: Array[Byte],
      length: Int,
      start: Int,
      target: Int
  ): Int = {
    var i = start
    while (i < length) {
      if ((bytes(i) & 0xff) == target) return i
      i += 1
    }
    -1
  }

  private def parseCommonLogInto(
      bytes: Array[Byte],
      length: Int,
      out: ParsedCommonLog
  ): Unit = {
    val ipEnd = indexOf(bytes, length, 0, ' '.toInt)
    if (ipEnd <= 0) {
      invalidate(out)
      return
    }

    val openBracket = indexOf(bytes, length, ipEnd + 1, '['.toInt)
    if (openBracket < 0) {
      invalidate(out)
      return
    }
    val hourColon = indexOf(bytes, length, openBracket + 1, ':'.toInt)
    if (hourColon < 0 || hourColon + 5 >= length) {
      invalidate(out)
      return
    }
    val hour = parseIntUntil(bytes, length, hourColon + 1, ':'.toInt)
    val minuteColon = indexOf(bytes, length, hourColon + 1, ':'.toInt)
    if (hour == Int.MinValue || minuteColon < 0) {
      invalidate(out)
      return
    }
    val minute = parseIntUntil(bytes, length, minuteColon + 1, ':'.toInt)
    if (minute == Int.MinValue) {
      invalidate(out)
      return
    }

    val quoteStart = indexOf(bytes, length, minuteColon + 1, '"'.toInt)
    if (quoteStart < 0) {
      invalidate(out)
      return
    }
    val quoteEnd = indexOf(bytes, length, quoteStart + 1, '"'.toInt)
    if (quoteEnd < 0) {
      invalidate(out)
      return
    }

    var statusStart = skipWhitespace(bytes, length, quoteEnd + 1)
    val status = parseIntAt(bytes, length, statusStart)
    if (status == Int.MinValue) {
      invalidate(out)
      return
    }
    val bytesStart = skipWhitespace(bytes, length, skipToken(bytes, length, statusStart))
    val byteSize =
      if (bytesStart >= length) 0
      else if (bytes(bytesStart).toInt == '-'.toInt) 0
      else {
        val parsed = parseIntAt(bytes, length, bytesStart)
        if (parsed == Int.MinValue) 0 else parsed
      }

    val statusBucket =
      BenchmarkInputSupport.positiveModulo(status, DSPBenchRegionConfig.logStatusBuckets)
    val minuteBucket =
      BenchmarkInputSupport.positiveModulo(hour * 60 + minute, DSPBenchRegionConfig.logMinuteBuckets)
    val requestHash =
      BenchmarkInputSupport.stableHash(bytes, quoteStart + 1, quoteEnd - quoteStart - 1)
    val ipHash = BenchmarkInputSupport.stableHash(bytes, 0, ipEnd)
    val recordHash = BenchmarkInputSupport.stableHash(bytes, 0, length).toLong ^
      (ipHash.toLong * 1099511628211L) ^
      (status.toLong * 1315423911L) ^
      (minuteBucket.toLong << 19) ^
      requestHash.toLong

    out.statusBucket = statusBucket
    out.minuteBucket = minuteBucket
    out.byteSize = byteSize
    out.requestHash = requestHash
    out.hash = recordHash
  }

  private def countFileBackedRows(query: String): Int = {
    val cfg = DSPBenchRegionConfig
    val parsed = new ParsedKeyValue
    val parsedLog = new ParsedCommonLog
    var count = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0) {
          if (length > 0) {
            if (fraudQuery(query)) {
              parseFraudInto(reader.bytes, length, parsed)
              if (parsed.key >= 0) count += 1
            } else if (logQuery(query)) {
              parseCommonLogInto(reader.bytes, length, parsedLog)
              if (parsedLog.statusBucket >= 0) count += 1
            } else {
              parseSensorInto(reader.bytes, length, parsed)
              if (parsed.key >= 0) count += 1
            }
          }
          length = reader.readLine()
        }
      } finally {
        reader.close()
      }
      pathIndex += 1
    }
    count
  }

  private def foreachGeneratedSensor(consumer: SensorConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    var i = 0
    while (i < cfg.events) {
      val device = generatedDevice(i)
      val value = generatedValue(i)
      consumer(i, device, value, generatedHash(i, device, value))
      i += 1
    }
    cfg.events
  }

  private def foreachFileBackedSensor(consumer: SensorConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    val parsed = new ParsedKeyValue
    var index = 0
    while (index < cfg.events) {
      var advanced = false
      var pathIndex = 0
      while (pathIndex < cfg.inputPaths.length && index < cfg.events) {
        val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
        try {
          var length = reader.readLine()
          while (length >= 0 && index < cfg.events) {
            if (length > 0) {
              parseSensorInto(reader.bytes, length, parsed)
              if (parsed.key >= 0) {
                consumer(index, parsed.key, parsed.value, parsed.hash ^ index.toLong)
                index += 1
                advanced = true
              }
            }
            length = reader.readLine()
          }
        } finally {
          reader.close()
        }
        pathIndex += 1
      }
      if (!advanced) {
        throw new IllegalStateException(
          s"DSPBench input '${cfg.inputPath}' stopped before producing any usable replay records"
        )
      }
    }
    index
  }

  private def foreachSensor(consumer: SensorConsumer^): Int =
    if (DSPBenchRegionConfig.fileBackedInput) foreachFileBackedSensor(consumer)
    else foreachGeneratedSensor(consumer)

  private abstract class FraudConsumer {
    def apply(
        eventIndex: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit
  }

  private final class FraudPredictorState {
    private val cfg = DSPBenchRegionConfig
    private val last = new Array[Int](cfg.fraudEntityBuckets)
    private val seen = new Array[Boolean](cfg.fraudEntityBuckets)
    private val total = new Array[Int](cfg.fraudEntityBuckets)
    private val misses = new Array[Int](cfg.fraudEntityBuckets)
    private val positions = new Array[Int](cfg.fraudEntityBuckets)
    private val history =
      new Array[Int](cfg.fraudEntityBuckets * cfg.fraudStateWindow)
    private var latestScore = 0
    private var latestAlert = false
    private var latestExpected = 0

    def update(entity: Int, stateCode: Int): Unit = {
      val expected =
        if (seen(entity)) (last(entity) * 7 + entity * 3 + 5) % 18
        else stateCode
      val miss = seen(entity) && stateCode != expected
      total(entity) += 1
      if (miss) misses(entity) += 1
      val score =
        if (total(entity) == 0) 0
        else ((misses(entity).toLong * 1000L) / total(entity).toLong).toInt
      val base = entity * cfg.fraudStateWindow
      val pos = positions(entity)
      history(base + pos) = stateCode
      positions(entity) = if (pos + 1 == cfg.fraudStateWindow) 0 else pos + 1
      last(entity) = stateCode
      seen(entity) = true
      latestScore = score
      latestAlert = score >= cfg.fraudAlertThresholdPermille
      latestExpected = expected
    }

    def score: Int = latestScore
    def alert: Boolean = latestAlert
    def expected: Int = latestExpected
  }

  private def generatedFraudEntity(index: Int): Int =
    mix(index * 214013 + 2531011) % DSPBenchRegionConfig.fraudEntityBuckets

  private def generatedFraudState(index: Int, entity: Int): Int =
    mix(index * 1103515245 + entity * 1009 + 12345) % 18

  private def foreachGeneratedFraud(consumer: FraudConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    var i = 0
    while (i < cfg.events) {
      val entity = generatedFraudEntity(i)
      val state = generatedFraudState(i, entity)
      consumer(i, entity, state, generatedHash(i, entity, state))
      i += 1
    }
    cfg.events
  }

  private def foreachFileBackedFraud(consumer: FraudConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    val parsed = new ParsedKeyValue
    var index = 0
    while (index < cfg.events) {
      var advanced = false
      var pathIndex = 0
      while (pathIndex < cfg.inputPaths.length && index < cfg.events) {
        val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
        try {
          var length = reader.readLine()
          while (length >= 0 && index < cfg.events) {
            if (length > 0) {
              parseFraudInto(reader.bytes, length, parsed)
              if (parsed.key >= 0) {
                consumer(index, parsed.key, parsed.value, parsed.hash ^ index.toLong)
                index += 1
                advanced = true
              }
            }
            length = reader.readLine()
          }
        } finally {
          reader.close()
        }
        pathIndex += 1
      }
      if (!advanced) {
        throw new IllegalStateException(
          s"DSPBench input '${cfg.inputPath}' stopped before producing any usable replay records"
        )
      }
    }
    index
  }

  private def foreachFraud(consumer: FraudConsumer^): Int =
    if (DSPBenchRegionConfig.fileBackedInput) foreachFileBackedFraud(consumer)
    else foreachGeneratedFraud(consumer)

  private abstract class LogConsumer {
    def apply(
        eventIndex: Int,
        statusBucket: Int,
        minuteBucket: Int,
        byteSize: Int,
        requestHash: Int,
        hash: Long
    ): Unit
  }

  private final class LogStatusState {
    private val cfg = DSPBenchRegionConfig
    private val counts = new Array[Int](cfg.logStatusBuckets)
    private val bytes = new Array[Long](cfg.logStatusBuckets)
    private val lastMinute = new Array[Int](cfg.logStatusBuckets)
    private var latestCount = 0
    private var latestByteDigest = 0

    def update(statusBucket: Int, minuteBucket: Int, byteSize: Int): Unit = {
      counts(statusBucket) += 1
      bytes(statusBucket) += byteSize.toLong
      lastMinute(statusBucket) = minuteBucket
      latestCount = counts(statusBucket)
      latestByteDigest = (bytes(statusBucket) & 0x7fffffffL).toInt
    }

    def count: Int = latestCount
    def byteDigest: Int = latestByteDigest
  }

  private def generatedLogStatus(index: Int): Int = {
    val selector = mix(index * 1103515245 + 17) % 100
    if (selector < 78) 200
    else if (selector < 85) 301
    else if (selector < 94) 404
    else if (selector < 98) 500
    else 503
  }

  private def foreachGeneratedLog(consumer: LogConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    var i = 0
    while (i < cfg.events) {
      val status = generatedLogStatus(i)
      val statusBucket =
        BenchmarkInputSupport.positiveModulo(status, cfg.logStatusBuckets)
      val minuteBucket =
        BenchmarkInputSupport.positiveModulo(i / 60, cfg.logMinuteBuckets)
      val byteSize = 64 + (mix(i * 1664525 + 1013904223) % 32768)
      val requestHash = mix(i * 1009 + status * 37)
      val hash = generatedHash(i, statusBucket, byteSize) ^
        (minuteBucket.toLong << 23) ^
        requestHash.toLong
      consumer(i, statusBucket, minuteBucket, byteSize, requestHash, hash)
      i += 1
    }
    cfg.events
  }

  private def foreachFileBackedLog(consumer: LogConsumer^): Int = {
    val cfg = DSPBenchRegionConfig
    val parsed = new ParsedCommonLog
    var index = 0
    while (index < cfg.events) {
      var advanced = false
      var pathIndex = 0
      while (pathIndex < cfg.inputPaths.length && index < cfg.events) {
        val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
        try {
          var length = reader.readLine()
          while (length >= 0 && index < cfg.events) {
            if (length > 0) {
              parseCommonLogInto(reader.bytes, length, parsed)
              if (parsed.statusBucket >= 0) {
                consumer(
                  index,
                  parsed.statusBucket,
                  parsed.minuteBucket,
                  parsed.byteSize,
                  parsed.requestHash,
                  parsed.hash ^ index.toLong
                )
                index += 1
                advanced = true
              }
            }
            length = reader.readLine()
          }
        } finally {
          reader.close()
        }
        pathIndex += 1
      }
      if (!advanced) {
        throw new IllegalStateException(
          s"DSPBench input '${cfg.inputPath}' stopped before producing any usable replay records"
        )
      }
    }
    index
  }

  private def foreachLog(consumer: LogConsumer^): Int =
    if (DSPBenchRegionConfig.fileBackedInput) foreachFileBackedLog(consumer)
    else foreachGeneratedLog(consumer)

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
    val cfg = DSPBenchRegionConfig
    val diagnostics = cfg.diagnostics
    val diag = new MemoryCostDiagnostics()
    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: HeapBucket): Unit = {
      val closeStarted = if (diagnostics) System.nanoTime() else 0L
      if (windowQuery(query)) {
        val counts = new Array[Int](keyBucketCount(query))
        val spikes = new Array[Int](keyBucketCount(query))
        val targetKind = windowRecordKind(query)
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          if (record.kind == targetKind) {
            counts(record.device) += 1
            if (record.spike) spikes(record.device) += 1
          }
          record = record.next
        }
        var device = 0
        while (device < counts.length) {
          val count = counts(device)
          if (count != 0) {
            checksum = fold(
              checksum,
              windowSummaryKind(query),
              bucket.startEvent.toInt,
              device,
              count,
              spikes(device),
              spikes(device) != 0,
              (device.toLong << 32) ^ count.toLong ^ spikes(device).toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          device += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.device,
            record.valueScaled,
            record.avgScaled,
            record.spike,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }
      if (diagnostics) {
        diag.closeCursorNanos += System.nanoTime() - closeStarted
        diag.closeBuckets += 1L
      }
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
        consumeBucket(bucket)
      }

    def bucketFor(startEvent: Long): HeapBucket =
      if (current != null && current.startEvent == startEvent) current
      else {
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
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
        if (diagnostics) {
          diag.bucketSwitchNanos += System.nanoTime() - bucketStarted
          diag.bucketSwitches += 1L
        }
        bucket
      }

    def appendHeap(
        bucket: HeapBucket,
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      appendRecord(
        bucket,
        new HeapRecord(kind, i, key, value, score, flag, hash, null)
      )
      if (diagnostics) {
        diag.appendNanos += System.nanoTime() - appendStarted
        diag.appendedRecords += 1L
      }
    }

    def processSensor(
        i: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendHeap(bucket, 10, i, device, valueScaled, 0, false, hash)
      if (averageQuery(query)) {
        val avg = state.update(device, valueScaled)
        appendHeap(bucket, 20, i, device, valueScaled, avg, false, hash ^ 20L)
        if (candidateQuery(query)) {
          val spike = isSpike(valueScaled, avg)
          appendHeap(bucket, 30, i, device, valueScaled, avg, spike, hash ^ 30L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, device, valueScaled, 0, false, hash, start)
    }

    def processFraud(
        i: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendHeap(bucket, 110, i, entity, stateCode, 0, false, hash)
      if (fraudPredictQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        fraudState.update(entity, stateCode)
        val score = fraudState.score
        val alert = fraudState.alert
        val expected = fraudState.expected
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendHeap(
          bucket,
          120,
          i,
          entity,
          stateCode,
          score,
          alert,
          hash ^ expected.toLong ^ 120L
        )
        appendHeap(bucket, 121, i, entity, expected, score, false, hash ^ 121L)
        appendHeap(bucket, 122, i, entity, stateCode, score, alert, hash ^ 122L)
        if (fraudAlertQuery(query) && alert) {
          appendHeap(bucket, 130, i, entity, stateCode, score, true, hash ^ 130L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    def processLog(
        i: Int,
        statusBucket: Int,
        minuteBucket: Int,
        byteSize: Int,
        requestHash: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val error = statusBucket >= 400 && statusBucket < 600
      appendHeap(bucket, 210, i, statusBucket, byteSize, minuteBucket, error, hash)
      if (logStatusQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        logState.update(statusBucket, minuteBucket, byteSize)
        val count = logState.count
        val byteDigest = logState.byteDigest
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendHeap(
          bucket,
          220,
          i,
          statusBucket,
          count,
          byteDigest,
          error,
          hash ^ requestHash.toLong ^ 220L
        )
        if (logWindowQuery(query))
          appendHeap(
            bucket,
            230,
            i,
            statusBucket,
            byteSize,
            minuteBucket,
            error,
            hash ^ 230L
          )
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 299, i, statusBucket, byteSize, minuteBucket, error, hash, start)
    }

    if (fraudQuery(query)) {
      foreachFraud(new FraudConsumer {
        def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
          processFraud(i, entity, stateCode, hash)
      })
    } else if (logQuery(query)) {
      foreachLog(new LogConsumer {
        def apply(
            i: Int,
            statusBucket: Int,
            minuteBucket: Int,
            byteSize: Int,
            requestHash: Int,
            hash: Long
        ): Unit =
          processLog(i, statusBucket, minuteBucket, byteSize, requestHash, hash)
      })
    } else {
      foreachSensor(new SensorConsumer {
        def apply(i: Int, device: Int, valueScaled: Int, hash: Long): Unit =
          processSensor(i, device, valueScaled, hash)
      })
    }
    val finalCloseStarted = if (diagnostics) System.nanoTime() else 0L
    closeExpired(Long.MaxValue)
    if (diagnostics) {
      diag.finalCloseNanos += System.nanoTime() - finalCloseStarted
      diag.print(query, "heap")
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runSafeZone(query: String): RunOutcome = {
    val cfg = DSPBenchRegionConfig
    val diagnostics = cfg.diagnostics
    val diag = new MemoryCostDiagnostics()
    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: SafeBucket): Unit = {
      val closeStarted = if (diagnostics) System.nanoTime() else 0L
      if (windowQuery(query)) {
        val counts = new Array[Int](keyBucketCount(query))
        val spikes = new Array[Int](keyBucketCount(query))
        val targetKind = windowRecordKind(query)
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          if (record.kind == targetKind) {
            counts(record.device) += 1
            if (record.spike) spikes(record.device) += 1
          }
          record = record.next
        }
        var device = 0
        while (device < counts.length) {
          val count = counts(device)
          if (count != 0) {
            checksum = fold(
              checksum,
              windowSummaryKind(query),
              bucket.startEvent.toInt,
              device,
              count,
              spikes(device),
              spikes(device) != 0,
              (device.toLong << 32) ^ count.toLong ^ spikes(device).toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          device += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.device,
            record.valueScaled,
            record.avgScaled,
            record.spike,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }
      if (diagnostics) {
        diag.closeCursorNanos += System.nanoTime() - closeStarted
        diag.closeBuckets += 1L
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
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
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
        if (diagnostics) {
          diag.bucketSwitchNanos += System.nanoTime() - bucketStarted
          diag.bucketSwitches += 1L
        }
        bucket
      }

    def processSensor(
        i: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendSafe(bucket, 10, i, device, valueScaled, 0, false, hash)
      if (averageQuery(query)) {
        val avg = state.update(device, valueScaled)
        appendSafe(bucket, 20, i, device, valueScaled, avg, false, hash ^ 20L)
        if (candidateQuery(query)) {
          val spike = isSpike(valueScaled, avg)
          appendSafe(bucket, 30, i, device, valueScaled, avg, spike, hash ^ 30L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, device, valueScaled, 0, false, hash, start)
    }

    def safeRecord(
        bucket: SafeBucket,
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): SafeRecord =
      SafeZoneAllocator
        .allocate(
          bucket.zone,
          new SafeRecord(kind, i, key, value, score, flag, hash, null)
        )
        .asInstanceOf[SafeRecord]

    def appendSafe(
        bucket: SafeBucket,
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      appendRecord(bucket, safeRecord(bucket, kind, i, key, value, score, flag, hash))
      if (diagnostics) {
        diag.appendNanos += System.nanoTime() - appendStarted
        diag.appendedRecords += 1L
      }
    }

    def processFraud(
        i: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      appendSafe(bucket, 110, i, entity, stateCode, 0, false, hash)
      if (fraudPredictQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        fraudState.update(entity, stateCode)
        val score = fraudState.score
        val alert = fraudState.alert
        val expected = fraudState.expected
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendSafe(
          bucket,
          120,
          i,
          entity,
          stateCode,
          score,
          alert,
          hash ^ expected.toLong ^ 120L
        )
        appendSafe(bucket, 121, i, entity, expected, score, false, hash ^ 121L)
        appendSafe(bucket, 122, i, entity, stateCode, score, alert, hash ^ 122L)
        if (fraudAlertQuery(query) && alert)
          appendSafe(bucket, 130, i, entity, stateCode, score, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    def processLog(
        i: Int,
        statusBucket: Int,
        minuteBucket: Int,
        byteSize: Int,
        requestHash: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val error = statusBucket >= 400 && statusBucket < 600
      appendSafe(bucket, 210, i, statusBucket, byteSize, minuteBucket, error, hash)
      if (logStatusQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        logState.update(statusBucket, minuteBucket, byteSize)
        val count = logState.count
        val byteDigest = logState.byteDigest
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendSafe(
          bucket,
          220,
          i,
          statusBucket,
          count,
          byteDigest,
          error,
          hash ^ requestHash.toLong ^ 220L
        )
        if (logWindowQuery(query))
          appendSafe(
            bucket,
            230,
            i,
            statusBucket,
            byteSize,
            minuteBucket,
            error,
            hash ^ 230L
          )
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 299, i, statusBucket, byteSize, minuteBucket, error, hash, start)
    }

    try {
      if (fraudQuery(query)) {
        foreachFraud(new FraudConsumer {
          def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
            processFraud(i, entity, stateCode, hash)
        })
      } else if (logQuery(query)) {
        foreachLog(new LogConsumer {
          def apply(
              i: Int,
              statusBucket: Int,
              minuteBucket: Int,
              byteSize: Int,
              requestHash: Int,
              hash: Long
          ): Unit =
            processLog(i, statusBucket, minuteBucket, byteSize, requestHash, hash)
        })
      } else {
        foreachSensor(new SensorConsumer {
          def apply(i: Int, device: Int, valueScaled: Int, hash: Long): Unit =
            processSensor(i, device, valueScaled, hash)
        })
      }
      val finalCloseStarted = if (diagnostics) System.nanoTime() else 0L
      closeExpired(Long.MaxValue)
      if (diagnostics)
        diag.finalCloseNanos += System.nanoTime() - finalCloseStarted
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket)
      }
    }

    if (diagnostics)
      diag.print(query, "safezone")

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftTrusted(query: String, kind: Int): RunOutcome = {
    val cfg = DSPBenchRegionConfig
    val diagnostics = cfg.diagnostics
    val diag = new MemoryCostDiagnostics()
    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consumeBucket(bucket: TrustedBucket): Unit = {
      val closeStarted = if (diagnostics) System.nanoTime() else 0L
      if (windowQuery(query)) {
        val counts = new Array[Int](keyBucketCount(query))
        val spikes = new Array[Int](keyBucketCount(query))
        val targetKind = windowRecordKind(query)
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          if (record.kind == targetKind) {
            counts(record.device) += 1
            if (record.spike) spikes(record.device) += 1
          }
          record = record.next
        }
        var device = 0
        while (device < counts.length) {
          val count = counts(device)
          if (count != 0) {
            checksum = fold(
              checksum,
              windowSummaryKind(query),
              bucket.startEvent.toInt,
              device,
              count,
              spikes(device),
              spikes(device) != 0,
              (device.toLong << 32) ^ count.toLong ^ spikes(device).toLong,
              bucket.startEvent
            )
            outputCount += 1L
          }
          device += 1
        }
      } else {
        var record = bucket.head
        while (record != null) {
          if (diagnostics) diag.closedRecords += 1L
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.device,
            record.valueScaled,
            record.avgScaled,
            record.spike,
            record.hash,
            bucket.startEvent
          )
          outputCount += 1L
          record = record.next
        }
      }
      if (diagnostics) {
        diag.closeCursorNanos += System.nanoTime() - closeStarted
        diag.closeBuckets += 1L
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
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
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
        if (diagnostics) {
          diag.bucketSwitchNanos += System.nanoTime() - bucketStarted
          diag.bucketSwitches += 1L
        }
        bucket
      }

    def appendTrusted(
        bucket: TrustedBucket,
        region: RiftRegion,
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      appendRecord(
        bucket,
        region.alloc(
          new TrustedRecord(kind, i, key, value, score, flag, hash, null)
        )
      )
      if (diagnostics) {
        diag.appendNanos += System.nanoTime() - appendStarted
        diag.appendedRecords += 1L
      }
    }

    def processSensor(
        i: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val region = bucket.region
      appendTrusted(bucket, region, 10, i, device, valueScaled, 0, false, hash)
      if (averageQuery(query)) {
        val avg = state.update(device, valueScaled)
        appendTrusted(bucket, region, 20, i, device, valueScaled, avg, false, hash ^ 20L)
        if (candidateQuery(query)) {
          val spike = isSpike(valueScaled, avg)
          appendTrusted(bucket, region, 30, i, device, valueScaled, avg, spike, hash ^ 30L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, device, valueScaled, 0, false, hash, start)
    }

    def processFraud(
        i: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val region = bucket.region
      appendTrusted(bucket, region, 110, i, entity, stateCode, 0, false, hash)
      if (fraudPredictQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        fraudState.update(entity, stateCode)
        val score = fraudState.score
        val alert = fraudState.alert
        val expected = fraudState.expected
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendTrusted(
          bucket,
          region,
          120,
          i,
          entity,
          stateCode,
          score,
          alert,
          hash ^ expected.toLong ^ 120L
        )
        appendTrusted(bucket, region, 121, i, entity, expected, score, false, hash ^ 121L)
        appendTrusted(bucket, region, 122, i, entity, stateCode, score, alert, hash ^ 122L)
        if (fraudAlertQuery(query) && alert)
          appendTrusted(bucket, region, 130, i, entity, stateCode, score, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    def processLog(
        i: Int,
        statusBucket: Int,
        minuteBucket: Int,
        byteSize: Int,
        requestHash: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      val bucket = bucketFor(start)
      val region = bucket.region
      val error = statusBucket >= 400 && statusBucket < 600
      appendTrusted(bucket, region, 210, i, statusBucket, byteSize, minuteBucket, error, hash)
      if (logStatusQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        logState.update(statusBucket, minuteBucket, byteSize)
        val count = logState.count
        val byteDigest = logState.byteDigest
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendTrusted(
          bucket,
          region,
          220,
          i,
          statusBucket,
          count,
          byteDigest,
          error,
          hash ^ requestHash.toLong ^ 220L
        )
        if (logWindowQuery(query))
          appendTrusted(
            bucket,
            region,
            230,
            i,
            statusBucket,
            byteSize,
            minuteBucket,
            error,
            hash ^ 230L
          )
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 299, i, statusBucket, byteSize, minuteBucket, error, hash, start)
    }

    try {
      if (fraudQuery(query)) {
        foreachFraud(new FraudConsumer {
          def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
            processFraud(i, entity, stateCode, hash)
        })
      } else if (logQuery(query)) {
        foreachLog(new LogConsumer {
          def apply(
              i: Int,
              statusBucket: Int,
              minuteBucket: Int,
              byteSize: Int,
              requestHash: Int,
              hash: Long
          ): Unit =
            processLog(i, statusBucket, minuteBucket, byteSize, requestHash, hash)
        })
      } else {
        foreachSensor(new SensorConsumer {
          def apply(i: Int, device: Int, valueScaled: Int, hash: Long): Unit =
            processSensor(i, device, valueScaled, hash)
        })
      }
      val finalCloseStarted = if (diagnostics) System.nanoTime() else 0L
      closeExpired(Long.MaxValue)
      if (diagnostics)
        diag.finalCloseNanos += System.nanoTime() - finalCloseStarted
    } finally {
      while (first != null) {
        val bucket = first
        first = bucket.next
        closeBucket(bucket)
      }
    }

    if (diagnostics) {
      val mode = if (kind == RiftRegion.Streaming) "rift-streaming" else "rift-hp"
      diag.print(query, mode)
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedPageTokenBody(
      query: String,
      modeLabel: String,
      useRiftHandle: Boolean,
      inferredAllocations: Boolean = false
  )(using stream: RiftRegion.StreamingRegion^): RunOutcome = {
    val cfg = DSPBenchRegionConfig
    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    val diagnostics = cfg.diagnostics
    var bucketSwitchNanos = 0L
    var appendNanos = 0L
    var predictNanos = 0L
    var closeCursorNanos = 0L
    var finalCloseNanos = 0L
    var bucketSwitches = 0L
    var appendedRecords = 0L
    var closedRecords = 0L
    var closeBuckets = 0L

    final class CheckedRecord(
        val kind: Int,
        val eventIndex: Int,
        val device: Int,
        val valueScaled: Int,
        val avgScaled: Int,
        val spike: Boolean,
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
    ): Unit = {
      val closeStarted = if (diagnostics) System.nanoTime() else 0L
      if (windowQuery(query)) {
        val counts = new Array[Int](keyBucketCount(query))
        val spikes = new Array[Int](keyBucketCount(query))
        val targetKind = windowRecordKind(query)
        var current = cursor.nextOwnedOrNull()
        while (current != null) {
          val record: CheckedRecord^{stream} =
            current.asInstanceOf[CheckedRecord^{stream}]
          if (diagnostics) closedRecords += 1L
          if (record.kind == targetKind) {
            counts(record.device) += 1
            if (record.spike) spikes(record.device) += 1
          }
          current = cursor.nextOwnedOrNull()
        }
        var device = 0
        while (device < counts.length) {
          val count = counts(device)
          if (count != 0) {
            checksum = fold(
              checksum,
              windowSummaryKind(query),
              bucket.startSeconds.toInt,
              device,
              count,
              spikes(device),
              spikes(device) != 0,
              (device.toLong << 32) ^ count.toLong ^ spikes(device).toLong,
              bucket.startSeconds
            )
            outputCount += 1L
          }
          device += 1
        }
      } else {
        var current = cursor.nextOwnedOrNull()
        while (current != null) {
          val record: CheckedRecord^{stream} =
            current.asInstanceOf[CheckedRecord^{stream}]
          if (diagnostics) closedRecords += 1L
          checksum = fold(
            checksum,
            record.kind,
            record.eventIndex,
            record.device,
            record.valueScaled,
            record.avgScaled,
            record.spike,
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

    var currentStartEvent = Long.MinValue
    var currentRegion: RiftRegion.OpenStreamingRegion^{stream} = null
    var currentHandle: RiftOpenStreamingHandle^{stream} = null

    def selectBucket(start: Long): Unit =
      if (start != currentStartEvent) {
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
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
        if (diagnostics) {
          bucketSwitchNanos += System.nanoTime() - bucketStarted
          bucketSwitches += 1L
        }
      }

    def processSensor(
        i: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      selectBucket(start)
      appendChecked(10, i, device, valueScaled, 0, false, hash)
      if (averageQuery(query)) {
        val avg = state.update(device, valueScaled)
        appendChecked(20, i, device, valueScaled, avg, false, hash ^ 20L)
        if (candidateQuery(query)) {
          val spike = isSpike(valueScaled, avg)
          appendChecked(30, i, device, valueScaled, avg, spike, hash ^ 30L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, device, valueScaled, 0, false, hash, start)
    }

    def appendCheckedLegacy(
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      val record: CheckedRecord^{stream} =
        RiftRegion.allocOpen(
          new CheckedRecord(kind, i, key, value, score, flag, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, record)
      if (diagnostics) {
        appendNanos += System.nanoTime() - appendStarted
        appendedRecords += 1L
      }
    }

    def appendCheckedHandleExplicit(
        region: RiftOpenStreamingHandle^{stream},
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      val local: CheckedRecord^{region} =
        RiftAllocator.allocateOpenHandle(
          region,
          new CheckedRecord(kind, i, key, value, score, flag, hash)
        )
      val record: CheckedRecord^{stream} = local
      RiftRegion.appendPageToken(stream, window, record)
      if (diagnostics) {
        appendNanos += System.nanoTime() - appendStarted
        appendedRecords += 1L
      }
    }

    def appendCheckedHandleInferred(
        region: RiftOpenStreamingHandle^{stream},
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit = {
      val appendStarted = if (diagnostics) System.nanoTime() else 0L
      val local: CheckedRecord^{region} =
        new CheckedRecord(kind, i, key, value, score, flag, hash)
      val record: CheckedRecord^{stream} = local
      RiftRegion.appendPageToken(stream, window, record)
      if (diagnostics) {
        appendNanos += System.nanoTime() - appendStarted
        appendedRecords += 1L
      }
    }

    def appendChecked(
        kind: Int,
        i: Int,
        key: Int,
        value: Int,
        score: Int,
        flag: Boolean,
        hash: Long
    ): Unit =
      if (useRiftHandle)
        if (inferredAllocations)
          appendCheckedHandleInferred(
            currentHandle,
            kind,
            i,
            key,
            value,
            score,
            flag,
            hash
          )
        else
          appendCheckedHandleExplicit(
            currentHandle,
            kind,
            i,
            key,
            value,
            score,
            flag,
            hash
          )
      else
        appendCheckedLegacy(kind, i, key, value, score, flag, hash)

    def processFraud(
        i: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      selectBucket(start)
      appendChecked(110, i, entity, stateCode, 0, false, hash)
      if (fraudPredictQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        fraudState.update(entity, stateCode)
        val score = fraudState.score
        val alert = fraudState.alert
        val expected = fraudState.expected
        if (diagnostics)
          predictNanos += System.nanoTime() - predictStarted
        appendChecked(
          120,
          i,
          entity,
          stateCode,
          score,
          alert,
          hash ^ expected.toLong ^ 120L
        )
        appendChecked(121, i, entity, expected, score, false, hash ^ 121L)
        appendChecked(122, i, entity, stateCode, score, alert, hash ^ 122L)
        if (fraudAlertQuery(query) && alert)
          appendChecked(130, i, entity, stateCode, score, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    def processLog(
        i: Int,
        statusBucket: Int,
        minuteBucket: Int,
        byteSize: Int,
        requestHash: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      selectBucket(start)
      val error = statusBucket >= 400 && statusBucket < 600
      appendChecked(210, i, statusBucket, byteSize, minuteBucket, error, hash)
      if (logStatusQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        logState.update(statusBucket, minuteBucket, byteSize)
        val count = logState.count
        val byteDigest = logState.byteDigest
        if (diagnostics)
          predictNanos += System.nanoTime() - predictStarted
        appendChecked(
          220,
          i,
          statusBucket,
          count,
          byteDigest,
          error,
          hash ^ requestHash.toLong ^ 220L
        )
        if (logWindowQuery(query))
          appendChecked(230, i, statusBucket, byteSize, minuteBucket, error, hash ^ 230L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 299, i, statusBucket, byteSize, minuteBucket, error, hash, start)
    }

    if (fraudQuery(query)) {
      foreachFraud(new FraudConsumer {
        def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
          processFraud(i, entity, stateCode, hash)
      })
    } else if (logQuery(query)) {
      foreachLog(new LogConsumer {
        def apply(
            i: Int,
            statusBucket: Int,
            minuteBucket: Int,
            byteSize: Int,
            requestHash: Int,
            hash: Long
        ): Unit =
          processLog(i, statusBucket, minuteBucket, byteSize, requestHash, hash)
      })
    } else {
      foreachSensor(new SensorConsumer {
        def apply(i: Int, device: Int, valueScaled: Int, hash: Long): Unit =
          processSensor(i, device, valueScaled, hash)
      })
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
        f"DSPBENCH_DIAG query=$query mode=$modeLabel " +
          s"bucket_switches=$bucketSwitches appended_records=$appendedRecords " +
          s"closed_records=$closedRecords close_buckets=$closeBuckets " +
          f"bucket_switch_ms=${ms(bucketSwitchNanos)}%.3f " +
          f"estimated_bucket_open_ms=${ms(estimatedBucketOpenNanos)}%.3f " +
          f"append_ms=${ms(appendNanos)}%.3f " +
          f"predict_ms=${ms(predictNanos)}%.3f " +
          f"close_cursor_ms=${ms(closeCursorNanos)}%.3f " +
          f"final_close_ms=${ms(finalCloseNanos)}%.3f"
      )
    }

    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runRiftCheckedPageToken(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(
        query,
        "rift-checked-page-token",
        useRiftHandle = true
      )
    }

  private def runRiftCheckedPageTokenInferred(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(
        query,
        "rift-checked-page-token-inferred",
        useRiftHandle = true,
        inferredAllocations = true
      )
    }

  private def runRiftCheckedPageTokenLegacy(query: String): RunOutcome =
    RiftRegion.streaming { stream ?=>
      runRiftCheckedPageTokenBody(
        query,
        "rift-checked-page-token-legacy",
        useRiftHandle = false
      )
    }

  private def runRiftCheckedSafeZonePageToken(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedPageTokenBody(
        query,
        "rift-checked-safezone-page-token",
        useRiftHandle = false
      )
    }

  private def runHeapDirectEpochAggregate(
      query: String,
      retainRecords: Boolean = false
  ): RunOutcome = {
    val cfg = DSPBenchRegionConfig
    if (cfg.fileBackedInput)
      throw new IllegalArgumentException(
        "DSPBench heap direct-epoch aggregate currently requires generated/indexable input; use page-token for file-backed rows"
      )
    if (!windowQuery(query))
      throw new IllegalArgumentException(
        s"DSPBench heap direct-epoch aggregate supports q2/window queries, not '$query'"
      )

    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    val keyCount = keyBucketCount(query)
    val slots = cfg.liveBuckets + 1
    val starts = Array.fill[Long](slots)(Long.MinValue)
    val counts = new Array[Int](slots * keyCount)
    val spikes = new Array[Int](slots * keyCount)
    val targetKind = windowRecordKind(query)
    var nextCloseStart = 0L
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L

    final class HeapDirectRecord(
        val kind: Int,
        val eventIndex: Int,
        val device: Int,
        val valueScaled: Int,
        val avgScaled: Int,
        val spike: Boolean,
        val hash: Long
    )

    final class HeapRetainedRecord(
        val kind: Int,
        val eventIndex: Int,
        val device: Int,
        val valueScaled: Int,
        val avgScaled: Int,
        val spike: Boolean,
        val hash: Long,
        val next: HeapRetainedRecord
    )

    def slotFor(start: Long): Int =
      ((start / cfg.eventsPerBucket.toLong) % slots.toLong).toInt

    def clearSlot(slot: Int): Unit = {
      val base = slot * keyCount
      var key = 0
      while (key < keyCount) {
        counts(base + key) = 0
        spikes(base + key) = 0
        key += 1
      }
    }

    def closeSummaries(cutoffEvent: Long): Unit =
      while (
        nextCloseStart < cfg.events.toLong &&
        nextCloseStart + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val slot = slotFor(nextCloseStart)
        if (starts(slot) == nextCloseStart) {
          val base = slot * keyCount
          var key = 0
          while (key < keyCount) {
            val count = counts(base + key)
            if (count != 0) {
              checksum = fold(
                checksum,
                windowSummaryKind(query),
                nextCloseStart.toInt,
                key,
                count,
                spikes(base + key),
                spikes(base + key) != 0,
                (key.toLong << 32) ^ count.toLong ^ spikes(base + key).toLong,
                nextCloseStart
              )
              outputCount += 1L
            }
            key += 1
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
      val base = slot * keyCount
      var head: HeapRetainedRecord = null
      var tail: HeapRetainedRecord = null
      var retainedCount = 0

      def appendHeapDirect(
          kind: Int,
          i: Int,
          key: Int,
          value: Int,
          score: Int,
          flag: Boolean,
          hash: Long
      ): Unit = {
        if (retainRecords) {
          val record =
            new HeapRetainedRecord(kind, i, key, value, score, flag, hash, head)
          if (head == null) tail = record
          head = record
          retainedCount += 1
          if (record.kind == targetKind) {
            counts(base + record.device) += 1
            if (record.spike) spikes(base + record.device) += 1
          }
        } else {
          val record =
            new HeapDirectRecord(kind, i, key, value, score, flag, hash)
          if (record.kind == targetKind) {
            counts(base + record.device) += 1
            if (record.spike) spikes(base + record.device) += 1
          }
        }
      }

      var i = startEvent
      if (fraudQuery(query)) {
        while (i < endEvent) {
          val entity = generatedFraudEntity(i)
          val stateCode = generatedFraudState(i, entity)
          val hash = generatedHash(i, entity, stateCode)
          appendHeapDirect(110, i, entity, stateCode, 0, false, hash)
          if (fraudPredictQuery(query)) {
            fraudState.update(entity, stateCode)
            val score = fraudState.score
            val alert = fraudState.alert
            val expected = fraudState.expected
            appendHeapDirect(
              120,
              i,
              entity,
              stateCode,
              score,
              alert,
              hash ^ expected.toLong ^ 120L
            )
            appendHeapDirect(121, i, entity, expected, score, false, hash ^ 121L)
            appendHeapDirect(122, i, entity, stateCode, score, alert, hash ^ 122L)
            if (fraudAlertQuery(query) && alert)
              appendHeapDirect(130, i, entity, stateCode, score, true, hash ^ 130L)
          }
          if (i % cfg.sampleEvery == 0)
            checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
          i += 1
        }
      } else if (logQuery(query)) {
        while (i < endEvent) {
          val status = generatedLogStatus(i)
          val statusBucket =
            BenchmarkInputSupport.positiveModulo(status, cfg.logStatusBuckets)
          val minuteBucket =
            BenchmarkInputSupport.positiveModulo(i / 60, cfg.logMinuteBuckets)
          val byteSize = 64 + (mix(i * 1664525 + 1013904223) % 32768)
          val requestHash = mix(i * 1009 + status * 37)
          val hash = generatedHash(i, statusBucket, byteSize) ^
            (minuteBucket.toLong << 23) ^
            requestHash.toLong
          val error = statusBucket >= 400 && statusBucket < 600
          appendHeapDirect(210, i, statusBucket, byteSize, minuteBucket, error, hash)
          if (logStatusQuery(query)) {
            logState.update(statusBucket, minuteBucket, byteSize)
            val count = logState.count
            val byteDigest = logState.byteDigest
            appendHeapDirect(
              220,
              i,
              statusBucket,
              count,
              byteDigest,
              error,
              hash ^ requestHash.toLong ^ 220L
            )
            if (logWindowQuery(query))
              appendHeapDirect(230, i, statusBucket, byteSize, minuteBucket, error, hash ^ 230L)
          }
          if (i % cfg.sampleEvery == 0)
            checksum = fold(
              checksum,
              299,
              i,
              statusBucket,
              byteSize,
              minuteBucket,
              error,
              hash,
              start
            )
          i += 1
        }
      } else {
        while (i < endEvent) {
          val device = generatedDevice(i)
          val value = generatedValue(i)
          val hash = generatedHash(i, device, value)
          appendHeapDirect(10, i, device, value, 0, false, hash)
          if (averageQuery(query)) {
            val avg = state.update(device, value)
            appendHeapDirect(20, i, device, value, avg, false, hash ^ 20L)
            if (candidateQuery(query)) {
              val spike = isSpike(value, avg)
              appendHeapDirect(30, i, device, value, avg, spike, hash ^ 30L)
            }
          }
          if (i % cfg.sampleEvery == 0)
            checksum = fold(checksum, 99, i, device, value, 0, false, hash, start)
          i += 1
        }
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
    while (bucketStartEvent < cfg.events) {
      val bucketEnd =
        math.min(cfg.events, bucketStartEvent + cfg.eventsPerBucket)
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
    val cfg = DSPBenchRegionConfig
    if (cfg.fileBackedInput)
      throw new IllegalArgumentException(
        "DSPBench checked direct-epoch currently requires generated/indexable input; use page-token for file-backed rows"
      )
    if (!windowQuery(query))
      throw new IllegalArgumentException(
        s"DSPBench checked direct-epoch supports q2/window queries, not '$query'"
      )

    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
    val logState = new LogStatusState()
    val keyCount = keyBucketCount(query)
    val slots = cfg.liveBuckets + 1
    val starts = Array.fill[Long](slots)(Long.MinValue)
    val counts = new Array[Int](slots * keyCount)
    val spikes = new Array[Int](slots * keyCount)
    val targetKind = windowRecordKind(query)
    var nextCloseStart = 0L
    var checksum = 0L
    var outputCount = 0L
    var retainedAnchor = 0L

    def slotFor(start: Long): Int =
      ((start / cfg.eventsPerBucket.toLong) % slots.toLong).toInt

    def clearSlot(slot: Int): Unit = {
      val base = slot * keyCount
      var key = 0
      while (key < keyCount) {
        counts(base + key) = 0
        spikes(base + key) = 0
        key += 1
      }
    }

    def closeSummaries(cutoffEvent: Long): Unit =
      while (
        nextCloseStart < cfg.events.toLong &&
        nextCloseStart + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val slot = slotFor(nextCloseStart)
        if (starts(slot) == nextCloseStart) {
          val base = slot * keyCount
          var key = 0
          while (key < keyCount) {
            val count = counts(base + key)
            if (count != 0) {
              checksum = fold(
                checksum,
                windowSummaryKind(query),
                nextCloseStart.toInt,
                key,
                count,
                spikes(base + key),
                spikes(base + key) != 0,
                (key.toLong << 32) ^ count.toLong ^ spikes(base + key).toLong,
                nextCloseStart
              )
              outputCount += 1L
            }
            key += 1
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
      val base = slot * keyCount

      RiftRegion.epoch { region ?=>
        final class CheckedRecord(
            val kind: Int,
            val eventIndex: Int,
            val device: Int,
            val valueScaled: Int,
            val avgScaled: Int,
            val spike: Boolean,
            val hash: Long
        )

        final class CheckedRetainedRecord(
            val kind: Int,
            val eventIndex: Int,
            val device: Int,
            val valueScaled: Int,
            val avgScaled: Int,
            val spike: Boolean,
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
            key: Int,
            value: Int,
            score: Int,
            flag: Boolean,
            hash: Long
        ): Unit = {
          if (retainRecords) {
            val record: CheckedRetainedRecord^{region} =
              RiftRegion.allocOpen(
                new CheckedRetainedRecord(
                  kind,
                  i,
                  key,
                  value,
                  score,
                  flag,
                  hash
                )
              )
            record.next = head
            if (head == null) tail = record
            head = record
            retainedCount += 1
            if (record.kind == targetKind) {
              counts(base + record.device) += 1
              if (record.spike) spikes(base + record.device) += 1
            }
          } else {
            val record = RiftRegion.allocOpen(
              new CheckedRecord(kind, i, key, value, score, flag, hash)
            )
            if (record.kind == targetKind) {
              counts(base + record.device) += 1
              if (record.spike) spikes(base + record.device) += 1
            }
          }
        }

        var i = startEvent
        if (fraudQuery(query)) {
          while (i < endEvent) {
            val entity = generatedFraudEntity(i)
            val stateCode = generatedFraudState(i, entity)
            val hash = generatedHash(i, entity, stateCode)
            appendChecked(110, i, entity, stateCode, 0, false, hash)
            if (fraudPredictQuery(query)) {
              fraudState.update(entity, stateCode)
              val score = fraudState.score
              val alert = fraudState.alert
              val expected = fraudState.expected
              appendChecked(
                120,
                i,
                entity,
                stateCode,
                score,
                alert,
                hash ^ expected.toLong ^ 120L
              )
              appendChecked(121, i, entity, expected, score, false, hash ^ 121L)
              appendChecked(122, i, entity, stateCode, score, alert, hash ^ 122L)
              if (fraudAlertQuery(query) && alert)
                appendChecked(130, i, entity, stateCode, score, true, hash ^ 130L)
            }
            if (i % cfg.sampleEvery == 0)
              checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
            i += 1
          }
        } else if (logQuery(query)) {
          while (i < endEvent) {
            val status = generatedLogStatus(i)
            val statusBucket =
              BenchmarkInputSupport.positiveModulo(status, cfg.logStatusBuckets)
            val minuteBucket =
              BenchmarkInputSupport.positiveModulo(i / 60, cfg.logMinuteBuckets)
            val byteSize = 64 + (mix(i * 1664525 + 1013904223) % 32768)
            val requestHash = mix(i * 1009 + status * 37)
            val hash = generatedHash(i, statusBucket, byteSize) ^
              (minuteBucket.toLong << 23) ^
              requestHash.toLong
            val error = statusBucket >= 400 && statusBucket < 600
            appendChecked(210, i, statusBucket, byteSize, minuteBucket, error, hash)
            if (logStatusQuery(query)) {
              logState.update(statusBucket, minuteBucket, byteSize)
              val count = logState.count
              val byteDigest = logState.byteDigest
              appendChecked(
                220,
                i,
                statusBucket,
                count,
                byteDigest,
                error,
                hash ^ requestHash.toLong ^ 220L
              )
              if (logWindowQuery(query))
                appendChecked(230, i, statusBucket, byteSize, minuteBucket, error, hash ^ 230L)
            }
            if (i % cfg.sampleEvery == 0)
              checksum = fold(
                checksum,
                299,
                i,
                statusBucket,
                byteSize,
                minuteBucket,
                error,
                hash,
                start
              )
            i += 1
          }
        } else {
          while (i < endEvent) {
            val device = generatedDevice(i)
            val value = generatedValue(i)
            val hash = generatedHash(i, device, value)
            appendChecked(10, i, device, value, 0, false, hash)
            if (averageQuery(query)) {
              val avg = state.update(device, value)
              appendChecked(20, i, device, value, avg, false, hash ^ 20L)
              if (candidateQuery(query)) {
                val spike = isSpike(value, avg)
                appendChecked(30, i, device, value, avg, spike, hash ^ 30L)
              }
            }
            if (i % cfg.sampleEvery == 0)
              checksum = fold(checksum, 99, i, device, value, 0, false, hash, start)
            i += 1
          }
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
    while (bucketStartEvent < cfg.events) {
      val bucketEnd =
        math.min(cfg.events, bucketStartEvent + cfg.eventsPerBucket)
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
      case "region-scoped-rooted" | "safezone-improved" |
          "safezone-improved-32k" =>
        "safezone"
      case "safezone-current" => "safezone"
      case "region-scoped-rootless" | "safezone-rootless-32k" |
          "unsafezone-hp" =>
        "safezone"
      case "region-hp-rootless" | "rift-trusted-hp" => "rift-hp"
      case "region-stream-rootless" | "rift-trusted-streaming" =>
        "rift-streaming"
      case "rift-checked-page-token-open-region" |
          "rift-checked-page-token-legacy" =>
        "rift-checked-page-token-legacy"
      case "checked-region-stream" | "rift-checked-page-token" =>
        "rift-checked-page-token"
      case "checked-region-stream-inferred" |
          "rift-checked-page-token-inferred" =>
        "rift-checked-page-token-inferred"
      case "checked-region-scoped" | "rift-checked-safezone-page-token" =>
        "rift-checked-safezone-page-token"
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
      case other => other
    }

  private def usesRiftRuntime(mode: String): Boolean =
    canonicalMode(mode) match {
      case "rift-hp" | "rift-streaming" | "rift-checked-page-token" |
          "rift-checked-page-token-inferred" |
          "rift-checked-page-token-legacy" |
          "rift-checked-direct-epoch" |
          "checked-epoch-retained-no-traverse" =>
        true
      case _                                                        => false
    }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "heap-direct-epoch" |
          "rift-checked-page-token" | "rift-checked-page-token-legacy" |
          "rift-checked-page-token-inferred" |
          "rift-checked-safezone-page-token" |
          "rift-checked-direct-epoch" |
          "rift-checked-safezone-direct-epoch" |
          "heap-epoch-retained-no-traverse" |
          "checked-epoch-retained-no-traverse" |
          "checked-scoped-epoch-retained-no-traverse" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown DSPBench mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-moving-average" | "q2-spike-window" |
          "fraud-q0-parse" | "fraud-q1-predict" |
          "fraud-q2-alert-window" | "log-q0-parse" |
          "log-q1-status" | "log-q2-window" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown DSPBench query '$other'"
        )
    }

  private def runMode(mode: String, query: String): RunOutcome =
    canonicalMode(mode) match {
      case "heap"           => runHeap(query)
      case "heap-direct-epoch" => runHeapDirectEpochAggregate(query)
      case "heap-epoch-retained-no-traverse" =>
        runHeapRetainedEpochNoTraverse(query)
      case "safezone"       => runSafeZone(query)
      case "rift-hp"        => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case "rift-checked-page-token" => runRiftCheckedPageToken(query)
      case "rift-checked-page-token-inferred" =>
        runRiftCheckedPageTokenInferred(query)
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
          s"unknown DSPBench mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = DSPBenchRegionConfig
    val input = inputDataFor(query)
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
        s"RESULT name=dspbench-$query-$canonical " +
          s"measurement_level=L1 final_clean=1 query=$query mode=$canonical " +
          s"input=${input.label} input_mode=${cfg.inputMode} " +
          s"loaded_events=${input.requestedEvents} " +
          s"unique_input_lines=${input.uniqueInputLines} " +
          s"input_replays=${input.replayCount} " +
          s"input_files=${input.inputFiles} runs=${cfg.benchmarkRuns} " +
          s"checksum=${expected.checksum} output_count=${expected.outputCount}"
      )
      return
    }

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
      s"Running dspbench-$query-$canonical for ${cfg.benchmarkRuns} timed runs"
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
      f"RESULT name=dspbench-$query-$canonical " +
        f"query=$query mode=$canonical input=${input.label} " +
        f"input_mode=${cfg.inputMode} " +
        f"loaded_events=${input.requestedEvents}%d " +
        f"unique_input_lines=${input.uniqueInputLines}%d " +
        f"input_replays=${input.replayCount}%d " +
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
    val cfg = DSPBenchRegionConfig
    val input = inputDataFor(query)
    println(
      s"CONFIG mode=$mode canonical_mode=${canonicalMode(mode)} query=$query " +
        s"events=${input.requestedEvents} configured_events=${cfg.events} " +
        s"events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} " +
        s"device_buckets=${cfg.deviceBuckets} " +
        s"fraud_entity_buckets=${cfg.fraudEntityBuckets} " +
        s"log_status_buckets=${cfg.logStatusBuckets} " +
        s"log_minute_buckets=${cfg.logMinuteBuckets} " +
        s"moving_average_window=${cfg.movingAverageWindow} " +
        s"spike_threshold_permille=${cfg.spikeThresholdPermille} " +
        s"sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns} input=${input.label} " +
        s"input_mode=${cfg.inputMode} input_files=${input.inputFiles} " +
        s"unique_input_lines=${input.uniqueInputLines} " +
        s"input_replays=${input.replayCount} input_path=${cfg.inputPath}"
    )
  }

  def requiresRiftRuntime(mode: String): Boolean =
    usesRiftRuntime(mode)
}

@main def DSPBenchRegionMatrix(
    mode: String = "heap",
    query: String = "q1-moving-average"
): Unit = {
  DSPBenchRegionMatrixHelpers.validateMode(mode)
  DSPBenchRegionMatrixHelpers.validateQuery(query)
  DSPBenchRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = DSPBenchRegionMatrixHelpers.requiresRiftRuntime(mode)
  if (usesRift) RiftRegion.init(0)
  try {
    DSPBenchRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
