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
  val sortRecordsPerEpoch: Int = envInt("YAK_SORT_RECORDS_PER_EPOCH", 20000)
  val graphChiSubintervals: Int = envInt("YAK_GRAPHCHI_SUBINTERVALS", 16)
  val graphChiEdgesPerSubinterval: Int =
    envInt("YAK_GRAPHCHI_EDGES_PER_SUBINTERVAL", 5000)
  val graphInputPath: String = BenchmarkInputSupport.envString("YAK_GRAPH_INPUT")
  val graphInputEdges: Int = envInt("YAK_GRAPH_INPUT_EDGES", 1000000)
  val graphInputVertices: Int =
    envInt("YAK_GRAPH_INPUT_VERTICES", vertices)
  val graphInputEdgesPerEpoch: Int =
    envInt("YAK_GRAPH_INPUT_EDGES_PER_EPOCH", messagesPerEpoch)
  val escapeModulo: Int = envInt("YAK_ESCAPE_MODULO", 1000)
  val scratchSlots: Int = envInt("YAK_SCRATCH_SLOTS", 128)
  val benchmarkRuns: Int = envInt("YAK_BENCHMARK_RUNS", 3)
  val warmupRuns: Int = envNonNegativeInt("YAK_WARMUPS", 1)

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
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

  private final class SortRecord(
      val key: Int,
      val value: Int
  )

  private final class WordRecord(
      val key: Int,
      val weight: Int,
      val keep: Boolean,
      val next: WordRecord
  )

  private final class EdgeUpdate(
      val src: Int,
      val dst: Int,
      val delta: Int,
      val next: EdgeUpdate
  )

  private final class PromoChild(
      val marker: Int,
      val weight: Int
  )

  private final class PromoToken(
      val key: Int,
      val weight: Int,
      val epoch: Int,
      val child: PromoChild
  )

  private final class WorkloadResult(
      val checksum: Long,
      val barrierChecks: Long,
      val rememberedRefs: Long,
      val promotedObjects: Long
  )

  private final class RealGraphInput(
      val srcs: Array[Int],
      val dsts: Array[Int],
      val edgeCount: Int,
      val vertices: Int,
      val label: String
  )

  private object RealGraphInput {
    private var cachedPath: String = ""
    private var cachedEdgeLimit: Int = 0
    private var cachedVertices: Int = 0
    private var cached: RealGraphInput = null

    private def digit(byte: Int): Boolean =
      byte >= '0' && byte <= '9'

    private def parseEdge(
        bytes: Array[Byte],
        length: Int,
        vertices: Int
    ): Long = {
      var i = 0
      var found = 0
      var src = 0
      var dst = 0
      while (i < length && found < 2) {
        var byte = bytes(i) & 0xff
        if (byte == '#') return -1L
        while (i < length && !digit(byte)) {
          i += 1
          if (i < length) {
            byte = bytes(i) & 0xff
            if (byte == '#') return -1L
          }
        }
        if (i < length) {
          var value = 0L
          while (i < length && digit(bytes(i) & 0xff)) {
            value = value * 10L + ((bytes(i) & 0xff) - '0')
            i += 1
          }
          val mapped = BenchmarkInputSupport.positiveModulo(value, vertices)
          if (found == 0) src = mapped else dst = mapped
          found += 1
        }
      }
      if (found < 2) -1L
      else ((src.toLong & 0xffffffffL) << 32) | (dst.toLong & 0xffffffffL)
    }

    def load(): RealGraphInput = this.synchronized {
      val cfg = YakRegionConfig
      if (cfg.graphInputPath.isEmpty)
        throw new IllegalArgumentException(
          "YAK_WORKLOAD=graphreal requires YAK_GRAPH_INPUT"
        )
      if (
        cached != null && cachedPath == cfg.graphInputPath &&
        cachedEdgeLimit == cfg.graphInputEdges &&
        cachedVertices == cfg.graphInputVertices
      ) return cached

      val srcs = new Array[Int](cfg.graphInputEdges)
      val dsts = new Array[Int](cfg.graphInputEdges)
      val reader = BenchmarkInputSupport.openByteLines(cfg.graphInputPath)
      var count = 0
      try {
        var length = reader.readLine()
        while (length >= 0 && count < cfg.graphInputEdges) {
          val packed = parseEdge(reader.bytes, length, cfg.graphInputVertices)
          if (packed >= 0L) {
            srcs(count) = (packed >>> 32).toInt
            dsts(count) = packed.toInt
            count += 1
          }
          length = reader.readLine()
        }
      } finally reader.close()

      if (count == 0)
        throw new IllegalArgumentException(
          s"YAK_GRAPH_INPUT '${cfg.graphInputPath}' did not contain usable edges"
        )

      val srcOut =
        if (count == srcs.length) srcs
        else {
          val out = new Array[Int](count)
          System.arraycopy(srcs, 0, out, 0, count)
          out
        }
      val dstOut =
        if (count == dsts.length) dsts
        else {
          val out = new Array[Int](count)
          System.arraycopy(dsts, 0, out, 0, count)
          out
        }

      cachedPath = cfg.graphInputPath
      cachedEdgeLimit = cfg.graphInputEdges
      cachedVertices = cfg.graphInputVertices
      cached = new RealGraphInput(
        srcOut,
        dstOut,
        count,
        cfg.graphInputVertices,
        s"real-graph:${cfg.graphInputPath}"
      )
      cached
    }
  }

  private final class YakRuntimeEpoch {
    private val epoch = RiftRegion.runtimeEpoch(RiftRegion.Streaming)

    def begin(): RiftRegion =
      epoch.begin()

    def end(): Unit =
      epoch.end()

    def close(): Unit =
      epoch.close()

    def allocToken(key: Int, weight: Int, next: Token): Token =
      epoch.alloc(new Token(key, weight, next))

    def allocMessage(dst: Int, delta: Int, tag: Int, next: Message): Message =
      epoch.alloc(new Message(dst, delta, tag, next))

    def allocSortRecord(key: Int, value: Int): SortRecord =
      epoch.alloc(new SortRecord(key, value))

    def allocWordRecord(
        key: Int,
        weight: Int,
        keep: Boolean,
        next: WordRecord
    ): WordRecord =
      epoch.alloc(new WordRecord(key, weight, keep, next))

    def allocEdgeUpdate(
        src: Int,
        dst: Int,
        delta: Int,
        next: EdgeUpdate
    ): EdgeUpdate =
      epoch.alloc(new EdgeUpdate(src, dst, delta, next))

    def allocPromoChild(marker: Int, weight: Int): PromoChild =
      epoch.alloc(new PromoChild(marker, weight))

    def allocPromoToken(
        key: Int,
        weight: Int,
        epoch: Int,
        child: PromoChild
    ): PromoToken = {
      this.epoch.alloc(new PromoToken(key, weight, epoch, child))
    }

    def controlWriteOrNull(
        value: PromoToken,
        retain: Boolean
    )(using promoter: RiftRegion.RuntimePromoter[PromoToken, PromoToken])
        : PromoToken =
      epoch.controlWriteOrNull(value, retain)

    def statsSnapshot(): RiftRegion.RuntimeEpochStats =
      epoch.statsSnapshot()
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

    def allocSortRecord(
        region: RiftRegion,
        key: Int,
        value: Int
    ): SortRecord =
      if (runtimeSafe) runtimeEpoch.allocSortRecord(key, value)
      else if (usesRift) region.alloc(new SortRecord(key, value))
      else new SortRecord(key, value)

    def allocWordRecord(
        region: RiftRegion,
        key: Int,
        weight: Int,
        keep: Boolean,
        next: WordRecord
    ): WordRecord =
      if (runtimeSafe) runtimeEpoch.allocWordRecord(key, weight, keep, next)
      else if (usesRift) region.alloc(new WordRecord(key, weight, keep, next))
      else new WordRecord(key, weight, keep, next)

    def allocEdgeUpdate(
        region: RiftRegion,
        src: Int,
        dst: Int,
        delta: Int,
        next: EdgeUpdate
    ): EdgeUpdate =
      if (runtimeSafe) runtimeEpoch.allocEdgeUpdate(src, dst, delta, next)
      else if (usesRift) region.alloc(new EdgeUpdate(src, dst, delta, next))
      else new EdgeUpdate(src, dst, delta, next)

    def allocPromoChild(
        region: RiftRegion,
        marker: Int,
        weight: Int
    ): PromoChild =
      if (runtimeSafe) runtimeEpoch.allocPromoChild(marker, weight)
      else if (usesRift) region.alloc(new PromoChild(marker, weight))
      else new PromoChild(marker, weight)

    def allocPromoToken(
        region: RiftRegion,
        key: Int,
        weight: Int,
        epoch: Int,
        child: PromoChild
    ): PromoToken =
      if (runtimeSafe) runtimeEpoch.allocPromoToken(key, weight, epoch, child)
      else if (usesRift) region.alloc(new PromoToken(key, weight, epoch, child))
      else new PromoToken(key, weight, epoch, child)

    def controlWrite(
        value: PromoToken,
        retained: Boolean
    ): PromoToken = {
      if (runtimeSafe) runtimeEpoch.controlWriteOrNull(value, retained)
      else if (usesRift && retained)
        throw new IllegalArgumentException(
          "escaping Yak promotion data requires yak-runtime; raw Rift modes are static/trusted and do not promote escaping region objects"
        )
      else if (usesRift) null
      else value
    }

    def promotionStats(): RiftRegion.RuntimeEpochStats =
      if (runtimeSafe && runtimeEpoch != null) runtimeEpoch.statsSnapshot()
      else RiftRegion.RuntimeEpochStats(0L, 0L, 0L)
  }

  private given promoTokenPromoter
      : RiftRegion.RuntimePromoter[PromoToken, PromoToken] =
    new RiftRegion.RuntimePromoter[PromoToken, PromoToken] {
      override def promote(value: PromoToken): PromoToken =
        new PromoToken(
          value.key,
          value.weight,
          value.epoch,
          new PromoChild(value.child.marker, value.child.weight)
        )

      override def promotedObjectCount(value: PromoToken): Int = 2
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

  private def sortRecordComesBefore(left: SortRecord, right: SortRecord): Boolean =
    left.key < right.key || (left.key == right.key && left.value < right.value)

  private def sortRecords(records: Array[SortRecord]): Unit = {
    def swap(i: Int, j: Int): Unit = {
      val tmp = records(i)
      records(i) = records(j)
      records(j) = tmp
    }

    def quickSort(lo: Int, hi: Int): Unit =
      if (lo < hi) {
        val pivot = records((lo + hi) >>> 1)
        var i = lo
        var j = hi
        while (i <= j) {
          while (sortRecordComesBefore(records(i), pivot)) i += 1
          while (sortRecordComesBefore(pivot, records(j))) j -= 1
          if (i <= j) {
            swap(i, j)
            i += 1
            j -= 1
          }
        }
        if (lo < j) quickSort(lo, j)
        if (i < hi) quickSort(i, hi)
      }

    if (records.length > 1) quickSort(0, records.length - 1)
  }

  private def consumeSortedRecords(
      records: Array[SortRecord],
      groups: Array[Long]
  ): Unit = {
    var i = 0
    while (i < records.length) {
      val key = records(i).key
      var sum = 0L
      while (i < records.length && records(i).key == key) {
        sum += records(i).value.toLong
        i += 1
      }
      groups(key) = (groups(key) + sum) & 0xffffffffL
    }
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

  def runHeapOrRiftSort(modeName: String): Long = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val groups = new Array[Long](cfg.keySpace)
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        val records = new Array[SortRecord](cfg.sortRecordsPerEpoch)
        var i = 0
        while (i < records.length) {
          val seed = mix(epoch * 1000003 + i * 97)
          val key = seed % cfg.keySpace
          val value = (mix(seed + 31337) & 0xffff) + 1
          records(i) = mode.allocSortRecord(region, key, value)
          i += 1
        }

        sortRecords(records)
        consumeSortedRecords(records, groups)
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()
    val checksum = checksumLongs(groups)
    checksumSink = checksum
    checksum
  }

  def runHeapOrRiftTopWord(modeName: String): Long = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val globalCounts = new Array[Long](cfg.keySpace)
    val localCounts = new Array[Int](cfg.keySpace)
    val touchedKeys = new Array[Int](cfg.keySpace)
    var topChecksum = 0L
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var records: WordRecord = null
        var i = 0
        while (i < cfg.recordsPerEpoch) {
          val seed = mix(epoch * 1000003 + i * 131)
          val key = seed % cfg.keySpace
          val weight = (mix(seed + 19) & 15) + 1
          val keep = ((seed ^ (seed >>> 3)) & 7) != 0
          records = mode.allocWordRecord(region, key, weight, keep, records)
          i += 1
        }

        var touched = 0
        var current = records
        while (current != null) {
          if (current.keep) {
            if (localCounts(current.key) == 0) {
              touchedKeys(touched) = current.key
              touched += 1
            }
            localCounts(current.key) += current.weight
          }
          current = current.next
        }

        var bestKey = -1
        var bestCount = -1L
        var j = 0
        while (j < touched) {
          val key = touchedKeys(j)
          val count = localCounts(key).toLong
          globalCounts(key) = (globalCounts(key) + count) & 0xffffffffL
          if (count > bestCount || (count == bestCount && key < bestKey)) {
            bestCount = count
            bestKey = key
          }
          localCounts(key) = 0
          j += 1
        }
        topChecksum =
          (topChecksum * 1099511628211L) ^ bestKey.toLong ^ bestCount
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()
    val checksum = checksumLongs(globalCounts) ^ topChecksum
    checksumSink = checksum
    checksum
  }

  def runHeapOrRiftGraphChi(modeName: String): Long = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val values = new Array[Long](cfg.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 71).toLong & 0xffffL
      vertex += 1
    }

    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        var subinterval = 0
        while (subinterval < cfg.graphChiSubintervals) {
          val region = mode.beginEpoch()
          val start =
            ((subinterval.toLong * cfg.vertices.toLong) /
              cfg.graphChiSubintervals.toLong).toInt
          val end =
            (((subinterval + 1).toLong * cfg.vertices.toLong) /
              cfg.graphChiSubintervals.toLong).toInt
          val width = math.max(1, end - start)

          var updates: EdgeUpdate = null
          var edge = 0
          while (edge < cfg.graphChiEdgesPerSubinterval) {
            val seed =
              mix(epoch * 1000003 + subinterval * 9176 + edge * 37)
            val src = mix(seed + 11) % cfg.vertices
            val dst = start + (mix(seed + 23) % width)
            val delta = (mix(seed + epoch + subinterval) & 31) - 15
            updates = mode.allocEdgeUpdate(region, src, dst, delta, updates)
            edge += 1
          }

          var current = updates
          while (current != null) {
            val contribution =
              (values(current.src) + current.delta.toLong + subinterval) &
                0xffL
            values(current.dst) =
              (values(current.dst) + contribution) & 0xffffffffL
            current = current.next
          }

          mode.endEpoch(region)
          subinterval += 1
        }
        epoch += 1
      }
    } finally mode.finish()
    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runHeapOrRiftGraphReal(modeName: String): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val mode = new ModeState(modeName)
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    var edgeCursor = 0
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var updates: EdgeUpdate = null
        var edge = 0
        while (edge < cfg.graphInputEdgesPerEpoch) {
          val index = (edgeCursor + edge) % input.edgeCount
          val src = input.srcs(index)
          val dst = input.dsts(index)
          val delta = ((src ^ dst ^ epoch ^ edge) & 31) - 15
          updates = mode.allocEdgeUpdate(region, src, dst, delta, updates)
          edge += 1
        }
        edgeCursor =
          (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount

        var current = updates
        while (current != null) {
          val contribution =
            (values(current.src) + current.delta.toLong + epoch) & 0xffL
          values(current.dst) =
            (values(current.dst) + contribution) & 0xffffffffL
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

  def runCheckedWordCountEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val counts = new Array[Long](cfg.keySpace)

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        RiftRegion.epoch { region ?=>
          final class CheckedToken(
              val key: Int,
              val weight: Int,
              val next: CheckedToken^{region}
          )

          var tokens: CheckedToken^{region} = null
          var i = 0
          while (i < cfg.recordsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val weight = (mix(seed + 17) & 7) + 1
            tokens = RiftRegion.allocOpen(new CheckedToken(key, weight, tokens))
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
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumLongs(counts)
    checksumSink = checksum
    checksum
  }

  def runCheckedTopWordEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val globalCounts = new Array[Long](cfg.keySpace)
    val localCounts = new Array[Int](cfg.keySpace)
    val touchedKeys = new Array[Int](cfg.keySpace)
    var topChecksum = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        RiftRegion.epoch { region ?=>
          final class CheckedWordRecord(
              val key: Int,
              val weight: Int,
              val keep: Boolean,
              val next: CheckedWordRecord^{region}
          )

          var records: CheckedWordRecord^{region} = null
          var i = 0
          while (i < cfg.recordsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i * 131)
            val key = seed % cfg.keySpace
            val weight = (mix(seed + 19) & 15) + 1
            val keep = ((seed ^ (seed >>> 3)) & 7) != 0
            records =
              RiftRegion.allocOpen(
                new CheckedWordRecord(key, weight, keep, records)
              )
            i += 1
          }

          var touched = 0
          var current = records
          while (current != null) {
            if (current.keep) {
              if (localCounts(current.key) == 0) {
                touchedKeys(touched) = current.key
                touched += 1
              }
              localCounts(current.key) += current.weight
            }
            current = current.next
          }

          var bestKey = -1
          var bestCount = -1L
          var j = 0
          while (j < touched) {
            val key = touchedKeys(j)
            val count = localCounts(key).toLong
            globalCounts(key) = (globalCounts(key) + count) & 0xffffffffL
            if (count > bestCount || (count == bestCount && key < bestKey)) {
              bestCount = count
              bestKey = key
            }
            localCounts(key) = 0
            j += 1
          }
          topChecksum =
            (topChecksum * 1099511628211L) ^ bestKey.toLong ^ bestCount
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumLongs(globalCounts) ^ topChecksum
    checksumSink = checksum
    checksum
  }

  def runCheckedGraphStepEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val values = new Array[Long](cfg.vertices)
    var i = 0
    while (i < values.length) {
      values(i) = mix(i + 41).toLong & 0xffffL
      i += 1
    }

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        RiftRegion.epoch { region ?=>
          final class CheckedMessage(
              val dst: Int,
              val delta: Int,
              val tag: Int,
              val next: CheckedMessage^{region}
          )

          var messages: CheckedMessage^{region} = null
          var msg = 0
          while (msg < cfg.messagesPerEpoch) {
            val seed = mix(currentEpoch * 65537 + msg * 17)
            val dst = seed % cfg.vertices
            val delta = (mix(seed + currentEpoch) & 31) - 15
            messages =
              RiftRegion.allocOpen(
                new CheckedMessage(dst, delta, seed & 0xff, messages)
              )
            msg += 1
          }

          var current = messages
          while (current != null) {
            val contribution =
              current.delta.toLong + ((current.tag.toLong * 17L) & 0xffL)
            values(current.dst) =
              (values(current.dst) + contribution) & 0xffffffffL
            current = current.next
          }
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runCheckedSortEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val groups = new Array[Long](cfg.keySpace)

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        RiftRegion.epoch { region ?=>
          def comesBefore(
              left: SortRecord^{region},
              right: SortRecord^{region}
          ): Boolean =
            left.key < right.key ||
              (left.key == right.key && left.value < right.value)

          def sortCheckedRecords(
              records: Array[SortRecord^{region}]^{region}
          ): Unit = {
            def swap(i: Int, j: Int): Unit = {
              val tmp = records(i)
              records(i) = records(j)
              records(j) = tmp
            }

            def quickSort(lo: Int, hi: Int): Unit =
              if (lo < hi) {
                val pivot = records((lo + hi) >>> 1)
                var i = lo
                var j = hi
                while (i <= j) {
                  while (comesBefore(records(i), pivot)) i += 1
                  while (comesBefore(pivot, records(j))) j -= 1
                  if (i <= j) {
                    swap(i, j)
                    i += 1
                    j -= 1
                  }
                }
                if (lo < j) quickSort(lo, j)
                if (i < hi) quickSort(i, hi)
              }

            if (records.length > 1) quickSort(0, records.length - 1)
          }

          def consumeCheckedRecords(
              records: Array[SortRecord^{region}]^{region}
          ): Unit = {
            var i = 0
            while (i < records.length) {
              val key = records(i).key
              var sum = 0L
              while (i < records.length && records(i).key == key) {
                sum += records(i).value.toLong
                i += 1
              }
              groups(key) = (groups(key) + sum) & 0xffffffffL
            }
          }

          val records: Array[SortRecord^{region}]^{region} =
            RiftRegion.allocOpen(
              new Array[SortRecord^{region}](cfg.sortRecordsPerEpoch)
            )
          var i = 0
          while (i < records.length) {
            val seed = mix(currentEpoch * 1000003 + i * 97)
            val key = seed % cfg.keySpace
            val value = (mix(seed + 31337) & 0xffff) + 1
            records(i) = RiftRegion.allocOpen(new SortRecord(key, value))
            i += 1
          }

          sortCheckedRecords(records)
          consumeCheckedRecords(records)
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumLongs(groups)
    checksumSink = checksum
    checksum
  }

  def runCheckedGraphChiEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val values = new Array[Long](cfg.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 71).toLong & 0xffffL
      vertex += 1
    }

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        var subinterval = 0
        while (subinterval < cfg.graphChiSubintervals) {
          val currentSubinterval = subinterval
          RiftRegion.epoch { region ?=>
            final class CheckedEdgeUpdate(
                val src: Int,
                val dst: Int,
                val delta: Int,
                val next: CheckedEdgeUpdate^{region}
            )

            val start =
              ((currentSubinterval.toLong * cfg.vertices.toLong) /
                cfg.graphChiSubintervals.toLong).toInt
            val end =
              (((currentSubinterval + 1).toLong * cfg.vertices.toLong) /
                cfg.graphChiSubintervals.toLong).toInt
            val width = math.max(1, end - start)

            var updates: CheckedEdgeUpdate^{region} = null
            var edge = 0
            while (edge < cfg.graphChiEdgesPerSubinterval) {
              val seed =
                mix(currentEpoch * 1000003 + currentSubinterval * 9176 + edge * 37)
              val src = mix(seed + 11) % cfg.vertices
              val dst = start + (mix(seed + 23) % width)
              val delta =
                (mix(seed + currentEpoch + currentSubinterval) & 31) - 15
              updates =
                RiftRegion.allocOpen(
                  new CheckedEdgeUpdate(src, dst, delta, updates)
                )
              edge += 1
            }

            var current = updates
            while (current != null) {
              val contribution =
                (values(current.src) + current.delta.toLong +
                  currentSubinterval) & 0xffL
              values(current.dst) =
                (values(current.dst) + contribution) & 0xffffffffL
              current = current.next
            }
          }
          subinterval += 1
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  private def runCheckedGraphRealBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    final class CheckedEdgeUpdate(
        val src: Int,
        val dst: Int,
        val delta: Int
    ) extends RiftRegion.StreamAppendNode

    val window = RiftRegion.streamPageTokenAppendWindow[CheckedEdgeUpdate](1L)

    def processBucket(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedEdgeUpdate]^{stream}
    ): Unit = {
      val epoch = bucket.startSeconds.toInt
      var current = cursor.nextOwnedOrNull()
      while (current != null) {
        val update = current.asInstanceOf[CheckedEdgeUpdate^{stream}]
        val contribution =
          (values(update.src) + update.delta.toLong + epoch) & 0xffL
        values(update.dst) =
          (values(update.dst) + contribution) & 0xffffffffL
        current = cursor.nextOwnedOrNull()
      }
    }

    var edgeCursor = 0
    var epoch = 0
    while (epoch < cfg.epochs) {
      val start = epoch.toLong
      val currentRegion =
        RiftRegion.pageTokenAppendOpenRegionFor(
          stream,
          window,
          start,
          start
        )(processBucket)
      var edge = cfg.graphInputEdgesPerEpoch - 1
      while (edge >= 0) {
        val index = (edgeCursor + edge) % input.edgeCount
        val src = input.srcs(index)
        val dst = input.dsts(index)
        val delta = ((src ^ dst ^ epoch ^ edge) & 31) - 15
        val update: CheckedEdgeUpdate^{stream} =
          RiftRegion.allocOpen(new CheckedEdgeUpdate(src, dst, delta))(
            using currentRegion
        )
        RiftRegion.appendPageToken(stream, window, update)
        edge -= 1
      }
      edgeCursor = (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
      epoch += 1
    }

    RiftRegion.closeAllPageTokenAppendBucketsWithCursor(stream, window)(
      processBucket
    )
    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runCheckedGraphReal(safeZoneBackend: Boolean): Long =
    if (safeZoneBackend)
      RiftRegion.streamingSafeZone { stream ?=>
        runCheckedGraphRealBody()
      }
    else
      RiftRegion.streaming { stream ?=>
        runCheckedGraphRealBody()
      }

  private def runCheckedGraphRealLinkedEpoch(
      input: RealGraphInput,
      values: Array[Long],
      currentEdgeCursor: Int,
      currentEpoch: Int
  )(using region: RiftRegion.OpenStreamingRegion^): Unit = {
    val cfg = YakRegionConfig
    final class CheckedEdgeUpdate(
        val src: Int,
        val dst: Int,
        val delta: Int,
        val next: CheckedEdgeUpdate^{region}
    )

    var updates: CheckedEdgeUpdate^{region} = null
    var edge = 0
    while (edge < cfg.graphInputEdgesPerEpoch) {
      val index = (currentEdgeCursor + edge) % input.edgeCount
      val src = input.srcs(index)
      val dst = input.dsts(index)
      val delta = ((src ^ dst ^ currentEpoch ^ edge) & 31) - 15
      updates =
        RiftRegion.allocOpen(new CheckedEdgeUpdate(src, dst, delta, updates))
      edge += 1
    }

    var current = updates
    while (current != null) {
      val contribution =
        (values(current.src) + current.delta.toLong + currentEpoch) & 0xffL
      values(current.dst) =
        (values(current.dst) + contribution) & 0xffffffffL
      current = current.next
    }
  }

  private def runCheckedGraphRealWholeRunBody()(using
      region: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    var edgeCursor = 0
    var epoch = 0
    while (epoch < cfg.epochs) {
      runCheckedGraphRealLinkedEpoch(
        input,
        values,
        edgeCursor,
        epoch
      )(using region.asInstanceOf[RiftRegion.OpenStreamingRegion])
      edgeCursor = (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
      epoch += 1
    }

    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runCheckedGraphRealWholeRun(safeZoneBackend: Boolean): Long =
    if (safeZoneBackend)
      RiftRegion.streamingSafeZone { stream ?=>
        runCheckedGraphRealWholeRunBody()
      }
    else
      RiftRegion.streaming { stream ?=>
        runCheckedGraphRealWholeRunBody()
      }

  def runCheckedGraphRealEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    var edgeCursor = 0
    if (safeZoneBackend) {
      RiftRegion.streamingSafeZone { stream ?=>
        var epoch = 0
        while (epoch < cfg.epochs) {
          val currentEdgeCursor = edgeCursor
          val currentEpoch = epoch
          RiftRegion.epoch { region ?=>
            runCheckedGraphRealLinkedEpoch(
              input,
              values,
              currentEdgeCursor,
              currentEpoch
            )
          }
          edgeCursor = (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
          epoch += 1
        }
      }
    } else {
      RiftRegion.streaming { stream ?=>
        var epoch = 0
        while (epoch < cfg.epochs) {
          val currentEdgeCursor = edgeCursor
          val currentEpoch = epoch
          RiftRegion.epoch { region ?=>
            runCheckedGraphRealLinkedEpoch(
              input,
              values,
              currentEdgeCursor,
              currentEpoch
            )
          }
          edgeCursor =
            (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
          epoch += 1
        }
      }
    }

    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  private def runCheckedGraphRealEpochBufferBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    final class CheckedEdgeUpdate(
        val src: Int,
        val dst: Int,
        val delta: Int
    ) extends RiftRegion.StreamAppendNode

    val buffer = RiftRegion.epochBuffer[CheckedEdgeUpdate]()
    var closingEpoch = 0

    def consume(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedEdgeUpdate]^{stream}
    ): Unit = {
      var current = cursor.nextOwnedOrNull()
      while (current != null) {
        val update = current.asInstanceOf[CheckedEdgeUpdate^{stream}]
        val contribution =
          (values(update.src) + update.delta.toLong + closingEpoch) & 0xffL
        values(update.dst) =
          (values(update.dst) + contribution) & 0xffffffffL
        current = cursor.nextOwnedOrNull()
      }
    }

    var edgeCursor = 0
    var epoch = 0
    while (epoch < cfg.epochs) {
      closingEpoch = epoch
      val currentRegion =
        RiftRegion.epochBufferOpenRegionFor(stream, buffer)
      var edge = cfg.graphInputEdgesPerEpoch - 1
      while (edge >= 0) {
        val index = (edgeCursor + edge) % input.edgeCount
        val src = input.srcs(index)
        val dst = input.dsts(index)
        val delta = ((src ^ dst ^ epoch ^ edge) & 31) - 15
        val update: CheckedEdgeUpdate^{stream} =
          RiftRegion.allocOpen(new CheckedEdgeUpdate(src, dst, delta))(
            using currentRegion
          )
        RiftRegion.appendEpochBuffer(stream, buffer, update)
        edge -= 1
      }
      edgeCursor = (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
      RiftRegion.closeEpochBufferWithCursor(stream, buffer)(consume)
      epoch += 1
    }

    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runCheckedGraphRealEpochBuffer(safeZoneBackend: Boolean): Long =
    if (safeZoneBackend)
      RiftRegion.streamingSafeZone { stream ?=>
        runCheckedGraphRealEpochBufferBody()
      }
    else
      RiftRegion.streaming { stream ?=>
        runCheckedGraphRealEpochBufferBody()
      }

  private def runHeapOrRuntimePromotion(modeName: String): WorkloadResult = {
    val cfg = YakRegionConfig
    val mode = new ModeState(modeName)
    val counts = new Array[Long](cfg.keySpace)
    val retainedPerEpoch =
      ((cfg.recordsPerEpoch - 1).toLong / cfg.escapeModulo.toLong) + 1L
    val retainedCapacityLong = cfg.epochs.toLong * retainedPerEpoch
    if (retainedCapacityLong > Int.MaxValue)
      throw new IllegalArgumentException(
        s"too many retained Yak promotion records: $retainedCapacityLong"
      )
    val retainedCapacity = retainedCapacityLong.toInt
    val retained = new Array[PromoToken](retainedCapacity)
    val scratch = new Array[PromoToken](cfg.scratchSlots)
    var retainedCount = 0
    var epoch = 0
    var stats = RiftRegion.RuntimeEpochStats(0L, 0L, 0L)
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var i = 0
        while (i < cfg.recordsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val weight = (mix(seed + 17) & 7) + 1
          val child =
            mode.allocPromoChild(region, seed & 0xffff, weight ^ (seed & 3))
          val token = mode.allocPromoToken(region, key, weight, epoch, child)
          counts(token.key) +=
            token.weight.toLong + (token.child.weight.toLong & 1L)

          val shouldRetain = i % cfg.escapeModulo == 0
          val stored = mode.controlWrite(token, shouldRetain)
          if (shouldRetain) {
            retained(retainedCount) = stored
            retainedCount += 1
          } else {
            scratch(i % cfg.scratchSlots) = null
          }
          i += 1
        }
        mode.endEpoch(region)
        epoch += 1
      }
      stats = mode.promotionStats()
    } finally mode.finish()

    var checksum = checksumLongs(counts)
    var i = 0
    while (i < retainedCount) {
      val token = retained(i)
      checksum =
        (checksum * 16777619L) ^ token.key.toLong ^ token.weight.toLong ^
          token.epoch.toLong ^ token.child.marker.toLong
      i += 1
    }
    checksumSink = checksum
    new WorkloadResult(
      checksum,
      stats.barrierChecks,
      stats.rememberedRefs,
      stats.promotedObjects
    )
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

  def runSafeZoneSort(): Long = {
    val cfg = YakRegionConfig
    val groups = new Array[Long](cfg.keySpace)
    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      SafeZone { sz ?=>
        def comesBefore(
            left: SortRecord^{sz},
            right: SortRecord^{sz}
        ): Boolean =
          left.key < right.key ||
            (left.key == right.key && left.value < right.value)

        def sortSafeRecords(records: Array[SortRecord^{sz}]^{sz}): Unit = {
          def swap(i: Int, j: Int): Unit = {
            val tmp = records(i)
            records(i) = records(j)
            records(j) = tmp
          }

          def quickSort(lo: Int, hi: Int): Unit =
            if (lo < hi) {
              val pivot = records((lo + hi) >>> 1)
              var i = lo
              var j = hi
              while (i <= j) {
                while (comesBefore(records(i), pivot)) i += 1
                while (comesBefore(pivot, records(j))) j -= 1
                if (i <= j) {
                  swap(i, j)
                  i += 1
                  j -= 1
                }
              }
              if (lo < j) quickSort(lo, j)
              if (i < hi) quickSort(i, hi)
            }

          if (records.length > 1) quickSort(0, records.length - 1)
        }

        def consumeSafeRecords(records: Array[SortRecord^{sz}]^{sz}): Unit = {
          var i = 0
          while (i < records.length) {
            val key = records(i).key
            var sum = 0L
            while (i < records.length && records(i).key == key) {
              sum += records(i).value.toLong
              i += 1
            }
            groups(key) = (groups(key) + sum) & 0xffffffffL
          }
        }

        val records: Array[SortRecord^{sz}]^{sz} =
          SafeZoneAllocator.allocate(
            sz,
            new Array[SortRecord^{sz}](cfg.sortRecordsPerEpoch)
          )
        var i = 0
        while (i < records.length) {
          val seed = mix(currentEpoch * 1000003 + i * 97)
          val key = seed % cfg.keySpace
          val value = (mix(seed + 31337) & 0xffff) + 1
          records(i) =
            SafeZoneAllocator.allocate(sz, new SortRecord(key, value))
          i += 1
        }

        sortSafeRecords(records)
        consumeSafeRecords(records)
      }
      epoch += 1
    }
    val checksum = checksumLongs(groups)
    checksumSink = checksum
    checksum
  }

  def runSafeZoneTopWord(): Long = {
    val cfg = YakRegionConfig
    val globalCounts = new Array[Long](cfg.keySpace)
    val localCounts = new Array[Int](cfg.keySpace)
    val touchedKeys = new Array[Int](cfg.keySpace)
    var topChecksum = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      SafeZone { sz ?=>
        final class SZWordRecord(
            val key: Int,
            val weight: Int,
            val keep: Boolean,
            val next: SZWordRecord^{sz}
        )

        var records: SZWordRecord^{sz} = null
        var i = 0
        while (i < cfg.recordsPerEpoch) {
          val seed = mix(currentEpoch * 1000003 + i * 131)
          val key = seed % cfg.keySpace
          val weight = (mix(seed + 19) & 15) + 1
          val keep = ((seed ^ (seed >>> 3)) & 7) != 0
          records = SafeZoneAllocator.allocate(
            sz,
            new SZWordRecord(key, weight, keep, records)
          )
          i += 1
        }

        var touched = 0
        var current = records
        while (current != null) {
          if (current.keep) {
            if (localCounts(current.key) == 0) {
              touchedKeys(touched) = current.key
              touched += 1
            }
            localCounts(current.key) += current.weight
          }
          current = current.next
        }

        var bestKey = -1
        var bestCount = -1L
        var j = 0
        while (j < touched) {
          val key = touchedKeys(j)
          val count = localCounts(key).toLong
          globalCounts(key) = (globalCounts(key) + count) & 0xffffffffL
          if (count > bestCount || (count == bestCount && key < bestKey)) {
            bestCount = count
            bestKey = key
          }
          localCounts(key) = 0
          j += 1
        }
        topChecksum =
          (topChecksum * 1099511628211L) ^ bestKey.toLong ^ bestCount
      }
      epoch += 1
    }
    val checksum = checksumLongs(globalCounts) ^ topChecksum
    checksumSink = checksum
    checksum
  }

  def runSafeZoneGraphChi(): Long = {
    val cfg = YakRegionConfig
    val values = new Array[Long](cfg.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 71).toLong & 0xffffL
      vertex += 1
    }

    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      var subinterval = 0
      while (subinterval < cfg.graphChiSubintervals) {
        val currentSubinterval = subinterval
        SafeZone { sz ?=>
          final class SZEdgeUpdate(
              val src: Int,
              val dst: Int,
              val delta: Int,
              val next: SZEdgeUpdate^{sz}
          )

          val start =
            ((currentSubinterval.toLong * cfg.vertices.toLong) /
              cfg.graphChiSubintervals.toLong).toInt
          val end =
            (((currentSubinterval + 1).toLong * cfg.vertices.toLong) /
              cfg.graphChiSubintervals.toLong).toInt
          val width = math.max(1, end - start)

          var updates: SZEdgeUpdate^{sz} = null
          var edge = 0
          while (edge < cfg.graphChiEdgesPerSubinterval) {
            val seed =
              mix(currentEpoch * 1000003 + currentSubinterval * 9176 + edge * 37)
            val src = mix(seed + 11) % cfg.vertices
            val dst = start + (mix(seed + 23) % width)
            val delta = (mix(seed + currentEpoch + currentSubinterval) & 31) - 15
            updates = SafeZoneAllocator.allocate(
              sz,
              new SZEdgeUpdate(src, dst, delta, updates)
            )
            edge += 1
          }

          var current = updates
          while (current != null) {
            val contribution =
              (values(current.src) + current.delta.toLong + currentSubinterval) &
                0xffL
            values(current.dst) =
              (values(current.dst) + contribution) & 0xffffffffL
            current = current.next
          }
        }
        subinterval += 1
      }
      epoch += 1
    }
    val checksum = checksumLongs(values)
    checksumSink = checksum
    checksum
  }

  def runSafeZoneGraphReal(): Long = {
    val cfg = YakRegionConfig
    val input = RealGraphInput.load()
    val values = new Array[Long](input.vertices)
    var vertex = 0
    while (vertex < values.length) {
      values(vertex) = mix(vertex + 131).toLong & 0xffffL
      vertex += 1
    }

    var edgeCursor = 0
    var epoch = 0
    while (epoch < cfg.epochs) {
      val currentEpoch = epoch
      val currentEdgeCursor = edgeCursor
      SafeZone { sz ?=>
        final class SZEdgeUpdate(
            val src: Int,
            val dst: Int,
            val delta: Int,
            val next: SZEdgeUpdate^{sz}
        )

        var updates: SZEdgeUpdate^{sz} = null
        var edge = 0
        while (edge < cfg.graphInputEdgesPerEpoch) {
          val index = (currentEdgeCursor + edge) % input.edgeCount
          val src = input.srcs(index)
          val dst = input.dsts(index)
          val delta = ((src ^ dst ^ currentEpoch ^ edge) & 31) - 15
          updates = SafeZoneAllocator.allocate(
            sz,
            new SZEdgeUpdate(src, dst, delta, updates)
          )
          edge += 1
        }

        var current = updates
        while (current != null) {
          val contribution =
            (values(current.src) + current.delta.toLong + currentEpoch) & 0xffL
          values(current.dst) =
            (values(current.dst) + contribution) & 0xffffffffL
          current = current.next
        }
      }
      edgeCursor = (edgeCursor + cfg.graphInputEdgesPerEpoch) % input.edgeCount
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

  private def runWorkload(mode: String, workload: String): WorkloadResult =
    workload match {
      case "wordcount" =>
        val checksum =
          if (mode == "safezone") runSafeZoneWordCount()
          else if (mode == "checked-epoch-stream")
            runCheckedWordCountEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedWordCountEpoch(true)
          else runHeapOrRiftWordCount(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "graphstep" =>
        val checksum =
          if (mode == "safezone") runSafeZoneGraphStep()
          else if (mode == "checked-epoch-stream")
            runCheckedGraphStepEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedGraphStepEpoch(true)
          else runHeapOrRiftGraphStep(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "sort" =>
        val checksum =
          if (mode == "safezone") runSafeZoneSort()
          else if (mode == "checked-epoch-stream")
            runCheckedSortEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedSortEpoch(true)
          else runHeapOrRiftSort(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "topword" =>
        val checksum =
          if (mode == "safezone") runSafeZoneTopWord()
          else if (mode == "checked-epoch-stream")
            runCheckedTopWordEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedTopWordEpoch(true)
          else runHeapOrRiftTopWord(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "graphchi" =>
        val checksum =
          if (mode == "safezone") runSafeZoneGraphChi()
          else if (mode == "checked-epoch-stream")
            runCheckedGraphChiEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedGraphChiEpoch(true)
          else runHeapOrRiftGraphChi(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "graphreal" =>
        val checksum =
          if (mode == "safezone") runSafeZoneGraphReal()
          else if (
            mode == "checked-region-stream" ||
            mode == "checked-page-token-stream"
          ) runCheckedGraphReal(false)
          else if (
            mode == "checked-region-scoped" ||
            mode == "checked-page-token-scoped"
          ) runCheckedGraphReal(true)
          else if (mode == "checked-whole-run-stream")
            runCheckedGraphRealWholeRun(false)
          else if (mode == "checked-whole-run-scoped")
            runCheckedGraphRealWholeRun(true)
          else if (mode == "checked-epoch-stream")
            runCheckedGraphRealEpoch(false)
          else if (mode == "checked-epoch-scoped")
            runCheckedGraphRealEpoch(true)
          else if (mode == "checked-epoch-buffer-stream")
            runCheckedGraphRealEpochBuffer(false)
          else if (mode == "checked-epoch-buffer-scoped")
            runCheckedGraphRealEpochBuffer(true)
          else runHeapOrRiftGraphReal(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "promotion" =>
        if (mode == "safezone")
          throw new IllegalArgumentException(
            "Yak promotion workload is not defined for SafeZone; use heap, rift-hp, rift-streaming, or yak-runtime"
          )
        else runHeapOrRuntimePromotion(mode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount, graphstep, sort, topword, graphchi, graphreal, or promotion"
        )
    }

  private def logicalDataObjects(workload: String): Long = {
    val cfg = YakRegionConfig
    workload match {
      case "wordcount" => cfg.epochs.toLong * cfg.recordsPerEpoch.toLong
      case "graphstep" => cfg.epochs.toLong * cfg.messagesPerEpoch.toLong
      case "sort" => cfg.epochs.toLong * cfg.sortRecordsPerEpoch.toLong
      case "topword" => cfg.epochs.toLong * cfg.recordsPerEpoch.toLong
      case "graphchi" =>
        cfg.epochs.toLong * cfg.graphChiSubintervals.toLong *
          cfg.graphChiEdgesPerSubinterval.toLong
      case "graphreal" =>
        cfg.epochs.toLong * cfg.graphInputEdgesPerEpoch.toLong
      case "promotion" => cfg.epochs.toLong * cfg.recordsPerEpoch.toLong * 2L
      case _           => 0L
    }
  }

  private def controlSlots(workload: String): Long = {
    val cfg = YakRegionConfig
    workload match {
      case "wordcount" => cfg.keySpace.toLong
      case "graphstep" => cfg.vertices.toLong
      case "sort"      => cfg.keySpace.toLong
      case "topword"   => cfg.keySpace.toLong * 3L
      case "graphchi"  => cfg.vertices.toLong
      case "graphreal" => cfg.graphInputVertices.toLong
      case "promotion" => cfg.keySpace.toLong + cfg.scratchSlots.toLong
      case _           => 0L
    }
  }

  def runBenchmark(mode: String, workload: String): Unit = {
    val cfg = YakRegionConfig
    val usesRift =
      mode == "rift-hp" || mode == "rift-streaming" || mode == "yak-runtime" ||
        mode == "checked-region-stream" ||
        mode == "checked-page-token-stream" ||
        mode == "checked-whole-run-stream" ||
        mode == "checked-epoch-stream" ||
        mode == "checked-epoch-buffer-stream"
    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      while (run < cfg.benchmarkRuns) {
        val result = runWorkload(mode, workload)
        if (run == 0) checksum = result.checksum
        else if (result.checksum != checksum)
          throw new IllegalStateException(
            s"final-clean Yak mismatch workload=$workload mode=$mode first_checksum=$checksum actual=${result.checksum}"
          )
        run += 1
      }
      val dataObjects = logicalDataObjects(workload)
      val slots = controlSlots(workload)
      println(
        s"RESULT name=yak-$workload-$mode " +
          s"measurement_level=L1 final_clean=1 workload=$workload " +
          s"mode=$mode runs=${cfg.benchmarkRuns} " +
          s"logical_data_objects=$dataObjects control_slots=$slots " +
          s"checksum=$checksum"
      )
      return
    }

    val expected = runWorkload("heap", workload)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val result = runWorkload(mode, workload)
      if (result.checksum != expected.checksum)
        throw new IllegalStateException(
          s"warmup checksum mismatch workload=$workload mode=$mode expected=${expected.checksum} actual=${result.checksum}"
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
    val barrierChecks = new Array[Long](cfg.benchmarkRuns)
    val rememberedRefs = new Array[Long](cfg.benchmarkRuns)
    val promotedObjects = new Array[Long](cfg.benchmarkRuns)

    println(s"Running yak-$workload-$mode for ${cfg.benchmarkRuns} timed runs")
    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val result = runWorkload(mode, workload)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)

      if (result.checksum != expected.checksum)
        throw new IllegalStateException(
          s"checksum mismatch workload=$workload mode=$mode expected=${expected.checksum} actual=${result.checksum}"
        )

      elapsedMs(run) = (end - start) / 1000000.0
      gcNanos(run) = runtime.gcNanos
      riftOpNanos(run) = runtime.riftRegionOpNanos
      riftSlowNanos(run) = runtime.riftSlowAllocNanos
      riftObjects(run) = runtime.riftAllocObjectTotal
      riftOpens(run) = runtime.riftRegionOpenTotal
      riftCloses(run) = runtime.riftRegionCloseTotal
      riftResets(run) = runtime.riftRegionResetTotal
      barrierChecks(run) = result.barrierChecks
      rememberedRefs(run) = result.rememberedRefs
      promotedObjects(run) = result.promotedObjects
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f " +
          f"gc_collections=${runtime.gcCollections}%d " +
          f"gc_ms=${runtime.gcNanos / 1000000.0}%.3f " +
          f"rift_op_ms=${runtime.riftRegionOpNanos / 1000000.0}%.3f " +
          f"rift_slow_alloc_ms=${runtime.riftSlowAllocNanos / 1000000.0}%.3f " +
          f"rift_alloc_object_total=${runtime.riftAllocObjectTotal}%d " +
          f"yak_barrier_checks=${result.barrierChecks}%d " +
          f"yak_remembered_refs=${result.rememberedRefs}%d " +
          f"yak_promoted_objects=${result.promotedObjects}%d"
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
        f"median_yak_barrier_checks=${medianLong(barrierChecks)}%d " +
        f"median_yak_remembered_refs=${medianLong(rememberedRefs)}%d " +
        f"median_yak_promoted_objects=${medianLong(promotedObjects)}%d " +
        f"logical_data_objects=$dataObjects%d " +
        f"control_slots=$slots%d " +
        f"checksum=${expected.checksum}%d"
    )
  }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = YakRegionConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    println(
      s"CONFIG mode=$mode workload=$workload runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} records_per_epoch=${cfg.recordsPerEpoch} key_space=${cfg.keySpace} vertices=${cfg.vertices} messages_per_epoch=${cfg.messagesPerEpoch} sort_records_per_epoch=${cfg.sortRecordsPerEpoch} graphchi_subintervals=${cfg.graphChiSubintervals} graphchi_edges_per_subinterval=${cfg.graphChiEdgesPerSubinterval} graph_input='${cfg.graphInputPath}' graph_input_edges=${cfg.graphInputEdges} graph_input_vertices=${cfg.graphInputVertices} graph_input_edges_per_epoch=${cfg.graphInputEdgesPerEpoch} escape_modulo=${cfg.escapeModulo} scratch_slots=${cfg.scratchSlots} safezone_roots_mode=$rootsMode"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" | "yak-runtime" |
          "checked-region-stream" | "checked-region-scoped" |
          "checked-page-token-stream" | "checked-page-token-scoped" |
          "checked-whole-run-stream" | "checked-whole-run-scoped" |
          "checked-epoch-stream" | "checked-epoch-scoped" |
          "checked-epoch-buffer-stream" | "checked-epoch-buffer-scoped" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak mode '$other'; expected heap, safezone, rift-hp, rift-streaming, yak-runtime, checked page-token, checked whole-run, or checked epoch modes"
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
    mode == "rift-hp" || mode == "rift-streaming" || mode == "yak-runtime" ||
      mode == "checked-region-stream" ||
      mode == "checked-page-token-stream" ||
      mode == "checked-whole-run-stream" ||
      mode == "checked-epoch-stream" ||
      mode == "checked-epoch-buffer-stream"
  if (usesRift) RiftRegion.init(0)
  try {
    workload match {
      case "all" =>
        YakRegionMatrixHelpers.runBenchmark(mode, "wordcount")
        YakRegionMatrixHelpers.runBenchmark(mode, "graphstep")
        YakRegionMatrixHelpers.runBenchmark(mode, "sort")
        YakRegionMatrixHelpers.runBenchmark(mode, "topword")
        YakRegionMatrixHelpers.runBenchmark(mode, "graphchi")
      case "wordcount" | "graphstep" | "sort" | "topword" | "graphchi" |
          "graphreal" | "promotion" =>
        YakRegionMatrixHelpers.runBenchmark(mode, workload)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount, graphstep, sort, topword, graphchi, graphreal, promotion, or all"
        )
    }
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
