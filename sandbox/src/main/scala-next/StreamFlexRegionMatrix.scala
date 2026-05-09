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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val events: Int = envInt("STREAMFLEX_EVENTS", 200000)
  val batchSize: Int = envInt("STREAMFLEX_BATCH_SIZE", 256)
  val objectsPerEvent: Int = envInt("STREAMFLEX_OBJECTS_PER_EVENT", 4)
  val latencyEvents: Int = envInt("STREAMFLEX_LATENCY_EVENTS", 10000)
  val latencyObjectsPerEvent: Int =
    envInt("STREAMFLEX_LATENCY_OBJECTS_PER_EVENT", 16)
  val periodNs: Long = envLong("STREAMFLEX_PERIOD_NS", 80000L)
  val benchmarkRuns: Int = envInt("STREAMFLEX_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("STREAMFLEX_WARMUPS", 1)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
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

  private final class CheckedPacket(
      val seq: Int,
      val key: Int,
      val payload: Int
  ) extends RiftRegion.StreamAppendNode

  private final class CheckedDecoded(
      val seq: Int,
      val lane: Int,
      val magnitude: Int
  ) extends RiftRegion.StreamAppendNode

  private final class CheckedClassified(
      val seq: Int,
      val lane: Int,
      val score: Long
  ) extends RiftRegion.StreamAppendNode

  private final class CheckedAlert(
      val seq: Int,
      val lane: Int,
      val score: Long
  ) extends RiftRegion.StreamAppendNode

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

  private def processCheckedEpochBatch(
      stream: RiftRegion.StreamingRegion^,
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  ): Long = {
    val packets = RiftRegion.epochBuffer[CheckedPacket]()(using stream)
    val decoded = RiftRegion.epochBuffer[CheckedDecoded]()(using stream)
    val classified = RiftRegion.epochBuffer[CheckedClassified]()(using stream)
    val alerts = RiftRegion.epochBuffer[CheckedAlert]()(using stream)

    val packetRegion = RiftRegion.epochBufferRegionFor(stream, packets)
    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        val packet: CheckedPacket^{stream} =
          RiftRegion.alloc(
            new CheckedPacket(seq, seed & 0xff, mix(seed + 31))
          )(using packetRegion)
        RiftRegion.appendEpochBuffer(stream, packets, packet)
        fragment += 1
      }
      i += 1
    }

    val decodedRegion = RiftRegion.epochBufferRegionFor(stream, decoded)
    RiftRegion.closeEpochBufferWithCursor(stream, packets) { (_, cursor) =>
      while (cursor.hasNext) {
        val packet: CheckedPacket^{stream} = cursor.next()
        val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
        val magnitude = mix(packet.payload + lane)
        val value: CheckedDecoded^{stream} =
          RiftRegion.alloc(
            new CheckedDecoded(packet.seq, lane, magnitude)
          )(using decodedRegion)
        RiftRegion.appendEpochBuffer(stream, decoded, value)
      }
    }

    val classifiedRegion = RiftRegion.epochBufferRegionFor(stream, classified)
    RiftRegion.closeEpochBufferWithCursor(stream, decoded) { (_, cursor) =>
      while (cursor.hasNext) {
        val dec: CheckedDecoded^{stream} = cursor.next()
        val score =
          (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
        val value: CheckedClassified^{stream} =
          RiftRegion.alloc(
            new CheckedClassified(dec.seq, dec.lane, score)
          )(using classifiedRegion)
        RiftRegion.appendEpochBuffer(stream, classified, value)
      }
    }

    val alertRegion = RiftRegion.epochBufferRegionFor(stream, alerts)
    RiftRegion.closeEpochBufferWithCursor(stream, classified) { (_, cursor) =>
      while (cursor.hasNext) {
        val cls: CheckedClassified^{stream} = cursor.next()
        if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L) {
          val value: CheckedAlert^{stream} =
            RiftRegion.alloc(
              new CheckedAlert(cls.seq, cls.lane, cls.score)
            )(using alertRegion)
          RiftRegion.appendEpochBuffer(stream, alerts, value)
        }
      }
    }

    var checksum = 0L
    RiftRegion.closeEpochBufferWithCursor(stream, alerts) { (_, cursor) =>
      while (cursor.hasNext) {
        val alert: CheckedAlert^{stream} = cursor.next()
        checksum += alert.score ^ alert.seq.toLong ^ alert.lane.toLong
      }
    }
    checksum
  }

  private def processCheckedDirectEpochBatch(
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  )(using region: RiftRegion.OpenStreamingRegion^): Long = {
    final class DirectPacket(
        val seq: Int,
        val key: Int,
        val payload: Int,
        val next: DirectPacket^{region}
    )

    final class DirectDecoded(
        val seq: Int,
        val lane: Int,
        val magnitude: Int,
        val next: DirectDecoded^{region}
    )

    final class DirectClassified(
        val seq: Int,
        val lane: Int,
        val score: Long,
        val next: DirectClassified^{region}
    )

    final class DirectAlert(
        val seq: Int,
        val lane: Int,
        val score: Long,
        val next: DirectAlert^{region}
    )

    var packets: DirectPacket^{region} = null
    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        packets = RiftRegion.allocOpen(
          new DirectPacket(seq, seed & 0xff, mix(seed + 31), packets)
        )
        fragment += 1
      }
      i += 1
    }

    var decoded: DirectDecoded^{region} = null
    var packet = packets
    while (packet != null) {
      val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
      val magnitude = mix(packet.payload + lane)
      decoded = RiftRegion.allocOpen(
        new DirectDecoded(packet.seq, lane, magnitude, decoded)
      )
      packet = packet.next
    }

    var classified: DirectClassified^{region} = null
    var dec = decoded
    while (dec != null) {
      val score =
        (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
      classified = RiftRegion.allocOpen(
        new DirectClassified(dec.seq, dec.lane, score, classified)
      )
      dec = dec.next
    }

    var alerts: DirectAlert^{region} = null
    var cls = classified
    while (cls != null) {
      if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L)
        alerts = RiftRegion.allocOpen(
          new DirectAlert(cls.seq, cls.lane, cls.score, alerts)
        )
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

  private def processCheckedTransactionBatch(
      stream: RiftRegion.StreamingRegion^,
      tx: RiftRegion.TransactionRegion^{stream},
      packets: RiftRegion.TransactionList[CheckedPacket]^{stream},
      decoded: RiftRegion.TransactionList[CheckedDecoded]^{stream},
      classified: RiftRegion.TransactionList[CheckedClassified]^{stream},
      alerts: RiftRegion.TransactionList[CheckedAlert]^{stream},
      startSeq: Int,
      count: Int,
      objectsPerEvent: Int
  ): Long = {
    val region = RiftRegion.transactionRegionFor(stream, tx)

    var i = 0
    while (i < count) {
      val seq = startSeq + i
      var fragment = 0
      while (fragment < objectsPerEvent) {
        val seed = mix(seq * 1009 + fragment * 9176)
        val packet: CheckedPacket^{stream} =
          RiftRegion.alloc(
            new CheckedPacket(seq, seed & 0xff, mix(seed + 31))
          )(using region)
        RiftRegion.appendTransactionList(stream, packets, packet)
        fragment += 1
      }
      i += 1
    }

    RiftRegion.drainTransactionListWithCursor(stream, packets) { cursor =>
      while (cursor.hasNext) {
        val packet: CheckedPacket^{stream} = cursor.next()
        val lane = (packet.key ^ (packet.payload >>> 7)) & 0x3f
        val magnitude = mix(packet.payload + lane)
        val value: CheckedDecoded^{stream} =
          RiftRegion.alloc(
            new CheckedDecoded(packet.seq, lane, magnitude)
          )(using region)
        RiftRegion.appendTransactionList(stream, decoded, value)
      }
    }

    RiftRegion.drainTransactionListWithCursor(stream, decoded) { cursor =>
      while (cursor.hasNext) {
        val dec: CheckedDecoded^{stream} = cursor.next()
        val score =
          (dec.magnitude.toLong * 31L) ^ (dec.lane.toLong << 11) ^ dec.seq.toLong
        val value: CheckedClassified^{stream} =
          RiftRegion.alloc(
            new CheckedClassified(dec.seq, dec.lane, score)
          )(using region)
        RiftRegion.appendTransactionList(stream, classified, value)
      }
    }

    RiftRegion.drainTransactionListWithCursor(stream, classified) { cursor =>
      while (cursor.hasNext) {
        val cls: CheckedClassified^{stream} = cursor.next()
        if (((cls.score ^ (cls.score >>> 13)) & 7L) == 0L) {
          val value: CheckedAlert^{stream} =
            RiftRegion.alloc(
              new CheckedAlert(cls.seq, cls.lane, cls.score)
            )(using region)
          RiftRegion.appendTransactionList(stream, alerts, value)
        }
      }
    }

    var checksum = 0L
    RiftRegion.drainTransactionListWithCursor(stream, alerts) { cursor =>
      while (cursor.hasNext) {
        val alert: CheckedAlert^{stream} = cursor.next()
        checksum += alert.score ^ alert.seq.toLong ^ alert.lane.toLong
      }
    }
    RiftRegion.closeTransactionRegion(stream, tx)
    checksum
  }

  def runCheckedEpochThroughputBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = StreamFlexRegionConfig
    var checksum = 0L
    var processed = 0
    while (processed < cfg.events) {
      val count = math.min(cfg.batchSize, cfg.events - processed)
      checksum += processCheckedEpochBatch(
        stream,
        processed,
        count,
        cfg.objectsPerEvent
      )
      processed += count
    }
    checksum
  }

  def runCheckedEpochThroughput(): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      runCheckedEpochThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedSafeZoneEpochThroughput(): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedEpochThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedDirectEpochThroughputBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = StreamFlexRegionConfig
    var checksum = 0L
    var processed = 0
    while (processed < cfg.events) {
      val count = math.min(cfg.batchSize, cfg.events - processed)
      checksum += RiftRegion.epoch {
        processCheckedDirectEpochBatch(
          processed,
          count,
          cfg.objectsPerEvent
        )
      }
      processed += count
    }
    checksum
  }

  def runCheckedDirectEpochThroughput(): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      runCheckedDirectEpochThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedSafeZoneDirectEpochThroughput(): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedDirectEpochThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedTransactionThroughputBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = StreamFlexRegionConfig
    val tx = RiftRegion.transactionRegion(4)(using stream)
    val packets = RiftRegion.transactionList[CheckedPacket](stream, tx, 0)
    val decoded = RiftRegion.transactionList[CheckedDecoded](stream, tx, 1)
    val classified = RiftRegion.transactionList[CheckedClassified](stream, tx, 2)
    val alerts = RiftRegion.transactionList[CheckedAlert](stream, tx, 3)
    var checksum = 0L
    var processed = 0
    while (processed < cfg.events) {
      val count = math.min(cfg.batchSize, cfg.events - processed)
      checksum += processCheckedTransactionBatch(
        stream,
        tx,
        packets,
        decoded,
        classified,
        alerts,
        processed,
        count,
        cfg.objectsPerEvent
      )
      processed += count
    }
    checksum
  }

  def runCheckedTransactionThroughput(): Long = {
    val checksum = RiftRegion.streaming { stream ?=>
      runCheckedTransactionThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedSafeZoneTransactionThroughput(): Long = {
    val checksum = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedTransactionThroughputBody()
    }
    checksumSink = checksum
    checksum
  }

  def runCheckedEpochLatencyBody()(using
      stream: RiftRegion.StreamingRegion^
  ): LatencyRun = {
    val cfg = StreamFlexRegionConfig
    val samples = new Array[Long](cfg.latencyEvents)
    var checksum = 0L
    var i = 0
    while (i < cfg.latencyEvents) {
      val start = System.nanoTime()
      checksum += processCheckedEpochBatch(
        stream,
        i,
        1,
        cfg.latencyObjectsPerEvent
      )
      val end = System.nanoTime()
      samples(i) = end - start
      i += 1
    }
    checksumSink = checksum
    summarizeLatency(samples, checksum)
  }

  def runCheckedEpochLatency(): LatencyRun =
    RiftRegion.streaming { stream ?=>
      runCheckedEpochLatencyBody()
    }

  def runCheckedSafeZoneEpochLatency(): LatencyRun =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedEpochLatencyBody()
    }

  def runCheckedDirectEpochLatencyBody()(using
      stream: RiftRegion.StreamingRegion^
  ): LatencyRun = {
    val cfg = StreamFlexRegionConfig
    val samples = new Array[Long](cfg.latencyEvents)
    var checksum = 0L
    var i = 0
    while (i < cfg.latencyEvents) {
      val start = System.nanoTime()
      checksum += RiftRegion.epoch {
        processCheckedDirectEpochBatch(
          i,
          1,
          cfg.latencyObjectsPerEvent
        )
      }
      val end = System.nanoTime()
      samples(i) = end - start
      i += 1
    }
    checksumSink = checksum
    summarizeLatency(samples, checksum)
  }

  def runCheckedDirectEpochLatency(): LatencyRun =
    RiftRegion.streaming { stream ?=>
      runCheckedDirectEpochLatencyBody()
    }

  def runCheckedSafeZoneDirectEpochLatency(): LatencyRun =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedDirectEpochLatencyBody()
    }

  def runCheckedTransactionLatencyBody()(using
      stream: RiftRegion.StreamingRegion^
  ): LatencyRun = {
    val cfg = StreamFlexRegionConfig
    val tx = RiftRegion.transactionRegion(4)(using stream)
    val packets = RiftRegion.transactionList[CheckedPacket](stream, tx, 0)
    val decoded = RiftRegion.transactionList[CheckedDecoded](stream, tx, 1)
    val classified = RiftRegion.transactionList[CheckedClassified](stream, tx, 2)
    val alerts = RiftRegion.transactionList[CheckedAlert](stream, tx, 3)
    val samples = new Array[Long](cfg.latencyEvents)
    var checksum = 0L
    var i = 0
    while (i < cfg.latencyEvents) {
      val start = System.nanoTime()
      checksum += processCheckedTransactionBatch(
        stream,
        tx,
        packets,
        decoded,
        classified,
        alerts,
        i,
        1,
        cfg.latencyObjectsPerEvent
      )
      val end = System.nanoTime()
      samples(i) = end - start
      i += 1
    }
    checksumSink = checksum
    summarizeLatency(samples, checksum)
  }

  def runCheckedTransactionLatency(): LatencyRun =
    RiftRegion.streaming { stream ?=>
      runCheckedTransactionLatencyBody()
    }

  def runCheckedSafeZoneTransactionLatency(): LatencyRun =
    RiftRegion.streamingSafeZone { stream ?=>
      runCheckedTransactionLatencyBody()
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
    else if (mode == "rift-checked-direct-epoch")
      runCheckedDirectEpochThroughput()
    else if (mode == "rift-checked-safezone-direct-epoch")
      runCheckedSafeZoneDirectEpochThroughput()
    else if (mode == "rift-checked-epoch-buffer") runCheckedEpochThroughput()
    else if (mode == "rift-checked-safezone-epoch-buffer")
      runCheckedSafeZoneEpochThroughput()
    else if (mode == "rift-checked-transaction-region")
      runCheckedTransactionThroughput()
    else if (mode == "rift-checked-safezone-transaction-region")
      runCheckedSafeZoneTransactionThroughput()
    else runHeapOrRiftThroughput(mode)

  private def runLatency(mode: String): LatencyRun =
    if (mode == "safezone") runSafeZoneLatency()
    else if (mode == "rift-checked-direct-epoch")
      runCheckedDirectEpochLatency()
    else if (mode == "rift-checked-safezone-direct-epoch")
      runCheckedSafeZoneDirectEpochLatency()
    else if (mode == "rift-checked-epoch-buffer") runCheckedEpochLatency()
    else if (mode == "rift-checked-safezone-epoch-buffer")
      runCheckedSafeZoneEpochLatency()
    else if (mode == "rift-checked-transaction-region")
      runCheckedTransactionLatency()
    else if (mode == "rift-checked-safezone-transaction-region")
      runCheckedSafeZoneTransactionLatency()
    else runHeapOrRiftLatency(mode)

  def runThroughputBenchmark(mode: String): Unit = {
    val cfg = StreamFlexRegionConfig
    val usesRift =
      mode == "rift-hp" ||
        mode == "rift-streaming" ||
        mode == "rift-checked-direct-epoch" ||
        mode == "rift-checked-epoch-buffer" ||
        mode == "rift-checked-transaction-region"
    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      while (run < cfg.benchmarkRuns) {
        val result = runThroughput(mode)
        if (run == 0) checksum = result
        else if (result != checksum)
          throw new IllegalStateException(
            s"final-clean streamflex throughput mismatch mode=$mode first_checksum=$checksum actual=$result"
          )
        run += 1
      }
      println(
        s"RESULT name=streamflex-throughput-$mode " +
          s"measurement_level=L1 final_clean=1 workload=throughput " +
          s"mode=$mode runs=${cfg.benchmarkRuns} events=${cfg.events} " +
          s"batch_size=${cfg.batchSize} objects_per_event=${cfg.objectsPerEvent} " +
          s"checksum=$checksum"
      )
      return
    }
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
    val usesRift =
      mode == "rift-hp" ||
        mode == "rift-streaming" ||
        mode == "rift-checked-direct-epoch" ||
        mode == "rift-checked-epoch-buffer" ||
        mode == "rift-checked-transaction-region"
    if (cfg.finalClean) {
      var run = 0
      var result = runLatency(mode)
      val checksum = result.checksum
      run = 1
      while (run < cfg.benchmarkRuns) {
        val next = runLatency(mode)
        if (next.checksum != checksum)
          throw new IllegalStateException(
            s"final-clean streamflex latency mismatch mode=$mode first_checksum=$checksum actual=${next.checksum}"
          )
        result = next
        run += 1
      }
      println(
        s"RESULT name=streamflex-latency-$mode " +
          s"measurement_level=L1 final_clean=1 workload=latency " +
          s"mode=$mode runs=${cfg.benchmarkRuns} " +
          s"latency_events=${cfg.latencyEvents} " +
          s"latency_objects_per_event=${cfg.latencyObjectsPerEvent} " +
          s"period_ns=${cfg.periodNs} p50_ns=${result.p50Ns} " +
          s"p99_ns=${result.p99Ns} p999_ns=${result.p999Ns} " +
          s"max_ns=${result.maxNs} deadline_misses=${result.deadlineMisses} " +
          s"checksum=$checksum"
      )
      return
    }
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
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked-direct-epoch" |
          "rift-checked-safezone-direct-epoch" |
          "rift-checked-epoch-buffer" |
          "rift-checked-safezone-epoch-buffer" |
          "rift-checked-transaction-region" |
          "rift-checked-safezone-transaction-region" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown StreamFlex mode '$other'; expected heap, safezone, rift-hp, rift-streaming, rift-checked-direct-epoch, rift-checked-safezone-direct-epoch, rift-checked-epoch-buffer, rift-checked-safezone-epoch-buffer, rift-checked-transaction-region, or rift-checked-safezone-transaction-region"
        )
    }
}

@main def StreamFlexRegionMatrix(
    mode: String = "heap",
    workload: String = "all"
): Unit = {
  StreamFlexRegionMatrixHelpers.validateMode(mode)
  StreamFlexRegionMatrixHelpers.printConfig(mode, workload)

  val usesRift =
    mode == "rift-hp" ||
      mode == "rift-streaming" ||
      mode == "rift-checked-direct-epoch" ||
      mode == "rift-checked-epoch-buffer" ||
      mode == "rift-checked-transaction-region"
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
