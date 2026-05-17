import scala.language.experimental.captureChecking

import scala.scalanative.memory.RiftRegion
import scala.scalanative.runtime.{fromRawUSize, GC, RawSize, RiftAllocator}

object LogHubRetainedSessionConfig {
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

  private def truthy(value: String): Boolean =
    value == "1" || value.equalsIgnoreCase("true") ||
      value.equalsIgnoreCase("yes")

  private def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parsePositiveInt).getOrElse(default)

  private def envNonNegativeInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(parseNonNegativeInt).getOrElse(default)

  val records: Int = envNonNegativeInt("LOGHUB_SESSION_RECORDS", 1000000)
  val recordsPerEpoch: Int =
    envInt("LOGHUB_SESSION_RECORDS_PER_EPOCH", 25000)
  val activeEpochs: Int = envInt("LOGHUB_SESSION_ACTIVE_EPOCHS", 8)
  val keySpace: Int = envInt("LOGHUB_SESSION_KEY_SPACE", 65536)
  val tableMultiplier: Int = envInt("LOGHUB_SESSION_TABLE_MULTIPLIER", 2)
  val sampleEvery: Int = envInt("LOGHUB_SESSION_SAMPLE_EVERY", 4096)
  val warmupRuns: Int = envNonNegativeInt("LOGHUB_SESSION_WARMUPS", 1)
  val benchmarkRuns: Int = envInt("LOGHUB_SESSION_BENCHMARK_RUNS", 3)
  val finalClean: Boolean =
    sys.env.get("RIFT_FINAL_CLEAN").exists(truthy) ||
      sys.env
        .get("RIFT_EVAL_MEASUREMENT_LEVEL")
        .exists(_.equalsIgnoreCase("L1"))

  private val inputRaw: String = {
    val multiple = BenchmarkInputSupport.envString("LOGHUB_SESSION_INPUTS")
    if (multiple.nonEmpty) multiple
    else BenchmarkInputSupport.envString("LOGHUB_SESSION_INPUT")
  }

  val inputPaths: Array[String] =
    if (inputRaw.isEmpty) Array.empty
    else inputRaw.split(",").map(_.trim).filter(_.nonEmpty)

  val inputPath: String = inputPaths.mkString(",")
}

object LogHubRetainedSessionMatrixHelpers {
  @volatile private var checksumSink = 0L
  @volatile private var outputSink = 0L
  @volatile private var retainedSink = 0L

  private final class HeapSessionEvent(
      val recordId: Int,
      val epoch: Int,
      val key: Int,
      val value: Int,
      val hash: Long,
      var next: HeapSessionEvent
  )

  private final class HeapSessionEntry(
      val key: Int,
      var count: Int,
      var sum: Long,
      var firstHash: Long,
      var lastHash: Long,
      var events: HeapSessionEvent,
      var next: HeapSessionEntry
  )

  private final class HeapJoinRecord(
      val recordId: Int,
      val epoch: Int,
      val key: Int,
      val value: Int,
      val hash: Long,
      var next: HeapJoinRecord
  )

  final case class Outcome(
      checksum: Long,
      outputCount: Long,
      retainedObjectProxy: Long,
      regionFreedObjectProxy: Long,
      maxLiveObjectProxy: Long,
      recordsRead: Int,
      bytesRead: Long,
      inputFiles: Int
  )

  private final case class GroupOutcome(
      checksum: Long,
      outputCount: Long,
      retainedObjectProxy: Long,
      regionFreedObjectProxy: Long,
      maxLiveObjectProxy: Long,
      recordsRead: Int
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
      epoch: Int,
      key: Int,
      count: Int,
      sum: Long
  ): Long =
    (((checksum ^ kind.toLong) * 1099511628211L) ^
      epoch.toLong ^
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

  private def groupSlots(
      remaining: Int,
      cfg: LogHubRetainedSessionConfig.type
  ): Int = {
    val epochsLeft =
      (remaining + cfg.recordsPerEpoch - 1) / cfg.recordsPerEpoch
    math.max(1, math.min(cfg.activeEpochs, epochsLeft))
  }

  private def tokenSeparator(byte: Int): Boolean =
    byte <= 32 || byte == ','.toInt || byte == ';'.toInt ||
      byte == '|'.toInt || byte == ':'.toInt || byte == '='.toInt

  private def containsAscii(
      bytes: Array[Byte],
      length: Int,
      text: String
  ): Boolean = {
    var i = 0
    val last = length - text.length
    while (i <= last) {
      var j = 0
      var ok = true
      while (j < text.length && ok) {
        if ((bytes(i + j) & 0xff) != text.charAt(j).toInt) ok = false
        j += 1
      }
      if (ok) return true
      i += 1
    }
    false
  }

  private def severityFor(bytes: Array[Byte], length: Int): Int =
    if (containsAscii(bytes, length, "ERROR")) 4
    else if (containsAscii(bytes, length, "FATAL")) 5
    else if (containsAscii(bytes, length, "WARN")) 3
    else if (containsAscii(bytes, length, "INFO")) 2
    else if (containsAscii(bytes, length, "DEBUG")) 1
    else 0

  private def tokenHash(
      bytes: Array[Byte],
      length: Int,
      tokenIndex: Int
  ): Int = {
    var tokens = 0
    var inToken = false
    var start = 0
    var i = 0
    while (i < length) {
      val sep = tokenSeparator(bytes(i) & 0xff)
      if (sep) {
        if (inToken && tokens == tokenIndex + 1)
          return BenchmarkInputSupport.stableHash(bytes, start, i - start)
        inToken = false
      } else if (!inToken) {
        if (tokens == tokenIndex) start = i
        inToken = true
        tokens += 1
      }
      i += 1
    }
    if (inToken && tokens == tokenIndex + 1)
      BenchmarkInputSupport.stableHash(bytes, start, length - start)
    else 0
  }

  private def sessionKeyFor(
      bytes: Array[Byte],
      length: Int,
      severity: Int
  ): Int = {
    val h0 = tokenHash(bytes, length, 0)
    val h3 = tokenHash(bytes, length, 3)
    val h5 = tokenHash(bytes, length, 5)
    val hAll = BenchmarkInputSupport.stableHash(bytes, 0, length)
    BenchmarkInputSupport.positiveModulo(
      (h0.toLong << 32) ^ h3.toLong ^ (h5.toLong << 11) ^
        hAll.toLong ^ severity.toLong,
      LogHubRetainedSessionConfig.keySpace
    )
  }

  private def valueFor(bytes: Array[Byte], length: Int, severity: Int): Int =
    ((BenchmarkInputSupport.stableHash(bytes, 0, length) ^ (severity * 65537)) &
      0xffff) + 1

  private def readLineFields(
      source: BenchmarkInputSupport.StreamingByteLineSource
  ): (Int, Int, Long) = {
    val bytes = source.bytes
    val length = source.length
    val severity = severityFor(bytes, length)
    val key = sessionKeyFor(bytes, length, severity)
    val value = valueFor(bytes, length, severity)
    val hash =
      BenchmarkInputSupport.stableHash(bytes, 0, length).toLong ^
        (severity.toLong * 1099511628211L) ^
        (key.toLong << 17)
    (key, value, hash)
  }

  private def openSource(): BenchmarkInputSupport.StreamingByteLineSource = {
    val cfg = LogHubRetainedSessionConfig
    if (cfg.inputPaths.isEmpty)
      throw new IllegalArgumentException(
        "LOGHUB_SESSION_INPUT or LOGHUB_SESSION_INPUTS is required"
      )
    BenchmarkInputSupport.openStreamingByteLines(cfg.inputPaths)
  }

  private def tableSizeFor(): Int = {
    val cfg = LogHubRetainedSessionConfig
    nextPowerOfTwo(math.max(16, cfg.keySpace * cfg.tableMultiplier))
  }

  private def runHeapSession(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      while (processed < cfg.records && !done) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
        val entries = new Array[HeapSessionEntry](slots * tableSize)
        val heads = new Array[HeapSessionEvent](slots)
        val tails = new Array[HeapSessionEvent](slots)
        val counts = new Array[Int](slots)
        var liveObjects = 0L
        var local = 0

        while (local < groupRecords && !done) {
          val length = source.readLine()
          if (length < 0) done = true
          else if (length > 0) {
            val slot = local % slots
            val epoch = group * cfg.activeEpochs + slot
            val recordId = processed + local
            val (key, value, hash) = readLineFields(source)
            val event = new HeapSessionEvent(recordId, epoch, key, value, hash, null)
            if (heads(slot) == null) {
              heads(slot) = event
              tails(slot) = event
            } else {
              event.next = heads(slot)
              heads(slot) = event
            }
            counts(slot) += 1
            retainedObjects += 1L
            liveObjects += 1L

            val bucket = slot * tableSize + (mix(key) & tableMask)
            var entry = entries(bucket)
            var found: HeapSessionEntry = null
            while (entry != null && found == null) {
              if (entry.key == key) found = entry
              entry = entry.next
            }
            if (found == null) {
              found =
                new HeapSessionEntry(key, 0, 0L, hash, hash, null, entries(bucket))
              entries(bucket) = found
              retainedObjects += 1L
              liveObjects += 1L
            }
            event.next = found.events
            found.events = event
            found.count += 1
            found.sum += value.toLong
            found.lastHash = hash

            if (recordId % cfg.sampleEvery == 0)
              checksum = fold(checksum, 101, epoch, key, 1, hash)
            local += 1
          } else {
            local += 1
          }
        }

        var slot = 0
        while (slot < slots) {
          val epoch = group * cfg.activeEpochs + slot
          if (heads(slot) != null && tails(slot) != null)
            checksum =
              fold(checksum, 107, epoch, heads(slot).key ^ tails(slot).key, counts(slot), heads(slot).hash ^ tails(slot).hash)
          var bucket = 0
          while (bucket < tableSize) {
            var entry = entries(slot * tableSize + bucket)
            while (entry != null) {
              checksum =
                fold(checksum, 109, epoch, entry.key, entry.count, entry.sum ^ entry.firstHash ^ entry.lastHash)
              outputCount += 1L
              entry = entry.next
            }
            bucket += 1
          }
          slot += 1
        }

        if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects
        processed += local
        group += 1
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      0L,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
  }

  private def runHeapJoin(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      while (processed < cfg.records && !done) {
        val remaining = cfg.records - processed
        val slots = groupSlots(remaining, cfg)
        val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
        val left = new Array[HeapJoinRecord](slots * tableSize)
        val right = new Array[HeapJoinRecord](slots * tableSize)
        var liveObjects = 0L
        var local = 0

        while (local < groupRecords && !done) {
          val length = source.readLine()
          if (length < 0) done = true
          else if (length > 0) {
            val slot = local % slots
            val epoch = group * cfg.activeEpochs + slot
            val recordId = processed + local
            val (rawKey, value, rawHash) = readLineFields(source)
            val key = rawKey
            val side = recordId & 1
            val hash = rawHash ^ (side.toLong * 1315423911L)
            val bucket = slot * tableSize + (mix(key) & tableMask)
            if (side == 0) {
              var cursor = right(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  checksum =
                    fold(checksum, 131, epoch, key, 1, hash ^ cursor.hash)
                  outputCount += 1L
                }
                cursor = cursor.next
              }
              left(bucket) =
                new HeapJoinRecord(recordId, epoch, key, value, hash, left(bucket))
            } else {
              var cursor = left(bucket)
              while (cursor != null) {
                if (cursor.key == key) {
                  checksum =
                    fold(checksum, 137, epoch, key, 1, hash ^ cursor.hash)
                  outputCount += 1L
                }
                cursor = cursor.next
              }
              right(bucket) =
                new HeapJoinRecord(recordId, epoch, key, value, hash, right(bucket))
            }
            retainedObjects += 1L
            liveObjects += 1L
            if (recordId % cfg.sampleEvery == 0)
              checksum = fold(checksum, 139, epoch, key, side, hash)
            local += 1
          } else {
            local += 1
          }
        }

        var slot = 0
        while (slot < slots) {
          val epoch = group * cfg.activeEpochs + slot
          var leftCount = 0
          var rightCount = 0
          var bucket = 0
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
          checksum =
            fold(checksum, 149, epoch, leftCount, rightCount, leftCount.toLong + rightCount)
          slot += 1
        }

        if (liveObjects > maxLiveObjects) maxLiveObjects = liveObjects
        processed += local
        group += 1
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      0L,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
  }

  private def runCheckedSession(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      RiftRegion.streamingOpenHandle {
        while (processed < cfg.records && !done) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
          val groupOutcome = RiftRegion.resetOpenHandle { region ?=>
            final class CheckedSessionEvent(
                val recordId: Int,
                val epoch: Int,
                val key: Int,
                val value: Int,
                val hash: Long
            ) {
              var next: CheckedSessionEvent^{region} = null
            }

            final class CheckedSessionEntry(
                val key: Int,
                var count: Int,
                var sum: Long,
                var firstHash: Long,
                var lastHash: Long
            ) {
              var events: CheckedSessionEvent^{region} = null
              var next: CheckedSessionEntry^{region} = null
            }

            val entries: Array[CheckedSessionEntry^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedSessionEntry^{region}](slots * tableSize)
              )
            val heads: Array[CheckedSessionEvent^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedSessionEvent^{region}](slots)
              )
            val tails: Array[CheckedSessionEvent^{region}]^{region} =
              RiftAllocator.allocateOpenHandle(
                region,
                new Array[CheckedSessionEvent^{region}](slots)
              )
            val counts = new Array[Int](slots)
            var localChecksum = checksum
            var localOutput = 0L
            var localRetained = 0L
            var local = 0
            var liveObjects = 3L

            while (local < groupRecords && !done) {
              val length = source.readLine()
              if (length < 0) done = true
              else if (length > 0) {
                val slot = local % slots
                val epoch = group * cfg.activeEpochs + slot
                val recordId = processed + local
                val (key, value, hash) = readLineFields(source)
                val event: CheckedSessionEvent^{region} =
                  RiftAllocator.allocateOpenHandle(
                    region,
                    new CheckedSessionEvent(recordId, epoch, key, value, hash)
                  )
                if (heads(slot) == null) {
                  heads(slot) = event
                  tails(slot) = event
                } else {
                  event.next = heads(slot)
                  heads(slot) = event
                }
                counts(slot) += 1
                localRetained += 1L
                liveObjects += 1L

                val bucket = slot * tableSize + (mix(key) & tableMask)
                var entry: CheckedSessionEntry^{region} = entries(bucket)
                var found: CheckedSessionEntry^{region} = null
                while (entry != null && found == null) {
                  if (entry.key == key) found = entry
                  entry = entry.next
                }
                if (found == null) {
                  found =
                    RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedSessionEntry(key, 0, 0L, hash, hash)
                    )
                  found.next = entries(bucket)
                  entries(bucket) = found
                  localRetained += 1L
                  liveObjects += 1L
                }
                event.next = found.events
                found.events = event
                found.count += 1
                found.sum += value.toLong
                found.lastHash = hash

                if (recordId % cfg.sampleEvery == 0)
                  localChecksum = fold(localChecksum, 101, epoch, key, 1, hash)
                local += 1
              } else {
                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val epoch = group * cfg.activeEpochs + slot
              val head: CheckedSessionEvent^{region} = heads(slot)
              val tail: CheckedSessionEvent^{region} = tails(slot)
              if (head != null && tail != null)
                localChecksum =
                  fold(localChecksum, 107, epoch, head.key ^ tail.key, counts(slot), head.hash ^ tail.hash)
              var bucket = 0
              while (bucket < tableSize) {
                var entry: CheckedSessionEntry^{region} =
                  entries(slot * tableSize + bucket)
                while (entry != null) {
                  localChecksum =
                    fold(localChecksum, 109, epoch, entry.key, entry.count, entry.sum ^ entry.firstHash ^ entry.lastHash)
                  localOutput += 1L
                  entry = entry.next
                }
                bucket += 1
              }
              slot += 1
            }

            GroupOutcome(
              localChecksum,
              localOutput,
              localRetained,
              localRetained + 3L,
              liveObjects,
              local
            )
          }
          checksum = groupOutcome.checksum
          outputCount += groupOutcome.outputCount
          retainedObjects += groupOutcome.retainedObjectProxy
          regionFreedObjects += groupOutcome.regionFreedObjectProxy
          if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = groupOutcome.maxLiveObjectProxy
          processed += groupOutcome.recordsRead
          group += 1
        }
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      regionFreedObjects,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
  }

  private def runCheckedJoin(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      RiftRegion.streamingOpenHandle {
        while (processed < cfg.records && !done) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
          val groupOutcome = RiftRegion.resetOpenHandle { region ?=>
            final class CheckedJoinRecord(
                val recordId: Int,
                val epoch: Int,
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

            while (local < groupRecords && !done) {
              val length = source.readLine()
              if (length < 0) done = true
              else if (length > 0) {
                val slot = local % slots
                val epoch = group * cfg.activeEpochs + slot
                val recordId = processed + local
                val (rawKey, value, rawHash) = readLineFields(source)
                val key = rawKey
                val side = recordId & 1
                val hash = rawHash ^ (side.toLong * 1315423911L)
                val bucket = slot * tableSize + (mix(key) & tableMask)
                if (side == 0) {
                  var cursor: CheckedJoinRecord^{region} = right(bucket)
                  while (cursor != null) {
                    if (cursor.key == key) {
                      localChecksum =
                        fold(localChecksum, 131, epoch, key, 1, hash ^ cursor.hash)
                      localOutput += 1L
                    }
                    cursor = cursor.next
                  }
                  val record: CheckedJoinRecord^{region} =
                    RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedJoinRecord(recordId, epoch, key, value, hash)
                    )
                  record.next = left(bucket)
                  left(bucket) = record
                } else {
                  var cursor: CheckedJoinRecord^{region} = left(bucket)
                  while (cursor != null) {
                    if (cursor.key == key) {
                      localChecksum =
                        fold(localChecksum, 137, epoch, key, 1, hash ^ cursor.hash)
                      localOutput += 1L
                    }
                    cursor = cursor.next
                  }
                  val record: CheckedJoinRecord^{region} =
                    RiftAllocator.allocateOpenHandle(
                      region,
                      new CheckedJoinRecord(recordId, epoch, key, value, hash)
                    )
                  record.next = right(bucket)
                  right(bucket) = record
                }
                localRetained += 1L
                liveObjects += 1L
                if (recordId % cfg.sampleEvery == 0)
                  localChecksum = fold(localChecksum, 139, epoch, key, side, hash)
                local += 1
              } else {
                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val epoch = group * cfg.activeEpochs + slot
              var leftCount = 0
              var rightCount = 0
              var bucket = 0
              while (bucket < tableSize) {
                var l: CheckedJoinRecord^{region} =
                  left(slot * tableSize + bucket)
                while (l != null) {
                  leftCount += 1
                  l = l.next
                }
                var r: CheckedJoinRecord^{region} =
                  right(slot * tableSize + bucket)
                while (r != null) {
                  rightCount += 1
                  r = r.next
                }
                bucket += 1
              }
              localChecksum =
                fold(localChecksum, 149, epoch, leftCount, rightCount, leftCount.toLong + rightCount)
              slot += 1
            }

            GroupOutcome(
              localChecksum,
              localOutput,
              localRetained,
              localRetained + 2L,
              liveObjects,
              local
            )
          }
          checksum = groupOutcome.checksum
          outputCount += groupOutcome.outputCount
          retainedObjects += groupOutcome.retainedObjectProxy
          regionFreedObjects += groupOutcome.regionFreedObjectProxy
          if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = groupOutcome.maxLiveObjectProxy
          processed += groupOutcome.recordsRead
          group += 1
        }
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      regionFreedObjects,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
  }

  private def runCheckedScopedSession(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      RiftRegion.streamingSafeZone { stream ?=>
        while (processed < cfg.records && !done) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
          val groupOutcome = RiftRegion.epoch { region ?=>
            final class CheckedSessionEvent(
                val recordId: Int,
                val epoch: Int,
                val key: Int,
                val value: Int,
                val hash: Long
            ) {
              var next: CheckedSessionEvent^{region} = null
            }

            final class CheckedSessionEntry(
                val key: Int,
                var count: Int,
                var sum: Long,
                var firstHash: Long,
                var lastHash: Long
            ) {
              var events: CheckedSessionEvent^{region} = null
              var next: CheckedSessionEntry^{region} = null
            }

            val entries: Array[CheckedSessionEntry^{region}]^{region} =
              RiftRegion.allocOpen(
                new Array[CheckedSessionEntry^{region}](slots * tableSize)
              )
            val heads: Array[CheckedSessionEvent^{region}]^{region} =
              RiftRegion.allocOpen(new Array[CheckedSessionEvent^{region}](slots))
            val tails: Array[CheckedSessionEvent^{region}]^{region} =
              RiftRegion.allocOpen(new Array[CheckedSessionEvent^{region}](slots))
            val counts = new Array[Int](slots)
            var localChecksum = checksum
            var localOutput = 0L
            var localRetained = 0L
            var local = 0
            var liveObjects = 3L

            while (local < groupRecords && !done) {
              val length = source.readLine()
              if (length < 0) done = true
              else if (length > 0) {
                val slot = local % slots
                val epoch = group * cfg.activeEpochs + slot
                val recordId = processed + local
                val (key, value, hash) = readLineFields(source)
                val event: CheckedSessionEvent^{region} =
                  RiftRegion.allocOpen(
                    new CheckedSessionEvent(recordId, epoch, key, value, hash)
                  )
                if (heads(slot) == null) {
                  heads(slot) = event
                  tails(slot) = event
                } else {
                  event.next = heads(slot)
                  heads(slot) = event
                }
                counts(slot) += 1
                localRetained += 1L
                liveObjects += 1L

                val bucket = slot * tableSize + (mix(key) & tableMask)
                var entry: CheckedSessionEntry^{region} = entries(bucket)
                var found: CheckedSessionEntry^{region} = null
                while (entry != null && found == null) {
                  if (entry.key == key) found = entry
                  entry = entry.next
                }
                if (found == null) {
                  found =
                    RiftRegion.allocOpen(
                      new CheckedSessionEntry(key, 0, 0L, hash, hash)
                    )
                  found.next = entries(bucket)
                  entries(bucket) = found
                  localRetained += 1L
                  liveObjects += 1L
                }
                event.next = found.events
                found.events = event
                found.count += 1
                found.sum += value.toLong
                found.lastHash = hash

                if (recordId % cfg.sampleEvery == 0)
                  localChecksum = fold(localChecksum, 101, epoch, key, 1, hash)
                local += 1
              } else {
                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val epoch = group * cfg.activeEpochs + slot
              val head: CheckedSessionEvent^{region} = heads(slot)
              val tail: CheckedSessionEvent^{region} = tails(slot)
              if (head != null && tail != null)
                localChecksum =
                  fold(localChecksum, 107, epoch, head.key ^ tail.key, counts(slot), head.hash ^ tail.hash)
              var bucket = 0
              while (bucket < tableSize) {
                var entry: CheckedSessionEntry^{region} =
                  entries(slot * tableSize + bucket)
                while (entry != null) {
                  localChecksum =
                    fold(localChecksum, 109, epoch, entry.key, entry.count, entry.sum ^ entry.firstHash ^ entry.lastHash)
                  localOutput += 1L
                  entry = entry.next
                }
                bucket += 1
              }
              slot += 1
            }

            GroupOutcome(
              localChecksum,
              localOutput,
              localRetained,
              localRetained + 3L,
              liveObjects,
              local
            )
          }
          checksum = groupOutcome.checksum
          outputCount += groupOutcome.outputCount
          retainedObjects += groupOutcome.retainedObjectProxy
          regionFreedObjects += groupOutcome.regionFreedObjectProxy
          if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = groupOutcome.maxLiveObjectProxy
          processed += groupOutcome.recordsRead
          group += 1
        }
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      regionFreedObjects,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
  }

  private def runCheckedScopedJoin(): Outcome = {
    val cfg = LogHubRetainedSessionConfig
    val tableSize = tableSizeFor()
    val tableMask = tableSize - 1
    val source = openSource()
    var checksum = 0L
    var outputCount = 0L
    var retainedObjects = 0L
    var regionFreedObjects = 0L
    var maxLiveObjects = 0L
    var processed = 0
    var group = 0
    var done = false

    try {
      RiftRegion.streamingSafeZone { stream ?=>
        while (processed < cfg.records && !done) {
          val remaining = cfg.records - processed
          val slots = groupSlots(remaining, cfg)
          val groupRecords = math.min(remaining, slots * cfg.recordsPerEpoch)
          val groupOutcome = RiftRegion.epoch { region ?=>
            final class CheckedJoinRecord(
                val recordId: Int,
                val epoch: Int,
                val key: Int,
                val value: Int,
                val hash: Long
            ) {
              var next: CheckedJoinRecord^{region} = null
            }

            val left: Array[CheckedJoinRecord^{region}]^{region} =
              RiftRegion.allocOpen(
                new Array[CheckedJoinRecord^{region}](slots * tableSize)
              )
            val right: Array[CheckedJoinRecord^{region}]^{region} =
              RiftRegion.allocOpen(
                new Array[CheckedJoinRecord^{region}](slots * tableSize)
              )
            var localChecksum = checksum
            var localOutput = 0L
            var localRetained = 0L
            var liveObjects = 2L
            var local = 0

            while (local < groupRecords && !done) {
              val length = source.readLine()
              if (length < 0) done = true
              else if (length > 0) {
                val slot = local % slots
                val epoch = group * cfg.activeEpochs + slot
                val recordId = processed + local
                val (rawKey, value, rawHash) = readLineFields(source)
                val key = rawKey
                val side = recordId & 1
                val hash = rawHash ^ (side.toLong * 1315423911L)
                val bucket = slot * tableSize + (mix(key) & tableMask)
                if (side == 0) {
                  var cursor: CheckedJoinRecord^{region} = right(bucket)
                  while (cursor != null) {
                    if (cursor.key == key) {
                      localChecksum =
                        fold(localChecksum, 131, epoch, key, 1, hash ^ cursor.hash)
                      localOutput += 1L
                    }
                    cursor = cursor.next
                  }
                  val record: CheckedJoinRecord^{region} =
                    RiftRegion.allocOpen(
                      new CheckedJoinRecord(recordId, epoch, key, value, hash)
                    )
                  record.next = left(bucket)
                  left(bucket) = record
                } else {
                  var cursor: CheckedJoinRecord^{region} = left(bucket)
                  while (cursor != null) {
                    if (cursor.key == key) {
                      localChecksum =
                        fold(localChecksum, 137, epoch, key, 1, hash ^ cursor.hash)
                      localOutput += 1L
                    }
                    cursor = cursor.next
                  }
                  val record: CheckedJoinRecord^{region} =
                    RiftRegion.allocOpen(
                      new CheckedJoinRecord(recordId, epoch, key, value, hash)
                    )
                  record.next = right(bucket)
                  right(bucket) = record
                }
                localRetained += 1L
                liveObjects += 1L
                if (recordId % cfg.sampleEvery == 0)
                  localChecksum = fold(localChecksum, 139, epoch, key, side, hash)
                local += 1
              } else {
                local += 1
              }
            }

            var slot = 0
            while (slot < slots) {
              val epoch = group * cfg.activeEpochs + slot
              var leftCount = 0
              var rightCount = 0
              var bucket = 0
              while (bucket < tableSize) {
                var l: CheckedJoinRecord^{region} =
                  left(slot * tableSize + bucket)
                while (l != null) {
                  leftCount += 1
                  l = l.next
                }
                var r: CheckedJoinRecord^{region} =
                  right(slot * tableSize + bucket)
                while (r != null) {
                  rightCount += 1
                  r = r.next
                }
                bucket += 1
              }
              localChecksum =
                fold(localChecksum, 149, epoch, leftCount, rightCount, leftCount.toLong + rightCount)
              slot += 1
            }

            GroupOutcome(
              localChecksum,
              localOutput,
              localRetained,
              localRetained + 2L,
              liveObjects,
              local
            )
          }
          checksum = groupOutcome.checksum
          outputCount += groupOutcome.outputCount
          retainedObjects += groupOutcome.retainedObjectProxy
          regionFreedObjects += groupOutcome.regionFreedObjectProxy
          if (groupOutcome.maxLiveObjectProxy > maxLiveObjects)
            maxLiveObjects = groupOutcome.maxLiveObjectProxy
          processed += groupOutcome.recordsRead
          group += 1
        }
      }
    } finally {
      source.close()
    }

    checksumSink = checksum
    outputSink = outputCount
    retainedSink = retainedObjects
    Outcome(
      checksum,
      outputCount,
      retainedObjects,
      regionFreedObjects,
      maxLiveObjects,
      processed,
      source.bytesRead,
      source.inputFiles
    )
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
        throw new IllegalArgumentException(
          s"unknown LogHub retained session mode '$other'"
        )
    }

  private def canonicalWorkload(workload: String): String =
    workload match {
      case "session" | "sessions" => "session"
      case "join"                 => "join"
      case other =>
        throw new IllegalArgumentException(
          s"unknown LogHub retained session workload '$other'"
        )
    }

  private def runOnce(mode: String, workload: String): Outcome =
    (canonicalMode(mode), canonicalWorkload(workload)) match {
      case ("heap-gc", "session")             => runHeapSession()
      case ("heap-gc", "join")                => runHeapJoin()
      case ("checked-rift", "session")        => runCheckedSession()
      case ("checked-rift", "join")           => runCheckedJoin()
      case ("checked-region-scoped", "session") => runCheckedScopedSession()
      case ("checked-region-scoped", "join")    => runCheckedScopedJoin()
      case other =>
        throw new IllegalArgumentException(
          s"unsupported LogHub retained session run selection $other"
        )
    }

  private def usesRift(mode: String): Boolean =
    canonicalMode(mode) != "heap-gc"

  def runBenchmark(mode: String, workload: String): Unit = {
    val cfg = LogHubRetainedSessionConfig
    val canonical = canonicalMode(mode)
    val query = canonicalWorkload(workload)
    val includeRift = usesRift(canonical)

    if (cfg.finalClean) {
      var run = 0
      var outcome = Outcome(0L, 0L, 0L, 0L, 0L, 0, 0L, cfg.inputPaths.length)
      while (run < cfg.benchmarkRuns) {
        outcome = runOnce(canonical, query)
        run += 1
      }
      println(
        s"RESULT name=loghub-retained-session-$query-$canonical " +
          s"measurement_level=L1 final_clean=1 workload=$query mode=$canonical " +
          s"input=real-loghub-streaming-file records=${cfg.records} " +
          s"records_read=${outcome.recordsRead} bytes_read=${outcome.bytesRead} " +
          s"input_files=${outcome.inputFiles} records_per_epoch=${cfg.recordsPerEpoch} " +
          s"active_epochs=${cfg.activeEpochs} key_space=${cfg.keySpace} " +
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
    var recordsRead = 0
    var bytesRead = 0L
    var inputFiles = cfg.inputPaths.length
    var run = 0

    println(
      s"Running loghub-retained-session-$query-$canonical for ${cfg.benchmarkRuns} timed runs"
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
      recordsRead = outcome.recordsRead
      bytesRead = outcome.bytesRead
      inputFiles = outcome.inputFiles
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
      if (medianMs <= 0.0) 0.0 else recordsRead.toDouble / (medianMs / 1000.0)

    println(
      f"RESULT name=loghub-retained-session-$query-$canonical " +
        f"workload=$query mode=$canonical input=real-loghub-streaming-file " +
        f"records=${cfg.records}%d records_read=$recordsRead%d bytes_read=$bytesRead%d " +
        f"input_files=$inputFiles%d records_per_epoch=${cfg.recordsPerEpoch}%d " +
        f"active_epochs=${cfg.activeEpochs}%d key_space=${cfg.keySpace}%d " +
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
    val cfg = LogHubRetainedSessionConfig
    println(
      s"CONFIG mode=${canonicalMode(mode)} workload=${canonicalWorkload(workload)} " +
        s"input_path=${cfg.inputPath} records=${cfg.records} " +
        s"records_per_epoch=${cfg.recordsPerEpoch} active_epochs=${cfg.activeEpochs} " +
        s"key_space=${cfg.keySpace} warmups=${cfg.warmupRuns} " +
        s"runs=${cfg.benchmarkRuns} final_clean=${cfg.finalClean}"
    )
  }
}

object LogHubRetainedSessionMatrix {
  def main(args: Array[String]): Unit = {
    val mode = if (args.length > 0) args(0) else "heap-gc"
    val workload = if (args.length > 1) args(1) else "session"
    LogHubRetainedSessionMatrixHelpers.printConfig(mode, workload)
    LogHubRetainedSessionMatrixHelpers.runBenchmark(mode, workload)
  }
}
