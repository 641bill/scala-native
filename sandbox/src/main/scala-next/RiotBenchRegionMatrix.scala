import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object RiotBenchRegionConfig {
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

  val events: Int = envInt("RIOTBENCH_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("RIOTBENCH_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("RIOTBENCH_LIVE_BUCKETS", 4)
  val sensorSpace: Int = envInt("RIOTBENCH_SENSOR_SPACE", 4096)
  val deviceSpace: Int = envInt("RIOTBENCH_DEVICE_SPACE", 1024)
  val warmupRuns: Int = envNonNegativeInt("RIOTBENCH_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("RIOTBENCH_BENCHMARK_RUNS", 3)
  val inputPath: String = BenchmarkInputSupport.envString("RIOTBENCH_INPUT")
}

object RiotBenchRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapRecord(
      val kind: Int,
      val timestamp: Long,
      val sensorId: Int,
      val deviceId: Int,
      val value: Int,
      val quality: Int,
      val hash: Long,
      var next: HeapRecord
  )

  private final class HeapBucket(val startEvent: Long, var next: HeapBucket) {
    var head: HeapRecord = null
    var tail: HeapRecord = null
  }

  private final class SafeRecord(
      val kind: Int,
      val timestamp: Long,
      val sensorId: Int,
      val deviceId: Int,
      val value: Int,
      val quality: Int,
      val hash: Long,
      var next: SafeRecord
  )

  private final class SafeBucket(val zone: SafeZone, val startEvent: Long, var next: SafeBucket) {
    var head: SafeRecord = null
    var tail: SafeRecord = null
  }

  private final class TrustedRecord(
      val kind: Int,
      val timestamp: Long,
      val sensorId: Int,
      val deviceId: Int,
      val value: Int,
      val quality: Int,
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
      val sensorIds: Array[Int],
      val deviceIds: Array[Int],
      val values: Array[Int],
      val qualities: Array[Int],
      val hashes: Array[Long]
  ) {
    def sensorAt(index: Int): Int =
      if (sensorIds == null) sensorFor(index) else sensorIds(index)

    def deviceAt(index: Int): Int =
      if (deviceIds == null) deviceFor(index) else deviceIds(index)

    def valueAt(index: Int): Int =
      if (values == null) valueFor(index) else values(index)

    def qualityAt(index: Int): Int =
      if (qualities == null) qualityFor(index) else qualities(index)

    def hashAt(index: Int, sensor: Int, value: Int): Long =
      if (hashes == null) readingHash(index, sensor, value)
      else hashes(index) ^ (value.toLong * 1099511628211L)
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

  private def sensorFor(index: Int): Int =
    mix(index * 1103515245 + 12345) % RiotBenchRegionConfig.sensorSpace

  private def deviceFor(index: Int): Int =
    mix(index * 1664525 + 1013904223) % RiotBenchRegionConfig.deviceSpace

  private def valueFor(index: Int): Int =
    (mix(index * 8191 + 17) % 240) - 40

  private def qualityFor(index: Int): Int =
    if ((mix(index * 65537 + 19) % 100) < 94) 1 else 0

  private def readingHash(index: Int, sensor: Int, value: Int): Long =
    mix(index * 1000003 + sensor * 8191 + value * 131).toLong

  private def bucketStart(eventIndex: Int): Long = {
    val cfg = RiotBenchRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartEvent: Long): Long = {
    val cfg = RiotBenchRegionConfig
    currentStartEvent -
      (cfg.liveBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def loadInput(): InputData = {
    val cfg = RiotBenchRegionConfig
    if (cfg.inputPath.isEmpty)
      return new InputData(
        "generated-riotbench-shaped",
        cfg.events,
        null,
        null,
        null,
        null,
        null
      )

    val sensors = scala.collection.mutable.ArrayBuffer.empty[Int]
    val devices = scala.collection.mutable.ArrayBuffer.empty[Int]
    val values = scala.collection.mutable.ArrayBuffer.empty[Int]
    val qualities = scala.collection.mutable.ArrayBuffer.empty[Int]
    val hashes = scala.collection.mutable.ArrayBuffer.empty[Long]
    val reader = BenchmarkInputSupport.openText(cfg.inputPath)

    try {
      var line = reader.readLine()
      while (line != null && sensors.length < cfg.events) {
        val trimmed = line.trim
        if (trimmed.nonEmpty && trimmed.charAt(0) != '#') {
          val fields = trimmed.split("[,\\t ]+")
          val sensorText = if (fields.length > 0) fields(0) else trimmed
          val deviceText = if (fields.length > 1) fields(1) else sensorText
          val valueText = if (fields.length > 2) fields(2) else "0"
          val qualityText = if (fields.length > 3) fields(3) else "1"
          sensors += BenchmarkInputSupport.positiveModulo(
            BenchmarkInputSupport.stableHash(sensorText),
            cfg.sensorSpace
          )
          devices += BenchmarkInputSupport.positiveModulo(
            BenchmarkInputSupport.stableHash(deviceText),
            cfg.deviceSpace
          )
          values += BenchmarkInputSupport.parseInt(valueText, 0)
          qualities += (if (BenchmarkInputSupport.parseInt(qualityText, 1) > 0) 1 else 0)
          hashes += BenchmarkInputSupport.stableHash(trimmed).toLong
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }

    if (sensors.isEmpty)
      throw new IllegalArgumentException(
        s"RIoTBench input '${cfg.inputPath}' did not contain usable rows"
      )

    new InputData(
      "real-riotbench-preloaded",
      sensors.length,
      sensors.toArray,
      devices.toArray,
      values.toArray,
      qualities.toArray,
      hashes.toArray
    )
  }

  private def cleanValue(value: Int): Int =
    if (value < -20) -20 else if (value > 180) 180 else value

  private def isClean(quality: Int, value: Int): Boolean =
    quality > 0 && value >= -30 && value <= 190

  private def fold(
      checksum: Long,
      kind: Int,
      timestamp: Long,
      sensorId: Int,
      deviceId: Int,
      value: Int,
      quality: Int,
      hash: Long,
      bucketStartEvent: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ timestamp
    h = (h * 1099511628211L) ^ sensorId.toLong
    h = (h * 1099511628211L) ^ deviceId.toLong
    h = (h * 1099511628211L) ^ value.toLong
    h = (h * 1099511628211L) ^ quality.toLong
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
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown RIoTBench mode '$other'")
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-parse" | "q1-clean-annotate" | "q2-window-stats" => ()
      case other =>
        throw new IllegalArgumentException(s"unknown RIoTBench query '$other'")
    }

  private def runHeap(query: String): RunOutcome = {
    val cfg = RiotBenchRegionConfig
    val input = inputData
    val sums =
      if (query == "q2-window-stats") new Array[Long](cfg.sensorSpace)
      else null
    val counts =
      if (query == "q2-window-stats") new Array[Int](cfg.sensorSpace)
      else null
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: HeapBucket, record: HeapRecord): Unit = {
      if (query == "q2-window-stats") {
        sums(record.sensorId) -= record.value.toLong
        counts(record.sensorId) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          counts(record.sensorId),
          record.quality,
          record.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          record.kind,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          record.value,
          record.quality,
          record.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }
    }

    def closeExpired(cutoffEvent: Long): Unit =
      while (
        first != null &&
        first.startEvent + cfg.eventsPerBucket.toLong <= cutoffEvent
      ) {
        val bucket = first
        var record = bucket.head
        while (record != null) {
          consume(bucket, record)
          record = record.next
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
      val sensor = input.sensorAt(i)
      val device = input.deviceAt(i)
      val rawValue = input.valueAt(i)
      val quality = input.qualityAt(i)
      val hash = input.hashAt(i, sensor, rawValue)
      val cleaned = cleanValue(rawValue)

      query match {
        case "q0-parse" =>
          appendRecord(bucket, new HeapRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null))
        case "q1-clean-annotate" =>
          appendRecord(bucket, new HeapRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null))
          if (isClean(quality, rawValue))
            appendRecord(bucket, new HeapRecord(21, i.toLong, sensor, device, cleaned, 2, hash, null))
        case "q2-window-stats" =>
          if (isClean(quality, rawValue)) {
            sums(sensor) += cleaned.toLong
            counts(sensor) += 1
            appendRecord(bucket, new HeapRecord(22, i.toLong, sensor, device, cleaned, 2, hash, null))
            val avg = (sums(sensor) / counts(sensor)).toInt
            checksum = fold(checksum, 32, i.toLong, sensor, device, avg, counts(sensor), hash, start)
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
    val cfg = RiotBenchRegionConfig
    val input = inputData
    val sums =
      if (query == "q2-window-stats") new Array[Long](cfg.sensorSpace)
      else null
    val counts =
      if (query == "q2-window-stats") new Array[Int](cfg.sensorSpace)
      else null
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: SafeBucket, record: SafeRecord): Unit =
      if (query == "q2-window-stats") {
        sums(record.sensorId) -= record.value.toLong
        counts(record.sensorId) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          counts(record.sensorId),
          record.quality,
          record.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          record.kind,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          record.value,
          record.quality,
          record.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }

    def closeBucket(bucket: SafeBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        consume(bucket, record)
        record = record.next
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
        val sensor = input.sensorAt(i)
        val device = input.deviceAt(i)
        val rawValue = input.valueAt(i)
        val quality = input.qualityAt(i)
        val hash = input.hashAt(i, sensor, rawValue)
        val cleaned = cleanValue(rawValue)

        query match {
          case "q0-parse" =>
            appendRecord(
              bucket,
              SafeZoneAllocator
                .allocate(zone, new SafeRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null))
                .asInstanceOf[SafeRecord]
            )
          case "q1-clean-annotate" =>
            appendRecord(
              bucket,
              SafeZoneAllocator
                .allocate(zone, new SafeRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null))
                .asInstanceOf[SafeRecord]
            )
            if (isClean(quality, rawValue))
              appendRecord(
                bucket,
                SafeZoneAllocator
                  .allocate(zone, new SafeRecord(21, i.toLong, sensor, device, cleaned, 2, hash, null))
                  .asInstanceOf[SafeRecord]
              )
          case "q2-window-stats" =>
            if (isClean(quality, rawValue)) {
              sums(sensor) += cleaned.toLong
              counts(sensor) += 1
              appendRecord(
                bucket,
                SafeZoneAllocator
                  .allocate(zone, new SafeRecord(22, i.toLong, sensor, device, cleaned, 2, hash, null))
                  .asInstanceOf[SafeRecord]
              )
              val avg = (sums(sensor) / counts(sensor)).toInt
              checksum = fold(checksum, 32, i.toLong, sensor, device, avg, counts(sensor), hash, start)
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
    val cfg = RiotBenchRegionConfig
    val input = inputData
    val sums =
      if (query == "q2-window-stats") new Array[Long](cfg.sensorSpace)
      else null
    val counts =
      if (query == "q2-window-stats") new Array[Int](cfg.sensorSpace)
      else null
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def consume(bucket: TrustedBucket, record: TrustedRecord): Unit =
      if (query == "q2-window-stats") {
        sums(record.sensorId) -= record.value.toLong
        counts(record.sensorId) -= 1
        checksum = fold(
          checksum,
          record.kind + 40,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          counts(record.sensorId),
          record.quality,
          record.hash,
          bucket.startEvent
        )
      } else {
        checksum = fold(
          checksum,
          record.kind,
          record.timestamp,
          record.sensorId,
          record.deviceId,
          record.value,
          record.quality,
          record.hash,
          bucket.startEvent
        )
        outputCount += 1L
      }

    def closeBucket(bucket: TrustedBucket): Unit = {
      var record = bucket.head
      while (record != null) {
        consume(bucket, record)
        record = record.next
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
        val sensor = input.sensorAt(i)
        val device = input.deviceAt(i)
        val rawValue = input.valueAt(i)
        val quality = input.qualityAt(i)
        val hash = input.hashAt(i, sensor, rawValue)
        val cleaned = cleanValue(rawValue)

        query match {
          case "q0-parse" =>
            appendRecord(bucket, region.alloc(new TrustedRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null)))
          case "q1-clean-annotate" =>
            appendRecord(bucket, region.alloc(new TrustedRecord(10, i.toLong, sensor, device, rawValue, quality, hash, null)))
            if (isClean(quality, rawValue))
              appendRecord(bucket, region.alloc(new TrustedRecord(21, i.toLong, sensor, device, cleaned, 2, hash, null)))
          case "q2-window-stats" =>
            if (isClean(quality, rawValue)) {
              sums(sensor) += cleaned.toLong
              counts(sensor) += 1
              appendRecord(bucket, region.alloc(new TrustedRecord(22, i.toLong, sensor, device, cleaned, 2, hash, null)))
              val avg = (sums(sensor) / counts(sensor)).toInt
              checksum = fold(checksum, 32, i.toLong, sensor, device, avg, counts(sensor), hash, start)
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
      case "heap"           => runHeap(query)
      case "safezone"       => runSafeZone(query)
      case "rift-hp"        => runRiftTrusted(query, RiftRegion.HPZone)
      case "rift-streaming" => runRiftTrusted(query, RiftRegion.Streaming)
      case other =>
        throw new IllegalArgumentException(s"unknown RIoTBench mode '$other'")
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = RiotBenchRegionConfig
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

    println(s"Running riotbench-$query-$mode for ${cfg.benchmarkRuns} timed runs")

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
      f"RESULT name=riotbench-$query-$mode " +
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
    val cfg = RiotBenchRegionConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode query=$query events=${input.events} configured_events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} sensor_space=${cfg.sensorSpace} device_space=${cfg.deviceSpace} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=${input.label} input_path=${cfg.inputPath}"
    )
  }
}

@main def RiotBenchRegionMatrix(
    mode: String = "heap",
    query: String = "q2-window-stats"
): Unit = {
  RiotBenchRegionMatrixHelpers.validateMode(mode)
  RiotBenchRegionMatrixHelpers.validateQuery(query)
  RiotBenchRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    RiotBenchRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
