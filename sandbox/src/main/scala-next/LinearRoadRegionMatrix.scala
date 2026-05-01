import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object LinearRoadRegionConfig {
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

  val events: Int = envInt("LINEAR_ROAD_EVENTS", 1000000)
  val eventsPerBucket: Int = envInt("LINEAR_ROAD_EVENTS_PER_BUCKET", 25000)
  val liveBuckets: Int = envInt("LINEAR_ROAD_LIVE_BUCKETS", 4)
  val vehicleSpace: Int = envInt("LINEAR_ROAD_VEHICLE_SPACE", 100000)
  val expresswaySpace: Int = envInt("LINEAR_ROAD_EXPRESSWAY_SPACE", 4)
  val segmentSpace: Int = envInt("LINEAR_ROAD_SEGMENT_SPACE", 1024)
  val laneSpace: Int = envInt("LINEAR_ROAD_LANE_SPACE", 4)
  val positionRange: Int = envInt("LINEAR_ROAD_POSITION_RANGE", 528000)
  val sampleEvery: Int = envInt("LINEAR_ROAD_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("LINEAR_ROAD_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("LINEAR_ROAD_BENCHMARK_RUNS", 3)
  val inputPath: String =
    BenchmarkInputSupport.envString("LINEAR_ROAD_INPUT")
}

object LinearRoadRegionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L

  private final class HeapEvent(
      val kind: Int,
      val timestamp: Long,
      val vehicle: Int,
      val expressway: Int,
      val segment: Int,
      val lane: Int,
      val position: Int,
      val speed: Int,
      val value: Int,
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
      val vehicle: Int,
      val expressway: Int,
      val segment: Int,
      val lane: Int,
      val position: Int,
      val speed: Int,
      val value: Int,
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
      val vehicle: Int,
      val expressway: Int,
      val segment: Int,
      val lane: Int,
      val position: Int,
      val speed: Int,
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

  final case class LatencySummary(
      eventP50Nanos: Long,
      eventP95Nanos: Long,
      eventMaxNanos: Long,
      bucketCloseMaxNanos: Long
  )

  private final class InputData(
      val label: String,
      val events: Int,
      val timestamps: Array[Long],
      val vehicles: Array[Int],
      val expressways: Array[Int],
      val segments: Array[Int],
      val lanes: Array[Int],
      val directions: Array[Int],
      val positions: Array[Int],
      val speeds: Array[Int]
  ) {
    def timestampAt(index: Int): Long =
      if (timestamps == null) index.toLong else timestamps(index)

    def vehicleAt(index: Int): Int =
      if (vehicles == null) vehicleFor(index) else vehicles(index)

    def expresswayAt(index: Int, vehicle: Int): Int =
      if (expressways == null) expresswayFor(index, vehicle)
      else expressways(index)

    def segmentAt(index: Int, vehicle: Int): Int =
      if (segments == null) segmentFor(index, vehicle) else segments(index)

    def laneAt(index: Int, vehicle: Int): Int =
      if (lanes == null) laneFor(index, vehicle) else lanes(index)

    def directionAt(index: Int, vehicle: Int): Int =
      if (directions == null) directionFor(index, vehicle) else directions(index)

    def positionAt(index: Int, segment: Int): Int =
      if (positions == null) positionFor(segment, index) else positions(index)

    def speedAt(index: Int): Int =
      if (speeds == null) speedFor(index) else speeds(index)
  }

  private lazy val inputData: InputData = loadInput()

  private final class LatencyRecorder(cfg: LinearRoadRegionConfig.type) {
    private val eventNanos =
      new Array[Long](cfg.events / cfg.sampleEvery + 4)
    private val closeNanos =
      new Array[Long](cfg.events / cfg.eventsPerBucket + cfg.liveBuckets + 8)
    private var eventCount = 0
    private var closeCount = 0

    def recordEvent(nanos: Long): Unit =
      if (eventCount < eventNanos.length) {
        eventNanos(eventCount) = nanos
        eventCount += 1
      }

    def recordBucketClose(nanos: Long): Unit =
      if (closeCount < closeNanos.length) {
        closeNanos(closeCount) = nanos
        closeCount += 1
      }

    private def percentile(values: Array[Long], count: Int, p: Double): Long =
      if (count == 0) 0L
      else {
        val copy = new Array[Long](count)
        var i = 0
        while (i < count) {
          copy(i) = values(i)
          i += 1
        }
        scala.util.Sorting.quickSort(copy)
        val raw = math.ceil(count.toDouble * p).toInt - 1
        val index =
          if (raw < 0) 0 else if (raw >= count) count - 1 else raw
        copy(index)
      }

    private def max(values: Array[Long], count: Int): Long = {
      var result = 0L
      var i = 0
      while (i < count) {
        if (values(i) > result) result = values(i)
        i += 1
      }
      result
    }

    def summary(): LatencySummary =
      LatencySummary(
        eventP50Nanos = percentile(eventNanos, eventCount, 0.50),
        eventP95Nanos = percentile(eventNanos, eventCount, 0.95),
        eventMaxNanos = max(eventNanos, eventCount),
        bucketCloseMaxNanos = max(closeNanos, closeCount)
      )
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
    val cfg = LinearRoadRegionConfig
    (eventIndex / cfg.eventsPerBucket).toLong * cfg.eventsPerBucket.toLong
  }

  private def closeCutoff(currentStartEvent: Long): Long = {
    val cfg = LinearRoadRegionConfig
    currentStartEvent -
      (cfg.liveBuckets.toLong - 1L) * cfg.eventsPerBucket.toLong
  }

  private def vehicleFor(eventIndex: Int): Int =
    mix(eventIndex * 1103515245 + 12345) %
      LinearRoadRegionConfig.vehicleSpace

  private def expresswayFor(eventIndex: Int, vehicle: Int): Int =
    mix(eventIndex * 31 + vehicle * 17) %
      LinearRoadRegionConfig.expresswaySpace

  private def segmentFor(eventIndex: Int, vehicle: Int): Int =
    mix(eventIndex / 4 + vehicle * 13 + 97) %
      LinearRoadRegionConfig.segmentSpace

  private def laneFor(eventIndex: Int, vehicle: Int): Int =
    mix(eventIndex * 7 + vehicle) % LinearRoadRegionConfig.laneSpace

  private def directionFor(eventIndex: Int, vehicle: Int): Int =
    mix(eventIndex + vehicle * 3) & 1

  private def speedFor(eventIndex: Int): Int = {
    val slow = mix(eventIndex * 97 + 11) % 100
    if (slow < 4) 0 else 20 + (mix(eventIndex * 23 + 53) % 55)
  }

  private def positionFor(segment: Int, eventIndex: Int): Int = {
    val cfg = LinearRoadRegionConfig
    val segmentBase =
      (segment.toLong * 528L % cfg.positionRange.toLong).toInt
    segmentBase + (mix(eventIndex * 19 + 5) % 528)
  }

  private def segmentSlot(expressway: Int, direction: Int, segment: Int): Int =
    ((expressway * 2 + direction) * LinearRoadRegionConfig.segmentSpace) +
      segment

  private def loadInput(): InputData = {
    val cfg = LinearRoadRegionConfig
    if (cfg.inputPath.isEmpty)
      return new InputData(
        "generated-linear-road-shaped",
        cfg.events,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
      )

    val timestamps = scala.collection.mutable.ArrayBuffer.empty[Long]
    val vehicles = scala.collection.mutable.ArrayBuffer.empty[Int]
    val expressways = scala.collection.mutable.ArrayBuffer.empty[Int]
    val segments = scala.collection.mutable.ArrayBuffer.empty[Int]
    val lanes = scala.collection.mutable.ArrayBuffer.empty[Int]
    val directions = scala.collection.mutable.ArrayBuffer.empty[Int]
    val positions = scala.collection.mutable.ArrayBuffer.empty[Int]
    val speeds = scala.collection.mutable.ArrayBuffer.empty[Int]
    val reader = BenchmarkInputSupport.openText(cfg.inputPath)
    try {
      var line = reader.readLine()
      while (line != null && timestamps.length < cfg.events) {
        if (line.nonEmpty) {
          val parts = line.split(",", -1)
          if (parts.length >= 9) {
            val recordType = BenchmarkInputSupport.parseInt(parts(0), -1)
            if (recordType == 0) {
              timestamps += BenchmarkInputSupport.parseLong(
                parts(1),
                timestamps.length.toLong
              )
              vehicles += BenchmarkInputSupport.positiveModulo(
                BenchmarkInputSupport.parseInt(parts(2), 0),
                cfg.vehicleSpace
              )
              speeds += BenchmarkInputSupport.parseInt(parts(3), 0)
              expressways += BenchmarkInputSupport.positiveModulo(
                BenchmarkInputSupport.parseInt(parts(4), 0),
                cfg.expresswaySpace
              )
              lanes += BenchmarkInputSupport.positiveModulo(
                BenchmarkInputSupport.parseInt(parts(5), 0),
                cfg.laneSpace
              )
              directions += (BenchmarkInputSupport.parseInt(parts(6), 0) & 1)
              segments += BenchmarkInputSupport.positiveModulo(
                BenchmarkInputSupport.parseInt(parts(7), 0),
                cfg.segmentSpace
              )
              positions += BenchmarkInputSupport.parseInt(parts(8), 0)
            }
          }
        }
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }

    if (timestamps.isEmpty)
      throw new IllegalArgumentException(
        s"Linear Road input '${cfg.inputPath}' did not contain any usable position reports"
      )

    new InputData(
      "real-linear-road-preloaded",
      timestamps.length,
      timestamps.toArray,
      vehicles.toArray,
      expressways.toArray,
      segments.toArray,
      lanes.toArray,
      directions.toArray,
      positions.toArray,
      speeds.toArray
    )
  }

  private def eventHash(
      kind: Int,
      eventIndex: Int,
      vehicle: Int,
      segment: Int,
      value: Int
  ): Long =
    mix(
      eventIndex * 1000003 +
        vehicle * 8191 +
        segment * 131 +
        kind * 53 +
        value
    ).toLong

  private def updateSegmentCount(
      vehicle: Int,
      slot: Int,
      lastSegmentByVehicle: Array[Int],
      segmentCounts: Array[Int]
  ): Int = {
    val old = lastSegmentByVehicle(vehicle)
    if (old != slot) {
      if (old >= 0) segmentCounts(old) -= 1
      segmentCounts(slot) += 1
      lastSegmentByVehicle(vehicle) = slot
    }
    segmentCounts(slot)
  }

  private def updateStoppedCount(
      vehicle: Int,
      position: Int,
      speed: Int,
      lastPositionByVehicle: Array[Int],
      stoppedCountByVehicle: Array[Int]
  ): Int = {
    val oldPosition = lastPositionByVehicle(vehicle)
    val stopped =
      if (speed <= 5 && oldPosition == position) stoppedCountByVehicle(vehicle) + 1
      else if (speed <= 5) 1
      else 0
    stoppedCountByVehicle(vehicle) = stopped
    lastPositionByVehicle(vehicle) = position
    stopped
  }

  private def fold(
      checksum: Long,
      kind: Int,
      timestamp: Long,
      vehicle: Int,
      expressway: Int,
      segment: Int,
      lane: Int,
      position: Int,
      speed: Int,
      value: Int,
      hash: Long,
      bucketStartEvent: Long
  ): Long = {
    var h = checksum ^ kind.toLong
    h = (h * 1099511628211L) ^ timestamp
    h = (h * 1099511628211L) ^ vehicle.toLong
    h = (h * 1099511628211L) ^ expressway.toLong
    h = (h * 1099511628211L) ^ segment.toLong
    h = (h * 1099511628211L) ^ lane.toLong
    h = (h * 1099511628211L) ^ position.toLong
    h = (h * 1099511628211L) ^ speed.toLong
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
        throw new IllegalArgumentException(
          s"unknown Linear Road mode '$other'"
        )
    }

  def validateQuery(query: String): Unit =
    query match {
      case "q0-reports" | "q1-tolls" | "q2-accidents" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Linear Road query '$other'"
        )
    }

  private def consume(bucketStart: Long, event: HeapEvent, checksum: Long): Long =
    fold(
      checksum,
      event.kind,
      event.timestamp,
      event.vehicle,
      event.expressway,
      event.segment,
      event.lane,
      event.position,
      event.speed,
      event.value,
      event.hash,
      bucketStart
    )

  private def consume(bucketStart: Long, event: SafeEvent, checksum: Long): Long =
    fold(
      checksum,
      event.kind,
      event.timestamp,
      event.vehicle,
      event.expressway,
      event.segment,
      event.lane,
      event.position,
      event.speed,
      event.value,
      event.hash,
      bucketStart
    )

  private def consume(
      bucketStart: Long,
      event: TrustedEvent,
      checksum: Long
  ): Long =
    fold(
      checksum,
      event.kind,
      event.timestamp,
      event.vehicle,
      event.expressway,
      event.segment,
      event.lane,
      event.position,
      event.speed,
      event.value,
      event.hash,
      bucketStart
    )

  private def runHeap(
      query: String,
      latency: LatencyRecorder | Null
  ): RunOutcome = {
    val cfg = LinearRoadRegionConfig
    val input = inputData
    val lastSegmentByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val lastPositionByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val stoppedCountByVehicle = new Array[Int](cfg.vehicleSpace)
    val segmentCounts = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    val accidentState = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    var first: HeapBucket = null
    var last: HeapBucket = null
    var current: HeapBucket = null
    var checksum = 0L
    var outputCount = 0L

    def closeBucket(bucket: HeapBucket): Unit = {
      val closeStart = if (latency == null) 0L else System.nanoTime()
      var event = bucket.head
      while (event != null) {
        checksum = consume(bucket.startEvent, event, checksum)
        outputCount += 1L
        event = event.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      if (latency != null)
        latency.recordBucketClose(System.nanoTime() - closeStart)
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
    while (eventIndex < input.events) {
      val sample =
        latency != null && eventIndex % cfg.sampleEvery == 0
      val sampleStart = if (sample) System.nanoTime() else 0L
      val vehicle = input.vehicleAt(eventIndex)
      val expressway = input.expresswayAt(eventIndex, vehicle)
      val segment = input.segmentAt(eventIndex, vehicle)
      val lane = input.laneAt(eventIndex, vehicle)
      val direction = input.directionAt(eventIndex, vehicle)
      val position = input.positionAt(eventIndex, segment)
      val speed = input.speedAt(eventIndex)
      val slot = segmentSlot(expressway, direction, segment)
      val count =
        updateSegmentCount(vehicle, slot, lastSegmentByVehicle, segmentCounts)
      val bucket = bucketFor(bucketStart(eventIndex))
      val timestamp = input.timestampAt(eventIndex)

      appendEvent(
        bucket,
        new HeapEvent(
          1,
          timestamp,
          vehicle,
          expressway,
          segment,
          lane,
          position,
          speed,
          count,
          eventHash(1, eventIndex, vehicle, segment, count),
          null
        )
      )

      if (query == "q1-tolls") {
        val toll = if (speed < 40) count * count + (40 - speed) else count
        appendEvent(
          bucket,
          new HeapEvent(
            2,
            timestamp,
            vehicle,
            expressway,
            segment,
            lane,
            position,
            speed,
            toll,
            eventHash(2, eventIndex, vehicle, segment, toll),
            null
          )
        )
      } else if (query == "q2-accidents") {
        val stopped =
          updateStoppedCount(
            vehicle,
            position,
            speed,
            lastPositionByVehicle,
            stoppedCountByVehicle
          )
        val accident =
          if (stopped >= 2) {
            accidentState(slot) = 1
            1
          } else if (accidentState(slot) == 1) 1
          else if (count >= 12) 2
          else 0
        appendEvent(
          bucket,
          new HeapEvent(
            3,
            timestamp,
            vehicle,
            expressway,
            segment,
            lane,
            position,
            speed,
            accident,
            eventHash(3, eventIndex, vehicle, segment, accident),
            null
          )
        )
      }

      if (sample)
        latency.recordEvent(System.nanoTime() - sampleStart)
      eventIndex += 1
    }

    closeExpired(Long.MaxValue)
    checksumSink = checksum
    outputSink = outputCount
    RunOutcome(checksum, outputCount)
  }

  private def runSafeZone(
      query: String,
      latency: LatencyRecorder | Null
  ): RunOutcome = {
    val cfg = LinearRoadRegionConfig
    val input = inputData
    val lastSegmentByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val lastPositionByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val stoppedCountByVehicle = new Array[Int](cfg.vehicleSpace)
    val segmentCounts = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    val accidentState = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    var first: SafeBucket = null
    var last: SafeBucket = null
    var current: SafeBucket = null
    var checksum = 0L
    var outputCount = 0L

    def closeBucket(bucket: SafeBucket): Unit = {
      val closeStart = if (latency == null) 0L else System.nanoTime()
      var event = bucket.head
      while (event != null) {
        checksum = consume(bucket.startEvent, event, checksum)
        outputCount += 1L
        event = event.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      SafeZone.close(bucket.zone)
      if (latency != null)
        latency.recordBucketClose(System.nanoTime() - closeStart)
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
      while (eventIndex < input.events) {
        val sample =
          latency != null && eventIndex % cfg.sampleEvery == 0
        val sampleStart = if (sample) System.nanoTime() else 0L
        val vehicle = input.vehicleAt(eventIndex)
        val expressway = input.expresswayAt(eventIndex, vehicle)
        val segment = input.segmentAt(eventIndex, vehicle)
        val lane = input.laneAt(eventIndex, vehicle)
        val direction = input.directionAt(eventIndex, vehicle)
        val position = input.positionAt(eventIndex, segment)
        val speed = input.speedAt(eventIndex)
        val slot = segmentSlot(expressway, direction, segment)
        val count =
          updateSegmentCount(vehicle, slot, lastSegmentByVehicle, segmentCounts)
        val bucket = bucketFor(bucketStart(eventIndex))
        val zone = bucket.zone
        val timestamp = input.timestampAt(eventIndex)

        appendEvent(
          bucket,
          SafeZoneAllocator
            .allocate(
              zone,
              new SafeEvent(
                1,
                timestamp,
                vehicle,
                expressway,
                segment,
                lane,
                position,
                speed,
                count,
                eventHash(1, eventIndex, vehicle, segment, count),
                null
              )
            )
            .asInstanceOf[SafeEvent]
        )

        if (query == "q1-tolls") {
          val toll = if (speed < 40) count * count + (40 - speed) else count
          appendEvent(
            bucket,
            SafeZoneAllocator
              .allocate(
                zone,
                new SafeEvent(
                  2,
                  timestamp,
                  vehicle,
                  expressway,
                  segment,
                  lane,
                  position,
                  speed,
                  toll,
                  eventHash(2, eventIndex, vehicle, segment, toll),
                  null
                )
              )
              .asInstanceOf[SafeEvent]
          )
        } else if (query == "q2-accidents") {
          val stopped =
            updateStoppedCount(
              vehicle,
              position,
              speed,
              lastPositionByVehicle,
              stoppedCountByVehicle
            )
          val accident =
            if (stopped >= 2) {
              accidentState(slot) = 1
              1
            } else if (accidentState(slot) == 1) 1
            else if (count >= 12) 2
            else 0
          appendEvent(
            bucket,
            SafeZoneAllocator
              .allocate(
                zone,
                new SafeEvent(
                  3,
                  timestamp,
                  vehicle,
                  expressway,
                  segment,
                  lane,
                  position,
                  speed,
                  accident,
                  eventHash(3, eventIndex, vehicle, segment, accident),
                  null
                )
              )
              .asInstanceOf[SafeEvent]
          )
        }

        if (sample)
          latency.recordEvent(System.nanoTime() - sampleStart)
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

  private def runRiftTrusted(
      query: String,
      kind: Int,
      latency: LatencyRecorder | Null
  ): RunOutcome = {
    val cfg = LinearRoadRegionConfig
    val input = inputData
    val lastSegmentByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val lastPositionByVehicle = Array.fill(cfg.vehicleSpace)(-1)
    val stoppedCountByVehicle = new Array[Int](cfg.vehicleSpace)
    val segmentCounts = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    val accidentState = new Array[Int](cfg.expresswaySpace * 2 * cfg.segmentSpace)
    var first: TrustedBucket = null
    var last: TrustedBucket = null
    var current: TrustedBucket = null
    var checksum = 0L
    var outputCount = 0L

    def closeBucket(bucket: TrustedBucket): Unit = {
      val closeStart = if (latency == null) 0L else System.nanoTime()
      var event = bucket.head
      while (event != null) {
        checksum = consume(bucket.startEvent, event, checksum)
        outputCount += 1L
        event = event.next
      }
      bucket.head = null
      bucket.tail = null
      bucket.next = null
      bucket.region.close()
      if (latency != null)
        latency.recordBucketClose(System.nanoTime() - closeStart)
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
      while (eventIndex < input.events) {
        val sample =
          latency != null && eventIndex % cfg.sampleEvery == 0
        val sampleStart = if (sample) System.nanoTime() else 0L
        val vehicle = input.vehicleAt(eventIndex)
        val expressway = input.expresswayAt(eventIndex, vehicle)
        val segment = input.segmentAt(eventIndex, vehicle)
        val lane = input.laneAt(eventIndex, vehicle)
        val direction = input.directionAt(eventIndex, vehicle)
        val position = input.positionAt(eventIndex, segment)
        val speed = input.speedAt(eventIndex)
        val slot = segmentSlot(expressway, direction, segment)
        val count =
          updateSegmentCount(vehicle, slot, lastSegmentByVehicle, segmentCounts)
        val bucket = bucketFor(bucketStart(eventIndex))
        val region = bucket.region
        val timestamp = input.timestampAt(eventIndex)

        appendEvent(
          bucket,
          region.alloc(
            new TrustedEvent(
              1,
              timestamp,
              vehicle,
              expressway,
              segment,
              lane,
              position,
              speed,
              count,
              eventHash(1, eventIndex, vehicle, segment, count),
              null
            )
          )
        )

        if (query == "q1-tolls") {
          val toll = if (speed < 40) count * count + (40 - speed) else count
          appendEvent(
            bucket,
            region.alloc(
              new TrustedEvent(
                2,
                timestamp,
                vehicle,
                expressway,
                segment,
                lane,
                position,
                speed,
                toll,
                eventHash(2, eventIndex, vehicle, segment, toll),
                null
              )
            )
          )
        } else if (query == "q2-accidents") {
          val stopped =
            updateStoppedCount(
              vehicle,
              position,
              speed,
              lastPositionByVehicle,
              stoppedCountByVehicle
            )
          val accident =
            if (stopped >= 2) {
              accidentState(slot) = 1
              1
            } else if (accidentState(slot) == 1) 1
            else if (count >= 12) 2
            else 0
          appendEvent(
            bucket,
            region.alloc(
              new TrustedEvent(
                3,
                timestamp,
                vehicle,
                expressway,
                segment,
                lane,
                position,
                speed,
                accident,
                eventHash(3, eventIndex, vehicle, segment, accident),
                null
              )
            )
          )
        }

        if (sample)
          latency.recordEvent(System.nanoTime() - sampleStart)
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

  private def runMode(
      mode: String,
      query: String,
      latency: LatencyRecorder | Null
  ): RunOutcome =
    mode match {
      case "heap" =>
        runHeap(query, latency)
      case "safezone" =>
        runSafeZone(query, latency)
      case "rift-hp" =>
        runRiftTrusted(query, RiftRegion.HPZone, latency)
      case "rift-streaming" =>
        runRiftTrusted(query, RiftRegion.Streaming, latency)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Linear Road mode '$other'"
        )
    }

  def runBenchmark(mode: String, query: String): Unit = {
    val cfg = LinearRoadRegionConfig
    val input = inputData
    val usesRift = mode == "rift-hp" || mode == "rift-streaming"
    val expected = runHeap(query, null)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val outcome = runMode(mode, query, null)
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
    val eventP50Nanos = new Array[Long](cfg.benchmarkRuns)
    val eventP95Nanos = new Array[Long](cfg.benchmarkRuns)
    val eventMaxNanos = new Array[Long](cfg.benchmarkRuns)
    val closeMaxNanos = new Array[Long](cfg.benchmarkRuns)

    println(
      s"Running linear-road-$query-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val latency = new LatencyRecorder(cfg)
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val outcome = runMode(mode, query, latency)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      val summary = latency.summary()

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
      eventP50Nanos(run) = summary.eventP50Nanos
      eventP95Nanos(run) = summary.eventP95Nanos
      eventMaxNanos(run) = summary.eventMaxNanos
      closeMaxNanos(run) = summary.bucketCloseMaxNanos

      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"rift_op_ms=${runtime.riftRegionOpNanos / 1000000.0}%.3f " +
          f"rift_slow_alloc_ms=${runtime.riftSlowAllocNanos / 1000000.0}%.3f " +
          f"rift_open_total=${runtime.riftRegionOpenTotal}%d " +
          f"rift_close_total=${runtime.riftRegionCloseTotal}%d " +
          f"rift_reset_total=${runtime.riftRegionResetTotal}%d " +
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d " +
          f"sample_event_p50_us=${summary.eventP50Nanos / 1000.0}%.3f " +
          f"sample_event_p95_us=${summary.eventP95Nanos / 1000.0}%.3f " +
          f"sample_event_max_us=${summary.eventMaxNanos / 1000.0}%.3f " +
          f"bucket_close_max_us=${summary.bucketCloseMaxNanos / 1000.0}%.3f"
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
    val medianEventP50 = medianLong(eventP50Nanos)
    val medianEventP95 = medianLong(eventP95Nanos)
    val medianEventMax = medianLong(eventMaxNanos)
    val medianCloseMax = medianLong(closeMaxNanos)

    println(
      f"RESULT name=linear-road-$query-$mode " +
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
        f"median_sample_event_p50_us=${medianEventP50 / 1000.0}%.3f " +
        f"median_sample_event_p95_us=${medianEventP95 / 1000.0}%.3f " +
        f"median_sample_event_max_us=${medianEventMax / 1000.0}%.3f " +
        f"median_bucket_close_max_us=${medianCloseMax / 1000.0}%.3f " +
        f"checksum=${expected.checksum}%d " +
        f"output_count=${expected.outputCount}%d"
    )
  }

  def printConfig(mode: String, query: String): Unit = {
    val cfg = LinearRoadRegionConfig
    val input = inputData
    println(
      s"CONFIG mode=$mode query=$query events=${input.events} configured_events=${cfg.events} events_per_bucket=${cfg.eventsPerBucket} live_buckets=${cfg.liveBuckets} vehicle_space=${cfg.vehicleSpace} expressway_space=${cfg.expresswaySpace} segment_space=${cfg.segmentSpace} lane_space=${cfg.laneSpace} position_range=${cfg.positionRange} sample_every=${cfg.sampleEvery} warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} input=${input.label} input_path=${cfg.inputPath}"
    )
  }
}

@main def LinearRoadRegionMatrix(
    mode: String = "heap",
    query: String = "q1-tolls"
): Unit = {
  LinearRoadRegionMatrixHelpers.validateMode(mode)
  LinearRoadRegionMatrixHelpers.validateQuery(query)
  LinearRoadRegionMatrixHelpers.printConfig(mode, query)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    LinearRoadRegionMatrixHelpers.runBenchmark(mode, query)
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
