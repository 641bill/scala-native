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

object YakRegionConfig {
  private def positiveInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed > 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def nonNegativeInt(value: String): Option[Int] =
    try {
      val parsed = value.toInt
      if (parsed >= 0) Some(parsed) else None
    } catch {
      case _: NumberFormatException => None
    }

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(positiveInt).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(nonNegativeInt).getOrElse(default)

  val epochs: Int = envInt("YAK_EPOCHS", 20)
  val recordsPerEpoch: Int = envInt("YAK_RECORDS_PER_EPOCH", 100000)
  val keySpace: Int = envInt("YAK_KEY_SPACE", 65536)
  val vertices: Int = envInt("YAK_VERTICES", 100000)
  val messagesPerEpoch: Int = envInt("YAK_MESSAGES_PER_EPOCH", 100000)
  val benchmarkRuns: Int = envInt("YAK_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("YAK_WARMUPS", 1)
}

object YakRegionMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class Token(
      val key: Int,
      val weight: Int,
      val next: Token
  )

  private final class Message(
      val dst: Int,
      val delta: Int,
      val tag: Int,
      val next: Message
  )

  private final class YakRuntimeEpoch {
    private var region: RiftRegion = RiftRegion.open(RiftRegion.Streaming)
    private var closed = false

    def begin(): RiftRegion = {
      if (closed)
        throw new IllegalStateException("Yak runtime epoch is closed")
      region.reset()
      region
    }

    def end(): Unit = {
      if (closed)
        throw new IllegalStateException("Yak runtime epoch is closed")
    }

    def close(): Unit =
      if (!closed) {
        region.close()
        region = null
        closed = true
      }

    def allocToken(key: Int, weight: Int, next: Token): Token = {
      if (closed)
        throw new IllegalStateException("allocation after Yak epoch close")
      region.alloc(new Token(key, weight, next))
    }

    def allocMessage(dst: Int, delta: Int, tag: Int, next: Message): Message = {
      if (closed)
        throw new IllegalStateException("allocation after Yak epoch close")
      region.alloc(new Message(dst, delta, tag, next))
    }
  }

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

  private final class ModeState(mode: String) {
    val usesRift: Boolean =
      mode == "rift-hp" || mode == "rift-streaming" || mode == "yak-runtime"
    private val runtimeSafe: Boolean = mode == "yak-runtime"
    private val streaming: Boolean = mode == "rift-streaming"
    private val kind: Int =
      if (streaming) RiftRegion.Streaming else RiftRegion.HPZone
    private var streamRegion: RiftRegion = null
    private var runtimeEpoch: YakRuntimeEpoch = null

    def beginEpoch(): RiftRegion =
      if (!usesRift) null
      else if (runtimeSafe) {
        if (runtimeEpoch == null) runtimeEpoch = new YakRuntimeEpoch()
        runtimeEpoch.begin()
      }
      else if (streaming) {
        if (streamRegion == null) streamRegion = RiftRegion.open(kind)
        else streamRegion.reset()
        streamRegion
      } else {
        RiftRegion.open(kind)
      }

    def endEpoch(region: RiftRegion): Unit =
      if (runtimeSafe) runtimeEpoch.end()
      else if (usesRift && !streaming) region.close()

    def finish(): Unit = {
      if (streamRegion != null) {
        streamRegion.close()
        streamRegion = null
      }
      if (runtimeEpoch != null) {
        runtimeEpoch.close()
        runtimeEpoch = null
      }
    }

    def allocToken(
        region: RiftRegion,
        key: Int,
        weight: Int,
        next: Token
    ): Token =
      if (runtimeSafe) runtimeEpoch.allocToken(key, weight, next)
      else if (usesRift) region.alloc(new Token(key, weight, next))
      else new Token(key, weight, next)

    def allocMessage(
        region: RiftRegion,
        dst: Int,
        delta: Int,
        tag: Int,
        next: Message
    ): Message =
      if (runtimeSafe) runtimeEpoch.allocMessage(dst, delta, tag, next)
      else if (usesRift) region.alloc(new Message(dst, delta, tag, next))
      else new Message(dst, delta, tag, next)
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def checksumLongs(values: Array[Long]): Long = {
    var checksum = 0L
    var i = 0
    while (i < values.length) {
      checksum = (checksum * 1315423911L) ^ values(i) ^ i.toLong
      i += 1
    }
    checksum
  }

  def runHeapOrRiftWordCount(modeName: String): Long = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val counts = new Array[Long](cfg.keySpace)
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var tokens: Token = null
        var i = 0
        while (i < cfg.recordsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val weight = (mix(seed + 17) & 7) + 1
          tokens = mode.allocToken(region, key, weight, tokens)
          i += 1
        }

        var token = tokens
        while (token != null) {
          counts(token.key) += token.weight.toLong
          token = token.next
        }
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()
    val checksum = checksumLongs(counts)
    checksumSink = checksum
    checksum
  }

  def runHeapOrRiftGraphStep(modeName: String): Long = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val values = new Array[Long](cfg.vertices)
    var i = 0
    while (i < values.length) {
      values(i) = mix(i + 41).toLong & 0xffffL
      i += 1
    }

    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var messages: Message = null
        var msg = 0
        while (msg < cfg.messagesPerEpoch) {
          val seed = mix(epoch * 65537 + msg * 17)
          val dst = seed % cfg.vertices
          val delta = (mix(seed + epoch) & 31) - 15
          messages = mode.allocMessage(
            region,
            dst,
            delta,
            seed & 0xff,
            messages
          )
          msg += 1
        }

        var current = messages
        while (current != null) {
          val contribution =
            current.delta.toLong + ((current.tag.toLong * 17L) & 0xffL)
          values(current.dst) = (values(current.dst) + contribution) & 0xffffffffL
          current = current.next
        }
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()
    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runSafeZoneWordCount(): Long = {
    val cfg = YakRegionConfig
    val counts = new Array[Long](cfg.keySpace)
    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      SafeZone { sz ?=>
        final class SZToken(
            val key: Int,
            val weight: Int,
            val next: SZToken^{sz}
        )

        var tokens: SZToken^{sz} = null
        var i = 0
        while (i < cfg.recordsPerEpoch) {
          val seed = mix(currentEpoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val weight = (mix(seed + 17) & 7) + 1
          tokens = SafeZoneAllocator.allocate(
            sz,
            new SZToken(key, weight, tokens)
          )
          i += 1
        }

        var token = tokens
        while (token != null) {
          counts(token.key) += token.weight.toLong
          token = token.next
        }
      }
      epoch += 1
    }
    val checksum = checksumLongs(counts)
    checksumSink = checksum
    checksum
  }

  def runSafeZoneGraphStep(): Long = {
    val cfg = YakRegionConfig
    val values = new Array[Long](cfg.vertices)
    var i = 0
    while (i < values.length) {
      values(i) = mix(i + 41).toLong & 0xffffL
      i += 1
    }

    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      SafeZone { sz ?=>
        final class SZMessage(
            val dst: Int,
            val delta: Int,
            val tag: Int,
            val next: SZMessage^{sz}
        )

        var messages: SZMessage^{sz} = null
        var msg = 0
        while (msg < cfg.messagesPerEpoch) {
          val seed = mix(currentEpoch * 65537 + msg * 17)
          val dst = seed % cfg.vertices
          val delta = (mix(seed + currentEpoch) & 31) - 15
          messages = SafeZoneAllocator.allocate(
            sz,
            new SZMessage(dst, delta, seed & 0xff, messages)
          )
          msg += 1
        }

        var current = messages
        while (current != null) {
          val contribution =
            current.delta.toLong + ((current.tag.toLong * 17L) & 0xffL)
          values(current.dst) = (values(current.dst) + contribution) & 0xffffffffL
          current = current.next
        }
      }
      epoch += 1
    }
    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
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

  private def runWorkload(mode: String, workload: String): Long =
    workload match {
      case "wordcount" =>
        if (mode == "safezone") runSafeZoneWordCount()
        else runHeapOrRiftWordCount(mode)
      case "graphstep" =>
        if (mode == "safezone") runSafeZoneGraphStep()
        else runHeapOrRiftGraphStep(mode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount or graphstep"
        )
    }

  private def logicalDataObjects(workload: String): Long = {
    val cfg = YakRegionConfig
    workload match {
      case "wordcount" => cfg.epochs.toLong * cfg.recordsPerEpoch.toLong
      case "graphstep" => cfg.epochs.toLong * cfg.messagesPerEpoch.toLong
      case _           => 0L
    }
  }

  private def controlSlots(workload: String): Long = {
    val cfg = YakRegionConfig
    workload match {
      case "wordcount" => cfg.keySpace.toLong
      case "graphstep" => cfg.vertices.toLong
      case _           => 0L
    }
  }

  def runBenchmark(mode: String, workload: String): Unit = {
    val cfg = YakRegionConfig
    val usesRift =
      mode == "rift-hp" || mode == "rift-streaming" || mode == "yak-runtime"
    val expected = runWorkload("heap", workload)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runWorkload(mode, workload)
      if (checksum != expected)
        throw new IllegalStateException(
          s"warmup checksum mismatch workload=$workload mode=$mode expected=$expected actual=$checksum"
        )
      warmup += 1
    }

    if (usesRift) RiftAllocator.Impl.statsReset()

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcNanos = new Array[Long](cfg.benchmarkRuns)
    val riftOpNanos = new Array[Long](cfg.benchmarkRuns)
    val riftSlowNanos = new Array[Long](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)

    println(s"Running yak-$workload-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runWorkload(mode, workload)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (checksum != expected)
        throw new IllegalStateException(
          s"checksum mismatch workload=$workload mode=$mode expected=$expected actual=$checksum"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftSlowNanos(run) = runtime.riftSlowAllocNanos
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
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d"
      )
      run += 1
    }

    val dataObjects = logicalDataObjects(workload)
    val slots = controlSlots(workload)
    println(
      f"RESULT name=yak-$workload-$mode " +
        f"median_ms=${medianDouble(elapsedMs)}%.3f " +
        f"median_gc_ms=${medianLong(gcNanos) / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianLong(riftOpNanos) / 1000000.0}%.3f " +
        f"median_rift_slow_alloc_ms=${medianLong(riftSlowNanos) / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=${medianLong(riftObjects)}%d " +
        f"median_rift_open_total=${medianLong(riftOpens)}%d " +
        f"median_rift_close_total=${medianLong(riftCloses)}%d " +
        f"median_rift_reset_total=${medianLong(riftResets)}%d " +
        f"logical_data_objects=$dataObjects%d " +
        f"control_slots=$slots%d " +
        f"checksum=$expected%d"
    )
  }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = YakRegionConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    println(
      s"CONFIG mode=$mode workload=$workload runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} records_per_epoch=${cfg.recordsPerEpoch} key_space=${cfg.keySpace} vertices=${cfg.vertices} messages_per_epoch=${cfg.messagesPerEpoch} safezone_roots_mode=$rootsMode"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" | "yak-runtime" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak mode '$other'; expected heap, safezone, rift-hp, rift-streaming, or yak-runtime"
        )
    }
}

@main def YakRegionMatrix(
    mode: String = "heap",
    workload: String = "all"
): Unit = {
  YakRegionMatrixHelpers.validateMode(mode)
  YakRegionMatrixHelpers.printConfig(mode, workload)

  val usesRift =
    mode == "rift-hp" || mode == "rift-streaming" || mode == "yak-runtime"
  if (usesRift) RiftRegion.init(0)
  try {
    workload match {
      case "all" =>
        YakRegionMatrixHelpers.runBenchmark(mode, "wordcount")
        YakRegionMatrixHelpers.runBenchmark(mode, "graphstep")
      case "wordcount" | "graphstep" =>
        YakRegionMatrixHelpers.runBenchmark(mode, workload)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount, graphstep, or all"
        )
    }
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
