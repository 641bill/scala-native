import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object DataflowRegionConfig {
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

  val epochs: Int = envInt("DATAFLOW_EPOCHS", 10)
  val docsPerEpoch: Int = envInt("DATAFLOW_DOCS_PER_EPOCH", 100000)
  val authorsPerEpoch: Int = envInt("DATAFLOW_AUTHORS_PER_EPOCH", 20)
  val keySpace: Int = envInt("DATAFLOW_KEY_SPACE", 65536)
  val authorKeySpace: Int = envInt("DATAFLOW_AUTHOR_KEY_SPACE", 256)
  val selectModulo: Int = envInt("DATAFLOW_SELECT_MODULO", 8)
  val warmupRuns: Int = envNonNegativeInt("DATAFLOW_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("DATAFLOW_BENCHMARK_RUNS", 3)
}

object DataflowRegionMatrixHelpers {
  @volatile private var checksumSink = 0L

  private final class Document(
      val docId: Int,
      val key: Int,
      val authorKey: Int,
      val value: Int,
      val next: Document
  )

  private final class SelectedRecord(
      val docId: Int,
      val key: Int,
      val score: Long,
      val next: SelectedRecord
  )

  private final class AggregateEntry(
      val key: Int,
      var count: Int,
      var sum: Long,
      val next: AggregateEntry
  )

  private final class AuthorEntry(
      val authorKey: Int,
      val weight: Int,
      val next: AuthorEntry
  )

  private final class JoinedRecord(
      val docId: Int,
      val authorKey: Int,
      val score: Long,
      val next: JoinedRecord
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

  private final class ModeState(val mode: String) {
    val usesRift: Boolean = mode == "rift-hp" || mode == "rift-streaming"
    private val streaming: Boolean = mode == "rift-streaming"
    private val kind: Int =
      if (streaming) RiftRegion.Streaming else RiftRegion.HPZone
    private var streamRegion: RiftRegion = null

    def beginEpoch(): RiftRegion =
      if (!usesRift) null
      else if (streaming) {
        if (streamRegion == null) streamRegion = RiftRegion.open(kind)
        else streamRegion.reset()
        streamRegion
      } else {
        RiftRegion.open(kind)
      }

    def endEpoch(region: RiftRegion): Unit =
      if (usesRift && !streaming) region.close()

    def finish(): Unit =
      if (streamRegion != null) {
        streamRegion.close()
        streamRegion = null
      }

    def allocDocument(
        region: RiftRegion,
        docId: Int,
        key: Int,
        authorKey: Int,
        value: Int,
        next: Document
    ): Document =
      if (usesRift) region.alloc(new Document(docId, key, authorKey, value, next))
      else new Document(docId, key, authorKey, value, next)

    def allocSelected(
        region: RiftRegion,
        docId: Int,
        key: Int,
        score: Long,
        next: SelectedRecord
    ): SelectedRecord =
      if (usesRift) region.alloc(new SelectedRecord(docId, key, score, next))
      else new SelectedRecord(docId, key, score, next)

    def allocAggregateEntry(
        region: RiftRegion,
        key: Int,
        next: AggregateEntry
    ): AggregateEntry =
      if (usesRift) region.alloc(new AggregateEntry(key, 0, 0L, next))
      else new AggregateEntry(key, 0, 0L, next)

    def allocAggregateTable(
        region: RiftRegion,
        size: Int
    ): Array[AggregateEntry] =
      if (usesRift) region.alloc(new Array[AggregateEntry](size))
      else new Array[AggregateEntry](size)

    def allocAuthorEntry(
        region: RiftRegion,
        authorKey: Int,
        weight: Int,
        next: AuthorEntry
    ): AuthorEntry =
      if (usesRift) region.alloc(new AuthorEntry(authorKey, weight, next))
      else new AuthorEntry(authorKey, weight, next)

    def allocAuthorTable(
        region: RiftRegion,
        size: Int
    ): Array[AuthorEntry] =
      if (usesRift) region.alloc(new Array[AuthorEntry](size))
      else new Array[AuthorEntry](size)

    def allocJoined(
        region: RiftRegion,
        docId: Int,
        authorKey: Int,
        score: Long,
        next: JoinedRecord
    ): JoinedRecord =
      if (usesRift) region.alloc(new JoinedRecord(docId, authorKey, score, next))
      else new JoinedRecord(docId, authorKey, score, next)
  }

  private def nextPowerOfTwo(value: Int): Int = {
    var n = 1
    while (n < value) n <<= 1
    n
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def makeDocuments(
      mode: ModeState,
      region: RiftRegion,
      epoch: Int
  ): Document = {
    val cfg = DataflowRegionConfig
    var head: Document = null
    var i = 0
    while (i < cfg.docsPerEpoch) {
      val seed = mix(epoch * 1000003 + i)
      val key = seed % cfg.keySpace
      val authorKey = mix(seed + 17) % cfg.authorKeySpace
      val value = mix(seed + 31) & 0xffff
      val docId = epoch * cfg.docsPerEpoch + i
      head = mode.allocDocument(region, docId, key, authorKey, value, head)
      i += 1
    }
    head
  }

  private def authorKey(epoch: Int, author: Int): Int =
    mix(epoch * 8191 + author * 131) % DataflowRegionConfig.authorKeySpace

  def runSelect(modeName: String): Long = {
    val cfg = DataflowRegionConfig
    val mode = new ModeState(modeName)
    var total = 0L
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var docs = makeDocuments(mode, region, epoch)
        var selected: SelectedRecord = null
        var cursor = docs
        while (cursor != null) {
          if ((cursor.value % cfg.selectModulo) == 0) {
            val score =
              cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
            selected =
              mode.allocSelected(region, cursor.docId, cursor.key, score, selected)
          }
          cursor = cursor.next
        }

        var out = selected
        while (out != null) {
          total += out.score ^ out.docId.toLong ^ out.key.toLong
          out = out.next
        }
        docs = null
        selected = null
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()

    checksumSink = total
    total
  }

  def runAggregate(modeName: String): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.keySpace * 2)
    val tableMask = tableSize - 1
    val mode = new ModeState(modeName)
    var total = 0L
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        var docs = makeDocuments(mode, region, epoch)
        val table = mode.allocAggregateTable(region, tableSize)

        var cursor = docs
        while (cursor != null) {
          val key = cursor.key
          val bucket = mix(key) & tableMask
          var entry = table(bucket)
          var found: AggregateEntry = null
          while (entry != null && found == null) {
            if (entry.key == key) found = entry
            entry = entry.next
          }
          if (found == null) {
            found = mode.allocAggregateEntry(region, key, table(bucket))
            table(bucket) = found
          }
          found.count += 1
          found.sum += cursor.value.toLong
          cursor = cursor.next
        }

        var i = 0
        while (i < table.length) {
          var entry = table(i)
          while (entry != null) {
            total += entry.sum ^ (entry.count.toLong << 17) ^ entry.key.toLong
            entry = entry.next
          }
          i += 1
        }
        docs = null
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()

    checksumSink = total
    total
  }

  def runJoin(modeName: String): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.authorKeySpace * 2)
    val tableMask = tableSize - 1
    val mode = new ModeState(modeName)
    var total = 0L
    var epoch = 0
    try {
      while (epoch < cfg.epochs) {
        val region = mode.beginEpoch()
        val authors = mode.allocAuthorTable(region, tableSize)
        var a = 0
        while (a < cfg.authorsPerEpoch) {
          val key = authorKey(epoch, a)
          val bucket = mix(key) & tableMask
          authors(bucket) =
            mode.allocAuthorEntry(region, key, (a + 1) * 7, authors(bucket))
          a += 1
        }

        var docs = makeDocuments(mode, region, epoch)
        var joined: JoinedRecord = null
        var cursor = docs
        while (cursor != null) {
          val bucket = mix(cursor.authorKey) & tableMask
          var author = authors(bucket)
          while (author != null) {
            if (author.authorKey == cursor.authorKey) {
              val score = cursor.value.toLong * author.weight.toLong + cursor.key
              joined =
                mode.allocJoined(region, cursor.docId, cursor.authorKey, score, joined)
            }
            author = author.next
          }
          cursor = cursor.next
        }

        var out = joined
        while (out != null) {
          total += out.score ^ out.docId.toLong ^ out.authorKey.toLong
          out = out.next
        }
        docs = null
        joined = null
        mode.endEpoch(region)
        epoch += 1
      }
    } finally mode.finish()

    checksumSink = total
    total
  }

  private def expected(operator: String): Long =
    operator match {
      case "select"    => runSelect("heap")
      case "aggregate" => runAggregate("heap")
      case "join"      => runJoin("heap")
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow operator '$other'; expected select, aggregate, join, or all"
        )
    }

  private def runOperator(operator: String, mode: String): Long =
    operator match {
      case "select"    => runSelect(mode)
      case "aggregate" => runAggregate(mode)
      case "join"      => runJoin(mode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow operator '$other'; expected select, aggregate, join, or all"
        )
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

  def runBenchmark(mode: String, operator: String): Unit = {
    val cfg = DataflowRegionConfig
    val usesRift = mode == "rift-hp" || mode == "rift-streaming"
    val expectedChecksum = expected(operator)

    var warmup = 0
    while (warmup < cfg.warmupRuns) {
      val checksum = runOperator(operator, mode)
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"warmup checksum mismatch operator=$operator mode=$mode expected=$expectedChecksum actual=$checksum"
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

    println(
      s"Running dataflow-$operator-$mode for ${cfg.benchmarkRuns} timed runs"
    )

    var run = 0
    while (run < cfg.benchmarkRuns) {
      val startRuntime = RuntimeSample.capture(usesRift)
      val start = System.nanoTime()
      val checksum = runOperator(operator, mode)
      val end = System.nanoTime()
      val endRuntime = RuntimeSample.capture(usesRift)
      val runtime = RuntimeSample.since(startRuntime, endRuntime)
      if (checksum != expectedChecksum)
        throw new IllegalStateException(
          s"checksum mismatch operator=$operator mode=$mode expected=$expectedChecksum actual=$checksum"
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
    val medianRiftOp = medianLong(riftOpNanos)
    val medianObjects = medianLong(riftObjects)
    val medianOpens = medianLong(riftOpens)
    val medianCloses = medianLong(riftCloses)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=dataflow-$operator-$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )
  }

  def printConfig(mode: String, operator: String): Unit = {
    val cfg = DataflowRegionConfig
    println(
      s"CONFIG mode=$mode operator=$operator runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} docs_per_epoch=${cfg.docsPerEpoch} authors_per_epoch=${cfg.authorsPerEpoch} key_space=${cfg.keySpace} author_key_space=${cfg.authorKeySpace} select_modulo=${cfg.selectModulo}"
    )
  }

  def validateMode(mode: String): Unit =
    mode match {
      case "heap" | "rift-hp" | "rift-streaming" => ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow mode '$other'; expected heap, rift-hp, or rift-streaming"
        )
    }
}

@main def DataflowRegionMatrix(
    mode: String = "heap",
    operator: String = "all"
): Unit = {
  DataflowRegionMatrixHelpers.validateMode(mode)
  DataflowRegionMatrixHelpers.printConfig(mode, operator)

  val usesRift = mode == "rift-hp" || mode == "rift-streaming"
  if (usesRift) RiftRegion.init(0)
  try {
    operator match {
      case "all" =>
        DataflowRegionMatrixHelpers.runBenchmark(mode, "select")
        DataflowRegionMatrixHelpers.runBenchmark(mode, "aggregate")
        DataflowRegionMatrixHelpers.runBenchmark(mode, "join")
      case "select" | "aggregate" | "join" =>
        DataflowRegionMatrixHelpers.runBenchmark(mode, operator)
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow operator '$other'; expected select, aggregate, join, or all"
        )
    }
  } finally {
    if (usesRift) RiftRegion.shutdown()
  }
}
