import scala.language.experimental.captureChecking

import java.io.{BufferedReader, FileReader}
import java.lang.Integer as JInteger
import java.util.HashSet as JHashSet

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object BroomRetainedDataflowConfig {
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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  val records: Int = envNonNegativeInt("BROOM_RECORDS", 1000000)
  val recordsPerTimestamp: Int =
    envInt("BROOM_RECORDS_PER_TIMESTAMP", 25000)
  val activeTimestamps: Int = envInt("BROOM_ACTIVE_TIMESTAMPS", 4)
  val keySpace: Int = envInt("BROOM_KEY_SPACE", 32768)
  val sampleEvery: Int = envInt("BROOM_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("BROOM_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("BROOM_BENCHMARK_RUNS", 3)
  val q17InputMode: String =
    sys.env.getOrElse("BROOM_Q17_INPUT_MODE", "generated")
      .toLowerCase
  val tpchPartInput: String = sys.env.getOrElse("BROOM_TPCH_PART_INPUT", "")
  val tpchLineItemInput: String =
    sys.env.getOrElse("BROOM_TPCH_LINEITEM_INPUT", "")
  val tpchBrand: String = sys.env.getOrElse("BROOM_TPCH_BRAND", "Brand#23")
  val tpchContainer: String =
    sys.env.getOrElse("BROOM_TPCH_CONTAINER", "MED BOX")
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env.get("RIFT_EVAL_MEASUREMENT_LEVEL").exists(_.equalsIgnoreCase("L1"))
}

object BroomRetainedDataflowMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var retainedSink = 0L

  private final class HeapEvent(
      val recordId: Int,
      val timestamp: Int,
      val key: Int,
      val value: Int,
      val hash: Long,
      var next: HeapEvent
  )

  private final class HeapAggregateEntry(
      val key: Int,
      var count: Int,
      var sum: Long,
      var next: HeapAggregateEntry
  )

  private final class HeapJoinRecord(
      val recordId: Int,
      val timestamp: Int,
      val key: Int,
      val value: Int,
      val hash: Long,
      var next: HeapJoinRecord
  )

  private final class HeapQ17Part(
      val key: Int,
      val brand: Int,
      val container: Int,
      val selected: Boolean
  )

  private final class HeapQ17LineItem(
      val recordId: Int,
      val timestamp: Int,
      val partKey: Int,
      val quantity: Int,
      val revenue: Long,
      val hash: Long,
      var next: HeapQ17LineItem
  )

  private final class HeapQ17PartEntry(
      val part: HeapQ17Part,
      var quantityCount: Int,
      var quantitySum: Long,
      var lineHead: HeapQ17LineItem,
      var next: HeapQ17PartEntry
  )

  final case class Outcome(
      checksum: Long,
      outputCount: Long,
      retainedObjectProxy: Long,
      regionFreedObjectProxy: Long,
      maxLiveObjectProxy: Long
  )

  private final case class FileGroupOutcome(
      outcome: Outcome,
      recordsRead: Int,
      eof: Boolean
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
    val zero: RuntimeSample = RuntimeSample(0L, 0L, 0L, 0L, 0L, 0L, 0L)

    private def rawSizeToLong(value: RawSize): Long =
      fromRawUSize(value).toLong

    private def nonNegative(value: Long): Long =
      if (value < 0L) 0L else value

    private def delta(end: Long, start: Long): Long =
      if (end >= 0L && start >= 0L && end >= start) end - start else 0L

    def capture(includeRift: Boolean): RuntimeSample = {
      val gcCollections = nonNegative(GC.getStatsCollectionTotal().toLong)
      val gcNanos = nonNegative(GC.getStatsCollectionDurationTotal().toLong)
      if (!includeRift) zero.copy(gcCollections = gcCollections, gcNanos = gcNanos)
      else
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

  private def nextPowerOfTwo(value: Int): Int = {
    var out = 1
    while (out < value) out <<= 1
    out
  }

  private def mix(value: Int): Int = {
    var x = value
    x ^= x << 13
    x ^= x >>> 17
    x ^= x << 5
    x & 0x7fffffff
  }

  private def fold(
      checksum: Long,
      kind: Int,
      timestamp: Int,
      key: Int,
      count: Int,
      sum: Long
  ): Long =
    (((checksum ^ kind.toLong) * 1099511628211L) ^
      timestamp.toLong ^
      (key.toLong << 21) ^
      (count.toLong << 7) ^
      sum)

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

  private def groupSlots(remaining: Int, cfg: BroomRetainedDataflowConfig.type): Int = {
    val timestampsLeft =
      (remaining + cfg.recordsPerTimestamp - 1) / cfg.recordsPerTimestamp
    math.max(1, math.min(cfg.activeTimestamps, timestampsLeft))
  }

  private def generatedKey(recordId: Int, timestamp: Int): Int =
    mix(recordId * 1103515245 + timestamp * 1009 + 17) %
      BroomRetainedDataflowConfig.keySpace

  private def generatedValue(recordId: Int, timestamp: Int): Int =
    (mix(recordId * 1664525 + timestamp * 9176 + 1013904223) & 0xffff) + 1

  private def generatedQ17PartKey(seed: Int, timestamp: Int): Int =
    mix(seed * 1103515245 + timestamp * 4099 + 31337) %
      BroomRetainedDataflowConfig.keySpace

  private def generatedQ17Quantity(
      recordId: Int,
      timestamp: Int,
      partKey: Int
  ): Int =
    (mix(recordId * 1664525 + timestamp * 97 + partKey * 31) % 50) + 1

  private def generatedQ17Revenue(
      recordId: Int,
      partKey: Int,
      quantity: Int
  ): Long =
    ((mix(recordId ^ (partKey * 1000003)) & 0xffff) + 100).toLong *
      quantity.toLong

  private def generatedQ17Brand(partKey: Int): Int =
    mix(partKey * 17 + 3) & 15

  private def generatedQ17Container(partKey: Int): Int =
    mix(partKey * 29 + 11) & 7

  private def selectedQ17Part(partKey: Int): Boolean =
    generatedQ17Brand(partKey) == 3 && generatedQ17Container(partKey) <= 3

  private def q17BelowAverageThreshold(quantity: Int, count: Int, sum: Long): Boolean =
    count > 0 && quantity.toLong * count.toLong * 5L < sum

  private def q17UsesTpchFileInput(): Boolean = {
    val mode = BroomRetainedDataflowConfig.q17InputMode
    mode == "tpch-file" || mode == "tpch" || mode == "file"
  }

  private def requirePath(path: String, name: String): String =
    if (path.nonEmpty) path
    else
      throw new IllegalArgumentException(
        s"$name must be set when BROOM_Q17_INPUT_MODE=tpch-file"
      )

  private def openInput(path: String, name: String): BufferedReader =
    new BufferedReader(new FileReader(requirePath(path, name)), 1 << 20)

  private def parseIntField(line: String, targetField: Int): Int = {
    val n = line.length
    var i = 0
    var field = 0
    while (field < targetField && i < n) {
      if (line.charAt(i) == '|') field += 1
      i += 1
    }
    var sign = 1
    if (i < n && line.charAt(i) == '-') {
      sign = -1
      i += 1
    }
    var value = 0
    while (i < n) {
      val ch = line.charAt(i)
      if (ch == '|' || ch == '.') return value * sign
      if (ch >= '0' && ch <= '9') value = value * 10 + (ch - '0')
      i += 1
    }
    value * sign
  }

  private def parseDecimal2Field(line: String, targetField: Int): Long = {
    val n = line.length
    var i = 0
    var field = 0
    while (field < targetField && i < n) {
      if (line.charAt(i) == '|') field += 1
      i += 1
    }
    var sign = 1L
    if (i < n && line.charAt(i) == '-') {
      sign = -1L
      i += 1
    }
    var whole = 0L
    var fraction = 0
    var fractionDigits = 0
    var afterDot = false
    while (i < n) {
      val ch = line.charAt(i)
      if (ch == '|') {
        i = n
      } else if (ch == '.') {
        afterDot = true
      } else if (ch >= '0' && ch <= '9') {
        val digit = ch - '0'
        if (afterDot) {
          if (fractionDigits < 2) {
            fraction = fraction * 10 + digit
            fractionDigits += 1
          }
        } else {
          whole = whole * 10L + digit.toLong
        }
      }
      i += 1
    }
    while (fractionDigits < 2) {
      fraction *= 10
      fractionDigits += 1
    }
    sign * (whole * 100L + fraction.toLong)
  }

  private def fieldEquals(line: String, targetField: Int, expected: String): Boolean = {
    val n = line.length
    var i = 0
    var field = 0
    while (field < targetField && i < n) {
      if (line.charAt(i) == '|') field += 1
      i += 1
    }
    val start = i
    while (i < n && line.charAt(i) != '|') i += 1
    val length = i - start
    length == expected.length &&
      line.regionMatches(start, expected, 0, expected.length)
  }

  private def loadTpchSelectedPartKeys(): JHashSet[JInteger] = {
    val cfg = BroomRetainedDataflowConfig
    val selected = new JHashSet[JInteger]()
    val reader = openInput(cfg.tpchPartInput, "BROOM_TPCH_PART_INPUT")
    try {
      var line = reader.readLine()
      while (line != null) {
        if (
          line.nonEmpty &&
            fieldEquals(line, 3, cfg.tpchBrand) &&
            fieldEquals(line, 6, cfg.tpchContainer)
        ) {
          selected.add(JInteger.valueOf(parseIntField(line, 0)))
        }
        line = reader.readLine()
      }
    } finally reader.close()
    selected
  }

  private def q17TpchTableSize(selectedPartCount: Int): Int = {
    val cfg = BroomRetainedDataflowConfig
    val selectedSized =
      if (selectedPartCount <= 0) 16
      else if (selectedPartCount > (1 << 27)) Int.MaxValue / 2
      else selectedPartCount * 4
    nextPowerOfTwo(math.max(16, math.max(cfg.keySpace * 2, selectedSized)))
  }

  private def tpchQ17Hash(
      recordId: Int,
      timestamp: Int,
      partKey: Int,
      quantity: Int
  ): Long =
    mix(
      recordId ^
        (timestamp * 65537) ^
        (partKey * 1000003) ^
        (quantity * 8191)
    ).toLong

  private def runHeapAggregate(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    while (processed < cfg.records) {
      val remaining = cfg.records - processed
      val slots = groupSlots(remaining, cfg)
      val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)
      val heads = new Array[HeapEvent](slots)
      val tails = new Array[HeapEvent](slots)
      val tables = new Array[HeapAggregateEntry](slots * tableSize)
      val counts = new Array[Int](slots)
      var liveObjects = 0L
      var local = 0

      while (local < groupRecords) {
        val slot = local % slots
        val timestamp = group * cfg.activeTimestamps + slot
        val recordId = processed + local
        val key = generatedKey(recordId, timestamp)
        val value = generatedValue(recordId, timestamp)
        val hash = mix(recordId ^ (timestamp * 65537)).toLong
        val event = new HeapEvent(recordId, timestamp, key, value, hash, heads(slot))
        if (heads(slot) == null) tails(slot) = event
        heads(slot) = event
        counts(slot) += 1
        retainedObjects += 1L
        liveObjects += 1L

        val bucket = slot * tableSize + (mix(key) & tableMask)
        var entry = tables(bucket)
        var found: HeapAggregateEntry = null
        while (entry != null && found == null) {
          if (entry.key == key) found = entry
          entry = entry.next
        }
        if (found == null) {
          found = new HeapAggregateEntry(key, 0, 0L, tables(bucket))
          tables(bucket) = found
          retainedObjects += 1L
          liveObjects += 1L
        }
        found.count += 1
        found.sum += value.toLong

        if (recordId % cfg.sampleEvery == 0)
          checksum = fold(checksum, 11, timestamp, key, 1, hash)
        local += 1
      }

      if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects

      var slot = 0
      while (slot < slots) {
        val timestamp = group * cfg.activeTimestamps + slot
        val head = heads(slot)
        val tail = tails(slot)
        if (head != null && tail != null)
          checksum =
            fold(checksum, 17, timestamp, head.key ^ tail.key, counts(slot), head.hash ^ tail.hash)
        var bucket = 0
        while (bucket < tableSize) {
          var entry = tables(slot * tableSize + bucket)
          while (entry != null) {
            checksum =
              fold(checksum, 23, timestamp, entry.key, entry.count, entry.sum)
            outputCount += 1L
            entry = entry.next
          }
          bucket += 1
        }
        slot += 1
      }

      processed += groupRecords
      group += 1
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, 0L, maxLiveObjects)
  }

  private def runCheckedAggregate(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingOpenHandle {
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.resetOpenHandle { region ?=>
          final class CheckedEvent(
              val recordId: Int,
              val timestamp: Int,
              val key: Int,
              val value: Int,
              val hash: Long
          ) {
            var next: CheckedEvent^{region} = null
          }

          final class CheckedAggregateEntry(
              val key: Int,
              var count: Int,
              var sum: Long
          ) {
            var next: CheckedAggregateEntry^{region} = null
          }

          val heads: Array[CheckedEvent^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedEvent^{region}](slots)
            )
          val tails: Array[CheckedEvent^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedEvent^{region}](slots)
            )
          val tables: Array[CheckedAggregateEntry^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedAggregateEntry^{region}](slots * tableSize)
            )
          val counts = new Array[Int](slots)
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 3L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val key = generatedKey(recordId, timestamp)
            val value = generatedValue(recordId, timestamp)
            val hash = mix(recordId ^ (timestamp * 65537)).toLong
            val event: CheckedEvent^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new CheckedEvent(recordId, timestamp, key, value, hash)
              )
            event.next = heads(slot)
            if (heads(slot) == null) tails(slot) = event
            heads(slot) = event
            counts(slot) += 1
            localRetained += 1L
            liveObjects += 1L

            val bucket = slot * tableSize + (mix(key) & tableMask)
            var entry: CheckedAggregateEntry^{region} = tables(bucket)
            var found: CheckedAggregateEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedAggregateEntry(key, 0, 0L)
                )
              found.next = tables(bucket)
              tables(bucket) = found
              localRetained += 1L
              liveObjects += 1L
            }
            found.count += 1
            found.sum += value.toLong

            if (recordId % cfg.sampleEvery == 0)
              localChecksum = fold(localChecksum, 11, timestamp, key, 1, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            val head = heads(slot)
            val tail = tails(slot)
            if (head != null && tail != null)
              localChecksum =
                fold(localChecksum, 17, timestamp, head.key ^ tail.key, counts(slot), head.hash ^ tail.hash)
            var bucket = 0
            while (bucket < tableSize) {
              var entry: CheckedAggregateEntry^{region} = tables(slot * tableSize + bucket)
              while (entry != null) {
                localChecksum =
                  fold(localChecksum, 23, timestamp, entry.key, entry.count, entry.sum)
                localOutput += 1L
                entry = entry.next
              }
              bucket += 1
            }
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 3L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runHeapJoin(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    while (processed < cfg.records) {
      val remaining = cfg.records - processed
      val slots = groupSlots(remaining, cfg)
      val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)
      val left = new Array[HeapJoinRecord](slots * tableSize)
      val right = new Array[HeapJoinRecord](slots * tableSize)
      var liveObjects = 0L
      var local = 0

      while (local < groupRecords) {
        val slot = local % slots
        val timestamp = group * cfg.activeTimestamps + slot
        val recordId = processed + local
        val ordinal = local / slots
        val side = ordinal & 1
        val key = generatedKey(ordinal / 2, timestamp)
        val value = generatedValue(recordId, timestamp)
        val hash = mix(recordId ^ (key * 1000003) ^ (side * 17)).toLong
        val bucket = slot * tableSize + (mix(key) & tableMask)
        if (side == 0) {
          var cursor = right(bucket)
          while (cursor != null) {
            if (cursor.key == key) {
              checksum = fold(checksum, 31, timestamp, key, 1, hash ^ cursor.hash)
              outputCount += 1L
            }
            cursor = cursor.next
          }
          left(bucket) = new HeapJoinRecord(recordId, timestamp, key, value, hash, left(bucket))
        } else {
          var cursor = left(bucket)
          while (cursor != null) {
            if (cursor.key == key) {
              checksum = fold(checksum, 37, timestamp, key, 1, hash ^ cursor.hash)
              outputCount += 1L
            }
            cursor = cursor.next
          }
          right(bucket) = new HeapJoinRecord(recordId, timestamp, key, value, hash, right(bucket))
        }
        retainedObjects += 1L
        liveObjects += 1L
        if (recordId % cfg.sampleEvery == 0)
          checksum = fold(checksum, 41, timestamp, key, side, hash)
        local += 1
      }

      if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects

      var slot = 0
      while (slot < slots) {
        val timestamp = group * cfg.activeTimestamps + slot
        var bucket = 0
        var leftCount = 0
        var rightCount = 0
        while (bucket < tableSize) {
          var l = left(slot * tableSize + bucket)
          while (l != null) {
            leftCount += 1
            l = l.next
          }
          var r = right(slot * tableSize + bucket)
          while (r != null) {
            rightCount += 1
            r = r.next
          }
          bucket += 1
        }
        checksum = fold(checksum, 43, timestamp, leftCount, rightCount, leftCount.toLong + rightCount)
        slot += 1
      }

      processed += groupRecords
      group += 1
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, 0L, maxLiveObjects)
  }

  private def runHeapQ17(): Outcome = {
    if (q17UsesTpchFileInput()) return runHeapTpchQ17()

    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    while (processed < cfg.records) {
      val remaining = cfg.records - processed
      val slots = groupSlots(remaining, cfg)
      val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)
      val tables = new Array[HeapQ17PartEntry](slots * tableSize)
      var liveObjects = 0L
      var local = 0

      while (local < groupRecords) {
        val slot = local % slots
        val timestamp = group * cfg.activeTimestamps + slot
        val recordId = processed + local
        val ordinal = local / slots
        val partKey = generatedQ17PartKey(ordinal / 4, timestamp)
        val quantity = generatedQ17Quantity(recordId, timestamp, partKey)
        val revenue = generatedQ17Revenue(recordId, partKey, quantity)
        val hash =
          mix(recordId ^ (timestamp * 65537) ^ (partKey * 1000003)).toLong
        val bucket = slot * tableSize + (mix(partKey) & tableMask)
        var entry = tables(bucket)
        var found: HeapQ17PartEntry = null
        while (entry != null && found == null) {
          if (entry.part.key == partKey) found = entry
          entry = entry.next
        }
        if (found == null) {
          val part =
            new HeapQ17Part(
              partKey,
              generatedQ17Brand(partKey),
              generatedQ17Container(partKey),
              selectedQ17Part(partKey)
            )
          found = new HeapQ17PartEntry(part, 0, 0L, null, tables(bucket))
          tables(bucket) = found
          retainedObjects += 2L
          liveObjects += 2L
        }
        val line =
          new HeapQ17LineItem(
            recordId,
            timestamp,
            partKey,
            quantity,
            revenue,
            hash,
            found.lineHead
          )
        found.lineHead = line
        found.quantityCount += 1
        found.quantitySum += quantity.toLong
        retainedObjects += 1L
        liveObjects += 1L
        if (recordId % cfg.sampleEvery == 0)
          checksum = fold(checksum, 51, timestamp, partKey, quantity, hash)
        local += 1
      }

      if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects

      var slot = 0
      while (slot < slots) {
        val timestamp = group * cfg.activeTimestamps + slot
        var bucket = 0
        while (bucket < tableSize) {
          var entry = tables(slot * tableSize + bucket)
          while (entry != null) {
            var selectedCount = 0
            var selectedRevenue = 0L
            var line = entry.lineHead
            while (line != null) {
              if (
                entry.part.selected &&
                  q17BelowAverageThreshold(
                    line.quantity,
                    entry.quantityCount,
                    entry.quantitySum
                  )
              ) {
                selectedCount += 1
                selectedRevenue += line.revenue
                outputCount += 1L
                checksum =
                  fold(
                    checksum,
                    53,
                    timestamp,
                    entry.part.key,
                    line.quantity,
                    line.revenue ^ entry.quantitySum
                  )
              }
              line = line.next
            }
            checksum =
              fold(
                checksum,
                59,
                timestamp,
                entry.part.key,
                selectedCount,
                selectedRevenue + entry.quantitySum
              )
            entry = entry.next
          }
          bucket += 1
        }
        slot += 1
      }

      processed += groupRecords
      group += 1
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, 0L, maxLiveObjects)
  }

  private def runHeapTpchQ17(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val selectedPartKeys = loadTpchSelectedPartKeys()
    val tableSize = q17TpchTableSize(selectedPartKeys.size())
    val tableMask = tableSize - 1
    val reader = openInput(cfg.tpchLineItemInput, "BROOM_TPCH_LINEITEM_INPUT")
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var eof = false

    try {
      while (processed < cfg.records && !eof) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)
        val tables = new Array[HeapQ17PartEntry](slots * tableSize)
        var liveObjects = 0L
        var local = 0

        while (local < groupRecords && !eof) {
          val line = reader.readLine()
          if (line == null) eof = true
          else {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed
            val partKey = parseIntField(line, 1)
            val quantity = parseDecimal2Field(line, 4).toInt
            val revenue = parseDecimal2Field(line, 5)
            val hash = tpchQ17Hash(recordId, timestamp, partKey, quantity)

            if (recordId % cfg.sampleEvery == 0)
              checksum = fold(checksum, 51, timestamp, partKey, quantity, hash)

            if (selectedPartKeys.contains(JInteger.valueOf(partKey))) {
              val bucket = slot * tableSize + (mix(partKey) & tableMask)
              var entry = tables(bucket)
              var found: HeapQ17PartEntry = null
              while (entry != null && found == null) {
                if (entry.part.key == partKey) found = entry
                entry = entry.next
              }
              if (found == null) {
                val part =
                  new HeapQ17Part(partKey, 0, 0, selected = true)
                found = new HeapQ17PartEntry(part, 0, 0L, null, tables(bucket))
                tables(bucket) = found
                retainedObjects += 2L
                liveObjects += 2L
              }
              val lineItem =
                new HeapQ17LineItem(
                  recordId,
                  timestamp,
                  partKey,
                  quantity,
                  revenue,
                  hash,
                  found.lineHead
                )
              found.lineHead = lineItem
              found.quantityCount += 1
              found.quantitySum += quantity.toLong
              retainedObjects += 1L
              liveObjects += 1L
            }

            processed += 1
            local += 1
          }
        }

        if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects

        var slot = 0
        while (slot < slots) {
          val timestamp = group * cfg.activeTimestamps + slot
          var bucket = 0
          while (bucket < tableSize) {
            var entry = tables(slot * tableSize + bucket)
            while (entry != null) {
              var selectedCount = 0
              var selectedRevenue = 0L
              var line = entry.lineHead
              while (line != null) {
                if (
                  entry.part.selected &&
                    q17BelowAverageThreshold(
                      line.quantity,
                      entry.quantityCount,
                      entry.quantitySum
                    )
                ) {
                  selectedCount += 1
                  selectedRevenue += line.revenue
                  outputCount += 1L
                  checksum =
                    fold(
                      checksum,
                      53,
                      timestamp,
                      entry.part.key,
                      line.quantity,
                      line.revenue ^ entry.quantitySum
                    )
                }
                line = line.next
              }
              checksum =
                fold(
                  checksum,
                  59,
                  timestamp,
                  entry.part.key,
                  selectedCount,
                  selectedRevenue + entry.quantitySum
                )
              entry = entry.next
            }
            bucket += 1
          }
          slot += 1
        }

        group += 1
      }
    } finally reader.close()

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, 0L, maxLiveObjects)
  }

  private def runCheckedJoin(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingOpenHandle {
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.resetOpenHandle { region ?=>
          final class CheckedJoinRecord(
              val recordId: Int,
              val timestamp: Int,
              val key: Int,
              val value: Int,
              val hash: Long
          ) {
            var next: CheckedJoinRecord^{region} = null
          }

          val left: Array[CheckedJoinRecord^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedJoinRecord^{region}](slots * tableSize)
            )
          val right: Array[CheckedJoinRecord^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedJoinRecord^{region}](slots * tableSize)
            )
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 2L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val ordinal = local / slots
            val side = ordinal & 1
            val key = generatedKey(ordinal / 2, timestamp)
            val value = generatedValue(recordId, timestamp)
            val hash = mix(recordId ^ (key * 1000003) ^ (side * 17)).toLong
            val bucket = slot * tableSize + (mix(key) & tableMask)
            if (side == 0) {
              val oldLeft: CheckedJoinRecord^{region} = left(bucket)
              var cursor: CheckedJoinRecord^{region} = right(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  localChecksum =
                    fold(localChecksum, 31, timestamp, key, 1, hash ^ cursor.hash)
                  localOutput += 1L
                }
                cursor = cursor.next
              }
              left(bucket) =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedJoinRecord(recordId, timestamp, key, value, hash)
                )
              left(bucket).next = oldLeft
            } else {
              val oldRight: CheckedJoinRecord^{region} = right(bucket)
              var cursor: CheckedJoinRecord^{region} = left(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  localChecksum =
                    fold(localChecksum, 37, timestamp, key, 1, hash ^ cursor.hash)
                  localOutput += 1L
                }
                cursor = cursor.next
              }
              right(bucket) =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedJoinRecord(recordId, timestamp, key, value, hash)
                )
              right(bucket).next = oldRight
            }
            localRetained += 1L
            liveObjects += 1L
            if (recordId % cfg.sampleEvery == 0)
              localChecksum = fold(localChecksum, 41, timestamp, key, side, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            var bucket = 0
            var leftCount = 0
            var rightCount = 0
            while (bucket < tableSize) {
              var l: CheckedJoinRecord^{region} = left(slot * tableSize + bucket)
              while (l != null) {
                leftCount += 1
                l = l.next
              }
              var r: CheckedJoinRecord^{region} = right(slot * tableSize + bucket)
              while (r != null) {
                rightCount += 1
                r = r.next
              }
              bucket += 1
            }
            localChecksum =
              fold(localChecksum, 43, timestamp, leftCount, rightCount, leftCount.toLong + rightCount)
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 2L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedQ17(): Outcome = {
    if (q17UsesTpchFileInput()) return runCheckedTpchQ17()

    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingOpenHandle {
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.resetOpenHandle { region ?=>
          final class CheckedQ17Part(
              val key: Int,
              val brand: Int,
              val container: Int,
              val selected: Boolean
          )

          final class CheckedQ17LineItem(
              val recordId: Int,
              val timestamp: Int,
              val partKey: Int,
              val quantity: Int,
              val revenue: Long,
              val hash: Long
          ) {
            var next: CheckedQ17LineItem^{region} = null
          }

          final class CheckedQ17PartEntry(
              val part: CheckedQ17Part^{region},
              var quantityCount: Int,
              var quantitySum: Long
          ) {
            var lineHead: CheckedQ17LineItem^{region} = null
            var next: CheckedQ17PartEntry^{region} = null
          }

          val tables: Array[CheckedQ17PartEntry^{region}]^{region} =
            RiftAllocator.allocateOpenHandle(
              region,
              new Array[CheckedQ17PartEntry^{region}](slots * tableSize)
            )
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 1L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val ordinal = local / slots
            val partKey = generatedQ17PartKey(ordinal / 4, timestamp)
            val quantity = generatedQ17Quantity(recordId, timestamp, partKey)
            val revenue = generatedQ17Revenue(recordId, partKey, quantity)
            val hash =
              mix(recordId ^ (timestamp * 65537) ^ (partKey * 1000003)).toLong
            val bucket = slot * tableSize + (mix(partKey) & tableMask)
            var entry: CheckedQ17PartEntry^{region} = tables(bucket)
            var found: CheckedQ17PartEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.part.key == partKey) found = entry
              entry = entry.next
            }
            if (found == null) {
              val part: CheckedQ17Part^{region} =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedQ17Part(
                    partKey,
                    generatedQ17Brand(partKey),
                    generatedQ17Container(partKey),
                    selectedQ17Part(partKey)
                  )
                )
              found =
                RiftAllocator.allocateOpenHandle(
                  region,
                  new CheckedQ17PartEntry(part, 0, 0L)
                )
              found.next = tables(bucket)
              tables(bucket) = found
              localRetained += 2L
              liveObjects += 2L
            }
            val line: CheckedQ17LineItem^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new CheckedQ17LineItem(
                  recordId,
                  timestamp,
                  partKey,
                  quantity,
                  revenue,
                  hash
                )
              )
            line.next = found.lineHead
            found.lineHead = line
            found.quantityCount += 1
            found.quantitySum += quantity.toLong
            localRetained += 1L
            liveObjects += 1L
            if (recordId % cfg.sampleEvery == 0)
              localChecksum =
                fold(localChecksum, 51, timestamp, partKey, quantity, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            var bucket = 0
            while (bucket < tableSize) {
              var entry: CheckedQ17PartEntry^{region} =
                tables(slot * tableSize + bucket)
              while (entry != null) {
                var selectedCount = 0
                var selectedRevenue = 0L
                var line: CheckedQ17LineItem^{region} = entry.lineHead
                while (line != null) {
                  if (
                    entry.part.selected &&
                      q17BelowAverageThreshold(
                        line.quantity,
                        entry.quantityCount,
                        entry.quantitySum
                      )
                  ) {
                    selectedCount += 1
                    selectedRevenue += line.revenue
                    localOutput += 1L
                    localChecksum =
                      fold(
                        localChecksum,
                        53,
                        timestamp,
                        entry.part.key,
                        line.quantity,
                        line.revenue ^ entry.quantitySum
                      )
                  }
                  line = line.next
                }
                localChecksum =
                  fold(
                    localChecksum,
                    59,
                    timestamp,
                    entry.part.key,
                    selectedCount,
                    selectedRevenue + entry.quantitySum
                  )
                entry = entry.next
              }
              bucket += 1
            }
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 1L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedTpchQ17(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val selectedPartKeys = loadTpchSelectedPartKeys()
    val tableSize = q17TpchTableSize(selectedPartKeys.size())
    val tableMask = tableSize - 1
    val reader = openInput(cfg.tpchLineItemInput, "BROOM_TPCH_LINEITEM_INPUT")
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var eof = false

    try {
      RiftRegion.streamingOpenHandle {
        while (processed < cfg.records && !eof) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

          val fileGroup = RiftRegion.resetOpenHandle { region ?=>
            final class CheckedQ17Part(
                val key: Int,
                val brand: Int,
                val container: Int,
                val selected: Boolean
            )

            final class CheckedQ17LineItem(
                val recordId: Int,
                val timestamp: Int,
                val partKey: Int,
                val quantity: Int,
                val revenue: Long,
                val hash: Long
            ) {
              var next: CheckedQ17LineItem^{region} = null
            }

            final class CheckedQ17PartEntry(
                val part: CheckedQ17Part^{region},
                var quantityCount: Int,
                var quantitySum: Long
            ) {
              var lineHead: CheckedQ17LineItem^{region} = null
              var next: CheckedQ17PartEntry^{region} = null
            }

            val tables: Array[CheckedQ17PartEntry^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedQ17PartEntry^{region}](slots * tableSize)
              )
            var localChecksum = checksum
            var localOutput = 0L
            var localRetained = 0L
            var liveObjects = 1L
            var local = 0
            var localEof = false

            while (local < groupRecords && !localEof) {
              val line = reader.readLine()
              if (line == null) localEof = true
              else {
                val slot = local % slots
                val timestamp = group * cfg.activeTimestamps + slot
                val recordId = processed + local
                val partKey = parseIntField(line, 1)
                val quantity = parseDecimal2Field(line, 4).toInt
                val revenue = parseDecimal2Field(line, 5)
                val hash = tpchQ17Hash(recordId, timestamp, partKey, quantity)

                if (recordId % cfg.sampleEvery == 0)
                  localChecksum =
                    fold(localChecksum, 51, timestamp, partKey, quantity, hash)

                if (selectedPartKeys.contains(JInteger.valueOf(partKey))) {
                  val bucket = slot * tableSize + (mix(partKey) & tableMask)
                  var entry: CheckedQ17PartEntry^{region} = tables(bucket)
                  var found: CheckedQ17PartEntry^{region} = null
                  while (entry != null && found == null) {
                    if (entry.part.key == partKey) found = entry
                    entry = entry.next
                  }
                  if (found == null) {
                    val part: CheckedQ17Part^{region} =
                      RiftAllocator.allocateOpenHandle(
                        region,
                        new CheckedQ17Part(partKey, 0, 0, selected = true)
                      )
                    found =
                      RiftAllocator.allocateOpenHandle(
                        region,
                        new CheckedQ17PartEntry(part, 0, 0L)
                      )
                    found.next = tables(bucket)
                    tables(bucket) = found
                    localRetained += 2L
                    liveObjects += 2L
                  }
                  val lineItem: CheckedQ17LineItem^{region} =
                    RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedQ17LineItem(
                        recordId,
                        timestamp,
                        partKey,
                        quantity,
                        revenue,
                        hash
                      )
                    )
                  lineItem.next = found.lineHead
                  found.lineHead = lineItem
                  found.quantityCount += 1
                  found.quantitySum += quantity.toLong
                  localRetained += 1L
                  liveObjects += 1L
                }

                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val timestamp = group * cfg.activeTimestamps + slot
              var bucket = 0
              while (bucket < tableSize) {
                var entry: CheckedQ17PartEntry^{region} =
                  tables(slot * tableSize + bucket)
                while (entry != null) {
                  var selectedCount = 0
                  var selectedRevenue = 0L
                  var line: CheckedQ17LineItem^{region} = entry.lineHead
                  while (line != null) {
                    if (
                      entry.part.selected &&
                        q17BelowAverageThreshold(
                          line.quantity,
                          entry.quantityCount,
                          entry.quantitySum
                        )
                    ) {
                      selectedCount += 1
                      selectedRevenue += line.revenue
                      localOutput += 1L
                      localChecksum =
                        fold(
                          localChecksum,
                          53,
                          timestamp,
                          entry.part.key,
                          line.quantity,
                          line.revenue ^ entry.quantitySum
                        )
                    }
                    line = line.next
                  }
                  localChecksum =
                    fold(
                      localChecksum,
                      59,
                      timestamp,
                      entry.part.key,
                      selectedCount,
                      selectedRevenue + entry.quantitySum
                    )
                  entry = entry.next
                }
                bucket += 1
              }
              slot += 1
            }

            FileGroupOutcome(
              Outcome(localChecksum, localOutput, localRetained, localRetained + 1L, liveObjects),
              local,
              localEof
            )
          }

          checksum = fileGroup.outcome.checksum
          outputCount += fileGroup.outcome.outputCount
          retainedObjects += fileGroup.outcome.retainedObjectProxy
          regionFreedObjects += fileGroup.outcome.regionFreedObjectProxy
          if (fileGroup.outcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = fileGroup.outcome.maxLiveObjectProxy
          processed += fileGroup.recordsRead
          eof = fileGroup.eof
          group += 1
        }
      }
    } finally reader.close()

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedScopedAggregate(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingSafeZone { stream ?=>
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.epoch { region ?=>
          final class CheckedEvent(
              val recordId: Int,
              val timestamp: Int,
              val key: Int,
              val value: Int,
              val hash: Long
          ) {
            var next: CheckedEvent^{region} = null
          }

          final class CheckedAggregateEntry(
              val key: Int,
              var count: Int,
              var sum: Long
          ) {
            var next: CheckedAggregateEntry^{region} = null
          }

          val heads: Array[CheckedEvent^{region}]^{region} =
            RiftRegion.allocOpen(new Array[CheckedEvent^{region}](slots))
          val tails: Array[CheckedEvent^{region}]^{region} =
            RiftRegion.allocOpen(new Array[CheckedEvent^{region}](slots))
          val tables: Array[CheckedAggregateEntry^{region}]^{region} =
            RiftRegion.allocOpen(
              new Array[CheckedAggregateEntry^{region}](slots * tableSize)
            )
          val counts = new Array[Int](slots)
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 3L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val key = generatedKey(recordId, timestamp)
            val value = generatedValue(recordId, timestamp)
            val hash = mix(recordId ^ (timestamp * 65537)).toLong
            val event: CheckedEvent^{region} =
              RiftRegion.allocOpen(
                new CheckedEvent(recordId, timestamp, key, value, hash)
              )
            event.next = heads(slot)
            if (heads(slot) == null) tails(slot) = event
            heads(slot) = event
            counts(slot) += 1
            localRetained += 1L
            liveObjects += 1L

            val bucket = slot * tableSize + (mix(key) & tableMask)
            var entry: CheckedAggregateEntry^{region} = tables(bucket)
            var found: CheckedAggregateEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found = RiftRegion.allocOpen(new CheckedAggregateEntry(key, 0, 0L))
              found.next = tables(bucket)
              tables(bucket) = found
              localRetained += 1L
              liveObjects += 1L
            }
            found.count += 1
            found.sum += value.toLong

            if (recordId % cfg.sampleEvery == 0)
              localChecksum = fold(localChecksum, 11, timestamp, key, 1, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            val head = heads(slot)
            val tail = tails(slot)
            if (head != null && tail != null)
              localChecksum =
                fold(localChecksum, 17, timestamp, head.key ^ tail.key, counts(slot), head.hash ^ tail.hash)
            var bucket = 0
            while (bucket < tableSize) {
              var entry: CheckedAggregateEntry^{region} = tables(slot * tableSize + bucket)
              while (entry != null) {
                localChecksum =
                  fold(localChecksum, 23, timestamp, entry.key, entry.count, entry.sum)
                localOutput += 1L
                entry = entry.next
              }
              bucket += 1
            }
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 3L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedScopedJoin(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingSafeZone { stream ?=>
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.epoch { region ?=>
          final class CheckedJoinRecord(
              val recordId: Int,
              val timestamp: Int,
              val key: Int,
              val value: Int,
              val hash: Long
          ) {
            var next: CheckedJoinRecord^{region} = null
          }

          val left: Array[CheckedJoinRecord^{region}]^{region} =
            RiftRegion.allocOpen(new Array[CheckedJoinRecord^{region}](slots * tableSize))
          val right: Array[CheckedJoinRecord^{region}]^{region} =
            RiftRegion.allocOpen(new Array[CheckedJoinRecord^{region}](slots * tableSize))
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 2L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val ordinal = local / slots
            val side = ordinal & 1
            val key = generatedKey(ordinal / 2, timestamp)
            val value = generatedValue(recordId, timestamp)
            val hash = mix(recordId ^ (key * 1000003) ^ (side * 17)).toLong
            val bucket = slot * tableSize + (mix(key) & tableMask)
            if (side == 0) {
              val oldLeft: CheckedJoinRecord^{region} = left(bucket)
              var cursor: CheckedJoinRecord^{region} = right(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  localChecksum =
                    fold(localChecksum, 31, timestamp, key, 1, hash ^ cursor.hash)
                  localOutput += 1L
                }
                cursor = cursor.next
              }
              left(bucket) =
                RiftRegion.allocOpen(
                  new CheckedJoinRecord(recordId, timestamp, key, value, hash)
                )
              left(bucket).next = oldLeft
            } else {
              val oldRight: CheckedJoinRecord^{region} = right(bucket)
              var cursor: CheckedJoinRecord^{region} = left(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  localChecksum =
                    fold(localChecksum, 37, timestamp, key, 1, hash ^ cursor.hash)
                  localOutput += 1L
                }
                cursor = cursor.next
              }
              right(bucket) =
                RiftRegion.allocOpen(
                  new CheckedJoinRecord(recordId, timestamp, key, value, hash)
                )
              right(bucket).next = oldRight
            }
            localRetained += 1L
            liveObjects += 1L
            if (recordId % cfg.sampleEvery == 0)
              localChecksum = fold(localChecksum, 41, timestamp, key, side, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            var bucket = 0
            var leftCount = 0
            var rightCount = 0
            while (bucket < tableSize) {
              var l: CheckedJoinRecord^{region} = left(slot * tableSize + bucket)
              while (l != null) {
                leftCount += 1
                l = l.next
              }
              var r: CheckedJoinRecord^{region} = right(slot * tableSize + bucket)
              while (r != null) {
                rightCount += 1
                r = r.next
              }
              bucket += 1
            }
            localChecksum =
              fold(localChecksum, 43, timestamp, leftCount, rightCount, leftCount.toLong + rightCount)
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 2L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedScopedQ17(): Outcome = {
    if (q17UsesTpchFileInput()) return runCheckedScopedTpchQ17()

    val cfg = BroomRetainedDataflowConfig
    val tableSize = nextPowerOfTwo(math.max(16, cfg.keySpace * 2))
    val tableMask = tableSize - 1
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0

    RiftRegion.streamingSafeZone { stream ?=>
      while (processed < cfg.records) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

        val groupOutcome = RiftRegion.epoch { region ?=>
          final class CheckedQ17Part(
              val key: Int,
              val brand: Int,
              val container: Int,
              val selected: Boolean
          )

          final class CheckedQ17LineItem(
              val recordId: Int,
              val timestamp: Int,
              val partKey: Int,
              val quantity: Int,
              val revenue: Long,
              val hash: Long
          ) {
            var next: CheckedQ17LineItem^{region} = null
          }

          final class CheckedQ17PartEntry(
              val part: CheckedQ17Part^{region},
              var quantityCount: Int,
              var quantitySum: Long
          ) {
            var lineHead: CheckedQ17LineItem^{region} = null
            var next: CheckedQ17PartEntry^{region} = null
          }

          val tables: Array[CheckedQ17PartEntry^{region}]^{region} =
            RiftRegion.allocOpen(
              new Array[CheckedQ17PartEntry^{region}](slots * tableSize)
            )
          var localChecksum = checksum
          var localOutput = 0L
          var localRetained = 0L
          var liveObjects = 1L
          var local = 0

          while (local < groupRecords) {
            val slot = local % slots
            val timestamp = group * cfg.activeTimestamps + slot
            val recordId = processed + local
            val ordinal = local / slots
            val partKey = generatedQ17PartKey(ordinal / 4, timestamp)
            val quantity = generatedQ17Quantity(recordId, timestamp, partKey)
            val revenue = generatedQ17Revenue(recordId, partKey, quantity)
            val hash =
              mix(recordId ^ (timestamp * 65537) ^ (partKey * 1000003)).toLong
            val bucket = slot * tableSize + (mix(partKey) & tableMask)
            var entry: CheckedQ17PartEntry^{region} = tables(bucket)
            var found: CheckedQ17PartEntry^{region} = null
            while (entry != null && found == null) {
              if (entry.part.key == partKey) found = entry
              entry = entry.next
            }
            if (found == null) {
              val part: CheckedQ17Part^{region} =
                RiftRegion.allocOpen(
                  new CheckedQ17Part(
                    partKey,
                    generatedQ17Brand(partKey),
                    generatedQ17Container(partKey),
                    selectedQ17Part(partKey)
                  )
                )
              found = RiftRegion.allocOpen(new CheckedQ17PartEntry(part, 0, 0L))
              found.next = tables(bucket)
              tables(bucket) = found
              localRetained += 2L
              liveObjects += 2L
            }
            val line: CheckedQ17LineItem^{region} =
              RiftRegion.allocOpen(
                new CheckedQ17LineItem(
                  recordId,
                  timestamp,
                  partKey,
                  quantity,
                  revenue,
                  hash
                )
              )
            line.next = found.lineHead
            found.lineHead = line
            found.quantityCount += 1
            found.quantitySum += quantity.toLong
            localRetained += 1L
            liveObjects += 1L
            if (recordId % cfg.sampleEvery == 0)
              localChecksum =
                fold(localChecksum, 51, timestamp, partKey, quantity, hash)
            local += 1
          }

          var slot = 0
          while (slot < slots) {
            val timestamp = group * cfg.activeTimestamps + slot
            var bucket = 0
            while (bucket < tableSize) {
              var entry: CheckedQ17PartEntry^{region} =
                tables(slot * tableSize + bucket)
              while (entry != null) {
                var selectedCount = 0
                var selectedRevenue = 0L
                var line: CheckedQ17LineItem^{region} = entry.lineHead
                while (line != null) {
                  if (
                    entry.part.selected &&
                      q17BelowAverageThreshold(
                        line.quantity,
                        entry.quantityCount,
                        entry.quantitySum
                      )
                  ) {
                    selectedCount += 1
                    selectedRevenue += line.revenue
                    localOutput += 1L
                    localChecksum =
                      fold(
                        localChecksum,
                        53,
                        timestamp,
                        entry.part.key,
                        line.quantity,
                        line.revenue ^ entry.quantitySum
                      )
                  }
                  line = line.next
                }
                localChecksum =
                  fold(
                    localChecksum,
                    59,
                    timestamp,
                    entry.part.key,
                    selectedCount,
                    selectedRevenue + entry.quantitySum
                  )
                entry = entry.next
              }
              bucket += 1
            }
            slot += 1
          }

          Outcome(localChecksum, localOutput, localRetained, localRetained + 1L, liveObjects)
        }

        checksum = groupOutcome.checksum
        outputCount += groupOutcome.outputCount
        retainedObjects += groupOutcome.retainedObjectProxy
        regionFreedObjects += groupOutcome.regionFreedObjectProxy
        if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
          maxLiveObjects = groupOutcome.maxLiveObjectProxy
        processed += groupRecords
        group += 1
      }
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def runCheckedScopedTpchQ17(): Outcome = {
    val cfg = BroomRetainedDataflowConfig
    val selectedPartKeys = loadTpchSelectedPartKeys()
    val tableSize = q17TpchTableSize(selectedPartKeys.size())
    val tableMask = tableSize - 1
    val reader = openInput(cfg.tpchLineItemInput, "BROOM_TPCH_LINEITEM_INPUT")
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var eof = false

    try {
      RiftRegion.streamingSafeZone { stream ?=>
        while (processed < cfg.records && !eof) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerTimestamp)

          val fileGroup = RiftRegion.epoch { region ?=>
            final class CheckedQ17Part(
                val key: Int,
                val brand: Int,
                val container: Int,
                val selected: Boolean
            )

            final class CheckedQ17LineItem(
                val recordId: Int,
                val timestamp: Int,
                val partKey: Int,
                val quantity: Int,
                val revenue: Long,
                val hash: Long
            ) {
              var next: CheckedQ17LineItem^{region} = null
            }

            final class CheckedQ17PartEntry(
                val part: CheckedQ17Part^{region},
                var quantityCount: Int,
                var quantitySum: Long
            ) {
              var lineHead: CheckedQ17LineItem^{region} = null
              var next: CheckedQ17PartEntry^{region} = null
            }

            val tables: Array[CheckedQ17PartEntry^{region}]^{region} =
              RiftRegion.allocOpen(
                new Array[CheckedQ17PartEntry^{region}](slots * tableSize)
              )
            var localChecksum = checksum
            var localOutput = 0L
            var localRetained = 0L
            var liveObjects = 1L
            var local = 0
            var localEof = false

            while (local < groupRecords && !localEof) {
              val line = reader.readLine()
              if (line == null) localEof = true
              else {
                val slot = local % slots
                val timestamp = group * cfg.activeTimestamps + slot
                val recordId = processed + local
                val partKey = parseIntField(line, 1)
                val quantity = parseDecimal2Field(line, 4).toInt
                val revenue = parseDecimal2Field(line, 5)
                val hash = tpchQ17Hash(recordId, timestamp, partKey, quantity)

                if (recordId % cfg.sampleEvery == 0)
                  localChecksum =
                    fold(localChecksum, 51, timestamp, partKey, quantity, hash)

                if (selectedPartKeys.contains(JInteger.valueOf(partKey))) {
                  val bucket = slot * tableSize + (mix(partKey) & tableMask)
                  var entry: CheckedQ17PartEntry^{region} = tables(bucket)
                  var found: CheckedQ17PartEntry^{region} = null
                  while (entry != null && found == null) {
                    if (entry.part.key == partKey) found = entry
                    entry = entry.next
                  }
                  if (found == null) {
                    val part: CheckedQ17Part^{region} =
                      RiftRegion.allocOpen(
                        new CheckedQ17Part(partKey, 0, 0, selected = true)
                      )
                    found =
                      RiftRegion.allocOpen(new CheckedQ17PartEntry(part, 0, 0L))
                    found.next = tables(bucket)
                    tables(bucket) = found
                    localRetained += 2L
                    liveObjects += 2L
                  }
                  val lineItem: CheckedQ17LineItem^{region} =
                    RiftRegion.allocOpen(
                      new CheckedQ17LineItem(
                        recordId,
                        timestamp,
                        partKey,
                        quantity,
                        revenue,
                        hash
                      )
                    )
                  lineItem.next = found.lineHead
                  found.lineHead = lineItem
                  found.quantityCount += 1
                  found.quantitySum += quantity.toLong
                  localRetained += 1L
                  liveObjects += 1L
                }

                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val timestamp = group * cfg.activeTimestamps + slot
              var bucket = 0
              while (bucket < tableSize) {
                var entry: CheckedQ17PartEntry^{region} =
                  tables(slot * tableSize + bucket)
                while (entry != null) {
                  var selectedCount = 0
                  var selectedRevenue = 0L
                  var line: CheckedQ17LineItem^{region} = entry.lineHead
                  while (line != null) {
                    if (
                      entry.part.selected &&
                        q17BelowAverageThreshold(
                          line.quantity,
                          entry.quantityCount,
                          entry.quantitySum
                        )
                    ) {
                      selectedCount += 1
                      selectedRevenue += line.revenue
                      localOutput += 1L
                      localChecksum =
                        fold(
                          localChecksum,
                          53,
                          timestamp,
                          entry.part.key,
                          line.quantity,
                          line.revenue ^ entry.quantitySum
                        )
                    }
                    line = line.next
                  }
                  localChecksum =
                    fold(
                      localChecksum,
                      59,
                      timestamp,
                      entry.part.key,
                      selectedCount,
                      selectedRevenue + entry.quantitySum
                    )
                  entry = entry.next
                }
                bucket += 1
              }
              slot += 1
            }

            FileGroupOutcome(
              Outcome(localChecksum, localOutput, localRetained, localRetained + 1L, liveObjects),
              local,
              localEof
            )
          }

          checksum = fileGroup.outcome.checksum
          outputCount += fileGroup.outcome.outputCount
          retainedObjects += fileGroup.outcome.retainedObjectProxy
          regionFreedObjects += fileGroup.outcome.regionFreedObjectProxy
          if (fileGroup.outcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = fileGroup.outcome.maxLiveObjectProxy
          processed += fileGroup.recordsRead
          eof = fileGroup.eof
          group += 1
        }
      }
    } finally reader.close()

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(checksum, outputCount, retainedObjects, regionFreedObjects, maxLiveObjects)
  }

  private def canonicalMode(mode: String): String =
    mode match {
      case "heap-gc" | "gc-heap" | "heap-immix" | "heap" => "heap-gc"
      case "checked-rift" | "checked-epoch-stream" | "checked-region-stream" =>
        "checked-rift"
      case "checked-region-scoped" | "checked-epoch-scoped" |
          "best-safe-region" | "checked-rift-scoped" =>
        "checked-region-scoped"
      case other =>
        throw new IllegalArgumentException(s"unknown Broom retained mode '$other'")
    }

  private def canonicalWorkload(workload: String): String =
    workload match {
      case "aggregate" | "agg" => "aggregate"
      case "join"              => "join"
      case "q17" | "tpch-q17" | "q17-retained" => "q17"
      case other =>
        throw new IllegalArgumentException(
          s"unknown Broom retained workload '$other'"
        )
    }

  private def runOnce(mode: String, workload: String): Outcome =
    (canonicalMode(mode), canonicalWorkload(workload)) match {
      case ("heap-gc", "aggregate")     => runHeapAggregate()
      case ("heap-gc", "join")          => runHeapJoin()
      case ("heap-gc", "q17")           => runHeapQ17()
      case ("checked-rift", "aggregate") => runCheckedAggregate()
      case ("checked-rift", "join")      => runCheckedJoin()
      case ("checked-rift", "q17")       => runCheckedQ17()
      case ("checked-region-scoped", "aggregate") => runCheckedScopedAggregate()
      case ("checked-region-scoped", "join")      => runCheckedScopedJoin()
      case ("checked-region-scoped", "q17")       => runCheckedScopedQ17()
      case other =>
        throw new IllegalArgumentException(
          s"unsupported Broom retained run selection $other"
        )
    }

  private def usesRift(mode: String): Boolean =
    canonicalMode(mode) != "heap-gc"

  def runBenchmark(mode: String, workload: String): Unit = {
    val cfg = BroomRetainedDataflowConfig
    val canonical = canonicalMode(mode)
    val query = canonicalWorkload(workload)
    val includeRift = usesRift(canonical)

    if (cfg.finalClean) {
      var run = 0
      var outcome = Outcome(0L, 0L, 0L, 0L, 0L)
      while (run < cfg.benchmarkRuns) {
        outcome = runOnce(canonical, query)
        run += 1
      }
      println(
        s"RESULT name=broom-retained-dataflow-$query-$canonical " +
          s"measurement_level=L1 final_clean=1 workload=$query mode=$canonical " +
          s"records=${cfg.records} records_per_timestamp=${cfg.recordsPerTimestamp} " +
          s"active_timestamps=${cfg.activeTimestamps} key_space=${cfg.keySpace} " +
          s"q17_input_mode=${cfg.q17InputMode} " +
          s"runs=${cfg.benchmarkRuns} checksum=${outcome.checksum} " +
          s"output_count=${outcome.outputCount} " +
          s"retained_object_proxy=${outcome.retainedObjectProxy} " +
          s"region_freed_object_proxy=${outcome.regionFreedObjectProxy} " +
          s"max_live_object_proxy=${outcome.maxLiveObjectProxy}"
      )
      return
    }

    var warm = 0
    while (warm < cfg.warmupRuns) {
      runOnce(canonical, query)
      warm += 1
    }

    val elapsedMs = new Array[Double](cfg.benchmarkRuns)
    val gcMs = new Array[Double](cfg.benchmarkRuns)
    val gcCollections = new Array[Long](cfg.benchmarkRuns)
    val riftOpMs = new Array[Double](cfg.benchmarkRuns)
    val riftObjects = new Array[Long](cfg.benchmarkRuns)
    val riftOpens = new Array[Long](cfg.benchmarkRuns)
    val riftCloses = new Array[Long](cfg.benchmarkRuns)
    val riftResets = new Array[Long](cfg.benchmarkRuns)
    var checksum = 0L
    var outputCount = 0L
    var retainedObjectProxy = 0L
    var regionFreedObjectProxy = 0L
    var maxLiveObjectProxy = 0L
    var run = 0

    println(
      s"Running broom-retained-dataflow-$query-$canonical for ${cfg.benchmarkRuns} timed runs"
    )
    while (run < cfg.benchmarkRuns) {
      val before = RuntimeSample.capture(includeRift)
      val start = System.nanoTime()
      val outcome = runOnce(canonical, query)
      val elapsed = System.nanoTime() - start
      val after = RuntimeSample.capture(includeRift)
      val sample = RuntimeSample.since(before, after)

      elapsedMs(run) = elapsed.toDouble / 1000000.0
      gcMs(run) = sample.gcNanos.toDouble / 1000000.0
      gcCollections(run) = sample.gcCollections
      riftOpMs(run) = sample.riftRegionOpNanos.toDouble / 1000000.0
      riftObjects(run) = sample.riftAllocObjectTotal
      riftOpens(run) = sample.riftRegionOpenTotal
      riftCloses(run) = sample.riftRegionCloseTotal
      riftResets(run) = sample.riftRegionResetTotal
      checksum = outcome.checksum
      outputCount = outcome.outputCount
      retainedObjectProxy = outcome.retainedObjectProxy
      regionFreedObjectProxy = outcome.regionFreedObjectProxy
      maxLiveObjectProxy = outcome.maxLiveObjectProxy
      println(
        f"  run=${run + 1}%d elapsed_ms=${elapsedMs(run)}%.3f gc_ms=${gcMs(run)}%.3f gc_collections=${gcCollections(run)}%d"
      )
      run += 1
    }

    val medianMs = medianDouble(elapsedMs)
    val minMs = elapsedMs.min
    val maxMs = elapsedMs.max
    val medianGcMs = medianDouble(gcMs)
    val maxGcMs = gcMs.max
    val runsWithGc = gcMs.count(_ > 0.0)
    val maxGcCollections = if (gcCollections.isEmpty) 0L else gcCollections.max
    val medianRiftOpMs = medianDouble(riftOpMs)
    val medianRiftObjects = medianLong(riftObjects)
    val medianRiftOpens = medianLong(riftOpens)
    val medianRiftCloses = medianLong(riftCloses)
    val medianRiftResets = medianLong(riftResets)
    val recordsPerSec =
      if (medianMs <= 0.0) 0.0 else cfg.records.toDouble / (medianMs / 1000.0)

    println(
      f"RESULT name=broom-retained-dataflow-$query-$canonical " +
        f"workload=$query mode=$canonical records=${cfg.records}%d " +
        f"records_per_timestamp=${cfg.recordsPerTimestamp}%d " +
        f"active_timestamps=${cfg.activeTimestamps}%d key_space=${cfg.keySpace}%d " +
        f"q17_input_mode=${cfg.q17InputMode} " +
        f"runs=${cfg.benchmarkRuns}%d median_ms=$medianMs%.3f min_ms=$minMs%.3f max_ms=$maxMs%.3f " +
        f"records_per_sec=$recordsPerSec%.3f median_gc_ms=$medianGcMs%.3f max_gc_ms=$maxGcMs%.3f " +
        f"runs_with_gc=$runsWithGc%d max_gc_collections=$maxGcCollections%d " +
        f"median_rift_op_ms=$medianRiftOpMs%.3f median_rift_alloc_object_total=$medianRiftObjects%d " +
        f"median_rift_open_total=$medianRiftOpens%d median_rift_close_total=$medianRiftCloses%d " +
        f"median_rift_reset_total=$medianRiftResets%d checksum=$checksum%d output_count=$outputCount%d " +
        f"retained_object_proxy=$retainedObjectProxy%d region_freed_object_proxy=$regionFreedObjectProxy%d " +
        f"max_live_object_proxy=$maxLiveObjectProxy%d"
    )
  }

  def printConfig(mode: String, workload: String): Unit = {
    val cfg = BroomRetainedDataflowConfig
    println(
      s"CONFIG mode=${canonicalMode(mode)} workload=${canonicalWorkload(workload)} " +
        s"records=${cfg.records} records_per_timestamp=${cfg.recordsPerTimestamp} " +
        s"active_timestamps=${cfg.activeTimestamps} key_space=${cfg.keySpace} " +
        s"warmups=${cfg.warmupRuns} runs=${cfg.benchmarkRuns} final_clean=${cfg.finalClean} " +
        s"q17_input_mode=${cfg.q17InputMode} tpch_part_input=${cfg.tpchPartInput} " +
        s"tpch_lineitem_input=${cfg.tpchLineItemInput}"
    )
  }
}

object BroomRetainedDataflowMatrix {
  def main(args: Array[String]): Unit = {
    val mode = if (args.length > 0) args(0) else "heap-gc"
    val workload = if (args.length > 1) args(1) else "aggregate"
    BroomRetainedDataflowMatrixHelpers.printConfig(mode, workload)
    BroomRetainedDataflowMatrixHelpers.runBenchmark(mode, workload)
  }
}
