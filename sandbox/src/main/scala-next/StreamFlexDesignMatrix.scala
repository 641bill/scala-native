import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object StreamFlexDesignConfig {
  private def parsePositiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def parsePositiveLong(value: String): Option[Long] =
    try {
      val parsed = value.toLong
      if (parsed > 0L) Some(parsed) else None
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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name).flatMap(parsePositiveLong).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val workload: String =
    sys.env.getOrElse("STREAMFLEX_DESIGN_WORKLOAD", "all").toLowerCase
  val events: Int =
    envInt("STREAMFLEX_DESIGN_EVENTS", 200000)
  val periodEvents: Int =
    envInt("STREAMFLEX_DESIGN_PERIOD_EVENTS", 256)
  val objectsPerEvent: Int =
    envInt("STREAMFLEX_DESIGN_OBJECTS_PER_EVENT", 8)
  val latencyEvents: Int =
    envInt("STREAMFLEX_DESIGN_LATENCY_EVENTS", 10000)
  val latencyObjectsPerEvent: Int =
    envInt("STREAMFLEX_DESIGN_LATENCY_OBJECTS_PER_EVENT", 16)
  val pressureLatencyEvents: Int =
    envInt("STREAMFLEX_DESIGN_PRESSURE_LATENCY_EVENTS", 50000)
  val pressureObjectsPerEvent: Int =
    envInt("STREAMFLEX_DESIGN_PRESSURE_OBJECTS_PER_EVENT", 64)
  val capsuleCapacity: Int =
    envInt("STREAMFLEX_DESIGN_CAPSULE_CAPACITY", 65536)
  val stableKeys: Int =
    envInt("STREAMFLEX_DESIGN_STABLE_KEYS", 256)
  val periodNs: Long =
    envLong("STREAMFLEX_DESIGN_PERIOD_NS", 80000L)
  val benchmarkRuns: Int =
    envInt("STREAMFLEX_DESIGN_BENCHMARK_RUNS", 3)
  val warmupRuns: Int =
    envNonNegativeInt("STREAMFLEX_DESIGN_WARMUPS", 1)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
}

object StreamFlexDesignMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class StableState(keyCount: Int) {
    val thresholds: Array[Long] = new Array[Long](keyCount)
    val laneBias: Array[Int] = new Array[Int](keyCount)
    val eventCounts: Array[Int] = new Array[Int](keyCount)
    val alertCounts: Array[Int] = new Array[Int](keyCount)
    val scoreTotals: Array[Long] = new Array[Long](keyCount)

    private var i = 0
    while (i < keyCount) {
      thresholds(i) = 300000L + (i.toLong * 7919L % 65536L)
      laneBias(i) = (i * 17 + 13) & 0xff
      i += 1
    }

    def key(masked: Int): Int =
      masked & (keyCount - 1)

    def recordEvent(key: Int): Unit =
      eventCounts(key) += 1

    def classify(key: Int, value: Int, seq: Int): Long =
      (value.toLong * 31L) + thresholds(key) + laneBias(key).toLong + seq.toLong

    def recordAlert(key: Int, score: Long): Unit = {
      alertCounts(key) += 1
      scoreTotals(key) += score
    }

    def checksum: Long = {
      var out = 0L
      var i = 0
      while (i < thresholds.length) {
        out = mix(out, eventCounts(i).toLong)
        out = mix(out, alertCounts(i).toLong)
        out = mix(out, scoreTotals(i))
        i += 1
      }
      out
    }
  }

  private final class AlertCapsule(capacity: Int) {
    private val seqs = new Array[Int](capacity)
    private val keys = new Array[Int](capacity)
    private val scores = new Array[Long](capacity)
    private var size0 = 0
    var dropped: Int = 0

    def clear(): Unit = {
      size0 = 0
      dropped = 0
    }

    def add(seq: Int, key: Int, score: Long): Unit =
      if (size0 < capacity) {
        seqs(size0) = seq
        keys(size0) = key
        scores(size0) = score
        size0 += 1
      } else dropped += 1

    def drainInto(stable: StableState): Long = {
      var checksum = 0L
      var i = 0
      while (i < size0) {
        stable.recordAlert(keys(i), scores(i))
        checksum = mix(checksum, seqs(i).toLong)
        checksum = mix(checksum, keys(i).toLong)
        checksum = mix(checksum, scores(i))
        i += 1
      }
      checksum
    }

    def size: Int = size0
  }

  private final class Packet(
      val seq: Int,
      val key: Int,
      val payload: Int,
      val next: Packet
  )

  private final class Feature(
      val seq: Int,
      val key: Int,
      val value: Int,
      val next: Feature
  )

  private final class Decision(
      val seq: Int,
      val key: Int,
      val score: Long,
      val next: Decision
  )

  private final class Alert(
      val seq: Int,
      val key: Int,
      val score: Long,
      val next: Alert
  )

  final case class RunResult(
      checksum: Long,
      outputCount: Long,
      dropped: Long
  )

  final case class LatencyResult(
      checksum: Long,
      outputCount: Long,
      dropped: Long,
      p50Ns: Long,
      p95Ns: Long,
      p99Ns: Long,
      p999Ns: Long,
      maxNs: Long,
      deadlineMisses: Int
  )

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftRegionOpNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L)

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
            rawSizeToLong(RiftAllocator.Impl.statsRegionOpNanos())
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
          delta(end.riftRegionOpNanos, start.riftRegionOpNanos)
      )
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def mix(seed: Long, value: Long): Long = {
    var x = seed ^ (value + 0x9e3779b97f4a7c15L + (seed << 6) + (seed >>> 2))
    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    x *= 0xc4ceb9fe1a85ec53L
    x ^ (x >>> 33)
  }

  private def percentile(sorted: Array[Long], thousandths: Int): Long = {
    val n = sorted.length
    val idx =
      math.min(n - 1, ((n.toLong * thousandths.toLong + 999L) / 1000L - 1L).toInt)
    sorted(idx)
  }

  private def medianDouble(values: Array[Double]): Double = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    val mid = copy.length / 2
    if ((copy.length & 1) == 1) copy(mid)
    else (copy(mid - 1) + copy(mid)) / 2.0
  }

  private def medianLong(values: Array[Long]): Long = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
  }

  private def medianInt(values: Array[Int]): Int = {
    val copy = values.clone()
    scala.util.Sorting.quickSort(copy)
    copy(copy.length / 2)
  }

  private def processHeapPeriod(
      stable: StableState,
      capsule: AlertCapsule,
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  ): RunResult = {
    capsule.clear()
    var packets: Packet = null
    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        val key = stable.key(seed)
        stable.recordEvent(key)
        packets = new Packet(seq, key, mix(seed + 31), packets)
        fragment += 1
      }
      i += 1
    }

    var features: Feature = null
    var packet = packets
    while (packet != null) {
      val value = mix(packet.payload + stable.laneBias(packet.key))
      features = new Feature(packet.seq, packet.key, value, features)
      packet = packet.next
    }

    var decisions: Decision = null
    var feature = features
    while (feature != null) {
      val score = stable.classify(feature.key, feature.value, feature.seq)
      decisions = new Decision(feature.seq, feature.key, score, decisions)
      feature = feature.next
    }

    var alerts: Alert = null
    var decision = decisions
    while (decision != null) {
      if (((decision.score ^ (decision.score >>> 11)) & 7L) == 0L)
        alerts = new Alert(decision.seq, decision.key, decision.score, alerts)
      decision = decision.next
    }

    var alert = alerts
    while (alert != null) {
      capsule.add(alert.seq, alert.key, alert.score)
      alert = alert.next
    }
    val checksum = capsule.drainInto(stable)
    val anchor =
      (if (packets == null) 0L else packets.payload.toLong) ^
        (if (features == null) 0L else features.value.toLong) ^
        (if (decisions == null) 0L else decisions.score) ^
        (if (alerts == null) 0L else alerts.score)
    RunResult(mix(checksum, anchor), capsule.size.toLong, capsule.dropped.toLong)
  }

  private def processSafeZonePeriod(
      stable: StableState,
      capsule: AlertCapsule,
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  ): RunResult =
    SafeZone { sz ?=>
      final class SZPacket(
          val seq: Int,
          val key: Int,
          val payload: Int,
          val next: SZPacket^{sz}
      )
      final class SZFeature(
          val seq: Int,
          val key: Int,
          val value: Int,
          val next: SZFeature^{sz}
      )
      final class SZDecision(
          val seq: Int,
          val key: Int,
          val score: Long,
          val next: SZDecision^{sz}
      )
      final class SZAlert(
          val seq: Int,
          val key: Int,
          val score: Long,
          val next: SZAlert^{sz}
      )

      capsule.clear()
      var packets: SZPacket^{sz} = null
      var i = 0
      while (i < count) {
        val seq = startSeq + i
        var fragment = 0
        while (fragment < objectsPerEvent) {
          val seed = mix(seq * 1009 + fragment * 9176)
          val key = stable.key(seed)
          stable.recordEvent(key)
          packets = SafeZone.alloc(new SZPacket(seq, key, mix(seed + 31), packets))
          fragment += 1
        }
        i += 1
      }

      var features: SZFeature^{sz} = null
      var packet = packets
      while (packet != null) {
        val value = mix(packet.payload + stable.laneBias(packet.key))
        features = SafeZone.alloc(new SZFeature(packet.seq, packet.key, value, features))
        packet = packet.next
      }

      var decisions: SZDecision^{sz} = null
      var feature = features
      while (feature != null) {
        val score = stable.classify(feature.key, feature.value, feature.seq)
        decisions =
          SafeZone.alloc(new SZDecision(feature.seq, feature.key, score, decisions))
        feature = feature.next
      }

      var alerts: SZAlert^{sz} = null
      var decision = decisions
      while (decision != null) {
        if (((decision.score ^ (decision.score >>> 11)) & 7L) == 0L)
          alerts = SafeZone.alloc(new SZAlert(decision.seq, decision.key, decision.score, alerts))
        decision = decision.next
      }

      var alert = alerts
      while (alert != null) {
        capsule.add(alert.seq, alert.key, alert.score)
        alert = alert.next
      }
      val checksum = capsule.drainInto(stable)
      val anchor =
        (if (packets == null) 0L else packets.payload.toLong) ^
          (if (features == null) 0L else features.value.toLong) ^
          (if (decisions == null) 0L else decisions.score) ^
          (if (alerts == null) 0L else alerts.score)
      RunResult(mix(checksum, anchor), capsule.size.toLong, capsule.dropped.toLong)
    }

  private def processCheckedPeriod(
      stable: StableState,
      capsule: AlertCapsule,
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  )(using region: RiftRegion.OpenStreamingRegion^): RunResult = {
    final class CheckedPacket(
        val seq: Int,
        val key: Int,
        val payload: Int,
        val next: CheckedPacket^{region}
    )
    final class CheckedFeature(
        val seq: Int,
        val key: Int,
        val value: Int,
        val next: CheckedFeature^{region}
    )
    final class CheckedDecision(
        val seq: Int,
        val key: Int,
        val score: Long,
        val next: CheckedDecision^{region}
    )
    final class CheckedAlert(
        val seq: Int,
        val key: Int,
        val score: Long,
        val next: CheckedAlert^{region}
    )

    capsule.clear()
    var packets: CheckedPacket^{region} = null
    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        val key = stable.key(seed)
        stable.recordEvent(key)
        packets = RiftRegion.allocOpen(
          new CheckedPacket(seq, key, mix(seed + 31), packets)
        )
        fragment += 1
      }
      i += 1
    }

    var features: CheckedFeature^{region} = null
    var packet = packets
    while (packet != null) {
      val value = mix(packet.payload + stable.laneBias(packet.key))
      features = RiftRegion.allocOpen(
        new CheckedFeature(packet.seq, packet.key, value, features)
      )
      packet = packet.next
    }

    var decisions: CheckedDecision^{region} = null
    var feature = features
    while (feature != null) {
      val score = stable.classify(feature.key, feature.value, feature.seq)
      decisions = RiftRegion.allocOpen(
        new CheckedDecision(feature.seq, feature.key, score, decisions)
      )
      feature = feature.next
    }

    var alerts: CheckedAlert^{region} = null
    var decision = decisions
    while (decision != null) {
      if (((decision.score ^ (decision.score >>> 11)) & 7L) == 0L)
        alerts = RiftRegion.allocOpen(
          new CheckedAlert(decision.seq, decision.key, decision.score, alerts)
        )
      decision = decision.next
    }

    var alert = alerts
    while (alert != null) {
      capsule.add(alert.seq, alert.key, alert.score)
      alert = alert.next
    }
    val checksum = capsule.drainInto(stable)
    val anchor =
      (if (packets == null) 0L else packets.payload.toLong) ^
        (if (features == null) 0L else features.value.toLong) ^
        (if (decisions == null) 0L else decisions.score) ^
        (if (alerts == null) 0L else alerts.score)
    RunResult(mix(checksum, anchor), capsule.size.toLong, capsule.dropped.toLong)
  }

  private def runThroughputOnce(mode: String): RunResult = {
    val cfg = StreamFlexDesignConfig
    val stable = new StableState(cfg.stableKeys)
    val capsule = new AlertCapsule(cfg.capsuleCapacity)
    var checksum = 0L
    var outputs = 0L
    var drops = 0L
    var start = 0
    def consume(result: RunResult): Unit = {
      checksum = mix(checksum, result.checksum)
      outputs += result.outputCount
      drops += result.dropped
    }

    mode match {
      case "gc-heap" | "heap-same-shape" =>
        while (start < cfg.events) {
          val count = math.min(cfg.periodEvents, cfg.events - start)
          consume(processHeapPeriod(stable, capsule, start, count, cfg.objectsPerEvent))
          start += count
        }
      case "region-scoped-rooted" =>
        while (start < cfg.events) {
          val count = math.min(cfg.periodEvents, cfg.events - start)
          consume(processSafeZonePeriod(stable, capsule, start, count, cfg.objectsPerEvent))
          start += count
        }
      case "checked-epoch-scoped" =>
        RiftRegion.streamingSafeZone { stream ?=>
          while (start < cfg.events) {
            val count = math.min(cfg.periodEvents, cfg.events - start)
            val result = RiftRegion.epoch {
              processCheckedPeriod(stable, capsule, start, count, cfg.objectsPerEvent)
            }
            consume(result)
            start += count
          }
        }
      case "checked-epoch-stream" =>
        RiftRegion.streaming { stream ?=>
          while (start < cfg.events) {
            val count = math.min(cfg.periodEvents, cfg.events - start)
            val result = RiftRegion.epoch {
              processCheckedPeriod(stable, capsule, start, count, cfg.objectsPerEvent)
            }
            consume(result)
            start += count
          }
        }
      case other =>
        throw new IllegalArgumentException(s"unknown StreamFlex design mode '$other'")
    }
    checksumSink = checksum
    RunResult(mix(checksum, stable.checksum), outputs, drops)
  }

  private def runLatencyOnce(
      mode: String,
      events: Int,
      objectsPerEvent: Int
  ): LatencyResult = {
    val cfg = StreamFlexDesignConfig
    val stable = new StableState(cfg.stableKeys)
    val capsule = new AlertCapsule(cfg.capsuleCapacity)
    val samples = new Array[Long](events)
    var checksum = 0L
    var outputs = 0L
    var drops = 0L
    var event = 0
    def consume(result: RunResult): Unit = {
      checksum = mix(checksum, result.checksum)
      outputs += result.outputCount
      drops += result.dropped
    }
    def timed(body: => RunResult): Unit = {
      val start = System.nanoTime()
      val result = body
      val end = System.nanoTime()
      samples(event) = end - start
      consume(result)
    }

    mode match {
      case "gc-heap" | "heap-same-shape" =>
        while (event < events) {
          timed(processHeapPeriod(stable, capsule, event, 1, objectsPerEvent))
          event += 1
        }
      case "region-scoped-rooted" =>
        while (event < events) {
          timed(processSafeZonePeriod(stable, capsule, event, 1, objectsPerEvent))
          event += 1
        }
      case "checked-epoch-scoped" =>
        RiftRegion.streamingSafeZone { stream ?=>
          while (event < events) {
            timed {
              RiftRegion.epoch {
                processCheckedPeriod(stable, capsule, event, 1, objectsPerEvent)
              }
            }
            event += 1
          }
        }
      case "checked-epoch-stream" =>
        RiftRegion.streaming { stream ?=>
          while (event < events) {
            timed {
              RiftRegion.epoch {
                processCheckedPeriod(stable, capsule, event, 1, objectsPerEvent)
              }
            }
            event += 1
          }
        }
      case other =>
        throw new IllegalArgumentException(s"unknown StreamFlex design mode '$other'")
    }

    val sorted = samples.clone()
    scala.util.Sorting.quickSort(sorted)
    var misses = 0
    var i = 0
    while (i < samples.length) {
      if (samples(i) > cfg.periodNs) misses += 1
      i += 1
    }
    val fullChecksum = mix(checksum, stable.checksum)
    checksumSink = fullChecksum
    LatencyResult(
      checksum = fullChecksum,
      outputCount = outputs,
      dropped = drops,
      p50Ns = percentile(sorted, 500),
      p95Ns = percentile(sorted, 950),
      p99Ns = percentile(sorted, 990),
      p999Ns = percentile(sorted, 999),
      maxNs = sorted(sorted.length - 1),
      deadlineMisses = misses
    )
  }

  private def usesRift(mode: String): Boolean =
    mode == "checked-epoch-scoped" || mode == "checked-epoch-stream"

  private def validateAgainstHeap(result: RunResult, expected: RunResult): Unit =
    if (result != expected)
      throw new IllegalStateException(
        s"checksum/output mismatch expected=$expected actual=$result"
      )

  private def validateAgainstHeap(result: LatencyResult, expected: LatencyResult): Unit =
    if (result.checksum != expected.checksum ||
        result.outputCount != expected.outputCount ||
        result.dropped != expected.dropped)
      throw new IllegalStateException(
        s"latency checksum/output mismatch expected=$expected actual=$result"
      )

  def runThroughputBenchmark(mode: String): Unit = {
    val cfg = StreamFlexDesignConfig
    val includeRift = usesRift(mode)
    if (cfg.finalClean) {
      var result = runThroughputOnce(mode)
      val expected = result
      var run = 1
      while (run < cfg.benchmarkRuns) {
        result = runThroughputOnce(mode)
        validateAgainstHeap(result, expected)
        run += 1
      }
      println(
        s"RESULT name=streamflex-design-throughput-$mode measurement_level=L1 " +
          s"final_clean=1 workload=throughput mode=$mode runs=${cfg.benchmarkRuns} " +
          s"events=${cfg.events} period_events=${cfg.periodEvents} " +
          s"objects_per_event=${cfg.objectsPerEvent} checksum=${expected.checksum} " +
          s"output_count=${expected.outputCount} dropped=${expected.dropped}"
      )
      return
    }

    val expected = runThroughputOnce("gc-heap")
    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      validateAgainstHeap(runThroughputOnce(mode), expected)
      warmup += 1
    }
    if (includeRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(s"Running streamflex-design-throughput-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(includeRift)
      val start = System.nanoTime()
      val result = runThroughputOnce(mode)
      val end = System.nanoTime()
      val runtime = RuntimeSample.since(startRuntime, RuntimeSample.capture(includeRift))
      validateAgainstHeap(result, expected)
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
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f gc_collections=${runtime.gcCollections}%d"
      )
      run += 1
    }
    val medianMs = medianDouble(elapsedMs)
    val recordsPerSec = cfg.events.toDouble / (medianMs / 1000.0)
    println(
      f"RESULT name=streamflex-design-throughput-$mode workload=throughput mode=$mode " +
        f"events=${cfg.events}%d period_events=${cfg.periodEvents}%d " +
        f"objects_per_event=${cfg.objectsPerEvent}%d median_ms=$medianMs%.3f " +
        f"records_per_sec=$recordsPerSec%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"max_gc_ms=${gcNanos.max / 1000000.0}%.3f " +
        f"runs_with_gc=${gcNanos.count(_ > 0L)}%d " +
        f"max_gc_collections=${gcCollections.max}%d " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"checksum=${expected.checksum}%d output_count=${expected.outputCount}%d " +
        f"dropped=${expected.dropped}%d"
    )
  }

  def runLatencyBenchmark(mode: String, pressure: Boolean): Unit = {
    val cfg = StreamFlexDesignConfig
    val includeRift = usesRift(mode)
    val label = if (pressure) "pressure-latency" else "latency"
    val events = if (pressure) cfg.pressureLatencyEvents else cfg.latencyEvents
    val objects =
      if (pressure) cfg.pressureObjectsPerEvent else cfg.latencyObjectsPerEvent
    if (cfg.finalClean) {
      var result = runLatencyOnce(mode, events, objects)
      val expected = result
      var run = 1
      while (run < cfg.benchmarkRuns) {
        result = runLatencyOnce(mode, events, objects)
        validateAgainstHeap(result, expected)
        run += 1
      }
      println(
        s"RESULT name=streamflex-design-$label-$mode measurement_level=L1 " +
          s"final_clean=1 workload=$label mode=$mode runs=${cfg.benchmarkRuns} " +
          s"events=$events objects_per_event=$objects period_ns=${cfg.periodNs} " +
          s"p50_ns=${expected.p50Ns} p95_ns=${expected.p95Ns} " +
          s"p99_ns=${expected.p99Ns} p999_ns=${expected.p999Ns} " +
          s"max_ns=${expected.maxNs} deadline_misses=${expected.deadlineMisses} " +
          s"checksum=${expected.checksum} output_count=${expected.outputCount} " +
          s"dropped=${expected.dropped}"
      )
      return
    }

    val expected = runLatencyOnce("gc-heap", events, objects)
    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      validateAgainstHeap(runLatencyOnce(mode, events, objects), expected)
      warmup += 1
    }
    if (includeRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val p50 = new Array[Long](cfg.benchmarkRuns)
    val p95 = new Array[Long](cfg.benchmarkRuns)
    val p99 = new Array[Long](cfg.benchmarkRuns)
    val p999 = new Array[Long](cfg.benchmarkRuns)
    val max = new Array[Long](cfg.benchmarkRuns)
    val misses = new Array[Int](cfg.benchmarkRuns)

    println(s"Running streamflex-design-$label-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(includeRift)
      val start = System.nanoTime()
      val result = runLatencyOnce(mode, events, objects)
      val end = System.nanoTime()
      val runtime = RuntimeSample.since(startRuntime, RuntimeSample.capture(includeRift))
      validateAgainstHeap(result, expected)
      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      gcCollections(run) = runtime.gcCollections
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      p50(run) = result.p50Ns
      p95(run) = result.p95Ns
      p99(run) = result.p99Ns
      p999(run) = result.p999Ns
      max(run) = result.maxNs
      misses(run) = result.deadlineMisses
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"p95_us=${result.p95Ns / 1000.0}%.3f " +
          f"max_us=${result.maxNs / 1000.0}%.3f misses=${result.deadlineMisses}%d"
      )
      run += 1
    }
    val medianMs = medianDouble(elapsedMs)
    val recordsPerSec = events.toDouble / (medianMs / 1000.0)
    println(
      f"RESULT name=streamflex-design-$label-$mode workload=$label mode=$mode " +
        f"events=$events%d objects_per_event=$objects%d period_ns=${cfg.periodNs}%d " +
        f"median_ms=$medianMs%.3f records_per_sec=$recordsPerSec%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"max_gc_ms=${gcNanos.max / 1000000.0}%.3f " +
        f"runs_with_gc=${gcNanos.count(_ > 0L)}%d " +
        f"max_gc_collections=${gcCollections.max}%d " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_p50_ns=${medianLong(p50)}%d " +
        f"median_p95_ns=${medianLong(p95)}%d " +
        f"median_p99_ns=${medianLong(p99)}%d " +
        f"median_p999_ns=${medianLong(p999)}%d " +
        f"median_max_ns=${medianLong(max)}%d " +
        f"median_deadline_misses=${medianInt(misses)}%d " +
        f"checksum=${expected.checksum}%d output_count=${expected.outputCount}%d " +
        f"dropped=${expected.dropped}%d"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "gc-heap" | "heap-same-shape" | "region-scoped-rooted" |
          "checked-epoch-scoped" | "checked-epoch-stream" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamFlex design mode '$other'"
        )
    }

  def validateWorkload(workload: String): Unit =
    workload match {
      case "throughput" | "latency" | "pressure-latency" | "all" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamFlex design workload '$other'"
        )
    }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = StreamFlexDesignConfig
    println(
      s"CONFIG mode=$mode workload=$workload runs=${cfg.benchmarkRuns} " +
        s"warmups=${cfg.warmupRuns} events=${cfg.events} " +
        s"period_events=${cfg.periodEvents} objects_per_event=${cfg.objectsPerEvent} " +
        s"latency_events=${cfg.latencyEvents} " +
        s"latency_objects_per_event=${cfg.latencyObjectsPerEvent} " +
        s"pressure_latency_events=${cfg.pressureLatencyEvents} " +
        s"pressure_objects_per_event=${cfg.pressureObjectsPerEvent} " +
        s"capsule_capacity=${cfg.capsuleCapacity} stable_keys=${cfg.stableKeys} " +
        s"period_ns=${cfg.periodNs} final_clean=${cfg.finalClean}"
    )
  }
}

@main def StreamFlexDesignMatrix(
    mode: String,
    workloadArg: String = StreamFlexDesignConfig.workload
): Unit = {
  val workload = workloadArg.toLowerCase
  StreamFlexDesignMatrixHelpers.validateMode(mode)
  StreamFlexDesignMatrixHelpers.validateWorkload(workload)
  StreamFlexDesignMatrixHelpers.printConfig(mode, workload)
  workload match {
    case "all" =>
      StreamFlexDesignMatrixHelpers.runThroughputBenchmark(mode)
      StreamFlexDesignMatrixHelpers.runLatencyBenchmark(mode, pressure = false)
      StreamFlexDesignMatrixHelpers.runLatencyBenchmark(mode, pressure = true)
    case "throughput" =>
      StreamFlexDesignMatrixHelpers.runThroughputBenchmark(mode)
    case "latency" =>
      StreamFlexDesignMatrixHelpers.runLatencyBenchmark(mode, pressure = false)
    case "pressure-latency" =>
      StreamFlexDesignMatrixHelpers.runLatencyBenchmark(mode, pressure = true)
  }
}
