import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
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
      println(
        f"DSPBENCH_DIAG query=$query mode=$mode " +
          s"bucket_switches=$bucketSwitches appended_records=$appendedRecords " +
          s"closed_records=$closedRecords close_buckets=$closeBuckets " +
          f"bucket_switch_ms=${ms(bucketSwitchNanos)}%.3f " +
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
    query == "q2-spike-window" || query == "fraud-q2-alert-window"

  private def fraudQuery(query: String): Boolean =
    query == "fraud-q0-parse" ||
      query == "fraud-q1-predict" ||
      query == "fraud-q2-alert-window"

  private def fraudPredictQuery(query: String): Boolean =
    query == "fraud-q1-predict" || query == "fraud-q2-alert-window"

  private def fraudAlertQuery(query: String): Boolean =
    query == "fraud-q2-alert-window"

  private def windowRecordKind(query: String): Int =
    if (fraudQuery(query)) 130 else 30

  private def windowSummaryKind(query: String): Int =
    if (fraudQuery(query)) 144 else 44

  private def keyBucketCount(query: String): Int =
    if (fraudQuery(query)) DSPBenchRegionConfig.fraudEntityBuckets
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

  private def parseSensor(
      bytes: Array[Byte],
      length: Int
  ): (Int, Int, Long) = {
    val deviceStart = fieldStart(bytes, length, 3)
    val valueStart = fieldStart(bytes, length, 4)
    if (deviceStart < 0 || valueStart < 0) return (-1, 0, 0L)
    val rawDevice = parseIntAt(bytes, length, deviceStart)
    val value = parseScaledAt(bytes, length, valueStart)
    if (rawDevice == Int.MinValue || value == Int.MinValue) (-1, 0, 0L)
    else {
      val device =
        BenchmarkInputSupport.positiveModulo(rawDevice, DSPBenchRegionConfig.deviceBuckets)
      val hash = BenchmarkInputSupport.stableHash(bytes, 0, length).toLong ^
        (device.toLong * 1099511628211L) ^
        (value.toLong * 1315423911L)
      (device, value, hash)
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

  private def parseFraud(
      bytes: Array[Byte],
      length: Int
  ): (Int, Int, Long) = {
    val firstComma = csvComma(bytes, length, 0)
    if (firstComma <= 0) return (-1, 0, 0L)
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
    (entity, state, recordHash)
  }

  private def countFileBackedRows(query: String): Int = {
    val cfg = DSPBenchRegionConfig
    var count = 0
    var pathIndex = 0
    while (pathIndex < cfg.inputPaths.length) {
      val reader = BenchmarkInputSupport.openByteLines(cfg.inputPaths(pathIndex))
      try {
        var length = reader.readLine()
        while (length >= 0) {
          if (length > 0) {
            val parsed =
              if (fraudQuery(query)) parseFraud(reader.bytes, length)
              else parseSensor(reader.bytes, length)
            if (parsed._1 >= 0) count += 1
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
              val parsed = parseSensor(reader.bytes, length)
              if (parsed._1 >= 0) {
                consumer(index, parsed._1, parsed._2, parsed._3 ^ index.toLong)
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

    def update(entity: Int, stateCode: Int): (Int, Boolean, Int) = {
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
      (score, score >= cfg.fraudAlertThresholdPermille, expected)
    }
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
              val parsed = parseFraud(reader.bytes, length)
              if (parsed._1 >= 0) {
                consumer(index, parsed._1, parsed._2, parsed._3 ^ index.toLong)
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
        val prediction = fraudState.update(entity, stateCode)
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendHeap(
          bucket,
          120,
          i,
          entity,
          stateCode,
          prediction._1,
          prediction._2,
          hash ^ prediction._3.toLong ^ 120L
        )
        appendHeap(bucket, 121, i, entity, prediction._3, prediction._1, false, hash ^ 121L)
        appendHeap(bucket, 122, i, entity, stateCode, prediction._1, prediction._2, hash ^ 122L)
        if (fraudAlertQuery(query) && prediction._2) {
          appendHeap(bucket, 130, i, entity, stateCode, prediction._1, true, hash ^ 130L)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    if (fraudQuery(query)) {
      foreachFraud(new FraudConsumer {
        def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
          processFraud(i, entity, stateCode, hash)
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
        val prediction = fraudState.update(entity, stateCode)
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendSafe(
          bucket,
          120,
          i,
          entity,
          stateCode,
          prediction._1,
          prediction._2,
          hash ^ prediction._3.toLong ^ 120L
        )
        appendSafe(bucket, 121, i, entity, prediction._3, prediction._1, false, hash ^ 121L)
        appendSafe(bucket, 122, i, entity, stateCode, prediction._1, prediction._2, hash ^ 122L)
        if (fraudAlertQuery(query) && prediction._2)
          appendSafe(bucket, 130, i, entity, stateCode, prediction._1, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    try {
      if (fraudQuery(query)) {
        foreachFraud(new FraudConsumer {
          def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
            processFraud(i, entity, stateCode, hash)
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
        val prediction = fraudState.update(entity, stateCode)
        if (diagnostics)
          diag.predictNanos += System.nanoTime() - predictStarted
        appendTrusted(
          bucket,
          region,
          120,
          i,
          entity,
          stateCode,
          prediction._1,
          prediction._2,
          hash ^ prediction._3.toLong ^ 120L
        )
        appendTrusted(bucket, region, 121, i, entity, prediction._3, prediction._1, false, hash ^ 121L)
        appendTrusted(bucket, region, 122, i, entity, stateCode, prediction._1, prediction._2, hash ^ 122L)
        if (fraudAlertQuery(query) && prediction._2)
          appendTrusted(bucket, region, 130, i, entity, stateCode, prediction._1, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    try {
      if (fraudQuery(query)) {
        foreachFraud(new FraudConsumer {
          def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
            processFraud(i, entity, stateCode, hash)
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

  private def runRiftCheckedPageTokenBody(query: String, modeLabel: String)(using
      stream: RiftRegion.StreamingRegion^
  ): RunOutcome = {
    val cfg = DSPBenchRegionConfig
    val state = new MovingAverageState()
    val fraudState = new FraudPredictorState()
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
        var current = cursor.nextOrNull()
        while (current != null) {
          val record: CheckedRecord^{stream} =
            current.asInstanceOf[CheckedRecord^{stream}]
          if (diagnostics) closedRecords += 1L
          if (record.kind == targetKind) {
            counts(record.device) += 1
            if (record.spike) spikes(record.device) += 1
          }
          current = cursor.nextOrNull()
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
        var current = cursor.nextOrNull()
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
          current = cursor.nextOrNull()
        }
      }
      if (diagnostics) {
        closeCursorNanos += System.nanoTime() - closeStarted
        closeBuckets += 1L
      }
    }

    var currentStartEvent = Long.MinValue
    var currentRegion: RiftRegion.StreamingRegion^{stream} = null

    def processSensor(
        i: Int,
        device: Int,
        valueScaled: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      if (start != currentStartEvent) {
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
        currentStartEvent = start
        currentRegion =
          RiftRegion.pageTokenAppendRegionFor(
            stream,
            window,
            start,
            closeCutoff(start)
          )(closeRecords)
        if (diagnostics) {
          bucketSwitchNanos += System.nanoTime() - bucketStarted
          bucketSwitches += 1L
        }
      }
      val sensorRecord: CheckedRecord^{stream} =
        RiftRegion.alloc(
          new CheckedRecord(10, i, device, valueScaled, 0, false, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, sensorRecord)
      if (averageQuery(query)) {
        val avg = state.update(device, valueScaled)
        val avgRecord: CheckedRecord^{stream} =
          RiftRegion.alloc(
            new CheckedRecord(20, i, device, valueScaled, avg, false, hash ^ 20L)
          )(using currentRegion)
        RiftRegion.appendPageToken(stream, window, avgRecord)
        if (candidateQuery(query)) {
          val spike = isSpike(valueScaled, avg)
          val candidate: CheckedRecord^{stream} =
            RiftRegion.alloc(
              new CheckedRecord(30, i, device, valueScaled, avg, spike, hash ^ 30L)
            )(using currentRegion)
          RiftRegion.appendPageToken(stream, window, candidate)
        }
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 99, i, device, valueScaled, 0, false, hash, start)
    }

    def appendChecked(
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
        RiftRegion.alloc(
          new CheckedRecord(kind, i, key, value, score, flag, hash)
        )(using currentRegion)
      RiftRegion.appendPageToken(stream, window, record)
      if (diagnostics) {
        appendNanos += System.nanoTime() - appendStarted
        appendedRecords += 1L
      }
    }

    def processFraud(
        i: Int,
        entity: Int,
        stateCode: Int,
        hash: Long
    ): Unit = {
      val start = bucketStart(i)
      if (start != currentStartEvent) {
        val bucketStarted = if (diagnostics) System.nanoTime() else 0L
        currentStartEvent = start
        currentRegion =
          RiftRegion.pageTokenAppendRegionFor(
            stream,
            window,
            start,
            closeCutoff(start)
          )(closeRecords)
        if (diagnostics) {
          bucketSwitchNanos += System.nanoTime() - bucketStarted
          bucketSwitches += 1L
        }
      }
      appendChecked(110, i, entity, stateCode, 0, false, hash)
      if (fraudPredictQuery(query)) {
        val predictStarted = if (diagnostics) System.nanoTime() else 0L
        val prediction = fraudState.update(entity, stateCode)
        if (diagnostics)
          predictNanos += System.nanoTime() - predictStarted
        appendChecked(
          120,
          i,
          entity,
          stateCode,
          prediction._1,
          prediction._2,
          hash ^ prediction._3.toLong ^ 120L
        )
        appendChecked(121, i, entity, prediction._3, prediction._1, false, hash ^ 121L)
        appendChecked(122, i, entity, stateCode, prediction._1, prediction._2, hash ^ 122L)
        if (fraudAlertQuery(query) && prediction._2)
          appendChecked(130, i, entity, stateCode, prediction._1, true, hash ^ 130L)
      }
      if (i % cfg.sampleEvery == 0)
        checksum = fold(checksum, 199, i, entity, stateCode, 0, false, hash, start)
    }

    if (fraudQuery(query)) {
      foreachFraud(new FraudConsumer {
        def apply(i: Int, entity: Int, stateCode: Int, hash: Long): Unit =
          processFraud(i, entity, stateCode, hash)
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
      println(
        f"DSPBENCH_DIAG query=$query mode=$modeLabel " +
          s"bucket_switches=$bucketSwitches appended_records=$appendedRecords " +
          s"closed_records=$closedRecords close_buckets=$closeBuckets " +
          f"bucket_switch_ms=${ms(bucketSwitchNanos)}%.3f " +
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
      runRiftCheckedPageTokenBody(query, "rift-checked-page-token")
    }

  private def runRiftCheckedSafeZonePageToken(query: String): RunOutcome =
    RiftRegion.streamingSafeZone { stream ?=>
      runRiftCheckedPageTokenBody(query, "rift-checked-safezone-page-token")
    }

  private def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" | "heap-immix" => "heap"
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
      case "checked-region-stream" | "rift-checked-page-token" =>
        "rift-checked-page-token"
      case "checked-region-scoped" | "rift-checked-safezone-page-token" =>
        "rift-checked-safezone-page-token"
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
          s"unknown DSPBench mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-moving-average" | "q2-spike-window" |
          "fraud-q0-parse" | "fraud-q1-predict" |
          "fraud-q2-alert-window" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown DSPBench query '$other'"
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
          s"unknown DSPBench mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = DSPBenchRegionConfig
    val input = inputDataFor(query)
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
