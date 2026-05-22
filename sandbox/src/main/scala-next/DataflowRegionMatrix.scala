import scala.language.experimental.captureChecking

import scala.scalanative.memory.{RiftOpenStreamingHandle, RiftRegion, SafeZone}
import scala.scalanative.memory.SafeZone._
import scala.scalanative.runtime.{
  fromRawUSize,
  GC,
  RawSize,
  RiftAllocator,
  SafeZoneAllocator
}

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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
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

  private final class CheckedSelectedNode(
      val docId: Int,
      val key: Int,
      val score: Long
  ) extends RiftRegion.StreamAppendNode

  final case class RuntimeSample(
      gcCollections: Long,
      gcNanos: Long,
      riftRegionOpenTotal: Long,
      riftRegionCloseTotal: Long,
      riftRegionResetTotal: Long,
      riftAllocObjectTotal: Long,
      riftAllocRawBytesTotal: Long,
      riftAllocSlowTotal: Long,
      riftMmapSlabTotal: Long,
      riftMmapBytesTotal: Long,
      riftTlsReuseTotal: Long,
      riftPoolReuseTotal: Long,
      riftRegionOpNanos: Long,
      riftSlowAllocNanos: Long
  )

  private object RuntimeSample {
    val zero: RuntimeSample =
      RuntimeSample(
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L
      )

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
          riftAllocRawBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocRawBytesTotal()),
          riftAllocSlowTotal =
            rawSizeToLong(RiftAllocator.Impl.statsAllocSlowTotal()),
          riftMmapSlabTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapSlabTotal()),
          riftMmapBytesTotal =
            rawSizeToLong(RiftAllocator.Impl.statsMmapBytesTotal()),
          riftTlsReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsTlsReuseTotal()),
          riftPoolReuseTotal =
            rawSizeToLong(RiftAllocator.Impl.statsPoolReuseTotal()),
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
        riftAllocRawBytesTotal =
          delta(end.riftAllocRawBytesTotal, start.riftAllocRawBytesTotal),
        riftAllocSlowTotal =
          delta(end.riftAllocSlowTotal, start.riftAllocSlowTotal),
        riftMmapSlabTotal =
          delta(end.riftMmapSlabTotal, start.riftMmapSlabTotal),
        riftMmapBytesTotal =
          delta(end.riftMmapBytesTotal, start.riftMmapBytesTotal),
        riftTlsReuseTotal =
          delta(end.riftTlsReuseTotal, start.riftTlsReuseTotal),
        riftPoolReuseTotal =
          delta(end.riftPoolReuseTotal, start.riftPoolReuseTotal),
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

  def runSafeZoneSelect(): Long = {
    val cfg = DataflowRegionConfig
    var total = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      total += SafeZone { sz ?=>
        final class SZDocument(
            val docId: Int,
            val key: Int,
            val authorKey: Int,
            val value: Int,
            val next: SZDocument^{sz}
        )
        final class SZSelectedRecord(
            val docId: Int,
            val key: Int,
            val score: Long,
            val next: SZSelectedRecord^{sz}
        )

        var docs: SZDocument^{sz} = null
        var i = 0
        while (i < cfg.docsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val author = mix(seed + 17) % cfg.authorKeySpace
          val value = mix(seed + 31) & 0xffff
          val docId = epoch * cfg.docsPerEpoch + i
          docs = SafeZoneAllocator.allocate(
            sz,
            new SZDocument(docId, key, author, value, docs)
          )
          i += 1
        }

        var selected: SZSelectedRecord^{sz} = null
        var cursor = docs
        while (cursor != null) {
          if ((cursor.value % cfg.selectModulo) == 0) {
            val score =
              cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
            selected = SafeZoneAllocator.allocate(
              sz,
              new SZSelectedRecord(cursor.docId, cursor.key, score, selected)
            )
          }
          cursor = cursor.next
        }

        var epochTotal = 0L
        var out = selected
        while (out != null) {
          epochTotal += out.score ^ out.docId.toLong ^ out.key.toLong
          out = out.next
        }
        epochTotal
      }
      epoch += 1
    }

    checksumSink = total
    total
  }

  def runSafeZoneAggregate(): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.keySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      total += SafeZone { sz ?=>
        final class SZDocument(
            val docId: Int,
            val key: Int,
            val authorKey: Int,
            val value: Int,
            val next: SZDocument^{sz}
        )
        final class SZAggregateEntry(
            val key: Int,
            var count: Int,
            var sum: Long,
            val next: SZAggregateEntry^{sz}
        )

        var docs: SZDocument^{sz} = null
        var i = 0
        while (i < cfg.docsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val author = mix(seed + 17) % cfg.authorKeySpace
          val value = mix(seed + 31) & 0xffff
          val docId = epoch * cfg.docsPerEpoch + i
          docs = SafeZoneAllocator.allocate(
            sz,
            new SZDocument(docId, key, author, value, docs)
          )
          i += 1
        }

        val table = SafeZoneAllocator.allocate(
          sz,
          new Array[SZAggregateEntry^{sz}](tableSize)
        )
        var cursor = docs
        while (cursor != null) {
          val key = cursor.key
          val bucket = mix(key) & tableMask
          var entry: SZAggregateEntry^{sz} = table(bucket)
          var found: SZAggregateEntry^{sz} = null
          while (entry != null && found == null) {
            if (entry.key == key) found = entry
            entry = entry.next
          }
          if (found == null) {
            found = SafeZoneAllocator.allocate(
              sz,
              new SZAggregateEntry(key, 0, 0L, table(bucket))
            )
            table(bucket) = found
          }
          found.count += 1
          found.sum += cursor.value.toLong
          cursor = cursor.next
        }

        var epochTotal = 0L
        i = 0
        while (i < table.length) {
          var entry: SZAggregateEntry^{sz} = table(i)
          while (entry != null) {
            epochTotal += entry.sum ^ (entry.count.toLong << 17) ^ entry.key.toLong
            entry = entry.next
          }
          i += 1
        }
        epochTotal
      }
      epoch += 1
    }

    checksumSink = total
    total
  }

  def runSafeZoneJoin(): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.authorKeySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L
    var epoch = 0
    while (epoch < cfg.epochs) {
      total += SafeZone { sz ?=>
        final class SZDocument(
            val docId: Int,
            val key: Int,
            val authorKey: Int,
            val value: Int,
            val next: SZDocument^{sz}
        )
        final class SZAuthorEntry(
            val authorKey: Int,
            val weight: Int,
            val next: SZAuthorEntry^{sz}
        )
        final class SZJoinedRecord(
            val docId: Int,
            val authorKey: Int,
            val score: Long,
            val next: SZJoinedRecord^{sz}
        )

        val authors = SafeZoneAllocator.allocate(
          sz,
          new Array[SZAuthorEntry^{sz}](tableSize)
        )
        var a = 0
        while (a < cfg.authorsPerEpoch) {
          val key = authorKey(epoch, a)
          val bucket = mix(key) & tableMask
          authors(bucket) = SafeZoneAllocator.allocate(
            sz,
            new SZAuthorEntry(key, (a + 1) * 7, authors(bucket))
          )
          a += 1
        }

        var docs: SZDocument^{sz} = null
        var i = 0
        while (i < cfg.docsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val author = mix(seed + 17) % cfg.authorKeySpace
          val value = mix(seed + 31) & 0xffff
          val docId = epoch * cfg.docsPerEpoch + i
          docs = SafeZoneAllocator.allocate(
            sz,
            new SZDocument(docId, key, author, value, docs)
          )
          i += 1
        }

        var joined: SZJoinedRecord^{sz} = null
        var cursor = docs
        while (cursor != null) {
          val bucket = mix(cursor.authorKey) & tableMask
          var author: SZAuthorEntry^{sz} = authors(bucket)
          while (author != null) {
            if (author.authorKey == cursor.authorKey) {
              val score = cursor.value.toLong * author.weight.toLong + cursor.key
              joined = SafeZoneAllocator.allocate(
                sz,
                new SZJoinedRecord(cursor.docId, cursor.authorKey, score, joined)
              )
            }
            author = author.next
          }
          cursor = cursor.next
        }

        var epochTotal = 0L
        var out = joined
        while (out != null) {
          epochTotal += out.score ^ out.docId.toLong ^ out.authorKey.toLong
          out = out.next
        }
        epochTotal
      }
      epoch += 1
    }

    checksumSink = total
    total
  }

  def runCheckedSelect(): Long = {
    val cfg = DataflowRegionConfig
    val total = RiftRegion.streaming { stream ?=>
      var total = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        total += RiftRegion.reset { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedSelectedRecord(
              val docId: Int,
              val key: Int,
              val score: Long
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(epoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = epoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.alloc(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          val selected =
            RiftRegion.regionBuffer[CheckedSelectedRecord](16)
          var cursor = docs
          while (cursor != null) {
            if ((cursor.value % cfg.selectModulo) == 0) {
              val score =
                cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
              val record: CheckedSelectedRecord^{region} =
                RiftRegion.alloc(
                  new CheckedSelectedRecord(cursor.docId, cursor.key, score)
                )
              region.append(selected, record)
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          i = 0
          while (i < region.length(selected)) {
            val out = region.get(selected, i)
            epochTotal += out.score ^ out.docId.toLong ^ out.key.toLong
            i += 1
          }
          epochTotal
        }
        epoch += 1
      }
      total
    }

    checksumSink = total
    total
  }

  private def runCheckedSelectPageTokenBody()(using
      stream: RiftRegion.StreamingRegion^
  ): Long = {
    val cfg = DataflowRegionConfig
    val window =
      RiftRegion.pageTokenMapFilter[CheckedSelectedNode](1L)
    var total = 0L

    def consume(
        bucket: RiftRegion.StreamBucket^{stream},
        cursor: RiftRegion.StreamAppendCursor[CheckedSelectedNode]^{stream}
    ): Unit =
      while (cursor.hasNext) {
        val out: CheckedSelectedNode^{stream} = cursor.next()
        total += out.score ^ out.docId.toLong ^ out.key.toLong
      }

    var epoch = 0
    while (epoch < cfg.epochs) {
      val region =
        RiftRegion.pageTokenMapFilterOpenRegionFor(
          stream,
          window,
          epoch.toLong,
          epoch.toLong
        )(consume)
      final class CheckedDocument(
          val docId: Int,
          val key: Int,
          val authorKey: Int,
          val value: Int,
          val next: CheckedDocument^{stream}
      )

      var docs: CheckedDocument^{stream} = null
      var i = 0
      while (i < cfg.docsPerEpoch) {
        val seed = mix(epoch * 1000003 + i)
        val key = seed % cfg.keySpace
        val author = mix(seed + 17) % cfg.authorKeySpace
        val value = mix(seed + 31) & 0xffff
        val docId = epoch * cfg.docsPerEpoch + i
        docs =
          RiftRegion.allocOpen(new CheckedDocument(docId, key, author, value, docs))(
            using region
          )
        i += 1
      }

      var cursor = docs
      while (cursor != null) {
        if ((cursor.value % cfg.selectModulo) == 0) {
          val score =
            cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
          val selected: CheckedSelectedNode^{stream} =
            RiftRegion.allocOpen(
              new CheckedSelectedNode(cursor.docId, cursor.key, score)
            )(using region)
          RiftRegion.emitPageTokenMapFilter(stream, window, selected)
        }
        cursor = cursor.next
      }
      epoch += 1
    }

    RiftRegion.closeAllPageTokenMapFilterBucketsWithCursor(stream, window)(
      consume
    )
    total
  }

  def runCheckedSelectPageToken(): Long = {
    val total = RiftRegion.streaming { stream ?=>
      runCheckedSelectPageTokenBody()
    }
    checksumSink = total
    total
  }

  def runCheckedSafeZoneSelectPageToken(): Long = {
    val total = RiftRegion.streamingSafeZone { stream ?=>
      runCheckedSelectPageTokenBody()
    }
    checksumSink = total
    total
  }

  def runCheckedAggregate(): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.keySpace * 2)
    val tableMask = tableSize - 1
    val total = RiftRegion.streaming { stream ?=>
      var total = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        total += RiftRegion.reset { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAggregateEntry(
              val key: Int,
              var count: Int,
              var sum: Long,
              var next: CheckedAggregateEntry^{region}
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(epoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = epoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.alloc(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          val table:
            Array[CheckedAggregateEntry^{region}]^{region} =
            RiftRegion.alloc(
              new Array[CheckedAggregateEntry^{region}](tableSize)
            )

          var cursor = docs
          while (cursor != null) {
            val key = cursor.key
            val bucket = mix(key) & tableMask
            var entry: CheckedAggregateEntry^{region} = table(bucket)
            var found: CheckedAggregateEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found =
                RiftRegion.alloc(
                  new CheckedAggregateEntry(key, 0, 0L, null)
                )
              found.next = table(bucket)
              table(bucket) = found
            }
            found.count += 1
            found.sum += cursor.value.toLong
            cursor = cursor.next
          }

          var epochTotal = 0L
          i = 0
          while (i < table.length) {
            var entry: CheckedAggregateEntry^{region} = table(i)
            while (entry != null) {
              epochTotal +=
                entry.sum ^ (entry.count.toLong << 17) ^ entry.key.toLong
              entry = entry.next
            }
            i += 1
          }
          epochTotal
        }
        epoch += 1
      }
      total
    }

    checksumSink = total
    total
  }

  def runCheckedAggregateEpochFold(): Long = {
    val cfg = DataflowRegionConfig
    val total = RiftRegion.streaming { stream ?=>
      final class CheckedAggregateEvent(
          val docId: Int,
          val key: Int,
          val value: Int
      ) extends RiftRegion.StreamAppendNode

      val fold =
        RiftRegion.epochFold[CheckedAggregateEvent](1L, cfg.keySpace)
      var total = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        val region =
          RiftRegion.epochFoldRegionFor(stream, fold, epoch.toLong)

        var i = 0
        while (i < cfg.docsPerEpoch) {
          val seed = mix(epoch * 1000003 + i)
          val key = seed % cfg.keySpace
          val value = mix(seed + 31) & 0xffff
          val docId = epoch * cfg.docsPerEpoch + i
          val event: CheckedAggregateEvent^{region} =
            new CheckedAggregateEvent(docId, key, value)
          val widened: CheckedAggregateEvent^{stream} = event
          RiftRegion.putEpochFold(stream, fold, key, value.toLong, widened)
          i += 1
        }

        var epochTotal = 0L
        RiftRegion.foreachEpochFoldEntry(stream, fold) { (key, sum, count) =>
          epochTotal += sum ^ (count.toLong << 17) ^ key.toLong
        }
        total += epochTotal

        RiftRegion.closeEpochFoldCurrentBucketAndClear(stream, fold) {
          (_, cursor) =>
            while (cursor.hasNext) cursor.next()
        }
        epoch += 1
      }
      total
    }

    checksumSink = total
    total
  }

  def runCheckedSelectEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = DataflowRegionConfig
    var total = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.epoch { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedSelectedRecord(
              val docId: Int,
              val key: Int,
              val score: Long,
              val next: CheckedSelectedRecord^{region}
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.allocOpen(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          var selected: CheckedSelectedRecord^{region} = null
          var cursor = docs
          while (cursor != null) {
            if ((cursor.value % cfg.selectModulo) == 0) {
              val score =
                cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
              selected =
                RiftRegion.allocOpen(
                  new CheckedSelectedRecord(
                    cursor.docId,
                    cursor.key,
                    score,
                    selected
                  )
                )
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          var out = selected
          while (out != null) {
            epochTotal += out.score ^ out.docId.toLong ^ out.key.toLong
            out = out.next
          }
          epochTotal
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    checksumSink = total
    total
  }

  def runCheckedAggregateEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.keySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.epoch { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAggregateEntry(
              val key: Int,
              var count: Int,
              var sum: Long,
              var next: CheckedAggregateEntry^{region}
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.allocOpen(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          val table: Array[CheckedAggregateEntry^{region}]^{region} =
            RiftRegion.allocOpen(
              new Array[CheckedAggregateEntry^{region}](tableSize)
            )
          var cursor = docs
          while (cursor != null) {
            val key = cursor.key
            val bucket = mix(key) & tableMask
            var entry: CheckedAggregateEntry^{region} = table(bucket)
            var found: CheckedAggregateEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found =
                RiftRegion.allocOpen(
                  new CheckedAggregateEntry(key, 0, 0L, null)
                )
              found.next = table(bucket)
              table(bucket) = found
            }
            found.count += 1
            found.sum += cursor.value.toLong
            cursor = cursor.next
          }

          var epochTotal = 0L
          i = 0
          while (i < table.length) {
            var entry: CheckedAggregateEntry^{region} = table(i)
            while (entry != null) {
              epochTotal +=
                entry.sum ^ (entry.count.toLong << 17) ^ entry.key.toLong
              entry = entry.next
            }
            i += 1
          }
          epochTotal
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    checksumSink = total
    total
  }

  private inline def runCheckedSelectEpochHandle(
      inline inferredAllocations: Boolean
  ): Long = {
    val cfg = DataflowRegionConfig
    var total = 0L

    RiftRegion.streamingOpenHandle {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.resetOpenHandle { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedSelectedRecord(
              val docId: Int,
              val key: Int,
              val score: Long,
              val next: CheckedSelectedRecord^{region}
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              inline if (inferredAllocations) then
                new CheckedDocument(docId, key, author, value, docs)
              else
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedDocument(docId, key, author, value, docs)
                )
            i += 1
          }

          var selected: CheckedSelectedRecord^{region} = null
          var cursor = docs
          while (cursor != null) {
            if ((cursor.value % cfg.selectModulo) == 0) {
              val score =
                cursor.value.toLong * 31L + cursor.key.toLong + cursor.authorKey
              selected =
                inline if (inferredAllocations) then
                  new CheckedSelectedRecord(
                    cursor.docId,
                    cursor.key,
                    score,
                    selected
                  )
                else
                  RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedSelectedRecord(
                      cursor.docId,
                      cursor.key,
                      score,
                      selected
                    )
                  )
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          var out = selected
          while (out != null) {
            epochTotal += out.score ^ out.docId.toLong ^ out.key.toLong
            out = out.next
          }
          epochTotal
        }
        epoch += 1
      }
    }

    checksumSink = total
    total
  }

  private inline def runCheckedAggregateEpochHandle(
      inline inferredAllocations: Boolean
  ): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.keySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L

    RiftRegion.streamingOpenHandle {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.resetOpenHandle { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAggregateEntry(
              val key: Int,
              var count: Int,
              var sum: Long,
              var next: CheckedAggregateEntry^{region}
          )

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              inline if (inferredAllocations) then
                new CheckedDocument(docId, key, author, value, docs)
              else
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedDocument(docId, key, author, value, docs)
                )
            i += 1
          }

          val table: Array[CheckedAggregateEntry^{region}]^{region} =
            inline if (inferredAllocations) then
              new Array[CheckedAggregateEntry^{region}](tableSize)
            else
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedAggregateEntry^{region}](tableSize)
              )
          var cursor = docs
          while (cursor != null) {
            val key = cursor.key
            val bucket = mix(key) & tableMask
            var entry: CheckedAggregateEntry^{region} = table(bucket)
            var found: CheckedAggregateEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found =
                inline if (inferredAllocations) then
                  new CheckedAggregateEntry(key, 0, 0L, null)
                else
                  RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedAggregateEntry(key, 0, 0L, null)
                  )
              found.next = table(bucket)
              table(bucket) = found
            }
            found.count += 1
            found.sum += cursor.value.toLong
            cursor = cursor.next
          }

          var epochTotal = 0L
          i = 0
          while (i < table.length) {
            var entry: CheckedAggregateEntry^{region} = table(i)
            while (entry != null) {
              epochTotal +=
                entry.sum ^ (entry.count.toLong << 17) ^ entry.key.toLong
              entry = entry.next
            }
            i += 1
          }
          epochTotal
        }
        epoch += 1
      }
    }

    checksumSink = total
    total
  }

  private inline def runCheckedJoinEpochHandle(
      inline inferredAllocations: Boolean
  ): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.authorKeySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L

    RiftRegion.streamingOpenHandle {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.resetOpenHandle { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAuthorEntry(
              val authorKey: Int,
              val weight: Int,
              var next: CheckedAuthorEntry^{region}
          )
          final class CheckedJoinedRecord(
              val docId: Int,
              val authorKey: Int,
              val score: Long,
              val next: CheckedJoinedRecord^{region}
          )

          val authors: Array[CheckedAuthorEntry^{region}]^{region} =
            inline if (inferredAllocations) then
              new Array[CheckedAuthorEntry^{region}](tableSize)
            else
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedAuthorEntry^{region}](tableSize)
              )
          var a = 0
          while (a < cfg.authorsPerEpoch) {
            val key = authorKey(currentEpoch, a)
            val bucket = mix(key) & tableMask
            val entry =
              inline if (inferredAllocations) then
                new CheckedAuthorEntry(key, (a + 1) * 7, null)
              else
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedAuthorEntry(key, (a + 1) * 7, null)
                )
            entry.next = authors(bucket)
            authors(bucket) = entry
            a += 1
          }

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              inline if (inferredAllocations) then
                new CheckedDocument(docId, key, author, value, docs)
              else
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedDocument(docId, key, author, value, docs)
                )
            i += 1
          }

          var joined: CheckedJoinedRecord^{region} = null
          var cursor = docs
          while (cursor != null) {
            val bucket = mix(cursor.authorKey) & tableMask
            var author: CheckedAuthorEntry^{region} = authors(bucket)
            while (author != null) {
              if (author.authorKey == cursor.authorKey) {
                val score =
                  cursor.value.toLong * author.weight.toLong + cursor.key
                joined =
                  inline if (inferredAllocations) then
                    new CheckedJoinedRecord(
                      cursor.docId,
                      cursor.authorKey,
                      score,
                      joined
                    )
                  else
                    RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedJoinedRecord(
                        cursor.docId,
                        cursor.authorKey,
                        score,
                        joined
                      )
                    )
              }
              author = author.next
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          var out = joined
          while (out != null) {
            epochTotal += out.score ^ out.docId.toLong ^ out.authorKey.toLong
            out = out.next
          }
          epochTotal
        }
        epoch += 1
      }
    }

    checksumSink = total
    total
  }

  def runCheckedJoin(): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.authorKeySpace * 2)
    val tableMask = tableSize - 1
    val total = RiftRegion.streaming { stream ?=>
      var total = 0L
      var epoch = 0
      while (epoch < cfg.epochs) {
        total += RiftRegion.reset { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAuthorEntry(
              val authorKey: Int,
              val weight: Int,
              var next: CheckedAuthorEntry^{region}
          )
          final class CheckedJoinedRecord(
              val docId: Int,
              val authorKey: Int,
              val score: Long
          )

          val authors:
            Array[CheckedAuthorEntry^{region}]^{region} =
            RiftRegion.alloc(
              new Array[CheckedAuthorEntry^{region}](tableSize)
            )
          var a = 0
          while (a < cfg.authorsPerEpoch) {
            val key = authorKey(epoch, a)
            val bucket = mix(key) & tableMask
            val entry: CheckedAuthorEntry^{region} =
              RiftRegion.alloc(
                new CheckedAuthorEntry(key, (a + 1) * 7, null)
              )
            entry.next = authors(bucket)
            authors(bucket) = entry
            a += 1
          }

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(epoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = epoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.alloc(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          val joined =
            RiftRegion.regionBuffer[CheckedJoinedRecord](16)
          var cursor = docs
          while (cursor != null) {
            val bucket = mix(cursor.authorKey) & tableMask
            var author: CheckedAuthorEntry^{region} = authors(bucket)
            while (author != null) {
              if (author.authorKey == cursor.authorKey) {
                val score =
                  cursor.value.toLong * author.weight.toLong + cursor.key
                val record: CheckedJoinedRecord^{region} =
                  RiftRegion.alloc(
                    new CheckedJoinedRecord(
                      cursor.docId,
                      cursor.authorKey,
                      score
                    )
                  )
                region.append(joined, record)
              }
              author = author.next
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          i = 0
          while (i < region.length(joined)) {
            val out = region.get(joined, i)
            epochTotal += out.score ^ out.docId.toLong ^ out.authorKey.toLong
            i += 1
          }
          epochTotal
        }
        epoch += 1
      }
      total
    }

    checksumSink = total
    total
  }

  def runCheckedJoinEpoch(safeZoneBackend: Boolean): Long = {
    val cfg = DataflowRegionConfig
    val tableSize = nextPowerOfTwo(cfg.authorKeySpace * 2)
    val tableMask = tableSize - 1
    var total = 0L

    def run()(using stream: RiftRegion.StreamingRegion^): Unit = {
      var epoch = 0
      while (epoch < cfg.epochs) {
        val currentEpoch = epoch
        total += RiftRegion.epoch { region ?=>
          final class CheckedDocument(
              val docId: Int,
              val key: Int,
              val authorKey: Int,
              val value: Int,
              val next: CheckedDocument^{region}
          )
          final class CheckedAuthorEntry(
              val authorKey: Int,
              val weight: Int,
              var next: CheckedAuthorEntry^{region}
          )
          final class CheckedJoinedRecord(
              val docId: Int,
              val authorKey: Int,
              val score: Long,
              val next: CheckedJoinedRecord^{region}
          )

          val authors: Array[CheckedAuthorEntry^{region}]^{region} =
            RiftRegion.allocOpen(
              new Array[CheckedAuthorEntry^{region}](tableSize)
            )
          var a = 0
          while (a < cfg.authorsPerEpoch) {
            val key = authorKey(currentEpoch, a)
            val bucket = mix(key) & tableMask
            val entry =
              RiftRegion.allocOpen(
                new CheckedAuthorEntry(key, (a + 1) * 7, null)
              )
            entry.next = authors(bucket)
            authors(bucket) = entry
            a += 1
          }

          var docs: CheckedDocument^{region} = null
          var i = 0
          while (i < cfg.docsPerEpoch) {
            val seed = mix(currentEpoch * 1000003 + i)
            val key = seed % cfg.keySpace
            val author = mix(seed + 17) % cfg.authorKeySpace
            val value = mix(seed + 31) & 0xffff
            val docId = currentEpoch * cfg.docsPerEpoch + i
            docs =
              RiftRegion.allocOpen(
                new CheckedDocument(docId, key, author, value, docs)
              )
            i += 1
          }

          var joined: CheckedJoinedRecord^{region} = null
          var cursor = docs
          while (cursor != null) {
            val bucket = mix(cursor.authorKey) & tableMask
            var author: CheckedAuthorEntry^{region} = authors(bucket)
            while (author != null) {
              if (author.authorKey == cursor.authorKey) {
                val score =
                  cursor.value.toLong * author.weight.toLong + cursor.key
                joined =
                  RiftRegion.allocOpen(
                    new CheckedJoinedRecord(
                      cursor.docId,
                      cursor.authorKey,
                      score,
                      joined
                    )
                  )
              }
              author = author.next
            }
            cursor = cursor.next
          }

          var epochTotal = 0L
          var out = joined
          while (out != null) {
            epochTotal += out.score ^ out.docId.toLong ^ out.authorKey.toLong
            out = out.next
          }
          epochTotal
        }
        epoch += 1
      }
    }

    if (safeZoneBackend) RiftRegion.streamingSafeZone { stream ?=> run() }
    else RiftRegion.streaming { stream ?=> run() }

    checksumSink = total
    total
  }

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

  def canonicalMode(mode: String): String =
    mode match {
      case "gc-heap" => "heap"
      case "region-scoped-rooted" | "region-scoped-rootless" => "safezone"
      case "region-stream-rootless" => "rift-streaming"
      case "checked-region-stream"  => "rift-checked"
      case "checked-page-token" | "checked-page-token-stream" =>
        "rift-checked-page-token"
      case "checked-page-token-scoped" | "checked-region-scoped-page-token" =>
        "rift-checked-safezone-page-token"
      case "checked-epoch-fold" | "checked-region-stream-epoch-fold" =>
        "rift-checked-epoch-fold"
      case "checked-epoch-stream" | "checked-region-stream-epoch" =>
        "rift-checked-direct-epoch"
      case "checked-epoch-stream-inferred" |
          "checked-region-stream-epoch-inferred" =>
        "rift-checked-direct-epoch-inferred"
      case "checked-epoch-stream-legacy" |
          "checked-region-stream-epoch-legacy" =>
        "rift-checked-direct-epoch-legacy"
      case "checked-epoch-stream-open-handle" |
          "checked-region-stream-epoch-open-handle" =>
        "rift-checked-direct-epoch-open-handle"
      case "checked-epoch-scoped" | "checked-region-scoped-epoch" =>
        "rift-checked-safezone-direct-epoch"
      case other => other
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

  private def runOperator(operator: String, mode: String): Long = {
    val internalMode = canonicalMode(mode)
    operator match {
      case "select" =>
        if (internalMode == "safezone") runSafeZoneSelect()
        else if (internalMode == "rift-checked") runCheckedSelect()
        else if (internalMode == "rift-checked-page-token")
          runCheckedSelectPageToken()
        else if (internalMode == "rift-checked-safezone-page-token")
          runCheckedSafeZoneSelectPageToken()
        else if (internalMode == "rift-checked-direct-epoch" ||
            internalMode == "rift-checked-direct-epoch-open-handle")
          runCheckedSelectEpochHandle(inferredAllocations = false)
        else if (internalMode == "rift-checked-direct-epoch-inferred")
          runCheckedSelectEpochHandle(inferredAllocations = true)
        else if (internalMode == "rift-checked-direct-epoch-legacy")
          runCheckedSelectEpoch(false)
        else if (internalMode == "rift-checked-safezone-direct-epoch")
          runCheckedSelectEpoch(true)
        else runSelect(internalMode)
      case "aggregate" =>
        if (internalMode == "safezone") runSafeZoneAggregate()
        else if (internalMode == "rift-checked") runCheckedAggregate()
        else if (internalMode == "rift-checked-epoch-fold")
          runCheckedAggregateEpochFold()
        else if (internalMode == "rift-checked-direct-epoch")
          runCheckedAggregateEpochHandle(inferredAllocations = false)
        else if (internalMode == "rift-checked-direct-epoch-open-handle")
          runCheckedAggregateEpochHandle(inferredAllocations = false)
        else if (internalMode == "rift-checked-direct-epoch-inferred")
          runCheckedAggregateEpochHandle(inferredAllocations = true)
        else if (internalMode == "rift-checked-direct-epoch-legacy")
          runCheckedAggregateEpoch(false)
        else if (internalMode == "rift-checked-safezone-direct-epoch")
          runCheckedAggregateEpoch(true)
        else runAggregate(internalMode)
      case "join" =>
        if (internalMode == "safezone") runSafeZoneJoin()
        else if (internalMode == "rift-checked") runCheckedJoin()
        else if (internalMode == "rift-checked-direct-epoch")
          runCheckedJoinEpochHandle(inferredAllocations = false)
        else if (internalMode == "rift-checked-direct-epoch-open-handle")
          runCheckedJoinEpochHandle(inferredAllocations = false)
        else if (internalMode == "rift-checked-direct-epoch-inferred")
          runCheckedJoinEpochHandle(inferredAllocations = true)
        else if (internalMode == "rift-checked-direct-epoch-legacy")
          runCheckedJoinEpoch(false)
        else if (internalMode == "rift-checked-safezone-direct-epoch")
          runCheckedJoinEpoch(true)
        else runJoin(internalMode)
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow operator '$other'; expected select, aggregate, join, or all"
        )
    }
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
    val internalMode = canonicalMode(mode)
    val usesRift =
        internalMode == "rift-hp" || internalMode == "rift-streaming" ||
        internalMode == "rift-checked" ||
        internalMode == "rift-checked-page-token" ||
        internalMode == "rift-checked-epoch-fold" ||
        internalMode == "rift-checked-direct-epoch" ||
        internalMode == "rift-checked-direct-epoch-inferred" ||
        internalMode == "rift-checked-direct-epoch-open-handle" ||
        internalMode == "rift-checked-direct-epoch-legacy"

    if (cfg.finalClean) {
      var run = 0
      var checksum = 0L
      while (run < cfg.benchmarkRuns) {
        val result = runOperator(operator, mode)
        if (run == 0) checksum = result
        else if (result != checksum)
          throw new IllegalStateException(
            s"final-clean dataflow mismatch operator=$operator mode=$mode first_checksum=$checksum actual=$result"
          )
        run += 1
      }
      println(
        s"RESULT name=dataflow-$operator-$mode " +
          s"measurement_level=L1 final_clean=1 operator=$operator " +
          s"mode=$mode backend_mode=$internalMode runs=${cfg.benchmarkRuns} " +
          s"epochs=${cfg.epochs} docs_per_epoch=${cfg.docsPerEpoch} " +
          s"checksum=$checksum"
      )
      return
    }

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
    val riftRawBytes = new Array[Long](cfg.benchmarkRuns)
    val riftSlowAllocs = new Array[Long](cfg.benchmarkRuns)
    val riftMmapSlabs = new Array[Long](cfg.benchmarkRuns)
    val riftMmapBytes = new Array[Long](cfg.benchmarkRuns)
    val riftTlsReuse = new Array[Long](cfg.benchmarkRuns)
    val riftPoolReuse = new Array[Long](cfg.benchmarkRuns)
    val riftSlowAllocNanos = new Array[Long](cfg.benchmarkRuns)
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
      riftRawBytes(run) = runtime.riftAllocRawBytesTotal
      riftSlowAllocs(run) = runtime.riftAllocSlowTotal
      riftMmapSlabs(run) = runtime.riftMmapSlabTotal
      riftMmapBytes(run) = runtime.riftMmapBytesTotal
      riftTlsReuse(run) = runtime.riftTlsReuseTotal
      riftPoolReuse(run) = runtime.riftPoolReuseTotal
      riftSlowAllocNanos(run) = runtime.riftSlowAllocNanos
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
    val medianRawBytes = medianLong(riftRawBytes)
    val medianSlowAllocs = medianLong(riftSlowAllocs)
    val medianMmapSlabs = medianLong(riftMmapSlabs)
    val medianMmapBytes = medianLong(riftMmapBytes)
    val medianTlsReuse = medianLong(riftTlsReuse)
    val medianPoolReuse = medianLong(riftPoolReuse)
    val medianSlowAllocNanos = medianLong(riftSlowAllocNanos)
    val medianOpens = medianLong(riftOpens)
    val medianCloses = medianLong(riftCloses)
    val medianResets = medianLong(riftResets)

    println(
      f"RESULT name=dataflow-$operator-$mode " +
        f"median_ms=$medianElapsed%.3f " +
        f"median_gc_ms=${medianGc / 1000000.0}%.3f " +
        f"median_rift_op_ms=${medianRiftOp / 1000000.0}%.3f " +
        f"median_rift_slow_alloc_ms=${medianSlowAllocNanos / 1000000.0}%.3f " +
        f"median_rift_alloc_object_total=$medianObjects%d " +
        f"median_rift_alloc_raw_bytes_total=$medianRawBytes%d " +
        f"median_rift_alloc_slow_total=$medianSlowAllocs%d " +
        f"median_rift_mmap_slab_total=$medianMmapSlabs%d " +
        f"median_rift_mmap_bytes_total=$medianMmapBytes%d " +
        f"median_rift_tls_reuse_total=$medianTlsReuse%d " +
        f"median_rift_pool_reuse_total=$medianPoolReuse%d " +
        f"median_rift_open_total=$medianOpens%d " +
        f"median_rift_close_total=$medianCloses%d " +
        f"median_rift_reset_total=$medianResets%d " +
        f"checksum=$expectedChecksum%d"
    )
  }

  def printConfig(mode: String, operator: String): Unit = {
    val cfg = DataflowRegionConfig
    val rootsMode = sys.env.getOrElse("SAFEZONE_ROOTS_MODE", "0")
    val pageSize = sys.env.getOrElse("SAFEZONE_PAGE_SIZE", "default")
    println(
      s"CONFIG mode=$mode backend_mode=${canonicalMode(mode)} operator=$operator runs=${cfg.benchmarkRuns} warmups=${cfg.warmupRuns} epochs=${cfg.epochs} docs_per_epoch=${cfg.docsPerEpoch} authors_per_epoch=${cfg.authorsPerEpoch} key_space=${cfg.keySpace} author_key_space=${cfg.authorKeySpace} select_modulo=${cfg.selectModulo} safezone_roots_mode=$rootsMode safezone_page_size=$pageSize"
    )
  }

  def validateMode(mode: String): Unit =
    canonicalMode(mode) match {
      case "heap" | "safezone" | "rift-hp" | "rift-streaming" |
          "rift-checked" | "rift-checked-page-token" |
          "rift-checked-safezone-page-token" | "rift-checked-epoch-fold" |
          "rift-checked-direct-epoch" |
          "rift-checked-direct-epoch-inferred" |
          "rift-checked-direct-epoch-open-handle" |
          "rift-checked-direct-epoch-legacy" |
          "rift-checked-safezone-direct-epoch" =>
        ()
      case other =>
        throw new IllegalArgumentException(
          s"unknown dataflow mode '$other'; expected heap, safezone, rift-hp, rift-streaming, rift-checked, rift-checked-page-token, rift-checked-safezone-page-token, or rift-checked-epoch-fold"
        )
    }

  def supportsOperator(mode: String, operator: String): Boolean =
    canonicalMode(mode) match {
      case "rift-checked-page-token" | "rift-checked-safezone-page-token" =>
        operator == "select"
      case "rift-checked-epoch-fold" =>
        operator == "aggregate"
      case "rift-checked-direct-epoch" | "rift-checked-direct-epoch-inferred" |
          "rift-checked-safezone-direct-epoch" =>
        operator == "select" || operator == "aggregate" || operator == "join"
      case "rift-checked-direct-epoch-open-handle" =>
        operator == "select" || operator == "aggregate" || operator == "join"
      case "rift-checked-direct-epoch-legacy" =>
        operator == "select" || operator == "aggregate" || operator == "join"
      case _ => true
    }
}

@main def DataflowRegionMatrix(
    mode: String = "heap",
    operator: String = "all"
): Unit = {
  DataflowRegionMatrixHelpers.validateMode(mode)
  DataflowRegionMatrixHelpers.printConfig(mode, operator)

  val internalMode = DataflowRegionMatrixHelpers.canonicalMode(mode)
  val usesRift =
    internalMode == "rift-hp" || internalMode == "rift-streaming" ||
      internalMode == "rift-checked" ||
      internalMode == "rift-checked-page-token" ||
      internalMode == "rift-checked-epoch-fold" ||
      internalMode == "rift-checked-direct-epoch" ||
      internalMode == "rift-checked-direct-epoch-inferred" ||
      internalMode == "rift-checked-direct-epoch-open-handle" ||
      internalMode == "rift-checked-direct-epoch-legacy"
  if (usesRift) RiftRegion.init(0)
  try {
    operator match {
      case "all" =>
        val operators = Array("select", "aggregate", "join")
        var i = 0
        while (i < operators.length) {
          val op = operators(i)
          if (DataflowRegionMatrixHelpers.supportsOperator(mode, op))
            DataflowRegionMatrixHelpers.runBenchmark(mode, op)
          i += 1
        }
      case "select" | "aggregate" | "join" =>
        if (!DataflowRegionMatrixHelpers.supportsOperator(mode, operator))
          throw new IllegalArgumentException(
            s"dataflow mode '$mode' does not support operator '$operator'"
          )
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
