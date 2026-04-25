import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

object StreamFlexRegionConfig {
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

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envLong(name: String, default: Long): Long =
    sys.env.get(name).flatMap(parsePositiveLong).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val events: Int = envInt("STREAMFLEX_EVENTS", 200000)
  val batchSize: Int = envInt("STREAMFLEX_BATCH_SIZE", 256)
  val objectsPerEvent: Int = envInt("STREAMFLEX_OBJECTS_PER_EVENT", 4)
  val latencyEvents: Int = envInt("STREAMFLEX_LATENCY_EVENTS", 10000)
  val latencyObjectsPerEvent: Int =
    envInt("STREAMFLEX_LATENCY_OBJECTS_PER_EVENT", 16)
  val periodNs: Long = envLong("STREAMFLEX_PERIOD_NS", 80000L)
  val benchmarkRuns: Int = envInt("STREAMFLEX_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("STREAMFLEX_WARMUPS", 1)
}

object StreamFlexRegionMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class Packet(
      val seq: Int,
      val key: Int,
      val payload: Int,
      val next: Packet
  )

  private final class Decoded(
      val seq: Int,
      val lane: Int,
      val magnitude: Int,
      val next: Decoded
  )

  private final class Classified(
      val seq: Int,
      val lane: Int,
      val score: Long,
      val next: Classified
  )

  private final class Alert(
      val seq: Int,
      val lane: Int,
      val score: Long,
      val next: Alert
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

  final case class LatencyRun(
      checksum: Long,
      p50Ns: Long,
      p99Ns: Long,
      p999Ns: Long,
      maxNs: Long,
      deadlineMisses: Int
  )

  private final class ModeState(val mode: String) {
    val usesRift: Boolean = mode == "rift-hp" || mode == "rift-streaming"
    private val streaming: Boolean = mode == "rift-streaming"
    private val kind: Int =
      if (streaming) RiftRegion.Streaming else RiftRegion.HPZone
    private var streamRegion: RiftRegion = null

    def beginRegion(): RiftRegion =
      if (!usesRift) null
      else if (streaming) {
        if (streamRegion == null) streamRegion = RiftRegion.open(kind)
        else streamRegion.reset()
        streamRegion
      } else {
        RiftRegion.open(kind)
      }

    def endRegion(region: RiftRegion): Unit =
      if (usesRift && !streaming) region.close()

    def finish(): Unit =
      if (streamRegion != null) {
        streamRegion.close()
        streamRegion = null
      }

    def allocPacket(
        region: RiftRegion,
        seq: Int,
        key: Int,
        payload: Int,
        next: Packet
    ): Packet =
      if (usesRift) region.alloc(new Packet(seq, key, payload, next))
      else new Packet(seq, key, payload, next)

    def allocDecoded(
        region: RiftRegion,
        seq: Int,
        lane: Int,
        magnitude: Int,
        next: Decoded
    ): Decoded =
      if (usesRift) region.alloc(new Decoded(seq, lane, magnitude, next))
      else new Decoded(seq, lane, magnitude, next)

    def allocClassified(
        region: RiftRegion,
        seq: Int,
        lane: Int,
        score: Long,
        next: Classified
    ): Classified =
      if (usesRift) region.alloc(new Classified(seq, lane, score, next))
      else new Classified(seq, lane, score, next)

    def allocAlert(
        region: RiftRegion,
        seq: Int,
        lane: Int,
        score: Long,
        next: Alert
    ): Alert =
      if (usesRift) region.alloc(new Alert(seq, lane, score, next))
      else new Alert(seq, lane, score, next)
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def percentile(sorted: Array[Long], thousandths: Int): Long = {
    val n = sorted.length
    val idx = math.min(n - 1, ((n.toLong * thousandths.toLong + 999L) / 1000L - 1L).toInt)
    sorted(idx)
  }

  private def summarizeLatency(samples: Array[Long], checksum: Long): LatencyRun = {
    val sorted = samples.clone()
    scala.util.Sorting.quickSort(sorted)
    var misses = 0
    var i = 0
    while (i < samples.length) {
      if (samples(i) > StreamFlexRegionConfig.periodNs) misses += 1
      i += 1
    }
    LatencyRun(
      checksum = checksum,
      p50Ns = percentile(sorted, 500),
      p99Ns = percentile(sorted, 990),
      p999Ns = percentile(sorted, 999),
      maxNs = sorted(sorted.length - 1),
      deadlineMisses = misses
    )
  }

  private def processBatch(
      mode: ModeState,
      region: RiftRegion,
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  ): Long = {
    var packets: Packet = null
    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        packets = mode.allocPacket(
          region,
          seq,
          seed & 0xff,
          mix(seed + 31),
          packets
        )
        fragment += 1
      }
      i += 1
    }

    var decoded: Decoded = null
    var packet = packets
    while (packet != null) {
      val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
      val magnitude = mix(packet.payload + lane)
      decoded = mode.allocDecoded(region, packet.seq, lane, magnitude, decoded)
      packet = packet.next
    }

    var classified: Classified = null
    var dec = decoded
    while (dec != null) {
      val score =
        (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
      classified =
        mode.allocClassified(region, dec.seq, dec.lane, score, classified)
      dec = dec.next
    }

    var alerts: Alert = null
    var cls = classified
    while (cls != null) {
      if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L)
        alerts = mode.allocAlert(region, cls.seq, cls.lane, cls.score, alerts)
      cls = cls.next
    }

    var checksum = 0L
    var alert = alerts
    while (alert != null) {
      checksum += alert.score ^ alert.seq.toLong ^ alert.lane.toLong
      alert = alert.next
    }
    checksum
  }

  def runHeapOrRiftThroughput(modeName: String): Long = {
    val cfg = StreamFlexRegionConfig
    val mode = new ModeState(modeName)
    var checksum = 0L
    var processed = 0
    try {
      while (processed < cfg.events) {
        val count = math.min(cfg.batchSize, cfg.events - processed)
        val region = mode.beginRegion()
        checksum += processBatch(
          mode,
          region,
          processed,
          count,
          cfg.objectsPerEvent
        )
        mode.endRegion(region)
        processed += count
      }
    } finally mode.finish()
    checksumSink = checksum
    checksum
  }

  def runHeapOrRiftLatency(modeName: String): LatencyRun = {
    val cfg = StreamFlexRegionConfig
    val mode = new ModeState(modeName)
    val samples = new Array[Long](cfg.latencyEvents)
    var checksum = 0L
    var i = 0
    try {
      while (i < cfg.latencyEvents) {
        val start = System.nanoTime()
        val region = mode.beginRegion()
        checksum += processBatch(
          mode,
          region,
          i,
          1,
          cfg.latencyObjectsPerEvent
        )
        mode.endRegion(region)
        val end = System.nanoTime()
        samples(i) = end - start
        i += 1
      }
    } finally mode.finish()
    checksumSink = checksum
    summarizeLatency(samples, checksum)
  }

  def runSafeZoneThroughput(): Long = {
    val cfg = StreamFlexRegionConfig
    var checksum = 0L
    var processed = 0
    while (processed < cfg.events) {
      val count = math.min(cfg.batchSize, cfg.events - processed)
      val batchStart = processed
      checksum += SafeZone { sz ?=>
        final class SZPacket(
            val seq: Int,
            val key: Int,
            val payload: Int,
            val next: SZPacket^{sz}
        )
        final class SZDecoded(
            val seq: Int,
            val lane: Int,
            val magnitude: Int,
            val next: SZDecoded^{sz}
        )
        final class SZClassified(
            val seq: Int,
            val lane: Int,
            val score: Long,
            val next: SZClassified^{sz}
        )
        final class SZAlert(
            val seq: Int,
            val lane: Int,
            val score: Long,
            val next: SZAlert^{sz}
        )

        var packets: SZPacket^{sz} = null
        var i = 0
        while (i < count) {
          val seq = batchStart + i
          var fragment = 0
          while (fragment < cfg.objectsPerEvent) {
            val seed = mix(seq * 1009 + fragment * 9176)
            packets = SafeZoneAllocator.allocate(
              sz,
              new SZPacket(seq, seed & 0xff, mix(seed + 31), packets)
            )
            fragment += 1
          }
          i += 1
        }

        var decoded: SZDecoded^{sz} = null
        var packet = packets
        while (packet != null) {
          val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
          val magnitude = mix(packet.payload + lane)
          decoded =
            SafeZoneAllocator.allocate(
              sz,
              new SZDecoded(packet.seq, lane, magnitude, decoded)
            )
          packet = packet.next
        }

        var classified: SZClassified^{sz} = null
        var dec = decoded
        while (dec != null) {
          val score =
            (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
          classified = SafeZoneAllocator.allocate(
            sz,
            new SZClassified(dec.seq, dec.lane, score, classified)
          )
          dec = dec.next
        }

        var alerts: SZAlert^{sz} = null
        var cls = classified
        while (cls != null) {
          if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L)
            alerts =
              SafeZoneAllocator.allocate(
                sz,
                new SZAlert(cls.seq, cls.lane, cls.score, alerts)
              )
          cls = cls.next
        }

        var total = 0L
        var alert = alerts
        while (alert != null) {
          total += alert.score ^ alert.seq.toLong ^ alert.lane.toLong
          alert = alert.next
        }
        total
      }
      processed += count
    }
    checksumSink = checksum
    checksum
  }

  def runSafeZoneLatency(): LatencyRun = {
    val cfg = StreamFlexRegionConfig
    val samples = new Array[Long](cfg.latencyEvents)
    var checksum = 0L
    var event = 0
    while (event < cfg.latencyEvents) {
      val start = System.nanoTime()
      checksum += SafeZone { sz ?=>
        final class SZPacket(
            val seq: Int,
            val key: Int,
            val payload: Int,
            val next: SZPacket^{sz}
        )
        final class SZDecoded(
            val seq: Int,
            val lane: Int,
            val magnitude: Int,
            val next: SZDecoded^{sz}
        )
        final class SZClassified(
            val seq: Int,
            val lane: Int,
            val score: Long,
            val next: SZClassified^{sz}
        )
        final class SZAlert(
            val seq: Int,
            val lane: Int,
            val score: Long,
            val next: SZAlert^{sz}
        )

        var packets: SZPacket^{sz} = null
        var fragment = 0
        while (fragment < cfg.latencyObjectsPerEvent) {
          val seed = mix(event * 1009 + fragment * 9176)
          packets = SafeZoneAllocator.allocate(
            sz,
            new SZPacket(event, seed & 0xff, mix(seed + 31), packets)
          )
          fragment += 1
        }

        var decoded: SZDecoded^{sz} = null
        var packet = packets
        while (packet != null) {
          val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
          val magnitude = mix(packet.payload + lane)
          decoded =
            SafeZoneAllocator.allocate(
              sz,
              new SZDecoded(packet.seq, lane, magnitude, decoded)
            )
          packet = packet.next
        }

        var classified: SZClassified^{sz} = null
        var dec = decoded
        while (dec != null) {
          val score =
            (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
          classified = SafeZoneAllocator.allocate(
            sz,
            new SZClassified(dec.seq, dec.lane, score, classified)
          )
          dec = dec.next
        }

        var alerts: SZAlert^{sz} = null
        var cls = classified
        while (cls != null) {
          if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L)
            alerts =
              SafeZoneAllocator.allocate(
                sz,
                new SZAlert(cls.seq, cls.lane, cls.score, alerts)
              )
          cls = cls.next
        }

        var total = 0L
        var alert = alerts
        while (alert != null) {
          total += alert.score ^ alert.seq.toLong ^ alert.lane.toLong
          alert = alert.next
        }
        total
      }
      val end = System.nanoTime()
      samples(event) = end - start
      event += 1
    }
    checksumSink = checksum
    summarizeLatency(samples, checksum)
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

  private def medianInt(values: Array[Int]): Int = {
    val sorted = values.clone()
    scala.util.Sorting.quickSort(sorted)
    if ((sorted.length & 1) == 1) sorted(sorted.length / 2)
    else (sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2
  }

  private def runThroughput(mode: String): Long =
    if (mode == "safezone") runSafeZoneThroughput()
    else runHeapOrRiftThroughput(mode)

  private def runLatency(mode: String): LatencyRun =
    if (mode == "safezone") runSafeZoneLatency()
    else runHeapOrRiftLatency(mode)

  def runThroughputBenchmark(mode: String): Unit = {
    val cfg = StreamFlexRegionConfig
    val usesRift = mode == "rift-hp" || mode == "rift-streaming"
    val expected = runThroughput("heap")

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runThroughput(mode)
      if (checksum != expected)
        throw new IllegalStateException(
          s"throughput warmup checksum mismatch mode=$mode expected=$expected actual=$checksum"
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

    println(s"Running streamflex-throughput-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runThroughput(mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (checksum != expected)
        throw new IllegalStateException(
          s"throughput checksum mismatch mode=$mode expected=$expected actual=$checksum"
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
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d"
      )
      run += 1
    }

    println(
      f"RESULT name=streamflex-throughput-$mode " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"checksum=$expected%d"
    )
  }

  def runLatencyBenchmark(mode: String): Unit = {
    val cfg = StreamFlexRegionConfig
    val usesRift = mode == "rift-hp" || mode == "rift-streaming"
    val expected = runLatency("heap").checksum

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val result = runLatency(mode)
      if (result.checksum != expected)
        throw new IllegalStateException(
          s"latency warmup checksum mismatch mode=$mode expected=$expected actual=${result.checksum}"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val p50 = new Array[Long](cfg.benchmarkRuns)
    val p99 = new Array[Long](cfg.benchmarkRuns)
    val p999 = new Array[Long](cfg.benchmarkRuns)
    val max = new Array[Long](cfg.benchmarkRuns)
    val misses = new Array[Int](cfg.benchmarkRuns)

    println(s"Running streamflex-latency-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val result = runLatency(mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (result.checksum != expected)
        throw new IllegalStateException(
          s"latency checksum mismatch mode=$mode expected=$expected actual=${result.checksum}"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      p50(run) = result.p50Ns
      p99(run) = result.p99Ns
      p999(run) = result.p999Ns
      max(run) = result.maxNs
      misses(run) = result.deadlineMisses
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"p99_us=${result.p99Ns / 1000.0}%.3f " +
          f"p999_us=${result.p999Ns / 1000.0}%.3f " +
          f"max_us=${result.maxNs / 1000.0}%.3f " +
          f"deadline_misses=${result.deadlineMisses}%d"
      )
      run += 1
    }

    println(
      f"RESULT name=streamflex-latency-$mode " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_p50_ns=${medianLong(p50)}%d " +
        f"median_p99_ns=${medianLong(p99)}%d " +
        f"median_p999_ns=${medianLong(p999)}%d " +
        f"median_max_ns=${medianLong(max)}%d " +
        f"median_deadline_misses=${medianInt(misses)}%d " +
        f"period_ns=${cfg.periodNs}%d " +
        f"checksum=$expected%d"
    )
  }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = StreamFlexRegionConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    println(
      s"CONFIG mode=$mode workload=$workload runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} events=${cfg.events} batch_size=${cfg.batchSize} objects_per_event=${cfg.objectsPerEvent} latency_events=${cfg.latencyEvents} latency_objects_per_event=${cfg.latencyObjectsPerEvent} period_ns=${cfg.periodNs} safezone_roots_mode=$rootsMode"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamFlex mode '$other'; expected heap, safezone, rift-hp, or rift-streaming"
        )
    }
}

@main def StreamFlexRegionMatrix(
    mode: String = "heap",
    workload: String = "all"
): Unit = {
  StreamFlexRegionMatrixHelpers.validateMode(mode)
  StreamFlexRegionMatrixHelpers.printConfig(mode, workload)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    workload match {
      case "all" =>
        StreamFlexRegionMatrixHelpers.runThroughputBenchmark(mode)
        StreamFlexRegionMatrixHelpers.runLatencyBenchmark(mode)
      case "throughput" =>
        StreamFlexRegionMatrixHelpers.runThroughputBenchmark(mode)
      case "latency" =>
        StreamFlexRegionMatrixHelpers.runLatencyBenchmark(mode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamFlex workload '$other'; expected throughput, latency, or all"
        )
    }
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
