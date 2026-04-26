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
  val escapeModulo: Int = envInt("YAK_ESCAPE_MODULO", 1000)
  val scratchSlots: Int = envInt("YAK_SCRATCH_SLOTS", 128)
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
          else runHeapOrRiftWordCount(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "graphstep" =>
        val checksum =
          if (mode == "safezone") runSafeZoneGraphStep()
          else runHeapOrRiftGraphStep(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "sort" =>
        val checksum =
          if (mode == "safezone") runSafeZoneSort()
          else runHeapOrRiftSort(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "topword" =>
        val checksum =
          if (mode == "safezone") runSafeZoneTopWord()
          else runHeapOrRiftTopWord(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "graphchi" =>
        val checksum =
          if (mode == "safezone") runSafeZoneGraphChi()
          else runHeapOrRiftGraphChi(mode)
        new WorkloadResult(checksum, 0L, 0L, 0L)
      case "promotion" =>
        if (mode == "safezone")
          throw new IllegalArgumentException(
            "Yak promotion workload is not defined for SafeZone; use heap, rift-hp, rift-streaming, or yak-runtime"
          )
        else runHeapOrRuntimePromotion(mode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount, graphstep, sort, topword, graphchi, or promotion"
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
      case "promotion" => cfg.keySpace.toLong + cfg.scratchSlots.toLong
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
      s"CONFIG mode=$mode workload=$workload runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} records_per_epoch=${cfg.recordsPerEpoch} key_space=${cfg.keySpace} vertices=${cfg.vertices} messages_per_epoch=${cfg.messagesPerEpoch} sort_records_per_epoch=${cfg.sortRecordsPerEpoch} graphchi_subintervals=${cfg.graphChiSubintervals} graphchi_edges_per_subinterval=${cfg.graphChiEdgesPerSubinterval} escape_modulo=${cfg.escapeModulo} scratch_slots=${cfg.scratchSlots} safezone_roots_mode=$rootsMode"
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
        YakRegionMatrixHelpers.runBenchmark(mode, "sort")
        YakRegionMatrixHelpers.runBenchmark(mode, "topword")
        YakRegionMatrixHelpers.runBenchmark(mode, "graphchi")
      case "wordcount" | "graphstep" | "sort" | "topword" | "graphchi" |
          "promotion" =>
        YakRegionMatrixHelpers.runBenchmark(mode, workload)
      case other =>
        throw new IllegalArgumentException(
          s"unknown Yak workload '$other'; expected wordcount, graphstep, sort, topword, graphchi, promotion, or all"
        )
    }
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
